// SPDX-License-Identifier: MPL-2.0
// Original contributions Copyright 2026 Nuri Bruner.

package run.nuri.getagrip.ble

import android.os.SystemClock

/// The two host clocks the gauge layer reads, behind one seam.
///
/// TRANSLATION NOTE: iOS reads `Date()` and `systemUptime` inline; here `SystemClock` would
/// read a stubbed zero in JVM tests, and the playback clock and broadcast watchdog are the
/// arithmetic worth testing.
///
/// - `wallSeconds`, the twin of `Date().timeIntervalSinceReferenceDate`. Only DIFFERENCES
///   are taken, so the epoch is irrelevant as long as one is used throughout — why
///   `DeviceStore` and every `now − t` view read this clock.
/// - `uptimeSeconds` is MONOTONIC and the only clock a timestamp may be synthesized from:
///   wall time steps under NTP and zone changes, and a backwards stamp would trip the
///   engine's fail-closed high-water check.
interface HostClock {
    fun wallSeconds(): Double
    fun uptimeSeconds(): Double
}

object SystemHostClock : HostClock {
    override fun wallSeconds(): Double = System.currentTimeMillis() / 1_000.0

    /// TRANSLATION NOTE: `elapsedRealtimeNanos` keeps counting through deep sleep, where
    /// `uptimeMillis()` (and Darwin's `systemUptime`) stops. The SAFE direction: a stamp
    /// frozen during sleep would under-report the gap, and a backlog delivered on wake
    /// would look like a dense fresh pull instead of a hole.
    override fun uptimeSeconds(): Double = SystemClock.elapsedRealtimeNanos() / 1_000_000_000.0
}
