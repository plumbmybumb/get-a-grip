// SPDX-License-Identifier: MPL-2.0
// Original contributions Copyright 2026 Nuri Bruner.

package run.nuri.getagrip

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import run.nuri.getagrip.data.GetAGripDatabase
import run.nuri.getagrip.data.MaxRecordEntity
import run.nuri.getagrip.data.SessionTemplateEntity
import run.nuri.getagrip.data.WorkoutLogEntity
import run.nuri.getagrip.engine.DayRecord
import run.nuri.getagrip.engine.DayStamp
import run.nuri.getagrip.engine.FingerSet
import run.nuri.getagrip.engine.FingerStrain
import run.nuri.getagrip.engine.GripPosition
import run.nuri.getagrip.engine.GripSpec
import run.nuri.getagrip.engine.HandMode
import run.nuri.getagrip.engine.LadderRung
import run.nuri.getagrip.engine.MaxSource
import run.nuri.getagrip.engine.RPE
import run.nuri.getagrip.engine.ReminderTime
import run.nuri.getagrip.engine.RepSummary
import run.nuri.getagrip.engine.RoutineDraft
import run.nuri.getagrip.engine.RoutineShare
import run.nuri.getagrip.engine.RoutineShareError
import run.nuri.getagrip.engine.RoutineSummary
import run.nuri.getagrip.engine.SessionKind
import run.nuri.getagrip.engine.SessionPlan
import run.nuri.getagrip.engine.SetPlan
import run.nuri.getagrip.engine.Side
import run.nuri.getagrip.store.DayClock
import run.nuri.getagrip.store.InMemoryRoutineSettings
import run.nuri.getagrip.store.RecordingAlarmScheduler
import run.nuri.getagrip.store.RoomStoreGateway
import run.nuri.getagrip.store.StoreGateway
import run.nuri.getagrip.store.StoreWriter
import run.nuri.getagrip.store.TemplateStore
import java.time.Instant
import java.util.UUID
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/// Everything below runs against an in-memory Room database built from the real
/// CloudKit-shaped schema, so the blob accessors, the derived recompute and the undo path
/// are exercised exactly as they are on device — only the store file is fake.
///
/// TRANSLATION NOTE (Tests/TemplateStoreTests.swift). Three things had to change:
///
/// - **Robolectric, and therefore JUnit 4.** Room needs a real SQLite and a real
///   `Context`; Robolectric's runner is a JUnit 4 runner with no JUnit 5 equivalent, so
///   these classes use `@RunWith` and ride the Vintage engine inside the same platform the
///   rest of the suite uses. `kotlin.test`'s assertions work on both.
/// - **Every mutation is `suspend`**, so each case is a `runTest`. The store's scope is an
///   `UnconfinedTestDispatcher` so the reminder replan and the undo timers it launches run
///   in the test's own time.
/// - **The rolled-back-save seam is a WRAPPER, not a read-only file.** iOS has to
///   materialise a real store, seed it, and reopen it `allowsSave: false`, and the comment
///   there explains at length why `isStoredInMemoryOnly` will not do. A gateway whose
///   `write` throws is the same refusal with none of that ceremony — and it is refusing at
///   exactly the same moment, the commit.
@RunWith(RobolectricTestRunner::class)
@OptIn(ExperimentalCoroutinesApi::class)
class TemplateStoreTests {

    private class World(
        val db: GetAGripDatabase,
        val gateway: StoreGateway,
        val clock: DayClock,
        val settings: InMemoryRoutineSettings,
        val scheduler: RecordingAlarmScheduler,
        val store: TemplateStore,
    )

    private val opened = mutableListOf<GetAGripDatabase>()

    @After
    fun tearDown() {
        opened.forEach { it.close() }
        opened.clear()
    }

    /// `allowsSave = false` refuses every WRITE while reads keep answering, which is what
    /// the two "the delete never landed" cases need: a store that cannot even be read
    /// would report the wrong failure.
    private suspend fun TestScope.makeWorld(
        allowsSave: Boolean = true,
        seeding: RoutineDraft? = null,
        seedingSession: Boolean = false,
    ): World {
        val db = GetAGripDatabase.inMemory(RuntimeEnvironment.getApplication())
        opened.add(db)
        val room = RoomStoreGateway(db, UnconfinedTestDispatcher(testScheduler))
        val gateway = if (allowsSave) room else RefusingWriteGateway(room)
        val clock = DayClock()
        // The iOS world resets five `UserDefaults` keys here because its settings store is
        // process-global; a fresh `InMemoryRoutineSettings` IS that reset.
        val settings = InMemoryRoutineSettings()
        val scheduler = RecordingAlarmScheduler()
        val store = TemplateStore(
            gateway = gateway,
            clock = clock,
            settings = settings,
            scheduler = scheduler,
            // The store's own scope, on its OWN test scheduler — deliberately not the
            // test's. Unconfined means everything without a `delay` (the reminder replan)
            // runs eagerly and in order; a scheduler nobody advances means the ten-second
            // undo windows never elapse. Sharing `testScheduler` would hand them to
            // `runTest`'s virtual clock, which skips ahead whenever the test coroutine
            // suspends — so every `store.delete(…)` would expire its own undo offer
            // before the next line could assert it was on offer.
            scope = CoroutineScope(UnconfinedTestDispatcher()),
        )
        // Seeded through the raw DAO, so it lands even on a world that refuses writes.
        seeding?.let {
            db.routines().upsert(SessionTemplateEntity.from(it.normalized, 0))
        }
        if (seedingSession) {
            val plan = RoutineDraft.starter.normalized.plan.executable
            db.logs().upsert(
                WorkoutLogEntity.from(
                    plan = plan, templateID = null, templateName = "Daily no-hangs",
                    sessionsPerDayTarget = 2, reps = emptyList(),
                    startedAt = Instant.now(), finishedAt = Instant.now(),
                    day = DayStamp.today(),
                )
            )
        }
        return World(db, gateway, clock, settings, scheduler, store)
    }

    private suspend fun routines(w: World): List<SessionTemplateEntity> =
        w.db.routines().all().sortedWith(TemplateStore.routineOrder)

    private suspend fun workoutLogs(w: World): List<WorkoutLogEntity> = w.db.logs().all()

    private suspend fun insertLog(
        w: World,
        template: SessionTemplateEntity?,
        day: DayStamp,
        target: Int = 2,
        name: String? = null,
    ): WorkoutLogEntity {
        val plan = (template?.plan ?: SessionPlan()).executable
        val rep = RepSummary(grip = plan.sets.firstOrNull()?.grip ?: GripSpec())
        val started = day.startOfDay().toInstant()
        val log = WorkoutLogEntity.from(
            plan = plan,
            templateID = template?.id,
            templateName = name ?: template?.name ?: "",
            sessionsPerDayTarget = target,
            reps = listOf(rep),
            startedAt = started,
            finishedAt = started.plusSeconds(1290),
            day = day,
        )
        w.db.logs().upsert(log)
        w.store.syncDerived()
        return log
    }

    private fun draft(name: String, grips: List<GripSpec>): RoutineDraft {
        val d = RoutineDraft.blank(name)
        return d.copy(plan = d.plan.copy(sets = grips.map { SetPlan(grip = it, repsPerSide = 3) }))
    }

    // MARK: - The prefill is a product spec

    /// Nuri's actual protocol, asserted field by field. If any of these move, the routine
    /// that greets him on first run is no longer the one he described.
    @Test
    fun prefillProducesSixSetsInNurisExactOrder() {
        val draft = RoutineDraft.starter

        assertEquals("Daily no-hangs", draft.plan.name)
        assertEquals(
            listOf(
                "20|IMRL|halfCrimp", "20|IMR|halfCrimp", "20|IM|openHand",
                "20|MR|openHand", "20|IM|fullCrimp", "20|MR|fullCrimp",
            ),
            draft.plan.sets.map { it.grip.key },
        )
        assertEquals(listOf(6, 6, 2, 2, 1, 1), draft.plan.sets.map { it.repsPerSide })
        assertEquals(HandMode.alternateEachRep, draft.plan.handMode)
        assertEquals(10, draft.plan.holdSeconds)
        assertEquals(20, draft.plan.restSeconds)
        assertEquals(60, draft.plan.setBreakSeconds)
        assertEquals(5, draft.plan.leadInSeconds)
        assertEquals(2.0, draft.plan.thresholdKg, 0.0001)
        assertEquals(2, draft.sessionsPerDay)
        assertTrue(draft.remindersEnabled)
        assertEquals(
            listOf(ReminderTime(hour = 8, minute = 0), ReminderTime(hour = 19, minute = 0)),
            draft.reminders,
        )
        assertEquals(emptyList(), draft.parkedReminders)
        assertTrue(draft.isNew)
        assertNull(draft.validationIssue)

        // ZERO per-set timing overrides: that is what makes changing one rest interval a
        // single edit across all six sets.
        assertTrue(draft.plan.sets.all { !it.overridesTiming })
        assertTrue(draft.plan.sets.all { !it.hasTarget })
    }

    /// A stored `val` would mint the six SetPlan ids once per process and hand identical
    /// ids to two routines built in one sitting.
    @Test
    fun prefillMintsFreshSetIDsOnEveryAccess() {
        val first = RoutineDraft.starter.plan.sets.map { it.id }.toSet()
        val second = RoutineDraft.starter.plan.sets.map { it.id }.toSet()
        assertEquals(6, first.size)
        assertTrue(first.intersect(second).isEmpty())
    }

