// SPDX-License-Identifier: MPL-2.0
// Original contributions Copyright 2026 Nuri Bruner.

import CoreBluetooth
import Foundation

/// The real gauge, over CoreBluetooth.
///
/// **Isolation.** The class is `@MainActor` and the central is created with
/// `queue: .main`, so every delegate callback genuinely arrives on the main thread.
/// The delegate methods below are therefore plain main-actor-isolated members: the
/// CoreBluetooth delegate protocols are `@objc`, so Swift 6 witnesses them with
/// isolated methods and inserts its own runtime isolation check (SE-0423) — the
/// same accommodation that lets a `@MainActor` class be a `UITableViewDataSource`.
///
/// The `queue: .main` argument is what makes that safe, so it is load-bearing: hand
/// CoreBluetooth a background queue and every callback below becomes a race. Note
/// that writing these as `nonisolated` + `MainActor.assumeIsolated` does NOT
/// compile — passing a non-Sendable `CBPeripheral` into the closure is "sending"
/// across an isolation boundary, which is exactly what the checker rejects.
@MainActor
final class LiveProgressorClient: NSObject, ProgressorClient {
    var onEvent: ((ProgressorEvent) -> Void)?
    var onPacketBoundary: ((PacketBoundary) -> Void)?
    var onStateChange: ((ProgressorConnectionState) -> Void)?
    var onDiagnostic: ((ProgressorClientDiagnostic) -> Void)?

    private(set) var state: ProgressorConnectionState = .idle {
        didSet { if state != oldValue { onStateChange?(state) } }
    }
    private(set) var deviceName: String?

    private static let serviceUUID = CBUUID(string: ProgressorGATT.serviceUUID)
    private static let dataUUID = CBUUID(string: ProgressorGATT.dataCharacteristicUUID)
    private static let controlUUID = CBUUID(string: ProgressorGATT.controlPointCharacteristicUUID)
    private static let attemptLimit = 5

    private struct WriteEntry {
        let id: UInt64
        let command: ProgressorCommand
        let startCause: StreamStartCause?
        var retryCount = 0
    }

    private struct PendingReply {
        let id: UInt64
        let command: ProgressorCommand
    }

    private var central: CBCentralManager?
    private var peripheral: CBPeripheral?
    private var controlPoint: CBCharacteristic?
    private var dataCharacteristic: CBCharacteristic?

    /// A deliberately cancelled peripheral stays quarantined until CoreBluetooth
    /// delivers its terminal callback. Reusing it earlier lets callbacks from the old
    /// generation satisfy the new connection attempt.
    private var retiringPeripheral: CBPeripheral?
    private var expectedDisconnect = false
    private var pendingConnectionStart = false

    private var generation: UInt64 = 0
    private var activeGeneration: UInt64?
    private var scanGeneration: UInt64?

    private var attemptsRemaining = 0
    /// Set only by an accepted explicit `connect()` call, and retained across radio
    /// power loss so powering Bluetooth back on resumes the same user intent.
    private var wantsConnection = false
    private var refreshBudgetWhenPoweredOn = false

    private var scanDeadlineTask: Task<Void, Never>?
    private var connectDeadlineTask: Task<Void, Never>?
    private var backoffTask: Task<Void, Never>?
    private var replyDeadlineTask: Task<Void, Never>?
    private var sleepFallbackTask: Task<Void, Never>?

    /// Queries are serialized because tag-0 replies carry no command echo. Once one
    /// reply times out, the channel is unusable for this physical connection: a late
    /// reply could otherwise be paired with every later query one slot off.
    private var pendingReplies: [PendingReply] = []
    private var queryChannelPoisoned = false

    private var writeQueue: [WriteEntry] = []
    private var inFlightWrite: WriteEntry?
    private var nextWriteID: UInt64 = 0

    private var tareIntegrityLatch = TareIntegrityLatch()
    private var deferredStart: WriteEntry?

    private var sleepRequested = false
    private var issuedSleepID: UInt64?

