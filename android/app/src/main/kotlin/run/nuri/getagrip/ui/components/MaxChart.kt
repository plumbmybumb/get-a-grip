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
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import run.nuri.getagrip.engine.Side
import run.nuri.getagrip.ui.l10n.tr
import run.nuri.getagrip.ui.theme.GetAGripTheme
import run.nuri.getagrip.ui.theme.LocalGripPalette

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
/// AXIS-FREE, exactly like the iOS card once its grid lines are stripped: the numbers that
/// matter are already on the card (the hero readout and the progress line), and a 130 dp
/// chart spending a third of its width on tick labels shows less of the shape it exists to
/// show.
///
/// TRANSLATION NOTE: iOS builds this from Swift Charts' `AreaMark`/`LineMark`/`PointMark`.
/// There is no Compose equivalent in this project's dependency set and a charting library
/// would be a large dependency for two cards, so it is a Canvas — same marks, same brush
/// as `ForceTraceView`'s fill (bleu 0.28 → 0.02), same monotone-ish reading of the data.
/// The one honest difference is INTERPOLATION: Swift Charts draws a monotone cubic, this
/// draws straight segments between points. With a handful of max tests months apart a
/// smoothed curve invents intermediate strength the gauge never measured, so the segments
/// are arguably the more truthful line — but it is a visible difference and it is noted.
@Composable
fun MaxChart(
    series: List<MaxSeries>,
    modifier: Modifier = Modifier,
    tint: Color = LocalGripPalette.current.bleu,
) {
    val palette = LocalGripPalette.current
    val drawable = series.filter { it.points.isNotEmpty() }
    if (drawable.isEmpty()) return

    Column(modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Canvas(
            Modifier
                .fillMaxWidth()
                .height(CHART_HEIGHT)
                .clearAndSetSemantics {},
        ) {
            drawMaxChart(drawable, tint)
        }
        // The dash key, in words, for the one case where it is ambiguous. Hidden from
        // TalkBack: the card's own spoken summary already carries the numbers per hand,
        // and a screen reader has no use for which line is dotted.
        if (drawable.any { it.side != Side.both }) {
            Text(
                tr("dashed left · dotted right"),
                style = MaterialTheme.typography.labelSmall,
                color = palette.inkTertiary,
                modifier = Modifier.clearAndSetSemantics {},
            )
        }
    }
}

private val CHART_HEIGHT = 130.dp

private fun DrawScope.drawMaxChart(series: List<MaxSeries>, tint: Color) {
    val insetTop = 10.dp.toPx()
    val insetBottom = 8.dp.toPx()
    val insetSide = 4.dp.toPx()
    val plotHeight = maxOf(1f, size.height - insetTop - insetBottom)
    val plotWidth = maxOf(1f, size.width - insetSide * 2)

    val all = series.flatMap { it.points }
    val minX = all.minOf { it.x }
    val maxX = all.maxOf { it.x }
    val maxKg = all.maxOf { it.kg }
    // The floor is NOT zero. A max curve that moves from 30 to 32 kg over a season is a
    // flat line against a zero baseline — the whole point of the card is the shape of that
    // two-kilogram move. 12 % of headroom under the lowest point keeps it from touching
    // the floor without pretending the axis starts at nothing.
    val minKg = all.minOf { it.kg }
    val span = maxOf(0.5, maxKg - minKg)
    val floor = maxOf(0.0, minKg - span * 0.12)
    val ceiling = maxKg + span * 0.12

    fun x(value: Double): Float =
        if (maxX <= minX) insetSide + plotWidth / 2f
        else insetSide + ((value - minX) / (maxX - minX)).toFloat() * plotWidth

    fun y(kg: Double): Float {
        val fraction = ((kg - floor) / (ceiling - floor)).coerceIn(0.0, 1.0)
        return insetTop + plotHeight - fraction.toFloat() * plotHeight
    }

    val single = series.size == 1

    for (line in series) {
        val ordered = line.points.sortedBy { it.x }
        val path = Path()
        ordered.forEachIndexed { index, point ->
            val px = x(point.x)
            val py = y(point.kg)
            if (index == 0) path.moveTo(px, py) else path.lineTo(px, py)
        }

        // The wash, only when there is one series to wash under — the runner's own brush,
        // because these are the same species of data (measured kilograms) and a naked
        // hairline made the card read as a second, thinner instrument.
        if (single && ordered.size >= 2) {
            val fill = Path().apply {
                addPath(path)
                lineTo(x(ordered.last().x), size.height)
                lineTo(x(ordered.first().x), size.height)
                close()
            }
            drawPath(
                path = fill,
                brush = Brush.verticalGradient(
                    colors = listOf(tint.copy(alpha = 0.28f), tint.copy(alpha = 0.02f)),
                    startY = 0f,
                    endY = size.height,
                ),
            )
        }

        if (ordered.size >= 2) {
            drawPath(
                path = path,
                color = tint,
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

        // The points, always — a two-test series is two facts, and the segment between
        // them is the inference. A single-point series draws nothing BUT its point, which
        // is the honest picture of one test.
        for (point in ordered) {
            drawCircle(color = tint, radius = POINT_RADIUS.toPx(), center = Offset(x(point.x), y(point.kg)))
        }
    }

    // A hairline baseline so the curve sits on something rather than floating. Neutral,
    // one pixel, no ticks: it is the card's floor, not an axis.
    drawRect(
        color = tint.copy(alpha = 0.14f),
        topLeft = Offset(insetSide, insetTop + plotHeight),
        size = Size(plotWidth, 1.dp.toPx()),
    )
}

private val POINT_RADIUS = 2.5.dp

private fun strokeWidth(side: Side) = if (side == Side.both) 2.5.dp else 2.dp

/// The iOS dash patterns, verbatim: `[6, 4]` for the left hand, `[1.5, 3.5]` for the
/// right, nothing for both. They are far enough apart to tell at a glance on a 130 dp
/// chart, which is the only test that matters.
private fun dash(side: Side): Pair<Double, Double>? = when (side) {
    Side.both -> null
    Side.left -> 6.0 to 4.0
    Side.right -> 1.5 to 3.5
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
