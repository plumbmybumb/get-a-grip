// SPDX-License-Identifier: MPL-2.0
// Original contributions Copyright 2026 Nuri Bruner.

import XCTest
@testable import Doigt

/// `DayStamp` is the join for everything day-shaped: "2 of 2 today", the 14-day
/// consistency strip, and `WorkoutLog.dayKey`. It is an epoch-day Int so all of that
/// is integer math, with `Calendar` touched only where a human date enters or leaves.
final class DayStampTests: XCTestCase {

    private func calendar(_ zone: String) -> Calendar {
        var c = Calendar(identifier: .gregorian)
        c.timeZone = TimeZone(identifier: zone)!
        return c
    }

    func testEpochAnchorAndIntegerArithmetic() {
        XCTAssertEqual(DayStamp(year: 1970, month: 1, day: 1).raw, 0)
        XCTAssertEqual(DayStamp(year: 1970, month: 1, day: 2).raw, 1)
        XCTAssertEqual(DayStamp(year: 2026, month: 8, day: 3) - DayStamp(year: 2026, month: 7, day: 24), 10)
        XCTAssertEqual(DayStamp(year: 2026, month: 7, day: 24) + 10, DayStamp(year: 2026, month: 8, day: 3))
        // 2028 is a leap year, 2027 is not — the arithmetic must not be calendar-blind
        // in the direction that loses a day.
        XCTAssertEqual(DayStamp(year: 2028, month: 3, day: 1) - DayStamp(year: 2028, month: 2, day: 28), 2)
        XCTAssertEqual(DayStamp(year: 2027, month: 3, day: 1) - DayStamp(year: 2027, month: 2, day: 28), 1)
    }

    /// A stamp names the day you actually experienced, which is a function of where you
    /// were standing. Once frozen into `WorkoutLog.dayKey` it is a bare Int, so flying
    /// to Los Angeles can never retro-move a session Nuri did in Paris to another day.
    func testDayKeyIsStableAcrossATimeZoneChange() {
        let paris = calendar("Europe/Paris")
        let losAngeles = calendar("America/Los_Angeles")

        // 12:00 in Paris is 03:00 the same date in Los Angeles — one instant, one day.
        let midday = paris.date(from: DateComponents(year: 2026, month: 7, day: 24, hour: 12))!
        XCTAssertEqual(DayStamp(date: midday, calendar: paris), DayStamp(year: 2026, month: 7, day: 24))
        XCTAssertEqual(DayStamp(date: midday, calendar: losAngeles), DayStamp(year: 2026, month: 7, day: 24))

        // Freeze it, then move the phone. The Int does not care where it is read.
        let frozen = DayStamp(date: midday, calendar: paris).raw
        XCTAssertEqual(DayStamp(raw: frozen), DayStamp(year: 2026, month: 7, day: 24))
        XCTAssertEqual(DayStamp(raw: frozen).raw, frozen)
    }

    /// The case that makes freezing `dayKey` at save the right call rather than deriving
    /// it later: a 00:30 session belongs to the day the user lived through, and a UTC
    /// read of the same instant would file it under the previous day.
    func testMidnightThirtySessionBelongsToTheLocalDayItStartedOn() {
        let paris = calendar("Europe/Paris")
        let utc = calendar("UTC")
        let lateNight = paris.date(from: DateComponents(year: 2026, month: 7, day: 24,
                                                        hour: 0, minute: 30))!

        XCTAssertEqual(DayStamp(date: lateNight, calendar: paris), DayStamp(year: 2026, month: 7, day: 24))
        XCTAssertEqual(DayStamp(date: lateNight, calendar: utc), DayStamp(year: 2026, month: 7, day: 23),
                       "reading the same instant in UTC files it under the wrong day")
    }

    /// A Thai-region iPhone defaults to the Buddhist calendar (2026 CE = 2569 BE) and a
    /// Japanese one can use the era calendar. `gregorian(zoneOf:)` takes the ZONE and
    /// never the calendar system, so epoch days stay proleptic Gregorian everywhere.
    func testNonGregorianDeviceCalendarStillProducesGregorianEpochDays() {
        var buddhist = Calendar(identifier: .buddhist)
        buddhist.timeZone = TimeZone(identifier: "Asia/Bangkok")!
        var japanese = Calendar(identifier: .japanese)
        japanese.timeZone = TimeZone(identifier: "Asia/Tokyo")!
        let gregorianBangkok = calendar("Asia/Bangkok")

        let noon = gregorianBangkok.date(from: DateComponents(year: 2026, month: 7, day: 24, hour: 12))!
        XCTAssertEqual(DayStamp(date: noon, calendar: buddhist),
                       DayStamp(date: noon, calendar: gregorianBangkok))
        XCTAssertEqual(DayStamp(date: noon, calendar: buddhist), DayStamp(year: 2026, month: 7, day: 24))

        // Leap-day integrity through a non-Gregorian calendar.
        let feb29 = gregorianBangkok.date(from: DateComponents(year: 2028, month: 2, day: 29, hour: 12))!
        let mar1 = gregorianBangkok.date(from: DateComponents(year: 2028, month: 3, day: 1, hour: 12))!
        XCTAssertEqual(DayStamp(date: mar1, calendar: buddhist) - DayStamp(date: feb29, calendar: buddhist), 1)

        for stamp in [DayStamp(year: 2026, month: 7, day: 24), DayStamp(year: 2028, month: 2, day: 29)] {
            XCTAssertEqual(DayStamp(date: stamp.date(calendar: japanese), calendar: japanese), stamp)
            XCTAssertEqual(DayStamp(date: stamp.date(calendar: buddhist), calendar: buddhist), stamp)
        }
    }

