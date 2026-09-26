// SPDX-License-Identifier: MPL-2.0
// Original contributions Copyright 2026 Nuri Bruner.

package run.nuri.getagrip.engine

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

// TRANSLATION NOTE (from Shared/Engine/CriticalForce.swift):
//   - Swift's enums with associated values (`Phase`, `VoidReason`, `Cue`,
//     `CriticalForceFailure`) become sealed interfaces of data classes and objects, as in
//     `SessionRunner`, so `assertEquals` on a list of cues works as `XCTAssertEqual` does.
//   - `Result<CriticalForceResult, CriticalForceFailure>` becomes `CriticalForceOutcome`:
//     Kotlin's own `Result` only carries a `Throwable`, and a failure here is a value.
//   - `ClosedRange<Int>` is `IntRange`; `Data` is `ByteArray` for the trace and the
//     canonical JSON text (`BlobCodec`) for the rep blob, like every other blob column
//     on this side.
//   - `CriticalForceTest` is a Swift value-type `struct`; here it is a class with the
//     same members. Nothing copies a test.
//   - `Double.rounded()` is ties-away-from-zero; spelled out in `roundedAwayFromZero`.

// MARK: - The protocol

/// **The critical force test: 24 all-out pulls, 7 s on and 3 s off, on a clock that never
/// waits for you.**
///
/// Critical force (CF) is the highest intermittent force the finger flexors hold at a
/// metabolic steady state; W′ is the finite impulse available above it. The 4-minute
/// all-out test (Giles et al. 2021, IJSPP) drives the reserve to empty, and the force the
/// climber can still produce once it has plateaued is CF. Research brief:
/// `docs/CRITICAL_FORCE.md`.
///
/// **THE CADENCE IS FIXED, AND THAT IS THE SCIENCE, NOT A LIMITATION.** CF is only
/// defined for a given work-to-rest ratio. Changing the ratio moves both CF and W′: in one
/// study the same elbow flexors measured 28 % of max held continuously and 41 % pulled
/// 3 s on, 2 s off. Letting the rest wait for release (the routine runner's
/// `waitForReleaseBeforeRest`) would stretch the duty cycle by however long each pull
/// overran, by a different amount on every test. The test would then disagree with
/// itself, and comparing with six weeks ago is the whole point of it.
///
/// **What this app does differently: every number comes from force INSIDE the 7-second
/// windows.** Climbers do not execute a perfect square wave (Giles 2019 names it; Baláš
/// 2024 measured 6.6 s pulls, not 7), and the published recommendation is to measure the
/// work actually done. Force pulled after the bell counts for nothing, because it only
/// spent your own rest. It is reported as a rest not kept and never credited.
data class CriticalForceProtocol(
    val workSeconds: Double = 7.0,
    val restSeconds: Double = 3.0,
    val reps: Int = 24,
) {
    val cycleSeconds: Double get() = workSeconds + restSeconds

    /// First pull to last bell. The test does not end with a rest.
    val totalSeconds: Double get() = reps.toDouble() * cycleSeconds - restSeconds

    /// Stable wire form for storage and export: `7:3x24`.
    val key: String get() = "${format(workSeconds)}:${format(restSeconds)}x$reps"

    fun workStart(rep: Int): Double = rep.toDouble() * cycleSeconds
    fun workEnd(rep: Int): Double = workStart(rep) + workSeconds

    companion object {
        val standard = CriticalForceProtocol()

        /// `key`'s inverse, total: an unreadable key from a newer build reads as the
        /// standard protocol, which is what every record so far used.
        fun fromKey(key: String): CriticalForceProtocol {
            val parts = key.split(':', 'x').filter { it.isNotEmpty() }.mapNotNull { it.toDoubleOrNull() }
            // Finite throughout, as on iOS, where a non-finite part traps `Int(_:)`.
            if (parts.size == 3 && parts.all { it.isFinite() } && parts[0] > 0 && parts[1] > 0 &&
                parts[2] >= 1 && parts[2] <= Int.MAX_VALUE) {
                return CriticalForceProtocol(parts[0], parts[1], parts[2].toInt())
            }
            return CriticalForceProtocol()
        }

        private fun format(value: Double): String =
            if (value == floor(value) && abs(value) < 1e15) value.toLong().toString() else value.toString()
    }
}

