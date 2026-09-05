// SPDX-License-Identifier: MPL-2.0
// Original contributions Copyright 2026 Nuri Bruner.

package run.nuri.getagrip.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import run.nuri.getagrip.engine.GripPosition
import run.nuri.getagrip.engine.HandMode
import run.nuri.getagrip.engine.L10n
import run.nuri.getagrip.ui.l10n.tr
import run.nuri.getagrip.ui.theme.GetAGripTheme
import run.nuri.getagrip.ui.theme.LocalGripPalette

// The app's ONE selection control, ported from Sources/UI/Components/Chips.swift.
//
// Chips survive exactly where the choice is genuinely CATEGORICAL — position, hand mode,
// sessions a day, ritual-or-whenever. Every QUANTITY moved to `ValueRow` and its dial
// (Nuri, 2026-08-03: "a lot of buttons instead of something you can just enter or drag"),
// because a menu of nine edge sizes is an arbitrary list that still cannot express 22 mm.

/// One capsule option.
///
/// Selected is a tonal graphite wash; unselected is a hairline capsule with no fill at
/// all. That asymmetry is load-bearing rather than cosmetic: fill-vs-outline survives
/// greyscale, Reduce Transparency and colourblindness, where a tint step does not.
///
/// TRANSLATION NOTE: iOS draws the selected state as one glass surface and says so is
/// deliberate — an expanded set row carries twenty-plus chips and every glass surface
/// re-blurs its backdrop. Android has no glass, so the tonal wash IS the surface and the
/// cost that rule was managing does not exist here.
@Composable
fun Chip(
    title: String,
    isSelected: Boolean,
    modifier: Modifier = Modifier,
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
            Modifier.padding(horizontal = 12.dp),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                title,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Medium,
                color = if (isSelected) palette.inkPrimary else palette.inkSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center,
            )
        }
    }
}

/// The house hit-target floor. `Chip` has always used 44 and the preset row once sat four
/// points under it — exactly the kind of miss that reads as "the tap didn't register".
/// (Named `chipMinHeight` rather than `CHIP_HEIGHT`, which `DeviceChip` already owns in
/// this package for the DRAWN pill — a 40 dp status capsule inside a 44 dp target.)
val chipMinHeight: Dp = 44.dp

// MARK: - Layout

/// The shared chip layout: cells of at least `minimumChipWidth`, sharing the row equally.
///
/// **Chip density is content-driven, never a fixed column count.** A frozen six-column
/// grid squeezed cells to about 48 dp and the edge row rendered "10…, 12…, 15…, 18…" —
/// four different edges made indistinguishable, which is worse than useless. `base` is the
/// comfortable count for THIS row's labels: six for "20 s", three for "Half crimp", two
/// for "Alternate each pull", which cannot survive a narrow cell at any text size.
///
/// TRANSLATION NOTE: SwiftUI's `LazyVGrid(.adaptive(minimum:))` computes the column count
/// from the proposed width and then shares it equally. `BoxWithConstraints` plus eager
/// rows reproduces that exactly — and eagerly, which the builder wants anyway: the coach's
/// scroll-to has to find anchors that a lazy grid has not built yet.
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
                Row(horizontalArrangement = Arrangement.spacedBy(spacing)) {
                    row.forEach { cell -> cell(Modifier.weight(1f)) }
                    // The last row keeps the grid's column WIDTH rather than stretching
                    // two chips across six columns' worth of space — a row of chips that
                    // change size between lines reads as two different controls.
                    repeat(columns - row.size) { Spacer(Modifier.weight(1f)) }
                }
            }
        }
    }
}

/// How many cells of at least `minimum` fit in `available`. At least one, always: a phone
/// narrower than one chip still has to draw the chip.
internal fun chipColumns(available: Dp, minimum: Dp, spacing: Dp): Int {
    if (available <= 0.dp) return 1
    val n = ((available + spacing).value / (minimum + spacing).value).toInt()
    return maxOf(1, n)
}

/// `base` is the row's intended column count at standard text, and it carries the caller's
/// real information: a numeric row (6) wants narrow cells, "Half crimp" (3) and "Alternate
/// each pull" (2) need wide ones. It sets a MINIMUM width rather than a hard count, so the
/// row keeps its intended density and wraps a chip instead of truncating one.
///
/// TRANSLATION NOTE: iOS steps the minimum on `dynamicTypeSize >= .accessibility1` and
/// `>= .accessibility3`. Android's equivalent is the font scale, whose accessibility range
/// runs to 2.0; 1.3 and 1.6 are the two rungs that land in the same places.
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

