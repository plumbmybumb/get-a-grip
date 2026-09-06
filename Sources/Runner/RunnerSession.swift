// SPDX-License-Identifier: MPL-2.0
// Original contributions Copyright 2026 Nuri Bruner.

import Foundation
import Observation

enum StaleBatchHealDecision: Equatable {
    case hold
    case fire
    case expire
}

/// Pure policy for the one recovery break a genuine stream re-kick authorizes.
/// Keeping the decision free of clocks and BLE makes the fail-closed boundary directly
/// testable: stale rejection alone can never heal itself.
enum StaleBatchHealer {
    static let armLifetime: TimeInterval = 5
    static let requiredRejectingChecks = 2
    static let minimumHealInterval: TimeInterval = 2

    static func decision(armed: Bool, armAge: TimeInterval,
                         consecutiveRejectingChecks: Int,
                         timeSinceLastHeal: TimeInterval) -> StaleBatchHealDecision {
        guard armed else { return .hold }
        guard armAge < armLifetime else { return .expire }
        guard consecutiveRejectingChecks >= requiredRejectingChecks,
              timeSinceLastHeal >= minimumHealInterval else { return .hold }
        return .fire
    }
}

/// Whether losing the foreground has to PAUSE the session — a question about whether
/// samples can still reach us, answered from capabilities rather than from a device name.
///
/// Three cases, and the middle one is the one `bluetooth-central` bought:
/// - **No link at all** → pause on any move off `.active`. Nothing keeps the process
///   alive, samples stop, and a rep would silently stall at whatever it had accrued.
/// - **A connected gauge that sustains background streaming** (the Progressor) → keep
///   running. Swiping home to change the music keeps the workout alive and the Live
///   Activity carries it (Nuri, 2026-08-09).
/// - **A connected gauge that does NOT** (the broadcast scales: CoreBluetooth coalesces
///   duplicate advertisements in the background, so the scan effectively goes silent) →
///   pause on `.background`. This is the app losing the ability to measure, which is the
///   same rule as having no gauge, not a dropout — and a dropout is the one thing that
///   must never end a rep.
///
/// `.inactive` deliberately does NOT pause a connected session either way: a
/// notification banner or a Control Centre pull is not a suspension, the scan is still
/// running, and a scenePhase pause needs a deliberate tap to come back from.
enum BackgroundPausePolicy {
    static func pausesOnLeavingForeground(isBackground: Bool, isConnected: Bool,
                                          sustainsBackgroundStreaming: Bool) -> Bool {
        guard isConnected else { return true }
        return isBackground && !sustainsBackgroundStreaming
    }
}

/// Owns a running session: the state machine, the clock that drives its countdowns,
/// the gauge subscription, and the cue playback.
///
/// This is a CLASS, and that is load-bearing rather than stylistic. The gauge hands
/// samples to a callback ~80 times a second, and that callback has to mutate the
/// runner. A `View` is a value type, so a closure stored on `DeviceStore` captures a
/// *copy* of it — mutating `@State` through that copy is exactly the kind of thing
/// that appears to work and then silently doesn't, which is how the first build ran a
/// session where the rep never started even though force was plainly over the line.
/// An `@Observable` reference type has one identity, so there is nothing to lose.
@Observable
@MainActor
final class RunnerSession {
    let template: SessionTemplate
    let plan: SessionPlan

    /// **NOT observable, and that is the whole performance story of this screen.**
    ///
    /// `SessionRunner` is a struct in a single stored property, so ANY mutation marks
    /// the whole property dirty — and force samples mutate it 80× a second. With it
    /// observable, every view that read `session.runner.anything` (RunnerView does, in
    /// fifteen places) rebuilt at 80 Hz to move two numbers: counters, prompts, grip
    /// line, controls, the lot. The engine state stays here; what the SCREEN needs is
    /// republished as `snapshot` only when it actually changes.
    @ObservationIgnored private(set) var runner: SessionRunner

    /// The coarse, view-shaped view of the runner. Equatable and assigned only on a real
    /// change, so a second of holding invalidates the UI ~1–2 times instead of ~80.
    private(set) var snapshot = RunnerSnapshot()

