// SPDX-License-Identifier: MPL-2.0
// Original contributions Copyright 2026 Nuri Bruner.

import Foundation
import SwiftData
import XCTest
@testable import Doigt

/// Everything below runs against an in-memory `ModelContainer` built from the real
/// CloudKit-shaped schema, so the blob accessors, the derived recompute and the undo
/// path are exercised exactly as they are on device — only the store file is fake.
///
/// There is no `setUp` override: `XCTestCase`'s hooks are nonisolated, and this class
/// is `@MainActor` because every store in this app is. `makeWorld()` is called
/// explicitly instead.
@MainActor
final class TemplateStoreTests: XCTestCase {

    private struct World {
        let container: ModelContainer
        let context: ModelContext
        let clock: DayClock
        let settings: SettingsStore
        let store: TemplateStore
    }

    private static let fullSchema = Schema([SessionTemplate.self, WorkoutLog.self, MaxRecord.self])

    /// On-disk stores created for the read-only seam, removed in `tearDown`.
    private var scratchStores: [URL] = []

    override func tearDownWithError() throws {
        for url in scratchStores {
            for suffix in ["", "-shm", "-wal"] {
                try? FileManager.default.removeItem(
                    at: url.deletingLastPathComponent()
                        .appendingPathComponent(url.lastPathComponent + suffix))
            }
        }
        scratchStores.removeAll()
    }

    /// `seeding` matters only for the read-only seam: a row that was never persisted can
    /// be inserted and deleted entirely in memory, so `save()` has nothing to write and
    /// succeeds even on a read-only store. To refuse a DELETE the store has to already
    /// contain the row, which means writing it before the file is reopened read-only.
    private func makeWorld(allowsSave: Bool = true,
                           seeding seed: RoutineDraft? = nil,
                           seedingSession: Bool = false) throws -> World {
        let config: ModelConfiguration
        if allowsSave {
            config = ModelConfiguration("Doigt", schema: Self.fullSchema,
                                        isStoredInMemoryOnly: true, allowsSave: true,
                                        cloudKitDatabase: .none)
        } else {
            // `isStoredInMemoryOnly: true` + `allowsSave: false` is NOT a save-failure
            // seam: the in-memory store is backed by /dev/null, and opening that
            // read-only fails to LOAD, so the container never exists and the test
            // reports a container error instead of the rollback it meant to check.
            // Materialise a real file with the schema in it first, then reopen the same
            // file read-only — which refuses writes at exactly the moment we want.
            let url = FileManager.default.temporaryDirectory
                .appendingPathComponent("doigt-test-\(UUID().uuidString).store")
            scratchStores.append(url)
            let seedConfig = ModelConfiguration(schema: Self.fullSchema, url: url,
                                                cloudKitDatabase: .none)
            let seedContainer = try ModelContainer(for: Self.fullSchema,
                                                   configurations: [seedConfig])
            if seed != nil || seedingSession {
                let seedContext = ModelContext(seedContainer)
                if let seed {
                    seedContext.insert(SessionTemplate(draft: seed.normalized, sortIndex: 0))
                }
                if seedingSession {
                    let plan = RoutineDraft.starter.normalized.plan.executable
                    seedContext.insert(WorkoutLog(plan: plan, templateID: nil,
                                                  templateName: "Daily no-hangs",
                                                  sessionsPerDayTarget: 2, reps: [],
                                                  startedAt: .now, finishedAt: .now,
                                                  day: DayStamp.today()))
                }
                try seedContext.save()
            }
            config = ModelConfiguration(schema: Self.fullSchema, url: url,
                                        allowsSave: false, cloudKitDatabase: .none)
        }
        let container = try ModelContainer(for: Self.fullSchema, configurations: [config])
        // An explicit context rather than `mainContext`: autosave would land writes at
        // unpredictable moments and make the rollback assertions racy.
        let context = ModelContext(container)
        let clock = DayClock()
        let settings = SettingsStore()
        // SettingsStore is UserDefaults-backed, so it carries state between test runs.
        settings.builderGuideDone = false
        settings.didAskNotificationPermission = true
        settings.lastStartedRoutineID = nil
        settings.lastStartedDayRaw = 0
        settings.draftStash = nil
        let store = TemplateStore(context: context, clock: clock, settings: settings,
                                  storageMode: .localOnly)
        return World(container: container, context: context, clock: clock,
                     settings: settings, store: store)
    }

    private func routines(_ w: World) -> [SessionTemplate] {
        let descriptor = FetchDescriptor<SessionTemplate>(sortBy: [SortDescriptor(\.sortIndex)])
        return (try? w.context.fetch(descriptor)) ?? []
    }

    private func workoutLogs(_ w: World) -> [WorkoutLog] {
        (try? w.context.fetch(FetchDescriptor<WorkoutLog>())) ?? []
    }

    @discardableResult
    private func insertLog(_ w: World, template: SessionTemplate?, day: DayStamp,
                           target: Int = 2, name: String? = nil) throws -> WorkoutLog {
        let plan = (template.map { w.store.plan(for: $0) } ?? SessionPlan()).executable
        var rep = RepSummary()
        rep.grip = plan.sets.first?.grip ?? GripSpec()
        let started = day.date()
        let log = WorkoutLog(plan: plan,
                             templateID: template?.id,
                             templateName: name ?? template?.name ?? "",
                             sessionsPerDayTarget: target,
                             reps: [rep],
                             startedAt: started,
                             finishedAt: started.addingTimeInterval(1290),
                             day: day)
        w.context.insert(log)
        try w.context.save()
        w.store.syncDerived()
        return log
    }

    private func draft(_ name: String, grips: [GripSpec]) -> RoutineDraft {
        var d = RoutineDraft.blank(named: name)
        d.plan.sets = grips.map { SetPlan(grip: $0, repsPerSide: 3) }
        return d
    }

    // MARK: - The prefill is a product spec

    /// Nuri's actual protocol, asserted field by field. If any of these move, the
    /// routine that greets him on first run is no longer the one he described.
    func testPrefillProducesSixSetsInNurisExactOrder() {
        let draft = RoutineDraft.starter

        XCTAssertEqual(draft.plan.name, "Daily no-hangs")
        XCTAssertEqual(draft.plan.sets.map(\.grip.key), [
            "20|IMRL|halfCrimp", "20|IMR|halfCrimp", "20|IM|openHand",
            "20|MR|openHand", "20|IM|fullCrimp", "20|MR|fullCrimp",
        ])
        XCTAssertEqual(draft.plan.sets.map(\.repsPerSide), [6, 6, 2, 2, 1, 1])
        XCTAssertEqual(draft.plan.handMode, .alternateEachRep)
        XCTAssertEqual(draft.plan.holdSeconds, 10)
        XCTAssertEqual(draft.plan.restSeconds, 20)
        XCTAssertEqual(draft.plan.setBreakSeconds, 60)
        XCTAssertEqual(draft.plan.leadInSeconds, 5)
        XCTAssertEqual(draft.plan.thresholdKg, 2.0, accuracy: 0.0001)
        XCTAssertEqual(draft.sessionsPerDay, 2)
        XCTAssertTrue(draft.remindersEnabled)
        XCTAssertEqual(draft.reminders, [ReminderTime(hour: 8, minute: 0),
                                         ReminderTime(hour: 19, minute: 0)])
        XCTAssertEqual(draft.parkedReminders, [])
        XCTAssertTrue(draft.isNew)
        XCTAssertNil(draft.validationIssue)

        // ZERO per-set timing overrides: that is what makes changing one rest interval
        // a single edit across all six sets.
        XCTAssertTrue(draft.plan.sets.allSatisfy { !$0.overridesTiming })
        XCTAssertTrue(draft.plan.sets.allSatisfy { !$0.hasTarget })
    }

    /// A lazy `static let` would mint the six SetPlan ids once per process and hand
    /// identical ids to two routines built in one sitting.
    func testPrefillMintsFreshSetIDsOnEveryAccess() {
        let first = Set(RoutineDraft.starter.plan.sets.map(\.id))
        let second = Set(RoutineDraft.starter.plan.sets.map(\.id))
        XCTAssertEqual(first.count, 6)
        XCTAssertTrue(first.isDisjoint(with: second))
    }

    /// BLANK MEANS BLANK (Nuri, 2026-08-11). It used to seed one 20 mm four-finger
    /// half-crimp set so the list was never empty; that set was a guess presented as your
    /// routine, and deleting it was the commonest first edit.
    ///
    /// What replaces the seed as the safety net is `validationIssue`: Save stays refused,
    /// and says why, until there is a real set in here. This test pins BOTH halves —
    /// remove the guard and the empty draft silently becomes saveable.
    func testBlankDraftIsEmptyAndCannotBeSavedUntilASetExists() {
        let blank = RoutineDraft.blank()
        XCTAssertTrue(blank.plan.sets.isEmpty, "no grip is presumed for you")
        XCTAssertEqual(blank.plan.name, "My routine")
        XCTAssertEqual(RoutineDraft.blank(named: "Rest day").plan.name, "Rest day")
        XCTAssertEqual(blank.validationIssue, "Add at least one set with a pull in it.")

        var withASet = blank
        withASet.plan.sets = [SetPlan(grip: GripSpec(edgeMM: 20, fingers: .four,
                                                     position: .halfCrimp),
                                      repsPerSide: 6)]
        XCTAssertNil(withASet.validationIssue, "and it saves the moment one exists")
    }

    // MARK: - Create / update / save

