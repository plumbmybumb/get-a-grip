// SPDX-License-Identifier: MPL-2.0
// Original contributions Copyright 2026 Nuri Bruner.

package run.nuri.getagrip.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import run.nuri.getagrip.ui.theme.LocalGripPalette
import run.nuri.getagrip.ui.theme.rememberReduceMotion

/// Which way the step goes. An enum rather than a raw icon, so the two glyphs and the two
/// spoken labels cannot drift apart.
enum class StepGlyph { Plus, Minus }

/// A stepper button that REPEATS WHILE HELD, and accelerates.
///
/// This is the thing whose absence made 80 % to 90 % a dozen separate taps (Nuri,
/// 2026-08-10: "You can't hold down +"), and it is the HIG's own guidance for a stepper:
/// a value nudged around a common one.
///
/// **A raw pointer gesture rather than a clickable, because a click fires on touch-UP**:
/// a held press would repeat twenty times and then add one more on release. The down is
/// deliberately NOT consumed and any real movement cancels the hold, so a scroll that
/// happens to start on the glyph still scrolls — the same courtesy the dial and the
/// trimmer buy the page.
///
/// TRANSLATION NOTE: SwiftUI attaches this as a `simultaneousGesture` with a 10 pt slop.
/// Compose's equivalent is an unconsumed `awaitEachGesture`: the parent scroll sees the
/// same pointer, and the moment it claims one the change arrives here already consumed,
/// which ends the hold. Same rule, expressed through the consumption flag rather than
/// through a recognizer's arbitration.
@Composable
fun RepeatingStep(
    glyph: StepGlyph,
    enabled: Boolean,
    contentDescription: String,
    modifier: Modifier = Modifier,
    /// Returns false when the value could not move, which ends the repeat at the bound
    /// rather than letting it spin against the wall.
    step: () -> Boolean,
) {
    val palette = LocalGripPalette.current
    val reduceMotion = rememberReduceMotion()
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current
    val currentStep by rememberUpdatedState(step)
    val currentEnabled by rememberUpdatedState(enabled)

    var pressed by remember { mutableStateOf(false) }
    var job by remember { mutableStateOf<Job?>(null) }

    DisposableEffect(Unit) { onDispose { job?.cancel() } }

    // The same immediate acknowledgement and quiet release as ordinary buttons.
    val scale = animateFloatAsState(
        targetValue = if (pressed && !reduceMotion) 0.975f else 1f,
        animationSpec = if (pressed) snap() else run.nuri.getagrip.ui.theme.Motion.state(reduceMotion),
        label = "stepPress",
    )

    Box(
        modifier
            // 44 dp around a glyph that draws far smaller — the house rule for every
            // bare-glyph control.
            .size(44.dp)
            .semantics {
                role = Role.Button
                this.contentDescription = contentDescription
                if (!enabled) disabled()
                // The accelerating hold is sighted-only (there is no TalkBack equivalent
                // to "held down"), but an activation performs ONE step — the same
                // precedent `HoldToEndButton` sets for a gesture-only control.
                onClick {
                    if (enabled) step()
                    true
                }
            }
            .pointerInput(density) {
                val slop = with(density) { 10.dp.toPx() }
                awaitEachGesture {
                    // UNCONSUMED: the parent scroll must keep seeing this pointer.
                    val down = awaitFirstDown(requireUnconsumed = false)
                    if (!currentEnabled) return@awaitEachGesture
                    pressed = true
                    var repeated = false
                    var cancelled = false
                    job?.cancel()
                    job = scope.launch {
                        // The dwell before it takes over. Any shorter and a deliberate
                        // single tap starts running away from you.
                        delay(450)
                        var wait = 90L
                        while (true) {
                            repeated = true
                            if (!currentEnabled || !currentStep()) break
                            delay(wait)
                            wait = maxOf(35L, wait - 5)
                        }
                    }
                    try {
                        while (true) {
                            val event = awaitPointerEvent()
                            val change = event.changes.firstOrNull { it.id == down.id } ?: break
                            // A scroll took the pointer: the hold is over, and it was never a tap.
                            if (change.isConsumed) { cancelled = true; break }
                            val moved = (change.position - down.position).getDistance() > slop
                            if (moved) { cancelled = true; break }
                            if (!change.pressed) break
                        }
                    } finally {
                        // Disposal or pointer cancellation must also stop auto-repeat.
                        job?.cancel()
                        job = null
                        pressed = false
                    }
                    // A tap is a press that never reached the dwell — and never wandered.
                    if (!repeated && !cancelled && currentEnabled) currentStep()
                }
            }
            .graphicsLayer { scaleX = scale.value; scaleY = scale.value },
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = if (glyph == StepGlyph.Plus) Icons.Filled.Add else Icons.Filled.Remove,
            contentDescription = null,
            tint = if (enabled) palette.graphite else palette.inkTertiary.copy(alpha = 0.5f),
            modifier = Modifier.size(20.dp),
        )
    }
}
