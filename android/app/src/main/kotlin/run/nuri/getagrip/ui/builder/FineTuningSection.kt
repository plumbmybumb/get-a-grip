// SPDX-License-Identifier: MPL-2.0
// Original contributions Copyright 2026 Nuri Bruner.

package run.nuri.getagrip.ui.builder

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.outlined.MonitorHeart
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import run.nuri.getagrip.ui.theme.InstrumentSurface as Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import run.nuri.getagrip.ble.StreamStartCause
import run.nuri.getagrip.ble.StreamStopCause
import run.nuri.getagrip.engine.L10n
import run.nuri.getagrip.engine.RoutineDraft
import run.nuri.getagrip.store.LocalDeviceStore
import run.nuri.getagrip.ui.components.CapsLabel
import run.nuri.getagrip.ui.components.IntValueRow
import run.nuri.getagrip.ui.components.SecondaryButton
import run.nuri.getagrip.ui.components.ValueControl
import run.nuri.getagrip.ui.components.ValueRow
import run.nuri.getagrip.ui.components.pressFeedback
import run.nuri.getagrip.ui.l10n.tr
import run.nuri.getagrip.ui.theme.GetAGripTheme
import run.nuri.getagrip.ui.theme.LocalGripPalette
import run.nuri.getagrip.ui.theme.Metrics
import run.nuri.getagrip.ui.theme.Motion
import run.nuri.getagrip.ui.theme.rememberReduceMotion

/// FINE TUNING — the settings that are not one of the things setup asks about, folded away
/// behind a row that still says out loud that they exist.
///
/// Collapsed on EVERY open and never persisted: the discipline is to hide the WORDS rather
/// than the fact that there is a setting. That is why the row keeps a title and a summary
/// line on its face instead of being a bare chevron — someone who has never opened it still
/// knows what is in there.
@Composable
fun FineTuningSection(
    draft: RoutineDraft,
    modifier: Modifier = Modifier,
    onChange: (RoutineDraft) -> Unit,
) {
    val palette = LocalGripPalette.current
    val reduceMotion = rememberReduceMotion()
    /// View-local and unpersisted BY CONSTRUCTION — a fresh section is built every time the
    /// builder opens, so "collapsed on every open" needs no resetting logic.
    var isOpen by remember { mutableStateOf(false) }
    val chevron by animateFloatAsState(
        targetValue = if (isOpen) 180f else 0f,
        animationSpec = Motion.state(reduceMotion),
        label = "fineTuningChevron",
    )

    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(Metrics.radiusCard),
        color = palette.card,
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
            val interactionSource = remember { MutableInteractionSource() }
            Row(
                Modifier
                    .fillMaxWidth()
                    .heightIn(min = 44.dp)
                    .semantics(mergeDescendants = true) {
                        role = Role.Button
                        contentDescription = L10n.tr("Fine tuning")
                        stateDescription = L10n.tr(if (isOpen) "Expanded" else "Collapsed")
                    }
                    .clickable(interactionSource = interactionSource, indication = null) {
                        isOpen = !isOpen
                    }
                    .pressFeedback(interactionSource, scales = false),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    Icons.Outlined.Tune,
                    contentDescription = null,
                    tint = palette.graphite,
                    modifier = Modifier.size(20.dp),
                )
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(
                        tr("Fine tuning"),
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = palette.inkPrimary,
                    )
                    if (!isOpen) {
                        Text(
                            tr("What counts as a pull, whether the range pauses you, and the lead-in."),
                            style = MaterialTheme.typography.bodySmall,
                            fontWeight = FontWeight.Medium,
                            color = palette.inkSecondary,
                        )
                    }
                }
                Icon(
                    Icons.Filled.KeyboardArrowDown,
                    contentDescription = null,
                    tint = palette.inkTertiary,
                    modifier = Modifier.size(20.dp).rotate(chevron),
                )
            }

            // A disclosure is the ladder's DEFAULT motion — critically damped, and flat under
        // Reduce Motion. Compose's own default here is an unguarded 400 ms tween nothing in
        // this app chose.
        AnimatedVisibility(
            visible = isOpen,
            enter = expandVertically(Motion.state(rememberReduceMotion())) +
                fadeIn(Motion.state(rememberReduceMotion())),
            exit = shrinkVertically(Motion.state(rememberReduceMotion())) +
                fadeOut(Motion.state(rememberReduceMotion())),
        ) {
                Column(verticalArrangement = Arrangement.spacedBy(18.dp)) {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text(
                            tr("A pull counts above"),
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = palette.inkPrimary,
                        )
                        ValueRow(
                            title = tr("A pull counts above"),
                            value = draft.plan.thresholdKg,
                            range = 0.5..10.0,
                            unit = tr("kg"),
                            limit = 0.5..30.0,
                            step = 0.5,
                            presets = listOf(1.0, 2.0, 3.0, 5.0),
                            decimals = 1,
                            caption = tr("Below this, the clock stops."),
                        ) { onChange(draft.copy(plan = draft.plan.copy(thresholdKg = it))) }
                        ThresholdGaugeStrip(draft.plan.thresholdKg)
                    }

                    // Sits between the threshold and the lead-in on purpose: all three
                    // answer "what counts as a pull", and this is the RANGE's half of that
                    // question where the threshold above is the floor's half.
                    ToggleRow(
                        title = tr("Pause when I'm out of range"),
                        checked = draft.plan.pausesOutsideTargetBand,
                        explainer = if (draft.plan.pausesOutsideTargetBand) {
                            tr("The clock only runs while you are inside the target range.")
                        } else {
                            tr("The clock runs whenever you are on the edge, whatever the load — the range is still drawn, it just stops judging. Letting go still stops the rep.")
                        },
                    ) { onChange(draft.copy(plan = draft.plan.copy(pausesOutsideTargetBand = it))) }

                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text(
                            tr("Lead-in before each set"),
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = palette.inkPrimary,
                        )
                        IntValueRow(
                            title = tr("Lead-in before each set"),
                            value = draft.plan.leadInSeconds,
                            range = 0..20,
                            unit = tr("s"),
                            limit = 0..60,
                            step = 5,
                            presets = listOf(0, 3, 5, 10),
                            caption = tr("Time to get your fingers on the edge."),
                        ) { onChange(draft.copy(plan = draft.plan.copy(leadInSeconds = it))) }
                    }
                }
            }
        }
    }
}

