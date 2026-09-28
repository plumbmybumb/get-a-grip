// SPDX-License-Identifier: MPL-2.0
// Original contributions Copyright 2026 Nuri Bruner.

package run.nuri.getagrip.engine

/// The live gauge's ESTIMATED rep count: a pull is counted once the load has risen
/// clearly off the gauge and then come back down to the baseline (Nuri, 2026-09-28).
///
/// An estimate, and the screen says so — there is no plan to hold it against, only the
/// shape of the signal. Three rules keep it honest:
/// - **A pull must be real to start**: at least `engageKg` (the same "on the edge" line
///   the max test uses) for `minimumPullSeconds`, so a knock on the gauge is not a rep.
/// - **It ends at the BASELINE, not at a dip.** The release line is a fraction of the
///   pull's own peak, floored near zero, so easing from 40 kg to 25 kg mid-pull is still
///   the same pull, and it has to stay down for `releaseSeconds` so a quick re-grip does
///   not split one pull in two.
/// - **It counts on the way DOWN** — "a spike and then a release", which is also the
///   only moment the pull is known to have happened.
///
/// It assumes a tared gauge: an untared one that rests above `engageKg` never releases,
/// so it counts nothing rather than inventing reps. Pure — no clock, device or store;
/// `t` is the store's monotone playback time in seconds.
///
/// TRANSLATION NOTE (from Shared/Engine/RepEstimator.swift): the Swift struct's
/// `mutating` methods make this a class; `reset()` replaces `self = RepEstimator()`.
class RepEstimator {
    var count: Int = 0
        private set

    /// A pull is under way (confirmed, not yet released).
    var isPulling: Boolean = false
        private set

    /// The pull in progress's highest reading.
    private var peakKg = 0.0
    /// When the load crossed `engageKg`, while the pull is still being confirmed.
    private var engagedSince: Double? = null
    /// When the load most recently fell to the baseline, mid-pull.
    private var releasedSince: Double? = null

    /// Where the load must fall to for the pull under way to end.
    val releaseKg: Double get() = maxOf(releaseFloorKg, releaseFraction * peakKg)

    /// Feed one sample. Returns true when this sample completed a rep.
    fun add(kg: Double, at: Double): Boolean {
        if (!kg.isFinite() || !at.isFinite()) return false

        if (!isPulling) {
            if (kg < engageKg) {
                engagedSince = null
                peakKg = 0.0
                return false
            }
            val since = engagedSince ?: at
            engagedSince = since
            peakKg = maxOf(peakKg, kg)
            if (at - since >= minimumPullSeconds) {
                isPulling = true
                releasedSince = null
            }
            return false
        }

        peakKg = maxOf(peakKg, kg)
        if (kg >= releaseKg) {
            releasedSince = null
            return false
        }
        val since = releasedSince ?: at
        releasedSince = since
        if (at - since < releaseSeconds) return false
        count += 1
        clearPull()
        return true
    }

    /// Drop the pull in progress without counting it — the stream stopped, or the gauge
    /// was zeroed under it, so how it would have ended is unknowable.
    fun cancelPull() = clearPull()

    fun reset() {
        count = 0
        clearPull()
    }

    private fun clearPull() {
        isPulling = false
        peakKg = 0.0
        engagedSince = null
        releasedSince = null
    }

    companion object {
        /// On the edge — `MaxAttempt.releaseKg`, so "pulling" is one idea across the app.
        const val engageKg: Double = MaxAttempt.releaseKg
        /// Above `engageKg` this long before it is a pull.
        const val minimumPullSeconds: Double = 0.2
        /// The baseline line, as a fraction of the pull's own peak…
        const val releaseFraction: Double = 0.15
        /// …but never under this, or load-cell drift could hold a pull open forever.
        const val releaseFloorKg: Double = 1.0
        /// Down at the baseline this long ends the pull.
        const val releaseSeconds: Double = 0.4
    }
}

/// A plain stopwatch: start, pause, reset. Pure — the caller supplies `now` in seconds
/// from a clock that keeps running while the phone sleeps (the gauge screen uses
/// `SystemClock.elapsedRealtimeNanos`), so a paused screen cannot lose time.
///
/// TRANSLATION NOTE (from Shared/Engine/RepEstimator.swift): an immutable data class, so
/// Compose state can hold it and every change is a new value.
data class Stopwatch(
    /// Time banked by earlier runs.
    val accumulated: Double = 0.0,
    /// When the current run began; null while stopped.
    val startedAt: Double? = null,
) {
    val isRunning: Boolean get() = startedAt != null

    fun elapsed(now: Double): Double {
        val started = startedAt ?: return accumulated
        // A clock that steps backwards must not subtract banked time.
        return accumulated + maxOf(0.0, now - started)
    }

    fun started(now: Double): Stopwatch = if (startedAt != null) this else copy(startedAt = now)

    fun paused(now: Double): Stopwatch =
        if (startedAt == null) this else Stopwatch(accumulated = elapsed(now), startedAt = null)

    companion object {
        /// "0:42.3", "12:05.0", "1:02:03.4" — tenths always, hours only when there are any.
        fun label(seconds: Double): String {
            val tenths = kotlin.math.floor(maxOf(0.0, seconds) * 10).toLong()
            val hours = tenths / 36_000
            val minutes = tenths / 600 % 60
            val secs = tenths / 10 % 60
            val tenth = tenths % 10
            val ss = if (secs < 10) "0$secs" else "$secs"
            if (hours > 0) {
                val mm = if (minutes < 10) "0$minutes" else "$minutes"
                return "$hours:$mm:$ss.$tenth"
            }
            return "$minutes:$ss.$tenth"
        }
    }
}
