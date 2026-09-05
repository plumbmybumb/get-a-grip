// SPDX-License-Identifier: MPL-2.0
// Original contributions Copyright 2026 Nuri Bruner.

import Foundation

// The guided session — the thing the whole app exists to run.
//
// Pure Swift: no SwiftUI, no CoreBluetooth, no clock of its own. Every input arrives
// as an event and every output leaves as a returned cue, so a whole workout can be
// replayed in a test from a synthetic force trace with no audio, no haptics and no
// waiting. That is the only way the timing rules below are checkable at all.

// MARK: - Phases

/// `indirect` because `.paused` wraps the phase it interrupted — resuming has to put
/// the runner back exactly where it was, including a countdown's remaining time.
indirect enum RunnerPhase: Sendable, Equatable {
    case idle
    /// "Get ready" before the first rep of a set. Wall-clock.
    case leadIn(slot: Int)
    /// Waiting for the climber to take the load. **No timeout, deliberately**: the
    /// human override is Skip, not a clock that gives up on you mid-chalk.
    case armed(slot: Int)
    /// Force is above threshold and the rep's clock is running.
    case working(slot: Int)
    /// The hold is DONE and recorded, but you are still on the edge. **No clock runs
    /// here** — the rest countdown has not started, and will not until force drops below
    /// release. Only reachable when `plan.waitForReleaseBeforeRest` is on.
    ///
    /// A phase of its own rather than "resting, but paused": `isCountingDown` would then
    /// be lying, and the screen has to say LET GO rather than show a frozen countdown.
    case releasing(slot: Int)
    /// Between reps, or between sets — the slot's own `restAfter` covers both, and
    /// `isSetBreak` is what tells them apart. Wall-clock.
    case resting(slot: Int)
    case paused(before: RunnerPhase)
    case finished

    /// The rep this phase belongs to, if any.
    var slotIndex: Int? {
        switch self {
        case .leadIn(let i), .armed(let i), .working(let i), .releasing(let i), .resting(let i): i
        case .paused(let inner): inner.slotIndex
        case .idle, .finished: nil
        }
    }

    var isPaused: Bool { if case .paused = self { true } else { false } }
    var isCountingDown: Bool {
        switch self {
        case .leadIn, .resting: true
        default: false
        }
    }
}

// MARK: - Events and cues

enum RunnerEvent: Sendable, Equatable {
    case start
    case sample(ForceSample)
    /// Wall-clock heartbeat, ~4–10 Hz. Drives every countdown; never work time.
    case tick
    case connectionLost
    case connectionRestored
    /// The gauge was re-zeroed, so the next sample's timestamp is not comparable
    /// with the last one.
    case tareCommitted
    /// The silence watchdog is about to restart weight measurement. The device may
    /// begin a fresh timestamp epoch, so this is an explicit timeline break just like
    /// a committed tare.
    case streamRestarted
    case pause
    case resume
    case skipRep
    case skipSet
    case abort
}

/// What the session wants the app to say or do. Returned from `handle`, never played
/// by the engine — which is what lets a test assert the entire cue schedule of a
/// workout without an audio session existing.
enum RunnerCue: Sendable, Equatable {
    case leadInTick(secondsRemaining: Int)
    case armed(Side)
    case repStarted
    case repHalfway
    case repEnded(completed: Bool)
    case restTick(secondsRemaining: Int)
    case setCompleted(setIndex: Int)
    case sessionCompleted
    /// Force fell below the release threshold mid-rep — the clock has stopped but the
    /// rep is still alive. Fires once per dip.
    case dropoutWarning
    case connectionLost
}

// MARK: - Runner

struct SessionRunner: Sendable {

    // MARK: Tuning
    //
    // Starting values, all named so they can be tuned from one place after real
    // hands have used it. Every one of them exists to absorb a specific physical
    // reality rather than to be clever.

    /// Force must stay above the threshold this long before the rep's clock starts.
    /// Rejects the single-sample spike of bumping the edge on the way to gripping it.
    static let engageDebounceMicros: UInt64 = 100_000
    /// A larger device-time delta is a broken timeline, not measured hang time. The
    /// Progressor samples every ~12.5 ms; 200 ms is deliberately generous. Applies to
    /// gauges that stamp their OWN samples; the rest get a clamp instead of a cliff —
    /// see `maxCreditedSampleGapSeconds` and `creditableDelta`.
    static let maxCreditableDeltaMicros: UInt32 = 200_000
    // THERE IS NO DROPOUT TIMEOUT, AND THERE MUST NOT BE ONE (Nuri, 2026-08-03).
    //
    // Coming off the edge NEVER ends a rep, however long you are off it: the clock
    // stops, the screen says RE-GRIP, and it resumes exactly where it paused. Re-gripping
    // honestly takes more than three seconds, so any grace long enough to be fair is
    // long enough to be pointless — and a rep that ends itself while you are chalking up
    // is the app overruling the person doing the work.
    //
    // **The Skip button is the only way to end a rep early.** One deliberate action,
    // never a clock you have to beat. The same goes for a dropped connection: a rep
    // waits for the gauge to come back rather than abandoning work somebody actually did.

    /// Release sits BELOW engage by this band, so force hovering exactly at the
    /// threshold cannot chatter the rep on and off. 5% of the threshold, clamped to
    /// something a hand can actually feel.
    static func releaseBand(for thresholdKg: Double) -> Double {
        min(2.0, max(0.5, thresholdKg * 0.05))
    }

