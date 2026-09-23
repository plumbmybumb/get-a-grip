// SPDX-License-Identifier: MPL-2.0
// Original contributions Copyright 2026 Nuri Bruner.

import XCTest
@testable import Doigt

/// The rules Today fronts a routine by — lifted out of the view so they are computed
/// once per render instead of once per card, and so they can be pinned here.
final class TodayDeckTests: XCTestCase {
    private let primary = UUID()
    private let second = UUID()
    private let third = UUID()

    private func candidates(_ minutes: [[Int]]) -> [TodayDeck.Candidate] {
        zip([primary, second, third], minutes).map { TodayDeck.Candidate(id: $0, callingMinutes: $1) }
    }

    func testTheMostRecentReminderToFireCallsTheRoutine() {
        let deck = candidates([[7 * 60], [13 * 60], [19 * 60]])
        XCTAssertEqual(TodayDeck.upNextID(deck, suggestedID: nil, nowMinutes: 14 * 60), second)
        XCTAssertEqual(TodayDeck.upNextID(deck, suggestedID: nil, nowMinutes: 20 * 60), third)
    }

    func testAReminderStillAheadDoesNotCall() {
        let deck = candidates([[], [18 * 60], []])
        XCTAssertEqual(TodayDeck.upNextID(deck, suggestedID: nil, nowMinutes: 17 * 60), primary)
    }

    func testATieGoesToTheEarlierRoutine() {
        let deck = candidates([[9 * 60], [9 * 60], []])
        XCTAssertEqual(TodayDeck.upNextID(deck, suggestedID: third, nowMinutes: 10 * 60), primary)
    }

    /// Empty minutes are how a done routine (or one with reminders off) arrives; it
    /// must fall through to the started-today rung, then the primary.
    func testWithNothingCallingTheSuggestionThenThePrimaryLead() {
        let quiet = candidates([[], [], []])
        XCTAssertEqual(TodayDeck.upNextID(quiet, suggestedID: third, nowMinutes: 600), third)
        XCTAssertEqual(TodayDeck.upNextID(quiet, suggestedID: UUID(), nowMinutes: 600), primary,
                       "a suggestion for a routine that no longer exists is ignored")
        XCTAssertNil(TodayDeck.upNextID([], suggestedID: third, nowMinutes: 600))
    }

    func testTodaysChoiceWinsOnlyWhileItExists() {
        let ids = [primary, second]
        XCTAssertEqual(TodayDeck.selectedID(chosenID: second, chosenToday: true,
                                            among: ids, upNextID: primary), second)
        XCTAssertEqual(TodayDeck.selectedID(chosenID: second, chosenToday: false,
                                            among: ids, upNextID: primary), primary,
                       "yesterday's pin does not survive the day")
        XCTAssertEqual(TodayDeck.selectedID(chosenID: third, chosenToday: true,
                                            among: ids, upNextID: primary), primary,
                       "a routine deleted by a merge falls through")
    }

    func testMinutesNowReadsTheWallClock() {
        var calendar = Calendar(identifier: .gregorian)
        calendar.timeZone = TimeZone(identifier: "UTC")!
        let date = calendar.date(from: DateComponents(year: 2026, month: 9, day: 23,
                                                      hour: 13, minute: 5))!
        XCTAssertEqual(TodayDeck.minutesNow(date, calendar: calendar), 13 * 60 + 5)
    }
}
