// SPDX-License-Identifier: MPL-2.0
// Original contributions Copyright 2026 Nuri Bruner.

package run.nuri.getagrip.ui.history

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import run.nuri.getagrip.data.WorkoutLogEntity
import run.nuri.getagrip.engine.RepSummary
import run.nuri.getagrip.ui.components.CapsLabel
import run.nuri.getagrip.ui.components.Chip
import run.nuri.getagrip.ui.l10n.tr
import run.nuri.getagrip.ui.theme.InstrumentSurface
import run.nuri.getagrip.ui.theme.LocalGripPalette
import run.nuri.getagrip.ui.theme.Metrics
import run.nuri.getagrip.ui.units.WeightUnit
import run.nuri.getagrip.ui.units.WeightUnits
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.UUID

/// History's "Load per grip" block: one trend card per routine with measured pulls — the
/// twin of iOS's `TrendDeck`.
///
/// Its own composable, for two reasons that each cost the whole screen:
///
/// - **The grip selection lives HERE.** Held by `HistoryScreen`, every chip tap would
///   recompose the calendar, the odometer and the session rows to move one chip's fill.
/// - **The model is built off the main thread** (`TrendModel.build` on `Dispatchers.Default`)
///   and only when the feed publishes a DIFFERENT list — keyed on its identity, never on its
///   contents, because comparing two histories is itself a pass over every blob. Reps come
///   from the feed's write-once cache (`HistoryFeed.reps`), which the feed's own fold has
///   already filled, so a rebuild after a save decodes one session. A same-height
///   placeholder stands in for the first build; after that the stale model keeps drawing
///   while its replacement builds, so nothing jumps.
@Composable
fun TrendDeck(
    logs: List<WorkoutLogEntity>,
    routineNames: Map<UUID, String>,
    reps: (WorkoutLogEntity) -> List<RepSummary>,
    modifier: Modifier = Modifier,
) {
    val input = ByIdentity(logs)
    val model by produceState<TrendModel?>(null, input, routineNames) {
        value = withContext(Dispatchers.Default) {
            TrendModel.build(input.value, routineNames, reps) { !isActive }
        }
    }
    // Which grip's trend is on screen; null means the most-trained one. ONE selection shared
    // across the deck, so a grip picked on one routine stays picked as you swipe — how you
    // compare a grip across routines. Cards without it fall back to their own and remember
    // nothing.
    var selectedGripKey by rememberSaveable { mutableStateOf<String?>(null) }
    val haptics = LocalHapticFeedback.current
    val onSelect: (String) -> Unit = { key ->
        // The tick names its cause: a chip that changed the chart. Re-tapping the lit chip
        // changes nothing, so it says nothing.
        if (key != selectedGripKey) haptics.performHapticFeedback(HapticFeedbackType.SegmentTick)
        selectedGripKey = key
    }

    val routines = model?.routines
    Box(modifier.fillMaxWidth().padding(vertical = 6.dp)) {
        when {
            routines == null -> PlaceholderCard(Modifier.padding(horizontal = Metrics.hPadding))
            // A deck only once a second routine has data — as on Today, a permanent sliver of
            // "more" with one routine would be a lie.
            routines.size > 1 -> Deck(routines, selectedGripKey, onSelect)
            routines.size == 1 -> TrendCard(
                routines.first(), selectedGripKey, onSelect,
                Modifier.padding(horizontal = Metrics.hPadding),
            )
            else -> EmptyTrendCard(Modifier.padding(horizontal = Metrics.hPadding))
        }
    }
}

/// Compared by REFERENCE: the feed publishes a new list on every read and never mutates one,
/// so "a different list" is exactly "a different history", and costs nothing to ask.
private class ByIdentity<T : Any>(val value: T) {
    override fun equals(other: Any?): Boolean = other is ByIdentity<*> && other.value === value
    override fun hashCode(): Int = System.identityHashCode(value)
}

