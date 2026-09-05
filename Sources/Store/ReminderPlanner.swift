// SPDX-License-Identifier: MPL-2.0
// Original contributions Copyright 2026 Nuri Bruner.

import Foundation
import UserNotifications

/// Wipe-and-reschedule local-notification planning for the daily ritual.
///
/// Deterministic `doigt.routine.<uuid>.<slot>` identifiers plus a full replan on every
/// change make scheduling idempotent — there is no incremental state to corrupt, and
/// re-adding an identifier replaces the request in place, so editing 08:00 → 09:00
/// moves one reminder rather than accumulating two.
///
/// M2 schedules REPEATING daily triggers, which means a reminder fires even on a day
/// already trained. That is deferred deliberately: suppression needs same-day
/// re-planning against real `WorkoutLog` writes, which do not exist until M3, and
/// nothing in M2 can log a session — so the gap is unobservable. When it lands it is a
/// body-only edit here (dated non-repeating requests, re-planned daily); the
/// identifier scheme does not change.
@MainActor
enum ReminderPlanner {
    /// One routine's reminder settings, flattened to Sendable value data so the whole
    /// plan can be computed off the main actor without touching a `@Model` object.
    struct RoutinePlanInput: Hashable, Sendable {
        let id: UUID
        let name: String
        let reminders: [ReminderTime]
        let enabled: Bool
        /// How many sessions today still owes. Zero means the day is already met and
        /// every one of this routine's remaining slots is suppressed.
        ///
        /// The whole point of the ritual is that the app stops nagging once you have
        /// done the thing. A reminder that fires after your second session of the day
        /// is the app failing to notice you succeeded, and it is exactly the kind of
        /// thing that gets notifications turned off for good.
        var outstandingToday: Int = 1
    }

    /// A request, fully resolved but not yet handed to the system — which is what makes
    /// the planning testable without a notification center.
    struct PlannedReminder: Hashable, Sendable {
        let identifier: String
        let title: String
        let body: String
        let components: DateComponents
    }

    /// The namespace we own. Everything with this prefix is ours to delete on a
    /// replan; anything without it belongs to another feature and is left alone.
    /// `nonisolated` so the pure half of this enum stays callable from anywhere.
    nonisolated static let identifierPrefix = "doigt.routine."

    /// Content-keyed on both halves: the routine's UUID survives an undo-delete (which
    /// restores the original id), and `slot` is derived from the TIME, so a slot moved
    /// from 08:00 to 09:00 replaces its own request instead of leaving an orphan
    /// firing at the old hour forever.
    nonisolated static func identifier(routine: UUID, slot: ReminderTime) -> String {
        "\(identifierPrefix)\(routine.uuidString).\(slot.slot)"
    }

    /// The whole plan, as a pure function of the routines — no clock, no notification
    /// center, no authorization. Sorted and deduped, and a routine with reminders
    /// switched off contributes nothing rather than contributing a disabled request.
    nonisolated static func requests(for routines: [RoutinePlanInput]) -> [PlannedReminder] {
        var planned: [PlannedReminder] = []
        // Two routines cannot share an identifier (the UUID is in it), but a caller
        // that passes the same routine twice must not produce a duplicate request.
        var claimed: Set<String> = []

        for routine in routines where routine.enabled {
            // Suppress from the FRONT of the day. Having trained once, the morning slot
            // is the one you have satisfied; the evening one is still owed. Dropping the
            // last slot instead would silence the reminder you still need.
            let sorted = Set(routine.reminders).sorted()
            let suppressed = max(0, sorted.count - max(0, routine.outstandingToday))
            for slot in sorted.dropFirst(suppressed) {
                let id = identifier(routine: routine.id, slot: slot)
                guard claimed.insert(id).inserted else { continue }
                planned.append(PlannedReminder(
                    identifier: id,
                    title: routine.name,
                    body: String(localized: "Time for a session."),
                    components: slot.dateComponents
                ))
            }
        }
        return planned
    }

