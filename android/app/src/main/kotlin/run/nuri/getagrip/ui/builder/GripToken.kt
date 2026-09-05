// SPDX-License-Identifier: MPL-2.0
// Original contributions Copyright 2026 Nuri Bruner.

package run.nuri.getagrip.ui.builder

import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import run.nuri.getagrip.engine.FingerSet
import run.nuri.getagrip.engine.GripPosition
import run.nuri.getagrip.engine.GripSpec
import run.nuri.getagrip.engine.L10n
import run.nuri.getagrip.ui.components.FingerGlyph
import run.nuri.getagrip.ui.components.pressFeedback
import run.nuri.getagrip.ui.l10n.tr
import run.nuri.getagrip.ui.theme.GetAGripTheme
import run.nuri.getagrip.ui.theme.LocalGripPalette
import run.nuri.getagrip.ui.theme.Metrics

/// **A GRIP IS ONE CONTROL, not three.**
///
/// It replaces an edge slider, a finger pad and a row of position chips — about 400 pt of
/// the set editor — with a 60 dp row you tap. Measured on the pinned simulator 2026-08-11:
/// building the six-grip daily routine from blank spent roughly three interactions in five
/// on CONSTRUCTING grips, and the edge slider came first in that stack even though the edge
/// is 20 mm on every set of that routine and never changes. You scrolled past the one
/// control you never touch, six times.
///
/// Constructing a grip is still possible — a grip is genuinely parametric and 22 mm has to
/// stay expressible — it just happens in `GripPanel` now. What moved is the DEFAULT, not
/// the capability, and this is still no grip library: nothing has to exist before you can
/// pull, and a grip remains a VALUE living inline on the set row that uses it.
///
/// **It only DISPLAYS and asks.** The panel it opens hangs from the top of the screen, and
/// nothing this deep in a scrolling set row can reach up there — so the panel is hoisted to
/// the builder's root and this reports the tap upward. A plain value in, a callback out,
/// which also keeps the row previewable.
@Composable
fun GripToken(
    grip: GripSpec,
    modifier: Modifier = Modifier,
    onEdit: () -> Unit,
) {
    val palette = LocalGripPalette.current
    val interactionSource = remember { MutableInteractionSource() }
    Surface(
        shape = RoundedCornerShape(Metrics.radiusInner),
        color = palette.inkTertiary.copy(alpha = 0.12f),
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 60.dp)
            .semantics(mergeDescendants = true) {
                role = Role.Button
                contentDescription = L10n.tr("Grip")
                stateDescription = grip.spoken
            }
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                onClick = onEdit,
            )
            .pressFeedback(interactionSource, scales = false),
    ) {
        Row(
            Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            FingerGlyph(
                fingers = grip.fingers,
                position = grip.position,
                dot = 7.dp,
                gap = 3.dp,
            )
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    tr("%d mm · %s", grip.edgeMM, grip.fingers.name),
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.SemiBold,
                    color = palette.inkPrimary,
                )
                Text(
                    grip.position.name.lowercase(),
                    style = MaterialTheme.typography.bodySmall,
                    color = palette.inkSecondary,
                )
            }
            Icon(
                Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = null,
                tint = palette.inkTertiary,
                modifier = Modifier.size(20.dp),
            )
        }
    }
}

@Preview(name = "GripToken", showBackground = true, widthDp = 360)
@Composable
private fun GripTokenPreview() {
    GetAGripTheme {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            GripToken(GripSpec(20, FingerSet.frontTwo, GripPosition.openHand)) {}
            GripToken(GripSpec(22, FingerSet.four.union(FingerSet.thumb), GripPosition.pinch)) {}
        }
    }
}
