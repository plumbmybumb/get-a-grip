// SPDX-License-Identifier: MPL-2.0
// Original contributions Copyright 2026 Nuri Bruner.

package run.nuri.getagrip.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import run.nuri.getagrip.engine.FingerSet
import run.nuri.getagrip.engine.GripPosition
import run.nuri.getagrip.engine.GripSpec
import run.nuri.getagrip.engine.Side
import run.nuri.getagrip.ui.theme.GetAGripTheme
import run.nuri.getagrip.ui.theme.LocalGripPalette

/// The hand as a pure drawing — **the one glyph a summary row and a notification both use.**
///
/// `FingerGlyph` is built out of layout (a Row of Boxes) and takes its outline colour from
/// the palette, which is right inside the app and wrong for a `RemoteViews`-shaped surface
/// that has to be handed a bitmap. This one is a single Canvas that takes its colour from
/// the caller and depends on nothing but the engine, so the same mark can be rasterised for
/// a Live Update notification when Phase 6 arrives.
///
/// Same proportions and the same capsule family as `FingerGlyph` and `PalmHand`, so all
/// three are recognisably one mark.
@Composable
fun HandMark(
    fingers: FingerSet,
    modifier: Modifier = Modifier,
    /// Facing a LEFT palm the thumb is on the right, and the index finger is therefore the
    /// RIGHTMOST bar. The whole hand mirrors — drawing only the thumb on the other side
    /// renders a front-2 grip on the little-finger side and reads as back-2.
    side: Side = Side.both,
    barWidth: Dp = 8.dp,
    tint: Color = LocalGripPalette.current.inkPrimary,
) {
    val gap = barWidth * 0.42f
    val barLength = barWidth * 1.75f
    val width = barWidth * 4 + gap * 3 + (if (fingers.hasThumb) barWidth * 1.9f else 0.dp)

    Canvas(modifier.size(width, barLength * 1.2f).clearAndSetSemantics {}) {
        val bar = barWidth.toPx()
        val gapPx = gap.toPx()
        val length = barLength.toPx()
        val radius = CornerRadius(bar / 2f)
        val mirrored = PalmGeometry.isMirrored(side)
        // The four bars sit at the LEADING edge when the thumb is on the trailing side, and
        // vice versa, so the thumb always has its own room and the hand never clips.
        val handWidth = bar * 4 + gapPx * 3
        val handLeft = if (mirrored) 0f else size.width - handWidth

        for (slot in 0 until 4) {
            val anatomical = PalmGeometry.anatomical(slot, side)
            val height = length * PalmGeometry.LENGTH_FACTOR[anatomical]
            val on = fingers.contains(FingerSet.allFingers[anatomical])
            drawRoundRect(
                color = tint.copy(alpha = if (on) tint.alpha else tint.alpha * OFF_ALPHA),
                topLeft = Offset(handLeft + slot * (bar + gapPx), length - height),
                size = Size(bar, height),
                cornerRadius = radius,
            )
        }

        if (!fingers.hasThumb) return@Canvas
        // The thumb sits on the same side it does on the palm, and only when the grip
        // actually calls for one.
        val thumbLength = bar * 1.6f
        val thickness = bar * 0.9f
        val pivotX = if (mirrored) handLeft + handWidth + bar * 0.2f else handLeft - bar * 0.2f
        val pivotY = length * 0.86f
        rotate(degrees = if (mirrored) THUMB_ANGLE else -THUMB_ANGLE, pivot = Offset(pivotX, pivotY)) {
            drawRoundRect(
                color = tint,
                topLeft = Offset(
                    if (mirrored) pivotX else pivotX - thumbLength,
                    pivotY - thickness / 2f,
                ),
                size = Size(thumbLength, thickness),
                cornerRadius = CornerRadius(thickness / 2f),
            )
        }
    }
}

/// A finger off the edge, at the mark's small sizes: a step of tint rather than an outline,
/// because a 1 dp stroke on an 8 dp bar is mostly antialiasing. `FingerGlyph` — which is
/// drawn larger and beside text — keeps the outline.
private const val OFF_ALPHA = 0.22f

/// Steeper than the palm's 26°, because this mark has no status bar to pass under and a
/// small drawing needs the angle to read at all.
private const val THUMB_ANGLE = 34f

@Preview(name = "HandMark", showBackground = true, widthDp = 220, heightDp = 60)
@Composable
private fun HandMarkPreview() {
    GetAGripTheme {
        androidx.compose.foundation.layout.Row(
            horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(18.dp),
        ) {
            HandMark(GripSpec().fingers, side = Side.left, barWidth = 10.dp)
            HandMark(FingerSet.frontTwo, side = Side.right, barWidth = 10.dp)
            HandMark(
                GripSpec(20, FingerSet.frontTwo, GripPosition.pinch).fingers,
                side = Side.left,
                barWidth = 10.dp,
            )
        }
    }
}
