// SPDX-License-Identifier: MPL-2.0
// Original contributions Copyright 2026 Nuri Bruner.

import XCTest
@testable import Doigt

/// The export document, which is the one artifact in this app that leaves it as prose.
///
/// Everything asserted here is a claim about HONESTY rather than about layout: that a
/// number nobody measured never appears, that a max belonging to one hand never gets
/// spent on the other, that the boundary between "written out in full" and "rolled up"
/// falls where the legend says it does, and that two exports of the same history are the
/// same bytes — because a document that shuffles is a document you cannot diff.
final class AnalysisExportTests: XCTestCase {

    // MARK: - Fixtures

    private let today = DayStamp(year: 2026, month: 8, day: 28)

    private func date(_ day: DayStamp, hour: Int = 9) -> Date {
        day.date(calendar: DayStamp.utcCalendar).addingTimeInterval(Double(hour) * 3600)
    }

    private func grip(_ edge: Int = 20, _ fingers: FingerSet = .four,
                      _ position: GripPosition = .halfCrimp) -> GripSpec {
        GripSpec(edgeMM: edge, fingers: fingers, position: position)
    }

    private func rep(_ index: Int, side: Side, grip: GripSpec, peak: Double,
                     held: Double = 10, planned: Int = 10,
                     outcome: RepOutcome = .completed) -> RepSummary {
        var summary = RepSummary()
        summary.setIndex = 0
        summary.repIndex = index
        summary.side = side
        summary.grip = grip
        summary.targetSeconds = planned
        summary.heldSeconds = held
        summary.peakKg = peak
        summary.avgKg = peak * 0.9
        summary.outcome = outcome
        return summary
    }

    private func session(_ day: DayStamp,
                         id: UUID = UUID(),
                         name: String = "Daily no-hangs",
                         kind: SessionKind = .hang,
                         timing: AnalysisExport.Timing = .gauge,
                         reps: [RepSummary] = [],
                         held: Double? = nil,
                         planned: Int? = nil,
                         completed: Int? = nil,
                         minutes: Int? = 20,
                         rpe: RPE? = nil,
                         strain: FingerStrain? = nil) -> AnalysisExport.Session {
        AnalysisExport.Session(
            id: id,
            day: day,
            startedAt: date(day),
            routineName: name,
            kind: kind,
            minutes: minutes,
            rpe: rpe,
            fingerStrain: strain,
            peakKg: reps.map(\.peakKg).max() ?? 0,
            avgKg: reps.map(\.avgKg).max() ?? 0,
            totalHeldSeconds: held ?? reps.reduce(0) { $0 + $1.heldSeconds },
            plannedReps: planned ?? reps.count,
            completedReps: completed ?? reps.filter { $0.outcome == .completed }.count,
            timing: timing,
            reps: reps,
            notes: "")
    }

    private func maxEntry(_ kg: Double, grip: GripSpec, side: Side, on day: DayStamp,
                          source: MaxSource = .measured) -> AnalysisExport.MaxEntry {
        AnalysisExport.MaxEntry(grip: grip, side: side, kg: kg, day: day,
                                recordedAt: date(day, hour: 8), source: source)
    }

    private func input(sessions: [AnalysisExport.Session] = [],
                       maxes: [AnalysisExport.MaxEntry] = []) -> AnalysisExport.Input {
        AnalysisExport.Input(sessions: sessions, maxes: maxes, today: today,
                             generatedOn: today, sessionsPerDayTarget: 2)
    }

    /// The `% max` cell of the first rep row after `header` — the tables are pipe-
    /// delimited, so a cell is an exact string rather than something to regex for.
    private func repRows(_ document: String) -> [[String]] {
        document
            .split(separator: "\n", omittingEmptySubsequences: false)
            .filter { $0.hasPrefix("| ") && $0.contains(" | ") }
            .map { row in
                row.split(separator: "|", omittingEmptySubsequences: false)
                    .map { $0.trimmingCharacters(in: .whitespaces) }
                    .filter { !$0.isEmpty }
            }
    }

    /// A rep row is the ten-column one; everything else in the document is narrower.
    private func repRow(_ document: String, pull: Int) -> [String]? {
        repRows(document).first { $0.count == 10 && $0.first == "\(pull)" }
    }

    private static let percentColumn = 8

    // MARK: - The legend

