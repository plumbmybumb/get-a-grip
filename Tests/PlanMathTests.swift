// SPDX-License-Identifier: MPL-2.0
// Original contributions Copyright 2026 Nuri Bruner.

import XCTest
@testable import Doigt

/// Deterministic generator so the randomized invariant below fails the same way twice.
/// SplitMix64 — small enough to read, good enough to shuffle plan parameters.
private struct SeededGenerator: RandomNumberGenerator {
    private var state: UInt64
    init(seed: UInt64) { state = seed }
    mutating func next() -> UInt64 {
        state &+= 0x9E37_79B9_7F4A_7C15
        var z = state
        z = (z ^ (z >> 30)) &* 0xBF58_476D_1CE4_E5B9
        z = (z ^ (z >> 27)) &* 0x94D0_49BB_1331_11EB
        return z ^ (z >> 31)
    }
}

/// Everything the app says about a routine is a fold over `PlanMath.sequence` — the
/// estimate, the per-side totals, the collapsed row's clock, and the order M3 will
/// actually execute. These tests pin the numbers as literals so a change to any
/// default shows up as a diff rather than a shrug, and pin the invariant that keeps
/// the card and the runner from ever drifting apart.
final class PlanMathTests: XCTestCase {

    private var starter: SessionPlan { RoutineDraft.starter.plan }

    // MARK: - The starter routine, number by number

    /// Spelled out because this single number is the commitment Nuri is agreeing to,
    /// twice a day:
    ///   set 1  5 + 12·10 + 11·20 = 345
    ///   set 2  5 + 12·10 + 11·20 = 345
    ///   set 3  5 +  4·10 +  3·20 = 105
    ///   set 4  5 +  4·10 +  3·20 = 105
    ///   set 5  5 +  2·10 +  1·20 =  45
    ///   set 6  5 +  2·10 +  1·20 =  45   → 990
    ///   + 5 set breaks × 60             = 300
    ///                                    1290
    /// Mutation check: dropping the six lead-ins gives 1260 and a 30 s set break gives
    /// 1140 — both must fail this test.
    func testStarterTotalsExactly1290Seconds() {
        XCTAssertEqual(PlanMath.totalSeconds(starter), 1290)
    }

    func testStarterDerivedNumbers() {
        XCTAssertEqual(PlanMath.setCount(starter), 6)
        XCTAssertEqual(PlanMath.totalReps(starter), 36)
        XCTAssertEqual(PlanMath.repsPerSide(starter), 18)
        XCTAssertEqual(PlanMath.tensionSeconds(starter), 360)
        XCTAssertEqual(PlanMath.tensionSecondsPerSide(starter), 180)
        XCTAssertEqual(PlanMath.sharedEdgeMM(starter), 20)
    }

    func testStarterSetSecondsPerRow() {
        let plan = starter
        XCTAssertEqual(plan.sets.map { PlanMath.setSeconds($0, in: plan) },
                       [345, 345, 105, 105, 45, 45])
        // The trailing value on the collapsed set row.
        XCTAssertEqual(PlanMath.clockText(345), "5:45")
    }

    /// The four frozen line builders. Every one of these is copy the user reads, and
    /// all four are computed — none of them is a literal anywhere in the app.
    func testStarterFrozenLines() {
        let plan = starter
        XCTAssertEqual(PlanMath.subtitleLine(plan), "≈21 min · 36 pulls")
        XCTAssertEqual(PlanMath.summaryLine(plan), "6 sets · 36 pulls · ≈21 min")
        XCTAssertEqual(PlanMath.totalsLine(plan), "6 sets · 36 pulls · 21:30 in total")
        XCTAssertEqual(PlanMath.perSideLine(plan), "18 pulls per side · 3:00 under tension per side")
    }

    // MARK: - The anti-drift invariant

    /// `totalSeconds` is a fold over `sequence`, never a parallel formula. A second
    /// formula would eventually disagree, and the disagreement would be invisible
    /// without a stopwatch.
    func testTotalSecondsAlwaysEqualsTheFoldOverTheSequence() {
        var rng = SeededGenerator(seed: 0xD016_7E57)
        for iteration in 0..<200 {
            let plan = randomPlan(using: &rng)
            let folded = PlanMath.sequence(for: plan).reduce(0) { $0 + $1.totalSeconds }
            XCTAssertEqual(PlanMath.totalSeconds(plan), folded, "iteration \(iteration)")
            XCTAssertEqual(PlanMath.sequence(for: plan).count, PlanMath.totalReps(plan),
                           "iteration \(iteration)")
        }
    }

    private func randomPlan(using rng: inout SeededGenerator) -> SessionPlan {
        var plan = SessionPlan()
        plan.handMode = HandMode.allCases.randomElement(using: &rng)!
        plan.holdSeconds = Int.random(in: 3...30, using: &rng)
        plan.restSeconds = Int.random(in: 0...60, using: &rng)
        plan.setBreakSeconds = Int.random(in: 0...180, using: &rng)
        plan.leadInSeconds = Int.random(in: 0...15, using: &rng)
        plan.sets = (0..<Int.random(in: 0...8, using: &rng)).map { _ in
            var set = SetPlan(
                grip: GripSpec(edgeMM: Int.random(in: 6...35, using: &rng),
                               fingers: FingerSet(rawValue: Int.random(in: 1...15, using: &rng)),
                               position: GripPosition.known.randomElement(using: &rng)!),
                repsPerSide: Int.random(in: 0...20, using: &rng))
            if Bool.random(using: &rng) { set.holdSeconds = Int.random(in: 3...40, using: &rng) }
            if Bool.random(using: &rng) { set.restSeconds = Int.random(in: 0...90, using: &rng) }
            return set
        }
        return plan
    }

