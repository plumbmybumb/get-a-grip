// SPDX-License-Identifier: MPL-2.0
// Original contributions Copyright 2026 Nuri Bruner.

import XCTest
@testable import Doigt

@MainActor
final class BLELifecycleTests: XCTestCase {
    func testLostLinkResetsTareLatchSoTheNextStartIsWritten() {
        var latch = TareIntegrityLatch()
        latch.tareEnqueued(id: 1)
        XCTAssertEqual(latch.startDecision, .deferred)

        // The tare ACK never arrives. A new physical link must be allowed to write its
        // own start; otherwise this safety latch bricks every later stream.
        latch.clearForNewLink()
        var writes: [String] = []
        if latch.startDecision == .write { writes.append("startWeightMeasurement") }

        XCTAssertEqual(writes, ["startWeightMeasurement"])
    }

    func testAcknowledgedTareReleasesItsDeferredStartExactlyOnce() {
        var latch = TareIntegrityLatch()
        latch.tareEnqueued(id: 7)
        XCTAssertFalse(latch.tareAcknowledged(id: 6))
        XCTAssertTrue(latch.tareAcknowledged(id: 7))
        XCTAssertEqual(latch.startDecision, .write)
        XCTAssertFalse(latch.tareAcknowledged(id: 7))
    }

    func testTarePolicyAllowsLoadedTareOnlyInPermittedPhases() {
        XCTAssertTrue(TarePolicy.phaseAllowsTare(.leadIn(slot: 0)))
        XCTAssertTrue(TarePolicy.phaseAllowsTare(.resting(slot: 0)))
        XCTAssertTrue(TarePolicy.phaseAllowsTare(.paused(before: .resting(slot: 0))))
        // ARMED allows taring (2026-08-18): getting set up while the screen says PULL
        // is exactly the taring moment, nothing accrues during armed, and both the
        // tap and the ≥1 kg confirmation re-read the phase before writing — a rep
        // that engaged mid-alert reads `.working` and rejects below.
        XCTAssertTrue(TarePolicy.phaseAllowsTare(.armed(slot: 0)))
        XCTAssertTrue(TarePolicy.phaseAllowsTare(.paused(before: .armed(slot: 0))))
        XCTAssertNil(TarePolicy.disabledReason(for: .armed(slot: 0)))

        XCTAssertEqual(
            TarePolicy.disabledReason(for: .working(slot: 0)),
            "Tare is unavailable during the pull."
        )
        XCTAssertEqual(
            TarePolicy.disabledReason(for: .releasing(slot: 0)),
            "Tare is unavailable while waiting for you to let go."
        )

        for phase in [RunnerPhase.working(slot: 0), .releasing(slot: 0)] {
            XCTAssertFalse(TarePolicy.phaseAllowsTare(phase))
            XCTAssertNotNil(TarePolicy.disabledReason(for: phase))
        }
    }

    /// **A stale reading must never be mistaken for an unloaded gauge.** `DeviceStore`
    /// forces `currentKg` to 0 when samples stop while the link stays up — exactly what a
    /// Siri interruption produces — so a climber still hanging at 5 kg reads as 0 kg.
    /// Without the freshness gate the tap skips confirmation and tares a live load.
    func testAStaleReadingWakesTheStreamRatherThanTaringAFalseZero() {
        XCTAssertEqual(
            TarePolicy.tapDecision(phase: .resting(slot: 0), isReadingLive: false,
                                   isLoadedForTare: false),
            .wakeStream,
            "a stale 0 kg is an unknown load, not an empty gauge"
        )
        XCTAssertEqual(
            TarePolicy.tapDecision(phase: .resting(slot: 0), isReadingLive: true,
                                   isLoadedForTare: false),
            .tare
        )
        XCTAssertEqual(
            TarePolicy.tapDecision(phase: .resting(slot: 0), isReadingLive: true,
                                   isLoadedForTare: true),
            .confirm
        )
        XCTAssertEqual(
            TarePolicy.tapDecision(phase: .working(slot: 0), isReadingLive: true,
                                   isLoadedForTare: false),
            .blocked
        )
    }