    /// BLANK MEANS BLANK (Nuri, 2026-08-11). It used to seed one 20 mm four-finger
    /// half-crimp set so the list was never empty; that set was a guess presented as your
    /// routine, and deleting it was the commonest first edit.
    ///
    /// What replaces the seed as the safety net is `validationIssue`: Save stays refused,
    /// and says why, until there is a real set in here. This test pins BOTH halves.
    @Test
    fun blankDraftIsEmptyAndCannotBeSavedUntilASetExists() {
        val blank = RoutineDraft.blank()
        assertTrue(blank.plan.sets.isEmpty(), "no grip is presumed for you")
        assertEquals("My routine", blank.plan.name)
        assertEquals("Rest day", RoutineDraft.blank("Rest day").plan.name)
        assertEquals("Add at least one set with a pull in it.", blank.validationIssue)

        val withASet = blank.copy(
            plan = blank.plan.copy(
                sets = listOf(
                    SetPlan(
                        grip = GripSpec(20, FingerSet.four, GripPosition.halfCrimp),
                        repsPerSide = 6,
                    )
                )
            )
        )
        assertNull(withASet.validationIssue, "and it saves the moment one exists")
    }

    // MARK: - Create / update / save

    /// One test covering every blob accessor at once: sets, reminders, parked reminders,
    /// hand mode and the whole rhythm block, out and back.
    @Test
    fun createdRoutineRoundTripsThroughTheModelUnchanged() = runTest {
        val w = makeWorld()
        val seed = RoutineDraft.starter
        val template = assertNotNull(w.store.create(seed))

        val expected = seed.normalized.copy(templateID = template.id)
        assertEquals(expected, w.store.draft(template))

        assertEquals("Daily no-hangs", template.name)
        assertEquals(0, template.sortIndex)
        assertEquals("alternateEachRep", template.handModeRaw)
        assertEquals(seed.plan.sets.map { it.grip.key }, template.sets.map { it.grip.key })
        assertEquals(seed.reminders, template.reminders)
        assertEquals(1290, template.estimatedSeconds)
        assertEquals("6 sets · 36 pulls · ≈21 min", template.summaryLine)

        val summary = w.store.summary(template)
        assertEquals("20 mm · 6 sets · 36 pulls · ≈21 min", summary.metaLine)
        assertEquals(6, summary.setCount)
        assertEquals(36, summary.totalReps)
        assertEquals(20, summary.sharedEdgeMM)
        assertEquals(2, summary.sessionsPerDay)
        assertEquals(listOf(6, 6, 2, 2, 1, 1), summary.ladder.map { it.repsPerSide })
        assertEquals(listOf(0, 1, 2, 3, 4, 5), summary.ladder.map { it.id })
        // WHICH slot is next depends on the wall clock at test time, so only membership is
        // assertable here — it may never be a time nobody set.
        summary.nextReminder?.let { assertContains(seed.reminders, it) }
    }

    /// The wizard IS the editor, so an edit must land on the same rows the user was
    /// looking at rather than replacing the routine wholesale.
    @Test
    fun updateEditsInPlaceAndPreservesSetIdentity() = runTest {
        val w = makeWorld()
        val template = assertNotNull(w.store.create(RoutineDraft.starter))
        val createdAt = template.createdAt
        val updatedBefore = template.updatedAt
        val idsBefore = template.sets.map { it.id }

        var edited = w.store.draft(template)
        val sets = edited.plan.sets.toMutableList()
        sets[0] = sets[0].copy(repsPerSide = 8)
        edited = edited.copy(plan = edited.plan.copy(sets = sets))
        assertTrue(w.store.update(template, edited))

        val stored = assertNotNull(w.store.routine(template.id))
        assertEquals(idsBefore, stored.sets.map { it.id }, "every set keeps its identity")
        assertEquals(8, stored.sets[0].repsPerSide)
        assertEquals(createdAt, stored.createdAt)
        assertTrue(stored.updatedAt >= updatedBefore)
        assertEquals(1, routines(w).size)
    }

    @Test
    fun saveRoutesToCreateOrUpdateByTemplateID() = runTest {
        val w = makeWorld()
        val created = assertNotNull(w.store.save(RoutineDraft.starter))
        assertEquals(1, routines(w).size)

        var edited = w.store.draft(created)
        assertEquals(created.id, edited.templateID)
        edited = edited.copy(plan = edited.plan.copy(name = "Morning no-hangs"))
        val saved = assertNotNull(w.store.save(edited))

        assertEquals(created.id, saved.id)
        assertEquals(1, routines(w).size, "a live templateID updates rather than inserting")
        assertEquals("Morning no-hangs", assertNotNull(w.store.routine(created.id)).name)
    }

    @Test
    fun duplicateAppendsWithANewIDFreshSetIDsAndACopyOfPrefix() = runTest {
        val w = makeWorld()
        val original = assertNotNull(w.store.create(RoutineDraft.starter))
        val copy = assertNotNull(w.store.duplicate(original))

        assertEquals("Copy of Daily no-hangs", copy.name)
        assertNotEquals(original.id, copy.id)
        assertEquals(1, copy.sortIndex, "a duplicate lands at the end, not on top of Today")
        assertTrue(
            copy.sets.map { it.id }.toSet().intersect(original.sets.map { it.id }.toSet()).isEmpty(),
            "shared set ids would make one edit hit two routines",
        )
        assertEquals(original.sets.map { it.grip.key }, copy.sets.map { it.grip.key })

        val second = assertNotNull(w.store.duplicate(original))
        assertEquals("Copy of Daily no-hangs 2", second.name)
        assertEquals(2, second.sortIndex)
    }

    // MARK: - Importing a shared routine

    /// The shape `RoutineShare.draft(from:)` hands over: a real plan, no template
    /// identity, and reminders OFF — the payload deliberately carries no personal times.
    private fun imported(name: String): RoutineDraft {
        val d = RoutineDraft.starter
        return d.copy(
            templateID = null,
            plan = d.plan.copy(name = name),
            remindersEnabled = false,
        )
    }

    @Test
    fun importingARoutineWhoseNameCollidesGetsANumberedUniqueName() = runTest {
        val w = makeWorld()
        val mine = assertNotNull(w.store.create(RoutineDraft.starter))
        assertEquals("Daily no-hangs", mine.name)

        // Two people converging on the same obvious name is the COMMON case for a shared
        // routine, not the edge one — and the chooser shows names only, so a second
        // "Daily no-hangs" is a routine you cannot pick by sight.
        val first = assertNotNull(w.store.importRoutine(imported("Daily no-hangs")))
        assertEquals("Daily no-hangs 2", first.name)

        val again = assertNotNull(w.store.importRoutine(imported("Daily no-hangs")))
        assertEquals("Daily no-hangs 3", again.name)

        val fresh = assertNotNull(w.store.importRoutine(imported("Somebody else's ladder")))
        assertEquals(
            "Somebody else's ladder", fresh.name,
            "a name nobody has taken is left exactly as it was shared",
        )
    }

    /// Both guarantees the store re-asserts rather than trusting from the wire, asserted
    /// against a draft that violates each: reminders would raise an OS permission prompt
    /// out of a scan, and an adopted `templateID` would mint a routine wearing the
    /// sharer's identity — the id every `doigt.routine.<uuid>.<slot>` notification is
    /// keyed from.
    @Test
    fun importNeverTurnsOnRemindersOrAdoptsTheSharersRoutineID() = runTest {
        val w = makeWorld()
        val sharersID = UUID.randomUUID()
        val incoming = imported("Borrowed burn")
            .copy(remindersEnabled = true, templateID = sharersID)

        val landed = assertNotNull(w.store.importRoutine(incoming))

        assertFalse(landed.remindersEnabled)
        assertNotEquals(sharersID, landed.id)
        assertNull(
            w.store.summary(landed).nextReminder,
            "reminders off means nothing is scheduled and nothing is promised",
        )
    }

    /// The inbox exists because a link can land while a full-screen destination owns the
    /// screen — presenting from the root at that moment tore it down (a session died
    /// unlogged). So the store HOLDS the decoded value, Today drains it when the screen is
    /// free, and a claim consumes the slot so one scan can never present twice.
    @Test
    fun theShareLinkInboxHoldsOneScanAndAClaimConsumesIt() = runTest {
        val w = makeWorld()
        val url = assertNotNull(RoutineShare.url(RoutineDraft.starter))

        w.store.receiveShareLink(url)
        assertNotNull(w.store.pendingImport)
        assertNull(w.store.pendingImportError)

        assertNotNull(w.store.claimPendingImport())
        assertNull(w.store.pendingImport, "a claim consumes the slot")
        assertNull(w.store.claimPendingImport(), "and a second drain finds nothing")

        // A damaged link fills the OTHER slot — and clears the first, latest scan wins:
        // two codes scanned back to back are one decision, about the second one.
        w.store.receiveShareLink(url)
        w.store.receiveShareLink("getagrip://routine#not-a-payload!!")
        assertNull(w.store.pendingImport)
        assertEquals(
            RoutineShareError.unreadable.errorDescription,
            w.store.claimPendingImportError(),
        )
        assertNull(w.store.pendingImportError)
    }

    /// The name the preview promises must be the name the card wears — deconfliction
    /// happens on save, so the preview asks the same question ahead of time.
    @Test
    fun thePlannedImportNameMatchesWhatImportActuallyLandsUnder() = runTest {
        val w = makeWorld()
        assertNotNull(w.store.create(RoutineDraft.starter))

        val planned = w.store.plannedImportName("Daily no-hangs")
        val landed = assertNotNull(w.store.importRoutine(imported("Daily no-hangs")))
        assertEquals(landed.name, planned)
        assertEquals("Daily no-hangs 2", landed.name)
    }