/// **THE ONE PLACE THE BUILDER TOUCHES BLUETOOTH.**
///
/// A live force bar with the threshold marked, so "2 kg" can be FELT instead of guessed at.
/// Deliberately its own small composable: everything else in the builder is pure editing of
/// a value type, and keeping the radio in one place means it can be deleted or moved without
/// opening the document.
@Composable
private fun ThresholdGaugeStrip(thresholdKg: Double) {
    val device = LocalDeviceStore.current
    val palette = LocalGripPalette.current
    var checking by remember { mutableStateOf(false) }

    // UNCONDITIONAL, and gated on the DEVICE's own truth rather than on `checking`: a gauge
    // left streaming behind a dismissed screen is a dead battery the user blames on the app.
    DisposableEffect(device) {
        onDispose {
            if (device.isStreaming) device.stopStreaming(StreamStopCause.screenClosed)
        }
    }

    // Long enough to take the load, let go and try again; short enough that a forgotten
    // check cannot flatten the gauge's battery.
    LaunchedEffect(checking) {
        if (!checking) return@LaunchedEffect
        delay(CHECK_SECONDS * 1000L)
        checking = false
        // The cause travels from the TRIGGER: labelling a timeout the same as a deliberate
        // Stop would put the wrong reason in the log.
        if (device.isStreaming) device.stopStreaming(StreamStopCause.timedOut)
    }

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        if (!device.state.isConnected) {
            // SHOWN, not a disabled button: a control you cannot use teaches nothing, and
            // the reason plus the reassurance is the whole content.
            Text(
                tr("Connect your gauge to try it — you can change this any time."),
                style = MaterialTheme.typography.bodySmall,
                fontWeight = FontWeight.Medium,
                color = palette.inkTertiary,
            )
        } else if (checking) {
            // LEAVES, deliberately: `currentKg` changes on every sample, and read from this
            // body it would re-evaluate the Stop button and the static caption beside it at
            // sample rate for the whole fifteen seconds.
            ThresholdReadout(thresholdKg)
            ThresholdBar(thresholdKg)
            Text(
                tr("Pull — anything above the line counts."),
                style = MaterialTheme.typography.bodySmall,
                fontWeight = FontWeight.Medium,
                color = palette.inkTertiary,
            )
            SecondaryButton(tr("Stop"), icon = Icons.Filled.Stop) {
                checking = false
                if (device.isStreaming) device.stopStreaming(StreamStopCause.userStopped)
            }
        } else {
            SecondaryButton(tr("Check on the gauge"), icon = Icons.Outlined.MonitorHeart) {
                // The bar's scale is peak-relative, so a peak left over from an earlier
                // check would otherwise draw this pull as a stub.
                device.resetPeak()
                device.startStreaming(StreamStartCause.manualMeasurement)
                checking = true
            }
        }
    }
}

