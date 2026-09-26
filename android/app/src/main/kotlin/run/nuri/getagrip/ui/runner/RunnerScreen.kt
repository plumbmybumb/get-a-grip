// SPDX-License-Identifier: MPL-2.0
// Original contributions Copyright 2026 Nuri Bruner.

package run.nuri.getagrip.ui.runner

import android.content.pm.ActivityInfo
import android.view.WindowManager
import androidx.activity.compose.BackHandler
import androidx.activity.compose.LocalActivity
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.SettingsInputAntenna
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import run.nuri.getagrip.ble.MockForceProfile
import run.nuri.getagrip.ble.MockProgressorClient
import run.nuri.getagrip.ble.StreamStartCause
import run.nuri.getagrip.engine.BackgroundPausePolicy
import run.nuri.getagrip.engine.FingerSet
import run.nuri.getagrip.engine.GripSpec
import run.nuri.getagrip.engine.L10n
import run.nuri.getagrip.engine.MaxTable
import run.nuri.getagrip.engine.RunnerEvent
import run.nuri.getagrip.engine.RunnerPhase
import run.nuri.getagrip.engine.SessionPlan
import run.nuri.getagrip.engine.SetPlan
import run.nuri.getagrip.engine.Side
import run.nuri.getagrip.runner.AndroidActivityPublisher
import run.nuri.getagrip.runner.AndroidSessionServiceController
import run.nuri.getagrip.runner.CuePlayer
import run.nuri.getagrip.runner.KeepScreenOn
import run.nuri.getagrip.runner.RunnerSession
import run.nuri.getagrip.runner.RunnerSnapshot
import run.nuri.getagrip.runner.SessionSummaryDecision
import run.nuri.getagrip.store.DeviceStore
import run.nuri.getagrip.store.LocalDeviceStore
import run.nuri.getagrip.store.RunnerControlPolicy
import run.nuri.getagrip.store.TareConfirmationDecision
import run.nuri.getagrip.store.TarePolicy
import run.nuri.getagrip.store.TareTapDecision
import run.nuri.getagrip.ui.components.CapsLabel
import run.nuri.getagrip.ui.components.AdaptiveActionRow
import run.nuri.getagrip.ui.components.FingerGlyph
import run.nuri.getagrip.ui.components.ForceTraceView
import run.nuri.getagrip.ui.components.HoldToEndButton
import run.nuri.getagrip.ui.components.cameraHandOffset
import run.nuri.getagrip.ui.components.PalmGeometry
import run.nuri.getagrip.ui.components.PalmHand
import run.nuri.getagrip.ui.components.pressFeedback
import run.nuri.getagrip.ui.gauge.calibrationNote
import run.nuri.getagrip.ui.l10n.tr
import run.nuri.getagrip.ui.theme.GetAGripTheme
import run.nuri.getagrip.ui.theme.GripPalette
import run.nuri.getagrip.ui.theme.LocalGripPalette
import run.nuri.getagrip.ui.theme.Motion
import run.nuri.getagrip.ui.theme.readablePageWidth
import run.nuri.getagrip.ui.theme.Metrics
import run.nuri.getagrip.ui.units.WeightUnits

/// The hero numeral, at the size the whole screen is arranged around. `sp`, not `dp`: a bare
/// pixel height renders identically at every accessibility setting while the controls around
/// it grow.
private val HERO_SIZE = 76.sp
private val UNIT_SIZE = 22.sp

/// **The whole runner, from a plan to a decision about what to log.**
///
/// Read at arm's length with chalk on your hands: what matters most is largest, the phase
/// is legible from its colour, and every control is hittable without looking. Timing lives
/// in `RunnerSession`; this only draws it.
///
/// Today builds the plan and max table; `onFinished` receives the session and the summary's
/// decision (where `recordSession` / `recordMax` belong); `onExit` pops. Only `DeviceStore`
/// is touched here, which keeps the screen previewable.
@Composable
fun RunnerHost(
    workout: run.nuri.getagrip.runner.ActiveWorkout,
    submissionScope: kotlinx.coroutines.CoroutineScope,
    modifier: Modifier = Modifier,
    onFinished: suspend (run.nuri.getagrip.runner.SessionOutcome, SessionSummaryDecision) -> Boolean,
    onExit: () -> Unit,
) {
    val device = LocalDeviceStore.current
    val session = workout.session
    val timerOnly = session.timerOnly
    // The ViewModel owns begin/end and the ticker. Disposing this drawing during
    // Activity recreation must not stop the gauge or discard the workout.

    // The screen you look at with both hands on an edge; the phone timing out mid-pull is
    // the app going blind.
    KeepScreenOn(true)

    // The status bar returns for the SUMMARY: a document you scroll, with no black palm under
    // the cutout for its glyphs to vanish into.
    RunnerWindowChrome(hideStatusBar = !session.isFinished)
    RunnerLifecycle(session, device, timerOnly)

    // Link changes reach the engine through the session's own watcher, not an effect
    // here: a composition stops with the Activity, and a locked screen is exactly when the
    // service-kept session most needs to hear the link drop. See `RunnerSession.begin`.

    // **System back PAUSES; it never ends.** Ending is the hold, and only the hold: a reflex
    // gesture must not destroy a workout. iOS gets this free (a cover has no back); Android's
    // back is always there, so it gets the one safe meaning mid-set. Predictive back is not
    // intercepted for an animation this screen would not honour.
    BackHandler(enabled = !session.isFinished) {
        if (!session.snapshot.phase.isPaused) session.send(RunnerEvent.Pause)
    }

    Box(
        modifier
            .fillMaxSize()
            .background(Color.Transparent),
    ) {
        if (session.isFinished) {
            // FROZEN when the session ended; recomputing would move `finishedAt` and re-derive the
            // max candidates under the rows being tapped.
            val outcome = workout.outcome()
            SessionSummaryScreen(
                outcome = outcome,
                sessionsPerDayTarget = workout.template.sessionsPerDay,
                state = workout.summary,
                submissionScope = submissionScope,
                modifier = Modifier.safeDrawingPadding(),
                onDone = { finishedOutcome, decision ->
                    val success = onFinished(finishedOutcome, decision)
                    if (success) onExit()
                    success
                },
            )
        } else {
            RunnerLive(session, timerOnly)
            // Place the fingers below a typical centred camera, without drawing a notch.
            // The root reaches the physical top edge; PalmGeometry reserves the camera gap.
            val snapshot = session.snapshot
            val grip = snapshot.grip
            if (grip != null) {
                PalmHand(
                    grip = grip,
                    newGripID = snapshot.newGripID,
                    holdsGripCueForRest = snapshot.gripChangesNext,
                    side = snapshot.side ?: Side.both,
                    // Dimmed while resting: this is what's COMING, not "pull this now".
                    isActive = !isResting(snapshot),
                    restFocus = !timerOnly && snapshot.showsRestFocus,
                    modifier = Modifier.align(Alignment.TopCenter),
                )
            }
            RunnerScreenBorder(runnerBorderCue(snapshot, timerOnly,
                device.state.isConnected && device.isStreaming && device.isSignalFresh), Modifier.matchParentSize())
        }
    }
}

