// SPDX-License-Identifier: MPL-2.0
// Original contributions Copyright 2026 Nuri Bruner.

package run.nuri.getagrip.ble

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.coroutines.CoroutineContext
import kotlin.math.ceil
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
/// The two connected clients need it as much as `BroadcastGaugeClient` does: every failed
/// attempt stops their scan, so a retry ladder of connect timeouts restarts one scan per
/// rung — exactly the shape that trips the quota. Reusing a RUNNING scan is not enough,
/// because by the next rung none is running. See `BudgetedScanStart`.
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

/// **A connected client's scan start, asked of the budget first.** Both connected clients
/// stop their scan on every failed attempt, so a retry ladder of connect timeouts would
/// restart one scan per rung; a start the quota cannot afford waits here instead, and the
/// attempt's deadline starts only once its scan does.
class BudgetedScanStart(
    private val scope: CoroutineScope,
    private val budget: ScanStartBudget,
    private val clock: HostClock,
    private val context: CoroutineContext = Dispatchers.Main.immediate,
) {
    private var deferred: Job? = null

    /// True when the start has to WAIT: `retry` then runs once the budget can afford it,
    /// provided `stillWanted()` — the attempt that asked may have been superseded meanwhile.
    /// False means start now, and report it with `started()`.
    fun deferIfOverBudget(stillWanted: () -> Boolean, retry: () -> Unit): Boolean {
        val wait = budget.delaySeconds(clock.uptimeSeconds())
        if (wait <= 0) return false
        deferred?.cancel()
        deferred = scope.launch(context) {
            delay(ceil(wait * 1_000).toLong())
            if (!stillWanted()) return@launch
            deferred = null
            retry()
        }
        return true
    }

    fun started() = budget.recordStart(clock.uptimeSeconds())

    /// The platform refused a start; if it said "too frequently", that outranks our count.
    fun failed(errorCode: Int) {
        if (errorCode == ScanStartBudget.scanTooFrequentError) {
            budget.noteTooFrequent(clock.uptimeSeconds())
        }
    }

    fun cancel() {
        deferred?.cancel()
        deferred = null
    }
}
