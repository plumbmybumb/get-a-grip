// SPDX-License-Identifier: MPL-2.0
// Original contributions Copyright 2026 Nuri Bruner.
import XCTest
@testable import Doigt

/// The live gauge and the max test re-kick a silent stream themselves, so the Tare
/// control stays Tare and Wake is only the fallback (a Progressor user, 2026-10-31:
/// "why do I have to press anything to start measuring?").
@MainActor
final class GaugeStreamKeeperTests: XCTestCase {
    private func starts(_ client: RecordingProgressorClient) -> Int {
        client.commands.filter { $0 == .startWeightMeasurement }.count
    }

    private func streamingStore(kind: GaugeKind = .progressor, holding: Bool = true)
        -> (DeviceStore, RecordingProgressorClient, Date) {
        let client = RecordingProgressorClient(kind: kind)
        let device = DeviceStore(client: client)
        client.connect()
        if holding { device.keepStreamAlive("gauge", true) }
        let start = Date()
        device.startStreaming(cause: .manualMeasurement)
        return (device, client, start)
    }

    func testASilentStreamIsReKickedWithoutAnyTap() {
        let (device, client, t0) = streamingStore()
        XCTAssertEqual(starts(client), 1)

        // Inside the spacing that follows the screen's own start: nothing yet.
        device.checkStreamKeepAlive(now: t0.addingTimeInterval(1.0))
        XCTAssertEqual(starts(client), 1)

        device.checkStreamKeepAlive(now: t0.addingTimeInterval(2.1))
        XCTAssertEqual(starts(client), 2, "silence past the threshold re-sends start")
        XCTAssertTrue(device.diagnosticEntries.contains { $0.event == .streamStartRequested(.watchdog) })

        // Re-kicks are spaced, never one per tick.
        device.checkStreamKeepAlive(now: t0.addingTimeInterval(2.6))
        XCTAssertEqual(starts(client), 2)
        XCTAssertFalse(device.streamRekickExhausted)
        device.stopStreaming(cause: .userStopped)
    }

    func testTheKeeperOnlyEverSendsStartNeverTareOrStop() {
        let (device, client, t0) = streamingStore()
        for second in stride(from: 0.5, through: 12, by: 0.5) {
            device.checkStreamKeepAlive(now: t0.addingTimeInterval(second))
        }
        XCTAssertTrue(client.commands.allSatisfy { $0 == .startWeightMeasurement }, "\(client.commands)")
        device.stopStreaming(cause: .userStopped)
    }

    func testAfterThreeUnansweredReKicksWakeIsOffered() {
        let (device, client, t0) = streamingStore()
        for second in [2.1, 4.2, 6.3] {
            device.checkStreamKeepAlive(now: t0.addingTimeInterval(second))
        }
        XCTAssertEqual(starts(client), 1 + DeviceStore.maximumRekicksWithoutSample)
        XCTAssertFalse(device.streamRekickExhausted)

        device.checkStreamKeepAlive(now: t0.addingTimeInterval(8.4))
        XCTAssertTrue(device.streamRekickExhausted)
        XCTAssertEqual(starts(client), 4, "giving up sends nothing")
        XCTAssertTrue(device.diagnosticEntries.contains { $0.event == .streamRekickGaveUp })
        XCTAssertEqual(TarePolicy.gaugeMode(isStreaming: device.isStreaming,
                                            isReadingLive: device.isReadingLive,
                                            keepsStreamAlive: device.keepsStreamAlive,
                                            rekickExhausted: device.streamRekickExhausted),
                       .wakeAfterRestarts)

        // Exhausted means quiet: no more automatic writes.
        device.checkStreamKeepAlive(now: t0.addingTimeInterval(20))
        XCTAssertEqual(starts(client), 4)

        // Wake hands the stream back to the keeper.
        device.startStreaming(cause: .manualWake)
        XCTAssertFalse(device.streamRekickExhausted)
        device.stopStreaming(cause: .userStopped)
    }

    func testASampleClearsTheGiveUpAndTheCount() {
        let (device, client, t0) = streamingStore()
        for second in [2.1, 4.2, 6.3, 8.4] {
            device.checkStreamKeepAlive(now: t0.addingTimeInterval(second))
        }
        XCTAssertTrue(device.streamRekickExhausted)
        client.emit(.sample(ForceSample(kg: 0.2, deviceMicros: 0)))
        XCTAssertFalse(device.streamRekickExhausted)
        XCTAssertTrue(device.isReadingLive)
        XCTAssertEqual(TarePolicy.gaugeMode(isStreaming: true, isReadingLive: device.isReadingLive,
                                            keepsStreamAlive: true, rekickExhausted: false), .tare)
        device.stopStreaming(cause: .userStopped)
    }

    func testAFlowingStreamIsNeverReKicked() {
        let (device, client, _) = streamingStore()
        client.emit(.sample(ForceSample(kg: 1, deviceMicros: 0)))
        device.checkStreamKeepAlive(now: Date().addingTimeInterval(0.5))
        XCTAssertEqual(starts(client), 1)
        device.stopStreaming(cause: .userStopped)
    }

