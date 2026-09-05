// SPDX-License-Identifier: MPL-2.0
// Original contributions Copyright 2026 Nuri Bruner.

package run.nuri.getagrip.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import run.nuri.getagrip.engine.HandMode
import run.nuri.getagrip.engine.L10n
import run.nuri.getagrip.engine.PlanMath
import run.nuri.getagrip.engine.SessionPlan
import run.nuri.getagrip.engine.SetPlan
import run.nuri.getagrip.engine.Side
import run.nuri.getagrip.ui.theme.GetAGripTheme
import run.nuri.getagrip.ui.theme.LocalGripPalette

/// The real L R L R sequence the Hands choice produces for the first set.
///
/// Fill-vs-outline, never two hues: it has to survive Reduce Transparency and
/// colourblindness, and a legend explaining which colour is which hand would be a legend
/// for a control that exists to remove one.
@Composable
fun HandOrderStrip(
    mode: HandMode,
    repsPerSide: Int,
    modifier: Modifier = Modifier,
) {
    val palette = LocalGripPalette.current

    // A throwaway `SetPlan` so the count comes out of `PlanMath.handSequence` — the single
    // ×2 resolver. Multiplying `repsPerSide` by `sideCount` here instead is exactly the
    // shortcut that makes a routine twice as long as its own summary claims.
    val sides = PlanMath.handSequence(
        SetPlan(repsPerSide = maxOf(0, repsPerSide)),
        SessionPlan(handMode = mode),
    )
    val sentence = sentence(mode, sides.size, maxOf(0, repsPerSide))

    // ONE element with one spoken sentence: twelve focusable capsules is twelve swipes to
    // learn something a sentence says once.
    Box(modifier.semantics(mergeDescendants = true) { contentDescription = sentence }) {
        // Past twelve pulls a row of capsules stops being countable at a glance and becomes
        // a texture, so the sentence takes over.
        if (sides.isEmpty() || sides.size > 12) {
            Sentence(sentence)
        } else {
            BoxWithConstraints {
                // TRANSLATION NOTE: iOS wraps this in `ViewThatFits`, which measures the
                // strip and falls back to the sentence when it overflows. Compose has no
                // such container, so the same decision is made from the measured width —
                // and the count cases above stay separate, because an absent candidate
                // measuring zero would "fit" and draw nothing at all for an empty set.
                val needed = sides.sumOf { width(it).value.toDouble() } +
                    (sides.size - 1) * GAP.value
                if (needed <= maxWidth.value) {
                    Row(horizontalArrangement = Arrangement.spacedBy(GAP)) {
                        sides.forEach { side ->
                            val shape = RoundedCornerShape(CAPSULE / 2)
                            Box(
                                Modifier
                                    .width(width(side))
                                    .height(CAPSULE * 2)
                                    .then(
                                        if (side == Side.right) {
                                            Modifier.border(1.5.dp, palette.inkTertiary.copy(alpha = 0.6f), shape)
                                        } else {
                                            Modifier.background(palette.graphite, shape)
                                        },
                                    )
                                    .clearAndSetSemantics {},
                            )
                        }
                    }
                } else {
                    Sentence(sentence)
                }
            }
        }
    }
}

@Composable
private fun Sentence(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodySmall,
        color = LocalGripPalette.current.inkSecondary,
        modifier = Modifier.clearAndSetSemantics {},
    )
}

private val CAPSULE = 9.dp
private val GAP = 5.dp

/// One pull with two hands on the edge is ONE event, drawn wide rather than as a left and
/// a right side by side — which would read as two pulls.
private fun width(side: Side) = if (side == Side.both) CAPSULE * 2.2f else CAPSULE

private fun sentence(mode: HandMode, count: Int, perSide: Int): String = when (mode) {
    HandMode.alternateEachRep -> L10n.tr("Left, right, left, right — %s", pulls(count))
    HandMode.alternateEachSet ->
        L10n.tr("All %d on the left, then all %d on the right", perSide, perSide)
    HandMode.bothHands -> L10n.tr("One pull, both hands — %s", pulls(count))
}

/// A one-pull set would otherwise be spoken "1 pulls" in the one mode where that count can
/// be odd.
private fun pulls(n: Int) = L10n.tr("%d %s", n, L10n.tr(if (n == 1) "pull" else "pulls"))

@Preview(name = "HandOrderStrip", showBackground = true, widthDp = 360)
@Composable
private fun HandOrderStripPreview() {
    GetAGripTheme {
        androidx.compose.foundation.layout.Column(
            Modifier.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            HandOrderStrip(HandMode.alternateEachRep, 6)
            HandOrderStrip(HandMode.alternateEachSet, 6)
            HandOrderStrip(HandMode.bothHands, 4)
        }
    }
}
