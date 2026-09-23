// SPDX-License-Identifier: MPL-2.0
// Original contributions Copyright 2026 Nuri Bruner.

package run.nuri.getagrip.ble

/// The app-side zero, for every gauge with no tare of its own (`GattGaugeClient`,
/// `BroadcastGaugeClient`). Free of clocks and Bluetooth so the double-subtract and
/// empty-capture mistakes are testable.
///
/// TRANSLATION NOTE: Swift's `mutating` struct becomes a class; each holder owns one per
/// link, so there was never a copy to preserve.
class SoftwareTare {
    private var offsetKg: Double = 0.0
    private var latestRawKg: Double? = null

    /// Observe and convert in ONE call, so no call site can skip remembering the raw
    /// reading and leave `capture()` stale.
    fun value(raw: Double): Double {
        latestRawKg = raw
        return raw - offsetKg
    }

    /// Zero against the newest reading. With nothing observed the offset stays: a fresh
    /// link is already zero, and discarding a good offset would shift every later reading.
    fun capture() {
        val latest = latestRawKg ?: return
        offsetKg = latest
    }

    /// The link went away; an offset from one connection's zero says nothing about the
    /// next.
    fun reset() {
        offsetKg = 0.0
        latestRawKg = null
    }

    val offset: Double get() = offsetKg

    val hasReading: Boolean get() = latestRawKg != null
}
