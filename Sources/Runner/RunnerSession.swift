// SPDX-License-Identifier: MPL-2.0
// Original contributions Copyright 2026 Nuri Bruner.

import Foundation
import Observation

@MainActor
protocol RunnerActivityPublishing: AnyObject {
    var isRunning: Bool { get }
    func start(routineName: String, plannedReps: Int, setCount: Int,
               state: SessionActivity.ContentState)
    func update(_ state: SessionActivity.ContentState) async
    func end() async
}

#if canImport(ActivityKit)
extension SessionActivityController: RunnerActivityPublishing {}
#endif

/// The wrist publishes into nothing: watchOS has no ActivityKit, and the phone's card
/// is mirrored to the watch by the system when the PHONE runs the session.
@MainActor
final class NoLiveActivity: RunnerActivityPublishing {
    let isRunning = false
    func start(routineName: String, plannedReps: Int, setCount: Int,
               state: SessionActivity.ContentState) {}
    func update(_ state: SessionActivity.ContentState) async {}
    func end() async {}
}

/// What a session sounds and feels like, behind one seam: `CuePlayer` on the phone
/// (tones plus Core Haptics), `WatchCuePlayer` on the wrist (the system's haptics). The
/// runner emits `RunnerCue`s and never plays one itself, so this is the only line
/// between the engine and anybody's ears.
@MainActor
protocol RunnerCuePlaying: AnyObject {
    func begin()
    func end()
    func play(_ cue: RunnerCue)
    func gripChanged()
    /// Where the player's own evidence goes — what the audio session found, an
    /// interruption — so it lands in the session's diagnostics ring beside the link's.
    func setDiagnosticSink(_ sink: @escaping (String) -> Void)
}

extension RunnerCuePlaying {
    /// A player with nothing to report — the watch's haptics, silence, a test double.
    func setDiagnosticSink(_ sink: @escaping (String) -> Void) {}
}

/// Nothing at all — the default where no player has been chosen, and what tests get.
@MainActor
final class SilentCuePlayer: RunnerCuePlaying {
    func begin() {}
    func end() {}
    func play(_ cue: RunnerCue) {}
    func gripChanged() {}
}

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
/// - **No link at all** → pause on any move off `.active`. Nothing keeps the process
///   alive, and a rep would silently stall.
/// - **A connected gauge that sustains background streaming** (the Progressor) → keep
///   running; the Live Activity carries it (Nuri, 2026-08-09).
/// - **A connected gauge that does NOT** (broadcast scales: CoreBluetooth coalesces
///   duplicate advertisements in the background) → pause on `.background`. That is the
///   app losing the ability to measure, not a dropout — and a dropout must never end a rep.
///
/// `.inactive` does NOT pause a connected session: a banner or a Control Centre pull is
/// not a suspension, and a scenePhase pause needs a deliberate tap to come back from.
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
/// A CLASS, and that is load-bearing: the gauge's ~80 Hz callback has to mutate the
/// runner, and a closure stored on `DeviceStore` would capture a *copy* of a `View` —
/// mutating `@State` through it silently doesn't (the first build's rep never started
/// with force plainly over the line). A reference type has one identity.
@Observable
@MainActor
final class RunnerSession {
    let template: SessionTemplate
    let plan: SessionPlan

    /// **NOT observable, and that is the whole performance story of this screen.**
    ///
    /// `SessionRunner` is a struct in one stored property, so every force sample (80×
    /// a second) would invalidate every view reading `session.runner.anything`. What the
    /// SCREEN needs is republished as `snapshot` only when it actually changes.
    @ObservationIgnored private(set) var runner: SessionRunner

    /// The coarse, view-shaped view of the runner. Equatable and assigned only on a real
    /// change, so a second of holding invalidates the UI ~1–2 times instead of ~80.
    private(set) var snapshot = RunnerSnapshot()

    /// The exact measured fraction, kept off the coarse snapshot: only the time bar
    /// (`RunnerTimeBar`) observes it.
    private(set) var repProgress: Double = 0
    /// The rest's countdown and the timer-only ring. Keep this 10 Hz fraction off the
    /// screen snapshot too.
    private(set) var phaseRemainingFraction: Double?
    private(set) var startedAt = Date.now
    private(set) var finishedAt: Date?
    /// Monotonic seconds the countdowns are measured against. NOT observable: the
    /// screen reads whole seconds off `snapshot`.
    @ObservationIgnored private(set) var now: TimeInterval = ProcessInfo.processInfo.systemUptime