    /// REGRESSION (audit rank 18, verified): `DeviceStore.isLoadedForTare` has to be the
    /// coarse, change-guarded flag `tapDecision` actually consumes — computed straight
    /// from `TarePolicy.shouldConfirm`'s own threshold, so the two can never disagree.
    func testIsLoadedForTareMatchesTheShouldConfirmThreshold() {
        XCTAssertFalse(TarePolicy.shouldConfirm(readingKg: 0))
        XCTAssertFalse(TarePolicy.shouldConfirm(readingKg: 0.99))
        XCTAssertTrue(TarePolicy.shouldConfirm(readingKg: 1.0))
        XCTAssertTrue(TarePolicy.shouldConfirm(readingKg: -5.0),
                      "a negative load past the threshold still confirms")
    }

    func testTareKeepsTraceAndClockAcrossDeviceCounterRestart() {
        for kind in [GaugeKind.progressor, .whc06] {
            let client = RecordingProgressorClient(kind: kind)
            let device = DeviceStore(client: client)
            client.setState(.connected)
            device.startStreaming(cause: .initial)
            client.emit(.sample(ForceSample(kg: 3, deviceMicros: 5_000_000)))
            client.emit(.sample(ForceSample(kg: 4, deviceMicros: 5_012_500)))
            let before = device.trace
            let commandCount = client.commands.count

            device.tare()

            XCTAssertEqual(device.trace, before, "tare must not erase measured history")
            XCTAssertEqual(device.peakKg, 0)
            XCTAssertEqual(Array(client.commands.dropFirst(commandCount)),
                           kind == .progressor ? [.tare, .startWeightMeasurement] : [.tare])
            client.emit(.sample(ForceSample(kg: 0, deviceMicros: 0)))
            XCTAssertEqual(Array(device.trace.prefix(before.count)), before)
            XCTAssertEqual(device.trace.count, before.count + 1)
            XCTAssertGreaterThan(device.trace.last!.t, before.last!.t)
            XCTAssertLessThan(device.trace.last!.t - before.last!.t, 0.35)
            XCTAssertEqual(device.trace.last!.kg, 0)

            device.resetPeak()
            XCTAssertTrue(device.trace.isEmpty, "a new measurement still clears its graph")
        }
    }

    /// The flag itself, not only the pure threshold function: a real sample delivered
    /// through the client has to flip `DeviceStore.isLoadedForTare` at that same
    /// threshold, and drop back once the load clears.
    func testDeviceStorePublishesIsLoadedForTareAtTheConfirmationThreshold() {
        let client = RecordingProgressorClient()
        let device = DeviceStore(client: client)
        client.setState(.connected)

        XCTAssertFalse(device.isLoadedForTare, "nothing on the gauge yet")

        client.emit(.sample(ForceSample(kg: 0.4, deviceMicros: 1_000, isBatchStart: true)))
        XCTAssertFalse(device.isLoadedForTare, "under the 1 kg threshold")

        client.emit(.sample(ForceSample(kg: 5.0, deviceMicros: 2_000, isBatchStart: true)))
        XCTAssertTrue(device.isLoadedForTare)

        client.emit(.sample(ForceSample(kg: 0.2, deviceMicros: 3_000, isBatchStart: true)))
        XCTAssertFalse(device.isLoadedForTare, "and it drops back once the load clears")
    }

    // MARK: - Pause/Skip controls (audit rank 2)

