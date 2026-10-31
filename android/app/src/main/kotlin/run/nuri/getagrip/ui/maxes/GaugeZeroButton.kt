// SPDX-License-Identifier: MPL-2.0
// Original contributions Copyright 2026 Nuri Bruner.

package run.nuri.getagrip.ui.maxes

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import run.nuri.getagrip.ble.StreamStartCause
import run.nuri.getagrip.engine.RunnerPhase
import run.nuri.getagrip.store.GaugeTareMode
import run.nuri.getagrip.store.LocalDeviceStore
import run.nuri.getagrip.store.TareConfirmationDecision
import run.nuri.getagrip.store.TarePolicy
import run.nuri.getagrip.ui.components.DockButton
import run.nuri.getagrip.ui.l10n.tr
import run.nuri.getagrip.ui.theme.LocalGripPalette
import run.nuri.getagrip.ui.units.WeightUnits

/// Tare the gauge: a confirmation quoting the real reading when there is load on it, a
/// direct tare otherwise.
///
/// **Wake is a fallback** (`TarePolicy.gaugeMode`): on a screen that keeps its stream alive
/// (the live gauge, the max test) silence is re-kicked automatically and the control stays
/// Tare — disabled, with the reason spoken — until the readings return. Wake appears only
/// once the automatic restarts have given up, or on a screen that runs no keeper.
///
/// Shared by the live gauge, the max test and the critical force test (iOS:
/// `GaugeTareDockButton`); `canTare` is false once a measurement runs, and is re-read when
/// the confirmation is answered, so a dialog left open across the start of a measurement
/// can never zero a live one.
@Composable
fun GaugeZeroButton(
    canTare: Boolean,
    modifier: Modifier = Modifier,
    /// The title while a live reading makes it a tare: "Tare", the one word both apps use.
    liveTitle: String = tr("Tare"),
    /// Spoken while `canTare` is false — why the control is off (the max visit: "Let go of
    /// the edge first.").
    disabledReason: String? = null,
    /// Runs just before a tare is written — the live gauge drops a pull in progress, which a
    /// new zero would otherwise read as ending.
    willTare: () -> Unit = {},
) {
    val device = LocalDeviceStore.current
    val palette = LocalGripPalette.current
    val haptics = LocalHapticFeedback.current
    val allowed by rememberUpdatedState(canTare)
    var promptedKg by remember { mutableStateOf<Double?>(null) }
    var promptedEpoch by remember { mutableStateOf(0uL) }

    val mode = TarePolicy.gaugeMode(
        isStreaming = device.isStreaming,
        isReadingLive = device.isReadingLive,
        keepsStreamAlive = device.keepsStreamAlive,
        rekickExhausted = device.streamRekickExhausted,
    )
    val currentWillTare by rememberUpdatedState(willTare)
    fun tareNow() {
        currentWillTare()
        device.tare()
        // The haptic names its cause: fire only when the tare did something.
        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
    }
    val reason = if (canTare) mode.reason else disabledReason

    // A dock action: every screen that zeroes the gauge carries it in its dock.
    DockButton(
        title = if (mode.isWake) tr("Wake") else liveTitle,
        icon = Icons.Outlined.Refresh,
        enabled = canTare && device.state.isConnected && mode.isEnabled,
        modifier = if (reason != null) modifier.semantics { stateDescription = reason } else modifier,
    ) {
        if (!allowed || !device.state.isConnected) return@DockButton
        // Re-read at the tap, never the rendered mode: the stream may have gone quiet since.
        val now = TarePolicy.gaugeMode(
            isStreaming = device.isStreaming,
            isReadingLive = device.isReadingLive,
            keepsStreamAlive = device.keepsStreamAlive,
            rekickExhausted = device.streamRekickExhausted,
        )
        when {
            now.isWake -> device.startStreaming(StreamStartCause.manualWake)
            now != GaugeTareMode.tare -> Unit
            // The exact age at ACTION time: the flag lags up to one watchdog tick. Drawn as
            // Tare, stale by the tap: never a zero. A keeper is already re-kicking; without
            // one, wake as this always did.
            !TarePolicy.isSafeToTareNow(device.secondsSinceLastSample(), device.tareReadingMaxAge) ->
                if (!device.keepsStreamAlive) device.startStreaming(StreamStartCause.manualWake)
            TarePolicy.shouldConfirm(device.currentKg) -> {
                promptedKg = device.currentKg
                promptedEpoch = device.connectionEpoch
            }
            else -> tareNow()
        }
    }

    val prompted = promptedKg
    if (prompted != null) {
        // Taring under load — see the confirmation in `GaugeScreen`; same revalidation.
        AlertDialog(
            onDismissRequest = { promptedKg = null },
            title = { Text(tr("Tare under load?")) },
            text = {
                Text(
                    WeightUnits.tr(
                        "There's %s %s on the gauge. Taring now counts it as zero.",
                        WeightUnits.number(if (prompted.isFinite()) prompted else 0.0, 1),
                        WeightUnits.symbol,
                    ),
                )
            },
            confirmButton = {
                TextButton(onClick = confirm@{
                    if (!allowed) {
                        promptedKg = null
                        return@confirm
                    }
                    when (
                        TarePolicy.confirmationDecision(
                            promptedKg = prompted,
                            currentKg = device.currentKg,
                            promptedEpoch = promptedEpoch,
                            currentEpoch = device.connectionEpoch,
                            isConnected = device.state.isConnected,
                            sampleAge = device.secondsSinceLastSample(),
                            phase = RunnerPhase.Idle,
                            maxAgeSeconds = device.tareReadingMaxAge,
                        )
                    ) {
                        TareConfirmationDecision.tare -> {
                            promptedKg = null
                            tareNow()
                        }
                        // The load moved while the alert was open: ask again with the true number.
                        TareConfirmationDecision.reask -> {
                            promptedKg = device.currentKg
                            promptedEpoch = device.connectionEpoch
                        }
                        TareConfirmationDecision.reject -> promptedKg = null
                    }
                }) { Text(tr("Tare")) }
            },
            dismissButton = { TextButton(onClick = { promptedKg = null }) { Text(tr("Cancel")) } },
            containerColor = palette.card,
            titleContentColor = palette.inkPrimary,
            textContentColor = palette.inkSecondary,
        )
    }
}
