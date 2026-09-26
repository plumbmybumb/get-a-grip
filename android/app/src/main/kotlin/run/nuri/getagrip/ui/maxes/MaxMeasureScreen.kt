// SPDX-License-Identifier: MPL-2.0
// Original contributions Copyright 2026 Nuri Bruner.

package run.nuri.getagrip.ui.maxes

import androidx.activity.compose.BackHandler
import androidx.activity.compose.LocalActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.outlined.SettingsInputAntenna
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch
import run.nuri.getagrip.ble.MockForceProfile
import run.nuri.getagrip.ble.MockProgressorClient
import run.nuri.getagrip.ble.ProgressorConnectionState
import run.nuri.getagrip.ble.StreamStartCause
import run.nuri.getagrip.ble.StreamStopCause
import run.nuri.getagrip.engine.GripSpec
import run.nuri.getagrip.engine.MaxMeasurementDraft
import run.nuri.getagrip.engine.MaxMeasurementResult
import run.nuri.getagrip.engine.Side
import run.nuri.getagrip.runner.KeepScreenOn
import run.nuri.getagrip.store.DeviceStore
import run.nuri.getagrip.store.LocalDeviceStore
import run.nuri.getagrip.store.LocalTemplateStore
import run.nuri.getagrip.store.TemplateStore
import run.nuri.getagrip.ui.components.AdaptiveActionRow
import run.nuri.getagrip.ui.components.CapsLabel
import run.nuri.getagrip.ui.components.ForceTraceView
import run.nuri.getagrip.ui.components.PrimaryButton
import run.nuri.getagrip.ui.l10n.tr
import run.nuri.getagrip.ui.theme.GetAGripTheme
import run.nuri.getagrip.ui.theme.InstrumentSurface
import run.nuri.getagrip.ui.theme.LocalGripPalette
import run.nuri.getagrip.ui.theme.Metrics
import run.nuri.getagrip.ui.theme.readablePageWidth
import run.nuri.getagrip.ui.units.WeightUnits

