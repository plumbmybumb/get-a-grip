// SPDX-License-Identifier: MPL-2.0
// Original contributions Copyright 2026 Nuri Bruner.

import Foundation
import Observation

/// One hand's finished test, before it is saved.
struct CriticalForceHandResult: Equatable {
    let side: Side
    let result: CriticalForceResult
    let trace: Data
}

/// The four screens of one visit.
enum CriticalForceStage: Equatable {
    case setup
    case testing
    case result
    case ended(String)
}

/// The setup's choice, in the routine builder's words. See `CriticalForceHands`.
enum CriticalForceHandChoice: Hashable { case oneAtATime, bothHands, single }

/// **Everything one critical force visit is in the middle of**: the setup's choices, the
/// stage, the hand on the gauge, the hands already done — and the FLOW between them
/// (`start`, `arm`, `phaseChanged`, `nextHandOrFinish`).
///
/// It lived inside `CriticalForceTestView` as untested view code, which is how Back on the
/// SECOND hand came to return to setup and let the next Start or Cancel discard the first
/// hand's finished test. Android always had it in a class of its own
/// (`CriticalForceTestRequest`, with `CriticalForceVisitTests`); this mirrors that
/// structure and those names, so the one-hand-at-a-time sequence is testable with no
/// screen and the view only draws it and forwards taps.
///
/// **Observation granularity.** Every stored value here changes a handful of times per
/// test (a stage, a hand, a result). The high-frequency values — the clock, the bars —
/// live on `session`, whose leaves read them themselves; nothing here is written per
/// reading, so the screen never rebuilds at the sample rate through the visit.
@Observable
@MainActor
final class CriticalForceVisit {
    var grip: GripSpec
    var handChoice: CriticalForceHandChoice
    /// Which hand starts (one at a time), or the hand (one hand).
    var pickedSide: Side

    private(set) var stage: CriticalForceStage = .setup

    /// Set when Start was refused because the gauge was loaded; cleared on the next Start
    /// that goes through. The dock says why nothing happened.
    private(set) var loadOnGaugeKg: Double?

    /// The test for the hand on the gauge now.
    private(set) var session: CriticalForceSession

    /// Index into `hands.sides` of the hand on the gauge now.
    private(set) var handIndex = 0

    /// Between hands: the first hand is done and the next is NOT armed until its Start.
    /// Moving the gauge or block to the other hand loads it, and an armed test would take
    /// that as the first pull (Nuri, 2026-09-25). Nothing is measuring meanwhile, so
    /// leaving the app or the screen costs nothing.
    private(set) var awaitingNextHand = false
    private(set) var results: [CriticalForceHandResult] = []

    /// What happened to a hand that produced no result, said on the result screen.
    private(set) var notes: [String] = []

    /// Which hand's pulls the result screen is showing.
    var shownSide: Side = .left

    /// Counts every Start that went through (a hand armed), for the view's haptic. A Start
    /// refused under load changes nothing, so it gets no tick.
    private(set) var armings = 0

    /// A fresh test for each hand.
    @ObservationIgnored private let newSession: () -> CriticalForceSession
    @ObservationIgnored private var streamingDevice: DeviceStore?

    init(grip: GripSpec, hands: CriticalForceHands,
         newSession: @escaping () -> CriticalForceSession = { CriticalForceSession() }) {
        self.grip = grip
        switch hands {
        case .oneAtATime(let first):
            handChoice = .oneAtATime
            pickedSide = first == .right ? .right : .left
        case .bothHands:
            handChoice = .bothHands
            pickedSide = .left
        case .single(let side):
            handChoice = .single
            pickedSide = side == .right ? .right : .left
        }
        self.newSession = newSession
        session = newSession()
    }

    var hands: CriticalForceHands {
        switch handChoice {
        case .oneAtATime: .oneAtATime(first: pickedSide)
        case .bothHands: .bothHands
        case .single: .single(pickedSide)
        }
    }

