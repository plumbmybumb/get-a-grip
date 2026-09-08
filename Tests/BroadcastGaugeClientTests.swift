// SPDX-License-Identifier: MPL-2.0
// Original contributions Copyright 2026 Nuri Bruner.

import XCTest
@testable import Doigt

/// These drive the production client, with only the radio and passage of time
/// replaced. No CoreBluetooth singleton or real sleep is needed in the Simulator.
@MainActor
final class BroadcastGaugeClientTests: XCTestCase {
    func testCreatingClientDoesNotActivateBluetooth() {
        let h = Harness()
        XCTAssertEqual(h.transport.activations, 0)
        XCTAssertEqual(h.transport.starts, 0)
        XCTAssertEqual(h.client.state, .idle)
        h.client.connect()
        XCTAssertEqual(h.transport.activations, 1)
        XCTAssertEqual(h.transport.starts, 1)
        XCTAssertEqual(h.client.state, .scanning)
    }

    func testConnectIsIdempotentWhileSearchingAndConnected() {
        let h = Harness()
        h.client.connect()
        h.client.connect()
        h.emit(kg: 6)
        h.client.connect()
        XCTAssertEqual(h.transport.starts, 1)
        XCTAssertEqual(h.transport.activations, 1)
        XCTAssertTrue(h.client.state.isConnected)
    }

    func testInitialSearchHasOneFifteenSecondDeadlineAndCanBeRetried() {
        let h = Harness()
        h.client.connect()
        h.clock.advance(by: 14)
        XCTAssertEqual(h.client.state, .scanning)
        h.clock.advance(by: 1)
        XCTAssertEqual(h.client.state, .disconnected(reason: String(localized: "No scale found")))
        XCTAssertEqual(h.transport.stops, 1)
        h.clock.advance(by: 120)
        h.client.startStreaming(cause: .watchdog)
        XCTAssertEqual(h.transport.starts, 1)
        h.client.connect()
        h.emit(kg: 4)
        XCTAssertEqual(h.transport.starts, 2)
        XCTAssertEqual(h.samples.map(\.kg), [4])
    }

    func testInvalidFramesNeitherConnectNorExtendTheInitialSearch() {
        let h = Harness()
        h.client.connect()
        var otherCompany = Harness.advertisement(kg: 6)
        otherCompany.manufacturerData[0] = 0xff
        h.transport.emit(otherCompany)
        var truncated = Harness.advertisement(kg: 6)
        truncated.manufacturerData = Data([0, 1, 0])
        h.transport.emit(truncated)
        h.transport.emit(Harness.advertisement(kg: 301))
        h.clock.advance(by: 15)
        XCTAssertTrue(h.samples.isEmpty)
        XCTAssertFalse(h.client.state.isBusy)
        XCTAssertFalse(h.client.state.isConnected)
    }

    func testZeroIsARealReadingWithOneSyntheticSampleAndOnePacketBoundary() {
        let h = Harness()
        h.client.connect()
        h.emit(kg: 0)
        XCTAssertEqual(h.samples.count, 1)
        XCTAssertEqual(h.samples.first?.kg, 0)
        XCTAssertEqual(h.samples.first?.deviceMicros, SyntheticSampleClock.micros(uptime: h.clock.now))
        XCTAssertEqual(h.samples.first?.isBatchStart, true)
        XCTAssertEqual(h.packetBegins, [h.clock.now])
        XCTAssertEqual(h.packetEnds, 1)
        XCTAssertEqual(h.client.deviceName, "Test scale")
        XCTAssertTrue(h.client.state.isConnected)
    }

    func testAFirstFrameNearDeadlineGetsAFullSilenceWindow() {
        let h = Harness()
        h.client.connect()
        h.clock.advance(by: 14)
        h.emit(kg: 3)
        h.clock.advance(by: 9)
        XCTAssertTrue(h.client.state.isConnected)
        XCTAssertEqual(h.transport.starts, 1)
        h.clock.advance(by: 1)
        XCTAssertEqual(h.client.state, .scanning)
        XCTAssertEqual(h.transport.starts, 2)
    }

