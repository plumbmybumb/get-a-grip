// SPDX-License-Identifier: MPL-2.0
// Original contributions Copyright 2026 Nuri Bruner.

import SwiftUI
import UIKit
import UserNotifications

/// EVERY DAY — how many sessions a day this routine asks for, and when Doigt should
/// say something about it.
///
/// The whole scheduling story is these few rows, and the session count DRIVES the
/// reminder rows underneath it: "twice a day" is expressed inline, in the document you
/// are already editing, rather than behind a scheduling screen you have to go and find.
/// There is deliberately no second surface where reminder times live.
struct EveryDaySection: View {
    @Binding var draft: RoutineDraft

    @Environment(\.scenePhase) private var scenePhase
    @Environment(\.accessibilityReduceMotion) private var reduceMotion

    /// Read from the notification centre, not from a store: nothing in the store layer
    /// owns authorization, and the answer can change WHILE this sheet is open — the
    /// user walks to iOS Settings, flips the switch and comes back.
    @State private var authStatus: UNAuthorizationStatus = .notDetermined

    /// A FIXED mid-January day carries the hour between `ReminderTime` and the
    /// `DatePicker` — the same date `ReminderTime.displayText()` uses, so a DST
    /// transition can never shift the hour under the picker.
    private static let referenceDay = DateComponents(year: 2001, month: 1, day: 15)

    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            // A PLAIN row, never a `Section` header: plain-style headers pin, and the
            // document then scrolls illegibly behind a clear background.
            CapsLabel(String(localized: "HOW OFTEN"))
                .padding(.leading, 6)

            MaterialCard {
                VStack(alignment: .leading, spacing: 18) {
                    kindBlock
                    if draft.isOnDemand {
                        // The whole scheduling story, declined in one sentence. The
                        // times are KEPT in the draft — flipping back to a ritual
                        // restores them — so nothing here is destroyed, only quiet.
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
        .animation(revealAnimation, value: draft.isOnDemand)
        .onAppear {
            // Repair, not normalization: a draft whose reminder list and session count
            // disagree (an older stash, a merge from another device) would otherwise
            // draw fewer rows than the count on its face promises. The guard keeps the
            // common path from marking an untouched document dirty.
            if draft.reminders.count != draft.sessionsPerDay {
                draft.setSessionsPerDay(draft.sessionsPerDay)
            }
        }
        .task { await refreshAuthorization() }
        .onChange(of: scenePhase) { _, phase in
            if phase == .active { Task { await refreshAuthorization() } }
        }
    }

    // MARK: - Kind

    /// Ritual or whenever (Nuri, 2026-08-10: "it's not a routine quite as much as
    /// something I want to do whenever I want"). A chip pair, not a toggle, because
    /// the two are peers with names — not one thing switched off.
    private var kindBlock: some View {
        VStack(alignment: .leading, spacing: 10) {
            ChipGrid(base: 2) {
                Chip(title: String(localized: "Daily ritual"), isSelected: !draft.isOnDemand) {
                    draft.isOnDemand = false
                }
                Chip(title: String(localized: "Whenever"), isSelected: draft.isOnDemand) {
                    draft.isOnDemand = true
                }
            }
        }
        .sensoryFeedback(.selection, trigger: draft.isOnDemand)
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
        // The chips speak as bare numerals, which means nothing on their own; the
        // container label is what makes "2" a sentence when VoiceOver enters the group.
        .accessibilityElement(children: .contain)
        .accessibilityLabel(String(localized: "Sessions a day"))
    }

    /// Never writes `sessionsPerDay` directly. `setSessionsPerDay` PARKS the times a
    /// lower count removes, so going 2 → 1 → 2 restores the user's own 19:15 instead of
    /// resetting it to the 19:00 default.
    private var sessionsBinding: Binding<Int> {
        Binding(get: { draft.sessionsPerDay },
                set: { draft.setSessionsPerDay($0) })
    }

    // MARK: - Reminders

    private var reminderBlock: some View {
        VStack(alignment: .leading, spacing: 14) {
            Toggle("Remind me", isOn: $draft.remindersEnabled)
                .font(.system(.subheadline, weight: .semibold))
                .foregroundStyle(Ink.primary)
                .tint(Accent.graphite)

            // The times are what the toggle is about, so they follow it rather than
            // sitting there inert while it is off. The values themselves are kept in
            // the draft either way — turning reminders back on restores the schedule.
            if draft.remindersEnabled {
                ForEach(0..<rowCount, id: \.self) { index in
                    reminderRow(index)
                }

                if authStatus == .denied {
                    deniedRow
                }
            }
        }
        .animation(revealAnimation, value: draft.remindersEnabled)
        .animation(revealAnimation, value: rowCount)
    }

    /// Bounded by the list itself, never by the count alone: a mismatched draft must
    /// degrade to one row fewer, never to an index crash.
    private var rowCount: Int {
        min(draft.sessionsPerDay, draft.reminders.count)
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

    /// Denied is a dead end for the notification, never for the setting: reminders stay
    /// ON in the draft, so changing your mind in iOS Settings later just works without
    /// coming back here to re-enable anything.
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

    /// `ReminderTime` stores minutes from midnight; a `DatePicker` wants a `Date`. The
    /// displayed string is always the picker's own locale-correct formatting — this
    /// carries the hour across and nothing else.
    private func timeBinding(_ index: Int) -> Binding<Date> {
        Binding(
            get: {
                guard index < draft.reminders.count else { return date(for: ReminderTime(hour: 8, minute: 0)) }
                return date(for: draft.reminders[index])
            },
            set: { newDate in
                guard index < draft.reminders.count else { return }
                let parts = Calendar.current.dateComponents([.hour, .minute], from: newDate)
                // Deliberately NOT sorted or deduped here: re-ordering the array under
                // the finger would swap the row being edited with the one below it.
                // `RoutineDraft.normalized` tidies on the way into the store.
                draft.reminders[index] = ReminderTime(hour: parts.hour ?? 0, minute: parts.minute ?? 0)
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

/// The one control that needs `openURL`, and therefore the only view that may STORE it.
///
/// `@Environment(\.openURL)` is a presentation-environment value, and its identity moves
/// with the presentation the way `@Environment(\.dismiss)`'s does — which is the trap
/// `BuilderDocument` already carries a warning about. Measured on the pinned sim
/// (2026-08-18): while a number field in the builder had focus, `_printChanges()` named
/// `EveryDaySection: _openURL changed` on every one of the three update passes a single
/// keystroke costs, rebuilding the whole section — the kind chips, the reminder rows and
/// their `DatePicker`s — to serve a button that is not even on screen unless
/// notifications have been denied. Nothing in the section's body reads it; being a stored
/// property was enough.
///
/// A leaf that holds it instead is the same answer the house rule gives for a
/// high-frequency read: the blast radius becomes the one thing that actually depends on
/// the value.
private struct OpenSettingsButton: View {
    @Environment(\.openURL) private var openURL

    var body: some View {
        SecondaryGlassButton(title: String(localized: "Open Settings")) {
            if let url = URL(string: UIApplication.openSettingsURLString) { openURL(url) }
        }
    }
}