    /// One test covering every blob accessor at once: sets, reminders, parked
    /// reminders, hand mode and the whole rhythm block, out and back.
    func testCreatedRoutineRoundTripsThroughTheModelUnchanged() throws {
        let w = try makeWorld()
        let seed = RoutineDraft.starter
        let template = try XCTUnwrap(w.store.create(seed))

        var expected = seed.normalized
        expected.templateID = template.id
        XCTAssertEqual(w.store.draft(editing: template), expected)

        XCTAssertEqual(template.name, "Daily no-hangs")
        XCTAssertEqual(template.sortIndex, 0)
        XCTAssertEqual(template.handModeRaw, "alternateEachRep")
        XCTAssertEqual(template.sets.map(\.grip.key), seed.plan.sets.map(\.grip.key))
        XCTAssertEqual(template.reminders, seed.reminders)
        XCTAssertEqual(template.estimatedSeconds, 1290)
        XCTAssertEqual(template.summaryLine, "6 sets · 36 pulls · ≈21 min")

        let summary = w.store.summary(for: template)
        XCTAssertEqual(summary.metaLine, "20 mm · 6 sets · 36 pulls · ≈21 min")
        XCTAssertEqual(summary.setCount, 6)
        XCTAssertEqual(summary.totalReps, 36)
        XCTAssertEqual(summary.sharedEdgeMM, 20)
        XCTAssertEqual(summary.sessionsPerDay, 2)
        XCTAssertEqual(summary.ladder.map(\.repsPerSide), [6, 6, 2, 2, 1, 1])
        XCTAssertEqual(summary.ladder.map(\.id), [0, 1, 2, 3, 4, 5])
        // WHICH slot is next depends on the wall clock at test time, so only
        // membership is assertable here — it may never be a time nobody set.
        if let next = summary.nextReminder {
            XCTAssertTrue(seed.reminders.contains(next))
        }
    }

    /// The wizard IS the editor, so an edit must land on the same rows the user was
    /// looking at rather than replacing the routine wholesale.
    func testUpdateEditsInPlaceAndPreservesSetIdentity() throws {
        let w = try makeWorld()
        let template = try XCTUnwrap(w.store.create(.starter))
        let createdAt = template.createdAt
        let updatedBefore = template.updatedAt
        let idsBefore = template.sets.map(\.id)

        var edited = w.store.draft(editing: template)
        edited.plan.sets[0].repsPerSide = 8
        XCTAssertTrue(w.store.update(template, with: edited))

        XCTAssertEqual(template.sets.map(\.id), idsBefore, "every set keeps its identity")
        XCTAssertEqual(template.sets[0].repsPerSide, 8)
        XCTAssertEqual(template.createdAt, createdAt)
        XCTAssertGreaterThan(template.updatedAt, updatedBefore)
        XCTAssertEqual(routines(w).count, 1)
    }

    func testSaveRoutesToCreateOrUpdateByTemplateID() throws {
        let w = try makeWorld()
        let created = try XCTUnwrap(w.store.save(.starter))
        XCTAssertEqual(routines(w).count, 1)

        var edited = w.store.draft(editing: created)
        XCTAssertEqual(edited.templateID, created.id)
        edited.plan.name = "Morning no-hangs"
        let saved = try XCTUnwrap(w.store.save(edited))

        XCTAssertEqual(saved.id, created.id)
        XCTAssertEqual(routines(w).count, 1, "a live templateID updates rather than inserting")
        XCTAssertEqual(created.name, "Morning no-hangs")
    }

    func testDuplicateAppendsWithANewIDFreshSetIDsAndACopyOfPrefix() throws {
        let w = try makeWorld()
        let original = try XCTUnwrap(w.store.create(.starter))
        let copy = try XCTUnwrap(w.store.duplicate(original))

        XCTAssertEqual(copy.name, "Copy of Daily no-hangs")
        XCTAssertNotEqual(copy.id, original.id)
        XCTAssertEqual(copy.sortIndex, 1, "a duplicate lands at the end, not on top of Today")
        XCTAssertTrue(Set(copy.sets.map(\.id)).isDisjoint(with: Set(original.sets.map(\.id))),
                      "shared set ids would make one edit hit two routines")
        XCTAssertEqual(copy.sets.map(\.grip.key), original.sets.map(\.grip.key))

        let second = try XCTUnwrap(w.store.duplicate(original))
        XCTAssertEqual(second.name, "Copy of Daily no-hangs 2")
        XCTAssertEqual(second.sortIndex, 2)
    }

    // MARK: - Importing a shared routine

    /// The shape `RoutineShare.draft(from:)` hands over: a real plan, no template
    /// identity, and reminders OFF — the payload deliberately carries no personal times.
    private func imported(_ name: String) -> RoutineDraft {
        var d = RoutineDraft.starter
        d.templateID = nil
        d.plan.name = name
        d.remindersEnabled = false
        return d
    }

    func testImportingARoutineWhoseNameCollidesGetsANumberedUniqueName() throws {
        let w = try makeWorld()
        let mine = try XCTUnwrap(w.store.create(.starter))
        XCTAssertEqual(mine.name, "Daily no-hangs")

        // Two people converging on the same obvious name is the COMMON case for a shared
        // routine, not the edge one — and the chooser shows names only, so a second
        // "Daily no-hangs" is a routine you cannot pick by sight.
        let first = try XCTUnwrap(w.store.importRoutine(imported("Daily no-hangs")))
        XCTAssertEqual(first.name, "Daily no-hangs 2")

        let again = try XCTUnwrap(w.store.importRoutine(imported("Daily no-hangs")))
        XCTAssertEqual(again.name, "Daily no-hangs 3")

        let fresh = try XCTUnwrap(w.store.importRoutine(imported("Somebody else's ladder")))
        XCTAssertEqual(fresh.name, "Somebody else's ladder",
                       "a name nobody has taken is left exactly as it was shared")
    }

    /// Both guarantees the store re-asserts rather than trusting from the wire, asserted
    /// against a draft that violates each: reminders would raise an OS permission prompt
    /// out of a scan, and an adopted `templateID` would mint a routine wearing the
    /// sharer's identity — the id every `doigt.routine.<uuid>.<slot>` notification is
    /// keyed from.
    func testImportNeverTurnsOnRemindersOrAdoptsTheSharersRoutineID() throws {
        let w = try makeWorld()
        var incoming = imported("Borrowed burn")
        incoming.remindersEnabled = true
        let sharersID = UUID()
        incoming.templateID = sharersID

        let landed = try XCTUnwrap(w.store.importRoutine(incoming))

        XCTAssertFalse(landed.remindersEnabled)
        XCTAssertNotEqual(landed.id, sharersID)
        XCTAssertNil(w.store.summary(for: landed).nextReminder,
                     "reminders off means nothing is scheduled and nothing is promised")
    }

    /// The inbox exists because a link can land while a full-screen cover owns the
    /// screen — presenting from the root at that moment tore the cover down (a session
    /// died unlogged). So the store HOLDS the decoded value, TodayView drains it when
    /// the screen is free, and a claim consumes the slot so one scan can never present
    /// twice.
    func testTheShareLinkInboxHoldsOneScanAndAClaimConsumesIt() throws {
        let w = try makeWorld()
        let url = try XCTUnwrap(RoutineShare.url(for: .starter))

        w.store.receiveShareLink(url)
        XCTAssertNotNil(w.store.pendingImport)
        XCTAssertNil(w.store.pendingImportError)

        let claimed = w.store.claimPendingImport()
        XCTAssertNotNil(claimed)
        XCTAssertNil(w.store.pendingImport, "a claim consumes the slot")
        XCTAssertNil(w.store.claimPendingImport(), "and a second drain finds nothing")

        // A damaged link fills the OTHER slot — and clears the first, latest scan wins:
        // two codes scanned back to back are one decision, about the second one.
        w.store.receiveShareLink(url)
        let damaged = try XCTUnwrap(URL(string: "getagrip://routine#not-a-payload!!"))
        w.store.receiveShareLink(damaged)
        XCTAssertNil(w.store.pendingImport)
        XCTAssertEqual(w.store.claimPendingImportError(),
                       RoutineShareError.unreadable.errorDescription)
        XCTAssertNil(w.store.pendingImportError)
    }

    /// The name the preview promises must be the name the card wears — deconfliction
    /// happens on save, so the preview asks the same question ahead of time.
    func testThePlannedImportNameMatchesWhatImportActuallyLandsUnder() throws {
        let w = try makeWorld()
        _ = try XCTUnwrap(w.store.create(.starter))

        let planned = w.store.plannedImportName(for: "Daily no-hangs")
        let landed = try XCTUnwrap(w.store.importRoutine(imported("Daily no-hangs")))
        XCTAssertEqual(planned, landed.name)
        XCTAssertEqual(landed.name, "Daily no-hangs 2")
    }

    /// The column arrived late (2026-08-19): the plan field, its toggle and the
    /// runner's gate all shipped first, and every save silently flipped the switch back
    /// to true on the way to disk. Found because the QR payload carried a field the
    /// store then lost at both ends.
    func testPausesOutsideTargetBandSurvivesTheSave() throws {
        let w = try makeWorld()
        var draft = RoutineDraft.starter
        draft.plan.pausesOutsideTargetBand = false

        let saved = try XCTUnwrap(w.store.create(draft))

        XCTAssertFalse(saved.pausesOutsideTargetBand)
        XCTAssertFalse(saved.plan.pausesOutsideTargetBand, "the runner reads the plan")
        XCTAssertFalse(saved.draft.plan.pausesOutsideTargetBand, "the editor reads the draft")
    }

    /// `DeletedRoutine` restores RAW columns, so every column the model grows must be
    /// added to the snapshot — `isOnDemand` was missing, and undoing a deleted WHENEVER
    /// routine brought it back as a ritual, daily target and all.
    func testUndoingADeletedWheneverRoutineRestoresItAsAWheneverRoutine() throws {
        let w = try makeWorld()
        var draft = RoutineDraft.maxDay
        draft.plan.pausesOutsideTargetBand = false
        let saved = try XCTUnwrap(w.store.create(draft))
        XCTAssertTrue(saved.isOnDemand)

        XCTAssertTrue(w.store.delete(saved))
        w.store.undoDelete()

        let restored = try XCTUnwrap(routines(w).first { $0.name == "Max day" })
        XCTAssertTrue(restored.isOnDemand, "a whenever routine must not come back owed")
        XCTAssertFalse(restored.plan.pausesOutsideTargetBand,
                       "both late columns ride the snapshot now")
    }

