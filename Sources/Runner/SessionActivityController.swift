// SPDX-License-Identifier: MPL-2.0
// Original contributions Copyright 2026 Nuri Bruner.

import ActivityKit
import Foundation

/// Starts, updates and ends the session's Live Activity.
///
/// **It pushes on STATE CHANGES, never on the clock.** The widget owns its own countdown
/// (`Text(timerInterval:)`), so the only reason to send anything is that something the
/// widget cannot derive has changed: the phase, the hand, the grip, the rep. That is a
/// handful of updates a minute against ActivityKit's coalescing budget, instead of the
/// ~80 a second the gauge produces — and it is why a twenty-minute session works at all.
///
/// **The `Activity` handle is deliberately never stored.** `ActivityKit.Activity` is a
/// plain class — `Identifiable`, NOT `Sendable` — so holding one on an actor and then
/// awaiting a method on it is a send across isolation domains, which Swift 6 rejects
/// outright. Fetching it from `Activity.activities` inside a `nonisolated` async function
/// keeps the handle's whole life in one context. There is only ever one of ours running,
/// so the lookup is a one-element array.
///
/// Everything here is best-effort by design: a Live Activity that cannot start
/// (permission off, budget spent) must never disturb a workout.
@MainActor
final class SessionActivityController {
    private(set) var isRunning = false
    /// The last state pushed. An update that would change nothing is dropped rather than
    /// spent — the runner republishes its snapshot on every tick, and forwarding those
    /// verbatim would burn the budget on identical frames.
    private var lastPushed: SessionActivity.ContentState?

    func start(routineName: String, plannedReps: Int, setCount: Int,
               state: SessionActivity.ContentState) {
        guard !isRunning, ActivityAuthorizationInfo().areActivitiesEnabled else { return }
        let attributes = SessionActivity(routineName: routineName,
                                         plannedReps: plannedReps,
                                         setCount: setCount)
        // The returned handle is discarded on purpose — see the type's note.
        guard (try? Activity.request(attributes: attributes,
                                     content: .init(state: state, staleDate: nil))) != nil
        else { return }
        isRunning = true
        lastPushed = state
    }

    func update(_ state: SessionActivity.ContentState) async {
        guard isRunning, state != lastPushed else { return }
        lastPushed = state
        await Self.pushToLiveActivities(state)
    }

    func end() async {
        guard isRunning else { return }
        isRunning = false
        lastPushed = nil
        await Self.endLiveActivities()
    }

    // MARK: - Off the actor
    //
    // `nonisolated` so the non-Sendable `Activity` is fetched, used and dropped inside a
    // single isolation domain. Nothing crosses; only the value type comes in.

    nonisolated private static func pushToLiveActivities(
        _ state: SessionActivity.ContentState
    ) async {
        for activity in Activity<SessionActivity>.activities {
            await activity.update(.init(state: state, staleDate: nil))
        }
    }

    /// `.immediate` because the session is over the moment it is over: a lingering card
    /// on the lock screen that still says "Pull" is worse than none at all.
    nonisolated private static func endLiveActivities() async {
        for activity in Activity<SessionActivity>.activities {
            await activity.end(nil, dismissalPolicy: .immediate)
        }
    }
}
