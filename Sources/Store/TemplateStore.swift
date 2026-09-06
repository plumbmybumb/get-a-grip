// SPDX-License-Identifier: MPL-2.0
// Original contributions Copyright 2026 Nuri Bruner.

import CoreData
import Foundation
import Observation
import SwiftData
import UIKit

/// Mutation hub: every write funnels through here so the side-effect pipeline
/// (persist → derived recompute → reminder replan) can never be skipped.
///
/// Views READ routines via `@Query`, which tracks CloudKit merges live and needs no
/// help from a store. What `@Query` cannot give is everything derived from more than
/// one entity — today's completion counts, the 14-day consistency strip, the recent
/// grips the builder offers, the current max per grip — so that, and only that, is
/// what this publishes.
@Observable @MainActor
final class TemplateStore {
    private let context: ModelContext
    private let clock: DayClock
    private let settings: SettingsStore

    /// Where the routines actually live — Settings › About tells the truth about sync
    /// instead of asserting iCloud unconditionally.
    let storageMode: StorageMode

    /// Sessions logged today, keyed by routine. M2 writes no logs, so this is `[:]`
    /// for everyone; the shape is what M3 fills in without touching a view.
    private(set) var completionsToday: [UUID: Int] = [:]

    /// Hangs logged BY HAND today. They belong to no routine — you did not run one, so
    /// there is nothing to attribute them to — but they are still one of the day's
    /// sessions, so `completed(_:)` adds them to every routine's count. A climb settles
    /// every routine's day on exactly the same principle.
    ///
    /// Without this the app contradicts itself on two screens at once: the consistency
    /// grid folds on `countsAsHang` and draws "1 of 2", while Today's card reads the
    /// routine-attributed map and says "0 of 2" — and the evening reminder still fires
    /// on a night already trained, which is the one failure the whole rule exists to
    /// prevent.
    private(set) var unattributedHangsToday: Int = 0

    /// The climb logged today, if any — `nil` on an ordinary day. Its mere presence
    /// completes the day; see `isDoneForToday`.
    private(set) var climbToday: SessionKind?
    /// Whether a benchmark was logged today — set when the day's first MEASURED max
    /// lands (see `recordMax`). Settles the day exactly like a climb; kept as its own
    /// flag rather than folded into `climbToday` because the card copy and the grid
    /// notch must not describe a max-testing morning as a trip to the gym.
    private(set) var benchmarkedToday = false
    /// When the newest gauge-MEASURED max was recorded, across every grip. The Maxes
    /// tab's staleness line and its icon's soft-nudge pulse both read this; manual
    /// edits deliberately don't move it, because typing a number is not a test.
    private(set) var lastMeasuredMaxAt: Date?
    /// Exactly 14 entries, oldest first — the consistency strip's whole input.
    private(set) var consistency: [DayRecord] = []
    /// At most 6, newest first, deduped by canonical key.
    private(set) var recentGrips: [GripSpec] = []

    /// What each routine is called RIGHT NOW, by id.
    ///
    /// History freezes a routine's name into every log at save time, which it must — a
    /// deleted routine still has to have something to be called. But while the routine
    /// still exists, the frozen copy is just a stale label: renaming "Daily no-hangs" to
    /// "Morning ladder" left every past session filed under a name the app no longer used
    /// anywhere else (Nuri, 2026-08-11). Displays resolve through this and fall back to
    /// the frozen name, so the rename shows up everywhere at once and a deleted routine
    /// keeps its history intact.
    ///
    /// Folded on the pass that already has the routines in hand, so it costs nothing.
    private(set) var routineNames: [UUID: String] = [:]
    /// Newest `MaxRecord` per canonical grip key. `MaxRecord` is append-only, so
    /// "current" is a fold, never a mutable row.
    private(set) var currentMaxes: [String: MaxRecord] = [:]

    /// The same maxes as the engine consumes them: by grip AND hand, in kilograms.
    ///
    /// STORED and rebuilt on the same pass as `currentMaxes`, never computed per access.
    /// The routine deck reads it inside a card body that re-evaluates on every frame of
    /// a slider drag, and rebuilding a dictionary there would put an allocation on the
    /// hot path for a value that only changes when a max is recorded.
    private(set) var maxTable = MaxTable()
    /// The first day the app had anything to track. Days before it are hairlines in
    /// the strip, not missed sessions.
    private(set) var trackingSince: DayStamp?

    /// The last deleted routine, held briefly so the swipe can be taken back. Delete
    /// carries no confirmation dialog — cheap undo is the forgiveness, and a routine
    /// is six sets of authored intent, so losing one silently is expensive.
    private(set) var lastDeleted: DeletedRoutine?

    /// The same offer for a deleted session, on its own slot rather than sharing the
    /// routine's. They live on different tabs and each screen shows its own bar, so one
    /// shared slot would let a delete on History silently retract the Undo still on
    /// offer on Today — and a session is unrepeatable in a way a routine is not.
    private(set) var lastDeletedSession: DeletedSession?

    /// Set when a save fails (disk full, store-level errors). The failed change has
    /// already been rolled back by the time a view reads this.
    var saveError: String?

    /// Everything needed to put a deleted routine back EXACTLY as it was, including
    /// the raw blobs — see `undoDelete()`.
    struct DeletedRoutine: Identifiable, Sendable, Equatable {
        let id: UUID
        let name: String
        let handModeRaw: String
        let holdSeconds: Int
        let restSeconds: Int
        let setBreakSeconds: Int
        let leadInSeconds: Int
        let thresholdKg: Double
        let waitForReleaseBeforeRest: Bool
        let pausesOutsideTargetBand: Bool
        let targetLoPercent: Double?
        let targetHiPercent: Double?
        let setsData: Data
        let sessionsPerDay: Int
        let remindersData: Data
        let parkedRemindersData: Data
        let remindersEnabled: Bool
        /// Was missing until 2026-08-19 — undoing a deleted WHENEVER routine restored
        /// it as a ritual, complete with a daily target it never had. Every column the
        /// model grows must be added here or the undo silently rewrites it to the
        /// default.
        let isOnDemand: Bool
        let sortIndex: Int
        let createdAt: Date
    }

    /// Everything needed to put a deleted session back EXACTLY as it happened — raw
    /// blobs and all, for the same reason `DeletedRoutine` carries them.
    ///
    /// It carries the DENORMALIZED columns too (`peakKg`, `completedReps`, …), including
    /// the two hand-log answers, rather than recomputing them from `resultsData` on
    /// restore. `WorkoutLog`'s init derives those from the reps it is handed, so
    /// re-deriving would quietly re-score a session under today's arithmetic — and a
    /// session restored with different numbers than it was deleted with is worse than
    /// one that stayed deleted.
    struct DeletedSession: Identifiable, Sendable, Equatable {
        let id: UUID
        let startedAt: Date
        let finishedAt: Date
        let dayKey: Int
        let templateID: UUID?
        let templateName: String
        let planData: Data
        let resultsData: Data
        let sessionsPerDayTarget: Int
        let totalHeldSeconds: Double
        let peakKg: Double
        let avgKg: Double
        let completedReps: Int
        let plannedReps: Int
        let rpe: Int?
        let fingerStrainRaw: Int?
        let durationMinutes: Int?
        let notes: String
        /// Restored like every other raw column — without it, undoing a deleted climb
        /// would put back a HANGBOARD session, and the day it completed would silently
        /// go back to being incomplete.
        let kindRaw: String
    }

