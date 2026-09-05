// SPDX-License-Identifier: MPL-2.0
// Original contributions Copyright 2026 Nuri Bruner.
package run.nuri.getagrip.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.unit.dp
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import run.nuri.getagrip.engine.GripSpec
import run.nuri.getagrip.runner.RunnerSnapshot
import run.nuri.getagrip.ui.runner.GraphGripChangeCue
import run.nuri.getagrip.ui.theme.GetAGripTheme
import run.nuri.getagrip.ui.theme.LocalGripPalette
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class GraphGripChangeCueTests {
    @get:Rule val compose = createComposeRule()

    @Test fun cueStaysForTheWholeRestIncludingPauseThenFadesWithoutRestarting() {
        var state by mutableStateOf(RunnerSnapshot(grip = GripSpec(), hasSignal = true,
            newGripID = "rest-change", gripChangesNext = true,
            phase = run.nuri.getagrip.engine.RunnerPhase.Resting(0)))
        compose.mainClock.autoAdvance = false
        compose.setContent {
            GetAGripTheme {
                Box(Modifier.size(320.dp, 220.dp)) {
                    GraphGripChangeCue(state, LocalGripPalette.current, Modifier.matchParentSize())
                }
            }
        }
        compose.mainClock.advanceTimeBy(20_000)
        compose.onNodeWithText("New grip", useUnmergedTree = true).assertIsDisplayed()
        compose.runOnIdle {
            state = state.copy(phase = run.nuri.getagrip.engine.RunnerPhase.Paused(state.phase))
            Snapshot.sendApplyNotifications()
        }
        compose.mainClock.advanceTimeBy(20_000)
        compose.onNodeWithText("New grip", useUnmergedTree = true).assertIsDisplayed()
        compose.runOnIdle {
            state = state.copy(gripChangesNext = false,
                phase = run.nuri.getagrip.engine.RunnerPhase.Armed(1))
            Snapshot.sendApplyNotifications()
        }
        compose.mainClock.advanceTimeBy(400)
        compose.onNodeWithText("New grip", useUnmergedTree = true).assertDoesNotExist()
    }

    @Test fun repeatedChangesRestartTheCueWithoutMovingThePlotOrMetrics() {
        var state by mutableStateOf(RunnerSnapshot(grip = GripSpec(), hasSignal = true))
        compose.mainClock.autoAdvance = false
        compose.setContent {
            GetAGripTheme {
                Column(Modifier.width(360.dp)) {
                    Text("12.3 kg · 7 s", Modifier.testTag("metrics"))
                    Box(Modifier.fillMaxWidth().height(220.dp).testTag("plot")
                        .background(LocalGripPalette.current.card)) {
                        GraphGripChangeCue(state, LocalGripPalette.current, Modifier.matchParentSize())
                    }
                }
            }
        }
        val plot = compose.onNodeWithTag("plot").fetchSemanticsNode().boundsInRoot
        val metrics = compose.onNodeWithTag("metrics").fetchSemanticsNode().boundsInRoot
        fun change(next: RunnerSnapshot) {
            compose.runOnIdle { state = next; Snapshot.sendApplyNotifications() }
            compose.mainClock.advanceTimeBy(32)
        }
        change(state.copy(newGripID = "first"))
        compose.mainClock.advanceTimeBy(240)
        compose.onNodeWithText("New grip", useUnmergedTree = true).assertIsDisplayed()
        assertEquals(plot, compose.onNodeWithTag("plot").fetchSemanticsNode().boundsInRoot)
        assertEquals(metrics, compose.onNodeWithTag("metrics").fetchSemanticsNode().boundsInRoot)
        val preview = java.io.File("build/reports/graph-grip-change.png")
        preview.parentFile?.mkdirs()
        preview.outputStream().use {
            compose.onRoot().captureToImage().asAndroidBitmap()
                .compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
        }
        fun borderPixel(): Int {
            val bitmap = compose.onNodeWithTag("plot").captureToImage().asAndroidBitmap()
            return bitmap.getPixel(bitmap.width / 2, 1)
        }
        val brightBorder = borderPixel()
        compose.mainClock.advanceTimeBy(260)
        assertNotEquals(brightBorder, borderPixel(), "The outline should pulse while the banner stays readable")
        compose.onNodeWithText("New grip", useUnmergedTree = true).assertIsDisplayed()
        compose.mainClock.advanceTimeBy(440)
        change(state.copy(newGripID = "second", grip = GripSpec(edgeMM = 10)))
        compose.mainClock.advanceTimeBy(1000)
        // Past the first event's expiry, the second event still has its own full beat.
        compose.onNodeWithText("New grip", useUnmergedTree = true).assertIsDisplayed()
        assertEquals(plot, compose.onNodeWithTag("plot").fetchSemanticsNode().boundsInRoot)
        compose.mainClock.advanceTimeBy(1000)
        compose.onNodeWithText("New grip", useUnmergedTree = true).assertDoesNotExist()
        change(state.copy(newGripID = "third"))
        compose.mainClock.advanceTimeBy(240)
        change(state.copy(hasSignal = false))
        compose.onNodeWithText("New grip", useUnmergedTree = true).assertDoesNotExist()
        assertEquals(metrics, compose.onNodeWithTag("metrics").fetchSemanticsNode().boundsInRoot)
    }
}