    /// The column arrived late on iOS (2026-08-19): the plan field, its toggle and the
    /// runner's gate all shipped first, and every save silently flipped the switch back to
    /// true on the way to disk. Found because the QR payload carried a field the store
    /// then lost at both ends.
    @Test
    fun pausesOutsideTargetBandSurvivesTheSave() = runTest {
        val w = makeWorld()
        val d = RoutineDraft.starter
        val draft = d.copy(plan = d.plan.copy(pausesOutsideTargetBand = false))

        val saved = assertNotNull(w.store.create(draft))

        assertFalse(saved.pausesOutsideTargetBand)
        assertFalse(saved.plan.pausesOutsideTargetBand, "the runner reads the plan")
        assertFalse(saved.draft.plan.pausesOutsideTargetBand, "the editor reads the draft")
    }

    /// The undo restores RAW columns, so every column the row grows rides along —
    /// `isOnDemand` was missing from the iOS snapshot struct, and undoing a deleted
    /// WHENEVER routine brought it back as a ritual, daily target and all. Here the
    /// snapshot IS the row, which is what makes that class of bug unavailable.
    @Test
    fun undoingADeletedWheneverRoutineRestoresItAsAWheneverRoutine() = runTest {
        val w = makeWorld()
        val d = RoutineDraft.maxDay
        val draft = d.copy(plan = d.plan.copy(pausesOutsideTargetBand = false))
        val saved = assertNotNull(w.store.create(draft))
        assertTrue(saved.isOnDemand)

        assertTrue(w.store.delete(saved))
        w.store.undoDelete()

        val restored = assertNotNull(routines(w).firstOrNull { it.name == "Max day" })
        assertTrue(restored.isOnDemand, "a whenever routine must not come back owed")
        assertFalse(
            restored.plan.pausesOutsideTargetBand,
            "both late columns ride the snapshot now",
        )
    }

    @Test
    fun anImportedRoutineAppendsAtTheEndAndNeverBecomesPrimary() = runTest {
        val w = makeWorld()
        val mine = assertNotNull(w.store.create(RoutineDraft.blank("Mine")))
        val restDay = assertNotNull(w.store.create(RoutineDraft.blank("Rest day")))

        val landed = assertNotNull(w.store.importRoutine(imported("Shared ladder")))

        // Scanning a code is somebody else's suggestion, not a decision about which
        // routine meets you on open. Landing at sortIndex 0 would make it exactly that.
        assertEquals(0, mine.sortIndex)
        assertEquals(1, restDay.sortIndex)
        assertEquals(2, landed.sortIndex)
        assertEquals(listOf("Mine", "Rest day", "Shared ladder"), routines(w).map { it.name })
    }

    // MARK: - Order

    @Test
    fun reorderRenormalizesSortIndicesToContiguousZeroBased() = runTest {
        val w = makeWorld()
        val alpha = assertNotNull(w.store.create(RoutineDraft.blank("Alpha")))
        val bravo = assertNotNull(w.store.create(RoutineDraft.blank("Bravo")))
        val charlie = assertNotNull(w.store.create(RoutineDraft.blank("Charlie")))
        assertEquals(listOf(0, 1, 2), listOf(alpha, bravo, charlie).map { it.sortIndex })

        w.store.move(fromIndex = 2, toOffset = 0)

        val byId = routines(w).associateBy { it.id }
        assertEquals(0, byId[charlie.id]?.sortIndex)
        assertEquals(1, byId[alpha.id]?.sortIndex)
        assertEquals(2, byId[bravo.id]?.sortIndex)
        assertEquals(
            listOf(0, 1, 2), routines(w).map { it.sortIndex }.sorted(),
            "no gaps and no duplicates",
        )
    }

    /// Two concurrent reorders are exactly what produce duplicate sortIndex values. A
    /// partial sort would render them in a different order on each read, which looks to
    /// the user like a bug.
    @Test
    fun tiedSortIndicesResolveDeterministically() = runTest {
        val w = makeWorld()
        val made = mutableListOf<SessionTemplateEntity>()
        listOf("Alpha", "Bravo", "Charlie").forEachIndexed { offset, name ->
            val row = SessionTemplateEntity.from(RoutineDraft.blank(name), sortIndex = 0)
                .copy(createdAt = Instant.ofEpochSecond(1_800_000_000L + offset))
            w.db.routines().upsert(row)
            made.add(row)
        }
        w.store.syncDerived()

        // An identity move still renormalizes, which is where the total order becomes
        // observable: ties break by createdAt, then by id.
        w.store.move(fromIndex = 0, toOffset = 0)
        assertEquals(
            listOf(0, 1, 2),
            made.map { assertNotNull(w.store.routine(it.id)).sortIndex },
        )

        w.store.syncDerived()
        w.store.move(fromIndex = 0, toOffset = 0)
        assertEquals(
            listOf(0, 1, 2),
            made.map { assertNotNull(w.store.routine(it.id)).sortIndex },
            "stable across repeated recomputes",
        )
    }

    // MARK: - Delete and undo

    /// The UUID matters because reminder identifiers are `doigt.routine.<uuid>.r0480`; the
    /// raw blobs matter because undo must not quietly drop a field this build cannot
    /// decode but a newer one wrote.
    @Test
    fun deleteThenUndoRestoresTheOriginalUUIDSortIndexAndRawBlobs() = runTest {
        val w = makeWorld()
        val created = assertNotNull(w.store.create(RoutineDraft.starter))
        w.store.create(RoutineDraft.blank("Rest day"))

        // A blob as a NEWER build would have written it: an extra key this build has never
        // heard of, plus a hand mode it cannot name.
        val futureBlob =
            """[{"grip":{"edgeMM":20,"fingers":"IMRL","position":"halfCrimp"},""" +
                """"id":"8B2C4E8A-0000-4000-8000-0000000000FF","repsPerSide":6,"tempoSeconds":3}]"""
        val template = assertNotNull(w.store.routine(created.id))
            .copy(setsData = futureBlob, handModeRaw = "leftOnly")
        w.db.routines().upsert(template)
        w.store.syncDerived()

        val id = template.id
        val sortIndex = template.sortIndex
        val createdAt = template.createdAt
        val remindersData = template.remindersData

        assertTrue(w.store.delete(template))
        assertEquals(id, w.store.lastDeleted?.id)
        assertEquals(1, routines(w).size)
        assertNull(w.store.routine(id))

        w.store.undoDelete()

        val restored = assertNotNull(w.store.routine(id))
        assertEquals(id, restored.id, "reminder identifiers are keyed on this")
        assertEquals(sortIndex, restored.sortIndex)
        assertEquals(createdAt, restored.createdAt)
        assertEquals(futureBlob, restored.setsData, "raw text, not a decode-re-encode")
        assertEquals("leftOnly", restored.handModeRaw)
        assertEquals(remindersData, restored.remindersData)
        assertNull(w.store.lastDeleted)
        assertEquals(2, routines(w).size)
    }

    /// "Undo" on a delete that never landed would insert a second copy of a routine that
    /// is still there.
    @Test
    fun undoIsOnlyOfferedWhenTheDeleteActuallyLanded() = runTest {
        val w = makeWorld(allowsSave = false, seeding = RoutineDraft.starter)
        val template = assertNotNull(
            routines(w).firstOrNull(), "the refusing world must open seeded",
        )

        assertFalse(
            w.store.delete(template),
            "if this passes, the refusing gateway is no longer a save-failure seam",
        )
        assertNull(w.store.lastDeleted)
        assertNotNull(w.store.saveError)
        assertEquals(1, routines(w).size, "the rolled-back delete must leave the row in place")
    }

    @Test
    fun deletingTheLastRoutineIsAllowedAndLeavesAnEmptyWorld() = runTest {
        val w = makeWorld()
        val template = assertNotNull(w.store.create(RoutineDraft.starter))

        assertTrue(w.store.delete(template))
        assertEquals(0, routines(w).size, "Today falls to its empty card")
        assertTrue(w.store.completionsToday.isEmpty())

        w.store.undoDelete()
        assertEquals(1, routines(w).size)
        assertEquals("6 sets · 36 pulls · ≈21 min", routines(w).firstOrNull()?.summaryLine)
    }

    // MARK: - The new routine-level columns

    /// Columns, not blob fields — so they need their own round trip through `applying` and
    /// back out through `plan`, and they have to survive the undo path.
    ///
    /// A routine-level band is DEMOTED onto the sets on the way in (2026-08-10: load lives
    /// per set), so what survives the round trip is the sets' bands — the routine columns
    /// come back empty, which is the new invariant worth pinning.
    @Test
    fun releaseGateAndTargetPercentSurviveSaveAndUndo() = runTest {
        val w = makeWorld()
        val d = RoutineDraft.starter
        val draft = d.copy(
            plan = d.plan.copy(
                waitForReleaseBeforeRest = false,
                targetLoPercent = 0.17,
                targetHiPercent = 0.22,
            )
        )
        val template = assertNotNull(w.store.create(draft))
        val id = template.id

        assertFalse(template.plan.waitForReleaseBeforeRest)
        assertNull(template.plan.targetPercentBand, "demoted, never stored on the routine")
        assertTrue(
            template.plan.executable.sets.all { it.targetPercentBand == 0.17..0.22 },
            "every set carries the band the routine was authored with",
        )
        assertTrue(
            template.draft.plan.executable.sets.all { it.targetPercentBand == 0.17..0.22 },
            "and the editor reads back what it wrote",
        )

        assertTrue(w.store.delete(template))
        w.store.undoDelete()

        val restored = assertNotNull(w.store.routine(id))
        assertFalse(restored.waitForReleaseBeforeRest)
        assertTrue(
            restored.plan.executable.sets.all { it.targetPercentBand == 0.17..0.22 },
            "undo restores the demoted bands with the sets",
        )
    }