    /// Counters the view hangs `.sensoryFeedback` off — the house pattern, rather than
    /// calling a feedback generator by hand.
    private(set) var repTick = 0
    private(set) var phaseTick = 0

    /// How many force samples have reached this session. Zero mid-workout means the
    /// gauge is connected but not talking, which the screen must not render as "0.0 kg".
    @ObservationIgnored private(set) var samplesSeen = 0

    @ObservationIgnored private let device: DeviceStore
    @ObservationIgnored private let liveActivity: any RunnerActivityPublishing
    @ObservationIgnored private let cues: any RunnerCuePlaying
    @ObservationIgnored private var ticker: Task<Void, Never>?
    @ObservationIgnored private var hasStarted = false
    @ObservationIgnored private var hasBegun = false
    @ObservationIgnored private var hasEnded = false
    /// Everything that only a LIVE workout needs has been let go — see `quiesce`. Set once,
    /// at the finish, or by `end()` for a session left before it finished.
    @ObservationIgnored private var isQuiesced = false
    /// The cue engines' deferred shutdown, so the finish chord is not cut off mid-note.
    /// Readable so a test can await it rather than sleep past it.
    @ObservationIgnored private(set) var cueShutdown: Task<Void, Never>?
    @ObservationIgnored private var cuesEnded = false
    /// The delayed Live Activity start — readable for the same reason.
    @ObservationIgnored private(set) var activityStart: Task<Void, Never>?
    /// The most recent push to the Live Activity (an update or the end). Pushes are
    /// fire-and-forget; this is only what a test awaits to see one land.
    @ObservationIgnored private(set) var lastActivityPush: Task<Void, Never>?
    @ObservationIgnored private let activityStartDelay: Duration?
    @ObservationIgnored private let finishCueTail: Duration?
    @ObservationIgnored private let draftStore: UnsavedSessionDraftStore?
    /// Names this session's on-disk draft — see `UnsavedSessionDraft`.
    let sessionID = UUID()
    @ObservationIgnored private var streamWatchdog: Task<Void, Never>?
    @ObservationIgnored private var lastSampleAt: TimeInterval = 0
    @ObservationIgnored private var staleBatchHealArmedAt: TimeInterval?
    @ObservationIgnored private var consecutiveRejectingChecks = 0
    @ObservationIgnored private var lastStaleBatchHealAt: TimeInterval?
    @ObservationIgnored private var lastActivitySignature: ActivitySignature?

