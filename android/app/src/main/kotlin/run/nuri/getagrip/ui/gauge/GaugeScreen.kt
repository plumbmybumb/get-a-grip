// SPDX-License-Identifier: MPL-2.0
// Original contributions Copyright 2026 Nuri Bruner.

package run.nuri.getagrip.ui.gauge

import run.nuri.getagrip.ui.units.WeightUnits

import run.nuri.getagrip.ui.components.LocalFloatingTabBarInset
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.SettingsInputAntenna
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.activity.compose.BackHandler
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import run.nuri.getagrip.ble.MockForceProfile
import run.nuri.getagrip.ble.MockProgressorClient
import run.nuri.getagrip.ble.ProgressorConnectionState
import run.nuri.getagrip.ble.StreamStartCause
import run.nuri.getagrip.ble.StreamStopCause
import run.nuri.getagrip.engine.Fmt
import run.nuri.getagrip.ble.GaugeCalibrationStatus
import run.nuri.getagrip.engine.L10n
import run.nuri.getagrip.engine.RunnerPhase
import run.nuri.getagrip.runner.KeepScreenOn
import run.nuri.getagrip.store.DeviceStore
import run.nuri.getagrip.store.LocalDeviceStore
import run.nuri.getagrip.store.TareConfirmationDecision
import run.nuri.getagrip.store.TarePolicy
import run.nuri.getagrip.store.TareTapDecision
import run.nuri.getagrip.ui.components.CapsLabel
import run.nuri.getagrip.ui.components.ForceTraceView
import run.nuri.getagrip.ui.components.PrimaryButton
import run.nuri.getagrip.ui.components.SecondaryButton
import run.nuri.getagrip.ui.l10n.tr
import run.nuri.getagrip.ui.theme.GetAGripTheme
import run.nuri.getagrip.ui.theme.LocalGripPalette
import run.nuri.getagrip.ui.theme.readablePageWidth
import run.nuri.getagrip.ui.theme.Metrics

/// `sp`, not `dp`: a bare `dp` numeral stays fixed at every accessibility setting while the
/// controls around it grow.
private val HERO_SIZE = 78.sp
private val UNIT_SIZE = 22.sp
private val TRACE_HEIGHT = 190.dp

