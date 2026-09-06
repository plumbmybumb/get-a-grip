// SPDX-License-Identifier: MPL-2.0
// Original contributions Copyright 2026 Nuri Bruner.

package run.nuri.getagrip

import run.nuri.getagrip.ble.ControlPointQueue
import run.nuri.getagrip.ble.ControlPointTransport
import run.nuri.getagrip.ble.ControlWriteType
import run.nuri.getagrip.ble.ProgressorClientDiagnostic
import run.nuri.getagrip.ble.ProgressorConnectionState
import run.nuri.getagrip.ble.StreamStartCause
import run.nuri.getagrip.ble.StreamStopCause
import run.nuri.getagrip.ble.TareIntegrityLatch
import run.nuri.getagrip.ble.TareStartDecision
import run.nuri.getagrip.store.DeviceStore
import run.nuri.getagrip.store.DiagnosticBreadcrumb
import run.nuri.getagrip.store.DiagnosticBreadcrumbRing
import run.nuri.getagrip.store.RunnerControlPolicy
import run.nuri.getagrip.store.TareConfirmationDecision
import run.nuri.getagrip.store.TarePolicy
import run.nuri.getagrip.store.TareTapDecision
import run.nuri.getagrip.engine.ForceSample
import run.nuri.getagrip.engine.GaugeKind
import run.nuri.getagrip.engine.ProgressorCommand
import run.nuri.getagrip.engine.ProgressorEvent
import run.nuri.getagrip.engine.RunnerPhase
import run.nuri.getagrip.engine.SamplePacing
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/// The translation of `Tests/BLELifecycleTests.swift`, one test per Swift `func`, same
/// names and the same doc comments.
///
/// TRANSLATION NOTE: four of the Swift tests drive a `RunnerSession`, which is exercised
/// through `SessionRunnerTests` here rather than through the store. The 45 s background
/// grace is now real (Phase 6) and asserted below; what has no Android twin is iOS's
/// background ASSERTION — see `aDeniedBackgroundAssertionDisconnectsAtOnce`. None of the
/// Swift names is silently dropped: a missing test name is how a ported rule goes missing.
class BLELifecycleTests {

    @Test
    fun lostLinkResetsTareLatchSoTheNextStartIsWritten() {
        val latch = TareIntegrityLatch()
        latch.tareEnqueued(1uL)
        assertEquals(TareStartDecision.deferred, latch.startDecision)

        // The tare ACK never arrives. A new physical link must be allowed to write its own
        // start; otherwise this safety latch bricks every later stream.
        latch.clearForNewLink()
        val writes = mutableListOf<String>()
        if (latch.startDecision == TareStartDecision.write) writes.add("startWeightMeasurement")

        assertEquals(listOf("startWeightMeasurement"), writes)
    }

    @Test
    fun acknowledgedTareReleasesItsDeferredStartExactlyOnce() {
        val latch = TareIntegrityLatch()
        latch.tareEnqueued(7uL)
        assertFalse(latch.tareAcknowledged(6uL))
        assertTrue(latch.tareAcknowledged(7uL))
        assertEquals(TareStartDecision.write, latch.startDecision)
        assertFalse(latch.tareAcknowledged(7uL))
    }

