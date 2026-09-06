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
/// Satisfied slots are suppressed for TODAY only. iOS has no start date for a daily
/// repeating calendar trigger, so we fill its available 64-request budget with dated
/// one-shot requests, earliest first. Normal app activity replenishes this finite
/// horizon; opening the app is required before the queued horizon runs out.
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
        var suppressToday: Bool = false
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
            for (index, slot) in sorted.enumerated() {
                let id = identifier(routine: routine.id, slot: slot)
                guard claimed.insert(id).inserted else { continue }
                planned.append(PlannedReminder(
                    identifier: id,
                    title: routine.name,
                    body: String(localized: "Time for a session."),
                    components: slot.dateComponents,
                    suppressToday: index < suppressed
                ))
            }
        }
        return planned
    }

    /// Resolve the slot plan to future calendar dates. Every enabled slot returns
    /// tomorrow even when today's target is met; deleting its repeating request used
    /// to silence every future day until the app happened to replan again.
    nonisolated static func scheduledRequests(for routines: [RoutinePlanInput],
                                              now: Date = .now,
                                              calendar: Calendar = .current,
                                              limit: Int = 64) -> [PlannedReminder] {
        let slots = requests(for: routines)
        guard !slots.isEmpty, limit > 0 else { return [] }
        let budget = min(64, limit)
        let today = calendar.startOfDay(for: now)
        var result: [PlannedReminder] = []
        for offset in 0...budget {
            guard let day = calendar.date(byAdding: .day, value: offset, to: today) else { continue }
            var candidates: [(Date, PlannedReminder)] = []
            for slot in slots where offset > 0 || !slot.suppressToday {
                guard let hour = slot.components.hour, let minute = slot.components.minute,
                      let fire = calendar.date(bySettingHour: hour, minute: minute, second: 0,
                                               of: day), fire > now,
                      calendar.isDate(fire, inSameDayAs: day) else { continue }
                let components = calendar.dateComponents([.year, .month, .day, .hour, .minute, .second],
                                                         from: fire)
                let dated = PlannedReminder(
                    identifier: "\(slot.identifier).d\(DayStamp(date: day, calendar: calendar).raw)",
                    title: slot.title, body: slot.body, components: components)
                candidates.append((fire, dated))
            }
            candidates.sort {
                $0.0 == $1.0 ? $0.1.identifier < $1.1.identifier : $0.0 < $1.0
            }
            result.append(contentsOf: candidates.prefix(budget - result.count).map { $0.1 })
            if result.count == budget { break }
        }
        return result
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

        // Enumerate first so unrelated notifications retain their budget. Equivalent
        // dated requests are replaced in place; only obsolete owned IDs are retired.
        let pending = await center.pendingNotificationRequests()
        guard !Task.isCancelled else { return }
        let ours = Set(pending.map(\.identifier).filter { $0.hasPrefix(identifierPrefix) })

        guard !requests(for: routines).isEmpty else {
            // Every routine's reminders are off (or there are no routines): the correct
            // plan is genuinely empty, and this is the one path that may clear without
            // checking authorization.
            center.removePendingNotificationRequests(withIdentifiers: Array(ours))
            return
        }

        let status = await center.notificationSettings().authorizationStatus
        guard status == .authorized || status == .provisional, !Task.isCancelled else { return }

        // Other features retain their requests and their share of the system budget.
        let available = max(0, 64 - (pending.count - ours.count))
        let planned = scheduledRequests(for: routines, limit: available)
        guard !planned.isEmpty else {
            // Even with no free budget, an obsolete repeater must not keep nagging
            // today after completion. Requests owned by other features stay untouched.
            center.removePendingNotificationRequests(withIdentifiers: Array(ours))
            return
        }
        let wanted = Set(planned.map(\.identifier))
        // Retire only obsolete requests before adding: otherwise the migration from
        // repeaters, or a refreshed full horizon, can temporarily exceed iOS's budget.
        center.removePendingNotificationRequests(withIdentifiers: Array(ours.subtracting(wanted)))
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

            let trigger = UNCalendarNotificationTrigger(dateMatching: item.components, repeats: false)
            do {
                try await center.add(UNNotificationRequest(identifier: item.identifier,
                                                          content: content, trigger: trigger))
            } catch {
                // Existing requests with this dated identifier remain installed if a
                // replacement fails; the next normal replan retries the missing add.
                continue
            }
        }

        #if DEBUG
        if ProcessInfo.processInfo.arguments.contains("-timings") {
            print("[timings] reminder plan \(ContinuousClock.now - computeStart) off-main (\(planned.count) items)")
        }
        #endif

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