private const val CHECK_SECONDS = 15

/// The live numeral and the Counting/Stopped word.
@Composable
private fun ThresholdReadout(thresholdKg: Double) {
    val device = LocalDeviceStore.current
    val palette = LocalGripPalette.current
    val haptics = LocalHapticFeedback.current

    /// HYSTERETIC, not a bare `>=`. The whole point of this check is to park a load AT the
    /// threshold, which is exactly where sensor noise flips a bare comparison many times a
    /// second — a continuous buzz and a flickering word. Same remedy as the runner's release
    /// band: crossing UP happens at the threshold, crossing back down only 5 % below it,
    /// clamped 0.5–2.0 kg.
    var crossed by remember { mutableStateOf(false) }
    // ONE coroutine for the life of the strip, fed by a snapshot flow — not a
    // `LaunchedEffect` keyed on the reading, which would cancel and relaunch a coroutine
    // on every one of the ~80 samples a second.
    LaunchedEffect(Unit) {
        snapshotFlow { device.currentKg to thresholdKg }.collect { (kg, threshold) ->
            val band = (threshold * 0.05).coerceIn(0.5, 2.0)
            val next = when {
                kg >= threshold -> true
                kg < threshold - band -> false
                else -> crossed
            }
            if (next != crossed) {
                crossed = next
                haptics.performHapticFeedback(HapticFeedbackType.SegmentTick)
            }
        }
    }
    val kg = device.currentKg

    Row(
        Modifier
            .fillMaxWidth()
            // A numeral changing 80×/sec is unusable under TalkBack; the crossing is felt
            // instead, since nothing here fires the runner's audio cues.
            .clearAndSetSemantics {},
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            kgText(kg),
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium,
            color = if (crossed) palette.bleu else palette.inkPrimary,
        )
        Text(tr("kg"), style = MaterialTheme.typography.bodySmall, color = palette.inkTertiary)
        Box(Modifier.weight(1f))
        // A WORD as well as a colour: the state has to survive greyscale.
        CapsLabel(
            if (crossed) tr("Counting") else tr("Stopped"),
            color = if (crossed) palette.bleu else palette.inkTertiary,
        )
    }
}

/// The live force bar with the threshold marked. Its own leaf for the same invalidation
/// reason as the readout.
@Composable
private fun ThresholdBar(thresholdKg: Double) {
    val device = LocalDeviceStore.current
    val palette = LocalGripPalette.current
    val kg = device.currentKg
    val isOver = kg >= thresholdKg
    /// PEAK-relative, and peak is monotonic within one check, so the scale only ever settles
    /// outward — a ceiling keyed to the live reading would jitter the threshold marker on
    /// every sample, which is the one thing on screen that must hold still.
    val ceiling = maxOf(10.0, device.peakKg * 1.25, thresholdKg * 1.6)
    val fraction = (kg.coerceAtLeast(0.0) / ceiling).coerceAtMost(1.0).toFloat()
    val markerFraction = (thresholdKg / ceiling).coerceIn(0.0, 1.0).toFloat()

    Canvas(Modifier.fillMaxWidth().height(26.dp).clearAndSetSemantics {}) {
        val radius = CornerRadius(size.height / 2f)
        drawRoundRect(
            color = palette.inkTertiary.copy(alpha = 0.18f),
            size = size,
            cornerRadius = radius,
        )
        // A full-bleed capsule CLIPPED to the fraction, not a second capsule at partial
        // width: a width-constrained capsule degenerates to a blob at small fractions and
        // its advancing edge is rounded like an end, so the fill reads as a different shape
        // than the track it sits in.
        clipRect(right = maxOf(2f, fraction * size.width)) {
            drawRoundRect(
                color = if (isOver) palette.bleu else palette.calm,
                size = size,
                cornerRadius = radius,
            )
        }
        // The line itself, drawn over the fill so it stays visible once the pull has passed
        // it.
        drawRoundRect(
            color = palette.inkPrimary.copy(alpha = 0.8f),
            topLeft = Offset(markerFraction * size.width - 1.dp.toPx(), 0f),
            size = Size(2.dp.toPx(), size.height),
        )
    }
}

@Preview(name = "FineTuningSection", showBackground = true, widthDp = 380)
@Composable
private fun FineTuningSectionPreview() {
    GetAGripTheme {
        var draft by remember { mutableStateOf(RoutineDraft.starter) }
        Column(Modifier.padding(16.dp)) {
            FineTuningSection(draft) { draft = it }
        }
    }
}
