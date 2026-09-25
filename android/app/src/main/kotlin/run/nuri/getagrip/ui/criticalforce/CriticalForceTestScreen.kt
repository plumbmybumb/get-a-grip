// SPDX-License-Identifier: MPL-2.0
// Original contributions Copyright 2026 Nuri Bruner.

package run.nuri.getagrip.ui.criticalforce

import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.filled.UnfoldMore
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.TextButton
import androidx.compose.runtime.key
import run.nuri.getagrip.ui.components.BodyWeightField
import androidx.compose.runtime.remember
import run.nuri.getagrip.data.initial
import run.nuri.getagrip.ui.theme.armedText
import android.content.pm.ActivityInfo
import androidx.activity.compose.BackHandler
import androidx.activity.compose.LocalActivity
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.outlined.SettingsInputAntenna
import androidx.compose.material.icons.outlined.Timer
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.launch
import run.nuri.getagrip.ble.MockForceProfile
import run.nuri.getagrip.ble.StreamStartCause
import run.nuri.getagrip.ble.StreamStopCause
import run.nuri.getagrip.data.CriticalForceRecordEntity
import run.nuri.getagrip.engine.BackgroundPausePolicy
import run.nuri.getagrip.engine.CriticalForceFailure
import run.nuri.getagrip.engine.CriticalForceOutcome
import run.nuri.getagrip.engine.CriticalForceResult
import run.nuri.getagrip.engine.CriticalForceRules
import run.nuri.getagrip.engine.CriticalForceTest
import run.nuri.getagrip.engine.GripSpec
import run.nuri.getagrip.engine.L10n
import run.nuri.getagrip.engine.MaxAttempt
import run.nuri.getagrip.engine.MaxSource
import run.nuri.getagrip.engine.MaxTable
import run.nuri.getagrip.engine.Side
import run.nuri.getagrip.runner.KeepScreenOn
import run.nuri.getagrip.store.DeviceStore
import run.nuri.getagrip.store.LocalDeviceStore
import run.nuri.getagrip.store.LocalSettingsStore
import run.nuri.getagrip.store.LocalTemplateStore
import run.nuri.getagrip.store.TemplateStore
import run.nuri.getagrip.ui.components.AdaptiveActionRow
import run.nuri.getagrip.ui.components.CapsLabel
import run.nuri.getagrip.ui.components.FingerGlyph
import run.nuri.getagrip.ui.components.FingerPips
import run.nuri.getagrip.ui.components.ForceTraceView
import run.nuri.getagrip.ui.components.HoldToStopTestButton
import run.nuri.getagrip.ui.components.IntValueRow
import run.nuri.getagrip.ui.components.PositionChipRow
import run.nuri.getagrip.ui.components.PrimaryButton
import run.nuri.getagrip.ui.components.SecondaryButton
import run.nuri.getagrip.ui.components.ValueRow
import run.nuri.getagrip.ui.l10n.tr
import run.nuri.getagrip.ui.maxes.GaugeZeroButton
import run.nuri.getagrip.ui.maxes.relative
import run.nuri.getagrip.ui.theme.GripPalette
import run.nuri.getagrip.ui.theme.InstrumentSurface
import run.nuri.getagrip.ui.theme.LocalGripPalette
import run.nuri.getagrip.ui.theme.Metrics
import run.nuri.getagrip.ui.theme.readablePageWidth
import run.nuri.getagrip.ui.units.WeightUnits

