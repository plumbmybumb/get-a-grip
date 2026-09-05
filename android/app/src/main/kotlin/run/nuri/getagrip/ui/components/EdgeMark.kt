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
import androidx.compose.foundation.layout.padding
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
import run.nuri.getagrip.engine.PlanMath
import run.nuri.getagrip.ui.theme.GetAGripTheme
import run.nuri.getagrip.ui.theme.GripPalette
import run.nuri.getagrip.ui.theme.LocalGripPalette

/// The intensity ladder's ONE mapping to colour, kept beside the mark that wears it.
///
/// Several surfaces draw the rung, and a routine changing colour between two of them would
/// be the mark contradicting itself — so the JUDGEMENT lives in `PlanMath.IntensityBand`
/// (where a test pins the boundaries) and the PAINT lives here, stated once.
///
/// TRANSLATION NOTE: iOS writes this as an extension on the enum, which can reach its
/// colour constants directly. Kotlin's palette is a composition local, so the mapping takes
/// one instead — same single statement, one argument longer.
fun PlanMath.IntensityBand.tint(palette: GripPalette): Color = when (this) {
    PlanMath.IntensityBand.unknown -> palette.bleu
    PlanMath.IntensityBand.light -> palette.moss
    PlanMath.IntensityBand.moderate -> palette.armed
    PlanMath.IntensityBand.nearMax -> palette.alarm
}

/// The routine's signature grip on a tinted edge — the card's mark.
///
/// It began as the app icon verbatim (all four bars, always) and Nuri's first reaction named
/// the flaw: every routine wore the same badge. So the mark draws the routine's OWN
/// signature grip — filled where a finger is on the edge, hairline where it is not, the
/// thumb as its horizontal bar — in exactly `FingerGlyph`'s vocabulary, so cards differ
/// precisely when routines differ.
///
/// The rung is the one place an accent appears at rest — the icon's own rule: one accent
/// element, the edge the force passes through. It doubles as the routine's INTENSITY signal
/// (Nuri, 2026-08-17), and colour is reinforcement rather than the sole carrier: the plan
/// row still states the percentage in words.
@Composable
fun EdgeMark(
    fingers: FingerSet = FingerSet.four,
    modifier: Modifier = Modifier,
    barWidth: Dp = 5.5.dp,
    rungTint: Color = LocalGripPalette.current.bleu,
) {
    val palette = LocalGripPalette.current
    val gap = barWidth * 0.55f
    // 2.2×, deliberately more elongated than `FingerGlyph`'s 1.75: at this mark's small
    // size the glyph's ratio rounds the bars into DOTS — and four filled dots one row above
    // the session dots is the "circles all the way down" collision the shape vocabulary
    // exists to prevent. Elongation is what keeps a bar a bar when the drawing shrinks.
    val tallest = barWidth * 2.2f
    val handWidth = barWidth * 4 + gap * 3

    Column(
        modifier.clearAndSetSemantics {},
        verticalArrangement = Arrangement.spacedBy(barWidth * 0.32f),
    ) {
        // Bottom-aligned: the varying lengths meet at a common fingertip line, because the
        // tips are what is ON the edge below.
        Row(
            horizontalArrangement = Arrangement.spacedBy(gap),
            verticalAlignment = Alignment.Bottom,
        ) {
            fingers.occupied.forEachIndexed { index, on ->
                val shape = RoundedCornerShape(barWidth / 2)
                Box(
                    Modifier
                        .width(barWidth)
                        .height(tallest * LENGTH_FACTOR[index])
                        .then(
                            if (on) {
                                Modifier.background(palette.graphite, shape)
                            } else {
                                // Stroked, not tinted lighter — fill-vs-outline survives
                                // greyscale, and full tertiary ink because a 1 dp hairline
                                // is nearly all antialiased edge.
                                Modifier.border(1.dp, palette.inkTertiary, shape)
                            },
                        ),
                )
            }
        }
        if (fingers.hasThumb) {
            // The thumb lies down under the index side, exactly as `FingerPips` and
            // `FingerGlyph` draw it — bars are digits; this one is horizontal.
            Box(
                Modifier
                    .width(barWidth * 2 + gap)
                    .height(barWidth * 0.62f)
                    .background(palette.graphite, RoundedCornerShape(barWidth * 0.31f)),
            )
        }
        Box(
            Modifier
                .width(handWidth)
                .height(barWidth * 0.72f)
                .background(rungTint, RoundedCornerShape(barWidth * 0.36f)),
        )
    }
}

/// A HAND's proportions — `FingerGlyph`'s array, verbatim.
private val LENGTH_FACTOR = listOf(0.86f, 1.0f, 0.94f, 0.80f)

@Preview(name = "EdgeMark", showBackground = true, widthDp = 320)
@Composable
private fun EdgeMarkPreview() {
    GetAGripTheme {
        val palette = LocalGripPalette.current
        Row(
            Modifier.padding(20.dp),
            horizontalArrangement = Arrangement.spacedBy(24.dp),
            verticalAlignment = Alignment.Bottom,
        ) {
            EdgeMark(FingerSet.four, barWidth = 10.dp)
            EdgeMark(
                FingerSet.frontTwo,
                barWidth = 10.dp,
                rungTint = PlanMath.IntensityBand.light.tint(palette),
            )
            EdgeMark(
                FingerSet.frontTwo.union(FingerSet.thumb),
                barWidth = 10.dp,
                rungTint = PlanMath.IntensityBand.nearMax.tint(palette),
            )
        }
    }
}