    func testHealthyTwelveMinuteStreamNeverRenewsOrBouncesItsScan() {
        let h = Harness()
        h.client.connect()
        h.emit(kg: 0)
        for second in 1...720 {
            h.clock.advance(by: 1)
            h.emit(kg: Double(second % 10))
            h.client.startStreaming(cause: .watchdog)
        }
        XCTAssertEqual(h.transport.starts, 1)
        XCTAssertEqual(h.transport.stops, 0)
        XCTAssertEqual(h.samples.count, 721)
        XCTAssertTrue(h.client.state.isConnected)
    }

    func testAcceptingPacketsDoesNotAllocateATimerPerPacket() {
        let h = Harness()
        h.client.connect()
        h.emit(kg: 1)
        let scheduled = h.clock.jobs.count
        for _ in 0..<1000 { h.emit(kg: 1) }
        XCTAssertEqual(h.clock.jobs.count, scheduled)
        XCTAssertEqual(h.clock.pendingCount, 1, "Only the link silence deadline remains")
    }

    func testTareAndRepeatedStartsPreserveTheScanAndSampleTimeline() {
        let h = Harness()
        h.client.connect()
        h.emit(kg: 6)
        h.clock.advance(by: 1)
        h.client.send(.tare)
        for cause in StreamStartCause.allCases { h.client.startStreaming(cause: cause) }
        h.emit(kg: 6)
        h.clock.advance(by: 1)
        h.emit(kg: 8)
        h.client.send(.tare)
        h.emit(kg: 8)
        XCTAssertEqual(h.samples.map(\.kg), [6, 0, 2, 0])
        XCTAssertEqual(h.samples.map(\.deviceMicros), [1_000_000_000, 1_001_000_000, 1_002_000_000, 1_002_000_000])
        XCTAssertEqual(h.transport.starts, 1)
        XCTAssertEqual(h.transport.stops, 0)
    }

    func testStoppingMeasurementDoesNotDisconnectTheAdvertisementLink() {
        let h = Harness()
        h.client.connect()
        h.emit(kg: 2)
        h.client.send(.stopWeightMeasurement)
        h.emit(kg: 3)
        h.client.startStreaming(cause: .manualMeasurement)
        XCTAssertTrue(h.client.state.isConnected)
        XCTAssertEqual(h.samples.map(\.kg), [2, 3], "DeviceStore governs whether these become recorded data")
        XCTAssertEqual(h.transport.stops, 0)
        XCTAssertEqual(h.transport.starts, 1)
    }

    func testTenSecondsOfSilenceReallyStopsAndReplacesTheScanWithoutInventingSamples() {
        let h = Harness()
        h.client.connect()
        h.emit(kg: 6)
        h.clock.advance(by: 9)
        XCTAssertTrue(h.client.state.isConnected)
        h.clock.advance(by: 1)
        XCTAssertEqual(h.transport.stops, 1)
        XCTAssertEqual(h.transport.starts, 2)
        XCTAssertEqual(h.client.state, .scanning)
        XCTAssertEqual(h.samples.count, 1)
        XCTAssertTrue(h.states.contains(.disconnected(reason: String(localized: "Scale stopped broadcasting"))))
        h.emit(kg: 7)
        XCTAssertEqual(h.samples.map(\.kg), [6, 7])
        XCTAssertTrue(h.client.state.isConnected)
    }

    func testRecoveryUsesThreeAcquisitionsWithTwoThenFiveSecondBackoff() {
        let h = Harness()
        h.client.connect()
        h.emit(kg: 6)
        h.clock.advance(by: 10)
        XCTAssertEqual(h.transport.starts, 2)
        h.clock.advance(by: 15)
        XCTAssertEqual(h.transport.stops, 2)
        h.clock.advance(by: 1)
        XCTAssertEqual(h.transport.starts, 2)
        h.clock.advance(by: 1)
        XCTAssertEqual(h.transport.starts, 3)
        h.clock.advance(by: 15)
        h.clock.advance(by: 4)
        XCTAssertEqual(h.transport.starts, 3)
        h.clock.advance(by: 1)
        XCTAssertEqual(h.transport.starts, 4)
        h.clock.advance(by: 15)
        XCTAssertEqual(h.client.state, .disconnected(reason: String(localized: "No scale found")))
        XCTAssertEqual(h.transport.stops, 4)
        XCTAssertEqual(h.clock.pendingCount, 0)
        XCTAssertEqual(h.samples.count, 1)
        h.clock.advance(by: 600)
        h.client.startStreaming(cause: .manualWake)
        XCTAssertEqual(h.transport.starts, 4)
        h.client.connect()
        h.emit(kg: 5)
        XCTAssertEqual(h.transport.starts, 5)
        XCTAssertTrue(h.client.state.isConnected)
    }