    deinit {
        scanDeadlineTask?.cancel()
        connectDeadlineTask?.cancel()
        backoffTask?.cancel()
        replyDeadlineTask?.cancel()
        sleepFallbackTask?.cancel()
    }

    // MARK: - ProgressorClient

    func connect() {
        // `wantsConnection` covers the backoff and radio-off gaps, when the public
        // state is not busy but the original user intent is still active. A duplicate
        // tap must not replenish its retry budget.
        guard !wantsConnection, !state.isBusy, !state.isConnected else { return }

        wantsConnection = true
        attemptsRemaining = Self.attemptLimit
        refreshBudgetWhenPoweredOn = false

        // LAZY on purpose: constructing the central is what triggers the system
        // Bluetooth permission dialog. Doing it at launch would ask before the user
        // has seen what the app is for; doing it here ties the ask to a Connect tap.
        guard let central else {
            central = CBCentralManager(delegate: self, queue: .main)
            return   // continues in centralManagerDidUpdateState
        }
        guard central.state == .poweredOn else {
            state = Self.state(for: central.state)
            return
        }
        beginAttemptIfPossible()
    }

    func disconnect() {
        wantsConnection = false
        refreshBudgetWhenPoweredOn = false
        pendingConnectionStart = false
        attemptsRemaining = 0
        generation &+= 1

        central?.stopScan()
        cancelAllTasks()
        scanGeneration = nil
        clearLinkState(clearDeferredStart: true)
        sleepRequested = false
        issuedSleepID = nil

        if let peripheral {
            retire(peripheral)
        }
        self.peripheral = nil
        activeGeneration = nil
        deviceName = nil
        state = .disconnected(reason: nil)
    }

    func sleepDevice() {
        // Sleep is a terminal user intent. Clear reconnect intent before the command
        // enters the queue so a peripheral-initiated shutdown cannot start a rescan.
        wantsConnection = false
        refreshBudgetWhenPoweredOn = false
        pendingConnectionStart = false
        backoffTask?.cancel()
        backoffTask = nil

        guard state.isConnected, !sleepRequested else {
            if !state.isConnected { disconnect() }
            return
        }
        sleepRequested = true
        enqueue(.enterSleep)
    }

    /// Commands are queued and paced, never written straight through. Each entry keeps
    /// its identity across its single retry so a failed query can remove exactly its
    /// own pending-reply slot without shifting the FIFO.
    func send(_ command: ProgressorCommand) {
        // Start writes carry a cause through the one public start funnel. Silently
        // accepting an uncaused start here would make its later hardware breadcrumb a
        // guess, so callers use `startStreaming(cause:)` instead.
        guard command != .startWeightMeasurement else { return }
        enqueue(command)
    }

    func startStreaming(cause: StreamStartCause) {
        enqueue(.startWeightMeasurement, startCause: cause)
    }

    private func enqueue(_ command: ProgressorCommand, startCause: StreamStartCause? = nil) {
        nextWriteID &+= 1
        let entry = WriteEntry(id: nextWriteID, command: command, startCause: startCause)

        if command == .tare {
            // Flip the latch before touching the queue. Any start already waiting to
            // drain is pulled into the one deferred slot before this tare is appended.
            tareIntegrityLatch.tareEnqueued(id: entry.id)
            for queued in writeQueue where queued.command == .startWeightMeasurement {
                deferredStart = queued
                if let cause = queued.startCause {
                    onDiagnostic?(.streamStartDeferred(cause))
                }
            }
            writeQueue.removeAll { $0.command == .startWeightMeasurement }
        } else if command == .startWeightMeasurement,
                  tareIntegrityLatch.startDecision == .deferred {
            deferredStart = entry   // latest wins
            if let startCause {
                onDiagnostic?(.streamStartDeferred(startCause))
            }
            return
        }

        if command.expectsResponse, queryChannelPoisoned { return }
        writeQueue.append(entry)
        drainWriteQueue()
    }

