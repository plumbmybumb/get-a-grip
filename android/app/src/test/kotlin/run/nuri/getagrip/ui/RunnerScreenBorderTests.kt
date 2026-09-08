// SPDX-License-Identifier: MPL-2.0
// Original contributions Copyright 2026 Nuri Bruner.
package run.nuri.getagrip.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.toArgb
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
import run.nuri.getagrip.engine.RunnerPhase
import run.nuri.getagrip.runner.RunnerSnapshot
import run.nuri.getagrip.ui.runner.*
import run.nuri.getagrip.ui.theme.GetAGripTheme
import run.nuri.getagrip.ui.theme.LocalGripPalette
import kotlin.test.assertEquals

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w393dp-h820dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class RunnerScreenBorderTests {
    @get:Rule val compose = createComposeRule()
    private val working = RunnerSnapshot(phase = RunnerPhase.Working(0), hasSignal = true)

    @Test fun activeMeasuredWorkUsesBlueOnlyForLiveAcceptedInRangeSamples() {
        assertEquals(RunnerBorderCue.Pulling, runnerBorderCue(working, false, true))
        for (warning in listOf(working.copy(isDropped = true), working.copy(isOverTarget = true),
            working.copy(hasSignal = false), working.copy(linkIsDown = true),
            working.copy(isRejectingStaleBatches = true))) {
            assertEquals(RunnerBorderCue.Warning, runnerBorderCue(warning, false, true))
        }
        assertEquals(RunnerBorderCue.Warning, runnerBorderCue(working, false, false))
    }

    @Test fun restCountdownPauseAndFinishedNeverInheritAWorkWarningOrPullCue() {
        for (phase in listOf(RunnerPhase.Idle, RunnerPhase.LeadIn(0), RunnerPhase.Armed(0),
            RunnerPhase.Resting(0), RunnerPhase.Paused(RunnerPhase.Working(0)),
            RunnerPhase.Paused(RunnerPhase.Releasing(0)), RunnerPhase.Finished)) {
            val snapshot = working.copy(phase = phase, isDropped = true, isOverTarget = true,
                linkIsDown = true, isRejectingStaleBatches = true)
            assertEquals(RunnerBorderCue.None, runnerBorderCue(snapshot, false, false))
            assertEquals(RunnerBorderCue.None, runnerBorderCue(snapshot, true, true))
        }
    }

    @Test fun releaseGateStaysOrangeUntilItsPhaseActuallyChanges() {
        val release = working.copy(phase = RunnerPhase.Releasing(0), isDropped = true,
            isOverTarget = true, hasSignal = false, linkIsDown = true)
        assertEquals(RunnerBorderCue.Release, runnerBorderCue(release, false, false))
        assertEquals(RunnerBorderCue.None,
            runnerBorderCue(release.copy(phase = RunnerPhase.Paused(release.phase)), false, false))
    }

    @Test fun timerOnlyWorkUsesItsOwnClockRegardlessOfAnUnrelatedGauge() {
        val timer = working.copy(hasSignal = false, linkIsDown = true,
            isRejectingStaleBatches = true)
        assertEquals(RunnerBorderCue.Pulling, runnerBorderCue(timer, true, false))
        assertEquals(RunnerBorderCue.Pulling, runnerBorderCue(timer, true, true))
        // Engine warnings always take precedence, although timer work currently never sets them.
        assertEquals(RunnerBorderCue.Warning, runnerBorderCue(timer.copy(isDropped = true), true, false))
        assertEquals(RunnerBorderCue.Warning, runnerBorderCue(timer.copy(isOverTarget = true), true, false))
    }

    @Test fun borderChangesAreSteadyDrawOnlyAndNeverStealThePauseTouch() {
        var snapshot by mutableStateOf(working)
        var signalLive by mutableStateOf(true)
        var clicked = 0
        var blue = 0; var red = 0; var orange = 0; var field = 0
        compose.mainClock.autoAdvance = false
        compose.setContent { GetAGripTheme {
            val palette = LocalGripPalette.current
            blue = palette.bleu.toArgb(); red = palette.alarm.toArgb()
            orange = palette.armed.toArgb(); field = palette.field.toArgb()
            Box(Modifier.size(320.dp, 640.dp).testTag("screen").background(palette.field)) {
                Column {
                    Text("12.3 kg", Modifier.testTag("metrics"))
                    Box(Modifier.height(220.dp).testTag("graph"))
                    Text("Pause", Modifier.clickable { clicked++ }.testTag("pause"))
                }
                RunnerScreenBorder(runnerBorderCue(snapshot, false, signalLive), Modifier.matchParentSize())
            }
        } }
        compose.mainClock.advanceTimeByFrame()
        val metrics = compose.onNodeWithTag("metrics").fetchSemanticsNode().boundsInRoot
        val graph = compose.onNodeWithTag("graph").fetchSemanticsNode().boundsInRoot
        fun pixels() = compose.onNodeWithTag("screen").captureToImage().asAndroidBitmap().let {
            listOf(it.getPixel(it.width / 2, 2), it.getPixel(it.width / 2, 5))
        }
        assertEquals(listOf(blue, field), pixels(), "Blue is four dp thick")
        compose.mainClock.advanceTimeBy(30_000)
        assertEquals(listOf(blue, field), pixels(), "An active pull does not pulse or time out")
        compose.onNodeWithTag("pause").performTouchInput { click() }
        compose.runOnIdle { assertEquals(1, clicked); snapshot = working.copy(isOverTarget = true); Snapshot.sendApplyNotifications() }
        compose.mainClock.advanceTimeByFrame()
        assertEquals(listOf(red, red), pixels(), "Warning is six dp thick and takes priority")
        compose.runOnIdle { snapshot = working; signalLive = false; Snapshot.sendApplyNotifications() }
        compose.mainClock.advanceTimeByFrame()
        assertEquals(listOf(red, red), pixels(), "A stale signal can never keep the pull-blue cue")
        compose.runOnIdle { snapshot = working.copy(phase = RunnerPhase.Releasing(0)); Snapshot.sendApplyNotifications() }
        compose.mainClock.advanceTimeByFrame()
        assertEquals(listOf(orange, orange), pixels())
        compose.runOnIdle { snapshot = working.copy(phase = RunnerPhase.Paused(RunnerPhase.Releasing(0))); Snapshot.sendApplyNotifications() }
        compose.mainClock.advanceTimeByFrame()
        assertEquals(listOf(field, field), pixels())
        assertEquals(metrics, compose.onNodeWithTag("metrics").fetchSemanticsNode().boundsInRoot)
        assertEquals(graph, compose.onNodeWithTag("graph").fetchSemanticsNode().boundsInRoot)
    }
}