    /// **The window a rep's clock runs inside.**
    ///
    /// With no target band this is the session threshold and no ceiling at all — the
    /// original rule, unchanged: pull harder than the line and the clock runs.
    ///
    /// With one, the BAND is the rule (Nuri, 2026-08-09): the clock runs only while the
    /// load is actually inside the range the routine asked for, so a rep prescribed at
    /// 22.5–34.0 kg cannot be banked at 12. Below the floor reads as RE-GRIP; above the
    /// ceiling reads as EASE OFF. Both stop the clock and neither ends the rep.
    ///
    /// `release*` sit OUTSIDE `engage*` by the same hysteresis in both directions, so a
    /// hand hovering on either edge cannot chatter the clock — and so the ordinary
    /// overshoot at the start of a hard pull costs nothing.
    struct RepGate: Equatable, Sendable {
        var engageLo: Double
        var engageHi: Double
        var releaseLo: Double
        var releaseHi: Double

        func holds(_ kg: Double) -> Bool { kg >= releaseLo && kg <= releaseHi }
        func admits(_ kg: Double) -> Bool { kg >= engageLo && kg <= engageHi }
    }

    private func gate(for slot: RepSlot) -> RepGate {
        // `pausesOutsideTargetBand == false` takes the SAME gate a band-less rep takes:
        // the engagement threshold and no ceiling. The band is still drawn on the trace —
        // it just stops refereeing. Note what this does NOT loosen: letting go of the
        // edge still stops the rep, because that is a question about whether you are
        // pulling, not about which range you are pulling in.
        guard plan.pausesOutsideTargetBand, let band = slot.targetBand else {
            return RepGate(engageLo: engageKg, engageHi: .infinity,
                           releaseLo: releaseKg, releaseHi: .infinity)
        }
        return RepGate(
            engageLo: band.lowerBound,
            engageHi: band.upperBound,
            releaseLo: max(0, band.lowerBound - Self.releaseBand(for: band.lowerBound)),
            releaseHi: band.upperBound + Self.releaseBand(for: band.upperBound))
    }

    // MARK: Plan

    let plan: SessionPlan
    /// The session as a flat list of fully-resolved reps. `PlanMath.sequence` is the
    /// single source of truth for what happens in what order, so the runner cannot
    /// drift from the duration the routine advertised.
    let slots: [RepSlot]

    /// **No gauge at all** — a flat battery, or the Progressor left at home (Nuri,
    /// 2026-08-09). The session becomes what it always was underneath: a count-in, a
    /// clock, and which hand goes on the edge.
    ///
    /// The engine changes in exactly three places, and nowhere else:
    /// - there is no `armed` phase, because nothing can observe you taking the load, so
    ///   the hold starts when the lead-in ends;
    /// - the hold's clock runs on `tick` (wall time) instead of device timestamps;
    /// - `waitForReleaseBeforeRest` is skipped, because there is no release to see.
    ///
    /// Everything else — the rep sequence, the rests, the set breaks, the cues, the
    /// summary — is the same code path. A session run this way is a real session with
    /// unmeasured load, not a simulation of one, which is why it books real reps.
    let timerOnly: Bool

    /// **The most hang time ONE sample-to-sample gap may ever bank**, for gauges whose
    /// timestamps the CLIENT synthesizes instead of the device measuring them.
    ///
    /// nil — the default, and the Progressor's setting — leaves the original rule alone:
    /// a device clock's deltas are truth, so a gap over `maxCreditableDeltaMicros` is a
    /// broken timeline and credits nothing at all.
    ///
    /// Every ported gauge is stamped from host uptime (`SyntheticSampleClock`), so its
    /// deltas measure the RADIO rather than the hand, and the two halves of that need
    /// opposite treatment. See `creditableDelta`.
    let maxCreditedSampleGapSeconds: Double?

    private let engageKg: Double
    private let releaseKg: Double
    /// `maxCreditedSampleGapSeconds` in the accumulator's own units, resolved once.
    private let creditedGapCeilingMicros: UInt64?

    // MARK: Observable state

    private(set) var phase: RunnerPhase = .idle
    private(set) var results: [RepSummary] = []
    /// Live force, for the hero numeral. Zeroed when the link drops so a stale number
    /// can't sit on screen looking current.
    private(set) var currentKg: Double = 0
    private(set) var linkIsDown = false
    /// Seconds of the current rep already banked. Drives the ring and the countdown.
    private(set) var heldSeconds: Double = 0
    /// True while a rep is alive but its clock is stopped because force fell below the
    /// release threshold. The screen has to SAY this — a timer that silently stops
    /// looks broken, and the climber's instinct is to pull harder rather than re-grip.
    private(set) var isDropped = false
    /// The other way to stall a banded rep: OVER the top of the target range. Same
    /// consequence, opposite instruction — and it needs its own flag because "pull
    /// harder" is the exact wrong thing to tell someone who is already pulling too hard.
    private(set) var isOverTarget = false

    /// Observational timing only. It never participates in thresholds, countdowns,
    /// sample ordering or credited hold time.
    private var recordingOrigin: TimeInterval?
    private var recordingNow: TimeInterval = 0
    private var repStartedElapsedSeconds: Double?
    private(set) var finishedElapsedSeconds: Double?

