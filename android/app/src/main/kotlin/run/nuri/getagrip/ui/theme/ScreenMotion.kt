// SPDX-License-Identifier: MPL-2.0
// Original contributions Copyright 2026 Nuri Bruner.

package run.nuri.getagrip.ui.theme

import androidx.compose.animation.core.Animatable
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp

/** Arrival communicates a new destination without keeping two live screens composed.
 * No delayed actions or blocked input; the travel is only 8dp and disappears under Reduce Motion.
 * Values are read in the layer so each animation frame skips composition and layout.
 */
@Composable
fun Modifier.screenArrival(destination: Any): Modifier {
    val reduced = rememberReduceMotion()
    // Keyed on the destination and STARTED at the arrival pose, so the first frame of the
    // new tab is already that pose. Snapping to 0 inside the effect drew one frame at full
    // state and then dipped (responsiveness audit, 2026-10-01).
    val arrival = remember(destination, reduced) { Animatable(if (reduced) 1f else 0f) }
    val travel = with(LocalDensity.current) { 8.dp.toPx() }
    LaunchedEffect(arrival) {
        if (!reduced) arrival.animateTo(1f, Motion.state(false))
    }
    return graphicsLayer {
        alpha = .75f + arrival.value * .25f
        translationY = if (reduced) 0f else (1f - arrival.value) * travel
        // The DEFAULT strategy on purpose: the screen fades as ONE image. ModulateAlpha
        // (tried 2026-10-01 to skip the offscreen buffer) fades every draw separately, so a
        // card's square backdrop showed through its own rounded fill on every fast tab
        // switch — seen on Nuri's phone the same day.
    }
}
