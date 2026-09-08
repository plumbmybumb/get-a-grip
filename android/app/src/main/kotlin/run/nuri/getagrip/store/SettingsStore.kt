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
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import run.nuri.getagrip.engine.GaugeKind
import java.util.UUID
import run.nuri.getagrip.ui.units.WeightUnit
import run.nuri.getagrip.ui.units.WeightUnits

/// Which gauge the app is driving. Split out from the rest of the settings surface
/// because `DeviceStore.init` chooses its client from the stored kind, so this one answer
/// has to be available synchronously — see `SettingsStore`'s note.
interface GaugeKindStore {
    fun load(): GaugeKind
    fun save(kind: GaugeKind)
}

/// The slice of the settings surface `TemplateStore` and `AndroidAlarmScheduler` write to.
///
/// Named as its own interface for the same reason `GaugeKindStore` is: it keeps the hub
/// testable without a `Context`, a DataStore file, or the cross-test bleed a real
/// preference file causes — which on iOS is handled instead by resetting five
/// `UserDefaults` keys inside `makeWorld()`.
///
/// TRANSLATION NOTE: `val` plus an explicit `setX(…)` rather than a `var`. A `var` in an
/// interface would force the implementation's setter to be public, and these are all
/// cached-then-persisted writes that must go through one door.
interface RoutineSettings {
    val lastStartedRoutineID: UUID?
    val lastStartedDayRaw: Int
    val draftStash: String?
    val didAskNotificationPermission: Boolean

    /// What the answer WAS. `didAskNotificationPermission` only says the dialog was raised;
    /// this says it came back no, which is the difference between "you will be asked when
    /// you save" and "reminders cannot fire". The builder needs to tell those apart, and the
    /// live system check cannot: an app that has never been asked is also not permitted.
    val deniedNotifications: Boolean
    val scheduledReminderIdentifiers: Set<String>

    fun setLastStartedRoutineID(value: UUID?)
    fun setLastStartedDayRaw(value: Int)
    fun setDraftStash(value: String?)
    fun setDidAskNotificationPermission(value: Boolean)
    fun setDeniedNotifications(value: Boolean)
    fun setScheduledReminderIdentifiers(value: Set<String>)
}

/// The in-memory counterpart, for tests. A fresh one is exactly a fresh install.
class InMemoryRoutineSettings : RoutineSettings {
    private var routineID: UUID? = null
    private var dayRaw: Int = 0
    private var stash: String? = null
    private var asked: Boolean = false
    private var denied: Boolean = false
    private var scheduled: Set<String> = emptySet()

    override val lastStartedRoutineID: UUID? get() = routineID
    override val lastStartedDayRaw: Int get() = dayRaw
    override val draftStash: String? get() = stash
    override val didAskNotificationPermission: Boolean get() = asked
    override val deniedNotifications: Boolean get() = denied
    override val scheduledReminderIdentifiers: Set<String> get() = scheduled

    override fun setLastStartedRoutineID(value: UUID?) { routineID = value }
    override fun setLastStartedDayRaw(value: Int) { dayRaw = value }
    override fun setDraftStash(value: String?) { stash = value }
    override fun setDidAskNotificationPermission(value: Boolean) { asked = value }
    override fun setDeniedNotifications(value: Boolean) { denied = value }
    override fun setScheduledReminderIdentifiers(value: Set<String>) { scheduled = value }
}

private val Context.settingsDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "getagrip.settings",
)

