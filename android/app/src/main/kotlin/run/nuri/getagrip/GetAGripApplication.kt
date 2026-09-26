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
import run.nuri.getagrip.runner.FinishedSessionDraftStore
import run.nuri.getagrip.runner.LiveUpdateNotification
import run.nuri.getagrip.runner.SessionForegroundService
import run.nuri.getagrip.store.AlarmGraceBackstop
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

/// Process-wide setup lives here, the engine's string lookup included.
class GetAGripApplication : Application() {

    /// **Every gauge callback lands on the main thread**, the twin of iOS's `@MainActor`
    /// client protocol. `immediate`: a client answering in the same turn must publish
    /// before the caller looks.
    val gaugeScope: CoroutineScope by lazy {
        CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    }

    /// The store's own scope for undo timers, the reminder replan and other outliving work.
    /// Main, because it touches snapshot state.
    val storeScope: CoroutineScope by lazy {
        CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    }

    val settings: SettingsStore by lazy { SettingsStore(this) }

    val database: GetAGripDatabase by lazy { GetAGripDatabase.open(this) }

    val clock: DayClock by lazy { DayClock() }

    /// The one finished-but-unsaved session, if any — see `FinishedSessionDraft`.
    val finishedSessionDrafts: FinishedSessionDraftStore by lazy {
        FinishedSessionDraftStore(java.io.File(filesDir, FinishedSessionDraftStore.FILE_NAME))
    }

    private val scheduler: AlarmScheduler by lazy { AndroidAlarmScheduler(this, settings) }

    /// The ONE door to Room. Store writes and feed reads share its serial lane, so a read
    /// can never overtake a write.
    private val gateway: StoreGateway by lazy { RoomStoreGateway(database) }

    /// The read side for whole tables (History, Maxes) — the `@Query` twin.
    val historyFeed: HistoryFeed by lazy { HistoryFeed(gateway.asHistorySource(), storeScope, revision = { templates.writeRevision }) }

    /// The hub, held for the PROCESS's life so a rotation neither rebuilds the world nor
    /// drops the undo offer.
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

    /// **Seed, THEN publish.** Debug seeders run before the first `syncDerived()` so the
    /// first frame (and a headless screenshot) shows the seeded world. `intent` comes from
    /// the Activity.
    fun startStores(intent: Intent?) {
        if (started) return
        started = true
        ReminderAlarms.ensureChannel(this)
        // The session channel exists from launch, so the first Live Update has somewhere to
        // post; a notification to a missing channel is silently dropped.
        LiveUpdateNotification.ensureChannel(this)
        dayClockReceiver.register(this)
        clock.scheduleRolloverRefresh(storeScope) {
            storeScope.launch { templates.refreshIfDayChanged() }
        }
        observeProcessLifecycle()
        storeScope.launch {
            Seeds.apply(database, intent)
            templates.syncDerived()
            historyFeed.refresh()
            // AFTER the first derived world: the repair reads every row's day column, and
            // the first frame must not wait on a read that grows each week. Once per device
            // (`repairTrainingDaysOnce`), republishing only if a row moved.
            if (templates.repairTrainingDaysOnce() > 0) {
                templates.syncDerived()
                historyFeed.refresh()
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        // Before anything asks a grip for its name: `:engine` owns no resources, so this
        // gives it the app's — see `L10n`.
        installStringLookup()
        // A fresh process cannot have a session running — see
        // `SessionForegroundService.cancelStaleCard`.
        SessionForegroundService.cancelStaleCard(this)
    }

    /// **The engine's display strings resolve through the app's string resources.**
    ///
    /// `L10n.tr`'s key is the ENGLISH sentence with Java specifiers — the UI's `tr(…)` key
    /// and iOS's `String(localized:)` key — so `STRING_KEYS` answers both. It returns the
    /// TEMPLATE; `L10n.tr` formats, and `getString(id)` with no arguments must not.
    ///
    /// Re-installed on every configuration change. `getResources()` is re-read inside the
    /// lambda, so a locale switch is already answered; the re-install covers a host that
    /// hands over a genuinely new `Resources`.
    private fun installStringLookup() {
        L10n.lookup = { key -> STRING_KEYS[key]?.let { resources.getString(it) } }
        // The same resources for plural forms, which a key-only lookup cannot choose. See
        // `trQuantity`.
        AppResources.current = resources
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        installStringLookup()
    }

    /// **The 45 s background grace hangs off the PROCESS's lifecycle, not an Activity's.**
    /// A rotation stops and restarts an Activity, and a per-Activity observer would
    /// schedule a disconnect for a gauge nobody put down; `ProcessLifecycleOwner` reports
    /// STOP only when no Activity is visible. Here rather than in `MainActivity` because
    /// the link belongs to the process.
    ///
    /// **A streaming session takes the store's `none` branch**, so this only concerns an
    /// idle gauge; `SessionForegroundService` keeps real sessions alive.
    ///
    /// Wired only once a store exists: building `deviceStore` here would construct a
    /// Bluetooth client for a process maybe only servicing a boot broadcast.
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

    /// Time, zone and midnight broadcasts, registered here so a day rolled while
    /// backgrounded is correct on return.
    private val dayClockReceiver: DayClockReceiver by lazy {
        DayClockReceiver(clock) {
            storeScope.launch { templates.refreshIfDayChanged() }
        }
    }

    /// **One store for the process, built lazily**, the ONLY thing that talks to the
    /// client. Here rather than the Activity so a rotation keeps the link; the Activity
    /// lends a `PermissionGate` and takes it back in `onDestroy`.
    ///
    /// `useMock` comes from the Activity's launch Intent — see
    /// `DeviceStore.mockRequestedAtLaunch`.
    fun deviceStore(useMock: Boolean): DeviceStore = existing ?: DeviceStore(
        useMock = useMock,
        scope = gaugeScope,
        kindStore = settings,
        clientFactory = AndroidGaugeClientFactory(this, gaugeScope, SystemHostClock),
        clock = SystemHostClock,
        graceBackstop = AlarmGraceBackstop(this),
    ).also { existing = it }

    private var existing: DeviceStore? = null

    /// The store if ever built: the grace backstop's receiver must not construct a
    /// Bluetooth client for a process woken only for its alarm.
    val existingDeviceStore: DeviceStore? get() = existing
}
