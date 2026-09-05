// SPDX-License-Identifier: MPL-2.0
// Original contributions Copyright 2026 Nuri Bruner.
package run.nuri.getagrip.ui

import android.content.res.Configuration
import android.os.LocaleList
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.UriHandler
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import run.nuri.getagrip.ui.settings.LegalCard
import run.nuri.getagrip.ui.theme.GetAGripTheme
import java.util.Locale
import kotlin.test.assertEquals

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@org.robolectric.annotation.GraphicsMode(org.robolectric.annotation.GraphicsMode.Mode.NATIVE)
class LegalCardTests {
    @get:Rule val compose = createComposeRule()

    @Test fun policiesOpenOnlyOnTapAndUseFrenchForAFrenchDevice() {
        val opened = mutableListOf<String>()
        val handler = object : UriHandler { override fun openUri(uri: String) { opened += uri } }
        val configuration = Configuration().apply { setLocales(LocaleList(Locale.CANADA_FRENCH)) }
        compose.setContent {
            CompositionLocalProvider(LocalUriHandler provides handler, LocalConfiguration provides configuration) {
                GetAGripTheme { LegalCard() }
            }
        }
        assertEquals(emptyList(), opened)
        // This test overrides configuration only; resource strings retain the test context's English.
        compose.onNodeWithText("Privacy policy").performClick()
        compose.onNodeWithText("Terms of use").performClick()
        assertEquals(listOf("https://nuri.run/getagrip/privacy/fr", "https://nuri.run/getagrip/terms/fr"), opened)
    }

    @Test fun missingBrowserShowsReadablePolicyAddressInsteadOfCrashing() {
        val handler = object : UriHandler {
            override fun openUri(uri: String) { throw IllegalArgumentException("No browser") }
        }
        val configuration = Configuration().apply { setLocales(LocaleList(Locale.ENGLISH)) }
        compose.setContent {
            CompositionLocalProvider(LocalUriHandler provides handler, LocalConfiguration provides configuration) {
                GetAGripTheme { LegalCard() }
            }
        }
        compose.onNodeWithText("Privacy policy").performClick()
        compose.onNodeWithText("https://nuri.run/getagrip/privacy").assertIsDisplayed()
        compose.onNodeWithText("Done").performClick()
        compose.onNodeWithText("https://nuri.run/getagrip/privacy").assertDoesNotExist()
    }
}