    @Test
    fun tarePolicyAllowsLoadedTareOnlyInPermittedPhases() {
        assertTrue(TarePolicy.phaseAllowsTare(RunnerPhase.LeadIn(0)))
        assertTrue(TarePolicy.phaseAllowsTare(RunnerPhase.Resting(0)))
        assertTrue(TarePolicy.phaseAllowsTare(RunnerPhase.Paused(RunnerPhase.Resting(0))))
        // ARMED allows taring (2026-08-18): getting set up while the screen says PULL is
        // exactly the taring moment, nothing accrues during armed, and both the tap and the
        // ≥1 kg confirmation re-read the phase before writing — a rep that engaged
        // mid-alert reads `Working` and rejects below.
        assertTrue(TarePolicy.phaseAllowsTare(RunnerPhase.Armed(0)))
        assertTrue(TarePolicy.phaseAllowsTare(RunnerPhase.Paused(RunnerPhase.Armed(0))))
        assertNull(TarePolicy.disabledReason(RunnerPhase.Armed(0)))

        assertEquals(
            "Tare is unavailable during the pull.",
            TarePolicy.disabledReason(RunnerPhase.Working(0)),
        )
        assertEquals(
            "Tare is unavailable while waiting for you to let go.",
            TarePolicy.disabledReason(RunnerPhase.Releasing(0)),
        )

        for (phase in listOf(RunnerPhase.Working(0), RunnerPhase.Releasing(0))) {
            assertFalse(TarePolicy.phaseAllowsTare(phase))
            assertNotNull(TarePolicy.disabledReason(phase))
        }
    }

    /// **A stale reading must never be mistaken for an unloaded gauge.** `DeviceStore`
    /// forces `currentKg` to 0 when samples stop while the link stays up — exactly what an
    /// interruption produces — so a climber still hanging at 5 kg reads as 0 kg. Without
    /// the freshness gate the tap skips confirmation and tares a live load.
    @Test
    fun aStaleReadingWakesTheStreamRatherThanTaringAFalseZero() {
        assertEquals(
            TareTapDecision.wakeStream,
            TarePolicy.tapDecision(RunnerPhase.Resting(0), isReadingLive = false, isLoadedForTare = false),
            "a stale 0 kg is an unknown load, not an empty gauge",
        )
        assertEquals(
            TareTapDecision.tare,
            TarePolicy.tapDecision(RunnerPhase.Resting(0), isReadingLive = true, isLoadedForTare = false),
        )
        assertEquals(
            TareTapDecision.confirm,
            TarePolicy.tapDecision(RunnerPhase.Resting(0), isReadingLive = true, isLoadedForTare = true),
        )
        assertEquals(
            TareTapDecision.blocked,
            TarePolicy.tapDecision(RunnerPhase.Working(0), isReadingLive = true, isLoadedForTare = false),
        )
    }

    /// REGRESSION (audit rank 18, verified): `DeviceStore.isLoadedForTare` has to be the
    /// coarse, change-guarded flag `tapDecision` actually consumes — computed straight from
    /// `TarePolicy.shouldConfirm`'s own threshold, so the two can never disagree.
    @Test
    fun isLoadedForTareMatchesTheShouldConfirmThreshold() {
        assertFalse(TarePolicy.shouldConfirm(0.0))
        assertFalse(TarePolicy.shouldConfirm(0.99))
        assertTrue(TarePolicy.shouldConfirm(1.0))
        assertTrue(
            TarePolicy.shouldConfirm(-5.0),
            "a negative load past the threshold still confirms",
        )
    }

    @Test
    fun tareKeepsTraceAndClockAcrossDeviceCounterRestart() {
        for (kind in listOf(GaugeKind.progressor, GaugeKind.whc06)) {
            val client = RecordingProgressorClient(kind)
            val device = DeviceStore(client = client, scope = inertScope(), clock = FakeClock())
            client.setState(ProgressorConnectionState.Connected)
            device.startStreaming(StreamStartCause.initial)
            client.emit(ProgressorEvent.Sample(ForceSample(kg = 3.0, deviceMicros = 5_000_000u)))
            client.emit(ProgressorEvent.Sample(ForceSample(kg = 4.0, deviceMicros = 5_012_500u)))
            val before = device.trace.toList()
            val commandCount = client.commands.size

            device.tare()

            assertEquals(before, device.trace, "tare must not erase measured history")
            assertEquals(0.0, device.peakKg)
            assertEquals(
                if (kind == GaugeKind.progressor) listOf(ProgressorCommand.tare, ProgressorCommand.startWeightMeasurement)
                else listOf(ProgressorCommand.tare),
                client.commands.drop(commandCount),
            )
            client.emit(ProgressorEvent.Sample(ForceSample(kg = 0.0, deviceMicros = 0u)))
            assertEquals(before, device.trace.take(before.size))
            assertEquals(before.size + 1, device.trace.size)
            assertTrue(device.trace.last().t > before.last().t)
            assertTrue(device.trace.last().t - before.last().t < 0.35)
            assertEquals(0.0, device.trace.last().kg)

            device.resetPeak()
            assertTrue(device.trace.isEmpty(), "a new measurement still clears its graph")
        }
    }

