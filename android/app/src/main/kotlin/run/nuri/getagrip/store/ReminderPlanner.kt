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
import run.nuri.getagrip.MainActivity
import run.nuri.getagrip.R
import run.nuri.getagrip.engine.DayStamp
import run.nuri.getagrip.engine.L10n
import run.nuri.getagrip.engine.ReminderTime
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.UUID

/// Wipe-and-reschedule local-notification planning for the daily ritual.
///
/// Deterministic `doigt.routine.<uuid>.<slot>` identifiers plus a full replan on every
/// change make scheduling idempotent: no incremental state to corrupt, and re-adding an
/// identifier replaces the alarm in place, so 08:00 → 09:00 moves one reminder rather than
/// adding a second.
///
/// TRANSLATION NOTE (Sources/Store/ReminderPlanner.swift): `requests(for:)`, the pure half
/// the tests exercise, is unchanged in its rules. Underneath:
///
/// - `UNUserNotificationCenter` becomes `AlarmManager` + a `BroadcastReceiver`.
///   **`setWindow` with a 15-minute window, never `setExactAndAllowWhileIdle`**: exact
///   alarms need `SCHEDULE_EXACT_ALARM`, which Play gates to alarm-clock and calendar apps,
///   and a habit nudge insisting on the minute is the overreach `.timeSensitive` was on
///   iOS.
/// - iOS's repeating trigger becomes a ONE-SHOT alarm the receiver reschedules after
///   firing. `setRepeating` is inexact and un-cancellable per occurrence; rescheduling on
///   fire lets a session at 18:00 suppress TODAY's 19:00 slot and restore it tomorrow.
/// - The identifier is hashed into a stable request code (`requestCode`) naming the
///   `PendingIntent`.
object ReminderPlanner {

    /// One routine's reminder settings as value data, so the plan is computed without a
    /// database row.
    data class RoutinePlanInput(
        val id: UUID,
        val name: String,
        val reminders: List<ReminderTime>,
        val enabled: Boolean,
        /// How many sessions today still owes. Zero means the day is met and every
        /// remaining slot is suppressed: a reminder after you have done the thing is how
        /// notifications get turned off for good.
        val outstandingToday: Int = 1,
    )

    /// A request fully resolved but not yet handed to the system, so planning is testable
    /// without `AlarmManager`. `hour`/`minute` stand in for the only two `DateComponents`
    /// fields the iOS trigger set.
    data class PlannedReminder(
        val identifier: String,
        val title: String,
        val body: String,
        val hour: Int,
        val minute: Int,
        val suppressToday: Boolean = false,
    ) {
        /// `AlarmManager` addresses an alarm by `PendingIntent`, identified by request
        /// code, so the identifier collapses to an Int. `String.hashCode` is specified
        /// (stable across processes), and the collision domain is this app's own alarms.
        val requestCode: Int get() = identifier.hashCode()
    }

    /// The namespace we own: everything with this prefix is ours to cancel on a replan.
    /// Kept as `doigt.` (the iOS identity, shared vocabulary between the ports, never
    /// user-visible).
    const val identifierPrefix = "doigt.routine."

    /// Content-keyed on both halves: the routine's UUID survives an undo-delete, and `slot`
    /// derives from the TIME, so moving 08:00 to 09:00 replaces its own request rather than
    /// leaving an orphan firing forever.
    fun identifier(routine: UUID, slot: ReminderTime): String =
        "$identifierPrefix${routine.toString().uppercase()}.${slot.slot}"

