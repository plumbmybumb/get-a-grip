// SPDX-License-Identifier: MPL-2.0
// Original contributions Copyright 2026 Nuri Bruner.
package run.nuri.getagrip.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.unit.Density
import kotlinx.coroutines.*
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import run.nuri.getagrip.data.GetAGripDatabase
import run.nuri.getagrip.data.MaxRecordEntity
import run.nuri.getagrip.data.WorkoutLogEntity
import run.nuri.getagrip.engine.*
import run.nuri.getagrip.store.*
import run.nuri.getagrip.ui.history.HistoryScreen
import run.nuri.getagrip.ui.history.SessionDetailContent
import run.nuri.getagrip.ui.history.SessionDetailContent.RecordFact
import run.nuri.getagrip.ui.theme.GetAGripTheme
import run.nuri.getagrip.ui.units.WeightUnit
import run.nuri.getagrip.ui.units.WeightUnits
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/// A past workout opens from History (support request, Gleb, 1.4.0: "I can't open each one
/// to see the insights"): the frozen log read back, Delete through the store's Undo, and the
/// one recovered link — which maxes the session saved — claimed only on frozen facts.
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w393dp-h820dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SessionDetailFlowTests {
    @get:Rule val compose = createComposeRule()
    private val grip = GripSpec()
    private val zone = ZoneOffset.UTC
    private val finished = Instant.parse("2026-10-30T20:20:00Z")
    private val originalUnits = WeightUnits.current
    private var world: World? = null

    private class World(logs: List<WorkoutLogEntity>, maxes: List<MaxRecordEntity>) {
        val db = GetAGripDatabase.inMemory(RuntimeEnvironment.getApplication())
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        val feedScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        private val gateway = RoomStoreGateway(db)
        val clock = DayClock()
        val store = TemplateStore(gateway, clock, InMemoryRoutineSettings(), RecordingAlarmScheduler(), scope)
        val feed = HistoryFeed(gateway.asHistorySource(), feedScope, processingDispatcher = Dispatchers.Unconfined)
        init { runBlocking {
            logs.forEach { db.logs().upsert(it) }
            maxes.forEach { db.maxes().upsert(it) }
            store.syncDerived()
            feed.refresh()
        } }
        fun logCount(): Int = runBlocking { db.logs().all().size }
    }

    @After fun close() {
        world?.let { it.scope.cancel(); it.feedScope.cancel(); it.db.close() }
        WeightUnits.current = originalUnits
    }

    private fun rep(index: Int, side: Side, peak: Double, outcome: RepOutcome = RepOutcome.completed) =
        RepSummary(setIndex = 0, repIndex = index, side = side, grip = grip, targetSeconds = 10,
            heldSeconds = if (outcome == RepOutcome.completed) 10.0 else 0.0, peakKg = peak,
            avgKg = if (peak > 0) peak - 1 else 0.0, outcome = outcome)

    private fun measuredLog(peak: Double = 33.4): Pair<WorkoutLogEntity, List<RepSummary>> {
        val plan = SessionPlan(name = "Daily no-hangs", sets = listOf(SetPlan(grip = grip, repsPerSide = 2)))
        val reps = listOf(rep(0, Side.left, peak), rep(1, Side.right, 22.6),
            rep(2, Side.left, 0.0, RepOutcome.skipped), rep(3, Side.right, 23.0))
        val log = WorkoutLogEntity.from(plan, null, "Daily no-hangs", 2, reps,
            finished.minusSeconds(600), finished, DayStamp.trainingDayOf(finished))
        return log.copy(rpe = RPE.hard.rawValue) to reps
    }

    private fun show(logs: List<WorkoutLogEntity>, maxes: List<MaxRecordEntity> = emptyList(),
                     darkTheme: Boolean = false, fontScale: Float = 1f): World {
        val w = World(logs, maxes).also { world = it }
        WeightUnits.current = WeightUnit.kg
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalTemplateStore provides w.store, LocalHistoryFeed provides w.feed,
                LocalDayClock provides w.clock, LocalDensity provides Density(density.density, fontScale)) {
                GetAGripTheme(darkTheme = darkTheme) { HistoryScreen(onLogSession = {}, feed = w.feed) }
            }
        }
        compose.waitForIdle()
        return w
    }

    private fun openRow(index: Int) {
        compose.onNode(hasScrollToIndexAction()).performScrollToNode(hasTestTag("history.session"))
        compose.onAllNodesWithTag("history.session")[index].assertHasClickAction().performClick()
        compose.waitForIdle()
    }

    // MARK: - The recovered max link

    private fun fact(side: Side, kg: Double, after: Long = 30, measured: Boolean = true, seconds: Int = 0,
                     grip: GripSpec = this.grip) =
        RecordFact(grip, side, kg, seconds, measured, finished.plusSeconds(after))

    private fun saved(reps: List<RepSummary>, records: List<RecordFact>, kind: SessionKind = SessionKind.hang) =
        SessionDetailContent.savedMaxes(kind, DayStamp.trainingDayOf(finished, zone), finished, reps, records, zone)

    @Test fun aMaxCopiedFromACompletedPullOfThatHandIsThisSessions() {
        val reps = listOf(rep(0, Side.left, 31.25), rep(1, Side.right, 28.5))
        val found = saved(reps, listOf(fact(Side.left, 31.25)))
        assertEquals(listOf(31.25), found.map { it.kg })
        assertEquals(Side.left, found.single().side)
    }

    @Test fun aNearMissIsNotClaimed() {
        val reps = listOf(rep(0, Side.left, 31.25))
        assertTrue(saved(reps, listOf(fact(Side.right, 31.25))).isEmpty())
        assertTrue(saved(reps, listOf(fact(Side.left, 31.25, grip = GripSpec(15)))).isEmpty())
        assertTrue(saved(reps, listOf(fact(Side.left, 31.2))).isEmpty())
        assertTrue(saved(listOf(rep(0, Side.left, 31.25, RepOutcome.skipped)), listOf(fact(Side.left, 31.25))).isEmpty())
        assertTrue(saved(reps, listOf(fact(Side.left, 31.25, measured = false))).isEmpty())
        assertTrue(saved(reps, listOf(fact(Side.left, 31.25, seconds = 10))).isEmpty())
        assertTrue(saved(reps, listOf(fact(Side.left, 31.25, after = -3600))).isEmpty())
        assertTrue(saved(reps, listOf(fact(Side.left, 31.25, after = 2 * 86_400))).isEmpty())
    }

    @Test fun handLoggedSessionsClaimNoMaxesAndABenchmarkListsItsDay() {
        for (kind in listOf(SessionKind.hangManual, SessionKind.climbVolume, SessionKind.climbLimit)) {
            assertTrue(saved(emptyList(), listOf(fact(Side.left, 31.25)), kind).isEmpty(), kind.name)
        }
        val day = saved(emptyList(), listOf(fact(Side.left, 30.0), fact(Side.right, 32.0, after = 600),
            fact(Side.both, 40.0, measured = false), fact(Side.both, 50.0, after = 3 * 86_400)), SessionKind.benchmark)
        assertEquals(listOf(32.0, 30.0), day.map { it.kg })
    }

    @Test fun theSnapshotCopiesTheLogAndNeverInventsALoad() {
        val plan = SessionPlan(name = "R", sets = listOf(SetPlan(grip = grip, repsPerSide = 2)), handMode = HandMode.bothHands)
        val reps = listOf(rep(0, Side.both, 0.0), rep(1, Side.both, 0.0, RepOutcome.skipped))
        val log = WorkoutLogEntity.from(plan, null, "R", 1, reps, finished.minusSeconds(120), finished,
            DayStamp.trainingDayOf(finished))
        val content = SessionDetailContent.of(log, "Renamed", reps, emptyList())
        assertEquals("Renamed", content.name)
        assertEquals(1, content.completedReps)
        assertEquals(PlanMath.totalReps(plan), content.plannedReps)
        assertEquals(2, content.minutes)
        assertFalse(content.measured, "a timer-only run read no load")
        val progress = content.progress!!
        assertEquals(PlanMath.totalReps(plan), progress.setSizes.sum())
        assertEquals(listOf(true, false), progress.finished)
        assertNull(progress.current, "a record has no pull in progress")
        val climb = WorkoutLogEntity.logged(SessionKind.climbLimit, DayStamp.trainingDayOf(finished), finished, 1, minutes = 90)
        assertNull(SessionDetailContent.of(climb, "x", emptyList(), emptyList()).progress)
    }

    // MARK: - The flow

    /// Tap a measured session: the summary's numbers, the max it saved, every pull — and
    /// Delete pops back to History with the house Undo, no dialog.
    @Test fun aRowOpensItsWorkoutAndDeleteReturnsWithUndo() {
        val (log, reps) = measuredLog()
        val max = MaxRecordEntity.from(grip, reps.first().peakKg, MaxSource.measured, Side.left, finished.plusSeconds(40))
        val w = show(listOf(log), listOf(max))
        openRow(0)
        compose.onNodeWithTag("sessionDetail.export").assertIsDisplayed()
        compose.onNodeWithContentDescription("Export workout").assertIsDisplayed()
        compose.onNodeWithContentDescription("3 of 4 pulls completed", substring = true)
            .assertIsDisplayed().assertContentDescriptionContains("peak 33.4", substring = true)
        compose.onNodeWithContentDescription("Left hand · ${grip.shortName} · 33.4 kg", substring = true).assertExists()
        compose.onNodeWithText("Save and finish").assertDoesNotExist()
        compose.onNode(hasScrollAction()).performScrollToNode(hasContentDescription("Skipped", substring = true))
        compose.onNodeWithContentDescription("Skipped", substring = true)
            .assertContentDescriptionContains("held 0.0 of 10 seconds", substring = true)
        capture("android-session-detail-measured-sets.png")

        compose.onNodeWithTag("sessionDetail.delete").performClick()
        compose.waitUntil(5_000) { w.logCount() == 0 }
        compose.waitUntil(5_000) { compose.onAllNodesWithText("Session deleted").fetchSemanticsNodes().isNotEmpty() }
        compose.onAllNodesWithTag("sessionDetail.export").assertCountEquals(0)
        compose.onNodeWithText("Undo").performClick()
        compose.waitUntil(5_000) { w.logCount() == 1 }
    }

    @Test fun aClimbShowsOnlyWhatWasLogged() {
        val climb = WorkoutLogEntity.logged(SessionKind.climbLimit, DayStamp.trainingDayOf(finished), finished, 2,
            minutes = 105, rpe = RPE.maximal, fingerStrain = FingerStrain.taxed, notes = "Projected the crimpy 7A.")
        show(listOf(climb))
        openRow(0)
        compose.onNodeWithTag("sessionDetail.delete").assertIsDisplayed()
        compose.onNodeWithContentDescription("Delete workout").assertIsDisplayed()
        compose.onAllNodesWithText("Sets").assertCountEquals(0)
        compose.onAllNodesWithContentDescription("pulls completed", substring = true).assertCountEquals(0)
        compose.onNodeWithContentDescription("Duration, 1 hour, 45 minutes").assertIsDisplayed()
        compose.onNodeWithText("Projected the crimpy 7A.").assertIsDisplayed()
    }

    @Test fun screenshots() {
        val (log, reps) = measuredLog()
        val max = MaxRecordEntity.from(grip, reps.first().peakKg, MaxSource.measured, Side.left, finished.plusSeconds(40))
        val timer = measuredLog(0.0).first.let { it.copy(id = java.util.UUID.randomUUID(), peakKg = 0.0, avgKg = 0.0,
            startedAt = it.startedAt.plusSeconds(1800), finishedAt = it.finishedAt.plusSeconds(1800)) }
        val manual = WorkoutLogEntity.logged(SessionKind.hangManual, DayStamp.trainingDayOf(finished),
            finished.plusSeconds(3600), 2, minutes = 25, rpe = RPE.comfortable, fingerStrain = FingerStrain.worked)
        val climb = WorkoutLogEntity.logged(SessionKind.climbLimit, DayStamp.trainingDayOf(finished),
            finished.plusSeconds(5400), 2, minutes = 105, rpe = RPE.maximal, fingerStrain = FingerStrain.taxed,
            notes = "Projected the crimpy 7A in the cave.")
        val names = listOf("climb", "hand-logged", "timer-only", "measured")
        for (dark in listOf(false, true)) for (scale in listOf(1f, 2f)) for (index in names.indices) {
            world?.let { it.scope.cancel(); it.feedScope.cancel(); it.db.close() }
            world = null
            showAgain(listOf(log, timer, manual, climb), listOf(max), dark, scale)
            openRow(index)
            capture("android-session-detail-${names[index]}-${if (dark) "dark" else "light"}-${if (scale > 1f) "ax" else "large"}.png")
        }
    }

    /// `setContent` may only be called once per rule; the screenshot sweep swaps the world
    /// and theme underneath one composition instead.
    private var swap: ((World, Boolean, Float) -> Unit)? = null

    private fun showAgain(logs: List<WorkoutLogEntity>, maxes: List<MaxRecordEntity>, dark: Boolean, scale: Float) {
        val w = World(logs, maxes).also { world = it }
        WeightUnits.current = WeightUnit.kg
        val current = swap
        if (current != null) {
            compose.runOnUiThread { current(w, dark, scale) }
        } else {
            compose.setContent {
                val state = androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(Triple(w, dark, scale)) }
                swap = { nw, nd, ns -> state.value = Triple(nw, nd, ns) }
                val (sw, sd, ss) = state.value
                val density = LocalDensity.current
                androidx.compose.runtime.key(sw, sd, ss) {
                    CompositionLocalProvider(LocalTemplateStore provides sw.store, LocalHistoryFeed provides sw.feed,
                        LocalDayClock provides sw.clock, LocalDensity provides Density(density.density, ss)) {
                        GetAGripTheme(darkTheme = sd) { HistoryScreen(onLogSession = {}, feed = sw.feed) }
                    }
                }
            }
        }
        compose.waitForIdle()
    }

    private fun capture(name: String) {
        val file = java.io.File("../../build/review/session-detail/$name").canonicalFile
        file.parentFile?.mkdirs()
        file.outputStream().use {
            compose.onRoot().captureToImage().asAndroidBitmap()
                .compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
        }
    }
}