    /// The day `syncDerived` last published for. Kept separately from `clock.today`
    /// so a failed fetch leaves it stale and the next call retries rather than
    /// concluding the day is already handled.
    private var syncedDay: DayStamp

    private static let consistencyDays = 14
    private static let recentGripLimit = 6

    /// How long "Undo" stays on offer.
    private static var undoWindow: Duration {
        #if DEBUG
        // Headless UI verification: simctl round-trips are slower than any human.
        if ProcessInfo.processInfo.arguments.contains("-longUndo") { return .seconds(600) }
        #endif
        return .seconds(10)
    }

    private var debouncedSync: Task<Void, Never>?
    private var undoExpiry: Task<Void, Never>?
    private var sessionUndoExpiry: Task<Void, Never>?

    init(context: ModelContext, clock: DayClock, settings: SettingsStore,
         storageMode: StorageMode = .cloud) {
        self.context = context
        self.clock = clock
        self.settings = settings
        self.storageMode = storageMode
        self.syncedDay = clock.today
        observeExternalChanges()
        syncDerived()
    }

    /// CloudKit imports merge on background contexts MID-session (a scenePhase hook
    /// only catches what is already there at foregrounding), and local midnight changes
    /// what "2 of 2 today" means. Imports arrive in bursts, so they are debounced; the
    /// day change is not, because it is one event and the strip is visibly wrong until
    /// it lands.
    private func observeExternalChanges() {
        let center = NotificationCenter.default
        center.addObserver(forName: .NSPersistentStoreRemoteChange, object: nil, queue: nil) { [weak self] _ in
            Task { @MainActor [weak self] in self?.scheduleDebouncedSync() }
        }
        for name in [UIApplication.significantTimeChangeNotification, .NSSystemTimeZoneDidChange] {
            center.addObserver(forName: name, object: nil, queue: .main) { [weak self] _ in
                Task { @MainActor [weak self] in self?.refreshIfDayChanged() }
            }
        }
    }

    private func scheduleDebouncedSync() {
        debouncedSync?.cancel()
        debouncedSync = Task { [weak self] in
            try? await Task.sleep(for: .seconds(2))
            guard !Task.isCancelled else { return }
            self?.syncDerived()
        }
    }

    /// From `.onChange(of: scenePhase)` and whenever the clock ticks: a phone left open
    /// past midnight must flip 2/2 back to 0/2 without a relaunch. `clock.refresh()`
    /// first because a device asleep across midnight may not deliver the time-change
    /// notification until the app is active again.
    /// Recompute if the day moved under us. Deliberately does NOT call `clock.refresh()`:
    /// the store REACTS to the clock, it does not drive it. Pushing the clock from here
    /// re-pins `today` to the system date on every call, which silently defeats
    /// `DayClock.advance(to:)` — the seam that makes crossing midnight testable at all.
    /// The app refreshes the clock on foreground (see DoigtApp) and the clock refreshes
    /// itself on `significantTimeChange`; both paths land here afterwards.
    func refreshIfDayChanged() {
        guard clock.today != syncedDay else { return }
        syncDerived()
    }

    /// Cheap full recompute — a handful of routines and at most 14 days of logs.
    ///
    /// BAILS rather than publishing an empty world it isn't sure about: a nil fetch
    /// means the read FAILED, which is not "no routines". Collapsing those two would
    /// blank the consistency strip and, worse, hand `ReminderPlanner` an empty plan
    /// that deletes every scheduled reminder the user has.
    ///
    /// `refoldingMaxes: false` skips the ONE fetch here that has no ceiling on it — see
    /// `fetchMaxes` — and is the caller stating that no `MaxRecord` moved. It defaults to
    /// true so every external trigger (launch, midnight, a CloudKit import, the
    /// notification-permission callback) still refolds unconditionally; only the internal
    /// write path opts out, and only where it can prove it wrote no max.
    func syncDerived(refoldingMaxes: Bool = true) {
        guard let routines = fetchRoutines() else { return }
        let today = clock.today
        let earliest = today - (Self.consistencyDays - 1)
        guard let logs = fetchLogs(from: earliest) else { return }
        // Skipping the fetch and FAILING it are different things and must not collapse
        // into one: a nil read still bails for the same reason the two above do, while a
        // skip publishes the rest and leaves the three max-derived values standing.
        var maxes: [MaxRecord]?
        if refoldingMaxes {
            guard let fetched = fetchMaxes() else { return }
            maxes = fetched
        }

        syncedDay = today
        completionsToday = Self.completions(in: logs, on: today)
        unattributedHangsToday = Self.unattributedHangs(in: logs, on: today)
        climbToday = Self.climb(in: logs, on: today)
        benchmarkedToday = logs.benchmark(on: today)
        trackingSince = Self.trackingStart(routines: routines, logs: logs)
        consistency = Self.consistency(today: today, logs: logs,
                                       primary: routines.first, since: trackingSince)
        recentGrips = Self.recentGrips(in: routines)
        // `uniquingKeysWith` rather than the exact initializer: two devices CAN produce
        // routines sharing an id over CloudKit, and a duplicate key is a crash there.
        routineNames = Dictionary(routines.map { ($0.id, $0.name) }, uniquingKeysWith: { a, _ in a })
        if let maxes {
            currentMaxes = Self.newestPerGrip(maxes)
            maxTable = Self.table(from: currentMaxes)
            // `maxes` arrives sorted by `recordedAt`, so the last measured one is newest.
            lastMeasuredMaxAt = maxes.last { $0.source == .measured }?.recordedAt
        }

        // Recomputed here, on the same pass that recomputed the completion counts, so
        // finishing a session re-plans the day's remaining reminders in the same breath
        // that Today's "2 of 2" appears. `refreshIfDayChanged` runs this again at
        // midnight, which is what restores tomorrow's full set.
        let inputs = routines.map { template in
            // `completed(_:)`, not the raw map: a hang logged by hand has to silence the
            // evening reminder too, and reading the map directly here is exactly how the
            // card and the reminder drift apart.
            let done = completed(template)
            // A climb — or a benchmark — ZEROES the day's outstanding sessions, which
            // is what stops the evening reminder firing on a night already spent at
            // the gym or a morning spent testing maxes. That is the single most felt
            // consequence of the rule: being told to hangboard after you have just
            // trained is the app failing to notice.
            let outstanding = (climbToday != nil || benchmarkedToday)
                ? 0
                : max(0, max(1, template.sessionsPerDay) - done)
            return ReminderPlanner.RoutinePlanInput(
                id: template.id,
                name: template.name,
                reminders: template.reminders,
                // Whenever routines never remind. Normalization already forces their
                // switch off on save; this is the belt for templates synced from a
                // build that predates the rule.
                enabled: template.remindersEnabled && !template.isOnDemand,
                outstandingToday: outstanding
            )
        }
        Task { await ReminderPlanner.replan(inputs) }
    }

    // MARK: - Fetches

    /// nil = the fetch FAILED, which is not the same as "no routines". The Optional
    /// exists only to keep those two apart; every caller that would publish state on
    /// the strength of the answer bails on nil.
    private func fetchRoutines() -> [SessionTemplate]? {
        guard let fetched = try? context.fetch(FetchDescriptor<SessionTemplate>()) else { return nil }
        return fetched.sorted(by: Self.routineOrder)
    }

