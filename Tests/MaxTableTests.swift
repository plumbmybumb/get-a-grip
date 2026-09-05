// SPDX-License-Identifier: MPL-2.0
// Original contributions Copyright 2026 Nuri Bruner.

import XCTest
@testable import Doigt

/// Per-hand maxes, end to end: the lookup rule, and the per-rep loads it produces.
///
/// The thing under test is really a claim about safety — that the right hand never
/// trains against the left hand's number. Every test here is one way that could happen.
final class MaxTableTests: XCTestCase {

    private func gripped(_ fingers: FingerSet) -> GripSpec {
        GripSpec(edgeMM: 20, fingers: fingers, position: .halfCrimp)
    }

    // MARK: - The lookup rule

    func testASideSpecificMaxBeatsTheBothHandsOne() {
        var table = MaxTable()
        table.record(40, grip: "g", side: .both)
        table.record(36, grip: "g", side: .right)

        XCTAssertEqual(table.max(grip: "g", side: .right), 36)
        XCTAssertEqual(table.max(grip: "g", side: .left), 40, "no left max, so the general one")
        XCTAssertEqual(table.max(grip: "g", side: .both), 40)
    }

    /// The whole app worked this way before hands existed, and a single recorded max has
    /// to keep behaving identically for everyone who never opens the picker.
    func testOneBothHandsMaxCoversEveryHand() {
        var table = MaxTable()
        table.record(40, grip: "g", side: .both)

        XCTAssertEqual(table.max(grip: "g", side: .left), 40)
        XCTAssertEqual(table.max(grip: "g", side: .right), 40)
        XCTAssertEqual(table.max(grip: "g", side: .both), 40)
    }

    /// THE SAFETY RULE. Choosing a hand is a statement that the other one is different,
    /// so it must not leak across. Nuri's right is ~10 % down on his left; borrowing the
    /// left number for the right hand would prescribe loads he cannot hold.
    func testAOneHandedMaxNeverLeaksToTheOtherHand() {
        var table = MaxTable()
        table.record(40, grip: "g", side: .left)

        XCTAssertEqual(table.max(grip: "g", side: .left), 40)
        XCTAssertNil(table.max(grip: "g", side: .right),
                     "the right hand has no max, and inventing one is the failure that hurts")
    }

    /// A two-handed pull is not the sum, the mean, or the weaker hand — it is a separate
    /// measurement. Deriving one would be a silent, doubled guess pointed at fingers.
    func testATwoHandedRepNeverSynthesisesAMaxFromTheSingleHands() {
        var table = MaxTable()
        table.record(40, grip: "g", side: .left)
        table.record(36, grip: "g", side: .right)

        XCTAssertNil(table.max(grip: "g", side: .both))
    }

    func testZeroAndNegativeAreNotRecorded() {
        var table = MaxTable()
        table.record(0, grip: "g", side: .both)
        table.record(-5, grip: "g", side: .left)
        table.record(.nan, grip: "g", side: .right)
        XCTAssertTrue(table.isEmpty)
    }

    func testDiffersByHand() {
        var same = MaxTable()
        same.record(40, grip: "g", side: .both)
        XCTAssertFalse(same.differsByHand(grip: "g"))

        var split = MaxTable()
        split.record(40, grip: "g", side: .left)
        split.record(36, grip: "g", side: .right)
        XCTAssertTrue(split.differsByHand(grip: "g"))

        XCTAssertFalse(MaxTable().differsByHand(grip: "g"), "nothing on file is not a difference")
    }

    func testExactIgnoresTheFallback() {
        var table = MaxTable()
        table.record(40, grip: "g", side: .both)
        XCTAssertEqual(table.exact(grip: "g", side: .both), 40)
        XCTAssertNil(table.exact(grip: "g", side: .left),
                     "the builder has to be able to see that this hand has nothing of its own")
    }

    // MARK: - Through the plan, per rep