/// Portrait, behind the cutout, no status bar — all three restored when the session ends.
@Composable
private fun RunnerWindowChrome(hideStatusBar: Boolean) {
    val activity = LocalActivity.current

    DisposableEffect(activity) {
        val window = activity?.window
        if (window == null) return@DisposableEffect onDispose { }
        val previousCutout = window.attributes.layoutInDisplayCutoutMode
        val previousOrientation = activity.requestedOrientation
        window.attributes = window.attributes.apply {
            layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
        }
        activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        onDispose {
            // Not while the Activity is RECREATED: the retained runner asks for portrait again, and
            // releasing the lock in between hands the new Activity a landscape frame and a second
            // recreation mid-pull.
            if (!activity.isChangingConfigurations) activity.requestedOrientation = previousOrientation
            window.attributes = window.attributes.apply {
                layoutInDisplayCutoutMode = previousCutout
            }
        }
    }

    DisposableEffect(activity, hideStatusBar) {
        val window = activity?.window
        if (window == null || !hideStatusBar) return@DisposableEffect onDispose { }
        val controller: WindowInsetsControllerCompat =
            WindowCompat.getInsetsController(window, window.decorView)
        val previousBehavior = controller.systemBarsBehavior
        // TRANSIENT by swipe, never sticky-hidden: the clock and the battery are one pull
        // away for anyone who wants them.
        controller.systemBarsBehavior =
            WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        controller.hide(WindowInsetsCompat.Type.statusBars())
        onDispose {
            controller.show(WindowInsetsCompat.Type.statusBars())
            controller.systemBarsBehavior = previousBehavior
        }
    }
}

/// Foregrounding kicks the stream; leaving the foreground pauses only when samples genuinely
/// cannot reach us. The rule itself lives in `BackgroundPausePolicy`.
@Composable
private fun RunnerLifecycle(session: RunnerSession, device: DeviceStore, timerOnly: Boolean) {
    val owner = LocalLifecycleOwner.current
    DisposableEffect(owner, session) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> {
                    // Bin whatever the radio buffered while we were away — see
                    // `DeviceStore.dropStaleTrace`.
                    device.dropStaleTrace()
                    // **AND KICK THE STREAM** (Nuri, 2026-08-10: a dot, then the stream resumed seconds later).
                    // The gauge stops sending while suspended and only the watchdog revived it, after its whole
                    // silence budget. Re-sending start to a live stream is harmless; not sending it is three
                    // dead seconds mid-rep.
                    session.startIfReady(StreamStartCause.foreground)
                }

                Lifecycle.Event.ON_PAUSE, Lifecycle.Event.ON_STOP -> {
                    val isBackground = event == Lifecycle.Event.ON_STOP
                    if (isBackground && !timerOnly) {
                        // Clear the device-time anchor BEFORE suspension, so queued old-epoch samples cannot
                        // carry a high-water mark across the foreground re-kick.
                        session.send(RunnerEvent.StreamRestarted)
                    }
                    // **`timerOnly` always pauses, whatever is connected.** A gauge-free session never
                    // streams, so nothing keeps the process alive: the ticker stops and the hold freezes
                    // with no PAUSED state to explain it.
                    //
                    // ON_STOP is "background" and ON_PAUSE "inactive", matching iOS's scenePhase: a CONNECTED
                    // session that can stream in the background survives both; one with no link pauses on either.
                    val pauses = timerOnly || BackgroundPausePolicy.pausesOnLeavingForeground(
                        isBackground = isBackground,
                        isConnected = device.state.isConnected,
                        sustainsBackgroundStreaming = device.gaugeCapabilities.sustainsBackgroundStreaming,
                    )
                    if (pauses) session.send(RunnerEvent.Pause)
                }

                else -> Unit
            }
        }
        owner.lifecycle.addObserver(observer)
        onDispose { owner.lifecycle.removeObserver(observer) }
    }
}

// MARK: - The session screen