/// Every tunable of the test, named once.
object CriticalForceRules {
    /// The first pull over this starts rep 1, so the countdown is YOUR pull, not a
    /// 10-second guess (Frez's one good idea). Above load-cell drift and the weight of a
    /// resting hand, and far below any real all-out pull.
    const val startKg: Double = 4.0

    /// On the edge, for counting load held into a rest. Same number as `MaxAttempt`, so
    /// "on the edge" means one thing across the app.
    const val onEdgeKg: Double = MaxAttempt.releaseKg

    /// The fewest reps that can carry a result. Giles 2021 found the last-six average
    /// stable at about 159 s, which is 16 cycles. Frez's "complete 16 sets" is the same
    /// number, and an interruption after it ends the test instead of voiding it.
    const val minRepsForResult = 16

    /// CF is the mean force of this many final reps: the published convention (Giles
    /// 2021, Lattice, Tindeq), so the number compares with the norms and the other apps.
    const val criticalForceReps = 6

    /// Of those final reps, at least this many need enough data to average. A rep lost to
    /// the radio is not a rep the climber failed.
    const val minValidFinalReps = 4

    /// End force: the last second of each of the last three pulls. Baláš 2024 found it
    /// closer to the load people can actually sustain than the mean. It is stored, not
    /// headlined.
    const val endForceReps = 3
    const val endWindowSeconds: Double = 1.0

    /// More than this held into a rest means that rest was not kept.
    const val restKeptLimitSeconds: Double = 1.0

    /// A window's summary waits this long past its bell, so readings still in flight from
    /// the radio land in the window they belong to.
    const val deliverySettleSeconds: Double = 0.4

    /// Readings further apart than this leave a hole. Nothing is interpolated across it.
    const val gapSeconds: Double = 0.25

    /// A window with less data than this fraction has no mean.
    const val minCoverage: Double = 0.5
}

// MARK: - Readings and results

/// One reading, `t` in seconds from the start of rep 1.
data class CriticalForcePoint(val t: Double, val kg: Double)

/// One pull, summarised from the force inside its 7-second window.
data class CriticalForceRep(
    /// 0-based.
    val index: Int,
    /// Mean force over the part of the window that has data. null when too little arrived.
    val meanKg: Double?,
    val peakKg: Double,
    /// Mean over the window's last `endWindowSeconds`.
    val endKg: Double?,
    /// Force × time actually measured inside the window.
    val impulseKgS: Double,
    /// Fraction of the window covered by readings, 0…1.
    val coverage: Double,
    /// Seconds on the edge during the rest after this pull. null for the final pull, which
    /// has no rest after it.
    val restLoadSeconds: Double?,
) : JsonEncodable {

    val keptRest: Boolean? get() = restLoadSeconds?.let { it <= CriticalForceRules.restKeptLimitSeconds }

    /// The Swift `Codable` key names; an absent optional is omitted.
    override fun toJson(): JsonElement {
        val fields = linkedMapOf<String, JsonElement>(
            "index" to JsonPrimitive(index),
        )
        meanKg?.let { fields["meanKg"] = JsonPrimitive(it) }
        fields["peakKg"] = JsonPrimitive(peakKg)
        endKg?.let { fields["endKg"] = JsonPrimitive(it) }
        fields["impulseKgS"] = JsonPrimitive(impulseKgS)
        fields["coverage"] = JsonPrimitive(coverage)
        restLoadSeconds?.let { fields["restLoadSeconds"] = JsonPrimitive(it) }
        return JsonObject(fields)
    }

    companion object {
        /// STRICT, like the synthesized `Decodable`: a missing required key is no rep.
        fun fromJson(element: JsonElement?): CriticalForceRep? {
            val o = JsonRead.obj(element) ?: return null
            return CriticalForceRep(
                index = JsonRead.int(o["index"]) ?: return null,
                meanKg = o.optionalDouble("meanKg"),
                peakKg = JsonRead.double(o["peakKg"]) ?: return null,
                endKg = o.optionalDouble("endKg"),
                impulseKgS = JsonRead.double(o["impulseKgS"]) ?: return null,
                coverage = JsonRead.double(o["coverage"]) ?: return null,
                restLoadSeconds = o.optionalDouble("restLoadSeconds"),
            )
        }
    }
}

