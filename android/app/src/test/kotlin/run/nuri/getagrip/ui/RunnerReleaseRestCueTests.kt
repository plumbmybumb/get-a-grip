// SPDX-License-Identifier: MPL-2.0
// Original contributions Copyright 2026 Nuri Bruner.
package run.nuri.getagrip.ui

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.cancel
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import run.nuri.getagrip.FakeClock
import run.nuri.getagrip.RecordingProgressorClient
import run.nuri.getagrip.inertScope
import run.nuri.getagrip.engine.*
import run.nuri.getagrip.runner.RunnerSession
import run.nuri.getagrip.runner.RunnerSnapshot
import run.nuri.getagrip.store.DeviceStore
import run.nuri.getagrip.store.LocalDeviceStore
import run.nuri.getagrip.ui.components.PalmHand
import run.nuri.getagrip.ui.l10n.tr
import run.nuri.getagrip.ui.runner.*
import run.nuri.getagrip.ui.theme.GetAGripTheme
import run.nuri.getagrip.ui.theme.LocalGripPalette
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w393dp-h820dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class RunnerReleaseRestCueTests {
    @get:Rule val compose = createComposeRule()

    @Test fun perimeterStaysSteadyUntilPhaseChangesWithoutMovingOrBlockingControls() {
        var phase: RunnerPhase by mutableStateOf(RunnerPhase.Releasing(0))
        var clicked = 0
        var orange = 0
        compose.mainClock.autoAdvance = false
        compose.setContent { GetAGripTheme {
            orange = LocalGripPalette.current.armed.toArgb()
            Box(Modifier.size(320.dp, 640.dp).testTag("screen")
                .background(LocalGripPalette.current.field)) {
                Column {
                    Text("12.3 kg", Modifier.testTag("metrics"))
                    Box(Modifier.height(220.dp).testTag("graph"))
                    Text("Pause", Modifier.clickable { clicked++ }.testTag("pause"))
                }
                RunnerScreenBorder(runnerBorderCue(RunnerSnapshot(phase = phase), false, false), Modifier.matchParentSize())
            }
        } }
        compose.mainClock.advanceTimeByFrame()
        val metrics = compose.onNodeWithTag("metrics").fetchSemanticsNode().boundsInRoot
        val graph = compose.onNodeWithTag("graph").fetchSemanticsNode().boundsInRoot
        fun pixels() = compose.onNodeWithTag("screen").captureToImage().asAndroidBitmap().let {
            listOf(it.getPixel(it.width / 2, 2), it.getPixel(it.width / 2, it.height - 3),
                it.getPixel(2, it.height / 2), it.getPixel(it.width - 3, it.height / 2))
        }
        assertEquals(List(4) { orange }, pixels(), "All four physical edges carry the cue")
        compose.mainClock.advanceTimeBy(30_000)
        assertEquals(List(4) { orange }, pixels(), "Waiting never times out or flashes the cue")
        compose.onNodeWithTag("pause").performTouchInput { click() }
        compose.runOnIdle { assertEquals(1, clicked) }
        for (next in listOf(RunnerPhase.Paused(RunnerPhase.Releasing(0)),
            RunnerPhase.Resting(0), RunnerPhase.Armed(1), RunnerPhase.Finished)) {
            compose.runOnIdle { phase = next; Snapshot.sendApplyNotifications() }
            compose.mainClock.advanceTimeByFrame()
            compose.onNodeWithTag("runner-release-border").assertDoesNotExist()
            assertEquals(metrics, compose.onNodeWithTag("metrics").fetchSemanticsNode().boundsInRoot)
            assertEquals(graph, compose.onNodeWithTag("graph").fetchSemanticsNode().boundsInRoot)
        }
    }

    @Test fun restUsesNextDisplaySlotAndNeverLabelsTheCurrentReleaseHandAsNext() {
        Harness().use { h ->
            h.holdUntilRelease()
            assertEquals(Side.left, h.session.snapshot.side)
            assertNull(nextHandText(h.session.snapshot))
            h.sample(0.0)
            assertTrue(h.session.snapshot.phase is RunnerPhase.Resting)
            assertEquals(Side.right, h.session.snapshot.side)
            assertEquals("RIGHT HAND NEXT", nextHandText(h.session.snapshot))
            assertEquals("REST", restPhaseText(h.session.snapshot))
            h.session.send(RunnerEvent.Pause)
            assertEquals("PAUSED", promptText(h.session.snapshot, false, true))
            assertEquals("RIGHT HAND NEXT", nextHandText(h.session.snapshot))
            h.session.send(RunnerEvent.Resume)
            h.session.send(RunnerEvent.SkipSet)
            assertEquals(Side.left, h.session.snapshot.side, "The next set restarts the actual plan order")
        }
    }

    @Test fun noRestAndUnspecifiedHandDoNotInventAnUpcomingHand() {
        Harness(rest = 0).use { h ->
            repeat(20) { h.sample(12.0) }
            assertTrue(h.session.snapshot.phase !is RunnerPhase.Releasing)
            assertTrue(h.session.snapshot.phase !is RunnerPhase.Resting)
            assertNull(nextHandText(h.session.snapshot))
        }
        val rest = RunnerSnapshot(phase = RunnerPhase.Resting(0))
        assertNull(nextHandText(rest))
        assertEquals("REST", promptText(rest, false, true))
        assertEquals("BOTH HANDS NEXT", nextHandText(rest.copy(side = Side.both)))
        assertEquals("LEFT HAND NEXT", nextHandText(rest.copy(side = Side.left, isSetBreak = true)))
        assertEquals("SET BREAK", restPhaseText(rest.copy(side = Side.left, isSetBreak = true)))
    }

    @Test fun releaseAndRestScreensPreserveMetricsAndShowTheNextHand() {
        Harness().use { h ->
            h.holdUntilRelease()
            showRunner(h)
            compose.onNodeWithText("LET GO").assertIsDisplayed()
            compose.onNodeWithTag("runner-release-border").assertIsDisplayed()
            val plot = compose.onNodeWithTag("runner-plot").fetchSemanticsNode().boundsInRoot
            capture("android-release-light.png")
            compose.runOnIdle { h.sample(0.0) }
            compose.onNodeWithTag("runner-release-border").assertDoesNotExist()
            compose.onNodeWithText("RIGHT HAND NEXT").assertIsDisplayed()
            compose.onNodeWithContentDescription("REST · RIGHT HAND NEXT").assertExists()
            assertEquals(plot, compose.onNodeWithTag("runner-plot").fetchSemanticsNode().boundsInRoot)
            capture("android-rest-right-light.png")
            compose.runOnIdle { h.session.send(RunnerEvent.Pause) }
            compose.onNodeWithText("PAUSED").assertIsDisplayed()
            compose.onNodeWithContentDescription("PAUSED · RIGHT HAND NEXT").assertExists()
            assertEquals(plot, compose.onNodeWithTag("runner-plot").fetchSemanticsNode().boundsInRoot)
            capture("android-rest-paused-light.png")
        }
    }

    @Test fun darkReleaseAndSetBreakUseTheSamePerimeterAndHandCue() {
        Harness(reps = 1).use { h ->
            h.holdUntilRelease()
            h.sample(0.0)
            h.clock.uptime += 21.0
            h.session.tickNow()
            h.holdUntilRelease()
            showRunner(h, dark = true)
            capture("android-release-dark.png")
            compose.runOnIdle { h.sample(0.0) }
            assertTrue(h.session.snapshot.isSetBreak)
            compose.onNodeWithText("LEFT HAND NEXT").assertIsDisplayed()
            compose.onNodeWithContentDescription("SET BREAK · LEFT HAND NEXT").assertExists()
            capture("android-rest-left-dark.png")
        }
    }

    @Test fun timerOnlyRestShowsPhaseAndHandWithoutNeedingARelease() {
        Harness(timerOnly = true, mode = HandMode.bothHands).use { h ->
            h.clock.uptime += 1.2
            h.session.tickNow()
            showRunner(h, timerOnly = true)
            compose.onNodeWithText("REST", useUnmergedTree = true).assertIsDisplayed()
            compose.onNodeWithText("BOTH HANDS NEXT", useUnmergedTree = true).assertIsDisplayed()
            compose.onNodeWithTag("runner-release-border").assertDoesNotExist()
            capture("android-timer-rest.png")
        }
    }

    @Test fun restLabelSitsBetweenTheCountersAndNeverCrowdsTheNumerals() {
        Harness().use { h ->
            h.holdUntilRelease()
            showRunner(h)
            val before = compose.onNodeWithTag("runner-plot").fetchSemanticsNode().boundsInRoot
            compose.onNodeWithTag("runner-rest-label", useUnmergedTree = true).assertDoesNotExist()
            compose.runOnIdle { h.sample(0.0) }
            val label = compose.onNodeWithTag("runner-rest-label", useUnmergedTree = true)
            label.assertTextEquals("REST").assertIsDisplayed()
            val rest = label.fetchSemanticsNode().boundsInRoot
            val set = compose.onNodeWithTag("runner-set-count", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
            val pull = compose.onNodeWithTag("runner-pull-count", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
            val counters = compose.onNodeWithTag("runner-counters").fetchSemanticsNode().boundsInRoot
            assertTrue(rest.left > set.right && rest.right < pull.left)
            assertEquals(counters.center.x, rest.center.x, 1f)
            assertTrue(rest.top >= counters.top && rest.bottom <= counters.bottom)
            val layouts = mutableListOf<androidx.compose.ui.text.TextLayoutResult>()
            label.performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.GetTextLayoutResult) { it(layouts) }
            assertTrue(layouts.single().layoutInput.style.fontSize.value >= 16f,
                "Rest must be legible at a distance, not the former 9–11sp annotation")
            assertEquals(before, compose.onNodeWithTag("runner-plot").fetchSemanticsNode().boundsInRoot)
        }
    }

    @Test
    @Config(qualifiers = "w360dp-h740dp-mdpi")
    fun smallScreenLargerTextKeepsNextHandAndControlsVisible() {
        Harness().use { h ->
            h.holdUntilRelease()
            h.sample(0.0)
            showRunner(h, fontScale = 1.3f)
            compose.onNodeWithText("RIGHT HAND NEXT").assertIsDisplayed()
            capture("android-rest-small-large-text.png")
            compose.onNodeWithText("Pause").assertIsDisplayed()
        }
    }

    @Test
    @Config(qualifiers = "fr-w360dp-h740dp-mdpi")
    fun frenchNextHandAndPausedDetailFitTheExistingRunnerSpace() {
        val previousLookup = L10n.lookup
        val context = ApplicationProvider.getApplicationContext<Context>()
        L10n.lookup = { context.tr(it) }
        try {
            Harness().use { h ->
                h.holdUntilRelease()
                h.sample(0.0)
                showRunner(h, fontScale = 1.3f)
                compose.onNodeWithText("MAIN DROITE ENSUITE").assertIsDisplayed()
                capture("android-rest-small-french.png")
                val plot = compose.onNodeWithTag("runner-plot").fetchSemanticsNode().boundsInRoot
                compose.runOnIdle { h.session.send(RunnerEvent.Pause) }
                capture("android-rest-paused-small-french.png")
                assertEquals(plot, compose.onNodeWithTag("runner-plot").fetchSemanticsNode().boundsInRoot)
            }
        } finally { L10n.lookup = previousLookup }
    }

    @Test
    @Config(qualifiers = "fr-w360dp-h740dp-mdpi")
    fun frenchRunnerControlsHaveInsetsAndNeverTruncateTheAction() {
        val previousLookup = L10n.lookup
        val context = ApplicationProvider.getApplicationContext<Context>()
        L10n.lookup = { context.tr(it) }
        try {
            Harness().use { h ->
                h.holdUntilRelease()
                h.sample(0.0)
                showRunner(h)
                for (key in listOf("Skip pull", "Skip set")) assertButtonLabelFits(context.tr(key))
                compose.onNodeWithText(context.tr("Hold to end"), useUnmergedTree = true).assertIsDisplayed()
                capture("android-runner-french-margins.png")
            }
        } finally { L10n.lookup = previousLookup }
    }

    @Test
    @Config(qualifiers = "fr-w360dp-h740dp-mdpi")
    fun twiceTextSizeKeepsEveryRunnerControlReachable() {
        val previousLookup = L10n.lookup
        val context = ApplicationProvider.getApplicationContext<Context>()
        L10n.lookup = { context.tr(it) }
        try {
            Harness().use { h ->
                h.holdUntilRelease()
                h.sample(0.0)
                showRunner(h, fontScale = 2f)
                capture("android-runner-french-large-top.png")
                val layouts = mutableListOf<androidx.compose.ui.text.TextLayoutResult>()
                val prompt = compose.onNodeWithText(context.tr("RIGHT HAND NEXT"), useUnmergedTree = true)
                prompt.performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.GetTextLayoutResult) { it(layouts) }
                val text = layouts.single()
                assertEquals(context.tr("RIGHT HAND NEXT").length, text.getLineEnd(text.lineCount - 1))
                assertTrue(text.getLineBottom(text.lineCount - 1) <= prompt.fetchSemanticsNode().boundsInRoot.height + 1f)
                for (key in listOf("Pause", "Skip pull", "Skip set")) {
                    compose.onNodeWithText(context.tr(key)).performScrollTo()
                    assertButtonLabelFits(context.tr(key))
                }
                compose.onNodeWithContentDescription(context.tr("End session")).performScrollTo()
                compose.onNodeWithText(context.tr("Hold to end"), useUnmergedTree = true).assertIsDisplayed()
                capture("android-runner-french-margins-2x.png")
            }
        } finally { L10n.lookup = previousLookup }
    }

    @Test
    @Config(qualifiers = "w840dp-h980dp-mdpi")
    fun tabletRunnerKeepsTheGraphAndControlsCenteredWithinTheReadableWidth() {
        Harness().use { h ->
            h.holdUntilRelease()
            showRunner(h)
            val plot = compose.onNodeWithTag("runner-plot").fetchSemanticsNode().boundsInRoot
            val viewport = compose.onRoot().fetchSemanticsNode().boundsInRoot
            assertEquals(440f, plot.width, 1f)
            assertEquals(viewport.center.x, plot.center.x, 1f)
            val pause = compose.onNode(hasText("Pause") and hasClickAction()).fetchSemanticsNode().boundsInRoot
            val hold = compose.onNodeWithContentDescription("End session").fetchSemanticsNode().boundsInRoot
            assertEquals(plot.left, pause.left, 1f)
            assertEquals(plot.right, hold.right, 1f)
            capture("android-tablet-runner-centered-margins.png")
        }
    }

    @Test fun livePullBorderTurnsRedForDropoutAndClearsOnPauseWithoutMovingTheGraph() {
        Harness().use { h ->
            repeat(6) { h.sample(12.0) }
            assertTrue(h.session.snapshot.phase is RunnerPhase.Working)
            showRunner(h)
            compose.onNodeWithTag("runner-pull-border").assertIsDisplayed()
            val plot = compose.onNodeWithTag("runner-plot").fetchSemanticsNode().boundsInRoot
            capture("android-pulling-blue.png")
            compose.runOnIdle { h.sample(0.0) }
            compose.onNodeWithTag("runner-pull-border").assertDoesNotExist()
            compose.onNodeWithTag("runner-warning-border").assertIsDisplayed()
            compose.onNodeWithText("RE-GRIP").assertIsDisplayed()
            capture("android-pulling-warning-red.png")
            compose.runOnIdle { h.sample(12.0) }
            compose.onNodeWithTag("runner-pull-border").assertIsDisplayed()
            compose.runOnIdle { h.session.send(RunnerEvent.Pause) }
            compose.onNodeWithTag("runner-pull-border").assertDoesNotExist()
            compose.onNodeWithTag("runner-warning-border").assertDoesNotExist()
            assertEquals(plot, compose.onNodeWithTag("runner-plot").fetchSemanticsNode().boundsInRoot)
        }
    }

    @Test fun timerOnlyPullIsBlueAndRestUsesTheQuietGrayBorder() {
        Harness(timerOnly = true).use { h ->
            assertTrue(h.session.snapshot.phase is RunnerPhase.Working)
            showRunner(h, timerOnly = true)
            compose.onNodeWithTag("runner-pull-border").assertIsDisplayed()
            capture("android-timer-pulling-blue.png")
            compose.runOnIdle { h.session.send(RunnerEvent.SkipRep) }
            compose.onNodeWithTag("runner-pull-border").assertDoesNotExist()
            compose.onNodeWithTag("runner-warning-border").assertDoesNotExist()
            compose.onNodeWithTag("runner-release-border").assertDoesNotExist()
            compose.onNodeWithTag("runner-rest-border").assertIsDisplayed()
        }
    }

    private fun assertButtonLabelFits(label: String) {
        val outer = compose.onNode(hasText(label) and hasClickAction()).fetchSemanticsNode().boundsInRoot
        val text = compose.onNodeWithText(label, useUnmergedTree = true)
        val inner = text.fetchSemanticsNode().boundsInRoot
        val layouts = mutableListOf<androidx.compose.ui.text.TextLayoutResult>()
        text.performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.GetTextLayoutResult) { it(layouts) }
        assertTrue(layouts.isNotEmpty())
        val layout = layouts.single()
        assertEquals(label.length, layout.getLineEnd(layout.lineCount - 1))
        for (line in 0 until layout.lineCount) {
            assertTrue(layout.getLineRight(line) - layout.getLineLeft(line) <= inner.width + 1f,
                "$label has no clipped glyphs")
            assertTrue(layout.getLineBottom(line) <= inner.height + 1f, "$label has no clipped lines")
        }
        assertTrue(inner.left - outer.left >= 15.5f && outer.right - inner.right >= 15.5f,
            "$label has at least 16dp horizontal insets")
        assertTrue(inner.top - outer.top >= 9.5f && outer.bottom - inner.bottom >= 9.5f,
            "$label has at least 10dp vertical insets")
    }

    private fun showRunner(h: Harness, dark: Boolean = false, timerOnly: Boolean = false, fontScale: Float = 1f) {
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale),
                LocalDeviceStore provides h.device) {
                GetAGripTheme(darkTheme = dark) {
                    Box(Modifier.fillMaxSize().background(LocalGripPalette.current.field)) {
                        RunnerLive(h.session, timerOnly)
                        h.session.snapshot.grip?.let {
                            PalmHand(grip = it, side = h.session.snapshot.side ?: Side.both,
                                isActive = h.session.snapshot.phase !is RunnerPhase.Resting,
                                modifier = Modifier.align(Alignment.TopCenter))
                        }
                        RunnerScreenBorder(runnerBorderCue(h.session.snapshot, timerOnly,
                            h.device.state.isConnected && h.device.isStreaming && h.device.isSignalFresh), Modifier.matchParentSize())
                    }
                }
            }
        }
    }

    private fun capture(name: String) {
        val output = File("../../build/review/release-rest-cues/$name").canonicalFile
        output.parentFile?.mkdirs()
        output.outputStream().use {
            compose.onRoot().captureToImage().asAndroidBitmap()
                .compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
        }
    }

    // These tests cover the compact rest interface. Long-rest presentation has its
    // own geometry, phase and accessibility coverage in RestFocusUiTests.
    private class Harness(rest: Int = 9, reps: Int = 2, mode: HandMode = HandMode.alternateEachRep,
        timerOnly: Boolean = false) : AutoCloseable {
        val clock = FakeClock()
        val scope = inertScope()
        val client = RecordingProgressorClient()
        val device = DeviceStore(client, scope = scope, clock = clock).also { client.connect() }
        val session = RunnerSession(
            SessionPlan(name = "Release review", sets = listOf(
                SetPlan(grip = GripSpec(), repsPerSide = reps), SetPlan(grip = GripSpec(edgeMM = 15), repsPerSide = reps)),
                holdSeconds = 1, restSeconds = rest, setBreakSeconds = 9, leadInSeconds = 0, handMode = mode),
            "Release review", device, scope = scope, clock = clock, timerOnly = timerOnly,
        ).also { it.begin() }
        var micros = 0u
        fun sample(kg: Double) {
            clock.uptime += 0.1
            micros += 100_000u
            client.emit(ProgressorEvent.Sample(ForceSample(kg, micros)))
            session.tickNow()
        }
        fun holdUntilRelease() {
            repeat(15) { sample(12.0) }
            assertTrue(session.snapshot.phase is RunnerPhase.Releasing,
                "Expected the real release gate, got ${session.snapshot.phase}")
        }
        override fun close() { session.end(); scope.cancel() }
    }
}
