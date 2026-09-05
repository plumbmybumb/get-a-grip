// SPDX-License-Identifier: MPL-2.0
// Original contributions Copyright 2026 Nuri Bruner.

package run.nuri.getagrip.ble

/// The app-side zero, for every gauge that has no tare of its own.
///
/// Shared by `GattGaugeClient` and `BroadcastGaugeClient` — a crane scale has nothing to
/// write a tare to at all. Kept free of clocks and Bluetooth so the arithmetic is
/// testable on its own, which is where the double-subtract and empty-capture mistakes
/// would otherwise hide.
///
/// TRANSLATION NOTE: Swift's `struct SoftwareTare` with `mutating` members becomes a
/// class, per the house type mapping. Every holder already owns exactly one instance for
/// the life of one link, so there was never a copy to preserve.
class SoftwareTare {
    private var offsetKg: Double = 0.0
    private var latestRawKg: Double? = null

    /// Observe and convert in ONE call, so the "remember the raw reading" half can never
    /// be forgotten at a call site and leave `capture()` zeroing against a stale value.
    fun value(raw: Double): Double {
        latestRawKg = raw
        return raw - offsetKg
    }

    /// Zero against the newest reading. A capture with nothing observed leaves the offset
    /// where it is: on a fresh link that is already zero, and throwing away a good offset
    /// because the stream went quiet would shift every later reading.
    fun capture() {
        val latest = latestRawKg ?: return
        offsetKg = latest
    }

    /// The link went away. An offset captured against one connection's zero says nothing
    /// about the next one's.
    fun reset() {
        offsetKg = 0.0
        latestRawKg = null
    }

    val offset: Double get() = offsetKg

    val hasReading: Boolean get() = latestRawKg != null
}