    /// The exact measured fraction, isolated from the coarse screen snapshot. Only the
    /// small progress bar observes this value and interpolates between received samples;
    /// countdowns, controls and grip instructions keep their change-guarded cadence.
    private(set) var repProgress: Double = 0
    var repProgressBucket: Int { Int((repProgress * 100).rounded()) }
    /// Timer-only ring state. Keep this 10 Hz fraction off the screen snapshot too.
    private(set) var phaseRemainingFraction: Double?
    private(set) var startedAt = Date.now
    private(set) var finishedAt: Date?
    /// Monotonic seconds the countdowns are measured against. NOT observable: the
    /// screen reads whole seconds off `snapshot`, so republishing this 10× a second
    /// would invalidate every reader for a number none of them display.
    @ObservationIgnored private(set) var now: TimeInterval = ProcessInfo.processInfo.systemUptime

    /// Counters the view hangs `.sensoryFeedback` off — the house pattern, rather than
    /// calling a feedback generator by hand.
    private(set) var repTick = 0
    private(set) var phaseTick = 0

    /// How many force samples have reached this session. Zero while a workout is under
    /// way means the gauge is connected but not talking, which is the one failure the
    /// screen must not render as "0.0 kg" — that reads as a device measuring nothing
    /// rather than an app receiving nothing.
    @ObservationIgnored private(set) var samplesSeen = 0

    @ObservationIgnored private let device: DeviceStore
    @ObservationIgnored private let liveActivity = SessionActivityController()
    @ObservationIgnored private let cues = CuePlayer()
    @ObservationIgnored private var ticker: Task<Void, Never>?
    @ObservationIgnored private var hasStarted = false
    @ObservationIgnored private var hasBegun = false
    @ObservationIgnored private var hasEnded = false
    @ObservationIgnored private var streamWatchdog: Task<Void, Never>?
    @ObservationIgnored private var lastSampleAt: TimeInterval = 0
    @ObservationIgnored private var staleBatchHealArmedAt: TimeInterval?
    @ObservationIgnored private var consecutiveRejectingChecks = 0
    @ObservationIgnored private var lastStaleBatchHealAt: TimeInterval?
    @ObservationIgnored private var lastActivitySignature: ActivitySignature?

    /// `maxes` is every max on file, by grip AND hand — `TemplateStore.maxTable`.
    /// Percentage targets become kilograms at the moment the session begins and never
    /// move again: the runner executes concrete loads and the `WorkoutLog` freezes them,
    /// so a max recorded next month cannot rewrite what this morning told you to pull.
    ///
    /// **The plan is NOT pre-baked** — `SessionRunner` resolves per REP instead, because
    /// a set covers both hands and the two hands do not have the same max. Baking a
    /// single band onto the set here would hand both hands the same kilograms and
    /// silently undo per-hand loads; the freeze is preserved by capturing the TABLE once,
    /// which is what the runner holds.
    /// `timerOnly` runs the whole session with no gauge at all — see
    /// `SessionRunner.timerOnly`. Nothing here touches `DeviceStore` in that mode: no
    /// connect, no stream, no sample callback, no watchdog. The store is still held
    /// because the screen shares one view, and because a session started without a gauge
    /// must not start quietly using one that happens to be connected.
    init(template: SessionTemplate, device: DeviceStore, maxes: MaxTable = MaxTable(),
         timerOnly: Bool = false) {
        self.template = template
        self.plan = template.plan
        self.device = device
        self.timerOnly = timerOnly
        // Read ONCE, like the timing policy below: what this session is driving must not
        // change under it because a different gauge was selected in Settings mid-workout.
        self.hasDeviceClock = device.gaugeCapabilities.hasDeviceClock
        self.silenceRestartSeconds = Self.silenceThreshold(
            forRate: device.gaugeCapabilities.nominalSampleRate,
            isBroadcast: device.gaugeCapabilities.isBroadcast)
        // Keyed to the CAPABILITY, never to the kind: a gauge with no clock of its own is
        // stamped from host uptime by the client, so one sample's delta is an arrival gap
        // and may only ever buy the cap. The Progressor's own µs deltas are truth and
        // stay uncapped. See `SessionRunner.maxCreditedSampleGapSeconds`.
        self.runner = SessionRunner(
            plan: template.plan, maxes: maxes, timerOnly: timerOnly,
            maxCreditedSampleGapSeconds: device.gaugeCapabilities.hasDeviceClock
                ? nil : Self.syntheticClockGapCapSeconds)
    }