/// The critical force test, start to saved. Presented from the root like the max test: the
/// phone is on a bench and both hands are on the edge.
///
/// Four screens in one place. SETUP (grip, hand, body weight once), the TEST (one countdown,
/// the trace, the plateau forming), the RESULT, and the words for a test that ended without
/// one. The test never pauses; see `CriticalForceTest`.
///
/// TRANSLATION NOTE (from Sources/UI/CriticalForce/CriticalForceTestView.swift): iOS puts the
/// test on the runner's Liquid Glass stack. This port has no glass; the TEST screen is laid
/// out the way the Android runner (`RunnerLive`) translated that stack — the word, force and
/// clock side by side at equal weight, the count, the trace in a card that takes the
/// slack, the controls at the foot — with the 24 pulls in their own capsule above them.
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CriticalForceTestScreen(request: CriticalForceTestRequest, onClose: () -> Unit) {
    val device = LocalDeviceStore.current
    val templates = LocalTemplateStore.current
    val settings = LocalSettingsStore.current
    val haptics = LocalHapticFeedback.current
    val scope = rememberCoroutineScope()
    val session = request.session
    val activity = LocalActivity.current

    KeepScreenOn(true)

    fun close() {
        request.teardown()
        onClose()
    }

    fun start() {
        if (!device.state.isConnected) return
        request.start(device)
        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
    }

    fun save() {
        if (request.isSaving || request.results.isEmpty()) return
        val saves = request.results.map { TemplateStore.CriticalForceSave(it.side, it.result, it.trace) }
        val offers = maxOffers(request, templates.maxTable)
        request.isSaving = true
        request.saveFailed = false
        scope.launch {
            val saved = try {
                templates.recordCriticalForces(saves, request.grip, settings.bodyWeightKg,
                    if (request.alsoSaveMaxes) offers else emptyList())
            } finally {
                request.isSaving = false
            }
            if (saved == null) {
                request.saveFailed = true
            } else {
                haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                close()
            }
        }
    }

    // The test ends itself on the clock; the visit follows each hand's phase.
    LaunchedEffect(session, session.phase) { request.phaseChanged(session.phase) }

    // A dropped gauge interrupts: void before pull 16, the end of that hand's test after it.
    LaunchedEffect(device.state.isConnected) {
        // Between hands nothing is measuring, so a drop voids nothing.
        if (!device.state.isConnected && request.stage == CriticalForceStage.Testing && !request.awaitingNextHand) {
            request.session.interrupt(CriticalForceTest.VoidReason.lostGauge)
        }
    }

    // The runner's own background rule: a connected gauge that keeps streaming in the
    // background keeps the test running (the cues still sound, and the session's foreground
    // service keeps the process alive). Only when the readings are about to stop does the
    // test end.
    val owner = LocalLifecycleOwner.current
    DisposableEffect(owner, request) {
        val observer = LifecycleEventObserver { _, event ->
            // Between hands nothing is measuring, so leaving the app costs nothing.
            if (request.stage != CriticalForceStage.Testing || request.awaitingNextHand) return@LifecycleEventObserver
            // A recreation is not the climber leaving: the request outlives it.
            if (activity?.isChangingConfigurations == true) return@LifecycleEventObserver
            when (event) {
                // Bin what the radio buffered while away: it cannot be DRAWN. The test's own
                // readings are untouched; see `DeviceStore.dropStaleTrace`.
                Lifecycle.Event.ON_RESUME -> device.dropStaleTrace()
                Lifecycle.Event.ON_PAUSE, Lifecycle.Event.ON_STOP -> {
                    if (BackgroundPausePolicy.pausesOnLeavingForeground(
                            isBackground = event == Lifecycle.Event.ON_STOP,
                            isConnected = device.state.isConnected,
                            sustainsBackgroundStreaming = device.gaugeCapabilities.sustainsBackgroundStreaming,
                        )) {
                        request.session.interrupt(CriticalForceTest.VoidReason.leftApp)
                    }
                }
                else -> Unit
            }
        }
        owner.lifecycle.addObserver(observer)
        onDispose { owner.lifecycle.removeObserver(observer) }
    }

    // Portrait while a test runs, as the runner: a rotation mid-pull is a frame of
    // re-layout nobody asked for.
    val testing = request.stage == CriticalForceStage.Testing
    DisposableEffect(activity, testing) {
        if (activity == null || !testing) return@DisposableEffect onDispose { }
        val previous = activity.requestedOrientation
        activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        onDispose {
            if (!activity.isChangingConfigurations) activity.requestedOrientation = previous
        }
    }

    // System back: free in setup and on the words; while armed on the first hand it steps
    // back to setup. Mid-test the hold is the only way out, and a result is kept or
    // discarded by its own two buttons, never by a reflex gesture.
    BackHandler {
        when (request.stage) {
            CriticalForceStage.Setup, is CriticalForceStage.Ended -> close()
            CriticalForceStage.Testing ->
                if (session.phase == CriticalForceTest.Phase.Armed && request.handIndex == 0) request.backToSetup()
            CriticalForceStage.Result -> Unit
        }
    }

    when (val stage = request.stage) {
        CriticalForceStage.Testing -> TestingScreen(request)
        CriticalForceStage.Result -> ResultScreen(request, onDiscard = ::close, onSave = ::save)
        CriticalForceStage.Setup, is CriticalForceStage.Ended ->
            FormScreen(request, ended = (stage as? CriticalForceStage.Ended)?.message, onClose = ::close, onStart = ::start)
    }
}

/// Hands whose hardest pull beats that hand's own max on file (or that have none).
internal fun maxOffers(request: CriticalForceTestRequest, table: MaxTable): List<TemplateStore.MaxSave> =
    request.results.mapNotNull { hand ->
        if (beatsMax(hand.result, request.grip, hand.side, table)) {
            TemplateStore.MaxSave(request.grip, hand.side, hand.result.peakKg, MaxSource.measured)
        } else null
    }