/// Average load per session for ONE grip, WITHIN ONE ROUTINE — one CARD per routine, swiped
/// like Today's deck (Nuri, 2026-08-10). Per routine because the same grip at two
/// intensities is two training lines: a 12 kg repeater and a 30 kg max pull averaged into a
/// zigzag that tracked which routine ran. Averaged, not peak: this is volume training, and
/// the session mean is what the fingers absorbed.
///
/// TRANSLATION NOTE: iOS pages with `.viewAligned(limitBehavior: .always)` — one card per
/// swipe, because three horizontal gestures share this screen (the deck, the grip chips, row
/// swipe actions). `HorizontalPager` does that by default, and nested scrolling hands a drag
/// on a chip row that FITS straight back to the pager, which is iOS's `.basedOnSize`.
///
/// Every card stands the deck's full height: a half-mast card beside a full chart reads as a
/// rendering fault. Cards report their natural height and each takes the tallest as its
/// floor — the pager composes every page (`beyondViewportPageCount`), since there is one per
/// routine, a handful at most.
@Composable
private fun Deck(
    routines: List<TrendModel.Routine>,
    selectedGripKey: String?,
    onSelect: (String) -> Unit,
) {
    val density = LocalDensity.current
    val state = rememberPagerState { routines.size }
    val heights = remember(routines) { mutableStateMapOf<String, Int>() }
    val floor = with(density) { (heights.values.maxOrNull() ?: 0).toDp() }
    HorizontalPager(
        state = state,
        modifier = Modifier.fillMaxWidth(),
        // Today's deck geometry, verbatim: cards on the house grid, the neighbour peeking 20 dp.
        contentPadding = PaddingValues(start = Metrics.hPadding, end = Metrics.hPadding + 8.dp),
        pageSpacing = 8.dp,
        beyondViewportPageCount = routines.size,
        key = { page -> routines.getOrNull(page)?.key ?: page },
        verticalAlignment = Alignment.Top,
    ) { page ->
        val routine = routines[page]
        TrendCard(
            routine, selectedGripKey, onSelect,
            modifier = Modifier.heightIn(min = floor),
            onContentHeight = { heights[routine.key] = it },
        )
    }
}

@Composable
private fun TrendCard(
    routine: TrendModel.Routine,
    selectedGripKey: String?,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier,
    onContentHeight: (Int) -> Unit = {},
) {
    val palette = LocalGripPalette.current
    val selected = routine.selectedGrip(selectedGripKey)
    val series = selected?.let { routine.series[it] }.orEmpty()
    InstrumentSurface(
        shape = RoundedCornerShape(Metrics.radiusCard),
        color = palette.card,
        modifier = modifier.fillMaxWidth(),
    ) {
        // The Box takes the card's floor; the column inside keeps its NATURAL height, which is
        // what the deck needs to know.
        Box(contentAlignment = Alignment.TopStart) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .onSizeChanged { onContentHeight(it.height) }
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                CapsLabel(tr("Load per grip"))
                // The page's identity, at card weight now that swiping reaches the others.
                Text(
                    routine.name,
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold,
                    color = palette.inkPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )

                GripPicker(routine.grips, selected, onSelect)

                if (series.size < 2) {
                    Text(
                        tr("One session so far. The trend starts after two."),
                        style = MaterialTheme.typography.bodyMedium,
                        color = palette.inkSecondary,
                    )
                } else {
                    val summary = trendSummary(series)
                    TrendChart(series, summary)
                    Text(
                        summary,
                        style = MaterialTheme.typography.bodySmall.copy(fontFeatureSettings = "tnum"),
                        color = palette.inkTertiary,
                        // The chart already speaks this sentence; once is enough.
                        modifier = Modifier.clearAndSetSemantics {},
                    )
                }
            }
        }
    }
}

@Composable
private fun GripPicker(
    options: List<TrendModel.GripOption>,
    selected: String?,
    onSelect: (String) -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(vertical = 2.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        for (option in options) {
            val spoken = option.grip.spoken
            Chip(
                title = option.grip.shortName,
                isSelected = option.key == selected,
                modifier = Modifier.semantics { contentDescription = spoken },
            ) { onSelect(option.key) }
        }
    }
}

