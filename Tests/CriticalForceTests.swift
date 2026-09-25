// SPDX-License-Identifier: MPL-2.0
// Original contributions Copyright 2026 Nuri Bruner.

import XCTest
@testable import Doigt

/// The critical force test: the fixed cadence, the arithmetic, and the honesty rules.
///
/// The ones that matter most are the honesty rules. Force pulled after the bell is never
/// credited, a test stopped before the plateau keeps nothing, and an interruption after it
/// keeps what was really run.
final class CriticalForceTests: XCTestCase {

    private let proto = CriticalForceProtocol.standard
    private let epoch = 800_000_000.0   // playback time is on a reference-date footing

    /// A climber whose all-out pulls decay from 40 kg to a 20 kg plateau.
    private func level(_ rep: Int) -> Double { 20 + 20 * exp(-Double(rep) / 5) }

    /// Force at `rel` seconds into the test: a square-ish pull (0.2 s ramp) held
    /// `holdSeconds` from each window's start, zero otherwise.
    private func force(_ rel: Double, hold holdSeconds: Double = 7,
                       level: (Int) -> Double) -> Double {
        guard rel >= 0 else { return 0 }
        let rep = Int(rel / proto.cycleSeconds)
        guard rep < proto.reps else { return 0 }
        let into = rel - proto.workStart(rep)
        guard into < holdSeconds else { return 0 }
        return level(rep) * min(1, into / 0.2 + 0.5)
    }

    /// Run a whole test at 80 Hz with a tick every 0.1 s. Returns the test and every cue.
    @discardableResult
    private func run(_ test: inout CriticalForceTest, until endRel: Double,
                     hold: Double = 7, level: ((Int) -> Double)? = nil,
                     dropping: (Double) -> Bool = { _ in false }) -> [CriticalForceTest.Cue] {
        let lvl = level ?? self.level
        var cues: [CriticalForceTest.Cue] = []
        // A pull that crosses the start threshold at rel 0.
        cues += test.sample(kg: 5, at: epoch)
        var rel = 1.0 / 80
        var nextTick = 0.1
        while rel <= endRel {
            if !dropping(rel) {
                cues += test.sample(kg: force(rel, hold: hold, level: lvl), at: epoch + rel)
            }
            if rel >= nextTick {
                cues += test.tick(now: epoch + rel)
                nextTick += 0.1
            }
            rel += 1.0 / 80
        }
        return cues
    }

    // MARK: - The protocol

    func testTheStandardProtocolIsSevenThreeTimesTwentyFour() {
        XCTAssertEqual(proto.key, "7:3x24")
        XCTAssertEqual(proto.totalSeconds, 237, accuracy: 1e-9)
        XCTAssertEqual(CriticalForceProtocol(key: "7:3x24"), proto)
        XCTAssertEqual(CriticalForceProtocol(key: "garbage"), proto, "an unreadable key is the standard test")
        XCTAssertEqual(CriticalForceProtocol(key: "1.5:1.5x80").workSeconds, 1.5)
    }

    // MARK: - The cadence

    func testTheFirstPullStartsTheClockAndNothingBeforeIt() {
        var test = CriticalForceTest()
        XCTAssertEqual(test.sample(kg: 3.9, at: epoch), [], "below the start threshold is setting up")
        XCTAssertEqual(test.tick(now: epoch + 30), [], "no clock runs while armed")
        XCTAssertEqual(test.sample(kg: 4.2, at: epoch + 31), [.pull(rep: 0)])
        XCTAssertEqual(test.phase, .pulling(rep: 0))
    }

    func testTheBellRingsOnTheClockAndCountsDownIntoTheNextPull() {
        var test = CriticalForceTest()
        _ = test.sample(kg: 30, at: epoch)
        XCTAssertEqual(test.tick(now: epoch + 6.9), [])
        XCTAssertEqual(test.tick(now: epoch + 7.0), [.letGo(rep: 0)])
        XCTAssertEqual(test.phase, .resting(afterRep: 0))
        XCTAssertEqual(test.tick(now: epoch + 8.0), [.countdown(2)])
        XCTAssertEqual(test.tick(now: epoch + 8.5), [])
        XCTAssertEqual(test.tick(now: epoch + 9.0), [.countdown(1)])
        XCTAssertEqual(test.tick(now: epoch + 10.0), [.pull(rep: 1)])
        XCTAssertEqual(test.remaining(at: epoch + 12.5), 4.5, accuracy: 1e-9)
    }