    /// An inverted band is one drag in the builder — it must never reach disk the wrong
    /// way up. It now also never reaches disk on the ROUTINE at all: the flipped band is
    /// demoted onto every set on the way in.
    @Test
    fun anUpsideDownRoutinePercentageIsNormalizedOnTheWayIn() = runTest {
        val w = makeWorld()
        val d = RoutineDraft.starter
        val draft = d.copy(plan = d.plan.copy(targetLoPercent = 0.30, targetHiPercent = 0.20))
        val template = assertNotNull(w.store.create(draft))

        assertNull(template.targetLoPercent)
        assertNull(template.targetHiPercent)
        assertTrue(
            template.plan.executable.sets.all { it.targetPercentBand == 0.20..0.30 },
            "flipped the right way up, and living on the sets",
        )
    }

    /// A routine that predates both fields keeps working and GAINS the release gate — the
    /// default that matters, since it changes how every existing rest behaves.
    @Test
    fun aRoutineWrittenBeforeTheseFieldsGetsTheDefaults() = runTest {
        val w = makeWorld()
        val template = assertNotNull(w.store.create(RoutineDraft.starter))

        assertTrue(template.waitForReleaseBeforeRest)
        assertNull(template.targetLoPercent)
        assertNull(template.plan.targetPercentBand)
    }

    // MARK: - Deleting a session

    /// The id matters because undo RE-INSERTS rather than un-deletes. The raw columns
    /// matter more: `WorkoutLogEntity.from` re-derives every denormalized number from the
    /// reps it is handed, so a restore that went through it would re-score a finished
    /// session under today's arithmetic — and quietly drop whatever a newer build wrote
    /// into the blobs.
    @Test
    fun deletingASessionThenUndoingRestoresItByteForByte() = runTest {
        val w = makeWorld()
        val template = assertNotNull(w.store.create(RoutineDraft.starter))
        val inserted = insertLog(w, template, DayStamp.today() - 1)

        // Deliberately inconsistent with `resultsData`: these are exactly the columns a
        // re-derive would "correct", so if the restore recomputes, this test fails.
        val futureBlob = """[{"unknownKeyFromANewerBuild":1}]"""
        val log = inserted.copy(
            peakKg = 41.5,
            completedReps = 36,
            totalHeldSeconds = 360.0,
            rpe = RPE.hard.rawValue,
            fingerStrainRaw = FingerStrain.taxed.rawValue,
            durationMinutes = 150,
            notes = "felt good",
            resultsData = futureBlob,
        )
        w.db.logs().upsert(log)
        w.store.syncDerived()

        assertTrue(w.store.deleteSession(log))
        assertEquals(log.id, w.store.lastDeletedSession?.id)
        assertEquals(0, workoutLogs(w).size)

        w.store.undoDeleteSession()

        val restored = assertNotNull(workoutLogs(w).firstOrNull())
        assertEquals(log.id, restored.id)
        assertEquals(log.startedAt, restored.startedAt)
        assertEquals(log.dayKey, restored.dayKey, "the day it was counted against")
        assertEquals(log.planData, restored.planData, "raw text, not a decode-re-encode")
        assertEquals(futureBlob, restored.resultsData)
        assertEquals(41.5, restored.peakKg, "denormalized, never recomputed from the reps")
        assertEquals(36, restored.completedReps)
        assertEquals(360.0, restored.totalHeldSeconds)
        assertEquals(RPE.hard.rawValue, restored.rpe, "how it felt survives the round trip")
        assertEquals(
            FingerStrain.taxed.rawValue, restored.fingerStrainRaw,
            "the local strain axis survives the round trip",
        )
        assertEquals(150, restored.durationMinutes, "the hand-entered duration survives")
        assertEquals("felt good", restored.notes)
        assertNull(w.store.lastDeletedSession)
        assertEquals(1, workoutLogs(w).size)
    }

    /// A session delete is not just a row leaving a list: Today counts "2 of 2" off these
    /// logs and the strip is drawn from them, so it has to walk back in the same breath —
    /// and come back on undo.
    @Test
    fun deletingTodaysSessionWalksTodaysCompletionBackAndUndoRestoresIt() = runTest {
        val w = makeWorld()
        val template = assertNotNull(w.store.create(RoutineDraft.starter))
        insertLog(w, template, w.clock.today)
        val second = insertLog(w, template, w.clock.today)
        assertEquals(2, w.store.completionsToday[template.id])

        assertTrue(w.store.deleteSession(second))
        assertEquals(1, w.store.completionsToday[template.id])
        assertNotNull(w.store.routine(template.id), "the routine is untouched")

        w.store.undoDeleteSession()
        assertEquals(2, w.store.completionsToday[template.id])
    }

    /// "Undo" on a delete that never landed would insert a second copy of a session that
    /// is still there — and history would gain a day it never trained.
    @Test
    fun sessionUndoIsOnlyOfferedWhenTheDeleteActuallyLanded() = runTest {
        val w = makeWorld(allowsSave = false, seedingSession = true)
        val log = assertNotNull(
            workoutLogs(w).firstOrNull(), "the refusing world must open seeded",
        )

        assertFalse(
            w.store.deleteSession(log),
            "if this passes, the refusing gateway is no longer a save-failure seam",
        )
        assertNull(w.store.lastDeletedSession)
        assertNotNull(w.store.saveError)
        assertEquals(1, workoutLogs(w).size, "the rolled-back delete must leave the row")
    }

    /// Two undo slots, not one: a delete on History must not silently retract the Undo
    /// still on offer on Today.
    @Test
    fun sessionAndRoutineUndoAreIndependent() = runTest {
        val w = makeWorld()
        val template = assertNotNull(w.store.create(RoutineDraft.starter))
        val log = insertLog(w, template, DayStamp.today() - 1)

        assertTrue(w.store.deleteSession(log))
        assertTrue(w.store.delete(template))

        assertNotNull(w.store.lastDeletedSession, "still on offer after a routine delete")
        assertNotNull(w.store.lastDeleted)

        w.store.undoDeleteSession()
        assertNull(w.store.lastDeletedSession)
        assertNotNull(w.store.lastDeleted, "the routine's offer outlives the session's")
    }

    // MARK: - History is frozen

    @Test
    fun deletingARoutineLeavesItsLogsIntactWithTheFrozenName() = runTest {
        val w = makeWorld()
        val template = assertNotNull(w.store.create(RoutineDraft.starter))
        val templateID = template.id
        insertLog(w, template, DayStamp.today() - 1)

        assertTrue(w.store.delete(template))

        val log = assertNotNull(workoutLogs(w).firstOrNull())
        assertEquals("Daily no-hangs", log.templateName)
        assertEquals(templateID, log.templateID)
        assertNull(w.store.routine(templateID), "templateID is best-effort grouping only")
        assertEquals(6, log.plan?.sets?.size, "the frozen plan is still readable")
        assertEquals(1, log.reps.size)
        assertTrue(log.planData.isNotEmpty())
    }

    /// The frozen-name rule, stated as a test because it is the one people "fix".
    @Test
    fun renamingARoutineDoesNotRetroRenameOldSessions() = runTest {
        val w = makeWorld()
        val template = assertNotNull(w.store.create(RoutineDraft.starter))
        insertLog(w, template, DayStamp.today() - 1)

        var edited = w.store.draft(template)
        edited = edited.copy(plan = edited.plan.copy(name = "Morning no-hangs"))
        assertTrue(w.store.update(template, edited))

        assertEquals("Morning no-hangs", assertNotNull(w.store.routine(template.id)).name)
        assertEquals("Daily no-hangs", workoutLogs(w).firstOrNull()?.templateName)
    }

    // MARK: - Completion counts

    @Test
    fun completedTodayCountsOnlyTodaysLogsForThatTemplate() = runTest {
        val w = makeWorld()
        val today = DayStamp.today()
        val daily = assertNotNull(w.store.create(RoutineDraft.starter))
        val rest = assertNotNull(w.store.create(RoutineDraft.blank("Rest day")))

        insertLog(w, daily, today - 1)   // yesterday
        insertLog(w, rest, today)        // another routine
        insertLog(w, daily, today)
        insertLog(w, daily, today)

        assertEquals(2, w.store.completed(daily))
        assertEquals(1, w.store.completed(rest))
        assertTrue(w.store.isDoneForToday(daily))
        assertFalse(w.store.isDoneForToday(rest))
        assertTrue(w.store.summary(daily).targetMet)
        assertEquals(2, w.store.summary(daily).completedToday)
        assertEquals("Both sessions done today", w.store.completionText(daily))
    }

    /// A phone left open past midnight must flip 2/2 back to 0/2 without a relaunch.
    @Test
    fun completionResetsWhenTheDayRolls() = runTest {
        val w = makeWorld()
        val today = DayStamp.today()
        val daily = assertNotNull(w.store.create(RoutineDraft.starter))
        insertLog(w, daily, today)
        insertLog(w, daily, today)
        assertEquals(2, w.store.completed(daily))

        w.clock.advance(today + 1)
        w.store.refreshIfDayChanged()

        assertEquals(0, w.store.completed(daily))
        assertFalse(w.store.isDoneForToday(daily))
        assertFalse(w.store.summary(daily).targetMet)
    }

    // MARK: - The consistency strip

    @Test
    fun consistencyReturnsExactlyFourteenRecordsOldestFirstEndingToday() = runTest {
        val w = makeWorld()
        assertNotNull(w.store.create(RoutineDraft.starter))
        val today = DayStamp.today()

        val records = w.store.consistency
        assertEquals(14, records.size)
        assertEquals((0..13).map { today - 13 + it }, records.map { it.day })
        assertEquals(today - 13, records.first().day)
        assertEquals(today, records.last().day)
    }

