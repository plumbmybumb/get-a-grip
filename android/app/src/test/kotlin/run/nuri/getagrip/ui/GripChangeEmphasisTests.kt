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
import run.nuri.getagrip.ui.components.rememberGripChangeEmphasis
import kotlin.test.assertEquals

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class GripChangeEmphasisTests {
    @get:Rule val compose = createComposeRule()

    @Test fun reducedMotionKeepsTheHandAndBannerSteadyForRestButPreservesBriefNoRestCues() {
        var resting by mutableStateOf(true)
        var id by mutableStateOf("first")
        var shown = 0f
        compose.mainClock.autoAdvance = false
        compose.setContent {
            val emphasis = rememberGripChangeEmphasis(id, resting, reduceMotion = true)
            BasicText("${emphasis.value}")
            SideEffect { shown = emphasis.value }
        }
        compose.mainClock.advanceTimeBy(30_000)
        compose.runOnIdle { assertEquals(1f, shown) }
        compose.runOnIdle { resting = false; Snapshot.sendApplyNotifications() }
        compose.mainClock.advanceTimeBy(48)
        compose.runOnIdle { assertEquals(0f, shown) }
        compose.runOnIdle { id = "second"; Snapshot.sendApplyNotifications() }
        compose.mainClock.advanceTimeBy(500)
        compose.runOnIdle { assertEquals(1f, shown) }
        compose.mainClock.advanceTimeBy(1000)
        compose.runOnIdle { assertEquals(0f, shown) }
    }
}