    // MARK: - The hand-alternation matrix

    /// THE silent-factor-of-two catch: with one pull covering both hands there is no
    /// "per side", and the copy drops the phrase rather than printing half a truth.
    func testPerSideValuesAreNilForBothHands() {
        var plan = starter
        plan.handMode = .bothHands
        XCTAssertNil(PlanMath.repsPerSide(plan))
        XCTAssertNil(PlanMath.tensionSecondsPerSide(plan))
        XCTAssertNil(PlanMath.perSideLine(plan))
        XCTAssertNil(PlanMath.tensionSecondsPerSide(plan.sets[0], in: plan))
    }

    func testTotalRepsCountsBothSidesUnderAlternatingModes() {
        for mode in [HandMode.alternateEachRep, .alternateEachSet] {
            var plan = SessionPlan()
            plan.handMode = mode
            plan.sets = [SetPlan(grip: GripSpec(), repsPerSide: 6)]
            XCTAssertEqual(PlanMath.totalReps(plan), 12, "\(mode)")
            XCTAssertEqual(PlanMath.repCount(plan.sets[0], mode: mode), 12)
            XCTAssertEqual(mode.sideCount, 2)
        }
    }

    func testBothHandsDoesNotDoubleTheReps() {
        var plan = SessionPlan()
        plan.handMode = .bothHands
        plan.sets = [SetPlan(grip: GripSpec(), repsPerSide: 6)]

        let slots = PlanMath.sequence(for: plan)
        XCTAssertEqual(slots.count, 6)
        XCTAssertEqual(PlanMath.totalReps(plan), 6)
        XCTAssertTrue(slots.allSatisfy { $0.side == .both })
    }

    /// Stated over `sideCount` rather than over the one case that has it today, so an
    /// injured-hand mode added in a later milestone inherits the guarantee.
    func testSingleSidedModesDoNotDoubleTheReps() {
        for mode in HandMode.allCases where mode.sideCount == 1 {
            var plan = SessionPlan()
            plan.handMode = mode
            plan.sets = [SetPlan(grip: GripSpec(), repsPerSide: 6)]
            XCTAssertEqual(PlanMath.totalReps(plan), 6, "\(mode)")
            XCTAssertNil(PlanMath.repsPerSide(plan), "\(mode)")
            XCTAssertEqual(PlanMath.sequence(for: plan).map(\.side),
                           Array(repeating: mode.startSide, count: 6), "\(mode)")
        }
    }

    func testAlternateEachRepInterleavesSides() {
        let set = SetPlan(grip: GripSpec(), repsPerSide: 3)
        var plan = SessionPlan()
        plan.handMode = .alternateEachRep
        plan.sets = [set]
        XCTAssertEqual(PlanMath.handSequence(set, in: plan),
                       [.left, .right, .left, .right, .left, .right])
    }

    func testAlternateEachSetGroupsSides() {
        let set = SetPlan(grip: GripSpec(), repsPerSide: 3)
        var plan = SessionPlan()
        plan.handMode = .alternateEachSet
        plan.sets = [set]
        XCTAssertEqual(PlanMath.handSequence(set, in: plan),
                       [.left, .left, .left, .right, .right, .right])
    }

    /// The odd rep counts are the point: a running counter that did not reset would
    /// start set 1 on the right. `side(forRep:mode:repsPerSide:)` deliberately takes no
    /// setIndex, so the rule is unrepresentable to break.
    func testAlternationResetsToTheStartSideAtEverySetBoundary() {
        for mode in [HandMode.alternateEachRep, .alternateEachSet] {
            var plan = SessionPlan()
            plan.handMode = mode
            plan.sets = [1, 2, 3].map { SetPlan(grip: GripSpec(), repsPerSide: $0) }

            let slots = PlanMath.sequence(for: plan)
            for setIndex in 0..<3 {
                let first = slots.first { $0.setIndex == setIndex && $0.repIndex == 0 }
                XCTAssertEqual(first?.side, .left, "set \(setIndex) under \(mode)")
                XCTAssertEqual(first?.isFirstOfSet, true)
            }
            XCTAssertEqual(PlanMath.side(forRep: 0, mode: mode, repsPerSide: 3), .left)
            XCTAssertEqual(mode.startSide, .left)
        }
    }

    func testEverySetIsSideBalanced() {
        for mode in [HandMode.alternateEachRep, .alternateEachSet] {
            for repsPerSide in 1...10 {
                var plan = SessionPlan()
                plan.handMode = mode
                plan.sets = [SetPlan(grip: GripSpec(), repsPerSide: repsPerSide)]
                let sides = PlanMath.handSequence(plan.sets[0], in: plan)
                XCTAssertEqual(sides.filter { $0 == .left }.count, repsPerSide, "\(mode) \(repsPerSide)")
                XCTAssertEqual(sides.filter { $0 == .right }.count, repsPerSide, "\(mode) \(repsPerSide)")
            }
        }
    }

    // MARK: - Rests, breaks and lead-ins

