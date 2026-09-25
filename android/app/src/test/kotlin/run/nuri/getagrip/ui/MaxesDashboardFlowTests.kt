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
import run.nuri.getagrip.ui.l10n.tr
import run.nuri.getagrip.ui.maxes.MaxesListScreen
import run.nuri.getagrip.ui.maxes.MaxesTabScreen
import run.nuri.getagrip.ui.theme.GetAGripTheme
import run.nuri.getagrip.ui.units.WeightUnit
import run.nuri.getagrip.ui.units.WeightUnits
import java.time.Instant
import kotlin.test.assertEquals

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w393dp-h820dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class MaxesDashboardFlowTests {
    @get:Rule val compose = createComposeRule()
    private val primary = GripSpec()
    private val other = GripSpec(15, FingerSet.frontThree, GripPosition.fullCrimp)
    private val originalUnits = WeightUnits.current
    private val originalLookup = L10n.lookup
    private var world: World? = null

    private class World(rows: List<MaxRecordEntity>) {
        val db = GetAGripDatabase.inMemory(RuntimeEnvironment.getApplication())
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        val feedScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        private val gateway = RoomStoreGateway(db)
        val store = TemplateStore(gateway, DayClock(), InMemoryRoutineSettings(), RecordingAlarmScheduler(), scope)
        // These tests exercise routes and filtering over a fixed history. Keep fixture
        // publication synchronous; Room/feed scheduling has separate store tests.
        val feed = HistoryFeed(object : HistorySource {
            override suspend fun allLogs() = emptyList<WorkoutLogEntity>()
            override suspend fun allMaxes() = rows
        }, feedScope, processingDispatcher = Dispatchers.Unconfined).also { it.refresh() }
        init { runBlocking {
            rows.forEach { db.maxes().upsert(it) }
            store.syncDerived()
        } }
        fun recordCount(): Int = runBlocking { db.maxes().all().size }
    }

    @After fun close() {
        world?.let { it.scope.cancel(); it.feedScope.cancel(); it.db.close() }
        WeightUnits.current = originalUnits
        L10n.lookup = originalLookup
    }

    private fun row(grip: GripSpec, side: Side, kg: Double, age: Long = 0) = MaxRecordEntity.from(
        grip, kg, MaxSource.measured, side, Instant.parse("2026-09-12T10:00:00Z").minusSeconds(age))

    private fun show(rows: List<MaxRecordEntity>, fontScale: Float = 1f,
                     content: @Composable (HistoryFeed) -> Unit): World {
        val w = World(rows).also { world = it }
        WeightUnits.current = WeightUnit.kg
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalTemplateStore provides w.store, LocalHistoryFeed provides w.feed,
                LocalDensity provides Density(density.density, fontScale)) {
                GetAGripTheme(darkTheme = false) { content(w.feed) }
            }
        }
        compose.waitForIdle()
        return w
    }

    private fun action(tag: String) {
        compose.onNode(hasScrollToIndexAction()).performScrollToNode(hasTestTag(tag))
        compose.onNodeWithTag(tag).assertIsDisplayed().performClick()
    }

    @Test fun toolbarPlusAndGripActionsCarryExactIdentityAndAlwaysBeginWithLeft() {
        val adds = mutableListOf<GripSpec?>()
        val measures = mutableListOf<Pair<GripSpec, Side>>()
        val edits = mutableListOf<GripSpec>()
        val w = show(listOf(row(primary, Side.both, 70.0), row(other, Side.right, 24.0, 60))) { feed ->
            MaxesTabScreen(onAddMax = { adds += it }, onMeasure = { grip, side -> measures += grip to side },
                onEdit = { edits += it }, feed = feed)
        }
        compose.onNodeWithTag("maxes.add").assertIsDisplayed().performClick()
        compose.onNodeWithTag("maxes.add.max").assertIsDisplayed().performClick()
        compose.onNodeWithText("Manage").assertDoesNotExist()
        capture("android-maxes-dashboard.png")
        listOf(primary, other).forEach { grip ->
            action("maxes.measure.${grip.key}")
            compose.onNodeWithTag("max.mode.hands").assertIsDisplayed().performClick()
            action("maxes.edit.${grip.key}")
        }
        compose.runOnIdle {
            assertEquals(listOf<GripSpec?>(null), adds)
            assertEquals(listOf(primary to Side.left, other to Side.left), measures)
            assertEquals(listOf(primary, other), edits)
        }
        assertEquals(2, w.recordCount(), "Opening any dashboard action must not write a max")
    }

    @Test fun gripHistoryKeepsBothHandsAndEarlierRowsWhileHidingOtherGripsAndAdd() {
        var added = false
        val w = show(listOf(row(other, Side.both, 99.0), row(primary, Side.left, 25.0, 60),
            row(primary, Side.right, 28.0, 120), row(primary, Side.left, 22.0, 180))) { feed ->
            MaxesListScreen(onAddMax = { added = true }, feed = feed, grip = primary)
        }
        compose.onNodeWithText("Add a max").assertDoesNotExist()
        compose.onAllNodes(hasContentDescription(primary.spoken, substring = true)).assertCountEquals(2)
        compose.onNode(hasContentDescription(other.spoken, substring = true)).assertDoesNotExist()
        compose.onNodeWithText("99.0").assertDoesNotExist()
        compose.onNode(hasContentDescription(primary.spoken, substring = true) and hasClickAction()).performClick()
        compose.onAllNodes(hasContentDescription(primary.spoken, substring = true)).assertCountEquals(3)
        compose.onNodeWithContentDescription("Earlier max for", substring = true).assertIsDisplayed()
        compose.onNodeWithText("Add a max").assertDoesNotExist()
        compose.runOnIdle { assertEquals(false, added) }
        assertEquals(4, w.recordCount())
    }

    @Test
    @Config(qualifiers = "fr-w393dp-h820dp-mdpi")
    fun frenchLargeTextKeepsPlusAndPerGripActionsReachable() {
        val context = RuntimeEnvironment.getApplication()
        L10n.lookup = { context.tr(it) }
        var additions = 0
        var measured: Pair<GripSpec, Side>? = null
        var edited: GripSpec? = null
        show(listOf(row(primary, Side.right, 24.0)), fontScale = 2f) { feed ->
            MaxesTabScreen(onAddMax = { additions += 1 }, onMeasure = { grip, side -> measured = grip to side },
                onEdit = { edited = it }, feed = feed)
        }
        compose.onNodeWithContentDescription(context.tr("Add a benchmark")).assertIsDisplayed().performClick()
        compose.onNodeWithText(context.tr("Measure a max")).assertIsDisplayed().performClick()
        capture("android-maxes-dashboard-french-large.png")
        action("maxes.measure.${primary.key}")
        compose.onNodeWithText(context.tr("Max, one hand at a time")).assertIsDisplayed().performClick()
        action("maxes.edit.${primary.key}")
        capture("android-maxes-dashboard-french-large-actions.png")
        compose.runOnIdle {
            assertEquals(1, additions)
            assertEquals(primary to Side.left, measured)
            assertEquals(primary, edited)
        }
    }

    /// The "+" is a menu of both measurements, and Measure on a grip asks which one.
    @Test fun measureAsksMaxOrCriticalForceAndThePlusOffersBoth() {
        val measures = mutableListOf<Pair<GripSpec, Side>>()
        val tests = mutableListOf<Pair<GripSpec, CriticalForceHands>>()
        var adds = 0
        val w = show(listOf(row(primary, Side.both, 70.0), row(other, Side.right, 24.0, 60))) { feed ->
            MaxesTabScreen(onAddMax = { adds += 1 }, onMeasure = { grip, side -> measures += grip to side },
                onEdit = {}, feed = feed, onCriticalForce = { grip, hands -> tests += grip to hands })
        }
        action("maxes.measure.${primary.key}")
        compose.onNodeWithText("What are you measuring?").assertIsDisplayed()
        compose.onNodeWithTag("max.mode.both").performClick()
        action("maxes.measure.${other.key}")
        compose.onNodeWithTag("max.mode.criticalForce").performClick()
        // With no test on file, the "+" opens the routines' first grip, one hand at a time.
        compose.onNodeWithTag("maxes.add").performClick()
        compose.onNodeWithTag("maxes.add.criticalForce").assertIsDisplayed().performClick()
        compose.runOnIdle {
            assertEquals(0, adds)
            assertEquals(listOf(primary to Side.both), measures)
            assertEquals(listOf<Pair<GripSpec, CriticalForceHands>>(
                other to CriticalForceHands.OneAtATime(Side.left),
                (w.store.recentGrips.firstOrNull() ?: GripSpec()) to CriticalForceHands.OneAtATime(Side.left),
            ), tests)
        }
    }

    private fun capture(name: String) {
        val file = java.io.File("../../build/review/max-hand/$name").canonicalFile
        file.parentFile?.mkdirs()
        file.outputStream().use {
            compose.onRoot().captureToImage().asAndroidBitmap()
                .compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
        }
    }
}