    func testAnImportedRoutineAppendsAtTheEndAndNeverBecomesPrimary() throws {
        let w = try makeWorld()
        let mine = try XCTUnwrap(w.store.create(.blank(named: "Mine")))
        let restDay = try XCTUnwrap(w.store.create(.blank(named: "Rest day")))

        let landed = try XCTUnwrap(w.store.importRoutine(imported("Shared ladder")))

        // Scanning a code is somebody else's suggestion, not a decision about which
        // routine meets you on open. Landing at sortIndex 0 would make it exactly that.
        XCTAssertEqual(mine.sortIndex, 0)
        XCTAssertEqual(restDay.sortIndex, 1)
        XCTAssertEqual(landed.sortIndex, 2)
        XCTAssertEqual(routines(w).map(\.name), ["Mine", "Rest day", "Shared ladder"])
    }

    // MARK: - Order

    func testReorderRenormalizesSortIndicesToContiguousZeroBased() throws {
        let w = try makeWorld()
        let alpha = try XCTUnwrap(w.store.create(.blank(named: "Alpha")))
        let bravo = try XCTUnwrap(w.store.create(.blank(named: "Bravo")))
        let charlie = try XCTUnwrap(w.store.create(.blank(named: "Charlie")))
        XCTAssertEqual([alpha, bravo, charlie].map(\.sortIndex), [0, 1, 2])

        w.store.move(fromOffsets: IndexSet(integer: 2), toOffset: 0)

        XCTAssertEqual(charlie.sortIndex, 0)
        XCTAssertEqual(alpha.sortIndex, 1)
        XCTAssertEqual(bravo.sortIndex, 2)
        XCTAssertEqual([alpha, bravo, charlie].map(\.sortIndex).sorted(), [0, 1, 2],
                       "no gaps and no duplicates")
    }

    /// Two devices reordering concurrently is exactly what produces duplicate
    /// sortIndex values over CloudKit. A partial sort would render them in a different
    /// order on each device, which reads to the user as a sync bug.
    func testTiedSortIndicesResolveDeterministically() throws {
        let w = try makeWorld()
        var made: [SessionTemplate] = []
        for (offset, name) in ["Alpha", "Bravo", "Charlie"].enumerated() {
            let template = SessionTemplate(draft: .blank(named: name), sortIndex: 0)
            template.createdAt = Date(timeIntervalSince1970: 1_800_000_000 + Double(offset))
            w.context.insert(template)
            made.append(template)
        }
        try w.context.save()
        w.store.syncDerived()

        // An identity move still renormalizes, which is where the total order becomes
        // observable: ties break by createdAt, then by id.
        w.store.move(fromOffsets: IndexSet(integer: 0), toOffset: 0)
        XCTAssertEqual(made.map(\.sortIndex), [0, 1, 2])

        w.store.syncDerived()
        w.store.move(fromOffsets: IndexSet(integer: 0), toOffset: 0)
        XCTAssertEqual(made.map(\.sortIndex), [0, 1, 2], "stable across repeated recomputes")
    }

    // MARK: - Delete and undo

    /// The UUID matters because reminder identifiers are `doigt.routine.<uuid>.r0480`;
    /// the raw blobs matter because undo must not quietly drop a field this build
    /// cannot decode but a newer one wrote.
    func testDeleteThenUndoRestoresTheOriginalUUIDSortIndexAndRawBlobs() throws {
        let w = try makeWorld()
        let template = try XCTUnwrap(w.store.create(.starter))
        _ = w.store.create(.blank(named: "Rest day"))

        // A blob as a NEWER build would have written it: an extra key this build has
        // never heard of, plus a hand mode it cannot name.
        let futureBlob = Data("""
        [{"grip":{"edgeMM":20,"fingers":"IMRL","position":"halfCrimp"},\
        "id":"8B2C4E8A-0000-4000-8000-0000000000FF","repsPerSide":6,"tempoSeconds":3}]
        """.utf8)
        template.setsData = futureBlob
        template.handModeRaw = "leftOnly"
        try w.context.save()

        let id = template.id
        let sortIndex = template.sortIndex
        let createdAt = template.createdAt
        let remindersData = template.remindersData

        XCTAssertTrue(w.store.delete(template))
        XCTAssertEqual(w.store.lastDeleted?.id, id)
        XCTAssertEqual(routines(w).count, 1)
        XCTAssertNil(w.store.routine(id: id))

        w.store.undoDelete()

        let restored = try XCTUnwrap(w.store.routine(id: id))
        XCTAssertEqual(restored.id, id, "st.* style identifiers are keyed on this")
        XCTAssertEqual(restored.sortIndex, sortIndex)
        XCTAssertEqual(restored.createdAt, createdAt)
        XCTAssertEqual(restored.setsData, futureBlob, "raw bytes, not a decode-re-encode")
        XCTAssertEqual(restored.handModeRaw, "leftOnly")
        XCTAssertEqual(restored.remindersData, remindersData)
        XCTAssertNil(w.store.lastDeleted)
        XCTAssertEqual(routines(w).count, 2)
    }

    /// "Undo" on a delete that never landed would insert a second copy of a routine
    /// that is still there.
    func testUndoIsOnlyOfferedWhenTheDeleteActuallyLanded() throws {
        let w = try makeWorld(allowsSave: false, seeding: .starter)
        let template = try XCTUnwrap(routines(w).first, "the read-only store must open seeded")

        XCTAssertFalse(w.store.delete(template),
                       "if this passes, allowsSave:false is no longer a save-failure seam")
        XCTAssertNil(w.store.lastDeleted)
        XCTAssertNotNil(w.store.saveError)
        XCTAssertEqual(routines(w).count, 1, "the rolled-back delete must leave it in memory")
    }

    func testDeletingTheLastRoutineIsAllowedAndLeavesAnEmptyWorld() throws {
        let w = try makeWorld()
        let template = try XCTUnwrap(w.store.create(.starter))

        XCTAssertTrue(w.store.delete(template))
        XCTAssertEqual(routines(w).count, 0, "Today falls to its empty card")
        XCTAssertTrue(w.store.completionsToday.isEmpty)

        w.store.undoDelete()
        XCTAssertEqual(routines(w).count, 1)
        XCTAssertEqual(routines(w).first?.summaryLine, "6 sets · 36 pulls · ≈21 min")
    }

    // MARK: - The new routine-level columns

    /// Columns, not blob fields — so they need their own round trip through `apply` and
    /// back out through `plan`, and they have to survive the undo path, which rebuilds a
    /// routine field by field and is the one place a new column is silently forgotten.
    ///
    /// A routine-level band is DEMOTED onto the sets on the way in (2026-08-10: load
    /// lives per set), so what survives the round trip is the sets' bands — the routine
    /// columns come back empty, which is the new invariant worth pinning.
    func testReleaseGateAndTargetPercentSurviveSaveAndUndo() throws {
        let w = try makeWorld()
        var draft = RoutineDraft.starter
        draft.plan.waitForReleaseBeforeRest = false
        draft.plan.targetLoPercent = 0.17
        draft.plan.targetHiPercent = 0.22
        let template = try XCTUnwrap(w.store.create(draft))
        let id = template.id

        XCTAssertFalse(template.plan.waitForReleaseBeforeRest)
        XCTAssertNil(template.plan.targetPercentBand, "demoted, never stored on the routine")
        XCTAssertTrue(template.plan.executable.sets.allSatisfy { $0.targetPercentBand == 0.17...0.22 },
                      "every set carries the band the routine was authored with")
        XCTAssertTrue(template.draft.plan.executable.sets.allSatisfy { $0.targetPercentBand == 0.17...0.22 },
                      "and the editor reads back what it wrote")

        XCTAssertTrue(w.store.delete(template))
        w.store.undoDelete()

        let restored = try XCTUnwrap(w.store.routine(id: id))
        XCTAssertFalse(restored.waitForReleaseBeforeRest)
        XCTAssertTrue(restored.plan.executable.sets.allSatisfy { $0.targetPercentBand == 0.17...0.22 },
                      "undo restores the demoted bands with the sets")
    }

    /// An inverted band is one drag in the builder — it must never reach disk the wrong
    /// way up. It now also never reaches disk on the ROUTINE at all: the flipped band
    /// is demoted onto every set on the way in.
    func testAnUpsideDownRoutinePercentageIsNormalizedOnTheWayIn() throws {
        let w = try makeWorld()
        var draft = RoutineDraft.starter
        draft.plan.targetLoPercent = 0.30
        draft.plan.targetHiPercent = 0.20
        let template = try XCTUnwrap(w.store.create(draft))

        XCTAssertNil(template.targetLoPercent)
        XCTAssertNil(template.targetHiPercent)
        XCTAssertTrue(template.plan.executable.sets.allSatisfy { $0.targetPercentBand == 0.20...0.30 },
                      "flipped the right way up, and living on the sets")
    }

    /// A routine that predates both fields keeps working and GAINS the release gate —
    /// the default that matters, since it changes how every existing rest behaves.
    func testARoutineWrittenBeforeTheseFieldsGetsTheDefaults() throws {
        let w = try makeWorld()
        let template = try XCTUnwrap(w.store.create(.starter))

        XCTAssertTrue(template.waitForReleaseBeforeRest)
        XCTAssertNil(template.targetLoPercent)
        XCTAssertNil(template.plan.targetPercentBand)
    }

