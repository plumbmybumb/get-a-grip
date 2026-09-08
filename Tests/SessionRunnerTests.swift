// SPDX-License-Identifier: MPL-2.0
// Original contributions Copyright 2026 Nuri Bruner.

import XCTest
@testable import Doigt

/// The runner's correctness gate.
///
/// Unlike the Tindeq codec there is no published oracle to replay against, so this
/// matrix carries the whole weight: every timing rule that matters is asserted from a
/// synthetic force trace, with no audio, no haptics, no BLE and no waiting.
final class SessionRunnerTests: XCTestCase {

    // MARK: - Harness

    /// 80 Hz, exactly like the device.
    private static let sampleMicros: UInt32 = 12_500
    private static let sampleSeconds: TimeInterval = 0.0125

    /// Drives a force trace into the runner the way the gauge would: device timestamps
    /// advancing 12.5 ms per sample, wall clock advancing with them.
    private struct Feeder {
        var micros: UInt32 = 0
        var now: TimeInterval = 0

        /// Holds `kg` for `seconds`, returning every cue the runner emitted.
        mutating func hold(_ runner: inout SessionRunner, kg: Double,
                           seconds: Double) -> [RunnerCue] {
            var cues: [RunnerCue] = []
            let count = Int((seconds / SessionRunnerTests.sampleSeconds).rounded())
            for _ in 0..<max(0, count) {
                micros = micros &+ SessionRunnerTests.sampleMicros
                now += SessionRunnerTests.sampleSeconds
                cues += runner.handle(.sample(ForceSample(kg: kg, deviceMicros: micros)), at: now)
                cues += runner.handle(.tick, at: now)
            }
            return cues
        }

        /// Come off the edge. A tenth of a second below release is all it takes, and it
        /// is what a human does the moment a hold ends — which is exactly why
        /// `waitForReleaseBeforeRest` defaults to on, and why every test that expects a
        /// rest to be running has to do it.
        @discardableResult
        mutating func letGo(_ runner: inout SessionRunner) -> [RunnerCue] {
            hold(&runner, kg: 0.2, seconds: 0.1)
        }

        /// Wall-clock only — no samples. What a rest period actually looks like.
        mutating func wait(_ runner: inout SessionRunner, seconds: Double) -> [RunnerCue] {
            var cues: [RunnerCue] = []
            let count = Int((seconds / 0.1).rounded())
            for _ in 0..<max(0, count) {
                now += 0.1
                cues += runner.handle(.tick, at: now)
            }
            return cues
        }
    }

    private func littleEndianBytes(_ value: UInt32) -> [UInt8] {
        [UInt8(truncatingIfNeeded: value),
         UInt8(truncatingIfNeeded: value >> 8),
         UInt8(truncatingIfNeeded: value >> 16),
         UInt8(truncatingIfNeeded: value >> 24)]
    }

    private func weightPacket(_ samples: [(Float, UInt32)]) -> Data {
        let payload = samples.flatMap { sample in
            littleEndianBytes(sample.0.bitPattern) + littleEndianBytes(sample.1)
        }
        return Data([0x01, UInt8(payload.count)] + payload)
    }

    @discardableResult
    private func deliver(_ packet: Data, to runner: inout SessionRunner,
                         at t: TimeInterval) -> [RunnerCue] {
        var cues: [RunnerCue] = []
        for event in ProgressorCodec.decode(packet) {
            guard case .sample(let sample) = event else { continue }
            cues += runner.handle(.sample(sample), at: t)
        }
        return cues
    }

    private func plan(reps: Int = 1, sets: Int = 1, hold: Int = 10, rest: Int = 20,
                      setBreak: Int = 60, leadIn: Int = 0, threshold: Double = 2.0,
                      mode: HandMode = .bothHands) -> SessionPlan {
        SessionPlan(
            name: "Test",
            sets: (0..<sets).map { _ in
                SetPlan(grip: GripSpec(edgeMM: 20, fingers: .four, position: .halfCrimp),
                        repsPerSide: reps)
            },
            handMode: mode,
            holdSeconds: hold,
            restSeconds: rest,
            setBreakSeconds: setBreak,
            leadInSeconds: leadIn,
            thresholdKg: threshold
        )
    }

    /// Above engage by a comfortable margin — a real working load.
    private let pulling: Double = 20
    /// Below the release threshold (2.0 kg engage → 1.5 kg release).
    private let released: Double = 0.2

    // MARK: - A clean rep

    func testSavedShortHoldsFinishAtTheirMeasuredDuration() throws {
        for seconds in [1, 2] {
            let saved = try JSONDecoder().decode(SessionPlan.self,
                from: JSONEncoder().encode(plan(hold: seconds)))
            var runner = SessionRunner(plan: saved)
            var feeder = Feeder()
            _ = runner.handle(.start, at: 0)
            _ = feeder.hold(&runner, kg: pulling, seconds: Double(seconds) - 0.1)
            XCTAssertTrue(runner.results.isEmpty, "Must not complete before the requested duration")
            _ = feeder.hold(&runner, kg: pulling, seconds: 0.4)
            let rep = try XCTUnwrap(runner.results.first)
            XCTAssertEqual(rep.outcome, .completed)
            XCTAssertEqual(rep.heldSeconds, Double(seconds), accuracy: 0.025)
            XCTAssertEqual(runner.phase, .finished)
        }
    }

    func testACleanRepCompletesExactlyAtItsTargetAndBanksTheTime() {
        var runner = SessionRunner(plan: plan(hold: 10))
        var feeder = Feeder()

        let opening = runner.handle(.start, at: 0)
        XCTAssertEqual(opening, [.armed(.both)])
        let cues = feeder.hold(&runner, kg: pulling, seconds: 11)

        XCTAssertTrue(cues.contains(.repStarted))
        XCTAssertTrue(cues.contains(.repHalfway))
        XCTAssertTrue(cues.contains(.repEnded(completed: true)))
        XCTAssertTrue(cues.contains(.sessionCompleted))
        XCTAssertEqual(runner.phase, .finished)

        let rep = try! XCTUnwrap(runner.results.first)
        XCTAssertEqual(rep.outcome, .completed)
        // The debounce is real time the climber spent pulling but is deliberately not
        // banked, so held lands just at target rather than over it.
        XCTAssertEqual(rep.heldSeconds, 10, accuracy: 0.05)
        XCTAssertEqual(rep.peakKg, pulling, accuracy: 0.001)
        XCTAssertEqual(rep.avgKg, pulling, accuracy: 0.001)
    }

    func testTheClockDoesNotStartUntilTheDebounceIsSatisfied() {
        var runner = SessionRunner(plan: plan())
        var feeder = Feeder()
        runner.handle(.start, at: 0)

        // A single spike — bumping the edge on the way to gripping it.
        let spike = feeder.hold(&runner, kg: pulling, seconds: 0.05)
        XCTAssertFalse(spike.contains(.repStarted), "50 ms is under the 100 ms debounce")
        XCTAssertEqual(runner.phase, .armed(slot: 0))

        _ = feeder.hold(&runner, kg: released, seconds: 0.2)
        XCTAssertEqual(runner.phase, .armed(slot: 0), "letting go must re-arm, not start")
    }

    // MARK: - No gauge at all

    /// THE RULE (Nuri, 2026-08-09): with no Progressor the session still runs — count-in,
    /// hold, rest, both hands — it just measures nothing. The clock is the tick.
    func testAGaugeFreeSessionRunsOnTicksAloneAndBooksRealReps() {
        var runner = SessionRunner(plan: plan(reps: 2, hold: 5, rest: 3, leadIn: 3),
                                   timerOnly: true)
        var feeder = Feeder()

        let opening = runner.handle(.start, at: 0)
        XCTAssertEqual(runner.phase, .leadIn(slot: 0), "the count-in still happens")
        XCTAssertTrue(opening.contains(.leadInTick(secondsRemaining: 3)))

        // NOT ONE SAMPLE from here on — only wall-clock ticks.
        let cues = feeder.wait(&runner, seconds: 40)

        XCTAssertTrue(cues.contains(.repStarted), "the hold starts when the count-in ends")
        XCTAssertTrue(cues.contains(.repHalfway))
        XCTAssertTrue(cues.contains(.sessionCompleted))
        XCTAssertEqual(runner.results.count, 2)
        XCTAssertTrue(runner.results.allSatisfy { $0.outcome == .completed })
        XCTAssertEqual(runner.results[0].heldSeconds, 5, accuracy: 0.3, "a full hold, timed")
        XCTAssertEqual(runner.results[0].peakKg, 0, "and nothing measured — honestly zero")
    }

    /// There is no `armed` phase without a gauge: nothing can observe you taking the load,
    /// so waiting for it would be waiting forever.
    func testWithoutAGaugeThereIsNoArmedPhaseToWaitIn() {
        var runner = SessionRunner(plan: plan(hold: 5, leadIn: 0), timerOnly: true)
        runner.handle(.start, at: 0)
        XCTAssertEqual(runner.phase, .working(slot: 0))
    }

    /// `waitForReleaseBeforeRest` has to be skipped too — the release it waits for is a
    /// force reading, and a gauge-free session would sit on LET GO for ever.
    func testWaitingForReleaseIsSkippedWithNoGauge() {
        var p = plan(reps: 2, hold: 3, rest: 5, leadIn: 0)
        p.waitForReleaseBeforeRest = true
        var runner = SessionRunner(plan: p, timerOnly: true)
        var feeder = Feeder()
        runner.handle(.start, at: 0)

        _ = feeder.wait(&runner, seconds: 4)
        XCTAssertEqual(runner.phase, .resting(slot: 0), "straight into the rest, not releasing")
    }

    /// A pause must not bank the time it was paused for — the same contract the force
    /// path has, and the one a wall-clock accumulator is most likely to get wrong.
    func testPausingAGaugeFreeHoldBanksNoneOfThePause() {
        var runner = SessionRunner(plan: plan(hold: 10, leadIn: 0), timerOnly: true)
        var feeder = Feeder()
        runner.handle(.start, at: 0)

        _ = feeder.wait(&runner, seconds: 3)
        _ = runner.handle(.pause, at: feeder.now)
        _ = feeder.wait(&runner, seconds: 30)
        _ = runner.handle(.resume, at: feeder.now)
        _ = feeder.wait(&runner, seconds: 1)

        XCTAssertEqual(runner.heldSeconds, 4, accuracy: 0.3,
                       "three seconds plus one, and none of the thirty")
        XCTAssertEqual(runner.phase, .working(slot: 0))
    }

