// SPDX-License-Identifier: MPL-2.0
// Original contributions Copyright 2026 Nuri Bruner.

package run.nuri.getagrip.store

import run.nuri.getagrip.engine.L10n

/// Where the routines and session history actually ended up.
///
/// Surfaced in Settings › About, unconditionally: someone who believes their training
/// history is backed up when it is device-only would discover the truth at the moment
/// they lose the device. Asserting the happy case is not a safe default.
///
/// TRANSLATION NOTE (Sources/Store/StorageMode.swift): iOS has three cases because it has
/// an App Group container and a CloudKit rung to fall off. **Android has exactly one**,
/// `localOnly` — there is no CloudKit, the Room file lives in the app's own data
/// directory, and Android Auto Backup (declared in `res/xml/data_extraction_rules.xml`)
/// is a device-to-device restore rather than a sync. The enum is kept as an enum rather
/// than collapsed to a constant because the About copy reads through it either way, and a
/// second rung (a real sync, one day) would then be a case rather than a refactor.
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