    func testEachLaterSignalLossGetsItsOwnFullRecoveryBudget() {
        let h = Harness()
        h.client.connect()
        h.emit(kg: 1)
        h.clock.advance(by: 27) // Recovery attempt two.
        h.emit(kg: 2)
        XCTAssertEqual(h.transport.starts, 3)
        h.clock.advance(by: 10)
        h.clock.advance(by: 52) // 15 + 2 + 15 + 5 + 15.
        XCTAssertEqual(h.transport.starts, 6)
        XCTAssertFalse(h.client.state.isBusy)
        XCTAssertFalse(h.client.state.isConnected)
        XCTAssertEqual(h.samples.map(\.kg), [1, 2])
    }

    func testForeignAdvertiserCannotRefreshTheLockedScalesSilenceDeadline() {
        let h = Harness()
        h.client.connect()
        h.emit(kg: 6)
        h.client.send(.tare)
        h.clock.advance(by: 9)
        h.transport.emit(Harness.advertisement(kg: 8, id: Harness.otherScaleID))
        h.clock.advance(by: 1)
        XCTAssertEqual(h.transport.starts, 2)
        XCTAssertEqual(h.samples.count, 1)
        h.transport.emit(Harness.advertisement(kg: 8, id: Harness.otherScaleID))
        XCTAssertEqual(h.samples.last?.kg, 8, "A new logical link does not inherit the old tare")
    }

    func testCancelInitialSearchIgnoresItsRetainedCallbackAndCancelledDeadline() {
        let h = Harness()
        h.client.connect()
        let oldDeadline = h.clock.jobs[0]
        h.client.disconnect()
        h.transport.emit(Harness.advertisement(kg: 5), registration: 0)
        oldDeadline.fireEvenIfCancelled()
        h.clock.advance(by: 100)
        XCTAssertEqual(h.client.state, .disconnected(reason: nil))
        XCTAssertEqual(h.transport.starts, 1)
        XCTAssertEqual(h.transport.stops, 1)
        XCTAssertTrue(h.samples.isEmpty)
    }

    func testCancelDuringRecoveryBackoffCannotBeUndoneByOldJobsOrRadioReturn() {
        let h = Harness()
        h.client.connect()
        h.emit(kg: 3)
        h.clock.advance(by: 25)
        let oldRetry = h.clock.jobs.last!
        h.client.disconnect()
        oldRetry.fireEvenIfCancelled()
        h.transport.emit(Harness.advertisement(kg: 7), registration: 1)
        h.transport.changeRadio(to: .poweredOff)
        h.transport.changeRadio(to: .poweredOn)
        h.clock.advance(by: 200)
        XCTAssertEqual(h.transport.starts, 2)
        XCTAssertEqual(h.samples.map(\.kg), [3])
        XCTAssertFalse(h.client.state.isBusy)
        h.client.connect()
        h.emit(kg: 4)
        XCTAssertTrue(h.client.state.isConnected)
        XCTAssertEqual(h.transport.starts, 3)
    }

    func testCancelDuringLastRecoveryAttemptStopsAllFutureAttempts() {
        let h = Harness()
        h.client.connect()
        h.emit(kg: 2)
        h.clock.advance(by: 47)
        XCTAssertEqual(h.transport.starts, 4)
        h.client.disconnect()
        h.clock.advance(by: 200)
        h.transport.emit(Harness.advertisement(kg: 9), registration: 3)
        XCTAssertEqual(h.transport.starts, 4)
        XCTAssertEqual(h.transport.stops, 4)
        XCTAssertEqual(h.samples.count, 1)
        XCTAssertFalse(h.client.state.isConnected)
    }

