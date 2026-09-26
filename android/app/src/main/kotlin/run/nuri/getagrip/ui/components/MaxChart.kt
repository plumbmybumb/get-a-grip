// SPDX-License-Identifier: MPL-2.0
// Original contributions Copyright 2026 Nuri Bruner.

package run.nuri.getagrip.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import android.text.format.DateFormat
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import run.nuri.getagrip.engine.Side
import run.nuri.getagrip.ui.l10n.tr
import run.nuri.getagrip.ui.theme.GetAGripTheme
import run.nuri.getagrip.ui.theme.LocalGripPalette
import run.nuri.getagrip.ui.units.WeightUnit
import run.nuri.getagrip.ui.units.WeightUnits

/// One point on a max curve: when it was pulled and how much.
///
/// `x` is any monotonically increasing quantity — the callers hand it epoch millis. The
/// chart never formats a date, which is what keeps it a drawing rather than a screen.
data class MaxPoint(val x: Double, val kg: Double)

/// One hand's series. `side` chooses the dash, and nothing else.
data class MaxSeries(val side: Side, val points: List<MaxPoint>)

/// The max curves for one grip — **all in bleu, because measured kilograms get the
/// measurement colour everywhere in this app.**
///
/// **Hands differ by DASH, not hue**, so the distinction survives greyscale and every kind
/// of colour vision: solid for both hands, `[6, 4]` dashed for the left, `[1.5, 3.5]`
/// dotted for the right. The wash under the curve appears ONLY on a single-series chart,
/// where it cannot smear two hands into one shape.
///
/// Drawn as the iOS card draws it with Swift Charts: MONOTONE curves through every test
/// (`MonotoneCubic` — Fritsch–Carlson, so the line never bulges past a measured value and
/// never invents a peak between two tests), a value axis from zero on the trailing edge
/// with the unit above it, ~3 date labels along the bottom, and quiet grid lines at every
/// tick (tertiary ink at 0.2).
///
/// TRANSLATION NOTE: iOS builds this from Swift Charts' `AreaMark`/`LineMark`/`PointMark`.
/// There is no Compose equivalent in this project's dependency set and a charting library
/// would be a large dependency for two cards, so it is a Canvas with the same marks, the
/// same brush as `ForceTraceView`'s fill (bleu 0.28 → 0.02) and the same axis layout.
///
/// `criticalForce` adds a grip's critical force tests UNDER its max, in steel with SQUARE
/// points: the gap between the two lines is the picture, the ceiling against what you can
/// keep using. Hands still differ by dash, so the two vocabularies never cross. With any
/// critical force on the chart there is no wash: it ended at the last max while the grey
/// lines ran on, and read as cut off.
@Composable
fun MaxChart(
    series: List<MaxSeries>,
    modifier: Modifier = Modifier,
    tint: Color = LocalGripPalette.current.bleu,
    criticalForce: List<MaxSeries> = emptyList(),
    criticalForceTint: Color = LocalGripPalette.current.calm,
) {
    val palette = LocalGripPalette.current
    val drawable = series.filter { it.points.isNotEmpty() }
    val tests = criticalForce.filter { it.points.isNotEmpty() }
    if (drawable.isEmpty() && tests.isEmpty()) return

    val unit = WeightUnits.current
    val fontScale = LocalDensity.current.fontScale
    val locale = LocalConfiguration.current.locales[0]
    val measurer = rememberTextMeasurer(cacheSize = 16)
    val labelStyle = MaterialTheme.typography.labelSmall.copy(
        fontWeight = FontWeight.Normal,
        letterSpacing = 0.sp,
        color = palette.inkSecondary,
        fontFeatureSettings = "tnum",
    )
    val dates = remember(locale) {
        DateTimeFormatter.ofPattern(DateFormat.getBestDateTimePattern(locale, "dMMM"), locale)
    }
    val axes = ChartAxisStyle(
        grid = palette.inkTertiary.copy(alpha = 0.2f),
        label = labelStyle,
        unit = unit,
        dates = dates,
        // Two date labels at accessibility sizes, as iOS asks for: three wide labels collide.
        dateCount = if (fontScale >= 1.5f) 2 else 3,
    )

    Column(modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Canvas(
            Modifier
                .fillMaxWidth()
                // iOS scales the chart with the body text (`@ScaledMetric`), so the labels keep
                // their share of it.
                .height(CHART_HEIGHT * fontScale.coerceIn(1f, 2f))
                .clearAndSetSemantics {},
        ) {
            drawMaxChart(drawable, tint, tests, criticalForceTint, axes, measurer)
        }
        // The key, in words, for the cases where it is ambiguous. Hidden from TalkBack: the
        // card's own spoken summary already carries the numbers per hand, and a screen
        // reader has no use for which line is dotted.
        val legend = buildList {
            if (drawable.isNotEmpty() && tests.isNotEmpty()) add(tr("blue max · grey critical force"))
            if ((drawable + tests).any { it.side != Side.both }) add(tr("dashed left · dotted right"))
        }
        if (legend.isNotEmpty()) {
            Text(
                legend.joinToString(" · "),
                style = MaterialTheme.typography.labelSmall,
                color = palette.inkTertiary,
                modifier = Modifier.clearAndSetSemantics {},
            )
        }
    }
}

