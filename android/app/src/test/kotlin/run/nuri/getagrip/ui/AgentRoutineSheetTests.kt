// SPDX-License-Identifier: MPL-2.0
// Original contributions Copyright 2026 Nuri Bruner.

package run.nuri.getagrip.ui

import android.content.ClipData
import android.content.ClipboardManager
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import run.nuri.getagrip.engine.AgentRoutine
import run.nuri.getagrip.ui.share.AgentRoutineSheet
import run.nuri.getagrip.ui.share.pastedText
import run.nuri.getagrip.ui.theme.GetAGripTheme
import kotlin.test.assertEquals
import kotlin.test.assertNull

/// "Create with AI": the instructions leave verbatim, a paste that is not a routine says why
/// INSIDE the sheet, and a routine leaves only once the sheet has gone.
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w400dp-h2000dp-mdpi")
class AgentRoutineSheetTests {
    @get:Rule val compose = createComposeRule()

    private val clipboard: ClipboardManager =
        RuntimeEnvironment.getApplication().getSystemService(ClipboardManager::class.java)

    private var reading: AgentRoutine.Reading? = null

    private fun show() {
        compose.setContent { GetAGripTheme { AgentRoutineSheet(onRoutine = { reading = it }, onClose = {}) } }
    }

    @Test fun copyingPutsTheInstructionsOnTheClipboardVerbatim() {
        show()
        compose.onNodeWithText("Copy instructions").performScrollTo().performClick()
        assertEquals(AgentRoutine.instructions, clipboard.primaryClip?.getItemAt(0)?.text?.toString())
        compose.onNodeWithText("Copied").assertExists()
    }

    @Test fun aPasteThatIsNotARoutineSaysWhyAndStays() {
        clipboard.setPrimaryClip(ClipData.newPlainText("chat", "Sounds good! Want me to write it?"))
        show()
        compose.onNodeWithText("Paste the routine").performScrollTo().performClick()
        compose.onNodeWithText(AgentRoutine.Failure.noRoutine.message).assertExists()
        assertNull(reading)
    }

    /// **The clipboard is read as TEXT and nothing else.** `coerceToText` turns an item
    /// that is a `content://` link into text by OPENING it — in this app's name — and
    /// reading whatever comes back. Whoever filled the clipboard chooses the link, so a
    /// Paste tap was a way to make the app read a provider of somebody else's choosing.
    /// This provider holds a perfectly good routine; it must never be asked for it.
    @Test fun aClipboardLinkIsNeverOpenedToFindARoutine() {
        val trap = org.robolectric.Robolectric.buildContentProvider(TrapProvider::class.java)
            .create(TrapProvider.AUTHORITY).get()
        clipboard.setPrimaryClip(
            ClipData.newRawUri("reply", android.net.Uri.parse("content://${TrapProvider.AUTHORITY}/routine")),
        )
        show()
        compose.onNodeWithText("Paste the routine").performScrollTo().performClick()
        compose.waitForIdle()

        assertEquals(0, trap.opened, "the link on the clipboard was opened")
        assertNull(reading)
        compose.onNodeWithText(AgentRoutine.Failure.noRoutine.message).assertExists()
    }

    /// A clipboard past the reader's cap is not handed to the reader at all — even with a
    /// perfectly good routine at the front of it.
    @Test fun aClipboardPastTheCapIsRefusedWhereTheClimberCanPasteAgain() {
        val routine = """{"name": "Pasted", "sets": [{"edgeMm": 20, "pulls": 6}]}"""
        clipboard.setPrimaryClip(ClipData.newPlainText("reply", routine + " ".repeat(AgentRoutine.maxInputUnits)))
        show()
        compose.onNodeWithText("Paste the routine").performScrollTo().performClick()
        compose.waitForIdle()

        assertNull(reading)
        compose.onNodeWithText(AgentRoutine.Failure.unreadable.message).assertExists()
    }

    /// The join, on its own: the items that carry text, a newline between them, and null the
    /// moment the whole would pass the cap — decided on lengths, so it is never built.
    @Test fun pastedItemsAreJoinedByTheirTextAndBoundedByTheCap() {
        assertEquals("", pastedText(emptyList()))
        assertEquals("one", pastedText(listOf("one")))
        assertEquals("one\ntwo", pastedText(listOf("one", "two")))
        // An item with no text of its own (a link, an intent) contributes nothing at all.
        assertEquals("one\ntwo", pastedText(listOf(null, "one", null, "two", null)))
        assertEquals("", pastedText(listOf(null, null)))
        // An EMPTY text is still an item, as it always was.
        assertEquals("one\n\ntwo", pastedText(listOf("one", "", "two")))

        assertEquals("abcde", pastedText(listOf("abcde"), cap = 5))
        assertNull(pastedText(listOf("abcdef"), cap = 5))
        assertEquals("ab\ncd", pastedText(listOf("ab", "cd"), cap = 5))
        assertNull(pastedText(listOf("ab", "cde"), cap = 5), "the separator counts")
        assertNull(pastedText(listOf("abc", "def", "x".repeat(1_000_000)), cap = 5))
        assertEquals(AgentRoutine.maxInputUnits, pastedText(listOf("x".repeat(AgentRoutine.maxInputUnits)))?.length)
        assertNull(pastedText(listOf("x".repeat(AgentRoutine.maxInputUnits + 1))))
    }

    @Test fun aRoutineLeavesOnceTheSheetHasGone() {
        clipboard.setPrimaryClip(ClipData.newPlainText("reply", """```json
{"name": "Pasted", "sets": [{"edgeMm": 20, "pulls": 6}]}
```"""))
        show()
        compose.onNodeWithText("Paste the routine").performScrollTo().performClick()
        compose.waitUntil(5_000) { reading != null }
        assertEquals("Pasted", reading?.draft?.plan?.name)
    }
}

/// A provider holding a routine, which counts every time anybody opens it.
class TrapProvider : android.content.ContentProvider() {
    var opened = 0
        private set

    override fun onCreate(): Boolean = true

    override fun openFile(uri: android.net.Uri, mode: String): android.os.ParcelFileDescriptor {
        opened += 1
        val file = java.io.File.createTempFile("trap", ".txt")
        file.writeText("""{"name": "Trapped", "sets": [{"edgeMm": 20, "pulls": 6}]}""")
        return android.os.ParcelFileDescriptor.open(file, android.os.ParcelFileDescriptor.MODE_READ_ONLY)
    }

    override fun getType(uri: android.net.Uri): String = "text/plain"
    override fun getStreamTypes(uri: android.net.Uri, mimeTypeFilter: String): Array<String> = arrayOf("text/plain")

    override fun query(
        uri: android.net.Uri, projection: Array<out String>?, selection: String?,
        selectionArgs: Array<out String>?, sortOrder: String?,
    ): android.database.Cursor? { opened += 1; return null }

    override fun insert(uri: android.net.Uri, values: android.content.ContentValues?): android.net.Uri? = null
    override fun delete(uri: android.net.Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0
    override fun update(
        uri: android.net.Uri, values: android.content.ContentValues?, selection: String?,
        selectionArgs: Array<out String>?,
    ): Int = 0

    companion object {
        const val AUTHORITY = "run.nuri.getagrip.test.trap"
    }
}
