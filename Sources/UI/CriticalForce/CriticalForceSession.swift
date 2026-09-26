// SPDX-License-Identifier: MPL-2.0
// Original contributions Copyright 2026 Nuri Bruner.

import Foundation
import Observation

/// Drives a `CriticalForceTest` from the gauge and the clock, and publishes only what the
/// screen draws.
///
/// The test is `@ObservationIgnored` and every published value is written only when it
/// CHANGES. Readings arrive about 80 times a second, and Observation fires on every set,
/// so an unguarded mirror would rebuild the screen at the sample rate (the lesson of
/// `MaxMeasurement` and `RunnerSnapshot`).
@Observable
@MainActor
final class CriticalForceSession {
    private(set) var phase: CriticalForceTest.Phase = .armed
    /// Whole seconds left in the current window: a clock, so it rolls.
    private(set) var secondsLeft = 7
    /// 1-based, for "Pull 9 of 24".
    private(set) var pullNumber = 1
    /// One entry per pull started: locked once its bell rings, live while it runs.
    private(set) var repMeans: [Double?] = []
    private(set) var canFinishEarly = false

    @ObservationIgnored private(set) var test = CriticalForceTest()
    @ObservationIgnored private var ticker: Task<Void, Never>?
    @ObservationIgnored private let cues: any RunnerCuePlaying
    @ObservationIgnored private var cuesRunning = false
    /// Wall time on the playback clock's epoch — see `CriticalForceTest`. A seam for
    /// tests, which run four minutes of test in no time.
    @ObservationIgnored private let now: @MainActor () -> TimeInterval

    init(cues: (any RunnerCuePlaying)? = nil,
         now: @escaping @MainActor () -> TimeInterval = { Date().timeIntervalSinceReferenceDate }) {
        self.cues = cues ?? CuePlayer()
        self.now = now
    }

    var proto: CriticalForceProtocol { test.proto }

    /// Arm: from now on the first pull over `startKg` starts rep 1.
    func arm() {
        test = CriticalForceTest()
        publish()
        // A runloop turn later, like the runner: `AVAudioEngine.start()` and the haptic
        // engine's start put tens of milliseconds between the Start tap and the screen.
        // Nothing sounds until the first pull.
        if !cuesRunning {
            cuesRunning = true
            Task { @MainActor [weak self] in
                await Task.yield()
                guard let self, self.cuesRunning else { return }
                self.cues.begin()
            }
        }
        ticker?.cancel()
        ticker = Task { [weak self] in
            while !Task.isCancelled {
                // A released session ends the loop rather than sleeping on forever. Bound
                // only for the tick, so the sleep holds no reference to it.
                if let self { self.tick() } else { return }
                try? await Task.sleep(for: .milliseconds(50))
            }
        }
    }

    /// ~80 readings a second. Only what a single reading can change is republished here
    /// (the phase, when the first pull arms the test); the bars and the clock follow on
    /// the 20 Hz tick, which is as often as either can visibly move.
    func receive(kg: Double, at t: TimeInterval) {
        play(test.sample(kg: kg, at: t))
        publishPhase()
    }

    /// Hold-to-stop. Keeps a result past `minRepsForResult`, voids before.
    func stop() {
        play(test.stop(now: now()))
        publish()
    }

    func interrupt(_ reason: CriticalForceTest.VoidReason) {
        play(test.interrupt(reason, now: now()))
        publish()
    }

    /// Everything off. Idempotent; called on every way out of the screen.
    func end() {
        ticker?.cancel()
        ticker = nil
        if cuesRunning { cues.end(); cuesRunning = false }
    }

    /// The finished test's outcome and its trace, encoded for storage.
    func outcome() -> (Result<CriticalForceResult, CriticalForceFailure>, Data)? {
        guard let result = test.result() else { return nil }
        return (result, CriticalForceTrace.encode(test.points))
    }

    // MARK: -

    /// One metronome step. Internal for tests; the ticker calls it every 50 ms.
    func tick() {
        play(test.tick(now: now()))
        publish()
        switch test.phase {
        case .finished, .voided:
            ticker?.cancel()
            ticker = nil
        default: break
        }
    }

    /// The routine runner's cue vocabulary, so the test sounds like the rest of the app:
    /// the go tone on the pull, the rep-complete tone on the bell, the rest ticks between.
    private func play(_ fired: [CriticalForceTest.Cue]) {
        for cue in fired {
            switch cue {
            case .pull: cues.play(.repStarted)
            case .letGo: cues.play(.repEnded(completed: true))
            case .countdown(let n): cues.play(.restTick(secondsRemaining: n))
            case .finished: cues.play(.sessionCompleted)
            case .voided: cues.play(.connectionLost)
            }
        }
    }

    private func publishPhase() {
        let p = test.phase
        if phase != p { phase = p }
    }

    private func publish() {
        publishPhase()
        let p = test.phase
        let left = Int(test.remaining(at: now()).rounded(.up))
        if secondsLeft != left { secondsLeft = left }
        let pull: Int = switch p {
        case .pulling(let rep): rep + 1
        case .resting(let rep): rep + 2
        case .armed: 1
        default: test.repsRun
        }
        if pullNumber != pull { pullNumber = pull }
        // Rounded to a tenth before comparing, so the live bar redraws when the average
        // visibly moves, not on every one of 80 readings a second.
        let means = test.displayMeans().map { $0.map { ($0 * 10).rounded() / 10 } }
        if repMeans != means { repMeans = means }
        let early = test.canFinishEarly
        if canFinishEarly != early { canFinishEarly = early }
    }
}