    let timerOnly: Bool

    /// Whether the gauge stamps its own samples. The Progressor does; every ported device
    /// is stamped from host uptime at ingestion, which is why a re-kick means something
    /// different to each — see `restartStreamArmingStaleBatchHeal`.
    @ObservationIgnored private let hasDeviceClock: Bool

    /// How much silence means the stream needs re-kicking, for THIS gauge.
    ///
    /// Internal rather than private because the arithmetic is the whole point and it is
    /// otherwise only observable by waiting seconds for a watchdog tick.
    let silenceRestartSeconds: Double

    /// Eight samples' worth of silence, floored at the Progressor's 0.8 s.
    ///
    /// 0.8 s was justified by "the gauge sends at 80 Hz", which is true of exactly one of
    /// the seven kinds: at 8 Hz an ordinary sample gap is 125 ms and two coalesced
    /// advertisements already spend a third of that budget, so a threshold that never moves
    /// re-kicks a perfectly healthy stream. Eight samples is the same judgement the 0.8 s
    /// expressed — silence long enough that a rep is quietly not being counted — read off
    /// the rate the gauge actually claims. The floor keeps the Progressor's number exactly
    /// where the hardware sessions put it.
    static func silenceThreshold(forRate rate: Double, isBroadcast: Bool = false) -> Double {
        // A broadcast gauge's delivery is bursty by NATURE — multi-second holes are
        // ordinary advertisements, not a stalled stream, and the re-kick is a no-op
        // against an already-running scan anyway. A three-second floor keeps the
        // watchdog for the case it exists for (a scan that actually died) instead of
        // letting it beat in time with the radio (Nuri's hardware session, 2026-08-17).
        isBroadcast ? max(3.0, 8.0 / max(1, rate)) : max(0.8, 8.0 / max(1, rate))
    }

    /// One second — deliberately the same clamp `SessionRunner.holdTick` puts on a
    /// stalled wall clock, because it is the same judgement: a hand that was on the edge
    /// before the gap and still on it after plausibly held through a second of silence,
    /// and anything longer is the radio's story rather than the climber's.
    static let syntheticClockGapCapSeconds: Double = 1

    // MARK: - Lifecycle

    func begin() {
        // Idempotent. SwiftUI can re-run an `onAppear` when the view's branch changes,
        // and a second `begin()` used to re-acquire the idle lock and re-arm the ticker.
        guard !hasBegun else { return }
        hasBegun = true

        IdleTimerLock.acquire()
        startedAt = .now
        runner.beginRecording(at: ProcessInfo.processInfo.systemUptime)
        // DEFERRED off the presenting frame. `AVAudioEngine.start()` and
        // `CHHapticEngine.start()` are tens of milliseconds of synchronous work, and
        // running them inside `onAppear` put that delay between tapping Start and the
        // runner appearing — the one tap in the app that must feel instant. The first
        // cue is at most a runloop turn late; nothing audible is due for five seconds.
        Task { @MainActor [weak self] in
            guard let self, !self.hasEnded else { return }
            self.cues.begin()
        }
        if !timerOnly {
            device.onSample = { [weak self] sample in
                guard let self else { return }
                self.samplesSeen += 1
                self.lastSampleAt = ProcessInfo.processInfo.systemUptime
                self.send(.sample(sample))
            }
        }

        // Wall-clock heartbeat. Countdowns live here; work time never does — that only
        // ever comes from the device's own timestamps inside the runner.
        ticker = Task { [weak self] in
            while !Task.isCancelled {
                do { try await Task.sleep(for: .milliseconds(100)) }
                catch { return }
                guard !Task.isCancelled, let self, !self.hasEnded else { return }
                self.now = ProcessInfo.processInfo.systemUptime
                self.send(.tick)
            }
        }

        if timerOnly {
            // The session starts on the spot: there is nothing to connect to and nothing
            // to tare, and the count-in is the only preamble a clock needs.
            send(.start)
        } else if device.state.isConnected {
            startIfReady(cause: .initial)
        } else {
            device.connect()
        }

        // PUBLISH FIRST. `activityState` reads the snapshot, and until this runs the
        // snapshot is still the empty default — which is how the very first card went out
        // saying "Pull 0 of 12" with no countdown, and then sat there until the next
        // phase change happened to correct it.
        publish()

        // Best-effort and deliberately last: a Live Activity that cannot start (setting
        // off, budget spent) must never disturb a workout that is already under way.
        if let grip = runner.displaySlot?.grip ?? plan.executable.sets.first?.grip {
            liveActivity.start(routineName: template.name,
                               plannedReps: runner.plannedRepCount,
                               setCount: runner.setCount,
                               state: activityState(grip: grip))
        }
    }

