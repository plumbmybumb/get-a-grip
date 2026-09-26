// SPDX-License-Identifier: MPL-2.0
// Original contributions Copyright 2026 Nuri Bruner.

package run.nuri.getagrip.ui.history

import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.TemporalAdjusters
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

    /// Date marks for a span, at most `desired` of them, on calendar boundaries — days, then
    /// Mondays, then month starts — the way Swift Charts' `.automatic(desiredCount: 4)` lands
    /// them. Only marks INSIDE the span are returned; a span shorter than one step gets its
    /// first day, so the axis is never unlabelled.
    fun dateTicks(first: Instant, last: Instant, zone: ZoneId, desired: Int = 4): List<LocalDate> {
        val start = first.atZone(zone).toLocalDate()
        val end = last.atZone(zone).toLocalDate()
        if (!end.isAfter(start)) return listOf(start)
        val spanDays = end.toEpochDay() - start.toEpochDay()

        fun inside(dates: Sequence<LocalDate>): List<LocalDate> =
            dates.dropWhile { it.isBefore(start) }.takeWhile { !it.isAfter(end) }.toList()

        for (step in longArrayOf(1, 2, 3)) {
            if (spanDays / step + 1 <= desired) {
                val firstMark = LocalDate.ofEpochDay(ceilTo(start.toEpochDay(), step))
                return inside(generateSequence(firstMark) { it.plusDays(step) }).ifEmpty { listOf(start) }
            }
        }
        for (weeks in longArrayOf(1, 2)) {
            if (spanDays / (7 * weeks) + 1 <= desired) {
                val monday = start.with(TemporalAdjusters.nextOrSame(DayOfWeek.MONDAY))
                return inside(generateSequence(monday) { it.plusWeeks(weeks) }).ifEmpty { listOf(start) }
            }
        }
        for (months in intArrayOf(1, 2, 3, 6)) {
            // The first 1st on or after the start whose month sits on the step's grid
            // (January, then every `months` after it), so marks read Jan/Apr/Jul, never Feb/May.
            var mark = start.withDayOfMonth(1)
            if (mark.isBefore(start)) mark = mark.plusMonths(1)
            while ((mark.monthValue - 1) % months != 0) mark = mark.plusMonths(1)
            val marks = inside(generateSequence(mark) { it.plusMonths(months.toLong()) })
            if (marks.size <= desired) return marks.ifEmpty { listOf(start) }
        }
        val years = ceil(spanDays / 365.0 / (desired - 1).coerceAtLeast(1)).toLong().coerceAtLeast(1)
        val january = start.withDayOfYear(1).let { if (it.isBefore(start)) it.plusYears(1) else it }
        return inside(generateSequence(january) { it.plusYears(years) }).ifEmpty { listOf(start) }
    }

    private fun ceilTo(value: Long, step: Long): Long = Math.floorDiv(value + step - 1, step) * step
}
