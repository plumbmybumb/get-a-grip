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
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import run.nuri.getagrip.ui.theme.Metrics

/** Equal action cells with real label insets. **A capsule label never wraps**: a label that
 * would take a second line ("Hold to / end", "Start / timer" at 1.3) moves cells to the next
 * row instead, down to one per row. Alternate labels are measured together so beginning a
 * hold never moves its target. */
@Composable
internal fun AdaptiveActionRow(
    labels: List<List<String>>,
    modifier: Modifier = Modifier,
    /// The gap between cells, and between rows once they wrap. A dock packs at its own 8 dp.
    spacing: Dp = 10.dp,
    /// Room a cell's leading icon takes beside its label (a dock icon is 18 dp plus a 6 dp gap).
    iconAllowance: Dp = 0.dp,
    content: @Composable (index: Int, modifier: Modifier) -> Unit,
) {
    val measurer = rememberTextMeasurer(cacheSize = 32)
    val density = LocalDensity.current
    val style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold)
    BoxWithConstraints(modifier.fillMaxWidth()) {
        val horizontalInsets = with(density) { (Metrics.buttonHorizontalPadding * 2 + iconAllowance).roundToPx() }
        val columns = remember(labels, maxWidth, spacing, iconAllowance, style, density.density, density.fontScale) {
            val variants = labels.flatten()
            (labels.size downTo 1).firstOrNull { count ->
                val width = with(density) {
                    ((maxWidth - spacing * (count - 1)) / count).roundToPx()
                } - horizontalInsets
                width > 0 && variants.all { label ->
                    measurer.measure(label, style, softWrap = false, maxLines = 1).size.width <= width
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