private val CHART_HEIGHT = 130.dp

private class ChartAxisStyle(
    val grid: Color,
    val label: TextStyle,
    val unit: WeightUnit,
    val dates: DateTimeFormatter,
    val dateCount: Int,
)

private fun DrawScope.drawMaxChart(
    series: List<MaxSeries>,
    tint: Color,
    criticalForce: List<MaxSeries>,
    criticalForceTint: Color,
    axes: ChartAxisStyle,
    measurer: TextMeasurer,
) {
    val all = (series + criticalForce).flatMap { it.points }
    val minX = all.minOf { it.x }
    val maxX = all.maxOf { it.x }
    // Ticks in the unit the climber reads, so a pound chart labels round pounds.
    val valueTicks = MaxChartAxes.valueTicks(
        axes.unit.fromKg(all.maxOf { it.kg }),
        count = if (size.height < 110.dp.toPx()) 3 else 4,
    )
    val top = valueTicks.last()

    val gap = 4.dp.toPx()
    val grid = 1.dp.toPx() / 2
    val decimals = if (valueTicks[1] - valueTicks[0] < 1.0) 1 else 0
    val valueLabels = valueTicks.map { measurer.measure(WeightUnits.formatDisplayed(it, decimals), axes.label) }
    val unitLabel = measurer.measure(axes.unit.symbol, axes.label)
    val labelHeight = valueLabels.first().size.height.toFloat()
    val labelColumn = maxOf(valueLabels.maxOf { it.size.width }, unitLabel.size.width).toFloat()

    // The plot: the value labels ride the trailing edge (Swift Charts' default), the unit sits
    // above them, and the dates take a row underneath.
    val plotLeft = POINT_CLEARANCE.toPx()
    val plotRight = size.width - labelColumn - gap
    val plotTop = unitLabel.size.height + labelHeight / 2
    val plotBottom = size.height - labelHeight - gap
    val plotWidth = maxOf(1f, plotRight - plotLeft - POINT_CLEARANCE.toPx())
    val plotHeight = maxOf(1f, plotBottom - plotTop)

    fun x(value: Double): Float =
        if (maxX <= minX) plotLeft + plotWidth / 2f
        else plotLeft + ((value - minX) / (maxX - minX)).toFloat() * plotWidth

    fun y(kg: Double): Float {
        val fraction = (axes.unit.fromKg(kg) / top).coerceIn(0.0, 1.0)
        return plotBottom - fraction.toFloat() * plotHeight
    }

    // The value axis: a grid line at every tick, its number beside it.
    valueTicks.forEachIndexed { index, value ->
        val lineY = plotBottom - (value / top).toFloat() * plotHeight
        drawRect(axes.grid, topLeft = Offset(0f, lineY - grid / 2), size = Size(plotRight, grid))
        val label = valueLabels[index]
        drawText(label, topLeft = Offset(plotRight + gap, lineY - label.size.height / 2f))
    }
    drawText(unitLabel, topLeft = Offset(plotRight + gap, 0f))

    // The date axis: calendar-aligned ticks, a grid line and a date under each.
    val zone = ZoneId.systemDefault()
    val dateTicks = MaxChartAxes.dateTicks(minX.toLong(), maxX.toLong(), axes.dateCount, zone)
    for (tick in dateTicks) {
        val tickX = x(tick.toDouble())
        drawRect(axes.grid, topLeft = Offset(tickX - grid / 2, plotTop), size = Size(grid, plotBottom - plotTop))
        val label = measurer.measure(
            axes.dates.format(Instant.ofEpochMilli(tick).atZone(zone)),
            axes.label,
        )
        val labelX = (tickX - label.size.width / 2f).coerceIn(0f, maxOf(0f, plotRight - label.size.width))
        drawText(label, topLeft = Offset(labelX, plotBottom + gap))
    }

    val single = series.size == 1 && criticalForce.isEmpty()

    fun line(line: MaxSeries, colour: Color, square: Boolean) {
        val ordered = line.points.sortedBy { it.x }
        val xs = FloatArray(ordered.size) { x(ordered[it].x) }
        val ys = FloatArray(ordered.size) { y(ordered[it].kg) }
        val path = Path().apply { MonotoneCubic.append(this, xs, ys) }

        // The wash, only when there is one series to wash under — the runner's own brush,
        // because these are the same species of data (measured kilograms). It runs down to
        // zero, the axis's floor, as Swift Charts' area does.
        if (single && !square && ordered.size >= 2) {
            val fill = Path().apply {
                addPath(path)
                lineTo(xs.last(), plotBottom)
                lineTo(xs.first(), plotBottom)
                close()
            }
            drawPath(
                path = fill,
                brush = Brush.verticalGradient(
                    colors = listOf(tint.copy(alpha = 0.28f), tint.copy(alpha = 0.02f)),
                    startY = plotTop,
                    endY = plotBottom,
                ),
            )
        }

        if (ordered.size >= 2) {
            drawPath(
                path = path,
                color = colour,
                style = Stroke(
                    width = strokeWidth(line.side).toPx(),
                    cap = StrokeCap.Round,
                    join = StrokeJoin.Round,
                    pathEffect = dash(line.side)?.let {
                        PathEffect.dashPathEffect(floatArrayOf(it.first.dp.toPx(), it.second.dp.toPx()))
                    },
                ),
            )
        }

        // The points, always — a two-test series is two facts, and the curve between them is
        // the inference. A single-point series draws nothing BUT its point.
        for (index in ordered.indices) {
            val center = Offset(xs[index], ys[index])
            if (square) {
                val half = SQUARE_HALF.toPx()
                drawRect(colour, topLeft = Offset(center.x - half, center.y - half), size = Size(half * 2, half * 2))
            } else {
                drawCircle(color = colour, radius = POINT_RADIUS.toPx(), center = center)
            }
        }
    }

    // Critical force first, so the max — the number it is a share of — draws on top.
    for (test in criticalForce) line(test, criticalForceTint, square = true)
    for (max in series) line(max, tint, square = false)
}