    func end() {
        // Only ever tear down a session we actually started, and only once: a stray
        // `onDisappear` reaching this used to stop the stream out from under a live
        // session, which looks exactly like a dead gauge.
        guard hasBegun, !hasEnded else { return }
        hasEnded = true

        ticker?.cancel()
        ticker = nil
        streamWatchdog?.cancel()
        streamWatchdog = nil
        device.onSample = nil
        // Never leave the gauge streaming behind us: it drains its own battery for ten
        // minutes and the user blames the app.
        if device.isStreaming { device.stopStreaming(cause: .sessionEnded) }
        // Ended with the session, not left to expire: a card still saying "Pull" on the
        // lock screen after you have finished is worse than no card at all.
        Task { await liveActivity.end() }
        cues.end()
        IdleTimerLock.release()
    }

    /// The session's real first phase is connect-and-tare, which is why Start on Today
    /// is deliberately enabled while the gauge is still asleep.
    func startIfReady(cause: StreamStartCause) {
        guard !timerOnly, device.state.isConnected else { return }
        // UNCONDITIONAL, deliberately. This used to be `if !device.isStreaming`, and
        // that guard could only ever SKIP the one command the session depends on — if
        // `isStreaming` was true while the gauge was not actually streaming (a stale
        // flag after a reconnect, a backgrounded app, a session torn down early), the
        // start opcode never went out and the whole workout sat at 0.0 kg with an empty
        // trace, while the Live gauge screen worked fine because it sends its own.
        // Re-sending `startWeight` to a streaming Progressor is harmless; not sending it
        // is a dead session.
        guard !hasStarted else {
            // `RunnerView` foregrounding lands here. A re-kick may start a fresh device
            // timestamp epoch, so it needs the same break-first ordering as the silence
            // watchdog and one bounded recovery opportunity for queued old-epoch data.
            restartStreamArmingStaleBatchHeal(cause: cause)
            return
        }
        hasStarted = true
        // Tare FIRST, then start — the order the vendor's own app uses. The reversed
        // order tared a freshly started stream, and on real firmware that killed it.
        device.tare()
        device.startStreaming(cause: .initial)
        send(.tareCommitted)
        send(.start)
        armStreamWatchdog()
    }

    /// Restart the stream whenever it falls SILENT — not merely if it never started.
    ///
    /// The first version fired three times and only when no sample had EVER arrived,
    /// which left a hole the first hardware session fell straight through: a stream
    /// that dies after a few samples (a tare mid-stream did exactly this) defeated the
    /// `samplesSeen == 0` guard, and the workout sat dead beside a connected gauge with
    /// nothing left trying to revive it. A session's stream is supposed to be
    /// CONTINUOUS, so silence while connected is always wrong and always worth a
    /// restart — the start command is harmless when the stream is alive, and this loop
    /// is one cheap check every 500 ms. It runs for the session's whole life; `end()`
    /// cancels it.
    private func armStreamWatchdog() {
        streamWatchdog?.cancel()
        lastSampleAt = ProcessInfo.processInfo.systemUptime
        streamWatchdog = Task { [weak self] in
            while !Task.isCancelled {
                try? await Task.sleep(for: .milliseconds(500))
                guard let self, !Task.isCancelled else { return }
                guard self.device.state.isConnected, !self.runner.isFinished,
                      !self.runner.phase.isPaused else { continue }
                let silence = ProcessInfo.processInfo.systemUptime - self.lastSampleAt
                // Eight samples of silence at whatever rate this gauge claims, floored at
                // 0.8 s — see `silenceThreshold(forRate:)`. On the Progressor that is the
                // 0.8 s the hardware sessions arrived at: a tenth of a second of silence is
                // already abnormal at 80 Hz and a whole second is a rep quietly not being
                // counted. Re-sending start is harmless, so the only cost of checking sooner
                // is a redundant write to a characteristic.
                if silence > self.silenceRestartSeconds {
                    self.restartStreamArmingStaleBatchHeal(cause: .watchdog)
                    continue
                }

                // Raw BLE data is arriving but the engine may be rejecting it because a
                // queued pre-background burst re-anchored the high-water mark. Two 500 ms
                // observations make that state durable rather than a packet-order blip.
                if self.snapshot.isRejectingStaleBatches {
                    self.consecutiveRejectingChecks += 1
                } else {
                    self.consecutiveRejectingChecks = 0
                }
                self.applyStaleBatchHealDecision(at: ProcessInfo.processInfo.systemUptime)
            }
        }
    }

