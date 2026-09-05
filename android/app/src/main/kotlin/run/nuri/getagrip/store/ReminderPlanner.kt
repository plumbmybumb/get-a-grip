// SPDX-License-Identifier: MPL-2.0
// Original contributions Copyright 2026 Nuri Bruner.

package run.nuri.getagrip.store

import android.Manifest
import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import run.nuri.getagrip.MainActivity
import run.nuri.getagrip.R
import run.nuri.getagrip.engine.L10n
import run.nuri.getagrip.engine.ReminderTime
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.UUID

/// Wipe-and-reschedule local-notification planning for the daily ritual.
///
/// Deterministic `doigt.routine.<uuid>.<slot>` identifiers plus a full replan on every
/// change make scheduling idempotent — there is no incremental state to corrupt, and
/// re-adding an identifier replaces the alarm in place, so editing 08:00 → 09:00 moves
/// one reminder rather than accumulating two.
///
/// TRANSLATION NOTE (Sources/Store/ReminderPlanner.swift): `requests(for:)` is unchanged,
/// character for character in its rules — it is the pure half and the whole thing the
/// tests exercise. What changed is everything under it:
///
/// - `UNUserNotificationCenter` becomes `AlarmManager` + a `BroadcastReceiver`, because
///   Android has no "fire this notification at 08:00 daily" primitive. **`setWindow` with
///   a 15-minute window, never `setExactAndAllowWhileIdle`**: an exact alarm needs
///   `SCHEDULE_EXACT_ALARM`, a permission Google Play gates to alarm-clock and calendar
///   apps, and a habit nudge that insists on the exact minute is the same overreach as
///   `.timeSensitive` was on iOS — the reason iOS uses `.active` rather than piercing
///   Focus.
/// - A repeating iOS `UNCalendarNotificationTrigger` becomes a ONE-SHOT alarm that the
///   receiver reschedules for tomorrow after it fires. Android's own `setRepeating` is
///   inexact and un-cancellable per occurrence, and rescheduling on fire is what lets a
///   session finished at 18:00 suppress the 19:00 slot for TODAY and restore it tomorrow.
/// - The identifier can't be handed to the OS, so it is hashed into a stable request code
///   (`requestCode`) that names the `PendingIntent`.
object ReminderPlanner {

    /// One routine's reminder settings, flattened to value data so the whole plan can be
    /// computed without touching a database row.
    data class RoutinePlanInput(
        val id: UUID,
        val name: String,
        val reminders: List<ReminderTime>,
        val enabled: Boolean,
        /// How many sessions today still owes. Zero means the day is already met and
        /// every one of this routine's remaining slots is suppressed.
        ///
        /// The whole point of the ritual is that the app stops nagging once you have
        /// done the thing. A reminder that fires after your second session of the day is
        /// the app failing to notice you succeeded, and it is exactly the kind of thing
        /// that gets notifications turned off for good.
        val outstandingToday: Int = 1,
    )

    /// A request, fully resolved but not yet handed to the system — which is what makes
    /// the planning testable without an `AlarmManager`.
    ///
    /// `hour`/`minute` stand in for Swift's `DateComponents`: those are the only two
    /// fields the iOS trigger ever set, and naming them keeps the type free of a
    /// platform date class.
    data class PlannedReminder(
        val identifier: String,
        val title: String,
        val body: String,
        val hour: Int,
        val minute: Int,
    ) {
        /// `AlarmManager` addresses an alarm by its `PendingIntent`, and a `PendingIntent`
        /// is identified by its request code — so the content-keyed identifier has to
        /// collapse to an Int. `hashCode` over a string that already contains a UUID is
        /// stable across processes (String's hash is specified) and its collision domain
        /// is one app's own alarms.
        val requestCode: Int get() = identifier.hashCode()
    }

    /// The namespace we own. Everything with this prefix is ours to cancel on a replan;
    /// anything without it belongs to another feature and is left alone.
    ///
    /// Kept as `doigt.` rather than renamed with the app: it is the iOS identity, the
    /// scheme is shared vocabulary between the two ports, and nothing user-visible reads
    /// it.
    const val identifierPrefix = "doigt.routine."

