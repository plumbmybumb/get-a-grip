// SPDX-License-Identifier: MPL-2.0
// Original contributions Copyright 2026 Nuri Bruner.
package run.nuri.getagrip.ui.components

import androidx.compose.animation.core.animateFloatAsState
import run.nuri.getagrip.ui.theme.Motion
import run.nuri.getagrip.ui.theme.rememberReduceMotion
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt
import run.nuri.getagrip.ui.l10n.tr
import run.nuri.getagrip.ui.theme.LocalGripPalette

private val EffortBarWidth = 44.dp
private val EffortTrackHeight = 72.dp

internal fun effortLevelAt(x: Float, width: Float, barWidth: Float): Int {
    if (!x.isFinite() || !width.isFinite() || width <= 0) return 1
    val bar = minOf(barWidth, width / 5)
    return (((x - bar / 2) / maxOf(1f, width - bar)).coerceIn(0f, 1f) * 4).roundToInt() + 1
}

/** Optional 1–5 self-report. Capsule geometry matches FingerGlyph; colors are not load percentages. */
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
    // Read these states in the draw phase: only the canvas redraws as the springs settle.
    val emphasis = (1..5).map { level ->
        animateFloatAsState(if (selection == level && !reduceMotion) 1f else 0f,
            animationSpec = Motion.state(reduceMotion), label = "effort.$level")
    }
    val haptics = LocalHapticFeedback.current
    val density = LocalDensity.current
    val barPx = with(density) { EffortBarWidth.toPx() }
    val selectedName = selection?.let { labels.getOrNull(it - 1) } ?: tr("Not rated")
    val tint = when (selection) {
        1, 2 -> palette.moss
        3, 4 -> palette.armed
        5 -> palette.alarm
        else -> palette.bleu
    }
    var width by remember { mutableFloatStateOf(0f) }
    var dragX by remember { mutableFloatStateOf(0f) }
    val choose by rememberUpdatedState<(Int?) -> Unit> { level ->
        if (level != selection) {
            haptics.performHapticFeedback(HapticFeedbackType.SegmentTick)
            onSelect(level)
        }
    }
    val land by rememberUpdatedState<(Float) -> Unit> { x -> choose(effortLevelAt(x, width, barPx)) }
    Column(modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        CapsLabel(title)
        Canvas(
            Modifier.fillMaxWidth().height(EffortTrackHeight).testTag(identifier)
                .semantics {
                    contentDescription = title
                    stateDescription = selectedName
                    progressBarRangeInfo = ProgressBarRangeInfo((selection ?: 1).toFloat(), 1f..5f, 3)
                    setProgress { value ->
                        if (!value.isFinite()) false
                        else { choose(value.coerceIn(1f, 5f).roundToInt()); true }
                    }
                }
                .onSizeChanged { width = it.width.toFloat() }
                .pointerInput(Unit) { detectTapGestures { land(it.x) } }
                .draggable(
                    state = rememberDraggableState { delta -> dragX += delta; land(dragX) },
                    orientation = Orientation.Horizontal,
                    onDragStarted = { start -> dragX = start.x; land(dragX) },
                ),
        ) {
            val bar = minOf(EffortBarWidth.toPx(), size.width / 5)
            repeat(5) { index ->
                val scale = if (reduceMotion) 1f else 1f + emphasis[index].value * 0.10f
                val drawnWidth = minOf(bar * scale, size.width / 5)
                val restingHeight = bar * 1.15f + (size.height / 1.10f - bar * 1.15f) * index / 4
                val height = restingHeight * scale
                val center = (bar / 2 + index * (size.width - bar) / 4)
                    .coerceIn(drawnWidth / 2, size.width - drawnWidth / 2)
                val x = center - drawnWidth / 2
                drawRoundRect(if (selection == index + 1) tint else palette.inkTertiary.copy(alpha = 0.18f),
                    topLeft = Offset(x, size.height - height), size = Size(drawnWidth, height),
                    cornerRadius = CornerRadius(drawnWidth / 2))
                if (selection == index + 1) drawCircle(palette.inkPrimary, radius = 3.dp.toPx(),
                    center = Offset(center, size.height - 10.dp.toPx()))
            }
        }
        Row(Modifier.fillMaxWidth().clearAndSetSemantics {}, horizontalArrangement = Arrangement.SpaceBetween) {
            Text(labels.firstOrNull().orEmpty(), style = MaterialTheme.typography.labelSmall, color = palette.inkSecondary)
            Text(labels.lastOrNull().orEmpty(), style = MaterialTheme.typography.labelSmall, color = palette.inkSecondary)
        }
        Row(Modifier.fillMaxWidth().heightIn(min = 44.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Canvas(Modifier.size(8.dp)) { drawCircle(tint) }
            Text(selectedName, Modifier.weight(1f), style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold, color = if (selection == null) palette.inkSecondary else palette.inkPrimary)
            if (selection != null) {
                val clearLabel = tr("Clear rating")
                TextButton(onClick = { choose(null) }, modifier = Modifier.testTag(identifier + ".clear")
                    .semantics { contentDescription = clearLabel }) {
                    Text(tr("Clear"), color = palette.inkSecondary)
                }
            }
        }
    }
}
