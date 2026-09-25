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
import run.nuri.getagrip.FakeClock
import run.nuri.getagrip.RecordingProgressorClient
import run.nuri.getagrip.inertScope
import run.nuri.getagrip.data.GetAGripDatabase
import run.nuri.getagrip.data.SessionTemplateEntity
import run.nuri.getagrip.engine.*
import run.nuri.getagrip.store.*
import run.nuri.getagrip.ui.theme.GetAGripTheme
import run.nuri.getagrip.ui.today.TodayScreen
import java.io.File
import kotlin.test.assertEquals

/// "New routine" (twin of iOS `NewRoutineUITests`): the chooser, a protocol's preview, and the
/// add that makes it a card; Build from scratch still opens the builder.
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w393dp-h820dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class NewRoutineFlowTests {
    @get:Rule val compose = createComposeRule()
    private var world: World? = null

    private class World(routine: RoutineDraft?) {
        val db = GetAGripDatabase.inMemory(RuntimeEnvironment.getApplication())
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        val gateway = RoomStoreGateway(db)
        val clock = DayClock()
        val store = TemplateStore(gateway, clock, InMemoryRoutineSettings(), RecordingAlarmScheduler(), scope)
        val feed = HistoryFeed(gateway.asHistorySource(), scope)
        val device = DeviceStore(RecordingProgressorClient(), scope = inertScope(), clock = FakeClock())
        init { runBlocking {
            routine?.let { db.routines().upsert(SessionTemplateEntity.from(it.normalized, 0)) }
            store.syncDerived()
        } }
        fun names(): List<String> = runBlocking { db.routines().all().map { it.name } }
    }

    @After fun close() { world?.let { it.scope.cancel(); it.db.close() } }

    private fun show(routine: RoutineDraft? = null, onBuild: () -> Unit = {}): World {
        val w = World(routine).also { world = it }
        compose.setContent {
            CompositionLocalProvider(
                LocalTemplateStore provides w.store, LocalHistoryFeed provides w.feed,
                LocalDayClock provides w.clock, LocalDeviceStore provides w.device,
            ) {
                GetAGripTheme(darkTheme = false) { TodayScreen(onBuild = onBuild) }
            }
        }
        return w
    }

    private fun tap(tag: String) {
        compose.onNodeWithTag(tag).apply { runCatching { performScrollTo() } }.performClick()
        compose.waitForIdle()
    }

    @Test fun aProtocolIsPreviewedThenAddedAsARoutine() {
        val w = show()
        // The empty card keeps its primary door and gains the protocols beside it.
        compose.onNodeWithText("Build my routine").assertExists()
        tap("today.protocols")
        compose.onNodeWithTag("newRoutine.scratch").assertIsDisplayed()
        for (item in RoutineProtocol.allCases) compose.onNodeWithTag("newRoutine.protocol.${item.rawValue}").assertExists()
        capture("chooser.png")
        tap("newRoutine.protocol.fingerRehab")
        compose.onNodeWithText("After Hooper's Beta", useUnmergedTree = true).assertExists()
        compose.onNodeWithTag("routinePreview.caution").assertExists()
        capture("preview.png")
        tap("routinePreview.add")
        compose.waitUntil(5_000) { w.store.routines.isNotEmpty() }
        compose.waitForIdle()
        assertEquals(listOf("Finger rehab"), w.names())
        val plan = w.store.routines.single().plan
        assertEquals(listOf(10, 50, 50), listOf(plan.holdSeconds, plan.restSeconds, plan.setBreakSeconds))
        assertEquals(5, plan.sets.size)
        compose.onNodeWithTag("routinePreview.add").assertDoesNotExist()
        compose.onNodeWithTag("newRoutine.scratch").assertDoesNotExist()
    }

    @Test fun buildFromScratchOpensTheBuilderAfterTheChooserCloses() {
        var built = 0
        show(onBuild = { built += 1 })
        tap("today.protocols")
        tap("newRoutine.scratch")
        compose.runOnIdle { assertEquals(1, built) }
        compose.onNodeWithTag("newRoutine.scratch").assertDoesNotExist()
    }

    @Test fun backReturnsToTheListAndNotNowWritesNothing() {
        val w = show()
        tap("today.protocols")
        tap("newRoutine.protocol.c4Max")
        compose.onNodeWithText("After Camp4 Human Performance", useUnmergedTree = true).assertExists()
        tap("newRoutine.back")
        compose.onNodeWithTag("newRoutine.scratch").assertIsDisplayed()
        tap("newRoutine.protocol.c4Max")
        compose.onNodeWithText("Not now").apply { runCatching { performScrollTo() } }.performClick()
        compose.waitForIdle()
        compose.onNodeWithTag("newRoutine.scratch").assertDoesNotExist()
        assertEquals(emptyList(), w.names())
    }

    /// With a routine on Today, "New routine…" asks first rather than opening the builder.
    @Test fun theCardMenuOpensTheChooser() {
        var built = 0
        show(routine = RoutineDraft.starter, onBuild = { built += 1 })
        compose.onAllNodesWithContentDescription("Routine options").onFirst().performClick()
        compose.onNodeWithText("New routine…").performClick()
        compose.waitForIdle()
        compose.onNodeWithTag("newRoutine.scratch").assertIsDisplayed()
        compose.runOnIdle { assertEquals(0, built) }
    }

    private fun capture(name: String) {
        val output = File("../../build/review/new-routine/$name").canonicalFile
        output.parentFile?.mkdirs()
        output.outputStream().use {
            compose.onRoot().captureToImage().asAndroidBitmap()
                .compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
        }
    }
}
