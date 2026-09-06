// SPDX-License-Identifier: MPL-2.0
// Original contributions Copyright 2026 Nuri Bruner.

package run.nuri.getagrip.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import kotlin.math.abs
import kotlin.math.roundToInt
import run.nuri.getagrip.engine.L10n
import run.nuri.getagrip.ui.l10n.tr
import run.nuri.getagrip.ui.theme.GetAGripTheme
import run.nuri.getagrip.ui.theme.LocalGripPalette

/// A range set by DRAGGING A BAND — the clip-trimmer gesture everyone's hands already
/// know: grab the middle to slide the whole band, grab an end to stretch it.
///
/// Built because the previous custom entry was two steppers, and "80 to 90" cost a dozen
/// taps with no hold-to-repeat (Nuri, 2026-08-10: "a huge pain"). Here it is one drag with
/// a detent click at every step.
///
/// Values snap DURING the drag, never on release: what you see settle is what you get, and
/// each snap fires a selection click so the control counts for you. The numbers ride on the
/// band's face — quotable exactly, because the steps are the same 5 % / 0.5 kg resolution
/// the app rounds targets to anyway.
@Composable
fun BandTrimmer(
    lo: Double,
    hi: Double,
    /// The full scale the track represents.
    scale: ClosedFloatingPointRange<Double>,
    /// Snap resolution — 0.05 for percent, 0.5 for kilograms.
    step: Double,
    /// Renders a value for the band label and the scale's end captions.
    format: (Double) -> String,
    /// Spoken unit for the two adjustable accessibility elements.
    spokenUnit: String,
    modifier: Modifier = Modifier,
    onChange: (Double, Double) -> Unit,
) {
    val palette = LocalGripPalette.current
    val haptics = LocalHapticFeedback.current
    val density = LocalDensity.current
    /// ≥44 dp of live edge each side; the visible handle bars are just the affordance.
    val edgeGrabPx = with(density) { 44.dp.toPx() }

    var width by remember { mutableFloatStateOf(0f) }
    /// Which part of the band the current drag owns — decided ONCE at first touch and
    /// held, so a finger that drifts across an edge mid-drag cannot switch jobs.
    var grab by remember { mutableStateOf<Grab?>(null) }
    var startLo by remember { mutableStateOf(0.0) }
    var startHi by remember { mutableStateOf(0.0) }
    var travelled by remember { mutableFloatStateOf(0f) }

    val span = (scale.endInclusive - scale.start).takeIf { it > 0 } ?: 1.0

    fun snap(value: Double) = (value / step).roundToInt() * step

    fun apply(newLo: Double, newHi: Double) {
        if (newLo == lo && newHi == hi) return
        onChange(newLo, newHi)
        haptics.performHapticFeedback(HapticFeedbackType.SegmentTick)
    }

    // Typed bands may be narrower than one drag step, including at either scale edge.
    fun setLo(value: Double) {
        apply(snap(value).coerceIn(scale.start, maxOf(scale.start, hi - step)), hi)
    }

    fun setHi(value: Double) {
        apply(lo, snap(value).coerceIn(minOf(lo + step, scale.endInclusive), scale.endInclusive))
    }

    fun fraction(value: Double) = ((value - scale.start) / span).coerceIn(0.0, 1.0).toFloat()
    fun xOf(value: Double) = fraction(value) * width

    val tapAt by rememberUpdatedState<(Float) -> Unit>({ x ->
        if (width > 0f) {
            val value = scale.start + (x / width) * span
            if (value < lo || value > hi) {
                if (abs(value - lo) < abs(value - hi)) setLo(value) else setHi(value)
            }
        }
    })

    /// Whether the band can carry its own numbers. Fraction-of-scale, not pixels — the
    /// decision has to be stable across widths without a geometry read outside the track.
    val narrow = (hi - lo) / span < 0.28

    Column(
        modifier
            .fillMaxWidth()
            .semantics(mergeDescendants = false) {
                // The group needs a NAME or TalkBack announces two bare "Lower bound" /
                // "Upper bound" stops with nothing saying what they bound.
                contentDescription = L10n.tr("Target range")
            },
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        // RESERVED line for the numbers when the band is too narrow to carry them itself —
        // floating the label above the track collided with whatever sat above the control.
        // Always present so the layout never jumps mid-drag.
        Text(
            if (narrow) "${format(lo)}–${format(hi)}" else " ",
            style = MaterialTheme.typography.bodySmall,
            fontWeight = FontWeight.SemiBold,
            color = palette.inkPrimary,
            modifier = Modifier.fillMaxWidth().clearAndSetSemantics {},
        )

        BoxWithConstraints(Modifier.fillMaxWidth().height(TRACK_HEIGHT)) {
            val trackWidth = maxWidth
            Canvas(
                Modifier
                    .fillMaxWidth()
                    .height(TRACK_HEIGHT)
                    .onSizeChanged { width = it.width.toFloat() }
                    .clearAndSetSemantics {}
                    // A tap moves THE NEARER EDGE to the tapped value — what a tap on a
                    // trimmer means. Taps inside the band do nothing; the band is moved by
                    // dragging it.
                    .pointerInput(scale, step) {
                        detectTapGestures { offset -> tapAt(offset.x) }
                    }
                    // See `DialTrack` for why this is `draggable(Horizontal)`: the axis gate
                    // is what lets a full-width drag strip live inside a vertical scroll.
                    // TRANSLATION rather than absolute position, because a band grabbed by
                    // its middle has to keep the offset it was grabbed at — jumping the
                    // band's centre under the finger would move it on touch-down.
                    .draggable(
                        state = rememberDraggableState { delta ->
                            travelled += delta
                            if (width <= 0f) return@rememberDraggableState
                            val moved = (travelled / width) * span
                            when (grab) {
                                Grab.Lower -> setLo(startLo + moved)
                                Grab.Upper -> setHi(startHi + moved)
                                Grab.Whole -> {
                                    // The band keeps its width against BOTH walls: sliding
                                    // into an end compresses nothing and loses nothing.
                                    val bandWidth = startHi - startLo
                                    val newLo = (startLo + moved)
                                        .coerceIn(scale.start, scale.endInclusive - bandWidth)
                                    val snapped = snap(newLo).coerceIn(scale.start, scale.endInclusive - bandWidth)
                                    apply(snapped, snapped + bandWidth)
                                }
                                null -> Unit
                            }
                        },
                        orientation = Orientation.Horizontal,
                        onDragStarted = { start ->
                            travelled = 0f
                            startLo = lo
                            startHi = hi
                            grab = grabTarget(start.x, xOf(lo), xOf(hi), edgeGrabPx)
                        },
                        onDragStopped = { grab = null },
                    ),
            ) {
                val centreY = size.height / 2f
                val loX = fraction(lo) * size.width
                val hiX = fraction(hi) * size.width

                // The full scale, quiet.
                drawRoundRect(
                    color = palette.inkTertiary.copy(alpha = 0.16f),
                    topLeft = Offset(0f, centreY - 5.dp.toPx()),
                    size = Size(size.width, 10.dp.toPx()),
                    cornerRadius = CornerRadius(5.dp.toPx()),
                )

                // The band. Graphite, not bleu — this is an instruction being AUTHORED,
                // not a measurement being taken.
                val bandWidth = maxOf(hiX - loX, 24.dp.toPx())
                val bandHeight = TRACK_HEIGHT.toPx() - 12.dp.toPx()
                drawRoundRect(
                    color = palette.graphite.copy(alpha = 0.85f),
                    topLeft = Offset(loX, centreY - bandHeight / 2f),
                    size = Size(bandWidth, bandHeight),
                    cornerRadius = CornerRadius(10.dp.toPx()),
                )

                // The two grab bars, drawn INSIDE the ends — the trimmer affordance,
                // readable at any band width.
                listOf(loX + 5.dp.toPx(), loX + bandWidth - 8.dp.toPx()).forEach { x ->
                    drawRoundRect(
                        color = Color.White.copy(alpha = 0.55f),
                        topLeft = Offset(x, centreY - 7.dp.toPx()),
                        size = Size(3.dp.toPx(), 14.dp.toPx()),
                        cornerRadius = CornerRadius(1.5.dp.toPx()),
                    )
                }
            }

            // Rides the band while it fits; the reserved line above carries it once the
            // band is too narrow.
            if (!narrow) {
                Text(
                    "${format(lo)}–${format(hi)}",
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = FontWeight.SemiBold,
                    color = palette.graphiteInverse,
                    maxLines = 1,
                    modifier = Modifier
                        .align(Alignment.CenterStart)
                        // Centred on the band it rides, measured against the REAL track
                        // width rather than a nominal one.
                        .offset(x = bandLabelOffset(lo, hi, scale, trackWidth))
                        .clearAndSetSemantics {},
                )
            }

            // TWO INDEPENDENTLY ADJUSTABLE ELEMENTS, one per handle — because the control's
            // whole purpose is choosing a RANGE, and a single combined element could only
            // ever slide the band by applying one delta to both ends, never widen or narrow
            // it. Semantics-only nodes: they take no hits, so tap-to-jump and grab-by-handle
            // are untouched (which is exactly the regression iOS had to undo by hand).
            HandleProxy(tr("Lower bound"), format(lo), spokenUnit, lo, scale, step) { setLo(it) }
            HandleProxy(tr("Upper bound"), format(hi), spokenUnit, hi, scale, step) { setHi(it) }
        }

        // The scale's ends, so the band has a ruler to be read against.
        Row(
            Modifier.fillMaxWidth().clearAndSetSemantics {},
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(format(scale.start), style = MaterialTheme.typography.labelSmall, color = palette.inkTertiary)
            Text(format(scale.endInclusive), style = MaterialTheme.typography.labelSmall, color = palette.inkTertiary)
        }
    }
}

