// SPDX-License-Identifier: MPL-2.0
// Original contributions Copyright 2026 Nuri Bruner.

package run.nuri.getagrip.ble

import kotlin.math.max

/// **Android's scan quota, counted — shared by every client the app builds.**
///
/// Five scan STARTS in thirty seconds and the platform stops delivering results for the
/// next thirty, silently: no error, no callback, just a scan that finds nothing. The quota
/// is per APP, not per client, so the factory hands one of these to every client it makes —
/// switching gauges does not reset what the phone has already counted.
///
/// Four starts per 31 s, not five per 30: one start under the limit and a second over the
/// window, so a boundary rounded the wrong way cannot tip the phone over it. A start the
/// platform refused as too frequent (`SCAN_FAILED_SCANNING_TOO_FREQUENTLY`, 6) buys a full
/// window of cooldown, because by then the platform is already counting against us.
///
/// This began as `BroadcastGaugeClient`'s private bookkeeping. The two connected clients
/// needed it too: every failed attempt stops their scan, so a retry ladder of connect
/// timeouts restarts one scan per rung — exactly the shape that trips the quota, and the
/// comment claiming a running scan was always reused only held while one was running.
class ScanStartBudget(
    private val windowSeconds: Double = 31.0,
    private val maximumStarts: Int = 4,
) {
    private val recentStarts = ArrayDeque<Double>()
    private var cooldownUntil = 0.0

    /// How long to wait before the next start may go out; 0 means now. `now` is host
    /// uptime seconds, the clock every client already carries.
    fun delaySeconds(now: Double): Double {
        while (recentStarts.isNotEmpty() && now - recentStarts.first() >= windowSeconds) {
            recentStarts.removeFirst()
        }
        val quotaDelay = if (recentStarts.size >= maximumStarts) {
            recentStarts.first() + windowSeconds - now
        } else 0.0
        return max(0.0, max(quotaDelay, cooldownUntil - now))
    }

    fun recordStart(now: Double) {
        recentStarts.addLast(now)
    }

    /// The platform said "too frequently" — trust it over our own count.
    fun noteTooFrequent(now: Double) {
        cooldownUntil = max(cooldownUntil, now + windowSeconds)
    }

    companion object {
        const val scanTooFrequentError = 6
    }
}

/// How the next connection attempt reaches the gauge.
enum class AttemptRoute {
    /// Look for it: the first link ever, or a remembered one that did not answer.
    scan,

    /// Connect straight to the remembered device, with the usual short timeout. No scan
    /// is involved, so neither the quota nor a locked screen can stop it.
    direct,

    /// Hand the remembered device to the OS to connect WHENEVER it is next in range
    /// (`autoConnect`), with no timeout. The only way a link that dropped behind a locked
    /// screen comes back: Android pauses unfiltered scans while the screen is off, so the
    /// old scan-based retry ladder found nothing, spent its five attempts, and gave up —
    /// leaving a session the foreground service was keeping alive waiting on a gauge
    /// nothing was looking for any more.
    awaitInRange,
}

/// Which route the next attempt takes — the rule on its own, so it can be asserted whole.
object AttemptRouting {
    /// - `recovering`: an ESTABLISHED link dropped while the user still wants it. Wait for
    ///   that same device, however long it takes; a session waits for its gauge (a rep never
    ///   ends itself, and neither does the wait for the link).
    /// - `directTriesLeft`: an explicit Connect with a device remembered from earlier gets
    ///   ONE quick direct try, then scans — the user may have picked up a different unit,
    ///   and waiting forever on the old one would never find it.
    fun route(hasRemembered: Boolean, recovering: Boolean, directTriesLeft: Int): AttemptRoute = when {
        !hasRemembered -> AttemptRoute.scan
        recovering -> AttemptRoute.awaitInRange
        directTriesLeft > 0 -> AttemptRoute.direct
        else -> AttemptRoute.scan
    }
}