    /// The dial describes the phase it is in, not just the hold. Lead-in, working and
    /// rest must all begin filled; wiring it to `repProgress` would leave the first and
    /// third phases empty because no hold time has been banked there.
    /// **Every wait past a phase boundary carries a margin, and that is not sloppiness.**
    /// `Feeder.wait` accumulates `now += 0.1`, so "5 seconds" lands on 4.999999999999998
    /// and a countdown ending at exactly 5.0 has not elapsed. Waiting the nominal duration
    /// leaves the runner one tick short of the transition — which is how the first draft
    /// of this test failed while the engine was correct.
    func testPhaseRemainingFractionCoversLeadInWorkingAndResting() {
        var runner = SessionRunner(plan: plan(reps: 2, hold: 2, rest: 5, leadIn: 5), timerOnly: true)
        var feeder = Feeder()

        runner.handle(.start, at: 0)
        XCTAssertEqual(runner.phase, .leadIn(slot: 0))
        XCTAssertEqual(runner.phaseRemainingFraction(at: 0) ?? -1, 1, accuracy: 0.001,
                       "a phase begins full")

        _ = feeder.wait(&runner, seconds: 1)
        XCTAssertEqual(runner.phase, .leadIn(slot: 0), "still counting in")
        XCTAssertEqual(runner.phaseRemainingFraction(at: feeder.now) ?? -1, 0.8,
                       accuracy: 0.02, "one of five seconds gone")

        // Past the boundary, not onto it — so these land a little INTO the new phase and
        // cannot be exactly 1. What is being proven is that the ring is meaningfully
        // FILLED here at all: wired to `repProgress` both of these would read 0, which is
        // the bug this whole change exists to fix.
        _ = feeder.wait(&runner, seconds: 4.5)
        XCTAssertEqual(runner.phase, .working(slot: 0))
        XCTAssertGreaterThan(runner.phaseRemainingFraction(at: feeder.now) ?? -1, 0.6,
                             "the hold starts nearly full, not empty")

        _ = feeder.wait(&runner, seconds: 2.5)
        XCTAssertEqual(runner.phase, .resting(slot: 0))
        XCTAssertGreaterThan(runner.phaseRemainingFraction(at: feeder.now) ?? -1, 0.6,
                             "and so does the rest — the other phase repProgress leaves empty")

        _ = feeder.wait(&runner, seconds: 1)
        XCTAssertEqual(runner.phase, .resting(slot: 0))
        let resting = runner.phaseRemainingFraction(at: feeder.now) ?? -1
        XCTAssertLessThan(resting, 0.9, "the rest ring is draining")
        XCTAssertGreaterThan(resting, 0.5)
    }

    /// Pausing is a visual freeze as well as a timing freeze. Advancing the caller's clock
    /// materially after `.pause` proves the readouts use `pausedAt`, rather than merely
    /// passing because the assertion happened in the same instant as the pause.
    func testPausedWorkingFreezesNumeralAndPhaseFraction() {
        var runner = SessionRunner(plan: plan(hold: 10, leadIn: 0), timerOnly: true)
        var feeder = Feeder()
        runner.handle(.start, at: 0)
        _ = feeder.wait(&runner, seconds: 3)

        _ = runner.handle(.pause, at: feeder.now)
        // **The engine half only.** Re-deriving the numeral here would be a tautology:
        // this file cannot reach `RunnerSession.secondsShown`, so an independent
        // `10 - heldSeconds` would stay green even with the paused display branch
        // deleted. `RunnerSessionDisplayTests` covers the numeral through the real
        // property instead; keep both.
        let fractionBefore = runner.phaseRemainingFraction(at: feeder.now)
        let heldBefore = runner.heldSeconds
        _ = feeder.wait(&runner, seconds: 30)

        XCTAssertEqual(runner.heldSeconds, heldBefore, accuracy: 0.001,
                       "a paused hold banks no more time")
        XCTAssertEqual(runner.phaseRemainingFraction(at: feeder.now) ?? -1,
                       fractionBefore ?? -2, accuracy: 0.001)
    }

    func testPausedRestingFreezesNumeralAndPhaseFraction() {
        var runner = SessionRunner(plan: plan(reps: 2, hold: 1, rest: 10, leadIn: 0), timerOnly: true)
        var feeder = Feeder()
        runner.handle(.start, at: 0)
        // Margin past the one-second hold — see the note on the fraction test above.
        _ = feeder.wait(&runner, seconds: 1.5)
        XCTAssertEqual(runner.phase, .resting(slot: 0))
        _ = feeder.wait(&runner, seconds: 3)

        _ = runner.handle(.pause, at: feeder.now)
        let secondsBefore = runner.secondsRemaining(at: feeder.now)
        let fractionBefore = runner.phaseRemainingFraction(at: feeder.now)
        _ = feeder.wait(&runner, seconds: 30)

        XCTAssertEqual(runner.secondsRemaining(at: feeder.now), secondsBefore)
        XCTAssertEqual(runner.phaseRemainingFraction(at: feeder.now) ?? -1,
                       fractionBefore ?? -2, accuracy: 0.001)
    }

    // MARK: - The target band gates the clock

    /// A plan whose reps carry an absolute 20–30 kg band.
    private func bandedPlan(hold: Int = 10) -> SessionPlan {
        var p = plan(hold: hold, rest: 20)
        p.sets = p.sets.map {
            var set = $0
            set.targetLoKg = 20
            set.targetHiKg = 30
            return set
        }
        return p
    }

    /// THE RULE (Nuri, 2026-08-09): with a target range, only load INSIDE it banks time.
    /// Pulling 12 kg on a rep prescribed at 20–30 is not the rep the routine asked for,
    /// and the old engine banked it in full because 12 cleared the session threshold.
    func testUnderTheBandNeverStartsTheRep() {
        var runner = SessionRunner(plan: bandedPlan())
        var feeder = Feeder()
        runner.handle(.start, at: 0)

        let cues = feeder.hold(&runner, kg: 12, seconds: 5)

        XCTAssertFalse(cues.contains(.repStarted), "12 kg is not in a 20–30 kg rep")
        XCTAssertEqual(runner.phase, .armed(slot: 0), "still waiting to be pulled properly")
        XCTAssertEqual(runner.heldSeconds, 0)
    }

    /// The other end, and the one that did not exist before: blowing straight through the
    /// ceiling is also not the prescribed rep.
    func testOverTheBandStopsTheClockAndSaysSoTheOtherWay() {
        var runner = SessionRunner(plan: bandedPlan())
        var feeder = Feeder()
        runner.handle(.start, at: 0)

        _ = feeder.hold(&runner, kg: 25, seconds: 4)
        XCTAssertTrue(runner.heldSeconds > 3, "in range, the clock runs")

        let cues = feeder.hold(&runner, kg: 45, seconds: 5)
        XCTAssertTrue(cues.contains(.dropoutWarning), "it SAYS the clock stopped")
        XCTAssertFalse(cues.contains { if case .repEnded = $0 { true } else { false } },
                       "over the top never ends a rep either")
        XCTAssertTrue(runner.isOverTarget, "the screen is saying EASE OFF")
        XCTAssertFalse(runner.isDropped, "and it must NOT say RE-GRIP — opposite instruction")
        XCTAssertEqual(runner.heldSeconds, 3.9, accuracy: 0.2, "it banked what it had")

        // Back into range, and it carries on from where it paused.
        _ = feeder.hold(&runner, kg: 25, seconds: 6.5)
        XCTAssertEqual(runner.results.first?.outcome, .completed)
        XCTAssertEqual(runner.results.first?.heldSeconds ?? 0, 10, accuracy: 0.15)
    }

    /// The hysteresis has to work in BOTH directions, or a hold sitting on the ceiling
    /// chatters the clock on and off exactly the way one sitting on the floor used to.
    func testSmallOvershootAtTheCeilingDoesNotStallTheClock() {
        var runner = SessionRunner(plan: bandedPlan(hold: 4))
        var feeder = Feeder()
        runner.handle(.start, at: 0)

        // Enter the band properly — engaging is strictly INSIDE it, deliberately, so a
        // pull that starts over the ceiling waits rather than banking at the wrong load.
        var cues = feeder.hold(&runner, kg: 25, seconds: 1)
        XCTAssertTrue(cues.contains(.repStarted))
        // Then drift to 31: over the 30 kg ceiling, inside the release band above it.
        cues += feeder.hold(&runner, kg: 31, seconds: 3.6)
        XCTAssertTrue(cues.contains(.repEnded(completed: true)))
        XCTAssertFalse(cues.contains(.dropoutWarning), "1 kg over is not a stall")
    }

    /// No band, no ceiling: the original rule is untouched for every routine that never
    /// set a load. Pulling as hard as you like still banks the full hold.
    func testWithoutABandThereIsStillNoUpperLimit() {
        var runner = SessionRunner(plan: plan(hold: 5))
        var feeder = Feeder()
        runner.handle(.start, at: 0)

        let cues = feeder.hold(&runner, kg: 90, seconds: 5.6)
        XCTAssertTrue(cues.contains(.repEnded(completed: true)))
        XCTAssertFalse(runner.isOverTarget)
    }

    // MARK: - The band can be told to stop refereeing

    /// `pausesOutsideTargetBand == false` (Nuri, 2026-08-11): the band still exists and
    /// still draws, but it no longer stops the clock. A load far over the ceiling — which
    /// would stall a normal banded rep dead — banks the full hold.
    func testWithTheBandGateOffAnOverloadStillBanksTheRep() {
        var plan = bandedPlan(hold: 5)
        plan.pausesOutsideTargetBand = false
        var runner = SessionRunner(plan: plan)
        var feeder = Feeder()
        runner.handle(.start, at: 0)

        // 60 kg is double the 30 kg ceiling. With the gate on, this never even starts.
        let cues = feeder.hold(&runner, kg: 60, seconds: 5.6)
        XCTAssertTrue(cues.contains(.repStarted), "engaging no longer needs to be inside")
        XCTAssertTrue(cues.contains(.repEnded(completed: true)), "and it banks the hold")
        XCTAssertFalse(cues.contains(.dropoutWarning), "nothing stalled, so nothing warns")
        XCTAssertFalse(runner.isOverTarget, "and the screen must not say EASE OFF")
    }

    /// What the switch must NOT loosen. Letting go of the edge is not a question about
    /// range — it is a question about whether you are pulling at all — so the engagement
    /// threshold still stops the clock with the gate off.
    func testTheBandGateOffStillStopsWhenYouLetGo() {
        var plan = bandedPlan(hold: 10)
        plan.pausesOutsideTargetBand = false
        var runner = SessionRunner(plan: plan)
        var feeder = Feeder()
        runner.handle(.start, at: 0)

        _ = feeder.hold(&runner, kg: 25, seconds: 4)
        let cues = feeder.hold(&runner, kg: 0, seconds: 3)
        XCTAssertTrue(cues.contains(.dropoutWarning), "coming off the edge still stalls")
        XCTAssertTrue(runner.isDropped, "and the screen says RE-GRIP")
        XCTAssertFalse(cues.contains { if case .repEnded = $0 { true } else { false } },
                       "a drop never ends the rep, gate or no gate")
        XCTAssertEqual(runner.heldSeconds, 4, accuracy: 0.2, "it banked only what it held")
    }

