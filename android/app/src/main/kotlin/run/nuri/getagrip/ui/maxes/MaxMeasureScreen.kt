// SPDX-License-Identifier: MPL-2.0
// Original contributions Copyright 2026 Nuri Bruner.

package run.nuri.getagrip.ui.maxes

import run.nuri.getagrip.ui.units.WeightUnits

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.stateDescription
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import run.nuri.getagrip.engine.MaxMeasurementDraft
import run.nuri.getagrip.engine.MaxMeasurementResult
import run.nuri.getagrip.engine.MaxSource
import run.nuri.getagrip.store.TemplateStore

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
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
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import run.nuri.getagrip.ble.MockForceProfile
import run.nuri.getagrip.ble.MockProgressorClient
import run.nuri.getagrip.ble.StreamStartCause
import run.nuri.getagrip.ble.StreamStopCause
import run.nuri.getagrip.engine.GripSpec
import run.nuri.getagrip.engine.L10n
import run.nuri.getagrip.engine.MaxAttempt
import run.nuri.getagrip.engine.Side
import run.nuri.getagrip.runner.KeepScreenOn
import run.nuri.getagrip.store.DeviceStore
import run.nuri.getagrip.store.LocalDeviceStore
import run.nuri.getagrip.ui.components.CapsLabel
import run.nuri.getagrip.ui.components.ForceTraceView
import run.nuri.getagrip.ui.components.PrimaryButton
import run.nuri.getagrip.ui.components.SecondaryButton
import run.nuri.getagrip.ui.l10n.tr
import run.nuri.getagrip.ui.theme.GetAGripTheme
import run.nuri.getagrip.ui.theme.LocalGripPalette
import run.nuri.getagrip.ui.theme.readablePageWidth
import run.nuri.getagrip.ui.theme.Metrics

private enum class MaxMeasurePhase { ready, measuring, done }