    func testTheLegendIsPresentAndExplainsTheSchema() {
        let doc = AnalysisExport.document(input(
            sessions: [session(today, reps: [rep(0, side: .left, grip: grip(), peak: 12)])],
            maxes: [maxEntry(40, grip: grip(), side: .both, on: today - 10)]))

        XCTAssertTrue(doc.contains("## Legend"))
        XCTAssertTrue(doc.contains("written in English"),
                      "the first legend line has to state the fixed-schema rule")
        XCTAssertTrue(doc.contains("`20mm 4F HC`"), "the grip notation is decoded by example")
        XCTAssertTrue(doc.contains("`4F` — all four fingers"))
        XCTAssertTrue(doc.contains("`HC` — half crimp"))
        XCTAssertTrue(doc.contains("`L` left, `R` right, `B` both"))
        XCTAssertTrue(doc.contains("1 easy"), "the systemic axis states its stored 1–5 scale")
        XCTAssertTrue(doc.contains("5 wrecked"), "so does the local one")
        XCTAssertTrue(doc.contains("climbing day counts as training"))
        XCTAssertTrue(doc.contains("skipped"), "skipped pulls are recorded, and the legend says so")
        XCTAssertTrue(doc.contains("## Questions worth asking"))
    }

    /// The document must never claim a per-rep number the schema does not hold, and must
    /// name what it DOES hold — `RepSummary` stores peak and average per pull.
    func testTheLegendNamesWhereTheKilogramsComeFrom() {
        let doc = AnalysisExport.document(input(
            sessions: [session(today, reps: [rep(0, side: .left, grip: grip(), peak: 12)])]))
        XCTAssertTrue(doc.contains("Peak kg / Avg kg are stored PER REP"))
        XCTAssertTrue(doc.contains("the session-level figures"))
    }

    // MARK: - Intensity resolution

    /// The whole point of a blank: no max on file means no percentage, never a guess.
    func testARepWithNoMaxOnFileExportsABlankIntensity() {
        let doc = AnalysisExport.document(input(
            sessions: [session(today, reps: [rep(0, side: .left, grip: grip(), peak: 18)])]))

        let row = repRow(doc, pull: 1)
        XCTAssertNotNil(row)
        XCTAssertEqual(row?[Self.percentColumn], AnalysisExport.blank)
        XCTAssertFalse(row?[Self.percentColumn].contains("%") ?? true,
                       "a blank is a blank — never a number and never 0 %")
    }

    func testAHandSpecificMaxBeatsTheBothHandsOne() {
        let g = grip()
        let doc = AnalysisExport.document(input(
            sessions: [session(today, reps: [
                rep(0, side: .left, grip: g, peak: 20),
                rep(1, side: .right, grip: g, peak: 18),
            ])],
            maxes: [maxEntry(40, grip: g, side: .both, on: today - 20),
                    maxEntry(36, grip: g, side: .right, on: today - 10)]))

        XCTAssertEqual(repRow(doc, pull: 1)?[Self.percentColumn], "50%",
                       "no left max, so the both-hands one")
        XCTAssertEqual(repRow(doc, pull: 2)?[Self.percentColumn], "50%",
                       "18 of the right hand's own 36, not of the general 40")
    }

    /// Adding two hands together to invent a two-handed max would be a silent, doubled
    /// error pointed at somebody's fingers. It stays blank.
    func testABothHandsRepNeverResolvesAgainstASummedGuess() {
        let g = grip()
        let doc = AnalysisExport.document(input(
            sessions: [session(today, reps: [rep(0, side: .both, grip: g, peak: 40)])],
            maxes: [maxEntry(30, grip: g, side: .left, on: today - 10),
                    maxEntry(30, grip: g, side: .right, on: today - 10)]))

        XCTAssertEqual(repRow(doc, pull: 1)?[Self.percentColumn], AnalysisExport.blank)
    }

    /// A max recorded after a session must not rewrite what that session was pulling at.
    func testIntensityUsesTheMaxThatWasCurrentOnTheDay() {
        let g = grip()
        let old = today - 30
        let doc = AnalysisExport.document(input(
            sessions: [session(old, reps: [rep(0, side: .left, grip: g, peak: 20)])],
            maxes: [maxEntry(40, grip: g, side: .both, on: old - 5),
                    maxEntry(50, grip: g, side: .both, on: today)]))

        XCTAssertEqual(repRow(doc, pull: 1)?[Self.percentColumn], "50%",
                       "20 of the 40 that stood that day, not of today's 50")
    }

    // MARK: - Timer-only sessions

