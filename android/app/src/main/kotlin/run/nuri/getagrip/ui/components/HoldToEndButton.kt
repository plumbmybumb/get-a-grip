// SPDX-License-Identifier: MPL-2.0
// Original contributions Copyright 2026 Nuri Bruner.

package run.nuri.getagrip.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
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
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import run.nuri.getagrip.ui.l10n.tr
import run.nuri.getagrip.ui.theme.GetAGripTheme
import run.nuri.getagrip.ui.theme.LocalGripPalette
import run.nuri.getagrip.ui.theme.Motion
import run.nuri.getagrip.ui.theme.Metrics
import run.nuri.getagrip.ui.theme.rememberReduceMotion

/// Ending a session takes a deliberate HOLD, not a tap plus a dialog.
///
/// A mid-workout confirmation is two taps with chalk on your hands, and the second becomes a
/// reflex. The button fills while you mean it; letting go early costs nothing. Everything
/// already done is kept either way.
@Composable
fun HoldToEndButton(modifier: Modifier = Modifier, onEnd: () -> Unit) {
    HoldButton(
        modifier = modifier,
        idleLabel = tr("Hold to end"),
        holdingLabel = tr("Keep holding…"),
        spokenLabel = tr("End session"),
        trackAlpha = 0.16f,
        fillAlpha = 0.42f,
        // Generous slop for chalky fingers; a consumed scroll still cancels in the large-text
        // scrolling layout.
        cancel = HoldCancel.LeavesBounds(SLIDE_SLOP_DP),
        onFire = onEnd,
    )
}

/// Discarding a finished session takes the same 0.9 s HOLD: one gesture for "cannot be
/// undone". A lighter fill than Save, because discarding is rarer and must never be the reflex.
@Composable
fun HoldToDiscardButton(modifier: Modifier = Modifier, onDiscard: () -> Unit) {
    HoldButton(
        modifier = modifier,
        idleLabel = tr("Hold to discard"),
        holdingLabel = tr("Keep holding…"),
        spokenLabel = tr("Discard this session"),
        trackAlpha = 0.12f,
        fillAlpha = 0.36f,
        // **Inside the summary's scroller**, often below the fold. A scroll CONSUMES the movement,
        // which is the cancel signal (iOS: `simultaneousGesture` plus a drift cancel): the page still
        // scrolls, and a scroll can never fire an irreversible discard.
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

    /// SEPARATE from `progress` — the fix for the overlap iOS hit: a label keyed on `progress > 0`
    /// changed inside the fill's animation and cross-faded two strings over each other for the
    /// whole hold. A Boolean flipped outside it swaps instantly.
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
            // UNCONDITIONAL, not gated on reduce motion: this fill is the functional readout of how
            // much longer to hold, not decoration, and it must track the real sleep below.
            launch { progress.animateTo(1f, tween(HOLD_MILLIS.toInt(), easing = LinearEasing)) }
            delay(HOLD_MILLIS)
            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
            currentOnFire()
        }
    }

    Box(
        modifier
            .fillMaxWidth()
            .heightIn(min = Metrics.controlMinHeight)
            // **Clipped to the button's OWN capsule.** A second capsule sized to `progress * width`
            // outgrew the button at low progress and became a circle (iOS). Clipping the PARENT keeps
            // the fill's outline the button's: rounded leading edge, straight sweep, never past bounds.
            .clip(CircleShape)
            .background(palette.alarm.copy(alpha = trackAlpha))
            .pointerInput(cancel) {
                val slopPx = when (cancel) {
                    is HoldCancel.LeavesBounds -> cancel.slopDp.dp.toPx()
                    is HoldCancel.ConsumedOrDrifts -> cancel.slopDp.dp.toPx()
                }
                awaitEachGesture {
                    // The Initial pass, so the fill starts on touch-DOWN; a long-press detector reports nothing
                    // until it has succeeded.
                    val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                    beginHold()
                    var slidOff = false
                    while (true) {
                        // The FINAL pass: `isConsumed` here means an ancestor's scroll took the touch.
                        val event = awaitPointerEvent(PointerEventPass.Final)
                        val change = event.changes.firstOrNull { it.id == down.id }
                        if (change == null || !change.pressed) break
                        if (slidOff) continue
                        val left = when (cancel) {
                            is HoldCancel.LeavesBounds -> change.isConsumed || !Rect(
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
            // TalkBack cannot express a hold, so activation ends outright: the gesture guards a thumb,
            // it is not the safeguard itself.
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
        // Decoration must not measure the button: the label stays in charge of height.
        Canvas(Modifier.matchParentSize()) {
            drawRect(palette.alarm.copy(alpha = fillAlpha),
                size = Size(size.width * progress.value, size.height))
        }
        // Reserve both labels, so pressing never moves the control under the finger.
        Box(Modifier.padding(horizontal = Metrics.buttonHorizontalPadding,
            vertical = Metrics.buttonVerticalPadding), contentAlignment = Alignment.Center) {
            for ((label, visible) in listOf(idleLabel to !isHolding, holdingLabel to isHolding)) {
                Text(label, style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold, color = palette.alarm,
                    textAlign = TextAlign.Center,
                    modifier = if (visible) Modifier else Modifier.alpha(0f).clearAndSetSemantics {})
            }
        }
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