    func testLastRepOfASetRestsForTheSetBreakNotTheIntraSetRest() {
        var plan = SessionPlan()
        plan.restSeconds = 20
        plan.setBreakSeconds = 60
        plan.sets = [SetPlan(grip: GripSpec(), repsPerSide: 6),
                     SetPlan(grip: GripSpec(), repsPerSide: 6)]

        let firstSet = PlanMath.sequence(for: plan).filter { $0.setIndex == 0 }
        XCTAssertEqual(firstSet.count, 12)
        XCTAssertEqual(firstSet[10].restAfter, 20)
        XCTAssertEqual(firstSet[11].restAfter, 60)
        XCTAssertTrue(firstSet[11].isLastOfSet)
        XCTAssertFalse(firstSet[10].isLastOfSet)
    }

    /// Nobody rests after the last pull. Without this the estimate is one whole set
    /// break too long, and the runner would sit on a rest screen at the end.
    func testFinalRepOfTheSessionHasZeroRestAfter() {
        let slots = PlanMath.sequence(for: starter)
        XCTAssertEqual(slots.last?.restAfter, 0)
        XCTAssertEqual(slots.last?.isLastOfSet, true)
        XCTAssertEqual(slots.last?.setIndex, 5)
    }

    func testLeadInIsChargedOncePerSetOnTheFirstRepOnly() {
        let slots = PlanMath.sequence(for: starter)
        for slot in slots {
            XCTAssertEqual(slot.leadInBefore, slot.isFirstOfSet ? 5 : 0,
                           "set \(slot.setIndex) rep \(slot.repIndex)")
        }
        XCTAssertEqual(slots.reduce(0) { $0 + $1.leadInBefore }, 6 * 5)
    }

    /// The structural expression of "one edit across all six sets": nil means follow
    /// the routine, so moving the routine's rest moves every non-overriding set at once.
    func testPerSetOverrideBeatsTheRoutineAndNilInherits() {
        var plan = SessionPlan()
        plan.holdSeconds = 10
        plan.restSeconds = 20
        var overriding = SetPlan(grip: GripSpec(), repsPerSide: 2)
        overriding.holdSeconds = 12
        overriding.restSeconds = nil
        let inheriting = SetPlan(grip: GripSpec(), repsPerSide: 2)
        plan.sets = [overriding, inheriting]

        XCTAssertEqual(PlanMath.hold(overriding, in: plan), 12)
        XCTAssertEqual(PlanMath.rest(overriding, in: plan), 20)
        XCTAssertEqual(PlanMath.hold(inheriting, in: plan), 10)
        XCTAssertEqual(PlanMath.rest(inheriting, in: plan), 20)
        XCTAssertTrue(overriding.overridesTiming)
        XCTAssertFalse(inheriting.overridesTiming)

        plan.restSeconds = 45
        XCTAssertEqual(PlanMath.rest(overriding, in: plan), 45)
        XCTAssertEqual(PlanMath.rest(inheriting, in: plan), 45)
        XCTAssertEqual(PlanMath.hold(overriding, in: plan), 12, "the override still stands")
    }

    // MARK: - Degenerate plans

    /// A zero-rep set is not a pause: it costs no lead-in and no set break, and it must
    /// not leave a hole in the setIndex values the runner walks.
    func testZeroRepSetIsDroppedEntirelyAndAddsNoSetBreak() {
        var plan = starter
        plan.sets.insert(SetPlan(grip: GripSpec(edgeMM: 25, fingers: .four, position: .drag),
                                 repsPerSide: 0), at: 2)

        XCTAssertEqual(PlanMath.totalSeconds(plan), 1290)
        XCTAssertEqual(PlanMath.setCount(plan), 6)
        XCTAssertEqual(plan.executable.sets.count, 6)
        XCTAssertEqual(Set(PlanMath.sequence(for: plan).map(\.setIndex)), Set(0..<6))
    }

    func testEmptyPlanHasZeroDurationZeroRepsAndAnEmptySequence() {
        let plan = SessionPlan()
        XCTAssertEqual(plan.sets, [])
        XCTAssertEqual(PlanMath.sequence(for: plan), [])
        XCTAssertEqual(PlanMath.totalSeconds(plan), 0)
        XCTAssertEqual(PlanMath.tensionSeconds(plan), 0)
        XCTAssertEqual(PlanMath.totalReps(plan), 0)
        XCTAssertEqual(PlanMath.setCount(plan), 0)
        XCTAssertNil(PlanMath.sharedEdgeMM(plan))
        XCTAssertEqual(PlanMath.gripTotals(plan), [])
    }

    // MARK: - Grip roll-up

    func testGripTotalsMergeByCanonicalKeyInFirstAppearanceOrder() {
        var plan = SessionPlan()
        plan.holdSeconds = 10
        plan.handMode = .alternateEachRep
        let open = GripSpec(edgeMM: 20, fingers: .frontTwo, position: .openHand)
        let crimped = GripSpec(edgeMM: 20, fingers: .frontTwo, position: .fullCrimp)
        plan.sets = [SetPlan(grip: open, repsPerSide: 2),
                     SetPlan(grip: crimped, repsPerSide: 1),
                     SetPlan(grip: open, repsPerSide: 3)]

        let totals = PlanMath.gripTotals(plan)
        XCTAssertEqual(totals.map(\.grip.key), ["20|IM|openHand", "20|IM|fullCrimp"],
                       "merged by key, in first-appearance order")
        XCTAssertEqual(totals[0].repsPerSide, 5)
        XCTAssertEqual(totals[0].totalReps, 10)
        XCTAssertEqual(totals[0].tensionSeconds, 100)
        XCTAssertEqual(totals[1].repsPerSide, 1)
        XCTAssertEqual(totals[1].totalReps, 2)
        XCTAssertEqual(totals[1].tensionSeconds, 20)
        XCTAssertFalse(totals[0].line.isEmpty)
    }

