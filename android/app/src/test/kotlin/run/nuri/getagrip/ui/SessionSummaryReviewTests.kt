// SPDX-License-Identifier: MPL-2.0
// Original contributions Copyright 2026 Nuri Bruner.
package run.nuri.getagrip.ui

import androidx.compose.ui.test.*
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.unit.Density
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

    private fun sameGripOutcome(): SessionOutcome = outcome().copy(
        peakKg = 40.0, avgKg = 35.0,
        maxCandidates = listOf(
            MaxCandidate(GripSpec(), Side.left, 35.0, null),
            MaxCandidate(GripSpec(), Side.right, 30.0, null),
            MaxCandidate(GripSpec(), Side.both, 40.0, null),
        ),
    )

    @Test
    @Config(qualifiers = "w393dp-h820dp-mdpi")
    fun selectingRightHandLeavesMatchingLeftAndBothPeaksUnselected() {
        val outcome = sameGripOutcome()
        var decision: SessionSummaryDecision? = null
        compose.setContent { GetAGripTheme {
            SessionSummaryScreen(outcome, 2) { _, chosen -> decision = chosen; true }
        } }
        compose.onNodeWithContentDescription("New peaks to review").performScrollTo().performClick()
        for (hand in listOf("Left hand", "Right hand", "Both hands")) {
            compose.onNodeWithText(hand).performScrollTo().assertIsDisplayed()
            compose.onNodeWithContentDescription(hand, substring = true)
                .assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Use as max"))
        }
        captureReview("summary-per-hand.png")
        compose.onNodeWithContentDescription("Right hand", substring = true).performScrollTo().performClick()
        compose.onNodeWithContentDescription("Right hand", substring = true)
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Selected"))
        for (hand in listOf("Left hand", "Both hands")) {
            compose.onNodeWithContentDescription(hand, substring = true)
                .assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Use as max"))
        }
        compose.onNodeWithText("Save and finish").assertIsDisplayed().performClick()
        compose.runOnIdle { assertEquals(listOf(outcome.maxCandidates[1]), decision!!.newMaxes) }
    }

    @Test
    @Config(qualifiers = "w393dp-h820dp-mdpi")
    fun everyHandLabelRemainsReadableAtLargeText() {
        val outcome = sameGripOutcome()
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale = 2f)) {
                GetAGripTheme { SessionSummaryScreen(outcome, 2) { _, _ -> true } }
            }
        }
        compose.onNodeWithContentDescription("New peaks to review").performScrollTo().performClick()
        for (hand in listOf("Left hand", "Right hand", "Both hands")) {
            compose.onNodeWithText(hand).performScrollTo().assertIsDisplayed()
            compose.onNodeWithContentDescription(hand, substring = true).assertExists()
        }
        captureReview("summary-per-hand-large-text.png")
        compose.onNodeWithText("Save and finish").assertIsDisplayed()
    }

    private fun captureReview(name: String) {
        val output = java.io.File("../../build/review/routine-max-hand/$name").canonicalFile
        output.parentFile?.mkdirs()
        output.outputStream().use {
            compose.onRoot().captureToImage().asAndroidBitmap()
                .compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
        }
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
