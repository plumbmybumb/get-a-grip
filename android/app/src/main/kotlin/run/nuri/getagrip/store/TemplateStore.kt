// SPDX-License-Identifier: MPL-2.0
// Original contributions Copyright 2026 Nuri Bruner.

package run.nuri.getagrip.store

import android.content.Intent
import androidx.compose.runtime.ProvidableCompositionLocal
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import run.nuri.getagrip.BuildConfig
import run.nuri.getagrip.data.CriticalForceRecordEntity
import run.nuri.getagrip.data.MaxRecordEntity
import run.nuri.getagrip.data.SessionTemplateEntity
import run.nuri.getagrip.data.WorkoutLogEntity
import run.nuri.getagrip.data.benchmark
import run.nuri.getagrip.data.climb
import run.nuri.getagrip.data.storedNow
import run.nuri.getagrip.engine.BlobCodec
import run.nuri.getagrip.engine.CriticalForceResult
import run.nuri.getagrip.engine.DayRecord
import run.nuri.getagrip.engine.DayStamp
import run.nuri.getagrip.engine.FingerSet
import run.nuri.getagrip.engine.FingerStrain
import run.nuri.getagrip.engine.GripPosition
import run.nuri.getagrip.engine.GripSpec
import run.nuri.getagrip.engine.HandMode
import run.nuri.getagrip.engine.L10n
import run.nuri.getagrip.engine.LadderRung
import run.nuri.getagrip.engine.MaxSource
import run.nuri.getagrip.engine.MaxTable
import run.nuri.getagrip.engine.PlanMath
import run.nuri.getagrip.engine.RPE
import run.nuri.getagrip.engine.ReminderTime
import run.nuri.getagrip.engine.RepSummary
import run.nuri.getagrip.engine.RoutineDraft
import run.nuri.getagrip.engine.RoutineShare
import run.nuri.getagrip.engine.RoutineShareError
import run.nuri.getagrip.engine.RoutineSummary
import run.nuri.getagrip.engine.SessionKind
import run.nuri.getagrip.engine.SessionPlan
import run.nuri.getagrip.engine.Side
import java.time.Instant
import java.time.ZoneId
import java.time.LocalTime
import java.util.UUID