    /// Today's meta line starts at "6 sets" rather than claiming an edge the routine
    /// does not actually share.
    func testSharedEdgeIsNilWhenSetsDisagree() {
        var plan = starter
        XCTAssertEqual(PlanMath.sharedEdgeMM(plan), 20)
        plan.sets[3].grip.edgeMM = 18
        XCTAssertNil(PlanMath.sharedEdgeMM(plan))
    }

    // MARK: - Loads

    /// The band is rounded INWARD to half kilos: a target you can actually read off a
    /// gauge, and never wider than the percentage it claims to be.
    func testSuggestedBandRoundsToHalfKilos() {
        // 23.7 × 0.20 = 4.74, whose nearest half-kilo is 4.5 (not 5.0 — 4.74 is 0.24
        // below 4.5 and 0.26 under 5.0). 23.7 × 0.30 = 7.11 → 7.0.
        // `testRoundedToHalfKgLandsOnTheHalfKiloGrid` asserts no value moves more than
        // half a step, which 4.74 → 5.0 would break; these two must agree.
        XCTAssertEqual(PlanMath.suggestedBand(maxKg: 23.7), 4.5...7.0)
        XCTAssertNil(PlanMath.suggestedBand(maxKg: 0), "no max is not a 0...0 band")
        XCTAssertNil(PlanMath.suggestedBand(maxKg: -4))
    }

    func testRoundedToHalfKgLandsOnTheHalfKiloGrid() {
        for kg in [0.0, 4.74, 7.11, 12.0, 23.7, 41.26] {
            let rounded = PlanMath.roundedToHalfKg(kg)
            XCTAssertEqual((rounded * 2).rounded(), rounded * 2, accuracy: 0.0001, "\(kg)")
            XCTAssertLessThanOrEqual(abs(rounded - kg), 0.2501, "\(kg) moved more than half a step")
        }
    }

    func testPercentOfMaxIsNilWithoutAUsableMax() throws {
        XCTAssertEqual(try XCTUnwrap(PlanMath.percentOfMax(5, maxKg: 20)), 0.25, accuracy: 0.0001)
        XCTAssertNil(PlanMath.percentOfMax(5, maxKg: 0))
        XCTAssertNil(PlanMath.percentOfMax(5, maxKg: -1))
    }

    // MARK: - Target loads

    private func gripped(_ fingers: FingerSet, _ position: GripPosition = .halfCrimp) -> GripSpec {
        GripSpec(edgeMM: 20, fingers: fingers, position: position)
    }

    /// The precedence, all four rungs, on one plan. Kilograms typed on a set are the most
    /// specific thing anyone can say and must never be second-guessed by arithmetic.
    func testTargetBandPrecedenceRunsSetKgThenSetPercentThenRoutinePercent() throws {
        var plan = SessionPlan()
        plan.targetLoPercent = 0.20
        plan.targetHiPercent = 0.30

        var typed = SetPlan(grip: gripped(.four))
        typed.targetLoKg = 6
        typed.targetHiKg = 9
        typed.targetLoPercent = 0.50          // present, and deliberately ignored
        XCTAssertEqual(PlanMath.targetBand(typed, in: plan, maxKg: 20), 6...9,
                       "a number a person typed wins over every percentage")

        var ownPercent = SetPlan(grip: gripped(.four))
        ownPercent.targetLoPercent = 0.40
        ownPercent.targetHiPercent = 0.50
        XCTAssertEqual(PlanMath.targetBand(ownPercent, in: plan, maxKg: 20), 8...10,
                       "the set's own percentage beats the routine's")

        let inherits = SetPlan(grip: gripped(.four))
        XCTAssertEqual(PlanMath.targetBand(inherits, in: plan, maxKg: 20), 4...6,
                       "and with neither, the routine's band applies")

        XCTAssertNil(PlanMath.targetBand(inherits, in: SessionPlan(), maxKg: 20),
                     "no band anywhere is no target")
    }

    /// There is no percentage of a max nobody has recorded. Inventing one would put a
    /// confident kilogram figure on a screen somebody trains against.
    func testAPercentageTargetResolvesToNothingWithoutAMax() {
        var plan = SessionPlan()
        plan.targetLoPercent = 0.20
        plan.targetHiPercent = 0.30
        let set = SetPlan(grip: gripped(.four))

        XCTAssertNil(PlanMath.targetBand(set, in: plan, maxKg: nil))
        XCTAssertNil(PlanMath.targetBand(set, in: plan, maxKg: 0))
        XCTAssertNil(PlanMath.targetBand(set, in: plan, maxKg: -3))

        // But an explicit kg band needs no max at all — that is the whole point of it.
        var typed = set
        typed.targetLoKg = 4
        typed.targetHiKg = 5
        XCTAssertEqual(PlanMath.targetBand(typed, in: plan, maxKg: nil), 4...5)
    }

