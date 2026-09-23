// SPDX-License-Identifier: MPL-2.0
// Original contributions Copyright 2026 Nuri Bruner.

package run.nuri.getagrip.runner

import android.content.Context
import androidx.compose.runtime.snapshotFlow
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.drop
import run.nuri.getagrip.ui.units.WeightUnit
import run.nuri.getagrip.ui.units.WeightUnits
import androidx.core.app.NotificationManagerCompat
import run.nuri.getagrip.engine.GripSpec
import run.nuri.getagrip.engine.Side

/// The real `ActivityPublisher`: the Live Update, as an ongoing notification.
///
/// TRANSLATION NOTE (Sources/Runner/SessionActivityController.swift): iOS requests and
/// updates an `ActivityKit.Activity`; here the same calls post, re-post and cancel one
/// notification. The controller's three SESSION rules carry over unchanged:
///
/// - **Publish BEFORE starting.** `RunnerSession.begin()` publishes first, or the first
///   card says "Pull 0 of 12" with no countdown.
/// - **Dedupe.** An update that draws the same card is dropped.
/// - **End immediately.** A card saying "Pull" after you finished is worse than none.
///
/// Best-effort: a card that cannot post (notifications off or never permitted) must not
/// disturb the workout. `isRunning` does NOT track VISIBILITY — the foreground service
/// needs a current card either way, and the session must keep streaming.
class AndroidActivityPublisher(context: Context) : ActivityPublisher {

    private val appContext = context.applicationContext
    private val notifications = NotificationManagerCompat.from(appContext)

    override var isRunning: Boolean = false
        private set

    private var plannedReps: Int = 0
    private var setCount: Int = 0

    /// The last card posted, compared WITHOUT its deadline — the second gate after
    /// `RunnerSession`'s own signature, as iOS keeps `lastPushed`. See `ActivityPublisher`
    /// for why the drifting deadline must be excluded.
    private var lastPosted: Signature? = null
    private var latestState: SessionActivityState? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var unitObservation: Job? = null

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
        unitObservation = scope.launch {
            snapshotFlow { WeightUnits.current }.drop(1).collect {
                if (isRunning) latestState?.let(::post)
            }
        }
    }

    override fun update(state: SessionActivityState) {
        if (!isRunning) return
        post(state)
    }

    override fun end() {
        if (!isRunning) return
        isRunning = false
        unitObservation?.cancel()
        unitObservation = null
        latestState = null
        lastPosted = null
        SessionForegroundService.pending = null
        notifications.cancel(LiveUpdateNotification.NOTIFICATION_ID)
    }

    /// "Session done — open to save it", silent, no clock. Replaces the live card under the
    /// SAME id, so the foreground service keeps running in it
    /// (`SessionForegroundService.pending`) until Save or Discard ends both.
    override fun showFinished(routineName: String) {
        if (!isRunning) return
        unitObservation?.cancel()
        unitObservation = null
        latestState = null
        lastPosted = null
        val notification = LiveUpdateNotification.finished(appContext, routineName)
        SessionForegroundService.pending = notification
        notifications.notify(LiveUpdateNotification.NOTIFICATION_ID, notification)
    }

    private fun post(state: SessionActivityState) {
        latestState = state
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
        // Stashed BEFORE posting, so a service starting in the same turn adopts this card —
        // see `SessionForegroundService`.
        SessionForegroundService.pending = notification
        // Silently dropped without `POST_NOTIFICATIONS` — the correct failure: session and
        // service go on, only the card is missing.
        notifications.notify(LiveUpdateNotification.NOTIFICATION_ID, notification)
    }

    /// Everything that changes the card, nothing that moves continuously: no deadline (see
    /// `lastPosted`), no live load.
    private data class Signature(
        val unit: WeightUnit,
        val grip: GripSpec,
        val side: Side,
        val phase: SessionActivityPhase,
        val setNumber: Int,
        val repPosition: Int,
        val targetLoKg: Double?,
        val targetHiKg: Double?,
        val pendingSeconds: Int?,
        /// Whether a clock runs at all: a rep ending with no rest owed goes from a deadline
        /// to none within one phase.
        val hasDeadline: Boolean,
    ) {
        constructor(state: SessionActivityState) : this(
            unit = WeightUnits.current,
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