    mutating func beginRecording(at time: TimeInterval) {
        if recordingOrigin == nil, time.isFinite { recordingOrigin = time }
    }

    private var recordedElapsed: Double? {
        guard let origin = recordingOrigin, recordingNow.isFinite else { return nil }
        return max(0, recordingNow - origin)
    }

    // MARK: Internal state

    /// When the current countdown phase ends, in the caller's monotonic seconds.
    private var countdownEndsAt: TimeInterval = 0
    /// The original length of that countdown, so the dial can describe the phase as a
    /// fraction rather than borrowing rep-only progress. It is replaced at every lead-in
    /// and rest, exactly when `countdownEndsAt` is replaced.
    private var countdownDuration: TimeInterval = 0
    /// Last value announced by a countdown cue, so a tick only speaks when the number
    /// on screen actually changes.
    private var lastAnnouncedSecond: Int = -1
    private var pausedAt: TimeInterval = 0

    /// Previous sample, for wrap-aware deltas. Set to nil whenever the timeline breaks
    /// (pause, reconnect, tare) so an outage is never credited as hang time.
    private var lastSample: ForceSample?
    /// Last accepted device timestamp. A notification beginning at or behind this mark
    /// is a retransmission and is rejected as a unit. Cleared only by an explicit
    /// timeline break; stale data can never authorize a new epoch by itself.
    private var highWaterMicros: UInt32?
    private var rejectingBatch = false
    /// The previous tick, for the gauge-free hold's wall-clock delta. nil across every
    /// discontinuity, exactly like `lastSample`, so a pause is never banked as hang time.
    private var lastTickAt: TimeInterval?
    /// Device time at which force first rose above engage, for the debounce.
    private var engagedSince: UInt32?
    private var warnedThisDip = false
    /// Once force leaves the outer HOLD range, the clock cannot resume in the dead band;
    /// it must cross back through the stricter ENGAGE range first.
    private var mustReengage = false
    private var announcedHalfway = false

    private var accruedMicros: UInt64 = 0
    private var peakKg: Double = 0
    /// Σ(kg × µs) over engaged time only, so the average is time-weighted: a 1 s tail
    /// must not pull the mean as hard as a 9 s plateau.
    private var weightedKgMicros: Double = 0

    // MARK: Init

    /// `maxes` is captured ONCE here and never consulted again — that is what freezes
    /// the session's loads. Percentage targets become kilograms per rep, per hand, at
    /// the moment the session begins, so a max recorded next month cannot rewrite what
    /// this morning told you to pull. Defaulted to empty for every test and caller that
    /// exercises timing rather than load.
    init(plan: SessionPlan, maxes: MaxTable = MaxTable(), timerOnly: Bool = false,
         maxCreditedSampleGapSeconds: Double? = nil) {
        let executable = plan.executable
        self.plan = executable
        let slots = PlanMath.sequence(for: executable, maxes: maxes)
        self.slots = slots
        self.setCount = Set(slots.map(\.setIndex)).count
        self.engageKg = executable.thresholdKg
        self.releaseKg = max(0, executable.thresholdKg - Self.releaseBand(for: executable.thresholdKg))
        self.timerOnly = timerOnly
        self.maxCreditedSampleGapSeconds = maxCreditedSampleGapSeconds
        // Clamped at zero rather than trusted: a negative cap would underflow the
        // unsigned accumulator this feeds, and "no credit" is the honest reading of it.
        self.creditedGapCeilingMicros = maxCreditedSampleGapSeconds
            .map { UInt64(max(0, $0) * 1_000_000) }
    }

    // MARK: - The one entry point

    /// `t` is caller-supplied monotonic seconds. The engine never reads a clock itself,
    /// which is what makes a whole session replayable from `0.0, 0.1, 0.2…`.
    @discardableResult
    mutating func handle(_ event: RunnerEvent, at t: TimeInterval,
                         recordedAt recordingTime: TimeInterval? = nil) -> [RunnerCue] {
        recordingNow = recordingTime ?? t
        defer {
            if isFinished, finishedElapsedSeconds == nil { finishedElapsedSeconds = recordedElapsed }
        }
        switch event {
        case .start:             return start(at: t)
        case .sample(let s):     return receive(s, at: t)
        case .tick:              return tick(at: t)
        case .connectionLost:    return linkLost(at: t)
        case .connectionRestored: return linkRestored()
        case .tareCommitted:     breakTimeline(); return []
        case .streamRestarted:   breakTimeline(); return []
        case .pause:             return pause(at: t)
        case .resume:            return resume(at: t)
        case .skipRep:           return endCurrentRep(.skipped, at: t)
        case .skipSet:           return skipSet(at: t)
        case .abort:             return abort(at: t)
        }
    }

    // MARK: - Lifecycle

    private mutating func start(at t: TimeInterval) -> [RunnerCue] {
        guard case .idle = phase else { return [] }
        beginRecording(at: recordingNow)
        guard !slots.isEmpty else {
            phase = .finished
            return [.sessionCompleted]
        }
        return enter(slot: 0, at: t)
    }

