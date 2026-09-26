// SPDX-License-Identifier: MPL-2.0
// Original contributions Copyright 2026 Nuri Bruner.

package run.nuri.getagrip.ui.criticalforce

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import run.nuri.getagrip.ble.MockForceProfile
import run.nuri.getagrip.ble.StreamStartCause
import run.nuri.getagrip.ble.StreamStopCause
import run.nuri.getagrip.engine.CriticalForceFailure
import run.nuri.getagrip.engine.CriticalForceHands
import run.nuri.getagrip.engine.CriticalForceOutcome
import run.nuri.getagrip.engine.CriticalForceResult
import run.nuri.getagrip.engine.CriticalForceTest
import run.nuri.getagrip.engine.GripSpec
import run.nuri.getagrip.engine.L10n
import run.nuri.getagrip.engine.Side
import run.nuri.getagrip.runner.NoSessionServiceController
import run.nuri.getagrip.runner.SessionServiceController
import run.nuri.getagrip.store.DeviceStore
import run.nuri.getagrip.store.TarePolicy

/// The four screens of one visit.
sealed interface CriticalForceStage {
    data object Setup : CriticalForceStage
    data object Testing : CriticalForceStage
    data object Result : CriticalForceStage
    data class Ended(val message: String) : CriticalForceStage
}

/// One hand's finished test, before it is saved. `trace` is already encoded for storage.
class CriticalForceHandResult(val side: Side, val result: CriticalForceResult, val trace: ByteArray)

/// The setup's choice, in the routine builder's words. See `CriticalForceHands`.
enum class CriticalForceHandChoice { oneAtATime, bothHands, single }