    func testATimerOnlySessionStatesWallClockAndCarriesNoKilograms() {
        let reps = [rep(0, side: .left, grip: grip(), peak: 0)]
        let doc = AnalysisExport.document(input(
            sessions: [session(today, timing: .timerOnly, reps: reps)],
            maxes: [maxEntry(40, grip: grip(), side: .both, on: today - 10)]))

        XCTAssertTrue(doc.contains("timer-only (wall clock)"),
                      "the session header names how its seconds were measured")
        XCTAssertTrue(doc.contains("came off the WALL CLOCK"),
                      "and the legend explains what that means")

        let row = repRow(doc, pull: 1)
        XCTAssertEqual(row?[5], AnalysisExport.blank, "peak kg")
        XCTAssertEqual(row?[6], AnalysisExport.blank, "avg kg")
        XCTAssertEqual(row?[Self.percentColumn], AnalysisExport.blank,
                       "a max on file cannot manufacture an intensity for an unmeasured pull")
    }

    /// A pull that was passed over registered no load because it never happened — which
    /// is a different fact from "it registered zero", and the difference is the whole
    /// reason the blank exists. Printing 0.0 would drag any average taken down the column.
    func testASkippedPullCarriesNoKilogramsAtAll() {
        let g = grip()
        let doc = AnalysisExport.document(input(
            sessions: [session(today, reps: [
                rep(0, side: .left, grip: g, peak: 20),
                rep(1, side: .right, grip: g, peak: 0, held: 0, outcome: .skipped),
            ], planned: 2, completed: 1)],
            maxes: [maxEntry(40, grip: g, side: .both, on: today - 10)]))

        let skipped = repRow(doc, pull: 2)
        XCTAssertEqual(skipped?[5], AnalysisExport.blank, "peak kg")
        XCTAssertEqual(skipped?[6], AnalysisExport.blank, "avg kg")
        XCTAssertEqual(skipped?[Self.percentColumn], AnalysisExport.blank)
        XCTAssertEqual(skipped?[9], "skipped", "but it is still recorded, as a skip")
        XCTAssertEqual(repRow(doc, pull: 1)?[5], "20.0", "the pull that happened is untouched")
    }

    func testAGaugeSessionSaysGauge() {
        let doc = AnalysisExport.document(input(
            sessions: [session(today, reps: [rep(0, side: .left, grip: grip(), peak: 12)])]))
        XCTAssertTrue(doc.contains(" · gauge"))
    }

    // MARK: - The 8-week boundary

    func testTheEightWeekBoundarySplitsDetailedFromRolledUp() {
        let inside = today - (AnalysisExport.detailedDays - 1)   // 56 days including today
        let outside = inside - 1
        XCTAssertEqual(AnalysisExport.detailCutoff(today: today), inside)

        let doc = AnalysisExport.document(input(sessions: [
            session(inside, name: "Inside", reps: [rep(0, side: .left, grip: grip(), peak: 12)]),
            session(outside, name: "Outside", reps: [rep(0, side: .left, grip: grip(), peak: 12)]),
        ]))

        let recent = doc.range(of: "## Sessions, last 8 weeks")!
        let rollup = doc.range(of: "## Older than 8 weeks, by week")!

        let detailed = String(doc[recent.lowerBound..<rollup.lowerBound])
        XCTAssertTrue(detailed.contains("Inside"), "the boundary day itself is written out")
        XCTAssertFalse(detailed.contains("Outside"), "one day earlier is not")

        let rolled = String(doc[rollup.lowerBound...])
        XCTAssertTrue(rolled.contains(AnalysisExport.isoDay(AnalysisExport.weekStart(outside))))
    }

    func testAnEmptyWindowSaysSoRatherThanDrawingAnEmptyTable() {
        let doc = AnalysisExport.document(input(sessions: [
            session(today - 100, reps: [rep(0, side: .left, grip: grip(), peak: 12)]),
        ]))
        XCTAssertTrue(doc.contains("No sessions in this window."))
    }

    // MARK: - Weekly rollups