    /// Begin a rep: its lead-in if it has one, otherwise straight to armed.
    private mutating func enter(slot index: Int, at t: TimeInterval) -> [RunnerCue] {
        resetRepAccumulators()
        let slot = slots[index]
        if slot.leadInBefore > 0 {
            phase = .leadIn(slot: index)
            return beginCountdown(seconds: slot.leadInBefore, at: t, tick: RunnerCue.leadInTick)
        }
        return arm(slot: index, at: t)
    }

    /// Hand the rep over to the climber. With a gauge that means WAITING for the load;
    /// without one there is nothing to wait for, so the hold starts on the same beat —
    /// which is exactly what a count-in is for.
    private mutating func arm(slot index: Int, at t: TimeInterval) -> [RunnerCue] {
        guard timerOnly else {
            phase = .armed(slot: index)
            return [.armed(slots[index].side)]
        }
        phase = .working(slot: index)
        repStartedElapsedSeconds = recordedElapsed
        lastTickAt = t
        return [.armed(slots[index].side), .repStarted]
    }

    /// The slot that has NOT been recorded yet — the one a skip or an abort applies to.
    ///
    /// Distinct from `phase.slotIndex` for exactly one phase, and that distinction is
    /// load-bearing: during `.resting(i)` slot `i` is already in `results`, so treating
    /// it as current books it a second time. That is precisely how a six-set routine
    /// reported "37 of 36 pulls" when its sets were skipped from the rest screen.
    private func pending(in phase: RunnerPhase) -> Int? {
        switch phase {
        case .leadIn(let i), .armed(let i), .working(let i):
            return i < slots.count ? i : nil
        // `.releasing` sits on the same side of the line as `.resting`: the rep is
        // already in `results` by the time either is entered.
        case .releasing(let i), .resting(let i):
            return i + 1 < slots.count ? i + 1 : nil
        case .paused(let inner):
            return pending(in: inner)
        case .idle, .finished:
            return nil
        }
    }

    /// Record the current rep and move on. The ONE place a rep is closed, so every
    /// outcome — completed, early, skipped, aborted — books the same fields.
    private mutating func endCurrentRep(_ outcome: RepOutcome, at t: TimeInterval) -> [RunnerCue] {
        guard let index = pending(in: phase), !phase.isPaused else { return [] }
        // A rep that never started still has to be recorded: a session summary with a
        // missing rep reads as a bug, and "you skipped it" is information.
        results.append(summary(for: slots[index], outcome: outcome))
        var cues: [RunnerCue] = [.repEnded(completed: outcome == .completed)]
        cues += advance(from: index, at: t)
        return cues
    }

    private func summary(for slot: RepSlot, outcome: RepOutcome) -> RepSummary {
        let held = Double(accruedMicros) / 1_000_000
        return RepSummary(
            setIndex: slot.setIndex,
            repIndex: slot.repIndex,
            side: slot.side,
            grip: slot.grip,
            targetSeconds: slot.holdSeconds,
            heldSeconds: held,
            peakKg: peakKg,
            avgKg: accruedMicros > 0 ? weightedKgMicros / Double(accruedMicros) : 0,
            // The load THIS hand was asked for, frozen with the rep that was asked for
            // it — see `RepSummary.targetLoKg`.
            targetLoKg: slot.targetBand?.lowerBound,
            targetHiKg: slot.targetBand?.upperBound,
            outcome: outcome,
            startedElapsedSeconds: repStartedElapsedSeconds,
            endedElapsedSeconds: recordedElapsed
        )
    }

    /// Move past `index`: rest if the slot calls for one, otherwise straight into the
    /// next rep; finish if there is none.
    private mutating func advance(from index: Int, at t: TimeInterval) -> [RunnerCue] {
        var cues: [RunnerCue] = []
        if slots[index].isLastOfSet {
            cues.append(.setCompleted(setIndex: slots[index].setIndex))
        }
        let next = index + 1
        guard next < slots.count else {
            phase = .finished
            heldSeconds = 0
            cues.append(.sessionCompleted)
            return cues
        }
        // `restAfter` is 0 on the very last rep, so this is also what stops the session
        // ending with a pointless countdown.
        if slots[index].restAfter > 0 {
            resetRepAccumulators()
            // Hold the countdown until the hand is actually off the edge. Gated on the
            // link being UP: with no samples arriving there is no release to observe,
            // and stalling the whole session on a dead radio is worse than starting the
            // rest a few seconds early.
            // `!timerOnly` because there is no release to observe without a gauge — the
            // session would sit on LET GO forever waiting for a sample that never comes.
            if plan.waitForReleaseBeforeRest, !timerOnly, !linkIsDown, currentKg >= releaseKg {
                phase = .releasing(slot: index)
                return cues
            }
            phase = .resting(slot: index)
            cues += beginCountdown(seconds: slots[index].restAfter, at: t, tick: RunnerCue.restTick)
            return cues
        }
        cues += enter(slot: next, at: t)
        return cues
    }

    /// Force has dropped off the edge after a completed hold — start the rest that was
    /// waiting for it. Also the path taken when the link dies mid-wait.
    private mutating func beginRest(after index: Int, at t: TimeInterval) -> [RunnerCue] {
        phase = .resting(slot: index)
        return beginCountdown(seconds: slots[index].restAfter, at: t, tick: RunnerCue.restTick)
    }