    /// The single most important honesty detail on Today: a day before any routine existed
    /// is a hairline, not a hole. Calling it a missed session would invent a failure the
    /// user could not possibly have avoided.
    @Test
    fun daysBeforeAnyRoutineExistedAreUntrackedNotMissed() = runTest {
        val w = makeWorld()
        val today = DayStamp.today()
        val created = assertNotNull(w.store.create(RoutineDraft.starter))
        w.db.routines().upsert(created.copy(createdAt = (today - 3).startOfDay().toInstant()))
        w.store.syncDerived()

        val since = assertNotNull(w.store.trackingSince)
        assertEquals(today - 3, since)

        val records = w.store.consistency
        assertEquals(14, records.size)
        for (record in records) {
            // A routine created on day X existed on day X — tracking starts there.
            assertEquals(record.day >= since, record.tracked, "${record.day}")
        }
        assertFalse(records.first().tracked)
        assertTrue(records.last().tracked)
        assertTrue(records.filter { !it.tracked }.all { it.completed == 0 })
    }

    /// A completed 1×/day rest day renders full, not half. The alternative calls a
    /// finished session a partial failure because a different routine asks for two.
    @Test
    fun consistencyTargetComesFromTheLogsOwnSessionsPerDayTarget() = runTest {
        val w = makeWorld()
        val today = DayStamp.today()
        val template = assertNotNull(w.store.create(RoutineDraft.starter))   // sessionsPerDay 2
        insertLog(w, template, today, target = 1)

        val record = assertNotNull(w.store.consistency.lastOrNull())
        assertEquals(today, record.day)
        assertEquals(1, record.target)
        assertEquals(1, record.completed)
        assertEquals(1.0, record.fraction, 0.0001)

        assertEquals(
            1.0,
            DayRecord(today, completed = 3, target = 2, tracked = true, climb = null).fraction,
            0.0001, "overshoot never draws past full",
        )
        assertEquals(
            0.0,
            DayRecord(today, completed = 1, target = 0, tracked = true, climb = null).fraction,
            0.0001, "nothing divides by zero",
        )
    }

    // MARK: - Climbing sessions

    /// THE RULE. A day at the gym completes the day outright — the whole reason the
    /// feature exists is that scoring it as a miss was the app lying about the week.
    @Test
    fun aClimbCompletesTheDayOnItsOwn() = runTest {
        val w = makeWorld()
        val saved = assertNotNull(w.store.save(draft("Daily", listOf(GripSpec()))))
        w.db.routines().upsert(saved.copy(sessionsPerDay = 2))
        w.store.syncDerived()
        val daily = assertNotNull(w.store.routine(saved.id))
        assertFalse(w.store.isDoneForToday(daily), "two hangs are owed")

        assertNotNull(w.store.recordLoggedSession(SessionKind.climbLimit))

        assertTrue(w.store.isDoneForToday(daily))
        assertEquals(SessionKind.climbLimit, w.store.climbToday)
        assertEquals(
            0, w.store.completed(daily),
            "a climb settles the day WITHOUT pretending a hang session happened",
        )
    }

    /// Volume completes it too — Nuri's call (2026-08-05). The style is recorded for
    /// reading the week back, not to change the arithmetic.
    @Test
    fun aVolumeClimbAlsoCompletesTheDay() = runTest {
        val w = makeWorld()
        val saved = assertNotNull(w.store.save(draft("Daily", listOf(GripSpec()))))
        w.db.routines().upsert(saved.copy(sessionsPerDay = 2))
        w.store.syncDerived()

        assertNotNull(w.store.recordLoggedSession(SessionKind.climbVolume))
        assertTrue(w.store.isDoneForToday(assertNotNull(w.store.routine(saved.id))))
    }

    /// An extra hang session after a climb still LOGS and still counts — the day is
    /// complete, not closed. "Offered, never demanded."
    @Test
    fun aHangSessionAfterAClimbStillCounts() = runTest {
        val w = makeWorld()
        val saved = assertNotNull(w.store.save(draft("Daily", listOf(GripSpec()))))
        assertNotNull(w.store.recordLoggedSession(SessionKind.climbVolume))
        insertLog(w, saved, w.clock.today)

        assertEquals(1, w.store.completed(saved))
        assertTrue(w.store.isDoneForToday(saved))
        assertTrue(w.store.completionText(saved).contains("plus a hang session"))
    }

    /// The routine CARD has to agree with the rest of the app. Before this, a day
    /// completed by a climb showed the chooser rail's dot as done and the sentence as "at
    /// the gym today", while the card still offered a primary "Start first session" with
    /// no checkmark — one fact, three surfaces, two answers.
    @Test
    fun theRoutineCardReadsTheDayAsMetAfterAClimb() = runTest {
        val w = makeWorld()
        val saved = assertNotNull(w.store.save(draft("Daily", listOf(GripSpec()))))
        w.db.routines().upsert(saved.copy(sessionsPerDay = 2))
        w.store.syncDerived()
        val daily = assertNotNull(w.store.routine(saved.id))
        assertFalse(w.store.summary(daily).targetMet)

        assertNotNull(w.store.recordLoggedSession(SessionKind.climbVolume))

        val summary = w.store.summary(daily)
        assertTrue(summary.targetMet)
        assertEquals(SessionKind.climbVolume, summary.climbedToday)
        assertEquals(0, summary.completedToday, "and it still knows no hangs happened")
    }

    /// The hardest climb names the day: a limit session followed by an easy evening is
    /// remembered as the limit session.
    @Test
    fun theHardestClimbNamesTheDay() = runTest {
        val w = makeWorld()
        assertNotNull(w.store.recordLoggedSession(SessionKind.climbVolume))
        assertNotNull(w.store.recordLoggedSession(SessionKind.climbLimit))
        assertEquals(SessionKind.climbLimit, w.store.climbToday)
    }

    /// A climb must not be counted as a hang session anywhere — not in the per-routine
    /// tally, and not in the consistency record's `completed`, which is what draws the
    /// "1 of 2" fill.
    @Test
    fun aClimbIsNeverCountedAsAHangSession() = runTest {
        val w = makeWorld()
        val saved = assertNotNull(w.store.save(draft("Daily", listOf(GripSpec()))))
        w.db.routines().upsert(saved.copy(sessionsPerDay = 2))
        assertNotNull(w.store.recordLoggedSession(SessionKind.climbLimit))

        val today = assertNotNull(w.store.consistency.lastOrNull())
        assertEquals(0, today.completed, "no hang session happened")
        assertEquals(SessionKind.climbLimit, today.climb)
        assertEquals(1.0, today.fraction, 0.0001, "but the cell is FULL — the day is complete")
    }

    /// Logging yesterday's session is the realistic case: you climb at night and reach for
    /// the phone the next morning.
    @Test
    fun aClimbCanBeLoggedForYesterday() = runTest {
        val w = makeWorld()
        assertNotNull(w.store.recordLoggedSession(SessionKind.climbVolume, daysAgo = 1))

        assertNull(w.store.climbToday, "yesterday's session does not complete today")
        val yesterday = assertNotNull(w.store.consistency.dropLast(1).lastOrNull())
        assertEquals(SessionKind.climbVolume, yesterday.climb)
    }

    /// The hand logger may create only climb kinds and a manual hang. Runner hangs and
    /// benchmark rows have their own writers, so accepting either here would create a day
    /// state without the event that normally owns it.
    @Test
    fun recordLoggedSessionAcceptsHandKindsAndRefusesRunnerKinds() = runTest {
        val w = makeWorld()
        assertNotNull(w.store.recordLoggedSession(SessionKind.climbVolume))
        assertNotNull(w.store.recordLoggedSession(SessionKind.climbLimit))
        val manualHang = assertNotNull(
            w.store.recordLoggedSession(
                SessionKind.hangManual, minutes = 120, rpe = RPE.hard,
                fingerStrain = FingerStrain.taxed,
            )
        )
        assertNull(w.store.recordLoggedSession(SessionKind.hang))
        assertNull(w.store.recordLoggedSession(SessionKind.benchmark))
        assertEquals(3, workoutLogs(w).size)
        assertEquals(SessionKind.hangManual, manualHang.kind)
        assertEquals(120, manualHang.sessionMinutes)
        assertEquals(RPE.hard, manualHang.grade)
        assertEquals(FingerStrain.taxed, manualHang.fingerStrain)
    }

    /// A hand-logged hang fills ONE of the day's slots — on the calendar and on Today's
    /// card alike. The two used to disagree: the grid folds on `countsAsHang` while the
    /// card read the routine-attributed map, and a hand-logged hang has no routine to
    /// attribute to, so the grid drew "1 of 2" while the card said "0 of 2".
    @Test
    fun aHandLoggedHangCountsOnBothTheGridAndTheCard() = runTest {
        val w = makeWorld()
        val saved = assertNotNull(w.store.save(draft("Daily", listOf(GripSpec()))))
        w.db.routines().upsert(saved.copy(sessionsPerDay = 2))
        assertNotNull(w.store.recordLoggedSession(SessionKind.hangManual))
        val daily = assertNotNull(w.store.routine(saved.id))

        assertEquals(1, w.store.consistency.lastOrNull()?.completed, "the grid counts it")
        assertEquals(1, w.store.completed(daily), "and so does the card")
        assertFalse(w.store.isDoneForToday(daily), "one of two is not done yet")
        assertNull(w.store.climbToday, "it is a session, not a day-settling climb")
    }

    /// Meeting the target entirely through hand-logged hangs still ends the day — which is
    /// what stops the evening reminder firing on a night already trained.
    @Test
    fun handLoggedHangsCanMeetTheDaysTargetOnTheirOwn() = runTest {
        val w = makeWorld()
        val saved = assertNotNull(w.store.save(draft("Daily", listOf(GripSpec()))))
        w.db.routines().upsert(saved.copy(sessionsPerDay = 2))

        assertNotNull(w.store.recordLoggedSession(SessionKind.hangManual))
        val daily = assertNotNull(w.store.routine(saved.id))
        assertFalse(w.store.isDoneForToday(daily))
        assertNotNull(w.store.recordLoggedSession(SessionKind.hangManual))

        assertEquals(2, w.store.completed(daily))
        assertTrue(w.store.isDoneForToday(daily), "two of two, by hand, is still done")
        assertNull(w.store.climbToday, "and still not a climb")
    }