    /// The TOTAL order. Two devices that both reorder produce DUPLICATE `sortIndex`
    /// values over CloudKit — there is no uniqueness constraint and there cannot be —
    /// and a partial sort leaves the tie to fetch order, so the same two routines render
    /// in opposite orders on the two phones. `id.uuidString` is the arbitrary-but-
    /// identical tiebreak, applied in memory because `UUID` is not `Comparable` and a
    /// SwiftData `SortDescriptor` cannot express it.
    private static func routineOrder(_ a: SessionTemplate, _ b: SessionTemplate) -> Bool {
        if a.sortIndex != b.sortIndex { return a.sortIndex < b.sortIndex }
        if a.createdAt != b.createdAt { return a.createdAt < b.createdAt }
        return a.id.uuidString < b.id.uuidString
    }

    /// `dayKey` is an Int column precisely so this is a cheap predicate rather than a
    /// Calendar pass over every log ever written.
    private func fetchLogs(from earliest: DayStamp) -> [WorkoutLog]? {
        let floor = earliest.raw
        let descriptor = FetchDescriptor<WorkoutLog>(
            predicate: #Predicate<WorkoutLog> { $0.dayKey >= floor },
            sortBy: [SortDescriptor(\.startedAt)]
        )
        return try? context.fetch(descriptor)
    }

    /// The one UNBOUNDED fetch in the store, and deliberately so: `newestPerGrip` has to
    /// see every grip ever tested, so a `fetchLimit` would silently drop the max for a
    /// grip you last measured a year ago and every percent target on it with it. The
    /// other two fetches have natural ceilings — a handful of routines, 14 days of logs —
    /// but max records accumulate for the life of the app and are never pruned.
    ///
    /// Which is why it is gated rather than capped: `syncDerived(refoldingMaxes:)` runs
    /// it only when a `MaxRecord` actually moved. It used to run on EVERY write, so
    /// finishing an ordinary session refetched and refolded the entire measurement
    /// history to recompute a table that could not have changed.
    private func fetchMaxes() -> [MaxRecord]? {
        try? context.fetch(FetchDescriptor<MaxRecord>(sortBy: [SortDescriptor(\.recordedAt)]))
    }

    // MARK: - Derived computations (pure over what was fetched)

    /// HANG sessions only, per routine. A climb has no routine to attribute to and does
    /// not fill a slot — it settles the whole day, which is a separate question asked by
    /// `climbToday`.
    private static func completions(in logs: [WorkoutLog], on day: DayStamp) -> [UUID: Int] {
        var counts: [UUID: Int] = [:]
        for log in logs where log.dayKey == day.raw && !log.kind.isClimb {
            // A log whose routine was deleted still counts as a session trained, but it
            // has no routine to attribute to — grouping is best-effort by design.
            guard let id = log.templateID else { continue }
            counts[id, default: 0] += 1
        }
        return counts
    }

    /// `.hangManual` ONLY, deliberately — not every log with a nil `templateID`. A
    /// runner session whose routine was later deleted also has no id, and it is dropped
    /// on purpose (see `completions`); crediting those here would retroactively change
    /// how old days score. A hand-logged hang never had a routine to begin with.
    private static func unattributedHangs(in logs: [WorkoutLog], on day: DayStamp) -> Int {
        logs.filter { $0.dayKey == day.raw && $0.kind == .hangManual }.count
    }

    /// The climb logged today, if any. The rule lives on `Collection where Element ==
    /// WorkoutLog` so History's independent fold answers identically.
    private static func climb(in logs: [WorkoutLog], on day: DayStamp) -> SessionKind? {
        logs.climb(on: day)
    }

    /// The earliest day the app could have expected anything of you: the oldest routine
    /// (or, if the routine that produced them has since been deleted, the oldest log).
    private static func trackingStart(routines: [SessionTemplate], logs: [WorkoutLog]) -> DayStamp? {
        let routineDays = routines.map { DayStamp(date: $0.createdAt) }
        let logDays = logs.map { DayStamp(raw: $0.dayKey) }
        return (routineDays + logDays).min()
    }

    private static func consistency(today: DayStamp, logs: [WorkoutLog],
                                    primary: SessionTemplate?, since: DayStamp?) -> [DayRecord] {
        var byDay: [Int: [WorkoutLog]] = [:]
        for log in logs { byDay[log.dayKey, default: []].append(log) }
        // Today's target comes from the routine Today opens on; a past day's comes from
        // what the logs themselves froze, so raising sessions-per-day tomorrow never
        // retroactively turns last week into a wall of half-full days.
        let currentTarget = primary?.sessionsPerDay ?? 0

        return (0..<consistencyDays).map { offset in
            let day = today - (consistencyDays - 1 - offset)
            let dayLogs = byDay[day.raw] ?? []
            let target = dayLogs.map(\.sessionsPerDayTarget).max() ?? currentTarget
            return DayRecord(day: day,
                             // Hang sessions, including hangs logged by hand, so the
                             // "1 of 2" a cell draws still means hang rounds; a climb is
                             // carried separately and fills the cell on its own. A
                             // benchmark log is neither — it fills via its own flag and
                             // counts toward nothing.
                             completed: dayLogs.filter { $0.kind.countsAsHang }.count,
                             target: target,
                             tracked: since.map { day >= $0 } ?? false,
                             climb: Self.climb(in: dayLogs, on: day),
                             benchmarked: dayLogs.benchmark(on: day))
        }
    }

    /// Newest-EDITED routine first. In M2 the only grips the app has ever seen are the
    /// ones the user authored, so "recent" means "recently in a routine"; M3 can prepend
    /// logged grips without changing the shape or the callers.
    private static func recentGrips(in routines: [SessionTemplate]) -> [GripSpec] {
        var grips: [GripSpec] = []
        var seen: Set<String> = []
        outer: for template in routines.sorted(by: { $0.updatedAt > $1.updatedAt }) {
            for set in template.sets {
                guard seen.insert(set.grip.key).inserted else { continue }
                grips.append(set.grip)
                if grips.count >= recentGripLimit { break outer }
            }
        }
        // With no history at all the RECENT rail would be empty on the one screen where
        // it helps most — a blank routine, where every set has to be built by hand. The
        // seed palette is the common no-hang vocabulary, so the rail is a shortcut from
        // the first tap rather than a feature that only appears once you no longer need it.
        return grips.isEmpty ? seedGrips : grips
    }

    /// Ordered widest-and-fullest first: the grips a beginner should reach for before
    /// the two-finger and full-crimp ones further down.
    private static let seedGrips: [GripSpec] = [
        GripSpec(edgeMM: 20, fingers: .four, position: .halfCrimp),
        GripSpec(edgeMM: 20, fingers: .frontThree, position: .halfCrimp),
        GripSpec(edgeMM: 20, fingers: .four, position: .openHand),
        GripSpec(edgeMM: 20, fingers: .frontTwo, position: .openHand),
        GripSpec(edgeMM: 20, fingers: .middleTwo, position: .openHand),
        GripSpec(edgeMM: 20, fingers: .four, position: .fullCrimp),
    ]

    /// Newest per GRIP **AND HAND** — see `MaxRecord.maxKey`. Keyed on the grip alone,
    /// recording a right-hand max would supersede the left-hand one you took a minute
    /// earlier, and one of your two hands would silently lose its number.
    private static func newestPerGrip(_ records: [MaxRecord]) -> [String: MaxRecord] {
        var newest: [String: MaxRecord] = [:]
        for record in records {
            let key = record.maxKey
            if let held = newest[key], held.recordedAt >= record.recordedAt { continue }
            newest[key] = record
        }
        return newest
    }