data class CriticalForceResult(
    val protocolUsed: CriticalForceProtocol,
    /// Mean force over the final `criticalForceReps` reps.
    val criticalForceKg: Double,
    /// Impulse above CF inside the work windows, kg·s.
    val wPrimeKgS: Double,
    /// The hardest single reading of the test.
    val peakKg: Double,
    val endForceKg: Double?,
    val reps: List<CriticalForceRep>,
    /// 1-based, inclusive: the reps CF is the mean of, e.g. 19…24.
    val criticalForceReps: IntRange,
) {
    val repsRun: Int get() = reps.size
    val restsTotal: Int get() = reps.count { it.restLoadSeconds != null }
    val restsKept: Int get() = reps.count { it.keptRest == true }

    fun percentOf(referenceKg: Double?): Double? {
        if (referenceKg == null || referenceKg <= 0) return null
        return criticalForceKg / referenceKg * 100
    }
}

sealed interface CriticalForceFailure {
    /// Stopped before the force could plateau.
    data class TooFewReps(val run: Int) : CriticalForceFailure

    /// The gauge's data had too many holes in the final reps to average.
    data object TooLittleData : CriticalForceFailure

    /// Nobody pulled.
    data object NoPull : CriticalForceFailure
}

/// Swift's `Result<CriticalForceResult, CriticalForceFailure>`.
sealed interface CriticalForceOutcome {
    data class Success(val result: CriticalForceResult) : CriticalForceOutcome
    data class Failure(val failure: CriticalForceFailure) : CriticalForceOutcome

    fun getOrNull(): CriticalForceResult? = (this as? Success)?.result
}

// MARK: - The analysis

/// Pure: readings in, result out. It runs once, full-resolution, when the test ends,
/// and again over a stored trace whenever a definition needs recomputing. The field has
/// changed its mind about which number to call CF three times in five years, so the
/// evidence is kept rather than one reading of it.
object CriticalForceAnalysis {

    fun analyze(
        points: List<CriticalForcePoint>,
        repsRun: Int,
        protocol: CriticalForceProtocol = CriticalForceProtocol.standard,
    ): CriticalForceOutcome {
        val proto = protocol
        val run = min(max(repsRun, 0), proto.reps)
        if (run < CriticalForceRules.minRepsForResult) {
            return CriticalForceOutcome.Failure(CriticalForceFailure.TooFewReps(run))
        }

        val reps = (0 until run).map { summarize(it, points, proto, isFinal = it == run - 1) }

        val finalRange = (run - CriticalForceRules.criticalForceReps) until run
        val finalMeans = finalRange.mapNotNull { reps[it].meanKg }
        if (finalMeans.size < CriticalForceRules.minValidFinalReps) {
            return CriticalForceOutcome.Failure(CriticalForceFailure.TooLittleData)
        }
        val cf = finalMeans.sum() / finalMeans.size.toDouble()
        if (cf < CriticalForceRules.onEdgeKg) return CriticalForceOutcome.Failure(CriticalForceFailure.NoPull)

        val endValues = ((run - CriticalForceRules.endForceReps) until run).mapNotNull { reps[it].endKg }
        val endForce = if (endValues.isEmpty()) null else endValues.sum() / endValues.size.toDouble()

        var wPrime = 0.0
        for (rep in 0 until run) {
            wPrime += integrate(points, proto.workStart(rep), proto.workEnd(rep), above = cf).area
        }

        return CriticalForceOutcome.Success(
            CriticalForceResult(
                protocolUsed = proto,
                criticalForceKg = cf,
                wPrimeKgS = wPrime,
                peakKg = reps.maxOfOrNull { it.peakKg } ?: 0.0,
                endForceKg = endForce,
                reps = reps,
                criticalForceReps = (finalRange.first + 1)..(finalRange.last + 1),
            )
        )
    }