    func testRetiredRegistrationCannotSupplyTheFirstFrameOfANewAttempt() {
        let h = Harness()
        h.client.connect()
        h.emit(kg: 1)
        h.clock.advance(by: 27)
        h.transport.emit(Harness.advertisement(kg: 90), registration: 0)
        h.transport.emit(Harness.advertisement(kg: 91), registration: 1)
        XCTAssertEqual(h.client.state, .scanning)
        XCTAssertEqual(h.samples.map(\.kg), [1])
        h.emit(kg: 3)
        XCTAssertEqual(h.samples.map(\.kg), [1, 3])
        XCTAssertTrue(h.client.state.isConnected)
    }

    func testOldDeadlinesCannotDisconnectANewExplicitConnection() {
        let h = Harness()
        h.client.connect()
        let oldInitialDeadline = h.clock.jobs[0]
        h.emit(kg: 1)
        let oldSilenceDeadline = h.clock.jobs.last!
        h.client.disconnect()
        h.client.connect()
        h.emit(kg: 3)
        oldInitialDeadline.fireEvenIfCancelled()
        oldSilenceDeadline.fireEvenIfCancelled()
        XCTAssertTrue(h.client.state.isConnected)
        XCTAssertEqual(h.transport.starts, 2)
        XCTAssertEqual(h.transport.stops, 1)
    }

    func testRadioLossClearsLinkAndResumesIntentWithAFreshScan() {
        let h = Harness()
        h.client.connect()
        h.emit(kg: 6)
        h.client.send(.tare)
        h.transport.changeRadio(to: .poweredOff)
        XCTAssertEqual(h.client.state, .bluetoothOff)
        XCTAssertNil(h.client.deviceName)
        XCTAssertEqual(h.clock.pendingCount, 0)
        h.clock.advance(by: 30)
        h.transport.changeRadio(to: .poweredOn)
        XCTAssertEqual(h.transport.starts, 2)
        h.transport.emit(Harness.advertisement(kg: 50), registration: 0)
        XCTAssertEqual(h.samples.map(\.kg), [6])
        h.transport.emit(Harness.advertisement(kg: 8, id: Harness.otherScaleID))
        XCTAssertEqual(h.samples.map(\.kg), [6, 8])
        XCTAssertTrue(h.client.state.isConnected)
    }

    func testRadioLossDuringBackoffCancelsRetryBeforeANewInitialSearch() {
        let h = Harness()
        h.client.connect()
        h.emit(kg: 2)
        h.clock.advance(by: 25)
        let oldRetry = h.clock.jobs.last!
        h.transport.changeRadio(to: .poweredOff)
        h.clock.advance(by: 100)
        XCTAssertEqual(h.transport.starts, 2)
        h.transport.changeRadio(to: .poweredOn)
        XCTAssertEqual(h.transport.starts, 3)
        oldRetry.fireEvenIfCancelled()
        XCTAssertEqual(h.transport.starts, 3)
        h.clock.advance(by: 15)
        XCTAssertFalse(h.client.state.isBusy, "A radio return has a bounded initial search")
    }

    func testConnectingWithRadioOffWaitsWithoutScanningAndCancelClearsIntent() {
        let h = Harness(radioState: .poweredOff)
        h.client.connect()
        XCTAssertEqual(h.client.state, .bluetoothOff)
        XCTAssertEqual(h.transport.starts, 0)
        XCTAssertEqual(h.clock.pendingCount, 0)
        h.client.disconnect()
        h.transport.changeRadio(to: .poweredOn)
        XCTAssertEqual(h.transport.starts, 0)
        h.client.connect()
        h.emit(kg: 1)
        XCTAssertTrue(h.client.state.isConnected)
    }

    func testRadioDeniedAndUnsupportedPublishTheirRealStatesWithoutScanning() {
        for radioState in [BroadcastRadioState.unauthorized, .unsupported] {
            let h = Harness(radioState: radioState)
            h.client.connect()
            XCTAssertEqual(h.client.state, radioState == .unauthorized ? .unauthorized : .unsupported)
            XCTAssertEqual(h.transport.starts, 0)
            h.client.disconnect()
        }
    }

    func testARejectedInitialRegistrationCanBeRetriedWithoutFakeSamples() {
        let h = Harness()
        h.transport.acceptStart = false
        h.client.connect()
        XCTAssertFalse(h.client.state.isBusy)
        XCTAssertEqual(h.clock.pendingCount, 0)
        h.transport.emit(Harness.advertisement(kg: 50), registration: 0)
        XCTAssertTrue(h.samples.isEmpty)
        h.transport.acceptStart = true
        h.client.connect()
        h.emit(kg: 2)
        XCTAssertEqual(h.samples.map(\.kg), [2])
        XCTAssertTrue(h.client.state.isConnected)
    }