    /// `maxes` is every max on file, by grip AND hand — `TemplateStore.maxTable`.
    /// Percentage targets become kilograms when the session begins and never move again,
    /// so a max recorded next month cannot rewrite what this morning told you to pull.
    ///
    /// **The plan is NOT pre-baked** — `SessionRunner` resolves per REP, because a set
    /// covers both hands and the hands have different maxes. Baking one band onto the
    /// set would silently undo per-hand loads; the freeze is the TABLE captured once.
    ///
    /// `timerOnly` runs the whole session with no gauge — see `SessionRunner.timerOnly`.
    /// Nothing touches `DeviceStore` in that mode: no connect, stream, sample callback or
    /// watchdog, so a gauge-free session never quietly starts using a connected gauge.
    ///
    /// Every default below is PRODUCTION's; tests and previews pass their own.
    ///
    /// `draftStore` is where a finished-but-unsaved session is kept until Save or Discard;
    /// nil writes nothing.
    ///
    /// `activityStartDelay` holds the Live Activity back off the presenting frame; nil
    /// starts it inside `begin()`, which is what tests that read the first card use.
    ///
    /// `finishCueTail` keeps the cue engines up after the finish for the chord that
    /// announces it; nil ends them at the finish.
    init(template: SessionTemplate, device: DeviceStore, maxes: MaxTable = MaxTable(),
         timerOnly: Bool = false,
         liveActivity: any RunnerActivityPublishing = RunnerSession.defaultLiveActivity(),
         cues: any RunnerCuePlaying = RunnerSession.defaultCues(),
         draftStore: UnsavedSessionDraftStore? = .standard,
         activityStartDelay: Duration? = RunnerSession.liveActivityStartDelay,
         finishCueTail: Duration? = RunnerSession.standardFinishCueTail) {
        self.template = template
        self.plan = template.plan
        self.device = device
        self.liveActivity = liveActivity
        self.cues = cues
        // Audio evidence lands in the link's ring, so the diagnostics export shows it.
        cues.setDiagnosticSink { [weak device] in device?.recordAudio($0) }
        self.draftStore = draftStore
        self.activityStartDelay = activityStartDelay
        self.finishCueTail = finishCueTail
        self.timerOnly = timerOnly
        // Read ONCE, like the timing policy below: what this session is driving must not
        // change under it because a different gauge was selected in Settings mid-workout.
        self.hasDeviceClock = device.gaugeCapabilities.hasDeviceClock
        self.silenceRestartSeconds = Self.silenceThreshold(
            forRate: device.gaugeCapabilities.nominalSampleRate,
            isBroadcast: device.gaugeCapabilities.isBroadcast)
        // Keyed to the CAPABILITY: a gauge with no clock is stamped from host uptime, so a
        // delta is an arrival gap and may only buy the cap; the Progressor's µs deltas are
        // truth. See `SessionRunner.maxCreditedSampleGapSeconds`.
        self.runner = SessionRunner(
            plan: template.plan, maxes: maxes, timerOnly: timerOnly,
            maxCreditedSampleGapSeconds: device.gaugeCapabilities.hasDeviceClock
                ? nil : Self.syntheticClockGapCapSeconds)
    }

    /// The platform's Live Activity, or none — a default argument, so every caller
    /// reads the same `init` signature.
    static func defaultLiveActivity() -> any RunnerActivityPublishing {
        #if canImport(ActivityKit)
        SessionActivityController()
        #else
        NoLiveActivity()
        #endif
    }

    /// The phone's tones and haptics; silence on the wrist until the watch screen hands
    /// in its own player, because the default must never reach for a framework the
    /// platform lacks.
    static func defaultCues() -> any RunnerCuePlaying {
        #if os(watchOS)
        SilentCuePlayer()
        #else
        CuePlayer()
        #endif
    }

    var weightUnit: WeightUnit = .kg {
        didSet { if oldValue != weightUnit { pushActivity() } }
    }

    let timerOnly: Bool

    /// Whether the gauge stamps its own samples. The Progressor does; every ported device
    /// is stamped from host uptime at ingestion, which is why a re-kick means something
    /// different to each — see `restartStreamArmingStaleBatchHeal`.
    @ObservationIgnored private let hasDeviceClock: Bool

    /// How much silence means the stream needs re-kicking, for THIS gauge.
    ///
    /// Internal so the arithmetic is testable without waiting for a watchdog tick.
    let silenceRestartSeconds: Double

    /// Eight samples' worth of silence, floored at the Progressor's 0.8 s.
    ///
    /// 0.8 s assumed 80 Hz, true of one of the seven kinds; at 8 Hz a fixed threshold
    /// re-kicks a healthy stream. Eight samples is the same judgement read off the rate
    /// the gauge claims, and the floor keeps the Progressor where hardware put it.
    static func silenceThreshold(forRate rate: Double, isBroadcast: Bool = false) -> Double {
        // Broadcast delivery is bursty by NATURE and a re-kick is a no-op on a running
        // scan, so a three-second floor keeps the watchdog for a scan that actually died
        // rather than beating in time with the radio (hardware, 2026-08-17).
        isBroadcast ? max(3.0, 8.0 / max(1, rate)) : max(0.8, 8.0 / max(1, rate))
    }

