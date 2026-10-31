// SPDX-License-Identifier: MPL-2.0
// Original contributions Copyright 2026 Nuri Bruner.

import XCTest
@testable import Doigt

/// Target loads as a percentage of CRITICAL FORCE (`TargetBasis`, `CriticalForceTable`,
/// `docs/TARGET_BASIS.md`). The cross-platform cases live in `Fixtures/target-basis/`
/// (`oracle target-basis verify`); these pin the same rules on the iOS side.
final class TargetBasisTests: XCTestCase {

    private let grip = GripSpec(edgeMM: 20, fingers: .frontThree, position: .halfCrimp)
    private var key: String { grip.key }

    private func cfTable(_ entries: [(Side, Double)]) -> CriticalForceTable {
        var t = CriticalForceTable()
        for (side, kg) in entries { t.record(kg, grip: key, side: side) }
        return t
    }

    private func maxTable(_ entries: [(Side, Double)]) -> MaxTable {
        var t = MaxTable()
        for (side, kg) in entries { t.record(kg, grip: key, side: side) }
        return t
    }

    private func plan(_ set: SetPlan, mode: HandMode = .alternateEachRep) -> SessionPlan {
        var p = SessionPlan()
        p.sets = [set]
        p.handMode = mode
        return p
    }

    private func cfSet(_ lo: Double, _ hi: Double) -> SetPlan {
        var s = SetPlan(grip: grip, repsPerSide: 2, targetLoPercent: lo, targetHiPercent: hi)
        s.targetBasis = .criticalForce
        return s
    }

    // MARK: - The model