    /// The flag itself, not only the pure threshold function: a real sample delivered
    /// through the client has to flip `DeviceStore.isLoadedForTare` at that same threshold,
    /// and drop back once the load clears.
    @Test
    fun deviceStorePublishesIsLoadedForTareAtTheConfirmationThreshold() {
        val client = RecordingProgressorClient()
        val device = DeviceStore(client = client, scope = inertScope(), clock = FakeClock())
        client.setState(ProgressorConnectionState.Connected)
        device.startStreaming(StreamStartCause.manualMeasurement)

        assertFalse(device.isLoadedForTare, "nothing on the gauge yet")

        client.emit(ProgressorEvent.Sample(ForceSample(kg = 0.4, deviceMicros = 1_000u)))
        assertFalse(device.isLoadedForTare, "under the 1 kg threshold")

        client.emit(ProgressorEvent.Sample(ForceSample(kg = 5.0, deviceMicros = 2_000u)))
        assertTrue(device.isLoadedForTare)

        client.emit(ProgressorEvent.Sample(ForceSample(kg = 0.2, deviceMicros = 3_000u)))
        assertFalse(device.isLoadedForTare, "and it drops back once the load clears")
    }

    // MARK: - Pause/Skip controls (audit rank 2)

    /// `Idle` is every session opened before the gauge has connected — indefinitely, if it
    /// never answers. Pause/Resume stays enabled once paused (it is the button that
    /// resumes); Skip is additionally dead while paused, matching `endCurrentRep`/`skipSet`'s
    /// own `!phase.isPaused` guard in the engine.
    @Test
    fun runnerControlPolicyDisablesPauseAndSkipOnlyWhereTheEngineWouldNoOp() {
        assertFalse(RunnerControlPolicy.pauseEnabled(RunnerPhase.Idle))
        assertEquals(
            "Pause is unavailable while connecting.",
            RunnerControlPolicy.pauseDisabledReason(RunnerPhase.Idle),
        )
        val livePhases = listOf(
            RunnerPhase.LeadIn(0), RunnerPhase.Armed(0), RunnerPhase.Working(0),
            RunnerPhase.Releasing(0), RunnerPhase.Resting(0),
            RunnerPhase.Paused(RunnerPhase.Working(0)),
        )
        for (phase in livePhases) {
            assertTrue(
                RunnerControlPolicy.pauseEnabled(phase),
                "$phase must still be able to pause or resume",
            )
            assertNull(RunnerControlPolicy.pauseDisabledReason(phase))
        }

        assertFalse(RunnerControlPolicy.skipEnabled(RunnerPhase.Idle))
        assertEquals(
            "Skip is unavailable while connecting.",
            RunnerControlPolicy.skipDisabledReason(RunnerPhase.Idle),
        )
        assertFalse(RunnerControlPolicy.skipEnabled(RunnerPhase.Paused(RunnerPhase.Working(0))))
        assertEquals(
            "Skip is unavailable while paused.",
            RunnerControlPolicy.skipDisabledReason(RunnerPhase.Paused(RunnerPhase.Working(0))),
        )
        for (phase in livePhases.dropLast(1)) {
            assertTrue(
                RunnerControlPolicy.skipEnabled(phase),
                "$phase is a legitimate phase to skip out of",
            )
            assertNull(RunnerControlPolicy.skipDisabledReason(phase))
        }
    }