    /// One window's numbers. Also what the live screen draws as each pull closes, so the
    /// bar you watch and the number you save come from the same arithmetic.
    fun summarize(
        rep: Int,
        points: List<CriticalForcePoint>,
        protocol: CriticalForceProtocol,
        isFinal: Boolean,
    ): CriticalForceRep {
        val start = protocol.workStart(rep)
        val end = protocol.workEnd(rep)
        val window = integrate(points, start, end)
        val tail = integrate(points, end - CriticalForceRules.endWindowSeconds, end)
        val coverage = window.covered / protocol.workSeconds
        val mean = if (coverage >= CriticalForceRules.minCoverage) window.area / window.covered else null
        val tailEnough = tail.covered >= CriticalForceRules.endWindowSeconds * CriticalForceRules.minCoverage
        val rest: Double? = if (isFinal) null
        else timeAbove(CriticalForceRules.onEdgeKg, points, end, start + protocol.cycleSeconds)
        return CriticalForceRep(
            index = rep,
            meanKg = mean,
            peakKg = window.peak,
            endKg = if (tailEnough) tail.area / tail.covered else null,
            impulseKgS = window.area,
            coverage = min(1.0, coverage),
            restLoadSeconds = rest,
        )
    }

    // MARK: Integration

    data class Integral(val area: Double = 0.0, val covered: Double = 0.0, val peak: Double = 0.0)

    /// Trapezoids between neighbouring readings, clipped to [from, to). With `above`, the
    /// area of the part over that line only, crossings included. A pair of readings
    /// further apart than `gapSeconds` is a hole: no area and no coverage.
    fun integrate(points: List<CriticalForcePoint>, from: Double, to: Double, above: Double = 0.0): Integral {
        if (!(to > from) || points.size < 2) return Integral()
        var area = 0.0
        var covered = 0.0
        var peak = 0.0
        var i = max(0, firstIndex(points, from) - 1)
        while (i + 1 < points.size && points[i].t < to) {
            val a = points[i]
            val b = points[i + 1]
            i += 1
            val dt = b.t - a.t
            if (!(dt > 0 && dt <= CriticalForceRules.gapSeconds)) continue
            val s = max(a.t, from)
            val e = min(b.t, to)
            if (!(e > s)) continue
            val ks = a.kg + (b.kg - a.kg) * (s - a.t) / dt
            val ke = a.kg + (b.kg - a.kg) * (e - a.t) / dt
            covered += e - s
            area += areaAbove(above, ks, ke, e - s)
            if (a.t >= from) peak = max(peak, a.kg)
            if (b.t < to) peak = max(peak, b.kg)
        }
        return Integral(area, covered, peak)
    }

    /// Seconds the linear force trace spends at or above `threshold` inside [from, to).
    fun timeAbove(threshold: Double, points: List<CriticalForcePoint>, from: Double, to: Double): Double {
        if (!(to > from) || points.size < 2) return 0.0
        var seconds = 0.0
        var i = max(0, firstIndex(points, from) - 1)
        while (i + 1 < points.size && points[i].t < to) {
            val a = points[i]
            val b = points[i + 1]
            i += 1
            val dt = b.t - a.t
            if (!(dt > 0 && dt <= CriticalForceRules.gapSeconds)) continue
            val s = max(a.t, from)
            val e = min(b.t, to)
            if (!(e > s)) continue
            val ks = a.kg + (b.kg - a.kg) * (s - a.t) / dt
            val ke = a.kg + (b.kg - a.kg) * (e - a.t) / dt
            seconds += fractionAbove(threshold, ks, ke) * (e - s)
        }
        return seconds
    }