    private mutating func skipSet(at t: TimeInterval) -> [RunnerCue] {
        guard let index = pending(in: phase), !phase.isPaused else { return [] }
        let set = slots[index].setIndex
        // Everything still owed in this set is recorded as skipped, so the log says
        // what happened rather than quietly containing fewer reps than the plan.
        var i = index
        while i < slots.count, slots[i].setIndex == set {
            results.append(summary(for: slots[i], outcome: .skipped))
            resetRepAccumulators()
            i += 1
        }
        var cues: [RunnerCue] = [.repEnded(completed: false), .setCompleted(setIndex: set)]
        guard i < slots.count else {
            phase = .finished
            heldSeconds = 0
            cues.append(.sessionCompleted)
            return cues
        }
        cues += enter(slot: i, at: t)
        return cues
    }

    private mutating func abort(at t: TimeInterval) -> [RunnerCue] {
        guard phase != .finished else { return [] }
        // Only the rep actually in flight is aborted — everything the climber already
        // finished keeps the outcome it earned. `pending(in:)` already resolves through
        // `.paused` (pause never wraps `.idle` — see `pause(at:)`), so a hold-to-end
        // fired while paused still books the rep with whatever it had accrued before
        // the pause. There is deliberately NO `!phase.isPaused` guard here any more:
        // that clause existed only to DROP the in-flight rep, silently, which broke
        // `HoldToEndButton`'s own promise — "nothing is destroyed either way" — for
        // anyone who paused mid-hold before ending. See
        // `testAbortWhilePausedMidHoldStillRecordsTheRepAndItsAccruedTime`.
        if let index = pending(in: phase) {
            results.append(summary(for: slots[index], outcome: .aborted))
        }
        phase = .finished
        heldSeconds = 0
        return [.sessionCompleted]
    }

    // MARK: - Countdowns (wall clock)

    private mutating func beginCountdown(seconds: Int, at t: TimeInterval,
                                         tick: (Int) -> RunnerCue) -> [RunnerCue] {
        countdownEndsAt = t + TimeInterval(seconds)
        countdownDuration = TimeInterval(seconds)
        lastAnnouncedSecond = seconds
        return [tick(seconds)]
    }

    private mutating func tick(at t: TimeInterval) -> [RunnerCue] {
        // WITH NO GAUGE the tick IS the hold's clock. It runs the same accumulator the
        // force path fills, so the halfway cue, the ring, the countdown and the summary
        // all read one number and none of them needs to know where it came from.
        if timerOnly, case .working(let index) = phase {
            return holdTick(slot: index, at: t)
        }
        guard phase.isCountingDown else { return [] }
        let remaining = max(0, Int(ceil(countdownEndsAt - t)))
        guard remaining < lastAnnouncedSecond else { return [] }
        lastAnnouncedSecond = remaining
        guard remaining > 0 else { return countdownFinished(at: t) }
        switch phase {
        case .leadIn: return [.leadInTick(secondsRemaining: remaining)]
        case .resting: return [.restTick(secondsRemaining: remaining)]
        default: return []
        }
    }

    /// The gauge-free hold, advanced by wall time.
    ///
    /// Deltas are CLAMPED to a second: the ticker is a `Task.sleep` loop, so a
    /// backgrounded phone or a stalled main thread would otherwise hand the rep half a
    /// minute it never spent on the edge. Credit the tick you can account for and no more
    /// — the same instinct as the force path's 200 ms plausibility limit.
    private mutating func holdTick(slot index: Int, at t: TimeInterval) -> [RunnerCue] {
        defer { lastTickAt = t }
        guard let last = lastTickAt else { return [] }
        accruedMicros += UInt64(max(0, min(t - last, 1.0)) * 1_000_000)
        heldSeconds = Double(accruedMicros) / 1_000_000

        var cues: [RunnerCue] = []
        let targetMicros = UInt64(max(0, slots[index].holdSeconds)) * 1_000_000
        if !announcedHalfway, targetMicros > 0, accruedMicros * 2 >= targetMicros {
            announcedHalfway = true
            cues.append(.repHalfway)
        }
        guard accruedMicros >= targetMicros else { return cues }
        return cues + endCurrentRep(.completed, at: t)
    }

    private mutating func countdownFinished(at t: TimeInterval) -> [RunnerCue] {
        switch phase {
        case .leadIn(let index):
            return arm(slot: index, at: t)
        case .resting(let index):
            let next = index + 1
            guard next < slots.count else {
                phase = .finished
                return [.sessionCompleted]
            }
            return enter(slot: next, at: t)
        default:
            return []
        }
    }

    // MARK: - Force samples (device clock)

    private mutating func receive(_ sample: ForceSample, at t: TimeInterval) -> [RunnerCue] {
        guard accept(sample) else { return [] }
        currentKg = sample.kg
        defer { lastSample = sample }

        switch phase {
        case .armed(let index):
            return armedSample(sample, slot: index, at: t)
        case .working(let index):
            return workingSample(sample, slot: index, at: t)
        case .releasing(let index):
            // The ONE place a sample starts a countdown. Uses the same release
            // threshold the rep's own clock uses, so "off the edge" means one thing in
            // the whole engine and a hand hovering at the line cannot chatter it.
            guard sample.kg < releaseKg else { return [] }
            return beginRest(after: index, at: t)
        default:
            // Samples outside a rep still update the live readout — the gauge is on
            // screen during rests — but must never move a clock.
            return []
        }
    }

