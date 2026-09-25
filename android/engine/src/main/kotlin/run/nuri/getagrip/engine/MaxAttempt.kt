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
) {

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
    val hasResult: Boolean get() = peakKg >= releaseKg

    /// When the load most recently fell below `releaseKg`.
    private var releasedAt: Double? = null

    /// Feed one sample. `t` is in SECONDS and must increase monotonically; the store's
    /// playback clock is built to guarantee that across tares and device counter resets
    /// (see `DeviceStore.playbackTime`).
    fun add(kg: Double, at: Double) {
        if (isComplete || !kg.isFinite() || !at.isFinite()) return

        peakKg = maxOf(peakKg, kg)

        if (kg >= releaseKg) {
            releasedAt = null
            return
        }
        // `hasResult` IS "has ever been above the threshold" — `peakKg` is a running
        // maximum, so no separate flag can drift out of step with it.
        if (!hasResult) return
        val since = releasedAt ?: at
        releasedAt = since
        if (at - since >= endsAfter) isComplete = true
    }

    /// Take what has been pulled so far — the manual "Done", and the timeout.
    /// Idempotent.
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