    /// The default is unchanged, so every routine authored before the switch existed
    /// still gets the rule the band was introduced to enforce.
    func testTheBandGateDefaultsToOn() {
        XCTAssertTrue(SessionPlan().pausesOutsideTargetBand)
        XCTAssertTrue(RoutineDraft.starter.plan.pausesOutsideTargetBand)
    }

    // MARK: - Dips and hysteresis

    /// THE RULE: coming off the edge never ends a rep, however long you are off it.
    /// Re-gripping honestly takes more than three seconds, so any grace long enough to
    /// be fair is long enough to be pointless. Skip is the only way out.
    func testALongDropNeverEndsTheRepItJustStopsTheClock() {
        var runner = SessionRunner(plan: plan(hold: 10))
        var feeder = Feeder()
        runner.handle(.start, at: 0)

        _ = feeder.hold(&runner, kg: pulling, seconds: 4)
        let cues = feeder.hold(&runner, kg: released, seconds: 30)

        XCTAssertTrue(cues.contains(.dropoutWarning), "it still SAYS the clock stopped")
        XCTAssertFalse(cues.contains { if case .repEnded = $0 { true } else { false } },
                       "half a minute off the edge is still not a finished rep")
        XCTAssertEqual(runner.phase, .working(slot: 0))
        XCTAssertEqual(runner.heldSeconds, 3.9, accuracy: 0.15, "and it banked what it had")
        XCTAssertTrue(runner.isDropped, "the screen is saying RE-GRIP")

        // Back on, and it carries on from where it paused rather than restarting.
        _ = feeder.hold(&runner, kg: pulling, seconds: 6.5)
        XCTAssertEqual(runner.results.first?.outcome, .completed)
        XCTAssertEqual(runner.results.first?.heldSeconds ?? 0, 10, accuracy: 0.1)
    }

    /// A shaky hold dips below the line ten times and still finishes — it just takes
    /// longer in wall-clock terms, because only time ON the edge is banked.
    func testAShakyHoldCompletesAndOnlyLosesTheSecondsItActuallyLost() {
        var runner = SessionRunner(plan: plan(hold: 5))
        var feeder = Feeder()
        runner.handle(.start, at: 0)

        var cues: [RunnerCue] = []
        for _ in 0..<10 {
            cues += feeder.hold(&runner, kg: pulling, seconds: 0.6)
            cues += feeder.hold(&runner, kg: released, seconds: 0.9)   // a real fumble
        }

        XCTAssertTrue(cues.contains(.repEnded(completed: true)), "dips never end a rep")
        let rep = try! XCTUnwrap(runner.results.first)
        XCTAssertEqual(rep.outcome, .completed)
        // Accrual PAUSES during a dip rather than resetting, so the rep still banks its
        // full target — it just takes longer in wall-clock terms to get there.
        XCTAssertEqual(rep.heldSeconds, 5, accuracy: 0.1)
    }

    /// Force sitting exactly on the threshold must not chatter the rep on and off.
    func testForceHoveringAtTheThresholdEngagesExactlyOnce() {
        // A long hold so the rep cannot simply finish during the 8 s of wobbling —
        // this test is about how many times it STARTS, and a completed rep would mask
        // a second engagement behind a legitimate ending.
        var runner = SessionRunner(plan: plan(hold: 60, threshold: 2.0))
        var feeder = Feeder()
        runner.handle(.start, at: 0)

        var starts = 0
        for _ in 0..<20 {
            // 2.1 and 1.9 straddle ENGAGE (2.0) but both sit above RELEASE (1.5),
            // which is the entire point of the hysteresis band.
            for cue in feeder.hold(&runner, kg: 2.1, seconds: 0.2) where cue == .repStarted { starts += 1 }
            for cue in feeder.hold(&runner, kg: 1.9, seconds: 0.2) where cue == .repStarted { starts += 1 }
        }
        XCTAssertEqual(starts, 1, "one engagement, no flicker")
        XCTAssertEqual(runner.results.count, 0, "and it is still running")
    }

    func testAfterDroppingBelowTheFloorTheDeadBandCannotRelatchTheClock() {
        var runner = SessionRunner(plan: plan(hold: 10, threshold: 2.0))
        var feeder = Feeder()
        runner.handle(.start, at: 0)
        _ = feeder.hold(&runner, kg: pulling, seconds: 1)

        let firstDip = feeder.hold(&runner, kg: 1.4, seconds: 0.1)
        let heldAtExit = runner.heldSeconds
        XCTAssertEqual(firstDip.filter { $0 == .dropoutWarning }.count, 1)

        let deadBand = feeder.hold(&runner, kg: 1.6, seconds: 1)
        XCTAssertEqual(runner.heldSeconds, heldAtExit, accuracy: 0.001,
                       "above release but below engage remains latched")
        XCTAssertFalse(deadBand.contains(.dropoutWarning),
                       "dead-band samples do not re-arm the one-shot warning")

        let sameDip = feeder.hold(&runner, kg: 1.4, seconds: 0.1)
        XCTAssertFalse(sameDip.contains(.dropoutWarning), "this is still the same dip")
        _ = feeder.hold(&runner, kg: 2.0, seconds: 0.1)
        XCTAssertGreaterThan(runner.heldSeconds, heldAtExit, "engage clears the latch")
        let secondDip = feeder.hold(&runner, kg: 1.4, seconds: 0.1)
        XCTAssertTrue(secondDip.contains(.dropoutWarning), "engage also re-arms the cue")
    }

    func testAfterExceedingTheCeilingTheUpperDeadBandCannotRelatchTheClock() {
        var runner = SessionRunner(plan: bandedPlan(hold: 10))
        var feeder = Feeder()
        runner.handle(.start, at: 0)
        _ = feeder.hold(&runner, kg: 25, seconds: 1)

        _ = feeder.hold(&runner, kg: 32, seconds: 0.1) // above releaseHi (31.5)
        let heldAtExit = runner.heldSeconds
        _ = feeder.hold(&runner, kg: 31, seconds: 1)   // dead band above engageHi (30)
        XCTAssertEqual(runner.heldSeconds, heldAtExit, accuracy: 0.001)
        XCTAssertTrue(runner.isOverTarget, "dead-band samples keep the original stall visible")

        _ = feeder.hold(&runner, kg: 30, seconds: 0.1)
        XCTAssertGreaterThan(runner.heldSeconds, heldAtExit,
                             "only an in-band sample clears the upper latch")
    }

    func testALateStartIsFineBecauseArmedNeverTimesOut() {
        var runner = SessionRunner(plan: plan(hold: 3))
        var feeder = Feeder()
        runner.handle(.start, at: 0)

        _ = feeder.wait(&runner, seconds: 120)
        XCTAssertEqual(runner.phase, .armed(slot: 0), "chalking up is not a failure")

        _ = feeder.hold(&runner, kg: pulling, seconds: 4)
        XCTAssertEqual(runner.results.first?.outcome, .completed)
    }

    // MARK: - The link

    func testADisconnectFreezesAccrualAndReconnectingDoesNotCreditTheOutage() {
        var runner = SessionRunner(plan: plan(hold: 10))
        var feeder = Feeder()
        runner.handle(.start, at: 0)
        _ = feeder.hold(&runner, kg: pulling, seconds: 3)

        let lost = runner.handle(.connectionLost, at: feeder.now)
        XCTAssertEqual(lost, [.connectionLost])
        _ = feeder.wait(&runner, seconds: 5)
        XCTAssertEqual(runner.phase, .working(slot: 0), "still inside the grace window")

        // Device timestamps jump by the whole outage; the runner must not bank it.
        feeder.micros = feeder.micros &+ 5_000_000
        runner.handle(.connectionRestored, at: feeder.now)
        _ = feeder.hold(&runner, kg: pulling, seconds: 1)

        XCTAssertEqual(runner.heldSeconds, 4, accuracy: 0.2,
                       "3 s before + 1 s after, never the 5 s nobody was measuring")
    }

    /// Same rule for a dropped link: the rep waits for the gauge rather than abandoning
    /// work somebody actually did.
    func testAnUnrecoveredDisconnectWaitsRatherThanAbandoningTheRep() {
        var runner = SessionRunner(plan: plan(sets: 1, hold: 10))
        var feeder = Feeder()
        runner.handle(.start, at: 0)
        _ = feeder.hold(&runner, kg: pulling, seconds: 3)
        runner.handle(.connectionLost, at: feeder.now)

        _ = feeder.wait(&runner, seconds: 60)
        XCTAssertEqual(runner.phase, .working(slot: 0), "still the same rep, a minute later")
        XCTAssertTrue(runner.results.isEmpty)

        // Reconnect and finish it. The outage is not credited as hang time.
        feeder.micros = feeder.micros &+ 60_000_000
        runner.handle(.connectionRestored, at: feeder.now)
        _ = feeder.hold(&runner, kg: pulling, seconds: 7.5)
        XCTAssertEqual(runner.results.first?.outcome, .completed)
        XCTAssertEqual(runner.results.first?.heldSeconds ?? 0, 10, accuracy: 0.15)
    }

    /// Taring re-zeroes the gauge, so the sample right after it can read near zero
    /// through no fault of the climber.
    func testTaringMidRepDoesNotSpuriouslyEndIt() {
        var runner = SessionRunner(plan: plan(hold: 10))
        var feeder = Feeder()
        runner.handle(.start, at: 0)
        _ = feeder.hold(&runner, kg: pulling, seconds: 2)

        runner.handle(.tareCommitted, at: feeder.now)
        _ = feeder.hold(&runner, kg: released, seconds: 0.1)   // the post-tare blip
        _ = feeder.hold(&runner, kg: pulling, seconds: 2)

        XCTAssertEqual(runner.phase, .working(slot: 0))
        XCTAssertTrue(runner.results.isEmpty)
    }

    /// The device's µs clock is a UInt32 and rolls over every ~71.6 minutes. A session
    /// that straddles the wrap must not lose or invent a rep.
    func testARepSpanningTheTimestampWrapKeepsCountingCorrectly() {
        var runner = SessionRunner(plan: plan(hold: 4))
        var feeder = Feeder()
        feeder.micros = UInt32.max - 25_000       // two samples from the wrap
        runner.handle(.start, at: 0)

        _ = feeder.hold(&runner, kg: pulling, seconds: 5)
        let rep = try! XCTUnwrap(runner.results.first)
        XCTAssertEqual(rep.outcome, .completed)
        XCTAssertEqual(rep.heldSeconds, 4, accuracy: 0.05)
    }

    // MARK: - Notification integrity

    func testFiniteFiveThousandKgSpikeNeverReachesTheRepPeak() {
        var runner = SessionRunner(plan: plan(hold: 10))
        var feeder = Feeder()
        runner.handle(.start, at: 0)
        _ = feeder.hold(&runner, kg: pulling, seconds: 1)

        let start = feeder.micros
        let packet = weightPacket([
            (20, start &+ 12_500),
            (5_000, start &+ 25_000),
            (20, start &+ 37_500),
        ])
        deliver(packet, to: &runner, at: feeder.now)
        runner.handle(.abort, at: feeder.now)

        XCTAssertEqual(runner.results.first?.peakKg ?? 0, 20, accuracy: 0.001)
    }