/// The live force gauge — connect, tare, stream, decode, draw — showing the raw truth
/// (current, peak, battery, one-second mean), not a prettified summary.
///
/// **No threshold rule and no target lane**: both belong to a rep asking for a load, and
/// this screen asks for nothing.
@Composable
fun GaugeScreen(modifier: Modifier = Modifier) {
    val device = LocalDeviceStore.current
    val palette = LocalGripPalette.current
    val haptics = LocalHapticFeedback.current

    /// The kilogram figure the confirmation was raised against: the alert quotes it and
    /// `TarePolicy.confirmationDecision` revalidates against it.
    var promptedKg by remember { mutableStateOf<Double?>(null) }
    var promptedEpoch by remember { mutableStateOf(0uL) }

    // Awake while readings arrive: the phone timing out mid-pull is the app going blind.
    KeepScreenOn(device.isStreaming)

    DisposableEffect(device) {
        onDispose {
            // Never leave the device streaming behind us: it drains its battery and keeps the radio busy.
            if (device.isStreaming) device.stopStreaming(StreamStopCause.screenClosed)
        }
    }

    fun tareNow() {
        device.tare()
        // **The haptic names its cause**: fire only when the tare did something. On every gauge but
        // the Progressor the zero is app-side and no-ops with no reading yet.
        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .readablePageWidth()
            .padding(horizontal = Metrics.hPadding)
            .padding(bottom = Metrics.spacing + LocalFloatingTabBarInset.current),
        verticalArrangement = Arrangement.spacedBy(18.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        // LEAVES: `currentKg`, `peakKg` and `trace` mutate per sample, and read here would
        // invalidate the whole screen 80×/second.
        GaugeHero()
        Surface(
            shape = RoundedCornerShape(Metrics.radiusCard),
            color = palette.card,
            modifier = Modifier
                .widthIn(max = Metrics.maxContentWidth)
                .fillMaxWidth()
                .height(TRACE_HEIGHT),
        ) {
            ForceTraceView(
                modifier = Modifier.fillMaxSize().padding(horizontal = 4.dp),
                tint = if (device.isStreaming) palette.bleu else palette.inkTertiary,
                lit = true,
            )
        }
        // Only once a stream was ASKED for: connected-and-idle is not silent, just not started
        // (iOS shows this in the runner, where the stream is always requested).
        if (device.state.isConnected && device.isStreaming && !device.isSignalFresh) {
            WaitingForGauge()
        }
        GaugeReadouts()
        Spacer(Modifier.weight(1f))

        if (device.state.isConnected) {
            // A remotely calibrated gauge can be connected with no force to show; say why rather than
            // display a guess (Frez's rule), only for the gauge that has a why.
            calibrationNote(device.calibrationStatus)?.let { note ->
                Text(
                    note,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (device.calibrationStatus.isReady) palette.inkTertiary else palette.armed,
                    textAlign = TextAlign.Center,
                    modifier = Modifier
                        .widthIn(max = Metrics.maxContentWidth)
                        .fillMaxWidth()
                        .testTag("gauge.calibration"),
                )
            }
            Row(
                Modifier.widthIn(max = Metrics.maxContentWidth).fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                SecondaryButton(
                    // **The label follows the DECISION, not the intent.** With no live reading the tap wakes
                    // the stream instead of taring; a button saying Tare that does something else is a lie.
                    title = if (device.isReadingLive) tr("Tare") else tr("Wake"),
                    icon = Icons.Outlined.Refresh,
                    modifier = Modifier.weight(1f),
                ) {
                    when (
                        TarePolicy.tapDecision(
                            phase = RunnerPhase.Idle,
                            isReadingLive = device.isReadingLive,
                            isLoadedForTare = device.isLoadedForTare,
                        )
                    ) {
                        TareTapDecision.wakeStream ->
                            device.startStreaming(StreamStartCause.manualWake)
                        // Unreachable here (no session, phase always `Idle`), but stated rather than folded into
                        // `else` so a future phase makes the compiler ask.
                        TareTapDecision.blocked -> Unit
                        TareTapDecision.confirm -> {
                            promptedKg = device.currentKg
                            promptedEpoch = device.connectionEpoch
                        }
                        TareTapDecision.tare -> {
                            // The exact age at ACTION time: the observable flag lags up to one watchdog tick. This
                            // can only downgrade, never authorize.
                            if (
                                TarePolicy.isSafeToTareNow(
                                    device.secondsSinceLastSample(),
                                    device.tareReadingMaxAge,
                                )
                            ) {
                                tareNow()
                            } else {
                                device.startStreaming(StreamStartCause.manualWake)
                            }
                        }
                    }
                }
                SecondaryButton(
                    title = tr("Disconnect"),
                    modifier = Modifier.weight(1f),
                ) { device.disconnect() }
            }
            PrimaryButton(
                title = if (device.isStreaming) tr("Stop") else tr("Start measuring"),
                icon = if (device.isStreaming) Icons.Filled.Stop else Icons.Filled.PlayArrow,
                tint = if (device.isStreaming) palette.alarm else palette.bleu,
                modifier = Modifier.widthIn(max = Metrics.maxContentWidth),
            ) {
                if (device.isStreaming) {
                    device.stopStreaming(StreamStopCause.userStopped)
                } else {
                    device.startStreaming(StreamStartCause.manualMeasurement)
                }
            }
        } else {
            // A broadcast scan can stay Searching while the scale is silent; always leave a manual way
            // back to Connect gauge, or restarting the app is the only escape.
            val canCancelScan = device.gaugeCapabilities.isBroadcast &&
                device.state == ProgressorConnectionState.Scanning
            PrimaryButton(
                title = when {
                    canCancelScan -> tr("Cancel")
                    device.state.isBusy -> device.state.label
                    else -> tr("Connect gauge")
                },
                icon = if (canCancelScan) Icons.Filled.Close else Icons.Outlined.SettingsInputAntenna,
                enabled = canCancelScan || !device.state.isBusy,
                modifier = Modifier.widthIn(max = Metrics.maxContentWidth),
            ) {
                if (canCancelScan) device.disconnect() else device.connect()
            }

            if (device.isMock) {
                TextButton(
                    onClick = { device.useMockDevice(false) },
                    modifier = Modifier.fillMaxWidth().heightIn(min = Metrics.controlMinHeight),
                ) {
                    Text(
                        tr("Leave demo mode"),
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = palette.inkSecondary,
                    )
                }
            } else {
                // ALWAYS compiled in: without hardware, on an emulator or in review, this is the only way to see it work.
                TextButton(
                    onClick = { device.useMockDevice(true) },
                    modifier = Modifier.fillMaxWidth().heightIn(min = Metrics.controlMinHeight),
                ) {
                    Text(
                        tr("Try demo mode"),
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = palette.inkSecondary,
                    )
                }
            }

            if (device.state is ProgressorConnectionState.Unsupported) {
                Text(
                    tr("This device has no Bluetooth radio. Use demo mode to look around."),
                    style = MaterialTheme.typography.bodySmall,
                    color = palette.inkTertiary,
                    textAlign = TextAlign.Center,
                )
            }
        }
    }

    val prompted = promptedKg
    if (prompted != null) {
        // **Taring under load can corrupt every reading after it**, so a meaningful load is
        // confirmed with the actual number, not refused. The confirmation revalidates epoch, phase
        // and signed load delta before writing, so an alert left open across a reconnect cannot
        // authorize a write on a link it never saw.
        AlertDialog(
            onDismissRequest = { promptedKg = null },
            title = { Text(tr("Zero the gauge?")) },
            text = {
                Text(
                    WeightUnits.tr(
                        "There is %s kg on the gauge. Taring now makes that the new zero for the rest of this session.",
                        kgText(prompted),
                    ),
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    val decision = TarePolicy.confirmationDecision(
                        promptedKg = prompted,
                        currentKg = device.currentKg,
                        promptedEpoch = promptedEpoch,
                        currentEpoch = device.connectionEpoch,
                        isConnected = device.state.isConnected,
                        sampleAge = device.secondsSinceLastSample(),
                        phase = RunnerPhase.Idle,
                        maxAgeSeconds = device.tareReadingMaxAge,
                    )
                    when (decision) {
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
            dismissButton = {
                TextButton(onClick = { promptedKg = null }) { Text(tr("Cancel")) }
            },
            containerColor = palette.card,
            titleContentColor = palette.inkPrimary,
            textContentColor = palette.inkSecondary,
        )
    }
}

/// Display precision only; the value is never rounded elsewhere. Locale-free until the
/// localisation pass.
///
/// **Guarded against a non-finite reading**: `Fmt.fixed` (the wire formatter) refuses NaN by
/// design, and a garbage float from a decoder must not take the hero numeral down.
private fun kgText(value: Double): String =
    WeightUnits.number(if (value.isFinite()) value else 0.0, 1)

// MARK: - Live leaves

/// The big number. Its own composable so a sample redraws THIS and nothing around it.
@Composable
private fun GaugeHero() {
    val device = LocalDeviceStore.current
    val palette = LocalGripPalette.current
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Row(
            verticalAlignment = Alignment.Bottom,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            // 80×/sec is unusable under TalkBack; the readouts' spoken summary below is the channel.
            modifier = Modifier.clearAndSetSemantics {},
        ) {
            Text(
                // Display precision only — see `kgText`.
                kgText(device.currentKg),
                style = TextStyle(
                    fontSize = HERO_SIZE,
                    fontWeight = FontWeight.Thin,
                    // Tabular figures so digits do not jitter sideways; −2 % display tracking because
                    // letterforms read further apart as they grow.
                    fontFeatureSettings = "tnum",
                    letterSpacing = (-0.02).em,
                ),
                // **CLOCKS ROLL, MEASUREMENTS SNAP.** No animation: a value changing ten times a second
                // under a transition is a permanent blur.
                color = if (device.isStreaming) palette.bleu else palette.inkPrimary,
            )
            Text(
                WeightUnits.symbol,
                style = TextStyle(fontSize = UNIT_SIZE),
                color = palette.inkTertiary,
                modifier = Modifier.padding(bottom = 12.dp),
            )
        }
        CapsLabel(if (device.isStreaming) tr("Live") else tr("Idle"))
    }
}

/// Peak, battery and the one-second mean. The mean walks the whole trace, so it is isolated here.
@Composable
private fun GaugeReadouts() {
    val device = LocalDeviceStore.current
    val palette = LocalGripPalette.current

    /// Rolling one-second mean — what you read when checking a steady hold. Null when idle, so
    /// the box shows "—" rather than a stale mean.
    val average: Double? = run {
        val trace = device.trace
        val newest = trace.lastOrNull()
        if (!device.isStreaming || newest == null) return@run null
        var sum = 0.0
        var count = 0.0
        for (i in trace.indices.reversed()) {
            // Playback-time age, the same convention as the trace's own drawing.
            if (newest.t - trace[i].t > 1.0) break
            sum += trace[i].kg
            count += 1.0
        }
        if (count > 0) sum / count else null
    }
    val battery = device.batteryFraction

    val summary = buildString {
        // The average is its own SUFFIX key, as on iOS: the sentence is one unit, the optional
        // clause another.
        append(
            WeightUnits.tr(
                "Current %s kilograms, peak %s kilograms%s",
                kgText(device.currentKg),
                kgText(device.peakKg),
                average?.let { WeightUnits.tr(", one-second average %s kilograms", kgText(it)) } ?: "",
            )
        )
    }

    Row(
        Modifier
            .widthIn(max = Metrics.maxContentWidth)
            .fillMaxWidth()
            .semantics(mergeDescendants = true) { contentDescription = summary },
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Readout(tr("Peak"), kgText(device.peakKg), WeightUnits.symbol, Modifier.weight(1f))
        Readout(
            tr("Battery"),
            battery?.let { "${run.nuri.getagrip.ui.components.BatteryDisplay.percentage(it)}" } ?: tr("—"),
            if (battery == null) "" else tr("%"),
            Modifier.weight(1f),
        )
        Readout(
            tr("Average"),
            average?.let { kgText(it) } ?: tr("—"),
            if (average == null) "" else WeightUnits.symbol,
            Modifier.weight(1f),
        )
    }
}

@Composable
private fun Readout(title: String, value: String, unit: String, modifier: Modifier = Modifier) {
    val palette = LocalGripPalette.current
    Surface(
        shape = RoundedCornerShape(Metrics.radiusInner),
        color = palette.card,
        modifier = modifier,
    ) {
        Column(
            Modifier.padding(vertical = 12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            CapsLabel(title)
            Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    value,
                    style = MaterialTheme.typography.titleLarge.copy(fontFeatureSettings = "tnum"),
                    fontWeight = FontWeight.Medium,
                    color = palette.inkPrimary,
                    maxLines = 1,
                )
                if (unit.isNotEmpty()) {
                    Text(
                        unit,
                        style = MaterialTheme.typography.bodySmall,
                        color = palette.inkTertiary,
                        modifier = Modifier.padding(bottom = 2.dp),
                    )
                }
            }
        }
    }
}

/// Nil for every gauge that reports kilograms itself, and for a calibrated one once its
/// coefficient is in hand — the readout is the answer then.
internal fun calibrationNote(status: GaugeCalibrationStatus): String? = when (status) {
    GaugeCalibrationStatus.NotRequired, is GaugeCalibrationStatus.Ready -> null
    GaugeCalibrationStatus.WaitingForSerial, is GaugeCalibrationStatus.Resolving ->
        L10n.tr("Looking up this Dyno's calibration…")
    is GaugeCalibrationStatus.Failed -> status.failure.label
}

/// **A connected-but-silent gauge must SAY so.** "0.0 kg" reads as a device measuring nothing,
/// indistinguishable from an app receiving nothing.
@Composable
private fun WaitingForGauge() {
    val palette = LocalGripPalette.current
    Row(
        Modifier.widthIn(max = Metrics.maxContentWidth).fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            Icons.Outlined.SettingsInputAntenna,
            contentDescription = null,
            tint = palette.inkTertiary,
            modifier = Modifier.size(18.dp),
        )
        Column {
            Text(
                tr("Waiting for the gauge"),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                color = palette.inkSecondary,
            )
            Text(
                tr("Connected, but no readings yet. Tap Wake to restart it."),
                style = MaterialTheme.typography.bodySmall,
                color = palette.inkTertiary,
            )
        }
    }
}

// MARK: - Keeping the screen awake

// The keep-screen-on lock is the shared, reference-counted one in runner/ScreenLock.kt —
// two counters for one global flag is the bug the counter prevents.

// MARK: - Previews

/// A preview store over the mock client, the only one needing no Context. Always compiled in,
/// so "Try demo mode" reaches the same client.
@Composable
private fun previewStore(): DeviceStore {
    val scope = remember { CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate) }
    return remember { DeviceStore(MockProgressorClient(scope, MockForceProfile.clean), isMock = true, scope = scope) }
}