    private func drainWriteQueue() {
        guard state.isConnected,
              let peripheral,
              let controlPoint,
              isCurrent(peripheral) else { return }

        let type: CBCharacteristicWriteType =
            controlPoint.properties.contains(.write) ? .withResponse : .withoutResponse

        while !writeQueue.isEmpty {
            if queryChannelPoisoned {
                writeQueue.removeAll { $0.command.expectsResponse }
                guard !writeQueue.isEmpty else { return }
            }

            // A waiting query must not hold safety/control commands behind it. Keep
            // non-query order intact while bypassing only the serialized query entries.
            let candidateIndex: Int
            if pendingReplies.isEmpty {
                candidateIndex = 0
            } else {
                guard let firstControl = writeQueue.firstIndex(where: {
                    !$0.command.expectsResponse
                }) else { return }
                candidateIndex = firstControl
            }
            let candidate = writeQueue[candidateIndex]

            if candidate.command == .tare, type != .withResponse {
                failPermanently(reason: String(localized: "Gauge control point cannot acknowledge tare writes"))
                return
            }

            if type == .withResponse {
                guard inFlightWrite == nil else { return }
            } else {
                guard peripheral.canSendWriteWithoutResponse else { return }
            }

            let entry = writeQueue.remove(at: candidateIndex)
            if entry.command.expectsResponse {
                pendingReplies.append(PendingReply(id: entry.id, command: entry.command))
                startReplyDeadline(for: entry.id)
            }
            if type == .withResponse { inFlightWrite = entry }
            if entry.command == .enterSleep { issuedSleepID = entry.id }

            peripheral.writeValue(entry.command.encoded, for: controlPoint, type: type)
            if entry.command == .startWeightMeasurement, let cause = entry.startCause {
                onDiagnostic?(.streamStartWritten(cause))
            }

            if type == .withResponse { return }
            if entry.command == .enterSleep {
                startSleepFallback(for: entry.id)
                return
            }
        }
    }

    // MARK: - Connection flow

    private func beginAttemptIfPossible() {
        guard wantsConnection,
              let central,
              central.state == .poweredOn,
              !state.isConnected,
              !state.isBusy else { return }

        guard retiringPeripheral == nil else {
            pendingConnectionStart = true
            return
        }
        guard attemptsRemaining > 0 else {
            wantsConnection = false
            state = .disconnected(reason: String(localized: "Connection attempts exhausted"))
            return
        }

        pendingConnectionStart = false
        backoffTask?.cancel()
        backoffTask = nil
        attemptsRemaining -= 1
        generation &+= 1
        scanGeneration = generation

        // BLE links are owned by the system daemon, not by this process, so after a
        // relaunch the device may already be connected — reattaching is instant and
        // skips the scan entirely.
        if let known = central.retrieveConnectedPeripherals(withServices: [Self.serviceUUID]).first {
            attach(known, via: central, generation: generation)
            return
        }

        state = .scanning
        central.scanForPeripherals(withServices: [Self.serviceUUID])
        startScanDeadline(generation: generation)
    }

    private func attach(_ found: CBPeripheral, via central: CBCentralManager,
                        generation: UInt64) {
        guard wantsConnection,
              central.state == .poweredOn,
              self.generation == generation,
              retiringPeripheral == nil else { return }

        central.stopScan()
        scanDeadlineTask?.cancel()
        scanDeadlineTask = nil
        scanGeneration = nil
        peripheral = found
        activeGeneration = generation
        deviceName = found.name
        found.delegate = self
        state = .connecting
        central.connect(found)
        startConnectDeadline(for: found, generation: generation)
    }

    private func startScanDeadline(generation: UInt64) {
        scanDeadlineTask?.cancel()
        scanDeadlineTask = Task { [weak self] in
            do {
                try await Task.sleep(for: .seconds(15))
            } catch {
                return
            }
            guard !Task.isCancelled,
                  let self,
                  self.generation == generation,
                  self.scanGeneration == generation,
                  self.state == .scanning else { return }
            self.central?.stopScan()
            self.failAttempt(reason: String(localized: "No gauge found"), terminalPeripheral: nil)
        }
    }