    /// Accept or reject one sample at its notification boundary. Wrapping subtraction
    /// distinguishes a genuine UInt32 rollover (a small forward delta) from an older
    /// epoch (a delta in the backward half of the counter's range). Once a batch start
    /// is stale, every interior sample remains rejected even if its raw timestamp moves
    /// beyond the previous high-water mark.
    private mutating func accept(_ sample: ForceSample) -> Bool {
        if sample.isBatchStart {
            rejectingBatch = false
            if let highWaterMicros {
                let forward = sample.deviceMicros &- highWaterMicros
                if forward == 0 || forward >= 0x8000_0000 {
                    rejectingBatch = true
                }
            }
        }
        guard !rejectingBatch else { return false }
        highWaterMicros = sample.deviceMicros
        return true
    }

    private mutating func armedSample(_ sample: ForceSample, slot index: Int,
                                      at t: TimeInterval) -> [RunnerCue] {
        // INSIDE the gate, not merely above its floor: with a target band the rep does
        // not start until the load is actually in range, so blowing straight through the
        // ceiling on the way up never banks the first tenth of a second at the wrong load.
        guard gate(for: slots[index]).admits(sample.kg) else {
            engagedSince = nil
            return []
        }
        guard let since = engagedSince else {
            engagedSince = sample.deviceMicros
            return []
        }
        // Debounce measured in DEVICE time, not arrival time: a stalled UI thread
        // must not be able to satisfy it.
        guard UInt64(sample.deviceMicros &- since) >= Self.engageDebounceMicros else { return [] }
        phase = .working(slot: index)
        resetRepAccumulators()
        repStartedElapsedSeconds = recordedElapsed
        peakKg = sample.kg
        return [.repStarted]
    }

    private mutating func workingSample(_ sample: ForceSample, slot index: Int,
                                        at t: TimeInterval) -> [RunnerCue] {
        var cues: [RunnerCue] = []
        peakKg = max(peakKg, sample.kg)
        let slot = slots[index]
        let repGate = gate(for: slot)
        let targetMicros = UInt64(max(0, slot.holdSeconds)) * 1_000_000

        // Crossing the outer HOLD edge latches the stall. Samples in the hysteresis
        // dead band keep both the latch and its one-shot warning; only re-entering the
        // stricter ENGAGE range resumes accrual and arms the next warning.
        if mustReengage {
            guard repGate.admits(sample.kg) else {
                if !repGate.holds(sample.kg) {
                    isDropped = sample.kg < repGate.releaseLo
                    isOverTarget = !isDropped
                }
                return cues
            }
            mustReengage = false
            warnedThisDip = false
            isDropped = false
            isOverTarget = false
        }

        if repGate.holds(sample.kg) {
            warnedThisDip = false
            isDropped = false
            isOverTarget = false
            // Accrue ONLY from sample-timestamp deltas, never from arrival time: arrival
            // is subject to BLE batching and UI hitches, and crediting it would let a
            // stalled phone invent hang time nothing measured. How much of one delta may
            // be banked is `creditableDelta`, and it differs by which clock stamped it.
            if let previous = lastSample, !linkIsDown {
                let credited = creditableDelta(UInt64(sample.microsSince(previous)))
                if credited > 0 {
                    accruedMicros += credited
                    weightedKgMicros += sample.kg * Double(credited)
                }
            }
            heldSeconds = Double(accruedMicros) / 1_000_000
            if !announcedHalfway, targetMicros > 0, accruedMicros * 2 >= targetMicros {
                announcedHalfway = true
                cues.append(.repHalfway)
            }
            if accruedMicros >= targetMicros {
                return cues + endCurrentRep(.completed, at: t)
            }
            return cues
        }

        // Outside the gate: the clock stops and the rep STAYS ALIVE, for as long as it
        // takes. A stall costs exactly the seconds it costs and nothing else — the same
        // contract whether you came off the edge or went over the top of the range.
        mustReengage = true
        isDropped = sample.kg < repGate.releaseLo
        isOverTarget = !isDropped
        if !warnedThisDip {
            warnedThisDip = true
            // One cue for both, because it says the same thing — the clock stopped. WHICH
            // way you left the range is a job for the screen, which can use words.
            cues.append(.dropoutWarning)
        }
        return cues
    }