/// **A max test is a visit, not a take** (Nuri, 2026-09-25, after trying Frez's): the gauge
/// connects and reads the moment this opens, every pull is logged as an attempt against the
/// selected hand, and you keep pulling until the number stops climbing. Switch hands with a
/// tap; Review picks the pull each hand keeps and saves.
///
/// The gauge screen's anatomy — the numbers on one panel over a state wash, the trace taking
/// the slack, every action in one dock. The dashed rule is the number to beat: this visit's
/// best on the selected hand, else its saved max.
///
/// TRANSLATION NOTE (from Sources/UI/Maxes/MaxMeasureView.swift): iOS draws the trace as the
/// screen under Liquid Glass. This port has no glass, so the stack is laid out the way the
/// Android runner and the critical force test translate it: the panel and the dock are
/// `InstrumentSurface`s, and the trace sits in a card between them that takes the slack.
/// The review is a full-screen layer over the visit rather than a sheet; the visit's effects
/// keep running under it, exactly as the iOS sheet leaves the live screen reading.
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MaxMeasureScreen(
    grip: GripSpec,
    onSave: suspend (List<MaxMeasurementResult>) -> TemplateStore.MaxSaveReceipt?,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
    initialSide: Side = Side.left,
    /// The caller may hold the session (the root does, so a rotation keeps the pulls).
    session: LiveMaxSession = remember(grip.key, initialSide) {
        LiveMaxSession(bothTogether = initialSide == Side.both, side = initialSide)
    },
    /// The saved max on a hand (both-hands fallback), for the number to beat and the hand
    /// captions. Null reads the template store.
    savedMax: ((Side) -> Double?)? = null,
) {
    val templates = if (savedMax == null) LocalTemplateStore.current else null
    val maxOn: (Side) -> Double? = savedMax ?: { side -> templates?.currentMax(grip, side) }
    val device = LocalDeviceStore.current
    val palette = LocalGripPalette.current
    val haptics = LocalHapticFeedback.current
    val scope = rememberCoroutineScope()
    val activity = LocalActivity.current
    val close by rememberUpdatedState(onClose)

    // Awake for the whole visit: it reads from the moment it opens.
    KeepScreenOn(true)

    fun startReading() {
        if (!session.hasStarted || session.committed || !device.state.isConnected || device.isStreaming) return
        device.resetPeak()
        device.startStreaming(
            if (session.snapshot.hasAttempts) StreamStartCause.reconnect else StreamStartCause.manualMeasurement,
        )
    }

    fun teardown() {
        device.onTracePoint = null
        if (device.isStreaming) device.stopStreaming(StreamStopCause.screenClosed)
    }

    // Connect if needed and read at once — no Start. A gauge already connected streams
    // immediately; otherwise the first connection starts the stream.
    DisposableEffect(device, session) {
        if (!session.committed) {
            // Capture the session object, never a changing lookup.
            device.onTracePoint = { point -> session.receive(point) }
            if (!session.hasStarted) {
                session.hasStarted = true
                if (device.state.isConnected) startReading() else if (!device.state.isBusy) device.connect()
            }
        }
        onDispose {
            // A rotation is not leaving: the session outlives it and the next composition
            // re-installs the callback on a stream that never stopped.
            if (activity?.isChangingConfigurations != true) teardown()
        }
    }
    LaunchedEffect(device.state.isConnected) {
        if (device.state.isConnected) startReading() else session.close()
    }
    // The success haptic names its cause: a pull that beat its hand's best.
    LaunchedEffect(session) {
        snapshotFlow { session.newBestTick }.drop(1).collect {
            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
        }
    }

    fun save() {
        session.close()
        val results = session.snapshot.results
        if (session.isSaving || session.committed || results.isEmpty()) return
        session.isSaving = true
        session.saveFailed = false
        scope.launch {
            try {
                val receipt = onSave(results)
                if (receipt == null) {
                    session.saveFailed = true
                } else {
                    session.committed = true
                    teardown()
                    session.reviewing = false
                    if (receipt.hasDetails) session.receipt = receipt else close()
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                session.saveFailed = true
            } finally {
                session.isSaving = false
            }
        }
    }

    BackHandler(enabled = session.receipt == null) {
        if (session.isSaving) return@BackHandler
        when {
            session.adjusting -> session.adjusting = false
            session.reviewing -> session.reviewing = false
            else -> onClose()
        }
    }

    session.receipt?.let { receipt ->
        MaxSaveReceiptScreen(receipt = receipt, onDone = onClose)
        return
    }
    if (session.adjusting) {
        val draft = session.snapshot
        MaxMeasurementCorrectionScreen(
            results = draft.results,
            measuredPeaks = draft.results.associate { it.side to (draft.measuredPeak(it.side) ?: it.kg) },
            onApply = { values -> if (session.correct(values)) session.adjusting = false },
            onCancel = { session.adjusting = false },
        )
        return
    }
    if (session.reviewing) {
        MaxAttemptReviewScreen(session, onSave = ::save, onClose = { session.reviewing = false })
        return
    }

    val scrollsForLargeText = LocalDensity.current.fontScale >= 1.5f
    Box(modifier.fillMaxSize()) {
        MaxWash(session)
        // Hosted at the ROOT, so nothing above pads the system bars: keep the Material defaults.
        Scaffold(
            containerColor = Color.Transparent,
            topBar = {
                TopAppBar(
                    title = {
                        Column {
                            Text(tr("Measure a max"), style = MaterialTheme.typography.titleMedium)
                            Text(grip.displayName, style = MaterialTheme.typography.bodySmall, color = palette.inkTertiary)
                        }
                    },
                    navigationIcon = {
                        IconButton(onClick = onClose, enabled = !session.isSaving,
                            modifier = Modifier.testTag("max.measure.cancel")) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = tr("Cancel"))
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = Color.Transparent,
                        scrolledContainerColor = palette.card,
                        titleContentColor = palette.inkPrimary,
                        navigationIconContentColor = palette.inkPrimary,
                    ),
                )
            },
        ) { padding ->
            Column(
                Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .readablePageWidth()
                    .then(if (scrollsForLargeText) Modifier.verticalScroll(rememberScrollState()) else Modifier)
                    .padding(horizontal = Metrics.hPadding)
                    .padding(top = 8.dp, bottom = Metrics.spacing),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                InfoPanel(session, maxOn)
                Surface(
                    shape = RoundedCornerShape(Metrics.radiusCard),
                    color = palette.card,
                    modifier = Modifier
                        .widthIn(max = Metrics.maxContentWidth)
                        .fillMaxWidth()
                        .then(if (scrollsForLargeText) Modifier.height(220.dp) else Modifier.weight(1f))
                        .testTag("max.measure.graph"),
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        MaxLiveTrace(session, maxOn)
                        GraphNotice()
                    }
                }
                Dock(session, onReview = {
                    session.close()
                    session.saveFailed = false
                    session.reviewing = true
                })
            }
        }
    }
}

