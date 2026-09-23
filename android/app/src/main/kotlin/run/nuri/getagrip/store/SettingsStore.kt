// SPDX-License-Identifier: MPL-2.0
// Original contributions Copyright 2026 Nuri Bruner.

package run.nuri.getagrip.store

import android.content.Context
import androidx.compose.runtime.ProvidableCompositionLocal
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import run.nuri.getagrip.engine.GaugeKind
import java.util.UUID
import run.nuri.getagrip.ui.units.WeightUnit
import run.nuri.getagrip.ui.units.WeightUnits

/// Which gauge the app is driving. Its own interface because `DeviceStore.init` chooses its
/// client from it, so it must be available synchronously — see `SettingsStore`.
interface GaugeKindStore {
    fun load(): GaugeKind
    fun save(kind: GaugeKind)
}

/// The slice of settings `TemplateStore` and `AndroidAlarmScheduler` write to — its own
/// interface so the hub is testable without a `Context`, a DataStore file, or cross-test
/// preference bleed (iOS resets `UserDefaults` keys in `makeWorld()`).
///
/// TRANSLATION NOTE: `val` plus `setX(…)` rather than `var`, which would force a public
/// setter; every write must go through the one cached-then-persisted door.
interface RoutineSettings {
    val lastStartedRoutineID: UUID?
    val lastStartedDayRaw: Int
    val draftStash: String?
    val didAskNotificationPermission: Boolean

    /// What the answer WAS: `didAskNotificationPermission` says the dialog was raised, this
    /// says it came back no — "you will be asked when you save" versus "reminders cannot
    /// fire". The live system check cannot tell them apart.
    val deniedNotifications: Boolean
    val scheduledReminderIdentifiers: Set<String>

    /// Which version of the one-shot training-day repair has run on this device; 0 = never.
    /// See `TemplateStore.repairTrainingDaysOnce`.
    val trainingDayRepairVersion: Int

    fun setLastStartedRoutineID(value: UUID?)
    fun setLastStartedDayRaw(value: Int)
    fun setDraftStash(value: String?)
    fun setDidAskNotificationPermission(value: Boolean)
    fun setDeniedNotifications(value: Boolean)
    fun setScheduledReminderIdentifiers(value: Set<String>)
    fun setTrainingDayRepairVersion(value: Int)
}

/// The in-memory counterpart, for tests. A fresh one is exactly a fresh install.
class InMemoryRoutineSettings : RoutineSettings {
    private var routineID: UUID? = null
    private var dayRaw: Int = 0
    private var stash: String? = null
    private var asked: Boolean = false
    private var denied: Boolean = false
    private var scheduled: Set<String> = emptySet()
    private var repairVersion: Int = 0

    override val lastStartedRoutineID: UUID? get() = routineID
    override val lastStartedDayRaw: Int get() = dayRaw
    override val draftStash: String? get() = stash
    override val didAskNotificationPermission: Boolean get() = asked
    override val deniedNotifications: Boolean get() = denied
    override val scheduledReminderIdentifiers: Set<String> get() = scheduled
    override val trainingDayRepairVersion: Int get() = repairVersion

    override fun setLastStartedRoutineID(value: UUID?) { routineID = value }
    override fun setLastStartedDayRaw(value: Int) { dayRaw = value }
    override fun setDraftStash(value: String?) { stash = value }
    override fun setDidAskNotificationPermission(value: Boolean) { asked = value }
    override fun setDeniedNotifications(value: Boolean) { denied = value }
    override fun setScheduledReminderIdentifiers(value: Set<String>) { scheduled = value }
    override fun setTrainingDayRepairVersion(value: Int) { repairVersion = value }
}

private val Context.settingsDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "getagrip.settings",
)