    func testWeeklyRollupSumsAreRight() {
        // Two hangboard sessions and one climb, all in the same Monday-start week, all
        // well outside the detailed window.
        let monday = AnalysisExport.weekStart(today - 100)
        let a = session(monday + 1, name: "A",
                        reps: [rep(0, side: .left, grip: grip(), peak: 12, held: 10),
                               rep(1, side: .right, grip: grip(), peak: 12, held: 6,
                                   outcome: .earlyRelease)],
                        planned: 2, completed: 1, rpe: .solid, strain: .worked)
        let b = session(monday + 3, name: "B",
                        reps: [rep(0, side: .left, grip: grip(), peak: 12, held: 20)],
                        planned: 4, completed: 1, rpe: .hard, strain: .taxed)
        let climb = session(monday + 3, name: "Limit climbing", kind: .climbLimit,
                            timing: .logged, held: 0, planned: 0, completed: 0)

        let doc = AnalysisExport.document(input(sessions: [a, b, climb]))
        let week = AnalysisExport.isoDay(monday)
        let row = repRows(doc).first { $0.first == week && $0.count == 7 }

        XCTAssertNotNil(row, "one row for the week")
        XCTAssertEqual(row?[1], "3", "three sessions")
        XCTAssertEqual(row?[2], "1", "one climb DAY, not one climb session per hang")
        XCTAssertEqual(row?[3], "2/6", "completed over planned, summed")
        XCTAssertEqual(row?[4], "36s", "10 + 6 + 20 seconds under tension")
        XCTAssertEqual(row?[5], "3.5", "median of 3 and 4")
        XCTAssertEqual(row?[6], "3.5", "median of 3 and 4")
    }

    func testWeekStartIsMonday() {
        // 2026-08-28 is a Friday; its week starts on Monday 2026-08-24.
        XCTAssertEqual(AnalysisExport.isoDay(AnalysisExport.weekStart(today)), "2026-08-24")
        XCTAssertEqual(AnalysisExport.isoDay(today), "2026-08-28")
    }

    // MARK: - Consistency

    func testConsistencyCountsDaysTrainedIncludingClimbs() {
        let monday = AnalysisExport.weekStart(today)
        let doc = AnalysisExport.document(input(sessions: [
            session(monday, name: "A", reps: [rep(0, side: .left, grip: grip(), peak: 12)]),
            session(monday, name: "B", reps: [rep(0, side: .left, grip: grip(), peak: 12)]),
            session(monday + 2, name: "Gym", kind: .climbVolume, timing: .logged),
        ]))

        XCTAssertTrue(doc.contains("Target: 2 sessions a day"))
        let row = repRows(doc).first { $0.first == AnalysisExport.isoDay(monday) && $0.count == 3 }
        // Today (2026-08-28) is the Friday of this week, so only five of its days
        // exist yet — future days are not scored as misses.
        XCTAssertEqual(row?[1], "2 of 5", "two distinct days, one of them a climb")
        XCTAssertEqual(row?[2], "3", "three sessions across them")
    }

    /// The bug the feature's first real reader caught (2026-08-28): a history that
    /// begins mid-week printed its opening week as "N of 7", scoring days that predate
    /// the history as misses. The denominator is only the days inside
    /// [first recorded day … today].
    func testAWeekIsOnlyAsLongAsTheHistoryInsideIt() {
        // History begins on a Thursday, three weeks back, trained every day through
        // Sunday: 4 of 4, never 4 of 7.
        let thursday = AnalysisExport.weekStart(today) - 18   // Mon − 18 = Thu, 3 weeks back
        let doc = AnalysisExport.document(input(sessions: (0..<4).map {
            session(thursday + $0, name: "A", reps: [rep(0, side: .left, grip: grip(), peak: 12)])
        }))

        let week = AnalysisExport.weekStart(thursday)
        let row = repRows(doc).first { $0.first == AnalysisExport.isoDay(week) && $0.count == 3 }
        XCTAssertEqual(row?[1], "4 of 4",
                       "days before the first recorded session are not misses")
        // And with the whole history inside the detailed window, the sessions header
        // claims the history's own start, not the eight-week cutoff.
        XCTAssertTrue(doc.contains("Every session on record — the history begins "
                                   + AnalysisExport.isoDay(thursday) + "."))
        XCTAssertFalse(doc.contains("Every session on or after"))
    }

    // MARK: - Maxes

    func testCurrentMaxesKeepBothHandsApart() {
        let g = grip()
        let doc = AnalysisExport.document(input(maxes: [
            maxEntry(38, grip: g, side: .left, on: today - 20),
            maxEntry(34, grip: g, side: .right, on: today - 10, source: .manual),
        ]))

        XCTAssertTrue(doc.contains("| 20mm 4F HC | L | 38.0 | \(AnalysisExport.isoDay(today - 20)) | measured |"))
        XCTAssertTrue(doc.contains("| 20mm 4F HC | R | 34.0 | \(AnalysisExport.isoDay(today - 10)) | typed |"),
                      "a later right-hand record must not swallow the left's row")
    }