/// The card's own anatomy, empty, while the first model builds — at a charted card's height,
/// so nothing below moves when the real one arrives.
@Composable
private fun PlaceholderCard(modifier: Modifier = Modifier) {
    val palette = LocalGripPalette.current
    val label = tr("Load per grip")
    InstrumentSurface(
        shape = RoundedCornerShape(Metrics.radiusCard),
        color = palette.card,
        modifier = modifier.fillMaxWidth().clearAndSetSemantics { contentDescription = label },
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            CapsLabel(label)
            Text(" ", style = MaterialTheme.typography.titleLarge)
            Box(Modifier.height(48.dp))
            Box(Modifier.fillMaxWidth().height(CHART_HEIGHT), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(Modifier.size(24.dp), color = palette.inkTertiary, strokeWidth = 2.dp)
            }
            Text(" ", style = MaterialTheme.typography.bodySmall)
        }
    }
}

/// Sessions exist but none carried kilograms — every pull so far was gauge-free.
@Composable
private fun EmptyTrendCard(modifier: Modifier = Modifier) {
    val palette = LocalGripPalette.current
    InstrumentSurface(
        shape = RoundedCornerShape(Metrics.radiusCard),
        color = palette.card,
        modifier = modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            CapsLabel(tr("Load per grip"))
            Text(
                tr("No measured pulls to chart yet."),
                style = MaterialTheme.typography.bodyMedium,
                color = palette.inkSecondary,
            )
        }
    }
}

@Composable
private fun trendSummary(series: List<TrendModel.Point>): String {
    val unit = WeightUnits.current
    return when (val summary = TrendModel.summary(series)) {
        null -> ""
        is TrendModel.Summary.Steady ->
            tr("Holding steady around %s %s.", unit.number(summary.lastKg), unit.symbol)
        is TrendModel.Summary.Up ->
            tr("Up %s %s across %d sessions.", unit.number(summary.deltaKg), unit.symbol, summary.sessions)
        is TrendModel.Summary.Down ->
            tr("Down %s %s across %d sessions.", unit.number(summary.deltaKg), unit.symbol, summary.sessions)
    }
}

private val CHART_HEIGHT = 170.dp

