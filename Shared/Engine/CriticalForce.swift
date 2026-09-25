// SPDX-License-Identifier: MPL-2.0
// Original contributions Copyright 2026 Nuri Bruner.

import Foundation

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
struct CriticalForceProtocol: Hashable, Sendable {
    var workSeconds: Double = 7
    var restSeconds: Double = 3
    var reps: Int = 24

    static let standard = CriticalForceProtocol()

    var cycleSeconds: Double { workSeconds + restSeconds }
    /// First pull to last bell. The test does not end with a rest.
    var totalSeconds: Double { Double(reps) * cycleSeconds - restSeconds }

    /// Stable wire form for storage and export: `7:3x24`.
    var key: String {
        "\(Self.format(workSeconds)):\(Self.format(restSeconds))x\(reps)"
    }

    /// `key`'s inverse, total: an unreadable key from a newer build reads as the standard
    /// protocol, which is what every record so far used.
    init(key: String) {
        let parts = key.split(whereSeparator: { $0 == ":" || $0 == "x" }).compactMap { Double($0) }
        if parts.count == 3, parts[0] > 0, parts[1] > 0, parts[2] >= 1 {
            self.init(workSeconds: parts[0], restSeconds: parts[1], reps: Int(parts[2]))
        } else {
            self.init()
        }
    }

    init(workSeconds: Double = 7, restSeconds: Double = 3, reps: Int = 24) {
        self.workSeconds = workSeconds
        self.restSeconds = restSeconds
        self.reps = reps
    }

    func workStart(_ rep: Int) -> Double { Double(rep) * cycleSeconds }
    func workEnd(_ rep: Int) -> Double { workStart(rep) + workSeconds }

    private static func format(_ value: Double) -> String {
        value == value.rounded() ? String(Int(value)) : String(value)
    }
}

/// Every tunable of the test, named once.
enum CriticalForceRules {
    /// The first pull over this starts rep 1, so the countdown is YOUR pull, not a
    /// 10-second guess (Frez's one good idea). Above load-cell drift and the weight of a
    /// resting hand, and far below any real all-out pull.
    static let startKg: Double = 4
    /// On the edge, for counting load held into a rest. Same number as `MaxAttempt`, so
    /// "on the edge" means one thing across the app.
    static let onEdgeKg: Double = MaxAttempt.releaseKg
    /// The fewest reps that can carry a result. Giles 2021 found the last-six average
    /// stable at about 159 s, which is 16 cycles. Frez's "complete 16 sets" is the same
    /// number, and an interruption after it ends the test instead of voiding it.
    static let minRepsForResult = 16
    /// CF is the mean force of this many final reps: the published convention (Giles
    /// 2021, Lattice, Tindeq), so the number compares with the norms and the other apps.
    static let criticalForceReps = 6
    /// Of those final reps, at least this many need enough data to average. A rep lost to
    /// the radio is not a rep the climber failed.
    static let minValidFinalReps = 4
    /// End force: the last second of each of the last three pulls. Baláš 2024 found it
    /// closer to the load people can actually sustain than the mean. It is stored, not
    /// headlined.
    static let endForceReps = 3
    static let endWindowSeconds: Double = 1
    /// More than this held into a rest means that rest was not kept.
    static let restKeptLimitSeconds: Double = 1
    /// A window's summary waits this long past its bell, so readings still in flight from
    /// the radio land in the window they belong to.
    static let deliverySettleSeconds: Double = 0.4
    /// Readings further apart than this leave a hole. Nothing is interpolated across it.
    static let gapSeconds: Double = 0.25
    /// A window with less data than this fraction has no mean.
    static let minCoverage: Double = 0.5
}

// MARK: - Readings and results

/// One reading, `t` in seconds from the start of rep 1.
struct CriticalForcePoint: Hashable, Sendable {
    var t: Double
    var kg: Double
}

