// SPDX-License-Identifier: MPL-2.0
// Original contributions Copyright 2026 Nuri Bruner.

#if canImport(ActivityKit)
import ActivityKit
import Foundation

/// Starts, updates and ends the session's Live Activity.
///
/// **It pushes on STATE CHANGES, never on the clock.** The widget owns its countdown
/// (`Text(timerInterval:)`), so only what it cannot derive is sent — phase, hand, grip,
/// rep: a handful of updates a minute against ActivityKit's budget.
///
/// **The `Activity` handle is never stored.** `ActivityKit.Activity` is NOT `Sendable`,
/// so awaiting a method on a stored one is a send Swift 6 rejects. Fetching it from
/// `Activity.activities` inside a `nonisolated` async function keeps its whole life in
/// one context. Each controller owns only its activity ID, so a prior session's delayed
/// cleanup cannot end a newer card.
///
/// Everything here is best-effort by design: a Live Activity that cannot start
/// (permission off, budget spent) must never disturb a workout.
@MainActor
final class SessionActivityController {
    private(set) var isRunning = false
    private var activityID: String?
    /// The last state pushed; an update that would change nothing is dropped.
    private var lastPushed: SessionActivity.ContentState?

    func start(routineName: String, plannedReps: Int, setCount: Int,
               state: SessionActivity.ContentState) {
        guard !isRunning, ActivityAuthorizationInfo().areActivitiesEnabled else { return }
        Self.clearOrphanedActivities()
        let attributes = SessionActivity(routineName: routineName,
                                         plannedReps: plannedReps,
                                         setCount: setCount)
        // A stale date on EVERY request and update — see `ContentState.staleDate` — or a
        // card whose app was killed reads "Pull" on the lock screen for hours.
        // Keep only the Sendable ID, never a handle across actor boundaries.
        guard let activity = try? Activity.request(attributes: attributes,
                                                   content: .init(state: state,
                                                                  staleDate: state.staleDate()))
        else { return }
        activityID = activity.id
        isRunning = true
        lastPushed = state
    }

    func update(_ state: SessionActivity.ContentState) async {
        guard isRunning, let activityID, state != lastPushed else { return }
        lastPushed = state
        await Self.pushToLiveActivity(state, id: activityID)
    }

    func end() async {
        guard let activityID else { return }
        isRunning = false
        self.activityID = nil
        lastPushed = nil
        await Self.endLiveActivities(ids: [activityID])
    }

    /// Called at process launch too: sessions do not resume after a killed process.
    /// Snapshot IDs before the task starts, so cleanup cannot end a new session's card.
    static func clearOrphanedActivities() {
        let ids = Set(Activity<SessionActivity>.activities.map(\.id))
        guard !ids.isEmpty else { return }
        Task { await endLiveActivities(ids: ids) }
    }

    // MARK: - Off the actor
    //
    // `nonisolated` so the non-Sendable `Activity` is fetched, used and dropped inside a
    // single isolation domain. Nothing crosses; only the value type comes in.

    nonisolated private static func pushToLiveActivity(
        _ state: SessionActivity.ContentState, id: String
    ) async {
        let staleDate = state.staleDate()
        for activity in Activity<SessionActivity>.activities where activity.id == id {
            await activity.update(.init(state: state, staleDate: staleDate))
        }
    }

    /// `.immediate` because the session is over the moment it is over: a lingering card
    /// on the lock screen that still says "Pull" is worse than none at all.
    nonisolated private static func endLiveActivities(ids: Set<String>) async {
        for activity in Activity<SessionActivity>.activities where ids.contains(activity.id) {
            await activity.end(nil, dismissalPolicy: .immediate)
        }
    }
}
#endif
