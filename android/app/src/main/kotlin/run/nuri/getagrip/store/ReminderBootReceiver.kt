// SPDX-License-Identifier: MPL-2.0
// Original contributions Copyright 2026 Nuri Bruner.

package run.nuri.getagrip.store

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import kotlinx.coroutines.launch
import run.nuri.getagrip.GetAGripApplication

/// **An alarm that dies with a reboot is a reminder that silently stops.**
///
/// `AlarmManager` drops every pending alarm when the phone restarts, and there is nothing
/// on screen to say so — the routine still shows its two times, the switch is still on,
/// and the nudge simply never comes again. iOS has no counterpart to this file because
/// `UNUserNotificationCenter` owns the schedule across restarts; on Android the schedule is
/// the app's to keep, so it is rebuilt here.
///
/// **It replans through the store, never by re-reading the alarms.** Android cannot
/// enumerate pending alarms, so `syncDerived()` — the same call a save, a finished session
/// and midnight all go through — is the only thing that knows what the plan should be. One
/// plan, one writer; a second scheduling path here is how the two would drift apart.
///
/// `MY_PACKAGE_REPLACED` is in the filter for the same reason: an in-place update also
/// clears pending alarms.
class ReminderBootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED &&
            intent.action != Intent.ACTION_MY_PACKAGE_REPLACED
        ) {
            return
        }
        val app = context.applicationContext as? GetAGripApplication ?: return

        // `goAsync` is what buys the Room read: a receiver's `onReceive` returns on the
        // main thread within seconds, and the replan has to open the database. The result
        // is finished on the store's own scope so the write lane is the one every other
        // writer uses.
        val result = goAsync()
        app.storeScope.launch {
            try {
                app.templates.syncDerived()
            } finally {
                // Always, on every path. A `goAsync` result never finished holds a wake
                // lock until the system times it out.
                result.finish()
            }
        }
    }
}
