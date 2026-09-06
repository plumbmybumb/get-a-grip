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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import run.nuri.getagrip.BuildConfig
import run.nuri.getagrip.data.MaxRecordEntity
import run.nuri.getagrip.data.SessionTemplateEntity
import run.nuri.getagrip.data.WorkoutLogEntity
import run.nuri.getagrip.data.benchmark
import run.nuri.getagrip.data.climb
import run.nuri.getagrip.data.storedNow
import run.nuri.getagrip.engine.BlobCodec
import run.nuri.getagrip.engine.DayRecord
import run.nuri.getagrip.engine.DayStamp
import run.nuri.getagrip.engine.FingerSet
import run.nuri.getagrip.engine.FingerStrain
import run.nuri.getagrip.engine.GripPosition
import run.nuri.getagrip.engine.GripSpec
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
import java.time.LocalTime
import java.util.UUID

/// Mutation hub: every write funnels through here so the side-effect pipeline
/// (persist → derived recompute → reminder replan) can never be skipped.
///
/// TRANSLATION NOTE (Sources/Store/TemplateStore.swift). Three shape changes, each
/// forced and each stated where it bites:
///
/// 1. **Every mutation is `suspend`.** Room must not touch the main thread, and there is
///    no `@MainActor` context that could hide the hop. Reads that the UI makes per frame
///    — `completed`, `completionText`, `summary` — stay synchronous, because they are
///    pure folds over state this class has already published.
/// 2. **`routines` is PUBLISHED here.** iOS reads them with `@Query`, which tracks
///    CloudKit merges live; Android has no merges to track and one total order
///    (`routineOrder`) that a SQL `ORDER BY` cannot express, so the same recompute that
///    publishes the derived values publishes the list.
/// 3. **A routine is a VALUE.** SwiftData's "was this object deleted under me"
///    (`modelContext != nil`) becomes a re-fetch by id, and mutating one produces a new
///    row rather than editing a live object.
///
/// What `@Query` could not give iOS is exactly what this publishes either way:
/// everything derived from more than one table — today's completion counts, the 14-day
/// consistency strip, the recent grips the builder offers, the current max per grip.
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
    /// How long "Undo" stays on offer. Ten seconds, or ten minutes behind the debug
    /// launch extra — headless UI verification round-trips are slower than any human.
    private val undoWindowMillis: Long = defaultUndoWindowMillis,
) {

    /// Every routine, in the total order below. Empty until the first `syncDerived`.
    var routines: List<SessionTemplateEntity> by mutableStateOf(emptyList())
        private set

    /// Sessions logged today, keyed by routine.
    var completionsToday: Map<UUID, Int> by mutableStateOf(emptyMap())
        private set

    /// Hangs logged BY HAND today. They belong to no routine — you did not run one, so
    /// there is nothing to attribute them to — but they are still one of the day's
    /// sessions, so `completed(_)` adds them to every routine's count. A climb settles
    /// every routine's day on exactly the same principle.
    ///
    /// Without this the app contradicts itself on two screens at once: the consistency
    /// grid folds on `countsAsHang` and draws "1 of 2", while Today's card reads the
    /// routine-attributed map and says "0 of 2" — and the evening reminder still fires
    /// on a night already trained, which is the one failure the whole rule exists to
    /// prevent.
    var unattributedHangsToday: Int by mutableStateOf(0)
        private set

    /// The climb logged today, if any — null on an ordinary day. Its mere presence
    /// completes the day; see `isDoneForToday`.
    var climbToday: SessionKind? by mutableStateOf(null)
        private set

    /// Whether a benchmark was logged today — set when the day's first MEASURED max
    /// lands (see `recordMax`). Settles the day exactly like a climb; kept as its own
    /// flag rather than folded into `climbToday` because the card copy and the grid
    /// notch must not describe a max-testing morning as a trip to the gym.
    var benchmarkedToday: Boolean by mutableStateOf(false)
        private set

    /// When the newest gauge-MEASURED max was recorded, across every grip. The Maxes
    /// tab's staleness line and its icon's soft-nudge pulse both read this; manual edits
    /// deliberately don't move it, because typing a number is not a test.
    var lastMeasuredMaxAt: Instant? by mutableStateOf(null)
        private set

    /// Exactly 14 entries, oldest first — the consistency strip's whole input.
    var consistency: List<DayRecord> by mutableStateOf(emptyList())
        private set

    /// At most 6, newest first, deduped by canonical key.
    var recentGrips: List<GripSpec> by mutableStateOf(emptyList())
        private set

    /// What each routine is called RIGHT NOW, by id.
    ///
    /// History freezes a routine's name into every log at save time, which it must — a
    /// deleted routine still has to have something to be called. But while the routine
    /// still exists, the frozen copy is just a stale label. Displays resolve through this
    /// and fall back to the frozen name, so a rename shows up everywhere at once and a
    /// deleted routine keeps its history intact.
    var routineNames: Map<UUID, String> by mutableStateOf(emptyMap())
        private set

    /// Newest `MaxRecordEntity` per canonical grip AND HAND key. The table is
    /// append-only, so "current" is a fold, never a mutable row.
    var currentMaxes: Map<String, MaxRecordEntity> by mutableStateOf(emptyMap())
        private set

    /// The same maxes as the engine consumes them: by grip AND hand, in kilograms.
    ///
    /// STORED and rebuilt on the same pass as `currentMaxes`, never computed per access.
    /// The builder reads it inside a card body that re-evaluates on every frame of a
    /// drag, and rebuilding a map there would put an allocation on the hot path for a
    /// value that only changes when a max is recorded.
    var maxTable: MaxTable by mutableStateOf(MaxTable())
        private set

    /// The first day the app had anything to track. Days before it are hairlines in the
    /// strip, not missed sessions.
    var trackingSince: DayStamp? by mutableStateOf(null)
        private set

    /// The last deleted routine, held briefly so the swipe can be taken back. Delete
    /// carries no confirmation dialog — cheap undo is the forgiveness, and a routine is
    /// six sets of authored intent, so losing one silently is expensive.
    ///
    /// TRANSLATION NOTE: iOS needs a `DeletedRoutine` struct to snapshot the RAW columns
    /// off a live `@Model` object. The Room row already IS those raw columns, blobs
    /// included, so the entity is the snapshot — and a column added to the entity can
    /// never go missing from the undo the way `isOnDemand` did on iOS.
    var lastDeleted: SessionTemplateEntity? by mutableStateOf(null)
        private set

    /// The same offer for a deleted session, on its own slot rather than sharing the
    /// routine's. They live on different tabs and each screen shows its own bar, so one
    /// shared slot would let a delete on History silently retract the Undo still on offer
    /// on Today — and a session is unrepeatable in a way a routine is not.
    var lastDeletedSession: WorkoutLogEntity? by mutableStateOf(null)
        private set

    /// Set when a write fails. The failed change has already been rolled back — it was
    /// one transaction — by the time a view reads this.
    var saveError: String? by mutableStateOf(null)

    /// A scanned routine (or the reason a scan failed), HELD rather than presented.
    ///
    /// A `getagrip://` link arrives from outside the app entirely — the system camera, a
    /// message — and can land while a full-screen destination owns the screen.
    /// Presenting from the root at that moment was measured tearing that destination
    /// down on iOS (2026-08-19): a running SESSION died unlogged, around every safeguard
    /// the runner has. So the URL is decoded here into a value, and Today — the one
    /// screen that owns every conflicting presentation — drains the inbox when nothing
    /// else is up. One slot, latest scan wins: two codes scanned back to back are one
    /// decision, about the second one.
    var pendingImport: RoutineDraft? by mutableStateOf(null)
        private set

    var pendingImportError: String? by mutableStateOf(null)
        private set

    /// The day `syncDerived` last published for. Kept separately from `clock.today` so a
    /// failed fetch leaves it stale and the next call retries rather than concluding the
    /// day is already handled.
    private var syncedDay: DayStamp = clock.today

    private var undoExpiry: Job? = null
    private var sessionUndoExpiry: Job? = null

    /// Fulfilled by the Activity when the wave that ships the builder wires it. Until
    /// then `askNotificationPermissionOnce` does its one-shot bookkeeping and has nobody
    /// to ask, which is deliberate: no dialog is raised anywhere yet.
    var notificationPermissionGate: NotificationPermissionGate? = null

    // MARK: - The day

    /// From the Activity's `onResume` and whenever the clock ticks: a phone left open
    /// past midnight must flip 2/2 back to 0/2 without a relaunch.
    ///
    /// Deliberately does NOT call `clock.refresh()`: **the store REACTS to the clock, it
    /// does not drive it.** Pushing the clock from here re-pins `today` to the system
    /// date on every call, which silently defeats `DayClock.advance(to:)` — the seam that
    /// makes crossing midnight testable at all. The Activity refreshes the clock on
    /// resume and `DayClockReceiver` refreshes it on a broadcast; both paths land here
    /// afterwards.
    suspend fun refreshIfDayChanged() {
        if (clock.today == syncedDay) return
        syncDerived()
    }

    /// Cheap full recompute — a handful of routines and at most 14 days of logs.
    ///
    /// BAILS rather than publishing an empty world it isn't sure about: a null fetch
    /// means the read FAILED, which is not "no routines". Collapsing those two would
    /// blank the consistency strip and, worse, hand `ReminderPlanner` an empty plan that
    /// cancels every reminder the user has.
    ///
    /// `refoldingMaxes = false` skips the ONE fetch here that has no ceiling on it — see
    /// `allMaxes` — and is the caller stating that no `MaxRecord` moved. It defaults to
    /// true so every external trigger (launch, midnight, the permission callback) still
    /// refolds unconditionally; only the internal write path opts out, and only where it
    /// can prove it wrote no max.
    suspend fun syncDerived(refoldingMaxes: Boolean = true) {
        val fetched = gateway.allRoutines() ?: return
        val ordered = fetched.sortedWith(routineOrder)
        val today = clock.today
        val earliest = today - (consistencyDays - 1)
        val logs = gateway.logsFrom(earliest.raw) ?: return
        // Skipping the fetch and FAILING it are different things and must not collapse
        // into one: a null read still bails for the same reason the two above do, while a
        // skip publishes the rest and leaves the three max-derived values standing.
        var maxes: List<MaxRecordEntity>? = null
        if (refoldingMaxes) maxes = gateway.allMaxes() ?: return

        syncedDay = today
        routines = ordered
        completionsToday = completions(logs, today)
        unattributedHangsToday = unattributedHangs(logs, today)
        climbToday = logs.climb(today)
        benchmarkedToday = logs.benchmark(today)
        trackingSince = trackingStart(ordered, logs)
        consistency = consistency(today, logs, ordered.firstOrNull(), trackingSince)
        recentGrips = recentGrips(ordered)
        // Last key wins would be just as arbitrary; what matters is that a duplicate id
        // is survivable rather than a crash.
        routineNames = ordered.associate { it.id to it.name }
        if (maxes != null) {
            currentMaxes = newestPerGrip(maxes)
            maxTable = table(currentMaxes)
            // `maxes` arrives sorted by `recordedAt`, so the last measured one is newest.
            lastMeasuredMaxAt = maxes.lastOrNull { it.source == MaxSource.measured }?.recordedAt
        }

        // Recomputed here, on the same pass that recomputed the completion counts, so
        // finishing a session re-plans the day's remaining reminders in the same breath
        // that Today's "2 of 2" appears. `refreshIfDayChanged` runs this again at
        // midnight, which is what restores tomorrow's full set.
        val inputs = ordered.map { template ->
            // `completed(_)`, not the raw map: a hang logged by hand has to silence the
            // evening reminder too, and reading the map directly here is exactly how the
            // card and the reminder drift apart.
            val done = completed(template)
            // A climb — or a benchmark — ZEROES the day's outstanding sessions, which is
            // what stops the evening reminder firing on a night already spent at the gym
            // or a morning spent testing maxes. That is the single most felt consequence
            // of the rule: being told to hangboard after you have just trained is the app
            // failing to notice.
            val outstanding = if (climbToday != null || benchmarkedToday) {
                0
            } else {
                maxOf(0, maxOf(1, template.sessionsPerDay) - done)
            }
            ReminderPlanner.RoutinePlanInput(
                id = template.id,
                name = template.name,
                reminders = template.reminders,
                // Whenever routines never remind. Normalization already forces their
                // switch off on save; this is the belt for templates written by a build
                // that predates the rule.
                enabled = template.remindersEnabled && !template.isOnDemand,
                outstandingToday = outstanding,
            )
        }
        // Off the caller's turn, exactly as iOS detaches it: an `AlarmManager` write per
        // slot is not something a save should wait on.
        scope.launch { ReminderPlanner.replan(inputs, scheduler) }
    }

    // MARK: - Derived computations (pure over what was fetched)

    /// HANG sessions only, per routine. A climb has no routine to attribute to and does
    /// not fill a slot — it settles the whole day, which is a separate question asked by
    /// `climbToday`.
    private fun completions(logs: List<WorkoutLogEntity>, day: DayStamp): Map<UUID, Int> {
        val counts = HashMap<UUID, Int>()
        for (log in logs) {
            if (log.dayKey != day.raw || log.kind.isClimb) continue
            // A log whose routine was deleted still counts as a session trained, but it
            // has no routine to attribute to — grouping is best-effort by design.
            val id = log.templateID ?: continue
            counts[id] = (counts[id] ?: 0) + 1
        }
        return counts
    }

    /// `hangManual` ONLY, deliberately — not every log with a null `templateID`. A runner
    /// session whose routine was later deleted also has no id, and it is dropped on
    /// purpose (see `completions`); crediting those here would retroactively change how
    /// old days score. A hand-logged hang never had a routine to begin with.
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
        // Today's target comes from the routine Today opens on; a past day's comes from
        // what the logs themselves froze, so raising sessions-per-day tomorrow never
        // retroactively turns last week into a wall of half-full days.
        val currentTarget = primary?.sessionsPerDay ?: 0

        return (0 until consistencyDays).map { offset ->
            val day = today - (consistencyDays - 1 - offset)
            val dayLogs = byDay[day.raw] ?: emptyList()
            val target = dayLogs.maxOfOrNull { it.sessionsPerDayTarget } ?: currentTarget
            DayRecord(
                day = day,
                // Hang sessions, including hangs logged by hand, so the "1 of 2" a cell
                // draws still means hang rounds; a climb is carried separately and fills
                // the cell on its own. A benchmark log is neither — it fills via its own
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
        // With no history at all the RECENT rail would be empty on the one screen where
        // it helps most — a blank routine, where every set has to be built by hand. The
        // seed palette is the common no-hang vocabulary, so the rail is a shortcut from
        // the first tap rather than a feature that only appears once you no longer need it.
        return grips.ifEmpty { seedGrips }
    }

    /// Newest per GRIP **AND HAND** — see `MaxRecordEntity.maxKey`. Keyed on the grip
    /// alone, recording a right-hand max would supersede the left-hand one you took a
    /// minute earlier, and one of your two hands would silently lose its number.
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

    /// Derived from `currentMaxes` in the same breath, so the two can never disagree
    /// about what your max is.
    private fun table(newest: Map<String, MaxRecordEntity>): MaxTable {
        val table = MaxTable()
        for (record in newest.values) table.record(record.kg, record.gripKey, record.side)
        return table
    }

    // MARK: - Reads

    suspend fun routine(id: UUID): SessionTemplateEntity? = gateway.routine(id)

    fun plan(template: SessionTemplateEntity): SessionPlan = template.plan

    /// null means "new", and a new routine is BLANK — never `.starter`. No screen offers
    /// a prefill any more, so seeding one here would silently put the full daily protocol
    /// under someone adding a rest day.
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
            // Against the live max table, so recording a max recolours the rung on the
            // next summary rebuild — intensity is a fact about TODAY's prescription,
            // unlike the runner's freeze-at-start rule for what a session displays.
            peakIntensity = PlanMath.peakIntensity(plan, maxTable),
        )
    }

    /// Wraps to tomorrow's first slot rather than returning null once the day's last
    /// reminder has passed: at 22:00 the honest answer is still "next at 08:00", and a
    /// row that empties itself in the evening reads as broken. Recomputed on every call
    /// because it depends on the wall clock, which is not observable.
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
    /// routine it displaced, so scoring that day as a miss was the app lying about the
    /// week (Nuri, 2026-08-05). Nothing is asked for afterwards — but the routine stays
    /// startable, because after an easy volume evening an extra hang round is perfectly
    /// reasonable. Offered, never demanded.
    fun isDoneForToday(template: SessionTemplateEntity): Boolean {
        if (template.isOnDemand) {
            // Never OWED — but "done" still means something: once you've run it today the
            // card earns its checkmark and Start demotes to "Start another".
            return climbToday != null || benchmarkedToday || completed(template) > 0
        }
        return climbToday != null || benchmarkedToday ||
            completed(template) >= maxOf(1, template.sessionsPerDay)
    }

    /// A whole sentence, not the "1 of 2" fragment beside it: this is what a screen
    /// reader reads, and "one of two" with no noun is the classic dashboard-accessibility
    /// failure.
    fun completionText(template: SessionTemplateEntity): String {
        val done = completed(template)
        val target = maxOf(1, template.sessionsPerDay)
        // The climb LEADS the sentence, because on a day you climbed it is the training
        // that happened — and any hang rounds are the extra, said second.
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
        // The climb wins the sentence when both happened — the gym is the day's story and
        // the benchmark still shows in History.
        if (benchmarkedToday) {
            return when (done) {
                0 -> L10n.tr("Maxes tested today")
                1 -> L10n.tr("Maxes tested today, plus a hang session")
                else -> L10n.tr("Maxes tested today, plus %d hang sessions", done)
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
            // "Both" rather than "2" for the twice-a-day case, which is the app's whole
            // reason for existing — it reads as a finished ritual rather than a tally.
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

    /// The Maxes tab's SOFT NUDGE: true once the newest measured max is four weeks stale.
    /// Nobody who has never measured gets nudged — there is nothing to re-test, and the
    /// tab's own empty state does the inviting. Four weeks because finger strength moves
    /// on a monthly timescale; there is deliberately no setting for it (a cadence you
    /// configure is a schedule, and the schedule was voted down for a pulse).
    val benchmarkNudge: Boolean
        get() {
            val last = lastMeasuredMaxAt ?: return false
            return storedNow().toEpochMilli() - last.toEpochMilli() >= 28L * 86_400_000L
        }

    fun suggestedBand(grip: GripSpec) = PlanMath.suggestedBand(currentMax(grip) ?: 0.0)

    /// Rung 2 of Today's selection rule: the routine started today on THIS device.
    /// DEVICE-LOCAL and day-scoped — a synced "primary" flag is the classic second-device
    /// bug, and yesterday's choice is not evidence about today.
    val suggestedRoutineID: UUID?
        get() {
            if (settings.lastStartedDayRaw != clock.today.raw) return null
            return settings.lastStartedRoutineID
        }

    // MARK: - Mutations

    /// The ONE entry point the builder calls, so the builder never has to know whether it
    /// is creating or editing.
    suspend fun save(draft: RoutineDraft): SessionTemplateEntity? {
        val normalized = draft.normalized
        val existing = normalized.templateID?.let { gateway.routine(it) }
        val saved = if (existing != null) {
            if (update(existing, normalized)) gateway.routine(existing.id) else null
        } else {
            // templateID null, or deleted while this editor was open. Re-creating is the
            // only non-destructive answer to the latter: the alternative silently
            // discards work the user is in the middle of saving.
            create(normalized)
        }
        // Only on success — a rollback leaves the sheet open with the error inline, and
        // the rescue copy has to survive for the retry.
        if (saved != null) clearDraft()
        return saved
    }

    suspend fun create(draft: RoutineDraft): SessionTemplateEntity? {
        val siblings = gateway.allRoutines()
        if (siblings == null) {
            // A failed READ is not "no routines": minting sortIndex 0 here would make
            // this the primary routine and push whatever exists behind it.
            saveError = L10n.tr("Couldn't read your routines just now — the new one wasn't saved.")
            return null
        }
        val normalized = draft.normalized
        val row = SessionTemplateEntity.from(
            draft = normalized,
            sortIndex = (siblings.maxOfOrNull { it.sortIndex } ?: -1) + 1,
        )
        persistAndSync(maxesChanged = false) { it.putRoutine(row) }
        if (saveError != null) return null
        askNotificationPermissionOnce(normalized)
        return row
    }

    suspend fun update(template: SessionTemplateEntity, draft: RoutineDraft): Boolean {
        // A routine deleted under us is refused rather than resurrected. iOS guards
        // `modelContext != nil` because mutating a faulted `@Model` throws an ObjC
        // exception no Swift `catch` can reach; here an upsert would simply INSERT the
        // row again, which is worse — a delete the user made would silently undo itself.
        if (gateway.routine(template.id) == null) return false
        val normalized = draft.normalized
        persistAndSync(maxesChanged = false) { it.putRoutine(template.applying(normalized)) }
        if (saveError != null) return false
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

    /// The name an import WILL land under, so the preview can promise it rather than a
    /// name the store is about to change — two people keeping the shipped default and
    /// exchanging codes is the common case, not the edge one.
    suspend fun plannedImportName(wanted: String): String = uniqueName(wanted)

    /// A routine that arrived from somebody else's QR code — `duplicate`'s twin, and
    /// deliberately the same two moves: deconflict the name, then `create`. There is no
    /// second save path, so an import lands at the END of the sort order like every other
    /// new routine and can never displace the one Today opens on.
    ///
    /// Two facts are re-asserted here rather than trusted from the wire, because this is
    /// the last gate before disk and the payload is untrusted input:
    ///
    /// - **`templateID` is nulled.** `SessionTemplateEntity.from` ADOPTS a draft's id, so
    ///   a code carrying its author's UUID would mint a routine wearing somebody else's
    ///   identity — and that id is what `doigt.routine.<uuid>.<slot>` reminder
    ///   identifiers are built from. Same reason `RoutineDraft.copying` nulls it.
    /// - **Reminders are forced OFF.** They are personal times the payload deliberately
    ///   omits, and `create` asks for notification permission whenever a draft arrives
    ///   with them on — an OS prompt raised by scanning a stranger's code is an ambush,
    ///   not a request.
    suspend fun importRoutine(draft: RoutineDraft): SessionTemplateEntity? {
        // Normalized FIRST so the name `uniqueName` deconflicts is the name that will
        // actually be written — an empty one becomes the house default on the way in, and
        // deconflicting the empty string would let two "Daily no-hangs" through.
        var incoming = draft.normalized
        incoming = incoming.copy(templateID = null, remindersEnabled = false)
        incoming = incoming.copy(plan = incoming.plan.copy(name = uniqueName(incoming.plan.name)))
        return create(incoming)
    }

    /// Duplicating twice must not produce two routines called "Copy of Daily no-hangs":
    /// the chooser rail shows names only, so identical ones make the second routine
    /// unpickable by sight. Suffixes count up from 2 — "Copy of X", "Copy of X 2".
    private suspend fun uniqueName(wanted: String): String {
        val taken = (gateway.allRoutines() ?: emptyList()).map { it.name }.toSet()
        if (!taken.contains(wanted)) return wanted
        var suffix = 2
        while (taken.contains("$wanted $suffix")) suffix += 1
        return "$wanted $suffix"
    }

    // MARK: - Delete and undo

    suspend fun delete(template: SessionTemplateEntity): Boolean {
        // The row as it stands on disk, not the caller's copy — the RAW columns, blobs
        // included, are what `undoDelete` puts back.
        val restorable = gateway.routine(template.id) ?: return false
        persistAndSync(maxesChanged = false) { it.removeRoutine(template.id) }
        // Only offer undo for a delete that actually landed — the transaction rolled
        // back on failure, so the routine is still there and "Undo" would duplicate it.
        if (saveError != null) return false
        lastDeleted = restorable
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
    /// The id matters because `doigt.routine.<uuid>.r0480` identifiers are content-keyed
    /// on it — a new id would leave the old reminders orphaned and firing. The raw blobs
    /// matter because decoding and re-encoding a routine written by a NEWER build drops
    /// every field this one does not understand, and losing a field to the gesture whose
    /// entire job is putting things back is the worst possible place for it. Which is
    /// exactly why the snapshot is the ENTITY and not a hand-listed struct: a column this
    /// row grows cannot go missing from the restore.
    suspend fun undoDelete() {
        val restorable = lastDeleted ?: return
        undoExpiry?.cancel()

        // `updatedAt` is deliberately NOT restored: the restore is itself the most recent
        // thing that happened to this routine, and `recentGrips` reads that order.
        val row = restorable.copy(updatedAt = storedNow())
        persistAndSync(maxesChanged = false) { it.putRoutine(row) }

        // Only consume the undo once the restore has landed. Clearing it first would mean
        // a rolled-back save loses the routine for good — the one outcome the undo bar
        // exists to prevent.
        if (saveError == null) lastDeleted = null else armUndoExpiry()
    }

    fun dismissUndo() {
        undoExpiry?.cancel()
        undoExpiry = null
        lastDeleted = null
    }

    // MARK: - Order

    /// TRANSLATION NOTE: SwiftUI's `move(fromOffsets:toOffset:)`, where `toOffset` is an
    /// index in the array BEFORE the removal. Spelled out here because a naive
    /// remove-then-insert-at-`to` disagrees with it whenever `to > from`, and the two are
    /// indistinguishable in the common single-step drag.
    suspend fun move(fromIndex: Int, toOffset: Int) {
        val current = gateway.allRoutines()?.sortedWith(routineOrder) ?: return
        if (fromIndex !in current.indices) return
        val mutable = current.toMutableList()
        val moved = mutable.removeAt(fromIndex)
        val insertAt = if (toOffset > fromIndex) toOffset - 1 else toOffset
        mutable.add(insertAt.coerceIn(0, mutable.size), moved)
        renumber(mutable)
    }

    /// Position 0 IS the primary routine — the one Today opens on. There is no separate
    /// `isPrimary` column, because a flag that can disagree with the order is a second
    /// source of truth.
    suspend fun makePrimary(template: SessionTemplateEntity) {
        val current = gateway.allRoutines()?.sortedWith(routineOrder) ?: return
        val index = current.indexOfFirst { it.id == template.id }
        if (index < 0) return
        val mutable = current.toMutableList()
        mutable.add(0, mutable.removeAt(index))
        renumber(mutable)
    }

    /// Renormalizes to a dense 0..<n so two routines that ended up sharing a `sortIndex`
    /// are repaired by the next reorder rather than persisting as a tie broken
    /// differently on each read.
    private suspend fun renumber(ordered: List<SessionTemplateEntity>) {
        persistAndSync(maxesChanged = false) { writer ->
            ordered.forEachIndexed { index, template ->
                if (template.sortIndex == index) return@forEachIndexed
                // `updatedAt` untouched: reordering is not an edit to the routine's
                // content, and bumping it would scramble the "recently authored" grip
                // order.
                writer.putRoutine(template.copy(sortIndex = index))
            }
        }
    }

    // MARK: - Sessions

    /// Rung 2 of Today's selection rule is written here, on START rather than on finish,
    /// because the useful question at 19:00 is "which one am I in the middle of", not
    /// "which one did I complete".
    fun noteSessionStarted(template: SessionTemplateEntity) {
        settings.setLastStartedRoutineID(template.id)
        settings.setLastStartedDayRaw(clock.today.raw)
    }

    /// Log a session after the fact — at the climbing gym or a hang session done away
    /// from the gauge. There is nothing for the app to time or measure in either case.
    ///
    /// `daysAgo` exists because the realistic moment to log one is the next morning.
    /// Clamped rather than validated: a negative would file training in the future.
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
            // Frozen like any other log, so changing sessions-a-day later cannot re-score
            // a day already lived.
            sessionsPerDayTarget = firstRoutineSessionsPerDay(),
            minutes = minutes,
            rpe = rpe,
            fingerStrain = fingerStrain,
            notes = notes,
        )
        persistAndSync(maxesChanged = false) { it.putLog(log) }
        return if (saveError == null) log else null
    }

    /// Write a finished session. Goes through the hub like every other mutation, so the
    /// completion count on Today and the consistency strip update in the same breath — a
    /// session that vanished until relaunch would read as lost work.
    ///
    /// `day` comes from the app's own `DayClock`, not the wall clock, so a session
    /// finished at 00:30 lands on the day the climber actually lived through.
    suspend fun recordSession(
        plan: SessionPlan,
        template: SessionTemplateEntity?,
        reps: List<RepSummary>,
        startedAt: Instant,
        finishedAt: Instant,
        rpe: RPE?,
        newMaxes: List<MaxRecordEntity> = emptyList(),
    ): WorkoutLogEntity? {
        if (newMaxes.any { !it.kg.isFinite() || it.kg <= 0 }) {
            saveError = L10n.tr("Couldn't save this workout. Please try again.")
            return null
        }
        val log = WorkoutLogEntity.from(
            plan = plan,
            templateID = template?.id,
            // FROZEN at save: renaming a routine later must not retro-rename history.
            templateName = template?.name ?: plan.name,
            sessionsPerDayTarget = template?.sessionsPerDay ?: 1,
            reps = reps,
            startedAt = startedAt,
            finishedAt = finishedAt,
            day = clock.today,
        ).copy(rpe = rpe?.rawValue)
        persistAndSync(maxesChanged = newMaxes.isNotEmpty()) { writer ->
            writer.putLog(log)
            newMaxes.forEach { writer.putMax(it) }
        }
        return if (saveError == null) log else null
    }

    /// Remove a session from history — the one destructive act on this data.
    ///
    /// It is not merely a row leaving a list: `dayKey` and `sessionsPerDayTarget` are what
    /// "2 of 2 today" and the consistency strip are counted from, so deleting today's
    /// session must walk Today's completion back in the same breath. `persistAndSync` is
    /// what guarantees that, which is why this goes through the hub like every other
    /// write rather than deleting from the screen.
    suspend fun deleteSession(log: WorkoutLogEntity): Boolean {
        // Captured as the raw row, blobs included — see `undoDeleteSession`.
        val restorable = (gateway.allLogs() ?: return false).firstOrNull { it.id == log.id }
            ?: return false
        persistAndSync(maxesChanged = false) { it.removeLog(log.id) }
        // Only offer undo for a delete that actually landed.
        if (saveError != null) return false
        lastDeletedSession = restorable
        armSessionUndoExpiry()
        return true
    }

    /// Re-insert with the ORIGINAL UUID, dates and raw blobs.
    ///
    /// The row goes back exactly as it was, and never through `WorkoutLogEntity.from`:
    /// that builder RE-DERIVES every denormalized number from the reps it is given, and
    /// re-encoding a plan this build cannot fully decode would drop whatever a newer one
    /// wrote. A session is a record of something that happened — putting it back must not
    /// recompute it. `kindRaw` rides along for the same reason: without it, undoing a
    /// deleted climb would put back a HANGBOARD session and the day it completed would
    /// silently go back to being incomplete.
    suspend fun undoDeleteSession() {
        val restorable = lastDeletedSession ?: return
        sessionUndoExpiry?.cancel()
        persistAndSync(maxesChanged = false) { it.putLog(restorable) }
        if (saveError == null) lastDeletedSession = null else armSessionUndoExpiry()
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

    suspend fun recordMax(
        kg: Double,
        grip: GripSpec,
        source: MaxSource = MaxSource.manual,
        side: Side = Side.both,
        marksBenchmarkDay: Boolean = true,
    ): Boolean {
        // A zero or NaN max would make every percentage-of-max caption in the app lie,
        // and `PlanMath.percentOfMax` would have to defend against it forever.
        if (!kg.isFinite() || kg <= 0) return false
        val record = MaxRecordEntity.from(grip = grip, kg = kg, source = source, side = side)

        // **A MEASURED max makes today a benchmark day** — the lightweight version of a
        // test session (Nuri, 2026-08-10): no ceremony, but the day still reads as
        // trained, the grid fills, and no reminder nags after maximal pulls. One log per
        // day however many grips get tested; typed numbers never create one, because
        // typing is not training. `marksBenchmarkDay = false` is the session-PR path: a
        // max hit INSIDE a routine already logged its session, and settling the day on
        // top would silently cancel the evening ritual.
        val alreadyBenchmarked = benchmarkedToday ||
            (gateway.logsFrom(clock.today.raw)?.benchmark(clock.today) ?: false)
        val benchmarkLog =
            if (marksBenchmarkDay && source == MaxSource.measured && !alreadyBenchmarked) {
                WorkoutLogEntity.logged(
                    kind = SessionKind.benchmark,
                    day = clock.today,
                    at = storedNow(),
                    sessionsPerDayTarget = firstRoutineSessionsPerDay(),
                )
            } else {
                null
            }

        persistAndSync {
            it.putMax(record)
            benchmarkLog?.let { log -> it.putLog(log) }
        }
        return saveError == null
    }

    suspend fun deleteMax(record: MaxRecordEntity): Boolean {
        if ((gateway.allMaxes() ?: return false).none { it.id == record.id }) return false
        persistAndSync { it.removeMax(record.id) }
        return saveError == null
    }

    // MARK: - What a new max moves

    /// Everything a new max on one grip changes across the routines, computed against the
    /// max it REPLACES — so it must be asked BEFORE `recordMax` (afterwards the old
    /// number is just history).
    data class MaxImpact(
        val percentMoves: List<PercentMove>,
        val kgOffers: List<KgOffer>,
        /// new ÷ old — what "scale with your new max" multiplies by. null when there was
        /// no old max, which is also why `kgOffers` is empty then.
        val ratio: Double?,
    ) {
        /// A percentage band that now resolves to different kilograms. INFORMATIONAL:
        /// percent targets follow the newest max by design — this is the visibility, not
        /// a consent form.
        data class PercentMove(
            val routineName: String,
            val loPercent: Double,
            val hiPercent: Double,
            /// null when the grip had no max before — the band never resolved until now.
            val oldBand: ClosedFloatingPointRange<Double>?,
            val newBand: ClosedFloatingPointRange<Double>,
        )

        /// Explicit-kilogram sets on this grip, offered a proportional rescale. An OFFER,
        /// never automatic: a number a person typed is never moved by arithmetic without
        /// a yes — the same precedence rule `PlanMath` states.
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

    suspend fun maxImpact(grip: GripSpec, oldKg: Double?, newKg: Double): MaxImpact {
        val routines = gateway.allRoutines()
            ?: return MaxImpact(emptyList(), emptyList(), null)
        val ratio = oldKg?.let { if (it > 0) newKg / it else null }
        val percentMoves = mutableListOf<MaxImpact.PercentMove>()
        val kgOffers = mutableListOf<MaxImpact.KgOffer>()

        for (routine in routines.sortedWith(routineOrder)) {
            val plan = routine.plan.executable
            val seenPercents = HashSet<String>()
            val moves = mutableListOf<MaxImpact.KgOffer.Move>()
            for (set in plan.sets) {
                if (set.grip.key != grip.key) continue
                val explicit = set.targetBand
                if (explicit != null) {
                    val r = ratio ?: continue
                    val move = MaxImpact.KgOffer.Move(explicit, scaled(explicit, r))
                    // Two byte-identical sets would offer the same line twice.
                    if (!moves.contains(move)) moves.add(move)
                } else {
                    val percent = PlanMath.targetPercent(set, plan) ?: continue
                    if (!seenPercents.add("${percent.start}–${percent.endInclusive}")) continue
                    val newBand = PlanMath.targetBand(set, plan, newKg) ?: continue
                    percentMoves.add(
                        MaxImpact.PercentMove(
                            routineName = routine.name,
                            loPercent = percent.start,
                            hiPercent = percent.endInclusive,
                            oldBand = oldKg?.let { PlanMath.targetBand(set, plan, it) },
                            newBand = newBand,
                        )
                    )
                }
            }
            if (moves.isNotEmpty()) {
                kgOffers.add(MaxImpact.KgOffer(routine.id, routine.name, moves))
            }
        }
        return MaxImpact(percentMoves, kgOffers, ratio)
    }

    /// Apply the accepted rescale: every explicit-kg set on `grip` in the given routines,
    /// multiplied by `ratio`. Normalized exactly as a builder save is, so a rescale cannot
    /// produce a routine the builder itself would have refused.
    ///
    /// **Applied to every routine first, then persisted ONCE.** Calling `save(draft)` per
    /// routine would drag a whole-store recompute and a reminder replan behind each one,
    /// for what is a single tap on a grip that appears in four routines. Going direct also
    /// stops it calling `clearDraft()`, which would have discarded the builder's unsaved
    /// rescue copy as a side effect of a tap in the Maxes tab, and makes the rescale
    /// ATOMIC — a write that fails now rolls every routine back together, where before it
    /// could leave half the offer scaled and half not.
    ///
    /// It deliberately does NOT `askNotificationPermissionOnce`: every routine here has
    /// already been through the builder at least once, which is where that question
    /// belongs — an OS permission dialog raised from a max-entry sheet arrives with no
    /// reason anywhere on screen.
    suspend fun scaleKgTargets(grip: GripSpec, ratio: Double, routineIDs: List<UUID>): Boolean {
        if (!ratio.isFinite() || ratio <= 0) return false
        // ONE fetch for the batch: asking `routine(id)` per id would pay for the read N
        // times over.
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

        // Nothing was touched, so there is nothing to save and no rollback to survive —
        // and an empty save would still cost a full recompute.
        if (updated.isEmpty()) return true
        // The new max was written and folded by `recordMax` before this offer was even
        // computed; this write moves routines only.
        persistAndSync(maxesChanged = false) { writer ->
            updated.forEach { writer.putRoutine(it) }
        }
        return saveError == null
    }

    /// Half-kilogram rounding, same as the percent path resolves to — a scaled typed
    /// number should look like a number someone could have typed.
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

    /// The builder's first-run flow is the one place a user can spend real effort before
    /// anything is persisted, so the working draft is stashed as it changes. Cleared on
    /// BOTH Save and Cancel — a stash that outlives an explicit Cancel comes back as a
    /// ghost the next time the builder opens.
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
    /// `maxesChanged = false` is the caller asserting that this write touched no
    /// `MaxRecord`, which is what lets `syncDerived` skip the unbounded fetch. It defaults
    /// to TRUE so the conservative answer is the one you get by forgetting, and the debug
    /// check below catches the other direction — a `false` that is a lie leaves `maxTable`
    /// stale, and every percent-of-max band in the app resolves through it.
    ///
    /// TRANSLATION NOTE: iOS stages changes on a `ModelContext`, saves, and calls
    /// `context.rollback()` by hand when the save throws, "so memory matches disk — a
    /// phantom routine that dies with the process is far worse than a visible error".
    /// Here the whole unit of work is a Room TRANSACTION, so a failure never wrote
    /// anything, and the `syncDerived` that follows republishes from the same disk. The
    /// rollback is structural rather than a call that could be forgotten.
    private suspend fun persistAndSync(
        maxesChanged: Boolean = true,
        work: suspend (StoreWriter) -> Unit,
    ) {
        saveError = null
        try {
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
        } catch (error: Throwable) {
            saveError = L10n.tr("That change couldn't be saved — %s", error.message ?: "")
        }
        syncDerived(refoldingMaxes = maxesChanged)
    }

    /// Asked on the first Save of a routine that actually WANTS reminders — by then the
    /// user has been through the builder and seen both times on screen, so the OS dialog
    /// arrives with its reason already on the previous screen.
    private fun askNotificationPermissionOnce(draft: RoutineDraft) {
        if (!draft.remindersEnabled || draft.reminders.isEmpty()) return
        if (settings.didAskNotificationPermission) return
        val gate = notificationPermissionGate ?: return
        // Set BEFORE the ask, not after: two saves in quick succession would both see
        // false and stack two OS dialogs, and the second one is the one that reads as a
        // bug.
        settings.setDidAskNotificationPermission(true)
        gate.request { granted ->
            // **Denied is a dead end for the NOTIFICATION, never for the setting.**
            // `remindersEnabled` stays exactly as the user left it, the plan stays in the
            // routine, and nothing is scheduled — so granting the permission in system
            // Settings later just works, with no second visit to the builder. The flag is
            // only so the builder can SAY so; the planner already refuses to install a plan
            // it cannot post (`AndroidAlarmScheduler.canPost`).
            settings.setDeniedNotifications(!granted)
            // Plan immediately if granted: the replan that ran inside the save saw an
            // app that could not post and scheduled nothing.
            scope.launch { syncDerived() }
        }
    }

    private suspend fun firstRoutineSessionsPerDay(): Int =
        gateway.allRoutines()?.sortedWith(routineOrder)?.firstOrNull()?.sessionsPerDay ?: 1

    companion object {
        private const val consistencyDays = 14
        private const val recentGripLimit = 6

        const val defaultUndoWindowMillis = 10_000L
        private const val longUndoWindowMillis = 600_000L

        /// Headless UI verification: `adb shell am start … --ez longUndo true` round-trips
        /// are slower than any human. DEBUG builds only, exactly like the iOS
        /// `-longUndo` argument.
        fun undoWindowMillis(intent: Intent?): Long =
            if (BuildConfig.DEBUG && intent?.getBooleanExtra("longUndo", false) == true) {
                longUndoWindowMillis
            } else {
                defaultUndoWindowMillis
            }

        /// The TOTAL order. Two writes that both reorder can produce DUPLICATE `sortIndex`
        /// values — there is no uniqueness constraint and there cannot be — and a partial
        /// sort leaves the tie to fetch order, so the same two routines render in
        /// different orders on two reads. `id.toString()` is the arbitrary-but-identical
        /// tiebreak, applied in memory because it is not a column SQL could sort on
        /// meaningfully.
        val routineOrder: Comparator<SessionTemplateEntity> =
            compareBy<SessionTemplateEntity> { it.sortIndex }
                .thenBy { it.createdAt }
                .thenBy { it.id.toString() }

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
/// `maxesChanged = false` that is a lie. The iOS twin inspects the context's pending
/// sets; here the writer is the only door, so it can simply count.
private class AuditingWriter(private val inner: StoreWriter) : StoreWriter {
    var touchedMax = false
        private set

    override suspend fun putRoutine(row: SessionTemplateEntity) = inner.putRoutine(row)
    override suspend fun removeRoutine(id: UUID) = inner.removeRoutine(id)
    override suspend fun putLog(row: WorkoutLogEntity) = inner.putLog(row)
    override suspend fun removeLog(id: UUID) = inner.removeLog(id)
    override suspend fun putMax(row: MaxRecordEntity) {
        touchedMax = true
        inner.putMax(row)
    }

    override suspend fun removeMax(id: UUID) {
        touchedMax = true
        inner.removeMax(id)
    }
}

/// See `LocalDeviceStore` for why this is `staticCompositionLocalOf`.
val LocalTemplateStore: ProvidableCompositionLocal<TemplateStore> = staticCompositionLocalOf {
    error("LocalTemplateStore was read outside a CompositionLocalProvider")
}
