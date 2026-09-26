// SPDX-License-Identifier: MPL-2.0
// Original contributions Copyright 2026 Nuri Bruner.

package run.nuri.getagrip.ui.gauge

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.SettingsInputAntenna
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import run.nuri.getagrip.ble.GaugeCalibrationStatus
import run.nuri.getagrip.ble.MockForceProfile
import run.nuri.getagrip.ble.MockProgressorClient
import run.nuri.getagrip.ble.ProgressorConnectionState
import run.nuri.getagrip.ble.StreamStartCause
import run.nuri.getagrip.ble.StreamStopCause
import run.nuri.getagrip.engine.L10n
import run.nuri.getagrip.engine.RunnerPhase
import run.nuri.getagrip.runner.KeepScreenOn
import run.nuri.getagrip.store.DeviceStore
import run.nuri.getagrip.store.LocalDeviceStore
import run.nuri.getagrip.store.TareConfirmationDecision
import run.nuri.getagrip.store.TarePolicy
import run.nuri.getagrip.store.TareTapDecision
import run.nuri.getagrip.ui.components.AdaptiveActionRow
import run.nuri.getagrip.ui.components.CapsLabel
import run.nuri.getagrip.ui.components.DockButton
import run.nuri.getagrip.ui.components.DockNote
import run.nuri.getagrip.ui.components.DockTint
import run.nuri.getagrip.ui.components.DockTintedButton
import run.nuri.getagrip.ui.components.ForceTraceView
import run.nuri.getagrip.ui.components.InstrumentDock
import run.nuri.getagrip.ui.components.InstrumentPanel
import run.nuri.getagrip.ui.components.InstrumentStage
import run.nuri.getagrip.ui.components.LocalFloatingTabBarInset
import run.nuri.getagrip.ui.components.OpenGraphRegion
import run.nuri.getagrip.ui.components.StageGeometry
import run.nuri.getagrip.ui.components.phaseWash
import run.nuri.getagrip.ui.components.rememberStageGeometry
import run.nuri.getagrip.ui.l10n.tr
import run.nuri.getagrip.ui.theme.GetAGripTheme
import run.nuri.getagrip.ui.theme.LocalGripPalette
import run.nuri.getagrip.ui.theme.Metrics
import run.nuri.getagrip.ui.theme.armedText
import run.nuri.getagrip.ui.theme.readablePageWidth
import run.nuri.getagrip.ui.units.WeightUnits

/// `sp`, not `dp`: a bare `dp` numeral stays fixed at every accessibility setting while the
/// controls around it grow.
private val HERO_SIZE = 78.sp
private val UNIT_SIZE = 22.sp

/// Bleu while a reading is live, steel otherwise. Never alarm: not connected is the starting
/// state here, not a fault (iOS `GaugeView.tint`).
internal fun gaugeTint(isStreaming: Boolean, palette: run.nuri.getagrip.ui.theme.GripPalette) =
    if (isStreaming) palette.bleu else palette.calm