    /// The whole plan as a pure function of the routines — no clock, notification manager
    /// or permission. Sorted and deduped; a routine with reminders off contributes nothing.
    fun requests(routines: List<RoutinePlanInput>): List<PlannedReminder> {
        val planned = mutableListOf<PlannedReminder>()
        // The UUID makes identifiers unique per routine; this only guards a caller passing
        // one routine twice.
        val claimed = HashSet<String>()

        for (routine in routines) {
            if (!routine.enabled) continue
            // Suppress from the FRONT of the day: having trained once, the morning slot is
            // satisfied and the evening one still owed. Dropping the last slot would
            // silence the reminder you need.
            // Front of the TRAINING day (from `DayStamp.ROLLOVER_HOUR`): a 01:00 reminder
            // is the last slot of the evening before, and counting it first would spend the
            // morning's suppression on it.
            val sorted = routine.reminders.toSet().sorted()
            val owed = maxOf(0, sorted.size - maxOf(0, routine.outstandingToday))
            val suppressed = sorted.sortedBy { trainingDayMinute(it) }.take(owed).toSet()
            for (slot in sorted) {
                val id = identifier(routine.id, slot)
                if (!claimed.add(id)) continue
                planned.add(
                    PlannedReminder(
                        identifier = id,
                        title = routine.name,
                        body = L10n.tr("Time for a session."),
                        hour = slot.hour,
                        minute = slot.minute,
                        suppressToday = slot in suppressed,
                    )
                )
            }
        }
        return planned
    }

    /// Minutes since the training day began — the order slots are OWED in.
    private fun trainingDayMinute(slot: ReminderTime): Int =
        Math.floorMod(slot.minutesFromMidnight - DayStamp.ROLLOVER_HOUR * 60, 24 * 60)

    /// Compute the plan and hand it to the scheduler. **Not serialized here**: the one
    /// caller, `TemplateStore` (the door for every replan, boot receiver included), runs
    /// these through its `replanLane`. A lock here would have to be process-global, and one
    /// can be left held by a coroutine whose dispatcher stopped.
    ///
    /// TRANSLATION NOTE: iOS cancels its predecessor Task. The lane cancels nothing and
    /// applies one plan at a time — safer here, because an `AlarmManager` write abandoned
    /// halfway leaves a real alarm behind.
    suspend fun replan(routines: List<RoutinePlanInput>, scheduler: AlarmScheduler) {
        scheduler.apply(requests(routines))
    }
}

/// What talks to the OS. An interface so `ReminderPlannerTests` can assert the plan against
/// a fake.
interface AlarmScheduler {
    /// Add-before-remove: install the new plan FIRST, then drop the remainder. Wiping first
    /// leaves a window with zero reminders, and process death inside it makes that
    /// permanent.
    suspend fun apply(planned: List<ReminderPlanner.PlannedReminder>)
}

/// Records what it was asked to schedule and nothing else — the tests' scheduler, and the
/// app's before any permission exists.
class RecordingAlarmScheduler : AlarmScheduler {
    var applied: List<ReminderPlanner.PlannedReminder> = emptyList()
        private set

    override suspend fun apply(planned: List<ReminderPlanner.PlannedReminder>) {
        applied = planned
    }
}

