// SPDX-License-Identifier: MPL-2.0
// Original contributions Copyright 2026 Nuri Bruner.

package run.nuri.getagrip.runner

import run.nuri.getagrip.engine.GripSpec
import run.nuri.getagrip.engine.Side

/// **PHASE 6 SEAM.** iOS publishes a Live Activity from `RunnerSession`; Android's
/// counterpart is a Live Update notification, which needs its own channel, its own
/// `POST_NOTIFICATIONS` permission and its own hardware verification. None of that exists
/// yet — but the RULE it has to obey does, and it is the whole reason this interface is
/// declared now rather than later.
///
/// **Push on STATE, never on the clock.** The card carries an absolute DEADLINE
/// (`endsAtEpochMillis`) and counts itself down from it, exactly as
/// `Text(timerInterval:)` does on iOS and as a `Notification` chronometer does here. That
/// single choice is what makes a twenty-minute session affordable: the app pushes only on
/// real state changes — a rep ending, a hand swapping — instead of once a second.
/// `RunnerSession` dedupes on a signature that EXCLUDES the deadline, because the deadline
/// is `now + remaining` and drifts on all ten publishes a second.
///
/// **No force trace and no live kilograms**, for the same reason: the platform coalesces
/// and throttles, and the gauge produces ~80 samples a second. The card shows the rep's
/// TARGET band instead — the one load figure that stays true between pushes.
interface ActivityPublisher {
    /// Whether a card is actually on screen. `RunnerSession` will not compute an update for
    /// a publisher that never started one.
    val isRunning: Boolean

    fun start(routineName: String, plannedReps: Int, setCount: Int, state: SessionActivityState)
    fun update(state: SessionActivityState)
    fun end()
}

/// The default. Best-effort and silent: an activity that cannot start must never disturb a
/// workout that is already under way.
object NoActivityPublisher : ActivityPublisher {
    override val isRunning: Boolean = false
    override fun start(routineName: String, plannedReps: Int, setCount: Int, state: SessionActivityState) = Unit
    override fun update(state: SessionActivityState) = Unit
    override fun end() = Unit
}

/// The runner's phases collapsed to the ones that change what you do with your hands — and
/// `armed` is one of them, with no clock at all.
///
/// One ladder, shared with the runner screen (`RunnerTint.of`): steel while resting or
/// leading in, amber the moment it is on you, bleu while the clock runs. Blue is "pulling",
/// not yellow — amber means *waiting on you*.
enum class SessionActivityPhase {
    leadIn,
    armed,
    pulling,
    resting,
    paused,
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
    /// Wall-clock deadline the card counts down to on its own. Null while armed — armed
    /// waits on you, with no timeout, by design.
    val endsAtEpochMillis: Long? = null,
    /// The hold LENGTH, sent only while armed and drawn dimmed, so a number sitting still
    /// cannot be read as a countdown that has stopped.
    val pendingSeconds: Int? = null,
)