/// The exact hand's max, not the both-hands fallback: a one-handed test beating a
/// two-handed max says nothing.
internal fun beatsMax(result: CriticalForceResult, grip: GripSpec, side: Side, table: MaxTable): Boolean {
    val exact = table.exact(grip.key, side) ?: return result.peakKg >= MaxAttempt.releaseKg
    return result.peakKg > exact
}

/// One tint ladder for the test, the runner's: amber while it waits on you, bleu while a
/// pull's clock runs, steel at rest.
internal fun criticalForceTint(phase: CriticalForceTest.Phase, palette: GripPalette): Color = when (phase) {
    CriticalForceTest.Phase.Armed -> palette.armed
    is CriticalForceTest.Phase.Pulling -> palette.bleu
    else -> palette.calm
}

// MARK: - Setup and the words

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FormScreen(request: CriticalForceTestRequest, ended: String?, onClose: () -> Unit, onStart: () -> Unit) {
    val device = LocalDeviceStore.current
    val palette = LocalGripPalette.current
    var showingAbout by remember { mutableStateOf(false) }
    Scaffold(
        containerColor = Color.Transparent,
        topBar = {
            TopAppBar(
                title = { Text(tr("Critical force"), style = MaterialTheme.typography.titleMedium) },
                navigationIcon = {
                    if (ended == null) {
                        IconButton(onClick = onClose, modifier = Modifier.testTag("cf.cancel")) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = tr("Cancel"))
                        }
                    }
                },
                actions = {
                    if (ended == null) {
                        IconButton(onClick = { showingAbout = true }, modifier = Modifier.testTag("cf.about")) {
                            Icon(Icons.Outlined.Info, contentDescription = tr("About the test"), tint = palette.graphite)
                        }
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
        bottomBar = {
            Box(Modifier.fillMaxWidth().windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom + WindowInsetsSides.Horizontal))
                .readablePageWidth().padding(horizontal = Metrics.hPadding).padding(bottom = 8.dp)) {
                Dock {
                    if (ended != null) {
                        PrimaryButton(tr("Close"), modifier = Modifier.fillMaxWidth().testTag("cf.close")) { onClose() }
                    } else if (!device.state.isConnected) {
                        Text(tr("Connect your gauge to test."), style = MaterialTheme.typography.bodySmall,
                            fontWeight = FontWeight.Medium, color = palette.inkSecondary,
                            textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().padding(top = 4.dp))
                        PrimaryButton(
                            title = if (device.state.isBusy) device.state.label else tr("Connect"),
                            icon = Icons.Outlined.SettingsInputAntenna, tint = palette.bleu,
                            enabled = !device.state.isBusy,
                            modifier = Modifier.fillMaxWidth().testTag("cf.connect"),
                        ) { device.connect() }
                    } else {
                        // The max test's pair of actions, so the measurement screens share one shape.
                        TareAndStart(tr("Start"), "cf.start", onStart)
                    }
                }
            }
        },
    ) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).readablePageWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = Metrics.hPadding).padding(top = 12.dp, bottom = Metrics.spacing),
            verticalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            if (ended != null) Ended(ended) else Setup(request)
        }
    }
    if (showingAbout) CriticalForceAboutSheet { showingAbout = false }
}

@Composable
private fun Ended(message: String) {
    val palette = LocalGripPalette.current
    Column(Modifier.fillMaxWidth().padding(top = 60.dp), horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(18.dp)) {
        Icon(Icons.Outlined.Timer, contentDescription = null, tint = palette.inkTertiary,
            modifier = Modifier.height(40.dp).widthIn(min = 40.dp))
        Text(message, style = MaterialTheme.typography.bodyLarge, color = palette.inkPrimary,
            textAlign = TextAlign.Center, modifier = Modifier.testTag("cf.ended"))
    }
}

