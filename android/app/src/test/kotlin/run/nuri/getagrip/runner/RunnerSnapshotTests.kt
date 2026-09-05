// SPDX-License-Identifier: MPL-2.0
// Original contributions Copyright 2026 Nuri Bruner.

package run.nuri.getagrip.runner

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import run.nuri.getagrip.RecordingProgressorClient
import run.nuri.getagrip.ble.HostClock
import run.nuri.getagrip.ble.ProgressorConnectionState
import run.nuri.getagrip.engine.ForceSample
import run.nuri.getagrip.engine.GripSpec
import run.nuri.getagrip.engine.RunnerEvent
import run.nuri.getagrip.engine.RunnerPhase
import run.nuri.getagrip.engine.SessionPlan
import run.nuri.getagrip.engine.SetPlan
import run.nuri.getagrip.store.DeviceStore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/// **The republish policy** — the one rule that decides whether the runner screen costs one
/// recomposition a second or eighty.
///
/// `SessionRunner` is mutated by every force sample, so the whole screen's cost is set by how
/// coarsely `RunnerSession.publish()` re-exposes it. Two guards do that work and neither is
/// visible from the engine: the snapshot is a value assigned ONLY when it differs, and the
/// one field that genuinely steps faster than a second (rep progress) is published
/// separately so a view can depend on it alone.
///
/// The test drives real 80 Hz samples through the real funnel and counts republishes, which
/// is the only way to catch the regression that matters: someone adding a continuously
/// moving field to `RunnerSnapshot` turns every sample into a full-screen invalidation, and
/// nothing else in the suite would notice.
class RunnerSnapshotTests {

    private class FakeClock(var uptime: Double = 0.0) : HostClock {
        override fun wallSeconds(): Double = 1_000.0 + uptime
        override fun uptimeSeconds(): Double = uptime
    }

    /// 80 Hz, exactly like the device.
    private val sampleMicros: UInt = 12_500u
    private val sampleSeconds = 0.0125

    private class Harness {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val clock = FakeClock()
        val client = RecordingProgressorClient()
        val device = DeviceStore(client, scope = scope, clock = clock)
        val session: RunnerSession

        init {
            client.setState(ProgressorConnectionState.Connected)
            session = RunnerSession(
                plan = SessionPlan(
                    name = "Snapshot",
                    sets = listOf(SetPlan(grip = GripSpec(), repsPerSide = 2)),
                    holdSeconds = 10,
                    restSeconds = 20,
                    leadInSeconds = 0,
                ),
                routineName = "Snapshot",
                device = device,
                scope = scope,
                clock = clock,
            )
        }
    }

    /// Feeds one second of 80 Hz samples at a constant load, ticking the wall clock with
    /// them exactly as the real ticker would. Returns the harness for inspection.
    private fun pulling(seconds: Double, kg: Double): Harness {
        val harness = Harness()
        // No `begin()`: this test drives the funnel directly rather than waiting on the real
        // ticker, which is what makes it deterministic.
        harness.session.send(RunnerEvent.Start)
        var micros: UInt = 0u
        val count = (seconds / sampleSeconds).toInt()
        repeat(count) { i ->
            micros += sampleMicros
            harness.clock.uptime += sampleSeconds
            harness.session.send(RunnerEvent.Sample(ForceSample(kg, micros)))
            // The wall-clock beat is 10 Hz, not 80 — one tick per eight samples.
            if (i % 8 == 7) harness.session.tickNow()
        }
        return harness
    }

    /// Eighty samples a second must not cost eighty republishes. The snapshot's finest field
    /// is a WHOLE second, so a steady hold moves it about once a second and nothing else.
    @Test
    fun aSecondOfHoldingRepublishesTheSnapshotOnlyAHandfulOfTimes() {
        val harness = pulling(seconds = 1.0, kg = 10.0)
        assertTrue(
            harness.session.snapshot.phase is RunnerPhase.Working,
            "the rep has to be running or this test is measuring the armed phase",
        )
        // One for Armed → Working, one for the first whole second falling off the countdown,
        // and room for a boundary landing either side of the eightieth sample. Eighty would
        // mean the change guard is gone.
        assertTrue(
            harness.session.snapshotRevision in 1..5,
            "80 samples produced ${harness.session.snapshotRevision} republishes",
        )
    }