@Preview(name = "Gauge · light", showBackground = true, heightDp = 780)
@Composable
private fun GaugeScreenLightPreview() {
    GetAGripTheme(darkTheme = false) {
        androidx.compose.runtime.CompositionLocalProvider(LocalDeviceStore provides previewStore()) {
            Surface(color = LocalGripPalette.current.field) { GaugeScreen() }
        }
    }
}

@Preview(name = "Gauge · dark", showBackground = true, heightDp = 780)
@Composable
private fun GaugeScreenDarkPreview() {
    GetAGripTheme(darkTheme = true) {
        androidx.compose.runtime.CompositionLocalProvider(LocalDeviceStore provides previewStore()) {
            Surface(color = LocalGripPalette.current.field) { GaugeScreen() }
        }
    }
}

/// **The gauge as a screen of its own**, opened from Today's header. Only the chrome is new
/// — title, back arrow, back gesture (Nuri, 2026-09-20: no redesign of working screens).
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LiveGaugeHost(onClose: () -> Unit) {
    val palette = LocalGripPalette.current
    BackHandler(onBack = onClose)
    Scaffold(
        containerColor = androidx.compose.ui.graphics.Color.Transparent,
        topBar = {
            TopAppBar(
                title = { Text(tr("Gauge")) },
                navigationIcon = {
                    IconButton(onClick = onClose) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = tr("Back"))
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = androidx.compose.ui.graphics.Color.Transparent,
                    scrolledContainerColor = palette.card,
                    titleContentColor = palette.inkPrimary,
                    navigationIconContentColor = palette.inkPrimary,
                ),
            )
        },
    ) { padding ->
        GaugeScreen(Modifier.padding(padding))
    }
}