    private func startConnectDeadline(for found: CBPeripheral, generation: UInt64) {
        let identity = ObjectIdentifier(found)
        connectDeadlineTask?.cancel()
        connectDeadlineTask = Task { [weak self] in
            do {
                try await Task.sleep(for: .seconds(8))
            } catch {
                return
            }
            guard !Task.isCancelled,
                  let self,
                  self.generation == generation,
                  self.activeGeneration == generation,
                  let active = self.peripheral,
                  ObjectIdentifier(active) == identity else { return }
            self.failAttempt(reason: String(localized: "Connection timed out"), cancelling: active)
        }
    }

    private func scheduleRetry() {
        guard wantsConnection, attemptsRemaining > 0 else {
            wantsConnection = false
            return
        }
        let retryGeneration = generation
        backoffTask?.cancel()
        backoffTask = Task { [weak self] in
            do {
                try await Task.sleep(for: .seconds(1))
            } catch {
                return
            }
            guard !Task.isCancelled,
                  let self,
                  self.generation == retryGeneration,
                  self.wantsConnection,
                  self.central?.state == .poweredOn else { return }
            self.beginAttemptIfPossible()
        }
    }

    /// The callback in hand is already terminal, so the peripheral may be released
    /// immediately rather than entering the deliberate-cancellation quarantine.
    private func failAttempt(reason: String, terminalPeripheral: CBPeripheral?) {
        if let terminalPeripheral {
            guard isCurrent(terminalPeripheral) else { return }
        }
        invalidateAttempt()
        state = .disconnected(reason: reason)

        guard wantsConnection, attemptsRemaining > 0 else {
            wantsConnection = false
            return
        }
        scheduleRetry()
    }

    private func failAttempt(reason: String, cancelling failedPeripheral: CBPeripheral) {
        guard isCurrent(failedPeripheral) else { return }
        invalidateAttempt()
        retire(failedPeripheral)
        state = .disconnected(reason: reason)

        guard wantsConnection, attemptsRemaining > 0 else {
            wantsConnection = false
            return
        }
        scheduleRetry()
    }

    private func failPermanently(reason: String) {
        wantsConnection = false
        refreshBudgetWhenPoweredOn = false
        pendingConnectionStart = false
        attemptsRemaining = 0
        let failedPeripheral = peripheral
        invalidateAttempt()
        if let failedPeripheral { retire(failedPeripheral) }
        state = .disconnected(reason: reason)
    }

    private func invalidateAttempt() {
        generation &+= 1
        central?.stopScan()
        scanDeadlineTask?.cancel()
        scanDeadlineTask = nil
        connectDeadlineTask?.cancel()
        connectDeadlineTask = nil
        backoffTask?.cancel()
        backoffTask = nil
        scanGeneration = nil
        activeGeneration = nil
        peripheral = nil
        deviceName = nil
        clearLinkState(clearDeferredStart: true)
    }

    private func retire(_ retiring: CBPeripheral) {
        // There can only be one gauge. If an earlier deliberate cancellation is still
        // awaiting its terminal callback, never replace its quarantine with another.
        guard retiringPeripheral == nil else { return }
        onDiagnostic?(.retiringPeripheral)
        retiringPeripheral = retiring
        expectedDisconnect = true
        central?.cancelPeripheralConnection(retiring)
    }

    private func finishExpectedDisconnect(_ finished: CBPeripheral) -> Bool {
        guard expectedDisconnect,
              let retiringPeripheral,
              retiringPeripheral === finished else { return false }
        retiringPeripheral.delegate = nil
        self.retiringPeripheral = nil
        expectedDisconnect = false
        onDiagnostic?(.quarantineReleased)

        if pendingConnectionStart {
            pendingConnectionStart = false
            beginAttemptIfPossible()
        }
        return true
    }

    private func handlePowerUnavailable(_ central: CBCentralManager) {
        refreshBudgetWhenPoweredOn = wantsConnection
        attemptsRemaining = 0
        pendingConnectionStart = false
        let active = peripheral
        invalidateAttempt()
        replyDeadlineTask?.cancel()
        replyDeadlineTask = nil
        sleepFallbackTask?.cancel()
        sleepFallbackTask = nil
        if let active { retire(active) }
        state = Self.state(for: central.state)
    }

