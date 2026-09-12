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
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
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
import run.nuri.getagrip.ui.l10n.tr
import run.nuri.getagrip.ui.theme.GetAGripTheme
import run.nuri.getagrip.ui.theme.GripPalette
import run.nuri.getagrip.ui.theme.LocalGripPalette
import run.nuri.getagrip.ui.theme.Motion
import run.nuri.getagrip.ui.theme.readablePageWidth
import run.nuri.getagrip.ui.theme.Metrics
import run.nuri.getagrip.ui.units.WeightUnits
import run.nuri.getagrip.ui.tour.LocalTourController
import run.nuri.getagrip.ui.tour.TourAct
import run.nuri.getagrip.ui.tour.TourTarget
import run.nuri.getagrip.ui.tour.tourAnchor

/// The hero numeral, at the size the whole screen is arranged around. `sp`, not `dp`: a bare
/// pixel height renders identically at every accessibility setting while the controls around
/// it grow.
private val HERO_SIZE = 76.sp
private val UNIT_SIZE = 22.sp

/// **The whole runner, from a plan to a decision about what to log.**
///
/// The guided session — the screen you look at, at arm's length, with chalk on your hands
/// and something heavy on your fingers. Everything is arranged around that: what matters
/// most is largest, the phase is legible from its colour before you read a word, and every
/// control is big enough to hit without looking. It owns no timing logic — `RunnerSession`
/// holds the state machine and this only draws what it says.
///
/// **What the integrator wires:** Today builds the plan and the max table and pushes this
/// destination; `onFinished` receives the finished session and what the summary decided, and
/// is where `recordSession` / `recordMax` belong. `onExit` pops the destination. Nothing in
/// here touches a store other than `DeviceStore`, which is what keeps the screen previewable.
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

    // **THE SESSION ACT, on the first MEASURED session you run.**
    //
    // Started inside the same effect that creates the session, not as a modifier of its own:
    // ordered separately it ran first, found no session, and taught over a live workout.
    // Measured sessions only — a gauge-free one has no trace and no lane to point at.
    val tour = LocalTourController.current
    LaunchedEffect(session, timerOnly) {
        if (!timerOnly) tour.beginIfUnseen(TourAct.Session)
    }

    // **The session act PAUSES the runner, and resumes it when the act ends.** Teaching over a
    // running clock costs the pull being explained. `RunnerEvent.Pause` is the same event the
    // Pause button and a background send, so the engine needs no tour-shaped special case —
    // and the resume is guarded on having been the one to pause, so ending the tour can never
    // restart a session the climber paused themselves.
    val teaching = tour.sessionPausesRunner
    LaunchedEffect(teaching) {
        if (teaching) {
            if (!session.snapshot.phase.isPaused) {
                session.send(RunnerEvent.Pause)
                workout.pausedByTour = true
            }
        } else if (workout.pausedByTour) {
            workout.pausedByTour = false
            if (session.snapshot.phase.isPaused) session.send(RunnerEvent.Resume)
        }
    }

    // The screen you look at with both hands on an edge; the phone timing out mid-pull is
    // the app going blind.
    KeepScreenOn(true)

    // The status bar comes back for the SUMMARY: that screen is a document you read and
    // scroll, not a thing you glance at mid-hang, and there is no black palm under the
    // cutout for its glyphs to disappear into.
    RunnerWindowChrome(hideStatusBar = !session.isFinished)
    RunnerLifecycle(session, device, timerOnly)

    // `onChange`, not "on every composition": `connectionChanged` sends real engine events.
    val connected = device.state.isConnected
    LaunchedEffect(connected) {
        if (workout.lastConnected != null && workout.lastConnected != connected) session.connectionChanged(connected)
        workout.lastConnected = connected
    }

    // **System back PAUSES; it never ends.** Ending a session is the hold, and only the
    // hold — a gesture people fire by reflex must not be able to destroy a workout, and iOS
    // gets this for free by presenting the runner as a cover with no back affordance at all.
    // Android's back gesture is always there, so it is given the one meaning that is safe
    // and useful mid-set: stop the clock. Leaving is then a deliberate Hold to end, exactly
    // as it is on iOS. (Predictive back is deliberately not intercepted for an animation
    // this screen would not honour anyway.)
    BackHandler(enabled = !session.isFinished) {
        if (!session.snapshot.phase.isPaused) session.send(RunnerEvent.Pause)
    }

    Box(
        modifier
            .fillMaxSize()
            .background(Color.Transparent),
    ) {
        if (session.isFinished) {
            // FROZEN at the moment the session ended. Recomputing it every recomposition
            // would move `finishedAt` and re-derive the max candidates under the rows the
            // climber is tapping.
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
                    // The tour's first session step lights the palm — "your fingers hang off
                    // the palm at the top of the screen".
                    tourAnchor = Modifier.tourAnchor(TourTarget.RunnerHand),
                    // Dimmed while resting, so the hand says "this is what's COMING" rather
                    // than "pull this now".
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

/// Portrait, behind the cutout, no status bar — and all three put back on the way out.
///
/// Keep the camera-aligned fingers in portrait and the workout free of status-bar chrome.
/// Window settings are restored when the session ends.
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
            activity.requestedOrientation = previousOrientation
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
                    // **AND KICK THE STREAM.** This is the one that actually mattered (Nuri,
                    // 2026-08-10: "when you first come back you get a little dot, then after
                    // a while the stream continues"). The gauge stops sending while the app
                    // is suspended, and the only thing that revived it was the watchdog —
                    // which sleeps 500 ms between checks and then wants its whole silence
                    // budget. Re-sending start to a live stream is harmless; not sending it
                    // is three dead seconds in the middle of a rep.
                    session.startIfReady(StreamStartCause.foreground)
                }

                Lifecycle.Event.ON_PAUSE, Lifecycle.Event.ON_STOP -> {
                    val isBackground = event == Lifecycle.Event.ON_STOP
                    if (isBackground && !timerOnly) {
                        // Clear the device-time anchor BEFORE suspension. Backgrounding
                        // already forfeits any unobserved work; this prevents queued
                        // old-epoch samples from inheriting a high-water mark across the
                        // foreground re-kick.
                        session.send(RunnerEvent.StreamRestarted)
                    }
                    // **`timerOnly` short-circuits the lot, whatever is connected.** A
                    // gauge-free session never streams, so nothing keeps the process alive
                    // even with a Progressor sitting there connected from earlier: the
                    // ticker stops with the process and the hold freezes with no PAUSED
                    // state to explain it, which is precisely the silent stall this guard
                    // exists to prevent.
                    //
                    // ON_STOP is "background" and ON_PAUSE is "inactive", matching iOS's
                    // scenePhase exactly — so a CONNECTED session that can stream in the
                    // background survives both, and a session with no link pauses on either.
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
            // The gesture bar only. The TOP inset is deliberately NOT consumed: the window
            // draws behind the cutout and the status bar is hidden, which is exactly what
            // lets the fingers align beneath the physical camera region.
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
            // The hand owns the whole top band, so the grip's NAME is all that goes up here —
            // drawing the glyph again would be the same picture twice — and the counters move
            // DOWN to sit above the graph. They read just as well there: they are the two
            // numbers you check between pulls, not while pulling.
            RestFocusHeaderFrame(
                focused = snapshot.showsRestFocus,
                liveHeader = {
                    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp),
                        horizontalAlignment = Alignment.CenterHorizontally) {
                        GripNameRow(snapshot, palette, timerOnly = false)
                        Prompt(snapshot, tint, timerOnly, device.state.isConnected)
                        Hero(session, snapshot, palette, timerOnly, measureOnly = snapshot.showsRestFocus)
                        RepProgress(session, snapshot, palette)
                        Counters(snapshot)
                    }
                },
                restHeader = { RunnerRestFocus(snapshot) },
            )
            Surface(
                shape = RoundedCornerShape(Metrics.radiusCard),
                color = palette.card,
                modifier = Modifier
                    .widthIn(max = Metrics.maxContentWidth)
                    .fillMaxWidth()
                    .then(if (scrollsForLargeText) Modifier.height(220.dp) else Modifier.weight(1f))
                    // The tour's "lane" step lights the whole card, not the Canvas: the band
                    // is drawn inside it and a hole cropped to the plot would cut the card's
                    // own corners off.
                    .tourAnchor(TourTarget.RunnerTrace),
            ) {
                Box(Modifier.testTag("runner-plot"), contentAlignment = Alignment.Center) {
                    ForceTraceView(
                        modifier = Modifier.fillMaxSize().padding(horizontal = 4.dp),
                        thresholdKg = session.plan.thresholdKg,
                        // Only while the rep is actually live. A lane drawn during the rest
                        // would ask you to hold a load you are not holding.
                        targetBand = if (isWorking(snapshot) || isArmed(snapshot)) {
                            snapshot.targetBand
                        } else {
                            null
                        },
                        tint = tint,
                    )
                    // A connected gauge that is not sending is the one failure "0.0 kg"
                    // renders as a lie — it reads as a device measuring nothing rather than
                    // an app receiving nothing, and there is no way to tell them apart by
                    // looking. Say it, and say what to do.
                    // Focus replaces the old prompt that normally reports a lost link.
                    // hasSignal means a sample has arrived at least once, not that the
                    // gauge is still sending. Keep its live warning visible in the graph.
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

/// The grip, and — during a rest — the fact that it is the one COMING UP.
///
/// The snapshot already looks forward while resting (`SessionRunner.displaySlot`), so this
/// row silently changed meaning between phases. "Next" is what makes that legible instead of
/// leaving you to work out which grip you are being shown.
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
            // The full name fits here — the glyph no longer shares this row — so the short
            // form is only needed when a badge or a target chip is also present.
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
            // A gauge-free session is a legitimate timing protocol, so its mode belongs beside
            // the target instruction rather than underneath a card as an apology.
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

/// The rest screen's change of tense — and, when the pull ahead is on a different grip, the
/// whole cue that it is (a grip change between sets is easy to miss while you shake out).
///
/// Amber is the house colour for "waiting on you", which choosing a new grip during a rest
/// literally is — alarm red stays reserved for attention. The WORD changes with the colour,
/// so the cue survives greyscale and colourblindness on its own.
///
/// A SOLID amber capsule with fixed dark ink, not amber TEXT: amber ink on the light field
/// measures 1.72:1 against a 4.5:1 floor and cannot carry small text in either scheme.
/// Filling the capsule flips the arithmetic, and both colours are fixed literals, so the
/// ratio cannot move with the scheme.
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
    // In a timer-only session the gauge value is zero or stale by definition. Letting it
    // light this instruction chip would claim that an unmeasured pull is engaged.
    val live = !timerOnly && isWorking && device.currentKg in band
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

/// The one thing that has to be readable across a room: which hand, and whether to be
/// pulling right now.
///
/// `BasicText` with `autoSize`, not `Text` — the Compose answer to iOS's
/// `minimumScaleFactor(0.6)`. "RIGHT — PULL" at a large accessibility font scale does not fit
/// one line, and the alternatives are both wrong: ellipsis turns the decision-critical word
/// into "RIGHT — PU…", and wrapping shoves the hero numeral down mid-rep.
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

/// BOTH numbers, always: what you are pulling and how much longer.
///
/// They answer different questions and you need them at the same moment — the force tells
/// you whether to pull harder or ease off, the clock tells you whether to hang on. An
/// earlier build swapped one for the other and the load simply vanished for the ten seconds
/// it mattered most.
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
        // No gauge, no kilogram. The clock takes the whole hero rather than sharing it with a
        // permanent 0.0 — an empty measurement reads as a fault.
        //
        // Each half takes a WEIGHT, which is what bounds the numeral's width — `autoSize`
        // shrinks to the constraints it is given, and two unbounded 76 sp figures side by
        // side simply overflow at a large font scale.
        if (!timerOnly) {
            if (measureOnly) ForceReadoutText("0.0", palette.inkPrimary, palette, Modifier.weight(1f))
            else LiveForceReadout(snapshot, palette, Modifier.weight(1f))
        }
        CountdownNumeral(
            seconds = snapshot.secondsShown,
            tint = if (isStalled(snapshot)) palette.armed else palette.inkPrimary,
            palette = palette,
            modifier = Modifier.weight(1f).tourAnchor(TourTarget.RunnerClock),
        )
    }
}