/// One pull, summarised from the force inside its 7-second window.
struct CriticalForceRep: Hashable, Sendable, Codable {
    /// 0-based.
    var index: Int
    /// Mean force over the part of the window that has data. nil when too little arrived.
    var meanKg: Double?
    var peakKg: Double
    /// Mean over the window's last `endWindowSeconds`.
    var endKg: Double?
    /// Force × time actually measured inside the window.
    var impulseKgS: Double
    /// Fraction of the window covered by readings, 0…1.
    var coverage: Double
    /// Seconds on the edge during the rest after this pull. nil for the final pull, which
    /// has no rest after it.
    var restLoadSeconds: Double?

    var keptRest: Bool? {
        restLoadSeconds.map { $0 <= CriticalForceRules.restKeptLimitSeconds }
    }
}

struct CriticalForceResult: Hashable, Sendable {
    var protocolUsed: CriticalForceProtocol
    /// Mean force over the final `criticalForceReps` reps.
    var criticalForceKg: Double
    /// Impulse above CF inside the work windows, kg·s.
    var wPrimeKgS: Double
    /// The hardest single reading of the test.
    var peakKg: Double
    var endForceKg: Double?
    var reps: [CriticalForceRep]
    /// 1-based, inclusive: the reps CF is the mean of, e.g. 19…24.
    var criticalForceReps: ClosedRange<Int>

    var repsRun: Int { reps.count }
    var restsTotal: Int { reps.filter { $0.restLoadSeconds != nil }.count }
    var restsKept: Int { reps.filter { $0.keptRest == true }.count }

    func percentOf(_ referenceKg: Double?) -> Double? {
        guard let referenceKg, referenceKg > 0 else { return nil }
        return criticalForceKg / referenceKg * 100
    }
}

enum CriticalForceFailure: Error, Hashable, Sendable {
    /// Stopped before the force could plateau.
    case tooFewReps(run: Int)
    /// The gauge's data had too many holes in the final reps to average.
    case tooLittleData
    /// Nobody pulled.
    case noPull
}

// MARK: - The analysis

/// Pure: readings in, result out. It runs once, full-resolution, when the test ends,
/// and again over a stored trace whenever a definition needs recomputing. The field has
/// changed its mind about which number to call CF three times in five years, so the
/// evidence is kept rather than one reading of it.
enum CriticalForceAnalysis {

    static func analyze(_ points: [CriticalForcePoint], repsRun: Int,
                        protocol proto: CriticalForceProtocol = .standard)
        -> Result<CriticalForceResult, CriticalForceFailure> {
        let run = min(max(repsRun, 0), proto.reps)
        guard run >= CriticalForceRules.minRepsForResult else { return .failure(.tooFewReps(run: run)) }

        let reps = (0..<run).map { summarize(rep: $0, of: points, protocol: proto, isFinal: $0 == run - 1) }

        let finalRange = (run - CriticalForceRules.criticalForceReps)..<run
        let finalMeans = finalRange.compactMap { reps[$0].meanKg }
        guard finalMeans.count >= CriticalForceRules.minValidFinalReps else { return .failure(.tooLittleData) }
        let cf = finalMeans.reduce(0, +) / Double(finalMeans.count)
        guard cf >= CriticalForceRules.onEdgeKg else { return .failure(.noPull) }

        let endValues = ((run - CriticalForceRules.endForceReps)..<run).compactMap { reps[$0].endKg }
        let endForce = endValues.isEmpty ? nil : endValues.reduce(0, +) / Double(endValues.count)

        var wPrime = 0.0
        for rep in 0..<run {
            wPrime += integrate(points, from: proto.workStart(rep), to: proto.workEnd(rep),
                                above: cf).area
        }

        return .success(CriticalForceResult(
            protocolUsed: proto,
            criticalForceKg: cf,
            wPrimeKgS: wPrime,
            peakKg: reps.map(\.peakKg).max() ?? 0,
            endForceKg: endForce,
            reps: reps,
            criticalForceReps: (finalRange.lowerBound + 1)...finalRange.upperBound))
    }

