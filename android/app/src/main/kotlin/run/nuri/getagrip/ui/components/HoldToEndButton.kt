// SPDX-License-Identifier: MPL-2.0
// Original contributions Copyright 2026 Nuri Bruner.

package run.nuri.getagrip.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import run.nuri.getagrip.ui.l10n.tr
import run.nuri.getagrip.ui.theme.GetAGripTheme
import run.nuri.getagrip.ui.theme.LocalGripPalette
import run.nuri.getagrip.ui.theme.Motion
import run.nuri.getagrip.ui.theme.rememberReduceMotion

/// Ending a session takes a deliberate HOLD, not a tap plus a dialog.
///
/// A confirmation sheet mid-workout is two taps with chalk on your hands, and the second one
/// is the reflex you learn to fire without reading. A hold carries the same "are you sure"
/// in the gesture itself: the button fills while you mean it, and letting go early costs
/// nothing. Nothing is destroyed either way — everything already done is kept.
@Composable
fun HoldToEndButton(modifier: Modifier = Modifier, onEnd: () -> Unit) {
    HoldButton(
        modifier = modifier,
        idleLabel = tr("Hold to end"),
        holdingLabel = tr("Keep holding…"),
        spokenLabel = tr("End session"),
        trackAlpha = 0.16f,
        fillAlpha = 0.42f,
        // Not inside a scroller, so the whole button plus a generous slop is the hold area —
        // a thumb that drifts a few dp during a 0.9 s press has not changed its mind.
        cancel = HoldCancel.LeavesBounds(SLIDE_SLOP_DP),
        onFire = onEnd,
    )
}

/// Discarding a finished session takes a deliberate HOLD, exactly like ending one.
///
/// Shares the shape and the 0.9 s: one gesture vocabulary for "this cannot be undone",
/// learned once. It is quieter than Save — a lighter fill — because throwing the session
/// away is the rarer answer and must never be the reflex.
@Composable
fun HoldToDiscardButton(modifier: Modifier = Modifier, onDiscard: () -> Unit) {
    HoldButton(
        modifier = modifier,
        idleLabel = tr("Hold to discard"),
        holdingLabel = tr("Keep holding…"),
        spokenLabel = tr("Discard this session"),
        trackAlpha = 0.12f,
        fillAlpha = 0.36f,
        // **This one lives inside the summary's scroller**, and the button legitimately sits
        // below the fold on a session with several new maxes. A holding finger is stationary;
        // a scrolling one moves — and the scroll CONSUMES the change, which is the signal
        // this watches for. Cancelling on consumption is the Compose equivalent of iOS's
        // `simultaneousGesture` plus a global-space drift cancel: the page still scrolls, and
        // a scroll can never fire an irreversible discard with no undo behind it.
        cancel = HoldCancel.ConsumedOrDrifts(DRIFT_SLOP_DP),
        onFire = onDiscard,
    )
}

/// Long enough to be deliberate, short enough not to feel like a punishment.
private const val HOLD_MILLIS = 900L
private const val SLIDE_SLOP_DP = 24f
private const val DRIFT_SLOP_DP = 10f

private sealed interface HoldCancel {
    /// Cancel once the finger leaves the button's bounds inflated by `slopDp`.
    data class LeavesBounds(val slopDp: Float) : HoldCancel

    /// Cancel once the finger has drifted `slopDp`, or once an ancestor (a scroller) has
    /// consumed the movement.
    data class ConsumedOrDrifts(val slopDp: Float) : HoldCancel
}