/// Bleu while a pull is under way, steel between pulls — so the top of the phone says
/// "that one is counting" before a number is read. A leaf: `isPulling` flips twice a pull.
@Composable
private fun MaxWash(session: LiveMaxSession) {
    val palette = LocalGripPalette.current
    val tint = if (session.isPulling) palette.bleu else palette.calm
    Box(Modifier.fillMaxWidth().height(260.dp)
        .background(Brush.verticalGradient(listOf(tint.copy(alpha = 0.14f), Color.Transparent))))
}

@Composable
private fun InfoPanel(session: LiveMaxSession, savedMax: (Side) -> Double?) {
    InstrumentSurface(
        modifier = Modifier.widthIn(max = Metrics.maxContentWidth).fillMaxWidth().testTag("max.measure.panel"),
    ) {
        Column(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp).padding(top = 12.dp, bottom = 14.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            if (session.bothTogether) {
                CapsLabel(tr("Both hands together"))
            } else {
                MaxHandSwitch(session, savedMax)
            }
            MaxLiveHero(session)
        }
    }
}

// MARK: - Hands

/// Left and right as two wells inside the panel. Each states the hand's best this visit and
/// how many pulls it took; the selected one is the bleu well. Reads only `snapshot`, so it
/// recomposes when a pull is logged, not per sample.
@Composable
private fun MaxHandSwitch(session: LiveMaxSession, savedMax: (Side) -> Double?) {
    val haptics = LocalHapticFeedback.current
    val draft = session.snapshot
    val enabled = !session.isPulling && !session.committed
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        listOf(Side.left, Side.right).forEach { side ->
            HandTile(
                side = side,
                selected = side == draft.log.side,
                pulls = draft.log.attempts(side).size,
                peak = draft.peak(side),
                savedMax = savedMax,
                enabled = enabled,
                modifier = Modifier.weight(1f),
            ) {
                if (side != session.side) {
                    session.select(side)
                    haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                }
            }
        }
    }
}

@Composable
private fun HandTile(
    side: Side,
    selected: Boolean,
    pulls: Int,
    peak: Double?,
    savedMax: (Side) -> Double?,
    enabled: Boolean,
    modifier: Modifier,
    onClick: () -> Unit,
) {
    val palette = LocalGripPalette.current
    val ink = if (selected) palette.bleu else palette.inkSecondary
    val caption = when {
        pulls > 0 -> tr("%d pulls", pulls)
        else -> savedMax(side)?.let { tr("Max %s", WeightUnits.number(it)) } ?: tr("Not measured")
    }
    val title = if (side == Side.left) tr("Left hand") else tr("Right hand")
    val state = peak?.let { tr("%s %s, best of %d pulls", WeightUnits.number(it), WeightUnits.spoken, pulls) }
        ?: tr("Not measured")
    Surface(
        onClick = onClick,
        enabled = enabled,
        shape = RoundedCornerShape(Metrics.radiusInner),
        color = if (selected) palette.bleu.copy(alpha = 0.16f) else palette.inkPrimary.copy(alpha = 0.05f),
        modifier = modifier.testTag("max.measure.${side.rawValue}").semantics {
            role = Role.Button
            this.selected = selected
            contentDescription = title
            stateDescription = state
        },
    ) {
        Column(
            Modifier.padding(horizontal = 8.dp, vertical = 10.dp).clearAndSetSemantics {},
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(side.displayName, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold,
                color = ink, maxLines = 1)
            Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                // A measurement SNAPS: no transition.
                Text(peak?.let { WeightUnits.number(it) } ?: "—",
                    style = MaterialTheme.typography.titleLarge.copy(fontFeatureSettings = "tnum"),
                    fontWeight = FontWeight.SemiBold, color = ink, maxLines = 1)
                if (peak != null) {
                    Text(WeightUnits.symbol, style = MaterialTheme.typography.labelMedium, color = ink,
                        modifier = Modifier.padding(bottom = 3.dp))
                }
            }
            Text(caption, style = MaterialTheme.typography.bodySmall, color = ink, maxLines = 1)
        }
    }
}

// MARK: - Per-sample leaves

private val HERO_SIZE = 78.sp
private val HERO_UNIT_SIZE = 22.sp