/// The live force gauge — **the runner's screen without a routine** (iOS `GaugeView`): the
/// trace IS the screen, the numbers (live kg, peak, one-second mean, battery) on one panel
/// over the state wash, the actions (Tare or Wake, Disconnect, Start or Stop) on one dock. It
/// still shows the raw truth, not a prettified summary: this screen retires every hardware risk.
///
/// **No threshold rule and no target lane**: both belong to a rep asking for a load, and
/// this screen asks for nothing.
///
/// The wash hangs from the top of the SCREEN, so its host draws it (`LiveGaugeHost`); pass the
/// same `stage` so it ends where the open graph begins.
@Composable
fun GaugeScreen(modifier: Modifier = Modifier, stage: StageGeometry = rememberStageGeometry()) {
    val device = LocalDeviceStore.current
    val palette = LocalGripPalette.current
    val haptics = LocalHapticFeedback.current
    val scrollsForLargeText = LocalDensity.current.fontScale >= 1.5f

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

    fun requestTare() {
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

    Column(
        modifier = modifier
            .fillMaxSize()
            .readablePageWidth()
            // Scroll rather than clip labels or shrink type at accessibility sizes — the runner's rule.
            .then(if (scrollsForLargeText) Modifier.verticalScroll(rememberScrollState()) else Modifier)
            .padding(horizontal = Metrics.hPadding)
            .padding(top = 8.dp, bottom = Metrics.spacing + LocalFloatingTabBarInset.current),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        InstrumentPanel(
            tint = gaugeTint(device.isStreaming, palette),
            modifier = Modifier.testTag("gauge.panel"),
            contentPadding = PaddingValues(start = 16.dp, top = 14.dp, end = 16.dp, bottom = 14.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            // LEAVES: `currentKg`, `peakKg` and `trace` mutate per sample, and read here would
            // invalidate the whole screen 80×/second.
            GaugeHero()
            GaugeReadouts()
            // A remotely calibrated gauge can be connected with no force to show; say why rather than
            // display a guess (Frez's rule), only for the gauge that has a why.
            if (device.state.isConnected) {
                calibrationNote(device.calibrationStatus)?.let { note ->
                    Text(
                        note,
                        style = MaterialTheme.typography.bodySmall,
                        color = if (device.calibrationStatus.isReady) palette.inkTertiary else palette.armedText,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth().testTag("gauge.calibration"),
                    )
                }
            }
        }

        OpenGraphRegion(
            geometry = stage,
            modifier = Modifier
                .widthIn(max = Metrics.maxContentWidth)
                .fillMaxWidth()
                .then(if (scrollsForLargeText) Modifier.height(200.dp) else Modifier.weight(1f))
                .testTag("gauge.graph"),
            trace = { GaugeTrace() },
        ) {
            GaugeGraphNotice()
        }

        InstrumentDock(Modifier.testTag("gauge.dock")) {
            if (device.state.isConnected) {
                // **The label follows the DECISION, not the intent.** With no live reading the tap wakes
                // the stream instead of taring; a button saying Tare that does something else is a lie.
                val tare = if (device.isReadingLive) tr("Tare") else tr("Wake")
                val disconnect = tr("Disconnect")
                AdaptiveActionRow(listOf(listOf(tr("Tare"), tr("Wake")), listOf(disconnect)),
                    spacing = InstrumentStage.dockSpacing) { index, cell ->
                    if (index == 0) {
                        DockButton(title = tare, icon = Icons.Outlined.Refresh,
                            modifier = cell.testTag("gauge.tare")) { requestTare() }
                    } else {
                        DockButton(title = disconnect, modifier = cell.testTag("gauge.disconnect")) {
                            device.disconnect()
                        }
                    }
                }
                DockTintedButton(
                    title = if (device.isStreaming) tr("Stop") else tr("Start measuring"),
                    icon = if (device.isStreaming) Icons.Filled.Stop else Icons.Filled.PlayArrow,
                    tint = if (device.isStreaming) DockTint.alarm else DockTint.bleu,
                    modifier = Modifier.fillMaxWidth().testTag("gauge.measure"),
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
                DockTintedButton(
                    title = when {
                        canCancelScan -> tr("Cancel")
                        device.state.isBusy -> device.state.label
                        else -> tr("Connect gauge")
                    },
                    icon = if (canCancelScan) Icons.Filled.Close else Icons.Outlined.SettingsInputAntenna,
                    tint = DockTint.bleu,
                    enabled = canCancelScan || !device.state.isBusy,
                    modifier = Modifier.fillMaxWidth().testTag("gauge.connectionAction"),
                ) {
                    if (canCancelScan) device.disconnect() else device.connect()
                }
                // ALWAYS compiled in: without hardware, on an emulator or in review, this is the only way
                // to see it work.
                DockButton(
                    title = if (device.isMock) tr("Leave demo mode") else tr("Try demo mode"),
                    tint = palette.inkSecondary,
                    modifier = Modifier.fillMaxWidth(),
                ) { device.useMockDevice(!device.isMock) }

                if (device.state is ProgressorConnectionState.Unsupported) {
                    DockNote(
                        tr("This device has no Bluetooth radio. Use demo mode to look around."),
                        color = palette.inkTertiary,
                        modifier = Modifier.padding(bottom = 4.dp),
                    )
                }
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
            BasicText(
                // Display precision only — see `kgText`.
                kgText(device.currentKg),
                style = TextStyle(
                    fontSize = HERO_SIZE,
                    fontWeight = FontWeight.Thin,
                    // Tabular figures so digits do not jitter sideways; −2 % display tracking because
                    // letterforms read further apart as they grow.
                    fontFeatureSettings = "tnum",
                    letterSpacing = (-0.02).em,
                    // **CLOCKS ROLL, MEASUREMENTS SNAP.** No animation: a value changing ten times a
                    // second under a transition is a permanent blur.
                    color = if (device.isStreaming) palette.bleu else palette.inkPrimary,
                ),
                maxLines = 1,
                // A monospaced decimal cannot wrap; without this floor it clipped at large text.
                autoSize = TextAutoSize.StepBased(minFontSize = 40.sp, maxFontSize = HERO_SIZE),
                modifier = Modifier.weight(1f, fill = false),
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

/// Peak, one-second mean and battery, as three quiet columns on the panel. The mean walks the
/// whole trace, so it is isolated here.
@Composable
private fun GaugeReadouts() {
    val device = LocalDeviceStore.current

    /// Rolling one-second mean — what you read when checking a steady hold. Null when idle, so
    /// the column shows "—" rather than a stale mean.
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
            .fillMaxWidth()
            .testTag("gauge.readouts")
            .semantics(mergeDescendants = true) { contentDescription = summary },
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Readout(tr("Peak"), kgText(device.peakKg), WeightUnits.symbol, Modifier.weight(1f))
        // The reading the flickering hero can't give: hang steady, read the average.
        Readout(
            tr("Average"),
            average?.let { kgText(it) } ?: tr("—"),
            if (average == null) "" else WeightUnits.symbol,
            Modifier.weight(1f),
        )
        Readout(
            tr("Battery"),
            battery?.let { "${run.nuri.getagrip.ui.components.BatteryDisplay.percentage(it)}" } ?: tr("—"),
            if (battery == null) "" else tr("%"),
            Modifier.weight(1f),
        )
    }
}

@Composable
private fun Readout(title: String, value: String, unit: String, modifier: Modifier = Modifier) {
    val palette = LocalGripPalette.current
    Column(
        modifier.clearAndSetSemantics {},
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        CapsLabel(title)
        Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(2.dp)) {
            // A measurement SNAPS: no transition on a figure changing ten times a second.
            BasicText(
                value,
                style = MaterialTheme.typography.titleLarge.copy(
                    fontFeatureSettings = "tnum", fontWeight = FontWeight.Medium, color = palette.inkPrimary),
                maxLines = 1,
                autoSize = TextAutoSize.StepBased(minFontSize = 14.sp, maxFontSize = 22.sp),
                modifier = Modifier.weight(1f, fill = false),
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

/// The trace. `device.trace` grows per sample and is read inside the canvas's draw; only the
/// tint (per stream start and stop) recomposes this. Drawn LIT, like the runner's.
@Composable
private fun GaugeTrace() {
    val device = LocalDeviceStore.current
    val palette = LocalGripPalette.current
    ForceTraceView(
        modifier = Modifier.fillMaxSize(),
        tint = if (device.isStreaming) palette.bleu else palette.inkTertiary,
        lit = true,
    )
}

/// Nil for every gauge that reports kilograms itself, and for a calibrated one once its
/// coefficient is in hand — the readout is the answer then.
internal fun calibrationNote(status: GaugeCalibrationStatus): String? = when (status) {
    GaugeCalibrationStatus.NotRequired, is GaugeCalibrationStatus.Ready -> null
    GaugeCalibrationStatus.WaitingForSerial, is GaugeCalibrationStatus.Resolving ->
        L10n.tr("Looking up this Dyno's calibration…")
    is GaugeCalibrationStatus.Failed -> status.failure.label
}

/// **What the open graph says when it has nothing to draw**: how to start when nothing is
/// connected, and — a connected-but-silent gauge must SAY so — the warning when a stream was
/// asked for and nothing arrives. "0.0 kg" reads as a device measuring nothing,
/// indistinguishable from an app receiving nothing.
///
/// A leaf with its own grace: armed 1.5 s after a start, or the warning flashes on every tap
/// before the first packet (iOS `waitingForSignal`).
@Composable
private fun GaugeGraphNotice() {
    val device = LocalDeviceStore.current
    val palette = LocalGripPalette.current
    var waitingForSignal by remember { mutableStateOf(false) }
    LaunchedEffect(device.isStreaming) {
        waitingForSignal = false
        if (!device.isStreaming) return@LaunchedEffect
        delay(1_500)
        waitingForSignal = true
    }
    if (!device.state.isConnected) {
        Text(
            tr("Connect a gauge and pull — the force draws here."),
            style = MaterialTheme.typography.bodyMedium,
            color = palette.inkSecondary,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(horizontal = 24.dp).testTag("gauge.notice"),
        )
    } else if (device.isStreaming && waitingForSignal && !device.isSignalFresh) {
        Column(
            Modifier.padding(horizontal = 24.dp).testTag("gauge.signalWarning")
                .semantics(mergeDescendants = true) {},
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Icon(
                Icons.Outlined.SettingsInputAntenna,
                contentDescription = null,
                tint = palette.inkTertiary,
                modifier = Modifier.size(26.dp),
            )
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
                textAlign = TextAlign.Center,
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

/// **The gauge as a screen of its own**, opened from Today's header: title, back arrow, back
/// gesture — and the phase wash hung from the top of the SCREEN, under the transparent bar,
/// down to the open graph's upper edge.
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LiveGaugeHost(onClose: () -> Unit) {
    val palette = LocalGripPalette.current
    val device = LocalDeviceStore.current
    val stage = rememberStageGeometry()
    BackHandler(onBack = onClose)
    Box(Modifier.fillMaxSize().phaseWash(gaugeTint(device.isStreaming, palette), stage)) {
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
            GaugeScreen(Modifier.padding(padding), stage)
        }
    }
}