/// **Glanceable, not a manual** (Nuri: "look at all that text"). The protocol is one line,
/// the instruction one more; everything else is behind the ⓘ (`CriticalForceAboutSheet`).
@Composable
private fun Setup(request: CriticalForceTestRequest) {
    val palette = LocalGripPalette.current
    val templates = LocalTemplateStore.current
    val settings = LocalSettingsStore.current
    val haptics = LocalHapticFeedback.current

    Column(Modifier.fillMaxWidth().padding(bottom = 4.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(tr("24 pulls · 7 s on · 3 s off"), style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.SemiBold, color = palette.inkPrimary)
        Text(tr("Pull as hard as you can. Let go at the bell."), style = MaterialTheme.typography.bodyMedium,
            color = palette.inkSecondary)
    }

    // The grip: a value, built here, never picked from a library.
    Surface(
        onClick = { request.editingGrip = !request.editingGrip },
        shape = RoundedCornerShape(Metrics.radiusInner),
        color = palette.inkPrimary.copy(alpha = 0.05f),
        modifier = Modifier.fillMaxWidth().heightIn(min = 60.dp).testTag("cf.grip"),
    ) {
        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            FingerGlyph(request.grip.fingers, position = request.grip.position, dot = 8.dp, gap = 3.dp,
                modifier = Modifier.clearAndSetSemantics {})
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                CapsLabel(tr("GRIP"))
                Text(request.grip.displayName, style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold, color = palette.inkPrimary)
            }
            Text(if (request.editingGrip) tr("Done") else tr("Change"), style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold, color = palette.graphite)
        }
    }
    if (request.editingGrip) {
        Column(verticalArrangement = Arrangement.spacedBy(18.dp)) {
            // Grips already tested come first, then the ones your routines train.
            val seen = HashSet<String>()
            val choices = (templates.criticalForceRecords.reversed().map { it.grip } + templates.recentGrips)
                .filter { seen.add(it.key) }
            if (choices.isNotEmpty()) {
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    choices.forEach { candidate ->
                        val selected = candidate.key == request.grip.key
                        FilterChip(
                            selected = selected,
                            onClick = {
                                if (!selected) {
                                    request.grip = candidate
                                    haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                }
                            },
                            label = {
                                Row(verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    modifier = Modifier.padding(vertical = 8.dp)) {
                                    FingerGlyph(candidate.fingers, position = candidate.position, dot = 5.5.dp, gap = 2.dp)
                                    Text(candidate.shortName)
                                }
                            },
                            modifier = Modifier.semantics {
                                contentDescription = candidate.spoken
                                this.selected = selected
                            },
                        )
                    }
                }
            }
            IntValueRow(title = tr("Edge"), value = request.grip.edgeMM, range = 4..45, unit = tr("mm"),
                limit = GripSpec.edgeRange, presets = listOf(6, 10, 20, 30)) {
                request.grip = request.grip.withEdgeMM(it)
            }
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                CapsLabel(tr("FINGERS"))
                FingerPips(request.grip.fingers, request.grip.position) { request.grip = request.grip.withFingers(it) }
            }
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                CapsLabel(tr("POSITION"))
                PositionChipRow(request.grip.position) { request.grip = request.grip.withPosition(it) }
            }
        }
    }

    HandPicker(request)

    // Only until it is set: asked once, then it lives in Settings. Typed, never a slider.
    // Left empty, the test saves without it and asks again next time.
    if (settings.bodyWeightKg == null) {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            BodyWeightField(kilograms = settings.bodyWeightKg, onChange = { settings.setBodyWeightKg(it) })
            Text(tr("Asked once; change it in Settings."),
                style = MaterialTheme.typography.bodySmall, color = palette.inkTertiary)
        }
    }

    // One quiet line: "Last: L 17.5 · R 16.3 kg · last week".
    val lasts = request.hands.sides.mapNotNull { side ->
        val key = MaxTable.key(request.grip.key, side)
        templates.criticalForceRecords.lastOrNull { it.testKey == key }
    }
    lasts.maxByOrNull { it.recordedAt }?.let { newest: CriticalForceRecordEntity ->
        val values = if (lasts.size > 1) {
            lasts.joinToString(" · ") { "${it.side.initial} ${WeightUnits.number(it.criticalForceKg)}" } +
                " ${WeightUnits.symbol}"
        } else WeightUnits.text(newest.criticalForceKg)
        Text(tr("Last: %s · %s", values, relative(newest.recordedAt)),
            style = MaterialTheme.typography.bodySmall, color = palette.inkTertiary,
            modifier = Modifier.testTag("cf.last"))
    }
}

