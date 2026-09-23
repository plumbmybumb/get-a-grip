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
/// Satisfied slots are suppressed for TODAY only — the TRAINING day, which turns at
/// `DayStamp.rolloverHour`, because that is the day `outstandingToday` was counted for.
/// iOS has no start date for a daily repeating calendar trigger, so we fill its
/// available 64-request budget with dated one-shot requests, earliest first. Normal app
/// activity replenishes this finite horizon; opening the app is required before the
/// queued horizon runs out.
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
        /// A reminder that fires after the day's sessions is the app failing to notice
        /// you succeeded — the thing that gets notifications turned off for good.
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

    /// Content-keyed on both halves: the routine's UUID survives an undo-delete, and
    /// `slot` is derived from the TIME, so a moved slot replaces its own request
    /// rather than leaving an orphan firing at the old hour.
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
            // Suppress from the FRONT of the TRAINING day: having trained once, the
            // morning slot is satisfied and the evening one still owed. A 01:00 slot is
            // the last of the evening before, so it sorts after 23:00.
            let sorted = Set(routine.reminders).sorted {
                $0.trainingDayOrder < $1.trainingDayOrder
            }
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
    /// tomorrow even when today's target is met.
    ///
    /// **Suppression is keyed to the TRAINING day each firing falls in**, never the
    /// calendar day: a session at 00:30 satisfied the evening before, and must not delete
    /// the next morning's reminders. A 01:00 slot after a 23:30 session is still that
    /// evening, so it is silenced.
    nonisolated static func scheduledRequests(for routines: [RoutinePlanInput],
                                              now: Date = .now,
                                              calendar: Calendar = .current,
                                              limit: Int = 64) -> [PlannedReminder] {
        let slots = requests(for: routines)
        guard !slots.isEmpty, limit > 0 else { return [] }
        let budget = min(64, limit)
        let today = calendar.startOfDay(for: now)
        let trainingToday = DayStamp(trainingDayOf: now, calendar: calendar)
        var result: [PlannedReminder] = []
        for offset in 0...budget {
            guard let day = calendar.date(byAdding: .day, value: offset, to: today) else { continue }
            var candidates: [(Date, PlannedReminder)] = []
            for slot in slots {
                guard let hour = slot.components.hour, let minute = slot.components.minute,
                      let fire = calendar.date(bySettingHour: hour, minute: minute, second: 0,
                                               of: day), fire > now,
                      calendar.isDate(fire, inSameDayAs: day) else { continue }
                if slot.suppressToday,
                   DayStamp(trainingDayOf: fire, calendar: calendar) == trainingToday { continue }
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
        // DETACHED: a plain `Task {}` inherits this enum's main actor, which in the
        // sibling app cost 70–200 ms on the main thread after every save. The worker
        // touches only Sendable values and the thread-safe UNUserNotificationCenter.
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
            // `.active`, not `.timeSensitive`: a habit nudge must not pierce Focus.
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
        // Set BEFORE the await: two quick saves would otherwise stack two OS alerts.
        settings.didAskNotificationPermission = true
        _ = try? await UNUserNotificationCenter.current()
            .requestAuthorization(options: [.alert, .sound, .badge])
    }
}
