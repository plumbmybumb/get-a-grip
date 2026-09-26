// SPDX-License-Identifier: MPL-2.0
// Original contributions Copyright 2026 Nuri Bruner.

package run.nuri.getagrip.ui.builder

import run.nuri.getagrip.ui.units.WeightUnits

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
import androidx.compose.runtime.saveable.rememberSaveable
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
import run.nuri.getagrip.engine.SessionPlan
import run.nuri.getagrip.store.LocalDeviceStore
import run.nuri.getagrip.ui.components.CapsLabel
import run.nuri.getagrip.ui.components.IntValueRow
import run.nuri.getagrip.ui.components.SecondaryButton
import run.nuri.getagrip.ui.components.ValueRow
import run.nuri.getagrip.ui.components.pressFeedback
import run.nuri.getagrip.ui.l10n.tr
import run.nuri.getagrip.ui.theme.GetAGripTheme
import run.nuri.getagrip.ui.theme.LocalGripPalette
import run.nuri.getagrip.ui.theme.Metrics
import run.nuri.getagrip.ui.theme.Motion
import run.nuri.getagrip.ui.theme.rememberReduceMotion

/// FINE TUNING — settings setup does not ask about, folded behind a row that still says they
/// exist.
///
/// Collapsed on EVERY open, never persisted: hide the WORDS, not the fact of a setting. So the
/// row keeps a title and summary instead of a bare chevron.
@Composable
fun FineTuningSection(
    /// Only what this card draws — see `FineTuningValues`.
    values: FineTuningValues,
    modifier: Modifier = Modifier,
    update: DraftUpdate,
) {
    val palette = LocalGripPalette.current
    fun edit(transform: (SessionPlan) -> SessionPlan) = update { it.copy(plan = transform(it.plan)) }
    val reduceMotion = rememberReduceMotion()
    /// Unpersisted BY CONSTRUCTION: each builder open builds a fresh section. Saved only across
    /// rotation, which is not an open.
    var isOpen by rememberSaveable { mutableStateOf(false) }
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

            // `Motion.state`, not Compose's unguarded 400 ms default — see `Motion`.
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
                            value = WeightUnits.fromKg(values.thresholdKg),
                            range = WeightUnits.sliderRange(0.5..10.0, 0.1),
                            unit = WeightUnits.symbol,
                            limit = WeightUnits.fromKg(0.5..run.nuri.getagrip.engine.SessionPlan.thresholdRange.endInclusive),
                            step = 0.1,
                            presets = if (WeightUnits.current == run.nuri.getagrip.ui.units.WeightUnit.kg) listOf(1.0, 2.0, 3.0, 5.0) else listOf(2.0, 4.0, 6.0, 10.0),
                            decimals = 1,
                            caption = tr("Below this, the clock stops."),
                        ) { shown -> edit { it.copy(thresholdKg = WeightUnits.toKg(shown)) } }
                        ThresholdGaugeStrip(values.thresholdKg)
                    }

                    // Between threshold and lead-in: all three answer "what counts as a pull", and this is the
                    // RANGE's half where the threshold is the floor's.
                    ToggleRow(
                        title = tr("Pause when I'm out of range"),
                        checked = values.pausesOutsideTargetBand,
                        explainer = if (values.pausesOutsideTargetBand) {
                            tr("The clock only runs while you are inside the target range.")
                        } else {
                            tr("The clock runs whenever you are on the edge, whatever the load — the range is still drawn, it just stops judging. Letting go still stops the rep.")
                        },
                    ) { pauses -> edit { it.copy(pausesOutsideTargetBand = pauses) } }

                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text(
                            tr("Lead-in before each set"),
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = palette.inkPrimary,
                        )
                        IntValueRow(
                            title = tr("Lead-in before each set"),
                            value = values.leadInSeconds,
                            range = 0..20,
                            unit = tr("s"),
                            limit = 0..60,
                            step = 5,
                            presets = listOf(0, 3, 5, 10),
                            caption = tr("Time to get your fingers on the edge."),
                        ) { seconds -> edit { it.copy(leadInSeconds = seconds) } }
                    }
                }
            }
        }
    }
}