    /// **Rep progress is published SEPARATELY, and this is why.** It genuinely steps at up to
    /// ~33 Hz, so if it lived on the snapshot the guard above would be worthless — the whole
    /// screen would rebuild at that rate to move a 4 dp bar.
    @Test
    fun repProgressStepsFarMoreOftenThanTheSnapshotIsRepublished() {
        val harness = Harness()
        harness.session.send(RunnerEvent.Start)
        var micros: UInt = 0u
        var buckets = 0
        var lastBucket = harness.session.repProgressBucket
        repeat(80) { i ->
            micros += sampleMicros
            harness.clock.uptime += sampleSeconds
            harness.session.send(RunnerEvent.Sample(ForceSample(10.0, micros)))
            if (i % 8 == 7) harness.session.tickNow()
            if (harness.session.repProgressBucket != lastBucket) {
                buckets += 1
                lastBucket = harness.session.repProgressBucket
            }
        }
        // A 10 s hold advances 10 % in one second, so ten 1 % buckets.
        assertTrue(buckets >= 8, "rep progress only stepped $buckets times in a second")
        assertTrue(
            harness.session.snapshotRevision < buckets,
            "the snapshot republished ${harness.session.snapshotRevision} times against " +
                "$buckets progress steps — the two are not separated any more",
        )
    }

    @Test fun measuredProgressRetainsSubPercentStepsAndDoesNotAdvanceWithoutSamples() {
        val harness = pulling(seconds = 1.0, kg = 10.0)
        val before = harness.session.repProgress
        harness.session.send(RunnerEvent.Sample(ForceSample(10.0, 1_012_500u)))
        val after = harness.session.repProgress
        assertTrue(after > before && after - before < 0.01f)
        repeat(20) { harness.clock.uptime += 0.1; harness.session.tickNow() }
        assertEquals(after, harness.session.repProgress)
    }

    /// A snapshot is a VALUE: two identical readings compare equal, which is the whole
    /// mechanism the change guard rests on. Kotlin gives that away with `data class`, and a
    /// refactor to a plain class would silently take it back.
    @Test
    fun twoIdenticalSnapshotsCompareEqual() {
        val a = RunnerSnapshot(phase = RunnerPhase.Working(2), secondsShown = 7, setNumber = 1)
        val b = RunnerSnapshot(phase = RunnerPhase.Working(2), secondsShown = 7, setNumber = 1)
        assertEquals(a, b)
        assertTrue(a != b.copy(secondsShown = 6))
    }

    /// A paused session freezes the numeral, and it freezes the REPUBLISHES with it: ticks
    /// keep arriving from the 100 ms loop, and every one of them would otherwise invalidate
    /// the screen for a number that is deliberately not moving.
    @Test
    fun tickingWhilePausedRepublishesNothing() {
        val harness = pulling(seconds = 1.0, kg = 10.0)
        harness.session.send(RunnerEvent.Pause)
        val atPause = harness.session.snapshotRevision
        repeat(20) {
            harness.clock.uptime += 0.1
            harness.session.tickNow()
        }
        assertEquals(atPause, harness.session.snapshotRevision)
    }

    @Test fun gripSoundAnnouncesOnceAcrossRepeatedTicksPauseAndResume() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        var announcements = 0
        val cueSink = object : CueSink {
            override fun play(cue: run.nuri.getagrip.engine.RunnerCue) = Unit
            override fun gripChanged() { announcements++ }
        }
        val session = RunnerSession(plan = SessionPlan(sets = listOf(SetPlan(),
            SetPlan(grip = GripSpec(edgeMM = 10))), leadInSeconds = 0, setBreakSeconds = 0),
            routineName = "Changes", device = DeviceStore(RecordingProgressorClient(), scope = scope),
            scope = scope, clock = FakeClock(), cues = cueSink)
        session.send(RunnerEvent.Start)
        assertEquals(0, announcements)
        session.send(RunnerEvent.SkipSet)
        assertEquals(1, announcements)
        repeat(20) { session.send(RunnerEvent.Tick) }
        session.send(RunnerEvent.Pause)
        session.send(RunnerEvent.Resume)
        assertEquals(1, announcements)
        session.end()
    }
}
