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
import run.nuri.getagrip.ble.StreamStartCause
import run.nuri.getagrip.engine.RunnerPhase
import run.nuri.getagrip.store.LocalDeviceStore
import run.nuri.getagrip.store.TareConfirmationDecision
import run.nuri.getagrip.store.TarePolicy
import run.nuri.getagrip.store.TareTapDecision
import run.nuri.getagrip.ui.components.SecondaryButton
import run.nuri.getagrip.ui.l10n.tr
import run.nuri.getagrip.ui.theme.LocalGripPalette
import run.nuri.getagrip.ui.units.WeightUnits

/// Zero the gauge before a measurement: "Wake" while the stream is silent, a confirmation
/// quoting the real reading when there is load on it, a direct tare otherwise.
///
/// Shared by the max test and the critical force test (iOS: `GaugeZeroButton`); `canTare`
/// is false once a measurement runs, and is re-read when the confirmation is answered, so
/// a dialog left open across the start of a measurement can never zero a live one.
@Composable
fun GaugeZeroButton(canTare: Boolean, modifier: Modifier = Modifier) {
    val device = LocalDeviceStore.current
    val palette = LocalGripPalette.current
    val haptics = LocalHapticFeedback.current
    val allowed by rememberUpdatedState(canTare)
    var promptedKg by remember { mutableStateOf<Double?>(null) }
    var promptedEpoch by remember { mutableStateOf(0uL) }

    SecondaryButton(
        title = if (device.isReadingLive) tr("Zero the gauge") else tr("Wake"),
        icon = Icons.Outlined.Refresh,
        enabled = canTare,
        modifier = modifier,
    ) {
        if (!allowed || !device.state.isConnected) return@SecondaryButton
        when (TarePolicy.tapDecision(phase = RunnerPhase.Idle,
            isReadingLive = device.isReadingLive, isLoadedForTare = device.isLoadedForTare)) {
            TareTapDecision.wakeStream -> device.startStreaming(StreamStartCause.manualWake)
            TareTapDecision.blocked -> Unit
            TareTapDecision.confirm -> {
                promptedKg = device.currentKg
                promptedEpoch = device.connectionEpoch
            }
            TareTapDecision.tare -> {
                if (TarePolicy.isSafeToTareNow(device.secondsSinceLastSample(), device.tareReadingMaxAge)) {
                    device.tare()
                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                } else device.startStreaming(StreamStartCause.manualWake)
            }
        }
    }

    val prompted = promptedKg
    if (prompted != null) {
        // Taring under load — see the confirmation in `GaugeScreen`; same revalidation.
        AlertDialog(
            onDismissRequest = { promptedKg = null },
            title = { Text(tr("Zero the gauge?")) },
            text = {
                Text(
                    WeightUnits.tr(
                        "There is %s kg on the gauge. Taring now makes that the new zero for this measurement.",
                        WeightUnits.number(if (prompted.isFinite()) prompted else 0.0, 1),
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
                            device.tare()
                            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
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