/// The hero: THIS pull's peak while it climbs, then the last pull's result until the next one
/// starts. The peak, not the live reading — watching the number you are about to log fall away
/// as you let go is not what anyone wants at the end of a max effort. The live reading is the
/// caption under it.
@Composable
private fun MaxLiveHero(session: LiveMaxSession) {
    val palette = LocalGripPalette.current
    val pulling = session.pullPeakKg
    val last = session.lastAttempt
    val shown = pulling ?: last?.peakKg
    val label = when {
        pulling != null -> tr("This pull")
        last == null -> tr("Pull when ready")
        else -> tr("Last pull")
    }
    val spoken = when {
        shown == null -> tr("No pull yet")
        pulling != null -> tr("Pulling, %s %s so far", WeightUnits.number(shown), WeightUnits.spoken)
        else -> tr("Last pull %s %s", WeightUnits.number(shown), WeightUnits.spoken)
    }
    val ink = when {
        pulling != null -> palette.bleu
        shown == null -> palette.inkTertiary
        else -> palette.inkPrimary
    }
    Column(
        Modifier.fillMaxWidth().testTag("max.measure.hero")
            .clearAndSetSemantics { contentDescription = spoken },
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        CapsLabel(label)
        Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            BasicText(
                shown?.let { WeightUnits.number(it) } ?: "—",
                style = TextStyle(
                    fontSize = HERO_SIZE,
                    fontWeight = FontWeight.Thin,
                    fontFeatureSettings = "tnum",
                    // Display numerals carry negative tracking.
                    letterSpacing = (-0.02).em,
                    color = ink,
                ),
                maxLines = 1,
                autoSize = TextAutoSize.StepBased(minFontSize = 40.sp, maxFontSize = HERO_SIZE),
                modifier = Modifier.weight(1f, fill = false),
            )
            Text(WeightUnits.symbol, style = TextStyle(fontSize = HERO_UNIT_SIZE), color = palette.inkTertiary,
                modifier = Modifier.padding(bottom = 12.dp))
        }
        MaxLiveReadout()
    }
}

/// The instantaneous reading, in a leaf because `currentKg` changes ~80×/s.
@Composable
private fun MaxLiveReadout() {
    val device = LocalDeviceStore.current
    val palette = LocalGripPalette.current
    val isLive = device.isStreaming && device.isSignalFresh
    Row(
        horizontalArrangement = Arrangement.spacedBy(3.dp),
        verticalAlignment = Alignment.Bottom,
    ) {
        Text(if (isLive) tr("now") else tr("No live reading"),
            style = MaterialTheme.typography.labelMedium, color = palette.inkTertiary)
        if (isLive) {
            Text(WeightUnits.number(device.currentKg),
                style = MaterialTheme.typography.titleSmall.copy(fontFeatureSettings = "tnum"),
                fontWeight = FontWeight.SemiBold, color = palette.inkSecondary)
            Text(WeightUnits.symbol, style = MaterialTheme.typography.labelMedium, color = palette.inkTertiary)
        }
    }
}

/// The lit trace, with the number to beat as its dashed rule: this visit's best on the
/// selected hand, else that hand's saved max.
@Composable
private fun MaxLiveTrace(session: LiveMaxSession, savedMax: (Side) -> Double?) {
    val device = LocalDeviceStore.current
    val palette = LocalGripPalette.current
    val toBeat = maxToBeat(session.snapshot, savedMax)
    ForceTraceView(
        modifier = Modifier.fillMaxSize().padding(horizontal = 4.dp).clearAndSetSemantics {},
        thresholdKg = toBeat,
        tint = if (device.isStreaming) palette.bleu else palette.inkTertiary,
        lit = true,
    )
}

/// Why nothing is being drawn, when nothing is: not connected, connecting, or connected and
/// silent. Its own leaf, with its own grace timer, so the screen never waits on it.
@Composable
private fun GraphNotice() {
    val device = LocalDeviceStore.current
    val palette = LocalGripPalette.current
    var waitingForSignal by remember { mutableStateOf(false) }
    LaunchedEffect(device.isStreaming) {
        waitingForSignal = false
        if (!device.isStreaming) return@LaunchedEffect
        delay(1_500)
        waitingForSignal = true
    }
    val text = when {
        !device.state.isConnected ->
            if (device.state.isBusy) tr("Connecting to your gauge…") else tr("Connect your gauge to measure. Every pull counts.")
        device.isStreaming && waitingForSignal && !device.isSignalFresh ->
            tr("Waiting for the gauge. It's connected but not sending — try Wake.")
        else -> null
    } ?: return
    Text(text, style = MaterialTheme.typography.bodyMedium, color = palette.inkSecondary, textAlign = TextAlign.Center,
        modifier = Modifier.padding(horizontal = 24.dp).testTag("max.measure.notice"))
}

