// SPDX-License-Identifier: MPL-2.0
// Original contributions Copyright 2026 Nuri Bruner.

import Foundation

/// ATT transport and deadline scheduling stay with CoreBluetooth; ordering and
/// fail-closed reply ownership are executable without a physical peripheral.
@MainActor
protocol ProgressorControlPointTransport: AnyObject {
    var canWrite: Bool { get }
    var writesWithResponse: Bool { get }
    var readyWithoutResponse: Bool { get }
    func write(_ command: ProgressorCommand, withResponse: Bool)
    func failPermanently(reason: String)
    func completeSleep()
    func armReplyDeadline(id: UInt64)
    func cancelReplyDeadline()
    func armWriteDeadline(id: UInt64)
    func cancelWriteDeadline()
    func armSleepFallback(id: UInt64)
    func diagnostic(_ event: ProgressorClientDiagnostic)
}

@MainActor
final class ProgressorControlPointQueue {
    private struct Entry {
        var id: UInt64
        var command: ProgressorCommand
        var startCause: StreamStartCause?
        var retryCount = 0
    }
    private unowned let transport: any ProgressorControlPointTransport
    private var writes: [Entry] = []
    private var inFlight: Entry?
    private var reply: Entry?
    private var nextID: UInt64 = 0
    private var tareLatch = TareIntegrityLatch()
    private var deferredStart: Entry?
    private(set) var isQueryChannelPoisoned = false
    private(set) var issuedSleepID: UInt64?

    init(transport: any ProgressorControlPointTransport) { self.transport = transport }
    var pendingReplyCommand: ProgressorCommand? { reply?.command }
    var queuedCommands: [ProgressorCommand] { writes.map(\.command) }
    var hasDeferredStart: Bool { deferredStart != nil }

    func enqueue(_ command: ProgressorCommand, startCause: StreamStartCause? = nil) {
        // Commands belong to the physical link that authorized them. In particular,
        // a disconnected tare must never survive to zero the next connection's load.
        guard transport.canWrite, command != .addCalibrationPoint else { return }
        nextID &+= 1
        let entry = Entry(id: nextID, command: command, startCause: startCause)
        if command == .stopWeightMeasurement || command == .enterSleep {
            deferredStart = nil
            writes.removeAll { $0.command == .startWeightMeasurement }
        } else if command == .startWeightMeasurement {
            writes.removeAll { $0.command == .stopWeightMeasurement }
        }
        if command == .tare {
            tareLatch.tareEnqueued(id: entry.id)
            for queued in writes where queued.command == .startWeightMeasurement {
                deferredStart = queued
                if let cause = queued.startCause { transport.diagnostic(.streamStartDeferred(cause)) }
            }
            writes.removeAll { $0.command == .startWeightMeasurement }
        } else if command == .startWeightMeasurement, tareLatch.startDecision == .deferred {
            deferredStart = entry
            if let startCause { transport.diagnostic(.streamStartDeferred(startCause)) }
            return
        }
        if command.expectsResponse, isQueryChannelPoisoned { return }
        writes.append(entry)
        drain()
    }

    func drain() {
        guard transport.canWrite else { return }
        let acknowledged = transport.writesWithResponse
        while !writes.isEmpty {
            if isQueryChannelPoisoned { writes.removeAll { $0.command.expectsResponse } }
            guard !writes.isEmpty else { return }
            let index: Int
            if reply == nil { index = 0 }
            else {
                guard let control = writes.firstIndex(where: { !$0.command.expectsResponse }) else { return }
                index = control
            }
            if writes[index].command == .tare, !acknowledged {
                transport.failPermanently(reason: String(localized: "Gauge control point cannot acknowledge tare writes"))
                return
            }
            if acknowledged {
                guard inFlight == nil else { return }
            } else if !transport.readyWithoutResponse { return }

            let entry = writes.remove(at: index)
            if entry.command.expectsResponse {
                reply = entry
                transport.armReplyDeadline(id: entry.id)
            }
            if acknowledged {
                inFlight = entry
                transport.armWriteDeadline(id: entry.id)
            }
            if entry.command == .enterSleep { issuedSleepID = entry.id }
            transport.write(entry.command, withResponse: acknowledged)
            if entry.command == .startWeightMeasurement, let cause = entry.startCause {
                transport.diagnostic(.streamStartWritten(cause))
            }
            if acknowledged { return }
            if entry.command == .enterSleep {
                transport.armSleepFallback(id: entry.id)
                return
            }
        }
    }

