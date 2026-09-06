// SPDX-License-Identifier: MPL-2.0
// Original contributions Copyright 2026 Nuri Bruner.
package run.nuri.getagrip.ui

import android.content.res.Configuration
import android.os.LocaleList
import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import run.nuri.getagrip.store.LegalAgreementStore
import run.nuri.getagrip.store.LegalBundle
import run.nuri.getagrip.ui.theme.GetAGripTheme
import java.io.File
import java.util.Locale
import kotlin.test.assertEquals
import kotlin.test.assertFalse

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class LegalSettingsRecordTests {
    @get:Rule val compose = createComposeRule()
    private val context get() = RuntimeEnvironment.getApplication()
    private val receipt get() = File(context.filesDir, "legal-acceptances.json")

    @Before fun resetFixture() {
        context.filesDir.listFiles()?.filter { it.name.startsWith("legal-acceptances.json") }?.forEach { it.delete() }
    }

    private fun showSettings() {
        val configuration = Configuration().apply { setLocales(LocaleList(Locale.ENGLISH)) }
        compose.setContent {
            CompositionLocalProvider(LocalConfiguration provides configuration) {
                GetAGripTheme { Column { LegalSettingsContent() } }
            }
        }
    }

    @Test fun readingDocumentsCreatesNoAcceptanceOrEmptyRecordPrompt() {
        showSettings()
        compose.onNodeWithText("Your agreement record").assertDoesNotExist()
        compose.onNodeWithText("Terms of use").performClick()
        compose.onNodeWithText("The app and its provider").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Done").performClick()
        compose.onNodeWithText("Privacy policy").performClick()
        compose.onNodeWithText("Who is responsible").assertIsDisplayed()
        compose.onNodeWithTag("legal.agree").assertDoesNotExist()
        compose.onNodeWithTag("legal.continue").assertDoesNotExist()
        compose.runOnIdle { assertFalse(receipt.exists()) }
    }

    @Test fun genuineHistoricalRecordRemainsReadableAndUnmodified() = runTest {
        LegalAgreementStore(context).accept(LegalBundle.load(context), "en")
        val original = receipt.readText()
        showSettings()
        compose.onNodeWithText("Your agreement record").performClick()
        compose.onNodeWithText("Stored on this device. This records acceptance of the Terms, not an injury release or health-data consent.").assertIsDisplayed()
        compose.onNodeWithText("Share a copy").assertIsDisplayed()
        compose.runOnIdle { assertEquals(original, receipt.readText()) }
    }
}