    /// `.idle` is every session opened before the gauge has connected — indefinitely, if
    /// it never answers. Pause/Resume stays enabled once paused (it is the button that
    /// resumes); Skip is additionally dead while paused, matching
    /// `endCurrentRep`/`skipSet`'s own `!phase.isPaused` guard in the engine.
    func testRunnerControlPolicyDisablesPauseAndSkipOnlyWhereTheEngineWouldNoOp() {
        XCTAssertFalse(RunnerControlPolicy.pauseEnabled(for: .idle))
        XCTAssertEqual(RunnerControlPolicy.pauseDisabledReason(for: .idle),
                       "Pause is unavailable while connecting.")
        for phase in [RunnerPhase.leadIn(slot: 0), .armed(slot: 0), .working(slot: 0),
                      .releasing(slot: 0), .resting(slot: 0),
                      .paused(before: .working(slot: 0))] {
            XCTAssertTrue(RunnerControlPolicy.pauseEnabled(for: phase),
                          "\(phase) must still be able to pause or resume")
            XCTAssertNil(RunnerControlPolicy.pauseDisabledReason(for: phase))
        }

        XCTAssertFalse(RunnerControlPolicy.skipEnabled(for: .idle))
        XCTAssertEqual(RunnerControlPolicy.skipDisabledReason(for: .idle),
                       "Skip is unavailable while connecting.")
        XCTAssertFalse(RunnerControlPolicy.skipEnabled(for: .paused(before: .working(slot: 0))))
        XCTAssertEqual(RunnerControlPolicy.skipDisabledReason(for: .paused(before: .working(slot: 0))),
                       "Skip is unavailable while paused.")
        for phase in [RunnerPhase.leadIn(slot: 0), .armed(slot: 0), .working(slot: 0),
                      .releasing(slot: 0), .resting(slot: 0)] {
            XCTAssertTrue(RunnerControlPolicy.skipEnabled(for: phase),
                          "\(phase) is a legitimate phase to skip out of")
            XCTAssertNil(RunnerControlPolicy.skipDisabledReason(for: phase))
        }
    }

    /// The same trap one step later: the alert can be open across the moment the samples
    /// stop, and the quoted 5 kg collapses to a false 0 kg that sails through the
    /// tolerance check.
    func testAConfirmationGoingStaleWhileOpenIsRejected() {
        XCTAssertEqual(
            TarePolicy.confirmationDecision(promptedKg: 5, currentKg: 0,
                                            promptedEpoch: 1, currentEpoch: 1,
                                            isConnected: true, sampleAge: 3,
                                            phase: .resting(slot: 0)),
            .reject
        )
        XCTAssertEqual(
            TarePolicy.confirmationDecision(promptedKg: 5, currentKg: 5.2,
                                            promptedEpoch: 1, currentEpoch: 1,
                                            isConnected: true, sampleAge: 0,
                                            phase: .resting(slot: 0)),
            .tare,
            "jitter inside the tolerance still tares"
        )
    }

    /// The reason has to be SEEN, not only spoken — the button's own label carries it,
    /// because a dimmed control with no explanation is the bug this change exists to fix.
    /// Every phase that disables the button must supply one, and no phase that allows
    /// taring may supply one, or the button would sit there labelled "Pulling" while it
    /// was perfectly usable.
    func testEveryDisabledPhaseLabelsItselfAndNoAllowedPhaseDoes() {
        for phase in [RunnerPhase.working(slot: 0), .releasing(slot: 0)] {
            XCTAssertNotNil(TarePolicy.disabledLabel(for: phase))
            XCTAssertNotNil(TarePolicy.disabledLabel(for: .paused(before: phase)))
        }
        for phase in [RunnerPhase.idle, .leadIn(slot: 0), .armed(slot: 0),
                      .resting(slot: 0), .finished] {
            XCTAssertNil(TarePolicy.disabledLabel(for: phase),
                         "a phase that allows taring must leave the label as 'Tare'")
        }
    }