    /// The payoff: ONE percentage on the routine, two different loads, because the reps
    /// alternate hands and the hands have different maxes.
    func testAlternatingRepsGetTheirOwnHandsLoad() {
        var plan = SessionPlan()
        plan.handMode = .alternateEachRep          // L R L R …
        plan.targetLoPercent = 0.20
        plan.targetHiPercent = 0.30
        plan.sets = [SetPlan(grip: gripped(.four), repsPerSide: 2)]

        var maxes = MaxTable()
        maxes.record(40, grip: gripped(.four).key, side: .left)
        maxes.record(36, grip: gripped(.four).key, side: .right)

        let slots = PlanMath.sequence(for: plan, maxes: maxes)
        XCTAssertEqual(slots.count, 4)
        XCTAssertEqual(slots.map(\.side), [.left, .right, .left, .right])
        XCTAssertEqual(slots[0].targetBand, 8...12,   "20–30 % of 40")
        XCTAssertEqual(slots[1].targetBand, 7...11,   "20–30 % of 36, on the half-kilo grid")
        XCTAssertEqual(slots[2].targetBand, slots[0].targetBand)
        XCTAssertEqual(slots[3].targetBand, slots[1].targetBand)
    }

    /// The hand a rep belongs to must not shift the load when only one max exists —
    /// the no-hands-configured case has to stay exactly as it was.
    func testOneMaxGivesBothHandsTheSameLoad() {
        var plan = SessionPlan()
        plan.handMode = .alternateEachRep
        plan.targetLoPercent = 0.20
        plan.targetHiPercent = 0.30
        plan.sets = [SetPlan(grip: gripped(.four), repsPerSide: 2)]

        var maxes = MaxTable()
        maxes.record(40, grip: gripped(.four).key, side: .both)

        let bands = PlanMath.sequence(for: plan, maxes: maxes).map(\.targetBand)
        XCTAssertEqual(Set(bands.compactMap { $0.map { "\($0)" } }).count, 1)
    }

    /// A left-only max leaves the RIGHT reps with no target rather than the left one's.
    func testRepsOnAHandWithNoMaxGetNoTarget() {
        var plan = SessionPlan()
        plan.handMode = .alternateEachRep
        plan.targetLoPercent = 0.20
        plan.targetHiPercent = 0.30
        plan.sets = [SetPlan(grip: gripped(.four), repsPerSide: 1)]

        var maxes = MaxTable()
        maxes.record(40, grip: gripped(.four).key, side: .left)

        let slots = PlanMath.sequence(for: plan, maxes: maxes)
        XCTAssertEqual(slots[0].targetBand, 8...12)
        XCTAssertNil(slots[1].targetBand)
    }

    /// An explicit kilogram band typed on the set stays hand-agnostic — see
    /// `PlanMath.targetBand`. Splitting a number a person typed is exactly the
    /// second-guessing the precedence rule forbids.
    func testATypedKilogramBandIsTheSameForBothHands() {
        var plan = SessionPlan()
        plan.handMode = .alternateEachRep
        var set = SetPlan(grip: gripped(.four), repsPerSide: 1)
        set.targetLoKg = 8
        set.targetHiKg = 10
        plan.sets = [set]

        var maxes = MaxTable()
        maxes.record(40, grip: gripped(.four).key, side: .left)
        maxes.record(20, grip: gripped(.four).key, side: .right)

        let slots = PlanMath.sequence(for: plan, maxes: maxes)
        XCTAssertEqual(slots[0].targetBand, 8...10)
        XCTAssertEqual(slots[1].targetBand, 8...10)
    }

    // MARK: - What history freezes

