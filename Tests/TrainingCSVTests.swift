// SPDX-License-Identifier: MPL-2.0
// Original contributions Copyright 2026 Nuri Bruner.

import XCTest
@testable import Doigt

final class TrainingCSVTests: XCTestCase {
    private let today = DayStamp(raw: 20_000)
    private func session(_ day: DayStamp, name: String = "Daily") -> AnalysisExport.Session {
        AnalysisExport.Session(day: day, startedAt: Date(timeIntervalSince1970: Double(day.raw) * 86_400),
                               routineName: name, plannedReps: 1, completedReps: 1,
                               reps: [RepSummary(peakKg: 12, avgKg: 10)], sessionsPerDayTarget: 2)
    }
    private func input(_ sessions: [AnalysisExport.Session], _ maxes: [AnalysisExport.MaxEntry] = []) -> AnalysisExport.Input {
        AnalysisExport.Input(sessions: sessions, maxes: maxes, today: today, generatedOn: today)
    }
    private func pullCSV(_ input: AnalysisExport.Input, scope: AnalysisExport.CSVScope = .recent) -> AnalysisExport.CSVDocument {
        AnalysisExport.csv(input, scope: scope, detail: .pulls)
    }
    // Numeric assertions use unquoted data rows. The quoting case is checked separately.
    private func records(_ document: AnalysisExport.CSVDocument, type: String) -> [[String: String]] {
        let lines = document.text.components(separatedBy: "\r\n")
        let keys = lines[0].components(separatedBy: ",")
        return lines.filter { $0.hasPrefix(type + ",") }.map {
            Dictionary(uniqueKeysWithValues: zip(keys, $0.components(separatedBy: ",")))
        }
    }
    func testRangeIsInclusiveAndAllHistoryRetainsOldPulls() {
        let data = input([session(today), session(today - 55), session(today - 56)])
        XCTAssertEqual(pullCSV(data).sessionCount, 2)
        XCTAssertEqual(pullCSV(data, scope: .all).pullCount, 3)
        XCTAssertEqual(records(pullCSV(data), type: "workout").first?["daily_target"], "2")
    }
    func testWorkoutUsesHistoricalMaxButExportsNoOtherWorkoutsOrMaxHistory() {
        let s = session(today)
        let old = AnalysisExport.MaxEntry(grip: GripSpec(), side: .left, kg: 30, day: today - 100,
                                         recordedAt: s.startedAt.addingTimeInterval(-8_640_000))
        let future = AnalysisExport.MaxEntry(grip: GripSpec(), side: .left, kg: 90, day: today,
                                            recordedAt: s.startedAt.addingTimeInterval(1))
        let csv = pullCSV(input([s], [future, old]), scope: .workout)
        XCTAssertEqual(csv.sessionCount, 1)
        XCTAssertEqual(csv.maxCount, 0)
        XCTAssertEqual(records(csv, type: "pull").first?["max_at_start_kg"], "30.0")
        XCTAssertFalse(csv.text.contains("90.0"))
        XCTAssertTrue(csv.filename.hasSuffix(".csv"))
    }
    func testSkippedAndTimerOnlyForceIsBlankButRealZeroSurvives() {
        var s = session(today)
        s.reps = [RepSummary(peakKg: 0), RepSummary(peakKg: 20, outcome: .skipped)]
        var rows = records(pullCSV(input([s])), type: "pull")
        XCTAssertEqual(rows[0]["peak_kg"], "0.0")
        XCTAssertEqual(rows[1]["peak_kg"], "")
        s.timing = .timerOnly
        rows = records(pullCSV(input([s])), type: "pull")
        XCTAssertTrue(rows.allSatisfy { $0["peak_kg"] == "" && $0["avg_kg"] == "" })
    }
    func testQuotedUnicodeNotesAndFormulaLikeNamesSurviveSafely() {
        var s = session(today, name: "=HYPERLINK(\"test\")")
        s.notes = "Flexion, café\n\"felt good\""
        let csv = pullCSV(input([s])).text
        XCTAssertTrue(csv.contains("\"'=HYPERLINK(\"\"test\"\")\""))
        XCTAssertTrue(csv.contains("\"Flexion, café\n\"\"felt good\"\"\""))
    }
    func testNewlineWithoutAnyOtherCSVPunctuationIsQuoted() {
        var s = session(today)
        for note in ["first\nsecond", "first\rsecond", "first\r\nsecond"] {
            s.notes = note
            XCTAssertTrue(pullCSV(input([s])).text.contains("\"" + note + "\""))
        }
    }
    func testCurlIdentityTargetAndOutcomeRemainExplicit() {
        var s = session(today)
        s.reps = [RepSummary(grip: GripSpec(position: .fingerCurl), targetLoKg: 8, targetHiKg: 12, outcome: .earlyRelease)]
        let pull = records(pullCSV(input([s])), type: "pull")[0]
        XCTAssertEqual(pull["position"], "fingerCurl")
        XCTAssertEqual(pull["fingers"], "IMRL")
        XCTAssertEqual(pull["target_low_kg"], "8.0")
        XCTAssertEqual(pull["target_high_kg"], "12.0")
        XCTAssertEqual(pull["outcome"], "earlyRelease")
    }

