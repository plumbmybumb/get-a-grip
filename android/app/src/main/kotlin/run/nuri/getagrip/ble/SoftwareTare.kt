// SPDX-License-Identifier: MPL-2.0
// Original contributions Copyright 2026 Nuri Bruner.

package run.nuri.getagrip.ble

import kotlin.math.abs

/// The outer bound on a force reading, in ONE place because two things must agree on it:
/// the store's door, where a reading becomes app state, and the app-side zero below, which
/// must never capture what that door would refuse.
///
/// Above every codec's own ceiling (the highest is 1000 kg) and far above any hand, so it
/// never competes with them — it only catches what none of them caught. Lives beside the
/// clients rather than in `DeviceStore` so `ble` keeps depending on nothing in `store`;
/// the store re-exports it under the names the iOS store uses.
object SamplePlausibility {
    const val maxAcceptedKilograms: Double = 1500.0

    /// Finite, and within `maxAcceptedKilograms` either way: a software tare can push a
    /// reading negative, and an absurd negative poisons an average as surely as a peak.
    fun isPlausible(kg: Double): Boolean = kg.isFinite() && abs(kg) <= maxAcceptedKilograms
}

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
    ///
    /// **An implausible reading is never remembered.** Captured as the offset, a NaN would
    /// turn every later reading into NaN for as long as the link lived, and a 1e33 would
    /// bury them all. `capture()` therefore only ever sees a finite reading inside the
    /// bound the store holds readings to, and the offset cannot leave it.
    fun value(raw: Double): Double {
        if (SamplePlausibility.isPlausible(raw)) latestRawKg = raw
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