/// `AlarmManager.setWindow`, a notification channel, and the identifier bookkeeping Android
/// forces because alarms cannot be enumerated.
class AndroidAlarmScheduler(
    context: Context,
    private val settings: RoutineSettings,
    private val zone: ZoneId = ZoneId.systemDefault(),
) : AlarmScheduler {

    private val app = context.applicationContext
    private val alarms = app.getSystemService(AlarmManager::class.java)

    override suspend fun apply(planned: List<ReminderPlanner.PlannedReminder>) {
        // Note what we believe is scheduled but DON'T cancel yet — see
        // `AlarmScheduler.apply`.
        val ours = settings.scheduledReminderIdentifiers

        if (planned.isEmpty()) {
            // All reminders off (or no routines): the plan is genuinely empty, the one path
            // that may clear without checking permission.
            ours.forEach { cancel(it) }
            settings.setScheduledReminderIdentifiers(emptySet())
            return
        }

        // An alarm for a notification we may not post wakes the phone to do nothing. Until
        // `POST_NOTIFICATIONS` is granted (asked on the first Save wanting reminders — see
        // `NotificationPermissionGate`) the plan is not installed, and nothing is
        // cancelled: the user has not said no.
        if (!canPost()) return

        val scheduled = HashSet<String>()
        for (item in planned) {
            schedule(item)
            scheduled.add(item.identifier)
        }

        // Only now drop what the new plan no longer covers.
        (ours - scheduled).forEach { cancel(it) }
        settings.setScheduledReminderIdentifiers(scheduled)
    }

    private fun canPost(): Boolean =
        ContextCompat.checkSelfPermission(app, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED

    private fun schedule(item: ReminderPlanner.PlannedReminder) {
        val at = ReminderAlarms.nextOccurrence(item.hour, item.minute, zone,
            suppressToday = item.suppressToday)
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

/// Shared alarm plumbing — intent shape, window, "when is the next one" — so scheduler and
/// rescheduling receiver cannot disagree.
object ReminderAlarms {
    const val channelId = "reminders"
    const val action = "run.nuri.getagrip.REMINDER"
    const val extraIdentifier = "identifier"
    const val extraTitle = "title"
    const val extraBody = "body"
    const val extraHour = "hour"
    const val extraMinute = "minute"

    /// Fifteen minutes: a habit nudge does not need the exact minute (see the planner's
    /// note on `SCHEDULE_EXACT_ALARM`).
    const val windowMillis: Long = 15 * 60 * 1000L

    /// The first firing of `hour:minute` after `from`, skipped once more when suppressed
    /// AND still inside the current training day.
    ///
    /// **Suppression is about the TRAINING day, not the calendar.** Pushing the slot past
    /// its next calendar occurrence unconditionally is right at 18:00 and wrong after
    /// midnight: a session at 00:30 settles the day that began at 04:00 yesterday, so
    /// tomorrow's 08:00 is the next training day's first reminder and was being skipped
    /// (the same bug existed on iOS). A slot is skipped only when its next firing lands
    /// before the coming rollover.
    fun nextOccurrence(hour: Int, minute: Int, zone: ZoneId, from: LocalDateTime? = null,
                       suppressToday: Boolean = false): Long {
        val now = from ?: LocalDateTime.now(zone)
        var next = LocalDateTime.of(now.toLocalDate(), java.time.LocalTime.of(hour, minute))
        if (!next.isAfter(now)) next = next.plusDays(1)
        if (suppressToday && next.isBefore(trainingDayEnd(now))) next = next.plusDays(1)
        return next.atZone(zone).toInstant().toEpochMilli()
    }

    /// The coming `DayStamp.ROLLOVER_HOUR` in local wall time, where `now`'s training day
    /// ends. Local-time arithmetic, so a DST night moves the hour with the clock.
    fun trainingDayEnd(now: LocalDateTime): LocalDateTime {
        val rollover = now.toLocalDate().atTime(DayStamp.ROLLOVER_HOUR, 0)
        return if (now.isBefore(rollover)) rollover else rollover.plusDays(1)
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
            // UPDATE_CURRENT makes a replan idempotent: re-adding an identifier replaces
            // the alarm in place.
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    /// NO_CREATE: with nothing scheduled there is nothing to cancel, and minting a
    /// `PendingIntent` to cancel it leaves a fresh one behind on some OEM builds.
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

    /// **DEFAULT importance, never HIGH.** A habit nudge claiming heads-up banners is how
    /// people turn a whole app's notifications off. The iOS twin is
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

/// Posts one reminder and schedules the same slot for tomorrow — the replacement for iOS's
/// repeating trigger. Here rather than at launch because a phone unopened for a week must
/// still be reminded; the next replan overwrites this alarm in place, so the two cannot
/// drift.
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
/// reminders, never to launch** — by then the reason is on the previous screen. The twin of
/// `DeviceStore`'s `PermissionGate`: the Activity lends one to the store.
///
/// Nothing installs a gate yet; `TemplateStore.askNotificationPermissionOnce` does its
/// bookkeeping with nobody to ask until the builder wires the dialog.
fun interface NotificationPermissionGate {
    fun request(onResult: (Boolean) -> Unit)
}