    /// The same trap one step later: the alert can be open across the moment the samples
    /// stop, and the quoted 5 kg collapses to a false 0 kg that sails through the tolerance
    /// check.
    @Test
    fun aConfirmationGoingStaleWhileOpenIsRejected() {
        assertEquals(
            TareConfirmationDecision.reject,
            TarePolicy.confirmationDecision(
                promptedKg = 5.0, currentKg = 0.0,
                promptedEpoch = 1uL, currentEpoch = 1uL,
                isConnected = true, sampleAge = 3.0,
                phase = RunnerPhase.Resting(0),
            ),
        )
        assertEquals(
            TareConfirmationDecision.tare,
            TarePolicy.confirmationDecision(
                promptedKg = 5.0, currentKg = 5.2,
                promptedEpoch = 1uL, currentEpoch = 1uL,
                isConnected = true, sampleAge = 0.0,
                phase = RunnerPhase.Resting(0),
            ),
            "jitter inside the tolerance still tares",
        )
    }

    /// The reason has to be SEEN, not only spoken — the button's own label carries it,
    /// because a dimmed control with no explanation is the bug this change exists to fix.
    /// Every phase that disables the button must supply one, and no phase that allows
    /// taring may supply one, or the button would sit there labelled "Pulling" while it was
    /// perfectly usable.
    @Test
    fun everyDisabledPhaseLabelsItselfAndNoAllowedPhaseDoes() {
        for (phase in listOf(RunnerPhase.Working(0), RunnerPhase.Releasing(0))) {
            assertNotNull(TarePolicy.disabledLabel(phase))
            assertNotNull(TarePolicy.disabledLabel(RunnerPhase.Paused(phase)))
        }
        val allowed = listOf(
            RunnerPhase.Idle, RunnerPhase.LeadIn(0), RunnerPhase.Armed(0),
            RunnerPhase.Resting(0), RunnerPhase.Finished,
        )
        for (phase in allowed) {
            assertNull(
                TarePolicy.disabledLabel(phase),
                "a phase that allows taring must leave the label as 'Tare'",
            )
        }
    }

    @Test
    fun tareConfirmationUsesMagnitudeThresholdAndSignedTolerance() {
        assertFalse(TarePolicy.shouldConfirm(0.99))
        assertTrue(TarePolicy.shouldConfirm(1.0))
        assertTrue(TarePolicy.shouldConfirm(-5.0))

        assertTrue(TarePolicy.readingIsStable(promptedKg = 5.0, currentKg = 5.49))
        assertTrue(TarePolicy.readingIsStable(promptedKg = 5.0, currentKg = 4.51))
        assertFalse(TarePolicy.readingIsStable(promptedKg = 5.0, currentKg = 5.51))
        assertFalse(TarePolicy.readingIsStable(promptedKg = 5.0, currentKg = -5.0))
    }

    @Test
    fun tareConfirmationReasksOnLoadMoveAndRejectsChangedLinkEpoch() {
        assertEquals(
            TareConfirmationDecision.reask,
            TarePolicy.confirmationDecision(
                promptedKg = 5.0, currentKg = 5.6,
                promptedEpoch = 3uL, currentEpoch = 3uL,
                isConnected = true, sampleAge = 0.0, phase = RunnerPhase.Resting(0),
            ),
        )
        assertEquals(
            TareConfirmationDecision.reject,
            TarePolicy.confirmationDecision(
                promptedKg = 5.0, currentKg = 5.0,
                promptedEpoch = 3uL, currentEpoch = 4uL,
                isConnected = true, sampleAge = 0.0, phase = RunnerPhase.Resting(0),
            ),
        )
        assertEquals(
            TareConfirmationDecision.tare,
            TarePolicy.confirmationDecision(
                promptedKg = 5.0, currentKg = 5.3,
                promptedEpoch = 3uL, currentEpoch = 3uL,
                isConnected = true, sampleAge = 0.0, phase = RunnerPhase.Resting(0),
            ),
        )
    }

