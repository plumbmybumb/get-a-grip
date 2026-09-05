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
    val arrival = remember { Animatable(1f) }
    val travel = with(LocalDensity.current) { 8.dp.toPx() }
    LaunchedEffect(destination, reduced) {
        if (reduced) arrival.snapTo(1f) else {
            arrival.snapTo(0f)
            arrival.animateTo(1f, Motion.state(false))
        }
    }
    return graphicsLayer {
        alpha = .75f + arrival.value * .25f
        translationY = if (reduced) 0f else (1f - arrival.value) * travel
    }
}