/** One visit can capture either hand or both in turn. Values stay local until Save. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MaxMeasureScreen(
    grip: GripSpec,
    onSave: suspend (List<MaxMeasurementResult>) -> TemplateStore.MaxSaveReceipt?,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
    initialSide: Side = Side.left,
) {
    val device = LocalDeviceStore.current
    val palette = LocalGripPalette.current
    val haptics = LocalHapticFeedback.current
    val scope = rememberCoroutineScope()
    var draft by remember(grip.key, initialSide) { mutableStateOf(MaxMeasurementDraft(initialSide == Side.both)) }
    var selectedSide by remember(grip.key, initialSide) { mutableStateOf(initialSide) }
    val attempts = remember(grip.key, initialSide) {
        mutableStateMapOf(Side.left to MaxMeasurement(), Side.right to MaxMeasurement(), Side.both to MaxMeasurement())
    }
    val completed = remember(grip.key, initialSide) { mutableMapOf<Side, MaxMeasurement>() }
    val traces = remember(grip.key, initialSide) { mutableMapOf<Side, List<DeviceStore.TracePoint>>() }
    val measurement = attempts.getValue(selectedSide)
    var phase by remember { mutableStateOf(MaxMeasurePhase.ready) }
    var attemptTick by remember { mutableIntStateOf(0) }
    var keptPreviousAfterRetry by remember { mutableStateOf(false) }
    var adjusting by remember { mutableStateOf(false) }
    var isSaving by remember { mutableStateOf(false) }
    var saveFailed by remember { mutableStateOf(false) }
    var committed by remember { mutableStateOf(false) }
    var savedReceipt by remember { mutableStateOf<TemplateStore.MaxSaveReceipt?>(null) }

    KeepScreenOn(phase == MaxMeasurePhase.measuring)

    fun stop(cause: StreamStopCause) {
        if (phase != MaxMeasurePhase.measuring) return
        device.onTracePoint = null
        if (device.isStreaming) device.stopStreaming(cause)
        val next = draft.copy()
        next.finish(measurement.peakKg)?.let { side ->
            if (measurement.hasResult && measurement.peakKg.isFinite()) {
                completed[side] = measurement
                traces[side] = device.trace.toList()
                haptics.performHapticFeedback(HapticFeedbackType.LongPress)
            } else {
                attempts[side] = completed[side] ?: MaxMeasurement()
                keptPreviousAfterRetry = completed[side] != null
            }
        }
        draft = next
        phase = MaxMeasurePhase.done
    }

    fun start() {
        if (!device.state.isConnected || isSaving || committed) return
        val next = draft.copy()
        if (!next.begin(selectedSide)) return
        draft = next
        val attempt = MaxMeasurement()
        attempts[selectedSide] = attempt
        keptPreviousAfterRetry = false
        saveFailed = false
        device.resetPeak()
        // The callback owns this attempt, never the selected-hand lookup. Playback time is the
        // release clock across reconnects and tare.
        device.onTracePoint = { point -> attempt.receive(point) }
        device.startStreaming(StreamStartCause.manualMeasurement)
        phase = MaxMeasurePhase.measuring
        attemptTick += 1
    }

    fun select(side: Side) {
        if (phase == MaxMeasurePhase.measuring || isSaving || committed || side == selectedSide) return
        selectedSide = side
        phase = if (draft.peak(side) == null) MaxMeasurePhase.ready else MaxMeasurePhase.done
        keptPreviousAfterRetry = false
        saveFailed = false
        haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
    }

    fun save() {
        val values = draft.results
        if (isSaving || committed || values.isEmpty()) return
        isSaving = true
        saveFailed = false
        scope.launch {
            try {
                val receipt = onSave(values)
                if (receipt == null) {
                    saveFailed = true
                } else {
                    committed = true
                    device.onTracePoint = null
                    if (device.isStreaming) device.stopStreaming(StreamStopCause.measurementComplete)
                    if (receipt.hasDetails) savedReceipt = receipt else onClose()
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                saveFailed = true
            } finally {
                isSaving = false
            }
        }
    }

    LaunchedEffect(measurement, measurement.isComplete) {
        if (measurement.isComplete && phase == MaxMeasurePhase.measuring) stop(StreamStopCause.measurementComplete)
    }
    LaunchedEffect(attemptTick, phase) {
        if (phase != MaxMeasurePhase.measuring) return@LaunchedEffect
        delay((MaxMeasureTiming.TIMEOUT_SECONDS * 1000).toLong())
        measurement.finish()
        stop(StreamStopCause.timedOut)
    }
    LaunchedEffect(device.state.isConnected) {
        if (device.state.isConnected && phase == MaxMeasurePhase.measuring) {
            device.startStreaming(StreamStartCause.reconnect)
        }
    }
    DisposableEffect(device) {
        onDispose {
            device.onTracePoint = null
            if (device.isStreaming) device.stopStreaming(StreamStopCause.screenClosed)
        }
    }
    BackHandler(enabled = savedReceipt == null) {
        if (!isSaving) {
            if (adjusting) adjusting = false else onClose()
        }
    }

    val receipt = savedReceipt
    if (receipt != null) {
        MaxSaveReceiptScreen(receipt = receipt, onDone = onClose)
        return
    }
    if (adjusting) {
        MaxMeasurementCorrectionScreen(
            results = draft.results,
            measuredPeaks = draft.results.associate { it.side to (draft.measuredPeak(it.side) ?: it.kg) },
            onApply = { values ->
                val next = draft.copy()
                if (next.correct(values)) {
                    draft = next
                    adjusting = false
                    saveFailed = false
                }
            },
            onCancel = { adjusting = false },
        )
        return
    }

    // Hosted at the ROOT, so nothing above pads the system bars: keep the Material defaults
    // (as MaxesFlowScaffold and the builder). Zeroing them drew the title under the clock.
    Scaffold(
        containerColor = androidx.compose.ui.graphics.Color.Transparent,
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(tr("Measure a max"), style = MaterialTheme.typography.titleMedium)
                        Text(grip.displayName, style = MaterialTheme.typography.bodySmall, color = palette.inkTertiary)
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onClose, enabled = !isSaving && !committed,
                               modifier = Modifier.testTag("max.measure.cancel")) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = tr("Cancel"))
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
        BoxWithConstraints(Modifier.fillMaxSize().padding(padding).readablePageWidth()) {
            Column(
                Modifier.fillMaxWidth().verticalScroll(rememberScrollState())
                    .heightIn(min = maxHeight).padding(horizontal = Metrics.hPadding)
                    .padding(bottom = Metrics.spacing),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                if (draft.bothTogether) {
                    Text(tr("Both hands together"), style = MaterialTheme.typography.titleLarge,
                         fontWeight = FontWeight.SemiBold)
                    Text(tr("One combined measurement"), style = MaterialTheme.typography.bodySmall,
                         color = palette.inkSecondary)
                } else {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        listOf(Side.left, Side.right).forEach { side ->
                            MeasurementHand(
                                side = side, peak = draft.peak(side), selected = selectedSide == side,
                                enabled = phase != MaxMeasurePhase.measuring && !isSaving && !committed,
                                modifier = Modifier.weight(1f), onClick = { select(side) },
                            )
                        }
                    }
                }
                CapsLabel(if (phase == MaxMeasurePhase.done && draft.peak(selectedSide) != null)
                              tr("Measured") else tr("HARDEST PULL"), Modifier.fillMaxWidth())
                Hero(measurement, phase)
                Surface(
                    shape = RoundedCornerShape(Metrics.radiusCard), color = palette.card,
                    modifier = Modifier.fillMaxWidth().height(TRACE_HEIGHT),
                ) {
                    ForceTraceView(
                        frozenSamples = if (phase == MaxMeasurePhase.measuring) null else traces[selectedSide] ?: emptyList(),
                        modifier = Modifier.fillMaxSize().padding(horizontal = 4.dp),
                        thresholdKg = if (measurement.hasResult) measurement.peakKg else null,
                        tint = if (phase == MaxMeasurePhase.measuring) palette.bleu else palette.inkTertiary,
                    )
                }
                Text(
                    if (keptPreviousAfterRetry) tr("No new pull recorded. Your previous measurement is still ready to save.")
                    else guidance(measurement, phase),
                    style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium,
                    color = palette.inkSecondary, textAlign = TextAlign.Center,
                )
                Spacer(Modifier.weight(1f))
                if (!device.state.isConnected && phase == MaxMeasurePhase.ready) {
                    Text(tr("Connect your gauge to measure."), style = MaterialTheme.typography.bodySmall,
                         color = palette.inkTertiary, textAlign = TextAlign.Center)
                    PrimaryButton(
                        title = if (device.state.isBusy) device.state.label else tr("Connect"),
                        icon = Icons.Outlined.SettingsInputAntenna, tint = palette.bleu,
                        enabled = !device.state.isBusy && !isSaving, modifier = Modifier.testTag("max.measure.connect"),
                    ) { device.connect() }
                } else {
                    when (phase) {
                        MaxMeasurePhase.ready -> {
                            GaugeZeroButton(
                                canTare = phase == MaxMeasurePhase.ready && !isSaving && !committed,
                                modifier = Modifier.fillMaxWidth().testTag("max.measure.tare"),
                            )
                            PrimaryButton(
                                title = when (selectedSide) {
                                    Side.left -> tr("Measure left hand")
                                    Side.right -> tr("Measure right hand")
                                    Side.both -> tr("Start")
                                }, icon = Icons.Filled.PlayArrow, tint = palette.bleu,
                                enabled = !isSaving && !committed,
                                modifier = Modifier.testTag("max.measure.start"),
                            ) { start() }
                        }
                        MaxMeasurePhase.measuring -> PrimaryButton(
                            title = tr("Done"), icon = Icons.Filled.Stop, tint = palette.alarm,
                            modifier = Modifier.testTag("max.measure.finish"),
                        ) {
                            measurement.finish()
                            stop(StreamStopCause.userStopped)
                        }
                        MaxMeasurePhase.done -> {
                            val other = if (selectedSide == Side.left) Side.right else Side.left
                            if (!draft.bothTogether && draft.peak(other) == null) {
                                SecondaryButton(
                                    title = if (other == Side.left) tr("Measure left hand") else tr("Measure right hand"),
                                    enabled = !isSaving && !committed, modifier = Modifier.testTag("max.measure.other"),
                                ) { select(other) }
                            }
                            SecondaryButton(
                                title = tr("Try again"), icon = Icons.Outlined.Refresh,
                                enabled = !isSaving && !committed, modifier = Modifier.testTag("max.measure.retry"),
                            ) {
                                phase = MaxMeasurePhase.ready
                                keptPreviousAfterRetry = false
                                saveFailed = false
                            }
                        }
                    }
                }
                if (phase != MaxMeasurePhase.measuring && draft.results.isNotEmpty()) {
                    SecondaryButton(
                        title = tr("Adjust values"), icon = Icons.Outlined.Edit,
                        enabled = !isSaving && !committed, modifier = Modifier.testTag("max.measure.adjust"),
                    ) { adjusting = true }
                    PrimaryButton(
                        title = if (draft.results.size == 1) tr("Save max") else tr("Save maxes"),
                        icon = Icons.Filled.Check, enabled = !isSaving && !committed,
                        modifier = Modifier.testTag("max.measure.save"),
                    ) { save() }
                    if (draft.results.any { it.source == MaxSource.manual }) {
                        Text(tr("Adjusted values are saved as manual entries. Your recorded trace stays unchanged."),
                             style = MaterialTheme.typography.bodySmall, color = palette.inkTertiary,
                             textAlign = TextAlign.Center)
                    }
                }
                if (saveFailed) {
                    Text(tr("That couldn't be saved — nothing was recorded. Try again."),
                         style = MaterialTheme.typography.bodySmall, color = palette.alarm,
                         modifier = Modifier.testTag("max.measure.saveFailed"))
                }
            }
        }
    }

}

@Composable
private fun MeasurementHand(
    side: Side,
    peak: Double?,
    selected: Boolean,
    enabled: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val palette = LocalGripPalette.current
    val title = if (side == Side.left) tr("Left hand") else tr("Right hand")
    Surface(
        onClick = onClick,
        enabled = enabled,
        shape = RoundedCornerShape(Metrics.radiusInner),
        color = if (selected) palette.bleu.copy(alpha = 0.12f) else palette.inkPrimary.copy(alpha = 0.04f),
        border = BorderStroke(if (selected) 1.5.dp else 1.dp,
                             if (selected) palette.bleu else palette.inkTertiary.copy(alpha = 0.2f)),
        modifier = modifier.testTag("max.measure.${side.rawValue}").semantics {
            role = Role.Button
            this.selected = selected
            contentDescription = title
            stateDescription = if (peak == null) L10n.tr("Not measured")
                else L10n.tr("%s %s, ready to save", WeightUnits.number(peak, 1), WeightUnits.symbol)
        },
    ) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
               horizontalAlignment = Alignment.CenterHorizontally,
               verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall,
                 fontWeight = FontWeight.SemiBold, color = palette.inkPrimary)
            Text(peak?.let { WeightUnits.text(it) } ?: "—",
                 style = MaterialTheme.typography.titleLarge.copy(fontFeatureSettings = "tnum"),
                 color = if (selected) palette.inkPrimary else palette.inkSecondary)
        }
    }
}

private val TRACE_HEIGHT = 168.dp
private val HERO_SIZE = 76.sp
private val HERO_UNIT_SIZE = 21.sp

/// The measured peak climbs then holds; corrected working values live in the hand tiles. The
/// live reading is demoted beneath: watching the figure you are about to record fall toward
/// zero is not what anyone wants after a max effort.
@Composable
private fun Hero(measurement: MaxMeasurement, phase: MaxMeasurePhase) {
    val palette = LocalGripPalette.current
    val device = LocalDeviceStore.current
    val signal = if (phase == MaxMeasurePhase.measuring && (!device.isStreaming || !device.isSignalFresh))
        ". " + tr("No live reading") else ""
    val spoken = (when {
        measurement.hasResult ->
            WeightUnits.tr("%s kilograms, your hardest pull", WeightUnits.number(measurement.peakKg, 1))
        phase == MaxMeasurePhase.measuring -> tr("No pull yet")
        else -> tr("No measurement yet")
    }) + signal
    Column(
        Modifier
            .fillMaxWidth()
            .semantics(mergeDescendants = true) { contentDescription = spoken },
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(
                if (measurement.hasResult) WeightUnits.number(measurement.peakKg, 1) else tr("—"),
                style = MaterialTheme.typography.displayLarge.copy(
                    fontSize = HERO_SIZE,
                    fontFeatureSettings = "tnum",
                    // Display numerals carry negative tracking.
                    letterSpacing = (-0.02).em,
                ),
                fontWeight = FontWeight.Light,
                color = if (measurement.hasResult) palette.inkPrimary else palette.inkTertiary,
                maxLines = 1,
            )
            Text(
                WeightUnits.symbol,
                style = MaterialTheme.typography.titleMedium.copy(fontSize = HERO_UNIT_SIZE),
                color = palette.inkTertiary,
            )
        }
        LiveReadout()
    }
}

/// The instantaneous reading, in a leaf: `currentKg` changes ~80 times a second, and the hero,
/// controls and guidance must not recompose with it.
@Composable
private fun LiveReadout() {
    val device = LocalDeviceStore.current
    val isLive = device.isStreaming && device.isSignalFresh
    val palette = LocalGripPalette.current
    Row(
        // 80×/sec is unusable under TalkBack; the hero carries the summary.
        modifier = Modifier.clearAndSetSemantics {},
        horizontalArrangement = Arrangement.spacedBy(3.dp),
        verticalAlignment = Alignment.Bottom,
    ) {
        Text(
            if (isLive) tr("now") else tr("No live reading"),
            style = MaterialTheme.typography.labelMedium,
            color = palette.inkTertiary,
        )
        Text(
            // **Clocks roll, measurements SNAP**: no transition on a figure changing ten times a second.
            if (isLive) WeightUnits.number(device.currentKg, 1) else "—",
            style = MaterialTheme.typography.bodyLarge.copy(fontFeatureSettings = "tnum"),
            fontWeight = FontWeight.SemiBold,
            color = if (isLive) palette.bleu else palette.inkTertiary,
        )
        Text(WeightUnits.symbol, style = MaterialTheme.typography.labelMedium, color = palette.inkTertiary)
    }
}

/// With the result BEING the peak, there is no peak-versus-held gap left to footnote.
private fun guidance(measurement: MaxMeasurement, phase: MaxMeasurePhase): String = when (phase) {
    MaxMeasurePhase.ready ->
        L10n.tr("Build force gradually and stop if it hurts. This measures a peak, not a safe training limit.")
    MaxMeasurePhase.measuring ->
        if (measurement.hasResult) L10n.tr("Let go when you are ready to finish.") else L10n.tr("Pull…")
    MaxMeasurePhase.done ->
        if (measurement.hasResult) L10n.tr("Save this as your max on this grip, or try again.")
        else L10n.tr("No pull was recorded. You can close this or try again.")
}

// MARK: - State

/// The attempt's timing, as pure numbers, so the deadline can be asserted without a screen.
object MaxMeasureTiming {
    /// Long enough for a slow set-up on the edge; short enough that a forgotten screen cannot
    /// flatten the gauge (the builder's threshold-check guard, sized for a longer job).
    const val TIMEOUT_SECONDS: Double = 45.0

    fun hasTimedOut(secondsSinceStart: Double): Boolean = secondsSinceStart >= TIMEOUT_SECONDS
}

/// Owns the `MaxAttempt` and publishes ONLY what the screen draws.
///
/// The attempt is a plain field and display values are written only when they CHANGE: at ~80
/// samples a second an unconditional mirror invalidates the view per sample (what made the
/// routine deck laggy). The peak holds still after the ramp, so the screen settles with the pull.
///
/// TRANSLATION NOTE: iOS uses `@ObservationIgnored` and `if x != y` guards. `mutableStateOf`
/// already skips equal writes, but implicitly; the explicit guard plus `publishes` lets a JVM
/// test assert "80 identical samples published once".
class MaxMeasurement {
    var peakKg: Double by mutableDoubleStateOf(0.0)
        private set

    var isComplete: Boolean by mutableStateOf(false)
        private set

    /// A TEST SEAM and nothing else.
    var publishes: Int = 0
        private set

    private var attempt = MaxAttempt()

    /// Off the SAME constant as `MaxAttempt.hasResult`, so the screen never offers a non-pull.
    val hasResult: Boolean get() = peakKg >= MaxAttempt.releaseKg

    fun receive(point: DeviceStore.TracePoint) {
        attempt.add(point.kg, point.t)
        publish()
    }

    /// Take what has been pulled so far — the manual "Done", and the timeout. Idempotent.
    fun finish() {
        attempt.finish()
        publish()
    }

    fun reset() {
        attempt = MaxAttempt()
        peakKg = 0.0
        isComplete = false
        publishes = 0
    }

    private fun publish() {
        var changed = false
        if (peakKg != attempt.peakKg) {
            peakKg = attempt.peakKg
            changed = true
        }
        if (isComplete != attempt.isComplete) {
            isComplete = attempt.isComplete
            changed = true
        }
        if (changed) publishes += 1
    }
}

@Preview(name = "MaxMeasure", showBackground = true, widthDp = 400, heightDp = 860)
@Composable
private fun MaxMeasurePreview() {
    // A real `DeviceStore` over the MOCK client (no Context, no radio until `connect()`), so the
    // preview shows the DISCONNECTED face — the one worth checking by eye.
    val scope = rememberCoroutineScope()
    val device = remember(scope) {
        DeviceStore(
            client = MockProgressorClient(scope, MockForceProfile.clean),
            isMock = true,
            scope = scope,
        )
    }
    GetAGripTheme {
        CompositionLocalProvider(LocalDeviceStore provides device) {
            MaxMeasureScreen(grip = GripSpec(), onSave = { null }, onClose = {})
        }
    }
}