    /// **The load each rep was asked for has to survive in the LOG.** Moving resolution
    /// from the plan to the rep briefly lost it: `RunnerSession` stopped baking kilograms
    /// into the plan it hands to `WorkoutLog`, so a percent-only routine froze a plan
    /// whose `targetLoKg` was nil and the prescribed load went unrecorded. The frozen
    /// number belongs on the REP anyway — it is the only place a per-hand load is
    /// single-valued.
    func testEachRepFreezesTheLoadItsOwnHandWasAskedFor() {
        var plan = SessionPlan()
        plan.handMode = .alternateEachRep
        plan.targetLoPercent = 0.20
        plan.targetHiPercent = 0.30
        // Short and lead-in free: this test is about the number each rep carries, not
        // about timing, and a 10 s hold is 800 simulated samples of nothing.
        plan.holdSeconds = 2
        plan.restSeconds = 1
        plan.leadInSeconds = 0
        plan.sets = [SetPlan(grip: gripped(.four), repsPerSide: 1)]

        var maxes = MaxTable()
        maxes.record(40, grip: gripped(.four).key, side: .left)
        maxes.record(36, grip: gripped(.four).key, side: .right)

        var runner = SessionRunner(plan: plan, maxes: maxes)
        _ = runner.handle(.start, at: 0)

        var micros: UInt32 = 0
        var now: TimeInterval = 0
        var releaseUntil: TimeInterval?
        while runner.results.count < 2, now < 60 {
            micros = micros &+ 12_500
            now += 0.0125
            // Come off the edge for two seconds once the first rep is banked — the rest
            // is release-gated, so holding on forever would never start it, and letting
            // go forever would never arm the second rep.
            if runner.results.count == 1, releaseUntil == nil { releaseUntil = now + 2 }
            let released = releaseUntil.map { now < $0 } ?? false
            // 10 kg sits inside BOTH hands' bands (8–12 left, 7–11 right). It used to be
            // 30 — "pull hard", back when any load over the session threshold banked the
            // rep. The clock is gated on the band now, so 30 would be over the top of both
            // and this session would never start a single rep.
            _ = runner.handle(.sample(ForceSample(kg: released ? 0.2 : 10,
                                                  deviceMicros: micros)), at: now)
            _ = runner.handle(.tick, at: now)
        }

        guard runner.results.count == 2 else {
            return XCTFail("expected two logged reps, got \(runner.results.count)")
        }
        XCTAssertEqual(runner.results[0].side, .left)
        XCTAssertEqual(runner.results[0].targetBand, 8...12)
        XCTAssertEqual(runner.results[1].side, .right)
        XCTAssertEqual(runner.results[1].targetBand, 7...11)
    }

    // MARK: - What the builder says

    func testTargetTextNamesBothHandsOnlyWhenTheyDiffer() {
        var plan = SessionPlan()
        plan.handMode = .alternateEachRep
        plan.targetLoPercent = 0.20
        plan.targetHiPercent = 0.30
        let set = SetPlan(grip: gripped(.four))
        plan.sets = [set]

        var same = MaxTable()
        same.record(40, grip: gripped(.four).key, side: .both)
        XCTAssertEqual(PlanMath.targetText(set, in: plan, maxes: same), "8.0–12.0 kg")

        var split = MaxTable()
        split.record(40, grip: gripped(.four).key, side: .left)
        split.record(36, grip: gripped(.four).key, side: .right)
        XCTAssertEqual(PlanMath.targetText(set, in: plan, maxes: split),
                       "L 8.0–12.0 · R 7.0–11.0 kg")

        XCTAssertNil(PlanMath.targetText(set, in: plan, maxes: MaxTable()))
    }

    /// A two-handed routine has no per-hand question, so it must never draw "L … · R …".
    func testATwoHandedRoutineNeverSplitsTheText() {
        var plan = SessionPlan()
        plan.handMode = .bothHands
        plan.targetLoPercent = 0.20
        plan.targetHiPercent = 0.30
        let set = SetPlan(grip: gripped(.four))
        plan.sets = [set]

        var maxes = MaxTable()
        maxes.record(50, grip: gripped(.four).key, side: .both)
        maxes.record(40, grip: gripped(.four).key, side: .left)

        XCTAssertEqual(PlanMath.targetText(set, in: plan, maxes: maxes), "10.0–15.0 kg")
    }

    /// Counted per HAND: a grip with a left max and no right one is half unloaded, and a
    /// count that missed it would report the routine as fully targeted.
    func testUntargetedGripsAreCountedPerHand() {
        var plan = SessionPlan()
        plan.handMode = .alternateEachRep
        plan.targetLoPercent = 0.20
        plan.targetHiPercent = 0.30
        plan.sets = [SetPlan(grip: gripped(.four)), SetPlan(grip: gripped(.frontTwo))]

        var maxes = MaxTable()
        maxes.record(40, grip: gripped(.four).key, side: .left)      // right missing
        maxes.record(20, grip: gripped(.frontTwo).key, side: .both)  // fine

        XCTAssertEqual(PlanMath.untargetedGripCount(plan, maxes: maxes), 1)
    }
}