    /// The runner has its own watchdog (which also breaks the engine's timeline first),
    /// and a screen that stopped the stream on purpose stays stopped.
    func testNoHolderOrAStoppedStreamMeansNoReKick() {
        let (device, client, t0) = streamingStore(holding: false)
        device.checkStreamKeepAlive(now: t0.addingTimeInterval(10))
        XCTAssertEqual(starts(client), 1, "no keeper without a holder")

        device.keepStreamAlive("gauge", true)
        device.stopStreaming(cause: .userStopped)
        device.checkStreamKeepAlive(now: t0.addingTimeInterval(20))
        XCTAssertEqual(starts(client), 1, "a stopped stream is not revived")

        device.keepStreamAlive("gauge", false)
        XCTAssertFalse(device.keepsStreamAlive)
    }

    func testHoldersAreNamedSoARepeatedAppearCannotLeakOne() {
        let device = DeviceStore(client: RecordingProgressorClient())
        device.keepStreamAlive("gauge", true)
        device.keepStreamAlive("gauge", true)
        device.keepStreamAlive("gauge", false)
        XCTAssertFalse(device.keepsStreamAlive)
    }

    /// Broadcast delivery is bursty: the 3 s floor, and nothing at all while scanning.
    func testABroadcastScaleGetsTheThreeSecondFloorAndIsNeverBouncedWhileScanning() {
        let (device, client, t0) = streamingStore(kind: .whc06)
        XCTAssertEqual(device.streamSilenceRekickSeconds, 3.0)
        device.checkStreamKeepAlive(now: t0.addingTimeInterval(2.9))
        XCTAssertEqual(starts(client), 1)
        device.checkStreamKeepAlive(now: t0.addingTimeInterval(3.1))
        XCTAssertEqual(starts(client), 2)

        client.setState(.scanning)
        device.checkStreamKeepAlive(now: t0.addingTimeInterval(30))
        XCTAssertEqual(starts(client), 2, "a scale still scanning is not connected")
    }

    /// Restated in the store because it also builds for the watch; must not drift.
    func testTheKeeperUsesTheRunnersSilenceThresholdForEveryKind() {
        for kind in GaugeKind.allCases {
            let caps = kind.capabilities
            XCTAssertEqual(DeviceStore.silenceRekickSeconds(forRate: caps.nominalSampleRate,
                                                           isBroadcast: caps.isBroadcast),
                           RunnerSession.silenceThreshold(forRate: caps.nominalSampleRate,
                                                          isBroadcast: caps.isBroadcast),
                           "\(kind)")
        }
    }

    // MARK: - The control's mode

    func testTheGaugeControlStaysTareWhileTheKeeperWorks() {
        XCTAssertEqual(TarePolicy.gaugeMode(isStreaming: true, isReadingLive: true,
                                            keepsStreamAlive: true, rekickExhausted: false), .tare)
        XCTAssertEqual(TarePolicy.gaugeMode(isStreaming: true, isReadingLive: false,
                                            keepsStreamAlive: true, rekickExhausted: false), .waitingForReadings)
        XCTAssertEqual(TarePolicy.gaugeMode(isStreaming: false, isReadingLive: false,
                                            keepsStreamAlive: true, rekickExhausted: false), .notMeasuring)
        XCTAssertEqual(TarePolicy.gaugeMode(isStreaming: true, isReadingLive: false,
                                            keepsStreamAlive: true, rekickExhausted: true), .wakeAfterRestarts)
        // A screen with no keeper keeps the old rule.
        XCTAssertEqual(TarePolicy.gaugeMode(isStreaming: true, isReadingLive: false,
                                            keepsStreamAlive: false, rekickExhausted: false), .wake)
    }

    func testOnlyTareAndWakeAreTappableAndWakeAlwaysSaysWhy() {
        XCTAssertTrue(GaugeTareMode.tare.isEnabled)
        XCTAssertTrue(GaugeTareMode.wakeAfterRestarts.isEnabled)
        XCTAssertFalse(GaugeTareMode.waitingForReadings.isEnabled)
        XCTAssertFalse(GaugeTareMode.notMeasuring.isEnabled)
        XCTAssertNotNil(GaugeTareMode.wakeAfterRestarts.reason)
        XCTAssertNotNil(GaugeTareMode.waitingForReadings.reason)
        XCTAssertEqual(GaugeTareMode.waitingForReadings.title, GaugeTareMode.tare.title)
    }

    // MARK: - Reset

    func testResetZeroesThePeakAndTouchesNothingElse() {
        let (device, client, _) = streamingStore()
        client.emit(.sample(ForceSample(kg: 12, deviceMicros: 0)))
        client.emit(.sample(ForceSample(kg: 4, deviceMicros: 12_500)))
        XCTAssertEqual(device.peakKg, 12)
        XCTAssertTrue(device.hasPeak)
        let commands = client.commands
        let trace = device.trace

        device.resetDisplayStatistics()

        XCTAssertEqual(device.peakKg, 0)
        XCTAssertFalse(device.hasPeak)
        XCTAssertEqual(device.currentKg, 4, "the reading is untouched — no tare")
        XCTAssertEqual(device.trace, trace, "the graph stays")
        XCTAssertEqual(client.commands, commands, "no tare, stop or start written")
        XCTAssertTrue(device.isStreaming)
        device.stopStreaming(cause: .userStopped)
    }
}
