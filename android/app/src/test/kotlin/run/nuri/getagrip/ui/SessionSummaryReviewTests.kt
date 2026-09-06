// SPDX-License-Identifier: MPL-2.0
// Original contributions Copyright 2026 Nuri Bruner.
package run.nuri.getagrip.ui

import androidx.compose.ui.test.*
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.junit4.v2.createComposeRule
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import run.nuri.getagrip.engine.*
import run.nuri.getagrip.runner.*
import run.nuri.getagrip.ui.runner.SessionSummaryScreen
import run.nuri.getagrip.ui.theme.GetAGripTheme
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SessionSummaryReviewTests {
    @get:Rule val compose = createComposeRule()
    private fun outcome(): SessionOutcome {
        val candidates = (10..15).map { MaxCandidate(GripSpec(edgeMM = it), Side.left, 12.0, null) }
        return SessionOutcome(SessionPlan(), "Daily", emptyList(), Instant.now(), Instant.now(),
            12.0, 10.0, 60.0, 6, 6, false, GaugeKind.progressor, true, candidates)
    }
    @Test fun manyFirstPeaksStartCollapsedAndSavingNeedsNoSelection() {
        var decision: SessionSummaryDecision? = null
        compose.setContent { GetAGripTheme {
            SessionSummaryScreen(outcome(), 2) { _, chosen -> decision = chosen; true }
        } }
        compose.onNodeWithContentDescription("New peaks to review").assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Collapsed"))
        compose.onNodeWithContentDescription("Sets").assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Collapsed"))
        compose.onNodeWithContentDescription("Discard this session").assertIsDisplayed()
        compose.onAllNodes(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Use as max")).assertCountEquals(0)
        compose.onNodeWithText("Save and finish").assertIsDisplayed().performClick()
        compose.runOnIdle { assertTrue(decision!!.save); assertTrue(decision!!.newMaxes.isEmpty()) }
    }
    @Test fun selectedEffortReachesSavedWorkout() {
        var decision: SessionSummaryDecision? = null
        compose.setContent { GetAGripTheme {
            SessionSummaryScreen(outcome(), 2) { _, chosen -> decision = chosen; true }
        } }
        compose.onNodeWithTag("effort.overall").performSemanticsAction(SemanticsActions.SetProgress) { it(4f) }
        compose.onNodeWithText("Save and finish").assertIsDisplayed().performClick()
        compose.runOnIdle { assertEquals(RPE.hard, decision!!.rpe) }
    }

    @Test fun selectionIsReversibleAndFailedSaveKeepsItForRetry() {
        val outcome = outcome()
        val attempts = mutableListOf<SessionSummaryDecision>()
        compose.setContent { GetAGripTheme {
            SessionSummaryScreen(outcome, 2) { _, decision -> attempts.add(decision); attempts.size > 1 }
        } }
        compose.onNodeWithContentDescription("New peaks to review").performScrollTo().performClick()
        compose.onNodeWithText("Save and finish").assertIsDisplayed()
        compose.onNodeWithContentDescription("Discard this session").assertIsDisplayed()
        compose.onAllNodes(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Use as max"))[0].performScrollTo().performClick()
        compose.onNode(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Selected")).performClick()
        compose.onAllNodes(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Use as max")).assertCountEquals(6)
        compose.onAllNodes(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Use as max"))[1].performScrollTo().performClick()
        compose.onNodeWithContentDescription("New peaks to review").performScrollTo().performClick()
        compose.onNodeWithText("Save and finish").assertIsDisplayed().performClick()
        compose.onNodeWithText("Couldn't save this workout. Please try again.").assertExists()
        compose.onNodeWithText("Save and finish").performClick()
        compose.runOnIdle {
            assertEquals(2, attempts.size)
            attempts.forEach { assertEquals(listOf(outcome.maxCandidates[1]), it.newMaxes) }
        }
    }
}
