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
import kotlinx.coroutines.test.StandardTestDispatcher
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
import run.nuri.getagrip.engine.CriticalForceHands
import run.nuri.getagrip.engine.CriticalForceTest
import run.nuri.getagrip.engine.GripSpec
import run.nuri.getagrip.engine.Side
import run.nuri.getagrip.runner.CueSink
import run.nuri.getagrip.store.DeviceStore
import run.nuri.getagrip.store.LocalDeviceStore
import run.nuri.getagrip.ui.criticalforce.CriticalForceSession
import run.nuri.getagrip.ui.criticalforce.CriticalForceTestRequest
import run.nuri.getagrip.ui.criticalforce.TestingScreen
import run.nuri.getagrip.ui.theme.GetAGripTheme
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/// The live bar moves several times a second while a pull runs. Counted with the runtime's
/// trace hook, like the runner's target chip: it must recompose the PILL, never the whole
/// test screen (iOS d29ee1a measured the screen rebuilding with it).
@OptIn(InternalComposeTracingApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w393dp-h820dp-mdpi")
class CriticalForceRecompositionTests {
    @get:Rule val compose = createComposeRule()

    private val screens = AtomicInteger()
    private val pills = AtomicInteger()
    private val clock = FakeClock()
    private val scope = CoroutineScope(UnconfinedTestDispatcher(TestCoroutineScheduler()))
    private val client = RecordingProgressorClient()
    private val device = DeviceStore(client, scope = scope, clock = clock).also { client.connect() }
    private val request = CriticalForceTestRequest(
        grip = GripSpec(), hands = CriticalForceHands.Single(Side.left), scope = scope,
        newSession = { CriticalForceSession(CoroutineScope(StandardTestDispatcher()), CueSink { }, clock) },
    ).also { it.start(device) }

    /// A quarter second of readings at 80 Hz, then the 20 Hz tick.
    private fun pull(kg: Double) {
        repeat(20) {
            clock.wall += 0.0125
            device.onTracePoint?.invoke(DeviceStore.TracePoint(kg, clock.wall))
        }
        request.session.tick()
    }

    @After fun close() {
        Composer.setTracer(object : CompositionTracer {
            override fun traceEventStart(key: Int, dirty1: Int, dirty2: Int, info: String) = Unit
            override fun traceEventEnd() = Unit
            override fun isTraceInProgress() = false
        })
        request.teardown()
        scope.cancel()
    }

    @Test fun aMovingLiveBarRecomposesOnlyThePill() {
        compose.setContent {
            CompositionLocalProvider(LocalDeviceStore provides device) {
                GetAGripTheme { TestingScreen(request) }
            }
        }
        // The first pull arms the test: the phase change recomposes the screen (its tint).
        compose.runOnIdle {
            device.onTracePoint?.invoke(DeviceStore.TracePoint(30.0, clock.wall))
            pull(30.0)
        }
        compose.waitForIdle()
        assertEquals(CriticalForceTest.Phase.Pulling(0), request.session.phase)
        Composer.setTracer(object : CompositionTracer {
            override fun traceEventStart(key: Int, dirty1: Int, dirty2: Int, info: String) {
                val name = info.substringBefore(" (")
                if (name.endsWith(".TestingScreen")) screens.incrementAndGet()
                if (name.endsWith(".LivePlateau")) pills.incrementAndGet()
            }
            override fun traceEventEnd() = Unit
            override fun isTraceInProgress() = true
        })
        // A pull that builds: the running average rises every tick, inside one phase.
        for (step in 1..16) {
            compose.runOnIdle { pull(30.0 + step * 2) }
            compose.waitForIdle()
        }
        assertEquals(CriticalForceTest.Phase.Pulling(0), request.session.phase)
        assertTrue(pills.get() >= 8, "the pill must follow its live bar: ${pills.get()}")
        assertEquals(0, screens.get(), "the live bar recomposed the whole test screen")
    }
}
