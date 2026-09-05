// SPDX-License-Identifier: MPL-2.0
// Original contributions Copyright 2026 Nuri Bruner.
package run.nuri.getagrip.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.runtime.*
import kotlinx.coroutines.delay
import run.nuri.getagrip.ui.theme.Motion

/** One lifetime for the hand and graph. A rest (including a paused rest) owns the
 * cue until it ends; without a rest, retain the short timed introduction. */
@Composable
internal fun rememberGripChangeEmphasis(id: String?, holdsForRest: Boolean, reduceMotion: Boolean): Animatable<Float, androidx.compose.animation.core.AnimationVector1D> {
    val emphasis = remember { Animatable(0f) }
    var previousID by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(id, holdsForRest, reduceMotion) {
        val changed = previousID != id
        previousID = id
        if (id == null) {
            emphasis.snapTo(0f)
            return@LaunchedEffect
        }
        if (changed) emphasis.snapTo(0f)
        if (changed || holdsForRest) {
            if (reduceMotion) emphasis.snapTo(1f)
            else emphasis.animateTo(1f, Motion.gripChangeIn())
            if (holdsForRest) return@LaunchedEffect
            delay(Motion.GRIP_CHANGE_HOLD_MILLIS)
        }
        // Leaving rest on the same ID fades out immediately instead of reintroducing it.
        if (reduceMotion) emphasis.snapTo(0f)
        else emphasis.animateTo(0f, Motion.gripChangeOut())
    }
    return emphasis
}
