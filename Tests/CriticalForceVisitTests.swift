// SPDX-License-Identifier: MPL-2.0
// Original contributions Copyright 2026 Nuri Bruner.

import XCTest
@testable import Doigt

/// One visit, one hand after the other, driven with no screen: `CriticalForceVisit`, the
/// twin of Android's `CriticalForceTestRequest` and its `CriticalForceVisitTests`. The
/// screen's phase watch (`onChange(of: session.phase)`) is replayed after every tick.
@MainActor
final class CriticalForceVisitTests: XCTestCase {

    @MainActor
    private final class World {
        /// Wall time on the playback epoch, shared by the readings and the ticks.
        var wall: TimeInterval = 10_000
        let client = RecordingProgressorClient()
        let device: DeviceStore
        let cues = RunnerCueRecorder()
        private var micros: UInt32 = 0

        init() {
            device = DeviceStore(client: client)
            client.setState(.connected)
        }

        func visit(_ hands: CriticalForceHands) -> CriticalForceVisit {
            CriticalForceVisit(grip: GripSpec(), hands: hands, newSession: { [unowned self] in
                CriticalForceSession(cues: cues, now: { [unowned self] in wall })
            })
        }

        /// One hand's full test through the device's own callback, the screen's phase
        /// watch replayed after every tick. `upTo` stops early (seconds from the first pull).
        func pull(_ visit: CriticalForceVisit, floor: Double, upTo: Double = 238) {
            let start = wall
            var rel = 0.0
            var nextTick = 0.0
            while rel <= upTo {
                let rep = Int(rel / 10)
                let into = rel - Double(rep) * 10
                let kg = rel == 0 ? 30 : (rep < 24 && into < 7 ? floor + 20 * exp(-Double(rep) / 5) : 0)
                wall = start + rel
                device.onTracePoint?(DeviceStore.TracePoint(kg: kg, t: wall))
                if rel >= nextTick {
                    visit.session.tick()
                    visit.phaseChanged(visit.session.phase)
                    nextTick += 0.1
                }
                rel += 1.0 / 40
            }
            wall += 1
        }

        /// A live reading on the gauge, through the client, as the radio delivers it.
        func onGauge(_ kg: Double) {
            if !device.isStreaming { device.startStreaming(cause: .manualWake) }
            micros &+= 12_500
            client.emit(.sample(ForceSample(kg: kg, deviceMicros: micros, isBatchStart: true)))
        }

        func count(_ command: ProgressorCommand) -> Int { client.commands.filter { $0 == command }.count }

        func index(of command: ProgressorCommand, after: Int = -1) -> Int? {
            client.commands.indices.first { $0 > after && client.commands[$0] == command }
        }
    }

    private var worlds: [World] = []

    private func world() -> World {
        let w = World()
        worlds.append(w)
        return w
    }

    override func tearDown() async throws {
        worlds.removeAll()
        try await super.tearDown()
    }

    // MARK: - The flow

    func testOneAtATimeRunsBothHandsAndRestartsTheStreamBetween() {
        let w = world()
        let visit = w.visit(.oneAtATime(first: .right))
        visit.start(device: w.device)
        XCTAssertEqual(visit.side, .right)
        XCTAssertEqual(visit.stage, .testing)

        w.pull(visit, floor: 20)
        XCTAssertEqual(visit.handIndex, 1)
        XCTAssertEqual(visit.stage, .testing)
        XCTAssertTrue(visit.awaitingNextHand, "the second hand waits for its own Start")
        XCTAssertNil(w.device.onTracePoint, "nothing is measuring between hands")
        XCTAssertEqual(visit.side, .left)
        let firstSession = visit.session
        // Moving the gauge to the other hand loads it: that must not start the test.
        w.wall += 5
        w.device.onTracePoint?(DeviceStore.TracePoint(kg: 30, t: w.wall))
        XCTAssertTrue(visit.session === firstSession)
        XCTAssertEqual(w.count(.startWeightMeasurement), 1)

        visit.startNextHand(device: w.device)
        XCTAssertFalse(visit.awaitingNextHand)
        XCTAssertEqual(visit.session.phase, .armed, "Start arms a fresh test")
        XCTAssertFalse(visit.session === firstSession)
        XCTAssertEqual(w.count(.startWeightMeasurement), 2, "a fresh stream for the fresh hand")

        w.pull(visit, floor: 17)
        XCTAssertEqual(visit.stage, .result)
        XCTAssertEqual(visit.results.map(\.side), [.right, .left])
        XCTAssertGreaterThan(visit.results[0].result.criticalForceKg, visit.results[1].result.criticalForceKg)
        XCTAssertEqual(visit.shownSide, .right, "the result opens on the first hand")
        XCTAssertNil(w.device.onTracePoint)
        XCTAssertFalse(w.device.isStreaming)
    }

