// SPDX-License-Identifier: MPL-2.0
// Original contributions Copyright 2026 Nuri Bruner.

package run.nuri.getagrip.store

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import run.nuri.getagrip.GetAGripApplication

/// What leaving the foreground should do to the gauge's link — the rule on its own, with
/// no store, no coroutine and no radio behind it.
///
/// TRANSLATION NOTE (from `DeviceStore.beginBackgroundGrace` on iOS): the Swift version
/// interleaves the decision with a `beginBackgroundTask` assertion and a sleeping `Task`.
/// Splitting the decision out is what makes it testable here, and the three outcomes are
/// exactly the three branches that file has.
enum class BackgroundGraceAction {
    /// A session is streaming (or nothing is connected): leave the link alone. On Android
    /// what actually keeps such a session alive is `SessionForegroundService`; the grace is
    /// for the OTHER case, an idle gauge left connected by a screen you walked away from.
    none,

    /// **A gauge that cannot stream in the background gets no grace at all.** For a
    /// broadcast scale the "link" is an unfiltered all-matches scan — the most power-hungry
    /// BLE mode there is — and the OS silences it the moment we background whatever we ask
    /// for. Reacquiring costs about a second of rescan, so the grace was buying nothing on
    /// either side of the trade. The caller arms the scan to stand itself back up on the
    /// way in.
    disconnectNow,

    /// The 45 s window. Firing at once could not tell a two-second "hey Siri" from a phone
    /// put in a bag, and charged both a 5–6 s reconnect; that churn is what Nuri reported
    /// as "weird Bluetooth drops".
    scheduleDisconnect,
}

object BackgroundGracePolicy {

    /// 45 seconds, the same window iOS opens. Long enough that an app switch is free, short
    /// enough that a phone in a bag does not leave the gauge awake for the ten minutes it
    /// takes to self-sleep after a disconnect.
    const val graceSeconds = 45L

    fun onLeavingForeground(
        isConnected: Boolean,
        isBusy: Boolean,
        isStreaming: Boolean,
        sustainsBackgroundStreaming: Boolean,
    ): BackgroundGraceAction {
        // `isBusy` is in the guard on purpose — scanning and connecting are exactly the
        // states this has to catch, because by the time anything looks at a broadcast
        // gauge's link the scan has usually already re-armed itself.
        if (!sustainsBackgroundStreaming && (isConnected || isBusy)) {
            return BackgroundGraceAction.disconnectNow
        }
        if (isConnected && !isStreaming) return BackgroundGraceAction.scheduleDisconnect
        return BackgroundGraceAction.none
    }
}

/// **The grace's second clock — one that survives the process being FROZEN.**
///
/// The 45 s window is a coroutine `delay`, and a delay only elapses while the process runs.
/// Android's cached-apps freezer stops a backgrounded app's threads within seconds of it
/// leaving the screen (no service, no visible Activity), and a frozen process's GATT client
/// stays registered with the Bluetooth stack: the link stays UP, the timer never fires, and
/// the gauge — which only self-sleeps ten minutes after a DISCONNECT — stays awake until the
/// user comes back or its battery goes. That is the failure the whole grace exists to
/// prevent, reached by the one path the old comment ruled out.
///
/// So the window is armed twice: the coroutine for the ordinary case, and an `AlarmManager`
/// alarm the system delivers even to a frozen app, thawing it to run the receiver. Whichever
/// fires first disconnects (both re-check that the app is still backgrounded and nothing is
/// streaming); coming back to the foreground cancels both. An interface so the store stays
/// JVM-testable — the app's is `AlarmGraceBackstop`.
interface BackgroundGraceBackstop {
    fun arm(afterMillis: Long)
    fun cancel()
}

/// Nothing to arm — tests, previews, and a store with no `Context`.
object NoGraceBackstop : BackgroundGraceBackstop {
    override fun arm(afterMillis: Long) = Unit
    override fun cancel() = Unit
}

/// `setAndAllowWhileIdle` on the elapsed-realtime clock: inexact, so it needs no
/// `SCHEDULE_EXACT_ALARM` (Play gates that to alarm-clock apps), and allowed in Doze, so a
/// phone put face-down in a bag still gets it. Inexact means it may land minutes late under
/// Doze's own batching: a grace that runs LONG, never one that never ends, which is the
/// property that matters.
class AlarmGraceBackstop(context: Context) : BackgroundGraceBackstop {
    private val app = context.applicationContext
    private val alarms = app.getSystemService(AlarmManager::class.java)

    override fun arm(afterMillis: Long) {
        alarms?.setAndAllowWhileIdle(
            AlarmManager.ELAPSED_REALTIME_WAKEUP,
            SystemClock.elapsedRealtime() + afterMillis,
            intent(),
        )
    }

    override fun cancel() {
        alarms?.cancel(intent())
    }

    private fun intent(): PendingIntent = PendingIntent.getBroadcast(
        app,
        REQUEST_CODE,
        Intent(app, BackgroundGraceReceiver::class.java).setAction(ACTION),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    companion object {
        const val ACTION = "run.nuri.getagrip.BACKGROUND_GRACE"
        private const val REQUEST_CODE = 4_501
    }
}

/// Delivered by the backstop alarm. If the process died meanwhile there is no store and no
/// link — a dead process takes its GATT client with it — so there is nothing to do.
class BackgroundGraceReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != AlarmGraceBackstop.ACTION) return
        val app = context.applicationContext as? GetAGripApplication ?: return
        app.existingDeviceStore?.backgroundGraceBackstopFired()
    }
}
