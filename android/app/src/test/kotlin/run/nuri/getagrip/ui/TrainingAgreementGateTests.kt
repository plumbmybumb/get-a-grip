// SPDX-License-Identifier: MPL-2.0
// Original contributions Copyright 2026 Nuri Bruner.
package run.nuri.getagrip.ui

import android.graphics.Bitmap
import androidx.compose.material3.Text
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import run.nuri.getagrip.ui.theme.GetAGripTheme
import java.io.File
import kotlin.test.assertEquals

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class TrainingAgreementGateTests {
    @get:Rule val compose = createComposeRule()
    @Before fun resetAcceptance() {
        RuntimeEnvironment.getApplication().filesDir.listFiles()?.filter { it.name.startsWith("legal-acceptances.json") }?.forEach { it.delete() }
    }
    @Test fun readingAndDecliningNeverEnterTraining() {
        var cancelled = 0
        compose.setContent { GetAGripTheme { TrainingAgreementGate(onCancel = { cancelled++ }) { Text("Training started") } } }
        compose.onNodeWithTag("legal.continue").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithText("Terms of use").performScrollTo().performClick()
        compose.onNodeWithText("The app and its provider").assertIsDisplayed()
        compose.onNodeWithText("Training started").assertDoesNotExist()
        compose.onNodeWithText("Done").performClick()
        compose.onNodeWithTag("legal.agree").performScrollTo().assertIsOff()
        compose.onNodeWithText("Not now").performClick()
        compose.runOnIdle { assertEquals(1, cancelled) }
        compose.onNodeWithText("Training started").assertDoesNotExist()
    }
    @Test fun explicitAgreementEntersTrainingOnlyAfterSaving() {
        compose.setContent { GetAGripTheme { TrainingAgreementGate(onCancel = {}) { Text("Training started") } } }
        compose.onNodeWithTag("legal.agree").performScrollTo().performClick()
        compose.onNodeWithTag("legal.continue").performScrollTo().assertIsEnabled()
        compose.onNodeWithTag("legal.continue").performClick()
        compose.waitUntil(5_000) { compose.onAllNodesWithText("Training started").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Training started").assertIsDisplayed()
    }
    @Test fun agreementScreenRenders() {
        compose.setContent { GetAGripTheme { TrainingAgreementGate(onCancel = {}) { Text("Training started") } } }
        compose.onNodeWithText("Before you train").assertIsDisplayed()
        val path = File("build/legal-qa/android-agreement.png")
        path.parentFile.mkdirs()
        path.outputStream().use { compose.onRoot().captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it) }
        compose.onNodeWithTag("legal.continue").performScrollTo().assertIsNotEnabled()
        File("build/legal-qa/android-agreement-controls.png").outputStream().use {
            compose.onRoot().captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it)
        }
    }
}