    /// Replans are fully serialized: each new one cancels its predecessor AND awaits it
    /// before touching the notification center, so a superseded run's in-flight adds can
    /// never land after the successor's wipe.
    private static var inflight: Task<Void, Never>?

    static func replan(_ routines: [RoutinePlanInput]) async {
        let predecessor = inflight
        predecessor?.cancel()
        // DETACHED, deliberately: this enum is @MainActor, so a plain `Task {}` inherits
        // the main actor — which is what put the equivalent computation in the sibling
        // app on the main thread after every save, stepper tick and foreground (measured
        // 70–200 ms), and it was felt as a hitch behind the sheet's dismiss animation.
        // Everything the worker touches is Sendable value data or the thread-safe
        // UNUserNotificationCenter.
        let task = Task.detached(priority: .utility) {
            _ = await predecessor?.value
            await run(routines)
        }
        inflight = task
        await task.value
    }

    private nonisolated static func run(_ routines: [RoutinePlanInput]) async {
        #if DEBUG
        assert(!Thread.isMainThread, "reminder planning must stay off the main thread")
        let computeStart = ContinuousClock.now
        #endif
        let center = UNUserNotificationCenter.current()

        // Note what is already scheduled but DON'T wipe it yet. Wiping first leaves a
        // window — however short — with zero reminders, and process death or a thrown
        // add inside that window makes it permanent. Adds replace in place, so the new
        // plan goes in first and only then is the remainder dropped.
        let pending = await center.pendingNotificationRequests()
        guard !Task.isCancelled else { return }
        let ours = Set(pending.map(\.identifier).filter { $0.hasPrefix(identifierPrefix) })

        let planned = requests(for: routines)
        guard !planned.isEmpty else {
            // Every routine's reminders are off (or there are no routines): the correct
            // plan is genuinely empty, and this is the one path that may clear without
            // checking authorization.
            center.removePendingNotificationRequests(withIdentifiers: Array(ours))
            return
        }

        let status = await center.notificationSettings().authorizationStatus
        guard status == .authorized || status == .provisional, !Task.isCancelled else { return }

        var scheduled: Set<String> = []
        for item in planned {
            guard !Task.isCancelled else { return }
            let content = UNMutableNotificationContent()
            content.title = item.title
            content.body = item.body
            content.sound = .default
            // `.active`, not `.timeSensitive`: this is a habit nudge, and a training
            // app that claims the right to pierce Focus for a routine reminder is the
            // kind of app people turn notifications off for entirely.
            content.interruptionLevel = .active

            let trigger = UNCalendarNotificationTrigger(dateMatching: item.components, repeats: true)
            do {
                try await center.add(UNNotificationRequest(identifier: item.identifier,
                                                          content: content, trigger: trigger))
                scheduled.insert(item.identifier)
            } catch {
                // Leave it unclaimed so the next replan retries it — and so the wipe
                // below does not remove a request we failed to replace.
                continue
            }
        }

        #if DEBUG
        if ProcessInfo.processInfo.arguments.contains("-timings") {
            print("[timings] reminder plan \(ContinuousClock.now - computeStart) off-main (\(planned.count) items)")
        }
        #endif

        // Now — and only now — drop what the new plan no longer covers.
        guard !Task.isCancelled else { return }
        center.removePendingNotificationRequests(
            withIdentifiers: Array(ours.subtracting(scheduled)))
    }

    /// Contextual, one-shot permission ask: the first Save of a routine WITH reminders
    /// on is the moment notifications become meaningful. Never at cold launch, where
    /// the alert arrives before the user knows what the app does.
    static func requestAuthorizationIfNeeded(settings: SettingsStore) async {
        guard !settings.didAskNotificationPermission else { return }
        // Set BEFORE the await, not after: two saves in quick succession would both see
        // `false` and stack two OS alerts, and the second one is the one that reads as
        // a bug.
        settings.didAskNotificationPermission = true
        _ = try? await UNUserNotificationCenter.current()
            .requestAuthorization(options: [.alert, .sound, .badge])
    }
}