    /// The hand on the gauge now.
    var side: Side { hands.sides[min(handIndex, hands.sides.count - 1)] }

    /// Measuring now: a hand's test is armed or running. Between hands nothing is.
    var isMeasuring: Bool { stage == .testing && !awaitingNextHand }

    // MARK: - Flow

    /// Start the visit on the first hand. The demo gauge pulls an all-out profile while a
    /// test runs, so demo mode sees a real-looking plateau — switched only for a Start that
    /// goes through, never for one refused under load.
    func start(device: DeviceStore) {
        guard stage == .setup, device.state.isConnected, !refusesUnderLoad(device) else { return }
        handIndex = 0
        awaitingNextHand = false
        results = []
        notes = []
        streamingDevice = device
        device.setMockProfile(.allOut)
        zeroThenStream(device)
        arm(newSession())
        stage = .testing
    }

    /// A fresh test for the hand now on the gauge. Only the reading callback moves.
    private func arm(_ next: CriticalForceSession) {
        session.end()
        session = next
        next.arm()
        streamingDevice?.onTracePoint = { [next] point in next.receive(kg: point.kg, at: point.t) }
        armings += 1
    }

    /// The screen reports every phase change of the current session here.
    func phaseChanged(_ phase: CriticalForceTest.Phase) {
        guard isMeasuring else { return }
        let hand = Self.handName(side)
        switch phase {
        case .finished:
            guard let (outcome, trace) = session.outcome() else { return }
            switch outcome {
            case .success(let result):
                results.append(CriticalForceHandResult(side: side, result: result, trace: trace))
            case .failure(let failure):
                notes.append("\(hand): \(Self.words(for: failure))")
            }
            nextHandOrFinish(canContinue: true)
        case .voided(let reason):
            // Stopping by hand, losing the gauge or leaving the app ends the VISIT: the
            // next hand would start on a gauge that is gone or a climber who said stop.
            if results.isEmpty {
                stopStream(cause: .userStopped)
                stage = .ended(Self.words(for: reason))
            } else {
                notes.append("\(hand): \(Self.words(for: reason))")
                nextHandOrFinish(canContinue: false)
            }
        default:
            break
        }
    }

    /// The gauge's link, from the screen. A drop mid-test interrupts it: void before pull
    /// 16, the end of that hand's test after. Between hands nothing is measuring, so a
    /// drop voids nothing.
    func connectionChanged(isConnected: Bool) {
        if !isConnected, isMeasuring { session.interrupt(.lostGauge) }
    }

    /// Leaving the app with no gauge that keeps it alive: the readings are about to stop,
    /// so the test in progress ends. See `BackgroundPausePolicy`.
    func leftApp() {
        if isMeasuring { session.interrupt(.leftApp) }
    }

    private func nextHandOrFinish(canContinue: Bool) {
        if canContinue, handIndex + 1 < hands.sides.count, streamingDevice != nil {
            handIndex += 1
            // Wait for the climber: the next hand starts from its own Start tap. The
            // readings stop reaching any test until then.
            session.end()
            streamingDevice?.onTracePoint = nil
            awaitingNextHand = true
            return
        }
        showResults()
    }

    /// The next hand's Start. A fresh stream for the fresh hand: harmless on a gauge (the
    /// same re-kick the runner sends), and the demo gauge replays its test from the start.
    func startNextHand(device: DeviceStore) {
        guard awaitingNextHand, device.state.isConnected else { return }
        // Checked on the live reading BEFORE the stream stops: afterwards the load is
        // unknown, and a tare must not be guessed at.
        if refusesUnderLoad(device) { return }
        awaitingNextHand = false
        streamingDevice = device
        if device.isStreaming { device.stopStreaming(cause: .measurementComplete) }
        zeroThenStream(device)
        arm(newSession())
    }

