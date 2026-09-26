// SPDX-License-Identifier: MPL-2.0
// Original contributions Copyright 2026 Nuri Bruner.

package run.nuri.getagrip.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import run.nuri.getagrip.engine.GripPosition
import run.nuri.getagrip.engine.HandMode
import run.nuri.getagrip.engine.L10n
import run.nuri.getagrip.ui.l10n.tr
import run.nuri.getagrip.ui.theme.GetAGripTheme
import run.nuri.getagrip.ui.theme.LocalGripPalette
import run.nuri.getagrip.ui.theme.Metrics

// The app's ONE selection control, ported from Sources/UI/Components/Chips.swift.
//
// Chips survive only where the choice is CATEGORICAL (position, hand mode, sessions a day,
// ritual-or-whenever). Every QUANTITY moved to `ValueRow` (Nuri, 2026-08-03): nine edge
// chips still cannot express 22 mm.

/// One capsule option.
///
/// Selected is a tonal graphite wash; unselected a hairline capsule with no fill. The
/// asymmetry is load-bearing: fill-vs-outline survives greyscale, Reduce Transparency and
/// colourblindness, where a tint step does not.
///
/// TRANSLATION NOTE: iOS limits glass for re-blur cost across twenty-plus chips; Android has
/// no glass, so the tonal wash IS the surface.
@Composable
fun Chip(
    title: String,
    isSelected: Boolean,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    val palette = LocalGripPalette.current
    val interactionSource = remember { MutableInteractionSource() }
    Surface(
        // Drawn at 44 and SHAPED at 44: the visual IS the target.
        modifier = modifier
            .heightIn(min = chipMinHeight)
            .semantics {
                role = Role.Button
                selected = isSelected
            }
            .clickable(
                enabled = enabled,
                interactionSource = interactionSource,
                indication = null,
                onClick = onClick,
            )
            .pressFeedback(interactionSource),
        shape = CircleShape,
        color = if (isSelected) palette.graphite.copy(alpha = 0.20f) else Color.Transparent,
        border = if (isSelected) null else BorderStroke(1.dp, palette.inkTertiary.copy(alpha = 0.35f)),
    ) {
        Row(
            Modifier.padding(horizontal = Metrics.buttonHorizontalPadding, vertical = Metrics.buttonVerticalPadding),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                title,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Medium,
                color = if (isSelected) palette.inkPrimary else palette.inkSecondary,
                textAlign = TextAlign.Center,
            )
        }
    }
}

/// The house hit-target floor; the preset row once sat four points under it and taps read as
/// unregistered. (Not `CHIP_HEIGHT`: `DeviceChip` owns that for its 40 dp drawn pill.)
val chipMinHeight: Dp = 44.dp

// MARK: - Layout

/// The shared chip layout: cells of at least `minimumChipWidth`, sharing the row equally.
///
/// **Chip density is content-driven, never a fixed column count.** A six-column grid
/// squeezed cells to ~48 dp and the edge row read "10…, 12…, 15…, 18…" — four edges made
/// indistinguishable.
///
/// TRANSLATION NOTE: SwiftUI's `LazyVGrid(.adaptive(minimum:))`, reproduced with
/// `BoxWithConstraints` and eager rows: a handful of chips costs nothing to lay out, and a lazy
/// grid inside the builder's scrolling document would need a fixed height.
@Composable
fun ChipGrid(
    base: Int,
    modifier: Modifier = Modifier,
    spacing: Dp = 8.dp,
    content: List<@Composable (Modifier) -> Unit>,
) {
    val minimum = minimumChipWidth(base)
    BoxWithConstraints(modifier.fillMaxWidth()) {
        val available = maxWidth
        val columns = chipColumns(available, minimum, spacing).coerceAtMost(maxOf(1, content.size))
        Column(verticalArrangement = Arrangement.spacedBy(spacing)) {
            content.chunked(columns).forEach { row ->
                Row(Modifier.height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(spacing)) {
                    row.forEach { cell -> cell(Modifier.weight(1f).fillMaxHeight()) }
                    // The last row keeps the column WIDTH: chips changing size between lines read as two controls.
                    repeat(columns - row.size) { Spacer(Modifier.weight(1f)) }
                }
            }
        }
    }
}

/// Cells of at least `minimum` that fit. At least one: a phone narrower than a chip still draws it.
internal fun chipColumns(available: Dp, minimum: Dp, spacing: Dp): Int {
    if (available <= 0.dp) return 1
    val n = ((available + spacing).value / (minimum + spacing).value).toInt()
    return maxOf(1, n)
}

