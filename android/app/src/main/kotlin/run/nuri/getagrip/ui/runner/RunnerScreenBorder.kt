// SPDX-License-Identifier: MPL-2.0
// Original contributions Copyright 2026 Nuri Bruner.
package run.nuri.getagrip.ui.runner

import android.view.ViewTreeObserver
import android.view.WindowInsets
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.asComposePath
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import run.nuri.getagrip.engine.RunnerPhase
import run.nuri.getagrip.runner.RunnerSnapshot
import run.nuri.getagrip.ui.theme.LocalGripPalette

/** State-derived guidance only; measurement and release timing remain engine-owned. */
internal enum class RunnerBorderCue(val thicknessDp: Int, val testTag: String) {
    None(0, ""), Pulling(4, "runner-pull-border"),
    Release(6, "runner-release-border"), Warning(6, "runner-warning-border"),
}

internal fun runnerBorderCue(
    snapshot: RunnerSnapshot,
    timerOnly: Boolean,
    measuredSignalIsLive: Boolean,
): RunnerBorderCue = when (snapshot.phase) {
    is RunnerPhase.Releasing -> RunnerBorderCue.Release
    is RunnerPhase.Working -> when {
        snapshot.isDropped || snapshot.isOverTarget -> RunnerBorderCue.Warning
        // Timer-only work has its own clock; unrelated gauge readiness cannot change the cue.
        timerOnly -> RunnerBorderCue.Pulling
        !measuredSignalIsLive || !snapshot.hasSignal || snapshot.linkIsDown ||
            snapshot.isRejectingStaleBatches -> RunnerBorderCue.Warning
        else -> RunnerBorderCue.Pulling
    }
    // Paused release samples cannot clear the engine gate. The phase must be active to
    // show any instruction to pull or release; old flags never color a paused screen.
    else -> RunnerBorderCue.None
}

@Composable
internal fun RunnerScreenBorder(cue: RunnerBorderCue, modifier: Modifier = Modifier) {
    if (cue == RunnerBorderCue.None) return
    val view = LocalView.current
    val palette = LocalGripPalette.current
    val color = when (cue) {
        RunnerBorderCue.Pulling -> palette.bleu
        RunnerBorderCue.Warning -> palette.alarm
        RunnerBorderCue.Release -> palette.armed
        RunnerBorderCue.None -> return
    }
    var insets by remember(view) { mutableStateOf<WindowInsets?>(view.rootWindowInsets) }
    var originInWindow by remember(view) { mutableStateOf(Offset.Zero) }

    DisposableEffect(view) {
        // Insets can change without a size change (windowing, foldable displays). Reading
        // rootWindowInsets only inside drawWithCache would leave the old outline cached.
        // Observe the immutable value while the cue exists, without replacing Compose's
        // own inset listener. Geometry is rebuilt only when that value or placement changes.
        val observer = view.viewTreeObserver
        val listener = ViewTreeObserver.OnPreDrawListener {
            val current = view.rootWindowInsets
            if (current != insets) insets = current
            true
        }
        observer.addOnPreDrawListener(listener)
        onDispose {
            if (observer.isAlive) observer.removeOnPreDrawListener(listener)
            else view.viewTreeObserver.removeOnPreDrawListener(listener)
        }
    }

    Canvas(
        modifier.testTag(cue.testTag)
            .onGloballyPositioned { originInWindow = it.positionInWindow() }
            .drawWithCache {
                val outline = releaseScreenOutline(
                    width = size.width,
                    height = size.height,
                    windowX = originInWindow.x,
                    windowY = originInWindow.y,
                    geometry = releaseWindowGeometry(insets),
                ).asComposePath()
                // Center a double-width stroke on the reported edge, then keep only its
                // inner half: the requested visible width, with no guessed corner radius.
                val stroke = Stroke((cue.thicknessDp * 2).dp.toPx())
                onDrawBehind {
                    clipPath(outline) { drawPath(outline, color, style = stroke) }
                }
            },
    ) { }
}
