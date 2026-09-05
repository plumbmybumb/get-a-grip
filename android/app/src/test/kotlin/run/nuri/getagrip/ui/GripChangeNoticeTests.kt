// SPDX-License-Identifier: MPL-2.0
// Original contributions Copyright 2026 Nuri Bruner.

package run.nuri.getagrip.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.Config
import run.nuri.getagrip.engine.GripSpec
import run.nuri.getagrip.engine.GripPosition
import run.nuri.getagrip.runner.RunnerSnapshot
import run.nuri.getagrip.ui.runner.GripNameRow
import run.nuri.getagrip.ui.runner.GripChangeNotice
import run.nuri.getagrip.ui.theme.GetAGripTheme
import run.nuri.getagrip.ui.theme.LocalGripPalette

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class GripChangeNoticeTests {
    @get:Rule val compose = createComposeRule()
    @Test fun changingAndPreviewNoticesNeverMoveMetrics() = verify(1f)
    @Test fun consecutiveChangesKeepMetricsFixedAtLargeText() = verify(2f)
    private fun verify(fontScale: Float) {
        val grip = GripSpec()
        val next = GripSpec(edgeMM = 10, position = GripPosition.fingerCurl)
        var state by mutableStateOf(RunnerSnapshot(grip = grip))
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(1f, fontScale)) {
                GetAGripTheme {
                    Column(Modifier.width(320.dp)) {
                        GripNameRow(state, LocalGripPalette.current, timerOnly = false)
                        GripChangeNotice(state, LocalGripPalette.current)
                        Text("12.3 kg", Modifier.testTag("force"))
                        Text("0:07", Modifier.testTag("timer"))
                    }
                }
            }
        }
        val forceBounds = compose.onNodeWithTag("force").fetchSemanticsNode().boundsInRoot
        val timerBounds = compose.onNodeWithTag("timer").fetchSemanticsNode().boundsInRoot
        for (changed in listOf(RunnerSnapshot(grip = grip, phase = run.nuri.getagrip.engine.RunnerPhase.Resting(0)),
            RunnerSnapshot(grip = grip, upcomingGrip = next),
            RunnerSnapshot(grip = grip, newGripID = "1.0"),
            RunnerSnapshot(grip = grip, newGripID = "1.0", upcomingGrip = next),
            RunnerSnapshot(grip = next, newGripID = "2.0"), RunnerSnapshot(grip = next))) {
            compose.runOnIdle { state = changed }
            assertEquals(forceBounds, compose.onNodeWithTag("force").fetchSemanticsNode().boundsInRoot)
            assertEquals(timerBounds, compose.onNodeWithTag("timer").fetchSemanticsNode().boundsInRoot)
            compose.onNodeWithTag("force").assertIsDisplayed()
            compose.onNodeWithTag("timer").assertIsDisplayed()
            if (changed.newGripID == "1.0" && changed.upcomingGrip != null) {
                val output = java.io.File("build/reports/grip-change-header-$fontScale.png")
                output.parentFile.mkdirs()
                output.outputStream().use {
                    compose.onRoot().captureToImage().asAndroidBitmap()
                        .compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
                }
            }
        }
    }
}
