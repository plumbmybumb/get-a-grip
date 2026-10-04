// SPDX-License-Identifier: MPL-2.0
// Original contributions Copyright 2026 Nuri Bruner.

package run.nuri.getagrip.ui.history

import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.log10
import kotlin.math.min
import kotlin.math.pow

/// The arithmetic behind `TrendChart`, with no Compose in it so the tests can pin it.
///
/// TRANSLATION NOTE: iOS draws this card with Swift Charts — `.interpolationMethod(.monotone)`,
/// automatic y marks over a domain that includes zero (an `AreaMark` always does), and four
/// date marks. Compose has no chart kit in this project's dependency set, so these three
/// decisions are made here by hand.
object TrendChartGeometry {

    /// Where the chart's date labels go, by SESSION, not by calendar (Nuri, 2026-10-04; iOS
    /// `TrendModel.sessionTicks`): the x axis spaces sessions evenly, so two weeks off is not a
    /// two-week hole in the line. Up to `desired` indices, always the first and the last.
    fun sessionTicks(count: Int, desired: Int = 4): List<Int> {
        if (count <= 0) return emptyList()
        if (count <= desired || desired <= 1) return (0 until count).toList()
        val ticks = mutableListOf<Int>()
        for (step in 0 until desired) {
            val index = Math.round(step * (count - 1).toDouble() / (desired - 1)).toInt()
            if (ticks.lastOrNull() != index) ticks += index
        }
        return ticks
    }

    /// Tangents for a MONOTONE cubic through `(xs, ys)` — the Steffen/`d3.curveMonotoneX`
    /// construction Swift Charts' `.monotone` draws. It never overshoots: between two
    /// sessions the curve stays between their two loads, so the line never shows a session
    /// heavier or lighter than any that happened. `xs` strictly increasing.
    fun monotoneTangents(xs: DoubleArray, ys: DoubleArray): DoubleArray {
        val n = xs.size
        require(ys.size == n)
        val tangents = DoubleArray(n)
        if (n < 2) return tangents
        fun secant(i: Int): Double {
            val h = xs[i + 1] - xs[i]
            return if (h == 0.0) 0.0 else (ys[i + 1] - ys[i]) / h
        }
        if (n == 2) {
            tangents[0] = secant(0)
            tangents[1] = secant(0)
            return tangents
        }
        for (i in 1 until n - 1) {
            val h0 = xs[i] - xs[i - 1]
            val h1 = xs[i + 1] - xs[i]
            val s0 = secant(i - 1)
            val s1 = secant(i)
            val p = if (h0 + h1 == 0.0) 0.0 else (s0 * h1 + s1 * h0) / (h0 + h1)
            val t = (sign(s0) + sign(s1)) * min(min(abs(s0), abs(s1)), 0.5 * abs(p))
            tangents[i] = if (t.isFinite()) t else 0.0
        }
        // The ends take the one-sided estimate, which keeps the end segments monotone too.
        tangents[0] = (3 * secant(0) - tangents[1]) / 2
        tangents[n - 1] = (3 * secant(n - 2) - tangents[n - 2]) / 2
        return tangents
    }

    /// d3's sign: zero counts as positive, so a flat neighbour zeroes the tangent.
    private fun sign(x: Double): Double = if (x < 0) -1.0 else 1.0

    /// The value axis: from ZERO (an area chart's floor, as Swift Charts draws it) to a round
    /// number over the top, in ~four steps of 1, 2, 2.5 or 5 × 10ⁿ.
    fun valueTicks(maxValue: Double, desired: Int = 4): List<Double> {
        val top = if (maxValue.isFinite() && maxValue > 0) maxValue else 1.0
        val rough = top / desired
        val magnitude = 10.0.pow(floor(log10(rough)))
        val step = listOf(1.0, 2.0, 2.5, 5.0, 10.0).map { it * magnitude }.first { it >= rough }
        val count = ceil(top / step - 1e-9).toInt().coerceAtLeast(1)
        return (0..count).map { it * step }
    }
}
