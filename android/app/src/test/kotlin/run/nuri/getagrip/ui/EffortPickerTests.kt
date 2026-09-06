// SPDX-License-Identifier: MPL-2.0
// Original contributions Copyright 2026 Nuri Bruner.
package run.nuri.getagrip.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.unit.dp
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import run.nuri.getagrip.ui.components.EffortPicker
import run.nuri.getagrip.ui.components.effortLevelAt
import run.nuri.getagrip.ui.theme.GetAGripTheme
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class EffortPickerTests {
    @get:Rule val compose = createComposeRule()
    private val names = listOf("Easy", "Comfortable", "Solid", "Hard", "All I had")

    @Test fun tapDragClearAndAccessibilityPreserveOptionalFiveLevelScale() {
        var chosen: Int? by mutableStateOf(null)
        compose.setContent { GetAGripTheme {
            EffortPicker(chosen, names, "How hard did it feel?", "effort") { chosen = it }
        } }
        val control = compose.onNodeWithTag("effort")
        control.assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Not rated"))
        for (level in 1..5) {
            control.performTouchInput { click(Offset(1f + (width - 2f) * (level - 1) / 4f, height / 2f)) }
            compose.runOnIdle { assertEquals(level, chosen) }
        }
        control.performTouchInput { swipeLeft() }
        compose.runOnIdle { assertEquals(1, chosen) }
        control.performTouchInput { swipeRight() }
        compose.runOnIdle { assertEquals(5, chosen) }
        compose.onNodeWithTag("effort.clear").performClick()
        compose.runOnIdle { assertEquals(null, chosen) }
        control.performSemanticsAction(SemanticsActions.SetProgress) { it(4f) }
        compose.runOnIdle { assertEquals(4, chosen) }
        control.assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Hard"))
    }

    @Test fun verticalSwipeScrollsPageWithoutAnsweringTheRating() {
        var chosen: Int? by mutableStateOf(null)
        lateinit var pageScroll: ScrollState
        compose.setContent { GetAGripTheme {
            val scroll = rememberScrollState()
            SideEffect { pageScroll = scroll }
            Column(Modifier.height(300.dp).verticalScroll(scroll)) {
                EffortPicker(chosen, names, "Effort", "effort") { chosen = it }
                Spacer(Modifier.height(800.dp))
            }
        } }
        compose.onNodeWithTag("effort").performTouchInput { swipeUp() }
        compose.runOnIdle { assertEquals(null, chosen); assertTrue(pageScroll.value > 0) }
    }

    @Test fun barCentersAndOutsideDragsMatchVisibleLevels() {
        for (width in listOf(180f, 300f, 440f)) {
            val bar = minOf(44f, width / 5)
            for (i in 0..4) assertEquals(i + 1, effortLevelAt(bar / 2 + i * (width - bar) / 4, width, 44f))
            assertEquals(1, effortLevelAt(-100f, width, 44f))
            assertEquals(5, effortLevelAt(width + 100f, width, 44f))
        }
    }
}