    func testTareConfirmationUsesMagnitudeThresholdAndSignedTolerance() {
        XCTAssertFalse(TarePolicy.shouldConfirm(readingKg: 0.99))
        XCTAssertTrue(TarePolicy.shouldConfirm(readingKg: 1.0))
        XCTAssertTrue(TarePolicy.shouldConfirm(readingKg: -5.0))

        XCTAssertTrue(TarePolicy.readingIsStable(promptedKg: 5.0, currentKg: 5.49))
        XCTAssertTrue(TarePolicy.readingIsStable(promptedKg: 5.0, currentKg: 4.51))
        XCTAssertFalse(TarePolicy.readingIsStable(promptedKg: 5.0, currentKg: 5.51))
        XCTAssertFalse(TarePolicy.readingIsStable(promptedKg: 5.0, currentKg: -5.0))
    }

    func testTareConfirmationReasksOnLoadMoveAndRejectsChangedLinkEpoch() {
        XCTAssertEqual(
            TarePolicy.confirmationDecision(
                promptedKg: 5.0, currentKg: 5.6,
                promptedEpoch: 3, currentEpoch: 3,
                isConnected: true, sampleAge: 0, phase: .resting(slot: 0)
            ),
            .reask
        )
        XCTAssertEqual(
            TarePolicy.confirmationDecision(
                promptedKg: 5.0, currentKg: 5.0,
                promptedEpoch: 3, currentEpoch: 4,
                isConnected: true, sampleAge: 0, phase: .resting(slot: 0)
            ),
            .reject
        )
        XCTAssertEqual(
            TarePolicy.confirmationDecision(
                promptedKg: 5.0, currentKg: 5.3,
                promptedEpoch: 3, currentEpoch: 3,
                isConnected: true, sampleAge: 0, phase: .resting(slot: 0)
            ),
            .tare
        )
    }

    func testFreshSessionTaresBeforeStartingAndReconnectDoesNotRetare() {
        let client = RecordingProgressorClient()
        let device = DeviceStore(client: client)
        let session = RunnerSession(template: SessionTemplate(draft: .starter, sortIndex: 0),
                                     device: device)
        session.begin()
        defer { session.end() }

        // The fake connects synchronously; this is the same callback RunnerView forwards
        // from its connected-state observation.
        client.setState(.connected)
        session.connectionChanged(isConnected: true)
        XCTAssertEqual(client.commands, [.tare, .startWeightMeasurement])
        XCTAssertTrue(device.diagnosticEntries.contains {
            $0.event == .streamStartRequested(.initial)
        })
        XCTAssertTrue(device.diagnosticEntries.contains {
            $0.event == .streamStartWritten(.initial)
        })
        let firstEpoch = device.connectionEpoch

        client.setState(.disconnected(reason: "test"))
        session.connectionChanged(isConnected: false)
        client.setState(.connected)
        session.connectionChanged(isConnected: true)

        XCTAssertEqual(device.connectionEpoch, firstEpoch + 1)
        XCTAssertEqual(client.commands,
                       [.tare, .startWeightMeasurement, .startWeightMeasurement])
        XCTAssertTrue(device.diagnosticEntries.contains {
            $0.event == .streamStartRequested(.reconnect)
        })
        XCTAssertTrue(device.diagnosticEntries.contains {
            $0.event == .streamStartWritten(.reconnect)
        })
    }

    /// **Waking must never tare, and that has to be a property of the API rather than of
    /// which branch happened to run.** `startIfReady` tares on its first-start branch, so
    /// routing a manual wake through it would make the guarantee accidental — this
    /// asserts against the commands actually written, not against the intent.
    ///
    /// It also pins the cause: recording a human tap as `.watchdog` would make the
    /// breadcrumb report blame the automatic recovery for a manual one, in the very log
    /// that exists to explain who revived the stream.
    func testWakingTheStreamWritesAStartAndNeverATare() {
        let client = RecordingProgressorClient()
        let device = DeviceStore(client: client)
        let session = RunnerSession(template: SessionTemplate(draft: .starter, sortIndex: 0),
                                    device: device)
        session.begin()
        defer { session.end() }

        client.setState(.connected)
        session.connectionChanged(isConnected: true)
        XCTAssertEqual(client.commands, [.tare, .startWeightMeasurement])

        session.wakeStream()

        XCTAssertEqual(client.commands,
                       [.tare, .startWeightMeasurement, .startWeightMeasurement],
                       "the wake added exactly one start and no second tare")
        XCTAssertTrue(device.diagnosticEntries.contains {
            $0.event == .streamStartWritten(.manualWake)
        }, "a manual wake must not be filed under the automatic watchdog")
        XCTAssertFalse(device.diagnosticEntries.contains {
            $0.event == .streamStartWritten(.watchdog)
        })
    }

