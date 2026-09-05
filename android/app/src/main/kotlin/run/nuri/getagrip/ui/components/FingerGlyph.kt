// SPDX-License-Identifier: MPL-2.0
// Original contributions Copyright 2026 Nuri Bruner.

package run.nuri.getagrip.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import run.nuri.getagrip.engine.FingerSet
import run.nuri.getagrip.engine.GripPosition
import run.nuri.getagrip.ui.theme.GetAGripTheme
import run.nuri.getagrip.ui.theme.LocalGripPalette

/// A grip drawn as four BARS — index to little, filled when that finger is on the edge and
/// hairline when it is not.
///
/// Bars, not dots, and that is the whole point. As squares with a full corner radius these
/// rendered as literal CIRCLES at open hand and drag — indistinguishable from the session
/// dots one row above them and the consistency strip below, so a card that meant three
/// different things looked like circles all the way down. A finger is taller than it is
/// wide; drawing it that way makes the collision impossible rather than merely unlikely, and
/// it matches the app icon, which is this same mark.
///
/// **ONE GLYPH, ONE RULE: full capsule, 22 : 38.** The radius is half the width, byte for
/// byte the same rule `PalmHand` and `HandMark` use, so the runner's hand, a History row and
/// a notification are recognisably one drawing. The closure-driven radius iOS once had is
/// GONE: half a radius is a subtler distinction than "same mark or not", and the position is
/// spoken in words everywhere the glyph appears.
///
/// TRANSLATION NOTE: iOS declares `dot`/`gap` as `@ScaledMetric` so the glyph grows with the
/// text beside it. Compose has no environment-scaled Dp; callers pass a plain size and the
/// system's font scale reaches the words next to it but not this. That is a real, small
/// difference from iOS — noted rather than faked, because scaling a drawing by
/// `LocalDensity.fontScale` would grow it in places iOS does not.
@Composable
fun FingerGlyph(
    fingers: FingerSet,
    modifier: Modifier = Modifier,
    position: GripPosition = GripPosition.halfCrimp,
    dot: Dp = 5.5.dp,
    gap: Dp = 3.dp,
    tint: Color = LocalGripPalette.current.graphite,
) {
    val palette = LocalGripPalette.current
    // Fingers are taller than they are wide — the ratio is what stops a bar ever reading as
    // a dot, at any size.
    val barHeight = dot * BAR_RATIO
    val radius = dot / 2

    Column(
        modifier = modifier.clearAndSetSemantics {},
        verticalArrangement = Arrangement.spacedBy(maxOf(1.5.dp, gap * 0.6f)),
        horizontalAlignment = Alignment.Start,
    ) {
        // BOTTOM-aligned, so the varying lengths hang from a common knuckle line the way
        // fingers do — top-aligned they splay downward and read as a chart.
        Row(
            horizontalArrangement = Arrangement.spacedBy(gap),
            verticalAlignment = Alignment.Bottom,
        ) {
            fingers.occupied.forEachIndexed { index, on ->
                val shape = RoundedCornerShape(radius)
                Box(
                    Modifier
                        .width(dot)
                        .height(barHeight * LENGTH_FACTOR[index])
                        .then(
                            if (on) {
                                Modifier.background(tint, shape)
                            } else {
                                // Stroked rather than a lighter fill: fill-vs-outline survives
                                // greyscale, Reduce Transparency and colourblindness; a tint
                                // step does not. FULL tertiary ink, no opacity — a 1 dp
                                // hairline is nearly all antialiased edge, and which fingers
                                // are OFF is half the grip's meaning.
                                Modifier.border(1.dp, palette.inkTertiary, shape)
                            },
                        ),
                )
            }
        }
        if (fingers.hasThumb) {
            // The thumb bar: horizontal, under the index side, because that is where a thumb
            // sits when a hand pinches. It keeps the shape grammar — BARS are digits; this
            // one just lies down.
            Box(
                Modifier
                    .width(dot * 2 + gap)
                    .height(dot * 0.62f)
                    .background(tint, RoundedCornerShape(radius)),
            )
        }
    }
}

/// The proportion that makes a bar a finger rather than a dot.
private const val BAR_RATIO = 1.75f

/// A HAND's proportions — middle longest, little shortest — matching `PalmHand` and
/// `HandMark`. Flat bars read as a barcode; these read as a hand at a glance, which is what
/// makes the glyph work at 6 dp in a History row.
private val LENGTH_FACTOR = listOf(0.86f, 1.0f, 0.94f, 0.80f)

@Preview(name = "FingerGlyph", showBackground = true)
@Composable
private fun FingerGlyphPreview() {
    GetAGripTheme {
        Row(
            Modifier.size(240.dp, 60.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            FingerGlyph(FingerSet.four, dot = 14.dp, gap = 6.dp)
            FingerGlyph(FingerSet.frontTwo, dot = 14.dp, gap = 6.dp)
            FingerGlyph(FingerSet.backTwo, dot = 14.dp, gap = 6.dp)
            FingerGlyph(
                FingerSet.frontTwo.union(FingerSet.thumb),
                position = GripPosition.pinch,
                dot = 14.dp,
                gap = 6.dp,
            )
        }
    }
}