    /// How much of one sample-to-sample gap may be banked as hang time. **Two clocks,
    /// two opposite rules, and the difference is which of them the gap is a fact about.**
    ///
    /// A DEVICE clock (the Progressor's) measures the gauge's own sampling, so at ~80 Hz
    /// a delta over 200 ms is not a slow sample, it is a broken timeline — a
    /// retransmission, a restarted epoch, a corrupt payload. It credits NOTHING, and
    /// `lastSample` still advances so the next coherent delta resumes. Nothing is
    /// invented from an interval the device cannot account for.
    ///
    /// A SYNTHETIC clock stamps each reading with host uptime at ingestion, so its
    /// deltas measure the RADIO. Dropping them the same way would throw away ordinary
    /// sampling rather than protecting anything: an 8 Hz scale is already 125 ms apart
    /// and one coalesced advertisement doubles that, so the 200 ms rule would quietly
    /// under-count every hang on those gauges — the failure mode the runner exists to
    /// prevent, arriving from the other direction. The gap is CLAMPED instead: credit
    /// the interval the cap can account for, exactly as `holdTick` credits a stalled
    /// wall-clock tick, and never the surplus — a two-second RF hole is a fact about
    /// the antenna, not about the hand.
    ///
    /// **KNOWN LIMITATION, named rather than hidden: on a gauge that packs several readings
    /// into one notification, `avgKg` is sampled at the NOTIFICATION rate.** Those readings
    /// all share the batch's stamp (the client refuses to invent spacing it was never told),
    /// so the interior ones have a zero delta, credit nothing, and never reach
    /// `weightedKgMicros` — the average is computed from the first reading of each packet.
    /// The two figures that matter are unaffected: `accruedMicros` is exact because the next
    /// batch's delta carries the whole elapsed interval, and `peakKg` takes its `max` before
    /// this gate. Weighting interior samples by an equal share of the batch delta is the fix
    /// when a real ForceBoard is in hand to measure it against; guessing the spacing now
    /// would trade an under-sampled average for a fabricated one.
    private func creditableDelta(_ delta: UInt64) -> UInt64 {
        guard let ceiling = creditedGapCeilingMicros else {
            return delta <= UInt64(Self.maxCreditableDeltaMicros) ? delta : 0
        }
        return min(delta, ceiling)
    }

    // MARK: - Link and pause

    private mutating func linkLost(at t: TimeInterval) -> [RunnerCue] {
        guard !linkIsDown else { return [] }
        linkIsDown = true
        currentKg = 0
        breakTimeline()
        // A rep waits for the gauge to come back; a REST does not. Waiting for a release
        // nobody can observe would strand the session on a phase that has no clock and
        // no way out but Skip — so the rest starts now and the countdown is honest.
        if case .releasing(let index) = phase {
            return [.connectionLost] + beginRest(after: index, at: t)
        }
        return [.connectionLost]
    }

    private mutating func linkRestored() -> [RunnerCue] {
        linkIsDown = false
        breakTimeline()
        return []
    }

    private mutating func pause(at t: TimeInterval) -> [RunnerCue] {
        switch phase {
        case .idle, .finished, .paused: return []
        default:
            pausedAt = t
            phase = .paused(before: phase)
            breakTimeline()
            return []
        }
    }

    private mutating func resume(at t: TimeInterval) -> [RunnerCue] {
        guard case .paused(let inner) = phase else { return [] }
        // Shift the countdown by however long we were away, so a pause costs no rest
        // and steals none either.
        if inner.isCountingDown { countdownEndsAt += t - pausedAt }
        phase = inner
        breakTimeline()
        return []
    }

    /// Forget the previous sample so the next delta is not measured across a gap.
    /// Called for every explicit discontinuity: pause, resume, disconnect, reconnect,
    /// tare, and a watchdog-driven stream restart.
    private mutating func breakTimeline() {
        lastSample = nil
        highWaterMicros = nil
        rejectingBatch = false
        engagedSince = nil
        lastTickAt = nil
    }

    private mutating func resetRepAccumulators() {
        repStartedElapsedSeconds = nil
        accruedMicros = 0
        weightedKgMicros = 0
        peakKg = 0
        heldSeconds = 0
        engagedSince = nil
        lastTickAt = nil
        warnedThisDip = false
        mustReengage = false
        announcedHalfway = false
        isDropped = false
        isOverTarget = false
    }

    // MARK: - Readouts for the UI

    var currentSlot: RepSlot? {
        guard let index = phase.slotIndex, index < slots.count else { return nil }
        return slots[index]
    }

    /// The rep the SCREEN should be describing — which during a rest is the one you are
    /// about to do, not the one you just finished (Nuri, 2026-08-04).
    ///
    /// Resting is preparation: the grip, the hand and the set number are all there to be
    /// read while you shake out, and the rep already behind you is the one thing you do
    /// not need. `pullLine` has always counted forward ("Pull 13 of 36" during the rest
    /// after pull 12) — everything else was still describing the past, which is how a set
    /// break came to show the grip you had just put down.
    ///
    /// `.releasing` deliberately does NOT look forward: you are still on the current
    /// edge, and swapping the grip out from under a hand that has not let go yet would
    /// be the app describing something that isn't happening.
    var displaySlot: RepSlot? {
        if case .resting = unpaused, let index = pending(in: phase), index < slots.count {
            return slots[index]
        }
        return currentSlot
    }

    /// Whether the rest currently running is a SET BREAK. Keyed to the slot just
    /// finished, deliberately — this describes the rest itself, not what comes next, and
    /// it is the one readout that must keep looking backwards while the rest look
    /// forward. (`isLastOfSet` on `displaySlot` would say "set break" during the rest
    /// before the last rep of the NEXT set.)
    var isSetBreak: Bool {
        guard case .resting(let index) = unpaused, index < slots.count else { return false }
        return slots[index].isLastOfSet
    }