    /// **A re-kick breaks the engine's timeline only for the gauge whose clock can reset.**
    ///
    /// The break exists to survive a Tindeq starting a fresh µs epoch when it is re-sent a
    /// start command. Every ported device is stamped from host uptime at ingestion, which no
    /// device restart can rewind — so there is nothing to break, and breaking is not free:
    /// it clears the accrual anchor and the arming debounce, and the watchdog fires often
    /// enough on a slow gauge to land one between every pair of samples.
    ///
    /// Asserted through the one thing the break is observable by: the high-water mark. A
    /// batch stamped BEHIND the newest one is refused unless the timeline was broken.
    func testARekickBreaksTheTimelineOnlyForAGaugeWithItsOwnClock() {
        for kind in [GaugeKind.progressor, .whc06] {
            let client = RecordingProgressorClient(kind: kind)
            let device = DeviceStore(client: client)
            let session = RunnerSession(template: SessionTemplate(draft: .starter, sortIndex: 0),
                                        device: device)
            session.begin()
            defer { session.end() }

            client.setState(.connected)
            session.connectionChanged(isConnected: true)

            client.emit(.sample(ForceSample(kg: 5, deviceMicros: 1_000_000, isBatchStart: true)))
            session.wakeStream()
            // Half a second BEHIND the mark the first batch set.
            client.emit(.sample(ForceSample(kg: 7, deviceMicros: 500_000, isBatchStart: true)))

            if kind.capabilities.hasDeviceClock {
                XCTAssertFalse(session.snapshot.isRejectingStaleBatches,
                               "the re-kick cleared the high-water mark, as a fresh epoch needs")
            } else {
                XCTAssertTrue(session.snapshot.isRejectingStaleBatches,
                              "a synthetic-clock re-kick must not touch the timeline at all")
            }
        }
    }

    /// The silence a re-kick waits for is EIGHT SAMPLES of this gauge, floored at the
    /// Progressor's 0.8 s — a threshold that never moved would re-kick a healthy 8 Hz
    /// stream, whose ordinary sample gap is 125 ms and whose coalesced advertisements
    /// routinely double it.
    func testTheSilenceThresholdFollowsTheGaugesOwnSampleRate() {
        XCTAssertEqual(RunnerSession.silenceThreshold(forRate: 80), 0.8, accuracy: 1e-9,
                       "8/80 is under the floor, so the hardware-earned number stands")
        XCTAssertEqual(RunnerSession.silenceThreshold(forRate: 8), 1.0, accuracy: 1e-9)
        XCTAssertEqual(RunnerSession.silenceThreshold(forRate: 10), 0.8, accuracy: 1e-9)
        // Broadcast delivery is bursty by nature — multi-second advertisement holes
        // are ordinary — so the watchdog gets a 3 s floor there instead of beating
        // in time with the radio (the WH-C06 "blips" loop, 2026-08-17).
        XCTAssertEqual(RunnerSession.silenceThreshold(forRate: 8, isBroadcast: true), 3.0, accuracy: 1e-9)
        XCTAssertEqual(RunnerSession.silenceThreshold(forRate: 1, isBroadcast: true), 8.0, accuracy: 1e-9)
        // A rate of zero would divide by nothing; the floor is what answers.
        XCTAssertEqual(RunnerSession.silenceThreshold(forRate: 0), 8.0, accuracy: 1e-9)

        let scale = DeviceStore(client: RecordingProgressorClient(kind: .whc06))
        let session = RunnerSession(template: SessionTemplate(draft: .starter, sortIndex: 0),
                                    device: scale)
        XCTAssertEqual(session.silenceRestartSeconds, 3.0, accuracy: 1e-9,
                       "read once at init, from the capability table — and a broadcast "
                       + "session gets the 3 s floor, not the rate-derived 1 s")
    }