    /// **PHASE 5 (RunnerSession).** The Swift test drives a whole session: connect, tare,
    /// start; drop the link, reconnect, and assert the reconnect writes a start and NOT a
    /// second tare. Two thirds of that rule live below the session and are asserted here —
    /// the ORDER (a start enqueued behind an un-ACKed tare is deferred, and released by the
    /// tare's own ACK) and the EPOCH bump the tare confirmation is keyed to. The session's
    /// own tare-once-then-start-on-every-reconnect policy comes with `RunnerSession`.
    @Test
    fun freshSessionTaresBeforeStartingAndReconnectDoesNotRetare() {
        val transport = FakeControlPointTransport()
        val queue = ControlPointQueue(transport)
        queue.enqueue(ProgressorCommand.tare)
        queue.enqueue(ProgressorCommand.startWeightMeasurement, StreamStartCause.initial)
        assertEquals(
            listOf(ProgressorCommand.tare),
            transport.writes,
            "the start waits behind an un-acknowledged tare",
        )
        assertTrue(transport.diagnostics.any {
            it == ProgressorClientDiagnostic.StreamStartDeferred(StreamStartCause.initial)
        })

        queue.writeCompleted(error = null)
        assertEquals(
            listOf(ProgressorCommand.tare, ProgressorCommand.startWeightMeasurement),
            transport.writes,
            "and the tare's own ACK is what releases it",
        )

        val client = RecordingProgressorClient()
        val device = DeviceStore(client = client, scope = inertScope(), clock = FakeClock())
        client.setState(ProgressorConnectionState.Connected)
        val firstEpoch = device.connectionEpoch
        client.setState(ProgressorConnectionState.Disconnected("test"))
        client.setState(ProgressorConnectionState.Connected)
        assertEquals(firstEpoch + 1uL, device.connectionEpoch)
    }

    /// **Waking must never tare, and that has to be a property of the API rather than of
    /// which branch happened to run.** This asserts against the commands actually written,
    /// not against the intent.
    ///
    /// It also pins the cause: recording a human tap as `watchdog` would make the breadcrumb
    /// report blame the automatic recovery for a manual one, in the very log that exists to
    /// explain who revived the stream.
    @Test
    fun wakingTheStreamWritesAStartAndNeverATare() {
        val client = RecordingProgressorClient()
        val device = DeviceStore(client = client, scope = inertScope(), clock = FakeClock())
        client.setState(ProgressorConnectionState.Connected)

        device.tare()
        device.startStreaming(StreamStartCause.initial)
        assertEquals(
            listOf(ProgressorCommand.tare, ProgressorCommand.startWeightMeasurement),
            client.commands,
        )

        device.startStreaming(StreamStartCause.manualWake)

        assertEquals(
            listOf(
                ProgressorCommand.tare,
                ProgressorCommand.startWeightMeasurement,
                ProgressorCommand.startWeightMeasurement,
            ),
            client.commands,
            "the wake added exactly one start and no second tare",
        )
        assertTrue(
            device.diagnosticEntries.any {
                it.event == DiagnosticBreadcrumb.StreamStartWritten(StreamStartCause.manualWake)
            },
            "a manual wake must not be filed under the automatic watchdog",
        )
        assertFalse(
            device.diagnosticEntries.any {
                it.event == DiagnosticBreadcrumb.StreamStartWritten(StreamStartCause.watchdog)
            },
        )
    }

