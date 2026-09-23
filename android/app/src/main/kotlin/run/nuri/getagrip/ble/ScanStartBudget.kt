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
/// Five scan STARTS in thirty seconds and the platform silently delivers nothing for the
/// next thirty. The quota is per APP, so the factory shares one budget and switching gauges
/// does not reset the count.
///
/// Four per 31 s, not five per 30: one start and one second of margin, so a boundary
/// rounded the wrong way cannot tip it. A `SCAN_FAILED_SCANNING_TOO_FREQUENTLY` (6) refusal
/// buys a full cooldown window: the platform is already counting against us.
///
/// The connected clients need it too: every failed attempt stops their scan, so a ladder of
/// connect timeouts restarts one scan per rung, and reusing a running scan does not help
/// when none is running. See `BudgetedScanStart`.
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

/// **A connected client's scan start, asked of the budget first** (see `ScanStartBudget`).
/// A start the quota cannot afford waits here, and the attempt's deadline starts only once
/// its scan does.
class BudgetedScanStart(
    private val scope: CoroutineScope,
    private val budget: ScanStartBudget,
    private val clock: HostClock,
    private val context: CoroutineContext = Dispatchers.Main.immediate,
) {
    private var deferred: Job? = null

    /// True when the start must WAIT: `retry` runs once affordable, if `stillWanted()` (the
    /// attempt may have been superseded). False means start now and report `started()`.
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
