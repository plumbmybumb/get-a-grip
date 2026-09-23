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

/// What leaving the foreground should do to the gauge's link — the rule alone, with no
/// store, coroutine or radio.
///
/// TRANSLATION NOTE (iOS `DeviceStore.beginBackgroundGrace`): Swift interleaves this with a
/// `beginBackgroundTask` assertion and a sleeping `Task`; split out it is testable, and the
/// three outcomes are that file's three branches.
enum class BackgroundGraceAction {
    /// A session is streaming (or nothing is connected): leave the link alone.
    /// `SessionForegroundService` keeps such a session alive; the grace is for an idle
    /// gauge left connected.
    none,

    /// **A gauge that cannot stream in the background gets no grace.** A broadcast scale's
    /// "link" is an unfiltered all-matches scan (the most power-hungry BLE mode), which the
    /// OS silences on background anyway, and reacquiring costs about a second of rescan.
    /// The caller arms the scan to restart on the way back.
    disconnectNow,

    /// The 45 s window. Firing at once could not tell a two-second "hey Siri" from a phone
    /// put in a bag, and charged both a 5–6 s reconnect; that churn is what Nuri reported
    /// as "weird Bluetooth drops".
    scheduleDisconnect,
}

object BackgroundGracePolicy {

    /// 45 seconds, iOS's window: an app switch is free, and a phone in a bag does not keep
    /// the gauge awake (it self-sleeps only ten minutes AFTER a disconnect).
    const val graceSeconds = 45L

    fun onLeavingForeground(
        isConnected: Boolean,
        isBusy: Boolean,
        isStreaming: Boolean,
        sustainsBackgroundStreaming: Boolean,
    ): BackgroundGraceAction {
        // `isBusy` in the guard: scanning and connecting are exactly the states to catch,
        // since a broadcast gauge's scan has usually re-armed itself by now.
        if (!sustainsBackgroundStreaming && (isConnected || isBusy)) {
            return BackgroundGraceAction.disconnectNow
        }
        if (isConnected && !isStreaming) return BackgroundGraceAction.scheduleDisconnect
        return BackgroundGraceAction.none
    }
}

/// **The grace's second clock — one that survives the process being FROZEN.**
///
/// A coroutine `delay` elapses only while the process runs. Android's cached-apps freezer
/// stops a backgrounded app's threads within seconds, but its GATT client stays registered:
/// the link stays UP, the timer never fires, and the gauge stays awake until the user
/// returns or its battery dies — the failure the grace exists to prevent.
///
/// So the window is armed twice: the coroutine, and an `AlarmManager` alarm the system
/// delivers even to a frozen app. Whichever fires first disconnects (both re-check
/// background and streaming); returning to the foreground cancels both. An interface so the
/// store stays JVM-testable — the app's is `AlarmGraceBackstop`.
interface BackgroundGraceBackstop {
    fun arm(afterMillis: Long)
    fun cancel()
}

/// Nothing to arm — tests, previews, and a store with no `Context`.
object NoGraceBackstop : BackgroundGraceBackstop {
    override fun arm(afterMillis: Long) = Unit
    override fun cancel() = Unit
}

/// `setAndAllowWhileIdle` on elapsed realtime: inexact, so no `SCHEDULE_EXACT_ALARM` (Play
/// gates it to alarm-clock apps), and allowed in Doze. It may land minutes late: a grace
/// that runs LONG, never one that never ends.
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

/// Delivered by the backstop alarm. A process that died meanwhile took its GATT client with
/// it, so there is nothing to do.
class BackgroundGraceReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != AlarmGraceBackstop.ACTION) return
        val app = context.applicationContext as? GetAGripApplication ?: return
        app.existingDeviceStore?.backgroundGraceBackstopFired()
    }
}