/// The routine builder's words for the hands, so "both" means one thing everywhere: both
/// hands pulling together. One at a time is 24 pulls on one hand, then 24 on the other.
/// Which hand goes first (or which hand) is a quiet menu in the label's row rather than a
/// second segmented control; the explainer rides the picker's accessibility description.
@Composable
private fun HandPicker(request: CriticalForceTestRequest) {
    val palette = LocalGripPalette.current
    val choice = request.handChoice
    val explainer = when (choice) {
        CriticalForceHandChoice.oneAtATime ->
            tr("All 24 pulls on one hand, then all 24 on the other. About 8 minutes, and each hand gets its own number.")
        CriticalForceHandChoice.bothHands ->
            tr("Both hands pulling together through the gauge, on a hangboard or a two-handed block. One number.")
        CriticalForceHandChoice.single -> tr("Just one hand, for when only one needs testing.")
    }
    fun sideTitle(side: Side): String = if (choice == CriticalForceHandChoice.oneAtATime) {
        if (side == Side.right) L10n.tr("Right first") else L10n.tr("Left first")
    } else side.displayName

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            CapsLabel(tr("HANDS"), Modifier.weight(1f))
            if (choice != CriticalForceHandChoice.bothHands) {
                var open by remember { mutableStateOf(false) }
                Box {
                    TextButton(onClick = { open = true }, modifier = Modifier.heightIn(min = 44.dp).testTag("cf.side")) {
                        Text(sideTitle(request.pickedSide), color = palette.graphite, fontWeight = FontWeight.SemiBold)
                        Icon(Icons.Filled.UnfoldMore, contentDescription = null, tint = palette.graphite,
                            modifier = Modifier.padding(start = 4.dp).size(16.dp))
                    }
                    DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                        listOf(Side.left, Side.right).forEach { side ->
                            DropdownMenuItem(
                                text = { Text(sideTitle(side)) },
                                onClick = {
                                    request.pickedSide = side
                                    open = false
                                },
                                trailingIcon = if (request.pickedSide == side) {
                                    { Icon(Icons.Filled.Check, contentDescription = null) }
                                } else null,
                            )
                        }
                    }
                }
            }
        }
        val options = listOf(
            CriticalForceHandChoice.oneAtATime to tr("One at a time"),
            CriticalForceHandChoice.bothHands to tr("Both hands"),
            CriticalForceHandChoice.single to tr("One hand"),
        )
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().testTag("cf.hands")
            .semantics { contentDescription = explainer }) {
            options.forEachIndexed { index, (option, label) ->
                SegmentedButton(
                    selected = choice == option,
                    onClick = { request.handChoice = option },
                    shape = SegmentedButtonDefaults.itemShape(index, options.size),
                    // Graphite selection, as the tab bar: Material's lavender is a hue the
                    // palette does not have.
                    colors = SegmentedButtonDefaults.colors(
                        activeContainerColor = palette.graphite.copy(alpha = 0.12f),
                        activeContentColor = palette.inkPrimary,
                        activeBorderColor = palette.inkTertiary.copy(alpha = 0.5f),
                        inactiveContainerColor = Color.Transparent,
                        inactiveContentColor = palette.inkSecondary,
                        inactiveBorderColor = palette.inkTertiary.copy(alpha = 0.5f),
                    ),
                    icon = {},
                ) { Text(label, maxLines = 1) }
            }
        }
    }
}

// MARK: - The test

/// **The test is the runner's screen**: the word for what to do, then live force and the
/// clock side by side at equal weight, exactly the runner's hero, then where you are in the
/// 24; the trace takes the slack; the pulls ride in their own capsule; the one control sits
/// at the foot.
@Composable
internal fun TestingScreen(request: CriticalForceTestRequest) {
    val palette = LocalGripPalette.current
    val device = LocalDeviceStore.current
    val session = request.session
    val tint = criticalForceTint(session.phase, palette)
    val scrollsForLargeText = LocalDensity.current.fontScale >= 1.5f
    Box(Modifier.fillMaxSize()) {
        // The phase wash, hanging from the top: the screen's colour says the phase before any
        // word is read. Static per phase, so nothing animates behind a live graph.
        Box(Modifier.fillMaxWidth().height(260.dp)
            .background(Brush.verticalGradient(listOf(tint.copy(alpha = 0.14f), Color.Transparent))))
        Column(
            Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.safeDrawing)
                .readablePageWidth()
                .padding(horizontal = Metrics.hPadding)
                .padding(top = 12.dp, bottom = 12.dp)
                .then(if (scrollsForLargeText) Modifier.verticalScroll(rememberScrollState()) else Modifier),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Panel(request, tint)
            Surface(
                shape = RoundedCornerShape(Metrics.radiusCard),
                color = palette.card,
                modifier = Modifier
                    .widthIn(max = Metrics.maxContentWidth)
                    .fillMaxWidth()
                    .then(if (scrollsForLargeText) Modifier.height(220.dp) else Modifier.weight(1f))
                    .clearAndSetSemantics {},
            ) {
                ForceTraceView(
                    modifier = Modifier.fillMaxSize().padding(horizontal = 4.dp),
                    thresholdKg = if (session.phase == CriticalForceTest.Phase.Armed) CriticalForceRules.startKg else null,
                    tint = tint,
                )
            }
            // A LEAF that reads the session itself: the live bar moves several times a second,
            // and read here it recomposed the whole screen with it. Between hands the pill is
            // the NEXT hand's: empty.
            LivePlateau(request)
            Dock(Modifier.testTag("cf.dock")) {
                if (request.awaitingNextHand) {
                    val first = request.hands.sides.first()
                    Text(tr("%s hand done. Get set on your %s hand, then start.",
                            first.displayName, request.side.displayName.lowercase()),
                        style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Medium,
                        color = palette.inkSecondary, textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth().padding(top = 6.dp))
                    TareAndStart(tr("Start %s hand", request.side.displayName.lowercase()), "cf.startNextHand") {
                        request.startNextHand(device)
                    }
                    SecondaryButton(tr("Finish with %s hand only", first.displayName.lowercase()),
                        modifier = Modifier.fillMaxWidth().testTag("cf.finishFirstHand")) {
                        request.finishEarlyBetweenHands()
                    }
                } else if (session.phase == CriticalForceTest.Phase.Armed) {
                    Text(tr("The test starts the moment you pull. Pull as hard as you can."),
                        style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Medium,
                        color = palette.inkSecondary, textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth().padding(top = 6.dp))
                    SecondaryButton(tr("Back"), icon = Icons.AutoMirrored.Filled.ArrowBack,
                        modifier = Modifier.fillMaxWidth().testTag("cf.back")) { request.backToSetup() }
                } else {
                    HoldToStopTestButton(canKeep = session.canFinishEarly, modifier = Modifier.testTag("cf.stop")) {
                        session.stop()
                    }
                }
            }
        }
    }
}