/// `base` is the row's column count at standard text: numeric rows (6) want narrow cells,
/// "Half crimp" (3) and "Alternate each pull" (2) wide ones. It sets a MINIMUM width, so the
/// row wraps a chip rather than truncating one.
///
/// TRANSLATION NOTE: iOS steps at `.accessibility1` and `.accessibility3`; font scales 1.3
/// and 1.6 land in the same places.
@Composable
private fun minimumChipWidth(base: Int): Dp {
    val standard: Dp = when {
        base <= 2 -> 150.dp
        base == 3 -> 104.dp
        base <= 5 -> 76.dp
        else -> 56.dp
    }
    val scale = LocalDensity.current.fontScale
    return when {
        scale >= 1.6f -> standard * 2.2f
        scale >= 1.3f -> standard * 1.6f
        else -> standard
    }
}

// MARK: - Whole-number rows

/// A small categorical integer choice, currently sessions per day. Preserve an off-menu
/// stored value as its own chip rather than changing a draft merely by opening it.
@Composable
fun IntChipRow(
    values: List<Int>,
    selection: Int,
    modifier: Modifier = Modifier,
    unit: String = "",
    onSelect: (Int) -> Unit,
) {
    val haptics = LocalHapticFeedback.current
    fun label(value: Int) = if (unit.isEmpty()) "$value" else L10n.tr("%d %s", value, unit)
    val drawn = if (values.contains(selection)) values else (values + selection).sorted()
    val longest = drawn.maxOfOrNull { label(it).length } ?: 1
    val density = when {
        longest <= 2 -> 6
        longest <= 4 -> 5
        else -> 4
    }
    ChipGrid(
        base = density,
        modifier = modifier,
        content = drawn.map { value ->
            { cellModifier: Modifier ->
                Chip(label(value), value == selection, cellModifier) {
                    if (value != selection) {
                        haptics.performHapticFeedback(HapticFeedbackType.SegmentTick)
                        onSelect(value)
                    }
                }
            }
        },
    )
}

/// How the hand is SET on the edge — a genuinely categorical choice, so it stays chips.
@Composable
fun PositionChipRow(
    selection: GripPosition,
    modifier: Modifier = Modifier,
    onSelect: (GripPosition) -> Unit,
) {
    val haptics = LocalHapticFeedback.current

    /// A position from a newer build still gets a (selected) chip — why `GripPosition` is an
    /// open value class. Dropping it would silently rewrite the set.
    val options = if (GripPosition.known.contains(selection)) {
        GripPosition.known
    } else {
        GripPosition.known + selection
    }

    fun choose(position: GripPosition) {
        if (position == selection) return
        haptics.performHapticFeedback(HapticFeedbackType.SegmentTick)
        onSelect(position)
    }

    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        ChipGrid(
            base = 3,
            content = options.map { position ->
                { cellModifier: Modifier ->
                    Chip(position.name, position == selection, cellModifier) { choose(position) }
                }
            },
        )
        if (selection == GripPosition.fingerCurl) {
            Text(
                tr("Start in half crimp and curl your fingers into the edge."),
                style = MaterialTheme.typography.bodySmall,
                color = LocalGripPalette.current.inkSecondary,
            )
        }
    }
}

/// How a set is shared between hands.
@Composable
fun HandModeChipRow(
    selection: HandMode,
    modifier: Modifier = Modifier,
    onSelect: (HandMode) -> Unit,
) {
    val haptics = LocalHapticFeedback.current
    // Two across at most: "Alternate each pull" cannot be read in a narrow cell.
    ChipGrid(
        base = 2,
        modifier = modifier,
        content = HandMode.entries.map { mode ->
            { cellModifier: Modifier ->
                Chip(mode.displayName, mode == selection, cellModifier) {
                    if (mode != selection) {
                        haptics.performHapticFeedback(HapticFeedbackType.SegmentTick)
                        onSelect(mode)
                    }
                }
            }
        },
    )
}

@Preview(name = "Chips", showBackground = true, widthDp = 380)
@Composable
private fun ChipsPreview() {
    GetAGripTheme {
        var sessions by remember { mutableStateOf(2) }
        var position by remember { mutableStateOf(GripPosition.halfCrimp) }
        var hands by remember { mutableStateOf(HandMode.alternateEachRep) }
        Column(
            Modifier.padding(20.dp).width(340.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            CapsLabel(tr("Sessions a day"))
            IntChipRow(listOf(1, 2, 3, 4), sessions) { sessions = it }
            CapsLabel(tr("GRIP"))
            PositionChipRow(position) { position = it }
            CapsLabel(tr("HANDS"))
            HandModeChipRow(hands) { hands = it }
        }
    }
}
