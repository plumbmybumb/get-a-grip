// SPDX-License-Identifier: MPL-2.0
// Original contributions Copyright 2026 Nuri Bruner.

package run.nuri.getagrip.ble

import android.os.SystemClock

/// The two host clocks the gauge layer reads, behind one seam.
///
/// TRANSLATION NOTE: on iOS these are `Date()` and `ProcessInfo.processInfo.systemUptime`,
/// read inline wherever they are needed. Android puts both behind an interface for one
/// reason: `SystemClock` is an Android framework class, so a JVM unit test would read a
/// stubbed zero forever, and the playback clock and the broadcast silence watchdog are
/// exactly the arithmetic worth testing.
///
/// - `wallSeconds` is the twin of `Date().timeIntervalSinceReferenceDate`. Only
///   DIFFERENCES are ever taken, so the epoch (1970 here, 2001 there) is irrelevant as
///   long as one epoch is used throughout — which is why `DeviceStore` and every view
///   that draws `now − t` read this same clock.
/// - `uptimeSeconds` is the MONOTONIC one, and the only clock a timestamp may be
///   synthesized from: wall time steps under NTP and timezone changes, and a stamp that
///   jumped backwards would read as a stale batch and trip the engine's fail-closed
///   high-water check.
interface HostClock {
    fun wallSeconds(): Double
    fun uptimeSeconds(): Double
}

object SystemHostClock : HostClock {
    override fun wallSeconds(): Double = System.currentTimeMillis() / 1_000.0

    /// TRANSLATION NOTE: `elapsedRealtimeNanos` keeps counting through deep sleep, where
    /// `SystemClock.uptimeMillis()` (and Darwin's monotonic clock behind
    /// `ProcessInfo.systemUptime`) stops. That difference is in the SAFE direction and is
    /// why the plan names this one: a synthetic stamp that froze while the phone slept
    /// would under-report the gap across a suspension, and a backlog delivered on wake
    /// would look like a fresh, densely-sampled pull instead of the hole it is.
    override fun uptimeSeconds(): Double = SystemClock.elapsedRealtimeNanos() / 1_000_000_000.0
}