/// **What opens the test — and, on this side, everything the visit is in the middle of.**
///
/// iOS keeps the stage in the cover's `@State`. Here a configuration change destroys every
/// `remember`, and a test cannot pause (W′ refills while you wait), so a rotation that
/// dropped the screen's state would void a maximal effort. The request is held by
/// `RootPresentation`, a ViewModel, exactly as `WorkoutViewModel` holds the runner: the
/// sessions, their tickers and the stage outlive the Activity's recreation.
///
/// It is also where the visit's FLOW lives (iOS: the view's `start`, `arm`,
/// `finishIfDone`, `nextHandOrFinish`), so the one-hand-at-a-time sequence is testable
/// without a screen. The composable only draws it and forwards taps.
///
/// **The visit follows the gauge and the test on its OWN scope** (`watch`), never from a
/// `LaunchedEffect`: a stopped Activity runs no effects, so a test kept alive behind a
/// locked screen by the foreground service never heard its gauge drop, and a test that
/// finished there kept the stream and the service running until the screen came back.
/// The runner learned this first — see `RunnerSession.watchConnection`.
@Stable
class CriticalForceTestRequest(
    grip: GripSpec,
    hands: CriticalForceHands,
    /// Where the visit's watchers run: `RootPresentation.viewModelScope`, which no screen
    /// can pause.
    private val scope: CoroutineScope,
    /// A fresh test for each hand. Each shares the visit's cue player.
    private val newSession: () -> CriticalForceSession,
    /// What keeps a connected test alive in the background — the runner's foreground
    /// service. iOS gets it from `bluetooth-central`.
    private val service: SessionServiceController = NoSessionServiceController,
) {
    var grip: GripSpec by mutableStateOf(grip)
    var handChoice: CriticalForceHandChoice by mutableStateOf(
        when (hands) {
            is CriticalForceHands.OneAtATime -> CriticalForceHandChoice.oneAtATime
            CriticalForceHands.BothHands -> CriticalForceHandChoice.bothHands
            is CriticalForceHands.Single -> CriticalForceHandChoice.single
        },
    )

    /// Which hand starts (one at a time), or the hand (one hand).
    var pickedSide: Side by mutableStateOf(
        when (hands) {
            is CriticalForceHands.OneAtATime -> if (hands.first == Side.right) Side.right else Side.left
            CriticalForceHands.BothHands -> Side.left
            is CriticalForceHands.Single -> if (hands.side == Side.right) Side.right else Side.left
        },
    )

    var stage: CriticalForceStage by mutableStateOf(CriticalForceStage.Setup)
        private set
    var editingGrip: Boolean by mutableStateOf(false)

    var alsoSaveMaxes: Boolean by mutableStateOf(true)
    var saveFailed: Boolean by mutableStateOf(false)

    /// Set when Start was refused because the gauge was loaded; cleared on the next Start
    /// that goes through. The dock says why nothing happened.
    var loadOnGaugeKg: Double? by mutableStateOf(null)
        private set
    var isSaving: Boolean by mutableStateOf(false)

    /// The test for the hand on the gauge now.
    var session: CriticalForceSession by mutableStateOf(newSession())
        private set

    /// Index into `hands.sides` of the hand on the gauge now.
    var handIndex: Int by mutableIntStateOf(0)
        private set

    /// Between hands: the first hand is done and the next is NOT armed until its Start.
    /// Moving the gauge or block to the other hand loads it, and an armed test would take
    /// that as the first pull (Nuri, 2026-09-25). Nothing is measuring meanwhile, so
    /// leaving the app or the screen costs nothing.
    var awaitingNextHand: Boolean by mutableStateOf(false)
        private set
    var results: List<CriticalForceHandResult> by mutableStateOf(emptyList())
        private set

    /// What happened to a hand that produced no result, said on the result screen.
    var notes: List<String> by mutableStateOf(emptyList())
        private set

    /// Which hand's pulls the result screen is showing.
    var shownSide: Side by mutableStateOf(Side.left)

    val hands: CriticalForceHands
        get() = when (handChoice) {
            CriticalForceHandChoice.oneAtATime -> CriticalForceHands.OneAtATime(pickedSide)
            CriticalForceHandChoice.bothHands -> CriticalForceHands.BothHands
            CriticalForceHandChoice.single -> CriticalForceHands.Single(pickedSide)
        }

    /// The hand on the gauge now.
    val side: Side get() = hands.sides.let { it[minOf(handIndex, it.size - 1)] }

    /// The demo gauge's profile before the test asked for `allOut`, handed back after.
    private var previousMockProfile: MockForceProfile? = null
    private var streamingDevice: DeviceStore? = null

    /// The link and the phase, followed from `start` until the stream stops.
    private var watching: Job? = null

    /// The link the current hand was armed on. A flow CONFLATES, so a drop and reconnect
    /// between two collections reads as a new epoch rather than a drop — still a gap in
    /// the readings the test cannot have seen.
    private var armedEpoch: ULong? = null

    /// Measuring now: a hand's test is armed or running. Between hands nothing is.
    val isMeasuring: Boolean get() = stage == CriticalForceStage.Testing && !awaitingNextHand

    // MARK: - Flow

    /// Start the visit on the first hand. The demo gauge pulls an all-out profile while a
    /// test runs, so demo mode sees a real-looking plateau.
    fun start(device: DeviceStore) {
        if (stage != CriticalForceStage.Setup || !device.state.isConnected) return
        if (refusesUnderLoad(device)) return
        handIndex = 0
        awaitingNextHand = false
        results = emptyList()
        notes = emptyList()
        saveFailed = false
        streamingDevice = device
        previousMockProfile = device.mockProfile
        device.setMockProfile(MockForceProfile.allOut)
        zeroThenStream(device)
        // The runner's rule: only a connected gauge that can stream in the background is
        // worth keeping the process alive for.
        if (device.gaugeCapabilities.sustainsBackgroundStreaming) service.begin()
        arm(newSession())
        stage = CriticalForceStage.Testing
        watch(device)
    }

    /// A fresh test for the hand now on the gauge. Only the reading callback moves.
    private fun arm(next: CriticalForceSession) {
        session.end()
        session = next
        next.arm()
        armedEpoch = streamingDevice?.link?.value?.epoch
        streamingDevice?.onTracePoint = { point -> next.receive(point.kg, point.t) }
    }

    /// Follow the gauge's link and the current hand's phase for as long as the visit
    /// streams. A drop mid-test interrupts it (void before pull 16, the end of that hand's
    /// test after); a finished or voided hand moves the visit on, which stops the stream
    /// and the service even with no screen to notice.
    private fun watch(device: DeviceStore) {
        watching?.cancel()
        watching = scope.launch {
            launch {
                device.link.collect { link ->
                    if (isMeasuring && (!link.isConnected || link.epoch != armedEpoch)) {
                        session.interrupt(CriticalForceTest.VoidReason.lostGauge)
                    }
                }
            }
            // The session is state too: a new hand's test is followed from its first phase.
            snapshotFlow { session to session.phase }.collect { (watched, phase) ->
                if (watched === session) phaseChanged(phase)
            }
        }
    }

    /// Every phase change of the current session lands here, from `watch`.
    internal fun phaseChanged(phase: CriticalForceTest.Phase) {
        if (!isMeasuring) return
        val hand = handName(side)
        when (phase) {
            CriticalForceTest.Phase.Finished -> {
                val (outcome, trace) = session.outcome() ?: return
                when (outcome) {
                    is CriticalForceOutcome.Success ->
                        results = results + CriticalForceHandResult(side, outcome.result, trace)
                    is CriticalForceOutcome.Failure ->
                        notes = notes + L10n.tr("%s: %s", hand, words(outcome.failure))
                }
                nextHandOrFinish(canContinue = true)
            }
            is CriticalForceTest.Phase.Voided -> {
                // Stopping by hand, losing the gauge or leaving the app ends the VISIT: the
                // next hand would start on a gauge that is gone or a climber who said stop.
                if (results.isEmpty()) {
                    stopStream(StreamStopCause.userStopped)
                    stage = CriticalForceStage.Ended(words(phase.reason))
                } else {
                    notes = notes + L10n.tr("%s: %s", hand, words(phase.reason))
                    nextHandOrFinish(canContinue = false)
                }
            }
            else -> Unit
        }
    }

    private fun nextHandOrFinish(canContinue: Boolean) {
        if (canContinue && handIndex + 1 < hands.sides.size && streamingDevice != null) {
            handIndex += 1
            // Wait for the climber: the next hand starts from its own Start tap. The
            // readings stop reaching any test until then.
            session.end()
            streamingDevice?.onTracePoint = null
            awaitingNextHand = true
            return
        }
        showResults()
    }

    /// The next hand's Start. A fresh stream for the fresh hand: harmless on a gauge (the
    /// same re-kick the runner sends), and the demo gauge replays its test from the start.
    fun startNextHand(device: DeviceStore) {
        if (!awaitingNextHand || !device.state.isConnected) return
        // Checked on the live reading BEFORE the stream stops: afterwards the load is
        // unknown, and a tare must not be guessed at.
        if (refusesUnderLoad(device)) return
        awaitingNextHand = false
        streamingDevice = device
        if (device.isStreaming) device.stopStreaming(StreamStopCause.measurementComplete)
        zeroThenStream(device)
        arm(newSession())
    }

    /// **Every test starts on a zeroed gauge**, as a routine does (`RunnerSession.startIfReady`).
    /// Refused while a live reading shows a hand still on it: taring under load would shift
    /// every reading of the test by that load. The threshold is `TarePolicy`'s confirm one.
    private fun refusesUnderLoad(device: DeviceStore): Boolean {
        if (device.isReadingLive && TarePolicy.shouldConfirm(device.currentKg)) {
            loadOnGaugeKg = device.currentKg
            return true
        }
        return false
    }

    /// Tare FIRST, then start the stream: the vendor's order; the reverse killed a fresh
    /// stream on hardware.
    private fun zeroThenStream(device: DeviceStore) {
        loadOnGaugeKg = null
        device.tare()
        device.resetPeak()
        device.startStreaming(StreamStartCause.manualMeasurement)
    }

    /// "Finish with the first hand only", from between the hands.
    fun finishEarlyBetweenHands() {
        if (stage != CriticalForceStage.Testing || handIndex == 0) return
        awaitingNextHand = false
        showResults()
    }

    private fun showResults() {
        stopStream(StreamStopCause.measurementComplete)
        if (results.isEmpty()) {
            stage = CriticalForceStage.Ended(
                if (notes.isEmpty()) words(CriticalForceTest.VoidReason.tooFewReps) else notes.joinToString("\n\n"),
            )
        } else {
            shownSide = results.first().side
            stage = CriticalForceStage.Result
        }
    }

    /// Back, from an armed hand that has not pulled yet.
    ///
    /// On the FIRST hand nothing has been measured, so leaving is free and it is setup
    /// again. On a later hand the visit already holds a finished hand, and setup's Start and
    /// Cancel both begin again from nothing: Back there must never discard it. It returns
    /// to between the hands, where the next hand's Start and "Finish with … only" wait.
    fun back() {
        if (!isMeasuring || session.phase != CriticalForceTest.Phase.Armed) return
        if (handIndex > 0) {
            session.end()
            streamingDevice?.onTracePoint = null
            awaitingNextHand = true
            return
        }
        stopStream(StreamStopCause.userStopped)
        stage = CriticalForceStage.Setup
    }

    /// Everything the test switched on, off. Idempotent.
    fun stopStream(cause: StreamStopCause) {
        val device = streamingDevice
        if (device != null) {
            device.onTracePoint = null
            if (device.isStreaming) device.stopStreaming(cause)
            previousMockProfile?.let { device.setMockProfile(it) }
        }
        streamingDevice = null
        previousMockProfile = null
        armedEpoch = null
        watching?.cancel()
        watching = null
        session.end()
        service.end()
    }

    /// Leaving the screen: a test in progress is interrupted (voided before pull 16), then
    /// the stream stops.
    fun teardown() {
        if (stage == CriticalForceStage.Testing && !awaitingNextHand) {
            session.interrupt(CriticalForceTest.VoidReason.leftApp)
        }
        stopStream(StreamStopCause.screenClosed)
    }

    companion object {
        /// "Both hands", or the hand's own name.
        fun handName(side: Side): String = if (side == Side.both) L10n.tr("Both hands") else side.displayName
    }
}

internal fun words(reason: CriticalForceTest.VoidReason): String = when (reason) {
    CriticalForceTest.VoidReason.tooFewReps ->
        L10n.tr("Stopped before pull 16, so there's no result. Rest at least 30 minutes before retesting.")
    CriticalForceTest.VoidReason.lostGauge ->
        L10n.tr("The gauge disconnected before pull 16, so there's no result. Rest at least 30 minutes before retesting.")
    CriticalForceTest.VoidReason.leftApp ->
        L10n.tr("You left the app before pull 16, so there's no result. Rest at least 30 minutes before retesting.")
}

internal fun words(failure: CriticalForceFailure): String = when (failure) {
    is CriticalForceFailure.TooFewReps -> L10n.tr("Stopped before pull 16, so there's no result.")
    CriticalForceFailure.TooLittleData ->
        L10n.tr("Too many gaps in the gauge's readings to give a result. Keep the phone closer next time.")
    CriticalForceFailure.NoPull ->
        L10n.tr("No pulls recorded. Tare the gauge and pull through it.")
}
