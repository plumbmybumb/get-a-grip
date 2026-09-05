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
/// TRANSLATION NOTE (from the iOS `bluetooth-central` background mode): on iOS a session
/// with a connected gauge survives backgrounding because the app declares that mode and
/// CoreBluetooth keeps delivering notifications to a suspended-but-resumable process.
/// Android has no equivalent capability bit — the only way to go on receiving GATT
/// notifications with the app in the background is a FOREGROUND SERVICE, and the type that
/// names this exact use is `connectedDevice`. So the rule "a connected session never
/// pauses in the background" (`BackgroundPausePolicy`) is only TRUE on Android for as long
/// as this service is running; without it the process is frozen, samples stop, and the rep
/// silently stalls.
///
/// **The service's notification IS the Live Update.** A foreground service must show one,
/// and a session already wants exactly one card on the lock screen — so rather than
/// posting two, `AndroidActivityPublisher` builds the card and this adopts it under the
/// same id. That is also why `pending` exists: `startForeground` needs a notification in
/// the same breath as the start, and a placeholder would flash the wrong card for a frame.
///
/// **Started only for a session that can actually use it** — measured, connected, and on a
/// gauge whose capabilities say it sustains background streaming. A timer-only session has
/// nothing to keep alive (see `RunnerLifecycle`, which pauses it outright), and a
/// broadcast scale cannot stream backgrounded whatever we do.
///
/// **The gauge is never told to sleep.** Ending a session stops the stream and stops this
/// service; the `sleep` opcode is not sent here or anywhere, because it powers the device
/// off and costs a physical button press to wake — the wrong price for a session that just
/// finished, when a second one is due the same day.
class SessionForegroundService : Service() {

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val notification = pending
        if (notification == null) {
            // Nothing to show means nothing to keep alive: a service that started with no
            // card behind it is a stray, and stopping is the honest response. (This is the
            // path a system-initiated restart would take, which is also why the return
            // below is NOT_STICKY.)
            stopSelf()
            return START_NOT_STICKY
        }
        ServiceCompat.startForeground(
            this,
            LiveUpdateNotification.NOTIFICATION_ID,
            notification,
            ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE,
        )
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        // REMOVE, not detach: a session that has ended must leave nothing behind, and a
        // card still saying "Holding" on the lock screen is worse than no card at all —
        // the same reason iOS ends its activity with `.immediate`.
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        /// The card to run in, set by `AndroidActivityPublisher` immediately BEFORE the
        /// start so `startForeground` adopts the real one. Volatile because the service is
        /// started asynchronously; it is only ever written from the main thread.
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

        /// **Called at process start, and that is the whole leak guard.**
        ///
        /// If the process is killed mid-session — force-stopped, or reclaimed by an
        /// aggressive OEM battery manager — `RunnerSession.end()` never runs and the
        /// ongoing card is never cancelled, so it would sit on the lock screen claiming a
        /// session that is not happening. A process that has just started cannot have one
        /// running, so the first thing a fresh process does is clear it.
        fun cancelStaleCard(context: Context) {
            NotificationManagerCompat.from(context)
                .cancel(LiveUpdateNotification.NOTIFICATION_ID)
        }
    }
}

/// Where a session asks the OS to keep it alive.
///
/// One seam, for the same reason `CueSink` and `ActivityPublisher` are seams: a JVM test
/// drives whole sessions through `RunnerSession`, and a `Service` needs a real Android
/// runtime. The default does nothing, so nothing in the engine's timing depends on it.
interface SessionServiceController {
    /// Idempotent by contract — `RunnerSession` calls this on `begin()` and again when a
    /// link arrives later, and only one service may ever be running.
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
