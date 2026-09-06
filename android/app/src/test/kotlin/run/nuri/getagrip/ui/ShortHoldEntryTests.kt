// SPDX-License-Identifier: MPL-2.0
// Original contributions Copyright 2026 Nuri Bruner.
package run.nuri.getagrip.ui

import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import run.nuri.getagrip.engine.SetPlan
import run.nuri.getagrip.ui.components.IntValueRow
import run.nuri.getagrip.ui.components.ValueControl
import run.nuri.getagrip.ui.theme.GetAGripTheme
import kotlin.test.assertEquals

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ShortHoldEntryTests {
    @get:Rule val compose = createComposeRule()

    @Test fun typingShortHoldsCommitsWithoutSnappingToTheDial() {
        val hold = mutableIntStateOf(10)
        compose.setContent {
            GetAGripTheme {
                IntValueRow(title = "Hold", value = hold.intValue, range = 1..60,
                    unit = "s", limit = SetPlan.holdRange,
                    control = ValueControl.Dial(listOf(1.0, 3.0, 5.0, 10.0, 60.0))) {
                    hold.intValue = it
                }
            }
        }
        for (seconds in listOf(2, 1)) {
            compose.onNodeWithContentDescription(
                "Hold, ${hold.intValue} s. Double tap to type a value.").performClick()
            compose.onNode(hasSetTextAction()).performTextInput(seconds.toString())
            compose.onNode(hasSetTextAction()).performImeAction()
            compose.runOnIdle { assertEquals(seconds, hold.intValue) }
            compose.onNodeWithContentDescription(
                "Hold, $seconds s. Double tap to type a value.").assertIsDisplayed()
        }
    }
}