    /// Derived from `currentMaxes` in the same breath, so the two can never disagree
    /// about what your max is.
    private static func table(from newest: [String: MaxRecord]) -> MaxTable {
        var table = MaxTable()
        for record in newest.values {
            table.record(record.kg, grip: record.gripKey, side: record.side)
        }
        return table
    }

    // MARK: - Reads

    func routine(id: UUID) -> SessionTemplate? {
        fetchRoutines()?.first { $0.id == id }
    }

    func plan(for template: SessionTemplate) -> SessionPlan { template.plan }

    /// `nil` means "new", and a new routine is BLANK — never `.starter`. No screen
    /// offers a prefill any more (the START FROM chips left 2026-08-19), so seeding one
    /// here would silently put the full daily protocol under someone adding a rest day.
    func draft(editing template: SessionTemplate?) -> RoutineDraft {
        template.map(\.draft) ?? .blank()
    }

    func summary(for template: SessionTemplate) -> RoutineSummary {
        let plan = template.plan
        let ladder = plan.executable.sets.enumerated().map { index, set in
            LadderRung(id: index, grip: set.grip, repsPerSide: set.repsPerSide)
        }
        return RoutineSummary(
            id: template.id,
            name: template.name,
            ladder: ladder,
            setCount: PlanMath.setCount(plan),
            totalReps: PlanMath.totalReps(plan),
            sharedEdgeMM: PlanMath.sharedEdgeMM(plan),
            estimatedSeconds: PlanMath.totalSeconds(plan),
            sessionsPerDay: template.sessionsPerDay,
            completedToday: completed(template),
            nextReminder: nextReminder(for: template),
            climbedToday: climbToday,
            benchmarkedToday: benchmarkedToday,
            isOnDemand: template.isOnDemand,
            // Against the live max table, so recording a max recolours the rung on the
            // next summary rebuild — intensity is a fact about TODAY's prescription,
            // unlike the runner's freeze-at-start rule for what a session displays.
            peakIntensity: PlanMath.peakIntensity(of: plan, maxes: maxTable)
        )
    }

    /// Wraps to tomorrow's first slot rather than returning nil once the day's last
    /// reminder has passed: at 22:00 the honest answer is still "next at 08:00", and a
    /// row that empties itself in the evening reads as broken. Recomputed on every call
    /// because it depends on the wall clock, which is not observable.
    private func nextReminder(for template: SessionTemplate) -> ReminderTime? {
        guard template.remindersEnabled else { return nil }
        let slots = template.reminders.sorted()
        guard let first = slots.first else { return nil }
        let now = Calendar.current.dateComponents([.hour, .minute], from: .now)
        let minutes = (now.hour ?? 0) * 60 + (now.minute ?? 0)
        return slots.first { $0.minutesFromMidnight >= minutes } ?? first
    }

    func completed(_ template: SessionTemplate) -> Int {
        (completionsToday[template.id] ?? 0) + unattributedHangsToday
    }

    /// **A climb settles the day.** Bouldering at your limit is more finger load than
    /// the routine it displaced, so scoring that day as a miss was the app lying about
    /// the week (Nuri, 2026-08-05). Nothing is asked for afterwards — but the routine
    /// stays startable, because after an easy volume evening an extra hang round is
    /// perfectly reasonable. Offered, never demanded.
    func isDoneForToday(_ template: SessionTemplate) -> Bool {
        if template.isOnDemand {
            // Never OWED — but "done" still means something: once you've run it today
            // the card earns its checkmark and Start demotes to "Start another".
            return climbToday != nil || benchmarkedToday || completed(template) > 0
        }
        return climbToday != nil || benchmarkedToday
            || completed(template) >= max(1, template.sessionsPerDay)
    }

    /// A whole sentence, not the "1 of 2" fragment beside it: this is what VoiceOver
    /// reads, and "one of two" with no noun is the classic dashboard-accessibility
    /// failure.
    func completionText(_ template: SessionTemplate) -> String {
        let done = completed(template)
        let target = max(1, template.sessionsPerDay)
        // The climb LEADS the sentence, because on a day you climbed it is the training
        // that happened — and any hang rounds are the extra, said second.
        if let climb = climbToday {
            let what = climb == .climbLimit ? String(localized: "Limit session") : String(localized: "Volume session")
            switch done {
            case 0:  return String(localized: "\(what) at the gym today")
            case 1:  return String(localized: "\(what) at the gym today, plus a hang session")
            default: return String(localized: "\(what) at the gym today, plus \(done) hang sessions")
            }
        }
        // The climb wins the sentence when both happened — the gym is the day's story
        // and the benchmark still shows in History.
        if benchmarkedToday {
            switch done {
            case 0:  return String(localized: "Maxes tested today")
            case 1:  return String(localized: "Maxes tested today, plus a hang session")
            default: return String(localized: "Maxes tested today, plus \(done) hang sessions")
            }
        }
        if template.isOnDemand {
            switch done {
            case 0:  return String(localized: "A whenever routine — nothing owed today")
            case 1:  return String(localized: "Done today")
            default: return String(localized: "Done \(done) times today")
            }
        }
        if done == 0 {
            return target == 1 ? String(localized: "No session done today")
                               : String(localized: "No sessions done today, \(target) planned")
        }
        if done >= target {
            if done == 1 { return String(localized: "Session done today") }
            // "Both" rather than "2" for the twice-a-day case, which is the app's whole
            // reason for existing — it reads as a finished ritual rather than a tally.
            if done == 2 && target == 2 { return String(localized: "Both sessions done today") }
            return String(localized: "\(done) sessions done today")
        }
        return String(localized: "\(done) of \(target) sessions done today")
    }

    /// The max for a grip on a given hand, with the both-hands fallback — see
    /// `MaxTable`. Defaulted to `.both` so every existing caller keeps its meaning.
    func currentMax(for grip: GripSpec, side: Side = .both) -> Double? {
        maxTable.max(grip: grip.key, side: side)
    }

    /// The Maxes tab's SOFT NUDGE: true once the newest measured max is four weeks
    /// stale. Nobody who has never measured gets nudged — there is nothing to re-test,
    /// and the tab's own empty state does the inviting. Four weeks because finger
    /// strength moves on a monthly timescale; there is deliberately no setting for it
    /// (a cadence you configure is a schedule, and the schedule was voted down for a
    /// pulse).
    var benchmarkNudge: Bool {
        guard let last = lastMeasuredMaxAt else { return false }
        return Date.now.timeIntervalSince(last) >= 28 * 86_400
    }

    func suggestedBand(for grip: GripSpec) -> ClosedRange<Double>? {
        PlanMath.suggestedBand(maxKg: currentMax(for: grip) ?? 0)
    }

    /// Rung 2 of Today's selection rule: the routine started today on THIS device.
    /// DEVICE-LOCAL and day-scoped — a synced "primary" flag is the classic
    /// second-device bug, and yesterday's choice is not evidence about today. M3 swaps
    /// the backing store for today's newest log without changing this contract.
    var suggestedRoutineID: UUID? {
        guard settings.lastStartedDayRaw == clock.today.raw else { return nil }
        return settings.lastStartedRoutineID
    }

    // MARK: - Mutations