@Composable
internal fun RunnerLive(session: RunnerSession, timerOnly: Boolean) {
    val device = LocalDeviceStore.current
    val palette = LocalGripPalette.current
    val snapshot = session.snapshot
    val tint = RunnerTint.of(snapshot, palette, timerOnly, device.state.isConnected)
    val scrollsForLargeText = LocalDensity.current.fontScale >= 1.5f
    val scrollState = rememberScrollState()

    Column(
        Modifier
            .fillMaxSize()
            // The gesture bar only. The TOP inset is NOT consumed: drawing behind the hidden status
            // bar is what lets the fingers align beneath the camera.
            .windowInsetsPadding(WindowInsets.navigationBars)
            .readablePageWidth()
            .padding(horizontal = Metrics.hPadding)
            // The longest finger grows 9.5dp during its cue. Fourteen dp clears that
            // expansion; the remaining space belongs to the graph, not an empty header.
            .padding(
                top = PalmGeometry.TOTAL_HEIGHT.dp + cameraHandOffset() + if (timerOnly) 22.dp else 14.dp,
                bottom = if (timerOnly) Metrics.spacing else 12.dp,
            )
            .then(if (scrollsForLargeText) Modifier.verticalScroll(scrollState) else Modifier),
        verticalArrangement = Arrangement.spacedBy(if (timerOnly) 12.dp else 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        if (timerOnly) {
            GripNameRow(snapshot, palette, timerOnly = true)
            GripChangeNotice(snapshot, palette)
            TimerDial(session, snapshot, tint, palette)
            Counters(snapshot)
        } else {
            // The hand owns the top band, so only the grip's NAME goes here (the glyph would be the
            // same picture twice); the counters move DOWN above the graph — checked between pulls.
            RunnerPanelHeader(session, snapshot, tint, timerOnly, device.state.isConnected)
            Surface(
                shape = RoundedCornerShape(Metrics.radiusCard),
                color = palette.card,
                modifier = Modifier
                    .widthIn(max = Metrics.maxContentWidth)
                    .fillMaxWidth()
                    .then(if (scrollsForLargeText) Modifier.height(220.dp) else Modifier.weight(1f)),
            ) {
                Box(Modifier.testTag("runner-plot"), contentAlignment = Alignment.Center) {
                    ForceTraceView(
                        modifier = Modifier.fillMaxSize().padding(horizontal = 4.dp),
                        thresholdKg = session.plan.thresholdKg,
                        // Only while the rep is live: a lane during the rest asks for a load you are not holding.
                        targetBand = if (isWorking(snapshot) || isArmed(snapshot)) {
                            snapshot.targetBand
                        } else {
                            null
                        },
                        tint = tint,
                    )
                    // A connected gauge that is not sending renders "0.0 kg" as a lie: a device measuring
                    // nothing and an app receiving nothing look identical. Say it, and say what to do.
                    // Focus replaces the prompt that normally reports a lost link. hasSignal means a sample
                    // arrived at least once, not that the gauge is still sending, so keep the warning visible.
                    if (!snapshot.hasSignal || (snapshot.showsRestFocus &&
                            (snapshot.linkIsDown || !device.state.isConnected || !device.isSignalFresh))) {
                        NoSignalNotice(device)
                    }
                    GraphGripChangeCue(snapshot, palette, Modifier.matchParentSize(),
                        showBanner = !snapshot.showsRestFocus)
                }
            }
        }
        Controls(session, snapshot, timerOnly)
    }
}

/// The grip, and — during a rest — the fact that it is the one COMING UP. The snapshot looks
/// forward while resting (`SessionRunner.displaySlot`); "Next" makes that change legible.
@Composable
internal fun GripNameRow(snapshot: RunnerSnapshot, palette: GripPalette, timerOnly: Boolean) {
    val grip = snapshot.grip ?: return
    val resting = isResting(snapshot)
    val fontScale = LocalDensity.current.fontScale
    // Reserve two text lines at accessibility sizes, even when this particular grip's
    // short name fits one. A rest badge or grip change must never shift the live metrics.
    val rowHeight = ((if (fontScale >= 1.3f) 44 else 28) * fontScale).dp
    Row(
        Modifier.fillMaxWidth()
            .heightIn(min = rowHeight)
            .semantics(mergeDescendants = true) {
            contentDescription = spokenGrip(snapshot, grip, timerOnly)
        },
        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (resting && snapshot.newGripID == null) RestBadge(snapshot.gripChangesNext, palette)
        Text(
            // The full name fits unless a badge or target chip shares the row.
            if (!timerOnly && snapshot.upcomingGrip != null) {
                "${grip.shortName} → ${snapshot.upcomingGrip.shortName}"
            } else if (resting || snapshot.targetBand != null) grip.shortName else grip.line,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Medium,
            color = palette.inkSecondary,
            maxLines = 2,
            modifier = Modifier.weight(1f, fill = false),
        )
        val band = snapshot.targetBand
        if (band != null) LiveTargetChip(band, isWorking(snapshot), timerOnly, palette)
        if (timerOnly) {
            // Gauge-free is a legitimate protocol: its mode sits beside the target, not as an apology.
            CapsLabel(
                tr("Timing only"),
                Modifier
                    .background(palette.inkTertiary.copy(alpha = 0.12f), CircleShape)
                    .padding(horizontal = 7.dp, vertical = 3.dp),
            )
        }
    }
}

/** Reserved throughout the workout so a change never shifts or covers the metrics. */
@Composable
internal fun GripChangeNotice(snapshot: RunnerSnapshot, palette: GripPalette) {
    val active = snapshot.newGripID != null || snapshot.upcomingGrip != null
    val density = androidx.compose.ui.platform.LocalDensity.current
    Box(Modifier.fillMaxWidth()
        .height((38 * density.fontScale).dp)
        .background(if (active) palette.armed.copy(alpha = 0.12f) else Color.Transparent,
            RoundedCornerShape(Metrics.radiusInner)), contentAlignment = Alignment.Center) {
        val next = snapshot.upcomingGrip
        val grip = snapshot.grip
        if (snapshot.newGripID != null && grip != null) {
            Column(Modifier.semantics(mergeDescendants = true) {
                contentDescription = L10n.tr("New grip: %s", grip.spoken) +
                    (next?.let { ". " + L10n.tr("Next grip: %s", it.spoken) } ?: "")
                liveRegion = androidx.compose.ui.semantics.LiveRegionMode.Polite
            }, horizontalAlignment = Alignment.CenterHorizontally) {
                Row(verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    RestBadge(true, palette)
                    Text(if (next == null) grip.line else grip.shortName,
                        style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.SemiBold,
                        color = palette.inkPrimary, maxLines = if (next == null) 2 else 1,
                        modifier = Modifier.weight(1f, fill = false))
                }
                if (next != null) Text(tr("Next grip: %s", next.shortName),
                    style = MaterialTheme.typography.labelSmall, color = palette.inkSecondary, maxLines = 1)
            }
        } else if (next != null) {
            Text(tr("Next grip: %s", next.line), style = MaterialTheme.typography.bodySmall,
                fontWeight = FontWeight.Medium, color = palette.inkPrimary, maxLines = 2,
                modifier = Modifier.semantics { liveRegion = androidx.compose.ui.semantics.LiveRegionMode.Polite })
        }
    }
}

/// The rest screen's change of tense — and, when the next pull is on a different grip, the
/// whole cue that it is (easy to miss while you shake out).
///
/// Amber is "waiting on you"; red stays reserved for attention. The WORD changes with the
/// colour, so the cue survives greyscale. A SOLID capsule with fixed dark ink, because amber
/// ink on the light field measures 1.72:1 against a 4.5:1 floor; fixed literals keep the
/// ratio stable in both schemes.
@Composable
private fun RestBadge(changing: Boolean, palette: GripPalette) {
    CapsLabel(
        if (changing) tr("New grip") else tr("Next"),
        Modifier
            .background(
                if (changing) palette.armed else palette.inkTertiary.copy(alpha = 0.16f),
                CircleShape,
            )
            .padding(horizontal = 7.dp, vertical = 3.dp),
        color = if (changing) Color(0xFF1B1F25) else palette.inkTertiary,
    )
}

/// The target's live state changes with every force sample, so the chip owns that
/// high-frequency observation instead of invalidating the runner screen around it.
@Composable
private fun LiveTargetChip(
    band: ClosedFloatingPointRange<Double>,
    isWorking: Boolean,
    timerOnly: Boolean,
    palette: GripPalette,
) {
    val device = LocalDeviceStore.current
    // In a timer-only session the gauge value is zero or stale; it must not light the chip.
    //
    // DERIVED, so the chip redraws when the load CROSSES a band edge, not on each of ~80
    // readings a second.
    val live by remember(device, band, isWorking, timerOnly) {
        derivedStateOf { !timerOnly && isWorking && device.currentKg in band }
    }
    Text(
        WeightUnits.band(band),
        style = MaterialTheme.typography.labelMedium.copy(fontFeatureSettings = "tnum"),
        fontWeight = FontWeight.SemiBold,
        color = if (live) Color.White else palette.inkSecondary,
        modifier = Modifier
            .background(if (live) palette.bleu else Color.Transparent, CircleShape)
            .padding(horizontal = 9.dp, vertical = 4.dp)
            .clearAndSetSemantics {},
    )
}

/// Readable across a room: which hand, and whether to pull now.
///
/// `BasicText` with `autoSize` (iOS's `minimumScaleFactor(0.6)`): at a large font scale
/// "RIGHT — PULL" does not fit one line, ellipsis would cut the decision-critical word, and
/// wrapping would shove the hero numeral down mid-rep.
@Composable
internal fun Prompt(
    snapshot: RunnerSnapshot,
    tint: Color,
    timerOnly: Boolean,
    isConnected: Boolean,
) {
    val text = promptText(snapshot, timerOnly, isConnected)
    val style = MaterialTheme.typography.displaySmall
    val fontScale = LocalDensity.current.fontScale
    val promptHeight = if (fontScale >= 1.5f) (48 * fontScale).dp
        else with(LocalDensity.current) { style.lineHeight.toDp() }
    // Preserve the original full-size prompt line even when a longer translation needs
    // smaller glyphs. Otherwise pausing from HAND NEXT makes the graph jump vertically.
    Box(Modifier.fillMaxWidth().height(promptHeight).semantics {
            contentDescription = spokenPrompt(snapshot, timerOnly, isConnected)
        },
        contentAlignment = Alignment.Center,
    ) {
        BasicText(
            text,
            style = style.copy(fontWeight = FontWeight.ExtraBold, color = tint,
                textAlign = TextAlign.Center, lineHeight = 1.1.em),
            maxLines = if (fontScale >= 1.5f) 2 else 1,
            autoSize = TextAutoSize.StepBased(minFontSize = 20.sp, maxFontSize = 36.sp),
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

/// BOTH numbers, always: force says pull harder or ease off, the clock says hang on. An
/// earlier build swapped one for the other and the load vanished when it mattered most.
@Composable
private fun Hero(
    session: RunnerSession,
    snapshot: RunnerSnapshot,
    palette: GripPalette,
    timerOnly: Boolean,
    measureOnly: Boolean = false,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .testTag("runner.hero")
            // A numeral changing 80×/second is unusable under TalkBack; the cues and the
            // counters row are the accessible channel.
            .clearAndSetSemantics {},
        horizontalArrangement = Arrangement.spacedBy(18.dp, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.Bottom,
    ) {
        // No gauge, no kilogram: a permanent 0.0 reads as a fault, so the clock takes the hero.
        //
        // Each half takes a WEIGHT, which bounds the numeral's width: `autoSize` shrinks to its
        // constraints, and two unbounded 76 sp figures overflow at a large font scale.
        if (!timerOnly) {
            if (measureOnly) ForceReadoutText("0.0", palette.inkPrimary, palette, Modifier.weight(1f))
            else LiveForceReadout(snapshot, palette, Modifier.weight(1f))
        }
        CountdownNumeral(
            seconds = snapshot.secondsShown,
            tint = if (isStalled(snapshot)) palette.armed else palette.inkPrimary,
            palette = palette,
            modifier = Modifier.weight(1f),
        )
    }
}

/// The live kilogram readout, isolated in its OWN composable: `currentKg` changes per
/// sample, and read from the screen's body it recomposed everything 80×/s to move one number.
@Composable
private fun LiveForceReadout(snapshot: RunnerSnapshot, palette: GripPalette, modifier: Modifier = Modifier) {
    val device = LocalDeviceStore.current
    // The force number carries the "are you actually on it" signal: blue while the load is in
    // range and the clock is banking, amber the moment it leaves — either end.
    val tint = when {
        snapshot.phase !is RunnerPhase.Working -> palette.inkPrimary
        isStalled(snapshot) -> palette.armed
        else -> palette.bleu
    }
    ForceReadoutText(kgText(device.currentKg), tint, palette, modifier)
}

/** The hidden header uses the same typography without observing live force. */
@Composable
private fun ForceReadoutText(value: String, tint: Color, palette: GripPalette, modifier: Modifier) {
    Row(
        modifier,
        verticalAlignment = Alignment.Bottom,
        horizontalArrangement = Arrangement.spacedBy(4.dp, Alignment.CenterHorizontally),
    ) {
        BasicText(
            value,
            // **CLOCKS ROLL, MEASUREMENTS SNAP.** No animation: a value changing ten times a second
            // under a transition is a permanent blur.
            style = heroStyle(tint),
            maxLines = 1,
            autoSize = heroAutoSize,
            modifier = Modifier.weight(1f, fill = false),
        )
        Text(WeightUnits.symbol, style = TextStyle(fontSize = UNIT_SIZE), color = palette.inkTertiary, modifier = Modifier.padding(bottom = 10.dp))
    }
}

/// The clock. It SNAPS: Compose's digit slide read as "really bad and super laggy" on the
/// phone (Nuri, 2026-09-04), unlike SwiftUI's `.numericText()`. Counts DOWN the hold —
/// mid-hang you want how much longer, not a stopwatch.
@Composable
private fun CountdownNumeral(
    seconds: Int,
    tint: Color,
    palette: GripPalette,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier,
        verticalAlignment = Alignment.Bottom,
        horizontalArrangement = Arrangement.spacedBy(4.dp, Alignment.CenterHorizontally),
    ) {
        BasicText(
            "$seconds",
            style = heroStyle(tint),
            maxLines = 1,
            autoSize = heroAutoSize,
            modifier = Modifier.weight(1f, fill = false),
        )
        Text(tr("s"), style = TextStyle(fontSize = UNIT_SIZE), color = palette.inkTertiary,
            modifier = Modifier.padding(bottom = 10.dp))
    }
}

/// Tabular figures so a digit change does not shove the number sideways, and display
/// tracking at −2 % because letterforms read further apart as they grow.
private fun heroStyle(tint: Color) = TextStyle(
    fontSize = HERO_SIZE,
    fontWeight = FontWeight.Thin,
    fontFeatureSettings = "tnum",
    letterSpacing = (-0.02).em,
    color = tint,
)

/// The hero's own `minimumScaleFactor`. 40 sp is still a number readable at arm's length; a
/// clipped one is not readable at all.
private val heroAutoSize = TextAutoSize.StepBased(minFontSize = 40.sp, maxFontSize = HERO_SIZE)

/// The measured panel above the graph (iOS `measuredTop`): grip, prompt and hero, then the
/// time bar and the whole routine as pills, then the counters. The time bar, the pills and the
/// counters stay where they are through a long rest — that is when the whole routine is worth
/// reading — and only the block above them hands over to the rest summary.
///
/// The rhythm groups by proximity: the time bar sits CLOSE under the hero (it belongs to the
/// seconds), then a clear gap, then the pills sitting tight on the labels, which read as one
/// group: 8 + 6 + 11 + 3 + 4.
@Composable
private fun RunnerPanelHeader(
    session: RunnerSession,
    snapshot: RunnerSnapshot,
    tint: Color,
    timerOnly: Boolean,
    isConnected: Boolean,
) {
    val palette = LocalGripPalette.current
    val focused = snapshot.showsRestFocus
    // `results` changes only when the recorded count does, so the snapshot keys the model.
    val model = remember(snapshot.completedRepCount, session) {
        SessionProgressModel.of(session.runner.slots, session.runner.results)
    }
    if (focused && LocalDensity.current.fontScale >= 1.5f) {
        // Accessibility sizes reflow the rest into the summary alone. The routine rides above
        // the summary's own compact counts — re-stacking the full labels row there cost 55 pt
        // at AX3 on iOS.
        RunnerRestFocus(snapshot, routineRow = { RoutinePills(model, isLive = false) })
        return
    }
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        RestFocusHeaderFrame(
            focused = focused,
            liveHeader = {
                Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp),
                    horizontalAlignment = Alignment.CenterHorizontally) {
                    GripNameRow(snapshot, palette, timerOnly = false)
                    Prompt(snapshot, tint, timerOnly, isConnected)
                    Hero(session, snapshot, palette, timerOnly, measureOnly = focused)
                }
            },
            restHeader = { RunnerRestFocus(snapshot) },
        )
        Spacer(Modifier.height(8.dp))
        // One bar, always there: the hold while pulling, the rest's countdown while resting.
        RunnerTimeBar(session, timeBarMode(snapshot.phase), identity = snapshot.phase.slotIndex ?: -1)
        Spacer(Modifier.height(11.dp))
        RoutinePills(model, isLive = isHoldLive(snapshot.phase))
        Spacer(Modifier.height(4.dp))
        // The summary's badge already says REST; the row keeps its height.
        Counters(snapshot, showsPhaseWord = !focused, routineLeft = true)
    }
}

