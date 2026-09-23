// SPDX-License-Identifier: MPL-2.0
// Original contributions Copyright 2026 Nuri Bruner.

package run.nuri.getagrip.ui

import androidx.compose.runtime.Composer
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.CompositionTracer
import androidx.compose.runtime.InternalComposeTracingApi
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
import run.nuri.getagrip.FakeClock
import run.nuri.getagrip.RecordingProgressorClient
import run.nuri.getagrip.engine.*
import run.nuri.getagrip.runner.RunnerSession
import run.nuri.getagrip.store.DeviceStore
import run.nuri.getagrip.store.LocalDeviceStore
import run.nuri.getagrip.ui.runner.RunnerLive
import run.nuri.getagrip.ui.theme.GetAGripTheme
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertTrue

/// The target chip changes colour at the band's EDGES, and the gauge reports ~80 times a
/// second between them. Counted with the runtime's trace hook, like the builder's rows.
@OptIn(InternalComposeTracingApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w393dp-h820dp-mdpi")
class TargetChipRecompositionTests {
    @get:Rule val compose = createComposeRule()

    private val chipBodies = AtomicInteger()
    private val clock = FakeClock()
    private val scope = CoroutineScope(UnconfinedTestDispatcher(TestCoroutineScheduler()))
    private val client = RecordingProgressorClient()
    private val device = DeviceStore(client, scope = scope, clock = clock).also { client.connect() }
    private val session = RunnerSession(
        plan = SessionPlan(name = "Band", sets = listOf(
            SetPlan(grip = GripSpec(edgeMM = 20), repsPerSide = 2, targetLoKg = 4.0, targetHiKg = 8.0)),
            handMode = HandMode.bothHands, holdSeconds = 30, restSeconds = 10, leadInSeconds = 0),
        routineName = "Band", device = device, scope = scope, clock = clock,
    ).also { it.begin() }
    private var micros = 0u

    private fun sample(kg: Double) {
        clock.uptime += 0.0125
        micros += 12_500u
        client.emit(ProgressorEvent.Sample(ForceSample(kg, micros)))
        session.tickNow()
    }

    @After fun close() {
        Composer.setTracer(object : CompositionTracer {
            override fun traceEventStart(key: Int, dirty1: Int, dirty2: Int, info: String) = Unit
            override fun traceEventEnd() = Unit
            override fun isTraceInProgress() = false
        })
        session.end(); scope.cancel()
    }

    @Test fun loadMovingInsideTheBandDoesNotRedrawTheChip() {
        compose.setContent {
            CompositionLocalProvider(LocalDeviceStore provides device) {
                GetAGripTheme { RunnerLive(session, timerOnly = false) }
            }
        }
        // Onto the edge and into the band: the clock is running.
        compose.runOnIdle { repeat(20) { sample(6.0) } }
        compose.waitForIdle()
        assertTrue(session.snapshot.phase is RunnerPhase.Working, "${session.snapshot.phase}")
        Composer.setTracer(object : CompositionTracer {
            override fun traceEventStart(key: Int, dirty1: Int, dirty2: Int, info: String) {
                if (info.substringBefore(" (").endsWith(".LiveTargetChip")) chipBodies.incrementAndGet()
            }
            override fun traceEventEnd() = Unit
            override fun isTraceInProgress() = true
        })
        // A second of honest wobble, every reading inside 4–8 kg.
        for (kg in generateSequence(5.0) { if (it >= 7.5) 5.0 else it + 0.25 }.take(80)) {
            compose.runOnIdle { sample(kg) }
            compose.waitForIdle()
        }
        // Eighty readings. The row around the chip still republishes when the countdown's
        // whole second turns, which may pass it a fresh band — that is at most once here;
        // per-reading would be eighty (it was).
        val inside = chipBodies.get()
        assertTrue(inside <= 2, "the chip redrew for readings that changed nothing it shows: $inside")
        compose.runOnIdle { sample(9.5) }
        compose.waitForIdle()
        assertTrue(chipBodies.get() > inside, "crossing the ceiling must redraw it")
    }
}
