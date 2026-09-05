// SPDX-License-Identifier: MPL-2.0
// Original contributions Copyright 2026 Nuri Bruner.

package run.nuri.getagrip.ui.components

import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/// A CLIMB day, marked by a diagonal notch punched clean through the fill.
///
/// Shape, not colour. The house rule on both calendars is that every state survives
/// greyscale, Reduce Transparency and colourblindness, which a tone-only difference does
/// not — and in History's grid the obvious accent is already spoken for, since `bleu` rings
/// today. (Nuri asked for "a different colour, dashed or something"; a punched slash is the
/// version that still reads at twelve points.)
///
/// The fill stays GRAPHITE and stays full: a climb completes the day, and a lighter or
/// partial-looking cell would contradict the sentence on Today. Identical glyph in the
/// 12 dp strip and the 28 dp grid, so the vocabulary is learned once.
///
/// **A real HOLE, not a stroke in the backdrop's colour.** These cells sit on a card over
/// the slate field, so nothing can be painted to match what is behind them.
///
/// TRANSLATION NOTE: SwiftUI spells this `.blendMode(.destinationOut)` on an overlay plus
/// `.compositingGroup()`. The Compose twin is `BlendMode.Clear` inside a layer forced
/// offscreen — without `CompositingStrategy.Offscreen` the clear blends against the window
/// rather than against this node's own drawing, which punches a hole through the CARD as
/// well as the cell.
fun Modifier.climbNotch(show: Boolean): Modifier =
    if (!show) this else this
        // Not free, so it is applied only when there is a notch to punch — the same
        // contract iOS's `@ViewBuilder` wrapper has.
        .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
        .drawWithContent {
            drawContent()
            rotate(degrees = 45f) {
                drawRect(
                    color = Color.Black,
                    // Overscaled before rotating so the slash still spans the CORNERS of a
                    // square cell rather than stopping short of them.
                    topLeft = Offset(
                        x = (size.width - NOTCH_WIDTH.toPx()) / 2f,
                        y = -size.height * (NOTCH_OVERSCALE - 1f) / 2f,
                    ),
                    size = Size(NOTCH_WIDTH.toPx(), size.height * NOTCH_OVERSCALE),
                    // The colour is irrelevant: Clear writes transparency, it does not
                    // paint. What it clears is whatever `drawContent()` just put down.
                    blendMode = BlendMode.Clear,
                )
            }
        }

/// A BENCHMARK day, marked by a round bore punched through the centre of the fill — the
/// calendars' third glyph, beside the plain fill (hangs) and the slash (climbs).
///
/// A hole, for the same reason the notch is one. Round rather than another slash because it
/// should read as a different KIND of mark at twelve points, not a different angle of the
/// same one — the gauge's point against the climb's stroke. Shape carries the meaning; the
/// bleu fill the calendars pair it with is the glance, never the message.
///
/// `size` is passed in because the strip's 12 dp dots and the grid's 28 dp cells cannot
/// share a constant.
fun Modifier.benchmarkBore(show: Boolean, size: Dp): Modifier =
    if (!show) this else this
        .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
        .drawWithContent {
            drawContent()
            drawCircle(
                color = Color.Black,
                radius = size.toPx() / 2f,
                center = center,
                blendMode = BlendMode.Clear,
            )
        }

private val NOTCH_WIDTH = 2.5.dp

/// iOS scales the slash 1.8× in y before rotating it; the same factor, for the same reason.
private const val NOTCH_OVERSCALE = 1.8f