/// At the screen edges and a size up: the two numbers you check from a metre away between pulls.
@Composable
internal fun Counters(snapshot: RunnerSnapshot, showsPhaseWord: Boolean = true, routineLeft: Boolean = false) {
    val palette = LocalGripPalette.current
    val fontScale = LocalDensity.current.fontScale
    val annotation = if (!showsPhaseWord) null
        else if (snapshot.phase is RunnerPhase.Paused) nextHandText(snapshot)
        else restPhaseText(snapshot)
    // A routine line draws the whole routine, so the spoken line gains how much is left.
    val spoken = spokenState(snapshot) +
        if (routineLeft) routineLeftSpoken(snapshot.plannedRepCount, snapshot.completedRepCount) else ""
    Row(
        Modifier
            .widthIn(max = Metrics.maxContentWidth)
            .fillMaxWidth()
            // Reserve the same compact band in every phase. Two lines keep French set
            // breaks/paused hand guidance readable without shifting the graph on change.
            .height((40 * fontScale).dp)
            .testTag("runner-counters")
            .semantics(mergeDescendants = true) { contentDescription = spoken },
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CapsLabel(setLine(snapshot), Modifier.weight(1f).testTag("runner-set-count"))
        Box(Modifier.weight(1.1f), contentAlignment = Alignment.Center) {
            if (annotation != null) BasicText(
                annotation,
                style = MaterialTheme.typography.titleMedium.copy(
                    fontWeight = FontWeight.SemiBold, color = palette.inkSecondary,
                    textAlign = TextAlign.Center,
                ),
                autoSize = TextAutoSize.StepBased(minFontSize = 14.sp, maxFontSize = 18.sp),
                maxLines = 2,
                modifier = Modifier.fillMaxWidth().testTag("runner-rest-label"),
            )
        }
        CapsLabel(pullLine(snapshot), Modifier.weight(1f).testTag("runner-pull-count"), textAlign = TextAlign.End)
    }
}