    /// The rest does NOT wait for release: holding on through the bell changes nothing
    /// about when the next pull starts. That is the protocol; see `CriticalForceProtocol`.
    func testHoldingThroughTheBellDoesNotMoveTheNextPull() {
        var test = CriticalForceTest()
        _ = test.sample(kg: 30, at: epoch)
        for step in 1...95 {
            let rel = Double(step) / 10
            _ = test.sample(kg: 30, at: epoch + rel)   // still pulling at 9.5 s
            _ = test.tick(now: epoch + rel)
        }
        XCTAssertEqual(test.tick(now: epoch + 10), [.pull(rep: 1)])
    }

    func testTheTestFinishesAtTheLastBellWithNoTrailingRest() {
        var test = CriticalForceTest()
        let cues = run(&test, until: proto.totalSeconds + 1)
        XCTAssertEqual(cues.filter { if case .pull = $0 { true } else { false } }.count, 24)
        XCTAssertEqual(cues.filter { if case .letGo = $0 { true } else { false } }.count, 24)
        XCTAssertEqual(cues.last, .finished)
        XCTAssertEqual(test.phase, .finished)
        XCTAssertEqual(test.repsRun, 24)
        XCTAssertEqual(test.closedReps.count, 24)
    }

    /// The bar in progress is LIVE, its running average moving with the pull, and the
    /// bell locks it to exactly the number the result will use.
    func testThePullInProgressHasALiveAverageThatLocksAtTheBell() {
        var test = CriticalForceTest()
        _ = test.sample(kg: 20, at: epoch)
        for i in 1...160 { _ = test.sample(kg: 20, at: epoch + Double(i) / 80) }   // 2 s at 20
        _ = test.tick(now: epoch + 2)
        XCTAssertEqual(test.displayMeans().count, 1)
        XCTAssertEqual(test.displayMeans()[0]!, 20, accuracy: 1e-9)

        for i in 161...559 { _ = test.sample(kg: 40, at: epoch + Double(i) / 80) }   // then 40
        let live = test.displayMeans()[0]!
        XCTAssertGreaterThan(live, 30, "the running average rises with the pull")

        _ = test.tick(now: epoch + 7.05)                                        // the bell
        _ = test.sample(kg: 0, at: epoch + 7.1)
        let locked = test.displayMeans()[0]!
        _ = test.tick(now: epoch + 10.5)                                        // next pull, closed
        XCTAssertEqual(test.closedReps.first?.meanKg ?? 0, locked, accuracy: 1e-9,
                       "the locked bar is the saved number")
    }

    // MARK: - The arithmetic

    func testCriticalForceIsTheMeanOfTheLastSixPulls() throws {
        var test = CriticalForceTest()
        run(&test, until: proto.totalSeconds + 1)
        let result = try XCTUnwrap(try test.result()?.get())

        let expected = (18..<24).map { rep in
            // The window mean of this synthetic pull, integrated at the same resolution.
            test.closedReps[rep].meanKg!
        }.reduce(0, +) / 6
        XCTAssertEqual(result.criticalForceKg, expected, accuracy: 1e-9)
        XCTAssertEqual(result.criticalForceKg, level(21), accuracy: 0.4)
        XCTAssertEqual(result.criticalForceReps, 19...24)
        XCTAssertEqual(result.peakKg, level(0), accuracy: 0.01)
        XCTAssertEqual(result.repsRun, 24)
    }

    func testWPrimeIsTheImpulseAboveCriticalForce() throws {
        var test = CriticalForceTest()
        run(&test, until: proto.totalSeconds + 1)
        let result = try XCTUnwrap(try test.result()?.get())
        // Each pull holds ~level(rep) for ~6.9 s after its ramp; W′ ≈ Σ (level − CF)⁺ × 7.
        let approx = (0..<24).map { max(0, level($0) - result.criticalForceKg) * 7 }.reduce(0, +)
        XCTAssertEqual(result.wPrimeKgS, approx, accuracy: approx * 0.05)
        XCTAssertGreaterThan(result.wPrimeKgS, 0)
    }

