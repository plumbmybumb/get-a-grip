// SPDX-License-Identifier: MPL-2.0
// Original contributions Copyright 2026 Nuri Bruner.

package run.nuri.getagrip.ui

import android.content.Intent
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import run.nuri.getagrip.GetAGripApplication
import run.nuri.getagrip.MainActivity

/** A second tap landing during a push or a pop must not stack a second picker, nor pop
 * Settings out from under its own tab. Real Activity, real NavHost, the clock held still so
 * both taps land inside the transition the way a quick thumb's do. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w393dp-h820dp-notnight-mdpi", application = GetAGripApplication::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SettingsNavigationTests {
    @get:Rule val compose = createEmptyComposeRule()
    private lateinit var application: GetAGripApplication
    private lateinit var controller: ActivityController<MainActivity>

    @Before fun openSettings() {
        application = ApplicationProvider.getApplicationContext()
        compose.runOnUiThread {
            val intent = Intent(application, MainActivity::class.java).putExtra("mockDevice", true)
            controller = Robolectric.buildActivity(MainActivity::class.java, intent).setup().visible()
        }
        compose.waitForIdle()
        compose.onNodeWithText("Settings").performClick()
        compose.waitForIdle()
    }

    @After fun close() {
        if (::controller.isInitialized) compose.runOnUiThread { controller.pause().stop().destroy() }
        if (::application.isInitialized) {
            application.gaugeScope.cancel()
            application.storeScope.cancel()
            application.database.close()
        }
    }

    private val pickerRow = hasText("Tap to choose yours", substring = true)

    private fun systemBack() {
        compose.runOnUiThread { controller.get().onBackPressedDispatcher.onBackPressed() }
        compose.waitForIdle()
    }

    @Test fun aDoubleTapOpensThePickerOnce() {
        compose.mainClock.autoAdvance = false
        val row = compose.onNode(pickerRow and hasClickAction())
        row.performClick()
        row.performClick()
        compose.mainClock.autoAdvance = true
        compose.waitForIdle()
        compose.onNodeWithText("Gauge").assertIsDisplayed()
        // One back leaves ONE picker.
        systemBack()
        compose.onNode(pickerRow).assertIsDisplayed()
    }

    @Test fun aDoubleTapOnBackNeverPopsSettingsItself() {
        compose.onNode(pickerRow and hasClickAction()).performClick()
        compose.waitForIdle()
        compose.mainClock.autoAdvance = false
        val back = compose.onNodeWithContentDescription("Back")
        back.performClick()
        back.performClick()
        compose.mainClock.autoAdvance = true
        compose.waitForIdle()
        compose.onNode(pickerRow).assertIsDisplayed()
    }
}
