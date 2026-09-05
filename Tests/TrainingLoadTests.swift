// SPDX-License-Identifier: MPL-2.0
// Original contributions Copyright 2026 Nuri Bruner.

import XCTest
@testable import Doigt

final class TrainingLoadTests: XCTestCase {
    func testCr10UsesTheTwoDocumentedFivePointMappings() {
        XCTAssertEqual(TrainingLoad.cr10(.easy), 2)
        XCTAssertEqual(TrainingLoad.cr10(.comfortable), 4)
        XCTAssertEqual(TrainingLoad.cr10(.solid), 6)
        XCTAssertEqual(TrainingLoad.cr10(.hard), 8)
        XCTAssertEqual(TrainingLoad.cr10(.maximal), 10)

        XCTAssertEqual(TrainingLoad.cr10(.nothing), 0)
        XCTAssertEqual(TrainingLoad.cr10(.light), 2.5)
        XCTAssertEqual(TrainingLoad.cr10(.worked), 5)
        XCTAssertEqual(TrainingLoad.cr10(.taxed), 7.5)
        XCTAssertEqual(TrainingLoad.cr10(.wrecked), 10)
    }

    func testSessionLoadIsIntensityTimesMinutes() {
        XCTAssertEqual(TrainingLoad.sessionLoad(cr10: 8, minutes: 30), 240)
    }

    func testDailyFoldsEntriesAndKeepsGapDays() {
        let first = DayStamp(raw: 10)
        let entries = [
            LoggedLoad(day: first, minutes: 60, rpe: .easy, finger: .light),
            LoggedLoad(day: first, minutes: 30, rpe: .hard, finger: nil),
            LoggedLoad(day: first + 2, minutes: 20, rpe: nil, finger: .wrecked),
        ]

        let result = TrainingLoad.daily(entries, from: first, to: first + 3)

        XCTAssertEqual(result.map(\.day), [first, first + 1, first + 2, first + 3])
        XCTAssertEqual(result[0].systemic, 360)
        XCTAssertEqual(result[0].finger, 150)
        XCTAssertEqual(result[1], .init(day: first + 1, systemic: 0, finger: 0))
        XCTAssertEqual(result[2].systemic, 0)
        XCTAssertEqual(result[2].finger, 200)
        XCTAssertEqual(result[3].systemic, 0)
        XCTAssertEqual(result[3].finger, 0)
    }

    func testRollingSumsUseInclusiveWindowEdges() {
        let first = DayStamp(raw: 20)
        let series = (0..<5).map {
            TrainingLoad.DayLoad(day: first + $0,
                                 systemic: Double($0 + 1), finger: Double(($0 + 1) * 10))
        }

        XCTAssertEqual(TrainingLoad.rolling(series, days: 3, endingAt: first + 2).systemic, 6)
        XCTAssertEqual(TrainingLoad.rolling(series, days: 3, endingAt: first + 2).finger, 60)
        XCTAssertEqual(TrainingLoad.rolling(series, days: 2, endingAt: first + 4).systemic, 9)
        XCTAssertEqual(TrainingLoad.rolling(series, days: 2, endingAt: first + 4).finger, 90)
    }

    func testNilRPEOrMinutesContributesZeroRatherThanAComputerGuess() {
        let day = DayStamp(raw: 40)
        let entries = [
            LoggedLoad(day: day, minutes: nil, rpe: .maximal, finger: .wrecked),
            LoggedLoad(day: day, minutes: 30, rpe: nil, finger: .taxed),
        ]

        let result = TrainingLoad.daily(entries, from: day, to: day)[0]

        XCTAssertEqual(result.systemic, 0)
        XCTAssertEqual(result.finger, 225)
    }
}