    /// **Every test starts on a zeroed gauge**, as a routine does (`RunnerSession.startIfReady`).
    /// Refused while a live reading shows a hand still on it: taring under load would shift
    /// every reading of the test by that load. The threshold is `TarePolicy`'s confirm one.
    private func refusesUnderLoad(_ device: DeviceStore) -> Bool {
        guard device.isReadingLive, TarePolicy.shouldConfirm(readingKg: device.currentKg) else { return false }
        loadOnGaugeKg = device.currentKg
        return true
    }

    /// Tare FIRST, then start the stream: the vendor's order; the reverse killed a fresh
    /// stream on hardware.
    private func zeroThenStream(_ device: DeviceStore) {
        loadOnGaugeKg = nil
        device.tare()
        device.resetPeak()
        device.startStreaming(cause: .manualMeasurement)
    }

    /// "Finish with the first hand only", from between the hands.
    func finishEarlyBetweenHands() {
        guard stage == .testing, handIndex > 0 else { return }
        awaitingNextHand = false
        showResults()
    }

    private func showResults() {
        stopStream(cause: .measurementComplete)
        if let first = results.first {
            shownSide = first.side
            stage = .result
        } else {
            stage = .ended(notes.isEmpty ? Self.words(for: .tooFewReps) : notes.joined(separator: "\n\n"))
        }
    }

    /// Back, from an armed hand that has not pulled yet.
    ///
    /// On the FIRST hand nothing has been measured, so leaving is free and it is setup
    /// again. On a later hand the visit already holds a finished hand, and setup's Start and
    /// Cancel both begin again from nothing: Back there must never discard it. It returns
    /// to between the hands, where the next hand's Start and "Finish with … only" wait.
    func back() {
        guard isMeasuring, session.phase == .armed else { return }
        if handIndex > 0 {
            session.end()
            streamingDevice?.onTracePoint = nil
            awaitingNextHand = true
            return
        }
        stopStream(cause: .userStopped)
        stage = .setup
    }

    /// Everything the test switched on, off. Idempotent.
    func stopStream(cause: StreamStopCause) {
        if let device = streamingDevice {
            device.onTracePoint = nil
            if device.isStreaming { device.stopStreaming(cause: cause) }
            device.setMockProfile(DeviceStore.mockProfileRequestedAtLaunch)
        }
        streamingDevice = nil
        session.end()
    }

    /// Leaving the screen: a test in progress is interrupted (voided before pull 16), then
    /// the stream stops.
    func teardown() {
        if isMeasuring { session.interrupt(.leftApp) }
        stopStream(cause: .screenClosed)
    }

    #if DEBUG
    /// Headless: the result screen without four minutes per hand.
    func showPreviewResults(_ previews: [CriticalForceHandResult]) {
        guard stage == .setup, let first = previews.first else { return }
        results = previews
        shownSide = first.side
        stage = .result
    }
    #endif

    // MARK: - Words

    /// "Both hands", or the hand's own name.
    static func handName(_ side: Side) -> String {
        side == .both ? String(localized: "Both hands") : side.name
    }

    static func words(for reason: CriticalForceTest.VoidReason) -> String {
        switch reason {
        case .tooFewReps:
            String(localized: "Stopped before pull 16, so there's no result. Rest at least 30 minutes before retesting.")
        case .lostGauge:
            String(localized: "The gauge disconnected before pull 16, so there's no result. Rest at least 30 minutes before retesting.")
        case .leftApp:
            String(localized: "You left the app before pull 16, so there's no result. Rest at least 30 minutes before retesting.")
        }
    }

    static func words(for failure: CriticalForceFailure) -> String {
        switch failure {
        case .tooFewReps:
            String(localized: "Stopped before pull 16, so there's no result.")
        case .tooLittleData:
            String(localized: "Too many gaps in the gauge's readings to give a result. Keep the phone closer next time.")
        case .noPull:
            String(localized: "No pulls recorded. Tare the gauge and pull through it.")
        }
    }
}
