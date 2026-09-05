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
/// The control this replaces was a slider with four preset chips underneath, and it was
/// the wrong control twice over. Nielsen Norman is unusually blunt about the first: use a
/// slider only when the precise value will not matter and only the approximate range will.
/// Every number in a routine is exact — a 3 s hold, 4 pulls, a 5 s rest — which is why
/// each row needed chips bolted underneath to reach the values people actually use.
///
/// The second is arithmetic. A hold slider spanning 3…45 in steps of five lands on 3, 8,
/// 13, 18 — so 5, 7, 10 and 12 were unreachable by dragging and existed only as chips, and
/// four chips hold four numbers. The C4 protocol wants a 3 s hold, a 5 s rest and 4 pulls;
/// not one of those was a chip.
///
/// **The dial's positions are the ladder's INDICES, not the value's magnitude.** Detents
/// are evenly spaced whatever the numbers are, so 3 and 30 are equally easy to hit, and the
/// scale underneath states every value the control can produce. Tap-to-type stays on the
/// row above and is not optional — Zwift's editor is drag-only and its own power users
/// hand-edit exported XML to reach values the GUI cannot express.
@Composable
fun DialTrack(
    value: Double,
    /// Ascending, and FIXED — the same stops whatever `value` currently is.
    ///
    /// The caller used to splice the current value in so a typed number "kept its own
    /// detent". Because positions come from the INDEX, that re-spaced every other stop the
    /// moment you typed, and re-spaced them again on the first drag (Nuri, 2026-08-11:
    /// "every time you type in a manual number it changes the scale of the bar, but then
    /// when you drag the bar it changes again"). A ruler whose marks move is not a ruler.
    /// The scale is immovable and an off-ladder value gets `offLadderMark` instead.
    values: List<Double>,
    format: (Double) -> String,
    spokenUnit: String,
    label: String,
    modifier: Modifier = Modifier,
    /// An unanswered scale is a real state, not a value below the ladder. The caller still
    /// supplies a value for the drag to write into, but this flag keeps that temporary
    /// number from drawing a misleading detent or off-ladder mark.
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
        // Fires the per-detent click. A tick has to name its cause, so it is bumped by a
        // LANDING and never by the value — a typed number is not a detent.
        haptics.performHapticFeedback(HapticFeedbackType.SegmentTick)
    }

    // A pointer coroutine survives value changes. Read the latest landing callback
    // without restarting the gesture or comparing against its first displayed value.
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
                    // ONE adjustable element rather than a mute picture, stepping the same
                    // detents the drag lands on.
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
                // What the finger gets. The drawing is 30 dp and that is right — a taller
                // tick reads as a fence — but the live strip must clear the 44 dp floor,
                // exactly as `BandTrimmer` does. Hit area LARGER than the drawing is the
                // safe direction; the reverse is the "four points is exactly the kind of
                // miss that reads as the tap didn't register" bug already paid for once.
                .height(HIT_HEIGHT)
                // Measured here, never inside the draw scope: a state write during
                // drawing schedules another draw, which is a loop.
                .onSizeChanged { width = it.width.toFloat() }
                .clearAndSetSemantics {}
                // Absolute tracking, not translation: on a ladder this short the value
                // belongs under the finger, and there is no offset worth preserving.
                .pointerInput(values, isUnset) {
                    detectTapGestures { offset -> currentLand(offset.x) }
                }
                // **THE AXIS GATE.** A full-width drag strip can only live inside a
                // vertical scroll if the two gestures are on different axes and the
                // horizontal one refuses to begin on a vertical move. Compose expresses
                // that natively: `draggable(Horizontal)` claims the pointer only once the
                // HORIZONTAL touch slop is crossed, so a vertical swipe starting on the
                // dial reaches the page and scrolls it, and the dial never sees it. (iOS
                // needs a UIKit recognizer with `gestureRecognizerShouldBegin` for the
                // same rule — `DragGesture.minimumDistance` is a distance, not a
                // direction.)
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
                // Only when the value is EXACTLY here. Off the ladder, no detent is
                // "current" and the hollow mark carries the reading instead — a
                // highlighted 20 while the face says 22 is the control disagreeing with
                // the number above it.
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

            // A typed value that is not on the ladder, drawn WHERE IT ACTUALLY FALLS —
            // between its two neighbouring stops, proportionally. Hollow rather than solid
            // so it reads as "here, but not a stop": the next drag will land on a detent,
            // and the mark should say so before it happens rather than after.
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

        // The ladder, STATED. It is what makes the control quotable — you can read every
        // value it will produce without touching it, which a slider never allowed.
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

/// **The dial's arithmetic, with no Canvas in it** — so what it draws, and where a drag
/// lands, can be asserted in a JVM test rather than from a screenshot.
///
/// Lifted out for exactly the reason iOS lifted `renderingMark` out of its view body: the
/// detent highlight, the scale's bold label and the off-ladder mark all read one decision,
/// so a test on it is a test of the real rendering path rather than a parallel copy of the
/// rule that could pass while the drawing regressed.
object DialLadder {

    sealed interface RenderingMark {
        data class Detent(val index: Int) : RenderingMark
        data object OffLadder : RenderingMark
    }

    /// The detent the value sits exactly on, or null when it falls between two.
    fun exactIndex(value: Double, values: List<Double>): Int? =
        values.indexOfFirst { abs(it - value) < 0.001 }.takeIf { it >= 0 }

    /// **The single decision about what this dial draws.** The two marks are mutually
    /// exclusive, and BOTH disappear while the answer is unset — the trap being that the
    /// obvious implementation of "unset" is a sentinel below the ladder, and `offLadderX`
    /// parks such a value on the FIRST stop, so an untouched dial would draw a mark sitting
    /// on stop one, which is an answer.
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

    /// Where an off-ladder value falls, interpolated between its neighbours so the mark
    /// lands in the gap it belongs to. null when the value IS a detent, or sits outside the
    /// ladder entirely — past either end there is no gap to interpolate into, so the mark
    /// parks on the end stop rather than floating off the track.
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

    /// Which detent a touch at `x` belongs to. The whole strip is live, not just the ticks:
    /// a 2 dp bar is not a target, and the gap between two of them is the most natural
    /// place to aim.
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