    func testRejectedRecoveryRegistrationsStillRespectTheRetryBudget() {
        let h = Harness()
        h.client.connect()
        h.emit(kg: 1)
        h.transport.acceptStart = false
        h.clock.advance(by: 10)
        XCTAssertEqual(h.transport.starts, 2)
        h.clock.advance(by: 2)
        XCTAssertEqual(h.transport.starts, 3)
        h.clock.advance(by: 5)
        XCTAssertEqual(h.transport.starts, 4)
        XCTAssertFalse(h.client.state.isBusy)
        XCTAssertEqual(h.clock.pendingCount, 0)
        XCTAssertEqual(h.samples.map(\.kg), [1])
    }

    func testRepeatedPoweredOnNotificationsDoNotResetTheRecoveryBudget() {
        let h = Harness()
        h.client.connect()
        h.emit(kg: 1)
        h.clock.advance(by: 10)
        h.transport.changeRadio(to: .poweredOn)
        h.clock.advance(by: 15)
        h.transport.changeRadio(to: .poweredOn)
        h.clock.advance(by: 37)
        XCTAssertEqual(h.transport.starts, 4)
        XCTAssertFalse(h.client.state.isBusy)
        XCTAssertEqual(h.clock.pendingCount, 0)
    }

    func testAStateObserverCancellingSearchCannotBeOverruledByStartingTheRadio() {
        let h = Harness()
        h.client.onStateChange = { [weak client = h.client] state in
            if state == .scanning { client?.disconnect() }
        }
        h.client.connect()
        XCTAssertEqual(h.transport.starts, 0)
        XCTAssertEqual(h.clock.pendingCount, 0)
        XCTAssertEqual(h.client.state, .disconnected(reason: nil))
    }

    func testAStateObserverCancellingConnectionCannotReceiveThatFirstSample() {
        let h = Harness()
        h.client.onStateChange = { [weak client = h.client] state in
            if state.isConnected { client?.disconnect() }
        }
        h.client.connect()
        h.emit(kg: 3)
        XCTAssertTrue(h.samples.isEmpty)
        XCTAssertEqual(h.clock.pendingCount, 0)
        XCTAssertEqual(h.client.state, .disconnected(reason: nil))
    }

    func testDiagnosticsReportScanFactsAndNeverClaimACommandWrite() {
        let h = Harness()
        h.client.startStreaming(cause: .manualWake)
        h.client.connect()
        h.emit(kg: 1)
        h.client.startStreaming(cause: .watchdog)
        h.clock.advance(by: 10)
        h.clock.advance(by: 15)
        h.client.startStreaming(cause: .manualWake)
        h.clock.advance(by: 37)
        XCTAssertTrue(h.nonScanDiagnostics.isEmpty)
        XCTAssertTrue(h.diagnostics.contains("scan already active (watchdog)"))
        XCTAssertTrue(h.diagnostics.contains("scan stopped (advertisement silence)"))
        XCTAssertTrue(h.diagnostics.contains("scan recovery pending (manual wake)"))
        XCTAssertTrue(h.diagnostics.contains("scan retry in 2 s"))
        XCTAssertTrue(h.diagnostics.contains("scan retry in 5 s"))
        XCTAssertTrue(h.diagnostics.contains("scan recovery exhausted"))
    }

    func testSleepStopsScanningAndCannotResumeItself() {
        let h = Harness()
        h.client.connect()
        h.emit(kg: 2)
        h.client.sleepDevice()
        h.clock.advance(by: 200)
        h.transport.changeRadio(to: .poweredOff)
        h.transport.changeRadio(to: .poweredOn)
        XCTAssertEqual(h.transport.starts, 1)
        XCTAssertEqual(h.transport.stops, 1)
        XCTAssertFalse(h.client.state.isConnected)
    }
}

