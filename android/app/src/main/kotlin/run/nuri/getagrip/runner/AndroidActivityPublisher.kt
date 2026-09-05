// SPDX-License-Identifier: MPL-2.0
// Original contributions Copyright 2026 Nuri Bruner.

package run.nuri.getagrip.runner

import android.content.Context
import androidx.core.app.NotificationManagerCompat
import run.nuri.getagrip.engine.GripSpec
import run.nuri.getagrip.engine.Side

/// The real `ActivityPublisher`: the Live Update, as an ongoing notification.
///
/// TRANSLATION NOTE (from Sources/Runner/SessionActivityController.swift): iOS requests an
/// `ActivityKit.Activity` and updates it; here the same three calls post, re-post and
/// cancel one notification. The three rules that make the iOS controller work are carried
/// over unchanged, because they are about the SESSION rather than about ActivityKit:
///
/// - **Publish BEFORE starting.** `RunnerSession.begin()` calls `publish()` first, so the
///   snapshot the first card reads is the real one. Without it the very first card went
///   out saying "Pull 0 of 12" with no countdown and sat there until the next phase change.
/// - **Dedupe.** An update that would draw the same card is dropped rather than spent.
/// - **End immediately.** A card still saying "Pull" after you have finished is worse than
///   no card at all.
///
/// Everything here is best-effort: a card that cannot be posted (notifications off, the
/// permission never granted) must never disturb a workout that is already under way. Note
/// that `isRunning` deliberately does NOT track whether the notification is VISIBLE — the
/// foreground service needs a current card to run in whether or not the user can see it,
/// and a session whose notifications are off still has to keep streaming.
class AndroidActivityPublisher(context: Context) : ActivityPublisher {

    private val appContext = context.applicationContext
    private val notifications = NotificationManagerCompat.from(appContext)

    override var isRunning: Boolean = false
        private set

    private var plannedReps: Int = 0
    private var setCount: Int = 0

    /// The last card actually posted, compared WITHOUT its deadline. `RunnerSession`
    /// already dedupes on its own signature, and this is the second gate for the same
    /// reason iOS keeps `lastPushed`: `endsAtEpochMillis` is `now + remaining`, so it
    /// drifts on every publish, and a comparison that included it would repost ten times a
    /// second and spend a whole session's budget in the first minute. Recomputing the
    /// deadline only when the phase or the rep actually moved is also the CORRECT moment —
    /// that is exactly when a new countdown should start.
    private var lastPosted: Signature? = null

    override fun start(
        routineName: String,
        plannedReps: Int,
        setCount: Int,
        state: SessionActivityState,
    ) {
        if (isRunning) return
        this.plannedReps = plannedReps
        this.setCount = setCount
        isRunning = true
        post(state)
    }

    override fun update(state: SessionActivityState) {
        if (!isRunning) return
        post(state)
    }

    override fun end() {
        if (!isRunning) return
        isRunning = false
        lastPosted = null
        SessionForegroundService.pending = null
        notifications.cancel(LiveUpdateNotification.NOTIFICATION_ID)
    }

    private fun post(state: SessionActivityState) {
        val signature = Signature(state)
        if (signature == lastPosted) return
        lastPosted = signature
        val content = LiveUpdateContent.of(
            state = state,
            plannedReps = plannedReps,
            setCount = setCount,
            nowMillis = System.currentTimeMillis(),
        )
        val notification = LiveUpdateNotification.build(appContext, content)
        // Stashed BEFORE posting, so a foreground service starting in the same turn adopts
        // this card rather than flashing a placeholder — see `SessionForegroundService`.
        SessionForegroundService.pending = notification
        // Silently dropped by the system when `POST_NOTIFICATIONS` was never granted. That
        // is the correct failure: the session goes on, the service goes on, and only the
        // card is missing.
        notifications.notify(LiveUpdateNotification.NOTIFICATION_ID, notification)
    }

    /// Everything that changes the card, and nothing that moves continuously. The deadline
    /// is excluded on purpose (see `lastPosted`); the live load never reaches this surface
    /// at all, because the platform coalesces and the gauge produces ~80 samples a second.
    private data class Signature(
        val grip: GripSpec,
        val side: Side,
        val phase: SessionActivityPhase,
        val setNumber: Int,
        val repPosition: Int,
        val targetLoKg: Double?,
        val targetHiKg: Double?,
        val pendingSeconds: Int?,
        /// Whether a clock is running at all. A phase change already implies it, but a rep
        /// that ends with no rest owed goes from a deadline to none inside one phase.
        val hasDeadline: Boolean,
    ) {
        constructor(state: SessionActivityState) : this(
            grip = state.grip,
            side = state.side,
            phase = state.phase,
            setNumber = state.setNumber,
            repPosition = state.repPosition,
            targetLoKg = state.targetLoKg,
            targetHiKg = state.targetHiKg,
            pendingSeconds = state.pendingSeconds,
            hasDeadline = state.endsAtEpochMillis != null,
        )
    }
}