private val HERO_SIZE = 76.sp
private val UNIT_SIZE = 22.sp
private val heroAutoSize = TextAutoSize.StepBased(minFontSize = 40.sp, maxFontSize = HERO_SIZE)

private fun heroStyle(tint: Color) = TextStyle(
    fontSize = HERO_SIZE,
    fontWeight = FontWeight.Thin,
    fontFeatureSettings = "tnum",
    letterSpacing = (-0.02).em,
    color = tint,
)

/// The information panel: the grip, the word, the runner's hero (force and clock side by
/// side, equal weight), then where you are in the 24. The clock reads the session's
/// once-a-second values; the kilograms live in their own leaf.
@Composable
private fun Panel(request: CriticalForceTestRequest, tint: Color) {
    val palette = LocalGripPalette.current
    val session = request.session
    // Between hands, the panel names the next hand instead of the finished test's DONE.
    val nextHand = request.awaitingNextHand
    val word = if (nextHand) {
        if (request.side == Side.right) tr("RIGHT HAND NEXT") else tr("LEFT HAND NEXT")
    } else when (session.phase) {
        CriticalForceTest.Phase.Armed -> tr("PULL TO START")
        is CriticalForceTest.Phase.Pulling -> tr("PULL")
        is CriticalForceTest.Phase.Resting -> tr("REST")
        CriticalForceTest.Phase.Settling, CriticalForceTest.Phase.Finished -> tr("DONE")
        is CriticalForceTest.Phase.Voided -> tr("STOPPED")
    }
    val seconds = if (nextHand) null else when (session.phase) {
        is CriticalForceTest.Phase.Pulling, is CriticalForceTest.Phase.Resting -> session.secondsLeft
        else -> null
    }
    // Before the first pull the clock shows the full pull it is waiting to start; once the
    // last bell has rung, nothing is left.
    val clock = when {
        seconds != null -> "$seconds"
        nextHand || session.phase == CriticalForceTest.Phase.Armed -> "${session.proto.workSeconds.toInt()}"
        else -> "0"
    }
    val countLine = tr("Pull %d of %d", if (nextHand) 1 else minOf(session.pullNumber, session.proto.reps),
        session.proto.reps)
    val grip = request.grip
    val spoken = if (seconds != null) L10n.tr("%s, %d seconds. %s", word, seconds, countLine)
    else L10n.tr("%s. %s", word, countLine)

    InstrumentSurface(
        modifier = Modifier.widthIn(max = Metrics.maxContentWidth).fillMaxWidth()
            .testTag("cf.panel")
            .semantics(mergeDescendants = true) { contentDescription = spoken },
    ) {
        Column(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(top = 14.dp, bottom = 12.dp)
                .clearAndSetSemantics {},
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            CapsLabel(if (request.side == Side.both) grip.shortName else "${grip.shortName} · ${request.side.displayName}")
            BasicText(
                word,
                style = MaterialTheme.typography.displaySmall.copy(fontWeight = FontWeight.ExtraBold, color = tint,
                    textAlign = TextAlign.Center),
                maxLines = 1,
                autoSize = TextAutoSize.StepBased(minFontSize = 20.sp, maxFontSize = 34.sp),
                modifier = Modifier.fillMaxWidth(),
            )
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(18.dp, Alignment.CenterHorizontally),
                verticalAlignment = Alignment.Bottom,
            ) {
                LiveForce(if (tint == palette.bleu) palette.bleu else palette.inkPrimary, Modifier.weight(1f))
                Row(Modifier.weight(1f), verticalAlignment = Alignment.Bottom,
                    horizontalArrangement = Arrangement.spacedBy(4.dp, Alignment.CenterHorizontally)) {
                    BasicText(clock, style = heroStyle(palette.inkPrimary), maxLines = 1, autoSize = heroAutoSize,
                        modifier = Modifier.weight(1f, fill = false))
                    Text(tr("s"), style = TextStyle(fontSize = UNIT_SIZE), color = palette.inkTertiary,
                        modifier = Modifier.padding(bottom = 10.dp))
                }
            }
            CapsLabel(countLine)
        }
    }
}

