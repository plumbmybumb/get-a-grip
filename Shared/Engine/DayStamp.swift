// SPDX-License-Identifier: MPL-2.0
// Original contributions Copyright 2026 Nuri Bruner.

import Foundation

/// A calendar day as an integer — days since 1970-01-01 (proleptic Gregorian).
///
/// A training day is the day the user lived through, which has no timezone: a session
/// finished at 00:30 belongs to the evening it was part of — the day turns at
/// `rolloverHour`, not midnight — and "2 of 2 today" must flip at that hour without a
/// relaunch. Storing days as integers makes all of
/// that pure integer math and immune to the DST/timezone off-by-one bugs `Date`
/// invites (a local-midnight `Date` re-read in another timezone shifts a day), and it
/// makes `WorkoutLog.dayKey` a cheap Int predicate instead of a Calendar pass over
/// every log.
///
/// The ONLY place `Calendar` appears is this file's conversion boundary: take Y/M/D in
/// the user's calendar, rebuild it in a fixed UTC Gregorian calendar, divide by 86 400.
///
/// Ported from Schengen Slice, trimmed to what a training app needs.
struct DayStamp: Hashable, Comparable, Sendable, Strideable {
    var raw: Int

    init(raw: Int) { self.raw = raw }

    static func < (lhs: DayStamp, rhs: DayStamp) -> Bool { lhs.raw < rhs.raw }

    // Strideable — lets ranges of days iterate naturally (the 14-day consistency strip
    // is literally `(today - 13)...today`).
    func advanced(by n: Int) -> DayStamp { DayStamp(raw: raw + n) }
    func distance(to other: DayStamp) -> Int { other.raw - raw }

    static func + (lhs: DayStamp, rhs: Int) -> DayStamp { DayStamp(raw: lhs.raw + rhs) }
    static func - (lhs: DayStamp, rhs: Int) -> DayStamp { DayStamp(raw: lhs.raw - rhs) }
    /// Signed distance in days (NOT a count of inclusive days — that is this + 1).
    static func - (lhs: DayStamp, rhs: DayStamp) -> Int { lhs.raw - rhs.raw }
}

// MARK: - Codable (a bare Int on the wire)

extension DayStamp: Codable {
    init(from decoder: Decoder) throws {
        raw = try decoder.singleValueContainer().decode(Int.self)
    }

    func encode(to encoder: Encoder) throws {
        var c = encoder.singleValueContainer()
        try c.encode(raw)
    }
}

// MARK: - Date boundary (the only Calendar use in the engine)

extension DayStamp {
    /// Fixed UTC Gregorian calendar: the neutral frame every Y/M/D is rebuilt in, so
    /// `raw` is exact division and never affected by the device's zone or DST.
    static let utcCalendar: Calendar = {
        var c = Calendar(identifier: .gregorian)
        c.timeZone = TimeZone(identifier: "UTC")!
        return c
    }()

    private static let secondsPerDay: Double = 86_400

    /// A Gregorian calendar in the given calendar's TIME ZONE. Only the zone is taken
    /// from the user's calendar, never its calendar system: a device set to the
    /// Buddhist or Japanese calendar reports years like 2569 or Reiwa 8, and rebuilding
    /// those components under Gregorian rules would shift every stamp by centuries.
    /// `raw` must always mean true days-since-1970 proleptic Gregorian.
    static func gregorian(zoneOf calendar: Calendar) -> Calendar {
        var g = Calendar(identifier: .gregorian)
        g.timeZone = calendar.timeZone
        return g
    }

    /// The calendar day `date` falls on in `calendar`'s time zone (default: the user's).
    init(date: Date, calendar: Calendar = .current) {
        let comps = Self.gregorian(zoneOf: calendar).dateComponents([.year, .month, .day], from: date)
        let utcMidnight = Self.utcCalendar.date(from: comps)!
        self.raw = Int((utcMidnight.timeIntervalSince1970 / Self.secondsPerDay).rounded(.down))
    }

    /// A specific Y/M/D (proleptic Gregorian) — mainly for tests and fixed dates.
    init(year: Int, month: Int, day: Int) {
        let utcMidnight = Self.utcCalendar.date(from: DateComponents(year: year, month: month, day: day))!
        self.raw = Int((utcMidnight.timeIntervalSince1970 / Self.secondsPerDay).rounded(.down))
    }

    /// TODAY is the training day, not the calendar day — see `rolloverHour`. Every
    /// "today" in the app comes through here (`DayClock`), so the tally, the strip, the
    /// grid and the day a session is filed under cannot disagree about when a day ends.
    /// `now` is a test seam.
    static func today(calendar: Calendar = .current, now: Date = .now) -> DayStamp {
        DayStamp(trainingDayOf: now, calendar: calendar)
    }

    // MARK: - The training day

    /// **A training day turns at 04:00, not at midnight.** A hang that starts at 23:47
    /// and ends 44 seconds past midnight is an evening session; filing it under the
    /// morning after scores one evening as two days, which is exactly what Nuri's own
    /// history showed (2026-09-20: the row dated the 19th, the tally and the calendar
    /// crediting the 20th). The promise this file always made — a 00:30 session belongs
    /// to the day the climber lived through — was never actually implemented until this
    /// constant existed: the clock simply turned at midnight. Anything in the small hours
    /// before this counts for the day before. Four is after any late session and before
    /// any morning one.
    static let rolloverHour = 4

    /// The training day `date` falls in: its calendar day, or the previous one when the
    /// local clock reads earlier than `rolloverHour`. Calendar arithmetic, deliberately
    /// not "minus four hours": on the night the clocks go back, 04:30 is only three and a
    /// half hours past midnight, and subtracting a fixed interval filed it a day early.
    init(trainingDayOf date: Date, calendar: Calendar = .current) {
        let comps = Self.gregorian(zoneOf: calendar)
            .dateComponents([.year, .month, .day, .hour], from: date)
        var day = DayStamp(year: comps.year!, month: comps.month!, day: comps.day!)
        if let hour = comps.hour, hour < Self.rolloverHour { day = day - 1 }
        self = day
    }

    /// The instant the training day after `date`'s begins — the coming 04:00 local. What
    /// `DayClock` sleeps until, since UIKit posts nothing at that hour.
    static func nextRollover(after date: Date, calendar: Calendar = .current) -> Date {
        let rollover = DateComponents(hour: rolloverHour, minute: 0, second: 0)
        return Self.gregorian(zoneOf: calendar)
            .nextDate(after: date, matching: rollover, matchingPolicy: .nextTime)
            ?? date.addingTimeInterval(Self.secondsPerDay)
    }

    /// Local start-of-day for this day — for date pickers and chart axes. (If local
    /// midnight does not exist on a DST edge, Calendar picks the first valid instant.)
    func date(calendar: Calendar = .current) -> Date {
        let utcDate = Date(timeIntervalSince1970: Double(raw) * Self.secondsPerDay)
        let comps = Self.utcCalendar.dateComponents([.year, .month, .day], from: utcDate)
        return Self.gregorian(zoneOf: calendar).date(from: comps)!
    }

    /// "21 Jul", through the user's locale — never hand-assembled from month names.
    func formatted(calendar: Calendar = .current) -> String {
        date(calendar: calendar).formatted(.dateTime.day().month(.abbreviated))
    }
}