    /// ∫ max(0, F − threshold) over one linear segment.
    private fun areaAbove(threshold: Double, ks: Double, ke: Double, duration: Double): Double {
        val a = ks - threshold
        val b = ke - threshold
        if (a >= 0 && b >= 0) return (a + b) / 2 * duration
        if (a <= 0 && b <= 0) return 0.0
        // One crossing: only the triangle above the line counts.
        val high = max(a, b)
        val fraction = high / (abs(a) + abs(b))
        return high * fraction * duration / 2
    }

    private fun fractionAbove(threshold: Double, ks: Double, ke: Double): Double {
        val a = ks - threshold
        val b = ke - threshold
        if (a >= 0 && b >= 0) return 1.0
        if (a < 0 && b < 0) return 0.0
        return max(a, b) / (abs(a) + abs(b))
    }

    /// Binary search: the first reading at or after `t`.
    fun firstIndex(points: List<CriticalForcePoint>, notBefore: Double): Int {
        var lo = 0
        var hi = points.size
        while (lo < hi) {
            val mid = (lo + hi) / 2
            if (points[mid].t < notBefore) lo = mid + 1 else hi = mid
        }
        return lo
    }
}

// MARK: - The live test

/// The test as it runs. Pure, like `SessionRunner`: readings and clock ticks go in, cues
/// come out, and nothing here reads a clock or touches a device, so the whole protocol is
/// testable at `t = 0, 0.1, …`.
///
/// **Two clocks, one epoch.** Readings carry the store's PLAYBACK time (device deltas on a
/// wall-time footing, monotone across tares and counter resets; see
/// `DeviceStore.playbackTime`). Ticks carry wall time on the same epoch. The metronome
/// runs on the ticks, because that is what the climber hears; readings are sorted into
/// windows by their own timestamps, so a batch arriving late still lands in the pull it
/// was measured in.
///
/// **A REP ENDS ITSELF HERE, deliberately, unlike the routine runner.** In training the
/// climber's hand is the authority and the clock waits for it. In this test the clock IS
/// the protocol: coming off the edge mid-pull is simply recorded as low force, which is
/// the truthful value of an all-out effort.
class CriticalForceTest(val proto: CriticalForceProtocol = CriticalForceProtocol.standard) {

    sealed interface Phase {
        /// Waiting for the first pull over `startKg`.
        data object Armed : Phase
        data class Pulling(val rep: Int) : Phase
        data class Resting(val afterRep: Int) : Phase

        /// The last bell has rung; readings still in flight are allowed to land.
        data object Settling : Phase
        data object Finished : Phase
        data class Voided(val reason: VoidReason) : Phase

        val isRunning: Boolean get() = this is Pulling || this is Resting || this == Settling
    }

    enum class VoidReason {
        /// Stopped, or interrupted, before `minRepsForResult`.
        tooFewReps,
        lostGauge,
        leftApp,
    }

    sealed interface Cue {
        data class Pull(val rep: Int) : Cue
        data class LetGo(val rep: Int) : Cue

        /// Seconds until the next pull.
        data class Countdown(val seconds: Int) : Cue
        data object Finished : Cue
        data object Voided : Cue
    }

    var phase: Phase = Phase.Armed
        private set

    /// Playback time of rep 1's start.
    var anchor: Double? = null
        private set

    private val _points = ArrayList<CriticalForcePoint>()
    val points: List<CriticalForcePoint> get() = _points

    /// Windows closed so far, summarised: the bars the screen draws as the plateau forms.
    private val _closedReps = ArrayList<CriticalForceRep>()
    val closedReps: List<CriticalForceRep> get() = _closedReps

    /// Reps whose bell has rung and which count toward the result.
    var repsRun = 0
        private set
    private var lastCountdown: Int? = null
    private var settleUntil: Double? = null

    val isRunning: Boolean get() = phase.isRunning

    /// Whether ending now keeps a result: `minRepsForResult` bells have rung.
    val canFinishEarly: Boolean get() = isRunning && repsRun >= CriticalForceRules.minRepsForResult

