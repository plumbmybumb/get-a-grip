// SPDX-License-Identifier: MPL-2.0
// Original contributions Copyright 2026 Nuri Bruner.

package run.nuri.getagrip.ui.criticalforce

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
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
@Stable
class CriticalForceTestRequest(
    grip: GripSpec,
    hands: CriticalForceHands,
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

    /// The first test asks; afterwards the value lives in Settings.
    var bodyWeightDraft: Double by mutableDoubleStateOf(70.0)
    var alsoSaveMaxes: Boolean by mutableStateOf(true)
    var saveFailed: Boolean by mutableStateOf(false)
    var isSaving: Boolean by mutableStateOf(false)

    /// The test for the hand on the gauge now.
    var session: CriticalForceSession by mutableStateOf(newSession())
        private set

    /// Index into `hands.sides` of the hand on the gauge now.
    var handIndex: Int by mutableIntStateOf(0)
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

    // MARK: - Flow

    /// Start the visit on the first hand. The demo gauge pulls an all-out profile while a
    /// test runs, so demo mode sees a real-looking plateau.
    fun start(device: DeviceStore) {
        if (!device.state.isConnected) return
        handIndex = 0
        results = emptyList()
        notes = emptyList()
        saveFailed = false
        streamingDevice = device
        previousMockProfile = device.mockProfile
        device.setMockProfile(MockForceProfile.allOut)
        device.resetPeak()
        device.startStreaming(StreamStartCause.manualMeasurement)
        // The runner's rule: only a connected gauge that can stream in the background is
        // worth keeping the process alive for.
        if (device.gaugeCapabilities.sustainsBackgroundStreaming) service.begin()
        arm(newSession())
        stage = CriticalForceStage.Testing
    }

    /// A fresh test for the hand now on the gauge. Only the reading callback moves.
    private fun arm(next: CriticalForceSession) {
        session.end()
        session = next
        next.arm()
        streamingDevice?.onTracePoint = { point -> next.receive(point.kg, point.t) }
    }

    /// The screen reports every phase change of the current session here.
    fun phaseChanged(phase: CriticalForceTest.Phase) {
        if (stage != CriticalForceStage.Testing) return
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
        val device = streamingDevice
        if (canContinue && handIndex + 1 < hands.sides.size && device != null) {
            handIndex += 1
            // A fresh stream for the fresh hand: harmless on a gauge (the same re-kick the
            // runner sends), and the demo gauge replays its test from the start.
            device.stopStreaming(StreamStopCause.measurementComplete)
            device.startStreaming(StreamStartCause.manualMeasurement)
            arm(newSession())
            return
        }
        showResults()
    }

    /// "Finish with the first hand only", from between the hands.
    fun finishEarlyBetweenHands() {
        if (stage == CriticalForceStage.Testing && handIndex > 0) showResults()
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

    /// Nothing has been measured while armed on the first hand, so leaving is free.
    fun backToSetup() {
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
        session.end()
        service.end()
    }

    /// Leaving the screen: a test in progress is interrupted (voided before pull 16), then
    /// the stream stops.
    fun teardown() {
        if (stage == CriticalForceStage.Testing) session.interrupt(CriticalForceTest.VoidReason.leftApp)
        stopStream(StreamStopCause.screenClosed)
    }

    companion object {
        /// "Both hands", or the hand's own name.
        fun handName(side: Side): String = if (side == Side.both) L10n.tr("Both hands") else side.displayName
    }
}

internal fun words(reason: CriticalForceTest.VoidReason): String = when (reason) {
    CriticalForceTest.VoidReason.tooFewReps ->
        L10n.tr("Stopped before pull 16, before your force had levelled off, so there’s no result. Rest at least half an hour before trying again, or test another day.")
    CriticalForceTest.VoidReason.lostGauge ->
        L10n.tr("The gauge dropped before pull 16, so there’s no result. A paused test measures something else, because the reserve refills while you wait. Rest at least half an hour, then test again.")
    CriticalForceTest.VoidReason.leftApp ->
        L10n.tr("The test stopped when you left the app before pull 16, so there’s no result. Rest at least half an hour, then test again.")
}

internal fun words(failure: CriticalForceFailure): String = when (failure) {
    is CriticalForceFailure.TooFewReps -> L10n.tr("Stopped before pull 16, so there’s no result.")
    CriticalForceFailure.TooLittleData ->
        L10n.tr("The gauge’s readings had too many gaps in the last pulls to give a result. Keep the phone close to the gauge next time.")
    CriticalForceFailure.NoPull ->
        L10n.tr("No pulls were recorded. Check the gauge is zeroed and that the pull goes through it.")
}
