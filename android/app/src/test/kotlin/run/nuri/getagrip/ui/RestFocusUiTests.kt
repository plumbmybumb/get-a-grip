// SPDX-License-Identifier: MPL-2.0
// Original contributions Copyright 2026 Nuri Bruner.

package run.nuri.getagrip.ui

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import run.nuri.getagrip.FakeClock
import run.nuri.getagrip.RecordingProgressorClient
import run.nuri.getagrip.engine.*
import run.nuri.getagrip.runner.RunnerSession
import run.nuri.getagrip.store.DeviceStore
import run.nuri.getagrip.store.LocalDeviceStore
import run.nuri.getagrip.ui.components.PalmHand
import run.nuri.getagrip.ui.l10n.tr
import run.nuri.getagrip.ui.runner.RunnerLive
import run.nuri.getagrip.ui.theme.GetAGripTheme
import run.nuri.getagrip.ui.theme.LocalGripPalette
import run.nuri.getagrip.ui.units.WeightUnit
import run.nuri.getagrip.ui.units.WeightUnits
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w393dp-h820dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class RestFocusUiTests {
    @get:Rule val compose = createComposeRule()
    private var originalUnits = WeightUnit.kg

    @Before fun usePredictableUnits() { originalUnits = WeightUnits.current; WeightUnits.current = WeightUnit.kg }
    @After fun restoreUnits() { WeightUnits.current = originalUnits }

    @Test fun longRestShowsTheNextHandAndFullGripAboveTheUnchangedLiveGraph() {
        Harness(target = true).use { h ->
            WeightUnits.current = WeightUnit.lb
            h.holdUntilRelease()
            showRunner(h)
            compose.onNodeWithTag("runner.hero").assertIsDisplayed()
            compose.onNodeWithTag("runner.restFocus").assertDoesNotExist()
            val baseline = geometry()
            compose.runOnIdle { h.sample(0.0) }
            assertText("hand", "Right hand next")
            val grip = text("grip").lowercase()
            assertTrue(grip.contains("20 mm") && grip.contains("half crimp"), grip)
            assertFalse(grip.contains("new grip"), "A hand swap is not a different grip")
            assertText("countdown", "10")
            assertTrue(text("target").contains("8.8–17.6 lb"), text("target"))
            assertText("setCount", "set 1 of 2", ignoreCase = true)
            assertText("pullCount", "pull 2 of 8", ignoreCase = true)
            compose.onNodeWithTag("runner.hero").assertDoesNotExist()
            assertAboveGraph()
            assertEquals(baseline, geometry())
            val traceSize = h.device.trace.size
            compose.runOnIdle { h.sample(4.0) }
            assertTrue(h.device.trace.size > traceSize, "Rest must keep receiving real graph samples")
            capture("android-rest-focus-right-lb.png")

            compose.runOnIdle { h.advanceToRestElapsed(8.0) }
            assertText("countdown", "2")
            assertEquals(baseline, geometry())
            assertAboveGraph()
            capture("android-rest-focus-final-two.png")
            compose.runOnIdle { h.advanceToRestElapsed(10.1) }
            compose.onNodeWithTag("runner.restFocus").assertDoesNotExist()
            compose.onNodeWithTag("runner.hero").assertIsDisplayed()
            assertEquals(baseline, geometry())
        }
    }

    @Test fun nineSecondRestKeepsTheCompactInterfaceAndReleaseNeverLooksAhead() {
        Harness(rest = 9).use { h ->
            repeat(6) { h.sample(6.0) }
            showRunner(h)
            compose.onNodeWithTag("runner.hero").assertIsDisplayed()
            compose.onNodeWithTag("runner.restFocus").assertDoesNotExist()
            val baseline = geometry()
            compose.runOnIdle { h.holdUntilRelease() }
            assertEquals(Side.left, h.session.snapshot.side)
            compose.onNodeWithText("LET GO").assertIsDisplayed()
            compose.onNodeWithTag("runner.restFocus").assertDoesNotExist()
            compose.runOnIdle { h.sample(0.0) }
            compose.onNodeWithTag("runner.restFocus").assertDoesNotExist()
            compose.onNodeWithTag("runner.hero").assertIsDisplayed()
            compose.onNodeWithTag("runner-rest-label", useUnmergedTree = true).assertTextEquals("REST")
            compose.onNodeWithText("RIGHT HAND NEXT").assertIsDisplayed()
            assertEquals(baseline, geometry())
            capture("android-nine-second-compact-rest.png")
        }
    }

    @Test fun changedGripKeepsItsOrangeOutlineThroughPausedFinalSecondsAndThenClears() {
        Harness(rest = 3, setBreak = 10, reps = 1).use { h ->
            h.finishFirstSet()
            showRunner(h, dark = true)
            compose.mainClock.advanceTimeBy(5_000)
            assertText("hand", "Left hand next")
            assertTrue(text("grip").contains("15 mm"))
            assertText("phase", "SET BREAK")
            assertText("setCount", "set 2 of 2", ignoreCase = true)
            assertText("pullCount", "pull 3 of 4", ignoreCase = true)
            val baseline = geometry()
            compose.runOnIdle { h.advanceToRestElapsed(8.0) }
            assertText("countdown", "2")
            compose.onNodeWithText("Pause").performClick()
            assertText("phase", "PAUSED")
            compose.runOnIdle { h.clock.uptime += 20; h.session.tickNow() }
            assertText("countdown", "2")
            assertAboveGraph()
            assertTrue(orangeEdgeFraction() > 0.08,
                "The actual orange graph stroke must remain after its entry animation and during pause")
            capture("android-grip-change-paused-final-two.png")
            compose.onNodeWithText("Resume").performClick()
            compose.runOnIdle { h.clock.uptime += 2.1; h.session.tickNow() }
            compose.mainClock.advanceTimeBy(1_000)
            compose.onNodeWithTag("runner.restFocus").assertDoesNotExist()
            assertTrue(orangeEdgeFraction() < 0.01, "The previous rest outline clears when the next pull is ready")
            assertEquals(baseline, geometry())
        }
    }

    @Test fun losingTheGaugeKeepsRestInformationAboveTheRecoveryWarning() {
        Harness().use { h ->
            h.holdUntilRelease(); h.sample(0.0)
            assertTrue(h.device.isSignalFresh)
            assertTrue(h.session.snapshot.hasSignal)
            showRunner(h)
            val baseline = geometry()
            compose.runOnIdle {
                h.client.disconnect()
                h.session.connectionChanged(false)
            }
            assertTrue(h.session.snapshot.hasSignal,
                "An earlier sample must not suppress the warning after the link disappears")
            assertText("countdown", "10")
            assertText("hand", "Right hand next")
            assertAboveGraph()
            val warning = compose.onNodeWithTag("runner.signalWarning").assertIsDisplayed()
                .fetchSemanticsNode().boundsInRoot
            assertTrue(warning.top >= baseline.graph.top && warning.bottom <= baseline.graph.bottom)
            compose.onNodeWithText("Connect").assertIsDisplayed()
            assertEquals(baseline.graph, geometry().graph)
            capture("android-rest-focus-disconnected.png")
        }
    }

    @Test fun timerOnlyLongRestKeepsItsExistingDialAndHandPrompt() {
        Harness(timerOnly = true).use { h ->
            h.clock.uptime += 1.1
            h.session.tickNow()
            assertTrue(h.session.snapshot.showsRestFocus,
                "The engine reports a long rest; only the measured presentation uses the new layout")
            showRunner(h)
            compose.onNodeWithTag("runner.restFocus").assertDoesNotExist()
            compose.onNodeWithText("RIGHT HAND NEXT", useUnmergedTree = true).assertIsDisplayed()
            compose.onNodeWithText("REST", useUnmergedTree = true).assertIsDisplayed()
            compose.onNodeWithText("Pause").assertIsDisplayed()
        }
    }

    @Test fun aConnectedButSilentGaugeShowsTheRecoveryWarningWithoutHidingRest() {
        Harness().use { h ->
            h.holdUntilRelease(); h.sample(0.0)
            assertTrue(h.device.isSignalFresh)
            showRunner(h)
            compose.onNodeWithTag("runner.signalWarning").assertDoesNotExist()
            compose.runOnIdle { h.ageSignal(2.0) }
            assertTrue(h.device.state.isConnected)
            assertTrue(h.session.snapshot.hasSignal)
            assertFalse(h.device.isSignalFresh)
            compose.onNodeWithTag("runner.signalWarning").assertIsDisplayed()
            assertText("countdown", "8")
            assertText("hand", "Right hand next")
            assertAboveGraph()
            capture("android-rest-focus-connected-silent.png")
        }
    }

    @Test
    @Config(qualifiers = "fr-w360dp-h740dp-mdpi")
    fun compactFrenchScreenFitsTheLongestRestAndThreeDigitTotalWithoutClipping() {
        val previous = L10n.lookup
        val context = ApplicationProvider.getApplicationContext<Context>()
        L10n.lookup = { context.tr(it) }
        try {
            Harness(rest = 3, setBreak = 900, reps = 1, nextReps = 60).use { h ->
                h.finishFirstSet()
                showRunner(h, fontScale = 1.3f)
                assertText("countdown", "900")
                assertTrue(text("pullCount").contains("122"), text("pullCount"))
                assertAboveGraph()
                capture("android-rest-focus-900-french-compact.png")
                for (id in listOf("hand", "grip", "phase", "countdown", "setCount", "pullCount")) assertTextFits(id)
                val countdown = bounds("countdown")
                for (id in listOf("setCount", "pullCount")) {
                    assertFalse(countdown.overlaps(bounds(id)), "$id must not collide with a 900-second timer")
                }
                compose.onNodeWithText(context.tr("Pause")).assertIsDisplayed()
                compose.onNodeWithContentDescription(context.tr("End session")).assertIsDisplayed()
                capture("android-rest-focus-900-french-compact.png")
            }
        } finally { L10n.lookup = previous }
    }

    @Test
    @Config(qualifiers = "fr-w360dp-h740dp-mdpi")
    fun frenchAccessibilityTextKeepsFullGripTargetAndCountdownReadableWithControlsReachable() =
        checkFrenchAccessibilityText(fontScale = 2f, suffix = "2")

    @Test
    @Config(qualifiers = "fr-w360dp-h740dp-mdpi")
    fun accessibilityLayoutBoundaryKeepsTheRestInformationAndControlsReadable() =
        checkFrenchAccessibilityText(fontScale = 1.5f, suffix = "1-5")

    private fun checkFrenchAccessibilityText(fontScale: Float, suffix: String) {
        val previous = L10n.lookup
        val context = ApplicationProvider.getApplicationContext<Context>()
        L10n.lookup = { context.tr(it) }
        try {
            val grip = GripSpec(edgeMM = 100,
                fingers = FingerSet.frontThree.union(FingerSet.thumb), position = GripPosition.fingerCurl)
            Harness(rest = 900, customSets = listOf(
                SetPlan(grip = grip, repsPerSide = 2, targetLoKg = 4.0, targetHiKg = 8.0),
            )).use { h ->
                h.holdUntilRelease(); h.sample(0.0)
                showRunner(h, fontScale = fontScale)
                assertText("countdown", "900")
                node("grip").assertTextEquals(grip.line)
                node("target").assertExists()
                capture("android-rest-focus-french-accessibility-$suffix-top.png")
                val graphTop = compose.onNodeWithTag("runner-plot").fetchSemanticsNode().positionInRoot.y
                val ids = listOf("hand", "grip", "target", "phase", "countdown", "setCount", "pullCount")
                for (id in ids) {
                    val semantic = node(id).fetchSemanticsNode()
                    assertTrue(semantic.positionInRoot.y + semantic.size.height <= graphTop,
                        "$id must remain above the graph at the largest text size")
                }
                for (id in ids) {
                    node(id).performScrollTo().assertIsDisplayed()
                    assertTextFits(id)
                }
                capture("android-rest-focus-french-accessibility-$suffix-counts.png")
                compose.onNodeWithText(context.tr("Pause")).performScrollTo().assertIsDisplayed()
                compose.onNodeWithContentDescription(context.tr("End session")).performScrollTo().assertIsDisplayed()
                capture("android-rest-focus-french-accessibility-$suffix-controls.png")
            }
        } finally { L10n.lookup = previous }
    }

    @Test
    @Config(qualifiers = "w360dp-h740dp-mdpi")
    fun maximumPlanPullTotalDoesNotCollideWithTheLongestCountdown() {
        val sets = List(50) { SetPlan(grip = GripSpec(edgeMM = 20), repsPerSide = 100) }
        Harness(rest = 900, customSets = sets).use { h ->
            h.holdUntilRelease(); h.sample(0.0)
            assertEquals(10_000, h.session.snapshot.plannedRepCount)
            showRunner(h, fontScale = 1.3f)
            assertText("countdown", "900")
            assertText("pullCount", "Pull 2 of 10000")
            assertAboveGraph()
            capture("android-rest-focus-10000-pulls.png")
            for (id in listOf("setCount", "pullCount", "countdown")) assertTextFits(id)
            for (id in listOf("setCount", "pullCount")) {
                assertFalse(bounds("countdown").overlaps(bounds(id)), "$id must not overlap the countdown")
            }
            capture("android-rest-focus-10000-pulls.png")
        }
    }

    private data class Geometry(val graph: Rect, val pause: Rect, val end: Rect)
    private fun geometry() = Geometry(
        compose.onNodeWithTag("runner-plot").fetchSemanticsNode().boundsInRoot,
        compose.onNode(hasText("Pause") and hasClickAction()).fetchSemanticsNode().boundsInRoot,
        compose.onNodeWithContentDescription("End session").fetchSemanticsNode().boundsInRoot,
    )
    private fun node(suffix: String) = compose.onNodeWithTag("runner.restFocus.$suffix", useUnmergedTree = true)
    private fun bounds(suffix: String) = node(suffix).fetchSemanticsNode().boundsInRoot
    private fun text(suffix: String): String {
        val config = node(suffix).fetchSemanticsNode().config
        return config.getOrNull(SemanticsProperties.Text)?.joinToString(" ") { it.text }
            ?: config.getOrNull(SemanticsProperties.ContentDescription)?.joinToString(" ").orEmpty()
    }
    private fun assertText(suffix: String, expected: String, ignoreCase: Boolean = false) {
        node(suffix).assertIsDisplayed()
        assertTrue(text(suffix).equals(expected, ignoreCase), "$suffix: expected $expected, got ${text(suffix)}")
    }
    private fun assertAboveGraph() {
        val graph = compose.onNodeWithTag("runner-plot").assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        assertTrue(graph.height > 160, "The graph must remain a useful live plot")
        for (id in listOf("hand", "grip", "countdown", "phase", "setCount", "pullCount")) {
            node(id).assertIsDisplayed()
            assertTrue(bounds(id).bottom <= graph.top, "$id must stay entirely above the graph")
        }
    }
    private fun assertTextFits(suffix: String) {
        val tag = "runner.restFocus.$suffix"
        val texts = compose.onAllNodes(
            (hasTestTag(tag) or hasAnyAncestor(hasTestTag(tag))) and
                SemanticsMatcher.keyIsDefined(SemanticsActions.GetTextLayoutResult),
            useUnmergedTree = true,
        )
        val count = texts.fetchSemanticsNodes().size
        assertTrue(count > 0, "$suffix needs readable text")
        repeat(count) { index ->
            val layouts = mutableListOf<TextLayoutResult>()
            texts[index].performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
            val result = layouts.single()
            val frame = texts[index].fetchSemanticsNode().boundsInRoot
            assertFalse(result.hasVisualOverflow,
                "$suffix must show its full localized text: text=${result.layoutInput.text}, " +
                    "size=${result.size}, frame=$frame, lines=${result.lineCount}, " +
                    "right=${result.getLineRight(0)}, widthOverflow=${result.didOverflowWidth}, " +
                    "heightOverflow=${result.didOverflowHeight}")
            // Native text can report didOverflowWidth after rounding an intrinsic
            // fractional width down to pixels (even the word “Set”). Check the
            // rendered line extents, with one physical pixel of rounding room,
            // while still refusing ellipsis, missing characters or clipped lines.
            for (line in 0 until result.lineCount) {
                assertFalse(result.isLineEllipsized(line), "$suffix must not abbreviate its text")
                assertTrue(result.getLineLeft(line) >= -1 && result.getLineRight(line) <= frame.width + 1,
                    "$suffix clips ${result.layoutInput.text}: line=$line, " +
                        "left=${result.getLineLeft(line)}, right=${result.getLineRight(line)}, frame=$frame")
            }
            assertEquals(result.layoutInput.text.length, result.getLineEnd(result.lineCount - 1),
                "$suffix must lay out every character")
            assertTrue(result.getLineBottom(result.lineCount - 1) <= frame.height + 1,
                "$suffix must not lose its last line")
        }
    }
    private fun orangeEdgeFraction(): Double {
        val image = compose.onNodeWithTag("runner-plot").captureToImage().asAndroidBitmap()
        var orange = 0
        var sampled = 0
        fun sample(x: Int, y: Int) {
            val color = image.getPixel(x, y)
            val red = android.graphics.Color.red(color)
            val green = android.graphics.Color.green(color)
            val blue = android.graphics.Color.blue(color)
            if (red > 160 && green in 65..215 && blue < 120 && red > green * 1.12) orange++
            sampled++
        }
        for (offset in 1..4) {
            for (x in 40 until image.width - 40 step 3) { sample(x, offset); sample(x, image.height - 1 - offset) }
            for (y in 40 until image.height - 40 step 3) { sample(offset, y); sample(image.width - 1 - offset, y) }
        }
        return orange.toDouble() / sampled.coerceAtLeast(1)
    }
    private fun showRunner(h: Harness, dark: Boolean = false, fontScale: Float = 1f) {
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale), LocalDeviceStore provides h.device) {
                GetAGripTheme(darkTheme = dark) {
                    Box(Modifier.fillMaxSize().background(LocalGripPalette.current.field)) {
                        RunnerLive(h.session, timerOnly = h.session.timerOnly)
                        val snapshot = h.session.snapshot
                        val resting = snapshot.phase is RunnerPhase.Resting ||
                            (snapshot.phase as? RunnerPhase.Paused)?.before is RunnerPhase.Resting
                        snapshot.grip?.let { grip ->
                            PalmHand(grip = grip, newGripID = snapshot.newGripID,
                                holdsGripCueForRest = snapshot.gripChangesNext, side = snapshot.side ?: Side.both,
                                isActive = !resting, restFocus = !h.session.timerOnly && snapshot.showsRestFocus,
                                modifier = Modifier.align(Alignment.TopCenter))
                        }
                    }
                }
            }
        }
    }
    private fun capture(name: String) {
        val output = File("../../build/review/rest-focus/$name").canonicalFile
        output.parentFile?.mkdirs()
        output.outputStream().use { compose.onRoot().captureToImage().asAndroidBitmap()
            .compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
    }
    @OptIn(ExperimentalCoroutinesApi::class)
    private class Harness(rest: Int = 10, setBreak: Int = 30, reps: Int = 2,
        nextReps: Int = reps, target: Boolean = false, timerOnly: Boolean = false,
        customSets: List<SetPlan>? = null) : AutoCloseable {
        val clock = FakeClock()
        private val scheduler = TestCoroutineScheduler()
        private val scope = CoroutineScope(UnconfinedTestDispatcher(scheduler))
        val client = RecordingProgressorClient()
        val device = DeviceStore(client, scope = scope, clock = clock).also { client.connect() }
        val session = RunnerSession(
            plan = SessionPlan(name = "Rest UI", sets = customSets ?: listOf(
                SetPlan(grip = GripSpec(edgeMM = 20), repsPerSide = reps,
                    targetLoKg = if (target) 4.0 else null, targetHiKg = if (target) 8.0 else null),
                SetPlan(grip = GripSpec(edgeMM = 15), repsPerSide = nextReps),
            ), handMode = HandMode.alternateEachRep, holdSeconds = 1, restSeconds = rest,
                setBreakSeconds = setBreak, leadInSeconds = 0, waitForReleaseBeforeRest = true),
            routineName = "Rest UI", device = device, scope = scope, clock = clock, timerOnly = timerOnly,
        ).also { it.begin() }
        private var micros = 0u
        private var restStartedAt = 0.0
        fun sample(kg: Double) {
            val wasResting = session.snapshot.phase is RunnerPhase.Resting
            clock.uptime += 0.1
            micros += 100_000u
            client.emit(ProgressorEvent.Sample(ForceSample(kg, micros)))
            session.tickNow()
            if (!wasResting && session.snapshot.phase is RunnerPhase.Resting) restStartedAt = clock.uptime
        }
        fun holdUntilRelease() {
            repeat(16) { if (session.snapshot.phase !is RunnerPhase.Releasing) sample(6.0) }
            assertTrue(session.snapshot.phase is RunnerPhase.Releasing, "Expected release, got ${session.snapshot.phase}")
        }
        fun advanceToRestElapsed(seconds: Double) { clock.uptime = restStartedAt + seconds; session.tickNow() }
        fun ageSignal(seconds: Double) {
            clock.wall += seconds
            clock.uptime += seconds
            scheduler.advanceTimeBy(501)
            scheduler.runCurrent()
            session.tickNow()
        }
        fun finishFirstSet() {
            holdUntilRelease(); sample(0.0)
            advanceToRestElapsed(session.plan.restSeconds + 0.1)
            holdUntilRelease(); sample(0.0)
            assertTrue(session.snapshot.isSetBreak)
        }
        override fun close() { session.end(); scope.cancel() }
    }
}