    // MARK: - Deleting a session

    /// The id matters because undo RE-INSERTS rather than un-deletes. The raw columns
    /// matter more: `WorkoutLog`'s init re-derives every denormalized number from the
    /// reps it is handed, so a restore that went through it would re-score a finished
    /// session under today's arithmetic — and quietly drop whatever a newer build wrote
    /// into the blobs.
    func testDeletingASessionThenUndoingRestoresItByteForByte() throws {
        let w = try makeWorld()
        let template = try XCTUnwrap(w.store.create(.starter))
        let log = try insertLog(w, template: template, day: DayStamp.today() - 1)

        // Deliberately inconsistent with `resultsData`: these are exactly the columns a
        // re-derive would "correct", so if the restore recomputes, this test fails.
        log.peakKg = 41.5
        log.completedReps = 36
        log.totalHeldSeconds = 360
        log.rpe = RPE.hard.rawValue
        log.fingerStrainRaw = FingerStrain.taxed.rawValue
        log.durationMinutes = 150
        log.notes = "felt good"
        let futureBlob = Data(#"[{"unknownKeyFromANewerBuild":1}]"#.utf8)
        log.resultsData = futureBlob
        try w.context.save()

        let id = log.id
        let startedAt = log.startedAt
        let dayKey = log.dayKey
        let planData = log.planData

        XCTAssertTrue(w.store.deleteSession(log))
        XCTAssertEqual(w.store.lastDeletedSession?.id, id)
        XCTAssertEqual(workoutLogs(w).count, 0)

        w.store.undoDeleteSession()

        let restored = try XCTUnwrap(workoutLogs(w).first)
        XCTAssertEqual(restored.id, id)
        XCTAssertEqual(restored.startedAt, startedAt)
        XCTAssertEqual(restored.dayKey, dayKey, "the day it was counted against")
        XCTAssertEqual(restored.planData, planData, "raw bytes, not a decode-re-encode")
        XCTAssertEqual(restored.resultsData, futureBlob)
        XCTAssertEqual(restored.peakKg, 41.5, "denormalized, never recomputed from the reps")
        XCTAssertEqual(restored.completedReps, 36)
        XCTAssertEqual(restored.totalHeldSeconds, 360)
        XCTAssertEqual(restored.rpe, RPE.hard.rawValue, "how it felt survives the round trip")
        XCTAssertEqual(restored.fingerStrainRaw, FingerStrain.taxed.rawValue,
                       "the local strain axis survives the round trip")
        XCTAssertEqual(restored.durationMinutes, 150,
                       "the hand-entered duration survives the round trip")
        XCTAssertEqual(restored.notes, "felt good")
        XCTAssertNil(w.store.lastDeletedSession)
        XCTAssertEqual(workoutLogs(w).count, 1)
    }

    /// A session delete is not just a row leaving a list: Today counts "2 of 2" off
    /// these logs and the strip is drawn from them, so it has to walk back in the same
    /// breath — and come back on undo.
    func testDeletingTodaysSessionWalksTodaysCompletionBackAndUndoRestoresIt() throws {
        let w = try makeWorld()
        let template = try XCTUnwrap(w.store.create(.starter))
        try insertLog(w, template: template, day: w.clock.today)
        let second = try insertLog(w, template: template, day: w.clock.today)
        XCTAssertEqual(w.store.completionsToday[template.id], 2)

        XCTAssertTrue(w.store.deleteSession(second))
        XCTAssertEqual(w.store.completionsToday[template.id], 1)
        XCTAssertNotNil(w.store.routine(id: template.id), "the routine is untouched")

        w.store.undoDeleteSession()
        XCTAssertEqual(w.store.completionsToday[template.id], 2)
    }

    /// "Undo" on a delete that never landed would insert a second copy of a session
    /// that is still there — and history would gain a day it never trained.
    func testSessionUndoIsOnlyOfferedWhenTheDeleteActuallyLanded() throws {
        let w = try makeWorld(allowsSave: false, seedingSession: true)
        let log = try XCTUnwrap(workoutLogs(w).first, "the read-only store must open seeded")

        XCTAssertFalse(w.store.deleteSession(log),
                       "if this passes, allowsSave:false is no longer a save-failure seam")
        XCTAssertNil(w.store.lastDeletedSession)
        XCTAssertNotNil(w.store.saveError)
        XCTAssertEqual(workoutLogs(w).count, 1, "the rolled-back delete must leave it in memory")
    }

    /// Two undo slots, not one: a delete on History must not silently retract the Undo
    /// still on offer on Today.
    func testSessionAndRoutineUndoAreIndependent() throws {
        let w = try makeWorld()
        let template = try XCTUnwrap(w.store.create(.starter))
        let log = try insertLog(w, template: template, day: DayStamp.today() - 1)

        XCTAssertTrue(w.store.deleteSession(log))
        XCTAssertTrue(w.store.delete(template))

        XCTAssertNotNil(w.store.lastDeletedSession, "still on offer after a routine delete")
        XCTAssertNotNil(w.store.lastDeleted)

        w.store.undoDeleteSession()
        XCTAssertNil(w.store.lastDeletedSession)
        XCTAssertNotNil(w.store.lastDeleted, "the routine's offer outlives the session's")
    }

    // MARK: - History is frozen

    func testDeletingARoutineLeavesItsLogsIntactWithTheFrozenName() throws {
        let w = try makeWorld()
        let template = try XCTUnwrap(w.store.create(.starter))
        let templateID = template.id
        try insertLog(w, template: template, day: DayStamp.today() - 1)

        XCTAssertTrue(w.store.delete(template))

        let log = try XCTUnwrap(workoutLogs(w).first)
        XCTAssertEqual(log.templateName, "Daily no-hangs")
        XCTAssertEqual(log.templateID, templateID)
        XCTAssertNil(w.store.routine(id: templateID), "templateID is best-effort grouping only")
        XCTAssertEqual(log.plan?.sets.count, 6, "the frozen plan is still readable")
        XCTAssertEqual(log.reps.count, 1)
        XCTAssertFalse(log.planData.isEmpty)
    }

    /// The frozen-name rule, stated as a test because it is the one people "fix".
    func testRenamingARoutineDoesNotRetroRenameOldSessions() throws {
        let w = try makeWorld()
        let template = try XCTUnwrap(w.store.create(.starter))
        try insertLog(w, template: template, day: DayStamp.today() - 1)

        var edited = w.store.draft(editing: template)
        edited.plan.name = "Morning no-hangs"
        XCTAssertTrue(w.store.update(template, with: edited))

        XCTAssertEqual(template.name, "Morning no-hangs")
        XCTAssertEqual(workoutLogs(w).first?.templateName, "Daily no-hangs")
    }

    // MARK: - Completion counts

    func testCompletedTodayCountsOnlyTodaysLogsForThatTemplate() throws {
        let w = try makeWorld()
        let today = DayStamp.today()
        let daily = try XCTUnwrap(w.store.create(.starter))
        let rest = try XCTUnwrap(w.store.create(.blank(named: "Rest day")))

        try insertLog(w, template: daily, day: today - 1)   // yesterday
        try insertLog(w, template: rest, day: today)        // another routine
        try insertLog(w, template: daily, day: today)
        try insertLog(w, template: daily, day: today)

        XCTAssertEqual(w.store.completed(daily), 2)
        XCTAssertEqual(w.store.completed(rest), 1)
        XCTAssertTrue(w.store.isDoneForToday(daily))
        XCTAssertFalse(w.store.isDoneForToday(rest))
        XCTAssertTrue(w.store.summary(for: daily).targetMet)
        XCTAssertEqual(w.store.summary(for: daily).completedToday, 2)
        XCTAssertEqual(w.store.completionText(daily), "Both sessions done today")
    }

    /// A phone left open past midnight must flip 2/2 back to 0/2 without a relaunch.
    func testCompletionResetsWhenTheDayRolls() throws {
        let w = try makeWorld()
        let today = DayStamp.today()
        let daily = try XCTUnwrap(w.store.create(.starter))
        try insertLog(w, template: daily, day: today)
        try insertLog(w, template: daily, day: today)
        XCTAssertEqual(w.store.completed(daily), 2)

        w.clock.advance(to: today + 1)
        w.store.refreshIfDayChanged()

        XCTAssertEqual(w.store.completed(daily), 0)
        XCTAssertFalse(w.store.isDoneForToday(daily))
        XCTAssertFalse(w.store.summary(for: daily).targetMet)
    }

    // MARK: - The consistency strip

    func testConsistencyReturnsExactlyFourteenRecordsOldestFirstEndingToday() throws {
        let w = try makeWorld()
        _ = try XCTUnwrap(w.store.create(.starter))
        let today = DayStamp.today()

        let records = w.store.consistency
        XCTAssertEqual(records.count, 14)
        XCTAssertEqual(records.map(\.day), Array(stride(from: today - 13, through: today, by: 1)))
        XCTAssertEqual(records.first?.day, today - 13)
        XCTAssertEqual(records.last?.day, today)
    }

    /// The single most important honesty detail on Today: a day before any routine
    /// existed is a hairline, not a hole. Calling it a missed session would invent a
    /// failure the user could not possibly have avoided.
    func testDaysBeforeAnyRoutineExistedAreUntrackedNotMissed() throws {
        let w = try makeWorld()
        let today = DayStamp.today()
        let template = try XCTUnwrap(w.store.create(.starter))
        template.createdAt = (today - 3).date()
        try w.context.save()
        w.store.syncDerived()

        let since = try XCTUnwrap(w.store.trackingSince)
        XCTAssertEqual(since, today - 3)

        let records = w.store.consistency
        XCTAssertEqual(records.count, 14)
        for record in records {
            // A routine created on day X existed on day X — tracking starts there.
            XCTAssertEqual(record.tracked, record.day >= since, "\(record.day)")
        }
        XCTAssertFalse(try XCTUnwrap(records.first).tracked)
        XCTAssertTrue(try XCTUnwrap(records.last).tracked)
        XCTAssertTrue(records.filter { !$0.tracked }.allSatisfy { $0.completed == 0 })
    }

    /// A completed 1×/day rest day renders full, not half. The alternative calls a
    /// finished session a partial failure because a different routine asks for two.
    func testConsistencyTargetComesFromTheLogsOwnSessionsPerDayTarget() throws {
        let w = try makeWorld()
        let today = DayStamp.today()
        let template = try XCTUnwrap(w.store.create(.starter))   // sessionsPerDay 2
        try insertLog(w, template: template, day: today, target: 1)

        let record = try XCTUnwrap(w.store.consistency.last)
        XCTAssertEqual(record.day, today)
        XCTAssertEqual(record.target, 1)
        XCTAssertEqual(record.completed, 1)
        XCTAssertEqual(record.fraction, 1.0, accuracy: 0.0001)

        XCTAssertEqual(DayRecord(day: today, completed: 3, target: 2, tracked: true, climb: nil).fraction,
                       1.0, accuracy: 0.0001, "overshoot never draws past full")
        XCTAssertEqual(DayRecord(day: today, completed: 1, target: 0, tracked: true, climb: nil).fraction,
                       0.0, accuracy: 0.0001, "nothing divides by zero")
    }

    // MARK: - Climbing sessions

    /// THE RULE. A day at the gym completes the day outright — the whole reason the
    /// feature exists is that scoring it as a miss was the app lying about the week.
    func testAClimbCompletesTheDayOnItsOwn() throws {
        let w = try makeWorld()
        let saved = try XCTUnwrap(w.store.save(draft("Daily", grips: [GripSpec()])))
        saved.sessionsPerDay = 2
        try w.context.save()
        w.store.syncDerived()
        XCTAssertFalse(w.store.isDoneForToday(saved), "two hangs are owed")

        XCTAssertNotNil(w.store.recordLoggedSession(.climbLimit))

        XCTAssertTrue(w.store.isDoneForToday(saved))
        XCTAssertEqual(w.store.climbToday, .climbLimit)
        XCTAssertEqual(w.store.completed(saved), 0,
                       "a climb settles the day WITHOUT pretending a hang session happened")
    }

    /// Volume completes it too — Nuri's call (2026-08-05). The style is recorded for
    /// reading the week back, not to change the arithmetic.
    func testAVolumeClimbAlsoCompletesTheDay() throws {
        let w = try makeWorld()
        let saved = try XCTUnwrap(w.store.save(draft("Daily", grips: [GripSpec()])))
        saved.sessionsPerDay = 2
        try w.context.save()
        w.store.syncDerived()

        XCTAssertNotNil(w.store.recordLoggedSession(.climbVolume))
        XCTAssertTrue(w.store.isDoneForToday(saved))
    }

    /// An extra hang session after a climb still LOGS and still counts — the day is
    /// complete, not closed. "Offered, never demanded."
    func testAHangSessionAfterAClimbStillCounts() throws {
        let w = try makeWorld()
        let saved = try XCTUnwrap(w.store.save(draft("Daily", grips: [GripSpec()])))
        XCTAssertNotNil(w.store.recordLoggedSession(.climbVolume))
        try insertLog(w, template: saved, day: w.clock.today)

        XCTAssertEqual(w.store.completed(saved), 1)
        XCTAssertTrue(w.store.isDoneForToday(saved))
        XCTAssertTrue(w.store.completionText(saved).contains("plus a hang session"))
    }

    /// The routine CARD has to agree with the rest of the app. Before this, a day
    /// completed by a climb showed the chooser rail's dot as done and the sentence as
    /// "at the gym today", while the card still offered a primary "Start first session"
    /// with no checkmark — one fact, three surfaces, two answers.
    func testTheRoutineCardReadsTheDayAsMetAfterAClimb() throws {
        let w = try makeWorld()
        let saved = try XCTUnwrap(w.store.save(draft("Daily", grips: [GripSpec()])))
        saved.sessionsPerDay = 2
        try w.context.save()
        w.store.syncDerived()
        XCTAssertFalse(w.store.summary(for: saved).targetMet)

        XCTAssertNotNil(w.store.recordLoggedSession(.climbVolume))

        let summary = w.store.summary(for: saved)
        XCTAssertTrue(summary.targetMet)
        XCTAssertEqual(summary.climbedToday, .climbVolume)
        XCTAssertEqual(summary.completedToday, 0, "and it still knows no hangs happened")
    }

    /// The hardest climb names the day: a limit session followed by an easy evening is
    /// remembered as the limit session.
    func testTheHardestClimbNamesTheDay() throws {
        let w = try makeWorld()
        XCTAssertNotNil(w.store.recordLoggedSession(.climbVolume))
        XCTAssertNotNil(w.store.recordLoggedSession(.climbLimit))
        XCTAssertEqual(w.store.climbToday, .climbLimit)
    }

    /// A climb must not be counted as a hang session anywhere — not in the per-routine
    /// tally, and not in the consistency record's `completed`, which is what draws the
    /// "1 of 2" fill.
    func testAClimbIsNeverCountedAsAHangSession() throws {
        let w = try makeWorld()
        let saved = try XCTUnwrap(w.store.save(draft("Daily", grips: [GripSpec()])))
        saved.sessionsPerDay = 2
        try w.context.save()
        XCTAssertNotNil(w.store.recordLoggedSession(.climbLimit))

        let today = try XCTUnwrap(w.store.consistency.last)
        XCTAssertEqual(today.completed, 0, "no hang session happened")
        XCTAssertEqual(today.climb, .climbLimit)
        XCTAssertEqual(today.fraction, 1.0, accuracy: 0.0001,
                       "but the cell is FULL — the day is complete")
    }

    /// Logging yesterday's session is the realistic case: you climb at night and reach
    /// for the phone the next morning.
    func testAClimbCanBeLoggedForYesterday() throws {
        let w = try makeWorld()
        XCTAssertNotNil(w.store.recordLoggedSession(.climbVolume, daysAgo: 1))

        XCTAssertNil(w.store.climbToday, "yesterday's session does not complete today")
        let yesterday = try XCTUnwrap(w.store.consistency.dropLast().last)
        XCTAssertEqual(yesterday.climb, .climbVolume)
    }

    /// The hand logger may create only climb kinds and a manual hang. Runner hangs and
    /// benchmark rows have their own writers, so accepting either here would create a
    /// day state without the event that normally owns it.
    func testRecordLoggedSessionAcceptsHandKindsAndRefusesRunnerKinds() throws {
        let w = try makeWorld()
        XCTAssertNotNil(w.store.recordLoggedSession(.climbVolume))
        XCTAssertNotNil(w.store.recordLoggedSession(.climbLimit))
        let manualHang = try XCTUnwrap(w.store.recordLoggedSession(.hangManual,
                                                                   minutes: 120,
                                                                   rpe: .hard,
                                                                   fingerStrain: .taxed))
        XCTAssertNil(w.store.recordLoggedSession(.hang))
        XCTAssertNil(w.store.recordLoggedSession(.benchmark))
        XCTAssertEqual(workoutLogs(w).count, 3)
        XCTAssertEqual(manualHang.kind, .hangManual)
        XCTAssertEqual(manualHang.sessionMinutes, 120)
        XCTAssertEqual(manualHang.grade, .hard)
        XCTAssertEqual(manualHang.fingerStrain, .taxed)
    }

    /// A hand-logged hang fills ONE of the day's slots — on the calendar and on Today's
    /// card alike. The two used to disagree: the grid folds on `countsAsHang` while the
    /// card read the routine-attributed map, and a hand-logged hang has no routine to
    /// attribute to, so the grid drew "1 of 2" while the card said "0 of 2".
    func testAHandLoggedHangCountsOnBothTheGridAndTheCard() throws {
        let w = try makeWorld()
        let saved = try XCTUnwrap(w.store.save(draft("Daily", grips: [GripSpec()])))
        saved.sessionsPerDay = 2
        try w.context.save()
        XCTAssertNotNil(w.store.recordLoggedSession(.hangManual))

        XCTAssertEqual(w.store.consistency.last?.completed, 1, "the grid counts it")
        XCTAssertEqual(w.store.completed(saved), 1, "and so does the card")
        XCTAssertFalse(w.store.isDoneForToday(saved), "one of two is not done yet")
        XCTAssertNil(w.store.climbToday, "it is a session, not a day-settling climb")
    }

    /// Meeting the target entirely through hand-logged hangs still ends the day — which
    /// is what stops the evening reminder firing on a night already trained.
    func testHandLoggedHangsCanMeetTheDaysTargetOnTheirOwn() throws {
        let w = try makeWorld()
        let saved = try XCTUnwrap(w.store.save(draft("Daily", grips: [GripSpec()])))
        saved.sessionsPerDay = 2
        try w.context.save()

        XCTAssertNotNil(w.store.recordLoggedSession(.hangManual))
        XCTAssertFalse(w.store.isDoneForToday(saved))
        XCTAssertNotNil(w.store.recordLoggedSession(.hangManual))

        XCTAssertEqual(w.store.completed(saved), 2)
        XCTAssertTrue(w.store.isDoneForToday(saved), "two of two, by hand, is still done")
        XCTAssertNil(w.store.climbToday, "and still not a climb")
    }

    /// A hand-logged hang is not attributable to any ONE routine, so it counts for every
    /// routine's day — the same rule a climb already follows.
    func testAHandLoggedHangCountsForEveryRoutine() throws {
        let w = try makeWorld()
        let daily = try XCTUnwrap(w.store.save(draft("Daily", grips: [GripSpec()])))
        let maxDay = try XCTUnwrap(w.store.save(draft("Max day", grips: [GripSpec()])))
        // A draft defaults to two a day; one each is what makes a single hang decisive.
        daily.sessionsPerDay = 1
        maxDay.sessionsPerDay = 1
        try w.context.save()
        XCTAssertNotNil(w.store.recordLoggedSession(.hangManual))

        XCTAssertTrue(w.store.isDoneForToday(daily))
        XCTAssertTrue(w.store.isDoneForToday(maxDay))
    }

    /// Every log written before climbing existed decodes as a hang session, so an
    /// upgraded install behaves exactly as it did.
    func testAnExistingLogIsAHangSession() throws {
        let w = try makeWorld()
        let saved = try XCTUnwrap(w.store.save(draft("Daily", grips: [GripSpec()])))
        let log = try insertLog(w, template: saved, day: w.clock.today)

        XCTAssertEqual(log.kind, .hang)
        XCTAssertEqual(log.kindRaw, "hang")
        XCTAssertNil(w.store.climbToday)
    }

    /// Undo restores the KIND. Without it, undoing a deleted climb would put back a
    /// hangboard session and the day it completed would quietly become incomplete.
    func testUndoingADeletedClimbRestoresItAsAClimb() throws {
        let w = try makeWorld()
        let climb = try XCTUnwrap(w.store.recordLoggedSession(.climbLimit))
        XCTAssertTrue(w.store.deleteSession(climb))
        XCTAssertNil(w.store.climbToday)

        w.store.undoDeleteSession()
        XCTAssertEqual(w.store.climbToday, .climbLimit)
        let restored = try XCTUnwrap(workoutLogs(w).first)
        XCTAssertEqual(restored.kind, .climbLimit)
    }

    // MARK: - Recent grips

    func testRecentGripsAreDeduplicatedByKeyMostRecentFirstAndCappedAtSix() throws {
        let w = try makeWorld()
        let a = GripSpec(edgeMM: 20, fingers: .four, position: .halfCrimp)
        let b = GripSpec(edgeMM: 18, fingers: .four, position: .halfCrimp)
        let c = GripSpec(edgeMM: 16, fingers: .four, position: .halfCrimp)
        let d = GripSpec(edgeMM: 14, fingers: .four, position: .halfCrimp)

        _ = w.store.create(draft("Older", grips: [a, b]))
        _ = w.store.create(draft("Newer", grips: [c, d, a]))

        let recent = w.store.recentGrips
        XCTAssertEqual(Set(recent.map(\.key)).count, recent.count, "deduplicated by canonical key")
        XCTAssertEqual(Set(recent.map(\.key)), Set([a, b, c, d].map(\.key)))
        let indexOf = { (grip: GripSpec) in recent.firstIndex { $0.key == grip.key } }
        XCTAssertLessThan(try XCTUnwrap(indexOf(c)), try XCTUnwrap(indexOf(b)),
                          "the routine touched most recently comes first")

        _ = w.store.create(draft("Newest", grips: (1...6).map {
            GripSpec(edgeMM: 20 + $0, fingers: .frontTwo, position: .openHand)
        }))
        XCTAssertEqual(w.store.recentGrips.count, 6, "the rail holds six")
        XCTAssertEqual(Set(w.store.recentGrips.map(\.key)).count, 6)
    }

    /// The RECENT rail must never be blank on a first build — an empty rail on the
    /// very first set row is a dead end where the fastest path should be.
    func testRecentGripsFallBackToASeedPaletteWhenThereIsNoHistory() throws {
        let w = try makeWorld()
        XCTAssertEqual(routines(w).count, 0)

        let seeded = w.store.recentGrips
        XCTAssertFalse(seeded.isEmpty)
        XCTAssertLessThanOrEqual(seeded.count, 6)
        XCTAssertEqual(Set(seeded.map(\.key)).count, seeded.count)
    }

    // MARK: - Maxes

    /// Append-only, so "current" means the NEWEST record for that key — not the
    /// biggest. A max that has come down is still the truth about today.
    func testRecordMaxAppendsAndCurrentMaxIsTheNewestForThatKey() throws {
        let w = try makeWorld()
        let grip = GripSpec(edgeMM: 20, fingers: .four, position: .halfCrimp)
        let other = GripSpec(edgeMM: 20, fingers: .frontTwo, position: .halfCrimp)

        let aWeekAgo = MaxRecord(grip: grip, kg: 44, source: .measured,
                                 recordedAt: Date.now.addingTimeInterval(-7 * 86_400))
        w.context.insert(aWeekAgo)
        try w.context.save()
        w.store.syncDerived()
        XCTAssertEqual(try XCTUnwrap(w.store.currentMax(for: grip)), 44, accuracy: 0.0001)

        XCTAssertTrue(w.store.recordMax(40, for: grip, source: .manual))
        XCTAssertEqual(try XCTUnwrap(w.store.currentMax(for: grip)), 40, accuracy: 0.0001)
        XCTAssertNil(w.store.currentMax(for: other), "a different key must not leak across")

        let stored = try w.context.fetch(FetchDescriptor<MaxRecord>())
        XCTAssertEqual(stored.count, 2, "recording a max inserts a row rather than mutating one")
        XCTAssertEqual(Set(stored.map(\.gripKey)), ["20|IMRL|halfCrimp"])
    }

    /// THE regression that would be invisible on screen: "current" is per grip AND per
    /// hand. Folded on the grip alone, the right-hand max recorded second would become
    /// the grip's current max and the left one would drop into history — one of your two
    /// hands silently losing its number, and every left-hand target quietly moving with
    /// it.
    func testLeftAndRightMaxesAreSeparateCurrentRecords() throws {
        let w = try makeWorld()
        let grip = GripSpec(edgeMM: 20, fingers: .four, position: .halfCrimp)

        XCTAssertTrue(w.store.recordMax(40, for: grip, side: .left))
        XCTAssertTrue(w.store.recordMax(36, for: grip, side: .right))

        XCTAssertEqual(try XCTUnwrap(w.store.currentMax(for: grip, side: .left)), 40, accuracy: 0.0001)
        XCTAssertEqual(try XCTUnwrap(w.store.currentMax(for: grip, side: .right)), 36, accuracy: 0.0001)
        XCTAssertNil(w.store.currentMax(for: grip),
                     "neither hand answers for a two-handed pull")

        XCTAssertEqual(w.store.currentMaxes.count, 2, "two current records, not one")
    }

    /// A both-hands max still covers every hand, so nothing changes for anyone who never
    /// touches the picker — and a later side-specific max overrides only that side.
    func testABothHandsMaxCoversEitherHandUntilThatHandHasItsOwn() throws {
        let w = try makeWorld()
        let grip = GripSpec(edgeMM: 20, fingers: .four, position: .halfCrimp)

        XCTAssertTrue(w.store.recordMax(40, for: grip))
        XCTAssertEqual(try XCTUnwrap(w.store.currentMax(for: grip, side: .left)), 40, accuracy: 0.0001)
        XCTAssertEqual(try XCTUnwrap(w.store.currentMax(for: grip, side: .right)), 40, accuracy: 0.0001)

        XCTAssertTrue(w.store.recordMax(36, for: grip, side: .right))
        XCTAssertEqual(try XCTUnwrap(w.store.currentMax(for: grip, side: .right)), 36, accuracy: 0.0001)
        XCTAssertEqual(try XCTUnwrap(w.store.currentMax(for: grip, side: .left)), 40,
                       accuracy: 0.0001, "the left hand still follows the both-hands max")
    }

    func testMaxTableMirrorsCurrentMaxes() throws {
        let w = try makeWorld()
        let grip = GripSpec(edgeMM: 20, fingers: .four, position: .halfCrimp)
        XCTAssertTrue(w.store.recordMax(40, for: grip, side: .left))
        XCTAssertTrue(w.store.recordMax(36, for: grip, side: .right))

        XCTAssertEqual(w.store.maxTable.exact(grip: grip.key, side: .left), 40)
        XCTAssertEqual(w.store.maxTable.exact(grip: grip.key, side: .right), 36)
        XCTAssertTrue(w.store.maxTable.differsByHand(grip: grip.key))
    }

    /// Every record written before the hand column existed decodes as `.both`, which is
    /// what keeps an upgraded install behaving exactly as it did.
    func testAMaxDefaultsToBothHands() throws {
        let w = try makeWorld()
        let grip = GripSpec(edgeMM: 20, fingers: .four, position: .halfCrimp)
        XCTAssertTrue(w.store.recordMax(40, for: grip))

        let stored = try XCTUnwrap(try w.context.fetch(FetchDescriptor<MaxRecord>()).first)
        XCTAssertEqual(stored.side, .both)
        XCTAssertEqual(stored.sideRaw, "both")
    }

    func testSuggestedBandIsNilWithoutAMaxForThatExactGrip() throws {
        let w = try makeWorld()
        let fourFinger = GripSpec(edgeMM: 20, fingers: .four, position: .halfCrimp)
        let frontTwo = GripSpec(edgeMM: 20, fingers: .frontTwo, position: .halfCrimp)

        XCTAssertTrue(w.store.recordMax(40, for: fourFinger))
        XCTAssertEqual(w.store.suggestedBand(for: fourFinger), 8.0...12.0)
        XCTAssertNil(w.store.suggestedBand(for: frontTwo),
                     "front two is a different key, so it has no max of its own yet")
    }

    // MARK: - Live routine names

    /// History resolves a session's routine name through this rather than showing the
    /// copy frozen into the log, so renaming a routine renames it everywhere at once.
    func testRenamingARoutineMovesItsPublishedName() throws {
        let w = try makeWorld()
        var draft = RoutineDraft.starter
        draft.plan.name = "Daily no-hangs"
        let saved = try XCTUnwrap(w.store.create(draft))
        XCTAssertEqual(w.store.routineNames[saved.id], "Daily no-hangs")

        var renamed = w.store.draft(editing: saved)
        renamed.plan.name = "Morning ladder"
        XCTAssertTrue(w.store.update(saved, with: renamed))

        XCTAssertEqual(w.store.routineNames[saved.id], "Morning ladder")
    }

    /// And a DELETED routine drops out, which is what sends History back to the frozen
    /// name in the log — the reason that column still exists.
    func testADeletedRoutineLeavesNoLiveName() throws {
        let w = try makeWorld()
        let saved = try XCTUnwrap(w.store.create(.starter))
        XCTAssertNotNil(w.store.routineNames[saved.id])

        XCTAssertTrue(w.store.delete(saved))
        XCTAssertNil(w.store.routineNames[saved.id])
    }

    // MARK: - The gated max refold

    /// `syncDerived` no longer refetches every `MaxRecord` ever written on every save —
    /// only writes that actually touch one ask for the refold. This is the assertion that
    /// makes that safe: the three max-derived values must survive every OTHER kind of
    /// write untouched. If a skip ever cleared or staled them, every percent-of-max band
    /// in the app would resolve against nothing.
    func testTheMaxTableSurvivesEveryWriteThatTouchesNoMax() throws {
        let w = try makeWorld()
        let grip = GripSpec(edgeMM: 20, fingers: .four, position: .halfCrimp)
        XCTAssertTrue(w.store.recordMax(42, for: grip, source: .measured))
        let measuredAt = try XCTUnwrap(w.store.lastMeasuredMaxAt)

        // One of each write that now passes `maxesChanged: false`.
        let created = try XCTUnwrap(w.store.create(.starter))
        w.store.makePrimary(created)
        XCTAssertTrue(w.store.update(created, with: w.store.draft(editing: created)))
        let log = try XCTUnwrap(w.store.recordSession(plan: w.store.plan(for: created),
                                                      template: created, reps: [],
                                                      startedAt: .now, finishedAt: .now,
                                                      rpe: nil))
        XCTAssertTrue(w.store.deleteSession(log))
        XCTAssertNotNil(w.store.recordLoggedSession(.climbVolume))
        XCTAssertTrue(w.store.delete(created))

        XCTAssertEqual(try XCTUnwrap(w.store.currentMax(for: grip)), 42, accuracy: 0.0001)
        XCTAssertEqual(w.store.currentMaxes.count, 1)
        // `max`, not `exact`: this is the lookup the engine makes when it resolves a
        // percent band, so it is the one that has to still answer.
        XCTAssertEqual(w.store.maxTable.max(grip: grip.key, side: .left), 42)
        XCTAssertEqual(w.store.lastMeasuredMaxAt, measuredAt)
    }

    /// The other direction: the two writes that DO move a max still refold, so deleting
    /// the newest one falls back to the record underneath rather than leaving the deleted
    /// number on screen.
    func testDeletingAMaxRefoldsBackToThePreviousOne() throws {
        let w = try makeWorld()
        let grip = GripSpec(edgeMM: 20, fingers: .four, position: .halfCrimp)
        w.context.insert(MaxRecord(grip: grip, kg: 38, source: .measured,
                                   recordedAt: Date.now.addingTimeInterval(-7 * 86_400)))
        try w.context.save()
        w.store.syncDerived()

        XCTAssertTrue(w.store.recordMax(44, for: grip, source: .manual))
        XCTAssertEqual(try XCTUnwrap(w.store.currentMax(for: grip)), 44, accuracy: 0.0001)

        let newest = try XCTUnwrap(w.store.currentMaxes.values.first { $0.gripKey == grip.key })
        XCTAssertTrue(w.store.deleteMax(newest))
        XCTAssertEqual(try XCTUnwrap(w.store.currentMax(for: grip)), 38, accuracy: 0.0001)
    }

    // MARK: - Rescaling typed kg targets

    private func kgTargetDraft(named name: String,
                               _ first: GripSpec, _ second: GripSpec) -> RoutineDraft {
        var draft = RoutineDraft.blank(named: name)
        var a = SetPlan(grip: first, repsPerSide: 4)
        a.targetLoKg = 20
        a.targetHiKg = 24
        var b = SetPlan(grip: second, repsPerSide: 4)
        b.targetLoKg = 10
        b.targetHiKg = 12
        draft.plan.sets = [a, b]
        return draft
    }

    func testScalingKgTargetsMovesEveryMatchingSetAndLeavesTheRestAlone() throws {
        let w = try makeWorld()
        let crimp = GripSpec(edgeMM: 20, fingers: .four, position: .halfCrimp)
        let drag = GripSpec(edgeMM: 20, fingers: .frontTwo, position: .drag)

        let morning = try XCTUnwrap(w.store.create(kgTargetDraft(named: "Morning", crimp, drag)))
        let evening = try XCTUnwrap(w.store.create(kgTargetDraft(named: "Evening", crimp, drag)))
        let declined = try XCTUnwrap(w.store.create(kgTargetDraft(named: "Rest day", crimp, drag)))

        XCTAssertTrue(w.store.scaleKgTargets(grip: crimp, ratio: 1.1,
                                             routineIDs: [morning.id, evening.id]))

        for template in [morning, evening] {
            let sets = w.store.plan(for: template).sets
            // Half-kilogram rounding, same as the percent path: 24 × 1.1 = 26.4 → 26.5.
            XCTAssertEqual(sets[0].targetLoKg, 22, "\(template.name) lower bound")
            XCTAssertEqual(sets[0].targetHiKg, 26.5, "\(template.name) upper bound")
            XCTAssertEqual(sets[1].targetLoKg, 10, "a set on another grip never moves")
            XCTAssertEqual(sets[1].targetHiKg, 12)
        }
        XCTAssertEqual(w.store.plan(for: declined).sets[0].targetLoKg, 20,
                       "a routine the user left out of the offer never moves")
    }

    /// It used to route through `save(_:)`, the builder's entry point, which clears the
    /// rescue stash on success. So accepting a rescale from the Maxes tab silently threw
    /// away a half-built routine someone had left unsaved in the builder.
    func testScalingKgTargetsLeavesTheBuilderRescueCopyAlone() throws {
        let w = try makeWorld()
        let crimp = GripSpec(edgeMM: 20, fingers: .four, position: .halfCrimp)
        let drag = GripSpec(edgeMM: 20, fingers: .frontTwo, position: .drag)
        let saved = try XCTUnwrap(w.store.create(kgTargetDraft(named: "Morning", crimp, drag)))

        w.store.stashDraft(RoutineDraft.blank(named: "Half-written"))
        XCTAssertTrue(w.store.scaleKgTargets(grip: crimp, ratio: 1.1, routineIDs: [saved.id]))

        XCTAssertEqual(w.store.restoreDraft()?.plan.name, "Half-written")
    }

    func testScalingKgTargetsRefusesANonsenseRatioAndIgnoresAnUnknownRoutine() throws {
        let w = try makeWorld()
        let crimp = GripSpec(edgeMM: 20, fingers: .four, position: .halfCrimp)
        let drag = GripSpec(edgeMM: 20, fingers: .frontTwo, position: .drag)
        let saved = try XCTUnwrap(w.store.create(kgTargetDraft(named: "Morning", crimp, drag)))

        XCTAssertFalse(w.store.scaleKgTargets(grip: crimp, ratio: 0, routineIDs: [saved.id]))
        XCTAssertFalse(w.store.scaleKgTargets(grip: crimp, ratio: .nan, routineIDs: [saved.id]))
        XCTAssertEqual(w.store.plan(for: saved).sets[0].targetLoKg, 20, "nothing moved")

        // Deleted on another device between the offer and the tap: there is nothing to
        // scale, and that is not a failure.
        XCTAssertTrue(w.store.scaleKgTargets(grip: crimp, ratio: 1.1, routineIDs: [UUID()]))
        XCTAssertEqual(w.store.plan(for: saved).sets[0].targetLoKg, 20)
    }

    // MARK: - Failure handling

    /// The sheet used to dismiss unconditionally, so a failed write closed the form
    /// over a routine that no longer existed. Memory must match disk, and the caller
    /// must be told.
    func testSaveFailureRollsBackAndPublishesSaveError() throws {
        let w = try makeWorld(allowsSave: false)

        let created = w.store.create(.starter)

        XCTAssertNil(created, "if this passes, allowsSave:false is no longer a save-failure seam")
        XCTAssertNotNil(w.store.saveError)
        XCTAssertEqual(routines(w).count, 0, "the rolled-back insert must not linger in memory")
    }

    /// A read that FAILED is not the same fact as "there are no routines". Collapsing
    /// the two is what let one bad read blank the Today card in the sibling app.
    ///
    /// NOTE: the fetch-failure path itself has no test seam — SwiftData offers no way
    /// to make `context.fetch` throw without a schema mismatch, which raises an
    /// uncatchable ObjC exception and would take the whole bundle down. What is
    /// reachable, and asserted here, is the same published-state invariant under the
    /// one refusal a test CAN drive: a rejected mutation must leave the derived world
    /// exactly as it was, never blanked.
    func testAFailedFetchDoesNotPublishAnEmptyWorld() throws {
        let w = try makeWorld()
        let today = DayStamp.today()
        let template = try XCTUnwrap(w.store.create(.starter))
        try insertLog(w, template: template, day: today)
        try insertLog(w, template: template, day: today)

        let completionsBefore = w.store.completionsToday
        let consistencyBefore = w.store.consistency
        XCTAssertEqual(completionsBefore[template.id], 2)
        XCTAssertEqual(consistencyBefore.count, 14)

        // A template that is not in any context — the shape a CloudKit merge leaves
        // behind — is refused, and the refusal must not disturb what is published.
        let orphan = SessionTemplate(draft: .blank(named: "Orphan"), sortIndex: 0)
        XCTAssertFalse(w.store.update(orphan, with: .starter))
        XCTAssertFalse(w.store.delete(orphan))

        XCTAssertEqual(w.store.completionsToday, completionsBefore)
        XCTAssertEqual(w.store.consistency, consistencyBefore)
        XCTAssertEqual(w.store.consistency.count, 14)
    }

    /// Mutating an object a CloudKit merge already deleted throws an ObjC exception no
    /// Swift `catch` can reach, so the guard has to be up front — and a refusal is not
    /// an error worth alarming the user about.
    func testMutatingAFaultedTemplateIsRefusedRatherThanCrashing() throws {
        let w = try makeWorld()
        let orphan = SessionTemplate(draft: .blank(named: "Orphan"), sortIndex: 0)
        XCTAssertNil(orphan.modelContext)

        XCTAssertFalse(w.store.update(orphan, with: .starter))
        XCTAssertNil(w.store.duplicate(orphan))
        XCTAssertFalse(w.store.delete(orphan))

        XCTAssertNil(w.store.saveError)
        XCTAssertNil(w.store.lastDeleted)
        XCTAssertEqual(routines(w).count, 0)
    }

    // MARK: - Device-local suggestions and draft rescue

    /// Rung 2 of Today's selection rule is device-local and expires with the day, so a
    /// new morning re-asserts the primary routine rather than reopening last night's.
    func testSuggestedRoutineExpiresWithTheDay() throws {
        let w = try makeWorld()
        let today = DayStamp.today()
        let daily = try XCTUnwrap(w.store.create(.starter))
        let rest = try XCTUnwrap(w.store.create(.blank(named: "Rest day")))
        XCTAssertNil(w.store.suggestedRoutineID)

        w.store.noteSessionStarted(rest)
        XCTAssertEqual(w.store.suggestedRoutineID, rest.id)
        XCTAssertNotEqual(w.store.suggestedRoutineID, daily.id)

        w.clock.advance(to: today + 1)
        w.store.refreshIfDayChanged()
        XCTAssertNil(w.store.suggestedRoutineID)
    }

    func testDraftStashIsClearedOnSaveAndOnCancel() throws {
        let w = try makeWorld()
        let rescued = RoutineDraft.blank(named: "Rescue me")

        w.store.stashDraft(rescued)
        XCTAssertEqual(w.store.restoreDraft()?.plan.name, "Rescue me")
        w.store.save(rescued)
        XCTAssertNil(w.store.restoreDraft(), "a saved draft has nothing left to rescue")

        w.store.stashDraft(rescued)
        XCTAssertNotNil(w.store.restoreDraft())
        w.store.clearDraft()
        XCTAssertNil(w.store.restoreDraft(), "cancel discards it too")
    }

    // MARK: - Draft normalization

    /// Lowering the count must not destroy a time the user customised: 19:15 comes back
    /// as 19:15, not as the ladder's 19:00.
    func testLoweringSessionsPerDayParksTimesAndRaisingRestoresThem() {
        var draft = RoutineDraft.starter
        draft.reminders = [ReminderTime(hour: 8, minute: 0), ReminderTime(hour: 19, minute: 15)]

        draft.setSessionsPerDay(1)
        XCTAssertEqual(draft.sessionsPerDay, 1)
        XCTAssertEqual(draft.reminders, [ReminderTime(hour: 8, minute: 0)])
        XCTAssertEqual(draft.parkedReminders, [ReminderTime(hour: 19, minute: 15)])

        draft.setSessionsPerDay(2)
        XCTAssertEqual(draft.reminders, [ReminderTime(hour: 8, minute: 0),
                                         ReminderTime(hour: 19, minute: 15)])
        XCTAssertEqual(draft.parkedReminders, [])

        draft.setSessionsPerDay(9)
        XCTAssertEqual(draft.sessionsPerDay, 4, "clamped 1...4")
        draft.setSessionsPerDay(0)
        XCTAssertEqual(draft.sessionsPerDay, 1)
    }

    func testNormalizedDedupesAndSortsRemindersAndSubstitutesAnEmptyName() {
        var draft = RoutineDraft.blank()
        draft.plan.name = "   "
        draft.reminders = [ReminderTime(hour: 19, minute: 0),
                           ReminderTime(hour: 8, minute: 0),
                           ReminderTime(hour: 8, minute: 0)]
        var upsideDown = SetPlan(grip: GripSpec(edgeMM: 20, fingers: .four, position: .halfCrimp),
                                 repsPerSide: 4)
        upsideDown.targetLoKg = 12
        upsideDown.targetHiKg = 8
        draft.plan.sets = [upsideDown,
                           SetPlan(grip: GripSpec(edgeMM: 18, fingers: .frontTwo, position: .drag),
                                   repsPerSide: 0)]
        draft.sessionsPerDay = 9

        // The computed band is already the right way up before normalization…
        XCTAssertEqual(upsideDown.targetBand, 8.0...12.0)

        let clean = draft.normalized
        XCTAssertEqual(clean.plan.name, "Daily no-hangs")
        XCTAssertEqual(clean.reminders, [ReminderTime(hour: 8, minute: 0),
                                         ReminderTime(hour: 19, minute: 0)])
        XCTAssertEqual(clean.plan.sets.count, 1, "a zero-rep set is not a routine row")
        // …and normalization is what stops it from being STORED inverted.
        XCTAssertEqual(clean.plan.sets[0].targetLoKg, 8)
        XCTAssertEqual(clean.plan.sets[0].targetHiKg, 12)
        XCTAssertEqual(clean.sessionsPerDay, 4)
        XCTAssertNil(clean.validationIssue)
    }
}

// MARK: - The summary's edge line and signature grip

/// Pure value tests — `RoutineSummary` derives both from its own ladder, so no store
/// world is needed and none is built.
@MainActor
final class RoutineSummaryValueTests: XCTestCase {