    /// The ONE entry point the builder calls, so the builder never has to know whether
    /// it is creating or editing.
    @discardableResult
    func save(_ draft: RoutineDraft) -> SessionTemplate? {
        let normalized = draft.normalized
        let saved: SessionTemplate?
        if let id = normalized.templateID, let existing = routine(id: id) {
            saved = update(existing, with: normalized) ? existing : nil
        } else {
            // templateID nil, or deleted on another device while this editor was open.
            // Re-creating is the only non-destructive answer to the latter: the
            // alternative silently discards work the user is in the middle of saving.
            saved = create(normalized)
        }
        // Only on success — a rollback leaves the sheet open with the error inline, and
        // the rescue copy has to survive for the retry.
        if saved != nil { clearDraft() }
        return saved
    }

    @discardableResult
    func create(_ draft: RoutineDraft) -> SessionTemplate? {
        guard let siblings = fetchRoutines() else {
            // A failed READ is not "no routines": minting sortIndex 0 here would make
            // this the primary routine and push whatever exists behind it.
            saveError = String(localized: "Couldn't read your routines just now — the new one wasn't saved.")
            return nil
        }
        let normalized = draft.normalized
        let template = SessionTemplate(draft: normalized,
                                       sortIndex: (siblings.map(\.sortIndex).max() ?? -1) + 1)
        context.insert(template)
        persistAndSync(maxesChanged: false)
        guard saveError == nil else { return nil }
        askNotificationPermissionOnce(for: normalized)
        return template
    }

    @discardableResult
    func update(_ template: SessionTemplate, with draft: RoutineDraft) -> Bool {
        // A routine deleted by a CloudKit merge is a faulted object; mutating it throws
        // an ObjC exception that no Swift `catch` can reach.
        guard template.modelContext != nil else { return false }
        let normalized = draft.normalized
        template.apply(normalized)
        persistAndSync(maxesChanged: false)
        guard saveError == nil else { return false }
        askNotificationPermissionOnce(for: normalized)
        return true
    }

    @discardableResult
    func duplicate(_ template: SessionTemplate) -> SessionTemplate? {
        guard template.modelContext != nil else { return nil }
        var draft = RoutineDraft.copying(template.draft)
        draft.plan.name = uniqueName(draft.plan.name)
        return create(draft)
    }

    // MARK: - The share-link inbox

    /// A scanned routine (or the reason a scan failed), HELD rather than presented.
    ///
    /// A `getagrip://` link arrives from outside the app entirely — the system Camera, a
    /// message — and can land while a full-screen cover owns the screen. Presenting
    /// from the root at that moment was measured tearing the cover down (2026-08-19,
    /// simulator): a running SESSION died unlogged, around every safeguard the runner
    /// has, and the builder lost its unsaved edits with the import sheet never even
    /// appearing. So the URL is decoded here into a value, and `TodayView` — the one
    /// view that owns every conflicting presentation — drains the inbox when nothing
    /// else is on screen. One slot, latest scan wins: two codes scanned back to back
    /// are one decision, about the second one.
    private(set) var pendingImport: RoutineDraft?
    private(set) var pendingImportError: String?

    func receiveShareLink(_ url: URL) {
        do {
            pendingImport = try RoutineShare.draft(from: url)
            pendingImportError = nil
        } catch {
            pendingImport = nil
            pendingImportError = (error as? RoutineShareError)?.errorDescription
                ?? String(localized: "This routine code couldn't be read.")
        }
    }

    /// Consume-on-read, so a drain can never present the same scan twice.
    func claimPendingImport() -> RoutineDraft? {
        defer { pendingImport = nil }
        return pendingImport
    }

    func claimPendingImportError() -> String? {
        defer { pendingImportError = nil }
        return pendingImportError
    }

    /// The name an import WILL land under, so the preview can promise it rather than a
    /// name the store is about to change — two people keeping the shipped default and
    /// exchanging codes is the common case, not the edge one.
    func plannedImportName(for wanted: String) -> String {
        uniqueName(wanted)
    }

    /// A routine that arrived from somebody else's QR code — `duplicate`'s twin, and
    /// deliberately the same two moves: deconflict the name, then `create`. There is no
    /// second save path, so an import lands at the END of the sort order like every
    /// other new routine and can never displace the one Today opens on.
    ///
    /// Two facts are re-asserted here rather than trusted from the wire, because this is
    /// the last gate before disk and the payload is untrusted input:
    ///
    /// - **`templateID` is nilled.** `SessionTemplate.init(draft:)` ADOPTS a draft's id,
    ///   so a code carrying its author's UUID would mint a routine wearing somebody
    ///   else's identity — and that id is what `doigt.routine.<uuid>.<slot>` reminder
    ///   identifiers are built from. Same reason `RoutineDraft.copying` nils it.
    /// - **Reminders are forced OFF.** They are personal times the payload deliberately
    ///   omits, and `create` asks for notification permission whenever a draft arrives
    ///   with them on — an OS prompt raised by scanning a stranger's code is an ambush,
    ///   not a request.
    @discardableResult
    func importRoutine(_ draft: RoutineDraft) -> SessionTemplate? {
        // Normalized FIRST so the name `uniqueName` deconflicts is the name that will
        // actually be written — an empty one becomes the house default on the way in,
        // and deconflicting the empty string would let two "Daily no-hangs" through.
        var incoming = draft.normalized
        incoming.templateID = nil
        incoming.remindersEnabled = false
        incoming.plan.name = uniqueName(incoming.plan.name)
        return create(incoming)
    }

    /// Duplicating twice must not produce two routines called "Copy of Daily no-hangs":
    /// the chooser rail shows names only, so identical ones make the second routine
    /// unpickable by sight. Suffixes count up from 2 — "Copy of X", "Copy of X 2".
    private func uniqueName(_ wanted: String) -> String {
        let taken = Set((fetchRoutines() ?? []).map(\.name))
        guard taken.contains(wanted) else { return wanted }
        var suffix = 2
        while taken.contains("\(wanted) \(suffix)") { suffix += 1 }
        return "\(wanted) \(suffix)"
    }

    @discardableResult
    func delete(_ template: SessionTemplate) -> Bool {
        guard template.modelContext != nil else { return false }
        // Captured as RAW columns, blobs included — see `undoDelete()`.
        let restorable = DeletedRoutine(
            id: template.id,
            name: template.name,
            handModeRaw: template.handModeRaw,
            holdSeconds: template.holdSeconds,
            restSeconds: template.restSeconds,
            setBreakSeconds: template.setBreakSeconds,
            leadInSeconds: template.leadInSeconds,
            thresholdKg: template.thresholdKg,
            waitForReleaseBeforeRest: template.waitForReleaseBeforeRest,
            pausesOutsideTargetBand: template.pausesOutsideTargetBand,
            targetLoPercent: template.targetLoPercent,
            targetHiPercent: template.targetHiPercent,
            setsData: template.setsData,
            sessionsPerDay: template.sessionsPerDay,
            remindersData: template.remindersData,
            parkedRemindersData: template.parkedRemindersData,
            remindersEnabled: template.remindersEnabled,
            isOnDemand: template.isOnDemand,
            sortIndex: template.sortIndex,
            createdAt: template.createdAt
        )
        context.delete(template)
        persistAndSync(maxesChanged: false)
        // Only offer undo for a delete that actually landed — `persistAndSync` rolls
        // back on failure, so the routine is still there and "Undo" would duplicate it.
        guard saveError == nil else { return false }
        lastDeleted = restorable
        armUndoExpiry()
        return true
    }

