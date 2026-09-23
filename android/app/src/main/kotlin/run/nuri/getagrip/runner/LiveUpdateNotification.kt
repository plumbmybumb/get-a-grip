// SPDX-License-Identifier: MPL-2.0
// Original contributions Copyright 2026 Nuri Bruner.

package run.nuri.getagrip.runner

import run.nuri.getagrip.ui.units.WeightUnits

import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import run.nuri.getagrip.MainActivity
import run.nuri.getagrip.R
import run.nuri.getagrip.engine.Fmt
import run.nuri.getagrip.engine.L10n
import run.nuri.getagrip.engine.Side

/// **Everything the Live Update draws, as a pure value.**
///
/// TRANSLATION NOTE (Widget/SessionLiveActivity.swift): iOS renders the card in a widget
/// process; Android posts an ongoing notification in-process, so the "view" is a few
/// strings and one colour, made a value to be testable without a device.
/// `LiveUpdateNotification` is then mechanical `NotificationCompat` assembly.
///
/// It mirrors the lock-screen card: the phase WORD is the title, the grip line the body,
/// and "Set 2 of 6 · Pull 7 of 36" the header sub-text. The target band rides with the
/// grip: it is the one load figure a state-pushed surface can state honestly (see
/// `SessionActivityState`), on the line it applies to.
data class LiveUpdateContent(
    /// The phase word — plus, while armed, the length of the hold ahead. **Armed runs no
    /// clock**, so there is no chronometer deadline; iOS draws the length dimmed, here it
    /// joins the title, the first thing read.
    val title: String,
    /// The grip, with the rep's target band when it has one.
    val text: String,
    /// Where you are in the session. **"Set 1 of 1" is dropped**: a constant dressed as a
    /// counter, taking the pull count's room.
    val subText: String,
    /// API 36's promoted-ongoing status-bar chip: the phase word, the one thing worth
    /// reading at that size.
    val shortCriticalText: String,
    /// The card's background — **fixed hex, never a translucent wash of the accent**
    /// (`SessionActivity.Phase.cardTint`). Translucent amber under white text is a
    /// legibility coin-toss against the wallpaper, and this surface must be read across a
    /// room, mid-hang.
    val cardTintArgb: Int,
    /// When the running clock runs out, as a wall-clock instant, or null. A DEADLINE, not
    /// seconds, so the chronometer ticks itself with no further pushes — what makes a
    /// twenty-minute session affordable.
    val chronometerEndsAtMillis: Long?,
    /// Whether the phase reads as "do this NOW" rather than "this is what's coming".
    val isActive: Boolean,
) {
    companion object {
        /// `SessionActivity.Phase.cardTint`, byte for byte. Opaque: a notification
        /// background has nothing to blend with.
        const val TINT_CALM = 0xFF161A20.toInt() // near-black slate: lead-in and rest
        const val TINT_ARMED = 0xFF3A2408.toInt() // deep amber: waiting on YOU
        const val TINT_PULLING = 0xFF0E2740.toInt() // deep bleu: the clock is running
        const val TINT_PAUSED = 0xFF24282F.toInt()

        fun word(phase: SessionActivityPhase): String = when (phase) {
            SessionActivityPhase.leadIn -> L10n.tr("Get ready")
            SessionActivityPhase.armed -> L10n.tr("Pull now")
            SessionActivityPhase.pulling -> L10n.tr("Holding")
            SessionActivityPhase.releasing -> L10n.tr("Let go")
            SessionActivityPhase.resting -> L10n.tr("Rest")
            SessionActivityPhase.paused -> L10n.tr("Paused")
        }

        fun cardTint(phase: SessionActivityPhase): Int = when (phase) {
            SessionActivityPhase.leadIn, SessionActivityPhase.releasing, SessionActivityPhase.resting -> TINT_CALM
            SessionActivityPhase.armed -> TINT_ARMED
            SessionActivityPhase.pulling -> TINT_PULLING
            SessionActivityPhase.paused -> TINT_PAUSED
        }

        /// `nowMillis` is passed in so the deadline guard below can be driven from a test.
        fun of(
            state: SessionActivityState,
            plannedReps: Int,
            setCount: Int,
            nowMillis: Long,
        ): LiveUpdateContent {
            val word = word(state.phase)
            // **`endsAt > now` is a correctness guard.** A chronometer given a past
            // deadline counts UP, so a stale card would claim a rep running for four
            // minutes (iOS guards it because `Date.now...endsAt` traps). Paused has no
            // clock running.
            val endsAt = state.endsAtEpochMillis
                ?.takeIf { it > nowMillis && state.phase.runsCountdown }
            val pending = state.pendingSeconds.takeIf { state.phase == SessionActivityPhase.armed }
            return LiveUpdateContent(
                title = if (endsAt == null && pending != null) {
                    L10n.tr("%s · %ds", word, pending)
                } else {
                    word
                },
                text = gripLine(state),
                subText = positionLine(state, plannedReps, setCount),
                shortCriticalText = word,
                cardTintArgb = cardTint(state.phase),
                chronometerEndsAtMillis = endsAt,
                isActive = state.phase == SessionActivityPhase.pulling ||
                    state.phase == SessionActivityPhase.armed,
            )
        }

        /// The hand leads: there is no room for the iOS hand MARK, and losing "which hand"
        /// describes the wrong pull. `both` is the default and says nothing, so it is left
        /// off.
        private fun gripLine(state: SessionActivityState): String {
            val grip = if (state.side == Side.both) {
                state.grip.line
            } else {
                L10n.tr("%s · %s", state.side.displayName, state.grip.line)
            }
            val lo = state.targetLoKg
            val hi = state.targetHiKg
            if (lo == null || hi == null) return grip
            return WeightUnits.tr("%s · %s–%s kg", grip, WeightUnits.number(lo, 1), WeightUnits.number(hi, 1))
        }

        private fun positionLine(
            state: SessionActivityState,
            plannedReps: Int,
            setCount: Int,
        ): String {
            val pulls = L10n.tr("Pull %d of %d", state.repPosition, plannedReps)
            if (setCount <= 1) return pulls
            return L10n.tr("Set %d of %d · %s", state.setNumber, setCount, pulls)
        }
    }
}

