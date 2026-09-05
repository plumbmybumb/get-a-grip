// SPDX-License-Identifier: MPL-2.0
// Original contributions Copyright 2026 Nuri Bruner.

package run.nuri.getagrip

import android.app.Application
import android.content.Intent
import android.content.res.Configuration
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.ProcessLifecycleOwner
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import run.nuri.getagrip.ble.SystemHostClock
import run.nuri.getagrip.data.GetAGripDatabase
import run.nuri.getagrip.debug.Seeds
import run.nuri.getagrip.engine.L10n
import run.nuri.getagrip.l10n.AppResources
import run.nuri.getagrip.l10n.STRING_KEYS
import run.nuri.getagrip.runner.LiveUpdateNotification
import run.nuri.getagrip.runner.SessionForegroundService
import run.nuri.getagrip.store.AlarmScheduler
import run.nuri.getagrip.store.AndroidAlarmScheduler
import run.nuri.getagrip.store.AndroidGaugeClientFactory
import run.nuri.getagrip.store.DayClock
import run.nuri.getagrip.store.DayClockReceiver
import run.nuri.getagrip.store.DeviceStore
import run.nuri.getagrip.store.ReminderAlarms
import run.nuri.getagrip.store.RoomStoreGateway
import run.nuri.getagrip.store.HistoryFeed
import run.nuri.getagrip.store.StoreGateway
import run.nuri.getagrip.store.asHistorySource
import run.nuri.getagrip.store.SettingsStore
import run.nuri.getagrip.store.StorageMode
import run.nuri.getagrip.store.TemplateStore
import run.nuri.getagrip.ui.tour.SettingsTourSeenStore
import run.nuri.getagrip.ui.tour.TourController

/// Process-wide setup lives here, the engine's string lookup included.
class GetAGripApplication : Application() {

    /// **Every gauge callback lands on the main thread**, which is the Android twin of the
    /// iOS client protocol being `@MainActor`. `immediate` and not plain `Main`: a client
    /// that can answer in the same turn must publish its state before the caller looks.
    val gaugeScope: CoroutineScope by lazy {
        CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    }

    /// The store's own scope: undo timers, the reminder replan, and whatever else outlives
    /// the call that started it. Main, because everything it touches is snapshot state a
    /// composition reads.
    val storeScope: CoroutineScope by lazy {
        CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    }

    val settings: SettingsStore by lazy { SettingsStore(this) }

    val database: GetAGripDatabase by lazy { GetAGripDatabase.open(this) }

    val clock: DayClock by lazy { DayClock() }

    /// THE SPOTLIGHT TOUR — one controller for the whole PROCESS, not one per Activity.
    ///
    /// Its three acts happen minutes or days apart and are hosted by three different screens,
    /// so they have to be the same tour; and a rotation must not restart an act you were
    /// halfway through, which is exactly what an Activity-scoped controller would do. The
    /// `seen` flags live one level down again, in `SettingsStore`, so a relaunch honours a
    /// Skip.
    val tour: TourController by lazy { TourController(SettingsTourSeenStore(settings)) }

    private val scheduler: AlarmScheduler by lazy { AndroidAlarmScheduler(this, settings) }

    /// The ONE door to Room. The store writes through it and the history feed reads through
    /// it, so both share the same serial lane and a read can never overtake a write.
    private val gateway: StoreGateway by lazy { RoomStoreGateway(database) }

    /// The read side for whole tables (History, Maxes) — the `@Query` twin.
    val historyFeed: HistoryFeed by lazy { HistoryFeed(gateway.asHistorySource(), storeScope) }

    /// The hub. Built lazily and held for the life of the PROCESS, not the Activity, so a
    /// rotation neither rebuilds the world nor drops the ten-second undo offer.
    val templates: TemplateStore by lazy {
        TemplateStore(
            gateway = gateway,
            clock = clock,
            settings = settings,
            scheduler = scheduler,
            scope = storeScope,
            storageMode = StorageMode.localOnly,
        )
    }

    /// **Seed, THEN publish.** The debug seeders run before the first `syncDerived()`, so
    /// the first frame already renders the seeded world — otherwise a headless screenshot
    /// catches the pre-seed state. `intent` comes from the Activity, which is where a
    /// launch extra arrives.
    fun startStores(intent: Intent?) {
        if (started) return
        started = true
        ReminderAlarms.ensureChannel(this)
        // The session channel exists from launch so the very first Live Update has somewhere
        // to post: creating a channel is idempotent and free, and a notification posted to a
        // channel that does not exist yet is silently dropped.
        LiveUpdateNotification.ensureChannel(this)
        dayClockReceiver.register(this)
        observeProcessLifecycle()
        storeScope.launch {
            Seeds.apply(database, intent)
            templates.syncDerived()
            historyFeed.refresh()
        }
    }