    private func summary(edges: [Int], fingers: [FingerSet]? = nil,
                         reps: [Int]? = nil) -> RoutineSummary {
        let sets = fingers ?? Array(repeating: FingerSet.four, count: edges.count)
        let pulls = reps ?? Array(repeating: 1, count: edges.count)
        let ladder = zip(zip(edges, sets), pulls).enumerated().map { index, pair in
            LadderRung(id: index,
                       grip: GripSpec(edgeMM: pair.0.0, fingers: pair.0.1),
                       repsPerSide: pair.1)
        }
        return RoutineSummary(id: UUID(), name: "T", ladder: ladder,
                              setCount: ladder.count, totalReps: ladder.count,
                              sharedEdgeMM: Set(edges).count == 1 ? edges.first : nil,
                              estimatedSeconds: 60, sessionsPerDay: 1,
                              completedToday: 0, nextReminder: nil)
    }

    func testAOneEdgeRoutineStatesTheEdgePlainly() {
        XCTAssertEqual(summary(edges: [20, 20, 20]).edgeLine, "20 mm")
    }

    /// The span runs in LADDER order — "20–10" for a protocol that starts deep and
    /// thins out (Nuri's own phrasing of the fix) — because it describes the routine's
    /// direction, not an interval on a number line.
    func testAMixedLadderStatesItsSpanInLadderOrder() {
        XCTAssertEqual(summary(edges: [20, 15, 10]).edgeLine, "20–10 mm")
        XCTAssertEqual(summary(edges: [10, 15, 20]).edgeLine, "10–20 mm")
    }

