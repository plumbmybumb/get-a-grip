// SPDX-License-Identifier: MPL-2.0
// Original contributions Copyright 2026 Nuri Bruner.

package run.nuri.getagrip.store

import run.nuri.getagrip.engine.L10n

/// Where the routines and session history actually ended up. Shown in Settings › About
/// unconditionally: someone who thinks device-only history is backed up finds out when they
/// lose the device.
///
/// TRANSLATION NOTE (Sources/Store/StorageMode.swift): iOS has three cases (App Group,
/// CloudKit fallbacks). **Android has one**, `localOnly`: no CloudKit, Room in the app's
/// data directory, and Auto Backup (`res/xml/data_extraction_rules.xml`) is a
/// device-to-device restore, not a sync. Kept an enum so a real sync one day is a case, not
/// a refactor.
enum class StorageMode {
    /// The app's own data directory, no sync. Auto Backup may carry it to a new phone;
    /// nothing keeps two phones in step.
    localOnly;

    val aboutLine: String
        get() = when (this) {
            localOnly -> L10n.tr(
                "Your routines and sessions are stored on this device. They don't sync between devices."
            )
        }
}
