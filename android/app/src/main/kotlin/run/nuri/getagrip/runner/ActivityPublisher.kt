// SPDX-License-Identifier: MPL-2.0
// Original contributions Copyright 2026 Nuri Bruner.

package run.nuri.getagrip.runner

import run.nuri.getagrip.engine.GripSpec
import run.nuri.getagrip.engine.Side

/// Where a session publishes its Live Update (iOS: a Live Activity from `RunnerSession`).
/// The app's is `AndroidActivityPublisher`.
///
/// **Push on STATE, never on the clock.** The card carries an absolute DEADLINE
/// (`endsAtEpochMillis`) and counts itself down, as `Text(timerInterval:)` does on iOS and
/// a notification chronometer does here — what makes a twenty-minute session affordable:
/// pushes only on real state changes (a rep ending, a hand swapping). Dedupe signatures
/// EXCLUDE the deadline, because `now + remaining` drifts on all ten publishes a second and
/// would spend the update budget in a minute.
///
/// **No force trace and no live kilograms**: the platform coalesces and throttles, and the
/// gauge produces ~80 samples a second. The card shows the rep's TARGET band — the one load
/// figure true between pushes.
interface ActivityPublisher {
    /// Whether a card is on screen; `RunnerSession` computes no update for a publisher that
    /// never started one.
    val isRunning: Boolean

    fun start(routineName: String, plannedReps: Int, setCount: Int, state: SessionActivityState)
    fun update(state: SessionActivityState)
    fun end()

    /// Over but not saved: the card says so while the summary is open, with the foreground
    /// service under it. A publisher without such a card simply ends; `end()` removes the
    /// finished card.
    fun showFinished(routineName: String) = end()
}

/// The default. Best-effort and silent.
object NoActivityPublisher : ActivityPublisher {
    override val isRunning: Boolean = false
    override fun start(routineName: String, plannedReps: Int, setCount: Int, state: SessionActivityState) = Unit
    override fun update(state: SessionActivityState) = Unit
    override fun end() = Unit
}

/// The phases that change what you do with your hands — `armed` included, with no clock.
///
/// One ladder with the runner screen (`RunnerTint.of`): steel resting or leading in, amber
/// when it is on you, bleu while the clock runs. Amber means *waiting on you*.
enum class SessionActivityPhase {
    leadIn,
    armed,
    pulling,
    releasing,
    resting,
    paused;

    val runsCountdown: Boolean
        get() = this == leadIn || this == pulling || this == resting
}

/// Exactly what the card draws, and nothing that moves continuously.
data class SessionActivityState(
    val grip: GripSpec,
    val side: Side,
    val phase: SessionActivityPhase,
    val setNumber: Int,
    val repPosition: Int,
    val targetLoKg: Double? = null,
    val targetHiKg: Double? = null,
    /// Wall-clock deadline the card counts down to. Null while armed (it waits on you, no
    /// timeout).
    val endsAtEpochMillis: Long? = null,
    /// The hold LENGTH, only while armed and drawn dimmed, so a still number is not read as
    /// a stopped countdown.
    val pendingSeconds: Int? = null,
)
