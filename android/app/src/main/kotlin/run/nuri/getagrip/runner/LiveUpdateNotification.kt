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
/// TRANSLATION NOTE (from Widget/SessionLiveActivity.swift): iOS renders the card from a
/// SwiftUI view in a separate widget process. Android's counterpart is an ongoing
/// notification built in-process — so the "view" is a handful of strings and one colour,
/// and the only way to test it without a device is to make that mapping a value.
/// `LiveUpdateNotification` below is then a mechanical `NotificationCompat` assembly with
/// no decisions left in it.
///
/// The layout mirrors the lock-screen card line for line: the phase WORD is the title
/// (what iOS draws in the phase tint), the grip line is the body, and the position line
/// — "Set 2 of 6 · Pull 7 of 36" — is the sub-text that sits in the header beside the app
/// name. The target band rides with the grip because it is the one load figure a surface
/// that updates on state changes can state honestly (see `SessionActivityState`), and
/// putting it beside the grip is what keeps it on the same line as the thing it applies to.
data class LiveUpdateContent(
    /// The phase word — plus, while armed, the length of the hold ahead. **Armed runs no
    /// clock at all**, so there is no deadline to hand a chronometer; iOS draws the hold
    /// length dimmed in the trailing column instead, and here it joins the title, which is
    /// the only place on a notification that is read before anything else.
    val title: String,
    /// The grip, with the rep's target band when it has one.
    val text: String,
    /// Where you are in the session. **"Set 1 of 1" is dropped** — a constant dressed up
    /// as a counter, and the pull count is what needs the room.
    val subText: String,
    /// API 36's promoted-ongoing strip: a few characters shown on the status bar chip.
    /// The phase word, because that is the one thing worth reading at that size.
    val shortCriticalText: String,
    /// The card's own background — **fixed hex, never a translucent wash of the accent.**
    /// Carried straight from `SessionActivity.Phase.cardTint`: a translucent amber under
    /// white text is a legibility coin-toss against whatever wallpaper is behind the lock
    /// screen, and this surface has one job — being read from across a room, mid-hang.
    val cardTintArgb: Int,
    /// When the clock currently running runs out, as a wall-clock instant, or null when
    /// nothing is counting. A DEADLINE and not a number of seconds, because a notification
    /// chronometer then ticks itself down with no further pushes from the app — the single
    /// choice that makes a twenty-minute session affordable.
    val chronometerEndsAtMillis: Long?,
    /// Whether the phase reads as "do this NOW" rather than "this is what's coming".
    val isActive: Boolean,
) {
    companion object {
        /// The colours are `SessionActivity.Phase.cardTint`, byte for byte. Opaque, because
        /// a notification background has nothing to blend with.
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

        /// `nowMillis` is passed in rather than read, because the deadline guard below is
        /// the one piece of this that has to be driven from a test.
        fun of(
            state: SessionActivityState,
            plannedReps: Int,
            setCount: Int,
            nowMillis: Long,
        ): LiveUpdateContent {
            val word = word(state.phase)
            // **`endsAt > now` is a correctness guard, not tidiness.** A chronometer handed
            // a deadline in the past counts UP from it, so a card nobody has pushed to in a
            // while would sit there claiming a rep has been running for four minutes. iOS
            // guards the same comparison because `Date.now...endsAt` traps outright. Paused
            // is excluded for the plain reason that a paused session has no clock running.
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

        /// The hand leads, because a notification has no room for the hand MARK the iOS
        /// card draws and losing "which hand" would make the line describe the wrong pull.
        /// `both` is left off: it is the default and says nothing.
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
/// Activity, and the same notification the `connectedDevice` foreground service runs in.
///
/// **Push on STATE, never on the clock.** Nothing here is called on a tick: the countdown
/// is a notification CHRONOMETER anchored to `setWhen(endsAtMillis)` and counting down, so
/// the system ticks it once a second with no work from us. `RunnerSession` pushes only
/// when its activity signature moves — a rep ending, a hand swapping — and
/// `AndroidActivityPublisher` drops anything that would redraw the same card.
///
/// **It cannot pulse, and nothing here pretends to.** A notification is an archived render
/// exactly as a Live Activity is; what it gets is a fresh render on every real push, so
/// the colour changes ON THE BEAT — armed, holding, rest — which is the only pulse worth
/// having and the only one the platform can give.
object LiveUpdateNotification {

    /// **Low importance, silent, and `setOnlyAlertOnce` on top of it.** The card updates a
    /// few times a minute for twenty minutes; a channel that could make a sound would turn
    /// a workout into a pager. It is also the reason the app can never disturb what you are
    /// listening to from this surface — there is no sound to duck.
    const val CHANNEL_ID = "session"

    /// Shared with the foreground service, so `startForeground` adopts the card the
    /// publisher already posted instead of flashing a placeholder first.
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
            // The app's own monochrome mark — the same one the reminder uses, and the same
            // drawing the palm and the icon are.
            .setSmallIcon(R.drawable.ic_launcher_monochrome)
            .setContentTitle(content.title)
            .setContentText(content.text)
            .setSubText(content.subText)
            .setContentIntent(openSession(context))
            .setOngoing(true)
            .setSilent(true)
            // The whole card is tinted, which is `activityBackgroundTint` on iOS. Colorized
            // is honoured for an ongoing FOREGROUND-SERVICE notification, which is exactly
            // what a connected session's card is; without the service it degrades to an
            // accent, which is why the tint is a full colour rather than a wash.
            .setColorized(true)
            .setColor(content.cardTintArgb)
            .setCategory(NotificationCompat.CATEGORY_WORKOUT)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            // **Never alert on an update.** Ten pushes across a session, each one a state
            // change; any of them re-alerting would be the app shouting about its own
            // bookkeeping.
            .setOnlyAlertOnce(true)

        val endsAt = content.chronometerEndsAtMillis
        if (endsAt != null) {
            // THE CLOCK, and the app's whole update budget in three lines. `setWhen` is the
            // anchor, `usesChronometer` makes the header a running timer instead of a
            // timestamp, and `chronometerCountDown` runs it backwards — the twin of
            // `Text(timerInterval:countsDown: true)`. Nothing pushes it; the system ticks it.
            builder.setWhen(endsAt).setUsesChronometer(true).setChronometerCountDown(true)
            builder.setShowWhen(true)
        } else {
            // Armed, paused, or finished: no clock. Showing `when` here would put a
            // wall-clock time where a countdown was a second ago, which reads as a stopped
            // timer rather than as no timer.
            builder.setShowWhen(false).setUsesChronometer(false)
        }

        if (Build.VERSION.SDK_INT >= 36) {
            // **API 36's Live Update.** A promoted ongoing notification is pinned to the
            // status bar and the lock screen with its own compact chip — the closest thing
            // Android has to the Dynamic Island, and the reason this feature is shaped like
            // a notification at all. Below 36 the same card is posted unpromoted, which is
            // an ordinary ongoing notification and still exactly what the session needs.
            builder.setRequestPromotedOngoing(true)
            builder.setShortCriticalText(content.shortCriticalText)
        }

        return builder.build()
    }

    /// Tapping the card returns to the session. `singleTask` plus CLEAR_TOP means the
    /// running Activity is brought forward rather than a second one created on top of it.
    private fun openSession(context: Context): PendingIntent = PendingIntent.getActivity(
        context,
        0,
        Intent(context, MainActivity::class.java)
            .setFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )
}
