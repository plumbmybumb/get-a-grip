// SPDX-License-Identifier: MPL-2.0
// Original contributions Copyright 2026 Nuri Bruner.

package run.nuri.getagrip.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.unit.dp
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertTrue

/** The Maxes nudge breathes a few times and then lets the frame clock stop. A forever pulse
 * never lets Compose go idle, so this test would time out rather than pass. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class BenchmarkPulseTests {
    @get:Rule val compose = createComposeRule()

    @Test fun theNudgePlaysItsBeatsAndStops() {
        val start = compose.mainClock.currentTime
        compose.setContent { Box(Modifier.size(24.dp).benchmarkPulse(true)) }
        compose.waitForIdle()
        val played = compose.mainClock.currentTime - start
        assertTrue(played >= BENCHMARK_PULSE_BEATS * 1_800L, "it pulsed for only $played ms")
        assertTrue(played < BENCHMARK_PULSE_BEATS * 1_800L + 1_000L, "it kept going: $played ms")
    }
}