/// Seconds, millimetres, pulls, sessions — anything counted in whole units, where the
/// choice really is a short menu.
///
/// The row can end in `Other…`, which swaps the chips in place for a repeating stepper:
/// one tap for the ~95 % of choices that are on the menu, a real escape hatch for the
/// rest, and no second screen for either.
@Composable
fun IntChipRow(
    values: List<Int>,
    selection: Int,
    modifier: Modifier = Modifier,
    unit: String = "",
    otherRange: IntRange? = null,
    onSelect: (Int) -> Unit,
) {
    val palette = LocalGripPalette.current
    val haptics = LocalHapticFeedback.current
    var showsCustom by remember { mutableStateOf(false) }

    fun label(value: Int) = if (unit.isEmpty()) "$value" else L10n.tr("%d %s", value, unit)

    /// A value that is not on the menu shows the stepper WITHOUT being asked. A chip row
    /// rendering with nothing selected reads as a bug, and silently snapping the value to
    /// the nearest chip would edit a routine the user never touched.
    val isCustom = otherRange != null && (showsCustom || !values.contains(selection))

    /// A row with no `Other…` escape gives an off-menu value its own chip rather than
    /// dropping it — same rule, and the same reason, as `PositionChipRow`.
    val drawn = if (otherRange == null && !values.contains(selection)) {
        (values + selection).sorted()
    } else {
        values
    }

    // Density is driven by the row's OWN longest label. "Other…" is deliberately excluded:
    // it is one chip at the end of the row and may wrap, whereas letting its six characters
    // set the width would drop a row of bare numbers from six columns to four for nothing.
    val longest = drawn.maxOfOrNull { label(it).length } ?: 1
    val density = when {
        longest <= 2 -> 6
        longest <= 4 -> 5
        else -> 4
    }

    fun choose(value: Int) {
        if (value == selection) return
        // The tick names its CAUSE — a chip tap that actually moved the value.
        haptics.performHapticFeedback(HapticFeedbackType.SegmentTick)
        onSelect(value)
    }

    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (isCustom) {
            // Non-null by construction: `isCustom` is false without a range to be custom in,
            // which the compiler proves for itself through that `val`.
            val custom = otherRange
            // A stepper states its value, moves by exactly one unit and reads as
            // "adjustable" to TalkBack for free — none of which is true of a wheel.
            Row(
                Modifier.fillMaxWidth().heightIn(min = chipMinHeight),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                RepeatingStep(
                    glyph = StepGlyph.Minus,
                    enabled = selection - stepFor(custom) >= custom.first,
                    contentDescription = L10n.tr("Decrease"),
                ) {
                    val next = selection - stepFor(custom)
                    if (next < custom.first) false else { onSelect(next); true }
                }
                Text(
                    label(selection),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = palette.inkPrimary,
                )
                RepeatingStep(
                    glyph = StepGlyph.Plus,
                    enabled = selection + stepFor(custom) <= custom.last,
                    contentDescription = L10n.tr("Increase"),
                ) {
                    val next = selection + stepFor(custom)
                    if (next > custom.last) false else { onSelect(next); true }
                }
            }
            Text(
                tr("Back to the usual values"),
                style = MaterialTheme.typography.bodySmall,
                fontWeight = FontWeight.Medium,
                color = palette.graphite,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = chipMinHeight)
                    .clickable(role = Role.Button) {
                        showsCustom = false
                        // Snap on the way back, so the chips can never reappear with no
                        // answer showing. The move is visible — the nearest chip lights up.
                        val nearest = values.minByOrNull { kotlin.math.abs(it - selection) }
                        if (nearest != null) onSelect(nearest)
                    }
                    .padding(vertical = 12.dp),
            )
        } else {
            ChipGrid(
                base = density,
                content = buildList {
                    drawn.forEach { value ->
                        add { cellModifier ->
                            Chip(label(value), value == selection, cellModifier) { choose(value) }
                        }
                    }
                    if (otherRange != null) {
                        add { cellModifier ->
                            Chip(tr("Other…"), false, cellModifier) { showsCustom = true }
                        }
                    }
                },
            )
        }
    }
}

/// Rest and set-break run to hundreds of seconds; one-unit steps there are a thousand
/// taps. Ranges that tight-fit a real edit keep their unit step.
private fun stepFor(range: IntRange): Int = if (range.last - range.first > 120) 5 else 1

/// How the hand is SET on the edge — a genuinely categorical choice, so it stays chips.
@Composable
fun PositionChipRow(
    selection: GripPosition,
    modifier: Modifier = Modifier,
    /// `false` lays the chips out as ONE horizontally scrolling row instead of a wrapping
    /// grid — six grips wrap to two rows, and on a panel fighting for height that
    /// second row is 45 dp it does not have.
    wraps: Boolean = true,
    onSelect: (GripPosition) -> Unit,
) {
    val haptics = LocalHapticFeedback.current

    /// A position written by a newer build is not in `known`, and it still gets a chip —
    /// showing it selected is the whole reason `GripPosition` is an open value class
    /// rather than an enum. Dropping it here would let this build silently rewrite the set.
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
        if (wraps) {
            ChipGrid(
                base = 3,
                modifier = Modifier,
                content = options.map { position ->
                    { cellModifier: Modifier ->
                        Chip(position.name, position == selection, cellModifier) { choose(position) }
                    }
                },
            )
        } else {
            Row(
                Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                options.forEach { position ->
                    // Chips fill their container in a grid, which is right there and wrong in
                    // a horizontal scroller — here they hug their label.
                    Chip(position.name, position == selection, Modifier.widthIn(min = 88.dp)) {
                        choose(position)
                    }
                }
            }
        }
        if (selection == GripPosition.fingerCurl) {
            Text(
                tr("Start in half crimp and build force by trying to curl your fingers into the edge."),
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
