// SPDX-License-Identifier: MPL-2.0
// Original contributions Copyright 2026 Nuri Bruner.
package run.nuri.getagrip.ui

import androidx.compose.runtime.*
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.foundation.text.BasicText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import run.nuri.getagrip.engine.RunnerPhase
import run.nuri.getagrip.ui.runner.rememberPullProgress
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class PullProgressTests {
    @get:Rule val compose = createComposeRule()

    @Test fun progressKeepsMovingBetweenPacketsWithoutExceedingTheMeasurement() {
        var measured by mutableFloatStateOf(0f)
        var shown = 0f
        compose.setContent {
            val value = rememberPullProgress(measured, RunnerPhase.Working(0), false).value
            BasicText(value.toString())
            SideEffect { shown = value }
        }
        compose.mainClock.autoAdvance = false
        // Commit state explicitly while automatic frame advancement is disabled.
        compose.runOnIdle { measured = 0.1f; Snapshot.sendApplyNotifications() }
        // One frame recomposes; the next establishes the animation start time.
        compose.mainClock.advanceTimeBy(32)
        compose.mainClock.advanceTimeBy(48)
        val first = shown
        compose.mainClock.advanceTimeBy(48)
        val second = shown
        compose.mainClock.advanceTimeBy(48)
        val third = shown
        assertTrue(first > 0f && second > first && third > second, "Frame samples: $first, $second, $third")
        assertEquals(second - first, third - second, 0.001f, "Must not ease to a stop per packet")
        assertTrue(third < 0.1f)
        compose.mainClock.advanceTimeBy(400)
        assertEquals(0.1f, shown, 0.0001f)
        compose.mainClock.advanceTimeBy(500)
        assertEquals(0.1f, shown, 0.0001f, "No extrapolation without a measurement")
    }

    @Test fun newPullResetsInsteadOfAnimatingBackwards() {
        var measured by mutableFloatStateOf(0.9f)
        var slot by mutableIntStateOf(0)
        var shown = 0f
        compose.setContent {
            val value = rememberPullProgress(measured, RunnerPhase.Working(slot), false).value
            BasicText(value.toString())
            SideEffect { shown = value }
        }
        compose.mainClock.autoAdvance = false
        compose.runOnIdle { measured = 0f; slot = 1; Snapshot.sendApplyNotifications() }
        compose.mainClock.advanceTimeByFrame()
        compose.waitForIdle()
        assertEquals(0f, shown)
    }

    @Test fun reducedMotionUsesTheMeasuredValueDirectly() {
        var measured by mutableFloatStateOf(0f)
        var shown = 0f
        compose.setContent {
            val value = rememberPullProgress(measured, RunnerPhase.Working(0), true).value
            BasicText(value.toString())
            SideEffect { shown = value }
        }
        compose.runOnIdle { measured = 0.4f }
        compose.waitForIdle()
        assertEquals(0.4f, shown)
    }
}
