// SPDX-License-Identifier: MPL-2.0
// Original contributions Copyright 2026 Nuri Bruner.

package run.nuri.getagrip

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.CompositionLocalProvider
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import run.nuri.getagrip.store.DayClock
import run.nuri.getagrip.store.DeviceStore
import run.nuri.getagrip.store.LocalDayClock
import run.nuri.getagrip.store.LocalDeviceStore
import run.nuri.getagrip.store.LocalHistoryFeed
import run.nuri.getagrip.store.LocalSettingsStore
import run.nuri.getagrip.store.LocalTemplateStore
import run.nuri.getagrip.store.NotificationPermissionGate
import run.nuri.getagrip.store.PermissionGate
import run.nuri.getagrip.store.TemplateStore
import run.nuri.getagrip.engine.RoutineShare
import run.nuri.getagrip.ui.RootTabView
import run.nuri.getagrip.ui.theme.GetAGripTheme

/// One activity, Compose all the way down. `enableEdgeToEdge()` before `super.onCreate` so
/// the first frame already draws the slate field behind the system bars.
class MainActivity : ComponentActivity() {

    private lateinit var device: DeviceStore
    private lateinit var templates: TemplateStore
    private lateinit var clock: DayClock

    /// The one in-flight permission answer. A second Connect tap while the dialog is open
    /// replaces it: one question on screen, one answer coming back.
    private var pendingPermissionResult: ((Boolean) -> Unit)? = null

    /// **The Connect tap behind an open permission dialog, remembered across recreation.**
    /// The callback above dies with this Activity, but the result API redelivers the answer
    /// to the NEXT instance (rotation, or process reclaimed), which used to drop a GRANT
    /// and make the person tap Connect again. This flag rides in the saved state and turns
    /// a callback-less grant into the connect it was asked for.
    private var connectRequested = false

    private val requestBluetooth = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { granted ->
        val allGranted = bluetoothPermissions.all { granted[it] == true }
        val callback = pendingPermissionResult
        pendingPermissionResult = null
        val answer = BluetoothPermissionAnswer.of(
            hasCallback = callback != null,
            connectRequested = connectRequested,
            allGranted = allGranted,
        )
        connectRequested = false
        when (answer) {
            BluetoothPermissionAnswer.deliver -> callback?.invoke(allGranted)
            BluetoothPermissionAnswer.resumeConnect -> device.connect()
            BluetoothPermissionAnswer.drop -> Unit
        }
    }

    private val gate = PermissionGate { onResult ->
        // Already granted answers SYNCHRONOUSLY: a Connect tap on a permitted app must not
        // cost a frame, matching iOS's `DeviceStore.connect()` once the prompt has been
        // seen.
        if (bluetoothPermissions.all(::isGranted)) {
            onResult(true)
        } else {
            pendingPermissionResult = onResult
            connectRequested = true
            requestBluetooth.launch(bluetoothPermissions)
        }
    }

    /// The notification answer, in its own slot: raised by different taps on different
    /// screens, and a shared slot would let a Connect tap swallow a Save's answer.
    private var pendingNotificationResult: ((Boolean) -> Unit)? = null