    override fun onCreate() {
        super.onCreate()
        // Before anything can ask a grip for its name. `:engine` is pure Kotlin and owns
        // no resources, so this is the seam that gives it the app's — see `L10n`.
        installStringLookup()
        // **A process that has just started cannot have a session running.** If the last one
        // was killed mid-workout — force-stopped, or reclaimed by an OEM battery manager —
        // `RunnerSession.end()` never ran and its ongoing card was never cancelled, so it
        // would still be sitting on the lock screen saying "Holding". This is the one moment
        // that can be sure it is stale.
        SessionForegroundService.cancelStaleCard(this)
    }

    /// **The engine's display strings resolve through the app's string resources.**
    ///
    /// `L10n.tr`'s key is the ENGLISH sentence with Java specifiers — the same key the UI's
    /// own `tr(…)` uses and the same one iOS hands `String(localized:)` — so `STRING_KEYS`
    /// answers for both. What comes back is the TEMPLATE, not a formatted string: `L10n.tr`
    /// does the formatting, and `getString(id)` with no arguments deliberately does not.
    ///
    /// Re-installed on every configuration change. `getResources()` is re-read inside the
    /// lambda rather than captured, so a locale switched under a running process is already
    /// answered by the time this fires — the re-install is the belt to that braces, for a
    /// host that hands the app a genuinely new `Resources`.
    private fun installStringLookup() {
        L10n.lookup = { key -> STRING_KEYS[key]?.let { resources.getString(it) } }
        // The same resources, for the one thing a key-only lookup cannot do: choose a
        // plural form. See `trQuantity`.
        AppResources.current = resources
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        installStringLookup()
    }

    /// **The 45 s background grace hangs off the PROCESS's lifecycle, not an Activity's.**
    ///
    /// An Activity stopping is not the app leaving the foreground: a rotation stops and
    /// restarts one, and a per-Activity observer would schedule a disconnect for a gauge
    /// nobody put down. `ProcessLifecycleOwner` debounces exactly that — it reports STOP
    /// only when no Activity in the process is visible. It lives here rather than in
    /// `MainActivity` for the same reason `DeviceStore` does: the link belongs to the
    /// process.
    ///
    /// **A session streaming takes the `none` branch inside the store**, so this is only
    /// ever about an idle gauge left connected by a screen somebody walked away from. What
    /// keeps a real session alive is `SessionForegroundService`.
    ///
    /// Only wired once a store has been built — `deviceStore` is created by the Activity,
    /// and asking for one here would construct a Bluetooth client for a process that may
    /// only be servicing a boot broadcast.
    private fun observeProcessLifecycle() {
        ProcessLifecycleOwner.get().lifecycle.addObserver(
            LifecycleEventObserver { _, event ->
                val device = existing ?: return@LifecycleEventObserver
                when (event) {
                    Lifecycle.Event.ON_STOP -> {
                        device.recordScenePhase("background")
                        device.beginBackgroundGrace()
                    }

                    Lifecycle.Event.ON_START -> {
                        device.recordScenePhase("foreground")
                        device.cancelBackgroundGrace()
                    }

                    else -> Unit
                }
            },
        )
    }

    private var started = false

    /// Time, zone and midnight broadcasts. Registered here rather than in the Activity so
    /// a day that rolls while the app is backgrounded is already correct when it returns.
    private val dayClockReceiver: DayClockReceiver by lazy {
        DayClockReceiver(clock) {
            storeScope.launch { templates.refreshIfDayChanged() }
        }
    }

    /// **One store for the process, built lazily.** It owns the client and is the ONLY
    /// thing that talks to it. Built here rather than in the Activity so a rotation does
    /// not drop a live link; the Activity lends it a `PermissionGate` and takes it back in
    /// `onDestroy`.
    ///
    /// `useMock` is decided by the Activity's launch Intent, so it is passed in rather than
    /// read here — see `DeviceStore.mockRequestedAtLaunch`.
    fun deviceStore(useMock: Boolean): DeviceStore = existing ?: DeviceStore(
        useMock = useMock,
        scope = gaugeScope,
        kindStore = settings,
        clientFactory = AndroidGaugeClientFactory(this, gaugeScope, SystemHostClock),
        clock = SystemHostClock,
    ).also { existing = it }

    private var existing: DeviceStore? = null
}