/// One handle's accessibility proxy — a zero-size node carrying a value and an adjust.
@Composable
private fun HandleProxy(
    label: String,
    value: String,
    spokenUnit: String,
    current: Double,
    scale: ClosedFloatingPointRange<Double>,
    step: Double,
    onSet: (Double) -> Unit,
) {
    Box(
        Modifier
            // 44 dp, not 1: these carry NO pointer input (the trimmer's own drag owns the
            // track) but they ARE TalkBack focus targets, and a 1 dp focus rectangle is
            // unreachable by touch exploration and invisible when swiped to. Sized to the
            // house floor so the focus ring lands on something.
            .size(44.dp)
            .semantics {
                contentDescription = label
                stateDescription = L10n.tr("%s %s", value, spokenUnit)
                progressBarRangeInfo = ProgressBarRangeInfo(
                    current = current.toFloat(),
                    range = scale.start.toFloat()..scale.endInclusive.toFloat(),
                )
                setProgress { target ->
                    onSet(target.toDouble())
                    true
                }
            },
    )
}

private val TRACK_HEIGHT = 44.dp

/// Where the band's own label sits: centred on the band, clamped inside the track. It is a
/// label riding a shape, so the half-width it backs off by is a nominal one — the text is
/// short ("20–30 %", "8.0–12.0 kg") and centring it exactly would cost a measurement pass.
internal fun bandLabelOffset(
    lo: Double,
    hi: Double,
    scale: ClosedFloatingPointRange<Double>,
    trackWidth: androidx.compose.ui.unit.Dp,
): androidx.compose.ui.unit.Dp {
    val span = (scale.endInclusive - scale.start).takeIf { it > 0 } ?: 1.0
    val start = ((lo - scale.start) / span).coerceIn(0.0, 1.0)
    val end = ((hi - scale.start) / span).coerceIn(0.0, 1.0)
    val centre = ((start + end) / 2.0) * trackWidth.value
    return (centre - 34.0).coerceIn(6.0, maxOf(6.0, trackWidth.value - 74.0)).dp
}