/// The session means as an area, a line and points — iOS's `AreaMark` + `LineMark` +
/// `PointMark`, all monotone, in the runner's brush (bleu 0.28 → 0.02 under the curve, as
/// `ForceTraceView` fills it), since these are the same measured kilograms. A naked hairline
/// read as a second, thinner instrument.
///
/// Quiet axes, as Swift Charts draws them here: tertiary-ink gridlines at 20 %, four date
/// marks along the bottom, value marks from zero on the trailing edge, the unit over them.
@Composable
private fun TrendChart(series: List<TrendModel.Point>, spoken: String) {
    val palette = LocalGripPalette.current
    val unit: WeightUnit = WeightUnits.current
    val measurer = rememberTextMeasurer()
    val locale = LocalConfiguration.current.locales[0]
    // Day and abbreviated month in the locale's own order ("Sep 3", "3 sept.") — iOS's
    // `.dateTime.day().month(.abbreviated)`.
    val dateFormat = remember(locale) {
        DateTimeFormatter.ofPattern(android.text.format.DateFormat.getBestDateTimePattern(locale, "dMMM"), locale)
    }
    val labelStyle = MaterialTheme.typography.bodySmall.copy(
        fontSize = 11.sp,
        lineHeight = 13.sp,
        color = palette.inkSecondary,
        fontFeatureSettings = "tnum",
    )
    val grid = palette.inkTertiary.copy(alpha = 0.2f)
    val tint = palette.bleu
    val zone = remember { ZoneId.systemDefault() }

    Canvas(
        Modifier
            .fillMaxWidth()
            .height(CHART_HEIGHT)
            .clearAndSetSemantics { contentDescription = spoken },
    ) {
        val values = series.map { unit.fromKg(it.avgKg) }
        val ticks = TrendChartGeometry.valueTicks(values.max())
        val top = ticks.last()
        val step = if (ticks.size > 1) ticks[1] - ticks[0] else 1.0
        val decimals = when {
            step % 1.0 == 0.0 -> 0
            (step * 10) % 1.0 == 0.0 -> 1
            else -> 2
        }
        val tickLabels = ticks.map { measurer.measure(WeightUnits.formatDisplayed(it, decimals), labelStyle) }
        val unitLabel = measurer.measure(unit.symbol, labelStyle)

        val gap = 6.dp.toPx()
        val labelColumn = maxOf(tickLabels.maxOf { it.size.width }, unitLabel.size.width)
        val plotLeft = 0f
        val plotRight = size.width - labelColumn - gap
        val plotTop = unitLabel.size.height + gap
        val xLabelHeight = tickLabels.first().size.height
        val plotBottom = size.height - xLabelHeight - gap
        val plotHeight = maxOf(1f, plotBottom - plotTop)
        val inset = 4.dp.toPx()

        val firstMillis = series.first().date.toEpochMilli().toDouble()
        val lastMillis = series.last().date.toEpochMilli().toDouble()
        fun x(millis: Double): Float =
            if (lastMillis <= firstMillis) (plotLeft + plotRight) / 2f
            else plotLeft + inset + ((millis - firstMillis) / (lastMillis - firstMillis)).toFloat() *
                (plotRight - plotLeft - inset * 2)
        fun y(value: Double): Float = plotBottom - (value / top).toFloat() * plotHeight

        val hairline = maxOf(1f, 0.5.dp.toPx())

        // Value gridlines and labels, trailing.
        ticks.forEachIndexed { index, tick ->
            val ty = y(tick)
            drawLine(grid, Offset(plotLeft, ty), Offset(plotRight, ty), hairline)
            val label = tickLabels[index]
            drawText(label, topLeft = Offset(plotRight + gap, ty - label.size.height / 2f))
        }
        // The unit, over the value labels.
        drawText(unitLabel, topLeft = Offset(plotRight + gap, 0f))

        // Date gridlines and labels, leading-aligned on their mark like Swift Charts' dates.
        val marks = TrendChartGeometry.dateTicks(series.first().date, series.last().date, zone)
        for (mark in marks) {
            val millis = mark.atStartOfDay(zone).toInstant().toEpochMilli().toDouble()
            val mx = x(millis.coerceIn(firstMillis, lastMillis))
            drawLine(grid, Offset(mx, plotTop), Offset(mx, plotBottom), hairline)
            val label = measurer.measure(dateFormat.format(mark), labelStyle)
            val lx = mx.coerceAtMost(plotRight - label.size.width).coerceAtLeast(0f)
            drawText(label, topLeft = Offset(lx, plotBottom + gap))
        }

        // The curve: a monotone cubic, so between two sessions it never claims a load
        // neither of them pulled.
        val xs = DoubleArray(series.size) { x(series[it].date.toEpochMilli().toDouble()).toDouble() }
        val ys = DoubleArray(series.size) { y(values[it]).toDouble() }
        val tangents = TrendChartGeometry.monotoneTangents(xs, ys)
        val line = Path().apply {
            moveTo(xs[0].toFloat(), ys[0].toFloat())
            for (i in 0 until xs.size - 1) {
                val third = (xs[i + 1] - xs[i]) / 3
                cubicTo(
                    (xs[i] + third).toFloat(), (ys[i] + third * tangents[i]).toFloat(),
                    (xs[i + 1] - third).toFloat(), (ys[i + 1] - third * tangents[i + 1]).toFloat(),
                    xs[i + 1].toFloat(), ys[i + 1].toFloat(),
                )
            }
        }
        val area = Path().apply {
            addPath(line)
            lineTo(xs.last().toFloat(), plotBottom)
            lineTo(xs.first().toFloat(), plotBottom)
            close()
        }
        drawPath(
            area,
            Brush.verticalGradient(
                listOf(tint.copy(alpha = 0.28f), tint.copy(alpha = 0.02f)),
                startY = plotTop,
                endY = plotBottom,
            ),
        )
        drawPath(line, tint, style = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
        // `symbolSize(28)` is an AREA in points: a circle of radius √(28/π).
        val radius = 3.dp.toPx()
        for (i in xs.indices) drawCircle(tint, radius, Offset(xs[i].toFloat(), ys[i].toFloat()))
    }
}