    /// ONE band, the right load on every grip — the reason this is a percentage and not
    /// six numbers to author.
    func testOneRoutinePercentageResolvesPerGripFromEachGripsOwnMax() throws {
        var plan = SessionPlan()
        plan.targetLoPercent = 0.20
        plan.targetHiPercent = 0.30
        plan.sets = [SetPlan(grip: gripped(.four)),
                     SetPlan(grip: gripped(.frontTwo)),
                     SetPlan(grip: gripped(.middleTwo))]

        var maxes = MaxTable()
        maxes.record(40, grip: gripped(.four).key, side: .both)
        maxes.record(20, grip: gripped(.frontTwo).key, side: .both)
        // middleTwo deliberately absent
        let resolved = PlanMath.resolvingTargets(plan, maxes: maxes)

        XCTAssertEqual(resolved.sets[0].targetBand, 8...12)
        XCTAssertEqual(resolved.sets[1].targetBand, 4...6)
        XCTAssertNil(resolved.sets[2].targetBand, "no max, no target — and no guess")
        XCTAssertEqual(resolved.targetLoPercent, 0.20, "the percentages survive resolution")
    }

    /// Resolution happens ONCE, at session start, and the resolved kilograms are what the
    /// runner executes — so a max recorded later cannot rewrite what a session told you
    /// to pull. Same freeze-at-save rule the routine's name already follows.
    func testResolvedTargetsReachEveryRepSlot() throws {
        var plan = SessionPlan()
        plan.targetLoPercent = 0.20
        plan.targetHiPercent = 0.25
        plan.handMode = .bothHands
        plan.sets = [SetPlan(grip: gripped(.four), repsPerSide: 2)]

        var maxes = MaxTable()
        maxes.record(30, grip: gripped(.four).key, side: .both)

        let resolved = PlanMath.resolvingTargets(plan, maxes: maxes)
        let slots = PlanMath.sequence(for: resolved)

        XCTAssertEqual(slots.count, 2)
        XCTAssertTrue(slots.allSatisfy { $0.targetBand == 6...7.5 })

        // The same answer straight from the table, which is the path a real session
        // takes now that loads resolve per REP — see `sequence(for:maxes:)`.
        let direct = PlanMath.sequence(for: plan, maxes: maxes)
        XCTAssertTrue(direct.allSatisfy { $0.targetBand == 6...7.5 })

        // With NEITHER a baked band nor a table, a percentage never becomes kilograms.
        XCTAssertTrue(PlanMath.sequence(for: plan).allSatisfy { $0.targetBand == nil })
    }

    /// Half-kilo steps, like every other load in the app: 22.3 × 0.17 = 3.791.
    func testResolvedPercentagesLandOnTheHalfKiloGrid() throws {
        var plan = SessionPlan()
        plan.targetLoPercent = 0.17
        plan.targetHiPercent = 0.22
        let band = try XCTUnwrap(PlanMath.targetBand(SetPlan(grip: gripped(.four)),
                                                     in: plan, maxKg: 22.3))
        XCTAssertEqual(band, 4.0...5.0, "3.791 → 4.0 and 4.906 → 5.0")
    }

    // MARK: - Folding deck overrides back into inheritance

    /// Uniform TIMING still consolidates up (the sets resolve identically either way).
    /// TARGETS deliberately do not: load lives per set (2026-08-10), the builder has no
    /// routine-level load editor left, and a promoted band would be active yet
    /// invisible. Every set keeps the band it was given.
    func testUniformPerSetOverridesArePromotedToTheRoutine() throws {
        var draft = RoutineDraft()
        draft.plan.handMode = .bothHands
        draft.plan.sets = (0..<3).map { _ in
            var s = SetPlan(grip: gripped(.four), repsPerSide: 6)
            s.holdSeconds = 7          // every set, the same value
            s.restSeconds = 25
            s.targetLoPercent = 0.17
            s.targetHiPercent = 0.22
            return s
        }

        let clean = draft.normalized

        XCTAssertEqual(clean.plan.holdSeconds, 7, "the routine now says what every set said")
        XCTAssertEqual(clean.plan.restSeconds, 25)
        XCTAssertNil(clean.plan.targetPercentBand, "targets are never promoted")
        XCTAssertTrue(clean.plan.sets.allSatisfy { $0.holdSeconds == nil },
                      "and the sets go back to inheriting, so one edit moves them all")
        XCTAssertTrue(clean.plan.sets.allSatisfy { $0.restSeconds == nil })
        XCTAssertTrue(clean.plan.sets.allSatisfy { $0.targetPercentBand == 0.17...0.22 },
                      "each set keeps its own band")
    }

    /// Genuinely different per-grip timing is the whole point of the grip card, so it
    /// must survive untouched — only the override that AGREES with the routine is cleared,
    /// because one that merely agrees silently skips that set the next time the rhythm
    /// changes.
    func testMixedOverridesSurviveAndOnlyAgreeingOnesAreCleared() throws {
        var draft = RoutineDraft()
        draft.plan.handMode = .bothHands
        draft.plan.holdSeconds = 10
        var crimp = SetPlan(grip: gripped(.frontTwo, .fullCrimp), repsPerSide: 1)
        crimp.holdSeconds = 5                        // deliberately different
        var drag = SetPlan(grip: gripped(.four, .openHand), repsPerSide: 6)
        drag.holdSeconds = 20                        // also different
        var agrees = SetPlan(grip: gripped(.four), repsPerSide: 6)
        agrees.holdSeconds = 10                      // same as the routine
        draft.plan.sets = [crimp, drag, agrees]

        let clean = draft.normalized

        XCTAssertEqual(clean.plan.holdSeconds, 10, "nothing to promote — they disagree")
        XCTAssertEqual(clean.plan.sets[0].holdSeconds, 5, "5 s crimps survive")
        XCTAssertEqual(clean.plan.sets[1].holdSeconds, 20, "20 s drags survive")
        XCTAssertNil(clean.plan.sets[2].holdSeconds, "the one that agreed now follows")
    }