    /// One second — the same clamp `SessionRunner.holdTick` puts on a stalled wall
    /// clock: a hand on the edge either side of a gap plausibly held through a second
    /// of it, and anything longer is the radio's story.
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
        // DEFERRED off the presenting frame: starting the audio and haptic engines is
        // tens of milliseconds of synchronous work between tapping Start and the runner
        // appearing. Nothing audible is due for five seconds.
        Task { @MainActor [weak self] in
            guard let self, !self.hasEnded, !self.isQuiesced else { return }
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
                self.now = ProcessInfo.processInfo.systemUptime + self.clockOffset
                self.send(.tick)
            }
        }

        if timerOnly {
            // Nothing to connect or tare; the count-in is the only preamble.
            send(.start)
        } else if device.state.isConnected {
            startIfReady(cause: .initial)
        } else {
            device.connect()
        }

        // PUBLISH FIRST: `activityState` reads the snapshot, and the empty default sent
        // a first card saying "Pull 0 of 12" with no countdown.
        publish()

        // Best-effort and last: a Live Activity that cannot start must never disturb a
        // workout. And OFF the presenting frame — `Activity.request` is an IPC round trip.
        // The card is built when the task FIRES, so it is never announced stale.
        if let activityStartDelay {
            activityStart = Task { @MainActor [weak self] in
                try? await Task.sleep(for: activityStartDelay)
                guard !Task.isCancelled else { return }
                self?.startLiveActivity()
            }
        } else {
            startLiveActivity()
        }
    }

    /// How long the Live Activity waits after `begin()` — long enough to clear the
    /// cover's presentation, short enough that nobody who swipes home ever sees no card.
    static let liveActivityStartDelay: Duration = .milliseconds(300)

    #if DEBUG
    /// Screenshot fixtures only: move the session's wall clock forward and tick, so a
    /// fixture can finish a REAL rest through the engine instead of skipping the pull
    /// behind it (a skipped pull draws differently from a completed one). Kept as an
    /// OFFSET, so a fixture left running keeps counting from where it was put.
    func debugAdvanceClock(by seconds: TimeInterval) {
        debugClockOffset += seconds
        now += seconds
        send(.tick)
    }
    @ObservationIgnored private var debugClockOffset: TimeInterval = 0
    #endif
    private var clockOffset: TimeInterval {
        #if DEBUG
        debugClockOffset
        #else
        0
        #endif
    }

    private func startLiveActivity() {
        activityStart = nil
        // A session already over by the time the delay ran out gets no card at all: it
        // would only be ended again on the next line of somebody's lock screen.
        guard !hasEnded, !isQuiesced, !runner.isFinished,
              let grip = runner.displaySlot?.grip ?? plan.executable.sets.first?.grip else { return }
        let state = activityState(grip: grip)
        liveActivity.start(routineName: template.name,
                           plannedReps: runner.plannedRepCount,
                           setCount: runner.setCount,
                           state: state)
        // The card just went out with exactly this; the next publish must not re-push it.
        lastActivitySignature = activitySignature(grip: grip)
    }

    func end() {
        // Only tear down a session we started, and only once: a stray `onDisappear`
        // stopping the stream under a live session looks exactly like a dead gauge.
        guard hasBegun, !hasEnded else { return }
        hasEnded = true
        // A finished session already quiesced; this at most brings the cue shutdown
        // forward. A session left BEFORE it finished gets the whole teardown here.
        quiesce(cueTail: nil)
    }

    /// **Let go of everything that only a LIVE workout needs, the moment it stops being
    /// one.** Called once at the finish, and by `end()` for a session left unfinished.
    ///
    /// Waiting for the view to disappear is too late: the summary can sit open for
    /// minutes, and a stream still running there keeps `beginBackgroundGrace` from ever
    /// disconnecting, so `bluetooth-central` drains phone and gauge in a pocket.
    ///
    /// `cueTail` delays only the cue engines' shutdown, so the finish chord that the same
    /// event just queued is heard in full; nil ends them now. Idempotent: everything else
    /// happens once, and a second call can only bring a pending cue shutdown forward.
    private func quiesce(cueTail: Duration?) {
        // A session driven without `begin()` (engine tests) acquired nothing; releasing
        // the idle lock would unbalance somebody else's.
        guard hasBegun else { return }
        if !isQuiesced {
            isQuiesced = true
            activityStart?.cancel()
            activityStart = nil
            ticker?.cancel()
            ticker = nil
            streamWatchdog?.cancel()
            streamWatchdog = nil
            device.onSample = nil
            // Never leave the gauge streaming behind us; stopping it is also what lets the
            // background grace disconnect a gauge nobody is using.
            if device.isStreaming { device.stopStreaming(cause: .sessionEnded) }
            // Ended with the session: a card still saying "Pull" afterwards is worse than
            // no card.
            lastActivityPush = Task { await liveActivity.end() }
            IdleTimerLock.release()
        }
        guard !cuesEnded else { return }
        cueShutdown?.cancel()
        cueShutdown = nil
        guard let cueTail else {
            endCues()
            return
        }
        // The player strongly as well as `self` weakly: a session released during the
        // tail must still let its player go, rather than leave an audio session active.
        let cues = self.cues
        cueShutdown = Task { @MainActor [weak self] in
            try? await Task.sleep(for: cueTail)
            guard !Task.isCancelled else { return }
            if let self { self.endCues() } else { cues.end() }
        }
    }

    private func endCues() {
        guard !cuesEnded else { return }
        cuesEnded = true
        cueShutdown = nil
        cues.end()
    }

    /// Long enough for `sessionCompleted`, the longest cue in the app (~0.5 s), to sound
    /// out before the audio session is released.
    static let standardFinishCueTail: Duration = .seconds(1)

    // MARK: - The unsaved-session draft

    /// Write the finished session to disk, BEFORE anybody is asked whether to keep it.
    /// Only when there is something to keep — a session with no work shows no Save.
    private func writeDraft() {
        guard hasBegun, let draftStore, runner.didAnyWork, let finishedAt else { return }
        let draft = UnsavedSessionDraft(id: sessionID, plan: plan, reps: runner.results,
                                        startedAt: startedAt, finishedAt: finishedAt,
                                        templateID: template.id, templateName: template.name)
        // Best-effort: a draft that cannot be written costs the recovery, never the
        // summary in front of you, whose Save does not depend on it.
        guard (try? draftStore.write(draft)) != nil else { return }
        LiveSessionDrafts.insert(sessionID)
    }

    /// The summary was answered — saved or discarded — so there is nothing to recover.
    /// Called on BOTH, and only after a save that landed: a failed save keeps the draft.
    func clearDraft() {
        draftStore?.delete(id: sessionID)
        LiveSessionDrafts.remove(sessionID)
    }

    /// The session's real first phase is connect-and-tare, which is why Start on Today
    /// is deliberately enabled while the gauge is still asleep.
    func startIfReady(cause: StreamStartCause) {
        guard !timerOnly, !hasEnded, !runner.isFinished, device.state.isConnected else { return }
        // UNCONDITIONAL — never guarded on `device.isStreaming`. A stale flag could only
        // SKIP the one command the session depends on, leaving a workout at 0.0 kg.
        // Re-sending `startWeight` to a streaming Progressor is harmless.
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
        armStaleBatchHeal()
        send(.tareCommitted)
        device.tare()
        device.startStreaming(cause: .initial)
        send(.start)
        armStreamWatchdog()
    }

    /// Restart the stream whenever it falls SILENT — not merely if it never started.
    ///
    /// A stream that died after a few samples (a mid-stream tare on hardware) defeated a
    /// never-started-only check. The stream should be CONTINUOUS, so silence while
    /// connected is always worth a restart. One cheap check every 500 ms for the
    /// session's whole life; `end()` cancels it.
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
                // See `silenceThreshold(forRate:)`. Re-sending start is harmless, so
                // checking sooner costs only a redundant write.
                if silence > self.silenceRestartSeconds {
                    self.restartStreamArmingStaleBatchHeal(cause: .watchdog)
                    continue
                }

                self.checkStaleBatches(at: ProcessInfo.processInfo.systemUptime)
            }
        }
    }

    /// Every RunnerSession-owned re-kick uses this path. Arming happens before the
    /// break, and the break remains immediately before the command that can reset the
    /// device clock.
    /// Restart the stream and NOTHING else — no tare, on any path.
    ///
    /// Not via `startIfReady`, whose first-start branch tares: with the load unknown
    /// (that is what stale means) a tare must not happen, as a guarantee of the API.
    func wakeStream() {
        guard !timerOnly, !hasEnded, !runner.isFinished, device.state.isConnected else { return }
        restartStreamArmingStaleBatchHeal(cause: .manualWake)
    }

    private func restartStreamArmingStaleBatchHeal(cause: StreamStartCause) {
        // **No timeline break on a gauge with no clock of its own.** The break survives a
        // Tindeq restarting its µs epoch; host-uptime stamps have no epoch to break. And
        // it is not free: it nulls the accrual anchor and arming debounce, and at 8–10 Hz
        // the watchdog could land one between every pair of samples — a rep that can
        // neither arm nor finish.
        guard hasDeviceClock else {
            device.startStreaming(cause: cause)
            return
        }
        armStaleBatchHeal()
        send(.streamRestarted)
        device.startStreaming(cause: cause)
    }

    private func armStaleBatchHeal() {
        guard hasDeviceClock else { return }
        staleBatchHealArmedAt = ProcessInfo.processInfo.systemUptime
        consecutiveRejectingChecks = 0
    }

    /// The watchdog's rejection check, separate from the sleeping task so regression
    /// tests can drive the real bounded recovery without waiting for the five-second arm.
    func checkStaleBatches(at checkTime: TimeInterval) {
        // Two 500 ms observations distinguish persistent epoch rejection from a single
        // out-of-order packet. Raw data must still be arriving: silence re-kicks above.
        consecutiveRejectingChecks = snapshot.isRejectingStaleBatches
            ? consecutiveRejectingChecks + 1 : 0
        applyStaleBatchHealDecision(at: checkTime)
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
        guard !timerOnly, !hasEnded, !runner.isFinished, device.state.isConnected,
              TarePolicy.phaseAllowsTare(runner.phase),
              TarePolicy.isSafeToTareNow(sampleAge: device.secondsSinceLastSample(),
                                         maxAgeSeconds: device.tareReadingMaxAge) else { return }
        // DeviceStore re-kicks a running stream after tare, and a queued pre-tare packet
        // can win the race to anchor the new epoch. Arm the same single-use recovery.
        if device.isStreaming { armStaleBatchHeal() }
        send(.tareCommitted)
        device.tare()
    }

    // MARK: - The one funnel

    /// Every event in, every cue out, in one place — so there is exactly one line in
    /// the app that decides what a session sounds like.
    @ObservationIgnored private var announcedGrips: Set<String> = []

    func send(_ event: RunnerEvent) {
        let emitted = runner.handle(event, at: now, recordedAt: ProcessInfo.processInfo.systemUptime)
        if runner.isFinished, finishedAt == nil {
            finishedAt = startedAt.addingTimeInterval(runner.finishedElapsedSeconds ?? 0)
            // On disk FIRST, so there is no instant in which the result exists only in
            // memory behind a summary that has not been answered.
            writeDraft()
            // The summary is not a live workout — see `quiesce`. The tail keeps the cue
            // engines up for the finish chord below.
            quiesce(cueTail: finishCueTail)
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
        // Sub-percent: rounding makes a long hold visibly stop between 1% boundaries.
        let progress = runner.repProgress
        if progress != repProgress { repProgress = progress }
        // Published for measured sessions too, for the time bar's rest countdown. The
        // engine returns nil while a measured pull works, so this changes only on
        // countdown ticks (10 Hz), and only leaf views observe it.
        let remaining = runner.phaseRemainingFraction(at: now)
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
            scheduledRestSeconds: RestFocusPresentation.scheduledRestSeconds(
                phase: runner.phase, slots: runner.slots),
            // WHOLE seconds: more precision only buys invalidations.
            secondsShown: secondsShown
        )
        if next != snapshot { snapshot = next }
        pushActivity()
    }

    /// Mirror the snapshot into the Live Activity — but only the parts it draws, and only
    /// when one of them actually moved. `secondsShown` does NOT reach the activity: the
    /// widget counts down on its own from `endsAt`.
    private func pushActivity() {
        guard !snapshot.isFinished, liveActivity.isRunning, let grip = snapshot.grip else { return }
        let signature = activitySignature(grip: grip)
        // **Compared WITHOUT `endsAt`.** It is `now + secondsRemaining` and drifts on
        // every publish, so comparing it would push ten times a second and spend the
        // session's ActivityKit budget in a minute. A new phase is exactly when a new
        // countdown should start anyway.
        guard signature != lastActivitySignature else { return }
        lastActivitySignature = signature
        let state = activityState(grip: grip)
        // The task carries only this controller and a value type; the `Activity` handle
        // itself never crosses an isolation boundary — see `SessionActivityController`.
        lastActivityPush = Task { await liveActivity.update(state) }
    }

    /// Everything that should force a push. Deliberately excludes the clock and the live
    /// load: both move continuously and neither is something the widget needs told.
    ///
    /// **`clockStopped` is in it**: a hold clock standing still (RE-GRIP, EASE OFF, a
    /// lost link) IS a change of state, or the card's deadline counts to zero while the
    /// app is still waiting for the hold.
    private struct ActivitySignature: Equatable {
        var grip: GripSpec
        var side: Side
        var phase: SessionActivity.Phase
        var setNumber: Int
        var repPosition: Int
        var weightUnit: WeightUnit
        var clockStopped: Bool
    }

    private func activitySignature(grip: GripSpec) -> ActivitySignature {
        ActivitySignature(grip: grip,
                          side: snapshot.side ?? .both,
                          phase: activityPhase,
                          setNumber: snapshot.setNumber ?? 1,
                          repPosition: snapshot.pullPosition,
                          weightUnit: weightUnit,
                          clockStopped: snapshot.holdClockIsStopped)
    }

    private func activityState(grip: GripSpec) -> SessionActivity.ContentState {
        let phase = activityPhase
        // ARMED runs no clock (it waits on you, no timeout), and neither does a STOPPED
        // hold. Both send a nil deadline and the frozen seconds, drawn dimmed — see
        // `pendingSeconds`. The push that restarts the clock brings a fresh deadline.
        let showsPending = phase == .armed || snapshot.holdClockIsStopped
        let remainingInterval = runner.countdownRemainingInterval(
            at: ProcessInfo.processInfo.systemUptime) ?? Double(snapshot.secondsShown)
        return SessionActivity.ContentState(
            grip: grip,
            side: snapshot.side ?? .both,
            phase: phase,
            setNumber: snapshot.setNumber ?? 1,
            repPosition: snapshot.pullPosition,
            targetLoKg: snapshot.targetBand?.lowerBound,
            targetHiKg: snapshot.targetBand?.upperBound,
            // An ABSOLUTE deadline, recomputed from the same countdown the screen shows.
            // Converting to a Date here is what lets the widget tick without us.
            endsAt: phase.runsCountdown && !showsPending && remainingInterval > 0
                ? Date.now.addingTimeInterval(remainingInterval)
                : nil,
            pendingSeconds: showsPending ? snapshot.secondsShown : nil,
            displayWeightUnit: weightUnit)
    }

    /// The runner's phases collapsed to the ones that change what you do with your hands —
    /// **and `armed` is one of them**, or the card says "Pull" and runs a countdown on an
    /// untouched edge. Amber, "waiting on you", against bleu for a running clock.
    private var activityPhase: SessionActivity.Phase {
        if snapshot.phase.isPaused { return .paused }
        switch snapshot.phase {
        case .idle, .leadIn:              return .leadIn
        case .armed:                      return .armed
        case .working:                    return .pulling
        case .releasing:                  return .releasing
        case .resting:                    return .resting
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

    /// Original duration of the rest currently running, not the ticking remainder.
    /// Read from the completed slot that owns this rest, while `grip` and `side`
    /// describe the upcoming slot. nil outside rest, including the release gate.
    var scheduledRestSeconds: Int? = nil

    /// Whole seconds on whichever clock is running.
    var secondsShown = 0

    /// **Which pull you are ON, not how many you have completed.**
    ///
    /// ONE definition for five readouts — counter row, rest focus, watch, Live Activity
    /// and VoiceOver — which must never disagree with each other or with "Set 6 of 6".
    ///
    /// Clamped at both ends, both real bugs: recorded reps include SKIPPED ones, so
    /// `completed + 1` claimed "pull 37 of 36"; and "Pull 0 of 12" says a session has
    /// not started when it has.
    var pullPosition: Int { max(1, min(completedRepCount + 1, plannedRepCount)) }

    /// A rep is under way but its clock is NOT running: off the edge (RE-GRIP), over the
    /// band (EASE OFF), or the link gone. Only ever while working — a rest runs on the wall
    /// clock whatever the gauge is doing, and armed never had a clock to stop.
    var holdClockIsStopped: Bool {
        guard case .working = phase else { return false }
        return isDropped || isOverTarget || linkIsDown
    }
}