    private func isCurrent(_ candidate: CBPeripheral) -> Bool {
        guard let peripheral, peripheral === candidate else { return false }
        return activeGeneration == generation
    }

    private func clearLinkState(clearDeferredStart: Bool) {
        controlPoint = nil
        dataCharacteristic = nil
        pendingReplies.removeAll()
        writeQueue.removeAll()
        inFlightWrite = nil
        replyDeadlineTask?.cancel()
        replyDeadlineTask = nil
        sleepFallbackTask?.cancel()
        sleepFallbackTask = nil
        issuedSleepID = nil
        sleepRequested = false
        if clearDeferredStart { deferredStart = nil }
        // This latch is link-local: preserving it after the queue and its ACK died made
        // every later start impossible, because only the retired link could release it.
        tareIntegrityLatch.clearForNewLink()
    }

    private func cancelAllTasks() {
        scanDeadlineTask?.cancel()
        scanDeadlineTask = nil
        connectDeadlineTask?.cancel()
        connectDeadlineTask = nil
        backoffTask?.cancel()
        backoffTask = nil
        replyDeadlineTask?.cancel()
        replyDeadlineTask = nil
        sleepFallbackTask?.cancel()
        sleepFallbackTask = nil
    }

    private func startReplyDeadline(for id: UInt64) {
        let replyGeneration = generation
        replyDeadlineTask?.cancel()
        replyDeadlineTask = Task { [weak self] in
            do {
                try await Task.sleep(for: .seconds(2))
            } catch {
                return
            }
            guard !Task.isCancelled,
                  let self,
                  self.generation == replyGeneration,
                  self.pendingReplies.first?.id == id else { return }
            self.pendingReplies.removeFirst()
            self.queryChannelPoisoned = true
            self.writeQueue.removeAll { $0.command.expectsResponse }
            self.replyDeadlineTask = nil
            self.drainWriteQueue()
        }
    }

    private func startSleepFallback(for id: UInt64) {
        let sleepGeneration = generation
        sleepFallbackTask?.cancel()
        sleepFallbackTask = Task { [weak self] in
            do {
                try await Task.sleep(for: .seconds(1))
            } catch {
                return
            }
            guard !Task.isCancelled,
                  let self,
                  self.generation == sleepGeneration,
                  self.issuedSleepID == id else { return }
            self.completeSleep()
        }
    }

    private func completeSleep() {
        guard let sleepingPeripheral = peripheral else { return }
        sleepFallbackTask?.cancel()
        sleepFallbackTask = nil
        sleepRequested = false
        issuedSleepID = nil
        generation &+= 1
        activeGeneration = nil
        peripheral = nil
        deviceName = nil
        clearLinkState(clearDeferredStart: true)
        retire(sleepingPeripheral)
        state = .disconnected(reason: String(localized: "Device asleep"))
    }

    private static func state(for cb: CBManagerState) -> ProgressorConnectionState {
        switch cb {
        case .poweredOn: .idle
        case .poweredOff: .bluetoothOff
        case .unauthorized: .unauthorized
        case .unsupported: .unsupported
        default: .idle
        }
    }
}

// MARK: - CBCentralManagerDelegate

// `@preconcurrency` is what lets main-actor-isolated methods witness CoreBluetooth's
// nonisolated `@objc` requirements: the compiler inserts a dynamic isolation check
// at each witness instead of rejecting the conformance outright (SE-0423). It is
// sound here precisely because the central is constructed with `queue: .main`, so
// the check can never fail — and would trap loudly if that ever changed.
extension LiveProgressorClient: @preconcurrency CBCentralManagerDelegate {
    func centralManagerDidUpdateState(_ central: CBCentralManager) {
        guard central.state == .poweredOn else {
            handlePowerUnavailable(central)
            return
        }

        if refreshBudgetWhenPoweredOn {
            attemptsRemaining = Self.attemptLimit
            refreshBudgetWhenPoweredOn = false
        }
        if wantsConnection { beginAttemptIfPossible() }
    }