    /// Every RunnerSession-owned re-kick uses this path. Arming happens before the
    /// break, and the break remains immediately before the command that can reset the
    /// device clock.
    /// Restart the stream and NOTHING else — no tare, on any path.
    ///
    /// `startIfReady` is not a substitute: its first-start branch tares, so routing a
    /// manual wake through it would make "waking never tares" a property of which branch
    /// happened to run rather than a guarantee of the API. With the load unknown (that is
    /// what stale means) a tare is the one thing that must not happen here.
    func wakeStream() {
        guard !timerOnly, device.state.isConnected else { return }
        restartStreamArmingStaleBatchHeal(cause: .manualWake)
    }

    private func restartStreamArmingStaleBatchHeal(cause: StreamStartCause) {
        // **No timeline break on a gauge with no clock of its own.** The break exists to
        // survive a Tindeq restarting its µs epoch mid-session; a synthetic stamp is host
        // uptime, which no device restart can rewind, so there is no epoch here to break —
        // and the break is not free. It nulls the accrual anchor and the arming debounce,
        // and at 8–10 Hz the watchdog can land one between every pair of samples: a screen
        // that looks alive, kg moving, on a rep that can neither arm nor finish. So the
        // re-kick is just the re-kick, and the single-shot heal machinery stays for the one
        // gauge whose clock can actually reset.
        guard hasDeviceClock else {
            device.startStreaming(cause: cause)
            return
        }
        staleBatchHealArmedAt = ProcessInfo.processInfo.systemUptime
        consecutiveRejectingChecks = 0
        send(.streamRestarted)
        device.startStreaming(cause: cause)
    }

    private func applyStaleBatchHealDecision(at checkTime: TimeInterval) {
        let armedAt = staleBatchHealArmedAt
        let decision = StaleBatchHealer.decision(
            armed: armedAt != nil,
            armAge: armedAt.map { checkTime - $0 } ?? 0,
            consecutiveRejectingChecks: consecutiveRejectingChecks,
            timeSinceLastHeal: lastStaleBatchHealAt.map { checkTime - $0 } ?? .infinity)

        switch decision {
        case .hold:
            break
        case .fire:
            // Consume BEFORE sending. The healing break cannot authorize itself again;
            // only another actual startWeight re-kick can create another opportunity.
            staleBatchHealArmedAt = nil
            consecutiveRejectingChecks = 0
            lastStaleBatchHealAt = checkTime
            send(.streamRestarted)
        case .expire:
            staleBatchHealArmedAt = nil
            consecutiveRejectingChecks = 0
        }
    }

    func connectionChanged(isConnected: Bool) {
        // A gauge waking up in your bag must not silently take over a session you chose
        // to run without it — the clock would suddenly start waiting for force.
        guard !timerOnly else { return }
        send(isConnected ? .connectionRestored : .connectionLost)
        if isConnected { startIfReady(cause: hasStarted ? .reconnect : .initial) }
    }

    func tare() {
        device.tare()
        send(.tareCommitted)
    }

    // MARK: - The one funnel

    /// Every event in, every cue out, in one place — so there is exactly one line in
    /// the app that decides what a session sounds like.
    @ObservationIgnored private var announcedGrips: Set<String> = []

    func send(_ event: RunnerEvent) {
        let emitted = runner.handle(event, at: now, recordedAt: ProcessInfo.processInfo.systemUptime)
        if runner.isFinished, finishedAt == nil {
            finishedAt = startedAt.addingTimeInterval(runner.finishedElapsedSeconds ?? 0)
        }
        publish()
        if let id = runner.newGripID, announcedGrips.insert(id).inserted {
            cues.gripChanged()
        }
        for cue in emitted {
            cues.play(cue)
            switch cue {
            case .repEnded: repTick += 1
            case .armed, .repStarted, .setCompleted, .sessionCompleted: phaseTick += 1
            default: break
            }
        }
    }