    /// One window's numbers. Also what the live screen draws as each pull closes, so the
    /// bar you watch and the number you save come from the same arithmetic.
    static func summarize(rep: Int, of points: [CriticalForcePoint],
                          protocol proto: CriticalForceProtocol, isFinal: Bool) -> CriticalForceRep {
        let start = proto.workStart(rep), end = proto.workEnd(rep)
        let window = integrate(points, from: start, to: end)
        let tail = integrate(points, from: end - CriticalForceRules.endWindowSeconds, to: end)
        let coverage = window.covered / proto.workSeconds
        let mean = coverage >= CriticalForceRules.minCoverage ? window.area / window.covered : nil
        let tailEnough = tail.covered >= CriticalForceRules.endWindowSeconds * CriticalForceRules.minCoverage
        let rest: Double? = isFinal ? nil
            : timeAbove(CriticalForceRules.onEdgeKg, in: points, from: end, to: start + proto.cycleSeconds)
        return CriticalForceRep(index: rep,
                                meanKg: mean,
                                peakKg: window.peak,
                                endKg: tailEnough ? tail.area / tail.covered : nil,
                                impulseKgS: window.area,
                                coverage: min(1, coverage),
                                restLoadSeconds: rest)
    }

    // MARK: Integration

    struct Integral: Equatable {
        var area: Double = 0
        var covered: Double = 0
        var peak: Double = 0
    }

    /// Trapezoids between neighbouring readings, clipped to [from, to). With `above`, the
    /// area of the part over that line only, crossings included. A pair of readings
    /// further apart than `gapSeconds` is a hole: no area and no coverage.
    static func integrate(_ points: [CriticalForcePoint], from: Double, to: Double,
                          above threshold: Double = 0) -> Integral {
        var result = Integral()
        guard to > from, points.count >= 2 else { return result }
        var i = max(0, firstIndex(in: points, notBefore: from) - 1)
        while i + 1 < points.count, points[i].t < to {
            let a = points[i], b = points[i + 1]
            i += 1
            let dt = b.t - a.t
            guard dt > 0, dt <= CriticalForceRules.gapSeconds else { continue }
            let s = max(a.t, from), e = min(b.t, to)
            guard e > s else { continue }
            let ks = a.kg + (b.kg - a.kg) * (s - a.t) / dt
            let ke = a.kg + (b.kg - a.kg) * (e - a.t) / dt
            result.covered += e - s
            result.area += areaAbove(threshold, ks, ke, duration: e - s)
            if a.t >= from { result.peak = max(result.peak, a.kg) }
            if b.t < to { result.peak = max(result.peak, b.kg) }
        }
        return result
    }

    /// Seconds the linear force trace spends at or above `threshold` inside [from, to).
    static func timeAbove(_ threshold: Double, in points: [CriticalForcePoint],
                          from: Double, to: Double) -> Double {
        guard to > from, points.count >= 2 else { return 0 }
        var seconds = 0.0
        var i = max(0, firstIndex(in: points, notBefore: from) - 1)
        while i + 1 < points.count, points[i].t < to {
            let a = points[i], b = points[i + 1]
            i += 1
            let dt = b.t - a.t
            guard dt > 0, dt <= CriticalForceRules.gapSeconds else { continue }
            let s = max(a.t, from), e = min(b.t, to)
            guard e > s else { continue }
            let ks = a.kg + (b.kg - a.kg) * (s - a.t) / dt
            let ke = a.kg + (b.kg - a.kg) * (e - a.t) / dt
            seconds += fractionAbove(threshold, ks, ke) * (e - s)
        }
        return seconds
    }

    /// ∫ max(0, F − threshold) over one linear segment.
    private static func areaAbove(_ threshold: Double, _ ks: Double, _ ke: Double,
                                  duration: Double) -> Double {
        let a = ks - threshold, b = ke - threshold
        if a >= 0 && b >= 0 { return (a + b) / 2 * duration }
        if a <= 0 && b <= 0 { return 0 }
        // One crossing: only the triangle above the line counts.
        let high = max(a, b)
        let fraction = high / (abs(a) + abs(b))
        return high * fraction * duration / 2
    }

    private static func fractionAbove(_ threshold: Double, _ ks: Double, _ ke: Double) -> Double {
        let a = ks - threshold, b = ke - threshold
        if a >= 0 && b >= 0 { return 1 }
        if a < 0 && b < 0 { return 0 }
        return max(a, b) / (abs(a) + abs(b))
    }

