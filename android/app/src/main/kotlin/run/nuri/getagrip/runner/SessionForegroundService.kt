// SPDX-License-Identifier: MPL-2.0
// Original contributions Copyright 2026 Nuri Bruner.

package run.nuri.getagrip.runner

import android.app.Notification
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat

/// **What keeps a session alive when the screen locks.**
///
/// TRANSLATION NOTE: iOS declares the `bluetooth-central` background mode and CoreBluetooth
/// keeps delivering to a suspended process. Android's only way to keep receiving GATT
/// notifications in the background is a FOREGROUND SERVICE of type `connectedDevice`, so "a
/// connected session never pauses in the background" (`BackgroundPausePolicy`) holds only
/// while this runs; without it the process freezes and the rep silently stalls.
///
/// **The service's notification IS the Live Update.** A session wants exactly one
/// lock-screen card, so `AndroidActivityPublisher` builds it and this adopts it under the
/// same id — hence `pending`: `startForeground` needs a notification at once, and a
/// placeholder would flash the wrong card.
///
/// **Started only for a session that can use it** — measured, connected, and on a gauge
/// that sustains background streaming. A timer-only session is paused outright
/// (`RunnerLifecycle`); a broadcast scale cannot stream backgrounded anyway.
///
/// **The gauge is never told to sleep.** The `sleep` opcode powers it off and costs a
/// button press to wake — the wrong price when a second session is due the same day.
class SessionForegroundService : Service() {

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val notification = pending
        // **`startForeground` FIRST, on every path — including the one about to stop.**
        // `startForegroundService` is a promise to call it within seconds, kept whether or
        // not the service still wants to run: a stray start that went straight to
        // `stopSelf()` crashed the process with
        // `ForegroundServiceDidNotStartInTimeException` (a session ending between start
        // request and this callback). A stray is promoted on a placeholder and stopped at
        // once.
        ServiceCompat.startForeground(
            this,
            LiveUpdateNotification.NOTIFICATION_ID,
            notification ?: LiveUpdateNotification.placeholder(this),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE,
        )
        if (notification == null) {
            // No card means nothing to keep alive: a stray, so stop. (A system restart
            // would take this path too, hence NOT_STICKY.) `onDestroy` removes the
            // placeholder.
            stopSelf(startId)
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        // REMOVE, not detach: a lock-screen card still saying "Holding" after a session is
        // worse than none (iOS ends with `.immediate`).
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        /// The card to run in, set by `AndroidActivityPublisher` just BEFORE the start.
        /// Volatile: the service starts asynchronously; written only from the main thread.
        @Volatile
        internal var pending: Notification? = null

        fun start(context: Context) {
            ContextCompat.startForegroundService(
                context,
                Intent(context, SessionForegroundService::class.java),
            )
        }

        fun stop(context: Context) {
            pending = null
            context.stopService(Intent(context, SessionForegroundService::class.java))
        }

        /// **Called at process start — the whole leak guard.** A process killed mid-session
        /// (force-stop, OEM battery manager) never ran `RunnerSession.end()`, so its card
        /// would claim a session that is not happening. A fresh process cannot have one
        /// running, so it clears it first.
        fun cancelStaleCard(context: Context) {
            NotificationManagerCompat.from(context)
                .cancel(LiveUpdateNotification.NOTIFICATION_ID)
        }
    }
}

/// Where a session asks the OS to keep it alive. A seam like `CueSink` and
/// `ActivityPublisher`: JVM tests drive whole sessions, and a `Service` needs a real
/// runtime. The default does nothing, so no engine timing depends on it.
interface SessionServiceController {
    /// Idempotent: `RunnerSession` calls it on `begin()` and again when a link arrives;
    /// only one service may run.
    fun begin()

    fun end()
}

/// The default: no service, for previews, tests, and every session that does not qualify.
object NoSessionServiceController : SessionServiceController {
    override fun begin() = Unit
    override fun end() = Unit
}

/// The real one.
class AndroidSessionServiceController(context: Context) : SessionServiceController {
    private val appContext = context.applicationContext
    private var running = false

    override fun begin() {
        if (running) return
        running = true
        SessionForegroundService.start(appContext)
    }

    override fun end() {
        if (!running) return
        running = false
        SessionForegroundService.stop(appContext)
    }
}

/// The same service for a critical force test. A test has no routine card to adopt, so it
/// posts its own quiet one first (`LiveUpdateNotification.criticalForce`) — the service
/// runs in whatever card is `pending`, and with none it treats the start as a stray.
///
/// iOS keeps the test alive the way it keeps a session alive, through `bluetooth-central`;
/// without this, Android freezes a connected test the moment the phone is put down.
class CriticalForceServiceController(context: Context) : SessionServiceController {
    private val appContext = context.applicationContext
    private val inner = AndroidSessionServiceController(appContext)
    private var running = false

    override fun begin() {
        if (running) return
        running = true
        val card = LiveUpdateNotification.criticalForce(appContext)
        SessionForegroundService.pending = card
        runCatching { NotificationManagerCompat.from(appContext).notify(LiveUpdateNotification.NOTIFICATION_ID, card) }
        inner.begin()
    }

    override fun end() {
        if (!running) return
        running = false
        inner.end()
        NotificationManagerCompat.from(appContext).cancel(LiveUpdateNotification.NOTIFICATION_ID)
    }
}