    /// Rebuild the view-facing snapshot, assigning ONLY on a real change. The guard is
    /// the point: `@Observable` invalidates on every set, equal or not.
    private func publish() {
        // `displaySlot`, not `currentSlot`: during a rest the screen describes the rep
        // you are about to do. See `SessionRunner.displaySlot`.
        let slot = runner.displaySlot
        // Retain sub-percent measurements: rounding here makes a long hold visibly
        // stop between 1% boundaries. This remains separate from the screen snapshot.
        let progress = runner.repProgress
        if progress != repProgress { repProgress = progress }
        let remaining = timerOnly ? runner.phaseRemainingFraction(at: now) : nil
        if remaining != phaseRemainingFraction { phaseRemainingFraction = remaining }
        let next = RunnerSnapshot(
            phase: runner.phase,
            isDropped: runner.isDropped,
            isOverTarget: runner.isOverTarget,
            isRejectingStaleBatches: runner.isRejectingStaleBatches,
            linkIsDown: runner.linkIsDown,
            isFinished: runner.isFinished,
            // A gauge-free session always "has signal": the clock is the signal, and the
            // no-readings notice would be complaining about a device nobody asked for.
            hasSignal: timerOnly || samplesSeen > 0,
            setNumber: runner.setNumber,
            setCount: runner.setCount,
            completedRepCount: runner.completedRepCount,
            plannedRepCount: runner.plannedRepCount,
            grip: slot?.grip,
            side: slot?.side,
            targetBand: slot?.targetBand,
            gripChangesNext: runner.nextGripDiffers,
            newGripID: runner.newGripID, upcomingGrip: runner.upcomingGrip,
            isSetBreak: runner.isSetBreak,
            // WHOLE seconds: the screen cannot show more precision than this, so
            // publishing more only buys invalidations. The exact measured progress lives
            // on `repProgress` instead, published just above — never here.
            secondsShown: secondsShown
        )
        if next != snapshot { snapshot = next }
        pushActivity()
    }

    /// Mirror the snapshot into the Live Activity — but only the parts it draws, and only
    /// when one of them actually moved. `secondsShown` deliberately does NOT reach the
    /// activity: the widget counts down on its own from `endsAt`, so forwarding a ticking
    /// number would spend ActivityKit's budget on frames it would have drawn anyway.
    private func pushActivity() {
        guard liveActivity.isRunning, let grip = snapshot.grip else { return }
        let signature = ActivitySignature(grip: grip,
                                          side: snapshot.side ?? .both,
                                          phase: activityPhase,
                                          setNumber: snapshot.setNumber ?? 1,
                                          repPosition: repPosition)
        // **Compared WITHOUT `endsAt`, and that is the whole point.** `endsAt` is
        // `now + secondsRemaining`, so it drifts by fractions of a second on every one of
        // the ten publishes a second — comparing it would push ten times a second and
        // spend a whole session's ActivityKit budget in the first minute. Recomputing the
        // deadline only when the rep or phase actually changes is also the CORRECT
        // moment: a new phase is exactly when a new countdown should start.
        guard signature != lastActivitySignature else { return }
        lastActivitySignature = signature
        let state = activityState(grip: grip)
        // The task carries only this controller and a value type; the `Activity` handle
        // itself never crosses an isolation boundary — see `SessionActivityController`.
        Task { await liveActivity.update(state) }
    }

    /// Everything that should force a push. Deliberately excludes the clock and the live
    /// load: both move continuously and neither is something the widget needs told.
    private struct ActivitySignature: Equatable {
        var grip: GripSpec
        var side: Side
        var phase: SessionActivity.Phase
        var setNumber: Int
        var repPosition: Int
    }

    /// Which pull you are ON. Floored at 1: a card reading "Pull 0 of 12" says the
    /// session has not started, and by the time anyone can see it, it has.
    private var repPosition: Int {
        guard snapshot.plannedRepCount > 0 else { return 1 }
        return max(1, min(snapshot.completedRepCount + 1, snapshot.plannedRepCount))
    }