    func testSeventeenTwoHundredMillisecondSamplesCannotFinishAnAccruedRep() {
        var runner = SessionRunner(plan: plan(hold: 3))
        var feeder = Feeder()
        runner.handle(.start, at: 0)
        _ = feeder.hold(&runner, kg: pulling, seconds: 2.7)
        let heldBefore = runner.heldSeconds
        XCTAssertEqual(heldBefore, 2.6, accuracy: 0.03)

        let packet = weightPacket((1...17).map {
            (Float(20), feeder.micros &+ UInt32($0) * 200_000)
        })
        let cues = deliver(packet, to: &runner, at: feeder.now)

        XCTAssertEqual(runner.heldSeconds, heldBefore, accuracy: 0.000_001)
        XCTAssertEqual(runner.phase, .working(slot: 0))
        XCTAssertFalse(cues.contains(.repEnded(completed: true)))
    }

    func testFiveOneSecondTimestampSamplesCannotCreateAPhantomRep() {
        var runner = SessionRunner(plan: plan(hold: 3))
        var feeder = Feeder()
        runner.handle(.start, at: 0)
        _ = feeder.hold(&runner, kg: pulling, seconds: 2.7)
        let heldBefore = runner.heldSeconds

        let packet = weightPacket((1...5).map {
            (Float(20), feeder.micros &+ UInt32($0) * 1_000_000)
        })
        let cues = deliver(packet, to: &runner, at: feeder.now)

        XCTAssertEqual(runner.heldSeconds, heldBefore, accuracy: 0.000_001)
        XCTAssertEqual(runner.phase, .working(slot: 0))
        XCTAssertFalse(cues.contains(.repEnded(completed: true)))
    }

    func testSameCorruptNotificationCannotEngageAnArmedRep() {
        var runner = SessionRunner(plan: plan(hold: 3))
        runner.handle(.start, at: 0)
        let packet = weightPacket((1...17).map {
            (Float(20), UInt32($0) * 200_000)
        })

        let cues = deliver(packet, to: &runner, at: 0)
        XCTAssertEqual(runner.phase, .armed(slot: 0))
        XCTAssertFalse(cues.contains(.repStarted))
    }

    func testReplayingAWholeNotificationCreditsZeroAdditionalTime() {
        var runner = SessionRunner(plan: plan(hold: 10))
        var feeder = Feeder()
        runner.handle(.start, at: 0)
        _ = feeder.hold(&runner, kg: pulling, seconds: 1)

        var samples: [(Float, UInt32)] = [(5_000, feeder.micros &+ Self.sampleMicros)]
        samples += (1...8).map {
            (Float(20), feeder.micros &+ UInt32($0 + 1) * Self.sampleMicros)
        }
        let packet = weightPacket(samples)
        let emitted = ProgressorCodec.decode(packet)
        guard case .sample(let first)? = emitted.first else {
            return XCTFail("the valid samples must survive the rejected leading spike")
        }
        XCTAssertTrue(first.isBatchStart, "the first emitted sample still marks the batch")
        deliver(packet, to: &runner, at: feeder.now)
        let heldAfterFirstDelivery = runner.heldSeconds
        deliver(packet, to: &runner, at: feeder.now)

        XCTAssertEqual(runner.heldSeconds, heldAfterFirstDelivery, accuracy: 0.000_001)
    }

    func testStaleEpochFailsClosedUntilAnExplicitStreamRestart() {
        var runner = SessionRunner(plan: plan(hold: 10))
        var feeder = Feeder()
        runner.handle(.start, at: 0)
        _ = feeder.hold(&runner, kg: pulling, seconds: 1)
        let heldBefore = runner.heldSeconds

        let staleOne = weightPacket([(20, 1_000), (20, 13_500), (20, 26_000)])
        let staleTwo = weightPacket([(20, 50_000), (20, 62_500), (20, 75_000)])
        deliver(staleOne, to: &runner, at: feeder.now)
        deliver(staleTwo, to: &runner, at: feeder.now)
        XCTAssertEqual(runner.heldSeconds, heldBefore, accuracy: 0.000_001,
                       "consecutive stale batches can never authorize their own epoch")
        XCTAssertTrue(runner.isRejectingStaleBatches)

        runner.handle(.streamRestarted, at: feeder.now)
        deliver(staleOne, to: &runner, at: feeder.now)
        XCTAssertEqual(runner.heldSeconds, heldBefore + 0.025, accuracy: 0.000_001,
                       "the explicit break accepts and re-anchors the fresh epoch")
        XCTAssertFalse(runner.isRejectingStaleBatches)
    }

    func testArmedHealRecoversWhenQueuedOldEpochDataWinsTheForegroundRace() {
        var runner = SessionRunner(plan: plan(hold: 10))
        var feeder = Feeder()
        runner.handle(.start, at: 0)
        _ = feeder.hold(&runner, kg: pulling, seconds: 1)

        // Foreground re-kick: the engine breaks for a possible fresh epoch, but queued
        // pre-background samples from epoch A arrive first and legitimately re-anchor it.
        runner.handle(.streamRestarted, at: feeder.now)
        let queuedEpochA = weightPacket([
            (20, feeder.micros &+ 12_500),
            (20, feeder.micros &+ 25_000),
            (20, feeder.micros &+ 37_500),
        ])
        deliver(queuedEpochA, to: &runner, at: feeder.now)
        let heldAfterQueuedBurst = runner.heldSeconds
        XCTAssertGreaterThan(heldAfterQueuedBurst, 0)

        // The restarted live stream is epoch B near zero. Without a second authorized
        // break it remains fail-closed behind epoch A's high-water mark.
        let liveEpochB = weightPacket([(20, 1_000), (20, 13_500), (20, 26_000)])
        deliver(liveEpochB, to: &runner, at: feeder.now)
        XCTAssertEqual(runner.heldSeconds, heldAfterQueuedBurst, accuracy: 0.000_001)
        XCTAssertTrue(runner.isRejectingStaleBatches)

        let decision = StaleBatchHealer.decision(
            armed: true, armAge: 1,
            consecutiveRejectingChecks: 2,
            timeSinceLastHeal: .infinity)
        XCTAssertEqual(decision, .fire)
        if decision == .fire {
            runner.handle(.streamRestarted, at: feeder.now)
        }
        deliver(liveEpochB, to: &runner, at: feeder.now)

        XCTAssertGreaterThan(runner.heldSeconds, heldAfterQueuedBurst,
                             "the armed healing break re-anchors on the live epoch")
        XCTAssertFalse(runner.isRejectingStaleBatches)
    }

    func testStreamRestartMidHoldPreservesAccrualAndOnlyResetsTheAnchor() {
        var runner = SessionRunner(plan: plan(hold: 10))
        var feeder = Feeder()
        runner.handle(.start, at: 0)
        _ = feeder.hold(&runner, kg: pulling, seconds: 1)
        let heldBeforeRestart = runner.heldSeconds

        runner.handle(.streamRestarted, at: feeder.now)
        XCTAssertEqual(runner.heldSeconds, heldBeforeRestart, accuracy: 0.000_001)

        runner.handle(.sample(ForceSample(kg: pulling, deviceMicros: 1_000)), at: feeder.now)
        XCTAssertEqual(runner.heldSeconds, heldBeforeRestart, accuracy: 0.000_001,
                       "the first fresh-epoch sample establishes the anchor")
        runner.handle(.sample(ForceSample(kg: pulling, deviceMicros: 13_500)), at: feeder.now)
        XCTAssertEqual(runner.heldSeconds, heldBeforeRestart + Self.sampleSeconds,
                       accuracy: 0.000_001)
    }

    func testRejectingFlagClearsOnTheFirstAcceptedBatch() {
        var runner = SessionRunner(plan: plan(hold: 10))
        var feeder = Feeder()
        runner.handle(.start, at: 0)
        _ = feeder.hold(&runner, kg: pulling, seconds: 1)

        deliver(weightPacket([(20, 1_000), (20, 13_500)]),
                to: &runner, at: feeder.now)
        XCTAssertTrue(runner.isRejectingStaleBatches)

        let accepted = weightPacket([(20, feeder.micros &+ 12_500)])
        deliver(accepted, to: &runner, at: feeder.now)
        XCTAssertFalse(runner.isRejectingStaleBatches)
    }

    func testStaleBatchHealerFiresExactlyOncePerArm() {
        XCTAssertEqual(StaleBatchHealer.decision(
            armed: true, armAge: 0.5,
            consecutiveRejectingChecks: 1,
            timeSinceLastHeal: .infinity), .hold)

        let first = StaleBatchHealer.decision(
            armed: true, armAge: 1,
            consecutiveRejectingChecks: 2,
            timeSinceLastHeal: .infinity)
        XCTAssertEqual(first, .fire)

        // RunnerSession consumes the arm before emitting the event. The same ongoing
        // rejection therefore cannot authorize a second break.
        let afterConsumption = StaleBatchHealer.decision(
            armed: false, armAge: 1.5,
            consecutiveRejectingChecks: 20,
            timeSinceLastHeal: 0.5)
        XCTAssertEqual(afterConsumption, .hold)
    }

    func testStaleBatchHealerNeverFiresWithoutARecentRekick() {
        XCTAssertEqual(StaleBatchHealer.decision(
            armed: false, armAge: 100,
            consecutiveRejectingChecks: 1_000,
            timeSinceLastHeal: 100), .hold)
    }

    func testStaleBatchHealerArmExpires() {
        XCTAssertEqual(StaleBatchHealer.decision(
            armed: true, armAge: StaleBatchHealer.armLifetime,
            consecutiveRejectingChecks: 2,
            timeSinceLastHeal: .infinity), .expire)
    }

    func testStaleBatchHealerHonorsTheTwoSecondRateLimit() {
        XCTAssertEqual(StaleBatchHealer.decision(
            armed: true, armAge: 1,
            consecutiveRejectingChecks: 2,
            timeSinceLastHeal: 1.99), .hold)
        XCTAssertEqual(StaleBatchHealer.decision(
            armed: true, armAge: 1,
            consecutiveRejectingChecks: 2,
            timeSinceLastHeal: 2), .fire)
    }

    func testAForwardGapOverTwoHundredMillisecondsLosesOnlyThatDelta() {
        var runner = SessionRunner(plan: plan(hold: 10))
        var feeder = Feeder()
        runner.handle(.start, at: 0)
        _ = feeder.hold(&runner, kg: pulling, seconds: 1)
        let heldBefore = runner.heldSeconds

        let afterGap = feeder.micros &+ 200_001
        runner.handle(.sample(ForceSample(kg: pulling, deviceMicros: afterGap)), at: feeder.now)
        XCTAssertEqual(runner.heldSeconds, heldBefore, accuracy: 0.000_001)

        runner.handle(.sample(ForceSample(kg: pulling,
                                         deviceMicros: afterGap &+ Self.sampleMicros)),
                      at: feeder.now)
        XCTAssertEqual(runner.heldSeconds, heldBefore + Self.sampleSeconds,
                       accuracy: 0.000_001)
    }