    func testPermanentKeysAndSelfContainedPullsAcrossRangesAndNewWorkouts() {
        let original = session(today - 1)
        let alone = records(pullCSV(input([original]), scope: .workout), type: "pull")[0]
        let expanded = records(pullCSV(input([session(today), original]), scope: .all), type: "pull")[1]
        XCTAssertEqual(alone["workout"], original.id.uuidString.lowercased())
        XCTAssertEqual(alone, expanded)
        XCTAssertEqual(alone["routine"], "Daily")
        XCTAssertEqual(alone["date"], AnalysisExport.isoDay(today - 1))
        XCTAssertEqual(alone["time_source"], "not_recorded")
    }

    func testSummaryPreservesOutcomesWeightedLoadsAndSeparateHandsAndPrescriptions() {
        var s = session(today)
        s.reps = [RepSummary(heldSeconds: 2, peakKg: 12, avgKg: 10),
                  RepSummary(heldSeconds: 6, peakKg: 24, avgKg: 20, outcome: .earlyRelease),
                  RepSummary(peakKg: 999, outcome: .skipped),
                  RepSummary(side: .right, heldSeconds: 3, peakKg: 9, avgKg: 8),
                  RepSummary(targetLoKg: 5, targetHiKg: 10)]
        let rows = records(AnalysisExport.csv(input([s])), type: "set")
        XCTAssertEqual(rows.count, 3)
        XCTAssertEqual(rows[0]["recorded"], "3")
        XCTAssertEqual(rows[0]["completed"], "1")
        XCTAssertEqual(rows[0]["held_s"], "8.0")
        XCTAssertEqual(rows[0]["peak_kg"], "24.0")
        XCTAssertEqual(rows[0]["avg_kg"], "17.5")
        XCTAssertEqual(rows[0]["outcome"], "completed:1|earlyRelease:1|skipped:1")
        XCTAssertEqual(rows[1]["hand"], "right")
        XCTAssertEqual(rows[2]["target_low_kg"], "5.0")
        s.timing = .timerOnly
        XCTAssertTrue(records(AnalysisExport.csv(input([s])), type: "set").allSatisfy { $0["peak_kg"] == "" && $0["avg_kg"] == "" })
    }

    func testElapsedTimingSavedRestAndBenchmarkProvenance() {
        var s = session(today)
        let plan = SessionPlan(sets: [SetPlan(repsPerSide: 2)], handMode: .bothHands,
                               holdSeconds: 10, restSeconds: 20, leadInSeconds: 3)
        s.plan = plan
        s.finishedAt = s.startedAt.addingTimeInterval(90)
        s.reps = [RepSummary(side: .both, startedElapsedSeconds: 3, endedElapsedSeconds: 13),
                  RepSummary(repIndex: 1, side: .both, startedElapsedSeconds: 43, endedElapsedSeconds: 53)]
        let maxEntry = AnalysisExport.MaxEntry(kg: 40, recordedAt: s.startedAt.addingTimeInterval(-1))
        var rows = records(pullCSV(input([s], [maxEntry])), type: "pull")
        XCTAssertEqual(rows[0]["planned_lead_in_s"], "3")
        XCTAssertEqual(rows[0]["planned_rest_s"], "20")
        XCTAssertEqual(rows[1]["gap_before_s"], "30.0")
        XCTAssertEqual(rows[0]["max_reference"], "both_hands")
        XCTAssertEqual(records(pullCSV(input([s])), type: "workout")[0]["time_source"], "runner_completion")
        s.reps[0].side = .left
        rows = records(pullCSV(input([s], [maxEntry])), type: "pull")
        XCTAssertEqual(rows[0]["max_reference"], "both_hands_fallback")
        XCTAssertEqual(records(pullCSV(input([s])), type: "pull")[0]["max_reference"], "no_recorded_max_at_start")
        s.reps[0].startedElapsedSeconds = -1
        XCTAssertEqual(records(pullCSV(input([s])), type: "pull")[0]["started_elapsed_s"], "")
    }

    func testSummaryDoesNotMergePrescriptionsThatRoundToTheSameDisplayValue() {
        var s = session(today)
        s.reps = [RepSummary(targetLoKg: 8.01, targetHiKg: 12), RepSummary(targetLoKg: 8.04, targetHiKg: 12)]
        XCTAssertEqual(records(AnalysisExport.csv(input([s])), type: "set").count, 2)
    }
}
