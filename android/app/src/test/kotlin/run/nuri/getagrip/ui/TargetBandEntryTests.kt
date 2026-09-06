// SPDX-License-Identifier: MPL-2.0
// Original contributions Copyright 2026 Nuri Bruner.
package run.nuri.getagrip.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import run.nuri.getagrip.engine.*
import run.nuri.getagrip.ui.builder.TargetBandRow
import run.nuri.getagrip.ui.theme.GetAGripTheme
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class TargetBandEntryTests {
    @get:Rule val compose = createComposeRule()

    @Test fun exactKilogramsAcceptDecimalCommaAndClearPercentages() {
        val set = mutableStateOf(SetPlan(targetLoKg = 10.0, targetHiKg = 15.0))
        compose.setContent {
            GetAGripTheme {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    TargetBandRow(set.value, MaxTable(), HandMode.bothHands) { set.value = it }
                }
            }
        }
        compose.onNodeWithContentDescription("Target load").performClick()
        fun enter(label: String, value: String) {
            compose.onNode(hasContentDescription(label + ", ", substring = true))
                .performScrollTo().performClick()
            compose.onNode(hasSetTextAction()).performTextInput(value)
            compose.onNode(hasSetTextAction()).performImeAction()
        }
        enter("Lower bound", "10,3")
        enter("Upper bound", "10.4")
        compose.runOnIdle {
            assertEquals(10.3, set.value.targetLoKg)
            assertEquals(10.4, set.value.targetHiKg)
            assertEquals(null, set.value.targetLoPercent)
            assertEquals(null, set.value.targetHiPercent)
        }
    }

    @Test fun exactPercentagesAndNarrowBandsRemainEditable() {
        val set = mutableStateOf(SetPlan(targetLoPercent = 0.20, targetHiPercent = 0.30))
        compose.setContent {
            GetAGripTheme {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    TargetBandRow(set.value, MaxTable(), HandMode.bothHands) { set.value = it }
                }
            }
        }
        compose.onNodeWithContentDescription("Target load").performClick()
        compose.onNodeWithText("Custom").performScrollTo().performClick()
        fun enter(label: String, old: Int, value: Int) {
            compose.onNodeWithContentDescription("$label, $old %. Double tap to type a value.")
                .performScrollTo().performClick()
            compose.onNode(hasSetTextAction()).performTextInput(value.toString())
            compose.onNode(hasSetTextAction()).performImeAction()
        }
        enter("Lower bound", 20, 17)
        enter("Upper bound", 30, 19)
        compose.runOnIdle {
            assertEquals(0.17, set.value.targetLoPercent)
            assertEquals(0.19, set.value.targetHiPercent)
        }
        enter("Lower bound", 17, 1)
        enter("Upper bound", 19, 2)
        compose.onNodeWithContentDescription("Lower bound").performSemanticsAction(SemanticsActions.SetProgress) { it(0.01f) }
        compose.runOnIdle {
            assertTrue(set.value.targetLoPercent!! >= 0.01)
            assertTrue(set.value.targetLoPercent!! <= set.value.targetHiPercent!!)
        }
        // Cross the other endpoint, then adjust at the top edge without an empty clamp range.
        enter("Lower bound", 1, 99)
        enter("Upper bound", 99, 100)
        compose.onNodeWithContentDescription("Upper bound").performSemanticsAction(SemanticsActions.SetProgress) { it(1f) }
        compose.runOnIdle {
            assertEquals(0.99, set.value.targetLoPercent)
            assertEquals(1.0, set.value.targetHiPercent)
        }
    }
}
