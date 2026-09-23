// SPDX-License-Identifier: MPL-2.0
// Original contributions Copyright 2026 Nuri Bruner.

package run.nuri.getagrip.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import kotlin.math.abs
import kotlin.math.floor
import run.nuri.getagrip.engine.L10n
import run.nuri.getagrip.ui.l10n.tr
import run.nuri.getagrip.ui.theme.GetAGripTheme
import run.nuri.getagrip.ui.theme.LocalGripPalette

/// A number chosen from a LADDER, not from a continuum.
///
/// It replaced a slider plus four preset chips. Nielsen Norman: use a slider only when the
/// precise value does not matter, and every number in a routine is exact. And arithmetically,
/// a 3…45 hold slider in fives lands on 3, 8, 13, 18: 5, 7, 10 and 12 were chips only, and
/// the C4 protocol's 3 s hold, 5 s rest and 4 pulls were none of them.
///
/// **Positions are the ladder's INDICES, not the value's magnitude**: evenly spaced detents
/// make 3 and 30 equally easy, and the scale states every value it can produce. Tap-to-type
/// on the row above is not optional — Zwift's drag-only editor drives power users to hand-edit
/// exported XML.
@Composable
fun DialTrack(
    value: Double,
    /// Ascending, and FIXED — the same stops whatever `value` is.
    ///
    /// Splicing the current value in re-spaced every stop on typing and again on the first drag
    /// (Nuri, 2026-08-11). A ruler whose marks move is not a ruler: an off-ladder value gets
    /// `offLadderMark` instead.
    values: List<Double>,
    format: (Double) -> String,
    spokenUnit: String,
    label: String,
    modifier: Modifier = Modifier,
    /// An unanswered scale is a real state. The caller still supplies a value for the drag to
    /// write, but this keeps it from drawing a misleading detent or mark.
    isUnset: Boolean = false,
    onValueChange: (Double) -> Unit,
) {
    val palette = LocalGripPalette.current
    val haptics = LocalHapticFeedback.current
    var width by remember { mutableFloatStateOf(0f) }
    var dragX by remember { mutableFloatStateOf(0f) }

    val mark = DialLadder.renderingMark(value, values, isUnset)

    fun land(x: Float) {
        if (width <= 0f || values.isEmpty()) return
        val index = DialLadder.landIndex(x, width, values.size)
        val landed = values[index]
        if (!isUnset && landed == value) return
        onValueChange(landed)
        // A tick names its cause: bumped by a LANDING, never the value (a typed number is no detent).
        haptics.performHapticFeedback(HapticFeedbackType.SegmentTick)
    }

    // The pointer coroutine outlives value changes, so read the latest callback without restarting.
    val currentLand by rememberUpdatedState<(Float) -> Unit> { x -> land(x) }

    Column(
        modifier
            .fillMaxWidth()
            .semantics {
                contentDescription = label
                stateDescription =
                    if (isUnset) L10n.tr("Not set") else L10n.tr("%s %s", format(value), spokenUnit)
                if (values.isNotEmpty()) {
                    val index = DialLadder.nearestIndex(value, values).toFloat()
                    progressBarRangeInfo = ProgressBarRangeInfo(
                        current = index,
                        range = 0f..(values.size - 1).toFloat(),
                        steps = maxOf(0, values.size - 2),
                    )
                    // ONE adjustable element, stepping the same detents the drag lands on.
                    setProgress { target ->
                        val next = target.toInt().coerceIn(0, values.size - 1)
                        onValueChange(values[next])
                        true
                    }
                }
            },
        verticalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        Canvas(
            Modifier
                .fillMaxWidth()
                // The drawing is 30 dp (taller reads as a fence), but the live strip clears the 44 dp floor,
                // like `BandTrimmer`. A hit area LARGER than the drawing is the safe direction.
                .height(HIT_HEIGHT)
                // Measured here: a state write during drawing schedules another draw, a loop.
                .onSizeChanged { width = it.width.toFloat() }
                .clearAndSetSemantics {}
                // Absolute tracking: on a short ladder the value belongs under the finger.
                .pointerInput(values, isUnset) {
                    detectTapGestures { offset -> currentLand(offset.x) }
                }
                // **THE AXIS GATE.** `draggable(Horizontal)` claims the pointer only past the HORIZONTAL
                // touch slop, so a vertical swipe starting here scrolls the page and the dial never sees
                // it. (iOS needs `gestureRecognizerShouldBegin`: `minimumDistance` is not a direction.)
                .draggable(
                    state = rememberDraggableState { delta ->
                        dragX += delta
                        land(dragX)
                    },
                    orientation = Orientation.Horizontal,
                    onDragStarted = { start ->
                        dragX = start.x
                        land(dragX)
                    },
                ),
        ) {
            val centreY = size.height / 2f

            drawRoundRect(
                color = palette.inkTertiary.copy(alpha = 0.16f),
                topLeft = Offset(0f, centreY - 1.dp.toPx()),
                size = Size(size.width, 2.dp.toPx()),
                cornerRadius = CornerRadius(1.dp.toPx()),
            )

            values.indices.forEach { index ->
                // Only when EXACTLY here; off the ladder the hollow mark reads instead. A highlighted 20
                // under a face reading 22 is the control disagreeing with itself.
                val isCurrent = mark == DialLadder.RenderingMark.Detent(index)
                val w = if (isCurrent) 4.dp.toPx() else 2.dp.toPx()
                val h = if (isCurrent) TRACK_HEIGHT.toPx() else 11.dp.toPx()
                val x = DialLadder.x(index, size.width, values.size)
                drawRoundRect(
                    color = if (isCurrent) palette.graphite else palette.inkTertiary.copy(alpha = 0.5f),
                    topLeft = Offset(x - w / 2f, centreY - h / 2f),
                    size = Size(w, h),
                    cornerRadius = CornerRadius(w / 2f),
                )
            }

            // An off-ladder typed value, drawn WHERE IT FALLS between its neighbours. Hollow: "here,
            // but not a stop" — the next drag lands on a detent.
            if (mark == DialLadder.RenderingMark.OffLadder) {
                val x = DialLadder.offLadderX(value, values, size.width)
                if (x != null) {
                    val w = 6.dp.toPx()
                    val h = TRACK_HEIGHT.toPx()
                    drawRoundRect(
                        color = palette.graphite,
                        topLeft = Offset(x - w / 2f, centreY - h / 2f),
                        size = Size(w, h),
                        cornerRadius = CornerRadius(w / 2f),
                        style = Stroke(width = 1.5.dp.toPx()),
                    )
                }
            }
        }

        // The ladder, STATED: every value it can produce is readable without touching it.
        Row(Modifier.fillMaxWidth().clearAndSetSemantics {}) {
            values.forEachIndexed { index, ladderValue ->
                val isCurrent = mark == DialLadder.RenderingMark.Detent(index)
                Text(
                    format(ladderValue),
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = if (isCurrent) FontWeight.SemiBold else FontWeight.Normal,
                    color = if (isCurrent) palette.inkPrimary else palette.inkTertiary,
                    maxLines = 1,
                    textAlign = TextAlign.Center,
                )
            }
        }
    }
}

