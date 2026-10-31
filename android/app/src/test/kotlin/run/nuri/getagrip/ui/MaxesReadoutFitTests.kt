// SPDX-License-Identifier: MPL-2.0
// Original contributions Copyright 2026 Nuri Bruner.
package run.nuri.getagrip.ui

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.Density
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
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
import run.nuri.getagrip.ui.maxes.MaxesTabScreen
import run.nuri.getagrip.ui.theme.GetAGripTheme
import run.nuri.getagrip.ui.units.WeightUnit
import run.nuri.getagrip.ui.units.WeightUnits
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/// A shut card with a shared max AND both hands (three columns plus Measure) at large text
/// (2026-10-31, iOS report: "Shared max" broke to "Share / d max" and 29.4 to one digit per
/// line). Every column must stay one label line over one number line — exactly as tall as
/// the lone column on a both-hands-only card — at every font scale.
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w393dp-h852dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class MaxesReadoutFitTests {
    @get:Rule val compose = createComposeRule()
    private val split = GripSpec(20, FingerSet.frontThree, GripPosition.halfCrimp)
    private val single = GripSpec()
    private val originalUnits = WeightUnits.current
    private var cleanup: (() -> Unit)? = null

    @After fun close() {
        cleanup?.invoke()
        WeightUnits.current = originalUnits
    }

    private fun row(grip: GripSpec, side: Side, kg: Double, age: Long = 0) = MaxRecordEntity.from(
        grip, kg, MaxSource.measured, side, Instant.parse("2026-10-28T10:00:00Z").minusSeconds(age))

    private fun show(fontScale: Float) {
        val rows = listOf(row(split, Side.both, 29.4), row(split, Side.left, 20.5, 60),
            row(split, Side.right, 21.8, 120), row(single, Side.both, 30.5, 600))
        val db = GetAGripDatabase.inMemory(RuntimeEnvironment.getApplication())
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        val feedScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val store = TemplateStore(RoomStoreGateway(db), DayClock(), InMemoryRoutineSettings(),
            RecordingAlarmScheduler(), scope)
        val feed = HistoryFeed(object : HistorySource {
            override suspend fun allLogs() = emptyList<WorkoutLogEntity>()
            override suspend fun allMaxes() = rows
        }, feedScope, processingDispatcher = Dispatchers.Unconfined).also { it.refresh() }
        runBlocking { rows.forEach { db.maxes().upsert(it) }; store.syncDerived() }
        cleanup = { scope.cancel(); feedScope.cancel(); db.close() }
        WeightUnits.current = WeightUnit.kg
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalTemplateStore provides store, LocalHistoryFeed provides feed,
                LocalDensity provides Density(density.density, fontScale)) {
                GetAGripTheme(darkTheme = false) {
                    MaxesTabScreen(onAddMax = {}, onMeasure = { _, _, _ -> }, onEdit = {}, feed = feed)
                }
            }
        }
        compose.waitForIdle()
    }

    private fun height(tag: String) = compose.onNodeWithTag(tag).fetchSemanticsNode().boundsInRoot.height

    private fun check(fontScale: Float, name: String, open: Boolean = false) {
        show(fontScale)
        // Read before opening: an open card can push the reference card out of the lazy list.
        val reference = height("maxes.current.${single.key}.both")
        if (open) {
            compose.onNodeWithTag("maxes.card.${split.key}").performClick()
            compose.waitForIdle()
        }
        val file = java.io.File("../../build/review/maxes-readout/$name").canonicalFile
        file.parentFile?.mkdirs()
        file.outputStream().use {
            compose.onRoot().captureToImage().asAndroidBitmap()
                .compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
        }
        assertTrue(reference > 0f)
        listOf(Side.both, Side.left, Side.right).forEach { side ->
            assertEquals(reference, height("maxes.current.${split.key}.${side.rawValue}"), 0.5f,
                "at font scale $fontScale the ${side.rawValue} column wrapped")
        }
    }

    @Test fun defaultTextKeepsEveryColumnOnOneLine() = check(1f, "scale-100.png")
    @Test fun largeTextKeepsEveryColumnOnOneLine() = check(1.3f, "scale-130.png")
    @Test fun largerTextKeepsEveryColumnOnOneLine() = check(1.45f, "scale-145.png")
    @Test fun hugeTextKeepsEveryColumnOnOneLine() = check(2f, "scale-200.png")
    @Test fun anOpenCardKeepsEveryColumnOnOneLine() = check(1.3f, "scale-130-open.png", open = true)
    @Test fun anOpenCardAtHugeTextKeepsEveryColumnOnOneLine() = check(2f, "scale-200-open.png", open = true)
}
