// SPDX-License-Identifier: MPL-2.0
// Original contributions Copyright 2026 Nuri Bruner.

import XCTest
@testable import Doigt

/// The rules Today fronts a routine by — lifted out of the view so they are computed
/// once per render instead of once per card, and so they can be pinned here.
final class TodaySelectionTests: XCTestCase {
    private let primary = UUID()
    private let second = UUID()
    private let third = UUID()

    private func candidates(_ minutes: [[Int]]) -> [TodaySelection.Candidate] {
        zip([primary, second, third], minutes).map { TodaySelection.Candidate(id: $0, callingMinutes: $1) }
    }

    func testTheMostRecentReminderToFireCallsTheRoutine() {
        let deck = candidates([[7 * 60], [13 * 60], [19 * 60]])
        XCTAssertEqual(TodaySelection.upNextID(deck, suggestedID: nil, nowMinutes: 14 * 60), second)
        XCTAssertEqual(TodaySelection.upNextID(deck, suggestedID: nil, nowMinutes: 20 * 60), third)
    }

    func testAReminderStillAheadDoesNotCall() {
        let deck = candidates([[], [18 * 60], []])
        XCTAssertEqual(TodaySelection.upNextID(deck, suggestedID: nil, nowMinutes: 17 * 60), primary)
    }

    func testATieGoesToTheEarlierRoutine() {
        let deck = candidates([[9 * 60], [9 * 60], []])
        XCTAssertEqual(TodaySelection.upNextID(deck, suggestedID: third, nowMinutes: 10 * 60), primary)
    }

    /// Empty minutes are how a done routine (or one with reminders off) arrives; it
    /// must fall through to the started-today rung, then the primary.
    func testWithNothingCallingTheSuggestionThenThePrimaryLead() {
        let quiet = candidates([[], [], []])
        XCTAssertEqual(TodaySelection.upNextID(quiet, suggestedID: third, nowMinutes: 600), third)
        XCTAssertEqual(TodaySelection.upNextID(quiet, suggestedID: UUID(), nowMinutes: 600), primary,
                       "a suggestion for a routine that no longer exists is ignored")
        XCTAssertNil(TodaySelection.upNextID([], suggestedID: third, nowMinutes: 600))
    }

    func testTodaysChoiceWinsOnlyWhileItExists() {
        let ids = [primary, second]
        XCTAssertEqual(TodaySelection.selectedID(chosenID: second, chosenToday: true,
                                            among: ids, upNextID: primary), second)
        XCTAssertEqual(TodaySelection.selectedID(chosenID: second, chosenToday: false,
                                            among: ids, upNextID: primary), primary,
                       "yesterday's pin does not survive the day")
        XCTAssertEqual(TodaySelection.selectedID(chosenID: third, chosenToday: true,
                                            among: ids, upNextID: primary), primary,
                       "a routine deleted by a merge falls through")
    }

    /// Between midnight and the 04:00 rollover it is still the evening before: the 23:00
    /// reminder called most recently and the 08:00 one has not called yet — the same
    /// order `ReminderPlanner` suppresses by.
    func testAfterMidnightTheEveningReminderStillCalls() {
        let deck = candidates([[8 * 60], [23 * 60], []])
        XCTAssertEqual(TodaySelection.upNextID(deck, suggestedID: nil, nowMinutes: 30), second)
        let lateNight = candidates([[23 * 60], [60], []])
        XCTAssertEqual(TodaySelection.upNextID(lateNight, suggestedID: nil, nowMinutes: 30), primary,
                       "a 01:00 reminder has not called at 00:30")
        XCTAssertEqual(TodaySelection.upNextID(lateNight, suggestedID: nil, nowMinutes: 90), second)
    }

    func testMinutesNowReadsTheWallClock() {
        var calendar = Calendar(identifier: .gregorian)
        calendar.timeZone = TimeZone(identifier: "UTC")!
        let date = calendar.date(from: DateComponents(year: 2026, month: 9, day: 23,
                                                      hour: 13, minute: 5))!
        XCTAssertEqual(TodaySelection.minutesNow(date, calendar: calendar), 13 * 60 + 5)
    }
}