    /// Seconds left in the current window.
    fun remaining(now: Double): Double {
        val anchor = anchor ?: return proto.workSeconds
        val elapsed = now - anchor
        return when (val p = phase) {
            is Phase.Pulling -> max(0.0, proto.workEnd(p.rep) - elapsed)
            is Phase.Resting -> max(0.0, proto.workStart(p.afterRep + 1) - elapsed)
            else -> 0.0
        }
    }

    /// Every pull's average so far, as the screen draws it: closed pulls locked, a pull
    /// whose bell has rung frozen at its window, and the pull in progress LIVE, its
    /// running average rising and falling until the bell locks it. Same integral as the
    /// result, so the bar you watch is the number that gets saved.
    fun displayMeans(): List<Double?> {
        val means: MutableList<Double?> = _closedReps.map { it.meanKg }.toMutableList()
        val started = when (val p = phase) {
            is Phase.Pulling -> p.rep + 1
            is Phase.Resting, Phase.Settling -> repsRun
            Phase.Finished -> repsRun
            else -> 0
        }
        val last = _points.lastOrNull()
        if (started <= means.size || last == null) return means
        for (rep in means.size until started) {
            val from = proto.workStart(rep)
            val to = min(proto.workEnd(rep), last.t)
            val window = CriticalForceAnalysis.integrate(_points, from, to)
            // A live bar needs a moment of data before it means anything.
            means.add(if (window.covered >= 0.2) window.area / window.covered else null)
        }
        return means
    }

    // MARK: Events

    fun sample(kg: Double, at: Double): List<Cue> {
        if (!kg.isFinite() || !at.isFinite()) return emptyList()
        return when (phase) {
            Phase.Armed -> {
                if (kg < CriticalForceRules.startKg) return emptyList()
                anchor = at
                _points.add(CriticalForcePoint(0.0, kg))
                phase = Phase.Pulling(0)
                listOf(Cue.Pull(0))
            }
            is Phase.Pulling, is Phase.Resting, Phase.Settling -> {
                val anchor = anchor ?: return emptyList()
                val rel = at - anchor
                // Monotone by construction upstream; a stray older reading is dropped
                // rather than sorted in, so the integrals never see time run backwards.
                if (rel < (_points.lastOrNull()?.t ?: 0.0) || rel > proto.totalSeconds + 1) return emptyList()
                _points.add(CriticalForcePoint(rel, kg))
                emptyList()
            }
            Phase.Finished, is Phase.Voided -> emptyList()
        }
    }

    fun tick(now: Double): List<Cue> {
        val anchor = anchor ?: return emptyList()
        if (!isRunning) return emptyList()
        val elapsed = now - anchor
        val cues = ArrayList<Cue>()

        if (phase == Phase.Settling) {
            val until = settleUntil
            if (until != null && elapsed >= until) close(repsRun, final = true)
            return cues
        }

        val rep = min((elapsed / proto.cycleSeconds).toInt(), proto.reps - 1)
        val intoCycle = elapsed - proto.workStart(rep)

        // Bells that have rung. Several at once only after a stalled main thread.
        val rung = min(proto.reps, floor((elapsed - proto.workSeconds) / proto.cycleSeconds).toInt() + 1)
        if (rung > repsRun) {
            for (bell in repsRun until rung) cues.add(Cue.LetGo(bell))
            repsRun = rung
        }

        if (repsRun >= proto.reps) {
            phase = Phase.Settling
            settleUntil = proto.totalSeconds + CriticalForceRules.deliverySettleSeconds
            cues.add(Cue.Finished)
            closeSettled(elapsed)
            return cues
        }

        if (intoCycle < proto.workSeconds) {
            if (phase != Phase.Pulling(rep)) {
                phase = Phase.Pulling(rep)
                lastCountdown = null
                cues.add(Cue.Pull(rep))
            }
        } else {
            phase = Phase.Resting(rep)
            val untilPull = proto.cycleSeconds - intoCycle
            val whole = ceil(untilPull).toInt()
            // 2, 1: the bell itself was the "3".
            if (whole in 1..2 && lastCountdown != whole) {
                lastCountdown = whole
                cues.add(Cue.Countdown(whole))
            }
        }
        closeSettled(elapsed)
        return cues
    }

