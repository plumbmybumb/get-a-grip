// SPDX-License-Identifier: MPL-2.0
// Original contributions Copyright 2026 Nuri Bruner.

package run.nuri.getagrip.engine

/// One max test: **the hardest the gauge saw you pull.**
///
/// `peakKg` is the highest single reading of the attempt, and that is the whole rule.
///
/// **It used to be a two-second sustained hold**, to discount a snatch at the edge. In
/// the hand that was wrong (Nuri, 2026-08-04: *"2s of consistent pull is too much, I
/// think we just measure peak"*): a real max peaks and decays at once, so it reported
/// well under what had just been pulled. A snatch now counts; if spikes ever become a
/// problem, the answer is a SHORT window (a few hundred ms), not a return to two seconds.
///
/// **A TIMED max is the AVERAGE over a window** (2026-10-01): "the most you can hold for
/// 10 s", for a routine prescribing 90 % of it. The window opens when the load first
/// crosses `releaseKg` and the result is the time-weighted mean force across it. NOT the
/// lowest point: an all-out pull fades, so one noisy moment would decide the max. Coming
/// off the edge before the window closes is no result: it is not a 10 s max.
///
/// The type also knows when an attempt is OVER, so pull-hold-let-go needs no tap (see
/// `isComplete`). Pure: no clock, device or store, so the rule is testable.
///
/// TRANSLATION NOTE (from Shared/Engine/MaxAttempt.swift): Swift's `struct` with
/// `mutating add`/`finish` is a class here; `private set` twins `private(set) var`.
class MaxAttempt(
    /// This attempt's own release window. A single test keeps the default; a visit of
    /// repeated pulls (`MaxAttemptLog`) ends each one sooner, because there a re-grip that
    /// splits one effort in two still logs its highest reading.
    val endsAfter: Double = releaseSeconds,
    window: Double? = null,
) {
    /// null: a PEAK max. Otherwise the timed window, in seconds.
    val window: Double? = window?.takeIf { it > 0 && it.isFinite() }

    /// THE RESULT — the hardest single reading. Only ever climbs, so the figure on
    /// screen is always exactly what would be recorded right now.
    var peakKg: Double = 0.0
        private set

    /// You pulled, and then came off the edge for `releaseSeconds`. The caller can also
    /// stop by hand at any point; this exists so the ordinary case needs no tap at all.
    var isComplete: Boolean = false
        private set

    /// **A result means you PULLED**, not that a number arrived: load-cell drift would
    /// make `peakKg > 0` offer a 0.4 kg max to someone who never touched the edge, and
    /// let a forgotten screen end its own attempt. Reuses `releaseKg`, so "on the edge"
    /// stays one idea.
    val hasResult: Boolean
        get() {
            val window = window ?: return hasPulled
            return hasPulled && !windowBroken && heldSeconds >= window && (averageKg ?: 0.0) >= releaseKg
        }

    /// What this attempt records: the peak, or the window's average when timed. null until
    /// there is a result, so a short timed pull can never be mistaken for a max.
    val resultKg: Double?
        get() = if (!hasResult) null else if (window == null) peakKg else averageKg

    /// Ever over the threshold — what release detection needs, separate from `hasResult`
    /// because a timed pull is ON the edge long before its result exists.
    private val hasPulled: Boolean get() = peakKg >= releaseKg

    // Timed window — sample-and-hold integration, so bursty delivery weighs each reading
    // by how long it actually stood.
    private var windowStart: Double? = null
    private var lastT: Double? = null
    private var lastKg: Double = 0.0
    private var impulse: Double = 0.0

    /// Seconds of the window covered so far, capped at the window.
    var heldSeconds: Double = 0.0
        private set

    /// Came off the edge before the window closed: time spent letting go is not time held.
    var windowBroken: Boolean = false
        private set

    /// The window's time-weighted mean so far. null for a peak attempt or before any time.
    val averageKg: Double?
        get() = if (window == null || heldSeconds <= 0) null else impulse / heldSeconds

    /// Seconds still to hold, for the countdown. null for a peak attempt.
    val remainingSeconds: Double?
        get() = window?.let { maxOf(0.0, it - heldSeconds) }

    /// When the load most recently fell below `releaseKg`.
    private var releasedAt: Double? = null

    /// Feed one sample. `t` is in SECONDS and must increase monotonically; the store's
    /// playback clock is built to guarantee that across tares and device counter resets
    /// (see `DeviceStore.playbackTime`).
    fun add(kg: Double, at: Double) {
        if (isComplete || !kg.isFinite() || !at.isFinite()) return

        peakKg = maxOf(peakKg, kg)
        accrueWindow(kg, at)

        if (kg >= releaseKg) {
            releasedAt = null
            return
        }
        // `hasPulled` IS "has ever been above the threshold" — `peakKg` is a running
        // maximum, so no separate flag can drift out of step with it.
        if (!hasPulled) return
        val since = releasedAt ?: at
        releasedAt = since
        if (at - since >= endsAfter) isComplete = true
    }

    /// Take what has been pulled so far — the manual "Done", and the timeout.
    /// Idempotent.
    private fun accrueWindow(kg: Double, at: Double) {
        val window = window ?: return
        val start = windowStart
        if (start == null) {
            if (kg < releaseKg) return
            windowStart = at
            lastT = at
            lastKg = kg
            return
        }
        val previous = lastT ?: return
        if (heldSeconds >= window || windowBroken) return
        val until = minOf(at, start + window)
        val dt = until - previous
        if (dt <= 0) return
        // The previous reading stood until now, whatever this one is.
        impulse += lastKg * dt
        if (kg < releaseKg && at < start + window) {
            heldSeconds = until - start
            windowBroken = true
            return
        }
        // Exactly `window` once closed: `(start + window) - start` need not round-trip.
        heldSeconds = if (at >= start + window) window else minOf(window, until - start)
        lastT = at
        lastKg = kg
    }

    fun finish() {
        isComplete = true
    }

    companion object {
        /// Below this you are off the edge (the runner's default threshold order). Used in
        /// BOTH directions — crossing it arms the attempt, staying below ends one — so
        /// there is one number rather than two that could disagree.
        const val releaseKg: Double = 2.0

        /// Off the edge this long, after pulling, ends a single-test attempt. Erring late is cheap:
        /// ending early on a re-grip throws away an effort someone paid for.
        const val releaseSeconds: Double = 2.0
    }
}