private val POINT_RADIUS = 2.5.dp
/// A square of roughly the circle's area, so neither mark reads as the heavier fact.
private val SQUARE_HALF = 2.2.dp
/// Keeps the first and last points whole inside the plot.
private val POINT_CLEARANCE = 4.dp

private fun strokeWidth(side: Side) = if (side == Side.both) 2.5.dp else 2.dp

/// The iOS dash patterns, verbatim: `[6, 4]` for the left hand, `[1.5, 3.5]` for the
/// right, nothing for both. They are far enough apart to tell at a glance on a 130 dp
/// chart, which is the only test that matters.
private fun dash(side: Side): Pair<Double, Double>? = when (side) {
    Side.both -> null
    Side.left -> 6.0 to 4.0
    Side.right -> 1.5 to 3.5
}

/// Monotone cubic interpolation (Fritsch–Carlson), what Swift Charts calls `.monotone`: a
/// smooth curve through every point that is monotone wherever the data is, so it never
/// overshoots a measured value — a max curve that swung above the best test would be
/// inventing strength nobody pulled.
internal object MonotoneCubic {
    /// The curve's slope at each point, in y per x.
    fun tangents(xs: FloatArray, ys: FloatArray): FloatArray {
        val n = xs.size
        val m = FloatArray(n)
        if (n < 2) return m
        val secants = FloatArray(n - 1) { k ->
            val h = xs[k + 1] - xs[k]
            if (h > 0f) (ys[k + 1] - ys[k]) / h else 0f
        }
        m[0] = secants[0]
        m[n - 1] = secants[n - 2]
        for (k in 1 until n - 1) {
            val a = secants[k - 1]
            val b = secants[k]
            m[k] = if (a * b <= 0f) 0f else (a + b) / 2f
        }
        for (k in 0 until n - 1) {
            val d = secants[k]
            if (d == 0f) {
                m[k] = 0f
                m[k + 1] = 0f
                continue
            }
            val alpha = m[k] / d
            val beta = m[k + 1] / d
            val length = alpha * alpha + beta * beta
            if (length > 9f) {
                val tau = 3f / kotlin.math.sqrt(length)
                m[k] = tau * alpha * d
                m[k + 1] = tau * beta * d
            }
        }
        return m
    }

