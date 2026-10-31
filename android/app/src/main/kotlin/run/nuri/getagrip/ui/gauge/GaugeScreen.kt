// SPDX-License-Identifier: MPL-2.0
// Original contributions Copyright 2026 Nuri Bruner.

package run.nuri.getagrip.ui.gauge

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.ui.text.rememberTextMeasurer
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
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.SettingsInputAntenna
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
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
import androidx.compose.ui.semantics.stateDescription
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
import run.nuri.getagrip.engine.RepEstimator
import run.nuri.getagrip.engine.Stopwatch
import run.nuri.getagrip.runner.KeepScreenOn
import run.nuri.getagrip.store.DeviceStore
import run.nuri.getagrip.store.LocalDeviceStore
import run.nuri.getagrip.ui.maxes.GaugeZeroButton
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
/// over the state wash, the actions (Tare, Disconnect, Start or Stop) on one dock. It
/// still shows the raw truth, not a prettified summary: this screen retires every hardware risk.
///
/// A stopwatch and an ESTIMATED pull count ride on the same panel (2026-09-28): the numbers
/// on the panel, Start/Pause/Reset on the dock. The count keeps going whenever the gauge
/// reads; Reset zeroes the peak, the average, the timer and the pulls. **A connected gauge
/// reads at once** — a separate Start after Connect was a step with no decision in it; Stop
/// and Start measuring remain for pausing. **A silent stream is re-kicked here**
/// (`DeviceStore.keepStreamAlive`), never handed to the person as "Wake".
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

    /// The stopwatch and the estimated pull count, fed by `onTracePoint` while the screen is open.
    val tally = remember { GaugeTally() }
    /// Where Reset put the average's floor: the one-second mean counts only readings after
    /// it, so it restarts from the tap instead of carrying the last second over.
    var averageSince by remember { mutableStateOf<Double?>(null) }

    // Awake while readings arrive: the phone timing out mid-pull is the app going blind.
    KeepScreenOn(device.isStreaming)

    DisposableEffect(device) {
        device.onTracePoint = { point -> tally.receive(point) }
        // This screen exists to show the force: a silent stream is re-kicked here, never
        // handed to the person as "Wake".
        device.keepStreamAlive(KEEP_ALIVE_HOLDER, true)
        onDispose {
            device.onTracePoint = null
            device.keepStreamAlive(KEEP_ALIVE_HOLDER, false)
            // Never leave the device streaming behind us: it drains its battery and keeps the radio busy.
            if (device.isStreaming) device.stopStreaming(StreamStopCause.screenClosed)
        }
    }

    // Reads as soon as a gauge is connected — on opening, and on connecting. After Stop it
    // stays stopped: only a new connection restarts it.
    LaunchedEffect(device, device.state.isConnected) {
        if (device.state.isConnected && !device.isStreaming) {
            device.startStreaming(StreamStartCause.manualMeasurement)
        }
    }

    // How a pull cut off by Stop would have ended is unknowable.
    LaunchedEffect(device.isStreaming) {
        if (!device.isStreaming) tally.cancelPull()
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
            GaugeReadouts(averageSince)
            GaugeTimerRow(tally)
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
                // **The label follows the DECISION, not the intent** — `TarePolicy.gaugeMode`: Tare
                // while the keeper works, Wake only once it has given up.
                val disconnect = tr("Disconnect")
                AdaptiveActionRow(listOf(listOf(tr("Tare"), tr("Wake")), listOf(disconnect)),
                    spacing = InstrumentStage.dockSpacing, iconAllowance = 24.dp) { index, cell ->
                    if (index == 0) {
                        // A new zero under a pull in progress would read as the pull ending, so
                        // the pull is dropped rather than counted.
                        GaugeZeroButton(canTare = true, modifier = cell.testTag("gauge.tare"),
                            willTare = { tally.cancelPull() })
                    } else {
                        DockButton(title = disconnect, modifier = cell.testTag("gauge.disconnect")) {
                            device.disconnect()
                        }
                    }
                }
                GaugeTimerDockRow(tally) { averageSince = device.trace.lastOrNull()?.t }
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
                GaugeTimerDockRow(tally) { averageSince = device.trace.lastOrNull()?.t }

                if (device.state is ProgressorConnectionState.Unsupported) {
                    DockNote(
                        tr("This device has no Bluetooth radio."),
                        color = palette.inkTertiary,
                        modifier = Modifier.padding(bottom = 4.dp),
                    )
                }
            }
        }
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
private fun GaugeReadouts(averageSince: Double?) {
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
            // Reset's floor: the mean restarts from the tap.
            if (averageSince != null && trace[i].t <= averageSince) break
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

    val peakTitle = tr("Peak")
    val averageTitle = tr("Average")
    val batteryTitle = tr("Battery")
    @Composable fun peak(modifier: Modifier) = Readout(peakTitle, kgText(device.peakKg), WeightUnits.symbol, modifier)
    // The reading the flickering hero can't give: hang steady, read the average.
    @Composable fun average(modifier: Modifier) = Readout(
        averageTitle,
        average?.let { kgText(it) } ?: tr("—"),
        if (average == null) "" else WeightUnits.symbol,
        modifier,
    )
    @Composable fun battery(modifier: Modifier) = Readout(
        batteryTitle,
        battery?.let { "${run.nuri.getagrip.ui.components.BatteryDisplay.percentage(it)}" } ?: tr("—"),
        if (battery == null) "" else tr("%"),
        modifier,
    )

    // Three across while every caps title fits its third with air to spare; two and one once a
    // title would touch its neighbour ("AVERAGE BATTERY" read as one heading at 2.0).
    val measurer = rememberTextMeasurer(cacheSize = 8)
    val density = LocalDensity.current
    val titleStyle = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold, letterSpacing = 0.8.sp)
    BoxWithConstraints(
        Modifier
            .fillMaxWidth()
            .testTag("gauge.readouts")
            .semantics(mergeDescendants = true) { contentDescription = summary },
    ) {
        val threeAcross = remember(maxWidth, density.density, density.fontScale, titleStyle, peakTitle, averageTitle, batteryTitle) {
            val third = with(density) { ((maxWidth - 16.dp) / 3 - 8.dp).roundToPx() }
            listOf(peakTitle, averageTitle, batteryTitle).all {
                measurer.measure(it.uppercase(), titleStyle, softWrap = false, maxLines = 1).size.width <= third
            }
        }
        if (threeAcross) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                peak(Modifier.weight(1f))
                average(Modifier.weight(1f))
                battery(Modifier.weight(1f))
            }
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    peak(Modifier.weight(1f))
                    average(Modifier.weight(1f))
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    battery(Modifier.weight(1f))
                    Spacer(Modifier.weight(1f))
                }
            }
        }
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
            tr("Connect a gauge and pull to see the force."),
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
                // The keeper is re-kicking; Wake is named only once it has given up.
                if (device.streamRekickExhausted) tr("No readings from the gauge. Check it's on, then tap Wake.")
                else tr("Connected. Waiting for readings…"),
                style = MaterialTheme.typography.bodySmall,
                color = palette.inkTertiary,
                textAlign = TextAlign.Center,
            )
        }
    }
}