    func testFinishingWithTheFirstHandOnlyKeepsIt() {
        let w = world()
        let visit = w.visit(.oneAtATime(first: .left))
        visit.start(device: w.device)
        w.pull(visit, floor: 20)
        XCTAssertTrue(visit.awaitingNextHand)
        visit.finishEarlyBetweenHands()
        XCTAssertFalse(visit.awaitingNextHand)
        XCTAssertEqual(visit.stage, .result)
        XCTAssertEqual(visit.results.map(\.side), [.left])
    }

    func testAVoidedFirstHandEndsTheVisitWithNoResult() {
        let w = world()
        let visit = w.visit(.oneAtATime(first: .left))
        visit.start(device: w.device)
        w.pull(visit, floor: 20, upTo: 50)
        visit.session.stop()
        visit.phaseChanged(visit.session.phase)
        guard case .ended(let message) = visit.stage else { return XCTFail("\(visit.stage)") }
        XCTAssertTrue(message.hasPrefix("Stopped before pull 16"), message)
        XCTAssertTrue(visit.results.isEmpty)
    }

    /// A later hand voiding keeps the first hand's result and says why on the result screen.
    func testAVoidedSecondHandIsANoteOnTheResult() {
        let w = world()
        let visit = w.visit(.oneAtATime(first: .left))
        visit.start(device: w.device)
        w.pull(visit, floor: 20)
        visit.startNextHand(device: w.device)
        w.pull(visit, floor: 17, upTo: 40)
        visit.connectionChanged(isConnected: false)
        visit.phaseChanged(visit.session.phase)
        XCTAssertEqual(visit.stage, .result)
        XCTAssertEqual(visit.results.map(\.side), [.left])
        XCTAssertEqual(visit.notes.count, 1)
        XCTAssertTrue(visit.notes[0].hasPrefix("Right: The gauge disconnected"), visit.notes[0])
    }

    /// Between hands nothing is measuring: leaving the screen voids nothing, and the first
    /// hand's result is not turned into a note.
    func testLeavingBetweenHandsVoidsNothing() {
        let w = world()
        let visit = w.visit(.oneAtATime(first: .left))
        visit.start(device: w.device)
        w.pull(visit, floor: 20)
        XCTAssertTrue(visit.awaitingNextHand)
        visit.teardown()
        XCTAssertEqual(visit.session.phase, .finished, "the finished hand stays finished")
        XCTAssertEqual(visit.results.map(\.side), [.left])
        XCTAssertTrue(visit.notes.isEmpty)
        XCTAssertFalse(w.device.isStreaming)
    }

    /// Between hands a dropped gauge or a trip out of the app voids nothing either.
    func testADropOrLeavingTheAppBetweenHandsVoidsNothing() {
        let w = world()
        let visit = w.visit(.oneAtATime(first: .left))
        visit.start(device: w.device)
        w.pull(visit, floor: 20)
        visit.connectionChanged(isConnected: false)
        visit.leftApp()
        visit.phaseChanged(visit.session.phase)
        XCTAssertTrue(visit.awaitingNextHand)
        XCTAssertEqual(visit.stage, .testing)
        XCTAssertEqual(visit.results.map(\.side), [.left])
        XCTAssertTrue(visit.notes.isEmpty)
    }