@Composable
private fun HoldButton(
    modifier: Modifier,
    idleLabel: String,
    holdingLabel: String,
    spokenLabel: String,
    trackAlpha: Float,
    fillAlpha: Float,
    cancel: HoldCancel,
    onFire: () -> Unit,
) {
    val palette = LocalGripPalette.current
    val reduceMotion = rememberReduceMotion()
    val haptics = LocalHapticFeedback.current
    val scope = rememberCoroutineScope()
    val progress = remember { Animatable(0f) }

    /// SEPARATE from `progress`, and that is the entire fix for the overlap iOS hit. The
    /// label used to read `progress > 0`, so it changed INSIDE the 0.9 s transaction that
    /// drives the fill — and a Text whose content changes under an animation cross-fades.
    /// Two strings of different widths, both half-opaque, sat on top of each other for the
    /// whole hold. A plain Boolean flipped outside the animation swaps the label instantly.
    var isHolding by remember { mutableStateOf(false) }
    var holdJob by remember { mutableStateOf<Job?>(null) }

    fun cancelHold() {
        holdJob?.cancel()
        holdJob = null
        isHolding = false
        scope.launch { progress.animateTo(0f, Motion.state(reduceMotion)) }
    }

    // A running pointer handler must fire the current action after recomposition.
    val currentOnFire by rememberUpdatedState(onFire)

    fun beginHold() {
        if (holdJob != null) return
        isHolding = true
        holdJob = scope.launch {
            // UNCONDITIONAL — deliberately not gated on reduce motion. This fill is the
            // functional progress readout for a 0.9 s hold-to-confirm gesture (how much
            // longer to keep holding), not decorative motion; snapping straight to a full bar
            // would remove the one signal that the hold is registering at all, while the
            // gesture itself still takes exactly 0.9 s either way. It also has to match the
            // real sleep below, which no motion token can express.
            launch { progress.animateTo(1f, tween(HOLD_MILLIS.toInt(), easing = LinearEasing)) }
            delay(HOLD_MILLIS)
            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
            currentOnFire()
        }
    }

    Box(
        modifier
            .fillMaxWidth()
            .height(48.dp)
            // **Clipped to the button's OWN capsule.** iOS learned this the hard way: a
            // second capsule sized to `progress * width` draws its own fully rounded outline
            // at that width — bigger than the button at low progress, and degenerating into a
            // circle at small values. Clipping the PARENT and letting a plain rectangle grow
            // inside it keeps the fill's outline exactly the button's outline: rounded
            // leading edge, straight trailing sweep, never past the bounds.
            .clip(CircleShape)
            .background(palette.alarm.copy(alpha = trackAlpha))
            .pointerInput(cancel) {
                val slopPx = when (cancel) {
                    is HoldCancel.LeavesBounds -> cancel.slopDp.dp.toPx()
                    is HoldCancel.ConsumedOrDrifts -> cancel.slopDp.dp.toPx()
                }
                awaitEachGesture {
                    // The Initial pass, so the fill starts on touch-DOWN. A long-press
                    // detector gives no progress to draw until it has already succeeded.
                    val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                    beginHold()
                    var slidOff = false
                    while (true) {
                        // The FINAL pass is where an ancestor's scroll has already had its
                        // say, so `isConsumed` here means "the page took this touch".
                        val event = awaitPointerEvent(PointerEventPass.Final)
                        val change = event.changes.firstOrNull { it.id == down.id }
                        if (change == null || !change.pressed) break
                        if (slidOff) continue
                        val left = when (cancel) {
                            is HoldCancel.LeavesBounds -> !Rect(
                                -slopPx,
                                -slopPx,
                                size.width + slopPx,
                                size.height + slopPx,
                            ).contains(change.position)

                            is HoldCancel.ConsumedOrDrifts -> change.isConsumed ||
                                (change.position - down.position).getDistance() > slopPx
                        }
                        if (left) {
                            slidOff = true
                            cancelHold()
                        }
                    }
                    cancelHold()
                }
            }
            // TalkBack cannot express a hold, so an activation ends it outright — the gesture
            // is the safeguard for a thumb, not a substitute for the action.
            .semantics(mergeDescendants = true) {
                role = Role.Button
                contentDescription = spokenLabel
                onClick(label = spokenLabel) {
                    currentOnFire()
                    true
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier
                .fillMaxWidth(progress.value)
                .fillMaxHeight()
                .background(palette.alarm.copy(alpha = fillAlpha)),
        )
        Text(
            if (isHolding) holdingLabel else idleLabel,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
            color = palette.alarm,
            maxLines = 1,
        )
    }
}

@Preview(name = "Hold buttons", showBackground = true, widthDp = 360, heightDp = 140)
@Composable
private fun HoldButtonsPreview() {
    GetAGripTheme {
        androidx.compose.foundation.layout.Column(
            verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(12.dp),
        ) {
            HoldToEndButton {}
            HoldToDiscardButton {}
        }
    }
}