    /// THE PROPERTY THAT MAKES IT SAFE TO RUN ON EVERY SAVE: consolidation may move a
    /// value between the routine and its sets, but no set may resolve to anything
    /// different afterwards.
    func testConsolidationNeverChangesWhatASetResolvesTo() throws {
        var draft = RoutineDraft()
        draft.plan.handMode = .bothHands
        draft.plan.holdSeconds = 10
        draft.plan.restSeconds = 20
        draft.plan.targetLoPercent = 0.20
        draft.plan.targetHiPercent = 0.30

        var a = SetPlan(grip: gripped(.four), repsPerSide: 6)
        a.holdSeconds = 10                 // agrees — will be cleared
        a.restSeconds = 45                 // differs — survives
        var b = SetPlan(grip: gripped(.frontTwo), repsPerSide: 2)
        b.targetLoPercent = 0.40           // differs — survives
        b.targetHiPercent = 0.50
        let c = SetPlan(grip: gripped(.middleTwo), repsPerSide: 2)  // inherits everything
        draft.plan.sets = [a, b, c]

        let maxes = [gripped(.four).key: 40.0,
                     gripped(.frontTwo).key: 20.0,
                     gripped(.middleTwo).key: 30.0]
        let before = draft.plan.sets.map {
            (PlanMath.hold($0, in: draft.plan),
             PlanMath.rest($0, in: draft.plan),
             PlanMath.targetBand($0, in: draft.plan, maxKg: maxes[$0.grip.key]))
        }

        let clean = draft.normalized
        let after = clean.plan.sets.map {
            (PlanMath.hold($0, in: clean.plan),
             PlanMath.rest($0, in: clean.plan),
             PlanMath.targetBand($0, in: clean.plan, maxKg: maxes[$0.grip.key]))
        }

        XCTAssertEqual(before.count, after.count)
        for (index, pair) in zip(before, after).enumerated() {
            XCTAssertEqual(pair.0.0, pair.1.0, "hold changed on set \(index)")
            XCTAssertEqual(pair.0.1, pair.1.1, "rest changed on set \(index)")
            XCTAssertEqual(pair.0.2, pair.1.2, "target changed on set \(index)")
        }
    }

    /// A one-grip routine is the deck's starting state, and every value on it is
    /// "uniform" by definition — so it must promote rather than leave the routine
    /// quoting a default the single set does not use.
    func testASingleGripRoutinePromotesItsOwnValues() throws {
        var draft = RoutineDraft()
        draft.plan.handMode = .bothHands
        var only = SetPlan(grip: gripped(.four), repsPerSide: 10)
        only.holdSeconds = 12
        draft.plan.sets = [only]

        let clean = draft.normalized
        XCTAssertEqual(clean.plan.holdSeconds, 12)
        XCTAssertNil(clean.plan.sets[0].holdSeconds)
        XCTAssertEqual(PlanMath.hold(clean.plan.sets[0], in: clean.plan), 12)
    }

    // MARK: - Formatting

    func testDurationAndClockFormatting() {
        XCTAssertEqual(PlanMath.durationText(45), "45 s")
        XCTAssertEqual(PlanMath.durationText(1290), "21 min 30 s")
        XCTAssertEqual(PlanMath.durationText(1140), "19 min")
        XCTAssertEqual(PlanMath.durationText(0), "0 s")
        XCTAssertEqual(PlanMath.durationText(-5), "0 s")

        XCTAssertEqual(PlanMath.clockText(345), "5:45")
        XCTAssertEqual(PlanMath.clockText(1290), "21:30")
        XCTAssertEqual(PlanMath.clockText(45), "0:45")
    }

    /// Truncation, not rounding: 1290 s rounds to 22 and would contradict every frozen
    /// copy string in the app.
    func testApproxMinutesTruncatesRatherThanRounds() {
        XCTAssertEqual(PlanMath.approxMinutes(1290), "≈21 min")
        XCTAssertEqual(PlanMath.approxMinutes(1140), "≈19 min")
        XCTAssertEqual(PlanMath.approxMinutes(45), "45 s")
    }

    // MARK: - Peak intensity

    /// A percentage IS the intensity: "20–30 %" says how hard the routine is whether or
    /// not anybody has ever measured that grip, and the HI bound is the figure — the
    /// hardest thing prescribed, not the average of the band.
    func testRoutinePercentageNamesThePeakIntensityWithNoMaxOnFile() throws {
        var plan = SessionPlan()
        plan.handMode = .bothHands
        plan.targetLoPercent = 0.20
        plan.targetHiPercent = 0.30
        plan.sets = [SetPlan(grip: gripped(.four), repsPerSide: 6),
                     SetPlan(grip: gripped(.frontTwo), repsPerSide: 2)]

        let peak = try XCTUnwrap(PlanMath.peakIntensity(of: plan, maxes: MaxTable()))
        XCTAssertEqual(peak, 0.30, accuracy: 0.0001, "the hi bound, with no max needed")
        XCTAssertEqual(PlanMath.IntensityBand.band(for: peak), .light)
    }