    /// THE HONESTY RULE: force after the bell counts for nothing. A climber who hangs on
    /// two seconds into every rest scores exactly what a clean climber does, and is told
    /// the rests were not kept.
    func testForcePulledAfterTheBellIsNeverCredited() throws {
        var clean = CriticalForceTest()
        run(&clean, until: proto.totalSeconds + 1, hold: 7)
        var late = CriticalForceTest()
        run(&late, until: proto.totalSeconds + 1, hold: 9)

        let a = try XCTUnwrap(try clean.result()?.get())
        let b = try XCTUnwrap(try late.result()?.get())
        XCTAssertEqual(a.criticalForceKg, b.criticalForceKg, accuracy: 0.05)
        XCTAssertEqual(a.wPrimeKgS, b.wPrimeKgS, accuracy: a.wPrimeKgS * 0.01)
        XCTAssertEqual(a.restsTotal, 23)
        XCTAssertEqual(a.restsKept, 23)
        XCTAssertEqual(b.restsKept, 0, "two seconds on the edge in every rest")
    }

    func testEndForceIsTheLastSecondOfTheLastThreePulls() throws {
        // A pull that fades within itself: the end force sits under the mean.
        var test = CriticalForceTest()
        run(&test, until: proto.totalSeconds + 1)
        let result = try XCTUnwrap(try test.result()?.get())
        XCTAssertNotNil(result.endForceKg)
        XCTAssertEqual(result.endForceKg!, (21..<24).map(level).reduce(0, +) / 3, accuracy: 0.05)
    }

    // MARK: - Stopping and interruptions

    func testStoppingBeforeTheFloorKeepsNothing() {
        var test = CriticalForceTest()
        run(&test, until: 100)   // ten bells
        XCTAssertFalse(test.canFinishEarly)
        XCTAssertEqual(test.stop(now: epoch + 100), [.voided])
        XCTAssertEqual(test.phase, .voided(.tooFewReps))
        XCTAssertNil(test.result())
    }

    func testStoppingAfterSixteenKeepsTheRepsRun() throws {
        var test = CriticalForceTest()
        run(&test, until: 165)   // bells 1…16 have rung, rep 17 underway
        XCTAssertTrue(test.canFinishEarly)
        XCTAssertEqual(test.stop(now: epoch + 165), [.finished])
        _ = test.tick(now: epoch + 165.5)
        XCTAssertEqual(test.phase, .finished)
        let result = try XCTUnwrap(try test.result()?.get())
        XCTAssertEqual(result.repsRun, 16, "the pull in progress is not counted")
        XCTAssertEqual(result.criticalForceReps, 11...16)
        XCTAssertNil(result.reps.last?.restLoadSeconds, "the final counted pull has no rest after it")
    }

    func testLosingTheGaugeBeforeTheFloorVoidsAndAfterItEnds() throws {
        var early = CriticalForceTest()
        run(&early, until: 50)
        XCTAssertEqual(early.interrupt(.lostGauge, now: epoch + 50), [.voided])
        XCTAssertEqual(early.phase, .voided(.lostGauge))

        var late = CriticalForceTest()
        run(&late, until: 203)
        XCTAssertEqual(late.interrupt(.leftApp, now: epoch + 203), [.finished])
        _ = late.tick(now: epoch + 204)
        XCTAssertEqual(try XCTUnwrap(try late.result()?.get()).repsRun, 20)
    }

    // MARK: - Data quality

    /// A batch that arrives after its bell was rung still lands in the pull it was
    /// measured in: windows are sorted by the reading's own time, not arrival.
    func testLateDeliveredReadingsLandInTheirOwnWindow() throws {
        var test = CriticalForceTest()
        _ = test.sample(kg: 30, at: epoch)
        _ = test.tick(now: epoch + 7.2)          // the bell has rung…
        for i in 1...559 {                        // …and only now does rep 1's data arrive
            _ = test.sample(kg: 30, at: epoch + Double(i) / 80)
        }
        let summary = CriticalForceAnalysis.summarize(rep: 0, of: test.points, protocol: proto, isFinal: false)
        XCTAssertEqual(summary.meanKg!, 30, accuracy: 1e-9)
        XCTAssertEqual(summary.coverage, 6.9875 / 7, accuracy: 0.001)
    }

    func testAHoleIsNotInterpolatedAcross() {
        let points = [CriticalForcePoint(t: 0, kg: 20), CriticalForcePoint(t: 1, kg: 20),
                      CriticalForcePoint(t: 3, kg: 20), CriticalForcePoint(t: 3.1, kg: 20)]
        let integral = CriticalForceAnalysis.integrate(points, from: 0, to: 7)
        XCTAssertEqual(integral.covered, 0.1, accuracy: 1e-9, "only the 0.1 s pair is close enough")
    }