    private func armUndoExpiry() {
        undoExpiry?.cancel()
        undoExpiry = Task { [weak self] in
            try? await Task.sleep(for: Self.undoWindow)
            guard !Task.isCancelled else { return }
            self?.lastDeleted = nil
        }
    }

    /// Re-insert with the ORIGINAL UUID, sortIndex and raw blob `Data`.
    ///
    /// The id matters because `doigt.routine.<uuid>.r0480` identifiers are content-keyed
    /// on it — a new id would leave the old reminders orphaned and firing. The raw blobs
    /// matter because decoding and re-encoding a routine written by a NEWER build drops
    /// every field this one does not understand, and losing a field to the gesture whose
    /// entire job is putting things back is the worst possible place for it.
    func undoDelete() {
        guard let restorable = lastDeleted else { return }
        undoExpiry?.cancel()

        // Built from a default draft and then overwritten column by column. Going
        // through `apply(_:)` would re-encode the blobs, which is exactly what this
        // must not do.
        let template = SessionTemplate(draft: RoutineDraft(), sortIndex: restorable.sortIndex)
        template.id = restorable.id
        template.name = restorable.name
        template.handModeRaw = restorable.handModeRaw
        template.holdSeconds = restorable.holdSeconds
        template.restSeconds = restorable.restSeconds
        template.setBreakSeconds = restorable.setBreakSeconds
        template.leadInSeconds = restorable.leadInSeconds
        template.thresholdKg = restorable.thresholdKg
        template.waitForReleaseBeforeRest = restorable.waitForReleaseBeforeRest
        template.pausesOutsideTargetBand = restorable.pausesOutsideTargetBand
        template.targetLoPercent = restorable.targetLoPercent
        template.targetHiPercent = restorable.targetHiPercent
        template.setsData = restorable.setsData
        template.sessionsPerDay = restorable.sessionsPerDay
        template.remindersData = restorable.remindersData
        template.parkedRemindersData = restorable.parkedRemindersData
        template.remindersEnabled = restorable.remindersEnabled
        template.isOnDemand = restorable.isOnDemand
        template.sortIndex = restorable.sortIndex
        template.createdAt = restorable.createdAt
        // `updatedAt` is deliberately NOT restored: the restore is itself the most
        // recent thing that happened to this routine, and `recentGrips` reads that
        // order.
        template.updatedAt = .now
        context.insert(template)
        persistAndSync(maxesChanged: false)

        // Only consume the undo once the restore has landed. Clearing it first would
        // mean a rolled-back save loses the routine for good — the one outcome the
        // undo bar exists to prevent.
        if saveError == nil {
            lastDeleted = nil
        } else {
            armUndoExpiry()
        }
    }

    func dismissUndo() {
        undoExpiry?.cancel()
        undoExpiry = nil
        lastDeleted = nil
    }

    func move(fromOffsets offsets: IndexSet, toOffset destination: Int) {
        guard var routines = fetchRoutines() else { return }
        routines.move(fromOffsets: offsets, toOffset: destination)
        renumber(routines)
        persistAndSync(maxesChanged: false)
    }

    /// Position 0 IS the primary routine — the one Today opens on. There is no separate
    /// `isPrimary` column, because a flag that can disagree with the order is a second
    /// source of truth, and over CloudKit it is one that two devices can both set.
    func makePrimary(_ template: SessionTemplate) {
        guard var routines = fetchRoutines(),
              let index = routines.firstIndex(where: { $0.id == template.id }) else { return }
        routines.insert(routines.remove(at: index), at: 0)
        renumber(routines)
        persistAndSync(maxesChanged: false)
    }

    /// Renormalizes to a dense 0..<n so a CloudKit merge that produced two routines
    /// with the same `sortIndex` is repaired by the next reorder rather than persisting
    /// as a tie broken differently on each device.
    private func renumber(_ routines: [SessionTemplate]) {
        for (index, template) in routines.enumerated() where template.sortIndex != index {
            // `updatedAt` untouched: reordering is not an edit to the routine's content,
            // and bumping it would scramble the "recently authored" grip order.
            template.sortIndex = index
        }
    }

    /// Rung 2 of Today's selection rule is written here, on START rather than on
    /// finish, because the useful question at 19:00 is "which one am I in the middle
    /// of", not "which one did I complete".
    func noteSessionStarted(_ template: SessionTemplate) {
        settings.lastStartedRoutineID = template.id
        settings.lastStartedDayRaw = clock.today.raw
    }

    /// Log a session after the fact — at the climbing gym or a hang session done away
    /// from the gauge. There is nothing for the app to time or measure in either case.
    ///
    /// `daysAgo` exists because the realistic moment to log one is the next morning.
    /// Clamped rather than validated: a negative would file training in the future.
    @discardableResult
    func recordLoggedSession(_ kind: SessionKind, daysAgo: Int = 0, minutes: Int? = nil,
                             rpe: RPE? = nil, fingerStrain: FingerStrain? = nil,
                             notes: String = "") -> WorkoutLog? {
        guard kind.isLoggedByHand else { return nil }
        let day = clock.today - max(0, daysAgo)
        let log = WorkoutLog(logged: kind,
                             day: day,
                             at: Date.now,
                             // Frozen like any other log, so changing sessions-a-day
                             // later cannot re-score a day already lived.
                             sessionsPerDayTarget: fetchRoutines()?.first?.sessionsPerDay ?? 1,
                             minutes: minutes,
                             rpe: rpe,
                             fingerStrain: fingerStrain,
                             notes: notes)
        context.insert(log)
        persistAndSync(maxesChanged: false)
        return saveError == nil ? log : nil
    }

    /// Write a finished session. Goes through the hub like every other mutation, so
    /// the completion count on Today and the consistency strip update in the same
    /// breath — a session that vanished until relaunch would read as lost work.
    ///
    /// `day` comes from the app's own `DayClock`, not `Date.now`, so a session finished
    /// at 00:30 lands on the day the climber actually lived through.
    @discardableResult
    func recordSession(plan: SessionPlan,
                       template: SessionTemplate?,
                       reps: [RepSummary],
                       startedAt: Date,
                       finishedAt: Date,
                       rpe: RPE?, newMaxes: [MaxRecord] = []) -> WorkoutLog? {
        guard newMaxes.allSatisfy({ $0.kg.isFinite && $0.kg > 0 }) else {
            saveError = String(localized: "Couldn't save this workout. Please try again.")
            return nil
        }
        let log = WorkoutLog(
            plan: plan,
            templateID: template?.id,
            // FROZEN at save: renaming a routine later must not retro-rename history.
            templateName: template?.name ?? plan.name,
            sessionsPerDayTarget: template?.sessionsPerDay ?? 1,
            reps: reps,
            startedAt: startedAt,
            finishedAt: finishedAt,
            day: clock.today
        )
        log.rpe = rpe?.rawValue
        context.insert(log)
        for max in newMaxes { context.insert(max) }
        persistAndSync(maxesChanged: !newMaxes.isEmpty)
        guard saveError == nil else { return nil }
        return log
    }

