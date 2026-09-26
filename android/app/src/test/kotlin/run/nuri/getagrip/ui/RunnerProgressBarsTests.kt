// SPDX-License-Identifier: MPL-2.0
// Original contributions Copyright 2026 Nuri Bruner.

package run.nuri.getagrip.ui

import androidx.compose.runtime.Composer
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.CompositionTracer
import androidx.compose.runtime.InternalComposeTracingApi
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import org.junit.After
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
import run.nuri.getagrip.ui.runner.RunnerLive
import run.nuri.getagrip.ui.theme.GetAGripTheme
import java.io.File
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertTrue

/// The runner's progress rows: one time bar and the routine's pills, identical in every phase, with
/// the counters row kept through a long rest. And the performance rule the iOS commits
/// measured: only the leaf reads the per-sample progress, never the runner screen.
@OptIn(InternalComposeTracingApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w393dp-h820dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class RunnerProgressBarsTests {
    @get:Rule val compose = createComposeRule()

    private val clock = FakeClock()
    private val scope = CoroutineScope(UnconfinedTestDispatcher(TestCoroutineScheduler()))
    private val client = RecordingProgressorClient()
    private val device = DeviceStore(client, scope = scope, clock = clock).also { client.connect() }
    private val session = RunnerSession(
        plan = SessionPlan(name = "Progress", sets = listOf(
            SetPlan(grip = GripSpec(edgeMM = 20), repsPerSide = 2),
            SetPlan(grip = GripSpec(edgeMM = 15), repsPerSide = 3)),
            handMode = HandMode.bothHands, holdSeconds = 7, restSeconds = 30, leadInSeconds = 0,
            waitForReleaseBeforeRest = false),
        routineName = "Progress", device = device, scope = scope, clock = clock,
    ).also { it.begin() }
    private var micros = 0u
    private val counts = mutableMapOf<String, AtomicInteger>()

    private fun sample(kg: Double) {
        clock.uptime += 0.0125
        micros += 12_500u
        client.emit(ProgressorEvent.Sample(ForceSample(kg, micros)))
        session.tickNow()
    }

    private fun show() {
        compose.setContent {
            CompositionLocalProvider(LocalDeviceStore provides device) {
                GetAGripTheme(darkTheme = false) { RunnerLive(session, timerOnly = false) }
            }
        }
    }

    private fun count(name: String) = counts.filterKeys { it.contains(name) }.values.sumOf { it.get() }

    @After fun close() {
        Composer.setTracer(object : CompositionTracer {
            override fun traceEventStart(key: Int, dirty1: Int, dirty2: Int, info: String) = Unit
            override fun traceEventEnd() = Unit
            override fun isTraceInProgress() = false
        })
        session.end(); scope.cancel()
    }

    @Test fun aHoldMovesTheTimeBarAndNeverTheRunnerScreen() {
        show()
        compose.runOnIdle { repeat(10) { sample(20.0) } }
        compose.waitForIdle()
        assertTrue(session.snapshot.phase is RunnerPhase.Working, "${session.snapshot.phase}")
        compose.onNodeWithTag("runner.timeBar").assertExists()
        compose.onNodeWithTag("runner.routinePills").assertExists()
        Composer.setTracer(object : CompositionTracer {
            override fun traceEventStart(key: Int, dirty1: Int, dirty2: Int, info: String) {
                val name = info.substringBefore(" (")
                counts.getOrPut(name) { AtomicInteger() }.incrementAndGet()
            }
            override fun traceEventEnd() = Unit
            override fun isTraceInProgress() = true
        })
        // One second of a hold at 80 Hz: repProgress moves on every reading.
        repeat(80) {
            compose.runOnIdle { sample(20.0) }
            if (it % 4 == 3) compose.waitForIdle()
        }
        compose.waitForIdle()
        assertTrue(session.snapshot.phase is RunnerPhase.Working, "${session.snapshot.phase}")
        val bar = count(".RunnerTimeBar")
        assertTrue(bar >= 10, "the time bar must follow the hold: $bar")
        // The whole-second numeral turns once in that second, which is the snapshot's own
        // republish; per reading would be eighty.
        for (still in listOf(".RunnerLive", ".RunnerPanelHeader", ".RoutinePills", ".Counters")) {
            val n = count(still)
            assertTrue(n <= 2, "$still recomposed $n times across 80 readings")
        }
    }

    @Test fun aLongRestKeepsTheRoutineAndTheCountersUnderTheSummary() {
        show()
        // A 7 s hold, then off the edge: the 30 s rest is long enough for the summary.
        compose.runOnIdle {
            repeat(600) { if (session.snapshot.phase !is RunnerPhase.Resting) sample(20.0) }
            sample(0.0)
        }
        compose.waitForIdle()
        assertTrue(session.snapshot.phase is RunnerPhase.Resting, "${session.snapshot.phase}")
        assertTrue(session.snapshot.showsRestFocus)
        assertTrue(session.phaseRemainingFraction != null, "A measured rest publishes its countdown for the bar")
        compose.onNodeWithTag("runner.restFocus").assertExists()
        compose.onNodeWithTag("runner.restFocus.setCount").assertDoesNotExist()
        compose.onNodeWithTag("runner.timeBar").assertIsDisplayed()
        compose.onNodeWithTag("runner.routinePills").assertIsDisplayed()
        compose.onNodeWithTag("runner-counters").assertIsDisplayed()
            .assert(SemanticsMatcher("says how much is left") {
                it.config.getOrNull(SemanticsProperties.ContentDescription)?.any { d: String -> d.endsWith("4 pulls left") } == true
            })
        capture("runner-rest.png")
    }

    private fun capture(name: String) {
        val output = File("../../build/review/runner/$name").canonicalFile
        output.parentFile?.mkdirs()
        output.outputStream().use {
            compose.onRoot().captureToImage().asAndroidBitmap()
                .compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
        }
    }
}