@Composable
private fun NoSignalNotice(device: DeviceStore) {
    val palette = LocalGripPalette.current
    val connected = device.state.isConnected
    Column(
        Modifier.padding(horizontal = 24.dp).testTag("runner.signalWarning")
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
            if (connected) tr("Waiting for the gauge") else tr("Gauge not connected"),
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
            color = palette.inkSecondary,
        )
        Text(
            if (connected) {
                // A silent connected gauge is usually a stalled stream — unless a Dyno is still waiting on
                // calibration, where Wake would not help. `calibrationNote` is nil for gauges that need none.
                calibrationNote(device.calibrationStatus)
                    ?: tr("Connected, but no readings yet. Tap Wake to restart it.")
            } else {
                // **Names the selected gauge and promises no pairing that does not exist**: a broadcast
                // scale is only listened to, never paired.
                if (device.gaugeCapabilities.isBroadcast) {
                    tr("Tap Connect to start listening for your %s.", device.gaugeKind.displayName)
                } else {
                    tr("Tap Connect to pair with your %s.", device.gaugeKind.displayName)
                }
            },
            style = MaterialTheme.typography.bodySmall,
            color = palette.inkTertiary,
            textAlign = TextAlign.Center,
        )
    }
}

// MARK: - Timer-only

/// The gauge-free hero: countdown numeral and remaining-time ring as one object.
///
/// The ring depletes per PHASE, not per rep: rep progress is hold-only, so a per-rep ring
/// sat empty through lead-in and rest. It uses the numeral's clock, so the two cannot drift.
@Composable
private fun androidx.compose.foundation.layout.ColumnScope.TimerDial(
    session: RunnerSession,
    snapshot: RunnerSnapshot,
    tint: Color,
    palette: GripPalette,
) {
    val working = isTimerWorking(snapshot)
    val strokeDp = if (working) 12.dp else 7.dp
    val spoken = tr(
        "%s, %d seconds remaining",
        spokenPrompt(snapshot, timerOnly = true, isConnected = false),
        snapshot.secondsShown,
    )

    Box(
        Modifier
            // The dial is the hero, so it takes the slack (the trace card's job in the measured layout).
            .then(if (LocalDensity.current.fontScale >= 1.5f) Modifier.height(320.dp) else Modifier.weight(1f))
            .fillMaxWidth()
            .semantics(mergeDescendants = true) { contentDescription = spoken },
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier
                .widthIn(max = 260.dp)
                .fillMaxWidth()
                .aspectRatio(1f),
            contentAlignment = Alignment.Center,
        ) {
            LiveTimerRing(session, snapshot.phase, strokeDp, tint, palette)
            Column(
                Modifier.padding(horizontal = 24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                CountdownNumeral(snapshot.secondsShown, palette.inkPrimary, palette)
                if (!isResting(snapshot) || snapshot.phase.isPaused) {
                    CapsLabel(phasePromptText(snapshot, timerOnly = true, isConnected = false), color = tint)
                }
                nextHandText(snapshot)?.let { nextHand ->
                    BasicText(
                        nextHand,
                        style = MaterialTheme.typography.titleMedium.copy(
                            color = palette.inkSecondary,
                            fontWeight = FontWeight.SemiBold,
                            textAlign = TextAlign.Center,
                        ),
                        autoSize = TextAutoSize.StepBased(minFontSize = 12.sp, maxFontSize = 18.sp),
                        maxLines = if (LocalDensity.current.fontScale >= 1.5f) 2 else 1,
                        modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
                    )
                }
            }
        }
    }
}

/** Only this leaf reads the 10 Hz ring fraction. Animation frames invalidate drawing,
 * leaving the timer numeral, buttons and the screen on their coarse snapshot cadence.
 */
@Composable
private fun LiveTimerRing(
    session: RunnerSession,
    phase: RunnerPhase,
    strokeDp: Dp,
    tint: Color,
    palette: GripPalette,
) {
    val reduced = run.nuri.getagrip.ui.theme.rememberReduceMotion()
    key(phase) {
        val fraction by animateFloatAsState(
            targetValue = (session.phaseRemainingFraction ?: 0.0).toFloat(),
            animationSpec = if (reduced) snap() else Motion.live(),
            label = "Timer phase remaining",
        )
        Canvas(Modifier.fillMaxSize()) {
            val stroke = strokeDp.toPx()
            val inset = stroke / 2f
            val arcSize = Size(size.width - stroke, size.height - stroke)
            drawArc(
                color = palette.inkTertiary.copy(alpha = 0.18f),
                startAngle = 0f,
                sweepAngle = 360f,
                useCenter = false,
                topLeft = Offset(inset, inset),
                size = arcSize,
                style = Stroke(width = stroke),
            )
            if (fraction > 0f) {
                drawArc(
                    color = tint,
                    startAngle = -90f,
                    sweepAngle = 360f * fraction,
                    useCenter = false,
                    topLeft = Offset(inset, inset),
                    size = arcSize,
                    style = Stroke(width = stroke, cap = androidx.compose.ui.graphics.StrokeCap.Round),
                )
            }
        }
    }
}

