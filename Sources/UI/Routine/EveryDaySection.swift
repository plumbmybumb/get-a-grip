// SPDX-License-Identifier: MPL-2.0
// Original contributions Copyright 2026 Nuri Bruner.

import SwiftUI
import UIKit
import UserNotifications

/// EVERY DAY — how many sessions a day this routine asks for, and when to remind you.
///
/// The session count DRIVES the reminder rows underneath it, so "twice a day" is set
/// inline in the document, not on a scheduling screen. There is no second surface where
/// reminder times live.
struct EveryDaySection: View, Equatable {
    /// The write path. Everything drawn comes from `schedule` — see `BuilderInputs`.
    let access: DraftAccess
    /// `draft.schedule` (the plan blanked) as a value, so this card compares on the every-day
    /// fields alone and an edit inside a set never re-runs its date pickers.
    let schedule: RoutineDraft

    nonisolated static func == (a: Self, b: Self) -> Bool { a.schedule == b.schedule }

    @Environment(\.scenePhase) private var scenePhase
    @Environment(\.accessibilityReduceMotion) private var reduceMotion

    /// From the notification centre, not a store: nothing there owns authorization, and it
    /// can change WHILE this sheet is open (a trip to iOS Settings and back).
    @State private var authStatus: UNAuthorizationStatus = .notDetermined

    /// A FIXED mid-January day carries the hour into the `DatePicker` — the date
    /// `ReminderTime.displayText()` uses — so DST can never shift the hour.
    private static let referenceDay = DateComponents(year: 2001, month: 1, day: 15)

    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            // A PLAIN row, never a `Section` header: plain-style headers pin.
            CapsLabel(String(localized: "HOW OFTEN"))
                .padding(.leading, 6)