    // MARK: - Human overrides

    func testSkipRepRecordsItAndMovesOn() {
        var runner = SessionRunner(plan: plan(reps: 2, hold: 10, rest: 5, mode: .bothHands))
        runner.handle(.start, at: 0)

        let cues: [RunnerCue] = runner.handle(.skipRep, at: 1)
        XCTAssertTrue(cues.contains(.repEnded(completed: false)))
        XCTAssertEqual(runner.results.map(\.outcome), [.skipped])
        XCTAssertEqual(runner.phase, .resting(slot: 0), "a skip still takes its rest")
    }

    func testSkipSetRecordsEveryRemainingRepInThatSetAndJumpsToTheNext() {
        var runner = SessionRunner(plan: plan(reps: 3, sets: 2, hold: 10, mode: .bothHands))
        runner.handle(.start, at: 0)

        let cues: [RunnerCue] = runner.handle(.skipSet, at: 1)
        XCTAssertEqual(runner.results.count, 3, "the whole set is accounted for")
        XCTAssertTrue(runner.results.allSatisfy { $0.outcome == .skipped })
        XCTAssertTrue(cues.contains(.setCompleted(setIndex: 0)))
        XCTAssertEqual(runner.currentSlot?.setIndex, 1)
    }

    /// Found by driving a real session: skipping from the REST screen re-recorded the
    /// rep that had just finished, and a six-set routine reported "37 of 36 pulls".
    /// A summary can never exceed the plan it came from.
    func testSkippingWhileRestingNeverRecordsMoreRepsThanThePlanHas() {
        let p = plan(reps: 2, sets: 3, hold: 1, rest: 5, setBreak: 5, mode: .bothHands)
        var runner = SessionRunner(plan: p)
        var feeder = Feeder()
        runner.handle(.start, at: 0)

        // Finish rep 1 honestly, then skip every remaining set from the rest screen —
        // exactly what a tap on "Skip set" during a break does.
        _ = feeder.hold(&runner, kg: pulling, seconds: 1.3)
        feeder.letGo(&runner)
        XCTAssertEqual(runner.phase, .resting(slot: 0))
        for _ in 0..<6 where !runner.isFinished {
            runner.handle(.skipSet, at: feeder.now)
            _ = feeder.wait(&runner, seconds: 5.2)
        }

        XCTAssertTrue(runner.isFinished)
        XCTAssertEqual(runner.results.count, runner.plannedRepCount, "6 planned, 6 recorded")
        XCTAssertLessThanOrEqual(runner.completedRepCount, runner.plannedRepCount)
        // And the honest rep keeps its outcome rather than being overwritten by a skip.
        XCTAssertEqual(runner.results.first?.outcome, .completed)
    }

    func testSkippingAPullWhileRestingSkipsTheNextOneNotTheFinishedOne() {
        var runner = SessionRunner(plan: plan(reps: 3, hold: 1, rest: 5, mode: .bothHands))
        var feeder = Feeder()
        runner.handle(.start, at: 0)
        _ = feeder.hold(&runner, kg: pulling, seconds: 1.3)
        feeder.letGo(&runner)
        XCTAssertEqual(runner.phase, .resting(slot: 0))

        runner.handle(.skipRep, at: feeder.now)
        XCTAssertEqual(runner.results.map(\.outcome), [.completed, .skipped],
                       "the finished rep is untouched; the upcoming one is skipped")
    }

    func testAbortEndsTheSessionAndRecordsTheRepInFlight() {
        var runner = SessionRunner(plan: plan(reps: 4, hold: 10, mode: .bothHands))
        var feeder = Feeder()
        runner.handle(.start, at: 0)
        _ = feeder.hold(&runner, kg: pulling, seconds: 3)

        let aborted = runner.handle(.abort, at: feeder.now)
        XCTAssertEqual(aborted, [.sessionCompleted])
        XCTAssertEqual(runner.phase, .finished)
        XCTAssertEqual(runner.results.map(\.outcome), [.aborted])
        XCTAssertEqual(runner.results.first?.heldSeconds ?? 0, 2.9, accuracy: 0.15)
    }

    // MARK: - Pause

    func testPausingMidRepBanksNothingAndResumingContinuesTheSameRep() {
        var runner = SessionRunner(plan: plan(hold: 10))
        var feeder = Feeder()
        runner.handle(.start, at: 0)
        _ = feeder.hold(&runner, kg: pulling, seconds: 3)

        runner.handle(.pause, at: feeder.now)
        XCTAssertEqual(runner.phase, .paused(before: .working(slot: 0)))
        _ = feeder.wait(&runner, seconds: 30)

        feeder.micros = feeder.micros &+ 30_000_000
        runner.handle(.resume, at: feeder.now)
        _ = feeder.hold(&runner, kg: pulling, seconds: 2)

        XCTAssertEqual(runner.phase, .working(slot: 0))
        XCTAssertEqual(runner.heldSeconds, 5, accuracy: 0.2, "the pause is not hang time")
    }

    func testPausingARestStealsNoRestAndGivesNoneBack() {
        var runner = SessionRunner(plan: plan(reps: 2, hold: 1, rest: 10, mode: .bothHands))
        var feeder = Feeder()
        runner.handle(.start, at: 0)
        _ = feeder.hold(&runner, kg: pulling, seconds: 1.3)
        feeder.letGo(&runner)
        XCTAssertEqual(runner.phase, .resting(slot: 0))

        _ = feeder.wait(&runner, seconds: 4)
        runner.handle(.pause, at: feeder.now)
        _ = feeder.wait(&runner, seconds: 60)
        runner.handle(.resume, at: feeder.now)

        XCTAssertEqual(runner.phase, .resting(slot: 0), "still ~6 s of rest owed")
        _ = feeder.wait(&runner, seconds: 5)
        XCTAssertEqual(runner.phase, .resting(slot: 0))
        _ = feeder.wait(&runner, seconds: 2)
        XCTAssertEqual(runner.phase, .armed(slot: 1))
    }

    /// REGRESSION (audit rank 1, verified): `abort(at:)` used to gate the in-flight rep's
    /// booking on `!phase.isPaused` — a clause that existed solely to DROP it, since
    /// `pending(in:)` already resolves through `.paused`. Pause mid-hold, then hold to
    /// end (answer the door, catch your breath) — the rep in flight, with its accrued
    /// hang time, must land in `results` exactly as it would unpaused, or a session
    /// whose only work happened before that pause writes no `WorkoutLog` at all
    /// (`SessionSummaryView` gates saving on `didAnyWork`).
    func testAbortWhilePausedMidHoldStillRecordsTheRepAndItsAccruedTime() {
        var runner = SessionRunner(plan: plan(hold: 10))
        var feeder = Feeder()
        runner.handle(.start, at: 0)
        _ = feeder.hold(&runner, kg: pulling, seconds: 3)

        runner.handle(.pause, at: feeder.now)
        XCTAssertEqual(runner.phase, .paused(before: .working(slot: 0)))

        let aborted = runner.handle(.abort, at: feeder.now)
        XCTAssertEqual(aborted, [.sessionCompleted])
        XCTAssertEqual(runner.phase, .finished)
        XCTAssertEqual(runner.results.map(\.outcome), [.aborted],
                       "the in-flight rep must land in results, not vanish silently")
        XCTAssertEqual(runner.results.first?.heldSeconds ?? 0, 2.9, accuracy: 0.15,
                       "with the hang time it had already accrued before the pause")
        XCTAssertTrue(runner.didAnyWork,
                      "so the session summary has something worth saving")
    }

    func testPauseAndResumeAreNoOpsWhereTheyHaveNoMeaning() {
        var runner = SessionRunner(plan: plan())
        let paused = runner.handle(.pause, at: 0)
        XCTAssertEqual(paused, [], "nothing to pause before start")
        XCTAssertEqual(runner.phase, .idle)
        let resumed = runner.handle(.resume, at: 0)
        XCTAssertEqual(resumed, [])
        XCTAssertEqual(runner.phase, .idle)
    }

    // MARK: - The rest waits for you to let go

    /// THE RULE (Nuri, 2026-08-04): the hold ends on time, the REST does not start until
    /// your hand is off the edge. Standing down off a 20 mm edge takes two or three
    /// seconds, and charging them to the rest means a 20 s rest was never 20 s.
    func testTheRestDoesNotStartUntilForceComesOffTheEdge() {
        var runner = SessionRunner(plan: plan(reps: 2, hold: 1, rest: 10, mode: .bothHands))
        var feeder = Feeder()
        runner.handle(.start, at: 0)

        // The hold completes at 1 s; keep pulling for five more.
        let cues = feeder.hold(&runner, kg: pulling, seconds: 6)

        XCTAssertEqual(runner.phase, .releasing(slot: 0), "still on the edge")
        XCTAssertTrue(cues.contains(.repEnded(completed: true)), "the REP is done and recorded")
        XCTAssertEqual(runner.results.count, 1)
        XCTAssertEqual(runner.results.first?.heldSeconds ?? 0, 1, accuracy: 0.05,
                       "and the extra five seconds are not hang time")
        XCTAssertFalse(cues.contains { if case .restTick = $0 { true } else { false } },
                       "no rest has begun, so nothing may count down")
        XCTAssertNil(runner.secondsRemaining(at: feeder.now), "there is no clock to read")

        // Let go, and only now does the rest start — its full length, from here.
        feeder.letGo(&runner)
        XCTAssertEqual(runner.phase, .resting(slot: 0))
        _ = feeder.wait(&runner, seconds: 9)
        XCTAssertEqual(runner.phase, .resting(slot: 0), "~1 s still owed")
        _ = feeder.wait(&runner, seconds: 1.5)
        XCTAssertEqual(runner.phase, .armed(slot: 1), "a full 10 s AFTER letting go")
    }

    /// Off, the old behaviour is still available and still honest — a fixed cadence you
    /// pace yourself to.
    func testWithTheFlagOffTheRestStartsWhileYouAreStillGripping() {
        var p = plan(reps: 2, hold: 1, rest: 10, mode: .bothHands)
        p.waitForReleaseBeforeRest = false
        var runner = SessionRunner(plan: p)
        var feeder = Feeder()
        runner.handle(.start, at: 0)

        _ = feeder.hold(&runner, kg: pulling, seconds: 1.3)
        XCTAssertEqual(runner.phase, .resting(slot: 0), "no release needed")
    }

    /// Waiting for a release nobody can observe would strand the session on a phase with
    /// no clock and no way out but Skip. A rep waits for the gauge to come back; a rest
    /// does not.
    func testLosingTheLinkWhileWaitingForAReleaseStartsTheRestAnyway() {
        var runner = SessionRunner(plan: plan(reps: 2, hold: 1, rest: 10, mode: .bothHands))
        var feeder = Feeder()
        runner.handle(.start, at: 0)
        _ = feeder.hold(&runner, kg: pulling, seconds: 1.3)
        XCTAssertEqual(runner.phase, .releasing(slot: 0))

        let cues = runner.handle(.connectionLost, at: feeder.now)
        XCTAssertTrue(cues.contains(.connectionLost))
        XCTAssertEqual(runner.phase, .resting(slot: 0), "the rest starts rather than stalling")
        XCTAssertNotNil(runner.secondsRemaining(at: feeder.now))
    }

