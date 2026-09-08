// SPDX-License-Identifier: MPL-2.0
// Original contributions Copyright 2026 Nuri Bruner.
package run.nuri.getagrip.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import run.nuri.getagrip.ui.theme.Metrics

/** Equal action cells with real label insets. Long translations can take two lines;
 * larger text moves a cell to the next row before a word gets split or clipped.
 * Alternate labels are measured together so beginning a hold never moves its target. */
@Composable
internal fun AdaptiveActionRow(
    labels: List<List<String>>,
    modifier: Modifier = Modifier,
    content: @Composable (index: Int, modifier: Modifier) -> Unit,
) {
    val measurer = rememberTextMeasurer(cacheSize = 32)
    val density = LocalDensity.current
    val style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold)
    val spacing = 10.dp
    BoxWithConstraints(modifier.fillMaxWidth()) {
        val horizontalInsets = with(density) { (Metrics.buttonHorizontalPadding * 2).roundToPx() }
        val columns = remember(labels, maxWidth, style, density.density, density.fontScale) {
            val variants = labels.flatten()
            (labels.size downTo 1).firstOrNull { count ->
                val width = with(density) {
                    ((maxWidth - spacing * (count - 1)) / count).roundToPx()
                } - horizontalInsets
                width > 0 && variants.all { label ->
                    val layout = measurer.measure(label, style, constraints = Constraints(maxWidth = width))
                    layout.lineCount <= 2 && !layout.hasVisualOverflow && label.split(Regex("\\s+")).all { word ->
                        measurer.measure(word, style, softWrap = false).size.width <= width
                    }
                }
            } ?: 1
        }
        Column(verticalArrangement = Arrangement.spacedBy(spacing)) {
            labels.indices.toList().chunked(columns).forEach { indices ->
                Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min),
                    horizontalArrangement = Arrangement.spacedBy(spacing)) {
                    indices.forEach { index -> content(index, Modifier.weight(1f).fillMaxHeight()) }
                }
            }
        }
    }
}