    private func activityState(grip: GripSpec) -> SessionActivity.ContentState {
        let phase = activityPhase
        // ARMED runs no clock — it waits on you, with no timeout, by design. So the
        // deadline goes out nil and the hold LENGTH goes out instead; see `pendingSeconds`.
        let isArmed = phase == .armed
        return SessionActivity.ContentState(
            grip: grip,
            side: snapshot.side ?? .both,
            phase: phase,
            setNumber: snapshot.setNumber ?? 1,
            repPosition: repPosition,
            targetLoKg: snapshot.targetBand?.lowerBound,
            targetHiKg: snapshot.targetBand?.upperBound,
            // An ABSOLUTE deadline, recomputed from the same countdown the screen shows.
            // Converting to a Date here is what lets the widget tick without us.
            endsAt: !isArmed && snapshot.secondsShown > 0
                ? Date.now.addingTimeInterval(Double(snapshot.secondsShown))
                : nil,
            pendingSeconds: isArmed ? snapshot.secondsShown : nil)
    }

    /// The runner's phases collapsed to the ones that change what you do with your hands —
    /// **and `armed` is one of them.** It used to fall into `.pulling` through the default
    /// branch, which meant the card said "Pull" and ran a countdown while the edge was
    /// still hanging there untouched. It is its own beat, and its own colour: amber, the
    /// app's "waiting on you", against bleu for a clock that is genuinely running.
    private var activityPhase: SessionActivity.Phase {
        if snapshot.phase.isPaused { return .paused }
        switch snapshot.phase {
        case .idle, .leadIn:              return .leadIn
        case .armed:                      return .armed
        case .working:                    return .pulling
        // "Let go" shares rest's colour: the hold is banked either way, and a fifth word
        // on a glanceable card buys less than it costs.
        case .releasing, .resting:        return .resting
        case .finished:                   return .resting
        case .paused:                     return .paused
        }
    }

    /// Whatever clock is running: the countdown during lead-in and rest, the hold
    /// counting DOWN while working, the target while armed.
    private var secondsShown: Int {
        if let remaining = runner.secondsRemaining(at: now) { return remaining }
        guard let slot = runner.currentSlot else { return 0 }
        switch runner.phase {
        case .working:
            return max(0, Int(ceil(Double(slot.holdSeconds) - runner.heldSeconds)))
        case .paused(let inner):
            if case .working = inner {
                return max(0, Int(ceil(Double(slot.holdSeconds) - runner.heldSeconds)))
            }
            return slot.holdSeconds
        default:
            return slot.holdSeconds
        }
    }

    // MARK: - Readouts

    var isFinished: Bool { snapshot.isFinished }
}

/// Everything `RunnerView` draws, at the resolution it draws it.
///
/// Equatable on purpose: `RunnerSession.publish()` assigns only on a real change, which
/// is what turns 80 engine mutations a second into one or two view invalidations.
struct RunnerSnapshot: Equatable {
    var phase: RunnerPhase = .idle
    var isDropped = false
    /// Over the top of the rep's target range — the clock is stopped and the instruction
    /// is the opposite of `isDropped`'s.
    var isOverTarget = false
    /// The engine is fail-closed on a notification whose starting timestamp is stale.
    /// RunnerSession reads this only for a recently armed, single-shot recovery break.
    var isRejectingStaleBatches = false
    var linkIsDown = false
    var isFinished = false
    /// False until the first force sample lands — a connected gauge that is silent.
    var hasSignal = false

    var setNumber: Int?
    var setCount = 0
    var completedRepCount = 0
    var plannedRepCount = 0

    var grip: GripSpec?
    var side: Side?
    /// The load this rep is aiming for, already resolved to kilograms. nil when the
    /// routine sets no target, or when the grip has no max to take a percentage of.
    var targetBand: ClosedRange<Double>?
    /// True while the rest currently running leads into a different grip — the one thing
    /// on a rest screen that is a change of instruction rather than a countdown. False
    /// outside a rest, `.releasing` included; see `SessionRunner.nextGripDiffers`.
    var gripChangesNext = false
    var newGripID: String?
    var upcomingGrip: GripSpec?
    /// True while the rest currently running is a SET BREAK rather than a between-pulls
    /// rest. Describes the rest, not the rep ahead — see `SessionRunner.isSetBreak`.
    var isSetBreak = false

    /// Whole seconds on whichever clock is running.
    var secondsShown = 0
}