    /// **A re-kick breaks the engine's timeline only for the gauge whose clock can reset.**
    ///
    /// The break exists to survive a Tindeq starting a fresh µs epoch when it is re-sent a
    /// start command. Every ported device is stamped from host uptime at ingestion, which no
    /// device restart can rewind — so there is nothing to break, and breaking is not free:
    /// it clears the accrual anchor and the arming debounce.
    ///
    /// **PHASE 5 (RunnerSession).** The Swift test asserts through the high-water mark,
    /// which belongs to the runner. What is assertable here is the fork the decision is
    /// keyed to — the CAPABILITY, never the device name — read through a store driving each
    /// kind.
    @Test
    fun aRekickBreaksTheTimelineOnlyForAGaugeWithItsOwnClock() {
        val tindeq = DeviceStore(
            client = FakeGaugeClient(GaugeKind.progressor),
            scope = inertScope(),
            clock = FakeClock(),
        )
        val scale = DeviceStore(
            client = FakeGaugeClient(GaugeKind.whc06),
            scope = inertScope(),
            clock = FakeClock(),
        )
        assertTrue(
            tindeq.gaugeCapabilities.hasDeviceClock,
            "the re-kick may clear the high-water mark, as a fresh epoch needs",
        )
        assertFalse(
            scale.gaugeCapabilities.hasDeviceClock,
            "a synthetic-clock re-kick must not touch the timeline at all",
        )
    }

    /// The silence a re-kick waits for is EIGHT SAMPLES of this gauge, floored at the
    /// Progressor's 0.8 s — a threshold that never moved would re-kick a healthy 8 Hz
    /// stream, whose ordinary sample gap is 125 ms and whose coalesced advertisements
    /// routinely double it.
    @Test
    fun theSilenceThresholdFollowsTheGaugesOwnSampleRate() {
        assertEquals(
            0.8, SamplePacing.silenceThreshold(forRate = 80.0), 1e-9,
            "8/80 is under the floor, so the hardware-earned number stands",
        )
        assertEquals(1.0, SamplePacing.silenceThreshold(forRate = 8.0), 1e-9)
        assertEquals(0.8, SamplePacing.silenceThreshold(forRate = 10.0), 1e-9)
        // Broadcast delivery is bursty by nature — multi-second advertisement holes are
        // ordinary — so the watchdog gets a 3 s floor there instead of beating in time with
        // the radio (the WH-C06 "blips" loop, 2026-08-17).
        assertEquals(3.0, SamplePacing.silenceThreshold(forRate = 8.0, isBroadcast = true), 1e-9)
        assertEquals(8.0, SamplePacing.silenceThreshold(forRate = 1.0, isBroadcast = true), 1e-9)
        // A rate of zero would divide by nothing; the floor is what answers.
        assertEquals(8.0, SamplePacing.silenceThreshold(forRate = 0.0), 1e-9)

        val scale = DeviceStore(
            client = FakeGaugeClient(GaugeKind.whc06),
            scope = inertScope(),
            clock = FakeClock(),
        )
        assertEquals(
            3.0,
            SamplePacing.silenceThreshold(
                forRate = scale.gaugeCapabilities.nominalSampleRate,
                isBroadcast = scale.gaugeCapabilities.isBroadcast,
            ),
            1e-9,
            "read from the capability table — and a broadcast session gets the 3 s floor, " +
                "not the rate-derived 1 s",
        )
    }

    /// **Backgrounding must no longer cost a reconnect.** The old rule disconnected the
    /// instant the app went to background, so a two-second voice-assistant call and a phone
    /// put in a bag were charged identically — 5–6 seconds of Searching/Connecting on the
    /// way back, which is what got reported as Bluetooth dropping.
    ///
    /// The window is opened and then called off, and the link is never touched on either
    /// side of it. Both breadcrumbs have to be there: a ring that showed a schedule with no
    /// cancellation would read as a disconnect that fired.
    @Test
    fun comingBackInsideTheGraceWindowKeepsTheLink() {
        val client = RecordingProgressorClient()
        val device = DeviceStore(client = client, scope = inertScope(), clock = FakeClock())
        client.setState(ProgressorConnectionState.Connected)

        device.beginBackgroundGrace()
        assertTrue(device.state.isConnected, "the grace holds the link open")
        assertTrue(device.diagnosticEntries.any {
            it.event == DiagnosticBreadcrumb.BackgroundDisconnectScheduled
        })

        device.cancelBackgroundGrace()

        assertTrue(device.state.isConnected, "the link was never touched")
        assertTrue(device.diagnosticEntries.any {
            it.event == DiagnosticBreadcrumb.BackgroundDisconnectCancelled
        })
    }