// MARK: - Controls

@Composable
private fun Controls(session: RunnerSession, snapshot: RunnerSnapshot, timerOnly: Boolean) {
    val device = LocalDeviceStore.current
    val phase = snapshot.phase
    val pauseEnabled = RunnerControlPolicy.pauseEnabled(phase)
    val skipEnabled = RunnerControlPolicy.skipEnabled(phase)

    Column(
        Modifier.widthIn(max = Metrics.maxContentWidth).fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(Modifier.height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            // Buttons KEEP their labels while disabled; the prompt above says PAUSED / CONNECTING.
            // Swapped labels made TalkBack read "Paused, dimmed. Paused." twice; the sentence rides
            // the description instead.
            WideButton(
                title = if (phase.isPaused) tr("Resume") else tr("Pause"),
                icon = if (phase.isPaused) Icons.Filled.PlayArrow else Icons.Filled.Pause,
                enabled = pauseEnabled,
                disabledReason = RunnerControlPolicy.pauseDisabledReason(phase),
                modifier = Modifier.weight(1f).fillMaxHeight(),
            ) {
                session.send(if (phase.isPaused) RunnerEvent.Resume else RunnerEvent.Pause)
            }
            // No gauge: Tare has nothing to zero and Connect would change the session you are in.
            if (!timerOnly) {
                if (device.state.isConnected) {
                    TareButton(session, snapshot, Modifier.weight(1f).fillMaxHeight())
                } else {
                    WideButton(
                        title = tr("Connect"),
                        icon = Icons.Outlined.SettingsInputAntenna,
                        modifier = Modifier.weight(1f).fillMaxHeight(),
                    ) { device.connect() }
                }
            }
        }
        val skipPull = tr("Skip pull")
        val skipSet = tr("Skip set")
        AdaptiveActionRow(listOf(listOf(skipPull), listOf(skipSet),
            listOf(tr("Hold to end"), tr("Keep holding…")))) { index, cell ->
            when (index) {
                0 -> WideButton(title = skipPull, enabled = skipEnabled,
                    disabledReason = RunnerControlPolicy.skipDisabledReason(phase), modifier = cell) {
                    session.send(RunnerEvent.SkipRep)
                }
                1 -> WideButton(title = skipSet, enabled = skipEnabled,
                    disabledReason = RunnerControlPolicy.skipDisabledReason(phase), modifier = cell) {
                    session.send(RunnerEvent.SkipSet)
                }
                else -> HoldToEndButton(cell) { session.send(RunnerEvent.Abort) }
            }
        }
    }
}

/// Flexible-width tonal button. `SecondaryButton` hugs its label, which is right on a sheet
/// and wrong here — three hugging buttons in one row truncate "Pause" to "Pa…".
@Composable
private fun WideButton(
    title: String,
    modifier: Modifier = Modifier,
    icon: androidx.compose.ui.graphics.vector.ImageVector? = null,
    enabled: Boolean = true,
    disabledReason: String? = null,
    onClick: () -> Unit,
) {
    val palette = LocalGripPalette.current
    val interactionSource = remember { MutableInteractionSource() }
    Button(
        onClick = onClick,
        enabled = enabled,
        interactionSource = interactionSource,
        shape = CircleShape,
        colors = ButtonDefaults.buttonColors(
            containerColor = palette.card,
            contentColor = palette.inkPrimary,
            disabledContainerColor = palette.card.copy(alpha = 0.6f),
            disabledContentColor = palette.inkTertiary.copy(alpha = 0.5f),
        ),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(
            horizontal = Metrics.buttonHorizontalPadding, vertical = Metrics.buttonVerticalPadding),
        modifier = modifier
            .heightIn(min = Metrics.controlMinHeight)
            .pressFeedback(interactionSource)
            .semantics {
                if (disabledReason != null) contentDescription = L10n.tr("%s. %s", title, disabledReason)
            },
    ) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (icon != null) Icon(icon, contentDescription = null, modifier = Modifier.size(18.dp))
            Text(
                title,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                textAlign = TextAlign.Center,
            )
        }
    }
}

/// Loaded taring suits a static sling or mounted block, so a meaningful load is confirmed,
/// not refused. The PHASE guard keeps taring out of a live rep.
@Composable
private fun TareButton(session: RunnerSession, snapshot: RunnerSnapshot, modifier: Modifier) {
    val device = LocalDeviceStore.current
    var promptedKg by remember { mutableStateOf<Double?>(null) }
    var promptedEpoch by remember { mutableStateOf(0uL) }
    val palette = LocalGripPalette.current

    // The coarse, change-guarded flags — never `currentKg`, which would subscribe at sample rate.
    val decision = TarePolicy.tapDecision(
        phase = snapshot.phase,
        isReadingLive = device.isReadingLive,
        isLoadedForTare = device.isLoadedForTare,
    )
    // Enabled for the WAKE even in a phase that forbids taring — waking never zeroes
    // anything, and a dead stream mid-pull is when you most need it back.
    val enabled = decision != TareTapDecision.blocked
    // The label says what the tap DOES: "Wake" when the stream is dead, the reason while disabled.
    val title = when {
        decision == TareTapDecision.wakeStream -> tr("Wake")
        else -> TarePolicy.disabledLabel(snapshot.phase) ?: tr("Tare")
    }

    WideButton(
        title = title,
        icon = Icons.Outlined.Refresh,
        enabled = enabled,
        disabledReason = TarePolicy.disabledReason(snapshot.phase).takeIf { !enabled },
        modifier = modifier,
    ) {
        // Re-check on the tap against the live stores. A pull that starts after an unloaded
        // press must not slip through an enabled frame and zero load.
        if (!device.state.isConnected) return@WideButton
        when (TarePolicy.tapDecision(
            phase = session.snapshot.phase,
            isReadingLive = device.isReadingLive,
            isLoadedForTare = device.isLoadedForTare,
        )) {
            TareTapDecision.blocked -> Unit
            // Not a tare, and `wakeStream()` cannot become one: with no live samples the load
            // is unknown, and the frozen reading says 0 kg however loaded the gauge is.
            TareTapDecision.wakeStream -> session.wakeStream()
            TareTapDecision.confirm, TareTapDecision.tare -> {
                // Re-confirm liveness against the exact clock before anything irreversible. This can only
                // downgrade to a wake, never authorize.
                if (!TarePolicy.isSafeToTareNow(device.secondsSinceLastSample(), device.tareReadingMaxAge)) {
                    session.wakeStream()
                } else if (TarePolicy.shouldConfirm(device.currentKg)) {
                    promptedKg = device.currentKg
                    promptedEpoch = device.connectionEpoch
                } else {
                    session.tare()
                }
            }
        }
    }

    val prompted = promptedKg
    if (prompted != null) {
        AlertDialog(
            onDismissRequest = { promptedKg = null },
            title = { Text(tr("Zero the gauge?")) },
            text = { Text(WeightUnits.tr("There's %s kg on the gauge. Zero it?", kgText(prompted))) },
            confirmButton = {
                TextButton(onClick = {
                    when (
                        TarePolicy.confirmationDecision(
                            promptedKg = prompted,
                            currentKg = device.currentKg,
                            promptedEpoch = promptedEpoch,
                            currentEpoch = device.connectionEpoch,
                            isConnected = device.state.isConnected,
                            sampleAge = device.secondsSinceLastSample(),
                            phase = session.snapshot.phase,
                            maxAgeSeconds = device.tareReadingMaxAge,
                        )
                    ) {
                        // Silent: a reject means the phase, link or gauge changed, and the screen behind the
                        // dialog (this button's label included) already says so.
                        TareConfirmationDecision.reject -> promptedKg = null
                        // The load moved while the dialog was open, so the number it quoted is
                        // no longer true — ask again with the one that is.
                        TareConfirmationDecision.reask -> {
                            promptedKg = device.currentKg
                            promptedEpoch = device.connectionEpoch
                        }
                        TareConfirmationDecision.tare -> {
                            promptedKg = null
                            session.tare()
                        }
                    }
                }) { Text(tr("Zero it")) }
            },
            dismissButton = { TextButton(onClick = { promptedKg = null }) { Text(tr("Cancel")) } },
            containerColor = palette.card,
            titleContentColor = palette.inkPrimary,
            textContentColor = palette.inkSecondary,
        )
    }
}