    /// A first edge that is neither extreme has no directional claim, so the span
    /// falls back to ascending rather than inventing one.
    func testAFirstEdgeThatIsNeitherExtremeFallsBackToAscending() {
        XCTAssertEqual(summary(edges: [15, 20, 10]).edgeLine, "10–20 mm")
    }

    /// The old behaviour this replaces: `sharedEdgeMM` DROPPED the edge from
    /// `metaLine` the moment sets disagreed, which read as the app not knowing its
    /// own routine. The sentence now opens with the span.
    func testMetaLineCarriesTheSpanInsteadOfGoingSilent() {
        XCTAssertTrue(summary(edges: [20, 10]).metaLine.hasPrefix("20–10 mm · "))
    }

    func testTheSignatureGripIsTheOneMostPullsTrain() {
        let s = summary(edges: [20, 20, 20],
                        fingers: [.frontTwo, .four, .frontTwo])
        XCTAssertEqual(s.signatureFingers, .frontTwo)
    }

    /// **Weighted by pulls, never by set count.** Nuri's Daily burn tapers through the
    /// small grips as short sets — two front-2 and two middle-2 SETS against one
    /// four-finger set — and on its first hardware day the card called his
    /// mostly-four-finger routine a two-finger one. Twelve four-finger pulls outweigh
    /// six front-2 pulls, whatever the set count says.
    func testShortTaperSetsCannotOutvoteThePullMass() {
        let s = summary(edges: [20, 20, 20, 20, 10, 10],
                        fingers: [.four, .frontThree, .frontTwo, .middleTwo, .frontTwo, .middleTwo],
                        reps: [6, 6, 2, 2, 1, 1])
        XCTAssertEqual(s.signatureFingers, .four)
    }

    /// A tie goes to the ladder's FIRST rung — the grip the session opens on — so the
    /// mark cannot flip between builds over dictionary ordering.
    func testASignatureTieGoesToTheOpeningGrip() {
        let s = summary(edges: [20, 20],
                        fingers: [.backTwo, .four])
        XCTAssertEqual(s.signatureFingers, .backTwo)
    }

    func testAnEmptyLadderHasNoSignatureAndNoEdgeLine() {
        XCTAssertNil(summary(edges: []).signatureFingers)
        XCTAssertNil(summary(edges: []).edgeLine)
    }
}
