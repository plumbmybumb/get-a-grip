// SPDX-License-Identifier: MPL-2.0
// Original contributions Copyright 2026 Nuri Bruner.
package run.nuri.getagrip.ui.runner

import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import run.nuri.getagrip.engine.L10n
import run.nuri.getagrip.runner.RunnerSnapshot
import run.nuri.getagrip.ui.theme.GripPalette
import run.nuri.getagrip.ui.theme.Metrics
import run.nuri.getagrip.ui.theme.Motion
import run.nuri.getagrip.ui.theme.rememberReduceMotion
import run.nuri.getagrip.ui.components.rememberGripChangeEmphasis

/** The grip-change cue (iOS `infoPanel`'s rim): an amber outline around the information
 * panel — the panel is where the grip is NAMED — rising, pulsing twice, then holding through a
 * rest that leads to the new grip. Draw-only: it cannot resize the panel or its neighbours.
 *
 * iOS dropped the old on-graph "New grip" banner: floating loose over an open graph it repeated
 * what the rim, the rest badge and the hand already say. Its one job no other cue did — telling
 * TalkBack — stays here as a polite announcement on the outline's node.
 */
@Composable
internal fun GripChangeOutline(snapshot: RunnerSnapshot, palette: GripPalette, modifier: Modifier = Modifier,
    cornerRadius: Dp = Metrics.radiusSheet) {
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
    Canvas(modifier.testTag("runner.gripChangeOutline").semantics {
        contentDescription = L10n.tr("New grip: %s", grip.spoken)
        liveRegion = LiveRegionMode.Polite
    }) {
        val width = 3.dp.toPx()
        drawRoundRect(
            color = palette.armed.copy(alpha = visibility.value * pulse.value),
            topLeft = Offset(width / 2, width / 2),
            size = Size((size.width - width).coerceAtLeast(0f), (size.height - width).coerceAtLeast(0f)),
            cornerRadius = CornerRadius((cornerRadius.toPx() - width / 2).coerceAtLeast(0f)),
            style = Stroke(width),
        )
    }
}