    /// A hand-logged hang is not attributable to any ONE routine, so it counts for every
    /// routine's day — the same rule a climb already follows.
    @Test
    fun aHandLoggedHangCountsForEveryRoutine() = runTest {
        val w = makeWorld()
        val daily = assertNotNull(w.store.save(draft("Daily", listOf(GripSpec()))))
        val maxDay = assertNotNull(w.store.save(draft("Max day", listOf(GripSpec()))))
        // A draft defaults to two a day; one each is what makes a single hang decisive.
        w.db.routines().upsert(daily.copy(sessionsPerDay = 1))
        w.db.routines().upsert(maxDay.copy(sessionsPerDay = 1))
        assertNotNull(w.store.recordLoggedSession(SessionKind.hangManual))

        assertTrue(w.store.isDoneForToday(assertNotNull(w.store.routine(daily.id))))
        assertTrue(w.store.isDoneForToday(assertNotNull(w.store.routine(maxDay.id))))
    }

    /// Every log written before climbing existed decodes as a hang session, so an upgraded
    /// install behaves exactly as it did.
    @Test
    fun anExistingLogIsAHangSession() = runTest {
        val w = makeWorld()
        val saved = assertNotNull(w.store.save(draft("Daily", listOf(GripSpec()))))
        val log = insertLog(w, saved, w.clock.today)

        assertEquals(SessionKind.hang, log.kind)
        assertEquals("hang", log.kindRaw)
        assertNull(w.store.climbToday)
    }

    /// Undo restores the KIND. Without it, undoing a deleted climb would put back a
    /// hangboard session and the day it completed would quietly become incomplete.
    @Test
    fun undoingADeletedClimbRestoresItAsAClimb() = runTest {
        val w = makeWorld()
        val climb = assertNotNull(w.store.recordLoggedSession(SessionKind.climbLimit))
        assertTrue(w.store.deleteSession(climb))
        assertNull(w.store.climbToday)

        w.store.undoDeleteSession()
        assertEquals(SessionKind.climbLimit, w.store.climbToday)
        assertEquals(SessionKind.climbLimit, assertNotNull(workoutLogs(w).firstOrNull()).kind)
    }

    // MARK: - Recent grips

    @Test
    fun recentGripsAreDeduplicatedByKeyMostRecentFirstAndCappedAtSix() = runTest {
        val w = makeWorld()
        val a = GripSpec(20, FingerSet.four, GripPosition.halfCrimp)
        val b = GripSpec(18, FingerSet.four, GripPosition.halfCrimp)
        val c = GripSpec(16, FingerSet.four, GripPosition.halfCrimp)
        val d = GripSpec(14, FingerSet.four, GripPosition.halfCrimp)

        w.store.create(draft("Older", listOf(a, b)))
        w.store.create(draft("Newer", listOf(c, d, a)))

        val recent = w.store.recentGrips
        assertEquals(recent.size, recent.map { it.key }.toSet().size, "deduplicated by key")
        assertEquals(listOf(a, b, c, d).map { it.key }.toSet(), recent.map { it.key }.toSet())
        val indexOf = { grip: GripSpec -> recent.indexOfFirst { it.key == grip.key } }
        assertTrue(indexOf(c) < indexOf(b), "the routine touched most recently comes first")

        w.store.create(
            draft("Newest", (1..6).map { GripSpec(20 + it, FingerSet.frontTwo, GripPosition.openHand) })
        )
        assertEquals(6, w.store.recentGrips.size, "the rail holds six")
        assertEquals(6, w.store.recentGrips.map { it.key }.toSet().size)
    }

    /// The RECENT rail must never be blank on a first build — an empty rail on the very
    /// first set row is a dead end where the fastest path should be.
    @Test
    fun recentGripsFallBackToASeedPaletteWhenThereIsNoHistory() = runTest {
        val w = makeWorld()
        w.store.syncDerived()
        assertEquals(0, routines(w).size)

        val seeded = w.store.recentGrips
        assertTrue(seeded.isNotEmpty())
        assertTrue(seeded.size <= 6)
        assertEquals(seeded.size, seeded.map { it.key }.toSet().size)
    }

    // MARK: - Maxes

    /// Append-only, so "current" means the NEWEST record for that key — not the biggest. A
    /// max that has come down is still the truth about today.
    @Test
    fun recordMaxAppendsAndCurrentMaxIsTheNewestForThatKey() = runTest {
        val w = makeWorld()
        val grip = GripSpec(20, FingerSet.four, GripPosition.halfCrimp)
        val other = GripSpec(20, FingerSet.frontTwo, GripPosition.halfCrimp)

        w.db.maxes().upsert(
            MaxRecordEntity.from(
                grip, 44.0, MaxSource.measured,
                recordedAt = Instant.now().minusSeconds(7 * 86_400L),
            )
        )
        w.store.syncDerived()
        assertEquals(44.0, assertNotNull(w.store.currentMax(grip)), 0.0001)

        assertTrue(w.store.recordMax(40.0, grip, MaxSource.manual))
        assertEquals(40.0, assertNotNull(w.store.currentMax(grip)), 0.0001)
        assertNull(w.store.currentMax(other), "a different key must not leak across")

        val stored = w.db.maxes().all()
        assertEquals(2, stored.size, "recording a max inserts a row rather than mutating one")
        assertEquals(setOf("20|IMRL|halfCrimp"), stored.map { it.gripKey }.toSet())
    }

    /// THE regression that would be invisible on screen: "current" is per grip AND per
    /// hand. Folded on the grip alone, the right-hand max recorded second would become the
    /// grip's current max and the left one would drop into history — one of your two hands
    /// silently losing its number, and every left-hand target quietly moving with it.
    @Test
    fun leftAndRightMaxesAreSeparateCurrentRecords() = runTest {
        val w = makeWorld()
        val grip = GripSpec(20, FingerSet.four, GripPosition.halfCrimp)

        assertTrue(w.store.recordMax(40.0, grip, side = Side.left))
        assertTrue(w.store.recordMax(36.0, grip, side = Side.right))

        assertEquals(40.0, assertNotNull(w.store.currentMax(grip, Side.left)), 0.0001)
        assertEquals(36.0, assertNotNull(w.store.currentMax(grip, Side.right)), 0.0001)
        assertNull(w.store.currentMax(grip), "neither hand answers for a two-handed pull")

        assertEquals(2, w.store.currentMaxes.size, "two current records, not one")
    }

    /// A both-hands max still covers every hand, so nothing changes for anyone who never
    /// touches the picker — and a later side-specific max overrides only that side.
    @Test
    fun aBothHandsMaxCoversEitherHandUntilThatHandHasItsOwn() = runTest {
        val w = makeWorld()
        val grip = GripSpec(20, FingerSet.four, GripPosition.halfCrimp)

        assertTrue(w.store.recordMax(40.0, grip))
        assertEquals(40.0, assertNotNull(w.store.currentMax(grip, Side.left)), 0.0001)
        assertEquals(40.0, assertNotNull(w.store.currentMax(grip, Side.right)), 0.0001)

        assertTrue(w.store.recordMax(36.0, grip, side = Side.right))
        assertEquals(36.0, assertNotNull(w.store.currentMax(grip, Side.right)), 0.0001)
        assertEquals(
            40.0, assertNotNull(w.store.currentMax(grip, Side.left)), 0.0001,
            "the left hand still follows the both-hands max",
        )
    }

    @Test
    fun maxTableMirrorsCurrentMaxes() = runTest {
        val w = makeWorld()
        val grip = GripSpec(20, FingerSet.four, GripPosition.halfCrimp)
        assertTrue(w.store.recordMax(40.0, grip, side = Side.left))
        assertTrue(w.store.recordMax(36.0, grip, side = Side.right))

        assertEquals(40.0, w.store.maxTable.exact(grip.key, Side.left))
        assertEquals(36.0, w.store.maxTable.exact(grip.key, Side.right))
        assertTrue(w.store.maxTable.differsByHand(grip.key))
    }

    /// Every record written before the hand column existed decodes as `both`, which is
    /// what keeps an upgraded install behaving exactly as it did.
    @Test
    fun aMaxDefaultsToBothHands() = runTest {
        val w = makeWorld()
        val grip = GripSpec(20, FingerSet.four, GripPosition.halfCrimp)
        assertTrue(w.store.recordMax(40.0, grip))

        val stored = assertNotNull(w.db.maxes().all().firstOrNull())
        assertEquals(Side.both, stored.side)
        assertEquals("both", stored.sideRaw)
    }

    @Test
    fun suggestedBandIsNilWithoutAMaxForThatExactGrip() = runTest {
        val w = makeWorld()
        val fourFinger = GripSpec(20, FingerSet.four, GripPosition.halfCrimp)
        val frontTwo = GripSpec(20, FingerSet.frontTwo, GripPosition.halfCrimp)

        assertTrue(w.store.recordMax(40.0, fourFinger))
        assertEquals(8.0..12.0, w.store.suggestedBand(fourFinger))
        assertNull(
            w.store.suggestedBand(frontTwo),
            "front two is a different key, so it has no max of its own yet",
        )
    }

    // MARK: - Live routine names

    /// History resolves a session's routine name through this rather than showing the copy
    /// frozen into the log, so renaming a routine renames it everywhere at once.
    @Test
    fun renamingARoutineMovesItsPublishedName() = runTest {
        val w = makeWorld()
        val d = RoutineDraft.starter
        val saved = assertNotNull(w.store.create(d.copy(plan = d.plan.copy(name = "Daily no-hangs"))))
        assertEquals("Daily no-hangs", w.store.routineNames[saved.id])

        var renamed = w.store.draft(saved)
        renamed = renamed.copy(plan = renamed.plan.copy(name = "Morning ladder"))
        assertTrue(w.store.update(saved, renamed))

        assertEquals("Morning ladder", w.store.routineNames[saved.id])
    }