    private val requestNotifications = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        val callback = pendingNotificationResult
        pendingNotificationResult = null
        callback?.invoke(granted)
    }

    /// **Asked on the first Save of a routine that wants reminders, never at launch** — see
    /// `NotificationPermissionGate`. Answered synchronously when already granted, like the
    /// Bluetooth gate.
    ///
    /// ONLY for reminders. The Live Update needs the same permission to be VISIBLE, but a
    /// foreground service runs regardless, so a session never raises this dialog and a
    /// denial costs the card, not the workout.
    private val notificationGate = NotificationPermissionGate { onResult ->
        if (isGranted(Manifest.permission.POST_NOTIFICATIONS)) {
            onResult(true)
        } else {
            pendingNotificationResult = onResult
            requestNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        connectRequested = savedInstanceState?.getBoolean(CONNECT_REQUESTED_KEY) ?: false

        val app = application as GetAGripApplication
        device = app.deviceStore(useMock = DeviceStore.mockRequestedAtLaunch(intent))
        templates = app.templates
        clock = app.clock
        // **The gate belongs to the Activity, the store to the process.** A permission
        // dialog needs a live Activity to return to, so the store borrows one and gives it
        // back below rather than holding it across a rotation.
        device.permissionGate = gate
        // Same loan, same reason.
        templates.notificationPermissionGate = notificationGate
        // Seeders first, then the first derived publish — see `startStores`.
        app.startStores(intent)
        // A shared routine that COLD-LAUNCHED the app: queued before the first composition,
        // swept by `TodayScreen` when it appears.
        receiveShareLink(intent)

        setContent {
            GetAGripTheme {
                CompositionLocalProvider(
                    LocalDeviceStore provides device,
                    LocalTemplateStore provides templates,
                    LocalSettingsStore provides app.settings,
                    LocalDayClock provides clock,
                    LocalHistoryFeed provides app.historyFeed,
                ) {
                    val preview = androidx.compose.runtime.remember {
                        androidx.compose.runtime.mutableStateOf(BuildConfig.DEBUG && intent.getBooleanExtra("previewSummary", false))
                    }
                    val runnerPreview = androidx.compose.runtime.remember {
                        androidx.compose.runtime.mutableStateOf(BuildConfig.DEBUG && intent.getBooleanExtra("previewRunner", false))
                    }
                    if (runnerPreview.value) {
                        run.nuri.getagrip.ui.runner.DebugRunnerPreview { runnerPreview.value = false }
                    } else if (preview.value) {
                        run.nuri.getagrip.ui.runner.DebugSummaryPreview { preview.value = false }
                        if (intent.getBooleanExtra("previewLog", false)) {
                            run.nuri.getagrip.ui.history.SessionLogSheet { preview.value = false }
                        }
                    } else {
                        RootTabView()
                    }
                }
            }
        }
    }

    /// The app is `singleTask`, so a link tapped while running arrives HERE, not in
    /// `onCreate`; reading only `onCreate`'s intent would drop every scan after the first.
    ///
    /// `setIntent` because `getIntent()` otherwise keeps returning the LAUNCHER intent, and
    /// later questions (the mock-device argument) would answer about an old launch.
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        receiveShareLink(intent)
    }

    /// **The URL handler NEVER presents anything.** It queues the link in the store's
    /// inbox; `TodayScreen` drains it when nothing else holds the screen. On iOS
    /// (2026-08-19) presenting from the root TORE DOWN a full-screen cover — a running
    /// session died unlogged. Compose tears nothing down, but a sheet over a live workout
    /// is still wrong.
    ///
    /// `isRoutineLink` is asked FIRST — see `ShareLinkRouting`.
    private fun receiveShareLink(intent: Intent?) {
        val url = ShareLinkRouting.routineLink(intent?.action, intent?.data?.toString()) ?: return
        templates.receiveShareLink(url)
    }

    /// **Refresh the clock, THEN ask the store to recompute.** A phone open past midnight
    /// must flip "2 of 2 today" to "0 of 2" without a relaunch. The clock is refreshed
    /// here, not in the store: `ACTION_DATE_CHANGED` may not arrive until the device wakes,
    /// and the store only REACTS to the clock. `DoigtApp`'s `scenePhase == .active` order.
    override fun onResume() {
        super.onResume()
        clock.refresh()
        lifecycleScope.launch { templates.refreshIfDayChanged() }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        // Compose redraws uiMode in place; refresh system-bar icon contrast too, keeping
        // the runner's transparent navigation bar.
        val navigationContrast = window.isNavigationBarContrastEnforced
        enableEdgeToEdge()
        window.isNavigationBarContrastEnforced = navigationContrast
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putBoolean(CONNECT_REQUESTED_KEY, connectRequested)
    }

    override fun onDestroy() {
        if (device.permissionGate === gate) device.permissionGate = null
        if (templates.notificationPermissionGate === notificationGate) {
            templates.notificationPermissionGate = null
        }
        pendingPermissionResult = null
        pendingNotificationResult = null
        super.onDestroy()
    }

    private fun isGranted(permission: String): Boolean =
        ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED

    private companion object {
        /// minSdk 31: always this pair, no legacy `BLUETOOTH`/`ACCESS_FINE_LOCATION`
        /// branch. SCAN is `neverForLocation` in the manifest, keeping the location prompt
        /// out.
        val bluetoothPermissions = arrayOf(
            Manifest.permission.BLUETOOTH_SCAN,
            Manifest.permission.BLUETOOTH_CONNECT,
        )

        const val CONNECT_REQUESTED_KEY = "connectRequested"
    }
}

/// What a Bluetooth permission answer turns into — the rule on its own, so it is a JVM test.
enum class BluetoothPermissionAnswer {
    /// The instance that asked is still here: hand it the answer.
    deliver,

    /// The asking instance is gone, its Connect tap was remembered, and the answer is yes:
    /// connect. `DeviceStore.connect()` re-asks the gate, which now answers synchronously.
    resumeConnect,

    /// Nobody to tell, or a refusal with no screen to report it; the next Connect tap asks
    /// again.
    drop;

    companion object {
        fun of(hasCallback: Boolean, connectRequested: Boolean, allGranted: Boolean): BluetoothPermissionAnswer =
            when {
                hasCallback -> deliver
                connectRequested && allGranted -> resumeConnect
                else -> drop
            }
    }
}

/// The intent → URL decision as a pure function, so routing is a JVM test.
///
/// **A link that is not ours is left completely alone** — not refused, not alerted.
/// `isRoutineLink` is the cheap shape-only check (scheme and host), asked first so the app
/// never answers for another handler's link. A link that passes and then fails to decode
/// gets a readable error from the store.
object ShareLinkRouting {

    /// The URL for `TemplateStore.receiveShareLink`, or null when this intent is not a
    /// routine (launcher tap, notification, foreign scheme).
    ///
    /// `ACTION_VIEW` only: the app declares no `SEND` filter, so accepting a `getagrip://`
    /// URL as `ACTION_SEND` text would answer for a door that does not exist.
    fun routineLink(action: String?, data: String?): String? {
        if (action != Intent.ACTION_VIEW) return null
        val url = data?.takeIf { it.isNotBlank() } ?: return null
        return url.takeIf { RoutineShare.isRoutineLink(it) }
    }
}
