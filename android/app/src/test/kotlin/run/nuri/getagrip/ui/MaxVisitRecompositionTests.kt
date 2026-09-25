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
import run.nuri.getagrip.engine.GripSpec
import run.nuri.getagrip.store.DeviceStore
import run.nuri.getagrip.store.LocalDeviceStore
import run.nuri.getagrip.ui.maxes.MaxMeasureScreen
import run.nuri.getagrip.ui.theme.GetAGripTheme
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/// A climbing pull changes the hero's number on nearly every sample. Only the hero may follow
/// it: the visit, its panel, its hand wells and its dock stay put (iOS keeps the per-sample
/// peak on its own observable for the same reason; see `LiveMaxSession`).
@OptIn(InternalComposeTracingApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w393dp-h820dp-mdpi")
class MaxVisitRecompositionTests {
    @get:Rule val compose = createComposeRule()

    private val counts = mutableMapOf<String, AtomicInteger>()
    private val clock = FakeClock()
    private val scope = CoroutineScope(UnconfinedTestDispatcher(TestCoroutineScheduler()))
    private val client = RecordingProgressorClient()
    private val device = DeviceStore(client, scope = scope, clock = clock).also { client.connect() }
    private var t = 0.0

    private fun sample(kg: Double) {
        t += 0.0125
        device.onTracePoint?.invoke(DeviceStore.TracePoint(kg, t))
    }

    private fun count(name: String) = counts[name]?.get() ?: 0

    @After fun close() {
        Composer.setTracer(object : CompositionTracer {
            override fun traceEventStart(key: Int, dirty1: Int, dirty2: Int, info: String) = Unit
            override fun traceEventEnd() = Unit
            override fun isTraceInProgress() = false
        })
        scope.cancel()
    }

    @Test fun aClimbingPullRecomposesOnlyTheHero() {
        compose.setContent {
            CompositionLocalProvider(LocalDeviceStore provides device) {
                GetAGripTheme { MaxMeasureScreen(GripSpec(), onSave = { null }, onClose = {}, savedMax = { null }) }
            }
        }
        // The first reading over the threshold opens the pull: the wash and the hand wells
        // follow that once (the wells lock while a pull is under way).
        compose.runOnIdle { sample(10.0) }
        compose.waitForIdle()
        Composer.setTracer(object : CompositionTracer {
            override fun traceEventStart(key: Int, dirty1: Int, dirty2: Int, info: String) {
                val name = info.substringBefore(" (").substringAfterLast('.')
                counts.getOrPut(name) { AtomicInteger() }.incrementAndGet()
            }
            override fun traceEventEnd() = Unit
            override fun isTraceInProgress() = true
        })
        // A pull that builds: a new peak on every sample, 80 of them.
        for (step in 1..80) {
            compose.runOnIdle { sample(10.0 + step * 0.25) }
            if (step % 8 == 0) compose.waitForIdle()
        }
        compose.waitForIdle()
        assertTrue(count("MaxLiveHero") >= 8, "the hero must follow the climbing peak: ${count("MaxLiveHero")}")
        for (still in listOf("MaxMeasureScreen", "InfoPanel", "MaxHandSwitch", "HandTile", "Dock", "MaxWash")) {
            assertEquals(0, count(still), "a climbing pull recomposed $still")
        }
    }
}
