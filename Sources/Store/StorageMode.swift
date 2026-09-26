// SPDX-License-Identifier: MPL-2.0
// Original contributions Copyright 2026 Nuri Bruner.

import Foundation

/// Where the routines and session history actually ended up.
///
/// Surfaced in Settings › About, unconditionally: someone who believes their training
/// history is in iCloud when it is device-only would discover the truth at the moment
/// they lose the device. Asserting the happy case is not a safe default.
enum StorageMode {
    /// App Group container configured for CloudKit private sync. This describes the
    /// store configuration, not account availability or a completed upload.
    case cloud
    /// App Group container, no sync — the iCloud entitlement is missing (ad-hoc
    /// simulator signing strips it) or the CloudKit container could not be opened.
    case localOnly
    /// Neither entitlement available: a DIFFERENT store file at the default location.
    /// Anything previously saved to the group container is not in this one, so an
    /// existing user opens the app to an empty history — it must never pass silently.
    case isolated

    var aboutLine: String {
        switch self {
        case .cloud:
            String(localized: "Stored on this device and synced to your private iCloud when available.")
        case .localOnly:
            String(localized: "iCloud is unavailable. Data stays on this device and won't sync.")
        case .isolated:
            String(localized: "Can't reach shared storage. Earlier routines may be missing.")
        }
    }
}