    /// End by hand. Keeps a result once `minRepsForResult` bells have rung; before that
    /// there is nothing to keep.
    fun stop(now: Double): List<Cue> {
        if (!isRunning) {
            if (phase == Phase.Armed) phase = Phase.Voided(VoidReason.tooFewReps)
            return emptyList()
        }
        if (!canFinishEarly) {
            phase = Phase.Voided(VoidReason.tooFewReps)
            return listOf(Cue.Voided)
        }
        return beginSettling(now)
    }

    /// The gauge dropped or the app left the screen. Past `minRepsForResult` that ENDS
    /// the test with what was run; before it, the test is void. A pause is never offered:
    /// W′ refills during it, so a resumed test measures something else.
    fun interrupt(reason: VoidReason, now: Double): List<Cue> {
        if (!isRunning) {
            if (phase == Phase.Armed) phase = Phase.Voided(reason)
            return emptyList()
        }
        if (!canFinishEarly) {
            phase = Phase.Voided(reason)
            return listOf(Cue.Voided)
        }
        return beginSettling(now)
    }

    /// The outcome, once `finished`.
    fun result(): CriticalForceOutcome? {
        if (phase != Phase.Finished) return null
        return CriticalForceAnalysis.analyze(_points, repsRun, proto)
    }

    // MARK: Internals

    private fun beginSettling(now: Double): List<Cue> {
        val anchor = anchor ?: return emptyList()
        val elapsed = now - anchor
        if (phase == Phase.Settling) return emptyList()
        phase = Phase.Settling
        // The last counted bell rang at `workEnd(repsRun - 1)`; wait out its delivery.
        val until = max(elapsed, proto.workEnd(repsRun - 1) + CriticalForceRules.deliverySettleSeconds)
        settleUntil = until
        if (elapsed >= until) close(repsRun, final = true)
        return listOf(Cue.Finished)
    }

    /// Summarise every window whose rest, and its delivery grace, has passed: a bar
    /// appears a moment into the next pull, complete with whether its rest was kept.
    private fun closeSettled(elapsed: Double) {
        var n = _closedReps.size
        while (n < repsRun && elapsed >= proto.workStart(n + 1) + CriticalForceRules.deliverySettleSeconds) {
            n += 1
        }
        close(n, final = false)
    }

    private fun close(until: Int, final: Boolean) {
        while (_closedReps.size < until) {
            val index = _closedReps.size
            _closedReps.add(
                CriticalForceAnalysis.summarize(index, _points, proto, isFinal = final && index == repsRun - 1)
            )
        }
        if (final) phase = Phase.Finished
    }
}

// MARK: - The stored trace

/// A test's force trace, kept so a definition can be recomputed later: 20 readings a
/// second, each the mean of its slot, in centi-kilograms. About 10 KB for four minutes.
/// This is the one place the app keeps raw force. Routine sessions still store only
/// per-rep summaries.
///
/// Byte layout, identical to iOS: `[version=1, hz, count as UInt32 LE]` then `count`
/// little-endian Int16 centi-kilograms, `Int16.MIN_VALUE` for a slot with no reading.
object CriticalForceTrace {
    const val version: Byte = 1
    const val hz = 20

    /// A slot with no reading. Distinct from any real value.
    private const val hole: Int = Short.MIN_VALUE.toInt()