    /// Content-keyed on both halves: the routine's UUID survives an undo-delete (which
    /// restores the original id), and `slot` is derived from the TIME, so a slot moved
    /// from 08:00 to 09:00 replaces its own request instead of leaving an orphan firing
    /// at the old hour forever.
    fun identifier(routine: UUID, slot: ReminderTime): String =
        "$identifierPrefix${routine.toString().uppercase()}.${slot.slot}"

    /// The whole plan, as a pure function of the routines — no clock, no notification
    /// manager, no permission. Sorted and deduped, and a routine with reminders switched
    /// off contributes nothing rather than contributing a disabled request.
    fun requests(routines: List<RoutinePlanInput>): List<PlannedReminder> {
        val planned = mutableListOf<PlannedReminder>()
        // Two routines cannot share an identifier (the UUID is in it), but a caller that
        // passes the same routine twice must not produce a duplicate request.
        val claimed = HashSet<String>()

        for (routine in routines) {
            if (!routine.enabled) continue
            // Suppress from the FRONT of the day. Having trained once, the morning slot
            // is the one you have satisfied; the evening one is still owed. Dropping the
            // last slot instead would silence the reminder you still need.
            val sorted = routine.reminders.toSet().sorted()
            val suppressed = maxOf(0, sorted.size - maxOf(0, routine.outstandingToday))
            for (slot in sorted.drop(suppressed)) {
                val id = identifier(routine.id, slot)
                if (!claimed.add(id)) continue
                planned.add(
                    PlannedReminder(
                        identifier = id,
                        title = routine.name,
                        body = L10n.tr("Time for a session."),
                        hour = slot.hour,
                        minute = slot.minute,
                    )
                )
            }
        }
        return planned
    }

    /// Replans are fully serialized: one runs at a time, so a superseded run's in-flight
    /// adds can never land after the successor's wipe.
    ///
    /// TRANSLATION NOTE: iOS cancels its predecessor Task and awaits it. A `Mutex` is the
    /// same guarantee with the opposite emphasis — nothing is cancelled, everything runs
    /// in order — which is the safer half here, because an `AlarmManager` write abandoned
    /// halfway leaves a real alarm behind rather than an unsent request.
    private val gate = Mutex()

    suspend fun replan(routines: List<RoutinePlanInput>, scheduler: AlarmScheduler) {
        gate.withLock { scheduler.apply(requests(routines)) }
    }
}

/// What actually talks to the OS. An interface so `ReminderPlannerTests` can assert the
/// plan against a fake, exactly as the iOS tests assert `requests(for:)` alone.
interface AlarmScheduler {
    /// Add-before-remove: the new plan goes in FIRST and only then is the remainder
    /// dropped. Wiping first leaves a window — however short — with zero reminders, and
    /// process death inside that window makes it permanent.
    suspend fun apply(planned: List<ReminderPlanner.PlannedReminder>)
}

/// Records what it was asked to schedule and nothing else — the tests' scheduler, and
/// the one the app uses before any permission exists.
class RecordingAlarmScheduler : AlarmScheduler {
    var applied: List<ReminderPlanner.PlannedReminder> = emptyList()
        private set

    override suspend fun apply(planned: List<ReminderPlanner.PlannedReminder>) {
        applied = planned
    }
}