    func testTooManyHolesInTheFinalPullsIsNoResult() {
        var test = CriticalForceTest()
        // The radio loses reps 20–22 entirely: three of the final six have no mean.
        run(&test, until: proto.totalSeconds + 1, dropping: { rel in
            (190.0..<220.0).contains(rel)
        })
        guard case .failure(.tooLittleData)? = test.result() else {
            return XCTFail("expected tooLittleData, got \(String(describing: test.result()))")
        }
    }

    func testNobodyPullingIsNoResult() {
        let points = stride(from: 0.0, to: 240, by: 1.0 / 80).map { CriticalForcePoint(t: $0, kg: 0.3) }
        guard case .failure(.noPull) = CriticalForceAnalysis.analyze(points, repsRun: 24) else {
            return XCTFail("a flat trace is not a critical force")
        }
    }

    func testAnalysisRefusesFewerThanSixteenReps() {
        guard case .failure(.tooFewReps(run: 15)) = CriticalForceAnalysis.analyze([], repsRun: 15) else {
            return XCTFail()
        }
    }

    func testLineCrossingsIntegrateExactly() {
        // 0 → 20 kg over one second: the area above 10 is the triangle ½ × 0.5 s × 10 kg.
        let points = [CriticalForcePoint(t: 0, kg: 0), CriticalForcePoint(t: 0.2, kg: 4),
                      CriticalForcePoint(t: 0.4, kg: 8), CriticalForcePoint(t: 0.6, kg: 12),
                      CriticalForcePoint(t: 0.8, kg: 16), CriticalForcePoint(t: 1, kg: 20)]
        XCTAssertEqual(CriticalForceAnalysis.integrate(points, from: 0, to: 1, above: 10).area,
                       2.5, accuracy: 1e-9)
        XCTAssertEqual(CriticalForceAnalysis.timeAbove(10, in: points, from: 0, to: 1), 0.5, accuracy: 1e-9)
    }

    // MARK: - The stored trace

    func testTheStoredTraceRoundTripsAndStillYieldsTheSameResult() throws {
        var test = CriticalForceTest()
        run(&test, until: proto.totalSeconds + 1)
        let full = try XCTUnwrap(try test.result()?.get())

        let data = CriticalForceTrace.encode(test.points)
        XCTAssertLessThan(data.count, 12_000, "about ten kilobytes for four minutes")
        let decoded = CriticalForceTrace.decode(data)
        XCTAssertEqual(Double(decoded.count), test.points.last!.t * 20 + 1, accuracy: 2)

        let again = try XCTUnwrap(try CriticalForceAnalysis.analyze(decoded, repsRun: 24).get())
        XCTAssertEqual(again.criticalForceKg, full.criticalForceKg, accuracy: 0.3)
        XCTAssertEqual(again.wPrimeKgS, full.wPrimeKgS, accuracy: full.wPrimeKgS * 0.05)
    }

    func testTheTraceKeepsHolesAndNegativeReadings() {
        let points = [CriticalForcePoint(t: 0.01, kg: -0.4), CriticalForcePoint(t: 0.5, kg: 12.34)]
        let decoded = CriticalForceTrace.decode(CriticalForceTrace.encode(points))
        XCTAssertEqual(decoded.count, 2, "empty slots between are holes, not zeros")
        XCTAssertEqual(decoded[0].kg, -0.4, accuracy: 0.005)
        XCTAssertEqual(decoded[1].kg, 12.34, accuracy: 0.005)
    }

    func testMalformedTraceDecodesToNothing() {
        XCTAssertEqual(CriticalForceTrace.decode(Data([9, 9, 9])), [])
        XCTAssertEqual(CriticalForceTrace.decode(Data()), [])
    }

    func testRepBlobRoundTrips() {
        let reps = [CriticalForceRep(index: 0, meanKg: 30, peakKg: 35, endKg: 28, impulseKgS: 210,
                                     coverage: 1, restLoadSeconds: 0.4),
                    CriticalForceRep(index: 1, meanKg: nil, peakKg: 0, endKg: nil, impulseKgS: 0,
                                     coverage: 0, restLoadSeconds: nil)]
        XCTAssertEqual(CriticalForceRepsCodec.decode(CriticalForceRepsCodec.encode(reps)), reps)
        XCTAssertEqual(CriticalForceRepsCodec.decode(Data("nope".utf8)), [])
    }
}