/// The live kilograms, in their OWN composable: `currentKg` changes per sample, and read
/// from the panel it would recompose everything 80×/s to move one number. Measurements
/// SNAP: no transition on a figure changing ten times a second.
@Composable
private fun LiveForce(tint: Color, modifier: Modifier) {
    val device = LocalDeviceStore.current
    val palette = LocalGripPalette.current
    Row(modifier, verticalAlignment = Alignment.Bottom,
        horizontalArrangement = Arrangement.spacedBy(4.dp, Alignment.CenterHorizontally)) {
        BasicText(WeightUnits.number(device.currentKg), style = heroStyle(tint), maxLines = 1,
            autoSize = heroAutoSize, modifier = Modifier.weight(1f, fill = false))
        Text(WeightUnits.symbol, style = TextStyle(fontSize = UNIT_SIZE), color = palette.inkTertiary,
            modifier = Modifier.padding(bottom = 10.dp))
    }
}

/// **The 24 pulls, in a capsule of their own.** One column per pull, filling in as each
/// window closes, so you watch the plateau form; the pull in progress is outlined and
/// drawn live. Squared columns, a CHART, so they never read as the capsule fingers.
@Composable
private fun LivePlateau(request: CriticalForceTestRequest) {
    val session = request.session
    val waiting = request.awaitingNextHand
    Plateau(
        means = if (waiting) emptyList() else session.repMeans,
        total = session.proto.reps,
        current = if (!waiting && session.phase.isRunning) session.pullNumber else null,
    )
}

/// Tare (or Wake) beside a Start, as the max test's dock pairs them.
@Composable
private fun TareAndStart(title: String, tag: String, onStart: () -> Unit) {
    val device = LocalDeviceStore.current
    val palette = LocalGripPalette.current
    AdaptiveActionRow(listOf(listOf(tr("Zero the gauge"), tr("Wake")), listOf(title))) { index, cell ->
        if (index == 0) {
            GaugeZeroButton(canTare = true, modifier = cell.testTag("cf.tare"))
        } else {
            PrimaryButton(title, icon = Icons.Filled.PlayArrow, tint = palette.bleu,
                enabled = device.state.isConnected, modifier = cell.testTag(tag)) { onStart() }
        }
    }
}

@Composable
private fun Plateau(means: List<Double?>, total: Int, current: Int?) {
    val palette = LocalGripPalette.current
    val top = maxOf(1.0, means.filterNotNull().maxOrNull() ?: 1.0)
    val spoken = L10n.tr("Average per pull") + ". " + L10n.tr("%d of %d pulls done", means.size, total)
    InstrumentSurface(
        shape = CircleShape,
        modifier = Modifier.widthIn(max = Metrics.maxContentWidth).fillMaxWidth().testTag("cf.plateau")
            .semantics(mergeDescendants = true) { contentDescription = spoken },
    ) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 22.dp, vertical = 14.dp).height(36.dp)
                .clearAndSetSemantics {},
            horizontalArrangement = Arrangement.spacedBy(3.dp),
            verticalAlignment = Alignment.Bottom,
        ) {
            for (index in 0 until total) {
                val mean = means.getOrNull(index)
                val fraction = ((mean ?: (top * 0.14)) / top).toFloat().coerceIn(0f, 1f)
                Box(
                    Modifier
                        .weight(1f)
                        .height(maxOf(4.dp, 36.dp * fraction))
                        .background(
                            if (mean == null) palette.inkPrimary.copy(alpha = 0.08f) else palette.bleu.copy(alpha = 0.8f),
                            RoundedCornerShape(2.dp),
                        )
                        .then(if (current == index + 1) {
                            Modifier.border(BorderStroke(1.5.dp, palette.inkSecondary), RoundedCornerShape(2.dp))
                        } else Modifier),
                )
            }
        }
    }
}

// MARK: - The result

