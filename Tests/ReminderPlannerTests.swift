// SPDX-License-Identifier: MPL-2.0
// Original contributions Copyright 2026 Nuri Bruner.

import XCTest
@testable import Doigt

/// The planner is wipe-and-reschedule, so there is no incremental state to corrupt —
/// which only holds if `requests(for:)` is a pure function of the routines. These tests
/// exercise that function alone; nothing here touches UNUserNotificationCenter.
@MainActor
final class ReminderPlannerTests: XCTestCase {

    private let routineA = UUID(uuidString: "11111111-2222-3333-4444-555555555555")!
    private let routineB = UUID(uuidString: "66666666-7777-8888-9999-000000000000")!

    private func input(_ id: UUID, _ name: String, _ times: [ReminderTime],
                       enabled: Bool = true,
                       outstanding: Int = 99) -> ReminderPlanner.RoutinePlanInput {
        // `outstanding` defaults high so the pre-existing tests keep asserting the
        // unsuppressed plan; the suppression cases below set it deliberately.
        ReminderPlanner.RoutinePlanInput(id: id, name: name, reminders: times,
                                         enabled: enabled, outstandingToday: outstanding)
    }

    // MARK: - Suppression

    /// The ritual's whole promise is that the app stops asking once you have done it.
    /// A reminder firing after the second session of the day is the app failing to
    /// notice you succeeded — and that is how notifications get switched off for good.
    func testSlotsAreSuppressedFromTheFrontOfTheDayAsSessionsAreDone() {
        let morning = ReminderTime(hour: 8, minute: 0)
        let evening = ReminderTime(hour: 19, minute: 0)

        let untouched = ReminderPlanner.requests(
            for: [input(routineA, "Daily", [morning, evening], outstanding: 2)])
        XCTAssertEqual(untouched.count, 2, "nothing done yet, both slots stand")

        // Having trained once, it is the MORNING you have satisfied. Dropping the later
        // slot instead would silence the reminder you still need.
        let halfDone = ReminderPlanner.requests(
            for: [input(routineA, "Daily", [morning, evening], outstanding: 1)])
        XCTAssertEqual(halfDone.count, 2, "future occurrences must survive today's completion")
        XCTAssertEqual(halfDone.map(\.suppressToday), [true, false])

        let finished = ReminderPlanner.requests(
            for: [input(routineA, "Daily", [morning, evening], outstanding: 0)])
        XCTAssertEqual(finished.count, 2)
        XCTAssertTrue(finished.allSatisfy(\.suppressToday), "say nothing today, resume tomorrow")
    }

    /// Overshooting the target must not wrap around and re-arm the morning.
    func testTrainingMoreThanPlannedStillSilencesTheRestOfTheDay() {
        let times = [ReminderTime(hour: 8, minute: 0), ReminderTime(hour: 19, minute: 0)]
        let requests = ReminderPlanner.requests(
            for: [input(routineA, "Daily", times, outstanding: -3)])
        XCTAssertEqual(requests.count, 2)
        XCTAssertTrue(requests.allSatisfy(\.suppressToday))
    }

    func testCompletedDayKeepsTomorrowAndFillsOnlyAvailableBudget() throws {
        let calendar = DayStamp.utcCalendar
        let now = try XCTUnwrap(calendar.date(from: DateComponents(year: 2026, month: 9, day: 6, hour: 7)))
        let routines = [input(routineA, "Daily", [ReminderTime(hour: 8, minute: 0),
                                                 ReminderTime(hour: 19, minute: 0)], outstanding: 0)]
        let planned = ReminderPlanner.scheduledRequests(for: routines, now: now, calendar: calendar, limit: 3)
        let dates = try planned.map { try XCTUnwrap(calendar.date(from: $0.components)) }
        XCTAssertEqual(dates.map { DayStamp(date: $0, calendar: calendar) }, [
            DayStamp(year: 2026, month: 9, day: 7), DayStamp(year: 2026, month: 9, day: 7),
            DayStamp(year: 2026, month: 9, day: 8)])
        XCTAssertEqual(planned.map { $0.components.hour }, [8, 19, 8])
        XCTAssertEqual(Set(planned.map(\.identifier)).count, 3)
        XCTAssertTrue(ReminderPlanner.scheduledRequests(for: routines, now: now,
                                                       calendar: calendar, limit: 0).isEmpty)
    }

    func testDatedHorizonIsChronologicalAcrossRoutinesAndSkipsPastSlots() throws {
        let calendar = DayStamp.utcCalendar
        let now = try XCTUnwrap(calendar.date(from: DateComponents(year: 2026, month: 9, day: 6, hour: 10)))
        let routines = [input(routineA, "Evening", [ReminderTime(hour: 19, minute: 0)]),
                        input(routineB, "Morning", [ReminderTime(hour: 8, minute: 0)])]
        let planned = ReminderPlanner.scheduledRequests(for: routines, now: now, calendar: calendar, limit: 4)
        XCTAssertEqual(planned.map(\.title), ["Evening", "Morning", "Evening", "Morning"])
        let replanned = ReminderPlanner.scheduledRequests(for: routines, now: now,
                                                         calendar: calendar, limit: 4)
        XCTAssertEqual(planned, replanned, "replanning must replace the same dated IDs")
    }

    // MARK: - Identity

