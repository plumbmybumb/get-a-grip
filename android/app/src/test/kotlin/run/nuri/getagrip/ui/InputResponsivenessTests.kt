// SPDX-License-Identifier: MPL-2.0
// Original contributions Copyright 2026 Nuri Bruner.

package run.nuri.getagrip.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.SoftwareKeyboardController
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performTouchInput
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import run.nuri.getagrip.ui.components.ValueRow
import run.nuri.getagrip.ui.components.ValueControl
import run.nuri.getagrip.ui.components.DialTrack
import run.nuri.getagrip.ui.components.RepeatingStep
import run.nuri.getagrip.ui.components.StepGlyph
import run.nuri.getagrip.ui.theme.GetAGripTheme

/** Real pointer events: pure ladder math cannot catch callbacks retaining old values. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class InputResponsivenessTests {
    @get:Rule val compose = createComposeRule()

    @Test fun dialCanReturnToItsOriginalValueAfterAnotherTap() {
        var value by mutableStateOf(5.0)
        compose.setContent {
            GetAGripTheme {
                DialTrack(value, listOf(5.0, 10.0, 20.0), { it.toInt().toString() }, "s", "Hold") {
                    value = it
                }
            }
        }
        val dial = compose.onNodeWithContentDescription("Hold")
        dial.performTouchInput { click(Offset(width - 1f, 22f)) }
        compose.runOnIdle { assertEquals(20.0, value, 0.0) }
        dial.performTouchInput { click(Offset(1f, 22f)) }
        compose.runOnIdle { assertEquals(5.0, value, 0.0) }
    }

    @Test fun consecutiveStepperTapsUseTheLatestValue() {
        var value by mutableStateOf(1)
        compose.setContent {
            GetAGripTheme {
                val current = value
                RepeatingStep(StepGlyph.Plus, current < 10, "Increase pulls") { value = current + 1; true }
            }
        }
        val plus = compose.onNodeWithContentDescription("Increase pulls")
        repeat(3) {
            plus.performTouchInput { click() }
            compose.runOnIdle { assertEquals(it + 2, value) }
        }
    }

    @Test fun heldStepperAccumulatesAndStopsAtTheBound() {
        var value by mutableStateOf(1)
        compose.setContent {
            GetAGripTheme {
                // Capture this frame's value just as ValueRow.stepBy does.
                val current = value
                RepeatingStep(StepGlyph.Plus, current < 5, "Increase pulls") {
                    if (current < 5) { value = current + 1; true } else false
                }
            }
        }
        val plus = compose.onNodeWithContentDescription("Increase pulls")
        plus.performTouchInput { down(center) }
        compose.mainClock.advanceTimeBy(1_500)
        compose.runOnIdle { assertEquals(5, value) }
        plus.performTouchInput { up() }
        compose.mainClock.advanceTimeBy(500)
        compose.runOnIdle { assertEquals(5, value) }
    }

    @Test fun movingOffStepperCancelsWithoutApplyingATap() {
        var value by mutableStateOf(1)
        compose.setContent {
            GetAGripTheme {
                RepeatingStep(StepGlyph.Plus, true, "Increase pulls") { value++; true }
            }
        }
        compose.onNodeWithContentDescription("Increase pulls").performTouchInput {
            down(center)
            moveBy(Offset(0f, 100f))
            up()
        }
        compose.mainClock.advanceTimeBy(1_000)
        compose.runOnIdle { assertEquals(1, value) }
    }
    @Test fun numberEntryKeepsDoneVisibleAndHandsOffFocusWithoutHidingKeyboard() {
        var first by mutableStateOf(60.0)
        var second by mutableStateOf(10.0)
        var hides = 0
        val keyboard = object : SoftwareKeyboardController {
            override fun show() = Unit
            override fun hide() { hides++ }
        }
        compose.setContent {
            GetAGripTheme {
                CompositionLocalProvider(LocalSoftwareKeyboardController provides keyboard) {
                    Column(Modifier.width(340.dp)) {
                        ValueRow("Break between sets", first, 0.0..180.0, unit = "s", control = ValueControl.None) { first = it }
                        ValueRow("Hold", second, 0.0..180.0, unit = "s", control = ValueControl.None) { second = it }
                    }
                }
            }
        }
        compose.onNodeWithContentDescription("Break between sets, 60 s. Double tap to type a value.").performClick()
        compose.onNodeWithText("Done").assertIsDisplayed()
        val title = compose.onNodeWithText("Break between sets").fetchSemanticsNode().boundsInRoot
        val editor = compose.onNode(hasSetTextAction()).fetchSemanticsNode().boundsInRoot
        assertTrue("title should fit beside the editor", title.height < editor.height * 2)
        compose.onNode(hasSetTextAction()).performTextInput("22")
        compose.onNodeWithContentDescription("Hold, 10 s. Double tap to type a value.").performClick()
        compose.runOnIdle {
            assertEquals(22.0, first, 0.0)
            assertEquals("moving to another field must not hide its keyboard", 0, hides)
        }
        compose.onNode(hasSetTextAction()).performTextInput("7")
        compose.onNodeWithText("Done").performClick()
        compose.runOnIdle {
            assertEquals(7.0, second, 0.0)
            assertEquals(1, hides)
        }
    }

}