// MARK: - Words, colours and small predicates

/// Blue while the clock runs, amber while it waits on you, red when something needs
/// attention, steel while resting. Read before any word is.
object RunnerTint {
    fun of(
        snapshot: RunnerSnapshot,
        palette: GripPalette,
        timerOnly: Boolean,
        isConnected: Boolean,
    ): Color {
        // Gauge-free is never "disconnected"; REST in alarm red would be an alarm about the app's
        // own choice.
        if (!timerOnly && (!isConnected || snapshot.linkIsDown)) return palette.alarm
        return when (snapshot.phase) {
            is RunnerPhase.Working -> if (isStalled(snapshot)) palette.armed else palette.bleu
            // Amber: "waiting on you".
            is RunnerPhase.Armed, is RunnerPhase.Releasing, is RunnerPhase.Paused -> palette.armed
            is RunnerPhase.Resting, is RunnerPhase.LeadIn, is RunnerPhase.Idle,
            is RunnerPhase.Finished,
            -> palette.calm
        }
    }
}

/** During rest, the engine's display slot already points at the actual next pull. */
internal fun nextHandText(snapshot: RunnerSnapshot): String? {
    if (!isResting(snapshot)) return null
    return when (snapshot.side) {
        Side.left -> L10n.tr("LEFT HAND NEXT")
        Side.right -> L10n.tr("RIGHT HAND NEXT")
        Side.both -> L10n.tr("BOTH HANDS NEXT")
        null -> null
    }
}

internal fun restPhaseText(snapshot: RunnerSnapshot): String? =
    if (isResting(snapshot)) {
        if (snapshot.isSetBreak) L10n.tr("SET BREAK") else L10n.tr("REST")
    } else null

internal fun promptText(snapshot: RunnerSnapshot, timerOnly: Boolean, isConnected: Boolean): String =
    if (snapshot.phase is RunnerPhase.Resting) {
        nextHandText(snapshot) ?: phasePromptText(snapshot, timerOnly, isConnected)
    } else phasePromptText(snapshot, timerOnly, isConnected)

private fun spokenPrompt(snapshot: RunnerSnapshot, timerOnly: Boolean, isConnected: Boolean): String {
    val phase = phasePromptText(snapshot, timerOnly, isConnected)
    return nextHandText(snapshot)?.let { L10n.tr("%s · %s", phase, it) } ?: phase
}

private fun phasePromptText(snapshot: RunnerSnapshot, timerOnly: Boolean, isConnected: Boolean): String =
    when (snapshot.phase) {
        is RunnerPhase.Idle -> if (timerOnly || isConnected) L10n.tr("GET READY") else L10n.tr("CONNECTING")
        is RunnerPhase.LeadIn -> L10n.tr("GET READY")
        is RunnerPhase.Armed -> L10n.tr("%s — PULL", snapshot.side?.prompt ?: "")
        // A silently stopped clock provokes pulling harder, the wrong instinct. With a target range
        // there are TWO ways to stall it and the fix for one worsens the other, so name which.
        is RunnerPhase.Working -> when {
            snapshot.isDropped -> L10n.tr("RE-GRIP")
            snapshot.isOverTarget -> L10n.tr("EASE OFF")
            else -> snapshot.side?.prompt ?: ""
        }
        // Hold banked, rest NOT started — say the one thing that starts it, or it reads as a frozen clock.
        is RunnerPhase.Releasing -> L10n.tr("LET GO")
        is RunnerPhase.Resting -> if (snapshot.isSetBreak) L10n.tr("SET BREAK") else L10n.tr("REST")
        is RunnerPhase.Paused -> L10n.tr("PAUSED")
        is RunnerPhase.Finished -> L10n.tr("DONE")
    }

private fun setLine(snapshot: RunnerSnapshot): String {
    val set = snapshot.setNumber ?: return L10n.tr("Session")
    return L10n.tr("Set %d of %d", set, snapshot.setCount)
}

/// Which pull you are ON, not how many you completed: recorded reps include skipped ones,
/// so a skipped-through session read "34 of 36 pulls". Matches "Set 6 of 6" beside it.
private fun pullLine(snapshot: RunnerSnapshot): String {
    val planned = snapshot.plannedRepCount
    val position = minOf(snapshot.completedRepCount + 1, planned)
    return L10n.tr("Pull %d of %d", position, planned)
}

private fun spokenState(snapshot: RunnerSnapshot): String {
    val grip = snapshot.grip ?: return L10n.tr("Session finished")
    val set = snapshot.setNumber ?: return L10n.tr("Session finished")
    return L10n.tr(
        "Set %d of %d, pull %d of %d, %s hand, %s",
        set,
        snapshot.setCount,
        snapshot.completedRepCount + 1,
        snapshot.plannedRepCount,
        snapshot.side?.displayName ?: "",
        grip.spoken,
    )
}

/// The amber badge is the sighted half of the grip-change cue; this is the other half, and
/// neither may be the only one.
private fun spokenGrip(snapshot: RunnerSnapshot, grip: GripSpec, timerOnly: Boolean): String {
    val resting = isResting(snapshot)
    val changing = snapshot.gripChangesNext
    val band = snapshot.targetBand
    // WHOLE SENTENCES, one key each: where the target lands is language-specific, and a
    // fragment starting with a comma cannot be moved by a translator.
    val body = if (band == null) {
        when {
            resting && changing -> L10n.tr("New grip next: %s", grip.spoken)
            resting -> L10n.tr("Next: %s", grip.spoken)
            else -> grip.spoken
        }
    } else {
        val lo = kgText(band.start)
        val hi = kgText(band.endInclusive)
        when {
            resting && changing ->
                WeightUnits.tr("New grip next: %s, target %s to %s kilograms", grip.spoken, lo, hi)
            resting -> WeightUnits.tr("Next: %s, target %s to %s kilograms", grip.spoken, lo, hi)
            else -> WeightUnits.tr("%s, target %s to %s kilograms", grip.spoken, lo, hi)
        }
    }
    return if (timerOnly) body + L10n.tr(", timing only") else body
}