    /// The LAST pull of a session owes no rest, so there is nothing to wait for — and a
    /// session that sat on LET GO forever, after the work was finished, would be the
    /// worst possible place to strand someone. Waiting is gated on a rest existing.
    func testTheFinalPullNeverWaitsForAReleaseAndTheSessionEnds() {
        var runner = SessionRunner(plan: plan(reps: 2, hold: 1, rest: 10, mode: .bothHands))
        var feeder = Feeder()
        runner.handle(.start, at: 0)

        _ = feeder.hold(&runner, kg: pulling, seconds: 1.3)
        feeder.letGo(&runner)
        _ = feeder.wait(&runner, seconds: 11)
        XCTAssertEqual(runner.phase, .armed(slot: 1))

        // The second and last pull: still gripping hard when it completes.
        let cues = feeder.hold(&runner, kg: pulling, seconds: 1.3)
        XCTAssertTrue(cues.contains(.sessionCompleted))
        XCTAssertTrue(runner.isFinished, "no rest is owed, so no release is waited for")
    }

    /// A skip from the LET GO screen must behave exactly as one from the rest screen:
    /// the finished rep keeps its outcome and the NEXT one is what gets skipped.
    func testSkippingWhileWaitingToLetGoSkipsTheNextPullNotTheFinishedOne() {
        var runner = SessionRunner(plan: plan(reps: 3, hold: 1, rest: 5, mode: .bothHands))
        var feeder = Feeder()
        runner.handle(.start, at: 0)
        _ = feeder.hold(&runner, kg: pulling, seconds: 1.3)
        XCTAssertEqual(runner.phase, .releasing(slot: 0))

        runner.handle(.skipRep, at: feeder.now)
        XCTAssertEqual(runner.results.map(\.outcome), [.completed, .skipped])
    }

    // MARK: - What the screen is told to describe

    /// Resting is PREPARATION: the grip, hand and set number are there to be read while
    /// you shake out, and the rep already behind you is the one thing you do not need.
    func testDuringARestTheScreenDescribesTheNextPullNotTheFinishedOne() {
        // Two sets of one, so the rest between them is a SET BREAK and the grips differ.
        var p = plan(reps: 1, sets: 2, hold: 1, rest: 5, setBreak: 5, mode: .bothHands)
        p.sets[1].grip = GripSpec(edgeMM: 20, fingers: .frontTwo, position: .openHand)
        var runner = SessionRunner(plan: p)
        var feeder = Feeder()
        runner.handle(.start, at: 0)

        _ = feeder.hold(&runner, kg: pulling, seconds: 1.3)
        feeder.letGo(&runner)
        XCTAssertEqual(runner.phase, .resting(slot: 0))

        XCTAssertEqual(runner.displaySlot?.grip, p.sets[1].grip, "the grip you are about to pull")
        XCTAssertEqual(runner.setNumber, 2, "and the set you are about to start")
        XCTAssertTrue(runner.isSetBreak, "while the REST itself is still the one just earned")
        XCTAssertEqual(runner.currentSlot?.grip, p.sets[0].grip,
                       "currentSlot still means the rep that owns this rest")
    }

    /// `.releasing` deliberately does NOT look forward — you are still on the current
    /// edge, and swapping the grip out from under a hand that has not let go would be
    /// describing something that isn't happening.
    func testWhileWaitingToLetGoTheScreenStillDescribesTheGripInYourHand() {
        var p = plan(reps: 1, sets: 2, hold: 1, rest: 5, setBreak: 5, mode: .bothHands)
        p.sets[1].grip = GripSpec(edgeMM: 20, fingers: .frontTwo, position: .openHand)
        var runner = SessionRunner(plan: p)
        var feeder = Feeder()
        runner.handle(.start, at: 0)

        _ = feeder.hold(&runner, kg: pulling, seconds: 1.3)
        XCTAssertEqual(runner.phase, .releasing(slot: 0))
        XCTAssertEqual(runner.displaySlot?.grip, p.sets[0].grip)
        XCTAssertFalse(runner.isSetBreak, "no rest is running yet")
    }

    /// A paused rest is still a rest for every purpose the screen has.
    func testAPausedRestStillDescribesTheNextPull() {
        var p = plan(reps: 1, sets: 2, hold: 1, rest: 5, setBreak: 5, mode: .bothHands)
        p.sets[1].grip = GripSpec(edgeMM: 20, fingers: .frontTwo, position: .openHand)
        var runner = SessionRunner(plan: p)
        var feeder = Feeder()
        runner.handle(.start, at: 0)
        _ = feeder.hold(&runner, kg: pulling, seconds: 1.3)
        feeder.letGo(&runner)
        runner.handle(.pause, at: feeder.now)

        XCTAssertEqual(runner.displaySlot?.grip, p.sets[1].grip)
        XCTAssertTrue(runner.isSetBreak)
    }

    /// The rest BEFORE the last rep of a set is not a set break, even though the rep it
    /// leads into is the last of that set. This is the case `isLastOfSet` on the
    /// forward-looking slot would get wrong.
    func testTheRestBeforeASetsLastPullIsNotASetBreak() {
        var runner = SessionRunner(plan: plan(reps: 2, sets: 2, hold: 1, rest: 5,
                                              setBreak: 30, mode: .bothHands))
        var feeder = Feeder()
        runner.handle(.start, at: 0)
        _ = feeder.hold(&runner, kg: pulling, seconds: 1.3)
        feeder.letGo(&runner)

        XCTAssertEqual(runner.phase, .resting(slot: 0))
        XCTAssertEqual(runner.displaySlot?.repIndex, 1, "the next pull IS the set's last")
        XCTAssertFalse(runner.isSetBreak, "but this rest is a between-pulls rest")
    }

    // MARK: - A grip change ahead

    /// The cue exists to be RARE (Nuri, 2026-08-19). Every rest inside a set leads back
    /// onto the same edge, so a badge that lit on all of them would say nothing.
    func testARestBetweenTwoPullsOnTheSameGripAnnouncesNoChange() {
        var runner = SessionRunner(plan: plan(reps: 2, hold: 1, rest: 5, mode: .bothHands))
        var feeder = Feeder()
        runner.handle(.start, at: 0)

        _ = feeder.hold(&runner, kg: pulling, seconds: 1.3)
        feeder.letGo(&runner)
        XCTAssertEqual(runner.phase, .resting(slot: 0))
        XCTAssertFalse(runner.nextGripDiffers, "the same grip is not news")
    }

    /// The case it was built for: the set break before a set on a different grip.
    func testTheSetBreakBeforeADifferentGripAnnouncesTheChange() {
        var p = plan(reps: 1, sets: 2, hold: 1, rest: 5, setBreak: 5, mode: .bothHands)
        p.sets[1].grip = GripSpec(edgeMM: 20, fingers: .frontTwo, position: .openHand)
        var runner = SessionRunner(plan: p)
        var feeder = Feeder()
        runner.handle(.start, at: 0)

        _ = feeder.hold(&runner, kg: pulling, seconds: 1.3)
        feeder.letGo(&runner)
        XCTAssertEqual(runner.phase, .resting(slot: 0))
        XCTAssertTrue(runner.nextGripDiffers, "the set you are about to start is a different grip")
    }

    /// `.releasing` does not look forward, so it does not warn forward either: a hand
    /// still on the edge is not being asked to change anything yet.
    func testWhileWaitingToLetGoTheComingGripChangeIsNotAnnouncedYet() {
        var p = plan(reps: 1, sets: 2, hold: 1, rest: 5, setBreak: 5, mode: .bothHands)
        p.sets[1].grip = GripSpec(edgeMM: 20, fingers: .frontTwo, position: .openHand)
        var runner = SessionRunner(plan: p)
        var feeder = Feeder()
        runner.handle(.start, at: 0)

        _ = feeder.hold(&runner, kg: pulling, seconds: 1.3)
        XCTAssertEqual(runner.phase, .releasing(slot: 0))
        XCTAssertFalse(runner.nextGripDiffers, "the rest it belongs to has not begun")
    }

    /// Mid-hold there is nothing to prepare for, and the screen is carrying the one word
    /// that matters. The cue belongs to the rest and to nothing else.
    func testMidHoldNoGripChangeIsAnnounced() {
        var p = plan(reps: 1, sets: 2, hold: 5, rest: 5, setBreak: 5, mode: .bothHands)
        p.sets[1].grip = GripSpec(edgeMM: 20, fingers: .frontTwo, position: .openHand)
        var runner = SessionRunner(plan: p)
        var feeder = Feeder()
        runner.handle(.start, at: 0)

        _ = feeder.hold(&runner, kg: pulling, seconds: 1)
        XCTAssertEqual(runner.phase, .working(slot: 0))
        XCTAssertFalse(runner.nextGripDiffers)
    }

    /// A paused rest is still a rest for every purpose the screen has — and the pause is
    /// exactly when someone reads the row.
    func testAPausedRestStillAnnouncesTheGripChange() {
        var p = plan(reps: 1, sets: 2, hold: 1, rest: 5, setBreak: 5, mode: .bothHands)
        p.sets[1].grip = GripSpec(edgeMM: 20, fingers: .frontTwo, position: .openHand)
        var runner = SessionRunner(plan: p)
        var feeder = Feeder()
        runner.handle(.start, at: 0)

        _ = feeder.hold(&runner, kg: pulling, seconds: 1.3)
        feeder.letGo(&runner)
        runner.handle(.pause, at: feeder.now)
        XCTAssertTrue(runner.nextGripDiffers)
    }

    /// Whole specs are compared, so a ladder that only thins the edge still warns: 20 mm
    /// to 10 mm on the same fingers is a different hold, and moving to it is the thing
    /// worth noticing.
    func testAChangeOfEdgeAloneIsStillANewGrip() {
        var p = plan(reps: 1, sets: 2, hold: 1, rest: 5, setBreak: 5, mode: .bothHands)
        p.sets[1].grip = GripSpec(edgeMM: 10, fingers: .four, position: .halfCrimp)
        var runner = SessionRunner(plan: p)
        var feeder = Feeder()
        runner.handle(.start, at: 0)

        _ = feeder.hold(&runner, kg: pulling, seconds: 1.3)
        feeder.letGo(&runner)
        XCTAssertEqual(runner.phase, .resting(slot: 0))
        XCTAssertTrue(runner.nextGripDiffers, "only the edge moved, and the hand moves with it")
    }

    // MARK: - Sequencing