    func testAMissingOrUnknownBasisReadsAsMaxAndTheUnknownRawSurvives() throws {
        let legacy = Data(#"{"repsPerSide":3,"targetLoPercent":0.2,"targetHiPercent":0.3}"#.utf8)
        let old = try JSONDecoder().decode(SetPlan.self, from: legacy)
        XCTAssertNil(old.targetBasisRaw)
        XCTAssertEqual(old.targetBasis, .max)

        let future = Data(#"{"repsPerSide":3,"targetLoPercent":0.2,"targetBasis":"wPrime"}"#.utf8)
        let decoded = try JSONDecoder().decode(SetPlan.self, from: future)
        XCTAssertEqual(decoded.targetBasis, .max, "a basis this build cannot read is a max")
        let reencoded = try XCTUnwrap(String(data: JSONEncoder().encode(decoded), encoding: .utf8))
        XCTAssertTrue(reencoded.contains(#""targetBasis":"wPrime""#), "a newer build's raw survives verbatim")

        let empty = Data(#"{"repsPerSide":3,"targetBasis":""}"#.utf8)
        XCTAssertNil(try JSONDecoder().decode(SetPlan.self, from: empty).targetBasisRaw)
        let number = Data(#"{"repsPerSide":3,"targetBasis":7}"#.utf8)
        XCTAssertNil(try JSONDecoder().decode(SetPlan.self, from: number).targetBasisRaw)

        let cf = Data(#"{"repsPerSide":3,"targetLoPercent":0.7,"targetBasis":"criticalForce"}"#.utf8)
        XCTAssertEqual(try JSONDecoder().decode(SetPlan.self, from: cf).targetBasis, .criticalForce)
    }

    /// An envelope written before the field existed decodes to the same plan and
    /// re-encodes byte for byte — no key appears on a plan nobody chose a basis for.
    func testAnOldPlanEncodesExactlyAsItDidBefore() throws {
        let draft = RoutineDraft.maxDay.normalized
        let encoder = JSONEncoder()
        encoder.outputFormatting = [.sortedKeys]
        let bytes = try encoder.encode(draft.plan)
        XCTAssertFalse(String(decoding: bytes, as: UTF8.self).contains("targetBasis"))
        let back = try JSONDecoder().decode(SessionPlan.self, from: bytes)
        XCTAssertEqual(back, draft.plan)
        XCTAssertEqual(try encoder.encode(back), bytes)
    }

    func testChoosingMaxIsEncodedOnceChosen() throws {
        var set = SetPlan(grip: grip, repsPerSide: 1, targetLoPercent: 0.2, targetHiPercent: 0.3)
        set.targetBasis = .max
        let text = String(decoding: try JSONEncoder().encode(set), as: UTF8.self)
        XCTAssertTrue(text.contains(#""targetBasis":"max""#))
    }

    func testRoutineShareRoundTripsTheBasisAtBothLevels() throws {
        var draft = RoutineDraft.blank(named: "Aerobic")
        draft.remindersEnabled = false
        draft.plan.sets = [cfSet(0.6, 0.7),
                           SetPlan(grip: grip, repsPerSide: 2, targetLoPercent: 0.2, targetHiPercent: 0.3)]
        // A routine-level band of CF on a set that inherits it — normalized demotes it.
        draft.plan.sets.append(SetPlan(grip: grip, repsPerSide: 1))
        draft.plan.targetLoPercent = 0.8
        draft.plan.targetHiPercent = 0.9
        draft.plan.targetBasis = .criticalForce
        let url = try XCTUnwrap(RoutineShare.url(for: draft))
        let back = try RoutineShare.draft(from: url)
        XCTAssertEqual(back.plan.sets[0].targetBasis, .criticalForce)
        XCTAssertEqual(back.plan.sets[1].targetBasis, .max)
        // The plan rides verbatim: the routine band keeps its basis, and the set that
        // inherits it resolves against critical force.
        XCTAssertEqual(back.plan.targetBasis, .criticalForce, "the routine band carried its basis")
        XCTAssertEqual(back.plan.targetPercentBand, 0.8...0.9)
        XCTAssertEqual(PlanMath.targetBasis(back.plan.sets[2], in: back.plan), .criticalForce)
        // And the importer's save demotes it onto the set, basis and all.
        XCTAssertEqual(back.normalized.plan.sets[2].targetBasis, .criticalForce)
    }

    // MARK: - Export

    /// `% max` and `% CF` are siblings: a CF pull reports against the CF as it stood at the
    /// session's start, never a later test and never the max; a max pull the reverse.
    func testTheExportReportsACriticalForcePullAgainstCriticalForceAsItStood() throws {
        let day = DayStamp(year: 2026, month: 8, day: 20)
        let start = day.date(calendar: DayStamp.utcCalendar).addingTimeInterval(9 * 3600)
        var p = SessionPlan()
        p.handMode = .alternateEachRep
        p.sets = [cfSet(0.7, 0.8), SetPlan(grip: grip, repsPerSide: 1, targetLoPercent: 0.2, targetHiPercent: 0.3)]
        func rep(_ set: Int, _ side: Side, _ peak: Double) -> RepSummary {
            var r = RepSummary()
            r.setIndex = set
            r.side = side
            r.grip = grip
            r.targetSeconds = 10
            r.heldSeconds = 10
            r.peakKg = peak
            r.avgKg = peak
            return r
        }
        var session = AnalysisExport.Session()
        session.day = day
        session.startedAt = start
        session.routineName = "Aerobic"
        session.reps = [rep(0, .left, 12), rep(0, .right, 11), rep(1, .left, 10)]
        session.plannedReps = 3
        session.completedReps = 3
        session.plan = p
        func test(_ side: Side, _ kg: Double, _ dt: TimeInterval) -> AnalysisExport.CriticalForceEntry {
            AnalysisExport.CriticalForceEntry(grip: grip, side: side, day: day,
                                              recordedAt: start.addingTimeInterval(dt), criticalForceKg: kg)
        }
        var input = AnalysisExport.Input()
        input.sessions = [session]
        input.maxes = [AnalysisExport.MaxEntry(grip: grip, side: .both, kg: 40, day: day - 5,
                                               recordedAt: start.addingTimeInterval(-5 * 86_400))]
        input.criticalForceTests = [test(.left, 15, -3600), test(.right, 14, 3600)]
        input.today = day
        input.generatedOn = day

        let doc = AnalysisExport.document(input)
        XCTAssertTrue(doc.contains("| % max | % CF | Outcome |"))
        XCTAssertTrue(doc.contains("| 12.0 | 12.0 | — | — | 80% | completed |"), "12 of the left CF of 15")
        XCTAssertTrue(doc.contains("| 11.0 | 11.0 | — | — | — | completed |"), "the right test came after the start")
        XCTAssertTrue(doc.contains("| 10.0 | 10.0 | — | 25% | — | completed |"), "a max pull reports % max only")

        let csv = AnalysisExport.csv(input, scope: .all, detail: .pulls).text
        let lines = csv.components(separatedBy: "\r\n")
        let header = lines[0].components(separatedBy: ",")
        XCTAssertEqual(Array(header.suffix(3)), ["target_basis", "cf_at_start_kg", "cf_reference"])
        let pulls = lines.filter { $0.hasPrefix("pull,") }.map { $0.components(separatedBy: ",") }
        func cell(_ row: [String], _ name: String) -> String { row[header.firstIndex(of: name)!] }
        XCTAssertEqual(pulls.map { cell($0, "target_basis") }, ["critical_force", "critical_force", "max"])
        XCTAssertEqual(pulls.map { cell($0, "cf_at_start_kg") }, ["15.0", "", ""])
        XCTAssertEqual(pulls.map { cell($0, "cf_reference") }, ["hand_specific", "no_recorded_cf_at_start", ""])
        XCTAssertEqual(pulls.map { cell($0, "max_at_start_kg") }, ["", "", "40.0"], "a CF pull names no max")
        XCTAssertEqual(pulls.map { cell($0, "max_basis") }, ["", "", "peak"])
    }

    func testNormalizedDemotesTheRoutineBasisWithItsBandAndDropsOrphans() {
        var draft = RoutineDraft.blank()
        var kgSet = SetPlan(grip: grip, repsPerSide: 1, targetLoKg: 8, targetHiKg: 10)
        kgSet.targetBasis = .criticalForce
        var orphan = SetPlan(grip: grip, repsPerSide: 1)
        orphan.targetBasis = .criticalForce
        var cfWithLength = cfSet(0.5, 0.6)
        cfWithLength.targetMaxSeconds = 10
        draft.plan.sets = [kgSet, orphan, cfWithLength]
        draft.plan.targetLoPercent = 0.7
        draft.plan.targetHiPercent = 0.8
        draft.plan.targetBasis = .criticalForce
        draft.plan.targetMaxSeconds = 7

        let saved = draft.normalized.plan
        XCTAssertNil(saved.targetBasisRaw)
        XCTAssertNil(saved.targetPercentBand)
        XCTAssertNil(saved.sets[0].targetBasisRaw, "a kg band carries no basis")
        XCTAssertEqual(saved.sets[1].targetBasis, .criticalForce, "the inheriting set took the routine's basis")
        XCTAssertEqual(saved.sets[1].targetPercentBand, 0.7...0.8)
        XCTAssertNil(saved.sets[1].targetMaxSeconds, "a critical force has no length")
        XCTAssertNil(saved.sets[2].targetMaxSeconds)
    }

    // MARK: - The table

    func testTheTableKeepsMaxTablesHandRules() {
        let t = cfTable([(.left, 14), (.both, 15)])
        XCTAssertEqual(t.criticalForce(grip: key, side: .left), 14)
        XCTAssertEqual(t.criticalForce(grip: key, side: .right), 15, "a hand falls back to both")
        XCTAssertEqual(t.criticalForce(grip: key, side: .both), 15)
        XCTAssertNil(t.exact(grip: key, side: .right))

        let oneHand = cfTable([(.left, 14)])
        XCTAssertNil(oneHand.criticalForce(grip: key, side: .right), "a one-handed CF never leaks")
        XCTAssertNil(oneHand.criticalForce(grip: key, side: .both), "both never sums or borrows a hand")

        let junk = cfTable([(.left, 0), (.right, -3), (.both, .nan)])
        XCTAssertTrue(junk.isEmpty)
    }

    func testTheFoldTakesTheNewestValidStandardTestPerGripAndHand() {
        let t0 = Date(timeIntervalSinceReferenceDate: 1_000)
        func entry(_ side: Side, _ kg: Double, _ dt: TimeInterval, proto: String = "7:3x24") -> CriticalForceTable.Entry {
            .init(gripKey: key, side: side, protocolKey: proto, criticalForceKg: kg,
                  recordedAt: t0.addingTimeInterval(dt))
        }
        let entries = [
            entry(.left, 14, 0),
            entry(.left, 16, 100),               // newest valid left
            entry(.left, 0, 200),                // invalid — never masks the 16
            entry(.left, 30, 300, proto: "5:5x20"),  // another protocol — never prescribes
            entry(.right, 12, 50),
        ]
        let now = CriticalForceTable.folding(entries)
        XCTAssertEqual(now.criticalForce(grip: key, side: .left), 16)
        XCTAssertEqual(now.criticalForce(grip: key, side: .right), 12)

        let then = CriticalForceTable.folding(entries, asOf: t0.addingTimeInterval(99))
        XCTAssertEqual(then.criticalForce(grip: key, side: .left), 14, "as it stood")
        let inclusive = CriticalForceTable.folding(entries, asOf: t0.addingTimeInterval(100))
        XCTAssertEqual(inclusive.criticalForce(grip: key, side: .left), 16, "at the instant counts")

        let tie = CriticalForceTable.folding([entry(.left, 14, 0), entry(.left, 15, 0)])
        XCTAssertEqual(tie.criticalForce(grip: key, side: .left), 15, "a tie goes to the later entry")
    }

    // MARK: - Resolution

    func testACriticalForceBandResolvesAgainstCriticalForceAndNeverTheMax() {
        let p = plan(cfSet(0.8, 0.9))
        let maxes = maxTable([(.both, 40)])
        let cfs = cfTable([(.left, 15), (.right, 20)])
        let set = p.sets[0]
        XCTAssertEqual(PlanMath.targetBand(set, in: p, side: .left, maxes: maxes, criticalForces: cfs), 12...13.5)
        XCTAssertEqual(PlanMath.targetBand(set, in: p, side: .right, maxes: maxes, criticalForces: cfs), 16...18)
        XCTAssertNil(PlanMath.targetBand(set, in: p, side: .left, maxes: maxes),
                     "no CF on file is no target — never 80 % of the max instead")
        XCTAssertNil(PlanMath.targetBand(set, in: p, side: .both, maxes: maxes, criticalForces: cfs),
                     "a both-hands rep needs a both-hands CF")
    }

    func testPrecedenceAndTheBasisTravelsWithItsLevel() {
        var p = SessionPlan()
        p.handMode = .bothHands
        p.targetLoPercent = 0.5
        p.targetHiPercent = 0.5
        p.targetBasis = .criticalForce
        let inherits = SetPlan(grip: grip, repsPerSide: 1)
        let ownMax = SetPlan(grip: grip, repsPerSide: 1, targetLoPercent: 0.25, targetHiPercent: 0.25)
        let kg = SetPlan(grip: grip, repsPerSide: 1, targetLoKg: 9, targetHiKg: 9)
        p.sets = [inherits, ownMax, kg]
        let maxes = maxTable([(.both, 40)])
        let cfs = cfTable([(.both, 16)])
        XCTAssertEqual(PlanMath.targetBasis(inherits, in: p), .criticalForce)
        XCTAssertEqual(PlanMath.targetBand(inherits, in: p, side: .both, maxes: maxes, criticalForces: cfs), 8...8)
        XCTAssertEqual(PlanMath.targetBasis(ownMax, in: p), .max, "its own band, its own (max) basis")
        XCTAssertEqual(PlanMath.targetBand(ownMax, in: p, side: .both, maxes: maxes, criticalForces: cfs), 10...10)
        XCTAssertEqual(PlanMath.targetBand(kg, in: p, side: .both, maxes: maxes, criticalForces: cfs), 9...9)
    }

    func testATimedLengthNeverSendsACriticalForceSetToATimedMax() {
        var set = cfSet(0.5, 0.5)
        set.targetMaxSeconds = 10
        let p = plan(set, mode: .bothHands)
        XCTAssertNil(PlanMath.maxSeconds(p.sets[0], in: p))
        var maxes = MaxTable()
        maxes.record(30, grip: key, side: .both, seconds: 10)
        XCTAssertNil(PlanMath.targetBand(p.sets[0], in: p, side: .both, maxes: maxes))
    }

    func testTheRunnerFreezesCriticalForceBandsPerHandAtStart() {
        let p = plan(cfSet(0.6, 0.8))
        let runner = SessionRunner(plan: p, maxes: maxTable([(.both, 40)]),
                                   criticalForces: cfTable([(.left, 15), (.right, 20)]))
        XCTAssertEqual(runner.slots.map(\.targetBand), [9...12, 12...16, 9...12, 12...16])
        let noCF = SessionRunner(plan: p, maxes: maxTable([(.both, 40)]))
        XCTAssertEqual(noCF.slots.compactMap(\.targetBand), [], "no CF, no target — the clock gates nothing")
    }

    func testUntargetedCountsAMissingCriticalForcePerHand() {
        let p = plan(cfSet(0.6, 0.8))
        XCTAssertEqual(PlanMath.untargetedGripCount(p, maxes: maxTable([(.both, 40)]),
                                                    criticalForces: cfTable([(.left, 15)])), 1)
        XCTAssertEqual(PlanMath.untargetedGripCount(p, maxes: MaxTable(),
                                                    criticalForces: cfTable([(.both, 15)])), 0,
                       "a CF set needs no max")
        let missing = PlanMath.missingReferences(p, maxes: MaxTable(), criticalForces: cfTable([(.left, 15)]))
        XCTAssertEqual(missing, [.init(grip: grip, side: .right, basis: .criticalForce, maxSeconds: nil)])
    }

    /// The rung colours a CF prescription only when it can be restated against the peak
    /// max; otherwise the set contributes nothing and the rung stays bleu (`unknown`).
    func testIntensityRestatesCriticalForceAgainstThePeakOrStaysUnknown() throws {
        let p = plan(cfSet(0.6, 0.8), mode: .bothHands)
        XCTAssertNil(PlanMath.peakIntensity(of: p, maxes: MaxTable()))
        XCTAssertNil(PlanMath.peakIntensity(of: p, maxes: maxTable([(.both, 40)])))
        XCTAssertNil(PlanMath.peakIntensity(of: p, maxes: MaxTable(), criticalForces: cfTable([(.both, 16)])))
        let fraction = try XCTUnwrap(PlanMath.peakIntensity(of: p, maxes: maxTable([(.both, 40)]),
                                                             criticalForces: cfTable([(.both, 16)])))
        XCTAssertEqual(fraction, 0.8 * 16 / 40, accuracy: 1e-12)
        XCTAssertEqual(PlanMath.IntensityBand.band(for: fraction), .moderate)
    }

    func testAMaxReceiptNeverMovesACriticalForceSet() {
        // A CF set at 20–30 % sits before a peak set at the SAME percentage. Keyed as
        // "peak", it used to swallow the real one.
        var p = SessionPlan()
        p.handMode = .bothHands
        p.sets = [cfSet(0.2, 0.3), SetPlan(grip: grip, repsPerSide: 1, targetLoPercent: 0.2, targetHiPercent: 0.3)]
        let routine = MaxReceiptMath.Routine(id: UUID(), name: "R", plan: p)
        let receipt = MaxReceiptMath.receipt(
            for: [MaxSave(grip: grip, side: .both, kg: 40, source: .manual)],
            previous: maxTable([(.both, 30)]), current: maxTable([(.both, 40)]),
            routines: [routine])
        XCTAssertEqual(receipt.percentMoves.count, 1)
        XCTAssertEqual(receipt.percentMoves.first?.move.newBand, 8...12)
    }
}
