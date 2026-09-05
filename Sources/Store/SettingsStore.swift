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
/// Everything in here is DEVICE-LOCAL by construction — the App Group container never
/// leaves the phone. That is a property two of these values depend on, not an accident:
/// a synced "the routine I last started" flag is the classic second-device bug, where the
/// phone you left at home decides what your iPad opens on.
@Observable @MainActor
final class SettingsStore {
    /// `.standard` only if the entitlement is broken. Note that
    /// `UserDefaults(suiteName:)` does NOT return nil in that case — it silently falls
    /// back to an unshared container — so this optional only guards a malformed name.
    private static let store = AppGroup.defaults ?? .standard

    /// The builder's five inline coach cards. Retired on the first save and replayable
    /// from Settings › "Show the setup guide again", so it is a preference, not a flag.
    var builderGuideDone: Bool {
        didSet { Self.store.set(builderGuideDone, forKey: "builderGuideDone") }
    }

    /// One-shot: the contextual permission ask happens on the first Save with reminders
    /// on, once. Never at launch, and never gating anything.
    var didAskNotificationPermission: Bool {
        didSet { Self.store.set(didAskNotificationPermission, forKey: "didAskNotificationPermission") }
    }

    /// Rung 2 of Today's selection rule: the routine started today on THIS device.
    /// Stored as a String because `UserDefaults` has no UUID type.
    var lastStartedRoutineID: UUID? {
        didSet {
            if let id = lastStartedRoutineID {
                Self.store.set(id.uuidString, forKey: "lastStartedRoutineID")
            } else {
                Self.store.removeObject(forKey: "lastStartedRoutineID")
            }
        }
    }

    /// `DayStamp.raw` of the day `lastStartedRoutineID` was written, which is what makes
    /// the suggestion expire at midnight instead of persisting for a week. A raw Int
    /// rather than a `DayStamp` because that is what `UserDefaults` can hold; 0 is
    /// 1970-01-01, which is never today, so a fresh install has no suggestion.
    var lastStartedDayRaw: Int {
        didSet { Self.store.set(lastStartedDayRaw, forKey: "lastStartedDayRaw") }
    }

    /// A debounced rescue copy of an in-progress routine draft — create/first-run only,
    /// cleared on BOTH Save and Cancel. Restoring a stale draft into an EDIT could
    /// overwrite a CloudKit merge the user never saw.
    var draftStash: Data? {
        didSet {
            if let data = draftStash {
                Self.store.set(data, forKey: "draftStash")
            } else {
                Self.store.removeObject(forKey: "draftStash")
            }
        }
    }

    init() {
        let s = Self.store
        // `bool(forKey:)` is false for an absent key, which is the correct reading for
        // both one-shots: a fresh install has not seen the guide and has not asked.
        builderGuideDone = s.bool(forKey: "builderGuideDone")
        didAskNotificationPermission = s.bool(forKey: "didAskNotificationPermission")
        lastStartedRoutineID = s.string(forKey: "lastStartedRoutineID").flatMap(UUID.init(uuidString:))
        lastStartedDayRaw = s.object(forKey: "lastStartedDayRaw") as? Int ?? 0
        draftStash = s.data(forKey: "draftStash")
    }
}