    /// **Two ON_STOPs must not stack two timers.** The second would extend a window the
    /// first already opened, so a phone bounced in and out of the foreground could hold the
    /// gauge awake indefinitely — the exact failure the 45 s bound exists to prevent.
    @Test
    fun aSecondBackgroundingDoesNotStackASecondGrace() {
        val client = RecordingProgressorClient()
        val device = DeviceStore(client = client, scope = inertScope(), clock = FakeClock())
        client.setState(ProgressorConnectionState.Connected)

        device.beginBackgroundGrace()
        device.beginBackgroundGrace()

        assertEquals(
            1,
            device.diagnosticEntries.count {
                it.event == DiagnosticBreadcrumb.BackgroundDisconnectScheduled
            },
            "one window, however many times we are told we left",
        )
    }

    /// A live session already keeps the link by other means — a `connectedDevice`
    /// foreground service — and scheduling a disconnect under a running stream would be a
    /// race against the workout.
    @Test
    fun aStreamingSessionSchedulesNoBackgroundDisconnect() {
        val client = RecordingProgressorClient()
        val device = DeviceStore(client = client, scope = inertScope(), clock = FakeClock())
        client.setState(ProgressorConnectionState.Connected)
        device.startStreaming(StreamStartCause.initial)

        device.beginBackgroundGrace()

        assertFalse(device.diagnosticEntries.any {
            it.event == DiagnosticBreadcrumb.BackgroundDisconnectScheduled
        })
        assertTrue(device.state.isConnected, "a streaming session keeps its link")
    }

    /// Every way a stream ends now says so, or "Signal became stale" stays ambiguous
    /// between a genuine stall and the app doing exactly what it should.
    @Test
    fun everyStreamStopIsRecordedWithItsCause() {
        val client = RecordingProgressorClient()
        val device = DeviceStore(client = client, scope = inertScope(), clock = FakeClock())
        client.setState(ProgressorConnectionState.Connected)

        // Every case, so a newly added one cannot be forgotten here. Note this proves
        // SERIALIZATION only — that a cause survives into the ring under its own name. It
        // cannot prove trigger-to-cause ROUTING, which is where the real mistakes live: two
        // of these were wired to the wrong trigger on iOS and this loop stayed green
        // (2026-08-16). Routing is guarded by passing the cause from each call site rather
        // than defaulting it, which is why `stopStreaming(cause)` has no default.
        for (cause in StreamStopCause.entries) {
            if (cause == StreamStopCause.disconnecting || cause == StreamStopCause.sleeping) {
                continue
            }
            device.startStreaming(StreamStartCause.initial)
            device.stopStreaming(cause)
            assertTrue(
                device.diagnosticEntries.any {
                    it.event == DiagnosticBreadcrumb.StreamStopped(cause)
                },
                "$cause must reach the ring under its own name",
            )
        }

        // The two that bypass `stopStreaming` entirely and set `isStreaming` themselves.
        device.startStreaming(StreamStartCause.manualMeasurement)
        device.disconnect()
        assertTrue(
            device.diagnosticEntries.any {
                it.event == DiagnosticBreadcrumb.StreamStopped(StreamStopCause.disconnecting)
            },
            "disconnect sets isStreaming directly and would otherwise record no stop",
        )

        client.setState(ProgressorConnectionState.Connected)
        device.startStreaming(StreamStartCause.manualMeasurement)
        device.sleepDevice()
        assertTrue(
            device.diagnosticEntries.any {
                it.event == DiagnosticBreadcrumb.StreamStopped(StreamStopCause.sleeping)
            },
            "sleeping bypasses stopStreaming the same way",
        )
    }

