// SPDX-License-Identifier: MPL-2.0
// Original contributions Copyright 2026 Nuri Bruner.

package run.nuri.getagrip.ui

import run.nuri.getagrip.ui.components.MaxChartAxes
import run.nuri.getagrip.ui.components.MonotoneCubic
import java.time.LocalDate
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/// The Benchmarks chart's pure half: the monotone curve and where its ticks land.
class MaxChartMathTests {
    private fun cubic(p0: Float, p1: Float, p2: Float, p3: Float, t: Float): Float {
        val u = 1 - t
        return u * u * u * p0 + 3 * u * u * t * p1 + 3 * u * t * t * p2 + t * t * t * p3
    }

    /// Samples every interval of the curve and checks it stays between its two points: the
    /// line may never promise a max above (or below) what was measured either side of it.
    @Test fun theCurveNeverOvershootsAMeasuredPoint() {
        val cases = listOf(
            floatArrayOf(0f, 10f, 20f, 30f, 40f) to floatArrayOf(30f, 31f, 29f, 45f, 45.5f),
            floatArrayOf(0f, 1f, 50f, 51f) to floatArrayOf(10f, 40f, 40.5f, 20f),
            floatArrayOf(0f, 5f, 6f, 100f) to floatArrayOf(5f, 5f, 80f, 81f),
        )
        for ((xs, ys) in cases) {
            val m = MonotoneCubic.tangents(xs, ys)
            for (k in 0 until xs.size - 1) {
                val third = (xs[k + 1] - xs[k]) / 3f
                val c1 = ys[k] + m[k] * third
                val c2 = ys[k + 1] - m[k + 1] * third
                val lo = minOf(ys[k], ys[k + 1]) - 1e-3f
                val hi = maxOf(ys[k], ys[k + 1]) + 1e-3f
                for (step in 0..50) {
                    val y = cubic(ys[k], c1, c2, ys[k + 1], step / 50f)
                    assertTrue(y in lo..hi, "interval $k overshoots: $y outside ${ys[k]}..${ys[k + 1]}")
                }
            }
        }
    }

    @Test fun aLocalPeakIsFlatAndAStraightRunStaysStraight() {
        val peak = MonotoneCubic.tangents(floatArrayOf(0f, 1f, 2f), floatArrayOf(0f, 5f, 0f))
        assertEquals(0f, peak[1], "A turning point has a flat tangent, so it never rises past itself")
        val straight = MonotoneCubic.tangents(floatArrayOf(0f, 1f, 2f, 3f), floatArrayOf(0f, 2f, 4f, 6f))
        straight.forEach { assertEquals(2f, it, 1e-6f) }
    }

    @Test fun valuesRunFromZeroToARoundCeiling() {
        assertEquals(listOf(0.0, 10.0, 20.0, 30.0, 40.0), MaxChartAxes.valueTicks(34.5, 4))
        assertEquals(listOf(0.0, 20.0, 40.0, 60.0, 80.0), MaxChartAxes.valueTicks(71.0, 4))
        assertEquals(listOf(0.0, 50.0, 100.0), MaxChartAxes.valueTicks(88.0, 3))
        assertTrue(MaxChartAxes.valueTicks(0.0, 4).last() > 0.0, "An empty chart still has a scale")
    }

    @Test fun datesLandOnCalendarBoundariesAndNeverCrowd() {
        val zone = ZoneId.of("Europe/Paris")
        fun millis(date: LocalDate) = date.atStartOfDay(zone).toInstant().toEpochMilli()
        val from = millis(LocalDate.of(2026, 3, 14)) + 3_600_000
        val to = millis(LocalDate.of(2026, 9, 20))
        val ticks = MaxChartAxes.dateTicks(from, to, 3, zone)
        assertTrue(ticks.size in 1..3, "$ticks")
        assertEquals(
            listOf(LocalDate.of(2026, 5, 1), LocalDate.of(2026, 7, 1), LocalDate.of(2026, 9, 1)),
            ticks.map { java.time.Instant.ofEpochMilli(it).atZone(zone).toLocalDate() },
        )
        // Two tests on one afternoon: no midnight in between, so the first test is labelled.
        assertEquals(listOf(from), MaxChartAxes.dateTicks(from, from + 3_600_000, 3, zone))
    }
}