    fun encode(points: List<CriticalForcePoint>, hz: Int = CriticalForceTrace.hz): ByteArray {
        val last = points.lastOrNull()
        if (last == null || !(last.t >= 0)) return byteArrayOf(version, hz.toByte(), 0, 0, 0, 0)
        val count = (last.t * hz.toDouble()).toInt() + 1
        val sums = DoubleArray(count)
        val counts = IntArray(count)
        for (point in points) {
            if (!(point.t >= 0)) continue
            val slot = min(count - 1, (point.t * hz.toDouble()).toInt())
            sums[slot] += point.kg
            counts[slot] += 1
        }
        val out = ByteArray(6 + count * 2)
        out[0] = version
        out[1] = hz.toByte()
        out[2] = (count and 0xFF).toByte()
        out[3] = ((count ushr 8) and 0xFF).toByte()
        out[4] = ((count ushr 16) and 0xFF).toByte()
        out[5] = ((count ushr 24) and 0xFF).toByte()
        for (slot in 0 until count) {
            val stored = if (counts[slot] == 0) hole else {
                val centi = roundedAwayFromZero(sums[slot] / counts[slot].toDouble() * 100)
                val clamped = centi.coerceIn(Short.MIN_VALUE.toDouble(), Short.MAX_VALUE.toDouble()).toInt()
                max(hole + 1, clamped)
            }
            out[6 + slot * 2] = (stored and 0xFF).toByte()
            out[7 + slot * 2] = ((stored shr 8) and 0xFF).toByte()
        }
        return out
    }

    /// Total: malformed data decodes to what it can, never traps.
    fun decode(data: ByteArray): List<CriticalForcePoint> {
        if (data.size < 6 || data[0] != version || (data[1].toInt() and 0xFF) == 0) return emptyList()
        val rate = (data[1].toInt() and 0xFF).toDouble()
        val declared = (data[2].toLong() and 0xFF) or
            ((data[3].toLong() and 0xFF) shl 8) or
            ((data[4].toLong() and 0xFF) shl 16) or
            ((data[5].toLong() and 0xFF) shl 24)
        val available = (data.size - 6) / 2
        val slots = min(declared, available.toLong()).toInt()
        val points = ArrayList<CriticalForcePoint>(slots)
        for (slot in 0 until slots) {
            val lo = data[6 + slot * 2].toInt() and 0xFF
            val hi = data[7 + slot * 2].toInt() and 0xFF
            val value = ((hi shl 8) or lo).toShort().toInt()
            if (value == hole) continue
            points.add(CriticalForcePoint((slot.toDouble() + 0.5) / rate, value.toDouble() / 100))
        }
        return points
    }

    private fun roundedAwayFromZero(value: Double): Double =
        if (value < 0) -floor(-value + 0.5) else floor(value + 0.5)
}

// MARK: - The rep blob

/// The rep blob. Canonical JSON (`BlobCodec`) with the Swift `Codable` key names. The
/// decode is all-or-nothing, like `JSONDecoder` on an array: a blob that does not read is
/// no reps, never a partial list that would renumber the pulls.
object CriticalForceRepsCodec {
    fun encode(reps: List<CriticalForceRep>): String = BlobCodec.encodeAll(reps) ?: ""

    fun decode(text: String): List<CriticalForceRep> {
        val array = BlobCodec.parse(text) as? JsonArray ?: return emptyList()
        val out = ArrayList<CriticalForceRep>(array.size)
        for (element in array) out.add(CriticalForceRep.fromJson(element) ?: return emptyList())
        return out
    }
}

// MARK: - The hands

/// How the hands take the test, in the routine builder's own words.
///
/// There is deliberately no "alternate each pull". L R L R would give each hand 7 s on and
/// 13 s off, a different duty cycle, so the number would read far above the published
/// 7:3 test and compare with nothing, including your own tests.
sealed interface CriticalForceHands {
    /// All 24 pulls on one hand, then all 24 on the other. Two results, one Save.
    data class OneAtATime(val first: Side) : CriticalForceHands

    /// Both hands together through one gauge. One result.
    data object BothHands : CriticalForceHands

    /// One hand only.
    data class Single(val side: Side) : CriticalForceHands

    val sides: List<Side>
        get() = when (this) {
            is OneAtATime -> if (first == Side.right) listOf(Side.right, Side.left) else listOf(Side.left, Side.right)
            BothHands -> listOf(Side.both)
            is Single -> listOf(if (side == Side.right) Side.right else Side.left)
        }
}