    /// Moves to the first point and adds one cubic per interval. Points must be sorted by x.
    fun append(path: Path, xs: FloatArray, ys: FloatArray) {
        if (xs.isEmpty()) return
        path.moveTo(xs[0], ys[0])
        val m = tangents(xs, ys)
        for (k in 0 until xs.size - 1) {
            val third = (xs[k + 1] - xs[k]) / 3f
            path.cubicTo(
                xs[k] + third, ys[k] + m[k] * third,
                xs[k + 1] - third, ys[k + 1] - m[k + 1] * third,
                xs[k + 1], ys[k + 1],
            )
        }
    }
}

/// Where the chart's ticks fall, as Swift Charts picks them: round values from zero, and
/// dates on calendar boundaries.
internal object MaxChartAxes {
    /// Zero to a round ceiling at or above `maxValue`, in steps of 1, 2 or 5 × 10ⁿ, about
    /// `count` intervals.
    fun valueTicks(maxValue: Double, count: Int): List<Double> {
        val ceiling = if (maxValue > 0 && maxValue.isFinite()) maxValue else 1.0
        val raw = ceiling / count
        val magnitude = Math.pow(10.0, kotlin.math.floor(kotlin.math.log10(raw)))
        val step = listOf(1.0, 2.0, 5.0, 10.0).map { it * magnitude }.first { it >= raw - 1e-9 }
        val top = kotlin.math.ceil(ceiling / step - 1e-9) * step
        val ticks = ArrayList<Double>()
        var value = 0.0
        while (value <= top + step / 2) {
            ticks += value
            value += step
        }
        return ticks
    }

    /// Up to `count` calendar-aligned instants (epoch millis) inside `[from, to]`: days,
    /// weeks, months or years, whichever is the finest that fits. A span shorter than any
    /// boundary labels its first test.
    fun dateTicks(from: Long, to: Long, count: Int, zone: ZoneId): List<Long> {
        val start = Instant.ofEpochMilli(from).atZone(zone)
        val end = Instant.ofEpochMilli(to)
        for (step in DATE_STEPS) {
            val ticks = ArrayList<Long>()
            var tick = step.first(start)
            while (!tick.toInstant().isAfter(end)) {
                if (tick.toInstant().toEpochMilli() >= from) ticks += tick.toInstant().toEpochMilli()
                if (ticks.size > count) break
                tick = step.next(tick)
            }
            if (ticks.size in 1..count) return ticks
        }
        return listOf(from)
    }

    private class DateStep(val unit: ChronoUnit, val amount: Long) {
        /// The first boundary at or after the start of `date`'s unit.
        fun first(date: ZonedDateTime): ZonedDateTime {
            val day = date.toLocalDate()
            val aligned = when (unit) {
                ChronoUnit.DAYS -> day
                ChronoUnit.WEEKS -> day.with(java.time.temporal.TemporalAdjusters.previousOrSame(java.time.DayOfWeek.MONDAY))
                ChronoUnit.MONTHS -> day.withDayOfMonth(1).let {
                    it.withMonth(((it.monthValue - 1) / amount.toInt()) * amount.toInt() + 1)
                }
                else -> day.withDayOfYear(1)
            }
            return aligned.atStartOfDay(date.zone)
        }

        fun next(date: ZonedDateTime): ZonedDateTime = date.plus(amount, unit)
    }

    private val DATE_STEPS = listOf(
        DateStep(ChronoUnit.DAYS, 1), DateStep(ChronoUnit.DAYS, 2),
        DateStep(ChronoUnit.WEEKS, 1), DateStep(ChronoUnit.WEEKS, 2),
        DateStep(ChronoUnit.MONTHS, 1), DateStep(ChronoUnit.MONTHS, 2), DateStep(ChronoUnit.MONTHS, 3),
        DateStep(ChronoUnit.MONTHS, 6), DateStep(ChronoUnit.YEARS, 1), DateStep(ChronoUnit.YEARS, 2),
        DateStep(ChronoUnit.YEARS, 5),
    )
}

@Preview(name = "MaxChart", showBackground = true, widthDp = 360)
@Composable
private fun MaxChartPreview() {
    GetAGripTheme {
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            MaxChart(
                listOf(
                    MaxSeries(Side.both, listOf(
                        MaxPoint(0.0, 28.0), MaxPoint(1.0, 29.5),
                        MaxPoint(2.0, 29.0), MaxPoint(3.0, 31.5),
                    )),
                ),
            )
            MaxChart(
                listOf(
                    MaxSeries(Side.left, listOf(MaxPoint(0.0, 30.0), MaxPoint(2.0, 32.0))),
                    MaxSeries(Side.right, listOf(MaxPoint(0.0, 27.0), MaxPoint(2.0, 29.5))),
                ),
            )
        }
    }
}