internal enum class Grab { Lower, Upper, Whole }

/// Edges win within their grab zone; the body wins between them; a touch on bare track
/// takes THE NEARER EDGE, which is what a grab on a trimmer means.
internal fun grabTarget(startX: Float, loX: Float, hiX: Float, edgeWidth: Float): Grab {
    val nearLower = abs(startX - loX) <= edgeWidth / 2
    val nearUpper = abs(startX - hiX) <= edgeWidth / 2
    return when {
        // Overlapping zones on a narrow band: the side of centre decides.
        nearLower && nearUpper -> if (startX < (loX + hiX) / 2) Grab.Lower else Grab.Upper
        nearLower -> Grab.Lower
        nearUpper -> Grab.Upper
        startX > loX && startX < hiX -> Grab.Whole
        else -> if (abs(startX - loX) < abs(startX - hiX)) Grab.Lower else Grab.Upper
    }
}

@Preview(name = "BandTrimmer", showBackground = true, widthDp = 360)
@Composable
private fun BandTrimmerPreview() {
    GetAGripTheme {
        var lo by remember { mutableStateOf(0.80) }
        var hi by remember { mutableStateOf(0.90) }
        Column(Modifier.padding(24.dp)) {
            BandTrimmer(
                lo = lo,
                hi = hi,
                scale = 0.05..1.0,
                step = 0.05,
                format = { L10n.tr("%d %%", (it * 100).roundToInt()) },
                spokenUnit = tr("percent of max"),
            ) { newLo, newHi -> lo = newLo; hi = newHi }
        }
    }
}