    /// `ReminderTime` has no UUID: identity IS the time. That makes the notification id
    /// content-keyed, so editing 08:00 → 09:00 replaces the request rather than leaving
    /// a stale 08:00 alarm nobody can see to cancel.
    func testIdentifierIsDeterministicAndContentKeyed() {
        let eight = ReminderTime(hour: 8, minute: 0)
        let nine = ReminderTime(hour: 9, minute: 0)

        XCTAssertEqual(eight.slot, "r0480")
        XCTAssertEqual(nine.slot, "r0540")
        XCTAssertEqual(ReminderTime(hour: 21, minute: 30).slot, "r1290")
        XCTAssertEqual(ReminderPlanner.identifierPrefix, "doigt.routine.")
        XCTAssertEqual(ReminderPlanner.identifier(routine: routineA, slot: eight),
                       "doigt.routine.11111111-2222-3333-4444-555555555555.r0480")
        // Same inputs, same string, every time — that is what makes a replan idempotent.
        XCTAssertEqual(ReminderPlanner.identifier(routine: routineA, slot: eight),
                       ReminderPlanner.identifier(routine: routineA, slot: eight))

        let before = ReminderPlanner.requests(for: [input(routineA, "Daily no-hangs", [eight])])
        let after = ReminderPlanner.requests(for: [input(routineA, "Daily no-hangs", [nine])])
        XCTAssertEqual(before.map(\.identifier), ["doigt.routine.11111111-2222-3333-4444-555555555555.r0480"])
        XCTAssertEqual(after.map(\.identifier), ["doigt.routine.11111111-2222-3333-4444-555555555555.r0540"])
        XCTAssertFalse(after.map(\.identifier).contains(before[0].identifier),
                       "the edited slot must not survive alongside its replacement")
    }

    func testPlannedRequestCarriesTheRoutineNameAndAFiringTime() throws {
        let planned = ReminderPlanner.requests(
            for: [input(routineA, "Rest day", [ReminderTime(hour: 19, minute: 15)])])
        let request = try XCTUnwrap(planned.first)

        XCTAssertEqual(request.title, "Rest day")
        XCTAssertEqual(request.body, "Time for a session.")
        XCTAssertEqual(request.components.hour, 19)
        XCTAssertEqual(request.components.minute, 15)
    }

    // MARK: - What contributes nothing

    func testDisabledOrEmptyRoutinesProduceNoRequests() {
        let times = [ReminderTime(hour: 8, minute: 0), ReminderTime(hour: 19, minute: 0)]
        XCTAssertEqual(ReminderPlanner.requests(for: []), [])
        XCTAssertEqual(ReminderPlanner.requests(for: [input(routineA, "Daily no-hangs", times, enabled: false)]), [])
        XCTAssertEqual(ReminderPlanner.requests(for: [input(routineA, "Daily no-hangs", [])]), [])

        // A disabled routine must not suppress an enabled one alongside it.
        let mixed = ReminderPlanner.requests(for: [
            input(routineA, "Daily no-hangs", times, enabled: false),
            input(routineB, "Rest day", [ReminderTime(hour: 8, minute: 0)]),
        ])
        XCTAssertEqual(mixed.map(\.title), ["Rest day"])
    }

    // MARK: - Sorting and dedupe

    /// Two routines legitimately share a wall-clock time, and the identifier keeps them
    /// apart. What must NOT survive is the same slot twice within one routine — that is
    /// one alarm the user set once, however many times it got into the array.
    func testRequestsAreSortedAndDeduplicatedAcrossRoutines() {
        let planned = ReminderPlanner.requests(for: [
            input(routineA, "Daily no-hangs", [ReminderTime(hour: 19, minute: 0),
                                               ReminderTime(hour: 8, minute: 0),
                                               ReminderTime(hour: 8, minute: 0)]),
            input(routineB, "Rest day", [ReminderTime(hour: 8, minute: 0)]),
        ])

        XCTAssertEqual(planned.count, 3)
        XCTAssertEqual(Set(planned.map(\.identifier)).count, 3, "identifiers must be unique")
        XCTAssertEqual(Set(planned.map(\.identifier)), [
            "doigt.routine.11111111-2222-3333-4444-555555555555.r0480",
            "doigt.routine.11111111-2222-3333-4444-555555555555.r1140",
            "doigt.routine.66666666-7777-8888-9999-000000000000.r0480",
        ])

        // Within a routine the slots come out in clock order regardless of input order.
        let aSlots = planned.filter { $0.title == "Daily no-hangs" }.map { $0.components.hour }
        XCTAssertEqual(aSlots, [8, 19])
    }

    func testReminderTimeClampsToTheDayAndSortsByClock() {
        XCTAssertEqual(ReminderTime(minutesFromMidnight: -30).minutesFromMidnight, 0)
        XCTAssertEqual(ReminderTime(minutesFromMidnight: 5_000).minutesFromMidnight, 1439)
        XCTAssertEqual(ReminderTime(hour: 8, minute: 0).id, 480)
        XCTAssertEqual(ReminderTime(hour: 21, minute: 30).hour, 21)
        XCTAssertEqual(ReminderTime(hour: 21, minute: 30).minute, 30)
        XCTAssertLessThan(ReminderTime(hour: 8, minute: 0), ReminderTime(hour: 19, minute: 0))

        // The ladder new slots are filled from, in order.
        XCTAssertEqual(ReminderTime.defaults, [ReminderTime(hour: 8, minute: 0),
                                               ReminderTime(hour: 19, minute: 0),
                                               ReminderTime(hour: 12, minute: 30),
                                               ReminderTime(hour: 21, minute: 30)])
    }
}