/// Mutation hub: every write funnels through here so the side-effect pipeline (persist →
/// derived recompute → reminder replan) can never be skipped.
///
/// TRANSLATION NOTE (Sources/Store/TemplateStore.swift). Three forced shape changes:
///
/// 1. **Every mutation is `suspend`.** Room must not touch the main thread. Per-frame UI
///    reads — `completed`, `completionText`, `summary` — stay synchronous: they are pure
///    folds over state already published.
/// 2. **`routines` is PUBLISHED here.** iOS uses `@Query` to track CloudKit merges; Android
///    has none, and `routineOrder` is a total order SQL `ORDER BY` cannot express, so the
///    derived recompute publishes the list too.
/// 3. **A routine is a VALUE.** SwiftData's "was this deleted under me"
///    (`modelContext != nil`) becomes a re-fetch by id, and mutating one produces a new
///    row.
///
/// Published either way: everything derived from more than one table — completion counts,
/// the 14-day strip, recent grips, the current max per grip.
@Stable
class TemplateStore(
    private val gateway: StoreGateway,
    private val clock: DayClock,
    private val settings: RoutineSettings,
    private val scheduler: AlarmScheduler,
    private val scope: CoroutineScope,
    /// Where the routines actually live — Settings › About tells the truth about sync
    /// instead of asserting a backup nobody promised.
    val storageMode: StorageMode = StorageMode.localOnly,
    /// How long "Undo" stays on offer: ten seconds, or ten minutes behind the debug launch
    /// extra for headless UI verification.
    private val undoWindowMillis: Long = defaultUndoWindowMillis,
) {

    /// Every routine, in the total order below. Empty until the first `syncDerived`.
    var routines: List<SessionTemplateEntity> by mutableStateOf(emptyList())
        private set

    /// Sessions logged today, keyed by routine.
    var completionsToday: Map<UUID, Int> by mutableStateOf(emptyMap())
        private set

    /// Hangs logged BY HAND today. They belong to no routine, but are still one of the
    /// day's sessions, so `completed(_)` adds them to every routine's count, as a climb
    /// settles every routine's day.
    ///
    /// Without this the grid (folding `countsAsHang`) draws "1 of 2" while Today's card
    /// says "0 of 2", and the evening reminder fires on a night already trained.
    var unattributedHangsToday: Int by mutableStateOf(0)
        private set

    /// The climb logged today, if any — null on an ordinary day. Its mere presence
    /// completes the day; see `isDoneForToday`.
    var climbToday: SessionKind? by mutableStateOf(null)
        private set

    /// Whether a benchmark was logged today — set by the day's first MEASURED max (see
    /// `recordMax`). Settles the day like a climb; its own flag because the card copy and
    /// grid notch must not call a max-testing morning a trip to the gym.
    var benchmarkedToday: Boolean by mutableStateOf(false)
        private set

    /// When the newest gauge-MEASURED max was recorded, across every grip. Drives the Maxes
    /// tab's staleness line and nudge; manual edits don't move it, because typing a number
    /// is not a test.
    var lastMeasuredMaxAt: Instant? by mutableStateOf(null)
        private set

    /// Exactly 14 entries, oldest first — the consistency strip's whole input.
    var consistency: List<DayRecord> by mutableStateOf(emptyList())
        private set

    /// At most 6, newest first, deduped by canonical key.
    var recentGrips: List<GripSpec> by mutableStateOf(emptyList())
        private set

    /// What each routine is called RIGHT NOW, by id. History freezes the name into every
    /// log (a deleted routine still needs one); displays resolve through this and fall back
    /// to the frozen name, so a rename shows everywhere and a deleted routine keeps its
    /// history.
    var routineNames: Map<UUID, String> by mutableStateOf(emptyMap())
        private set

    /// Newest `MaxRecordEntity` per canonical grip AND HAND key. The table is
    /// append-only, so "current" is a fold, never a mutable row.
    var currentMaxes: Map<String, MaxRecordEntity> by mutableStateOf(emptyMap())
        private set

    /// The same maxes as the engine consumes them: by grip AND hand, in kilograms. STORED
    /// and rebuilt with `currentMaxes`, never computed per access: the builder reads it on
    /// every drag frame.
    var maxTable: MaxTable by mutableStateOf(MaxTable())
        private set

    /// The first day the app had anything to track. Days before it are hairlines in the
    /// strip, not missed sessions.
    var trackingSince: DayStamp? by mutableStateOf(null)
        private set

    /// The last deleted routine, held briefly for Undo. Delete has no confirmation dialog —
    /// cheap undo is the forgiveness, and a routine is six sets of authored intent.
    ///
    /// TRANSLATION NOTE: iOS snapshots raw columns into a `DeletedRoutine` struct. The Room
    /// row already IS the raw columns, blobs included, so a column added to the entity can
    /// never go missing from the undo (as `isOnDemand` did on iOS).
    var lastDeleted: DeletedRoutine? by mutableStateOf(null)
        private set

    /// The same offer for a deleted session, on its own slot: the two live on different
    /// tabs with their own bars, and a shared slot would let a History delete silently
    /// retract the Undo on Today.
    var lastDeletedSession: WorkoutLogEntity? by mutableStateOf(null)
        private set

    /// Every critical force test, oldest first. Published here because Android has no
    /// `@Query`: Today's line, the Maxes cards and the test's own setup all read this one
    /// list, so a saved test redraws all three at once.
    var criticalForceRecords: List<CriticalForceRecordEntity> by mutableStateOf(emptyList())
        private set

    /// A deleted critical force test, restorable for the undo window. Its own slot, like
    /// sessions', so a delete on one surface never retracts another's Undo. The Room row
    /// IS the raw columns, blobs included, so restoring it is exact.
    var lastDeletedCriticalForce: CriticalForceRecordEntity? by mutableStateOf(null)
        private set

    /// Set when a write fails; the change has already been rolled back (one transaction).
    ///
    /// **DISPLAY ONLY — never the answer to "did MY write land?"** Two writes in flight
    /// share this field, so reading it back could take a failed save for a success (an Undo
    /// for a delete that never happened). Every write answers for itself via
    /// `persistAndSync`'s return.
    var saveError: String? by mutableStateOf(null)

    /// The database's write counter — see `StoreGateway.writeRevision`. Observable, so a
    /// screen keyed on it rereads when a write lands under it.
    val writeRevision: Long get() = gateway.writeRevision

    /// A scanned routine (or the reason a scan failed), HELD rather than presented.
    ///
    /// A `getagrip://` link can arrive while a full-screen destination owns the screen, and
    /// presenting from the root then tore that destination down on iOS (2026-08-19): a
    /// running SESSION died unlogged. So the URL is decoded into a value here and Today,
    /// which owns every conflicting presentation, drains it when nothing else is up. One
    /// slot, latest scan wins.
    var pendingImport: RoutineDraft? by mutableStateOf(null)
        private set

    var pendingImportError: String? by mutableStateOf(null)
        private set

    /// The day `syncDerived` last published for. Separate from `clock.today` so a failed
    /// fetch leaves it stale and the next call retries.
    private var syncedDay: DayStamp = clock.today

    private var undoExpiry: Job? = null
    private var sessionUndoExpiry: Job? = null
    private var criticalForceUndoExpiry: Job? = null

    /// Fulfilled by the Activity once the builder wires it. Until then
    /// `askNotificationPermissionOnce` does its bookkeeping and raises no dialog.
    var notificationPermissionGate: NotificationPermissionGate? = null

    // MARK: - The day

    /// From the Activity's `onResume` and on clock ticks: a phone left open past midnight
    /// must flip 2/2 back to 0/2 without a relaunch.
    ///
    /// Does NOT call `clock.refresh()`: **the store REACTS to the clock, it does not drive
    /// it.** Refreshing here re-pins `today` to the system date and defeats
    /// `DayClock.advance(to:)`, the seam that makes midnight testable. The Activity (on
    /// resume) and `DayClockReceiver` refresh the clock, then land here.
    suspend fun refreshIfDayChanged() {
        if (clock.today == syncedDay) return
        syncDerived()
    }

    /// Cheap full recompute — a handful of routines and at most 14 days of logs.
    ///
    /// BAILS on a null fetch: a failed read is not "no routines". Publishing an empty world
    /// would blank the strip and hand `ReminderPlanner` an empty plan that cancels every
    /// reminder.
    ///
    /// `refoldingMaxes = false` skips the one unbounded fetch (`allMaxes`); the caller
    /// asserts no `MaxRecord` moved. Defaults to true so every external trigger (launch,
    /// midnight, permission callback) refolds; only the internal write path opts out.
    ///
    /// `refoldingCriticalForce` is the same bargain for the critical force table: skipped
    /// by a write that asserts it moved no test.
    suspend fun syncDerived(
        refoldingMaxes: Boolean = true,
        refoldingCriticalForce: Boolean = refoldingMaxes,
    ) = syncLane.withLock {
        publishDerived(refoldingMaxes, refoldingCriticalForce)
    }

    /// **One recompute at a time, in request order.** `syncDerived` reads three tables
    /// across suspensions before publishing; two overlapping could finish out of order and
    /// leave the OLDER world on screen and in the planner.
    private val syncLane = Mutex()

    /// **Every reminder replan goes through this one lane**, in publish order, so a
    /// superseded run's alarm writes can never land after its successor's.
    ///
    /// LATEST WINS: plans queued during an install arrive as one batch and only the newest
    /// is applied; the older ones describe a world already replaced.
    private val replanLane = SerialWriteLane<List<ReminderPlanner.RoutinePlanInput>>(scope) { batch ->
        ReminderPlanner.replan(batch.last(), scheduler)
    }

    private suspend fun publishDerived(refoldingMaxes: Boolean, refoldingCriticalForce: Boolean) {
        val fetched = gateway.allRoutines() ?: return
        val ordered = fetched.sortedWith(routineOrder)
        val today = clock.today
        val earliest = today - (consistencyDays - 1)
        val logs = gateway.logsFrom(earliest.raw) ?: return
        // Skipping the fetch and FAILING it must not collapse: a null read still bails,
        // while a skip publishes the rest and leaves the three max-derived values standing.
        var maxes: List<MaxRecordEntity>? = null
        if (refoldingMaxes) maxes = gateway.allMaxes() ?: return
        // A failed read of the tests leaves the published list standing rather than
        // blanking it, and never holds up the routines' own world.
        val tests = if (refoldingCriticalForce) gateway.allCriticalForce() else null

        syncedDay = today
        routines = ordered
        completionsToday = completions(logs, today)
        unattributedHangsToday = unattributedHangs(logs, today)
        climbToday = logs.climb(today)
        benchmarkedToday = logs.benchmark(today)
        trackingSince = trackingStart(ordered, logs)
        consistency = consistency(today, logs, ordered.firstOrNull(), trackingSince)
        recentGrips = recentGrips(ordered)
        // Which duplicate id wins is arbitrary; what matters is that it cannot crash.
        routineNames = ordered.associate { it.id to it.name }
        if (maxes != null) {
            currentMaxes = newestPerGrip(maxes)
            maxTable = table(currentMaxes)
            // `maxes` arrives sorted by `recordedAt`, so the last measured one is newest.
            lastMeasuredMaxAt = maxes.lastOrNull { it.source == MaxSource.measured }?.recordedAt
        }
        if (tests != null) criticalForceRecords = tests.sortedWith(criticalForceOrder)

        // Recomputed on the same pass as the completion counts, so finishing a session
        // replans the day's reminders as "2 of 2" appears. The midnight refresh restores
        // tomorrow's full set.
        val inputs = ordered.map { template ->
            // `completed(_)`, not the raw map: a hand-logged hang must silence the evening
            // reminder too, and reading the map directly is how card and reminder drift
            // apart.
            val done = completed(template)
            // A climb or a benchmark ZEROES the day's outstanding sessions, so no evening
            // reminder fires after a day at the gym or a max test.
            val outstanding = if (climbToday != null || benchmarkedToday) {
                0
            } else {
                maxOf(0, maxOf(1, template.sessionsPerDay) - done)
            }
            ReminderPlanner.RoutinePlanInput(
                id = template.id,
                name = template.name,
                reminders = template.reminders,
                // Whenever routines never remind. Normalization already forces the switch
                // off on save; this covers templates written before the rule.
                enabled = template.remindersEnabled && !template.isOnDemand,
                outstandingToday = outstanding,
            )
        }
        // Off the caller's turn, as on iOS: a save should not wait on per-slot
        // `AlarmManager` writes. See `replanLane`.
        replanLane.submit(inputs)
    }

    // MARK: - Derived computations (pure over what was fetched)

    /// HANG sessions only, per routine. A climb fills no slot; it settles the whole day
    /// (`climbToday`).
    private fun completions(logs: List<WorkoutLogEntity>, day: DayStamp): Map<UUID, Int> {
        val counts = HashMap<UUID, Int>()
        for (log in logs) {
            if (log.dayKey != day.raw || log.kind.isClimb) continue
            // A log whose routine was deleted has nothing to attribute to; grouping is
            // best-effort.
            val id = log.templateID ?: continue
            counts[id] = (counts[id] ?: 0) + 1
        }
        return counts
    }

    /// `hangManual` ONLY, not every null-`templateID` log. A runner session whose routine
    /// was later deleted is dropped on purpose (see `completions`); crediting it would
    /// rescore old days.
    private fun unattributedHangs(logs: List<WorkoutLogEntity>, day: DayStamp): Int =
        logs.count { it.dayKey == day.raw && it.kind == SessionKind.hangManual }

    /// The earliest day the app could have expected anything of you: the oldest routine
    /// (or, if the routine that produced them has since been deleted, the oldest log).
    private fun trackingStart(
        routines: List<SessionTemplateEntity>,
        logs: List<WorkoutLogEntity>,
    ): DayStamp? {
        val routineDays = routines.map { DayStamp.of(it.createdAt) }
        val logDays = logs.map { DayStamp(it.dayKey) }
        return (routineDays + logDays).minOrNull()
    }

    private fun consistency(
        today: DayStamp,
        logs: List<WorkoutLogEntity>,
        primary: SessionTemplateEntity?,
        since: DayStamp?,
    ): List<DayRecord> {
        val byDay = logs.groupBy { it.dayKey }
        // Today's target comes from the routine Today opens on; a past day's from what its
        // logs froze, so raising sessions-per-day never rescores last week.
        val currentTarget = primary?.sessionsPerDay ?: 0

        return (0 until consistencyDays).map { offset ->
            val day = today - (consistencyDays - 1 - offset)
            val dayLogs = byDay[day.raw] ?: emptyList()
            val target = dayLogs.maxOfOrNull { it.sessionsPerDayTarget } ?: currentTarget
            DayRecord(
                day = day,
                // Hang sessions incl. hand-logged ones, so "1 of 2" still means hang
                // rounds. A climb fills the cell on its own; a benchmark fills via its own
                // flag and counts toward nothing.
                completed = dayLogs.count { it.kind.countsAsHang },
                target = target,
                tracked = since?.let { day >= it } ?: false,
                climb = dayLogs.climb(day),
                benchmarked = dayLogs.benchmark(day),
            )
        }
    }

    /// Newest-EDITED routine first. "Recent" means "recently in a routine"; logged grips
    /// can be prepended later without changing the shape or the callers.
    private fun recentGrips(routines: List<SessionTemplateEntity>): List<GripSpec> {
        val grips = mutableListOf<GripSpec>()
        val seen = HashSet<String>()
        outer@ for (template in routines.sortedByDescending { it.updatedAt }) {
            for (set in template.sets) {
                if (!seen.add(set.grip.key)) continue
                grips.add(set.grip)
                if (grips.size >= recentGripLimit) break@outer
            }
        }
        // With no history the RECENT rail would be empty exactly where it helps most, a
        // blank routine. The seed palette is the common no-hang vocabulary.
        return grips.ifEmpty { seedGrips }
    }

    /// Newest per GRIP **AND HAND** — see `MaxRecordEntity.maxKey`. Keyed on grip alone, a
    /// right-hand max would supersede the left and one hand would silently lose its number.
    private fun newestPerGrip(records: List<MaxRecordEntity>): Map<String, MaxRecordEntity> {
        val newest = HashMap<String, MaxRecordEntity>()
        for (record in records) {
            val key = record.maxKey
            val held = newest[key]
            if (held != null && !held.recordedAt.isBefore(record.recordedAt)) continue
            newest[key] = record
        }
        return newest
    }

    /// Derived from `currentMaxes` in the same pass, so the two can never disagree.
    private fun table(newest: Map<String, MaxRecordEntity>): MaxTable {
        val table = MaxTable()
        for (record in newest.values) table.record(record.kg, record.gripKey, record.side)
        return table
    }

    // MARK: - Reads

    suspend fun routine(id: UUID): SessionTemplateEntity? = gateway.routine(id)

    /// One logged session by id — a point read. Null when absent or when the read failed.
    suspend fun session(id: UUID): WorkoutLogEntity? = gateway.log(id)

    fun plan(template: SessionTemplateEntity): SessionPlan = template.plan

    /// null means "new", and a new routine is BLANK — never `.starter`: seeding here would
    /// silently put the full daily protocol under someone adding a rest day.
    fun draft(editing: SessionTemplateEntity?): RoutineDraft = editing?.draft ?: RoutineDraft.blank()

    fun summary(template: SessionTemplateEntity): RoutineSummary {
        val plan = template.plan
        val ladder = plan.executable.sets.mapIndexed { index, set ->
            LadderRung(id = index, grip = set.grip, repsPerSide = set.repsPerSide)
        }
        return RoutineSummary(
            id = template.id,
            name = template.name,
            ladder = ladder,
            setCount = PlanMath.setCount(plan),
            totalReps = PlanMath.totalReps(plan),
            sharedEdgeMM = PlanMath.sharedEdgeMM(plan),
            estimatedSeconds = PlanMath.totalSeconds(plan),
            sessionsPerDay = template.sessionsPerDay,
            completedToday = completed(template),
            nextReminder = nextReminder(template),
            climbedToday = climbToday,
            benchmarkedToday = benchmarkedToday,
            isOnDemand = template.isOnDemand,
            // Against the live max table: intensity is a fact about TODAY's prescription,
            // unlike the runner's freeze-at-start rule.
            peakIntensity = PlanMath.peakIntensity(plan, maxTable),
        )
    }

    /// Wraps to tomorrow's first slot after the day's last reminder: at 22:00 the honest
    /// answer is still "next at 08:00". Recomputed per call because it depends on the wall
    /// clock.
    private fun nextReminder(template: SessionTemplateEntity): ReminderTime? {
        if (!template.remindersEnabled) return null
        val slots = template.reminders.sorted()
        val first = slots.firstOrNull() ?: return null
        val now = LocalTime.now()
        val minutes = now.hour * 60 + now.minute
        return slots.firstOrNull { it.minutesFromMidnight >= minutes } ?: first
    }

    fun completed(template: SessionTemplateEntity): Int =
        (completionsToday[template.id] ?: 0) + unattributedHangsToday

    /// **A climb settles the day.** Bouldering at your limit is more finger load than the
    /// routine it displaced, so scoring it as a miss was the app lying about the week
    /// (Nuri, 2026-08-05). The routine stays startable: an extra hang round is offered,
    /// never demanded.
    fun isDoneForToday(template: SessionTemplateEntity): Boolean {
        if (template.isOnDemand) {
            // Never OWED, but once run today the card earns its checkmark and Start becomes
            // "Start another".
            return climbToday != null || benchmarkedToday || completed(template) > 0
        }
        return climbToday != null || benchmarkedToday ||
            completed(template) >= maxOf(1, template.sessionsPerDay)
    }

    /// A whole sentence, not the "1 of 2" fragment: this is what a screen reader reads, and
    /// "one of two" with no noun is the classic dashboard-accessibility failure.
    fun completionText(template: SessionTemplateEntity): String {
        val done = completed(template)
        val target = maxOf(1, template.sessionsPerDay)
        // On a climbing day the climb leads; any hang rounds are the extra, said second.
        climbToday?.let { climb ->
            val what = if (climb == SessionKind.climbLimit) {
                L10n.tr("Limit session")
            } else {
                L10n.tr("Volume session")
            }
            return when (done) {
                0 -> L10n.tr("%s at the gym today", what)
                1 -> L10n.tr("%s at the gym today, plus a hang session", what)
                else -> L10n.tr("%s at the gym today, plus %d hang sessions", what, done)
            }
        }
        // The climb wins the sentence when both happened; the benchmark still shows in
        // History.
        if (benchmarkedToday) {
            return when (done) {
                0 -> L10n.tr("Testing day today")
                1 -> L10n.tr("Testing day today, plus a hang session")
                else -> L10n.tr("Testing day today, plus %d hang sessions", done)
            }
        }
        if (template.isOnDemand) {
            return when (done) {
                0 -> L10n.tr("A whenever routine — nothing owed today")
                1 -> L10n.tr("Done today")
                else -> L10n.tr("Done %d times today", done)
            }
        }
        if (done == 0) {
            return if (target == 1) {
                L10n.tr("No session done today")
            } else {
                L10n.tr("No sessions done today, %d planned", target)
            }
        }
        if (done >= target) {
            if (done == 1) return L10n.tr("Session done today")
            // "Both" for the twice-a-day case reads as a finished ritual rather than a
            // tally.
            if (done == 2 && target == 2) return L10n.tr("Both sessions done today")
            return L10n.tr("%d sessions done today", done)
        }
        return L10n.tr("%d of %d sessions done today", done, target)
    }

    /// The Start button's title, as a state machine: what the primary control says is a
    /// fact about the day, not about the screen it is on.
    fun startTitle(template: SessionTemplateEntity): String = when {
        isDoneForToday(template) -> L10n.tr("Start another")
        completed(template) > 0 -> L10n.tr("Start next session")
        template.isOnDemand || template.sessionsPerDay <= 1 -> L10n.tr("Start session")
        else -> L10n.tr("Start first session")
    }

    /// The max for a grip on a given hand, with the both-hands fallback — see `MaxTable`.
    /// Defaulted to `both` so every existing caller keeps its meaning.
    fun currentMax(grip: GripSpec, side: Side = Side.both): Double? =
        maxTable.max(grip.key, side)

    /// The Maxes tab's SOFT NUDGE: true once the newest measured max is four weeks stale
    /// (finger strength moves monthly). Nobody who never measured is nudged. No setting: a
    /// configurable cadence is a schedule, and the schedule was voted down for a pulse.
    val benchmarkNudge: Boolean
        get() {
            val last = lastMeasuredMaxAt ?: return false
            return storedNow().toEpochMilli() - last.toEpochMilli() >= 28L * 86_400_000L
        }

    fun suggestedBand(grip: GripSpec) = PlanMath.suggestedBand(currentMax(grip) ?: 0.0)

    /// Rung 2 of Today's selection rule: the routine started today on THIS device.
    /// DEVICE-LOCAL and day-scoped — a synced "primary" flag is the classic second-device
    /// bug.
    val suggestedRoutineID: UUID?
        get() {
            if (settings.lastStartedDayRaw != clock.today.raw) return null
            return settings.lastStartedRoutineID
        }

    // MARK: - Mutations

    /// The ONE entry point the builder calls, whether creating or editing.
    suspend fun save(draft: RoutineDraft): SessionTemplateEntity? {
        val normalized = draft.normalized
        val existing = normalized.templateID?.let { gateway.routine(it) }
        val saved = if (existing != null) {
            if (update(existing, normalized)) gateway.routine(existing.id) else null
        } else {
            // templateID null, or deleted while the editor was open. Re-creating is the
            // only answer that does not discard work being saved.
            create(normalized)
        }
        // Only on success: after a rollback the sheet stays open and the rescue copy must
        // survive for the retry.
        if (saved != null) clearDraft()
        return saved
    }

    suspend fun create(draft: RoutineDraft): SessionTemplateEntity? {
        val siblings = gateway.allRoutines()
        if (siblings == null) {
            // A failed READ is not "no routines": minting sortIndex 0 would make this the
            // primary routine.
            saveError = L10n.tr("Couldn't read your routines just now — the new one wasn't saved.")
            return null
        }
        val normalized = draft.normalized
        val row = SessionTemplateEntity.from(
            draft = normalized,
            sortIndex = (siblings.maxOfOrNull { it.sortIndex } ?: -1) + 1,
        )
        if (!persistAndSync(maxesChanged = false) { it.putRoutine(row) }) return null
        askNotificationPermissionOnce(normalized)
        return row
    }

    suspend fun update(template: SessionTemplateEntity, draft: RoutineDraft): Boolean {
        // A routine deleted under us is refused, not resurrected: an upsert would INSERT it
        // again and silently undo the user's delete. (iOS guards `modelContext != nil`
        // instead.)
        if (gateway.routine(template.id) == null) return false
        val normalized = draft.normalized
        if (!persistAndSync(maxesChanged = false) { it.putRoutine(template.applying(normalized)) }) return false
        askNotificationPermissionOnce(normalized)
        return true
    }

    suspend fun duplicate(template: SessionTemplateEntity): SessionTemplateEntity? {
        if (gateway.routine(template.id) == null) return null
        var draft = RoutineDraft.copying(template.draft)
        draft = draft.copy(plan = draft.plan.copy(name = uniqueName(draft.plan.name)))
        return create(draft)
    }

    // MARK: - The share-link inbox

    fun receiveShareLink(url: String) {
        try {
            pendingImport = RoutineShare.draft(url)
            pendingImportError = null
        } catch (error: RoutineShareError) {
            pendingImport = null
            pendingImportError = error.errorDescription
        } catch (error: Throwable) {
            pendingImport = null
            pendingImportError = L10n.tr("This routine code couldn't be read.")
        }
    }

    /// Consume-on-read, so a drain can never present the same scan twice.
    fun claimPendingImport(): RoutineDraft? {
        val claimed = pendingImport
        pendingImport = null
        return claimed
    }

    fun claimPendingImportError(): String? {
        val claimed = pendingImportError
        pendingImportError = null
        return claimed
    }

    /// The name an import WILL land under, so the preview promises the real one. Two people
    /// exchanging codes for the shipped default is the common case.
    suspend fun plannedImportName(wanted: String): String = uniqueName(wanted)

    /// A routine from somebody else's QR code — `duplicate`'s twin: deconflict the name,
    /// then `create`. No second save path, so an import lands at the END of the order and
    /// never displaces the routine Today opens on.
    ///
    /// Re-asserted here, because this is the last gate before disk and the payload is
    /// untrusted:
    ///
    /// - **`templateID` is nulled.** `SessionTemplateEntity.from` ADOPTS a draft's id, so a
    ///   code carrying its author's UUID would mint a routine with somebody else's identity
    ///   — the id reminder identifiers are built from. Same reason `RoutineDraft.copying`
    ///   nulls it.
    /// - **Reminders are forced OFF.** They are personal times the payload omits, and
    ///   `create` asks for notification permission when a draft has them on: an OS prompt
    ///   from scanning a stranger's code is an ambush.
    suspend fun importRoutine(draft: RoutineDraft): SessionTemplateEntity? {
        // Normalized FIRST so `uniqueName` deconflicts the name actually written — an empty
        // one becomes the house default, and deconflicting "" would let two "Daily
        // no-hangs" through.
        var incoming = draft.normalized
        incoming = incoming.copy(templateID = null, remindersEnabled = false)
        incoming = incoming.copy(plan = incoming.plan.copy(name = uniqueName(incoming.plan.name)))
        return create(incoming)
    }

    /// The chooser rail shows names only, so two routines called "Copy of X" make one
    /// unpickable by sight. Suffixes count up from 2 — "Copy of X", "Copy of X 2".
    private suspend fun uniqueName(wanted: String): String {
        val taken = (gateway.allRoutines() ?: emptyList()).map { it.name }.toSet()
        if (!taken.contains(wanted)) return wanted
        var suffix = 2
        while (taken.contains("$wanted $suffix")) suffix += 1
        return "$wanted $suffix"
    }

    // MARK: - Delete and undo

    /// A deleted routine and ITS SESSIONS, together (Nuri, 2026-09-20): orphaned history
    /// left per-grip trends for a routine that no longer existed. Raw rows, blobs included,
    /// so Undo restores both exactly.
    data class DeletedRoutine(
        val routine: SessionTemplateEntity,
        val sessions: List<WorkoutLogEntity>,
    ) {
        val id: UUID get() = routine.id
    }

    suspend fun delete(template: SessionTemplateEntity): Boolean {
        // The row as it stands on disk — the raw columns `undoDelete` puts back.
        val restorable = gateway.routine(template.id) ?: return false
        // A failed sessions read refuses the whole delete rather than orphaning them.
        val sessions = gateway.logsFor(template.id) ?: return false
        val deleted = persistAndSync(maxesChanged = false) { writer ->
            sessions.forEach { writer.removeLog(it.id) }
            writer.removeRoutine(template.id)
        }
        // Only offer undo for a delete that landed; after a rollback "Undo" would duplicate
        // it.
        if (!deleted) return false
        lastDeleted = DeletedRoutine(restorable, sessions)
        armUndoExpiry()
        return true
    }

    private fun armUndoExpiry() {
        undoExpiry?.cancel()
        undoExpiry = scope.launch {
            delay(undoWindowMillis)
            lastDeleted = null
        }
    }

    /// Re-insert with the ORIGINAL UUID, sortIndex and raw blobs.
    ///
    /// The id keys the `doigt.routine.<uuid>.r0480` reminder identifiers — a new id would
    /// leave old reminders orphaned and firing. Raw blobs because re-encoding a routine
    /// written by a NEWER build drops fields this one does not understand. Hence the
    /// snapshot is the ENTITY: a new column cannot go missing from the restore.
    suspend fun undoDelete() {
        val restorable = lastDeleted ?: return
        undoExpiry?.cancel()

        // `updatedAt` is NOT restored: the restore is the latest thing to happen to it, and
        // `recentGrips` reads that order.
        val row = restorable.routine.copy(updatedAt = storedNow())
        val restored = persistAndSync(maxesChanged = false) { writer ->
            writer.putRoutine(row)
            // Sessions come back as raw rows — never through `WorkoutLogEntity.from`, which
            // re-derives their numbers.
            restorable.sessions.forEach { writer.putLog(it) }
        }

        // Consume the undo only once the restore landed, or a rolled-back save loses the
        // routine for good.
        if (restored) lastDeleted = null else armUndoExpiry()
    }

    fun dismissUndo() {
        undoExpiry?.cancel()
        undoExpiry = null
        lastDeleted = null
    }

    // MARK: - Order

    /// TRANSLATION NOTE: SwiftUI's `move(fromOffsets:toOffset:)`, where `toOffset` indexes
    /// the array BEFORE removal. A naive remove-then-insert-at-`to` disagrees whenever
    /// `to > from`, invisibly in single-step drags.
    suspend fun move(fromIndex: Int, toOffset: Int) {
        val current = gateway.allRoutines()?.sortedWith(routineOrder) ?: return
        if (fromIndex !in current.indices) return
        val mutable = current.toMutableList()
        val moved = mutable.removeAt(fromIndex)
        val insertAt = if (toOffset > fromIndex) toOffset - 1 else toOffset
        mutable.add(insertAt.coerceIn(0, mutable.size), moved)
        renumber(mutable)
    }

    /// Position 0 IS the primary routine. No `isPrimary` column: a flag that can disagree
    /// with the order is a second source of truth.
    suspend fun makePrimary(template: SessionTemplateEntity) {
        val current = gateway.allRoutines()?.sortedWith(routineOrder) ?: return
        val index = current.indexOfFirst { it.id == template.id }
        if (index < 0) return
        val mutable = current.toMutableList()
        mutable.add(0, mutable.removeAt(index))
        renumber(mutable)
    }

    /// Renormalizes to a dense 0..<n, so a shared `sortIndex` is repaired by the next
    /// reorder instead of a tie broken differently on each read.
    private suspend fun renumber(ordered: List<SessionTemplateEntity>) {
        persistAndSync(maxesChanged = false) { writer ->
            ordered.forEachIndexed { index, template ->
                if (template.sortIndex == index) return@forEachIndexed
                // `updatedAt` untouched: reordering is not a content edit, and bumping it
                // would scramble the recent-grip order.
                writer.putRoutine(template.copy(sortIndex = index))
            }
        }
    }

    // MARK: - Sessions

    /// Rung 2 of Today's selection rule, written on START rather than finish: at 19:00 the
    /// useful question is "which one am I in the middle of".
    fun noteSessionStarted(template: SessionTemplateEntity) {
        settings.setLastStartedRoutineID(template.id)
        settings.setLastStartedDayRaw(clock.today.raw)
    }

    /// Log a session after the fact — a climbing-gym day or a hang session away from the
    /// gauge. Nothing to time or measure.
    ///
    /// `daysAgo` because the realistic moment to log one is the next morning. Clamped, not
    /// validated: a negative would file training in the future.
    suspend fun recordLoggedSession(
        kind: SessionKind,
        daysAgo: Int = 0,
        minutes: Int? = null,
        rpe: RPE? = null,
        fingerStrain: FingerStrain? = null,
        notes: String = "",
    ): WorkoutLogEntity? {
        if (!kind.isLoggedByHand) return null
        val day = clock.today - maxOf(0, daysAgo)
        val log = WorkoutLogEntity.logged(
            kind = kind,
            day = day,
            at = storedNow(),
            // Frozen like any log, so changing sessions-a-day later cannot rescore a day
            // already lived.
            sessionsPerDayTarget = firstRoutineSessionsPerDay(),
            minutes = minutes,
            rpe = rpe,
            fingerStrain = fingerStrain,
            notes = notes,
        )
        return if (persistAndSync(maxesChanged = false) { it.putLog(log) }) log else null
    }

    /// **Re-file every session the app itself timed under the training day it started in.**
    /// Until 2026-09-20 the day turned at midnight, so a session across it scored one
    /// evening as two days. The day now turns at `DayStamp.ROLLOVER_HOUR`; this moves rows
    /// written under the old rule. Returns rows moved; 0 when the read fails.
    ///
    /// **Narrow on purpose, because it rewrites history:**
    ///
    /// - only rows started before `before` — this device's first launch of a build with the
    ///   new rule (`repairTrainingDaysOnce` passes it);
    /// - only rows still filed under the OLD rule (the calendar day it started or finished
    ///   on), so a row filed deliberately elsewhere is never second-guessed;
    /// - never a hand-logged row (its day was chosen), nor an unknown KIND:
    ///   `SessionKind.fallback` reads one as a hang, and moving a newer build's hand-logged
    ///   row on that guess rewrites a choice;
    /// - via a four-column projection and a one-column `UPDATE`, so no blob is read or
    ///   rewritten.
    ///
    /// Straight through the gateway rather than `persistAndSync`: the caller republishes.
    suspend fun repairTrainingDays(
        zone: ZoneId = ZoneId.systemDefault(),
        before: Instant = storedNow(),
    ): Int = refileTrainingDays(zone, before) ?: 0

    /// The pass itself; null when the read or write failed, which the one-shot must not
    /// mistake for "nothing to move".
    private suspend fun refileTrainingDays(zone: ZoneId, before: Instant): Int? {
        val stamps = gateway.dayStamps(before) ?: return null
        val moved = stamps.mapNotNull { row ->
            val kind = SessionKind.fromRaw(row.kindRaw) ?: return@mapNotNull null
            if (kind.isLoggedByHand) return@mapNotNull null
            val day = DayStamp.trainingDayOf(row.startedAt, zone).raw
            if (row.dayKey == day) return@mapNotNull null
            val oldRule = row.dayKey == DayStamp.of(row.startedAt, zone).raw ||
                row.dayKey == DayStamp.of(row.finishedAt, zone).raw
            if (!oldRule) return@mapNotNull null
            row.id to day
        }
        if (moved.isEmpty()) return 0
        try {
            gateway.write { writer -> moved.forEach { (id, day) -> writer.refileLog(id, day) } }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            return null
        }
        return moved.size
    }

    /// The launch-time door to `repairTrainingDays`: ONCE per device behind a versioned
    /// flag, marked done only when the pass ran, so a failed read retries next launch.
    /// Returns rows moved so the caller republishes only when needed.
    ///
    /// Called AFTER the first `syncDerived`: the first frame must not wait on a
    /// history-sized read.
    suspend fun repairTrainingDaysOnce(zone: ZoneId = ZoneId.systemDefault()): Int {
        if (settings.trainingDayRepairVersion >= trainingDayRepairVersion) return 0
        val moved = refileTrainingDays(zone, before = storedNow()) ?: return 0
        settings.setTrainingDayRepairVersion(trainingDayRepairVersion)
        return moved
    }

    /// Write a finished session through the hub, so Today's count and the strip update
    /// together — a session missing until relaunch reads as lost work.
    ///
    /// Filed under the training day it STARTED in (`DayStamp.trainingDayOf(startedAt)`),
    /// the rule `repairTrainingDays` applies, so writer and repair never disagree. Stamping
    /// `clock.today` at SAVE time put a session begun at 03:50 and saved at 04:10 on the
    /// next day.
    ///
    /// `identity.id` is the session's own (shared with its `FinishedSessionDraft`), so
    /// saving from both the summary and launch recovery replaces one row instead of writing
    /// two.
    suspend fun recordSession(
        plan: SessionPlan,
        identity: LogIdentity,
        reps: List<RepSummary>,
        startedAt: Instant,
        finishedAt: Instant,
        rpe: RPE?,
        newMaxes: List<MaxRecordEntity> = emptyList(),
        zone: ZoneId = ZoneId.systemDefault(),
    ): WorkoutLogEntity? {
        if (newMaxes.any { !it.kg.isFinite() || it.kg <= 0 }) {
            saveError = L10n.tr("Couldn't save this workout. Please try again.")
            return null
        }
        val log = WorkoutLogEntity.from(
            plan = plan,
            templateID = identity.templateID,
            // FROZEN at save: renaming a routine later must not retro-rename history.
            templateName = identity.templateName,
            sessionsPerDayTarget = identity.sessionsPerDayTarget,
            reps = reps,
            startedAt = startedAt,
            finishedAt = finishedAt,
            day = DayStamp.trainingDayOf(startedAt, zone),
        ).copy(id = identity.id, rpe = rpe?.rawValue)
        val saved = persistAndSync(maxesChanged = newMaxes.isNotEmpty()) { writer ->
            writer.putLog(log)
            newMaxes.forEach { writer.putMax(it) }
        }
        return if (saved) log else null
    }

    /// Remove a session from history — the one destructive act on this data. `dayKey` and
    /// `sessionsPerDayTarget` feed "2 of 2 today" and the strip, so it goes through the hub
    /// (`persistAndSync`) to walk Today's completion back at once.
    suspend fun deleteSession(log: WorkoutLogEntity): Boolean {
        // Captured as the raw row, blobs included — see `undoDeleteSession`.
        val restorable = gateway.log(log.id) ?: return false
        // Only offer undo for a delete that actually landed.
        if (!persistAndSync(maxesChanged = false) { it.removeLog(log.id) }) return false
        lastDeletedSession = restorable
        armSessionUndoExpiry()
        return true
    }

    /// Re-insert with the ORIGINAL UUID, dates and raw blobs — never through
    /// `WorkoutLogEntity.from`, which RE-DERIVES every denormalized number and would drop
    /// fields a newer build wrote. A session records something that happened; restoring
    /// must not recompute it. `kindRaw` rides along, or undoing a deleted climb would
    /// restore a HANGBOARD session and un-complete its day.
    suspend fun undoDeleteSession() {
        val restorable = lastDeletedSession ?: return
        sessionUndoExpiry?.cancel()
        val restored = persistAndSync(maxesChanged = false) { it.putLog(restorable) }
        if (restored) lastDeletedSession = null else armSessionUndoExpiry()
    }

    fun dismissSessionUndo() {
        sessionUndoExpiry?.cancel()
        sessionUndoExpiry = null
        lastDeletedSession = null
    }

    private fun armSessionUndoExpiry() {
        sessionUndoExpiry?.cancel()
        sessionUndoExpiry = scope.launch {
            delay(undoWindowMillis)
            lastDeletedSession = null
        }
    }

    // MARK: - Maxes

    data class MaxSave(val grip: GripSpec, val side: Side, val kg: Double, val source: MaxSource)

    private val maxSaveMutex = Mutex()

    suspend fun recordMax(
        kg: Double,
        grip: GripSpec,
        source: MaxSource = MaxSource.manual,
        side: Side = Side.both,
        marksBenchmarkDay: Boolean = true,
    ): Boolean = maxSaveMutex.withLock {
        saveMaxes(listOf(MaxSave(grip, side, kg, source)), marksBenchmarkDay)
    }

    suspend fun recordMaxes(values: List<MaxSave>): Boolean = maxSaveMutex.withLock {
        saveMaxes(values, marksBenchmarkDay = true)
    }

    private suspend fun saveMaxes(
        values: List<MaxSave>,
        marksBenchmarkDay: Boolean,
        snapshot: ((MaxTable, MaxTable, List<SessionTemplateEntity>) -> Unit)? = null,
    ): Boolean {
        if (values.isEmpty()) return true
        // A zero or NaN max would make every percent-of-max caption lie.
        if (values.any { !it.kg.isFinite() || it.kg <= 0 }) return false
        val keys = values.map { MaxTable.key(it.grip.key, it.side) }
        if (keys.toSet().size != keys.size) return false

        // **A MEASURED max makes today a benchmark day** (Nuri, 2026-08-10): no ceremony,
        // but the day reads as trained and no reminder nags after maximal pulls. One log
        // per day however many grips; typed numbers never create one.
        // `marksBenchmarkDay = false` is the session-PR path: that session already logged,
        // and settling the day would silently cancel the evening ritual.
        // A failed read is not evidence that today has no benchmark: keep the max, skip the
        // day marker.
        return persistAndSync { writer ->
            val existing = checkNotNull(writer.allMaxes()) { "Couldn't read existing maxes" }
            val newest = newestPerGrip(existing)
            val previous = table(newest)
            val current = previous.copy()
            val now = storedNow()
            val records = values.map { value ->
                // Room stores milliseconds; two saves in one millisecond must still append
                // rather than lose a correction to a tie.
                val last = newest[MaxTable.key(value.grip.key, value.side)]?.recordedAt
                val recordedAt = if (last != null && !now.isAfter(last)) last.plusMillis(1) else now
                current.record(value.kg, value.grip.key, value.side)
                MaxRecordEntity.from(value.grip, value.kg, value.source, value.side, recordedAt)
            }
            val routines = writer.allRoutines() ?: emptyList()
            val measured = marksBenchmarkDay && values.any { it.source == MaxSource.measured }
            val benchmarkLog = if (measured) benchmarkDayLog(writer, now, routines) else null
            records.forEach { writer.putMax(it) }
            benchmarkLog?.let { writer.putLog(it) }
            snapshot?.invoke(previous, current, routines)
        }
    }

    /// One `benchmark` log per day, or null when today already has one (or today's logs
    /// could not be read — a failed read is not evidence that today has no benchmark).
    /// Shared by measured maxes and critical force tests: both are maximal testing, and
    /// both settle the day.
    private suspend fun benchmarkDayLog(
        writer: StoreWriter,
        now: Instant,
        routines: List<SessionTemplateEntity>,
    ): WorkoutLogEntity? {
        val todaysLogs = writer.logsFrom(clock.today.raw) ?: return null
        if (todaysLogs.benchmark(clock.today)) return null
        return WorkoutLogEntity.logged(SessionKind.benchmark, clock.today, now,
            routines.sortedWith(routineOrder).firstOrNull()?.sessionsPerDay ?: 1)
    }

    suspend fun deleteMax(record: MaxRecordEntity): Boolean {
        if ((gateway.allMaxes() ?: return false).none { it.id == record.id }) return false
        return persistAndSync { it.removeMax(record.id) }
    }

    // MARK: - Critical force

    /// One hand's test, ready to save.
    class CriticalForceSave(val side: Side, val result: CriticalForceResult, val trace: ByteArray)

    /// Save one visit's critical force tests (one hand, both together, or each hand in
    /// turn) and any maxes the climber chose to take from them, in ONE transaction.
    ///
    /// A test makes today a benchmark day exactly as a measured max does. It is maximal
    /// testing, so it settles the day, fills the calendar cell and silences the evening
    /// reminder. Each hand's current max is frozen onto its record, so its "% of max" never
    /// moves when a later max lands. `alsoMaxes` are the tests' hardest pulls, saved only
    /// when ticked. Never silently. Every record of one visit shares ONE instant.
    ///
    /// Returns the saved rows in the order given, or null when nothing was written — also
    /// for two results for one hand, which is a bug, not a save.
    suspend fun recordCriticalForces(
        tests: List<CriticalForceSave>,
        grip: GripSpec,
        bodyMassKg: Double?,
        alsoMaxes: List<MaxSave> = emptyList(),
    ): List<CriticalForceRecordEntity>? = maxSaveMutex.withLock {
        if (tests.isEmpty()) return@withLock null
        if (tests.any { !it.result.criticalForceKg.isFinite() || it.result.criticalForceKg <= 0 }) return@withLock null
        if (tests.map { it.side }.toSet().size != tests.size) return@withLock null
        if (alsoMaxes.any { !(it.kg.isFinite() && it.kg > 0) }) return@withLock null
        val maxKeys = alsoMaxes.map { MaxTable.key(it.grip.key, it.side) }
        if (maxKeys.toSet().size != maxKeys.size) return@withLock null
        var saved: List<CriticalForceRecordEntity>? = null
        val committed = persistAndSync(maxesChanged = alsoMaxes.isNotEmpty(), criticalForceChanged = true) { writer ->
            val existing = checkNotNull(writer.allMaxes()) { "Couldn't read existing maxes" }
            val newest = newestPerGrip(existing)
            // Each hand against its OWN max, as it stood before this visit's new ones.
            val before = table(newest)
            val now = storedNow()
            val records = tests.map { test ->
                CriticalForceRecordEntity.from(
                    grip = grip, side = test.side, result = test.result, trace = test.trace,
                    bodyMassKg = bodyMassKg,
                    maxAtTestKg = before.max(grip.key, test.side),
                    recordedAt = now,
                )
            }
            records.forEach { writer.putCriticalForce(it) }
            for (max in alsoMaxes) {
                val last = newest[MaxTable.key(max.grip.key, max.side)]?.recordedAt
                val recordedAt = if (last != null && !now.isAfter(last)) last.plusMillis(1) else now
                writer.putMax(MaxRecordEntity.from(max.grip, max.kg, max.source, max.side, recordedAt))
            }
            benchmarkDayLog(writer, now, writer.allRoutines() ?: emptyList())?.let { writer.putLog(it) }
            saved = records
        }
        if (committed) saved else null
    }

    /// One test — the single-hand form of `recordCriticalForces`.
    suspend fun recordCriticalForce(
        result: CriticalForceResult,
        trace: ByteArray,
        grip: GripSpec,
        side: Side,
        bodyMassKg: Double?,
        alsoMax: MaxSave? = null,
    ): CriticalForceRecordEntity? =
        recordCriticalForces(listOf(CriticalForceSave(side, result, trace)), grip, bodyMassKg,
            listOfNotNull(alsoMax))?.firstOrNull()

    /// The house delete: a swipe, then ten seconds of Undo. The benchmark day it stamped
    /// stays. The testing happened, whatever became of the record.
    suspend fun deleteCriticalForce(record: CriticalForceRecordEntity): Boolean {
        // Captured as the raw row, blobs included — see `undoDeleteCriticalForce`.
        val restorable = (gateway.allCriticalForce() ?: return false).firstOrNull { it.id == record.id }
            ?: return false
        if (!persistAndSync(maxesChanged = false, criticalForceChanged = true) {
                it.removeCriticalForce(record.id)
            }) return false
        lastDeletedCriticalForce = restorable
        armCriticalForceUndoExpiry()
        return true
    }

    /// Re-insert the RAW row under its original id — never through `from`, which would
    /// re-derive what the test froze.
    suspend fun undoDeleteCriticalForce() {
        val restorable = lastDeletedCriticalForce ?: return
        criticalForceUndoExpiry?.cancel()
        val restored = persistAndSync(maxesChanged = false, criticalForceChanged = true) {
            it.putCriticalForce(restorable)
        }
        if (restored) lastDeletedCriticalForce = null else armCriticalForceUndoExpiry()
    }

    fun dismissCriticalForceUndo() {
        criticalForceUndoExpiry?.cancel()
        criticalForceUndoExpiry = null
        lastDeletedCriticalForce = null
    }

    private fun armCriticalForceUndoExpiry() {
        criticalForceUndoExpiry?.cancel()
        criticalForceUndoExpiry = scope.launch {
            delay(undoWindowMillis)
            lastDeletedCriticalForce = null
        }
    }

    // MARK: - What a new max moves

    /// Everything a new max on one grip changes across the routines, against the max it
    /// REPLACES — so ask BEFORE `recordMax`.
    data class MaxImpact(
        val percentMoves: List<PercentMove>,
        val kgOffers: List<KgOffer>,
        /// new ÷ old — the "scale with your new max" factor. null with no old max, which is
        /// also why `kgOffers` is then empty.
        val ratio: Double?,
    ) {
        /// A percentage band that now resolves to different kilograms. INFORMATIONAL:
        /// percent targets follow the newest max by design.
        data class PercentMove(
            val routineID: UUID,
            val routineName: String,
            val side: Side,
            val loPercent: Double,
            val hiPercent: Double,
            /// null when the grip had no max before — the band never resolved until now.
            val oldBand: ClosedFloatingPointRange<Double>?,
            val newBand: ClosedFloatingPointRange<Double>,
        )

        /// Explicit-kilogram sets on this grip, offered a proportional rescale. An OFFER,
        /// never automatic: a typed number is never moved by arithmetic without a yes
        /// (`PlanMath`'s precedence rule).
        data class KgOffer(
            val routineID: UUID,
            val routineName: String,
            val moves: List<Move>,
        ) {
            data class Move(
                val oldBand: ClosedFloatingPointRange<Double>,
                val newBand: ClosedFloatingPointRange<Double>,
            )
        }

        val isEmpty: Boolean get() = percentMoves.isEmpty() && kgOffers.isEmpty()
    }

    suspend fun maxImpact(grip: GripSpec, previousMaxes: MaxTable, newKg: Double,
                          side: Side = Side.both): MaxImpact {
        val routines = gateway.allRoutines()
            ?: return MaxImpact(emptyList(), emptyList(), null)
        // A fallback both-hands benchmark is not an earlier measurement of one hand.
        val ratio = previousMaxes.exact(grip.key, side)?.let { if (it > 0) newKg / it else null }
        val percentMoves = mutableListOf<MaxImpact.PercentMove>()
        val kgOffers = mutableListOf<MaxImpact.KgOffer>()

        for (routine in routines.sortedWith(routineOrder)) {
            val plan = routine.plan.executable
            val affectedSides = when {
                plan.handMode == HandMode.bothHands -> if (side == Side.both) listOf(Side.both) else emptyList()
                side == Side.both -> listOf(Side.left, Side.right).filter {
                    previousMaxes.exact(grip.key, it) == null
                }
                else -> listOf(side)
            }
            if (affectedSides.isEmpty()) continue
            // A shared ratio is valid only while both alternating hands resolve through
            // this fallback benchmark, with no exact hand overriding it.
            val canScaleSharedBand = side == Side.both && (plan.handMode == HandMode.bothHands ||
                listOf(Side.left, Side.right).all { previousMaxes.exact(grip.key, it) == null })
            val seenPercents = HashSet<String>()
            val moves = mutableListOf<MaxImpact.KgOffer.Move>()
            for (set in plan.sets) {
                if (set.grip.key != grip.key) continue
                val explicit = set.targetBand
                if (explicit != null) {
                    // A typed band is shared across both sides of an alternating routine.
                    if (!canScaleSharedBand) continue
                    val r = ratio ?: continue
                    val move = MaxImpact.KgOffer.Move(explicit, scaled(explicit, r))
                    // Two byte-identical sets would offer the same line twice.
                    if (!moves.contains(move)) moves.add(move)
                } else {
                    val percent = PlanMath.targetPercent(set, plan) ?: continue
                    if (!seenPercents.add("${percent.start}–${percent.endInclusive}")) continue
                    val newBand = PlanMath.targetBand(set, plan, newKg) ?: continue
                    for (affectedSide in affectedSides) {
                        val oldBand = previousMaxes.max(grip.key, affectedSide)
                            ?.let { PlanMath.targetBand(set, plan, it) }
                        if (oldBand == newBand) continue
                        percentMoves.add(
                            MaxImpact.PercentMove(
                                routineID = routine.id,
                                routineName = routine.name,
                                side = affectedSide,
                                loPercent = percent.start,
                                hiPercent = percent.endInclusive,
                                oldBand = oldBand,
                                newBand = newBand,
                            )
                        )
                    }
                }
            }
            if (moves.isNotEmpty()) {
                kgOffers.add(MaxImpact.KgOffer(routine.id, routine.name, moves))
            }
        }
        return MaxImpact(percentMoves, kgOffers, ratio)
    }

    data class MaxSaveReceipt(
        val values: List<MaxSave>,
        val percentMoves: List<PercentMove>,
        val rescaleOffers: List<RescaleOffer>,
        val id: UUID = UUID.randomUUID(),
    ) {
        data class PercentMove(val grip: GripSpec, val move: MaxImpact.PercentMove) {
            val id: String get() = "${grip.key}|${move.routineID}|${move.side.rawValue}|${move.loPercent}|${move.hiPercent}"
        }
        data class RescaleOffer(
            val grip: GripSpec,
            val ratio: Double,
            val newMaxKg: Double,
            val routines: List<MaxImpact.KgOffer>,
            val expectedPlans: Map<UUID, SessionPlan>,
        ) {
            val id: String get() = grip.key
        }
        val hasDetails: Boolean get() = percentMoves.isNotEmpty() || rescaleOffers.isNotEmpty()
    }

    /** One atomic commit, with its before/after receipt captured in the same transaction. */
    suspend fun recordMaxesWithReceipt(values: List<MaxSave>): MaxSaveReceipt? = maxSaveMutex.withLock {
        if (values.isEmpty()) return@withLock null
        var receipt: MaxSaveReceipt? = null
        val saved = saveMaxes(values, marksBenchmarkDay = true) { previous, current, routines ->
            receipt = maxSaveReceipt(values, previous, current, routines)
        }
        if (saved) receipt else null
    }

    private fun maxSaveReceipt(
        values: List<MaxSave>,
        previous: MaxTable,
        current: MaxTable,
        candidates: List<SessionTemplateEntity>,
    ): MaxSaveReceipt {
        // Room enforces unique routine IDs, but other gateways must not let a duplicate ID
        // identify two proposals or two Compose rows.
        val byID = candidates.groupBy { it.id }
        val routines = candidates.filter { byID[it.id]?.size == 1 }.sortedWith(routineOrder)
        val percentMoves = mutableListOf<MaxSaveReceipt.PercentMove>()
        val rescaleOffers = mutableListOf<MaxSaveReceipt.RescaleOffer>()
        for ((gripKey, changes) in values.groupBy { it.grip.key }.toSortedMap()) {
            val grip = changes.first().grip
            val sharedChange = changes.firstOrNull { it.side == Side.both }
            val ratio = sharedChange?.let { shared ->
                previous.exact(gripKey, Side.both)?.let { shared.kg / it }
            }
            val kgOffers = mutableListOf<MaxImpact.KgOffer>()
            val expectedPlans = mutableMapOf<UUID, SessionPlan>()
            for (routine in routines) {
                val plan = routine.plan.executable
                val sides = if (plan.handMode == HandMode.bothHands) listOf(Side.both)
                    else listOf(Side.left, Side.right)
                val canScale = sharedChange != null && (plan.handMode == HandMode.bothHands ||
                    listOf(Side.left, Side.right).all {
                        previous.exact(gripKey, it) == null && current.exact(gripKey, it) == null
                    })
                val seenPercents = mutableSetOf<String>()
                val kgMoves = mutableListOf<MaxImpact.KgOffer.Move>()
                for (set in plan.sets.filter { it.grip.key == gripKey }) {
                    val explicit = set.targetBand
                    if (explicit != null) {
                        if (!canScale || ratio == null || !ratio.isFinite() || ratio <= 0) continue
                        val newBand = scaled(explicit, ratio)
                        if (explicit == newBand) continue
                        val move = MaxImpact.KgOffer.Move(explicit, newBand)
                        if (move !in kgMoves) kgMoves.add(move)
                    } else {
                        val percent = PlanMath.targetPercent(set, plan) ?: continue
                        if (!seenPercents.add("${percent.start}–${percent.endInclusive}")) continue
                        for (side in sides) {
                            val oldBand = previous.max(gripKey, side)?.let { PlanMath.targetBand(set, plan, it) }
                            val newKg = current.max(gripKey, side) ?: continue
                            val newBand = PlanMath.targetBand(set, plan, newKg) ?: continue
                            if (oldBand == newBand) continue
                            percentMoves.add(MaxSaveReceipt.PercentMove(grip, MaxImpact.PercentMove(
                                routine.id, routine.name, side, percent.start, percent.endInclusive, oldBand, newBand,
                            )))
                        }
                    }
                }
                if (kgMoves.isNotEmpty()) {
                    kgOffers.add(MaxImpact.KgOffer(routine.id, routine.name, kgMoves))
                    expectedPlans[routine.id] = routine.plan
                }
            }
            if (kgOffers.isNotEmpty() && ratio != null && sharedChange != null) {
                rescaleOffers.add(MaxSaveReceipt.RescaleOffer(grip, ratio, sharedChange.kg, kgOffers, expectedPlans))
            }
        }
        return MaxSaveReceipt(values.toList(), percentMoves, rescaleOffers)
    }

    /** Validate and apply the displayed proposal inside one Room transaction. */
    suspend fun applyMaxRescale(offer: MaxSaveReceipt.RescaleOffer): Boolean = maxSaveMutex.withLock {
        if (offer.routines.isEmpty() || !offer.ratio.isFinite() || offer.ratio <= 0) return@withLock false
        persistAndSync(maxesChanged = false) { writer ->
            val maxes = table(newestPerGrip(checkNotNull(writer.allMaxes()) { "Couldn't read existing maxes" }))
            check(maxes.exact(offer.grip.key, Side.both) == offer.newMaxKg) { "The shared max has changed" }
            val current = checkNotNull(writer.allRoutines()) { "Couldn't read routines" }.groupBy { it.id }
            val updated = offer.routines.map { proposal ->
                val matches = checkNotNull(current[proposal.routineID]) { "The reviewed routine is missing" }
                check(matches.size == 1) { "The reviewed routine is ambiguous" }
                val routine = matches.single()
                check(routine.plan == offer.expectedPlans[proposal.routineID]) { "The reviewed routine has changed" }
                check(routine.plan.handMode == HandMode.bothHands || listOf(Side.left, Side.right).all {
                    maxes.exact(offer.grip.key, it) == null
                }) { "The hands now have separate maxes" }
                val draft = routine.draft
                val changed = draft.copy(plan = draft.plan.copy(sets = draft.plan.sets.map { set ->
                    if (set.grip.key != offer.grip.key || !set.hasTarget) set else set.copy(
                        targetLoKg = set.targetLoKg?.let { scaledKg(it, offer.ratio) },
                        targetHiKg = set.targetHiKg?.let { scaledKg(it, offer.ratio) },
                    )
                }))
                routine.applying(changed.normalized)
            }
            updated.forEach { writer.putRoutine(it) }
        }
    }

    /// Apply the accepted rescale: every explicit-kg set on `grip` in the given routines,
    /// times `ratio`, normalized as a builder save so it cannot produce a routine the
    /// builder would refuse.
    ///
    /// **Applied to every routine, then persisted ONCE**, not `save(draft)` per routine:
    /// that would drag a recompute and replan behind each, call `clearDraft()` (discarding
    /// the builder's rescue copy from a Maxes-tab tap), and could leave half the offer
    /// scaled when a write fails. One write rolls back together.
    ///
    /// Does NOT `askNotificationPermissionOnce`: these routines have all been through the
    /// builder, and an OS dialog from a max-entry sheet arrives with no reason on screen.
    suspend fun scaleKgTargets(grip: GripSpec, ratio: Double, routineIDs: List<UUID>): Boolean {
        if (!ratio.isFinite() || ratio <= 0) return false
        // ONE fetch for the batch rather than `routine(id)` per id.
        val routines = gateway.allRoutines() ?: return false
        val wanted = routineIDs.toSet()

        val updated = mutableListOf<SessionTemplateEntity>()
        for (template in routines) {
            if (!wanted.contains(template.id)) continue
            var draft = template.draft
            draft = draft.copy(
                plan = draft.plan.copy(
                    sets = draft.plan.sets.map { set ->
                        if (set.grip.key != grip.key || !set.hasTarget) return@map set
                        set.copy(
                            targetLoKg = set.targetLoKg?.let { scaledKg(it, ratio) },
                            targetHiKg = set.targetHiKg?.let { scaledKg(it, ratio) },
                        )
                    }
                )
            )
            updated.add(template.applying(draft.normalized))
        }

        // Nothing touched: no save, no rollback, and no pointless full recompute.
        if (updated.isEmpty()) return true
        // `recordMax` already wrote and folded the new max; this moves routines only.
        return persistAndSync(maxesChanged = false) { writer ->
            updated.forEach { writer.putRoutine(it) }
        }
    }

    /// Half-kilogram rounding like the percent path, so a scaled typed number looks
    /// typeable.
    private fun scaledKg(kg: Double, ratio: Double): Double = PlanMath.roundedToHalfKg(kg * ratio)

    private fun scaled(
        band: ClosedFloatingPointRange<Double>,
        ratio: Double,
    ): ClosedFloatingPointRange<Double> {
        val lo = scaledKg(band.start, ratio)
        val hi = scaledKg(band.endInclusive, ratio)
        return minOf(lo, hi)..maxOf(lo, hi)
    }

    // MARK: - Draft rescue

    /// First run is the one place real effort goes in before anything persists, so the
    /// draft is stashed as it changes. Cleared on BOTH Save and Cancel — a stash outliving
    /// Cancel comes back as a ghost.
    fun stashDraft(draft: RoutineDraft) {
        settings.setDraftStash(BlobCodec.encode(draft))
    }

    fun restoreDraft(): RoutineDraft? =
        settings.draftStash?.let { BlobCodec.decode(it) { e -> RoutineDraft.fromJson(e) } }

    fun clearDraft() {
        settings.setDraftStash(null)
    }

    // MARK: - Side-effect pipeline

    /// The ONE write path, and the reason a mutation cannot skip the recompute.
    ///
    /// `maxesChanged = false` asserts this write touched no `MaxRecord`, letting
    /// `syncDerived` skip the unbounded fetch. Defaults to TRUE so forgetting is safe; the
    /// debug check below catches a lying `false`, which would leave `maxTable` (and every
    /// percent band) stale.
    ///
    /// TRANSLATION NOTE: iOS calls `context.rollback()` by hand when a save throws, so
    /// memory matches disk. Here the unit of work is a Room TRANSACTION: a failure wrote
    /// nothing and the following `syncDerived` republishes from disk, so rollback is
    /// structural.
    ///
    /// **Returns whether THIS write committed** — the only answer a caller may act on (see
    /// `saveError`).
    private suspend fun persistAndSync(
        maxesChanged: Boolean = true,
        criticalForceChanged: Boolean = false,
        work: suspend (StoreWriter) -> Unit,
    ): Boolean {
        saveError = null
        val committed = try {
            gateway.write { writer ->
                val audited = AuditingWriter(writer)
                work(audited)
                if (BuildConfig.DEBUG && !maxesChanged && audited.touchedMax) {
                    error(
                        "persistAndSync(maxesChanged = false) wrote a MaxRecord — " +
                            "the max table would not be refolded."
                    )
                }
            }
            true
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            saveError = L10n.tr("That change couldn't be saved — %s", error.message ?: "")
            false
        }
        syncDerived(refoldingMaxes = maxesChanged, refoldingCriticalForce = criticalForceChanged)
        return committed
    }

    /// Asked on the first Save of a routine that WANTS reminders: by then the reason for
    /// the OS dialog is on the previous screen.
    private fun askNotificationPermissionOnce(draft: RoutineDraft) {
        if (!draft.remindersEnabled || draft.reminders.isEmpty()) return
        if (settings.didAskNotificationPermission) return
        val gate = notificationPermissionGate ?: return
        // Set BEFORE the ask: two quick saves would otherwise both see false and stack two
        // OS dialogs.
        settings.setDidAskNotificationPermission(true)
        gate.request { granted ->
            // **Denied is a dead end for the NOTIFICATION, never for the setting.**
            // `remindersEnabled` stays as the user left it and nothing is scheduled, so
            // granting in system Settings later just works. The flag only lets the builder
            // SAY so; the planner already refuses plans it cannot post
            // (`AndroidAlarmScheduler.canPost`).
            settings.setDeniedNotifications(!granted)
            // Plan now if granted: the replan inside the save saw an app that could not
            // post.
            scope.launch { syncDerived() }
        }
    }

    private suspend fun firstRoutineSessionsPerDay(): Int =
        gateway.allRoutines()?.sortedWith(routineOrder)?.firstOrNull()?.sessionsPerDay ?: 1

    companion object {
        private const val consistencyDays = 14
        private const val recentGripLimit = 6

        /// Bump to run the training-day repair once more on every device — only if its
        /// rule itself changes. See `repairTrainingDaysOnce`.
        const val trainingDayRepairVersion = 1

        const val defaultUndoWindowMillis = 10_000L
        private const val longUndoWindowMillis = 600_000L

        /// Headless UI verification: `adb shell am start … --ez longUndo true`. DEBUG
        /// builds only, like iOS `-longUndo`.
        fun undoWindowMillis(intent: Intent?): Long =
            if (BuildConfig.DEBUG && intent?.getBooleanExtra("longUndo", false) == true) {
                longUndoWindowMillis
            } else {
                defaultUndoWindowMillis
            }

        /// The TOTAL order. Concurrent reorders can produce DUPLICATE `sortIndex` values
        /// (no uniqueness constraint is possible), and a partial sort leaves ties to fetch
        /// order, so two reads disagree. `id.toString()` is the arbitrary-but-stable
        /// tiebreak, applied in memory.
        val routineOrder: Comparator<SessionTemplateEntity> =
            compareBy<SessionTemplateEntity> { it.sortIndex }
                .thenBy { it.createdAt }
                .thenBy { it.id.toString() }

        /// Oldest first, total: two tests saved in one millisecond still have one order.
        val criticalForceOrder: Comparator<CriticalForceRecordEntity> =
            compareBy<CriticalForceRecordEntity> { it.recordedAt }.thenBy { it.id.toString() }

        /// Ordered widest-and-fullest first: the grips a beginner should reach for before
        /// the two-finger and full-crimp ones further down.
        val seedGrips: List<GripSpec> = listOf(
            GripSpec(20, FingerSet.four, GripPosition.halfCrimp),
            GripSpec(20, FingerSet.frontThree, GripPosition.halfCrimp),
            GripSpec(20, FingerSet.four, GripPosition.openHand),
            GripSpec(20, FingerSet.frontTwo, GripPosition.openHand),
            GripSpec(20, FingerSet.middleTwo, GripPosition.openHand),
            GripSpec(20, FingerSet.four, GripPosition.fullCrimp),
        )
    }
}

