// SPDX-License-Identifier: MPL-2.0
// Original contributions Copyright 2026 Nuri Bruner.
package run.nuri.getagrip.ui.runner

import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import run.nuri.getagrip.engine.L10n
import run.nuri.getagrip.runner.RunnerSnapshot
import run.nuri.getagrip.ui.theme.GripPalette
import run.nuri.getagrip.ui.theme.Metrics
import run.nuri.getagrip.ui.theme.Motion
import run.nuri.getagrip.ui.theme.rememberReduceMotion
import run.nuri.getagrip.ui.l10n.tr
import run.nuri.getagrip.ui.components.rememberGripChangeEmphasis

/** Draw-only attention: it cannot resize the plot, the panel or their neighbours.
 *
 * Drawn in two places since the session stage: the OUTLINE rides the information panel (the
 * panel is where the grip is NAMED — iOS `infoPanel`; around an open, edge-to-edge graph a
 * rounded outline would read as the card that is no longer there), and the BANNER stays on
 * the graph. Both instances run the same emphasis and pulse from the same inputs.
 */
@Composable
internal fun GraphGripChangeCue(snapshot: RunnerSnapshot, palette: GripPalette, modifier: Modifier = Modifier,
    showBanner: Boolean = true, showOutline: Boolean = true, cornerRadius: Dp = Metrics.radiusCard) {
    val reduceMotion = rememberReduceMotion()
    val visibility = rememberGripChangeEmphasis(snapshot.newGripID, snapshot.gripChangesNext, reduceMotion)
    val pulse = remember { Animatable(1f) }
    LaunchedEffect(snapshot.newGripID, reduceMotion) {
        pulse.snapTo(1f)
        if (snapshot.newGripID == null) return@LaunchedEffect
        if (!reduceMotion) {
            delay(Motion.GRIP_CHANGE_RISE_MILLIS)
            repeat(2) {
                pulse.animateTo(0.4f, Motion.gripChangePulse())
                pulse.animateTo(1f, Motion.gripChangePulse())
            }
        }
    }
    val grip = snapshot.grip ?: return
    if (!snapshot.hasSignal || visibility.value == 0f) return
    Box(modifier) {
        if (showOutline) Canvas(Modifier.fillMaxSize()) {
            val width = 3.dp.toPx()
            drawRoundRect(
                color = palette.armed.copy(alpha = visibility.value * pulse.value),
                topLeft = Offset(width / 2, width / 2),
                size = Size((size.width - width).coerceAtLeast(0f), (size.height - width).coerceAtLeast(0f)),
                cornerRadius = CornerRadius((cornerRadius.toPx() - width / 2).coerceAtLeast(0f)),
                style = Stroke(width),
            )
        }
        if (showBanner) Column(
            Modifier.padding(start = 12.dp, top = 12.dp, end = 64.dp)
                .graphicsLayer { alpha = visibility.value }
                .background(palette.armed, RoundedCornerShape(Metrics.radiusInner))
                .padding(horizontal = 16.dp, vertical = 12.dp)
                .semantics(mergeDescendants = true) {
                    contentDescription = L10n.tr("New grip: %s", grip.spoken)
                    liveRegion = LiveRegionMode.Polite
                },
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(tr("New grip"), style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold, color = Color(0xFF1B1F25))
            Text(grip.shortName, style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold, color = Color(0xFF1B1F25), maxLines = 2)
        }
    }
}