    /// `targetBand`'s own precedence, and the fold that names the routine: the set's own
    /// percentage beats the one it would otherwise inherit, and the HARDEST set speaks for
    /// the whole routine.
    func testASetsOwnPercentageBeatsTheRoutinesAndTheHardestSetSpeaks() throws {
        var plan = SessionPlan()
        plan.handMode = .bothHands
        plan.targetLoPercent = 0.20
        plan.targetHiPercent = 0.30
        plan.sets = [SetPlan(grip: gripped(.four), repsPerSide: 6),       // inherits 20–30 %
                     SetPlan(grip: gripped(.four), repsPerSide: 2,
                             targetLoPercent: 0.80, targetHiPercent: 0.90)]

        let peak = try XCTUnwrap(PlanMath.peakIntensity(of: plan, maxes: MaxTable()))
        XCTAssertEqual(peak, 0.90, accuracy: 0.0001)
        XCTAssertEqual(PlanMath.IntensityBand.band(for: peak), .nearMax)
    }

    /// `MaxTable`'s refusal to guess, read through the intensity: a `.both` rep resolves
    /// against a both-hands max ONLY. Adding the two hands together would be a silent,
    /// doubled guess pointed at somebody's fingers.
    func testAKgBandOnABothHandsSetResolvesOnlyAgainstABothHandsMax() throws {
        var plan = SessionPlan()
        plan.handMode = .bothHands
        plan.sets = [SetPlan(grip: gripped(.four), repsPerSide: 4,
                             targetLoKg: 20, targetHiKg: 30)]

        var oneHandAtATime = MaxTable()
        oneHandAtATime.record(40, grip: gripped(.four).key, side: .left)
        oneHandAtATime.record(40, grip: gripped(.four).key, side: .right)
        let unresolved = PlanMath.peakIntensity(of: plan, maxes: oneHandAtATime)
        XCTAssertNil(unresolved, "two one-handed maxes never synthesise a two-handed one")
        XCTAssertEqual(PlanMath.IntensityBand.band(for: unresolved),
                       .unknown, "and unknown is not light")

        var bothHands = oneHandAtATime
        bothHands.record(40, grip: gripped(.four).key, side: .both)
        let peak = try XCTUnwrap(PlanMath.peakIntensity(of: plan, maxes: bothHands))
        XCTAssertEqual(peak, 0.75, accuracy: 0.0001, "30 kg of a 40 kg two-handed max")
        XCTAssertEqual(PlanMath.IntensityBand.band(for: peak), .moderate)
    }

    /// The same kilograms are a harder morning for the weaker hand, and that hand's
    /// experience names the day. The fallback half matters just as much: one both-hands
    /// max still serves an alternating routine exactly as it did before hands existed.
    func testAKgBandTakesTheHarderHandsFraction() throws {
        var plan = SessionPlan()
        plan.handMode = .alternateEachRep
        plan.sets = [SetPlan(grip: gripped(.four), repsPerSide: 2,
                             targetLoKg: 18, targetHiKg: 24)]

        var perHand = MaxTable()
        perHand.record(40, grip: gripped(.four).key, side: .left)
        perHand.record(30, grip: gripped(.four).key, side: .right)   // the weaker hand
        let peak = try XCTUnwrap(PlanMath.peakIntensity(of: plan, maxes: perHand))
        XCTAssertEqual(peak, 0.80, accuracy: 0.0001,
                       "24/30 on the right, not the flattering 24/40 on the left")
        XCTAssertEqual(PlanMath.IntensityBand.band(for: peak), .nearMax)

        var shared = MaxTable()
        shared.record(30, grip: gripped(.four).key, side: .both)
        XCTAssertEqual(try XCTUnwrap(PlanMath.peakIntensity(of: plan, maxes: shared)),
                       0.80, accuracy: 0.0001, "both hands fall back to the shared max")
    }

    /// A set that cannot resolve contributes NOTHING rather than making the whole answer
    /// unknown — five sets at 25 % plus one unmeasured grip is still a 25 % routine. And
    /// the kg band does not fall back to a percentage the same set happens to carry: a
    /// number a person typed is never second-guessed by arithmetic.
    func testAKgSetWithNoMaxIsSilentWhileAPercentageSetStillSpeaks() throws {
        var plan = SessionPlan()
        plan.handMode = .bothHands
        plan.sets = [SetPlan(grip: gripped(.middleTwo), repsPerSide: 2,
                             targetLoKg: 30, targetHiKg: 34,
                             targetLoPercent: 0.90, targetHiPercent: 0.95),
                     SetPlan(grip: gripped(.four), repsPerSide: 6,
                             targetLoPercent: 0.20, targetHiPercent: 0.25)]

        let peak = try XCTUnwrap(PlanMath.peakIntensity(of: plan, maxes: MaxTable()))
        XCTAssertEqual(peak, 0.25, accuracy: 0.0001,
                       "the percentage set speaks; the unmeasured kg set is silent, and its "
                       + "own 95 % is never reached because kilograms outrank it")
        XCTAssertEqual(PlanMath.IntensityBand.band(for: peak), .light)
    }