    func testLeadInCountsDownBeforeTheFirstRepOfEachSetOnly() {
        var runner = SessionRunner(plan: plan(reps: 2, sets: 2, hold: 1, rest: 2,
                                              setBreak: 3, leadIn: 3, mode: .bothHands))
        var feeder = Feeder()

        let opening = runner.handle(.start, at: 0)
        XCTAssertEqual(opening, [.leadInTick(secondsRemaining: 3)])
        let cues = feeder.wait(&runner, seconds: 3.2)
        XCTAssertTrue(cues.contains(.leadInTick(secondsRemaining: 1)))
        XCTAssertTrue(cues.contains(.armed(.both)))

        _ = feeder.hold(&runner, kg: pulling, seconds: 1.3)
        feeder.letGo(&runner)
        XCTAssertEqual(runner.phase, .resting(slot: 0))
        // Rep 2 of the same set gets NO lead-in — you are already on the edge.
        let afterRest = feeder.wait(&runner, seconds: 2.2)
        XCTAssertFalse(afterRest.contains { if case .leadInTick = $0 { true } else { false } })
        XCTAssertEqual(runner.phase, .armed(slot: 1))
    }

    func testTheRunnerWalksTheSameSequencePlanMathAdvertised() {
        let p = RoutineDraft.starter.normalized.plan
        let runner = SessionRunner(plan: p)
        XCTAssertEqual(runner.plannedRepCount, PlanMath.totalReps(p), "36 pulls")
        XCTAssertEqual(runner.plannedRepCount, 36)
        XCTAssertEqual(runner.setCount, 6)
        XCTAssertEqual(runner.slots, PlanMath.sequence(for: p))
    }

    func testHandsAlternateAcrossRepsAndResetAtEachSetBoundary() {
        // `repsPerSide: 2` with a two-sided mode is FOUR reps per set, not two — the
        // property is named per-side precisely so this factor of two is never a surprise.
        let p = plan(reps: 2, sets: 2, mode: .alternateEachRep)
        let runner = SessionRunner(plan: p)
        XCTAssertEqual(runner.slots.count, 8)
        XCTAssertEqual(runner.slots.map(\.side),
                       [.left, .right, .left, .right, .left, .right, .left, .right])
        // The load-bearing part: set 1 begins on the LEFT again rather than continuing
        // the parity of set 0, so each set is balanced on its own terms.
        let firstOfSecondSet = try! XCTUnwrap(runner.slots.first { $0.setIndex == 1 })
        XCTAssertEqual(firstOfSecondSet.side, .left)
        XCTAssertEqual(firstOfSecondSet.repIndex, 0)
    }

    func testCompletedPeaksStayWithTheHandThatPerformedEachPlannedPull() {
        let cases: [(HandMode, [Side])] = [
            (.alternateEachRep, [.left, .right, .left, .right]),
            (.alternateEachSet, [.left, .left, .right, .right]),
            (.bothHands, [.both, .both])
        ]
        for (mode, expectedSides) in cases {
            var runner = SessionRunner(plan: plan(reps: 2, hold: 1, rest: 1, mode: mode))
            var feeder = Feeder()
            let peaks = Array([31.0, 19.0, 35.0, 21.0].prefix(expectedSides.count))
            runner.handle(.start, at: 0)
            for peak in peaks {
                _ = feeder.hold(&runner, kg: peak, seconds: 1.3)
                feeder.letGo(&runner)
                _ = feeder.wait(&runner, seconds: 1.2)
            }
            XCTAssertEqual(runner.phase, .finished, mode.rawValue)
            XCTAssertEqual(runner.results.map(\.side), expectedSides, mode.rawValue)
            XCTAssertEqual(runner.results.map(\.peakKg), peaks, mode.rawValue)
            XCTAssertTrue(runner.results.allSatisfy { $0.outcome == .completed })
        }
    }

    func testAnEmptyPlanFinishesImmediatelyRatherThanHanging() {
        var runner = SessionRunner(plan: SessionPlan(name: "Empty", sets: []))
        let opening = runner.handle(.start, at: 0)
        XCTAssertEqual(opening, [.sessionCompleted])
        XCTAssertEqual(runner.phase, .finished)
    }

    // MARK: - A whole session

    /// The integration case: three sets, mixing a clean rep, a shaky-but-completed rep,
    /// an early release and a skip, asserting the final log rather than any one step.
    func testAFullSessionProducesOneSummaryPerPlannedRep() {
        let p = plan(reps: 2, sets: 3, hold: 3, rest: 1, setBreak: 2, mode: .bothHands)
        var runner = SessionRunner(plan: p)
        var feeder = Feeder()
        runner.handle(.start, at: 0)

        // Set 1 — clean, then shaky but complete.
        _ = feeder.hold(&runner, kg: pulling, seconds: 3.3)
        _ = feeder.wait(&runner, seconds: 1.2)
        for _ in 0..<6 {
            _ = feeder.hold(&runner, kg: pulling, seconds: 0.6)
            _ = feeder.hold(&runner, kg: released, seconds: 0.2)
        }
        _ = feeder.wait(&runner, seconds: 2.2)

        // Set 2 — one given up on with Skip (the ONLY way to end a rep short), then a
        // second skipped outright.
        _ = feeder.hold(&runner, kg: pulling, seconds: 1)
        _ = feeder.hold(&runner, kg: released, seconds: 2.6)
        runner.handle(.skipRep, at: feeder.now)
        _ = feeder.wait(&runner, seconds: 1.2)
        runner.handle(.skipRep, at: feeder.now)
        _ = feeder.wait(&runner, seconds: 2.2)

        // Set 3 — skipped wholesale.
        runner.handle(.skipSet, at: feeder.now)

        XCTAssertEqual(runner.phase, .finished)
        XCTAssertEqual(runner.results.count, p.executable.sets.count * 2, "6 planned, 6 recorded")
        XCTAssertEqual(runner.results.map(\.outcome),
                       [.completed, .completed, .skipped, .skipped, .skipped, .skipped])
        // Every summary carries its own grip and side, so history stays meaningful even
        // if the routine is edited beyond recognition later.
        XCTAssertTrue(runner.results.allSatisfy { $0.grip.key == "20|IMRL|halfCrimp" })
        XCTAssertEqual(runner.results.map(\.setIndex), [0, 0, 1, 1, 2, 2])
        XCTAssertTrue(runner.didAnyWork)
    }

    func testASessionNobodyPulledInIsNotWorthLogging() {
        var runner = SessionRunner(plan: plan(reps: 2, mode: .bothHands))
        runner.handle(.start, at: 0)
        runner.handle(.skipSet, at: 1)
        XCTAssertEqual(runner.phase, .finished)
        XCTAssertFalse(runner.didAnyWork, "all skipped — nothing happened")
    }

    // MARK: - A gauge with no clock of its own
    //
    // Every gauge but the Progressor is stamped from host uptime by the BLE client, so a
    // gap in its timestamps is a gap in the RADIO. `maxCreditedSampleGapSeconds` decides
    // how much of one such gap a rep may bank, and these four tests are the whole rule.

    func testAFiveSecondSyntheticGapCreditsExactlyTheCapAndNotTheGap() {
        var runner = SessionRunner(plan: plan(hold: 30), maxCreditedSampleGapSeconds: 1)
        var feeder = Feeder()
        runner.handle(.start, at: 0)
        _ = feeder.hold(&runner, kg: pulling, seconds: 1)
        let heldBefore = runner.heldSeconds
        XCTAssertGreaterThan(heldBefore, 0)

        // Five seconds of silence, then one reading still over the line. The hand was
        // plausibly on the edge for some of it and demonstrably not measured for the rest.
        runner.handle(.sample(ForceSample(kg: pulling,
                                         deviceMicros: feeder.micros &+ 5_000_000)),
                      at: feeder.now + 5)
        XCTAssertEqual(runner.heldSeconds, heldBefore + 1, accuracy: 0.000_001)
        XCTAssertEqual(runner.phase, .working(slot: 0), "and the rep is untouched")
    }

    /// The same gap, on the one gauge that timestamps its own samples, credits NOTHING —
    /// deliberately the opposite treatment. A device clock's deltas describe the device's
    /// sampling, so 5 s at 80 Hz is a broken timeline rather than a slow reading, and
    /// nothing may be invented from an interval the gauge cannot account for.
    func testTheSameFiveSecondGapOnADeviceClockCreditsNothingAtAll() {
        var runner = SessionRunner(plan: plan(hold: 30))
        var feeder = Feeder()
        runner.handle(.start, at: 0)
        _ = feeder.hold(&runner, kg: pulling, seconds: 1)
        let heldBefore = runner.heldSeconds

        runner.handle(.sample(ForceSample(kg: pulling,
                                         deviceMicros: feeder.micros &+ 5_000_000)),
                      at: feeder.now + 5)
        XCTAssertEqual(runner.heldSeconds, heldBefore, accuracy: 0.000_001)
    }

    /// Why the cap REPLACES the 200 ms plausibility limit instead of joining it: one
    /// coalesced advertisement from an 8 Hz scale is already a 250 ms gap, and the device
    /// clock's rule would throw the whole interval away. A gauge that silently
    /// under-counted every hang is the same failure the runner exists to prevent,
    /// arriving from the other direction.
    func testACoalescedAdvertisementIsCreditedInFullRatherThanDroppedWholesale() {
        let gapMicros: UInt32 = 250_000
        var capped = SessionRunner(plan: plan(hold: 30), maxCreditedSampleGapSeconds: 1)
        var uncapped = SessionRunner(plan: plan(hold: 30))
        var feeder = Feeder()
        capped.handle(.start, at: 0)
        uncapped.handle(.start, at: 0)
        _ = feeder.hold(&capped, kg: pulling, seconds: 1)
        var mirror = Feeder()
        _ = mirror.hold(&uncapped, kg: pulling, seconds: 1)
        let cappedBefore = capped.heldSeconds
        let uncappedBefore = uncapped.heldSeconds

        let stamp = feeder.micros &+ gapMicros
        capped.handle(.sample(ForceSample(kg: pulling, deviceMicros: stamp)), at: feeder.now)
        uncapped.handle(.sample(ForceSample(kg: pulling, deviceMicros: stamp)), at: mirror.now)

        XCTAssertEqual(capped.heldSeconds, cappedBefore + 0.25, accuracy: 0.000_001,
                       "under the cap, the gap is ordinary sampling and banks in full")
        XCTAssertEqual(uncapped.heldSeconds, uncappedBefore, accuracy: 0.000_001,
                       "the device-clock rule would have lost the whole interval")
    }

    /// The clamp is PER SAMPLE, so silence cannot accumulate credit at wall-clock rate:
    /// fifteen seconds of nothing, punctuated by three readings, buys three seconds.
    func testRepeatedSyntheticGapsEachCreditOnlyTheCap() {
        var runner = SessionRunner(plan: plan(hold: 30), maxCreditedSampleGapSeconds: 1)
        var feeder = Feeder()
        runner.handle(.start, at: 0)
        _ = feeder.hold(&runner, kg: pulling, seconds: 1)
        let heldBefore = runner.heldSeconds

        var stamp = feeder.micros
        var at = feeder.now
        for _ in 0..<3 {
            stamp = stamp &+ 5_000_000
            at += 5
            runner.handle(.sample(ForceSample(kg: pulling, deviceMicros: stamp)), at: at)
        }
        XCTAssertEqual(runner.heldSeconds, heldBefore + 3, accuracy: 0.000_001)
    }

