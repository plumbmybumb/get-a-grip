// SPDX-License-Identifier: MPL-2.0
// Original contributions Copyright 2026 Nuri Bruner.

import Foundation
import HealthKit
import Observation

/// Keeps the app — and with it the Bluetooth stream — running while the wrist is down.
///
/// watchOS suspends an app the moment the screen sleeps, and a suspended app receives no
/// force samples: the rep would stall silently at whatever it had accrued, which is the
/// exact failure the phone's background rule exists to prevent. A running
/// `HKWorkoutSession` is the one thing that keeps a watch app alive through a
/// twenty-minute session, so every session runs inside one. It is also honest: a
/// hangboard session IS a workout, and it lands in Fitness as one.
///
/// Best-effort throughout. Health access refused, Health unavailable, a session that
/// will not start — the runner still runs, and `state` says why the screen has to stay
/// awake. A cue that cannot be played is not a reason for a set to stop, and neither is
/// a workout that cannot be recorded.
@Observable @MainActor
final class WorkoutKeeper {
    enum State: Equatable {
        case idle
        /// Asked for — authorization or the session's own start is still in flight. Not
        /// yet keeping anything alive, which is why the runner treats it like `.idle`.
        case starting
        case running
        /// No Health data on this device at all.
        case unavailable
        /// Sharing workouts was refused, so nothing keeps the app awake with the wrist
        /// down — the screen has to.
        case denied
        /// It would not start, or the SYSTEM ended it under us (another workout app took
        /// over, Health failed) — either way nothing is keeping the app alive any more.
        case failed
    }

    private(set) var state: State = .idle

    @ObservationIgnored private let store = HKHealthStore()
    @ObservationIgnored private var session: HKWorkoutSession?
    @ObservationIgnored private var builder: HKLiveWorkoutBuilder?
    /// Whether a workout is WANTED right now — the caller's intent, as opposed to where
    /// the asynchronous start has got to.
    ///
    /// The start is two callbacks deep (authorization, then the builder), and the runner
    /// can end in between: a session aborted during the Health sheet, a screen dismissed
    /// before the first frame. Checked at each hop, this is what stops a callback landing
    /// after `end()` from starting a workout nobody will ever end — a phantom that keeps
    /// the watch awake and lands in Fitness as an hour of "strength training".
    @ObservationIgnored private var wanted = false
    /// Hears the system end a session we did not. Held strongly: `HKWorkoutSession`
    /// keeps its delegate weak. One per session, each with its own token.
    @ObservationIgnored private var sessionDelegate: WorkoutSessionDelegate?
    /// Which session is current, by a token rather than by object identity: an old
    /// session's late callback must never match a new session that happens to have been
    /// allocated at the same address.
    @ObservationIgnored private var sessionToken: UUID?

    /// Ask, then start. The ask is contextual — the first Start on the wrist is the
    /// moment a workout permission means something — and the system remembers the answer.
    ///
    /// Idempotent while a start is in flight or a workout is running: a second `begin()`
    /// used to request authorization again and start a SECOND workout session.
    func begin() {
        wanted = true
        guard state != .running, state != .starting else { return }
        guard HKHealthStore.isHealthDataAvailable() else {
            state = .unavailable
            return
        }
        state = .starting
        // Completion handlers rather than the async forms, deliberately: the builder and
        // the session are not Sendable, and awaiting a nonisolated async method on them
        // from the main actor is a send Swift 6 rejects. Callbacks hand back only value
        // types, which hop home safely.
        store.requestAuthorization(toShare: [.workoutType()], read: []) { [weak self] _, _ in
            Task { @MainActor in self?.startSession() }
        }
    }

    private func startSession() {
        // Ended while the Health sheet was up — or a start already won the race.
        guard wanted, session == nil else {
            if !wanted, state == .starting { state = .idle }
            return
        }
        guard store.authorizationStatus(for: .workoutType()) == .sharingAuthorized else {
            state = .denied
            return
        }
        let configuration = HKWorkoutConfiguration()
        // Strength, not climbing: a hangboard is finger strength work on a bench, and
        // Fitness's climbing type implies a wall and a rope this session never sees.
        configuration.activityType = .functionalStrengthTraining
        configuration.locationType = .indoor
        do {
            let session = try HKWorkoutSession(healthStore: store, configuration: configuration)
            let builder = session.associatedWorkoutBuilder()
            builder.dataSource = HKLiveWorkoutDataSource(healthStore: store,
                                                         workoutConfiguration: configuration)
            let token = UUID()
            let delegate = WorkoutSessionDelegate(token: token) { [weak self] token in
                Task { @MainActor in self?.systemEnded(token: token) }
            }
            session.delegate = delegate
            self.session = session
            self.builder = builder
            self.sessionDelegate = delegate
            self.sessionToken = token
            session.startActivity(with: .now)
            builder.beginCollection(withStart: .now) { [weak self] success, _ in
                Task { @MainActor in
                    // Only the session this callback belongs to may set the state: one
                    // ended in the meantime has already been answered by `end()`.
                    guard let self, self.sessionToken == token else { return }
                    self.state = success ? .running : .failed
                }
            }
        } catch {
            state = .failed
        }
    }

    /// End the workout and record it. Idempotent, and never awaited by the caller — a
    /// session teardown must not wait on Health.
    func end() {
        wanted = false
        state = .idle
        finish()
    }

    /// The system stopped a session we still wanted. Record what there is and say so:
    /// `state` leaving `.running` is what makes the runner pause when the wrist drops.
    private func systemEnded(token: UUID) {
        guard sessionToken == token else { return }
        finish()
        state = .failed
    }

    private func finish() {
        guard let session, let builder else { return }
        self.session = nil
        self.builder = nil
        sessionToken = nil
        sessionDelegate = nil
        session.end()
        builder.endCollection(withEnd: .now) { _, _ in
            builder.finishWorkout { _, _ in }
        }
    }
}

/// The session's delegate, apart from the keeper so the keeper stays a plain
/// `@Observable` class rather than an `NSObject`. HealthKit calls it on a queue of its
/// own; it hands back only its session's TOKEN — a value, where the session itself is
/// not Sendable — and the keeper compares it with the current one.
private final class WorkoutSessionDelegate: NSObject, HKWorkoutSessionDelegate, Sendable {
    private let token: UUID
    private let onSystemEnd: @Sendable (UUID) -> Void

    init(token: UUID, onSystemEnd: @escaping @Sendable (UUID) -> Void) {
        self.token = token
        self.onSystemEnd = onSystemEnd
    }

    func workoutSession(_ workoutSession: HKWorkoutSession,
                        didChangeTo toState: HKWorkoutSessionState,
                        from fromState: HKWorkoutSessionState, date: Date) {
        // `.stopped` and `.ended` both mean nothing is keeping the app alive. Our own
        // `end()` arrives here too, and is ignored by token: it cleared the token before
        // calling `end()` on the session.
        guard toState == .stopped || toState == .ended else { return }
        onSystemEnd(token)
    }

    func workoutSession(_ workoutSession: HKWorkoutSession, didFailWithError error: any Error) {
        onSystemEnd(token)
    }
}