    /// And a DELETED routine drops out, which is what sends History back to the frozen
    /// name in the log — the reason that column still exists.
    @Test
    fun aDeletedRoutineLeavesNoLiveName() = runTest {
        val w = makeWorld()
        val saved = assertNotNull(w.store.create(RoutineDraft.starter))
        assertNotNull(w.store.routineNames[saved.id])

        assertTrue(w.store.delete(saved))
        assertNull(w.store.routineNames[saved.id])
    }

    // MARK: - The gated max refold

    /// `syncDerived` no longer refetches every `MaxRecord` ever written on every save —
    /// only writes that actually touch one ask for the refold. This is the assertion that
    /// makes that safe: the three max-derived values must survive every OTHER kind of
    /// write untouched. If a skip ever cleared or staled them, every percent-of-max band
    /// in the app would resolve against nothing.
    @Test
    fun theMaxTableSurvivesEveryWriteThatTouchesNoMax() = runTest {
        val w = makeWorld()
        val grip = GripSpec(20, FingerSet.four, GripPosition.halfCrimp)
        assertTrue(w.store.recordMax(42.0, grip, MaxSource.measured))
        val measuredAt = assertNotNull(w.store.lastMeasuredMaxAt)

        // One of each write that now passes `maxesChanged = false`.
        val created = assertNotNull(w.store.create(RoutineDraft.starter))
        w.store.makePrimary(created)
        val live = assertNotNull(w.store.routine(created.id))
        assertTrue(w.store.update(live, w.store.draft(live)))
        val log = assertNotNull(
            w.store.recordSession(
                plan = live.plan, template = live, reps = emptyList(),
                startedAt = Instant.now(), finishedAt = Instant.now(), rpe = null,
            )
        )
        assertTrue(w.store.deleteSession(log))
        assertNotNull(w.store.recordLoggedSession(SessionKind.climbVolume))
        assertTrue(w.store.delete(assertNotNull(w.store.routine(created.id))))

        assertEquals(42.0, assertNotNull(w.store.currentMax(grip)), 0.0001)
        assertEquals(1, w.store.currentMaxes.size)
        // `max`, not `exact`: this is the lookup the engine makes when it resolves a
        // percent band, so it is the one that has to still answer.
        assertEquals(42.0, w.store.maxTable.max(grip.key, Side.left))
        assertEquals(measuredAt, w.store.lastMeasuredMaxAt)
    }

    /// The other direction: the two writes that DO move a max still refold, so deleting
    /// the newest one falls back to the record underneath rather than leaving the deleted
    /// number on screen.
    @Test
    fun deletingAMaxRefoldsBackToThePreviousOne() = runTest {
        val w = makeWorld()
        val grip = GripSpec(20, FingerSet.four, GripPosition.halfCrimp)
        w.db.maxes().upsert(
            MaxRecordEntity.from(
                grip, 38.0, MaxSource.measured,
                recordedAt = Instant.now().minusSeconds(7 * 86_400L),
            )
        )
        w.store.syncDerived()

        assertTrue(w.store.recordMax(44.0, grip, MaxSource.manual))
        assertEquals(44.0, assertNotNull(w.store.currentMax(grip)), 0.0001)

        val newest = assertNotNull(w.store.currentMaxes.values.firstOrNull { it.gripKey == grip.key })
        assertTrue(w.store.deleteMax(newest))
        assertEquals(38.0, assertNotNull(w.store.currentMax(grip)), 0.0001)
    }

    // MARK: - Rescaling typed kg targets

    private fun kgTargetDraft(name: String, first: GripSpec, second: GripSpec): RoutineDraft {
        val d = RoutineDraft.blank(name)
        val a = SetPlan(grip = first, repsPerSide = 4, targetLoKg = 20.0, targetHiKg = 24.0)
        val b = SetPlan(grip = second, repsPerSide = 4, targetLoKg = 10.0, targetHiKg = 12.0)
        return d.copy(plan = d.plan.copy(sets = listOf(a, b)))
    }

    @Test
    fun scalingKgTargetsMovesEveryMatchingSetAndLeavesTheRestAlone() = runTest {
        val w = makeWorld()
        val crimp = GripSpec(20, FingerSet.four, GripPosition.halfCrimp)
        val drag = GripSpec(20, FingerSet.frontTwo, GripPosition.drag)

        val morning = assertNotNull(w.store.create(kgTargetDraft("Morning", crimp, drag)))
        val evening = assertNotNull(w.store.create(kgTargetDraft("Evening", crimp, drag)))
        val declined = assertNotNull(w.store.create(kgTargetDraft("Rest day", crimp, drag)))

        assertTrue(w.store.scaleKgTargets(crimp, 1.1, listOf(morning.id, evening.id)))

        for (template in listOf(morning, evening)) {
            val sets = assertNotNull(w.store.routine(template.id)).plan.sets
            // Half-kilogram rounding, same as the percent path: 24 × 1.1 = 26.4 → 26.5.
            assertEquals(22.0, sets[0].targetLoKg, "${template.name} lower bound")
            assertEquals(26.5, sets[0].targetHiKg, "${template.name} upper bound")
            assertEquals(10.0, sets[1].targetLoKg, "a set on another grip never moves")
            assertEquals(12.0, sets[1].targetHiKg)
        }
        assertEquals(
            20.0, assertNotNull(w.store.routine(declined.id)).plan.sets[0].targetLoKg,
            "a routine the user left out of the offer never moves",
        )
    }

    /// It used to route through `save(_)`, the builder's entry point, which clears the
    /// rescue stash on success. So accepting a rescale from the Maxes tab silently threw
    /// away a half-built routine someone had left unsaved in the builder.
    @Test
    fun scalingKgTargetsLeavesTheBuilderRescueCopyAlone() = runTest {
        val w = makeWorld()
        val crimp = GripSpec(20, FingerSet.four, GripPosition.halfCrimp)
        val drag = GripSpec(20, FingerSet.frontTwo, GripPosition.drag)
        val saved = assertNotNull(w.store.create(kgTargetDraft("Morning", crimp, drag)))

        w.store.stashDraft(RoutineDraft.blank("Half-written"))
        assertTrue(w.store.scaleKgTargets(crimp, 1.1, listOf(saved.id)))

        assertEquals("Half-written", w.store.restoreDraft()?.plan?.name)
    }

    @Test
    fun scalingKgTargetsRefusesANonsenseRatioAndIgnoresAnUnknownRoutine() = runTest {
        val w = makeWorld()
        val crimp = GripSpec(20, FingerSet.four, GripPosition.halfCrimp)
        val drag = GripSpec(20, FingerSet.frontTwo, GripPosition.drag)
        val saved = assertNotNull(w.store.create(kgTargetDraft("Morning", crimp, drag)))

        assertFalse(w.store.scaleKgTargets(crimp, 0.0, listOf(saved.id)))
        assertFalse(w.store.scaleKgTargets(crimp, Double.NaN, listOf(saved.id)))
        assertEquals(
            20.0, assertNotNull(w.store.routine(saved.id)).plan.sets[0].targetLoKg,
            "nothing moved",
        )

        // Deleted between the offer and the tap: there is nothing to scale, and that is
        // not a failure.
        assertTrue(w.store.scaleKgTargets(crimp, 1.1, listOf(UUID.randomUUID())))
        assertEquals(20.0, assertNotNull(w.store.routine(saved.id)).plan.sets[0].targetLoKg)
    }

    // MARK: - Failure handling

    /// The sheet used to dismiss unconditionally, so a failed write closed the form over a
    /// routine that no longer existed. Memory must match disk, and the caller must be told.
    @Test
    fun saveFailureRollsBackAndPublishesSaveError() = runTest {
        val w = makeWorld(allowsSave = false)

        val created = w.store.create(RoutineDraft.starter)

        assertNull(created, "if this passes, the refusing gateway is no longer a seam")
        assertNotNull(w.store.saveError)
        assertEquals(0, routines(w).size, "the rolled-back insert must not linger")
    }

    /// A read that FAILED is not the same fact as "there are no routines". Collapsing the
    /// two is what let one bad read blank the Today card in the sibling app.
    ///
    /// NOTE: the fetch-failure path is reachable here in a way it is not on iOS — a
    /// gateway can simply refuse — but the invariant asserted is the same one: a rejected
    /// mutation must leave the derived world exactly as it was, never blanked.
    @Test
    fun aFailedFetchDoesNotPublishAnEmptyWorld() = runTest {
        val w = makeWorld()
        val today = DayStamp.today()
        val template = assertNotNull(w.store.create(RoutineDraft.starter))
        insertLog(w, template, today)
        insertLog(w, template, today)

        val completionsBefore = w.store.completionsToday
        val consistencyBefore = w.store.consistency
        assertEquals(2, completionsBefore[template.id])
        assertEquals(14, consistencyBefore.size)

        // A routine that is not in the store — the shape a merge or a concurrent delete
        // leaves behind — is refused, and the refusal must not disturb what is published.
        val orphan = SessionTemplateEntity.from(RoutineDraft.blank("Orphan"), sortIndex = 0)
        assertFalse(w.store.update(orphan, RoutineDraft.starter))
        assertFalse(w.store.delete(orphan))

        assertEquals(completionsBefore, w.store.completionsToday)
        assertEquals(consistencyBefore, w.store.consistency)
        assertEquals(14, w.store.consistency.size)
    }