@MainActor
private final class Harness {
    static let scaleID = UUID(uuidString: "00000000-0000-0000-0000-000000000001")!
    static let otherScaleID = UUID(uuidString: "00000000-0000-0000-0000-000000000002")!
    let clock = VirtualBroadcastScheduler()
    let transport: FakeBroadcastTransport
    let client: BroadcastGaugeClient
    var samples: [ForceSample] = []
    var states: [ProgressorConnectionState] = []
    var diagnostics: [String] = []
    var nonScanDiagnostics: [ProgressorClientDiagnostic] = []
    var packetBegins: [TimeInterval] = []
    var packetEnds = 0

    init(radioState: BroadcastRadioState = .poweredOn) {
        transport = FakeBroadcastTransport(radioState: radioState)
        client = BroadcastGaugeClient(transport: transport, scheduler: clock)
        client.onEvent = { [weak self] event in
            if case .sample(let sample) = event { self?.samples.append(sample) }
        }
        client.onStateChange = { [weak self] state in self?.states.append(state) }
        client.onDiagnostic = { [weak self] diagnostic in
            if case .broadcastScan(let event) = diagnostic { self?.diagnostics.append(event) }
            else { self?.nonScanDiagnostics.append(diagnostic) }
        }
        client.onPacketBoundary = { [weak self] boundary in
            switch boundary {
            case .began(let uptime): self?.packetBegins.append(uptime)
            case .ended: self?.packetEnds += 1
            }
        }
    }

    func emit(kg: Double) { transport.emit(Self.advertisement(kg: kg)) }

    static func advertisement(kg: Double, id: UUID = scaleID) -> BroadcastAdvertisement {
        let raw = UInt16((kg * 100).rounded())
        var data = Data(repeating: 0, count: 14)
        data[1] = 1
        data[12] = UInt8(raw >> 8)
        data[13] = UInt8(raw & 0xff)
        return BroadcastAdvertisement(peripheralID: id, name: "Test scale", manufacturerData: data)
    }
}

@MainActor
private final class FakeBroadcastTransport: BroadcastScanTransport {
    var radioState: BroadcastRadioState
    var onRadioStateChange: ((BroadcastRadioState) -> Void)?
    var acceptStart = true
    private(set) var activations = 0
    private(set) var starts = 0
    private(set) var stops = 0
    private var callbacks: [(BroadcastAdvertisement) -> Void] = []

    init(radioState: BroadcastRadioState) { self.radioState = radioState }
    func activate() { activations += 1 }
    func startScan(onAdvertisement: @escaping (BroadcastAdvertisement) -> Void) -> Bool {
        callbacks.append(onAdvertisement)
        starts += 1
        return acceptStart
    }
    func stopScan() { stops += 1 }
    func changeRadio(to state: BroadcastRadioState) {
        radioState = state
        onRadioStateChange?(state)
    }
    /// Keeping old callbacks deliberately models delivery racing cancellation. It
    /// does not claim iOS exposes Android's native advertisement observation time.
    func emit(_ advertisement: BroadcastAdvertisement, registration: Int? = nil) {
        callbacks[registration ?? (callbacks.count - 1)](advertisement)
    }
}

@MainActor
private final class VirtualBroadcastScheduler: BroadcastScanScheduler {
    private(set) var now: TimeInterval = 1000
    private(set) var jobs: [Job] = []
    var pendingCount: Int { jobs.filter { !$0.cancelled && !$0.fired }.count }

    func schedule(after delay: TimeInterval,
                  action: @escaping @MainActor () -> Void) -> any BroadcastScanCancellation {
        let job = Job(due: now + delay, order: jobs.count, action: action)
        jobs.append(job)
        return job
    }

    func advance(by duration: TimeInterval) {
        let target = now + duration
        while let job = jobs.filter({ !$0.cancelled && !$0.fired && $0.due <= target })
            .min(by: { ($0.due, $0.order) < ($1.due, $1.order) }) {
            now = job.due
            job.fired = true
            job.action()
        }
        now = target
    }

    final class Job: BroadcastScanCancellation {
        let due: TimeInterval
        let order: Int
        let action: @MainActor () -> Void
        var cancelled = false
        var fired = false
        init(due: TimeInterval, order: Int, action: @escaping @MainActor () -> Void) {
            self.due = due
            self.order = order
            self.action = action
        }
        func cancel() { cancelled = true }
        func fireEvenIfCancelled() { action() }
    }
}
