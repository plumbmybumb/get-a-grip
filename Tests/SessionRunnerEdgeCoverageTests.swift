// SPDX-License-Identifier: MPL-2.0
// Original contributions Copyright 2026 Nuri Bruner.
import XCTest
@testable import Doigt

final class SessionRunnerEdgeCoverageTests: XCTestCase {
    private func plan(hold: Int = 10) -> SessionPlan {
        var p = SessionPlan()
        p.sets = [SetPlan(repsPerSide: 2)]
        p.handMode = .bothHands
        p.holdSeconds = hold
        p.restSeconds = 20
        p.leadInSeconds = 0
        return p
    }
    func testAverageWeightsElapsedTimeRatherThanSampleCountAndExcludesDips() {
        for dip in [false, true] {
            var runner = SessionRunner(plan: plan())
            _ = runner.handle(.start, at: 0)
            var micros: UInt32 = 0
            func feed(_ kg: Double, step: UInt32, count: Int) {
                for _ in 0..<count {
                    _ = runner.handle(.sample(ForceSample(kg: kg, deviceMicros: micros)), at: Double(micros) / 1e6)
                    micros += step
                }
            }
            // Establish the 100ms engage debounce before the measured intervals.
            feed(20, step: 100_000, count: 2)
            let plateauSeconds = dip ? 6 : 9
            feed(20, step: 100_000, count: plateauSeconds * 10)
            if dip { feed(0, step: 100_000, count: 40) }
            // Transition timestamps explicitly: the low plateau is sampled eight times
            // as often, so a plain sample mean cannot accidentally pass this test.
            micros -= 100_000
            micros += 12_500
            feed(5, step: 12_500, count: (10 - plateauSeconds) * 80)
            XCTAssertEqual(runner.results.first?.heldSeconds, 10)
            XCTAssertEqual(runner.results.first?.avgKg ?? -1, dip ? 14 : 18.5, accuracy: 1e-9)
        }
    }
    func testTimerHoldCannotCreditAThirtySecondMainThreadStall() {
        var runner = SessionRunner(plan: plan(hold: 60), timerOnly: true)
        _ = runner.handle(.start, at: 0)
        _ = runner.handle(.tick, at: 0.1)
        let before = runner.heldSeconds
        _ = runner.handle(.tick, at: 30.1)
        XCTAssertEqual(runner.heldSeconds - before, 1, accuracy: 1e-9)
    }
    func testDisconnectedPausedReleaseBecomesPausedRestAndKeepsTheFullRest() {
        var runner = SessionRunner(plan: plan(hold: 1))
        _ = runner.handle(.start, at: 0)
        for index in 0...12 {
            _ = runner.handle(.sample(ForceSample(kg: 10, deviceMicros: UInt32(index * 100_000))), at: Double(index) / 10)
        }
        XCTAssertEqual(runner.phase, .releasing(slot: 0))
        _ = runner.handle(.pause, at: 2)
        _ = runner.handle(.connectionLost, at: 3)
        XCTAssertEqual(runner.phase, .paused(before: .resting(slot: 0)))
        XCTAssertEqual(runner.secondsRemaining(at: 9), 20)
        _ = runner.handle(.resume, at: 10)
        XCTAssertEqual(runner.phase, .resting(slot: 0))
        XCTAssertEqual(runner.secondsRemaining(at: 10), 20)
    }
}