/// **THE ONE PLACE THE BUILDER TOUCHES BLUETOOTH.**
///
/// A live force bar with the threshold marked, so "2 kg" is FELT, not guessed. Its own
/// composable, so the radio can be moved or deleted without opening the document.
@Composable
private fun ThresholdGaugeStrip(thresholdKg: Double) {
    val device = LocalDeviceStore.current
    val palette = LocalGripPalette.current
    var checking by remember { mutableStateOf(false) }

    // UNCONDITIONAL, gated on the DEVICE's truth, not `checking`: a gauge left streaming behind
    // a dismissed screen is a dead battery blamed on the app.
    DisposableEffect(device) {
        onDispose {
            if (device.isStreaming) device.stopStreaming(StreamStopCause.screenClosed)
        }
    }

    // Long enough to load, let go and retry; short enough not to flatten a forgotten gauge.
    LaunchedEffect(checking) {
        if (!checking) return@LaunchedEffect
        delay(CHECK_SECONDS * 1000L)
        checking = false
        // The cause comes from the TRIGGER: a timeout is not a deliberate Stop in the log.
        if (device.isStreaming) device.stopStreaming(StreamStopCause.timedOut)
    }

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        if (!device.state.isConnected) {
            // SHOWN, not a disabled button: the reason plus the reassurance is the content.
            Text(
                tr("Connect your gauge to try it — you can change this any time."),
                style = MaterialTheme.typography.bodySmall,
                fontWeight = FontWeight.Medium,
                color = palette.inkTertiary,
            )
        } else if (checking) {
            // LEAVES: `currentKg` changes per sample, and here it would redraw Stop and the caption at
            // sample rate for fifteen seconds.
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
                // Peak-relative scale: a leftover peak would draw this pull as a stub.
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

    /// HYSTERETIC, not a bare `>=`: this check parks a load AT the threshold, where noise flips a
    /// bare comparison many times a second. The runner's release band: up at the threshold,
    /// down 5 % below it, clamped 0.5–2.0 kg.
    var crossed by remember { mutableStateOf(false) }
    // ONE coroutine fed by a snapshot flow, not a `LaunchedEffect` keyed on the reading, which
    // would relaunch ~80 times a second.
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
            // 80×/sec is unusable under TalkBack; the crossing is felt instead.
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
        Text(WeightUnits.symbol, style = MaterialTheme.typography.bodySmall, color = palette.inkTertiary)
        Box(Modifier.weight(1f))
        // A WORD as well as a colour: the state has to survive greyscale.
        CapsLabel(
            if (crossed) tr("Counting") else tr("Stopped"),
            color = if (crossed) palette.bleu else palette.inkTertiary,
        )
    }
}

/// The live force bar with the threshold marked, a leaf like the readout.
@Composable
private fun ThresholdBar(thresholdKg: Double) {
    val device = LocalDeviceStore.current
    val palette = LocalGripPalette.current
    val kg = device.currentKg
    val isOver = kg >= thresholdKg
    /// PEAK-relative, and peak only grows within a check, so the scale settles outward; a live
    /// ceiling would jitter the threshold marker, which must hold still.
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
        // A full-bleed capsule CLIPPED to the fraction: a narrow capsule degenerates to a blob with
        // a rounded advancing edge, a different shape from its track.
        clipRect(right = maxOf(2f, fraction * size.width)) {
            drawRoundRect(
                color = if (isOver) palette.bleu else palette.calm,
                size = size,
                cornerRadius = radius,
            )
        }
        // Over the fill, so it stays visible once the pull passes it.
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
            FineTuningSection(FineTuningValues.of(draft.plan)) { draft = it(draft) }
        }
    }
}
