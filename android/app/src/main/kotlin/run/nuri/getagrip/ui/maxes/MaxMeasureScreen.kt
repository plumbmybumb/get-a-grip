// SPDX-License-Identifier: MPL-2.0
// Original contributions Copyright 2026 Nuri Bruner.

package run.nuri.getagrip.ui.maxes

import run.nuri.getagrip.ui.units.WeightUnits

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
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
import run.nuri.getagrip.engine.Fmt
import run.nuri.getagrip.engine.GripSpec
import run.nuri.getagrip.engine.L10n
import run.nuri.getagrip.engine.MaxAttempt
import run.nuri.getagrip.engine.RunnerPhase
import run.nuri.getagrip.engine.Side
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
import run.nuri.getagrip.ui.theme.Motion

private enum class MaxMeasurePhase { ready, measuring, done }

/// Measuring a max on the gauge, as its own full screen.
///
/// **Why it takes the whole screen rather than sitting in the composer sheet:** during the
/// one moment this view exists for, the phone is propped on a bench and you are hanging off
/// a fingerboard with both hands. A live number inside a scrolling form is unreadable from
/// there, and it can be scrolled away by the same finger that started it. Everything here
/// is sized to be read at arm's length.
///
/// The rule — **the hardest the gauge saw** — lives in `MaxAttempt`, tested away from any of
/// this, along with the reason it is no longer a sustained hold. The result is drawn across
/// the trace as the dashed rule, so the shape of the pull and the number it produced are one
/// picture rather than two things to reconcile.
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MaxMeasureScreen(
    grip: GripSpec,
    /// Handed the measured result when it is accepted. **The caller owns saving** — this
    /// screen never writes to the store, so "measure" and "record" stay separable and the
    /// number lands in the same field a typed one would.
    onMeasured: (Double, Side) -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
    initialSide: Side = Side.both,
    isSaving: Boolean = false,
    saveFailed: Boolean = false,
    onMeasurementStarted: () -> Unit = {},
) {
    val device = LocalDeviceStore.current
    val palette = LocalGripPalette.current
    val haptics = LocalHapticFeedback.current

    val measurement = remember { MaxMeasurement() }
    var frozenTrace by remember { mutableStateOf<List<DeviceStore.TracePoint>?>(null) }
    var phase by remember { mutableStateOf(MaxMeasurePhase.ready) }
    var selectedSide by remember(initialSide) { mutableStateOf(initialSide) }
    /// Bumped on every Start, so the timeout effect restarts with the attempt rather than
    /// continuing to count from the first one.
    var attemptTick by remember { mutableIntStateOf(0) }
    var promptedKg by remember { mutableStateOf<Double?>(null) }
    var promptedEpoch by remember { mutableStateOf(0uL) }

    // A screen you look at with both hands on an edge; the phone timing out mid-pull is the
    // app going blind.
    KeepScreenOn(phase == MaxMeasurePhase.measuring)

    fun stop(cause: StreamStopCause) {
        if (phase != MaxMeasurePhase.measuring) return
        device.onTracePoint = null
        if (device.isStreaming) device.stopStreaming(cause)
        frozenTrace = device.trace.toList()
        phase = MaxMeasurePhase.done
    }

    fun start() {
        if (!device.state.isConnected) return
        onMeasurementStarted()
        measurement.reset()
        // The trace is the attempt's own picture; leftovers from a previous go would be
        // drawn as part of this one, and the axis is latched off what it has seen.
        device.resetPeak()
        // **`onTracePoint`, not `onSample`.** The runner accrues hang time from raw device
        // deltas; anything measuring over a WINDOW OF SECONDS — which is what ending an
        // attempt is — needs the store's monotone PLAYBACK clock, which cannot jump
        // backwards across a tare or a device counter reset.
        device.onTracePoint = { point -> measurement.receive(point) }
        device.startStreaming(StreamStartCause.manualMeasurement)
        frozenTrace = null
        phase = MaxMeasurePhase.measuring
        attemptTick += 1
    }

    // Pull, hold, let go — no tap. `MaxAttempt` ends itself once the load has been off the
    // edge for two seconds, and this is the only thing watching for it.
    LaunchedEffect(measurement.isComplete) {
        if (measurement.isComplete && phase == MaxMeasurePhase.measuring) {
            // The moment a result exists is worth feeling: you are looking at the edge, not
            // at the phone.
            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
            stop(StreamStopCause.measurementComplete)
        }
    }

    // Long enough for a full attempt including a slow set-up on the edge; short enough that
    // a screen left open cannot flatten the gauge's battery.
    LaunchedEffect(attemptTick, phase) {
        if (phase != MaxMeasurePhase.measuring) return@LaunchedEffect
        delay((MaxMeasureTiming.TIMEOUT_SECONDS * 1000).toLong())
        // The cause travels from the TRIGGER, because `finish` is reached from two of them.
        // Labelling both "measurement finished" would put a completion in the log for a pull
        // that never completed.
        measurement.finish()
        stop(StreamStopCause.timedOut)
    }

    // A disconnect clears `isStreaming`. Keep the attempt and its callback alive, then
    // explicitly restart the stream when auto-reconnect restores the link.
    LaunchedEffect(device.state.isConnected) {
        if (device.state.isConnected && phase == MaxMeasurePhase.measuring) {
            device.startStreaming(StreamStartCause.reconnect)
        }
    }

    // UNCONDITIONAL, and gated on the DEVICE's own truth rather than on `phase`: a gauge
    // left streaming behind a dismissed screen is a dead battery the user blames on the app.
    DisposableEffect(device) {
        onDispose {
            device.onTracePoint = null
            if (device.isStreaming) device.stopStreaming(StreamStopCause.screenClosed)
        }
    }

    Scaffold(
        containerColor = androidx.compose.ui.graphics.Color.Transparent,
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(tr("Measure a max"), style = MaterialTheme.typography.titleMedium)
                        Text(
                            grip.displayName,
                            style = MaterialTheme.typography.bodySmall,
                            color = palette.inkTertiary,
                        )
                    }
                },
                windowInsets = WindowInsets(0, 0, 0, 0),
                navigationIcon = {
                    IconButton(onClick = onCancel, enabled = !isSaving) {
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
                Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .heightIn(min = maxHeight)
                    .padding(horizontal = Metrics.hPadding)
                    .padding(bottom = Metrics.spacing),
                verticalArrangement = Arrangement.spacedBy(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Column(
                    Modifier.widthIn(max = Metrics.maxContentWidth).fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    CapsLabel(tr("THIS MAX IS FOR"))
                    MaxHandPicker(
                        selectedSide = selectedSide,
                        onSelected = { selectedSide = it },
                        enabled = phase != MaxMeasurePhase.measuring && !isSaving,
                    )
                }

                CapsLabel(
                    if (phase == MaxMeasurePhase.done) tr("YOUR MAX ON THIS GRIP") else tr("HARDEST PULL"),
                    Modifier.fillMaxWidth(),
                )

                Hero(measurement, phase)

                // The shape of the pull, with the result drawn across it as the dashed rule.
                Surface(
                    shape = RoundedCornerShape(Metrics.radiusCard),
                    color = palette.card,
                    modifier = Modifier
                        .widthIn(max = Metrics.maxContentWidth)
                        .fillMaxWidth()
                        .height(TRACE_HEIGHT),
                ) {
                    ForceTraceView(
                        frozenSamples = frozenTrace,
                        modifier = Modifier.fillMaxSize().padding(horizontal = 4.dp),
                        thresholdKg = if (measurement.hasResult) measurement.peakKg else null,
                        tint = if (phase == MaxMeasurePhase.measuring) palette.bleu else palette.inkTertiary,
                    )
                }

                Text(
                    guidance(measurement, phase),
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                    color = palette.inkSecondary,
                    textAlign = TextAlign.Center,
                )

                Spacer(Modifier.weight(1f))

                // A completed attempt is local data: disconnecting cannot hide its save action.
                if (!device.state.isConnected && phase != MaxMeasurePhase.done) {
                    // SHOWN rather than a disabled button: a control you cannot use teaches
                    // nothing, and the way out is what matters here.
                    Text(
                        tr("Connect your gauge to measure. You can always type a max in instead."),
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = FontWeight.Medium,
                        color = palette.inkTertiary,
                        textAlign = TextAlign.Center,
                    )
                    PrimaryButton(
                        // A static "Connect" during the multi-second BLE connect showed a dimmed
                        // button with no state change — worse here than on the gauge screen,
                        // because the user has already committed to the gauge path.
                        title = if (device.state.isBusy) device.state.label else tr("Connect"),
                        icon = Icons.Outlined.SettingsInputAntenna,
                        tint = palette.bleu,
                        enabled = !device.state.isBusy,
                        modifier = Modifier.widthIn(max = Metrics.maxContentWidth),
                    ) { device.connect() }
                } else {
                    when (phase) {
                        MaxMeasurePhase.ready -> {
                            // Zeroing belongs BEFORE the pull and nowhere else: taring
                            // mid-attempt would zero out the load already on the edge and
                            // silently rewrite the result. It is offered here because a hanging
                            // sling or a mounted block reads as several kilograms the gauge would
                            // otherwise count.
                            SecondaryButton(
                                title = if (device.isReadingLive) tr("Zero the gauge") else tr("Wake"),
                                icon = Icons.Outlined.Refresh,
                                modifier = Modifier.widthIn(max = Metrics.maxContentWidth).fillMaxWidth(),
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
                                    TareTapDecision.blocked -> Unit
                                    TareTapDecision.confirm -> {
                                        promptedKg = device.currentKg
                                        promptedEpoch = device.connectionEpoch
                                    }
                                    TareTapDecision.tare -> {
                                        if (
                                            TarePolicy.isSafeToTareNow(
                                                device.secondsSinceLastSample(),
                                                device.tareReadingMaxAge,
                                            )
                                        ) {
                                            device.tare()
                                            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                        } else {
                                            device.startStreaming(StreamStartCause.manualWake)
                                        }
                                    }
                                }
                            }
                            PrimaryButton(
                                title = tr("Start"),
                                icon = Icons.Filled.PlayArrow,
                                tint = palette.bleu,
                                modifier = Modifier.widthIn(max = Metrics.maxContentWidth),
                            ) { start() }
                        }
                        MaxMeasurePhase.measuring -> PrimaryButton(
                            title = tr("Done"),
                            icon = Icons.Filled.Stop,
                            tint = palette.alarm,
                            modifier = Modifier.widthIn(max = Metrics.maxContentWidth),
                        ) {
                            measurement.finish()
                            stop(StreamStopCause.userStopped)
                        }
                        MaxMeasurePhase.done -> {
                            if (saveFailed) {
                                Text(
                                    tr("That couldn't be saved — nothing was recorded. Try again."),
                                    style = MaterialTheme.typography.bodySmall,
                                    fontWeight = FontWeight.Medium,
                                    color = palette.alarm,
                                )
                            }
                            SecondaryButton(
                                title = tr("Try again"),
                                icon = Icons.Outlined.Refresh,
                                enabled = device.state.isConnected && !isSaving,
                                modifier = Modifier.widthIn(max = Metrics.maxContentWidth).fillMaxWidth(),
                            ) { start() }
                            PrimaryButton(
                                title = tr("Use this max"),
                                icon = Icons.Filled.Check,
                                enabled = measurement.hasResult && !isSaving,
                                modifier = Modifier.widthIn(max = Metrics.maxContentWidth),
                            ) { onMeasured(measurement.peakKg, selectedSide) }
                        }
                    }
                }
            }
        }
    }

    val prompted = promptedKg
    if (prompted != null) {
        // Taring under load can corrupt every reading after it, so a meaningful load is
        // confirmed with the actual number rather than silently refused — and the
        // confirmation revalidates the epoch, the phase and the signed load delta before
        // anything is written.
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
                TextButton(onClick = confirm@ {
                    if (phase != MaxMeasurePhase.ready) {
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
                        // The load moved while the alert was open, so the number it quoted is
                        // no longer true — ask again with the one that is.
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

private val TRACE_HEIGHT = 168.dp
private val HERO_SIZE = 76.sp
private val HERO_UNIT_SIZE = 21.sp

/// THE NUMBER THAT WILL BE SAVED — the peak, which climbs and then holds still. The live
/// reading stays demoted to the line underneath: it falls away the instant you ease off, and
/// watching the figure you are about to record drop back toward zero is not what anyone
/// wants at the end of a max effort.
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
                    // Display numerals carry negative tracking — letterforms read further
                    // apart as they grow.
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

/// The instantaneous reading, isolated in its own composable for ONE reason: `currentKg`
/// changes ~80 times a second, so every composable that reads it recomposes at that rate.
/// Keeping it in a leaf means the hero, the controls and the guidance — none of which change
/// during a pull — are not dragged along with it.
@Composable
private fun LiveReadout() {
    val device = LocalDeviceStore.current
    val isLive = device.isStreaming && device.isSignalFresh
    val palette = LocalGripPalette.current
    Row(
        // A numeral changing 80×/sec is unusable under TalkBack; the hero carries the
        // accessible summary.
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
            // **Clocks roll, measurements SNAP.** No numeric transition and no animation on
            // this figure: on a readout that changes ten times a second the same animation
            // turns the number you are trying to read mid-pull into a permanent blur.
            if (isLive) WeightUnits.number(device.currentKg, 1) else "—",
            style = MaterialTheme.typography.bodyLarge.copy(fontFeatureSettings = "tnum"),
            fontWeight = FontWeight.SemiBold,
            color = if (isLive) palette.bleu else palette.inkTertiary,
        )
        Text(WeightUnits.symbol, style = MaterialTheme.typography.labelMedium, color = palette.inkTertiary)
    }
}

/// No peak-versus-held footnote any more: with the result BEING the peak there is no gap
/// left to explain, and the line that explained it went with the rule.
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
    /// Long enough for a full attempt including a slow set-up on the edge; short enough that
    /// a screen left open cannot flatten the gauge's battery. The same guard the builder's
    /// threshold check uses, sized for a longer job.
    const val TIMEOUT_SECONDS: Double = 45.0

    fun hasTimedOut(secondsSinceStart: Double): Boolean = secondsSinceStart >= TIMEOUT_SECONDS
}

/// Owns the `MaxAttempt` and publishes ONLY what the screen draws.
///
/// The attempt itself is a plain field — not observable — and the display values are written
/// only when they actually CHANGE. That matters more than it looks: samples arrive ~80 times
/// a second, and a mirror that wrote unconditionally would invalidate the view on every one
/// of them, which is the exact pattern that made the routine deck feel laggy. The peak climbs
/// during the ramp and then holds still, so the screen settles the moment the pull does.
///
/// TRANSLATION NOTE: iOS marks the attempt `@ObservationIgnored` inside an `@Observable`
/// class and guards each write with `if x != y`. Compose's `mutableStateOf` already skips a
/// write of an equal value — but only for the STRUCTURAL equality it can see, and relying on
/// that leaves the rule undocumented and untestable. So the guard is explicit, and
/// `publishes` counts the writes that got through: it is the only way a JVM test can assert
/// "80 identical samples published once", which is the whole point of this class.
class MaxMeasurement {
    var peakKg: Double by mutableDoubleStateOf(0.0)
        private set

    var isComplete: Boolean by mutableStateOf(false)
        private set

    /// How many times a value actually changed. A TEST SEAM and nothing else — see the note
    /// above.
    var publishes: Int = 0
        private set

    private var attempt = MaxAttempt()

    /// Mirrors `MaxAttempt.hasResult` off the SAME constant — a screen that offered to save a
    /// number the engine does not consider a pull would be the two disagreeing.
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
    // A real `DeviceStore` over the MOCK client — it needs no Context and touches no radio
    // until `connect()`, so a preview renders the DISCONNECTED face. That is the one worth
    // checking by eye anyway: the live faces are a number and a curve.
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
            MaxMeasureScreen(grip = GripSpec(), onMeasured = { _, _ -> }, onCancel = {})
        }
    }
}