    /// **Backgrounding must no longer cost a reconnect.** The old rule disconnected the
    /// instant the app went to background, so a two-second "hey Siri" and a phone put in
    /// a bag were charged identically — 5–6 seconds of Searching/Connecting on the way
    /// back, which is what got reported as Bluetooth dropping.
    func testComingBackInsideTheGraceWindowKeepsTheLink() {
        let client = RecordingProgressorClient()
        let device = DeviceStore(client: client)
        client.setState(.connected)

        device.beginBackgroundGrace()
        XCTAssertTrue(device.diagnosticEntries.contains { $0.event == .backgroundDisconnectScheduled })

        device.cancelBackgroundGrace()
        XCTAssertTrue(device.diagnosticEntries.contains { $0.event == .backgroundDisconnectCancelled })
        XCTAssertTrue(device.state.isConnected, "the link was never touched")
    }

    /// A live session already keeps the link by other means, and scheduling a disconnect
    /// under a running stream would be a race against the workout.
    func testAStreamingSessionSchedulesNoBackgroundDisconnect() {
        let client = RecordingProgressorClient()
        let device = DeviceStore(client: client)
        client.setState(.connected)
        device.startStreaming(cause: .initial)

        device.beginBackgroundGrace()

        XCTAssertFalse(device.diagnosticEntries.contains { $0.event == .backgroundDisconnectScheduled })
    }

    /// Every way a stream ends now says so, or "Signal became stale" stays ambiguous
    /// between a genuine stall and the app doing exactly what it should.
    func testEveryStreamStopIsRecordedWithItsCause() {
        let client = RecordingProgressorClient()
        let device = DeviceStore(client: client)
        client.setState(.connected)

        // Every case, so a newly added one cannot be forgotten here. Note this proves
        // SERIALIZATION only — that a cause survives into the ring under its own name.
        // It cannot prove trigger-to-cause ROUTING, which is where the real mistakes
        // live: two of these were wired to the wrong trigger and this loop stayed green
        // (2026-08-16). Routing is guarded by passing the cause from each call site
        // rather than defaulting it, which is why `stopStreaming(cause:)` has no default.
        for cause in StreamStopCause.allCases where cause != .disconnecting && cause != .sleeping {
            device.startStreaming(cause: .initial)
            device.stopStreaming(cause: cause)
            XCTAssertTrue(device.diagnosticEntries.contains { $0.event == .streamStopped(cause) },
                          "\(cause) must reach the ring under its own name")
        }

        // The two that bypass `stopStreaming` entirely and set `isStreaming` themselves.
        device.startStreaming(cause: .manualMeasurement)
        device.disconnect()
        XCTAssertTrue(device.diagnosticEntries.contains { $0.event == .streamStopped(.disconnecting) },
                      "disconnect sets isStreaming directly and would otherwise record no stop")

        client.setState(.connected)
        device.startStreaming(cause: .manualMeasurement)
        device.sleepDevice()
        XCTAssertTrue(device.diagnosticEntries.contains { $0.event == .streamStopped(.sleeping) },
                      "sleeping bypasses stopStreaming the same way")
    }