/// The live kilogram readout, isolated in its OWN composable.
///
/// `DeviceStore.currentKg` changes with every force sample. Read from the screen's body,
/// that recomposed counters, prompt, grip line and controls 80 times a second to move one
/// number. A leaf reading the store directly means the invalidation stops here, at the only
/// thing that actually changed.
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
            // **CLOCKS ROLL, MEASUREMENTS SNAP.** No animation at all on this number: a
            // value changing ten times a second under an animated transition turns the
            // figure you are trying to read mid-pull into a permanent blur.
            style = heroStyle(tint),
            maxLines = 1,
            autoSize = heroAutoSize,
            modifier = Modifier.weight(1f, fill = false),
        )
        Text(WeightUnits.symbol, style = TextStyle(fontSize = UNIT_SIZE), color = palette.inkTertiary, modifier = Modifier.padding(bottom = 10.dp))
    }
}

/// The clock. It SNAPS — on iOS a clock rolls (`.numericText()`) because SwiftUI renders
/// that well; Compose's digit slide read as "really bad and super laggy" on the phone
/// (Nuri, 2026-09-04), so on Android every numeral is a plain swap. Counts DOWN the hold,
/// never up: mid-hang you want to know how much longer, not a stopwatch you have to
/// subtract from.
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

/// Settles between measured fractions at display refresh rate. Only this small
/// composable reads sample-rate state; the rest of the runner stays on coarse updates.
@Composable
private fun RepProgress(session: RunnerSession, snapshot: RunnerSnapshot, palette: GripPalette) {
    if (snapshot.phase is RunnerPhase.Working) {
        val progress by rememberPullProgress(
            measured = session.repProgress,
            phase = snapshot.phase,
            reduced = run.nuri.getagrip.ui.theme.rememberReduceMotion(),
        )
        LinearProgressIndicator(
            progress = { progress },
            color = palette.bleu,
            trackColor = palette.inkTertiary.copy(alpha = 0.2f),
            modifier = Modifier
                .widthIn(max = Metrics.maxContentWidth)
                .fillMaxWidth()
                .height(4.dp)
                .clearAndSetSemantics {},
        )
    } else {
        // Reserve the row so the layout doesn't jump every time a rep starts.
        Box(Modifier.height(4.dp))
    }
}