    /// Remove a session from history — the one destructive act on this data.
    ///
    /// It is not merely a row disappearing: `dayKey` and `sessionsPerDayTarget` are what
    /// "2 of 2 today" and the consistency strip are counted from, so deleting today's
    /// session must walk Today's completion back in the same breath. `persistAndSync`
    /// is what guarantees that, which is why this goes through the hub like every other
    /// write rather than calling `context.delete` from the view.
    @discardableResult
    func deleteSession(_ log: WorkoutLog) -> Bool {
        guard log.modelContext != nil else { return false }
        // Captured as RAW columns, blobs included — see `undoDeleteSession()`.
        let restorable = DeletedSession(
            id: log.id,
            startedAt: log.startedAt,
            finishedAt: log.finishedAt,
            dayKey: log.dayKey,
            templateID: log.templateID,
            templateName: log.templateName,
            planData: log.planData,
            resultsData: log.resultsData,
            sessionsPerDayTarget: log.sessionsPerDayTarget,
            totalHeldSeconds: log.totalHeldSeconds,
            peakKg: log.peakKg,
            avgKg: log.avgKg,
            completedReps: log.completedReps,
            plannedReps: log.plannedReps,
            rpe: log.rpe,
            fingerStrainRaw: log.fingerStrainRaw,
            durationMinutes: log.durationMinutes,
            notes: log.notes,
            kindRaw: log.kindRaw
        )
        context.delete(log)
        persistAndSync(maxesChanged: false)
        // Only offer undo for a delete that actually landed — `persistAndSync` rolls
        // back on failure, so the session is still there and "Undo" would duplicate it.
        guard saveError == nil else { return false }
        lastDeletedSession = restorable
        armSessionUndoExpiry()
        return true
    }

    /// Re-insert with the ORIGINAL UUID, dates and raw blob `Data`.
    ///
    /// Built from a placeholder and overwritten column by column, exactly as
    /// `undoDelete()` is and for the same two reasons: `WorkoutLog`'s init RE-DERIVES
    /// every denormalized number from the reps it is given, and encoding a plan this
    /// build cannot fully decode would drop whatever a newer one wrote. A session is a
    /// record of something that happened — putting it back must not recompute it.
    func undoDeleteSession() {
        guard let restorable = lastDeletedSession else { return }
        sessionUndoExpiry?.cancel()

        let log = WorkoutLog(plan: SessionPlan(), templateID: nil, templateName: "",
                             sessionsPerDayTarget: 1, reps: [],
                             startedAt: restorable.startedAt, finishedAt: restorable.finishedAt,
                             day: DayStamp(raw: restorable.dayKey))
        log.id = restorable.id
        log.startedAt = restorable.startedAt
        log.finishedAt = restorable.finishedAt
        log.dayKey = restorable.dayKey
        log.templateID = restorable.templateID
        log.templateName = restorable.templateName
        log.planData = restorable.planData
        log.resultsData = restorable.resultsData
        log.sessionsPerDayTarget = restorable.sessionsPerDayTarget
        log.totalHeldSeconds = restorable.totalHeldSeconds
        log.peakKg = restorable.peakKg
        log.avgKg = restorable.avgKg
        log.completedReps = restorable.completedReps
        log.plannedReps = restorable.plannedReps
        log.rpe = restorable.rpe
        log.fingerStrainRaw = restorable.fingerStrainRaw
        log.durationMinutes = restorable.durationMinutes
        log.notes = restorable.notes
        log.kindRaw = restorable.kindRaw
        context.insert(log)
        persistAndSync(maxesChanged: false)

        // Only consume the undo once the restore has landed — see `undoDelete()`.
        if saveError == nil {
            lastDeletedSession = nil
        } else {
            armSessionUndoExpiry()
        }
    }

    func dismissSessionUndo() {
        sessionUndoExpiry?.cancel()
        sessionUndoExpiry = nil
        lastDeletedSession = nil
    }

    private func armSessionUndoExpiry() {
        sessionUndoExpiry?.cancel()
        sessionUndoExpiry = Task { [weak self] in
            try? await Task.sleep(for: Self.undoWindow)
            guard !Task.isCancelled else { return }
            self?.lastDeletedSession = nil
        }
    }

    @discardableResult
    func recordMax(_ kg: Double, for grip: GripSpec, source: MaxSource = .manual,
                   side: Side = .both, marksBenchmarkDay: Bool = true) -> Bool {
        // A zero or NaN max would make every percentage-of-max caption in the app lie,
        // and `PlanMath.percentOfMax` would have to defend against it forever.
        guard kg.isFinite, kg > 0 else { return false }
        context.insert(MaxRecord(grip: grip, kg: kg, source: source, side: side))
        // **A MEASURED max makes today a benchmark day** — the lightweight version of a
        // test session (Nuri, 2026-08-10): no ceremony, but the day still reads as
        // trained, the grid fills, and no reminder nags after maximal pulls. One log
        // per day however many grips get tested; typed numbers never create one,
        // because typing is not training. `marksBenchmarkDay: false` is the session-PR
        // path: a max hit INSIDE a routine already logged its session, and settling
        // the day on top would silently cancel the evening ritual.
        if marksBenchmarkDay, source == .measured, benchmarkedToday == false,
           !(fetchLogs(from: clock.today)?.benchmark(on: clock.today) ?? false) {
            let target = fetchRoutines()?.first?.sessionsPerDay ?? 1
            context.insert(WorkoutLog(logged: .benchmark, day: clock.today, at: .now,
                                      sessionsPerDayTarget: target))
        }
        persistAndSync()
        return saveError == nil
    }

    @discardableResult
    func deleteMax(_ record: MaxRecord) -> Bool {
        guard record.modelContext != nil else { return false }
        context.delete(record)
        persistAndSync()
        return saveError == nil
    }

    // MARK: - What a new max moves

    /// Everything a new max on one grip changes across the routines, computed against
    /// the max it REPLACES — so it must be asked BEFORE `recordMax` (afterwards the old
    /// number is just history). Shown once, right after saving; see `MaxEntrySheet`.
    struct MaxImpact: Hashable, Sendable {
        /// A percentage band that now resolves to different kilograms. INFORMATIONAL:
        /// percent targets follow the newest max by design — this is the visibility,
        /// not a consent form.
        struct PercentMove: Hashable, Sendable, Identifiable {
            let routineName: String
            let loPercent: Double
            let hiPercent: Double
            /// nil when the grip had no max before — the band never resolved until now.
            let oldBand: ClosedRange<Double>?
            let newBand: ClosedRange<Double>
            var id: String { routineName + "·\(loPercent)–\(hiPercent)" }
        }

        /// Explicit-kilogram sets on this grip, offered a proportional rescale. An
        /// OFFER, never automatic: a number a person typed is never moved by
        /// arithmetic without a yes — the same precedence rule `PlanMath` states.
        struct KgOffer: Hashable, Sendable, Identifiable {
            struct Move: Hashable, Sendable {
                let oldBand: ClosedRange<Double>
                let newBand: ClosedRange<Double>
            }
            let routineID: UUID
            let routineName: String
            let moves: [Move]
            var id: UUID { routineID }
        }

        var percentMoves: [PercentMove]
        var kgOffers: [KgOffer]
        /// new ÷ old — what "scale with your new max" multiplies by. nil when there
        /// was no old max, which is also why `kgOffers` is empty then.
        var ratio: Double?
        var isEmpty: Bool { percentMoves.isEmpty && kgOffers.isEmpty }
    }