    /// Whether the rest now running leads into a DIFFERENT grip (Nuri, 2026-08-19: the
    /// next set changing grip is easy to miss while you shake out).
    ///
    /// Compared against the slot that OWNS the rest, never against `displaySlot` — that
    /// one has already swapped to the upcoming rep, so asking it both halves of the
    /// question compares a slot with itself and nothing ever differs.
    ///
    /// `.releasing` is excluded for the same reason it does not look forward: a hand
    /// still on the edge is not being asked to change anything yet, and the warning
    /// belongs to the rest that follows.
    ///
    /// Whole specs, not just the fingers — an edge change alone IS a grip change,
    /// because you move your hand for it.
    var nextGripDiffers: Bool {
        guard case .resting(let index) = unpaused, index < slots.count,
              let next = pending(in: phase) else { return false }
        return slots[next].grip != slots[index].grip
    }

    /// Persists from the first display of a changed grip (rest/lead-in/armed) through
    /// its first pull. Compare actual recorded results so skipping a set is covered.
    /// Hand swaps and repeated sets with the same complete GripSpec are not changes.
    var newGripID: String? {
        guard let slot = displaySlot, let previous = results.last,
              slot.grip != previous.grip else { return nil }
        return slot.id
    }

    /// Advance notice only when no rest or lead-in will introduce the next grip.
    /// The current instruction remains current until the engine actually advances.
    var upcomingGrip: GripSpec? {
        guard case .working(let index) = unpaused, index + 1 < slots.count,
              slots[index].restAfter == 0, slots[index + 1].leadInBefore == 0,
              slots[index].grip != slots[index + 1].grip,
              Double(slots[index].holdSeconds) - heldSeconds <= 3 else { return nil }
        return slots[index + 1].grip
    }

    /// The phase with any `.paused` wrapper removed — a paused rest is still a rest for
    /// every purpose the screen has.
    private var unpaused: RunnerPhase {
        if case .paused(let inner) = phase { return inner }
        return phase
    }

    /// 0…1 through the current rep's hold, for the measured runner's progress bar. The
    /// timer-only dial deliberately uses `phaseRemainingFraction` instead: lead-in and
    /// rest have no held seconds to report.
    var repProgress: Double {
        guard let slot = currentSlot, slot.holdSeconds > 0 else { return 0 }
        return min(1, heldSeconds / Double(slot.holdSeconds))
    }

    /// Whole seconds left on whatever is counting down, for the big numeral.
    ///
    /// A paused phase is still the phase it interrupted. Evaluating it against the current
    /// time would make a paused REST continue to lose seconds behind the pause button; the
    /// deadline is shifted only on resume, so the frozen readout must use `pausedAt`.
    func secondsRemaining(at t: TimeInterval) -> Int? {
        let phaseToRead: RunnerPhase
        let clock: TimeInterval
        if case .paused = phase {
            phaseToRead = unpaused
            clock = pausedAt
        } else {
            phaseToRead = phase
            clock = t
        }
        guard phaseToRead.isCountingDown else { return nil }
        return max(0, Int(ceil(countdownEndsAt - clock)))
    }

    /// The fraction of the CURRENT phase remaining, 1…0 as its clock runs down.
    ///
    /// `repProgress` cannot answer this: it is hold-only, so it is zero for every second
    /// of lead-in and rest. Timer-only needs one dial that stays meaningfully filled in
    /// all three live phases, while measured sessions must not publish a continuous value
    /// on every force sample; `RunnerSession` gates this readout to `timerOnly`.
    func phaseRemainingFraction(at t: TimeInterval) -> Double? {
        let phaseToRead = unpaused
        let clock: TimeInterval
        if phase.isPaused {
            clock = pausedAt
        } else {
            clock = t
        }

        switch phaseToRead {
        case .leadIn, .resting:
            guard countdownDuration > 0 else { return nil }
            return max(0, min(1, (countdownEndsAt - clock) / countdownDuration))
        case .working(let index):
            guard timerOnly, index < slots.count, slots[index].holdSeconds > 0 else {
                return nil
            }
            return max(0, min(1,
                              1 - heldSeconds / Double(slots[index].holdSeconds)))
        default:
            return nil
        }
    }

    /// Reps closed out of reps planned — the "12 of 36" line.
    var completedRepCount: Int { results.count }
    var plannedRepCount: Int { slots.count }

    /// 1-based set position, for "SET 2 OF 6". Reads `displaySlot`, so a set break says
    /// the set you are about to start rather than the one you just finished — the same
    /// forward tense as the grip line and the pull count beside it.
    var setNumber: Int? { displaySlot.map { $0.setIndex + 1 } }
    /// STORED, not computed. `slots` is fixed at `init` and never mutates, but this was
    /// read from `RunnerSession.publish()` — which runs on EVERY sample — so an Array-map
    /// plus a Set construction ran ~96,000 times a session to recompute a constant.
    let setCount: Int
    /// 1-based rep position within the current set.
    var repNumberInSet: Int? { displaySlot.map { $0.repIndex + 1 } }
    var repsInCurrentSet: Int? {
        guard let slot = displaySlot else { return nil }
        return slots.filter { $0.setIndex == slot.setIndex }.count
    }

    var isFinished: Bool { phase == .finished }
    /// A notification began at or behind the accepted device-time high-water mark, so
    /// its whole batch is being rejected fail-closed. RunnerSession mirrors this into
    /// its snapshot for the explicitly armed foreground recovery path.
    var isRejectingStaleBatches: Bool { rejectingBatch }
    /// Any rep actually held — what decides whether a log is worth writing.
    var didAnyWork: Bool { results.contains { $0.heldSeconds > 0 } }
}
