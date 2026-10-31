// SPDX-License-Identifier: MPL-2.0
// Original contributions Copyright 2026 Nuri Bruner.

package run.nuri.getagrip.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.rememberTextMeasurer
import run.nuri.getagrip.ui.theme.Metrics
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.UnfoldMore
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import run.nuri.getagrip.engine.Side
import run.nuri.getagrip.ui.l10n.tr
import run.nuri.getagrip.ui.theme.LocalGripPalette

/// The HANDS label row shared by the critical force setup and the routine builder: the caps
/// label, and which hand goes first as a quiet menu on the SAME row rather than a sentence
/// plus a button under the control.
///
/// `menuTitle == null` hides the menu — both hands pulling together have no first hand — and
/// `hiddenReason` then tells TalkBack why, since a missing control says nothing.
@Composable
fun HandsHeader(
    /// "Left first", "Right", … null hides the menu.
    menuTitle: String?,
    side: Side,
    /// The menu's two items, in order: left, then right.
    sideTitle: (Side) -> String,
    modifier: Modifier = Modifier,
    hiddenReason: String? = null,
    /// Keep the row at the menu's height when it is hidden, so choosing Both does not jump the
    /// control under the finger that chose it. Off in the critical force setup, which has
    /// always let the row collapse.
    reservesMenuHeight: Boolean = false,
    menuModifier: Modifier = Modifier,
    onSide: (Side) -> Unit,
) {
    val palette = LocalGripPalette.current
    val hiddenLabel = tr("Hands")
    Row(
        modifier
            .fillMaxWidth()
            .then(
                if (menuTitle == null && hiddenReason != null) {
                    Modifier.clearAndSetSemantics {
                        contentDescription = hiddenLabel
                        stateDescription = hiddenReason
                    }
                } else Modifier,
            ),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CapsLabel(tr("HANDS"), Modifier.weight(1f))
        if (menuTitle != null) {
            var open by remember { mutableStateOf(false) }
            Box {
                TextButton(onClick = { open = true }, modifier = menuModifier.heightIn(min = 44.dp)) {
                    Text(
                        menuTitle,
                        style = MaterialTheme.typography.bodyMedium,
                        color = palette.graphite,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Icon(
                        Icons.Filled.UnfoldMore,
                        contentDescription = null,
                        tint = palette.graphite,
                        modifier = Modifier.padding(start = 4.dp).size(16.dp),
                    )
                }
                DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                    listOf(Side.left, Side.right).forEach { option ->
                        DropdownMenuItem(
                            text = { Text(sideTitle(option)) },
                            onClick = {
                                open = false
                                onSide(option)
                            },
                            trailingIcon = if (side == option) {
                                { Icon(Icons.Filled.Check, contentDescription = null) }
                            } else null,
                        )
                    }
                }
            }
        } else if (reservesMenuHeight) {
            Box(Modifier.height(44.dp))
        }
    }
}

/// ONE segmented control in the house colours: graphite selection, as the tab bar — Material's
/// lavender is a hue the palette does not have. Selection tick on change.
///
/// **A label never truncates.** When any label would not fit its segment on one line with air
/// on both sides ("Out of …", "One at a t…" at large text, labels touching the borders at 1.3),
/// the choice is drawn as the house chips instead, in whole rows — for two or three long
/// labels, one full-width chip each: the builder's Hands control made the same trade first.
@Composable
fun HouseSegmentedRow(
    labels: List<String>,
    selectedIndex: Int,
    modifier: Modifier = Modifier,
    onSelect: (Int) -> Unit,
) {
    val palette = LocalGripPalette.current
    val haptics = LocalHapticFeedback.current
    val measurer = rememberTextMeasurer(cacheSize = 16)
    val density = LocalDensity.current
    val segmentStyle = MaterialTheme.typography.labelLarge
    val chipStyle = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold)
    fun choose(index: Int) {
        if (index != selectedIndex) {
            haptics.performHapticFeedback(HapticFeedbackType.SegmentTick)
            onSelect(index)
        }
    }
    BoxWithConstraints(modifier.fillMaxWidth()) {
        val fallbackColumns = remember(labels, maxWidth, density.density, density.fontScale, segmentStyle, chipStyle) {
            fun widest(style: androidx.compose.ui.text.TextStyle) =
                labels.maxOfOrNull { measurer.measure(it, style, softWrap = false, maxLines = 1).size.width } ?: 0
            val segmentRoom = with(density) { (maxWidth / maxOf(1, labels.size) - segmentLabelInset * 2).roundToPx() }
            if (widest(segmentStyle) <= segmentRoom) {
                null
            } else {
                val chipLabel = widest(chipStyle)
                val fitting = (labels.size downTo 1).firstOrNull { columns ->
                    val cell = with(density) {
                        ((maxWidth - chipSpacing * (columns - 1)) / columns - Metrics.buttonHorizontalPadding * 2).roundToPx()
                    }
                    chipLabel <= cell
                } ?: 1
                // Whole rows only: three choices that cannot share a row stack one per line
                // rather than drawing two and an orphan.
                (fitting downTo 1).first { labels.size % it == 0 }
            }
        }
        if (fallbackColumns == null) {
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                labels.forEachIndexed { index, label ->
                    SegmentedButton(
                        selected = index == selectedIndex,
                        onClick = { choose(index) },
                        shape = SegmentedButtonDefaults.itemShape(index, labels.size),
                        colors = SegmentedButtonDefaults.colors(
                            activeContainerColor = palette.graphite.copy(alpha = 0.12f),
                            activeContentColor = palette.inkPrimary,
                            activeBorderColor = palette.inkTertiary.copy(alpha = 0.5f),
                            inactiveContainerColor = Color.Transparent,
                            inactiveContentColor = palette.inkSecondary,
                            inactiveBorderColor = palette.inkTertiary.copy(alpha = 0.5f),
                        ),
                        icon = {},
                        // Tighter than Material's 12 dp a side: "One at a time" must keep its words at 360 dp.
                        contentPadding = PaddingValues(horizontal = 4.dp),
                    ) { Text(label, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                }
            }
        } else {
            Column(Modifier.selectableGroup(), verticalArrangement = Arrangement.spacedBy(chipSpacing)) {
                labels.indices.chunked(fallbackColumns).forEach { row ->
                    Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min),
                        horizontalArrangement = Arrangement.spacedBy(chipSpacing)) {
                        row.forEach { index ->
                            Chip(labels[index], index == selectedIndex, Modifier.weight(1f).fillMaxHeight()) { choose(index) }
                        }
                        // The last row keeps the column width, as `ChipGrid` does.
                        repeat(fallbackColumns - row.size) { Spacer(Modifier.weight(1f)) }
                    }
                }
            }
        }
    }
}

/// Air each side of a segment's label: the 4 dp content padding, the border, and a few points
/// so a label never reads as touching its divider. Not more: "One at a time" must keep its
/// segment at 360 dp and standard text, which is why the padding was tightened to 4.
private val segmentLabelInset = 8.dp
private val chipSpacing = 8.dp