// MARK: - Stopwatch and pull count

/// The gauge screen's stopwatch and ESTIMATED pull count (iOS `GaugeTally`). Only what is
/// drawn is state, and `pulls` is written only when it CHANGES — `receive` runs ~80×/second.
internal class GaugeTally {
    var stopwatch by mutableStateOf(Stopwatch())
        private set
    var pulls by mutableIntStateOf(0)
        private set
    private val estimator = RepEstimator()

    val canReset: Boolean get() = stopwatch.isRunning || stopwatch.accumulated > 0 || pulls > 0

    fun receive(point: DeviceStore.TracePoint) {
        if (estimator.add(point.kg, point.t)) pulls = estimator.count
    }

    fun cancelPull() = estimator.cancelPull()

    fun startTimer() { stopwatch = stopwatch.started(now()) }

    fun pauseTimer() { stopwatch = stopwatch.paused(now()) }

    /// Zeroes both: a new set starts from nothing.
    fun reset() {
        stopwatch = Stopwatch()
        estimator.reset()
        pulls = 0
    }

    companion object {
        /// Seconds on a clock that keeps running while the phone sleeps, so a locked screen
        /// cannot lose stopwatch time.
        fun now(): Double = android.os.SystemClock.elapsedRealtimeNanos() / 1e9
    }
}

