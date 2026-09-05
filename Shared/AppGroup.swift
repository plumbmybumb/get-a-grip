// SPDX-License-Identifier: MPL-2.0
// Original contributions Copyright 2026 Nuri Bruner.

import Foundation

/// The App Group identity. Reserved and used from `Shared/` so a workout widget can be
/// added later without moving a file or renaming anything.
enum AppGroup {
    /// NEVER rename. The suite name is baked into the provisioning profile, and a
    /// change orphans every value already written on the user's phone.
    static let id = Bundle.main.object(forInfoDictionaryKey: "GetAGripAppGroup") as? String
        ?? "group.run.nuri.doigt"

    static let cloudContainer = Bundle.main.object(forInfoDictionaryKey: "GetAGripCloudContainer") as? String
        ?? "iCloud.run.nuri.doigt"

    /// `UserDefaults(suiteName:)` does NOT return nil when the entitlement is missing —
    /// it silently falls back to an unshared container, which is exactly why a
    /// simulator build with stripped entitlements still "works" and a widget then reads
    /// nothing. The Optional here guards only a malformed suite name.
    static var defaults: UserDefaults? { UserDefaults(suiteName: id) }
}
