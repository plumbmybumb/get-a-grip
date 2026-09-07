// SPDX-License-Identifier: MPL-2.0
// Original contributions Copyright 2026 Nuri Bruner.

package run.nuri.getagrip.ui.maxes

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import run.nuri.getagrip.engine.L10n
import run.nuri.getagrip.engine.Side
import run.nuri.getagrip.ui.components.Chip
import run.nuri.getagrip.ui.components.ChipGrid
import run.nuri.getagrip.ui.l10n.tr

/** The same hand vocabulary and selection surface in the composer and measurement screen. */
@Composable
internal fun MaxHandPicker(
    selectedSide: Side,
    onSelected: (Side) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    ChipGrid(
        base = 3,
        modifier = modifier,
        content = listOf(Side.both, Side.left, Side.right).map { side ->
            { cellModifier: Modifier ->
                Chip(
                    title = when (side) {
                        Side.both -> tr("Both hands")
                        Side.left -> tr("Left hand")
                        Side.right -> tr("Right hand")
                    },
                    isSelected = selectedSide == side,
                    modifier = cellModifier.semantics {
                        contentDescription = if (side == Side.both) {
                            L10n.tr("For both hands")
                        } else {
                            L10n.tr("For the %s hand only", side.displayName.lowercase())
                        }
                    },
                    enabled = enabled,
                ) { if (enabled && side != selectedSide) onSelected(side) }
            }
        },
    )
}
