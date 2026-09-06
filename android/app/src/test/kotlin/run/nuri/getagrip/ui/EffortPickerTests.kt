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
import androidx.compose.ui.hapticfeedback.HapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import run.nuri.getagrip.ui.components.EffortPicker
import run.nuri.getagrip.ui.components.effortBarHeight
import run.nuri.getagrip.ui.components.effortBarIsFilled
import run.nuri.getagrip.ui.components.effortEndpointLabelStart
import run.nuri.getagrip.ui.components.effortLevelAt
import run.nuri.getagrip.ui.theme.GetAGripTheme
import kotlin.test.assertEquals
import kotlin.test.assertFalse
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
            control.performTouchInput { click(Offset(width * (level - 0.5f) / 5f, height / 2f)) }
            compose.runOnIdle { assertEquals(level, chosen) }
        }
        // The answer remains optional without a separate footer or visible clear button.
        control.performTouchInput { click(Offset(width * 0.9f, height / 2f)) }
        compose.runOnIdle { assertEquals(null, chosen) }
        control.performTouchInput { swipeLeft() }
        compose.runOnIdle { assertEquals(1, chosen) }
        control.performTouchInput { swipeRight() }
        compose.runOnIdle { assertEquals(5, chosen) }
        control.performSemanticsAction(SemanticsActions.SetProgress) { it(4f) }
        compose.runOnIdle { assertEquals(4, chosen) }
        control.assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Hard"))
        val actions = control.fetchSemanticsNode().config[SemanticsActions.CustomActions]
        compose.runOnIdle {
            assertEquals(1, actions.size)
            assertEquals("Clear rating", actions.single().label)
            assertTrue(actions.single().action())
        }
        compose.runOnIdle { assertEquals(null, chosen) }
        control.assert(SemanticsMatcher.keyNotDefined(SemanticsActions.CustomActions))
    }

    @Test fun repeatedDragLandingsNeverClearOrRepeatHapticsAndSelectionDoesNotResizeTheTrack() {
        var chosen: Int? by mutableStateOf(3)
        var ticks = 0
        val haptics = object : HapticFeedback {
            override fun performHapticFeedback(hapticFeedbackType: HapticFeedbackType) { ticks++ }
        }
        compose.setContent { GetAGripTheme {
            CompositionLocalProvider(LocalHapticFeedback provides haptics) {
                Box(Modifier.width(400.dp)) {
                    EffortPicker(chosen, names, "Effort", "effort") { chosen = it }
                }
            }
        } }
        val control = compose.onNodeWithTag("effort")
        val initialBounds = control.fetchSemanticsNode().boundsInRoot
        // Cross horizontal slop while remaining entirely within the third slot.
        control.performTouchInput { swipe(Offset(width * 0.41f, height / 2f), Offset(width * 0.59f, height / 2f)) }
        compose.runOnIdle { assertEquals(3, chosen); assertEquals(0, ticks) }
        control.performSemanticsAction(SemanticsActions.SetProgress) { it(3f) }
        compose.runOnIdle { assertEquals(0, ticks) }
        control.performSemanticsAction(SemanticsActions.SetProgress) { it(4f) }
        compose.runOnIdle { assertEquals(4, chosen); assertEquals(1, ticks) }
        control.performSemanticsAction(SemanticsActions.SetProgress) { it(4f) }
        compose.runOnIdle { assertEquals(1, ticks) }
        assertEquals(initialBounds, control.fetchSemanticsNode().boundsInRoot)
        control.performTouchInput { click(Offset(width * 0.7f, height / 2f)) }
        compose.runOnIdle { assertEquals(null, chosen); assertEquals(2, ticks) }
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

    @Test fun equalSlotsAndOutsideDragsMatchVisibleRungCenters() {
        for (width in listOf(0.0001f, 2f, 180f, 300f, 440f)) {
            for (i in 0..4) assertEquals(i + 1, effortLevelAt(width * (i + 0.5f) / 5, width))
            assertEquals(1, effortLevelAt(-100f, width))
            assertEquals(5, effortLevelAt(width + 100f, width))
        }
        assertEquals(1, effortLevelAt(59f, 300f))
        assertEquals(2, effortLevelAt(60f, 300f))
        assertEquals(3, effortLevelAt(150f, 300f))
        for (invalid in listOf(Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY)) {
            assertEquals(1, effortLevelAt(invalid, 300f))
            assertEquals(1, effortLevelAt(100f, invalid))
        }
        assertEquals(1, effortLevelAt(100f, 0f))
    }

    @Test fun heightsRiseFromFortyToOneHundredPercentAndFillThroughTheAnswer() {
        val heights = (1..5).map { effortBarHeight(it, 44f) }
        assertEquals(17.6f, heights.first(), 0.001f)
        assertEquals(44f, heights.last(), 0.001f)
        assertTrue(heights.zipWithNext().all { (a, b) -> a < b })
        assertEquals(88f, effortBarHeight(5, 88f), "rungs scale with text size")
        assertEquals(0f, effortBarHeight(3, Float.NaN))
        for (answer in 1..5) {
            assertEquals((1..answer).toList(), (1..5).filter { effortBarIsFilled(it, answer) })
        }
        assertFalse(effortBarIsFilled(1, null))
        assertFalse(effortBarIsFilled(1, 99))
    }

    @Test fun endpointWordsAlignWithRungsAndClampInsideTheStrip() {
        assertEquals(20f, effortEndpointLabelStart(300f, 20f, false))
        assertEquals(260f, effortEndpointLabelStart(300f, 20f, true))
        assertEquals(0f, effortEndpointLabelStart(300f, 120f, false))
        assertEquals(180f, effortEndpointLabelStart(300f, 120f, true))
        assertEquals(0f, effortEndpointLabelStart(10f, 120f, true))
        assertEquals(0f, effortEndpointLabelStart(Float.NaN, 20f, true))
    }

    @Test fun emptyLabelsLargeTextAndNarrowWidthRemainUsableAndNonfiniteProgressIsIgnored() {
        var chosen: Int? by mutableStateOf(null)
        compose.setContent { GetAGripTheme {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale = 2f)) {
                Box(Modifier.width(60.dp)) {
                    EffortPicker(chosen, emptyList(), "Effort", "effort") { chosen = it }
                }
            }
        } }
        val control = compose.onNodeWithTag("effort")
        control.performSemanticsAction(SemanticsActions.SetProgress) { assertFalse(it(Float.NaN)) }
        compose.runOnIdle { assertEquals(null, chosen) }
        control.performTouchInput { click(Offset(width * 0.9f, height / 2f)) }
        compose.runOnIdle { assertEquals(5, chosen) }
        val actions = control.fetchSemanticsNode().config[SemanticsActions.CustomActions]
        compose.runOnIdle { assertTrue(actions.single().action()) }
        compose.runOnIdle { assertEquals(null, chosen) }
    }
}