/// Start/Pause and Reset for the stopwatch. Start also starts reading a connected gauge — a
/// timer running beside a dead graph is not what anyone pressed play for.
@Composable
private fun GaugeTimerDockRow(tally: GaugeTally, onResetStatistics: () -> Unit) {
    val device = LocalDeviceStore.current
    val haptics = LocalHapticFeedback.current
    val running = tally.stopwatch.isRunning
    val startPause = if (running) tr("Pause timer") else tr("Start timer")
    val reset = tr("Reset")
    AdaptiveActionRow(listOf(listOf(tr("Start timer"), tr("Pause timer")), listOf(reset)),
        spacing = InstrumentStage.dockSpacing, iconAllowance = 24.dp) { index, cell ->
        if (index == 0) {
            DockButton(
                title = startPause,
                icon = if (running) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                modifier = cell.testTag("gauge.timer"),
            ) {
                if (running) {
                    tally.pauseTimer()
                } else {
                    tally.startTimer()
                    if (device.state.isConnected && !device.isStreaming) {
                        device.startStreaming(StreamStartCause.manualMeasurement)
                    }
                }
                // The haptic names its cause: the tap.
                haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
            }
        } else {
            // **Reset is the screen's statistics, all of them** (a Progressor user, 2026-10-31):
            // peak, average, timer and pulls start over together. No confirmation — nothing
            // here is a record — and never a tare or a stream change.
            val resetHint = tr("Resets peak, average, timer and pulls. Doesn't tare.")
            DockButton(
                title = reset,
                icon = Icons.Outlined.Refresh,
                enabled = tally.canReset || device.hasPeak,
                modifier = cell.testTag("gauge.timerReset").semantics { stateDescription = resetHint },
            ) {
                tally.reset()
                device.resetDisplayStatistics()
                onResetStatistics()
                haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
            }
        }
    }
}

/// The stopwatch and the pull count, on the panel under the readouts. A LEAF: the running
/// clock ticks ten times a second and must recompose nothing else.
@Composable
private fun GaugeTimerRow(tally: GaugeTally) {
    val palette = LocalGripPalette.current
    val watch = tally.stopwatch
    var elapsed by remember { mutableStateOf(watch.elapsed(GaugeTally.now())) }
    LaunchedEffect(watch) {
        elapsed = watch.elapsed(GaugeTally.now())
        while (watch.isRunning) {
            delay(100)
            elapsed = watch.elapsed(GaugeTally.now())
        }
    }
    val spoken = tr("Timer %s. Pulls counted: %d", spokenDuration(elapsed), tally.pulls)
    Row(
        Modifier.fillMaxWidth().padding(top = 2.dp).testTag("gauge.tally")
            .clearAndSetSemantics { contentDescription = spoken },
        verticalAlignment = Alignment.Bottom,
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            CapsLabel(tr("Timer"))
            val label = Stopwatch.label(elapsed)
            val split = label.lastIndexOf('.').let { if (it < 0) label.length else it }
            val ink = if (watch.isRunning) palette.inkPrimary else palette.inkSecondary
            Row(verticalAlignment = Alignment.Bottom) {
                // A stopwatch at ten ticks a second would blur if it rolled: it snaps.
                Text(
                    label.substring(0, split),
                    style = MaterialTheme.typography.displaySmall.copy(
                        fontFeatureSettings = "tnum", fontWeight = FontWeight.Medium),
                    color = ink,
                    maxLines = 1,
                )
                // Tenths smaller: they would otherwise pull the eye off the seconds.
                Text(
                    label.substring(split),
                    style = MaterialTheme.typography.titleLarge.copy(
                        fontFeatureSettings = "tnum", fontWeight = FontWeight.Medium),
                    color = palette.inkSecondary,
                    maxLines = 1,
                    modifier = Modifier.padding(bottom = 3.dp),
                )
            }
        }
        Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(4.dp)) {
            CapsLabel(tr("Pulls"))
            Text(
                "${tally.pulls}",
                style = MaterialTheme.typography.displaySmall.copy(
                    fontFeatureSettings = "tnum", fontWeight = FontWeight.Medium),
                color = palette.inkPrimary,
                maxLines = 1,
            )
        }
    }
}

/// "1 minute, 5 seconds" for TalkBack, through the platform's own unit formatter.
private fun spokenDuration(seconds: Double): String {
    val whole = seconds.toLong().coerceAtLeast(0)
    val measures = buildList {
        val h = whole / 3600
        val m = whole / 60 % 60
        val s = whole % 60
        if (h > 0) add(android.icu.util.Measure(h, android.icu.util.MeasureUnit.HOUR))
        if (m > 0) add(android.icu.util.Measure(m, android.icu.util.MeasureUnit.MINUTE))
        if (s > 0 || (h == 0L && m == 0L)) add(android.icu.util.Measure(s, android.icu.util.MeasureUnit.SECOND))
    }
    return android.icu.text.MeasureFormat.getInstance(java.util.Locale.getDefault(),
        android.icu.text.MeasureFormat.FormatWidth.WIDE).formatMeasures(*measures.toTypedArray())
}

// MARK: - Keeping the screen awake

// The keep-screen-on lock is the shared, reference-counted one in runner/ScreenLock.kt —
// two counters for one global flag is the bug the counter prevents.

// MARK: - Previews

/// A preview store over the mock client, the only one needing no Context. Previews only — no
/// screen offers the mock to a user.
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

/// The keeper's name for this screen — see `DeviceStore.keepStreamAlive`.
private const val KEEP_ALIVE_HOLDER = "gauge"