            MaterialCard(surface: .flat) {
                VStack(alignment: .leading, spacing: 18) {
                    kindBlock
                    if schedule.isOnDemand {
                        // The times are KEPT in the draft — flipping back to a ritual restores
                        // them — so nothing is destroyed, only quiet.
                        Text("No daily target and no reminders — it waits on Today until you feel like it.")
                            .font(.system(.footnote))
                            .foregroundStyle(Ink.tertiary)
                            .fixedSize(horizontal: false, vertical: true)
                    } else {
                        sessionsBlock
                        reminderBlock
                    }
                }
            }
        }
        .animation(revealAnimation, value: schedule.isOnDemand)
        .onAppear {
            // Repair: a draft whose reminder list and count disagree (an older stash, a
            // merge) would draw fewer rows than promised. The guard keeps an untouched
            // document from turning dirty.
            if schedule.reminders.count != schedule.sessionsPerDay {
                access.mutate { $0.setSessionsPerDay($0.sessionsPerDay) }
            }
        }
        .task { await refreshAuthorization() }
        .onChange(of: scenePhase) { _, phase in
            if phase == .active { Task { await refreshAuthorization() } }
        }
    }

    // MARK: - Kind

    /// Ritual or whenever (Nuri, 2026-08-10). A chip pair, not a toggle: two named peers,
    /// not one thing switched off.
    private var kindBlock: some View {
        VStack(alignment: .leading, spacing: 10) {
            ChipGrid(base: 2) {
                Chip(title: String(localized: "Daily ritual"), isSelected: !schedule.isOnDemand) {
                    access.mutate { $0.isOnDemand = false }
                }
                Chip(title: String(localized: "Whenever"), isSelected: schedule.isOnDemand) {
                    access.mutate { $0.isOnDemand = true }
                }
            }
        }
        .sensoryFeedback(.selection, trigger: schedule.isOnDemand)
        .accessibilityElement(children: .contain)
        .accessibilityLabel(String(localized: "How often"))
    }

    // MARK: - Sessions a day

    private var sessionsBlock: some View {
        VStack(alignment: .leading, spacing: 10) {
            Text("Sessions a day")
                .font(.system(.subheadline, weight: .semibold))
                .foregroundStyle(Ink.primary)

            IntChipRow(values: [1, 2, 3, 4], selection: sessionsBinding)
        }
        // The chips are bare numerals; the container label makes "2" a sentence.
        .accessibilityElement(children: .contain)
        .accessibilityLabel(String(localized: "Sessions a day"))
    }

    /// Never writes `sessionsPerDay` directly: `setSessionsPerDay` PARKS removed times, so
    /// 2 → 1 → 2 restores the user's own 19:15, not the 19:00 default.
    private var sessionsBinding: Binding<Int> {
        Binding(get: { schedule.sessionsPerDay },
                set: { new in access.mutate { $0.setSessionsPerDay(new) } })
    }

    // MARK: - Reminders

    private var reminderBlock: some View {
        VStack(alignment: .leading, spacing: 14) {
            Toggle("Remind me", isOn: access.binding(\.remindersEnabled, current: schedule.remindersEnabled))
                .font(.system(.subheadline, weight: .semibold))
                .foregroundStyle(Ink.primary)
                .tint(Accent.graphite)

            // The times follow the toggle rather than sitting inert; the draft keeps
            // them either way, so turning reminders back on restores the schedule.
            if schedule.remindersEnabled {
                ForEach(0..<rowCount, id: \.self) { index in
                    reminderRow(index)
                }

                if authStatus == .denied {
                    deniedRow
                }
            }
        }
        .animation(revealAnimation, value: schedule.remindersEnabled)
        .animation(revealAnimation, value: rowCount)
    }

    /// Bounded by the list, never the count alone: a mismatched draft loses a row, never
    /// crashes.
    private var rowCount: Int {
        min(schedule.sessionsPerDay, schedule.reminders.count)
    }

    private func reminderRow(_ index: Int) -> some View {
        HStack(spacing: 12) {
            CapsLabel(String(localized: "SESSION \(index + 1)"))
            Spacer(minLength: 8)
            DatePicker("Session \(index + 1) reminder time",
                       selection: timeBinding(index),
                       displayedComponents: .hourAndMinute)
                .datePickerStyle(.compact)
                .labelsHidden()
                .accessibilityLabel(String(localized: "Session \(index + 1) reminder time"))
        }
    }

    /// Denied is a dead end for the notification, not the setting: reminders stay ON in the
    /// draft, so allowing them later in iOS Settings just works.
    private var deniedRow: some View {
        VStack(alignment: .leading, spacing: 10) {
            Text("Notifications are off for Get a Grip.")
                .font(.system(.footnote, weight: .medium))
                .foregroundStyle(Ink.secondary)
                .fixedSize(horizontal: false, vertical: true)

            OpenSettingsButton()
        }
    }

    // MARK: - Bridging ReminderTime ↔ Date

    /// `ReminderTime` stores minutes from midnight; a `DatePicker` wants a `Date`. Only the
    /// hour crosses; display is the picker's own locale formatting.
    private func timeBinding(_ index: Int) -> Binding<Date> {
        Binding(
            get: {
                let reminders = schedule.reminders
                guard index < reminders.count else { return date(for: ReminderTime(hour: 8, minute: 0)) }
                return date(for: reminders[index])
            },
            set: { newDate in
                let parts = Calendar.current.dateComponents([.hour, .minute], from: newDate)
                // NOT sorted or deduped here: reordering under the finger would swap the
                // row being edited. `RoutineDraft.normalized` tidies on the way to the store.
                access.mutate { draft in
                    guard index < draft.reminders.count else { return }
                    draft.reminders[index] = ReminderTime(hour: parts.hour ?? 0, minute: parts.minute ?? 0)
                }
            })
    }

    private func date(for time: ReminderTime, calendar: Calendar = .current) -> Date {
        var comps = Self.referenceDay
        comps.hour = time.hour
        comps.minute = time.minute
        return calendar.date(from: comps) ?? Date(timeIntervalSince1970: 0)
    }

    private var revealAnimation: Animation {
        Motion.state(reduceMotion)
    }

    private func refreshAuthorization() async {
        authStatus = await UNUserNotificationCenter.current().notificationSettings().authorizationStatus
    }
}

/// The one control that needs `openURL`, and so the only view that may STORE it.
///
/// `@Environment(\.openURL)`'s identity moves with the presentation, like `dismiss` (see
/// `BuilderDocument`). Measured 2026-08-18: with a builder field focused, every keystroke
/// logged `EveryDaySection: _openURL changed` three times, rebuilding the chips, reminder
/// rows and `DatePicker`s for a button usually not even on screen. Held by a leaf, the
/// blast radius is the one thing that depends on it.
private struct OpenSettingsButton: View {
    @Environment(\.openURL) private var openURL

    var body: some View {
        SecondaryGlassButton(title: String(localized: "Open Settings")) {
            if let url = URL(string: UIApplication.openSettingsURLString) { openURL(url) }
        }
    }
}