/// The handful of preferences that are not part of a routine.
///
/// Preferences DataStore, NOT Room: a synced settings ROW means duplicate-row headaches on
/// every device, and none of these values wants to sync.
///
/// DEVICE-LOCAL by construction, which two values depend on: a synced "routine I last
/// started" is the classic second-device bug.
///
/// **The first read BLOCKS.** `DeviceStore.init` picks its client from `gauge.kind` before
/// any frame (free with iOS's `UserDefaults`, not DataStore). One small file, once;
/// afterwards reads come from the in-memory cache (Compose state) and writes are
/// fire-and-forget.
///
/// **Fire-and-forget, but IN ORDER.** Separate `launch`es on the IO pool let an older write
/// land last: a draft stash came back after Save cleared it, and a replan's reminder-id
/// list (what the NEXT replan cancels from) was overwritten by its predecessor. All writes
/// go through ONE `SerialWriteLane`.
@Stable
class SettingsStore(
    context: Context,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
) : GaugeKindStore, RoutineSettings {

    private val dataStore = context.applicationContext.settingsDataStore

    private companion object {
        /// **These keys and raw values are a STORAGE FORMAT**: renaming one silently resets
        /// a chosen gauge, replays a seen tour, or re-asks for notification permission.
        val weightUnitKey = stringPreferencesKey("weightUnit")
        val gaugeKindKey = stringPreferencesKey("gauge.kind")
        val builderGuideDoneKey = booleanPreferencesKey("builderGuideDone")
        val didAskNotificationPermissionKey = booleanPreferencesKey("didAskNotificationPermission")
        val deniedNotificationsKey = booleanPreferencesKey("deniedNotifications")
        val lastStartedRoutineIDKey = stringPreferencesKey("lastStartedRoutineID")
        val lastStartedDayRawKey = intPreferencesKey("lastStartedDayRaw")
        val draftStashKey = stringPreferencesKey("draftStash")
        val shareCardStyleKey = stringPreferencesKey("shareCardStyle")
        val frezIntroSeenKey = booleanPreferencesKey("frezIntroSeen")
        val scheduledRemindersKey = stringPreferencesKey("reminders.scheduled")
        val trainingDayRepairKey = intPreferencesKey("repair.trainingDays.version")

        /// `tour.seen.<act>` — one key per act, holding a VERSION rather than a Bool, so a
        /// bumped act can run again for people who saw the old one.
        fun tourSeenKey(act: String) = intPreferencesKey("tour.seen.$act")

        const val tourSeenPrefix = "tour.seen."
    }

    /// One blocking read for every key, rather than one per accessor on the same file.
    private val loaded: Preferences = runBlocking { dataStore.data.first() }

    init { WeightUnits.current = WeightUnit.fromRaw(loaded[weightUnitKey]) }

    val weightUnit: WeightUnit get() = WeightUnits.current

    fun setWeightUnit(value: WeightUnit) {
        WeightUnits.current = value
        // No generation guard needed: the lane persists writes in order, so the last
        // written is the last chosen.
        write { it[weightUnitKey] = value.rawValue }
    }

    private var cachedGaugeKind: String? = loaded[gaugeKindKey]

    // Backing state. Public `val` + `setX(…)`, so every write updates the cache before
    // persisting.
    private var guideDone: Boolean by mutableStateOf(loaded[builderGuideDoneKey] ?: false)
    private var asked: Boolean by mutableStateOf(loaded[didAskNotificationPermissionKey] ?: false)
    private var denied: Boolean by mutableStateOf(loaded[deniedNotificationsKey] ?: false)
    private var routineID: UUID? by mutableStateOf(
        loaded[lastStartedRoutineIDKey]?.let { runCatching { UUID.fromString(it) }.getOrNull() }
    )
    private var dayRaw: Int by mutableStateOf(loaded[lastStartedDayRawKey] ?: 0)
    private var stash: String? by mutableStateOf(loaded[draftStashKey])
    private var cardStyle: String by mutableStateOf(loaded[shareCardStyleKey] ?: "white")
    private var frezIntro: Boolean by mutableStateOf(loaded[frezIntroSeenKey] ?: false)
    private var scheduled: Set<String> by mutableStateOf(
        loaded[scheduledRemindersKey]?.split('\n')?.filter { it.isNotEmpty() }?.toSet()
            ?: emptySet()
    )
    private var repairVersion: Int = loaded[trainingDayRepairKey] ?: 0
    private var tourSeen: Map<String, Int> by mutableStateOf(
        loaded.asMap().mapNotNull { (key, value) ->
            if (!key.name.startsWith(tourSeenPrefix)) return@mapNotNull null
            (value as? Int)?.let { key.name.removePrefix(tourSeenPrefix) to it }
        }.toMap()
    )

    /// The builder's five coach cards: retired on first save, replayable from Settings, so
    /// a preference, not a flag.
    val builderGuideDone: Boolean get() = guideDone

    /// One-shot: the permission ask happens once, on the first Save with reminders on.
    /// Never at launch, never gating anything.
    override val didAskNotificationPermission: Boolean get() = asked

    /// The dialog came back NO. Observable, because the builder's reminder rows are on
    /// screen when the answer arrives. Never cleared by the app: the system check overrides
    /// it once granted in Settings — see `EveryDaySection`.
    override val deniedNotifications: Boolean get() = denied

    /// Rung 2 of Today's selection rule: the routine started today on THIS device.
    override val lastStartedRoutineID: UUID? get() = routineID

    /// `DayStamp.raw` of the day `lastStartedRoutineID` was written, so the suggestion
    /// expires at midnight. 0 is 1970-01-01, never today, so a fresh install suggests
    /// nothing.
    override val lastStartedDayRaw: Int get() = dayRaw

    /// A debounced rescue copy of an in-progress draft — create/first-run only, cleared on
    /// BOTH Save and Cancel. Restoring a stale draft into an EDIT could overwrite a merge
    /// the user never saw.
    ///
    /// TRANSLATION NOTE: `Data?` on iOS; a String here because `BlobCodec` produces JSON
    /// TEXT and DataStore has no byte-array type.
    override val draftStash: String? get() = stash

    /// Which card the calendar share sheet draws — white, dark or frosted.
    val shareCardStyle: String get() = cardStyle

    /// One-shot: the note Frez asks to show the first time the Dyno is selected has been
    /// read on this device. Never shown for any other gauge.
    val frezIntroSeen: Boolean get() = frezIntro

    /// The reminder identifiers this app believes it has scheduled. `AlarmManager` alarms
    /// cannot be enumerated, so the planner's "note, add, THEN drop the remainder" rule
    /// needs this note to survive process death, or a relaunch could not cancel an alarm
    /// the new plan dropped.
    override val scheduledReminderIdentifiers: Set<String> get() = scheduled

    override val trainingDayRepairVersion: Int get() = repairVersion

    /// The version of `act` seen, or 0 for never. A map because `TourAct` is the tour's
    /// vocabulary, not this file's.
    fun tourSeenVersion(act: String): Int = tourSeen[act] ?: 0

    // MARK: - Writes
    //
    // Every setter updates the cache FIRST, then persists: a read right after a write must
    // answer with it, and DataStore's read-back is a suspend away.

    fun setBuilderGuideDone(value: Boolean) {
        guideDone = value
        write { it[builderGuideDoneKey] = value }
    }

    override fun setDidAskNotificationPermission(value: Boolean) {
        asked = value
        write { it[didAskNotificationPermissionKey] = value }
    }

    override fun setDeniedNotifications(value: Boolean) {
        denied = value
        write { it[deniedNotificationsKey] = value }
    }

    override fun setLastStartedRoutineID(value: UUID?) {
        routineID = value
        write {
            if (value == null) it.remove(lastStartedRoutineIDKey)
            else it[lastStartedRoutineIDKey] = value.toString()
        }
    }

    override fun setLastStartedDayRaw(value: Int) {
        dayRaw = value
        write { it[lastStartedDayRawKey] = value }
    }

    override fun setDraftStash(value: String?) {
        stash = value
        write { if (value == null) it.remove(draftStashKey) else it[draftStashKey] = value }
    }

    fun setFrezIntroSeen(value: Boolean) {
        frezIntro = value
        write { it[frezIntroSeenKey] = value }
    }

    fun setShareCardStyle(value: String) {
        cardStyle = value
        write { it[shareCardStyleKey] = value }
    }

    override fun setScheduledReminderIdentifiers(value: Set<String>) {
        scheduled = value
        write { it[scheduledRemindersKey] = value.joinToString("\n") }
    }

    override fun setTrainingDayRepairVersion(value: Int) {
        repairVersion = value
        write { it[trainingDayRepairKey] = value }
    }

    fun setTourSeenVersion(act: String, version: Int) {
        tourSeen = tourSeen + (act to version)
        write { it[tourSeenKey(act)] = version }
    }

    /// Clears every act's flag — Settings › "Take the tour again".
    fun clearTourSeen(acts: List<String>) {
        tourSeen = emptyMap()
        write { prefs -> acts.forEach { prefs.remove(tourSeenKey(it)) } }
    }

    // MARK: - GaugeKindStore

    /// An unrecognised raw value (a newer build's kind, then a downgrade) reads as the
    /// Progressor rather than refusing to build a client — the CONSERVATIVE direction
    /// `SessionKind` also follows.
    override fun load(): GaugeKind =
        cachedGaugeKind?.let { GaugeKind.fromRaw(it) } ?: GaugeKind.progressor

    override fun save(kind: GaugeKind) {
        cachedGaugeKind = kind.rawValue
        write { it[gaugeKindKey] = kind.rawValue }
    }

    private val lane = SerialWriteLane<(MutablePreferences) -> Unit>(scope) { batch ->
        dataStore.edit { prefs -> batch.forEach { it(prefs) } }
    }

    private fun write(block: (MutablePreferences) -> Unit) {
        lane.submit(block)
    }
}

/// The in-memory gauge half, for tests and the "relaunch" round trip: two stores over one
/// of these is what a fresh launch reads.
class InMemoryGaugeKindStore(private var raw: String? = null) : GaugeKindStore {
    override fun load(): GaugeKind = raw?.let { GaugeKind.fromRaw(it) } ?: GaugeKind.progressor

    override fun save(kind: GaugeKind) {
        raw = kind.rawValue
    }

    /// Writes a raw value the enum has never heard of — the only way to reproduce a newer
    /// build's kind.
    fun writeRaw(value: String?) {
        raw = value
    }
}

/// See `LocalDeviceStore` for why this is `staticCompositionLocalOf`.
val LocalSettingsStore: ProvidableCompositionLocal<SettingsStore> = staticCompositionLocalOf {
    error("LocalSettingsStore was read outside a CompositionLocalProvider")
}