    func testMaxHistoryKeepsEveryRecord() {
        let g = grip()
        let doc = AnalysisExport.document(input(maxes: [
            maxEntry(30, grip: g, side: .both, on: today - 60),
            maxEntry(34, grip: g, side: .both, on: today - 30),
            maxEntry(36, grip: g, side: .both, on: today - 1),
        ]))
        XCTAssertTrue(doc.contains("### 20mm 4F HC · both hands"))
        for kg in ["30.0", "34.0", "36.0"] {
            XCTAssertTrue(doc.contains("| \(kg) |"), "\(kg) is part of the progression")
        }
    }

    // MARK: - Determinism

    /// Dictionaries are folded in several places here, and Swift's iteration order is not
    /// stable between instances — an export that shuffles its own sections cannot be
    /// diffed against last month's.
    func testTwoCallsProduceIdenticalDocuments() {
        let g1 = grip(20, .four, .halfCrimp)
        let g2 = grip(14, .frontThree, .openHand)
        let g3 = grip(20, .frontTwo, .fullCrimp)
        let sessions = (0..<12).map { index in
            session(today - index * 9,
                    name: "Routine \(index % 3)",
                    reps: [rep(0, side: .left, grip: g1, peak: 12 + Double(index)),
                           rep(1, side: .right, grip: g2, peak: 10 + Double(index)),
                           rep(2, side: .both, grip: g3, peak: 20 + Double(index))],
                    rpe: RPE(rawValue: index % 5 + 1),
                    strain: FingerStrain(rawValue: index % 5 + 1))
        }
        let maxes = [maxEntry(40, grip: g1, side: .both, on: today - 80),
                     maxEntry(36, grip: g1, side: .right, on: today - 40),
                     maxEntry(22, grip: g2, side: .left, on: today - 30),
                     maxEntry(24, grip: g3, side: .both, on: today - 5)]

        let one = AnalysisExport.document(input(sessions: sessions, maxes: maxes))
        let two = AnalysisExport.document(input(sessions: sessions, maxes: maxes))
        XCTAssertEqual(one, two)

        // And the input's own arrival order must not show through either.
        let shuffled = AnalysisExport.document(input(sessions: Array(sessions.reversed()),
                                                     maxes: Array(maxes.reversed())))
        XCTAssertEqual(one, shuffled, "ordering is derived from the data, not from the array")
    }

    func testAnEmptyHistoryStillProducesAWholeDocument() {
        let doc = AnalysisExport.document(input())
        XCTAssertTrue(doc.contains("## Legend"))
        XCTAssertTrue(doc.contains("No maxes recorded."))
        XCTAssertTrue(doc.contains("No sessions in this window."))
        XCTAssertTrue(doc.contains("No sessions on record."))
        XCTAssertTrue(doc.contains("## Questions worth asking"))
    }

    // MARK: - Notation

    func testGripNotationIsEnglishAndStableWhateverTheLocale() {
        XCTAssertEqual(AnalysisExport.gripCode(grip(20, .four, .halfCrimp)), "20mm 4F HC")
        XCTAssertEqual(AnalysisExport.gripCode(grip(14, .frontTwo, .openHand)), "14mm F2 OH")
        XCTAssertEqual(AnalysisExport.gripCode(grip(30, [.index, .middle, .thumb], .pinch)),
                       "30mm F2+T PN")
        XCTAssertEqual(AnalysisExport.gripCode(grip(18, .backThree, GripPosition("sloper"))),
                       "18mm B3 SLOPER")
    }

    func testKilogramsAreWrittenWithADotWhateverTheLocale() {
        XCTAssertEqual(AnalysisExport.kgText(12.34), "12.3")
        XCTAssertEqual(AnalysisExport.secondsText(9.06), "9.1")
        XCTAssertEqual(AnalysisExport.percentText(20, maxKg: 40), "50%")
        XCTAssertEqual(AnalysisExport.percentText(20, maxKg: nil), AnalysisExport.blank)
        XCTAssertEqual(AnalysisExport.percentText(20, maxKg: 0), AnalysisExport.blank)
    }

    func testDurationsReadAsEnglish() {
        XCTAssertEqual(AnalysisExport.durationText(0), "0s")
        XCTAssertEqual(AnalysisExport.durationText(45), "45s")
        XCTAssertEqual(AnalysisExport.durationText(125), "2m 5s")
        XCTAssertEqual(AnalysisExport.durationText(3_725), "1h 2m")
    }
}