/// What the ticks draw into.
private val TRACK_HEIGHT = 30.dp

/// What the finger gets — see the note on the Canvas.
private val HIT_HEIGHT = 44.dp

/// **The dial's arithmetic, with no Canvas**, so drawing and landing are JVM-testable. The
/// detent highlight, the bold label and the off-ladder mark all read one decision (as iOS's
/// `renderingMark`), so the test covers the real rendering path.
object DialLadder {

    sealed interface RenderingMark {
        data class Detent(val index: Int) : RenderingMark
        data object OffLadder : RenderingMark
    }

    /// The detent the value sits exactly on, or null when it falls between two.
    fun exactIndex(value: Double, values: List<Double>): Int? =
        values.indexOfFirst { abs(it - value) < 0.001 }.takeIf { it >= 0 }

    /// **The single decision about what this dial draws.** The marks are exclusive and BOTH vanish
    /// while unset: a sentinel below the ladder would park on stop one via `offLadderX` — an answer.
    fun renderingMark(value: Double, values: List<Double>, isUnset: Boolean): RenderingMark? {
        if (isUnset) return null
        exactIndex(value, values)?.let { return RenderingMark.Detent(it) }
        return if (values.size > 1) RenderingMark.OffLadder else null
    }

    /// Nearest, for the accessibility stepper and for deciding which way an adjust moves.
    /// Never for DRAWING — see `exactIndex`.
    fun nearestIndex(value: Double, values: List<Double>): Int {
        if (values.isEmpty()) return 0
        var best = 0
        values.indices.forEach { index ->
            if (abs(values[index] - value) < abs(values[best] - value)) best = index
        }
        return best
    }

    fun slotWidth(width: Float, count: Int): Float = if (count <= 0) width else width / count

    fun x(index: Int, width: Float, count: Int): Float =
        (index + 0.5f) * slotWidth(width, count)

    /// Where an off-ladder value falls between its neighbours. null when it IS a detent or lies
    /// outside the ladder, where the mark parks on the end stop.
    fun offLadderX(value: Double, values: List<Double>, width: Float): Float? {
        if (width <= 0f || values.isEmpty()) return null
        val upper = values.indexOfFirst { it > value }
        if (upper < 0) return x(values.size - 1, width, values.size)
        if (upper == 0) return x(0, width, values.size)
        val lo = values[upper - 1]
        val hi = values[upper]
        val t = ((value - lo) / (hi - lo)).toFloat()
        return x(upper - 1, width, values.size) + t * slotWidth(width, values.size)
    }

    /// Which detent a touch at `x` belongs to. The whole strip is live: the gap between two 2 dp
    /// bars is the most natural place to aim.
    fun landIndex(x: Float, width: Float, count: Int): Int {
        if (count <= 0) return 0
        val slot = floor(x / slotWidth(width, count)).toInt()
        return slot.coerceIn(0, count - 1)
    }
}

@Preview(name = "DialTrack", showBackground = true, widthDp = 360)
@Composable
private fun DialTrackPreview() {
    GetAGripTheme {
        var hold by remember { androidx.compose.runtime.mutableStateOf(10.0) }
        Column(
            Modifier.padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(24.dp),
            horizontalAlignment = Alignment.Start,
        ) {
            DialTrack(
                value = hold,
                values = listOf(3.0, 5.0, 7.0, 10.0, 12.0, 15.0, 20.0, 30.0),
                format = { it.toInt().toString() },
                spokenUnit = tr("seconds"),
                label = tr("Hold"),
            ) { hold = it }
            DialTrack(
                value = 22.0,
                values = listOf(6.0, 10.0, 15.0, 20.0, 25.0, 30.0, 35.0, 45.0),
                format = { it.toInt().toString() },
                spokenUnit = tr("millimetres"),
                label = tr("Edge"),
            ) {}
        }
    }
}