private fun isResting(snapshot: RunnerSnapshot): Boolean = when (val phase = snapshot.phase) {
    is RunnerPhase.Resting -> true
    is RunnerPhase.Paused -> phase.before is RunnerPhase.Resting
    else -> false
}

private fun isWorking(snapshot: RunnerSnapshot): Boolean = snapshot.phase is RunnerPhase.Working

private fun isArmed(snapshot: RunnerSnapshot): Boolean = snapshot.phase is RunnerPhase.Armed

private fun isTimerWorking(snapshot: RunnerSnapshot): Boolean = when (val phase = snapshot.phase) {
    is RunnerPhase.Working -> true
    is RunnerPhase.Paused -> phase.before is RunnerPhase.Working
    else -> false
}

/// The rep is alive but its clock is not running — off the edge, or over the top of the
/// target range. One question, because the screen answers it the same way.
private fun isStalled(snapshot: RunnerSnapshot): Boolean = snapshot.isDropped || snapshot.isOverTarget

/// Display precision only, guarded against a non-finite reading — `Fmt.fixed` refuses one by
/// design, and a decoder that hands over a garbage float must not take the hero down with it.
internal fun kgText(value: Double): String = WeightUnits.number(value)

// MARK: - Previews

/** Debug intent only: the real runner and a private mock, with no training-store writes. */
@Composable
internal fun DebugRunnerPreview(onDone: () -> Unit) {
    val scope = rememberCoroutineScope()
    val device = remember(scope) {
        DeviceStore(MockProgressorClient(scope, MockForceProfile.clean), isMock = true, scope = scope)
    }
    val plan = remember {
        SessionPlan(name = "Runner preview", holdSeconds = 5, restSeconds = 20,
            setBreakSeconds = 30, leadInSeconds = 0,
            handMode = run.nuri.getagrip.engine.HandMode.alternateEachRep,
            sets = listOf(SetPlan(grip = GripSpec(), repsPerSide = 3,
                targetLoKg = 15.0, targetHiKg = 25.0),
                SetPlan(grip = GripSpec(edgeMM = 15), repsPerSide = 3)))
    }
    val session = remember(device, plan) { RunnerSession(plan, plan.name, device, scope = scope) }
    DisposableEffect(session) {
        session.begin()
        device.connect()
        onDispose { session.end(); device.disconnect() }
    }
    LaunchedEffect(device.state.isConnected) { session.startIfReady(StreamStartCause.initial) }
    LaunchedEffect(session.isFinished) { if (session.isFinished) onDone() }
    BackHandler(onBack = onDone)
    KeepScreenOn(true)
    RunnerWindowChrome(hideStatusBar = true)
    CompositionLocalProvider(LocalDeviceStore provides device) {
        Box(Modifier.fillMaxSize()) {
            RunnerLive(session, timerOnly = false)
            val snapshot = session.snapshot
            snapshot.grip?.let { grip ->
                PalmHand(grip = grip, side = snapshot.side ?: Side.both,
                    newGripID = snapshot.newGripID, holdsGripCueForRest = snapshot.gripChangesNext,
                    restFocus = snapshot.showsRestFocus,
                    isActive = !isResting(snapshot), modifier = Modifier.align(Alignment.TopCenter))
            }
            RunnerScreenBorder(runnerBorderCue(snapshot, false,
                device.state.isConnected && device.isStreaming && device.isSignalFresh), Modifier.matchParentSize())
        }
    }
}

@Composable
private fun previewStore(): DeviceStore {
    val scope = remember { CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate) }
    return remember {
        DeviceStore(MockProgressorClient(scope, MockForceProfile.clean), isMock = true, scope = scope)
    }
}

private fun previewPlan() = SessionPlan(
    name = L10n.tr("Daily no-hangs"),
    sets = listOf(
        SetPlan(grip = GripSpec(20, FingerSet.four), repsPerSide = 3),
        SetPlan(grip = GripSpec(20, FingerSet.frontTwo), repsPerSide = 3),
    ),
    holdSeconds = 7,
    restSeconds = 20,
)

@Composable
private fun PreviewRunner(dark: Boolean, timerOnly: Boolean, drive: (RunnerSession) -> Unit) {
    GetAGripTheme(darkTheme = dark) {
        androidx.compose.runtime.CompositionLocalProvider(LocalDeviceStore provides previewStore()) {
            val store = LocalDeviceStore.current
            val scope = rememberCoroutineScope()
            val session = remember {
                RunnerSession(
                    plan = previewPlan(),
                    routineName = L10n.tr("Daily no-hangs"),
                    device = store,
                    timerOnly = timerOnly,
                    scope = scope,
                ).also(drive)
            }
            Box(Modifier.fillMaxSize().background(LocalGripPalette.current.field)) {
                RunnerLive(session, timerOnly)
                session.snapshot.grip?.let { grip ->
                    PalmHand(
                        grip = grip,
                        side = session.snapshot.side ?: Side.both,
                        isActive = !isResting(session.snapshot),
                        restFocus = !timerOnly && session.snapshot.showsRestFocus,
                        modifier = Modifier.align(Alignment.TopCenter),
                    )
                }
                RunnerScreenBorder(runnerBorderCue(session.snapshot, timerOnly,
                    store.state.isConnected && store.isStreaming && store.isSignalFresh), Modifier.matchParentSize())
            }
        }
    }
}

@Preview(name = "Runner · working · light", showBackground = true, heightDp = 820)
@Composable
private fun RunnerWorkingLightPreview() = PreviewRunner(dark = false, timerOnly = false) {
    it.send(RunnerEvent.Start)
}

@Preview(name = "Runner · working · dark", showBackground = true, heightDp = 820)
@Composable
private fun RunnerWorkingDarkPreview() = PreviewRunner(dark = true, timerOnly = false) {
    it.send(RunnerEvent.Start)
}

@Preview(name = "Runner · resting · light", showBackground = true, heightDp = 820)
@Composable
private fun RunnerRestingLightPreview() = PreviewRunner(dark = false, timerOnly = false) {
    it.send(RunnerEvent.Start)
    it.send(RunnerEvent.SkipRep)
}

@Preview(name = "Runner · resting · dark", showBackground = true, heightDp = 820)
@Composable
private fun RunnerRestingDarkPreview() = PreviewRunner(dark = true, timerOnly = false) {
    it.send(RunnerEvent.Start)
    it.send(RunnerEvent.SkipRep)
}

@Preview(name = "Runner · timer only · light", showBackground = true, heightDp = 820)
@Composable
private fun RunnerTimerOnlyLightPreview() = PreviewRunner(dark = false, timerOnly = true) {
    it.send(RunnerEvent.Start)
}

@Preview(name = "Runner · timer only · dark", showBackground = true, heightDp = 820)
@Composable
private fun RunnerTimerOnlyDarkPreview() = PreviewRunner(dark = true, timerOnly = true) {
    it.send(RunnerEvent.Start)
}