/// The handful of preferences that are not part of a routine.
///
/// Backed by Preferences DataStore, NOT Room: a synced settings ROW means duplicate-row
/// headaches on every device that creates "the" settings row, and none of these values
/// wants to sync anyway.
///
/// Everything in here is DEVICE-LOCAL by construction. That is a property two of these
/// values depend on rather than an accident: a synced "the routine I last started" flag
/// is the classic second-device bug, where the phone you left at home decides what your
/// tablet opens on.
///
/// **The first read BLOCKS.** `DeviceStore.init` picks its client from `gauge.kind`
/// before any frame is drawn, which is what `UserDefaults` gives iOS for free and
/// DataStore does not. One small file, once, at launch; everything afterwards is served
/// from the in-memory cache and every write is fire-and-forget. Reads are Compose state,
/// so a screen re-reads by observing rather than by polling.
@Stable
class SettingsStore(
    context: Context,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
) : GaugeKindStore, RoutineSettings {

    private val dataStore = context.applicationContext.settingsDataStore

    private companion object {
        /// **These keys and the raw values they hold are a STORAGE FORMAT** — renaming
        /// one silently resets somebody's chosen gauge, replays a tour they have seen, or
        /// re-asks for notification permission. Same class of trap as the grip key's
        /// letter order.
        val weightUnitKey = stringPreferencesKey("weightUnit")
        val gaugeKindKey = stringPreferencesKey("gauge.kind")
        val builderGuideDoneKey = booleanPreferencesKey("builderGuideDone")
        val didAskNotificationPermissionKey = booleanPreferencesKey("didAskNotificationPermission")
        val deniedNotificationsKey = booleanPreferencesKey("deniedNotifications")
        val lastStartedRoutineIDKey = stringPreferencesKey("lastStartedRoutineID")
        val lastStartedDayRawKey = intPreferencesKey("lastStartedDayRaw")
        val draftStashKey = stringPreferencesKey("draftStash")
        val shareCardStyleKey = stringPreferencesKey("shareCardStyle")
        val scheduledRemindersKey = stringPreferencesKey("reminders.scheduled")

        /// `tour.seen.<act>` — one key per act, holding a VERSION rather than a Bool.
        /// When the tour gains an act, a bumped version is what lets it run again for
        /// people who saw the old one, and a Bool would have no way to say that.
        fun tourSeenKey(act: String) = intPreferencesKey("tour.seen.$act")

        const val tourSeenPrefix = "tour.seen."
    }

    /// One blocking read for every key — the alternative is one blocking read per
    /// accessor, and they all land in the same file.
    private val loaded: Preferences = runBlocking { dataStore.data.first() }

    init { WeightUnits.current = WeightUnit.fromRaw(loaded[weightUnitKey]) }

    private val weightWriteGeneration = java.util.concurrent.atomic.AtomicLong()
    val weightUnit: WeightUnit get() = WeightUnits.current

    fun setWeightUnit(value: WeightUnit) {
        WeightUnits.current = value
        val generation = weightWriteGeneration.incrementAndGet()
        // The shared IO scope may enqueue rapid toggles out of order. An older write
        // cannot replace the latest visible choice after the app next launches.
        write { if (generation == weightWriteGeneration.get()) it[weightUnitKey] = value.rawValue }
    }

    private var cachedGaugeKind: String? = loaded[gaugeKindKey]

    // Backing state. `val` + `setX(…)` in public, so every write goes through the one
    // door that updates the cache before it persists.
    private var guideDone: Boolean by mutableStateOf(loaded[builderGuideDoneKey] ?: false)
    private var asked: Boolean by mutableStateOf(loaded[didAskNotificationPermissionKey] ?: false)
    private var denied: Boolean by mutableStateOf(loaded[deniedNotificationsKey] ?: false)
    private var routineID: UUID? by mutableStateOf(
        loaded[lastStartedRoutineIDKey]?.let { runCatching { UUID.fromString(it) }.getOrNull() }
    )
    private var dayRaw: Int by mutableStateOf(loaded[lastStartedDayRawKey] ?: 0)
    private var stash: String? by mutableStateOf(loaded[draftStashKey])
    private var cardStyle: String by mutableStateOf(loaded[shareCardStyleKey] ?: "white")
    private var scheduled: Set<String> by mutableStateOf(
        loaded[scheduledRemindersKey]?.split('\n')?.filter { it.isNotEmpty() }?.toSet()
            ?: emptySet()
    )
    private var tourSeen: Map<String, Int> by mutableStateOf(
        loaded.asMap().mapNotNull { (key, value) ->
            if (!key.name.startsWith(tourSeenPrefix)) return@mapNotNull null
            (value as? Int)?.let { key.name.removePrefix(tourSeenPrefix) to it }
        }.toMap()
    )

    /// The builder's five inline coach cards. Retired on the first save and replayable
    /// from Settings › "Show the setup guide again", so it is a preference, not a flag.
    val builderGuideDone: Boolean get() = guideDone

    /// One-shot: the contextual permission ask happens on the first Save with reminders
    /// on, once. Never at launch, and never gating anything.
    override val didAskNotificationPermission: Boolean get() = asked

    /// The dialog was raised and came back NO. Observable, because the builder's reminder
    /// rows are on screen when the answer arrives and the note under them has to appear
    /// without a re-entry. Never cleared by the app: it is corrected by the system check
    /// the moment the permission is granted in Settings — see `EveryDaySection`.
    override val deniedNotifications: Boolean get() = denied

    /// Rung 2 of Today's selection rule: the routine started today on THIS device.
    override val lastStartedRoutineID: UUID? get() = routineID

    /// `DayStamp.raw` of the day `lastStartedRoutineID` was written, which is what makes
    /// the suggestion expire at midnight instead of persisting for a week. A raw Int
    /// because that is what the preference store can hold; 0 is 1970-01-01, which is
    /// never today, so a fresh install has no suggestion.
    override val lastStartedDayRaw: Int get() = dayRaw

    /// A debounced rescue copy of an in-progress routine draft — create/first-run only,
    /// cleared on BOTH Save and Cancel. Restoring a stale draft into an EDIT could
    /// overwrite a merge the user never saw.
    ///
    /// TRANSLATION NOTE: `Data?` on iOS, a String here, because `BlobCodec` produces
    /// canonical JSON TEXT and DataStore has no byte-array preference type.
    override val draftStash: String? get() = stash

    /// Which card the calendar share sheet draws — white, dark or frosted.
    val shareCardStyle: String get() = cardStyle

    /// The reminder identifiers this app believes it has scheduled.
    ///
    /// Android has no `pendingNotificationRequests()`: an `AlarmManager` alarm cannot be
    /// enumerated, only replaced or cancelled through its own `PendingIntent`. So the
    /// planner's "note what is already scheduled, add, THEN drop the remainder" rule needs
    /// somewhere to keep that note across a process death — otherwise a relaunch would
    /// have no way to cancel an alarm the new plan no longer covers.
    override val scheduledReminderIdentifiers: Set<String> get() = scheduled

    /// The version of `act` the user has seen, or 0 for never. Kept in a map rather than
    /// as three properties because `TourAct` is the tour's vocabulary, not this file's.
    fun tourSeenVersion(act: String): Int = tourSeen[act] ?: 0

    // MARK: - Writes
    //
    // Every setter updates the cache FIRST and persists after: a preference read a
    // microsecond after it was written must answer with what was written, and DataStore's
    // own read-back is a suspend away.

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

    fun setShareCardStyle(value: String) {
        cardStyle = value
        write { it[shareCardStyleKey] = value }
    }

    override fun setScheduledReminderIdentifiers(value: Set<String>) {
        scheduled = value
        write { it[scheduledRemindersKey] = value.joinToString("\n") }
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

    /// An unrecognised raw value — a kind written by a newer build, then a downgrade —
    /// reads as the Progressor rather than refusing to build a client. That is the
    /// CONSERVATIVE direction, the same rule `SessionKind` follows: under-serving one
    /// device beats a screen that never connects.
    override fun load(): GaugeKind =
        cachedGaugeKind?.let { GaugeKind.fromRaw(it) } ?: GaugeKind.progressor

    override fun save(kind: GaugeKind) {
        cachedGaugeKind = kind.rawValue
        write { it[gaugeKindKey] = kind.rawValue }
    }

    private fun write(block: (MutablePreferences) -> Unit) {
        scope.launch { dataStore.edit(block) }
    }
}

/// The in-memory counterpart of the gauge half, for tests and for the "relaunch" round
/// trip: two stores built over one of these is exactly what a fresh launch reads.
class InMemoryGaugeKindStore(private var raw: String? = null) : GaugeKindStore {
    override fun load(): GaugeKind = raw?.let { GaugeKind.fromRaw(it) } ?: GaugeKind.progressor

    override fun save(kind: GaugeKind) {
        raw = kind.rawValue
    }

    /// Writes a raw value the enum has never heard of — the only way to reproduce a kind
    /// stored by a newer build, since no typed API can express it.
    fun writeRaw(value: String?) {
        raw = value
    }
}

/// See `LocalDeviceStore` for why this is `staticCompositionLocalOf`.
val LocalSettingsStore: ProvidableCompositionLocal<SettingsStore> = staticCompositionLocalOf {
    error("LocalSettingsStore was read outside a CompositionLocalProvider")
}