    func maxImpact(grip: GripSpec, oldKg: Double?, newKg: Double) -> MaxImpact {
        guard let routines = fetchRoutines() else {
            return MaxImpact(percentMoves: [], kgOffers: [], ratio: nil)
        }
        let ratio = oldKg.flatMap { $0 > 0 ? newKg / $0 : nil }
        var percentMoves: [MaxImpact.PercentMove] = []
        var kgOffers: [MaxImpact.KgOffer] = []

        for routine in routines {
            let plan = routine.plan.executable
            var seenPercents: Set<String> = []
            var moves: [MaxImpact.KgOffer.Move] = []
            for set in plan.sets where set.grip.key == grip.key {
                if let explicit = set.targetBand {
                    guard let ratio else { continue }
                    let move = MaxImpact.KgOffer.Move(
                        oldBand: explicit,
                        newBand: Self.scaled(explicit, by: ratio))
                    // Two byte-identical sets would offer the same line twice.
                    if !moves.contains(move) { moves.append(move) }
                } else if let percent = PlanMath.targetPercent(set, in: plan) {
                    let key = "\(percent.lowerBound)–\(percent.upperBound)"
                    guard seenPercents.insert(key).inserted else { continue }
                    guard let newBand = PlanMath.targetBand(set, in: plan, maxKg: newKg)
                    else { continue }
                    percentMoves.append(MaxImpact.PercentMove(
                        routineName: routine.name,
                        loPercent: percent.lowerBound,
                        hiPercent: percent.upperBound,
                        oldBand: oldKg.flatMap { PlanMath.targetBand(set, in: plan, maxKg: $0) },
                        newBand: newBand))
                }
            }
            if !moves.isEmpty {
                kgOffers.append(MaxImpact.KgOffer(routineID: routine.id,
                                                  routineName: routine.name,
                                                  moves: moves))
            }
        }
        return MaxImpact(percentMoves: percentMoves, kgOffers: kgOffers, ratio: ratio)
    }

    /// Apply the accepted rescale: every explicit-kg set on `grip` in the given
    /// routines, multiplied by `ratio`. Normalized exactly as a builder save is, so a
    /// rescale cannot produce a routine the builder itself would have refused.
    ///
    /// **Applied to every routine first, then persisted ONCE.** It used to call
    /// `save(draft)` per routine, and that is the builder's entry point — it drags a full
    /// `context.save()`, a whole-store `syncDerived()` and a reminder replan behind each
    /// one. For what is a single tap ("Scale them with the new max") on a grip that
    /// appears in four routines, that was four of each. Going direct also stops it
    /// calling `clearDraft()`, which would have discarded the builder's unsaved rescue
    /// copy as a side effect of a tap in the Maxes tab, and makes the rescale ATOMIC — a
    /// write that fails now rolls every routine back together, where before it could
    /// leave half the offer scaled and half not.
    ///
    /// It deliberately does NOT `askNotificationPermissionOnce`: every routine here has
    /// already been through the builder at least once, which is where that question
    /// belongs — an OS permission alert raised from a max-entry sheet arrives with no
    /// reason anywhere on screen.
    @discardableResult
    func scaleKgTargets(grip: GripSpec, ratio: Double, routineIDs: [UUID]) -> Bool {
        guard ratio.isFinite, ratio > 0 else { return false }
        // ONE fetch for the batch: `routine(id:)` fetches and sorts every routine in the
        // store, so asking it per id paid for that N times over.
        guard let routines = fetchRoutines() else { return false }
        let wanted = Set(routineIDs)

        var applied = false
        var allApplied = true
        for template in routines where wanted.contains(template.id) {
            // Faulted by a CloudKit merge — the guard `update` makes, for the same
            // reason: mutating one throws an ObjC exception no Swift `catch` can reach.
            guard template.modelContext != nil else {
                allApplied = false
                continue
            }
            var draft = draft(editing: template)
            draft.plan.sets = draft.plan.sets.map { set in
                guard set.grip.key == grip.key, set.hasTarget else { return set }
                var scaled = set
                if let lo = scaled.targetLoKg { scaled.targetLoKg = Self.scaledKg(lo, by: ratio) }
                if let hi = scaled.targetHiKg { scaled.targetHiKg = Self.scaledKg(hi, by: ratio) }
                return scaled
            }
            template.apply(draft.normalized)
            applied = true
        }

        // Nothing was touched, so there is nothing to save and no rollback to survive —
        // and an empty save would still cost a full sync.
        guard applied else { return allApplied }
        // The new max was written and folded by `recordMax` before this offer was even
        // computed; this write moves routines only.
        persistAndSync(maxesChanged: false)
        return allApplied && saveError == nil
    }

    /// Half-kilogram rounding, same as the percent path resolves to — a scaled typed
    /// number should look like a number someone could have typed.
    private static func scaledKg(_ kg: Double, by ratio: Double) -> Double {
        PlanMath.roundedToHalfKg(kg * ratio)
    }

    private static func scaled(_ band: ClosedRange<Double>, by ratio: Double) -> ClosedRange<Double> {
        let lo = scaledKg(band.lowerBound, by: ratio)
        let hi = scaledKg(band.upperBound, by: ratio)
        return Swift.min(lo, hi)...Swift.max(lo, hi)
    }

    // MARK: - Draft rescue

    /// The builder's first-run flow is the one place a user can spend real effort
    /// before anything is persisted, so the working draft is stashed as it changes.
    /// Cleared on BOTH Save and Cancel — a stash that outlives an explicit Cancel comes
    /// back as a ghost the next time the builder opens.
    func stashDraft(_ draft: RoutineDraft) {
        settings.draftStash = BlobCodec.encode(draft)
    }

    func restoreDraft() -> RoutineDraft? {
        settings.draftStash.flatMap { BlobCodec.decode(RoutineDraft.self, from: $0) }
    }

    func clearDraft() {
        settings.draftStash = nil
    }

    // MARK: - Side-effect pipeline

    /// `maxesChanged: false` is the caller asserting that this write touched no
    /// `MaxRecord`, which is what lets `syncDerived` skip the unbounded fetch.
    ///
    /// It defaults to TRUE so the conservative answer is the one you get by forgetting,
    /// and the DEBUG assert catches the other direction — a `false` that is a lie leaves
    /// `maxTable` stale, and every percent-of-max band in the app resolves through it.
    /// The check has to run BEFORE the save, which is what drains the pending sets.
    private func persistAndSync(maxesChanged: Bool = true) {
        #if DEBUG
        if !maxesChanged {
            let pending = context.insertedModelsArray + context.changedModelsArray
                + context.deletedModelsArray
            assert(!pending.contains { $0 is MaxRecord },
                   "persistAndSync(maxesChanged: false) with a MaxRecord in the pending changes — "
                   + "the max table would not be refolded.")
        }
        #endif
        saveError = nil
        do {
            try context.save()
        } catch {
            // Roll back so memory matches disk: a phantom routine that dies with the
            // process — while the list, the reminders and the session it started all
            // confirm it — is far worse than a visible error.
            context.rollback()
            saveError = String(localized: "That change couldn't be saved — \(error.localizedDescription)")
        }
        syncDerived(refoldingMaxes: maxesChanged)
    }

    /// Asked on the first Save of a routine that actually WANTS reminders — by then the
    /// user has been through the builder and seen both times on screen, so the OS alert
    /// arrives with its reason already on the previous screen.
    private func askNotificationPermissionOnce(for draft: RoutineDraft) {
        guard draft.remindersEnabled, !draft.reminders.isEmpty,
              !settings.didAskNotificationPermission else { return }
        Task { [weak self] in
            guard let self else { return }
            await ReminderPlanner.requestAuthorizationIfNeeded(settings: settings)
            // Plan immediately if granted: the replan that ran inside the save saw an
            // unauthorized center and scheduled nothing.
            syncDerived()
        }
    }
}
