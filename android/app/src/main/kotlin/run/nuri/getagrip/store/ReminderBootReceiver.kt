// SPDX-License-Identifier: MPL-2.0
// Original contributions Copyright 2026 Nuri Bruner.

package run.nuri.getagrip.store

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import kotlinx.coroutines.launch
import run.nuri.getagrip.GetAGripApplication

/// **An alarm that dies with a reboot is a reminder that silently stops.** `AlarmManager`
/// drops pending alarms on restart while the routine still shows its times with the switch
/// on. iOS's `UNUserNotificationCenter` keeps its schedule; on Android it is the app's to
/// rebuild, here.
///
/// **Replans through the store, never by re-reading alarms** (which cannot be enumerated):
/// `syncDerived()`, the path every save, session and midnight uses. One plan, one writer.
///
/// `MY_PACKAGE_REPLACED` too: an in-place update also clears pending alarms.
class ReminderBootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED &&
            intent.action != Intent.ACTION_MY_PACKAGE_REPLACED
        ) {
            return
        }
        val app = context.applicationContext as? GetAGripApplication ?: return

        // `goAsync` buys time for the Room read (`onReceive` must return within seconds);
        // finished on the store's scope so the replan uses the usual write lane.
        val result = goAsync()
        app.storeScope.launch {
            try {
                app.templates.syncDerived()
            } finally {
                // On every path: an unfinished `goAsync` result holds a wake lock until the
                // system times out.
                result.finish()
            }
        }
    }
}
