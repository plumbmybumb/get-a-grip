// SPDX-License-Identifier: MPL-2.0
// Original contributions Copyright 2026 Nuri Bruner.

package run.nuri.getagrip.ui.criticalforce

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.yield
import run.nuri.getagrip.ble.HostClock
import run.nuri.getagrip.ble.SystemHostClock
import run.nuri.getagrip.engine.CriticalForceOutcome
import run.nuri.getagrip.engine.CriticalForceProtocol
import run.nuri.getagrip.engine.CriticalForceTest
import run.nuri.getagrip.engine.CriticalForceTrace
import run.nuri.getagrip.engine.RunnerCue
import run.nuri.getagrip.runner.CueSink
import kotlin.math.ceil
import kotlin.math.floor

/// Drives a `CriticalForceTest` from the gauge and the clock, and publishes only what the
/// screen draws.
///
/// The test is a plain field and every published value is written only when it CHANGES.
/// Readings arrive about 80 times a second, and an unguarded mirror would recompose the
/// screen at the sample rate (the lesson of `MaxMeasurement` and `RunnerSnapshot`).
///
/// TRANSLATION NOTE (from Sources/UI/CriticalForce/CriticalForceSession.swift): the ticker
/// is a coroutine on the caller's scope rather than a `Task`, and the clock is
/// `HostClock.wallSeconds` — the same clock `DeviceStore.TracePoint.t` is on, which is the
/// "two clocks, one epoch" contract `CriticalForceTest` states. `publishes` is a test seam,
/// as on `MaxMeasurement`.
@Stable
class CriticalForceSession(
    private val scope: CoroutineScope,
    private val cues: CueSink,
    private val clock: HostClock = SystemHostClock,
    /// How often the metronome is consulted. The iOS ticker's 50 ms.
    private val tickMillis: Long = 50L,
) {
    var phase: CriticalForceTest.Phase by mutableStateOf(CriticalForceTest.Phase.Armed)
        private set

    /// Whole seconds left in the current window: a clock.
    var secondsLeft: Int by mutableIntStateOf(7)
        private set

    /// 1-based, for "Pull 9 of 24".
    var pullNumber: Int by mutableIntStateOf(1)
        private set

    /// One entry per pull started: locked once its bell rings, live while it runs.
    var repMeans: List<Double?> by mutableStateOf(emptyList())
        private set

    var canFinishEarly: Boolean by mutableStateOf(false)
        private set

    /// A TEST SEAM and nothing else: how many times a published value actually changed.
    var publishes: Int = 0
        private set

    var test = CriticalForceTest()
        private set

    private var ticker: Job? = null
    private var cuesRunning = false

    val proto: CriticalForceProtocol get() = test.proto

    /// Arm: from now on the first pull over `startKg` starts rep 1.
    fun arm() {
        test = CriticalForceTest()
        publish()
        // A frame later, like the runner: starting the audio track and the haptics put tens
        // of milliseconds between the Start tap and the screen. Nothing sounds until the
        // first pull.
        if (!cuesRunning) {
            cuesRunning = true
            scope.launch {
                yield()
                if (cuesRunning) cues.begin()
            }
        }
        ticker?.cancel()
        ticker = scope.launch {
            while (isActive) {
                tick()
                delay(tickMillis)
            }
        }
    }

    /// ~80 readings a second. Only what a single reading can change is republished here
    /// (the phase, when the first pull arms the test); the bars and the clock follow on the
    /// 20 Hz tick, which is as often as either can visibly move.
    fun receive(kg: Double, t: Double) {
        play(test.sample(kg, t))
        if (publishPhase()) publishes += 1
    }

    /// Hold-to-stop. Keeps a result past `minRepsForResult`, voids before.
    fun stop() {
        play(test.stop(clock.wallSeconds()))
        publish()
    }

    fun interrupt(reason: CriticalForceTest.VoidReason) {
        play(test.interrupt(reason, clock.wallSeconds()))
        publish()
    }

    /// Everything off. Idempotent; called on every way out of the screen.
    fun end() {
        ticker?.cancel()
        ticker = null
        if (cuesRunning) {
            cues.end()
            cuesRunning = false
        }
    }

    /// The finished test's outcome and its trace, encoded for storage.
    fun outcome(): Pair<CriticalForceOutcome, ByteArray>? {
        val result = test.result() ?: return null
        return result to CriticalForceTrace.encode(test.points)
    }

    /// One metronome step. Public for tests; the ticker calls it every `tickMillis`.
    fun tick() {
        play(test.tick(clock.wallSeconds()))
        publish()
        when (test.phase) {
            CriticalForceTest.Phase.Finished, is CriticalForceTest.Phase.Voided -> {
                ticker?.cancel()
                ticker = null
            }
            else -> Unit
        }
    }

    // MARK: -

    /// The routine runner's cue vocabulary, so the test sounds like the rest of the app: the
    /// go tone on the pull, the rep-complete tone on the bell, the rest ticks between.
    private fun play(fired: List<CriticalForceTest.Cue>) {
        for (cue in fired) {
            cues.play(
                when (cue) {
                    is CriticalForceTest.Cue.Pull -> RunnerCue.RepStarted
                    is CriticalForceTest.Cue.LetGo -> RunnerCue.RepEnded(completed = true)
                    is CriticalForceTest.Cue.Countdown -> RunnerCue.RestTick(cue.seconds)
                    CriticalForceTest.Cue.Finished -> RunnerCue.SessionCompleted
                    CriticalForceTest.Cue.Voided -> RunnerCue.ConnectionLost
                }
            )
        }
    }

    private fun publishPhase(): Boolean {
        val p = test.phase
        if (phase == p) return false
        phase = p
        return true
    }

    private fun publish() {
        var changed = publishPhase()
        val p = test.phase
        val left = ceil(test.remaining(clock.wallSeconds())).toInt()
        if (secondsLeft != left) { secondsLeft = left; changed = true }
        val pull = when (p) {
            is CriticalForceTest.Phase.Pulling -> p.rep + 1
            is CriticalForceTest.Phase.Resting -> p.afterRep + 2
            CriticalForceTest.Phase.Armed -> 1
            else -> test.repsRun
        }
        if (pullNumber != pull) { pullNumber = pull; changed = true }
        // Rounded to a tenth before comparing, so the live bar redraws when the average
        // visibly moves, not on every one of 80 readings a second.
        val means = test.displayMeans().map { value -> value?.let { roundedTenth(it) } }
        if (repMeans != means) { repMeans = means; changed = true }
        val early = test.canFinishEarly
        if (canFinishEarly != early) { canFinishEarly = early; changed = true }
        if (changed) publishes += 1
    }

    /// Swift's `(x * 10).rounded() / 10`: ties away from zero.
    private fun roundedTenth(value: Double): Double {
        val scaled = value * 10
        val rounded = if (scaled < 0) -floor(-scaled + 0.5) else floor(scaled + 0.5)
        return rounded / 10
    }
}
