// SPDX-License-Identifier: MPL-2.0
// Original contributions Copyright 2026 Nuri Bruner.
package run.nuri.getagrip.ui

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.net.MailTo
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import run.nuri.getagrip.ui.settings.*
import run.nuri.getagrip.ui.theme.GetAGripTheme
import kotlin.test.*

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@org.robolectric.annotation.GraphicsMode(org.robolectric.annotation.GraphicsMode.Mode.NATIVE)
class SupportTests {
    @get:Rule val compose = createComposeRule()
    private val context: Context get() = RuntimeEnvironment.getApplication()

    @Test fun draftUsesEmailOnlyAndPreservesSpecialCharacters() {
        val draft = SupportDraft("Bug: café & 10% #1", "What happened?\nA+B & x=y #test")
        val intent = draft.intent()
        assertEquals(Intent.ACTION_SENDTO, intent.action)
        // Parse the raw query before decoding each value. Android's old MailTo helper
        // decodes the entire query first, incorrectly splitting encoded ampersands.
        val uri = java.net.URI(intent.dataString)
        assertEquals("mailto", uri.scheme)
        assertNull(uri.rawFragment)
        assertEquals(SUPPORT_ADDRESS, uri.rawSchemeSpecificPart.substringBefore('?'))
        val query = uri.rawSchemeSpecificPart.substringAfter('?').split('&').associate {
            it.substringBefore('=') to java.net.URLDecoder.decode(it.substringAfter('='), "UTF-8")
        }
        assertEquals(draft.subject, query["subject"])
        assertEquals(draft.body, query["body"])
        assertEquals(draft.subject, intent.getStringExtra(Intent.EXTRA_SUBJECT))
        assertEquals(draft.body, intent.getStringExtra(Intent.EXTRA_TEXT))
        assertEquals(listOf(SUPPORT_ADDRESS), intent.getStringArrayExtra(Intent.EXTRA_EMAIL)?.toList())
        assertFalse(intent.hasExtra(Intent.EXTRA_STREAM))
    }

    @Test fun diagnosticsRequireABugReportAndExplicitInclusion() {
        val feature = supportDraft(context, false, "Progressor (demo)", "private breadcrumb")
        val plainBug = supportDraft(context, true, "Progressor")
        val diagnosticBug = supportDraft(context, true, "Progressor", "private breadcrumb")
        assertFalse(feature.body.contains("private breadcrumb"))
        assertFalse(plainBug.body.contains("private breadcrumb"))
        assertTrue(diagnosticBug.body.contains("private breadcrumb"))
        assertTrue(feature.body.contains("Android"))
        assertTrue(feature.body.contains("Gauge: Progressor (demo)"))
        assertTrue(feature.body.contains("Get a Grip"))
    }

    @Test fun missingOrBlockedEmailAppFallsBackWithoutCrashing() {
        for (failure in listOf(ActivityNotFoundException(), SecurityException())) {
            val unavailable = object : ContextWrapper(context) {
                override fun startActivity(intent: Intent) { throw failure }
            }
            assertFalse(SupportDraft("subject", "body").open(unavailable))
        }
    }

    @Test fun bothSettingsActionsOpenTheCorrectDraftWithoutSending() {
        val intents = mutableListOf<Intent>()
        val capturing = object : ContextWrapper(context) {
            override fun startActivity(intent: Intent) { intents += intent }
        }
        compose.setContent {
            CompositionLocalProvider(LocalContext provides capturing) {
                GetAGripTheme { SupportCard("Progressor") { null } }
            }
        }
        compose.onNodeWithText("Report a bug").performClick()
        compose.onNodeWithText("Request a feature").performClick()
        assertEquals(listOf("Get a Grip — Bug report", "Get a Grip — Feature request"),
            intents.map { MailTo.parse(it.dataString).subject })
    }

    @Test fun noEmailAppOffersACopyableDraft() {
        val unavailable = object : ContextWrapper(context) {
            override fun startActivity(intent: Intent) { throw ActivityNotFoundException() }
        }
        compose.setContent {
            CompositionLocalProvider(LocalContext provides unavailable) {
                GetAGripTheme { SupportCard("Progressor") { null } }
            }
        }
        val output = java.io.File("build/reports/support-card.png")
        output.parentFile.mkdirs()
        output.outputStream().use {
            compose.onRoot().captureToImage().asAndroidBitmap()
                .compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
        }
        compose.onNodeWithText("Request a feature").performClick()
        compose.onNodeWithText("No email app available").assertIsDisplayed()
        compose.onNodeWithText(SUPPORT_ADDRESS).assertIsDisplayed()
        compose.onNodeWithText("Copy email draft").performClick()
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
        val copied = clipboard.primaryClip!!.getItemAt(0).text.toString()
        assertTrue(copied.contains(SUPPORT_ADDRESS))
        assertTrue(copied.contains("Get a Grip — Feature request"))
        assertTrue(copied.contains("Gauge: Progressor"))
    }

    @Test fun bugDiagnosticsAreOfferedBeforeOpeningEmail() {
        val intents = mutableListOf<Intent>()
        val capturing = object : ContextWrapper(context) {
            override fun startActivity(intent: Intent) { intents += intent }
        }
        compose.setContent {
            CompositionLocalProvider(LocalContext provides capturing) {
                GetAGripTheme { SupportCard("Progressor") { "connection breadcrumb" } }
            }
        }
        compose.onNodeWithText("Report a bug").performClick()
        assertTrue(intents.isEmpty())
        compose.onNodeWithText("Send without").performClick()
        assertFalse(MailTo.parse(intents.single().dataString).body.contains("connection breadcrumb"))
        compose.onNodeWithText("Report a bug").performClick()
        compose.onNodeWithText("Include gauge diagnostics").performClick()
        assertTrue(MailTo.parse(intents.last().dataString).body.contains("connection breadcrumb"))
    }
}