    /// **The branch that protects the gauge's battery.** iOS returns an invalid identifier
    /// when it refuses background time; with no assertion the sleeping task never runs once
    /// we are suspended, so holding the link would leave the gauge awake until flat. Denied
    /// means fall straight back to the fire-immediately rule.
    ///
    /// TRANSLATION NOTE: **Android has no assertion to be denied**, and it fails the other
    /// way — a process the OS reclaims takes its GATT link with it, so a grace cut short
    /// frees the gauge rather than stranding it. What survives is the rule for a gauge that
    /// cannot stream backgrounded at all: disconnect now, no window.
    @Test
    fun aDeniedBackgroundAssertionDisconnectsAtOnce() {
        val client = RecordingProgressorClient(GaugeKind.whc06)
        val device = DeviceStore(client = client, scope = inertScope(), clock = FakeClock())
        client.setState(ProgressorConnectionState.Connected)

        device.beginBackgroundGrace()

        assertFalse(device.state.isConnected, "no grace available means disconnect now")
        assertFalse(
            device.diagnosticEntries.any {
                it.event == DiagnosticBreadcrumb.BackgroundDisconnectScheduled
            },
            "nothing was scheduled, so the ring must not claim it was",
        )
    }

    /// Drives the expiry exactly as the timer would. Without this seam the grace tests would
    /// still pass if the disconnect never happened at all.
    @Test
    fun theGraceExpiringDisconnects() {
        val client = RecordingProgressorClient()
        val device = DeviceStore(client = client, scope = inertScope(), clock = FakeClock())
        client.setState(ProgressorConnectionState.Connected)

        device.beginBackgroundGrace()
        device.disconnectAfterGrace()

        assertFalse(device.state.isConnected, "expiry is the last reliable moment to act")
    }

    /// **Re-checked at expiry, never assumed.** Forty-five seconds is long enough for a
    /// session to have started from the Live Update on the lock screen, and disconnecting a
    /// streaming gauge would end a workout the grace was never about.
    @Test
    fun aGraceThatExpiresOverAStartedSessionLeavesTheLinkAlone() {
        val client = RecordingProgressorClient()
        val device = DeviceStore(client = client, scope = inertScope(), clock = FakeClock())
        client.setState(ProgressorConnectionState.Connected)

        device.beginBackgroundGrace()
        device.startStreaming(StreamStartCause.initial)
        device.disconnectAfterGrace()

        assertTrue(device.state.isConnected, "a session that began inside the window keeps it")
    }

    /// A gauge that cannot stream backgrounded is torn down on the spot, with no window at
    /// all — the branch that replaces iOS's denied assertion.
    @Test
    fun aBroadcastGaugeIsTornDownWithNoWindow() {
        val client = RecordingProgressorClient(GaugeKind.whc06)
        val device = DeviceStore(client = client, scope = inertScope(), clock = FakeClock())
        client.setState(ProgressorConnectionState.Connected)

        device.beginBackgroundGrace()

        assertFalse(device.state.isConnected, "no grace available means disconnect now")
    }

    @Test
    fun diagnosticRingIsBounded() {
        val ring = DiagnosticBreadcrumbRing()
        for (index in 0 until DiagnosticBreadcrumbRing.capacity + 20) {
            ring.append(DiagnosticBreadcrumb.ScenePhase("phase-$index"))
        }

        assertEquals(DiagnosticBreadcrumbRing.capacity, ring.entries.size)
    }

    @Test
    fun coalescedTraceFlushesKeepTheEarlierConnectionTransition() {
        val ring = DiagnosticBreadcrumbRing()
        ring.append(DiagnosticBreadcrumb.Connection(ProgressorConnectionState.Connected), at = 1.0)
        for (index in 0 until 500) {
            ring.append(DiagnosticBreadcrumb.TraceFlush(count = 1), at = (index + 2).toDouble())
        }

        assertEquals(2, ring.entries.size)
        assertEquals(
            DiagnosticBreadcrumb.Connection(ProgressorConnectionState.Connected),
            ring.entries.first().event,
        )
        assertEquals(DiagnosticBreadcrumb.TraceFlush(count = 500), ring.entries.last().event)
    }
}