/// Pushed OUT to the screen edges and up a size. They are the two numbers you check from a
/// metre away between pulls, and at caption size inside the house margin they were a footnote.
@Composable
internal fun Counters(snapshot: RunnerSnapshot) {
    val palette = LocalGripPalette.current
    val fontScale = LocalDensity.current.fontScale
    val annotation = if (snapshot.phase is RunnerPhase.Paused) nextHandText(snapshot)
        else restPhaseText(snapshot)
    Row(
        Modifier
            .widthIn(max = Metrics.maxContentWidth)
            .fillMaxWidth()
            // Reserve the same compact band in every phase. Two lines keep French set
            // breaks/paused hand guidance readable without shifting the graph on change.
            .height((40 * fontScale).dp)
            .testTag("runner-counters")
            .semantics(mergeDescendants = true) { contentDescription = spokenState(snapshot) },
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
                tr("Connected, but no readings yet. Tap Wake to restart it.")
            } else {
                // **Names the gauge that is actually selected, and does not promise a pairing
                // that does not exist.** A broadcast scale is never paired with — the app
                // listens for its advertisements — so telling somebody to pair with a
                // Progressor they do not own is wrong twice over.
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

/// The gauge-free hero: the countdown numeral and the phase's remaining time are one object,
/// because proximity is the mapping that makes a timer readable at a glance.
///
/// The ring depletes per PHASE, not per rep: rep progress is hold-only and is zero through
/// all of lead-in and rest, which made the old ring empty exactly when the timer-only user
/// needed it most. The ring's fraction uses the same countdown clock as the numeral, so
/// the two channels cannot drift.
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
            // The dial is the hero, so it is the element that takes the slack — the same job
            // the trace card's weight does in the measured layout.
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
            // The buttons KEEP their identity while disabled: the visible reason the house
            // rule demands is the prompt above them, which says PAUSED / CONNECTING at
            // display weight. Swapping the labels spent the two Skips' names on the same
            // repeated word, and TalkBack read "Paused, dimmed. Paused." twice with no way to
            // tell them apart. The full sentence rides the description instead.
            WideButton(
                title = if (phase.isPaused) tr("Resume") else tr("Pause"),
                icon = if (phase.isPaused) Icons.Filled.PlayArrow else Icons.Filled.Pause,
                enabled = pauseEnabled,
                disabledReason = RunnerControlPolicy.pauseDisabledReason(phase),
                modifier = Modifier.weight(1f).fillMaxHeight(),
            ) {
                session.send(if (phase.isPaused) RunnerEvent.Resume else RunnerEvent.Pause)
            }
            // Neither Tare nor Connect belongs here without a gauge: one has nothing to zero
            // and the other would offer to change the session you are in.
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

/// Loaded taring is useful for a static sling or mounted block, so the control confirms
/// instead of silently refusing a meaningful reading. The PHASE guard — not the load — keeps
/// taring out of a live rep; confirmation is the warning that prevents an allowed phase from
/// zeroing a load the climber did not mean to discard.
@Composable
private fun TareButton(session: RunnerSession, snapshot: RunnerSnapshot, modifier: Modifier) {
    val device = LocalDeviceStore.current
    var promptedKg by remember { mutableStateOf<Double?>(null) }
    var promptedEpoch by remember { mutableStateOf(0uL) }
    val palette = LocalGripPalette.current

    // `device.isLoadedForTare` and `device.isReadingLive`, never `device.currentKg` — the
    // coarse, change-guarded flags, so this does not register a dependency on a value moving
    // at sample rate.
    val decision = TarePolicy.tapDecision(
        phase = snapshot.phase,
        isReadingLive = device.isReadingLive,
        isLoadedForTare = device.isLoadedForTare,
    )
    // Enabled for the WAKE even in a phase that forbids taring — waking never zeroes
    // anything, and a dead stream mid-pull is when you most need it back.
    val enabled = decision != TareTapDecision.blocked
    // The label says what the tap will actually DO — "Wake" when the stream is dead, and the
    // phase's reason while disabled. A button reading "Tare" that restarts the stream instead
    // would be lying about itself.
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
                // The rendered decision said the reading was live; confirm that against the
                // exact clock before doing anything irreversible. This can only downgrade to
                // a wake, never authorize.
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
                        // Deliberately silent. A reject means the phase moved into a pull, the
                        // link changed, or the gauge went away — and in every one of those
                        // cases the screen behind the dialog has already changed to say so,
                        // including this button's own label.
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
        // A gauge-free session is never "disconnected": there is nothing to be connected to,
        // and painting REST in alarm red because of a device nobody asked for is the app
        // raising an alarm about its own choice.
        if (!timerOnly && (!isConnected || snapshot.linkIsDown)) return palette.alarm
        return when (snapshot.phase) {
            is RunnerPhase.Working -> if (isStalled(snapshot)) palette.armed else palette.bleu
            // Amber, the app's "waiting on you" colour — which is exactly what these are.
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
        // The clock stopping without saying so looks like a bug, and the instinct it provokes
        // — pull harder — is the wrong one. Say what to do instead, and note that with a
        // target range there are now TWO ways to stall it: the reflex that fixes one makes the
        // other worse, so the word has to name which.
        is RunnerPhase.Working -> when {
            snapshot.isDropped -> L10n.tr("RE-GRIP")
            snapshot.isOverTarget -> L10n.tr("EASE OFF")
            else -> snapshot.side?.prompt ?: ""
        }
        // The hold is banked and the rest has NOT started — say the one thing that starts it.
        // Silence here would read as a frozen clock, which is the same bug "RE-GRIP" exists to
        // prevent at the other end of the rep.
        is RunnerPhase.Releasing -> L10n.tr("LET GO")
        is RunnerPhase.Resting -> if (snapshot.isSetBreak) L10n.tr("SET BREAK") else L10n.tr("REST")
        is RunnerPhase.Paused -> L10n.tr("PAUSED")
        is RunnerPhase.Finished -> L10n.tr("DONE")
    }

private fun setLine(snapshot: RunnerSnapshot): String {
    val set = snapshot.setNumber ?: return L10n.tr("Session")
    return L10n.tr("Set %d of %d", set, snapshot.setCount)
}

/// Which pull you are ON, not how many you have completed.
///
/// The count of RECORDED reps includes skipped ones, so a session skipped through read
/// "34 of 36 pulls" — which says you did 34. Position through the plan is what this row is
/// for, and it matches the "Set 6 of 6" beside it.
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
    // WHOLE SENTENCES, one key each, rather than a body with a target fragment glued on:
    // where the target lands inside the sentence is language-specific, and a fragment
    // starting with a comma is not a thing a translator can move.
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
