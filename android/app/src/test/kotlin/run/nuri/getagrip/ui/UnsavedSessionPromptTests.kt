// SPDX-License-Identifier: MPL-2.0
// Original contributions Copyright 2026 Nuri Bruner.

package run.nuri.getagrip.ui

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import run.nuri.getagrip.MemoryStoreGateway
import run.nuri.getagrip.engine.GripSpec
import run.nuri.getagrip.engine.RepSummary
import run.nuri.getagrip.engine.SessionPlan
import run.nuri.getagrip.engine.SetPlan
import run.nuri.getagrip.runner.FinishedSessionDraft
import run.nuri.getagrip.runner.FinishedSessionDraftStore
import run.nuri.getagrip.runner.UnsavedSessionRecovery
import run.nuri.getagrip.store.DayClock
import run.nuri.getagrip.store.HistoryFeed
import run.nuri.getagrip.store.InMemoryRoutineSettings
import run.nuri.getagrip.store.LocalHistoryFeed
import run.nuri.getagrip.store.LocalTemplateStore
import run.nuri.getagrip.store.RecordingAlarmScheduler
import run.nuri.getagrip.store.StoreWriter
import run.nuri.getagrip.store.TemplateStore
import run.nuri.getagrip.store.asHistorySource
import run.nuri.getagrip.ui.theme.GetAGripTheme
import java.io.File
import java.nio.file.Files
import java.time.Instant
import java.util.UUID
import kotlin.test.assertNotNull

/// **A Save that fails says so.** It used to re-enable the button and nothing else, which
/// reads as a tap that did nothing — on the one prompt standing between a finished workout
/// and losing it.
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w393dp-h820dp-mdpi")
class UnsavedSessionPromptTests {
    @get:Rule val compose = createComposeRule()

    private val directory: File = Files.createTempDirectory("prompt").toFile()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)

    @After fun tearDown() { scope.cancel(); directory.deleteRecursively() }

    private class RefusingGateway : MemoryStoreGateway() {
        override suspend fun write(work: suspend (StoreWriter) -> Unit) {
            super.write { error("disk full") }
        }
    }

    @Test fun aFailedSaveShowsTheStoresReasonAndKeepsTheDraft() {
        val gateway = RefusingGateway()
        val store = TemplateStore(gateway, DayClock(), InMemoryRoutineSettings(),
            RecordingAlarmScheduler(), scope)
        val drafts = FinishedSessionDraftStore(File(directory, FinishedSessionDraftStore.FILE_NAME))
        val started = Instant.now().minusSeconds(3_600)
        drafts.save(FinishedSessionDraft(
            id = UUID.randomUUID(), templateID = null, templateName = "Evening burn",
            sessionsPerDayTarget = 2,
            plan = SessionPlan(name = "Evening burn", sets = listOf(SetPlan(grip = GripSpec(), repsPerSide = 1))),
            reps = listOf(RepSummary(grip = GripSpec(), heldSeconds = 10.0, peakKg = 14.0, avgKg = 12.0)),
            startedAt = started, finishedAt = started.plusSeconds(600),
        ))
        val recovery = UnsavedSessionRecovery(drafts, store)
        val feed = HistoryFeed(gateway.asHistorySource(), scope)

        compose.setContent {
            CompositionLocalProvider(LocalTemplateStore provides store, LocalHistoryFeed provides feed) {
                GetAGripTheme { UnsavedSessionPrompt(recovery) }
            }
        }
        compose.waitUntil(5_000) { count("Save") > 0 }
        compose.onNodeWithText("Save").performClick()

        compose.waitUntil(5_000) { count("disk full", substring = true) > 0 }
        compose.onNodeWithText("Save").assertIsEnabled()
        assertNotNull(drafts.load(), "the draft survives for another try")
    }

    private fun count(text: String, substring: Boolean = false): Int =
        compose.onAllNodes(hasText(text, substring = substring)).fetchSemanticsNodes().size
}