/// `AlarmManager.setWindow`, a notification channel, and the identifier bookkeeping
/// Android forces on us because an alarm cannot be enumerated.
class AndroidAlarmScheduler(
    context: Context,
    private val settings: RoutineSettings,
    private val zone: ZoneId = ZoneId.systemDefault(),
) : AlarmScheduler {

    private val app = context.applicationContext
    private val alarms = app.getSystemService(AlarmManager::class.java)

    override suspend fun apply(planned: List<ReminderPlanner.PlannedReminder>) {
        // Note what we believe is already scheduled but DON'T cancel it yet — see
        // `AlarmScheduler.apply`.
        val ours = settings.scheduledReminderIdentifiers

        if (planned.isEmpty()) {
            // Every routine's reminders are off (or there are no routines): the correct
            // plan is genuinely empty, and this is the one path that may clear without
            // checking permission.
            ours.forEach { cancel(it) }
            settings.setScheduledReminderIdentifiers(emptySet())
            return
        }

        // A notification we are not allowed to post is an alarm that wakes the phone to
        // do nothing. `POST_NOTIFICATIONS` is asked for on the first Save of a routine
        // that wants reminders — see `NotificationPermissionGate` — and until it is
        // granted the plan is simply not installed. Nothing is cancelled either: the
        // user has not said no to the reminders, only not yet been asked.
        if (!canPost()) return

        val scheduled = HashSet<String>()
        for (item in planned) {
            schedule(item)
            scheduled.add(item.identifier)
        }

        // Now — and only now — drop what the new plan no longer covers.
        (ours - scheduled).forEach { cancel(it) }
        settings.setScheduledReminderIdentifiers(scheduled)
    }

    private fun canPost(): Boolean =
        ContextCompat.checkSelfPermission(app, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED

    private fun schedule(item: ReminderPlanner.PlannedReminder) {
        val at = ReminderAlarms.nextOccurrence(item.hour, item.minute, zone)
        alarms.setWindow(
            AlarmManager.RTC_WAKEUP,
            at,
            ReminderAlarms.windowMillis,
            ReminderAlarms.pendingIntent(app, item),
        )
    }

    private fun cancel(identifier: String) {
        ReminderAlarms.cancelIntent(app, identifier)?.let {
            alarms.cancel(it)
            it.cancel()
        }
    }
}

/// The shared alarm plumbing — the intent shape, the window, and the "when is the next
/// one" arithmetic — so the scheduler and the receiver that reschedules cannot disagree
/// about any of it.
object ReminderAlarms {
    const val channelId = "reminders"
    const val action = "run.nuri.getagrip.REMINDER"
    const val extraIdentifier = "identifier"
    const val extraTitle = "title"
    const val extraBody = "body"
    const val extraHour = "hour"
    const val extraMinute = "minute"

    /// Fifteen minutes. A habit nudge does not need the exact minute, and asking for
    /// `SCHEDULE_EXACT_ALARM` to get one would be the same overreach as claiming the
    /// right to pierce a Focus mode.
    const val windowMillis: Long = 15 * 60 * 1000L

    fun nextOccurrence(hour: Int, minute: Int, zone: ZoneId, from: LocalDateTime? = null): Long {
        val now = from ?: LocalDateTime.now(zone)
        var next = LocalDateTime.of(now.toLocalDate(), java.time.LocalTime.of(hour, minute))
        if (!next.isAfter(now)) next = next.plusDays(1)
        return next.atZone(zone).toInstant().toEpochMilli()
    }

    fun tomorrow(hour: Int, minute: Int, zone: ZoneId, today: LocalDate): Long =
        LocalDateTime.of(today.plusDays(1), java.time.LocalTime.of(hour, minute))
            .atZone(zone).toInstant().toEpochMilli()

    fun pendingIntent(
        context: Context,
        item: ReminderPlanner.PlannedReminder,
    ): PendingIntent {
        val intent = Intent(context, ReminderReceiver::class.java).apply {
            action = ReminderAlarms.action
            putExtra(extraIdentifier, item.identifier)
            putExtra(extraTitle, item.title)
            putExtra(extraBody, item.body)
            putExtra(extraHour, item.hour)
            putExtra(extraMinute, item.minute)
        }
        return PendingIntent.getBroadcast(
            context,
            item.requestCode,
            intent,
            // UPDATE_CURRENT is what makes a replan idempotent: re-adding an identifier
            // replaces the alarm in place rather than stacking a second one.
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    /// NO_CREATE: if nothing is scheduled under this identifier there is nothing to
    /// cancel, and minting a `PendingIntent` in order to cancel it would leave a fresh
    /// one behind on some OEM builds.
    fun cancelIntent(context: Context, identifier: String): PendingIntent? {
        val intent = Intent(context, ReminderReceiver::class.java).apply {
            action = ReminderAlarms.action
        }
        return PendingIntent.getBroadcast(
            context,
            identifier.hashCode(),
            intent,
            PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    /// **DEFAULT importance, never HIGH.** This is a habit nudge; a training app that
    /// claims the right to a heads-up banner for a routine reminder is the kind of app
    /// people turn notifications off for entirely. The iOS twin is
    /// `interruptionLevel = .active`.
    fun ensureChannel(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java)
        if (manager.getNotificationChannel(channelId) != null) return
        manager.createNotificationChannel(
            NotificationChannel(
                channelId,
                L10n.tr("Session reminders"),
                NotificationManager.IMPORTANCE_DEFAULT,
            )
        )
    }
}

/// Posts one reminder and schedules the same slot for tomorrow.
///
/// The reschedule is what replaces iOS's repeating trigger. It happens here rather than
/// at launch because a phone that is not opened for a week must still be reminded — and
/// the next replan (any save, any finished session, midnight) overwrites this alarm in
/// place, so the two cannot drift apart.
class ReminderReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ReminderAlarms.action) return
        val identifier = intent.getStringExtra(ReminderAlarms.extraIdentifier) ?: return
        val title = intent.getStringExtra(ReminderAlarms.extraTitle) ?: return
        val body = intent.getStringExtra(ReminderAlarms.extraBody)
            ?: L10n.tr("Time for a session.")
        val hour = intent.getIntExtra(ReminderAlarms.extraHour, -1)
        val minute = intent.getIntExtra(ReminderAlarms.extraMinute, -1)
        if (hour < 0 || minute < 0) return

        post(context, identifier, title, body)
        rescheduleTomorrow(context, identifier, title, body, hour, minute)
    }

    private fun post(context: Context, identifier: String, title: String, body: String) {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) return

        ReminderAlarms.ensureChannel(context)
        val open = PendingIntent.getActivity(
            context,
            0,
            Intent(context, MainActivity::class.java)
                .setFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = NotificationCompat.Builder(context, ReminderAlarms.channelId)
            .setSmallIcon(R.drawable.ic_launcher_monochrome)
            .setContentTitle(title)
            .setContentText(body)
            .setContentIntent(open)
            .setAutoCancel(true)
            .setCategory(Notification.CATEGORY_REMINDER)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .build()
        context.getSystemService(NotificationManager::class.java)
            .notify(identifier.hashCode(), notification)
    }

    private fun rescheduleTomorrow(
        context: Context,
        identifier: String,
        title: String,
        body: String,
        hour: Int,
        minute: Int,
    ) {
        val zone = ZoneId.systemDefault()
        val item = ReminderPlanner.PlannedReminder(identifier, title, body, hour, minute)
        context.getSystemService(AlarmManager::class.java).setWindow(
            AlarmManager.RTC_WAKEUP,
            ReminderAlarms.tomorrow(hour, minute, zone, LocalDate.now(zone)),
            ReminderAlarms.windowMillis,
            ReminderAlarms.pendingIntent(context, item),
        )
    }
}

/// **Asking for `POST_NOTIFICATIONS` belongs to the first Save of a routine that wants
/// reminders, never to launch** — by then the user has been through the builder and seen
/// both times on screen, so the OS dialog arrives with its reason already on the previous
/// screen. The twin of `PermissionGate` in `DeviceStore`, and fulfilled the same way: the
/// Activity lends one to the store.
///
/// Nothing installs a gate yet. `TemplateStore.askNotificationPermissionOnce` still runs
/// its one-shot bookkeeping and simply has nobody to ask, which is deliberate — the
/// dialog is wired by the wave that ships the builder.
fun interface NotificationPermissionGate {
    fun request(onResult: (Boolean) -> Unit)
}
