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
import run.nuri.getagrip.ble.StreamStartCause
import run.nuri.getagrip.engine.*
import run.nuri.getagrip.runner.RunnerSession
import run.nuri.getagrip.store.DeviceStore
import run.nuri.getagrip.store.LocalDeviceStore
import run.nuri.getagrip.ui.gauge.GaugeScreen
import run.nuri.getagrip.ui.runner.RunnerLive
import run.nuri.getagrip.ui.theme.GetAGripTheme
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertTrue

/// **The session stage recomposes on the SNAPSHOT's cadence, never per sample.** The gauge
/// reports ~80 times a second; the panel's numbers, the trace and the time bar are leaves that
/// read the store themselves, and the wash and the panel's fill are read in the draw phase.
/// So a second of samples must leave the screen, its panel and its dock alone — counted with
/// the runtime's trace hook, like the target chip and the builder's rows.
@OptIn(InternalComposeTracingApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w393dp-h820dp-mdpi")
class StageRecompositionTests {
    @get:Rule val compose = createComposeRule()

    private val clock = FakeClock()
    private val scope = CoroutineScope(UnconfinedTestDispatcher(TestCoroutineScheduler()))
    private val client = RecordingProgressorClient()
    private val device = DeviceStore(client, scope = scope, clock = clock).also { client.connect() }
    private var micros = 0u
    private val bodies = mutableMapOf<String, AtomicInteger>()

    private fun sample(kg: Double) {
        clock.uptime += 0.0125
        clock.wall += 0.0125
        micros += 12_500u
        client.emit(ProgressorEvent.Sample(ForceSample(kg, micros)))
    }

    /// Count every body whose name ends with one of `names`, from now on.
    private fun countBodies(vararg names: String) {
        names.forEach { bodies[it] = AtomicInteger() }
        Composer.setTracer(object : CompositionTracer {
            override fun traceEventStart(key: Int, dirty1: Int, dirty2: Int, info: String) {
                val name = info.substringBefore(" (").substringAfterLast('.')
                bodies[name]?.incrementAndGet()
            }
            override fun traceEventEnd() = Unit
            override fun isTraceInProgress() = true
        })
    }

    @After fun close() {
        Composer.setTracer(object : CompositionTracer {
            override fun traceEventStart(key: Int, dirty1: Int, dirty2: Int, info: String) = Unit
            override fun traceEventEnd() = Unit
            override fun isTraceInProgress() = false
        })
        scope.cancel()
    }

    @Test fun aSecondOfSamplesMidPullLeavesTheRunnerStageAlone() {
        val session = RunnerSession(
            plan = SessionPlan(name = "Stage", sets = listOf(SetPlan(grip = GripSpec(edgeMM = 20), repsPerSide = 2)),
                handMode = HandMode.bothHands, holdSeconds = 30, restSeconds = 10, leadInSeconds = 0),
            routineName = "Stage", device = device, scope = scope, clock = clock,
        ).also { it.begin() }
        try {
            compose.setContent {
                CompositionLocalProvider(LocalDeviceStore provides device) {
                    GetAGripTheme { RunnerLive(session, timerOnly = false) }
                }
            }
            compose.runOnIdle { repeat(20) { sample(6.0); session.tickNow() } }
            compose.waitForIdle()
            assertTrue(session.snapshot.phase is RunnerPhase.Working, "${session.snapshot.phase}")
            countBodies("RunnerLive", "RunnerPanelHeader", "RunnerDock", "InstrumentPanel", "InstrumentDock",
                "LiveForceReadout")
            // Eighty readings of honest wobble: one second of a pull.
            for (kg in generateSequence(5.0) { if (it >= 7.5) 5.0 else it + 0.25 }.take(80)) {
                compose.runOnIdle { sample(kg); session.tickNow() }
                compose.waitForIdle()
            }
            // The countdown's whole second turns once in here, which republishes the snapshot:
            // at most a couple of bodies. Per sample would be eighty.
            for (name in listOf("RunnerLive", "RunnerPanelHeader", "RunnerDock", "InstrumentPanel", "InstrumentDock")) {
                val count = bodies.getValue(name).get()
                assertTrue(count <= 2, "$name recomposed for samples that changed nothing it shows: $count")
            }
            // The leaf that DOES show the kilograms is the one that moves.
            assertTrue(bodies.getValue("LiveForceReadout").get() > 10, "the live readout must follow the gauge")
        } finally {
            session.end()
        }
    }

    @Test fun aSecondOfSamplesLeavesTheGaugeStageAlone() {
        compose.setContent {
            CompositionLocalProvider(LocalDeviceStore provides device) {
                GetAGripTheme { GaugeScreen() }
            }
        }
        compose.runOnIdle {
            device.startStreaming(StreamStartCause.manualMeasurement)
            repeat(20) { sample(6.0) }
        }
        compose.waitForIdle()
        countBodies("GaugeScreen", "InstrumentPanel", "InstrumentDock", "GaugeHero")
        for (kg in generateSequence(5.0) { if (it >= 7.5) 5.0 else it + 0.25 }.take(80)) {
            compose.runOnIdle { sample(kg) }
            compose.waitForIdle()
        }
        for (name in listOf("GaugeScreen", "InstrumentPanel", "InstrumentDock")) {
            val count = bodies.getValue(name).get()
            assertTrue(count <= 1, "$name recomposed for samples that changed nothing it shows: $count")
        }
        assertTrue(bodies.getValue("GaugeHero").get() > 10, "the hero must follow the gauge")
    }
}
