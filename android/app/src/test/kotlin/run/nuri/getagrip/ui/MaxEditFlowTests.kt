// SPDX-License-Identifier: MPL-2.0
// Original contributions Copyright 2026 Nuri Bruner.
package run.nuri.getagrip.ui

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
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
import run.nuri.getagrip.data.SessionTemplateEntity
import run.nuri.getagrip.engine.*
import run.nuri.getagrip.store.*
import run.nuri.getagrip.ui.maxes.MaxEditSheet
import run.nuri.getagrip.ui.maxes.NewMaxDraft
import run.nuri.getagrip.ui.maxes.NewMaxSheet
import run.nuri.getagrip.ui.theme.GetAGripTheme
import run.nuri.getagrip.ui.units.WeightUnit
import run.nuri.getagrip.ui.units.WeightUnits
import java.time.Instant
import kotlin.test.assertEquals

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w393dp-h820dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class MaxEditFlowTests {
    @get:Rule val compose = createComposeRule()
    private val grip = GripSpec()
    private var world: World? = null

    private class World(val rows: List<MaxRecordEntity>, routine: RoutineDraft? = null) {
        val db = GetAGripDatabase.inMemory(RuntimeEnvironment.getApplication())
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        private val room = RoomStoreGateway(db)
        var refuseWrites = false
        val gateway = object : StoreGateway by room {
            override suspend fun write(work: suspend (StoreWriter) -> Unit) {
                if (refuseWrites) error("Test write refused")
                room.write(work)
            }
        }
        val store = TemplateStore(gateway, DayClock(), InMemoryRoutineSettings(), RecordingAlarmScheduler(), scope)
        val feed = HistoryFeed(gateway.asHistorySource(), scope)
        init { runBlocking {
            rows.forEach { db.maxes().upsert(it) }
            routine?.let { db.routines().upsert(SessionTemplateEntity.from(it.normalized, 0)) }
            store.syncDerived()
        } }
        fun records(): List<MaxRecordEntity> = runBlocking { db.maxes().all() }
    }

    @After fun close() { world?.let { it.scope.cancel(); it.db.close() } }

    private fun row(side: Side, kg: Double, age: Long = 0) = MaxRecordEntity.from(
        grip, kg, MaxSource.measured, side, Instant.parse("2020-09-12T10:00:00Z").minusSeconds(age))

    private fun show(rows: List<MaxRecordEntity>, onClose: () -> Unit = {}): World {
        val w = World(rows).also { world = it }
        WeightUnits.current = WeightUnit.kg
        compose.setContent {
            CompositionLocalProvider(LocalTemplateStore provides w.store, LocalHistoryFeed provides w.feed) {
                GetAGripTheme(darkTheme = false) { MaxEditSheet(grip, onClose = onClose) }
            }
        }
        return w
    }

    private fun type(side: String, current: String, value: String) {
        val label = "${if (side == "left") "Left" else "Right"} hand, $current kg. Double tap to type a value."
        compose.onNodeWithContentDescription(label).performScrollTo().performClick()
        compose.onNode(hasSetTextAction()).performTextInput(value)
    }

    @Test fun toolbarSaveCommitsFocusedSecondHandAndPreservesBothExactValues() {
        var closed = false
        val w = show(listOf(row(Side.left, 20.5), row(Side.right, 21.8), row(Side.both, 70.0))) { closed = true }
        type("left", "20.5", "22.2")
        compose.onNodeWithText("Done").performClick()
        type("right", "21.8", "23.4")
        compose.onNodeWithTag("maxEdit.save").assertIsEnabled().performClick()
        compose.waitUntil(10_000) { closed }
        assertEquals(22.2, w.store.maxTable.exact(grip.key, Side.left))
        assertEquals(23.4, w.store.maxTable.exact(grip.key, Side.right))
        assertEquals(70.0, w.store.maxTable.exact(grip.key, Side.both))
        assertEquals(5, w.records().size)
    }

    @Test fun explicitSameValueRetestRecordsOnlyTheSelectedHand() {
        var closed = false
        val w = show(listOf(row(Side.left, 20.5), row(Side.right, 21.8))) { closed = true }
        compose.onNodeWithTag("maxEdit.save").assertIsNotEnabled()
        compose.onNodeWithTag("maxEdit.retest.left").performScrollTo().performClick().assertIsSelected()
        compose.onNodeWithTag("maxEdit.save").performClick()
        compose.waitUntil(10_000) { closed }
        assertEquals(2, w.records().count { it.side == Side.left })
        assertEquals(1, w.records().count { it.side == Side.right })
    }

    @Test fun failedSaveKeepsDraftAndCanRetryWithoutPartialHandRecords() {
        var closed = false
        val w = show(listOf(row(Side.left, 20.5), row(Side.right, 21.8))) { closed = true }
        w.refuseWrites = true
        type("left", "20.5", "22.2")
        compose.onNodeWithTag("maxEdit.save").performClick()
        compose.waitUntil(10_000) {
            compose.onAllNodesWithText("Couldn’t save your maxes. Your changes are still here—please try again.")
                .fetchSemanticsNodes().isNotEmpty()
        }
        assertEquals(false, closed)
        assertEquals(2, w.records().size)
        compose.onNodeWithContentDescription("Left hand, 22.2 kg. Double tap to type a value.").assertExists()
        w.refuseWrites = false
        compose.onNodeWithTag("maxEdit.save").performClick()
        compose.waitUntil(10_000) { closed }
        assertEquals(3, w.records().size)
        assertEquals(22.2, w.store.maxTable.exact(grip.key, Side.left))
        assertEquals(21.8, w.store.maxTable.exact(grip.key, Side.right))
    }

    @Test fun missingHandsNeverPrefillSharedFallbackAndCancelDoesNotWrite() {
        var closed = false
        val w = show(listOf(row(Side.both, 70.0))) { closed = true }
        compose.onNodeWithContentDescription("Left hand, 0.0 kg. Double tap to type a value.").assertExists()
        compose.onNodeWithContentDescription("Right hand, 0.0 kg. Double tap to type a value.").assertExists()
        compose.onNodeWithTag("maxEdit.retest.left").assertDoesNotExist()
        compose.onNodeWithTag("maxEdit.save").assertIsNotEnabled()
        type("left", "0.0", "22.2")
        compose.onNodeWithContentDescription("Back").performClick()
        compose.runOnIdle { assertEquals(true, closed) }
        assertEquals(1, w.records().size)
    }

    @Test fun sharedChildCancelKeepsUnsavedIndividualHandDraft() {
        val w = show(listOf(row(Side.left, 20.5), row(Side.right, 21.8), row(Side.both, 70.0)))
        type("left", "20.5", "22.2")
        compose.onNodeWithText("Done").performClick()
        compose.onNodeWithTag("maxEdit.shared").performScrollTo().performClick()
        compose.onNodeWithTag("maxShared.save").assertExists()
        compose.onNodeWithContentDescription("Back").performClick()
        compose.onNodeWithContentDescription("Left hand, 22.2 kg. Double tap to type a value.").assertExists()
        assertEquals(3, w.records().size)
        val file = java.io.File("../../build/review/max-hand/android-manual.png").canonicalFile
        file.parentFile?.mkdirs()
        compose.onNodeWithTag("maxEdit.value.left").performScrollTo()
        file.outputStream().use { compose.onRoot().captureToImage().asAndroidBitmap()
            .compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
    }

    @Test fun recentGripCarriesItsEntireIdentityToManualAndGaugeRoutes() {
        val candidate = GripSpec(15, FingerSet.frontThree, GripPosition.fullCrimp)
        val routine = RoutineDraft(plan = SessionPlan(sets = listOf(SetPlan(grip = grip), SetPlan(grip = candidate))))
        val w = World(emptyList(), routine).also { world = it }
        val draft = NewMaxDraft(grip)
        var entered: GripSpec? = null
        var measured: Pair<GripSpec, Side>? = null
        compose.setContent {
            CompositionLocalProvider(LocalTemplateStore provides w.store, LocalHistoryFeed provides w.feed) {
                GetAGripTheme(darkTheme = false) {
                    NewMaxSheet(draft, onMeasure = { grip, side -> measured = grip to side },
                        onEnter = { entered = it }, onShared = {}, onClose = {})
                }
            }
        }
        compose.onNodeWithTag("newMax.grip.${candidate.key}").performScrollTo().performClick().assertIsSelected()
        compose.onNodeWithTag("newMax.enter").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(candidate, entered) }
        // Measure asks one question first: one hand at a time, or both together.
        compose.onNodeWithTag("newMax.measure").performScrollTo().performClick()
        compose.onNodeWithText("How are you measuring?").assertIsDisplayed()
        compose.onNodeWithTag("max.mode.criticalForce").assertDoesNotExist()
        compose.onNodeWithTag("max.mode.hands").performClick()
        compose.runOnIdle { assertEquals(candidate to Side.left, measured) }
        compose.onNodeWithTag("newMax.measure").performScrollTo().performClick()
        compose.onNodeWithTag("max.mode.both").performClick()
        compose.runOnIdle { assertEquals(candidate to Side.both, measured) }
        // The menu no longer carries its own "both together" door.
        compose.onNodeWithTag("newMax.options").performClick()
        compose.onNodeWithText("Measure both hands together").assertDoesNotExist()
        assertEquals(emptyList(), w.records())
    }
}