/// The result on the same stage: the numbers on a card, the pulls that made them, the
/// decision at the foot. With two hands the two numbers ARE the switch: tap a hand to see
/// its pulls.
@Composable
private fun ResultScreen(request: CriticalForceTestRequest, onDiscard: () -> Unit, onSave: () -> Unit) {
    val palette = LocalGripPalette.current
    val templates = LocalTemplateStore.current
    val settings = LocalSettingsStore.current
    val summaries = request.results.map { hand ->
        hand.side to CriticalForceSummary.of(hand.result, templates.maxTable.max(request.grip.key, hand.side),
            settings.bodyWeightKg)
    }
    val shown = summaries.firstOrNull { it.first == request.shownSide } ?: summaries.firstOrNull()
    val offers = maxOffers(request, templates.maxTable)
    Box(Modifier.fillMaxSize()) {
        Box(Modifier.fillMaxWidth().height(260.dp)
            .background(Brush.verticalGradient(listOf(palette.bleu.copy(alpha = 0.14f), Color.Transparent))))
        BoxWithConstraints(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing).readablePageWidth()) {
            Column(
                Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).heightIn(min = maxHeight)
                    .padding(horizontal = Metrics.hPadding).padding(top = 12.dp, bottom = 12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                InstrumentSurface(Modifier.widthIn(max = Metrics.maxContentWidth).fillMaxWidth()) {
                    Column(Modifier.padding(16.dp).testTag("cf.headline")) {
                        if (summaries.size > 1) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy(14.dp)) {
                                CapsLabel(tr("CRITICAL FORCE"))
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    summaries.forEach { (side, summary) ->
                                        CriticalForceHandColumn(side, summary, selected = side == shown?.first,
                                            modifier = Modifier.weight(1f)) { request.shownSide = side }
                                    }
                                }
                                shown?.let { CriticalForceStatsRow(it.second) }
                            }
                        } else if (shown != null) {
                            CriticalForceHeadline(shown.second)
                        }
                    }
                }
                shown?.let { (side, summary) ->
                    key(side) {
                        CriticalForcePullChart(summary, Modifier.widthIn(max = Metrics.maxContentWidth).padding(vertical = 8.dp))
                    }
                }
                Spacer(Modifier.weight(1f))
                Dock {
                    if (offers.isNotEmpty()) {
                        Row(
                            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp).testTag("cf.alsoMax")
                                .semantics(mergeDescendants = true) {},
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                Text(if (offers.size == 1) tr("Save your hardest pull as a max")
                                    else tr("Save your hardest pulls as maxes"),
                                    style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold,
                                    color = palette.inkPrimary)
                                Text(offers.joinToString("\n") { offerLine(it, templates.maxTable) },
                                    style = MaterialTheme.typography.bodySmall, color = palette.inkSecondary)
                            }
                            Switch(
                                checked = request.alsoSaveMaxes,
                                onCheckedChange = { request.alsoSaveMaxes = it },
                                colors = SwitchDefaults.colors(checkedTrackColor = palette.bleu),
                            )
                        }
                    }
                    request.notes.forEach { note ->
                        Text(note, style = MaterialTheme.typography.bodySmall, color = palette.armedText,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp).testTag("cf.note"))
                    }
                    if (request.saveFailed) {
                        Text(tr("Couldn’t save. Your result is still here — try again."),
                            style = MaterialTheme.typography.bodySmall, color = palette.alarm,
                            textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().testTag("cf.saveFailed"))
                    }
                    val discard = tr("Don’t save")
                    val save = tr("Save")
                    AdaptiveActionRow(listOf(listOf(discard), listOf(save))) { index, cell ->
                        if (index == 0) {
                            SecondaryButton(discard, modifier = cell.testTag("cf.discard"),
                                enabled = !request.isSaving) { onDiscard() }
                        } else {
                            PrimaryButton(save, icon = Icons.Filled.Check, enabled = !request.isSaving,
                                modifier = cell.testTag("cf.save")) { onSave() }
                        }
                    }
                }
            }
        }
    }
}

private fun offerLine(offer: TemplateStore.MaxSave, table: MaxTable): String {
    val hand = CriticalForceTestRequest.handName(offer.side)
    val old = table.exact(offer.grip.key, offer.side)
    return if (old != null) {
        L10n.tr("%s: %s, up from %s", hand, WeightUnits.text(offer.kg), WeightUnits.text(old))
    } else {
        L10n.tr("%s: %s, the first max on this grip", hand, WeightUnits.text(offer.kg))
    }
}

/// The dock at the foot of every stage: one surface holding the stage's one decision.
@Composable
private fun Dock(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    InstrumentSurface(modifier.widthIn(max = Metrics.maxContentWidth).fillMaxWidth()) {
        Column(Modifier.padding(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) { content() }
    }
}