    func centralManager(_ central: CBCentralManager,
                        didDiscover peripheral: CBPeripheral,
                        advertisementData: [String: Any],
                        rssi RSSI: NSNumber) {
        // Scanning is already filtered to the Progressor service, so the first hit is
        // the device. (Only one gauge is ever in play.)
        guard central.state == .poweredOn,
              wantsConnection,
              state == .scanning,
              self.peripheral == nil,
              retiringPeripheral == nil,
              scanGeneration == generation else { return }
        attach(peripheral, via: central, generation: generation)
    }

    func centralManager(_ central: CBCentralManager, didConnect peripheral: CBPeripheral) {
        guard central.state == .poweredOn, isCurrent(peripheral) else { return }
        deviceName = peripheral.name
        peripheral.discoverServices([Self.serviceUUID])
    }

    func centralManager(_ central: CBCentralManager,
                        didFailToConnect peripheral: CBPeripheral,
                        error: Error?) {
        if finishExpectedDisconnect(peripheral) { return }
        guard central.state == .poweredOn, isCurrent(peripheral) else { return }
        let reason = error?.localizedDescription ?? String(localized: "Couldn't connect")
        failAttempt(reason: reason, terminalPeripheral: peripheral)
    }

    func centralManager(_ central: CBCentralManager,
                        didDisconnectPeripheral peripheral: CBPeripheral,
                        error: Error?) {
        if finishExpectedDisconnect(peripheral) { return }
        guard isCurrent(peripheral) else { return }

        let wasEstablished = state.isConnected
        let sleepCompleted = issuedSleepID != nil
        invalidateAttempt()

        // Radio state is authoritative. Its callback already published the off or
        // unauthorized state, and a late disconnect must not overwrite or rescan it.
        guard central.state == .poweredOn else { return }

        if sleepCompleted {
            wantsConnection = false
            state = .disconnected(reason: String(localized: "Device asleep"))
            return
        }

        state = .disconnected(reason: error?.localizedDescription)
        guard wantsConnection else { return }

        if wasEstablished {
            // A successful notification subscription reset the budget, so a dropped
            // established link begins a fresh five-attempt cycle immediately.
            attemptsRemaining = Self.attemptLimit
            beginAttemptIfPossible()
        } else if attemptsRemaining > 0 {
            scheduleRetry()
        } else {
            wantsConnection = false
        }
    }
}

// MARK: - CBPeripheralDelegate

extension LiveProgressorClient: @preconcurrency CBPeripheralDelegate {
    /// A `.withResponse` write completed — the exact entry is either acknowledged or
    /// reinserted at the front once, preserving command order across the retry.
    func peripheral(_ peripheral: CBPeripheral, didWriteValueFor characteristic: CBCharacteristic,
                    error: Error?) {
        guard isCurrent(peripheral),
              characteristic.uuid == Self.controlUUID,
              let entry = inFlightWrite else { return }
        inFlightWrite = nil

        if let error {
            if let replyIndex = pendingReplies.firstIndex(where: { $0.id == entry.id }) {
                pendingReplies.remove(at: replyIndex)
                replyDeadlineTask?.cancel()
                replyDeadlineTask = nil
            }
            if issuedSleepID == entry.id { issuedSleepID = nil }

            if entry.retryCount == 0 {
                var retry = entry
                retry.retryCount = 1
                writeQueue.insert(retry, at: 0)
                drainWriteQueue()
            } else {
                failPermanently(
                    reason: String(localized: "Write failed twice for \(entry.command.writeFailureName): \(error.localizedDescription)")
                )
            }
            return
        }

        if entry.command == .tare, tareIntegrityLatch.tareAcknowledged(id: entry.id) {
            if let deferredStart {
                self.deferredStart = nil
                writeQueue.insert(deferredStart, at: 0)
            }
        }

        if entry.command == .enterSleep {
            completeSleep()
            return
        }
        drainWriteQueue()
    }

