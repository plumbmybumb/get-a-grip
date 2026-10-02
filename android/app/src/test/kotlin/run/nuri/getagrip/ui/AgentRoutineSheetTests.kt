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