/// The number to beat on the selected hand: this visit's best, else that hand's saved max.
internal fun maxToBeat(draft: MaxMeasurementDraft, savedMax: (Side) -> Double?): Double? {
    val side = draft.log.side
    return draft.log.best(side)?.peakKg ?: savedMax(side)
}

// MARK: - Dock

@Composable
private fun Dock(session: LiveMaxSession, onReview: () -> Unit) {
    val device = LocalDeviceStore.current
    val palette = LocalGripPalette.current
    val attempts = session.snapshot.log.attempts.size
    InstrumentSurface(
        Modifier.widthIn(max = Metrics.maxContentWidth).fillMaxWidth().testTag("max.measure.dock"),
    ) {
        Column(Modifier.padding(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (device.state.isConnected) {
                val review = reviewTitle(attempts)
                AdaptiveActionRow(listOf(listOf(tr("Tare"), tr("Wake")), listOf(review))) { index, cell ->
                    if (index == 0) {
                        GaugeZeroButton(
                            canTare = !session.isPulling && !session.committed,
                            modifier = cell.testTag("max.measure.tare"),
                            liveTitle = tr("Tare"),
                            disabledReason = tr("Let go of the edge first."),
                        )
                    } else {
                        ReviewButton(attempts, session, cell, onReview)
                    }
                }
            } else {
                // A broadcast scan can stay Searching while the scale is silent; always leave a
                // manual way back, or restarting the app is the only escape.
                val canCancelScan = device.gaugeCapabilities.isBroadcast &&
                    device.state == ProgressorConnectionState.Scanning
                PrimaryButton(
                    title = when {
                        canCancelScan -> tr("Cancel")
                        device.state.isBusy -> device.state.label
                        else -> tr("Connect gauge")
                    },
                    icon = if (canCancelScan) Icons.Filled.Close else Icons.Outlined.SettingsInputAntenna,
                    tint = palette.bleu,
                    enabled = canCancelScan || !device.state.isBusy,
                    modifier = Modifier.fillMaxWidth().testTag("max.measure.connect"),
                ) { if (canCancelScan) device.disconnect() else device.connect() }
                if (attempts > 0) {
                    // Logged pulls are local data: a lost link cannot hide their save.
                    ReviewButton(attempts, session, Modifier.fillMaxWidth(), onReview)
                } else {
                    TextButton(
                        onClick = { device.useMockDevice(!device.isMock) },
                        modifier = Modifier.fillMaxWidth().heightIn(min = Metrics.controlMinHeight),
                    ) {
                        Text(if (device.isMock) tr("Leave demo mode") else tr("Try demo mode"),
                            style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold,
                            color = palette.inkSecondary)
                    }
                }
            }
        }
    }
}

@Composable
private fun reviewTitle(attempts: Int): String =
    if (attempts == 0) tr("Save") else tr("Review %d pulls", attempts)

@Composable
private fun ReviewButton(attempts: Int, session: LiveMaxSession, modifier: Modifier, onReview: () -> Unit) {
    val palette = LocalGripPalette.current
    PrimaryButton(
        title = reviewTitle(attempts),
        icon = Icons.Filled.Check,
        tint = palette.bleu,
        enabled = attempts > 0 && !session.committed,
        modifier = modifier.testTag("max.measure.save"),
        onClick = onReview,
    )
}

// MARK: - Previews

@Preview(name = "Max visit", showBackground = true, widthDp = 400, heightDp = 860)
@Composable
private fun MaxMeasurePreview() {
    // A real `DeviceStore` over the MOCK client (no Context, no radio until `connect()`), so the
    // preview shows the DISCONNECTED face — the one worth checking by eye.
    val scope = rememberCoroutineScope()
    val device = remember(scope) {
        DeviceStore(client = MockProgressorClient(scope, MockForceProfile.clean), isMock = true, scope = scope)
    }
    GetAGripTheme {
        CompositionLocalProvider(LocalDeviceStore provides device) {
            MaxMeasureScreen(grip = GripSpec(), onSave = { null }, onClose = {}, savedMax = { null })
        }
    }
}