/// Notes whether a unit of work touched the max table, so `persistAndSync` can catch a
/// lying `maxesChanged = false`. The writer is the only door, so it can simply count.
private class AuditingWriter(private val inner: StoreWriter) : StoreWriter {
    var touchedMax = false
        private set

    override suspend fun allRoutines() = inner.allRoutines()
    override suspend fun allMaxes() = inner.allMaxes()
    override suspend fun logsFrom(dayKey: Int) = inner.logsFrom(dayKey)

    override suspend fun putRoutine(row: SessionTemplateEntity) = inner.putRoutine(row)
    override suspend fun removeRoutine(id: UUID) = inner.removeRoutine(id)
    override suspend fun putLog(row: WorkoutLogEntity) = inner.putLog(row)
    override suspend fun removeLog(id: UUID) = inner.removeLog(id)
    override suspend fun refileLog(id: UUID, dayKey: Int) = inner.refileLog(id, dayKey)
    override suspend fun putMax(row: MaxRecordEntity) {
        touchedMax = true
        inner.putMax(row)
    }

    override suspend fun removeMax(id: UUID) {
        touchedMax = true
        inner.removeMax(id)
    }

    override suspend fun putCriticalForce(row: CriticalForceRecordEntity) = inner.putCriticalForce(row)
    override suspend fun removeCriticalForce(id: UUID) = inner.removeCriticalForce(id)
}

/// See `LocalDeviceStore` for why this is `staticCompositionLocalOf`.
val LocalTemplateStore: ProvidableCompositionLocal<TemplateStore> = staticCompositionLocalOf {
    error("LocalTemplateStore was read outside a CompositionLocalProvider")
}