    /// The buffer drained — queued `.withoutResponse` writes may go.
    func peripheralIsReady(toSendWriteWithoutResponse peripheral: CBPeripheral) {
        guard isCurrent(peripheral) else { return }
        drainWriteQueue()
    }

    func peripheral(_ peripheral: CBPeripheral, didDiscoverServices error: Error?) {
        guard isCurrent(peripheral) else { return }
        if let error {
            failAttempt(reason: String(localized: "Service discovery failed: \(error.localizedDescription)"),
                        cancelling: peripheral)
            return
        }
        guard let service = peripheral.services?.first(where: { $0.uuid == Self.serviceUUID }) else {
            failAttempt(reason: String(localized: "Not a Progressor"), cancelling: peripheral)
            return
        }
        peripheral.discoverCharacteristics([Self.dataUUID, Self.controlUUID], for: service)
    }

    func peripheral(_ peripheral: CBPeripheral,
                    didDiscoverCharacteristicsFor service: CBService,
                    error: Error?) {
        guard isCurrent(peripheral) else { return }
        if let error {
            failAttempt(reason: String(localized: "Characteristic discovery failed: \(error.localizedDescription)"),
                        cancelling: peripheral)
            return
        }

        let characteristics = service.characteristics ?? []
        guard let data = characteristics.first(where: { $0.uuid == Self.dataUUID }) else {
            failAttempt(reason: String(localized: "Missing data characteristic"), cancelling: peripheral)
            return
        }
        guard let control = characteristics.first(where: { $0.uuid == Self.controlUUID }) else {
            failAttempt(reason: String(localized: "Missing control point"), cancelling: peripheral)
            return
        }
        guard control.properties.contains(.write)
                || control.properties.contains(.writeWithoutResponse) else {
            failAttempt(reason: String(localized: "Control point is not writable"), cancelling: peripheral)
            return
        }

        dataCharacteristic = data
        controlPoint = control
        peripheral.setNotifyValue(true, for: data)
    }

    func peripheral(_ peripheral: CBPeripheral,
                    didUpdateNotificationStateFor characteristic: CBCharacteristic,
                    error: Error?) {
        guard isCurrent(peripheral),
              characteristic.uuid == Self.dataUUID,
              dataCharacteristic === characteristic else { return }

        if let error {
            failAttempt(reason: String(localized: "Notification subscription failed: \(error.localizedDescription)"),
                        cancelling: peripheral)
            return
        }
        guard characteristic.isNotifying, controlPoint != nil else {
            failAttempt(reason: String(localized: "Notification subscription failed"), cancelling: peripheral)
            return
        }

        connectDeadlineTask?.cancel()
        connectDeadlineTask = nil
        attemptsRemaining = Self.attemptLimit
        queryChannelPoisoned = false
        state = .connected
        send(.getAppVersion)
        send(.getBatteryVoltage)
        drainWriteQueue()
    }

    func peripheral(_ peripheral: CBPeripheral,
                    didUpdateValueFor characteristic: CBCharacteristic,
                    error: Error?) {
        guard isCurrent(peripheral),
              characteristic.uuid == Self.dataUUID,
              error == nil,
              let data = characteristic.value else { return }

        onPacketBoundary?(.began(receivedAt: ProcessInfo.processInfo.systemUptime))
        defer { onPacketBoundary?(.ended) }
        let events = ProgressorCodec.decode(data, answering: pendingReplies.first?.command)
        // A tag-0 reply consumes the one pending query; weight notifications do not.
        if events.contains(where: \.isCommandReply), !pendingReplies.isEmpty {
            pendingReplies.removeFirst()
            replyDeadlineTask?.cancel()
            replyDeadlineTask = nil
            drainWriteQueue()
        }
        for event in events { onEvent?(event) }
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

private extension ProgressorEvent {
    var isCommandReply: Bool {
        switch self {
        case .battery, .appVersion, .errorInformation, .commandResponse: true
        default: false
        }
    }
}