    /// Mutating a row a concurrent delete already removed is refused rather than silently
    /// resurrecting it — an upsert would INSERT it again, which is worse than iOS's crash
    /// because nobody would ever see it.
    @Test
    fun mutatingAFaultedTemplateIsRefusedRatherThanCrashing() = runTest {
        val w = makeWorld()
        val orphan = SessionTemplateEntity.from(RoutineDraft.blank("Orphan"), sortIndex = 0)
        assertNull(w.store.routine(orphan.id))

        assertFalse(w.store.update(orphan, RoutineDraft.starter))
        assertNull(w.store.duplicate(orphan))
        assertFalse(w.store.delete(orphan))

        assertNull(w.store.saveError)
        assertNull(w.store.lastDeleted)
        assertEquals(0, routines(w).size)
    }

    // MARK: - Device-local suggestions and draft rescue

    /// Rung 2 of Today's selection rule is device-local and expires with the day, so a new
    /// morning re-asserts the primary routine rather than reopening last night's.
    @Test
    fun suggestedRoutineExpiresWithTheDay() = runTest {
        val w = makeWorld()
        val today = DayStamp.today()
        val daily = assertNotNull(w.store.create(RoutineDraft.starter))
        val rest = assertNotNull(w.store.create(RoutineDraft.blank("Rest day")))
        assertNull(w.store.suggestedRoutineID)

        w.store.noteSessionStarted(rest)
        assertEquals(rest.id, w.store.suggestedRoutineID)
        assertNotEquals(daily.id, w.store.suggestedRoutineID)

        w.clock.advance(today + 1)
        w.store.refreshIfDayChanged()
        assertNull(w.store.suggestedRoutineID)
    }

    @Test
    fun draftStashIsClearedOnSaveAndOnCancel() = runTest {
        val w = makeWorld()
        val rescued = RoutineDraft.blank("Rescue me")

        w.store.stashDraft(rescued)
        assertEquals("Rescue me", w.store.restoreDraft()?.plan?.name)
        w.store.save(rescued)
        assertNull(w.store.restoreDraft(), "a saved draft has nothing left to rescue")

        w.store.stashDraft(rescued)
        assertNotNull(w.store.restoreDraft())
        w.store.clearDraft()
        assertNull(w.store.restoreDraft(), "cancel discards it too")
    }

    // MARK: - Draft normalization

    /// Lowering the count must not destroy a time the user customised: 19:15 comes back as
    /// 19:15, not as the ladder's 19:00.
    @Test
    fun loweringSessionsPerDayParksTimesAndRaisingRestoresThem() {
        var draft = RoutineDraft.starter
        draft = draft.copy(
            reminders = listOf(
                ReminderTime(hour = 8, minute = 0),
                ReminderTime(hour = 19, minute = 15),
            )
        )

        draft = draft.setSessionsPerDay(1)
        assertEquals(1, draft.sessionsPerDay)
        assertEquals(listOf(ReminderTime(hour = 8, minute = 0)), draft.reminders)
        assertEquals(listOf(ReminderTime(hour = 19, minute = 15)), draft.parkedReminders)

        draft = draft.setSessionsPerDay(2)
        assertEquals(
            listOf(ReminderTime(hour = 8, minute = 0), ReminderTime(hour = 19, minute = 15)),
            draft.reminders,
        )
        assertEquals(emptyList(), draft.parkedReminders)

        assertEquals(4, draft.setSessionsPerDay(9).sessionsPerDay, "clamped 1..4")
        assertEquals(1, draft.setSessionsPerDay(0).sessionsPerDay)
    }

    @Test
    fun normalizedDedupesAndSortsRemindersAndSubstitutesAnEmptyName() {
        var draft = RoutineDraft.blank()
        val upsideDown = SetPlan(
            grip = GripSpec(20, FingerSet.four, GripPosition.halfCrimp),
            repsPerSide = 4, targetLoKg = 12.0, targetHiKg = 8.0,
        )
        draft = draft.copy(
            plan = draft.plan.copy(
                name = "   ",
                sets = listOf(
                    upsideDown,
                    SetPlan(
                        grip = GripSpec(18, FingerSet.frontTwo, GripPosition.drag),
                        repsPerSide = 0,
                    ),
                ),
            ),
            reminders = listOf(
                ReminderTime(hour = 19, minute = 0),
                ReminderTime(hour = 8, minute = 0),
                ReminderTime(hour = 8, minute = 0),
            ),
            sessionsPerDay = 9,
        )

        // The computed band is already the right way up before normalization…
        assertEquals(8.0..12.0, upsideDown.targetBand)

        val clean = draft.normalized
        assertEquals("Daily no-hangs", clean.plan.name)
        assertEquals(
            listOf(ReminderTime(hour = 8, minute = 0), ReminderTime(hour = 19, minute = 0)),
            clean.reminders,
        )
        assertEquals(1, clean.plan.sets.size, "a zero-rep set is not a routine row")
        // …and normalization is what stops it from being STORED inverted.
        assertEquals(8.0, clean.plan.sets[0].targetLoKg)
        assertEquals(12.0, clean.plan.sets[0].targetHiKg)
        assertEquals(4, clean.sessionsPerDay)
        assertNull(clean.validationIssue)
    }
}

/// Reads pass through; every WRITE throws.
///
/// The Android twin of iOS's read-only `ModelConfiguration`, and it refuses at exactly the
/// same moment — the commit. The long comment on the Swift `makeWorld` explains why
/// `isStoredInMemoryOnly` could not be used there; none of that applies here, because the
/// refusal is a wrapper rather than a property of the file.
private class RefusingWriteGateway(private val inner: StoreGateway) : StoreGateway by inner {
    override suspend fun write(work: suspend (StoreWriter) -> Unit) {
        throw IllegalStateException("this store is read-only")
    }
}

// MARK: - The summary's edge line and signature grip

/// Pure value tests — `RoutineSummary` derives both from its own ladder, so no store world
/// is needed and none is built. (Same Swift file, hence the same Kotlin file.)
class RoutineSummaryValueTests {

    private fun summary(
        edges: List<Int>,
        fingers: List<FingerSet>? = null,
        reps: List<Int>? = null,
    ): RoutineSummary {
        val sets = fingers ?: List(edges.size) { FingerSet.four }
        val pulls = reps ?: List(edges.size) { 1 }
        val ladder = edges.indices.map { index ->
            LadderRung(
                id = index,
                grip = GripSpec(edges[index], sets[index]),
                repsPerSide = pulls[index],
            )
        }
        return RoutineSummary(
            id = UUID.randomUUID(), name = "T", ladder = ladder,
            setCount = ladder.size, totalReps = ladder.size,
            sharedEdgeMM = if (edges.toSet().size == 1) edges.firstOrNull() else null,
            estimatedSeconds = 60, sessionsPerDay = 1,
            completedToday = 0, nextReminder = null,
        )
    }

    @Test
    fun aOneEdgeRoutineStatesTheEdgePlainly() {
        assertEquals("20 mm", summary(listOf(20, 20, 20)).edgeLine)
    }

    /// The span runs in LADDER order — "20–10" for a protocol that starts deep and thins
    /// out (Nuri's own phrasing of the fix) — because it describes the routine's
    /// direction, not an interval on a number line.
    @Test
    fun aMixedLadderStatesItsSpanInLadderOrder() {
        assertEquals("20–10 mm", summary(listOf(20, 15, 10)).edgeLine)
        assertEquals("10–20 mm", summary(listOf(10, 15, 20)).edgeLine)
    }

    /// A first edge that is neither extreme has no directional claim, so the span falls
    /// back to ascending rather than inventing one.
    @Test
    fun aFirstEdgeThatIsNeitherExtremeFallsBackToAscending() {
        assertEquals("10–20 mm", summary(listOf(15, 20, 10)).edgeLine)
    }

    /// The old behaviour this replaces: `sharedEdgeMM` DROPPED the edge from `metaLine`
    /// the moment sets disagreed, which read as the app not knowing its own routine. The
    /// sentence now opens with the span.
    @Test
    fun metaLineCarriesTheSpanInsteadOfGoingSilent() {
        assertTrue(summary(listOf(20, 10)).metaLine.startsWith("20–10 mm · "))
    }

    @Test
    fun theSignatureGripIsTheOneMostPullsTrain() {
        val s = summary(
            listOf(20, 20, 20),
            listOf(FingerSet.frontTwo, FingerSet.four, FingerSet.frontTwo),
        )
        assertEquals(FingerSet.frontTwo, s.signatureFingers)
    }

    /// **Weighted by pulls, never by set count.** Nuri's Daily burn tapers through the
    /// small grips as short sets — two front-2 and two middle-2 SETS against one
    /// four-finger set — and on its first hardware day the card called his
    /// mostly-four-finger routine a two-finger one. Twelve four-finger pulls outweigh six
    /// front-2 pulls, whatever the set count says.
    @Test
    fun shortTaperSetsCannotOutvoteThePullMass() {
        val s = summary(
            listOf(20, 20, 20, 20, 10, 10),
            listOf(
                FingerSet.four, FingerSet.frontThree, FingerSet.frontTwo,
                FingerSet.middleTwo, FingerSet.frontTwo, FingerSet.middleTwo,
            ),
            listOf(6, 6, 2, 2, 1, 1),
        )
        assertEquals(FingerSet.four, s.signatureFingers)
    }

    /// A tie goes to the ladder's FIRST rung — the grip the session opens on — so the mark
    /// cannot flip between builds over map ordering.
    @Test
    fun aSignatureTieGoesToTheOpeningGrip() {
        val s = summary(listOf(20, 20), listOf(FingerSet.backTwo, FingerSet.four))
        assertEquals(FingerSet.backTwo, s.signatureFingers)
    }

    @Test
    fun anEmptyLadderHasNoSignatureAndNoEdgeLine() {
        assertNull(summary(emptyList()).signatureFingers)
        assertNull(summary(emptyList()).edgeLine)
    }
}
