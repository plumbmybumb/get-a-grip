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
import run.nuri.getagrip.ui.tour.LocalTourController

/// One activity, Compose all the way down. `enableEdgeToEdge()` before `super.onCreate` so
/// the very first frame already draws behind the system bars — the slate field is the
/// ground of every screen, including under the status bar.
class MainActivity : ComponentActivity() {

    private lateinit var device: DeviceStore
    private lateinit var templates: TemplateStore
    private lateinit var clock: DayClock

    /// The one in-flight permission answer. A second Connect tap while the dialog is open
    /// replaces it rather than queueing: there is one question on screen and one answer
    /// coming back.
    private var pendingPermissionResult: ((Boolean) -> Unit)? = null

    private val requestBluetooth = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { granted ->
        val callback = pendingPermissionResult
        pendingPermissionResult = null
        callback?.invoke(bluetoothPermissions.all { granted[it] == true })
    }

    private val gate = PermissionGate { onResult ->
        // Already granted answers SYNCHRONOUSLY — a Connect tap on a permitted app must not
        // cost a frame, and it is what makes `DeviceStore.connect()` behave exactly as the
        // iOS one does once the prompt has been seen.
        if (bluetoothPermissions.all(::isGranted)) {
            onResult(true)
        } else {
            pendingPermissionResult = onResult
            requestBluetooth.launch(bluetoothPermissions)
        }
    }

    /// The notification answer, kept separately from the Bluetooth one. They are raised by
    /// different taps on different screens and either can be on screen while the other is
    /// not; one shared slot would let a Connect tap swallow a Save's answer.
    private var pendingNotificationResult: ((Boolean) -> Unit)? = null

    private val requestNotifications = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        val callback = pendingNotificationResult
        pendingNotificationResult = null
        callback?.invoke(granted)
    }

    /// **Asked on the first Save of a routine that actually wants reminders, never at
    /// launch** — by then the user has been through the builder and seen both times on
    /// screen, so the OS dialog arrives with its reason already on the previous screen. The
    /// twin of the Bluetooth gate above, answered synchronously when already granted for
    /// the same reason: a Save must not cost a frame.
    ///
    /// This surface is ONLY for reminders. The session's Live Update needs the same
    /// permission to be VISIBLE, but a foreground service runs whether or not it can be
    /// seen — so a session never raises this dialog, and a denial costs the card, not the
    /// workout.
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

        val app = application as GetAGripApplication
        device = app.deviceStore(useMock = DeviceStore.mockRequestedAtLaunch(intent))
        templates = app.templates
        clock = app.clock
        // **The gate belongs to the Activity, the store to the process.** A permission
        // dialog needs a live Activity to come back to, so the store borrows one and gives
        // it back below rather than holding a reference across a rotation.
        device.permissionGate = gate
        // Same loan, same reason: a permission dialog needs a live Activity to come back to.
        templates.notificationPermissionGate = notificationGate
        // Seeders first, then the first derived publish — see `startStores`.
        app.startStores(intent)
        // A shared routine that COLD-LAUNCHED the app. The inbox is filled before the first
        // composition, and `TodayScreen` sweeps whatever is already waiting when it appears.
        receiveShareLink(intent)

        setContent {
            GetAGripTheme {
                CompositionLocalProvider(
                    LocalDeviceStore provides device,
                    LocalTemplateStore provides templates,
                    LocalSettingsStore provides app.settings,
                    LocalDayClock provides clock,
                    LocalHistoryFeed provides app.historyFeed,
                    // ONE controller for the whole process, so the three acts — minutes or
                    // days apart, and hosted by three different screens — are the same tour
                    // rather than three that cannot see each other.
                    LocalTourController provides app.tour,
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

    /// The app is `singleTask`, so a link tapped while it is already running does NOT come
    /// back through `onCreate` — it arrives here, on the existing instance, and a handler
    /// that only read `onCreate`'s intent would silently drop every scan after the first.
    ///
    /// `setIntent` because `getIntent()` otherwise keeps returning the LAUNCHER intent for
    /// the life of the activity, and anything asked later (the mock-device launch argument,
    /// a future extra) would be answering about a launch two links ago.
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        receiveShareLink(intent)
    }

    /// **The URL handler NEVER presents anything.** It puts the link in the store's inbox and
    /// stops; `TodayScreen` drains that inbox when it can see that nothing else holds the
    /// screen. The iOS rule this mirrors was measured (2026-08-19): presenting from the root
    /// while a descendant's full-screen cover was up TORE THE COVER DOWN — a running session
    /// died unlogged, a dirty builder lost its edits. Compose tears down nothing, but the
    /// second half of the rule still bites here: a sheet appearing over a live workout is
    /// wrong however cleanly it draws.
    ///
    /// `isRoutineLink` is asked FIRST — see `ShareLinkRouting`.
    private fun receiveShareLink(intent: Intent?) {
        val url = ShareLinkRouting.routineLink(intent?.action, intent?.data?.toString()) ?: return
        templates.receiveShareLink(url)
    }

    /// **Refresh the clock, THEN ask the store to recompute — in that order.**
    ///
    /// A phone left open past local midnight must flip "2 of 2 today" back to "0 of 2"
    /// without a relaunch. The clock is refreshed HERE and not inside the store: a device
    /// asleep across midnight may not deliver `ACTION_DATE_CHANGED` until it is active
    /// again, and the store only ever REACTS to whatever day the clock reports. This is
    /// `DoigtApp`'s `scenePhase == .active` order, line for line.
    override fun onResume() {
        super.onResume()
        clock.refresh()
        lifecycleScope.launch { templates.refreshIfDayChanged() }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        // Compose observes uiMode and redraws in place. Refresh system-bar icon
        // contrast too, while preserving the runner's transparent navigation bar.
        val navigationContrast = window.isNavigationBarContrastEnforced
        enableEdgeToEdge()
        window.isNavigationBarContrastEnforced = navigationContrast
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
        /// minSdk is 31, so these two are always the right pair and there is no legacy
        /// `BLUETOOTH` / `ACCESS_FINE_LOCATION` branch to carry. SCAN is declared
        /// `neverForLocation` in the manifest, which is what keeps the location prompt out
        /// of a finger-training app.
        val bluetoothPermissions = arrayOf(
            Manifest.permission.BLUETOOTH_SCAN,
            Manifest.permission.BLUETOOTH_CONNECT,
        )
    }
}

/// The intent → URL decision, as a pure function of the two strings an `Intent` carries, so
/// the routing rule is a JVM test rather than something only an instrumented run can check.
///
/// **A link that is not ours is left completely alone** — not refused, not alerted about,
/// just ignored. `isRoutineLink` is the cheap shape-only question (scheme and host, no
/// payload work), and asking it first is what stops the app answering for some other
/// handler's link that happened to be routed here. A link that PASSES it and then fails to
/// decode gets an error the user can read; that is the store's job, not this one's.
object ShareLinkRouting {

    /// The URL to hand to `TemplateStore.receiveShareLink`, or null when this intent is not
    /// a routine at all — a launcher tap, a notification, a foreign scheme.
    ///
    /// `ACTION_VIEW` only. A `getagrip://` URL can also ride an `ACTION_SEND` as plain text,
    /// but the app declares no `SEND` filter, so accepting one here would be answering for a
    /// door that does not exist.
    fun routineLink(action: String?, data: String?): String? {
        if (action != Intent.ACTION_VIEW) return null
        val url = data?.takeIf { it.isNotBlank() } ?: return null
        return url.takeIf { RoutineShare.isRoutineLink(it) }
    }
}