    /// **The branch that protects the gauge's battery.** UIKit returns `.invalid` when it
    /// refuses background time; with no assertion the sleeping task never runs once we
    /// are suspended, so holding the link would leave the Progressor awake until flat.
    /// Denied means fall straight back to the old fire-immediately rule.
    func testADeniedBackgroundAssertionDisconnectsAtOnce() {
        let client = RecordingProgressorClient()
        let device = DeviceStore(client: client)
        device.beginAssertion = { _ in .invalid }
        device.endAssertion = { _ in }
        client.setState(.connected)

        device.beginBackgroundGrace()

        XCTAssertFalse(device.state.isConnected, "no grace available means disconnect now")
        XCTAssertFalse(device.diagnosticEntries.contains { $0.event == .backgroundDisconnectScheduled },
                       "nothing was scheduled, so the ring must not claim it was")
    }

    /// Drives the expiration handler exactly as iOS would when it suspends us early.
    /// Without this the grace tests would still pass if the disconnect never happened.
    func testTheGraceExpiringDisconnects() {
        let client = RecordingProgressorClient()
        let device = DeviceStore(client: client)
        var handler: (@MainActor () -> Void)?
        device.beginAssertion = { given in
            handler = given
            return UIBackgroundTaskIdentifier(rawValue: 1)
        }
        device.endAssertion = { _ in }
        client.setState(.connected)

        device.beginBackgroundGrace()
        XCTAssertTrue(device.state.isConnected, "still connected while the window is open")

        handler?()

        XCTAssertFalse(device.state.isConnected, "expiry is the last reliable moment to act")
    }

    func testDiagnosticRingIsBounded() {
        var ring = DiagnosticBreadcrumbRing()
        for index in 0..<(DiagnosticBreadcrumbRing.capacity + 20) {
            ring.append(.scenePhase("phase-\(index)"))
        }

        XCTAssertEqual(ring.entries.count, DiagnosticBreadcrumbRing.capacity)
    }

    func testCoalescedTraceFlushesKeepTheEarlierConnectionTransition() {
        var ring = DiagnosticBreadcrumbRing()
        ring.append(.connection(.connected), at: Date(timeIntervalSince1970: 1))
        for index in 0..<500 {
            ring.append(.traceFlush(count: 1),
                         at: Date(timeIntervalSince1970: TimeInterval(index + 2)))
        }

        XCTAssertEqual(ring.entries.count, 2)
        XCTAssertEqual(ring.entries.first?.event, .connection(.connected))
        XCTAssertEqual(ring.entries.last?.event, .traceFlush(count: 500))
    }
}

@MainActor
/// Shared across the lifecycle and display test files — both need a client that records
/// commands without CoreBluetooth, which the Simulator cannot provide.
final class RecordingProgressorClient: ProgressorClient {
    var onEvent: ((ProgressorEvent) -> Void)?
    var onPacketBoundary: ((PacketBoundary) -> Void)?
    var onStateChange: ((ProgressorConnectionState) -> Void)?
    var onDiagnostic: ((ProgressorClientDiagnostic) -> Void)?

    private(set) var state: ProgressorConnectionState = .idle
    private(set) var deviceName: String? = "Test gauge"
    private(set) var commands: [ProgressorCommand] = []
    /// Which device this stands in for. The timing rules a session picks up at
    /// construction — the credited-gap cap, the silence threshold, whether a re-kick may
    /// break the timeline — all read `kind.capabilities`, so a test about them has to be
    /// able to say what it is driving.
    let kind: GaugeKind

    init(kind: GaugeKind = .progressor) {
        self.kind = kind
    }

    func connect() { setState(.connected) }

    func disconnect() { setState(.disconnected(reason: nil)) }

    func send(_ command: ProgressorCommand) {
        commands.append(command)
    }

    func startStreaming(cause: StreamStartCause) {
        commands.append(.startWeightMeasurement)
        onDiagnostic?(.streamStartWritten(cause))
    }

    func sleepDevice() { send(.enterSleep) }

    func setState(_ next: ProgressorConnectionState) {
        state = next
        onStateChange?(next)
    }

    func emit(_ event: ProgressorEvent) { onEvent?(event) }
}