    func testDateRoundTripAcrossZonesIncludingDSTTransitions() {
        let stamps = [
            DayStamp(year: 2026, month: 8, day: 3),
            DayStamp(year: 2026, month: 12, day: 31),
            DayStamp(year: 2027, month: 3, day: 28),   // EU DST spring forward
            DayStamp(year: 2027, month: 10, day: 31),  // EU DST fall back
        ]
        for zone in ["Europe/Paris", "Asia/Tokyo", "Pacific/Honolulu", "America/Santiago"] {
            let c = calendar(zone)
            for stamp in stamps {
                XCTAssertEqual(DayStamp(date: stamp.date(calendar: c), calendar: c), stamp,
                               "round-trip failed in \(zone)")
            }
        }
    }

    func testStrideableAndComparableBehaveLikeIntegers() {
        let day = DayStamp(year: 2026, month: 8, day: 3)
        XCTAssertEqual(day.advanced(by: 13), day + 13)
        XCTAssertEqual(day.distance(to: day + 13), 13)
        XCTAssertLessThan(day - 1, day)
        XCTAssertEqual(Array(stride(from: day - 13, through: day, by: 1)).count, 14,
                       "the consistency strip is exactly this stride")
    }

    func testDayStampEncodesAsABareInt() throws {
        let data = try JSONEncoder().encode(DayStamp(raw: 20_669))
        XCTAssertEqual(String(decoding: data, as: UTF8.self), "20669")
        XCTAssertEqual(try JSONDecoder().decode(DayStamp.self, from: Data("20669".utf8)),
                       DayStamp(raw: 20_669))
    }

    // MARK: - The training day turns at 04:00

    /// Nuri's 2026-09-19 hang: started 23:47, over 44 seconds past midnight. One evening,
    /// one day — and "today" at 00:30 is still that evening, while the CALENDAR day is
    /// untouched for everything that genuinely wants a date.
    func testTheTrainingDayTurnsAtFourNotMidnight() {
        let paris = calendar("Europe/Paris")
        func at(_ day: Int, _ hour: Int, _ minute: Int = 0, _ second: Int = 0) -> Date {
            paris.date(from: DateComponents(year: 2026, month: 9, day: day,
                                            hour: hour, minute: minute, second: second))!
        }
        let sept19 = DayStamp(year: 2026, month: 9, day: 19)
        let sept20 = DayStamp(year: 2026, month: 9, day: 20)

        XCTAssertEqual(DayStamp(trainingDayOf: at(19, 23, 47), calendar: paris), sept19)
        XCTAssertEqual(DayStamp(trainingDayOf: at(20, 0, 0, 44), calendar: paris), sept19,
                       "44 seconds past midnight is still the evening")
        XCTAssertEqual(DayStamp(trainingDayOf: at(20, 3, 59, 59), calendar: paris), sept19)
        XCTAssertEqual(DayStamp(trainingDayOf: at(20, 4), calendar: paris), sept20)
        XCTAssertEqual(DayStamp(trainingDayOf: at(20, 10, 1), calendar: paris), sept20)

        XCTAssertEqual(DayStamp.today(calendar: paris, now: at(20, 0, 30)), sept19,
                       "'today' is the training day")
        XCTAssertEqual(DayStamp.today(calendar: paris, now: at(20, 4)), sept20)
        XCTAssertEqual(DayStamp(date: at(20, 0, 30), calendar: paris), sept20,
                       "the calendar day is untouched")
    }

    /// The night the clocks go forward in Paris (29 March 2026) has 23 hours. "Minus four
    /// hours" from 04:30 CEST lands at 23:30 CET the evening before — a session started
    /// well after the rollover filed under the wrong day. Calendar arithmetic does not.
    func testTheRolloverSurvivesTheNightTheClocksGoForward() {
        let paris = calendar("Europe/Paris")
        let halfPastFour = paris.date(from: DateComponents(year: 2026, month: 3, day: 29, hour: 4, minute: 30))!
        XCTAssertEqual(DayStamp(date: halfPastFour.addingTimeInterval(-4 * 3600), calendar: paris),
                       DayStamp(year: 2026, month: 3, day: 28),
                       "the naive arithmetic this test exists to rule out")
        XCTAssertEqual(DayStamp(trainingDayOf: halfPastFour, calendar: paris),
                       DayStamp(year: 2026, month: 3, day: 29))
        let halfPastThree = paris.date(from: DateComponents(year: 2026, month: 3, day: 29, hour: 3, minute: 30))!
        XCTAssertEqual(DayStamp(trainingDayOf: halfPastThree, calendar: paris),
                       DayStamp(year: 2026, month: 3, day: 28))
    }

    /// What `DayClock` sleeps until: the coming 04:00, and exactly 04:00 already counts
    /// as the new day so the next wake-up is a day away.
    func testNextRolloverIsTheComingFourAM() {
        let paris = calendar("Europe/Paris")
        let lateEvening = paris.date(from: DateComponents(year: 2026, month: 9, day: 19, hour: 23, minute: 47))!
        let fourAM = paris.date(from: DateComponents(year: 2026, month: 9, day: 20, hour: 4))!
        XCTAssertEqual(DayStamp.nextRollover(after: lateEvening, calendar: paris), fourAM)
        let morning = paris.date(from: DateComponents(year: 2026, month: 9, day: 20, hour: 9))!
        XCTAssertEqual(DayStamp.nextRollover(after: morning, calendar: paris),
                       fourAM.addingTimeInterval(24 * 3600))
        XCTAssertEqual(DayStamp.nextRollover(after: fourAM, calendar: paris),
                       fourAM.addingTimeInterval(24 * 3600))
    }
}
