// SPDX-License-Identifier: MPL-2.0
// Original contributions Copyright 2026 Nuri Bruner.
package run.nuri.getagrip.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlin.math.floor
import kotlin.math.roundToInt
import run.nuri.getagrip.ui.l10n.tr
import run.nuri.getagrip.ui.theme.LocalGripPalette
import run.nuri.getagrip.ui.theme.Motion
import run.nuri.getagrip.ui.theme.rememberReduceMotion

/** The whole strip is live: five equal slots, independent of the thin drawn rungs. */
internal fun effortLevelAt(x: Float, width: Float): Int {
    if (!x.isFinite() || !width.isFinite() || width <= 0f) return 1
    return floor(x.toDouble().coerceIn(0.0, width.toDouble()) / width.toDouble() * 5)
        .toInt().coerceIn(0, 4) + 1
}

internal fun effortBarHeight(level: Int, tallest: Float): Float {
    if (!tallest.isFinite() || tallest <= 0f) return 0f
    return tallest * (0.40f + 0.60f * (level.coerceIn(1, 5) - 1) / 4)
}

internal fun effortBarIsFilled(level: Int, selection: Int?): Boolean =
    level in 1..5 && selection != null && selection in 1..5 && level <= selection

/** Center the endpoint word under its rung, sliding inward only when it reaches an edge. */
internal fun effortEndpointLabelStart(width: Float, labelWidth: Float, last: Boolean): Float {
    if (!width.isFinite() || width <= 0f || !labelWidth.isFinite()) return 0f
    val label = labelWidth.coerceIn(0f, width)
    return (width * (if (last) 0.9f else 0.1f) - label / 2).coerceIn(0f, width - label)
}

/**
 * Optional 1–5 self-report. The monotonic capsule ladder fills through the answer;
 * selection never changes its geometry. Moss/orange/red describes subjective effort,
 * independently of measured force or a routine's percentage targets.
 */
@Composable
fun EffortPicker(
    selection: Int?,
    labels: List<String>,
    title: String,
    identifier: String,
    modifier: Modifier = Modifier,
    onSelect: (Int?) -> Unit,
) {
    val palette = LocalGripPalette.current
    val reduceMotion = rememberReduceMotion()
    val selected = selection?.takeIf { it in 1..5 }
    // Read animation states only in the canvas: fills settle without remeasuring text
    // or changing any rung's height. Reduce Motion uses the house cross-fade curve.
    val fills = (1..5).map { level ->
        animateFloatAsState(if (effortBarIsFilled(level, selected)) 1f else 0f,
            animationSpec = Motion.state(reduceMotion), label = "effort.$level")
    }
    val haptics = LocalHapticFeedback.current
    val fontScale = LocalDensity.current.fontScale.takeIf { it.isFinite() && it > 0f } ?: 1f
    val tallest = (44f * fontScale).dp
    val barWidth = (10f * fontScale).dp
    val selectedName = selected?.let { labels.getOrNull(it - 1) }?.takeIf { it.isNotBlank() }
        ?: tr("Not rated")
    val clearLabel = tr("Clear rating")
    val tint = when (selected) {
        1, 2 -> palette.moss
        3, 4 -> palette.armed
        5 -> palette.alarm
        else -> palette.inkTertiary
    }
    var width by remember { mutableFloatStateOf(0f) }
    var dragX by remember { mutableFloatStateOf(0f) }
    // A drag can cross multiple slots before the parent recomposes. Keep the latest
    // landing locally so duplicate pointer events cannot repeat callbacks or haptics.
    var lastLanding by remember(selected) { mutableStateOf(selected) }
    val choose by rememberUpdatedState<(Int?) -> Unit> { level ->
        if (level != lastLanding) {
            lastLanding = level
            haptics.performHapticFeedback(HapticFeedbackType.SegmentTick)
            onSelect(level)
        }
    }
    val land by rememberUpdatedState<(Float, Boolean) -> Unit> { x, tap ->
        if (x.isFinite() && width.isFinite() && width > 0f) {
            val level = effortLevelAt(x, width)
            choose(if (tap && level == lastLanding) null else level)
        }
    }
    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        CapsLabel(title)
        Text(selectedName, Modifier.clearAndSetSemantics {}, style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold, color = if (selected == null) palette.inkTertiary else palette.inkPrimary)
        Canvas(
            Modifier.fillMaxWidth().height(maxOf(44.dp, tallest)).testTag(identifier)
                .semantics {
                    contentDescription = title
                    stateDescription = selectedName
                    progressBarRangeInfo = ProgressBarRangeInfo((selected ?: 1).toFloat(), 1f..5f, 3)
                    setProgress { value ->
                        if (!value.isFinite()) false
                        else { choose(value.coerceIn(1f, 5f).roundToInt()); true }
                    }
                    if (selected != null) {
                        customActions = listOf(CustomAccessibilityAction(clearLabel) { choose(null); true })
                    }
                }
                .onSizeChanged { width = it.width.toFloat() }
                .pointerInput(Unit) { detectTapGestures { land(it.x, true) } }
                .draggable(
                    state = rememberDraggableState { delta -> dragX += delta; land(dragX, false) },
                    orientation = Orientation.Horizontal,
                    onDragStarted = { start -> dragX = start.x; land(dragX, false) },
                ),
        ) {
            if (!size.width.isFinite() || size.width <= 0f) return@Canvas
            val slot = size.width / 5
            val bar = minOf(barWidth.toPx(), slot)
            repeat(5) { index ->
                val fill = fills[index].value.coerceIn(0f, 1f)
                val height = minOf(effortBarHeight(index + 1, tallest.toPx()), size.height)
                val center = slot * (index + 0.5f)
                val x = center - bar / 2
                val y = size.height - height
                drawRoundRect(tint.copy(alpha = tint.alpha * fill),
                    topLeft = Offset(x, y), size = Size(bar, height), cornerRadius = CornerRadius(bar / 2))
                // Stroke is inset, exactly like FingerGlyph: the full capsule fits in
                // its bounds even on very narrow layouts or at large text sizes.
                val stroke = minOf(1.dp.toPx(), bar, height)
                drawRoundRect(lerp(palette.inkTertiary, tint, fill),
                    topLeft = Offset(x + stroke / 2, y + stroke / 2),
                    size = Size((bar - stroke).coerceAtLeast(0f), (height - stroke).coerceAtLeast(0f)),
                    cornerRadius = CornerRadius((bar - stroke).coerceAtLeast(0f) / 2), style = Stroke(stroke))
            }
        }
        EffortEndpointLabels(labels.getOrNull(0).orEmpty(), labels.getOrNull(4).orEmpty())
    }
}

@Composable
private fun EffortEndpointLabels(first: String, last: String) {
    val palette = LocalGripPalette.current
    Layout(content = {
        for (label in listOf(first, last)) {
            Text(label, style = MaterialTheme.typography.labelSmall, color = palette.inkTertiary)
        }
    }, modifier = Modifier.fillMaxWidth().clearAndSetSemantics {}) { measurables, constraints ->
        val width = constraints.maxWidth
        // Each word may grow beyond its rung's slot, but never through its neighbour.
        val labelConstraints = constraints.copy(minWidth = 0, minHeight = 0, maxWidth = width / 2)
        val words = measurables.map { it.measure(labelConstraints) }
        layout(width, words.maxOfOrNull { it.height } ?: 0) {
            words.forEachIndexed { index, word ->
                word.place(effortEndpointLabelStart(width.toFloat(), word.width.toFloat(), index == 1).roundToInt(), 0)
            }
        }
    }
}