    /// A max on file is not a prescription, and nil is the honest reading of a routine
    /// that goes by feel.
    func testARoutineWithNoTargetAnywhereHasNoPeakIntensity() {
        var plan = SessionPlan()
        plan.handMode = .bothHands
        plan.sets = [SetPlan(grip: gripped(.four), repsPerSide: 6)]
        var maxes = MaxTable()
        maxes.record(40, grip: gripped(.four).key, side: .both)

        XCTAssertNil(PlanMath.peakIntensity(of: plan, maxes: maxes))
        XCTAssertNil(PlanMath.peakIntensity(of: SessionPlan(), maxes: maxes))
        XCTAssertEqual(PlanMath.IntensityBand.band(for: nil), .unknown)
    }

    /// A set the user emptied out prescribes nothing — the same `executable` rule every
    /// other fold in `PlanMath` obeys.
    func testAZeroRepSetsBandNeverNamesTheRoutinesIntensity() throws {
        var plan = SessionPlan()
        plan.handMode = .bothHands
        plan.sets = [SetPlan(grip: gripped(.four), repsPerSide: 0,
                             targetLoPercent: 0.85, targetHiPercent: 0.95),
                     SetPlan(grip: gripped(.four), repsPerSide: 6,
                             targetLoPercent: 0.20, targetHiPercent: 0.25)]

        XCTAssertEqual(try XCTUnwrap(PlanMath.peakIntensity(of: plan, maxes: MaxTable())),
                       0.25, accuracy: 0.0001)
    }

    /// Nothing is clamped to 1.0. A kg band authored against a max that has since gone
    /// stale genuinely prescribes 120 %, and reporting it as 100 % would hide exactly the
    /// case worth seeing.
    func testAPrescriptionOverOneHundredPercentIsReportedRatherThanClamped() throws {
        var plan = SessionPlan()
        plan.handMode = .bothHands
        plan.sets = [SetPlan(grip: gripped(.four), repsPerSide: 2,
                             targetLoKg: 25, targetHiKg: 30)]
        var maxes = MaxTable()
        maxes.record(25, grip: gripped(.four).key, side: .both)

        let peak = try XCTUnwrap(PlanMath.peakIntensity(of: plan, maxes: maxes))
        XCTAssertEqual(peak, 1.20, accuracy: 0.0001)
        XCTAssertEqual(PlanMath.IntensityBand.band(for: peak), .nearMax, "120 % is a max effort, not an error")

        // Same for a percentage over 1.0. `SetPlan.percentRange` clamps on DECODE; a value
        // set in memory is not clamped, and it must still report rather than trap.
        var overPercent = SessionPlan()
        overPercent.handMode = .bothHands
        overPercent.sets = [SetPlan(grip: gripped(.four), repsPerSide: 2,
                                    targetLoPercent: 1.00, targetHiPercent: 1.10)]
        XCTAssertEqual(try XCTUnwrap(PlanMath.peakIntensity(of: overPercent, maxes: MaxTable())),
                       1.10, accuracy: 0.0001)
        XCTAssertEqual(PlanMath.IntensityBand.band(for: 1.10), .nearMax)
    }

    /// The boundaries the UI colours from, owned explicitly: 0.30 is light and 0.80 is
    /// nearMax. Understating a max effort is the harmful direction, so both edges belong
    /// to the louder side at the top and the quieter side at the bottom.
    func testIntensityBandBoundariesAreOwnedByLightAndNearMax() {
        XCTAssertEqual(PlanMath.IntensityBand.band(for: 0.30), .light, "0.30 is light, not moderate")
        XCTAssertEqual(PlanMath.IntensityBand.band(for: 0.80), .nearMax, "0.80 is nearMax, not moderate")
        XCTAssertEqual(PlanMath.IntensityBand.band(for: 0.31), .moderate)
        XCTAssertEqual(PlanMath.IntensityBand.band(for: 0.79), .moderate)
        XCTAssertEqual(PlanMath.IntensityBand.band(for: 0.20), .light)
        XCTAssertEqual(PlanMath.IntensityBand.band(for: 0.95), .nearMax)
        XCTAssertEqual(PlanMath.IntensityBand.band(for: nil), .unknown)
        // A NaN fails both comparisons and would otherwise be reported as `moderate` — a
        // confident answer about nothing.
        XCTAssertEqual(PlanMath.IntensityBand.band(for: .nan), .unknown)
        XCTAssertEqual(PlanMath.IntensityBand.band(for: .infinity), .unknown)
    }

    /// The two shipping prefills, as fixtures: the daily ritual prescribes nothing by
    /// design, and the C4 ladder's top ramp set is what makes a max day read as one.
    func testTheShippingPrefillsReportTheIntensityTheyPrescribe() throws {
        XCTAssertNil(PlanMath.peakIntensity(of: starter, maxes: MaxTable()),
                     "the daily no-hangs go by feel and set no target at all")

        let maxDay = try XCTUnwrap(PlanMath.peakIntensity(of: RoutineDraft.maxDay.plan,
                                                          maxes: MaxTable()))
        XCTAssertEqual(maxDay, 0.90, accuracy: 0.0001, "the 80–90 % ramp set")
        XCTAssertEqual(PlanMath.IntensityBand.band(for: maxDay), .nearMax)
    }
}
