// SPDX-License-Identifier: MPL-2.0
// Original contributions Copyright 2026 Nuri Bruner.
package run.nuri.getagrip.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.Measurable
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp

// **THE SHAPE COMES FROM WHAT FITS, not from a font-scale threshold.** Two layouts shared by
// every row that put a label beside a control: a label that would wrap inside the row is
// given the full width instead, and the control moves under it. Text measures its ONE-LINE
// width as its max intrinsic width, so "fits" here means "nothing wraps".
//
// Children must answer intrinsic queries: no `BoxWithConstraints` or lazy layouts inside.

/// A label and a trailing control side by side when both fit at their one-line widths;
/// otherwise the label takes the full width and the control sits under it.
///
/// Side by side, the trailing slot sits at the end and both centre vertically. Stacked, the trailing slot aligns to `stackedAlignment`.
@Composable
fun RowOrStack(
    modifier: Modifier = Modifier,
    spacing: Dp = 8.dp,
    stackedSpacing: Dp = 4.dp,
    stackedAlignment: Alignment.Horizontal = Alignment.Start,
    /// The leading slot takes every point the trailing one leaves (a title beside a field);
    /// false keeps it at its own width (a chip that must not stretch).
    fillLeading: Boolean = true,
    leading: @Composable () -> Unit,
    trailing: @Composable () -> Unit,
) {
    Layout(contents = listOf(leading, trailing), modifier = modifier) { (leadingSlot, trailingSlot), constraints ->
        val lead = leadingSlot.firstOrNull()
        val trail = trailingSlot.firstOrNull()
        val width = if (constraints.hasBoundedWidth) constraints.maxWidth else {
            (lead?.maxIntrinsicWidth(Constraints.Infinity) ?: 0) + spacing.roundToPx() +
                (trail?.maxIntrinsicWidth(Constraints.Infinity) ?: 0)
        }
        val gap = spacing.roundToPx()
        val leadWidth = lead?.maxIntrinsicWidth(Constraints.Infinity) ?: 0
        // The control is MEASURED, not asked: a field that fills its own `widthIn` reports a
        // smaller intrinsic width than it then takes, which squeezed the title beside it.
        // Same constraints in both forms, so one measurement serves either.
        val t = trail?.measure(Constraints(maxWidth = width))
        val sideBySide = t == null || lead == null || leadWidth + gap + t.width <= width

        if (sideBySide) {
            val leadMax = (width - (t?.let { it.width + gap } ?: 0)).coerceAtLeast(0)
            val l = lead?.measure(Constraints(minWidth = if (fillLeading) leadMax else 0, maxWidth = leadMax))
            val height = maxOf(l?.height ?: 0, t?.height ?: 0, constraints.minHeight)
            layout(width, height) {
                l?.placeRelative(0, (height - l.height) / 2)
                t?.placeRelative(width - t.width, (height - t.height) / 2)
            }
        } else {
            val l = lead.measure(Constraints(minWidth = if (fillLeading) width else 0, maxWidth = width))
            val between = stackedSpacing.roundToPx()
            val height = maxOf(l.height + between + t.height, constraints.minHeight)
            layout(width, height) {
                l.placeRelative(0, 0)
                t.placeRelative(stackedAlignment.align(t.width, width, LayoutDirection.Ltr), l.height + between)
            }
        }
    }
}

/// Equal cells in one row when every child's one-line width fits its cell; otherwise every
/// child takes the full width, one under another. A child with no width at all (an empty
/// placeholder holding the leading cell open) is dropped from the stacked form rather than
/// leaving a gap.
///
/// The cells share the tallest child's height, so two capsules side by side never differ.
@Composable
fun EqualCellsOrStack(
    modifier: Modifier = Modifier,
    spacing: Dp = 12.dp,
    content: @Composable () -> Unit,
) {
    Layout(content, modifier) { measurables, constraints ->
        val gap = spacing.roundToPx()
        val width = constraints.maxWidth
        val count = measurables.size
        if (count == 0) return@Layout layout(0, 0) {}
        val cell = ((width - gap * (count - 1)) / count).coerceAtLeast(0)
        val fits = measurables.all { it.maxIntrinsicWidth(Constraints.Infinity) <= cell }
        if (fits) {
            val height = measurables.maxOf { it.minIntrinsicHeight(cell) }
            val placeables = measurables.map { it.measure(Constraints.fixed(cell, height)) }
            layout(width, height) {
                placeables.forEachIndexed { index, p -> p.placeRelative(index * (cell + gap), 0) }
            }
        } else {
            val shown: List<Measurable> = measurables.filter { it.maxIntrinsicWidth(Constraints.Infinity) > 0 }
            val placeables = shown.map { it.measure(Constraints(minWidth = width, maxWidth = width)) }
            val height = placeables.sumOf { it.height } + gap * (placeables.size - 1).coerceAtLeast(0)
            layout(width, height) {
                var y = 0
                placeables.forEach { p ->
                    p.placeRelative(0, y)
                    y += p.height + gap
                }
            }
        }
    }
}