    /// Binary search: the first reading at or after `t`.
    static func firstIndex(in points: [CriticalForcePoint], notBefore t: Double) -> Int {
        var lo = 0, hi = points.count
        while lo < hi {
            let mid = (lo + hi) / 2
            if points[mid].t < t { lo = mid + 1 } else { hi = mid }
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
struct CriticalForceTest: Sendable {

    enum Phase: Hashable, Sendable {
        /// Waiting for the first pull over `startKg`.
        case armed
        case pulling(rep: Int)
        case resting(afterRep: Int)
        /// The last bell has rung; readings still in flight are allowed to land.
        case settling
        case finished
        case voided(VoidReason)
    }

    enum VoidReason: Hashable, Sendable {
        /// Stopped, or interrupted, before `minRepsForResult`.
        case tooFewReps
        case lostGauge
        case leftApp
    }

    enum Cue: Hashable, Sendable {
        case pull(rep: Int)
        case letGo(rep: Int)
        /// Seconds until the next pull.
        case countdown(Int)
        case finished
        case voided
    }

    let proto: CriticalForceProtocol
    private(set) var phase: Phase = .armed
    /// Playback time of rep 1's start.
    private(set) var anchor: Double?
    private(set) var points: [CriticalForcePoint] = []
    /// Windows closed so far, summarised: the bars the screen draws as the plateau forms.
    private(set) var closedReps: [CriticalForceRep] = []
    /// Reps whose bell has rung and which count toward the result.
    private(set) var repsRun = 0
    private var lastCountdown: Int?
    private var settleUntil: Double?

    init(protocol proto: CriticalForceProtocol = .standard) {
        self.proto = proto
    }

    var isRunning: Bool {
        switch phase {
        case .pulling, .resting, .settling: true
        default: false
        }
    }

    /// Whether ending now keeps a result: `minRepsForResult` bells have rung.
    var canFinishEarly: Bool { isRunning && repsRun >= CriticalForceRules.minRepsForResult }

    /// Seconds left in the current window.
    func remaining(at now: Double) -> Double {
        guard let anchor else { return proto.workSeconds }
        let elapsed = now - anchor
        switch phase {
        case .pulling(let rep): return max(0, proto.workEnd(rep) - elapsed)
        case .resting(let rep): return max(0, proto.workStart(rep + 1) - elapsed)
        default: return 0
        }
    }

    /// Every pull's average so far, as the screen draws it: closed pulls locked, a pull
    /// whose bell has rung frozen at its window, and the pull in progress LIVE, its
    /// running average rising and falling until the bell locks it. Same integral as the
    /// result, so the bar you watch is the number that gets saved.
    func displayMeans() -> [Double?] {
        var means = closedReps.map(\.meanKg)
        let started: Int = switch phase {
        case .pulling(let rep): rep + 1
        case .resting, .settling: repsRun
        case .finished: repsRun
        default: 0
        }
        guard started > means.count, let last = points.last else { return means }
        for rep in means.count..<started {
            let from = proto.workStart(rep)
            let to = min(proto.workEnd(rep), last.t)
            let window = CriticalForceAnalysis.integrate(points, from: from, to: to)
            // A live bar needs a moment of data before it means anything.
            means.append(window.covered >= 0.2 ? window.area / window.covered : nil)
        }
        return means
    }

    // MARK: Events

    mutating func sample(kg: Double, at t: Double) -> [Cue] {
        guard kg.isFinite, t.isFinite else { return [] }
        switch phase {
        case .armed:
            guard kg >= CriticalForceRules.startKg else { return [] }
            anchor = t
            points.append(CriticalForcePoint(t: 0, kg: kg))
            phase = .pulling(rep: 0)
            return [.pull(rep: 0)]
        case .pulling, .resting, .settling:
            guard let anchor else { return [] }
            let rel = t - anchor
            // Monotone by construction upstream; a stray older reading is dropped rather
            // than sorted in, so the integrals never see time run backwards.
            guard rel >= (points.last?.t ?? 0), rel <= proto.totalSeconds + 1 else { return [] }
            points.append(CriticalForcePoint(t: rel, kg: kg))
            return []
        case .finished, .voided:
            return []
        }
    }

    mutating func tick(now: Double) -> [Cue] {
        guard let anchor, isRunning else { return [] }
        let elapsed = now - anchor
        var cues: [Cue] = []

        if case .settling = phase {
            if let settleUntil, elapsed >= settleUntil { close(until: repsRun, final: true) }
            return cues
        }

        let rep = min(Int(elapsed / proto.cycleSeconds), proto.reps - 1)
        let intoCycle = elapsed - proto.workStart(rep)

        // Bells that have rung. Several at once only after a stalled main thread.
        let rung = min(proto.reps, Int(((elapsed - proto.workSeconds) / proto.cycleSeconds).rounded(.down)) + 1)
        if rung > repsRun {
            for bell in repsRun..<rung { cues.append(.letGo(rep: bell)) }
            repsRun = rung
        }

        if repsRun >= proto.reps {
            phase = .settling
            settleUntil = proto.totalSeconds + CriticalForceRules.deliverySettleSeconds
            cues.append(.finished)
            closeSettled(at: elapsed)
            return cues
        }

        if intoCycle < proto.workSeconds {
            if phase != .pulling(rep: rep) {
                phase = .pulling(rep: rep)
                lastCountdown = nil
                cues.append(.pull(rep: rep))
            }
        } else {
            phase = .resting(afterRep: rep)
            let untilPull = proto.cycleSeconds - intoCycle
            let whole = Int(untilPull.rounded(.up))
            // 2, 1: the bell itself was the "3".
            if whole <= 2, whole >= 1, lastCountdown != whole {
                lastCountdown = whole
                cues.append(.countdown(whole))
            }
        }
        closeSettled(at: elapsed)
        return cues
    }

    /// End by hand. Keeps a result once `minRepsForResult` bells have rung; before that
    /// there is nothing to keep.
    mutating func stop(now: Double) -> [Cue] {
        guard isRunning else {
            if phase == .armed { phase = .voided(.tooFewReps) }
            return []
        }
        guard canFinishEarly else {
            phase = .voided(.tooFewReps)
            return [.voided]
        }
        return beginSettling(now: now)
    }

    /// The gauge dropped or the app left the screen. Past `minRepsForResult` that ENDS
    /// the test with what was run; before it, the test is void. A pause is never offered:
    /// W′ refills during it, so a resumed test measures something else.
    mutating func interrupt(_ reason: VoidReason, now: Double) -> [Cue] {
        guard isRunning else {
            if phase == .armed { phase = .voided(reason) }
            return []
        }
        guard canFinishEarly else {
            phase = .voided(reason)
            return [.voided]
        }
        return beginSettling(now: now)
    }

    /// The outcome, once `finished`.
    func result() -> Result<CriticalForceResult, CriticalForceFailure>? {
        guard phase == .finished else { return nil }
        return CriticalForceAnalysis.analyze(points, repsRun: repsRun, protocol: proto)
    }

    // MARK: Internals

    private mutating func beginSettling(now: Double) -> [Cue] {
        guard let anchor else { return [] }
        let elapsed = now - anchor
        if case .settling = phase { return [] }
        phase = .settling
        // The last counted bell rang at `workEnd(repsRun - 1)`; wait out its delivery.
        settleUntil = max(elapsed, proto.workEnd(repsRun - 1) + CriticalForceRules.deliverySettleSeconds)
        if let settleUntil, elapsed >= settleUntil { close(until: repsRun, final: true) }
        return [.finished]
    }

    /// Summarise every window whose rest, and its delivery grace, has passed: a bar
    /// appears a moment into the next pull, complete with whether its rest was kept.
    private mutating func closeSettled(at elapsed: Double) {
        var n = closedReps.count
        while n < repsRun, elapsed >= proto.workStart(n + 1) + CriticalForceRules.deliverySettleSeconds {
            n += 1
        }
        close(until: n, final: false)
    }

    @discardableResult
    private mutating func close(until n: Int, final: Bool) -> [Cue] {
        while closedReps.count < n {
            let index = closedReps.count
            closedReps.append(CriticalForceAnalysis.summarize(
                rep: index, of: points, protocol: proto, isFinal: final && index == repsRun - 1))
        }
        if final { phase = .finished }
        return []
    }
}

// MARK: - The stored trace

/// A test's force trace, kept so a definition can be recomputed later: 20 readings a
/// second, each the mean of its slot, in centi-kilograms. About 10 KB for four minutes.
/// This is the one place the app keeps raw force. Routine sessions still store only
/// per-rep summaries.
enum CriticalForceTrace {
    static let version: UInt8 = 1
    static let hz = 20
    /// A slot with no reading. Distinct from any real value.
    private static let hole = Int16.min

    static func encode(_ points: [CriticalForcePoint], hz: Int = hz) -> Data {
        guard let last = points.last, last.t >= 0 else { return Data([version, UInt8(hz), 0, 0, 0, 0]) }
        let count = Int(last.t * Double(hz)) + 1
        var sums = [Double](repeating: 0, count: count)
        var counts = [Int](repeating: 0, count: count)
        for point in points where point.t >= 0 {
            let slot = min(count - 1, Int(point.t * Double(hz)))
            sums[slot] += point.kg
            counts[slot] += 1
        }
        var data = Data([version, UInt8(hz)])
        withUnsafeBytes(of: UInt32(count).littleEndian) { data.append(contentsOf: $0) }
        for slot in 0..<count {
            let value: Int16 = counts[slot] == 0 ? hole
                : Int16(clamping: Int((sums[slot] / Double(counts[slot]) * 100).rounded()))
            let stored = counts[slot] == 0 ? hole : max(hole + 1, value)
            withUnsafeBytes(of: stored.littleEndian) { data.append(contentsOf: $0) }
        }
        return data
    }

    /// Total: malformed data decodes to what it can, never traps.
    static func decode(_ data: Data) -> [CriticalForcePoint] {
        let bytes = [UInt8](data)
        guard bytes.count >= 6, bytes[0] == version, bytes[1] > 0 else { return [] }
        let rate = Double(bytes[1])
        let declared = Int(UInt32(bytes[2]) | UInt32(bytes[3]) << 8 | UInt32(bytes[4]) << 16 | UInt32(bytes[5]) << 24)
        let available = (bytes.count - 6) / 2
        var points: [CriticalForcePoint] = []
        points.reserveCapacity(min(declared, available))
        for slot in 0..<min(declared, available) {
            let lo = UInt16(bytes[6 + slot * 2]), hi = UInt16(bytes[7 + slot * 2])
            let value = Int16(bitPattern: lo | hi << 8)
            guard value != hole else { continue }
            points.append(CriticalForcePoint(t: (Double(slot) + 0.5) / rate, kg: Double(value) / 100))
        }
        return points
    }
}

// MARK: - The rep blob

enum CriticalForceRepsCodec {
    static func encode(_ reps: [CriticalForceRep]) -> Data {
        (try? JSONEncoder().encode(reps)) ?? Data()
    }

    static func decode(_ data: Data) -> [CriticalForceRep] {
        (try? JSONDecoder().decode([CriticalForceRep].self, from: data)) ?? []
    }
}

// MARK: - The hands

/// How the hands take the test, in the routine builder's own words.
///
/// There is deliberately no "alternate each pull". L R L R would give each hand 7 s on and
/// 13 s off, a different duty cycle, so the number would read far above the published
/// 7:3 test and compare with nothing, including your own tests.
enum CriticalForceHands: Hashable, Sendable {
    /// All 24 pulls on one hand, then all 24 on the other. Two results, one Save.
    case oneAtATime(first: Side)
    /// Both hands together through one gauge. One result.
    case bothHands
    /// One hand only.
    case single(Side)

    var sides: [Side] {
        switch self {
        case .oneAtATime(let first): first == .right ? [.right, .left] : [.left, .right]
        case .bothHands: [.both]
        case .single(let side): [side == .right ? .right : .left]
        }
    }
}
