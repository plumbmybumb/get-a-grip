// SPDX-License-Identifier: MPL-2.0
// Original contributions Copyright 2026 Nuri Bruner.
package run.nuri.getagrip.ui.runner

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.key
import run.nuri.getagrip.engine.RunnerPhase
import run.nuri.getagrip.ui.theme.Motion

/** Keep drawing between BLE batches without extrapolating past measured work.
 * Reset immediately for a different pull so the previous bar cannot drain backwards.
 */
@Composable
internal fun rememberPullProgress(
    measured: Float,
    phase: RunnerPhase.Working,
    reduced: Boolean,
): State<Float> = key(phase) {
    animateFloatAsState(
        targetValue = measured.coerceIn(0f, 1f),
        animationSpec = if (reduced) snap() else Motion.measuredProgress(),
        label = "Measured pull progress",
    )
}
