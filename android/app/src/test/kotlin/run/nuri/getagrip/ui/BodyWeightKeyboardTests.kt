// SPDX-License-Identifier: MPL-2.0
// Original contributions Copyright 2026 Nuri Bruner.
package run.nuri.getagrip.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import run.nuri.getagrip.ui.components.BodyWeightField
import run.nuri.getagrip.ui.theme.GetAGripTheme
import kotlin.test.assertEquals
import kotlin.test.assertNull

/// The body-weight keypad always has a way out (Thomas, 2026-10-31: "I can't get the keyboard
/// out so I can't Save or exit the settings screen"): a Done in the row while editing, and a
/// number typed before the screen goes away is kept rather than dropped.
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class BodyWeightKeyboardTests {
    @get:Rule val compose = createComposeRule()

    @Test fun theRowCarriesItsOwnDoneWhichCommitsAndLetsGoOfTheField() {
        var stored: Double? = null
        compose.setContent {
            GetAGripTheme { BodyWeightField(kilograms = stored, onChange = { stored = it }) }
        }
        compose.onNodeWithTag("bodyWeight.done").assertDoesNotExist()
        compose.onNodeWithTag("bodyWeight.field").performClick()
        compose.onNodeWithTag("bodyWeight.field").assertIsFocused()
        compose.onNodeWithTag("bodyWeight.field").performTextInput("72")
        compose.onNodeWithTag("bodyWeight.done").assertIsDisplayed().performClick()
        compose.onNodeWithTag("bodyWeight.field").assertIsNotFocused()
        compose.runOnIdle { assertEquals(72.0, stored) }
        compose.onNodeWithTag("bodyWeight.done").assertDoesNotExist()
    }

    @Test fun leavingTheScreenMidEditKeepsTheNumber() {
        var stored: Double? = null
        var shown by mutableStateOf(true)
        compose.setContent {
            GetAGripTheme { if (shown) BodyWeightField(kilograms = stored, onChange = { stored = it }) }
        }
        compose.onNodeWithTag("bodyWeight.field").performClick()
        compose.onNodeWithTag("bodyWeight.field").performTextInput("68,5")
        compose.runOnIdle { assertNull(stored) }
        shown = false
        compose.runOnIdle { assertEquals(68.5, stored) }
    }
}