    func writeCompleted(error: String?) {
        guard let entry = inFlight else { return }
        inFlight = nil
        transport.cancelWriteDeadline()
        if let error {
            if reply?.id == entry.id { reply = nil; transport.cancelReplyDeadline() }
            if issuedSleepID == entry.id { issuedSleepID = nil }
            // A failed old stream intent must not delay or revive its replacement.
            let superseded = (entry.command == .startWeightMeasurement
                && writes.contains { $0.command == .stopWeightMeasurement || $0.command == .enterSleep })
                || (entry.command == .stopWeightMeasurement
                    && (deferredStart != nil || writes.contains { $0.command == .startWeightMeasurement }))
            if superseded { drain(); return }
            if entry.retryCount == 0 {
                var retry = entry
                retry.retryCount = 1
                writes.insert(retry, at: 0)
                drain()
            } else {
                transport.failPermanently(reason: String(localized:
                    "Write failed twice for \(entry.command.writeFailureName): \(error)"))
            }
            return
        }
        if entry.command == .tare, tareLatch.tareAcknowledged(id: entry.id), let deferredStart {
            self.deferredStart = nil
            writes.insert(deferredStart, at: 0)
        }
        if entry.command == .enterSleep { transport.completeSleep(); return }
        drain()
    }

    func commandReplyReceived() {
        guard reply != nil else { return }
        reply = nil
        transport.cancelReplyDeadline()
        drain()
    }

    func replyDeadlineFired(id: UInt64) {
        guard reply?.id == id else { return }
        reply = nil
        isQueryChannelPoisoned = true
        writes.removeAll { $0.command.expectsResponse }
        drain()
    }

    func writeDeadlineFired(id: UInt64) {
        guard inFlight?.id == id else { return }
        // No ACK means completion is UNKNOWN. Retrying on this link would let a late
        // untagged ACK acknowledge the retry or next command. End the ambiguous link.
        clearLinkState(clearDeferredStart: true)
        transport.failPermanently(reason: String(localized: "Gauge did not acknowledge the command. Reconnect and try again."))
    }

    func linkEstablished() { isQueryChannelPoisoned = false }

    func clearLinkState(clearDeferredStart: Bool) {
        writes.removeAll()
        inFlight = nil
        reply = nil
        transport.cancelReplyDeadline()
        transport.cancelWriteDeadline()
        issuedSleepID = nil
        if clearDeferredStart { deferredStart = nil }
        tareLatch.clearForNewLink()
    }
}

private extension ProgressorCommand {
    var writeFailureName: String {
        switch self {
        case .tare: String(localized: "tare")
        case .startWeightMeasurement: String(localized: "start measurement")
        case .stopWeightMeasurement: String(localized: "stop measurement")
        case .startPeakRFDMeasurement: String(localized: "peak RFD measurement")
        case .startPeakRFDSeries: String(localized: "RFD series measurement")
        case .addCalibrationPoint: String(localized: "calibration point")
        case .saveCalibration: String(localized: "calibration save")
        case .getAppVersion: String(localized: "version query")
        case .getErrorInformation: String(localized: "error query")
        case .clearErrorInformation: String(localized: "error clear")
        case .enterSleep: String(localized: "sleep")
        case .getBatteryVoltage: String(localized: "battery query")
        }
    }
}