/// Builds the ongoing notification that IS the Live Update — the twin of iOS's Live
/// Activity, and the notification the `connectedDevice` foreground service runs in.
///
/// **Push on STATE, never on the clock.** The countdown is a CHRONOMETER anchored by
/// `setWhen(endsAtMillis)`, ticked by the system. `RunnerSession` pushes only when its
/// activity signature moves, and `AndroidActivityPublisher` drops identical cards.
///
/// **It cannot pulse.** A notification is an archived render like a Live Activity; each
/// real push re-renders, so the colour changes ON THE BEAT — armed, holding, rest — the
/// only pulse the platform can give.
object LiveUpdateNotification {

    /// **Low importance, silent, and `setOnlyAlertOnce`.** The card updates a few times a
    /// minute for twenty minutes; a sounding channel would turn a workout into a pager, and
    /// with no sound there is nothing to duck.
    const val CHANNEL_ID = "session"

    /// Shared with the foreground service, so `startForeground` adopts the posted card
    /// instead of flashing a placeholder.
    const val NOTIFICATION_ID = 4207

    fun ensureChannel(context: Context) {
        val manager = context.getSystemService(android.app.NotificationManager::class.java)
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        manager.createNotificationChannel(
            android.app.NotificationChannel(
                CHANNEL_ID,
                L10n.tr("Session"),
                android.app.NotificationManager.IMPORTANCE_LOW,
            ).apply {
                setShowBadge(false)
                enableVibration(false)
                setSound(null, null)
            },
        )
    }

    fun build(context: Context, content: LiveUpdateContent): Notification {
        ensureChannel(context)
        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            // The app's monochrome mark, as on the reminder.
            .setSmallIcon(R.drawable.ic_launcher_monochrome)
            .setContentTitle(content.title)
            .setContentText(content.text)
            .setSubText(content.subText)
            .setContentIntent(openSession(context))
            .setOngoing(true)
            .setSilent(true)
            // Whole-card tint (`activityBackgroundTint` on iOS). Colorized is honoured for
            // an ongoing FOREGROUND-SERVICE notification, which a connected session's card
            // is; otherwise it degrades to an accent, hence a full colour rather than a
            // wash.
            .setColorized(true)
            .setColor(content.cardTintArgb)
            .setCategory(NotificationCompat.CATEGORY_WORKOUT)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            // **Never alert on an update**: each push is a state change, and re-alerting
            // would be the app shouting about its own bookkeeping.
            .setOnlyAlertOnce(true)

        val endsAt = content.chronometerEndsAtMillis
        if (endsAt != null) {
            // THE CLOCK: `setWhen` anchors, `usesChronometer` makes the header a timer,
            // `chronometerCountDown` runs it backwards — the twin of
            // `Text(timerInterval:countsDown: true)`. The system ticks it.
            builder.setWhen(endsAt).setUsesChronometer(true).setChronometerCountDown(true)
            builder.setShowWhen(true)
        } else {
            // Armed, paused, or finished: no clock. Showing `when` would put a wall-clock
            // time where a countdown was, which reads as a stopped timer.
            builder.setShowWhen(false).setUsesChronometer(false)
        }

        if (Build.VERSION.SDK_INT >= 36) {
            // **API 36's Live Update.** A promoted ongoing notification is pinned to status
            // bar and lock screen with its own chip — Android's closest thing to the
            // Dynamic Island. Below 36 it posts unpromoted, still what the session needs.
            builder.setRequestPromotedOngoing(true)
            builder.setShortCriticalText(content.shortCriticalText)
        }

        return builder.build()
    }

    /// The card for a session FINISHED but not yet saved — see
    /// `AndroidActivityPublisher.showFinished`. Calm slate: nothing is asked of the hands.
    fun finished(context: Context, routineName: String): Notification {
        ensureChannel(context)
        return NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_monochrome)
            .setContentTitle(L10n.tr("Session done"))
            .setContentText(L10n.tr("Open Get a Grip to save it."))
            .setSubText(routineName)
            .setContentIntent(openSession(context))
            .setOngoing(true)
            .setSilent(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .setUsesChronometer(false)
            .setColorized(true)
            .setColor(LiveUpdateContent.TINT_CALM)
            .setCategory(NotificationCompat.CATEGORY_WORKOUT)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .build()
    }

    /// The card a STRAY service start runs in before it stops — see
    /// `SessionForegroundService.onStartCommand`. Silent, titled with the app name: it may
    /// flash for a frame and must not claim a phase.
    fun placeholder(context: Context): Notification {
        ensureChannel(context)
        return NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_monochrome)
            .setContentTitle(context.getString(R.string.app_name))
            .setContentIntent(openSession(context))
            .setOngoing(true)
            .setSilent(true)
            .setShowWhen(false)
            .setCategory(NotificationCompat.CATEGORY_WORKOUT)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    /// Tapping returns to the session: `singleTask` plus CLEAR_TOP brings the running
    /// Activity forward rather than stacking another.
    private fun openSession(context: Context): PendingIntent = PendingIntent.getActivity(
        context,
        0,
        Intent(context, MainActivity::class.java)
            .setFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )
}