    /// Start between hands needs a gauge; without one it does nothing.
    func testTheNextHandCannotStartWithoutTheGauge() {
        let w = world()
        let visit = w.visit(.oneAtATime(first: .left))
        visit.start(device: w.device)
        w.pull(visit, floor: 20)
        w.client.setState(.disconnected(reason: nil))
        visit.startNextHand(device: w.device)
        XCTAssertTrue(visit.awaitingNextHand)
    }

    func testBothHandsIsOneTestOnOneSide() {
        let w = world()
        let visit = w.visit(.bothHands)
        visit.start(device: w.device)
        w.pull(visit, floor: 30)
        XCTAssertEqual(visit.stage, .result)
        XCTAssertEqual(visit.results.map(\.side), [.both])
    }

    func testTheVisitSoundsLikeTheRunner() {
        let w = world()
        let visit = w.visit(.single(.right))
        visit.start(device: w.device)
        w.pull(visit, floor: 20)
        XCTAssertEqual(w.cues.played.filter { $0 == .repStarted }.count, 24)
        XCTAssertEqual(w.cues.played.last { if case .restTick = $0 { false } else { true } }, .sessionCompleted)
    }

    // MARK: - Back never discards a finished hand

    /// Armed on the SECOND hand, Back used to return to setup, and setup's Start (or
    /// Cancel) then threw the first hand's finished test away. It returns to between the
    /// hands instead, where the first hand can still be kept on its own.
    func testBackOnTheSecondHandReturnsBetweenHandsAndKeepsTheFirst() {
        let w = world()
        let visit = w.visit(.oneAtATime(first: .left))
        visit.start(device: w.device)
        w.pull(visit, floor: 20)
        visit.startNextHand(device: w.device)
        XCTAssertEqual(visit.session.phase, .armed)

        visit.back()
        XCTAssertEqual(visit.stage, .testing, "not setup")
        XCTAssertTrue(visit.awaitingNextHand, "back between the hands")
        XCTAssertEqual(visit.handIndex, 1)
        XCTAssertEqual(visit.side, .right)
        XCTAssertEqual(visit.results.map(\.side), [.left])
        XCTAssertNil(w.device.onTracePoint, "nothing is measuring between hands")

        // Setup's Start cannot restart the visit from here.
        visit.start(device: w.device)
        XCTAssertEqual(visit.results.map(\.side), [.left])
        XCTAssertTrue(visit.awaitingNextHand)

        // Both ways on from between the hands still work: the second hand again…
        visit.startNextHand(device: w.device)
        XCTAssertEqual(visit.session.phase, .armed)
        visit.back()
        // …or keeping the first hand alone.
        visit.finishEarlyBetweenHands()
        XCTAssertEqual(visit.stage, .result)
        XCTAssertEqual(visit.results.map(\.side), [.left])
        XCTAssertFalse(w.device.isStreaming)
    }

    /// On the first hand nothing has been measured, so Back is free: setup, stream off.
    func testBackOnTheFirstHandReturnsToSetup() {
        let w = world()
        let visit = w.visit(.oneAtATime(first: .left))
        visit.start(device: w.device)
        visit.back()
        XCTAssertEqual(visit.stage, .setup)
        XCTAssertFalse(w.device.isStreaming)
        XCTAssertNil(w.device.onTracePoint)
        // And the visit starts again from setup.
        visit.start(device: w.device)
        XCTAssertEqual(visit.stage, .testing)
    }

    /// Back is only for a hand that has not pulled: mid-test the hold is the only way out.
    func testBackDoesNothingOnceATestRuns() {
        let w = world()
        let visit = w.visit(.single(.left))
        visit.start(device: w.device)
        w.pull(visit, floor: 20, upTo: 20)
        visit.back()
        XCTAssertEqual(visit.stage, .testing)
        XCTAssertTrue(visit.session.phase.isRunning)
    }

