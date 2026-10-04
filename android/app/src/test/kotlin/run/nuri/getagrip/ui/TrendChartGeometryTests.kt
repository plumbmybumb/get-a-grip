// SPDX-License-Identifier: MPL-2.0
// Original contributions Copyright 2026 Nuri Bruner.

package run.nuri.getagrip.ui

import run.nuri.getagrip.ui.history.TrendChartGeometry
import kotlin.math.max
import kotlin.math.min
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/// The trend chart's hand-made Swift Charts decisions: a monotone curve that never invents a
/// load, a value axis from zero to a round number, and a handful of calendar-aligned dates.
class TrendChartGeometryTests {

    /// Samples each cubic segment exactly as `TrendChart` draws it.
    private fun curve(xs: DoubleArray, ys: DoubleArray, t: DoubleArray, segment: Int, u: Double): Double {
        val h = xs[segment + 1] - xs[segment]
        val y0 = ys[segment]
        val c0 = y0 + h / 3 * t[segment]
        val c1 = ys[segment + 1] - h / 3 * t[segment + 1]
        val y1 = ys[segment + 1]
        val v = 1 - u
        return v * v * v * y0 + 3 * v * v * u * c0 + 3 * v * u * u * c1 + u * u * u * y1
    }

    /// THE property: between two sessions the line stays between their two loads. A spline
    /// that overshoots draws a session heavier than any that happened.
    @Test fun theMonotoneCurveNeverOvershootsASegment() {
        val xs = doubleArrayOf(0.0, 10.0, 12.0, 40.0, 41.0, 90.0, 100.0)
        val ys = doubleArrayOf(12.0, 12.5, 20.0, 20.0, 11.0, 30.0, 29.0)
        val t = TrendChartGeometry.monotoneTangents(xs, ys)
        for (segment in 0 until xs.size - 1) {
            val lo = min(ys[segment], ys[segment + 1])
            val hi = max(ys[segment], ys[segment + 1])
            for (step in 0..50) {
                val y = curve(xs, ys, t, segment, step / 50.0)
                assertTrue(y >= lo - 1e-9 && y <= hi + 1e-9, "segment $segment overshot: $y outside $lo…$hi")
            }
        }
    }

    /// A plateau is flat, and a local peak gets a horizontal tangent — the point IS the top.
    @Test fun flatRunsAndPeaksGetFlatTangents() {
        val t = TrendChartGeometry.monotoneTangents(
            doubleArrayOf(0.0, 1.0, 2.0, 3.0),
            doubleArrayOf(5.0, 5.0, 8.0, 6.0),
        )
        assertEquals(0.0, t[1], 1e-12)
        assertEquals(0.0, t[2], 1e-12)
    }

    @Test fun twoPointsAreAStraightLine() {
        val t = TrendChartGeometry.monotoneTangents(doubleArrayOf(0.0, 4.0), doubleArrayOf(10.0, 18.0))
        assertEquals(listOf(2.0, 2.0), t.toList())
    }

    /// An area's floor is zero, as Swift Charts draws an `AreaMark`, and the top is a round
    /// number at or above the heaviest session.
    @Test fun valueTicksRunFromZeroToARoundNumberOverTheTop() {
        assertEquals(listOf(0.0, 5.0, 10.0, 15.0), TrendChartGeometry.valueTicks(12.5))
        assertEquals(listOf(0.0, 10.0, 20.0, 30.0, 40.0), TrendChartGeometry.valueTicks(31.0))
        assertEquals(listOf(0.0, 0.25, 0.5, 0.75, 1.0), TrendChartGeometry.valueTicks(0.9))
        for (top in listOf(0.3, 7.0, 12.0, 22.5, 48.0, 99.0, 180.0)) {
            val ticks = TrendChartGeometry.valueTicks(top)
            assertEquals(0.0, ticks.first())
            assertTrue(ticks.last() >= top, "$top above the axis")
            assertTrue(ticks.size in 2..6, "$top has ${ticks.size} ticks")
        }
    }

    @Test fun sessionTicksSpaceSessionsNotDays() {
        // Few sessions: every one is labelled.
        assertEquals(listOf(0, 1, 2), TrendChartGeometry.sessionTicks(3))
        // Many: four, always the first and the last, evenly between.
        assertEquals(listOf(0, 33, 66, 99), TrendChartGeometry.sessionTicks(100))
        assertEquals(listOf(0, 2, 3, 5), TrendChartGeometry.sessionTicks(6))
        assertTrue(TrendChartGeometry.sessionTicks(0).isEmpty())
    }
}
