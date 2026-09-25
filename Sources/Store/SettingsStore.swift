// SPDX-License-Identifier: MPL-2.0
// Original contributions Copyright 2026 Nuri Bruner.

import Foundation
import Observation

/// The handful of preferences that are not part of a routine.
///
/// Backed by App Group `UserDefaults`, NOT SwiftData: a CloudKit-synced singleton entity
/// means duplicate-row headaches (every device creates "the" settings row), and a future
/// widget needs to read these without a store.
///
/// Everything in here is DEVICE-LOCAL by construction, and two values depend on it: a
/// synced "the routine I last started" is the classic second-device bug.
@Observable @MainActor
final class SettingsStore {
    /// `.standard` only if the entitlement is broken. Note that
    /// `UserDefaults(suiteName:)` does NOT return nil in that case — it silently falls
    /// back to an unshared container — so this optional only guards a malformed name.
    private let store: UserDefaults

    /// The builder's five inline coach cards. Retired on the first save and replayable
    /// from Settings › "Show the setup guide again", so it is a preference, not a flag.
    var builderGuideDone: Bool {
        didSet { store.set(builderGuideDone, forKey: "builderGuideDone") }
    }

    /// One-shot: the contextual permission ask happens on the first Save with reminders
    /// on, once. Never at launch, and never gating anything.
    var didAskNotificationPermission: Bool {
        didSet { store.set(didAskNotificationPermission, forKey: "didAskNotificationPermission") }
    }

    /// Rung 2 of Today's selection rule: the routine started today on THIS device.
    /// Stored as a String because `UserDefaults` has no UUID type.
    var lastStartedRoutineID: UUID? {
        didSet {
            if let id = lastStartedRoutineID {
                store.set(id.uuidString, forKey: "lastStartedRoutineID")
            } else {
                store.removeObject(forKey: "lastStartedRoutineID")
            }
        }
    }

    /// `DayStamp.raw` of the day `lastStartedRoutineID` was written, so the suggestion
    /// expires with the day. 0 is 1970-01-01 — never today — so a fresh install has none.
    var lastStartedDayRaw: Int {
        didSet { store.set(lastStartedDayRaw, forKey: "lastStartedDayRaw") }
    }

    /// A debounced rescue copy of an in-progress routine draft — create/first-run only,
    /// cleared on BOTH Save and Cancel. Restoring a stale draft into an EDIT could
    /// overwrite a CloudKit merge the user never saw.
    var draftStash: Data? {
        didSet {
            if let data = draftStash {
                store.set(data, forKey: "draftStash")
            } else {
                store.removeObject(forKey: "draftStash")
            }
        }
    }

    var weightUnit: WeightUnit {
        didSet { store.set(weightUnit.rawValue, forKey: "weightUnit") }
    }

    /// Whether THIS device schedules the routine reminders. Device-local: the routines
    /// sync, so otherwise every device would fire the same reminder at once. Phone on,
    /// iPad off by default (injected by the app). Off clears this device's pending
    /// reminders and asks for no permission.
    var remindsOnThisDevice: Bool {
        didSet { store.set(remindsOnThisDevice, forKey: "remindsOnThisDevice") }
    }

    /// One-shot, like the notification ask: the note Frez asks to be shown the first time
    /// the Dyno is selected has been read on this device. Never shown again after Next,
    /// and never shown at all for any other gauge.
    var frezIntroSeen: Bool {
        didSet { store.set(frezIntroSeen, forKey: "frezIntroSeen") }
    }

    /// The App Store rating prompt has been requested once on this device. Persisted, so
    /// the ask never repeats here — see `ReviewRequestPolicy`.
    var reviewRequested: Bool {
        didSet { store.set(reviewRequested, forKey: "reviewRequested") }
    }

    /// TEST BRANCH ONLY (`design/set-rep-bars`): how the runner shows sets and pulls, so
    /// a TestFlight build can compare the variants on real hardware. Default Stacked.
    var runnerProgressStyle: RunnerProgressStyle {
        didSet { store.set(runnerProgressStyle.rawValue, forKey: "test.runnerProgressStyle") }
    }

    /// `remindersDefault` is what an untouched device does — see `remindsOnThisDevice`.
    init(defaults: UserDefaults? = nil, remindersDefault: Bool = true) {
        let s = defaults ?? AppGroup.defaults ?? .standard
        self.store = s
        weightUnit = WeightUnit(rawValue: s.string(forKey: "weightUnit") ?? "") ?? .kg
        remindsOnThisDevice = s.object(forKey: "remindsOnThisDevice") as? Bool ?? remindersDefault
        #if DEBUG
        if ProcessInfo.processInfo.arguments.contains("-previewWeightLb") { weightUnit = .lb }
        #endif
        // `bool(forKey:)` is false for an absent key, which is the correct reading for
        // both one-shots: a fresh install has not seen the guide and has not asked.
        builderGuideDone = s.bool(forKey: "builderGuideDone")
        didAskNotificationPermission = s.bool(forKey: "didAskNotificationPermission")
        lastStartedRoutineID = s.string(forKey: "lastStartedRoutineID").flatMap(UUID.init(uuidString:))
        lastStartedDayRaw = s.object(forKey: "lastStartedDayRaw") as? Int ?? 0
        draftStash = s.data(forKey: "draftStash")
        frezIntroSeen = s.bool(forKey: "frezIntroSeen")
        reviewRequested = s.bool(forKey: "reviewRequested")
        runnerProgressStyle = RunnerProgressStyle(rawValue: s.string(forKey: "test.runnerProgressStyle") ?? "")
            ?? .stacked
    }
}