    /// **What a timeline break COSTS, which is why a synthetic-clock re-kick must not send
    /// one.** `breakTimeline` clears the accrual anchor and the arming debounce together, so
    /// a break landing between every pair of samples leaves a rep that can neither arm nor
    /// finish while the screen looks perfectly alive — kg moving, samples arriving. On a
    /// gauge sampling every 125 ms the silence watchdog can do exactly that.
    ///
    /// `RunnerSession` keeps the break for the Progressor, whose µs epoch genuinely resets
    /// on a re-sent start, and skips it for every gauge stamped from host uptime.
    func testABreakBetweenEverySamplePairNeitherArmsNorAccrues() {
        func drive(breakingEachGap: Bool) -> SessionRunner {
            // 1 s cap, matching `RunnerSession.syntheticClockGapCapSeconds`.
            var runner = SessionRunner(plan: plan(hold: 5), maxCreditedSampleGapSeconds: 1)
            runner.handle(.start, at: 0)
            var stamp: UInt32 = 0
            var at: TimeInterval = 0
            // Fifty readings at 8 Hz — six seconds of honest pulling against a 5 s hold.
            for _ in 0..<50 {
                if breakingEachGap { runner.handle(.streamRestarted, at: at) }
                stamp = stamp &+ 125_000
                at += 0.125
                runner.handle(.sample(ForceSample(kg: pulling, deviceMicros: stamp)), at: at)
                runner.handle(.tick, at: at)
            }
            return runner
        }

        let broken = drive(breakingEachGap: true)
        XCTAssertEqual(broken.phase, .armed(slot: 0),
                       "the debounce anchor is cleared before it can ever be satisfied")
        XCTAssertEqual(broken.heldSeconds, 0, accuracy: 1e-9)

        let intact = drive(breakingEachGap: false)
        XCTAssertEqual(intact.phase, .finished, "the same trace, un-broken, is a whole rep")
        XCTAssertEqual(intact.results.first?.heldSeconds ?? 0, 5, accuracy: 0.2)
    }

    // MARK: - Backgrounding, keyed to what the gauge can sustain

    func testADisconnectedSessionPausesWheneverTheAppLeavesTheForeground() {
        XCTAssertTrue(BackgroundPausePolicy.pausesOnLeavingForeground(
            isBackground: false, isConnected: false, sustainsBackgroundStreaming: true))
        XCTAssertTrue(BackgroundPausePolicy.pausesOnLeavingForeground(
            isBackground: true, isConnected: false, sustainsBackgroundStreaming: true))
    }

    /// `bluetooth-central` keeps a connected stream alive, which is what lets somebody
    /// swipe home to change the music mid-set (Nuri, 2026-08-09).
    func testAConnectedGaugeThatSustainsStreamingKeepsRunningInTheBackground() {
        XCTAssertFalse(BackgroundPausePolicy.pausesOnLeavingForeground(
            isBackground: true, isConnected: true, sustainsBackgroundStreaming: true))
    }

    /// A broadcast scale's "connection" is a duplicate-allowing scan, and CoreBluetooth
    /// coalesces duplicates once the app is backgrounded — so the readings stop while the
    /// state still says connected. That is the app losing the ability to measure, exactly
    /// like having no gauge, and it must pause rather than stall a rep silently.
    func testABroadcastScanPausesOnBackgroundButNotOnAMereBanner() {
        XCTAssertTrue(BackgroundPausePolicy.pausesOnLeavingForeground(
            isBackground: true, isConnected: true, sustainsBackgroundStreaming: false))
        XCTAssertFalse(BackgroundPausePolicy.pausesOnLeavingForeground(
            isBackground: false, isConnected: true, sustainsBackgroundStreaming: false),
                       "a notification banner is not a suspension, and a scenePhase pause "
                       + "costs a deliberate tap to come back from")
    }

    /// The policy's inputs come from the registry, not from a device check anywhere in the
    /// view — this is the link that makes the rule reach the actual hardware.
    func testTheCapabilityTableIsWhatDrivesThoseTwoAnswers() {
        XCTAssertTrue(GaugeKind.progressor.capabilities.sustainsBackgroundStreaming)
        XCTAssertFalse(GaugeKind.whc06.capabilities.sustainsBackgroundStreaming)
        XCTAssertTrue(GaugeKind.progressor.capabilities.hasDeviceClock,
                      "the only gauge whose deltas are its own")
        XCTAssertFalse(GaugeKind.whc06.capabilities.hasDeviceClock)
    }

    func testRecordingClockIsIndependentOfCreditedTimeAndFinishFreezes() {
        var runner = SessionRunner(plan: plan(hold: 5), timerOnly: true)
        runner.beginRecording(at: 100)
        _ = runner.handle(.start, at: 0, recordedAt: 102)
        _ = runner.handle(.tick, at: 1, recordedAt: 103)
        _ = runner.handle(.tick, at: 2, recordedAt: 104)
        _ = runner.handle(.pause, at: 2, recordedAt: 104)
        _ = runner.handle(.resume, at: 32, recordedAt: 134)
        for t in 32...35 { _ = runner.handle(.tick, at: Double(t), recordedAt: Double(t + 102)) }
        XCTAssertTrue(runner.isFinished)
        XCTAssertEqual(runner.results.first?.heldSeconds, 5)
        XCTAssertEqual(runner.results.first?.startedElapsedSeconds, 2)
        XCTAssertEqual(runner.results.first?.endedElapsedSeconds, 37)
        _ = runner.handle(.tick, at: 999, recordedAt: 999)
        XCTAssertEqual(runner.finishedElapsedSeconds, 37)
    }

    func testSkippingUnstartedSetDoesNotInventStartTimes() {
        var runner = SessionRunner(plan: plan(reps: 3))
        _ = runner.handle(.start, at: 10)
        _ = runner.handle(.skipSet, at: 15)
        XCTAssertEqual(runner.results.count, 3)
        XCTAssertTrue(runner.results.allSatisfy { $0.startedElapsedSeconds == nil && $0.endedElapsedSeconds == 5 })
    }

    func testLegacyAndNewTimingBlobsDecodeWithoutMigration() throws {
        let old = try JSONDecoder().decode(RepSummary.self, from: Data("{}".utf8))
        XCTAssertNil(old.startedElapsedSeconds)
        XCTAssertNil(old.endedElapsedSeconds)
        let timed = RepSummary(startedElapsedSeconds: 12.5, endedElapsedSeconds: 22.5)
        XCTAssertEqual(try JSONDecoder().decode(RepSummary.self, from: JSONEncoder().encode(timed)), timed)
    }

    func testZeroRestGripPreviewKeepsCurrentInstructionThenPersistsThroughFirstPull() {
        var p = plan(reps: 1, sets: 2, hold: 6, rest: 0, setBreak: 0)
        p.sets[1].grip = GripSpec(edgeMM: 10, fingers: .frontTwo, position: .openHand)
        p.sets[1].repsPerSide = 2
        var runner = SessionRunner(plan: p, timerOnly: true)
        runner.handle(.start, at: 0)
        XCTAssertNil(runner.newGripID)
        for t in 1...2 { runner.handle(.tick, at: Double(t)) }
        XCTAssertNil(runner.upcomingGrip)
        runner.handle(.tick, at: 3)
        XCTAssertEqual(runner.upcomingGrip, p.sets[1].grip)
        XCTAssertEqual(runner.displaySlot?.grip, p.sets[0].grip)
        for t in 4...6 { runner.handle(.tick, at: Double(t)) }
        XCTAssertEqual(runner.newGripID, "1.0")
        XCTAssertNil(runner.upcomingGrip)
        runner.handle(.pause, at: 6)
        XCTAssertEqual(runner.newGripID, "1.0")
        runner.handle(.resume, at: 20)
        for t in 20...25 { runner.handle(.tick, at: Double(t)) }
        XCTAssertEqual(runner.newGripID, "1.0")
        runner.handle(.tick, at: 26)
        XCTAssertNil(runner.newGripID, "the second pull on that grip is no longer new")
    }

    func testNewGripWaitsForReleaseThenSurvivesRestAndLeadIn() {
        var p = plan(reps: 1, sets: 2, hold: 1, setBreak: 5, leadIn: 2)
        p.sets[1].grip.edgeMM = 10
        var runner = SessionRunner(plan: p)
        var feeder = Feeder()
        runner.handle(.start, at: 0)
        _ = feeder.wait(&runner, seconds: 2.1)
        _ = feeder.hold(&runner, kg: 20, seconds: 1.3)
        XCTAssertEqual(runner.phase, .releasing(slot: 0))
        XCTAssertNil(runner.newGripID)
        feeder.letGo(&runner)
        XCTAssertEqual(runner.newGripID, "1.0")
        _ = feeder.wait(&runner, seconds: 5.1)
        XCTAssertEqual(runner.phase, .leadIn(slot: 1))
        XCTAssertEqual(runner.newGripID, "1.0")
        _ = feeder.wait(&runner, seconds: 2.1)
        XCTAssertEqual(runner.phase, .armed(slot: 1))
        XCTAssertEqual(runner.newGripID, "1.0")
    }

    func testWholeGripChangesIncludingCurlAndSkippedSetsButNotHandSwaps() {
        for grip in [GripSpec(edgeMM: 10), GripSpec(fingers: .frontTwo), GripSpec(position: .fingerCurl)] {
            var p = plan(reps: 2, sets: 2, setBreak: 0)
            p.sets[1].grip = grip
            var runner = SessionRunner(plan: p)
            runner.handle(.start, at: 0)
            runner.handle(.skipSet, at: 1)
            XCTAssertEqual(runner.newGripID, "1.0")
        }
        var runner = SessionRunner(plan: plan(reps: 1, sets: 2, setBreak: 0, mode: .alternateEachRep))
        runner.handle(.start, at: 0)
        runner.handle(.skipRep, at: 1)
        XCTAssertNil(runner.newGripID)
        runner.handle(.skipSet, at: 2)
        XCTAssertNil(runner.newGripID)
    }

    func testPreviewIsSuppressedWhenRestOrLeadInAlreadyProvidesNotice() {
        for (rest, lead) in [(5, 0), (0, 2)] {
            var p = plan(reps: 1, sets: 2, hold: 5, setBreak: rest, leadIn: lead)
            p.sets[1].grip.edgeMM = 10
            var runner = SessionRunner(plan: p, timerOnly: true)
            runner.handle(.start, at: 0)
            var feeder = Feeder()
            _ = feeder.wait(&runner, seconds: Double(lead) + 3.1)
            XCTAssertNil(runner.upcomingGrip)
        }
    }
}
