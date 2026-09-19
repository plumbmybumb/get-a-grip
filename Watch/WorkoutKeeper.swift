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
        case running
        /// No Health data on this device at all.
        case unavailable
        /// Sharing workouts was refused, so nothing keeps the app awake with the wrist
        /// down — the screen has to.
        case denied
        case failed
    }

    private(set) var state: State = .idle

    @ObservationIgnored private let store = HKHealthStore()
    @ObservationIgnored private var session: HKWorkoutSession?
    @ObservationIgnored private var builder: HKLiveWorkoutBuilder?

    /// Ask, then start. The ask is contextual — the first Start on the wrist is the
    /// moment a workout permission means something — and the system remembers the answer.
    func begin() {
        guard state != .running else { return }
        guard HKHealthStore.isHealthDataAvailable() else {
            state = .unavailable
            return
        }
        // Completion handlers rather than the async forms, deliberately: the builder and
        // the session are not Sendable, and awaiting a nonisolated async method on them
        // from the main actor is a send Swift 6 rejects. Callbacks hand back only value
        // types, which hop home safely.
        store.requestAuthorization(toShare: [.workoutType()], read: []) { [weak self] _, _ in
            Task { @MainActor in self?.startSession() }
        }
    }

    private func startSession() {
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
            session.startActivity(with: .now)
            builder.beginCollection(withStart: .now) { [weak self] success, _ in
                Task { @MainActor in
                    guard let self else { return }
                    self.state = success ? .running : .failed
                }
            }
            self.session = session
            self.builder = builder
        } catch {
            state = .failed
        }
    }

    /// End the workout and record it. Idempotent, and never awaited by the caller — a
    /// session teardown must not wait on Health.
    func end() {
        guard let session, let builder else {
            state = .idle
            return
        }
        self.session = nil
        self.builder = nil
        state = .idle
        session.end()
        builder.endCollection(withEnd: .now) { _, _ in
            builder.finishWorkout { _, _ in }
        }
    }
}