    /// A gauge that drops mid-test voids it before pull 16, and the visit ends.
    func testADropMidTestEndsTheVisit() {
        let w = world()
        let visit = w.visit(.oneAtATime(first: .left))
        visit.start(device: w.device)
        w.pull(visit, floor: 20, upTo: 50)
        visit.connectionChanged(isConnected: false)
        XCTAssertEqual(visit.session.phase, .voided(.lostGauge))
        visit.phaseChanged(visit.session.phase)
        guard case .ended(let message) = visit.stage else { return XCTFail("\(visit.stage)") }
        XCTAssertTrue(message.hasPrefix("The gauge disconnected"), message)
        XCTAssertNil(w.device.onTracePoint)
    }

    // MARK: - Zeroed before each hand

    /// Every test starts on a zeroed gauge, as a routine does: tare FIRST, then the stream.
    func testStartZeroesTheGaugeBeforeTheStream() throws {
        let w = world()
        let visit = w.visit(.single(.left))
        visit.start(device: w.device)
        XCTAssertEqual(visit.stage, .testing)
        let tare = try XCTUnwrap(w.index(of: .tare))
        let start = try XCTUnwrap(w.index(of: .startWeightMeasurement))
        XCTAssertLessThan(tare, start, "tare must precede the stream: \(w.client.commands)")
        XCTAssertNil(visit.loadOnGaugeKg)
        XCTAssertEqual(visit.armings, 1)
    }

    /// A hand still on the gauge: Start zeroes nothing, arms nothing, and says why.
    func testStartIsRefusedUnderLoad() {
        let w = world()
        let visit = w.visit(.oneAtATime(first: .left))
        w.onGauge(5)
        XCTAssertTrue(w.device.isReadingLive)
        visit.start(device: w.device)
        XCTAssertEqual(visit.stage, .setup)
        XCTAssertEqual(visit.loadOnGaugeKg, 5)
        XCTAssertFalse(w.client.commands.contains(.tare), "no tare under load")
        XCTAssertNil(w.device.onTracePoint, "no test armed")
        XCTAssertEqual(visit.armings, 0, "no Start haptic for a refusal")

        // Let go, and the next Start goes through and clears the warning.
        w.onGauge(0.3)
        visit.start(device: w.device)
        XCTAssertEqual(visit.stage, .testing)
        XCTAssertNil(visit.loadOnGaugeKg)
        XCTAssertTrue(w.client.commands.contains(.tare))
    }

    /// Between hands the check runs on the live reading BEFORE the stream stops: afterwards
    /// the load is unknown, and a tare must not be guessed at.
    func testTheNextHandIsRefusedUnderLoadAndZeroedWhenLetGo() throws {
        let w = world()
        let visit = w.visit(.oneAtATime(first: .left))
        visit.start(device: w.device)
        w.pull(visit, floor: 20)
        XCTAssertTrue(visit.awaitingNextHand)
        let before = w.client.commands.count
        w.onGauge(6)
        visit.startNextHand(device: w.device)
        XCTAssertTrue(visit.awaitingNextHand, "still waiting")
        XCTAssertEqual(visit.loadOnGaugeKg, 6)
        XCTAssertNil(w.device.onTracePoint)
        XCTAssertFalse(w.client.commands.dropFirst(before).contains { $0 == .tare || $0 == .stopWeightMeasurement },
                       "nothing sent under load: \(w.client.commands.dropFirst(before))")

        w.onGauge(0.2)
        visit.startNextHand(device: w.device)
        XCTAssertFalse(visit.awaitingNextHand)
        XCTAssertNil(visit.loadOnGaugeKg)
        let stop = try XCTUnwrap(w.index(of: .stopWeightMeasurement, after: before - 1))
        let tare = try XCTUnwrap(w.index(of: .tare, after: stop))
        let start = try XCTUnwrap(w.index(of: .startWeightMeasurement, after: stop))
        XCTAssertLessThan(tare, start, "the next hand is zeroed before its stream: \(w.client.commands)")
        XCTAssertEqual(visit.session.phase, .armed)
    }
}
