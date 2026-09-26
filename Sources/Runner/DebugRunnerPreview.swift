// SPDX-License-Identifier: MPL-2.0
// Original contributions Copyright 2026 Nuri Bruner.

#if DEBUG
import SwiftData
import SwiftUI

enum RunnerCuePreviewPhase {
    case working, warning, releasing, resting

    static var requested: Self? {
        let args = ProcessInfo.processInfo.arguments
        if args.contains("-previewRunnerWarning") { return .warning }
        if args.contains("-previewRunnerWorking") { return .working }
        if args.contains("-previewRunnerRelease") { return .releasing }
        if args.contains("-previewRunnerRest") || args.contains("-previewRunnerPaused") { return .resting }
        return nil
    }
}

/// Screenshot fixtures drive real runner events in an in-memory store. The engine
/// normally stops at the requested phase; only the fake gauge keeps drawing a flat
/// trace. `-previewRunnerRestProgressing` keeps the real ticker for transition tests.
struct DebugRunnerPreview: View {
    @State private var preview: RunnerCuePreviewState

    init(phase: RunnerCuePreviewPhase, timerOnly: Bool = false, paused: Bool = false) {
        _preview = State(initialValue: RunnerCuePreviewState(phase: phase,
                                                             timerOnly: timerOnly, paused: paused))
    }

    var body: some View {
        RunnerView(template: preview.template, timerOnly: preview.session.timerOnly,
                   previewSession: preview.session)
            .environment(preview.device)
            .environment(preview.store)
            .environment(preview.settings)
            .modelContainer(preview.container)
            .dynamicTypeSize(...DynamicTypeSize.accessibility3)
            .onDisappear { preview.stop() }
    }
}

@MainActor
private final class RunnerCuePreviewState {
    let container: ModelContainer
    let template: SessionTemplate
    let store: TemplateStore
    let device: DeviceStore
    let session: RunnerSession
    let settings = SettingsStore()
    private let client = RunnerCuePreviewClient()
    private var pump: Task<Void, Never>?

    init(phase: RunnerCuePreviewPhase, timerOnly: Bool, paused: Bool) {
        let arguments = ProcessInfo.processInfo.arguments
        let setBreak = arguments.contains("-previewRunnerSetBreak")
        let largeCounts = arguments.contains("-previewRunnerLargeCounts")
        let signalLost = arguments.contains("-previewRunnerSignalLost")
        let shaped = arguments.contains("-previewRunnerWave")
        let cycle = arguments.contains("-previewRunnerCycle")
        let hasTarget = arguments.contains("-previewRunnerTarget")
        let pullingKg = hasTarget ? 6.0 : 12.0
        let pauseAtTwo = arguments.contains("-previewRunnerPauseAtTwo")
        let progressing = arguments.contains("-previewRunnerRestProgressing") || pauseAtTwo
        let restSeconds: Int = {
            guard let flag = arguments.firstIndex(of: "-previewRunnerRestSeconds"),
                  arguments.indices.contains(flag + 1),
                  let seconds = Int(arguments[flag + 1]),
                  (setBreak ? SessionPlan.setBreakRange : SetPlan.restRange).contains(seconds)
            else { return 20 }
            return seconds
        }()
        let container = try! ModelContainer(for: SessionTemplate.self, WorkoutLog.self, MaxRecord.self, CriticalForceRecord.self,
            configurations: ModelConfiguration(isStoredInMemoryOnly: true, cloudKitDatabase: .none))
        self.container = container
        // `-previewRunnerSets N [-previewRunnerRepsPerSide R] [-previewRunnerDone D]
        // [-previewRunnerHeld F] [-previewRunnerHoldSeconds H]`: a mid-session fixture for
        // the time bar and routine pills. D pulls are COMPLETED through the engine (not
        // skipped), then the requested phase is entered on the next one.
        func intArg(_ flag: String) -> Int? {
            guard let i = arguments.firstIndex(of: flag), arguments.indices.contains(i + 1) else { return nil }
            return Int(arguments[i + 1])
        }
        let planSets = intArg("-previewRunnerSets")
        let planPerSide = intArg("-previewRunnerRepsPerSide") ?? 3
        let planDone = intArg("-previewRunnerDone") ?? 0
        let planHeld: Double = {
            guard let i = arguments.firstIndex(of: "-previewRunnerHeld"), arguments.indices.contains(i + 1),
                  let value = Double(arguments[i + 1]) else { return 0.6 }
            return value
        }()
        var draft = RoutineDraft.blank(named: "Training cue preview")
        draft.plan.handMode = .alternateEachRep
        draft.plan.sets = [SetPlan(grip: GripSpec(), repsPerSide: setBreak ? (largeCounts ? 100 : 1) : 3)]
        if let planSets {
            draft.plan.sets = (0..<max(1, planSets)).map { index in
                SetPlan(grip: index.isMultiple(of: 2) ? GripSpec()
                              : GripSpec(edgeMM: 15, fingers: .frontTwo, position: .openHand),
                        repsPerSide: max(1, planPerSide))
            }
        }
        if setBreak {
            for _ in 0..<(largeCounts ? 49 : 1) {
                draft.plan.sets.append(SetPlan(grip: GripSpec(edgeMM: 15, fingers: .frontTwo,
                                                            position: .openHand),
                                              repsPerSide: largeCounts ? 100 : 3))
            }
        }
        if hasTarget {
            for index in draft.plan.sets.indices {
                draft.plan.sets[index].targetLoKg = 4
                draft.plan.sets[index].targetHiKg = 8
            }
        }
        let working = phase == .working || phase == .warning
        draft.plan.holdSeconds = planSets != nil ? (intArg("-previewRunnerHoldSeconds") ?? 7)
                                                 : (working ? 10 : 2)
        draft.plan.restSeconds = min(SetPlan.restRange.upperBound, restSeconds)
        draft.plan.setBreakSeconds = restSeconds
        draft.plan.leadInSeconds = 0
        draft.plan.waitForReleaseBeforeRest = true
        template = SessionTemplate(draft: draft, sortIndex: 0)
        store = TemplateStore(context: container.mainContext, clock: DayClock(),
                              settings: settings, storageMode: .localOnly)
        device = DeviceStore(client: client)
        device.connect()
        session = RunnerSession(template: template, device: device, timerOnly: timerOnly,
                                liveActivity: RunnerCuePreviewActivity(), draftStore: nil)
        session.begin()
        var micros: UInt32 = 0
        if planSets != nil, !timerOnly {
            let hold = Double(draft.plan.holdSeconds)
            let client = self.client
            func emit(_ kg: Double, seconds: Double) {
                for _ in 0..<Int((seconds * 10).rounded()) {
                    client.emit(kg: kg, micros: micros)
                    micros &+= 100_000
                }
            }
            let total = template.plan.executable.sets.count * 2 * max(1, planPerSide)
            // Resting lands in the rest after the D-th pull; working pulls the (D+1)-th.
            let finished = working ? min(planDone, total - 1) : max(1, min(planDone, total - 1))
            for index in 0..<finished {
                emit(pullingKg, seconds: hold + 0.4)
                emit(0, seconds: 0.3)
                if !working, index == finished - 1 { break }
                session.debugAdvanceClock(by: Double(max(restSeconds, draft.plan.setBreakSeconds)) + 1)
            }
            if working {
                // The pull in flight: engage, then bank `planHeld` of its hold.
                emit(pullingKg, seconds: 0.2 + planHeld * hold)
            }
        } else if timerOnly {
            if !working { session.send(.skipRep) }
        } else {
            for index in 0...(working ? 3 : 24) {
                client.emit(kg: pullingKg, micros: UInt32(index * 100_000))
            }
            if phase == .warning { client.emit(kg: 0, micros: 400_000) }
            if phase == .resting { client.emit(kg: 0, micros: 2_500_000) }
        }
        // Finish the remaining first-set pulls through real events, so the next
        // set and its counts come from the same engine used in production.
        if planSets == nil, setBreak, phase == .resting {
            for _ in 0..<(largeCounts ? 199 : 1) { session.send(.skipRep) }
        }
        if paused { session.send(.pause) }
        // Stop the ticker and remove its sample callback, preserving the snapshot.
        // The independent fixture gauge below never advances the frozen runner.
        if !progressing { session.end() }
        if signalLost, !timerOnly {
            device.disconnect()
            session.connectionChanged(isConnected: false)
        } else if !timerOnly {
            if !progressing { device.startStreaming(cause: .manualMeasurement) }
            let kg = phase == .releasing || phase == .working ? pullingKg : 0.0
            let pumpStart = max(micros, 2_600_000)
            pump = Task { [weak self] in
                var micros: UInt32 = pumpStart
                var didPauseAtTwo = false
                var beat = 0
                while !Task.isCancelled {
                    guard let self else { return }
                    // `-previewRunnerCycle`: pull while the pull is on you, let go otherwise,
                    // so a live fixture runs pull → rest → pull on its own (recordings).
                    var base = kg
                    if cycle {
                        switch self.session.snapshot.phase {
                        case .armed, .working: base = pullingKg
                        default: base = 0
                        }
                    }
                    self.client.emit(kg: shaped ? Self.shapedKg(base: base, at: Double(beat) * 0.1) : base,
                                     micros: micros)
                    beat += 1
                    micros &+= 100_000
                    // XCTest may wait for an accessibility snapshot longer than one
                    // countdown second. Pause through the real event funnel once at
                    // two, so tests can inspect that state and tap the normal Resume.
                    // Neither the runner clock nor its snapshot is replaced.
                    if pauseAtTwo, !didPauseAtTwo,
                       case .resting = self.session.snapshot.phase,
                       self.session.snapshot.secondsShown == 2 {
                        didPauseAtTwo = true
                        self.session.send(.pause)
                    }
                    do { try await Task.sleep(for: .milliseconds(100)) }
                    catch { return }
                }
            }
        }
    }

    /// `-previewRunnerWave`: a pull with a SHAPE — the ramp on, a wobble about the
    /// load, a settle — for screenshots that judge the graph. The flat line the fixtures
    /// draw by default is right for geometry tests and wrong for looking at a trace.
    /// Zero stays zero: a resting gauge does not wobble.
    private static func shapedKg(base: Double, at t: Double) -> Double {
        guard base > 0 else { return 0 }
        let onset = min(1, t / 0.7)
        let rise = 1 - pow(1 - onset, 3)
        let wobble = sin(t * 5.1) * 0.9 + sin(t * 13.7) * 0.35 + sin(t * 2.3) * 0.6
        return max(0, base * rise + wobble * onset)
    }

    func stop() {
        pump?.cancel()
        pump = nil
        session.end()
        device.disconnect()
    }
}

private final class RunnerCuePreviewClient: ProgressorClient {
    var onEvent: ((ProgressorEvent) -> Void)?
    var onStateChange: ((ProgressorConnectionState) -> Void)?
    var onDiagnostic: ((ProgressorClientDiagnostic) -> Void)?
    private(set) var state: ProgressorConnectionState = .idle
    var deviceName: String? { "Preview gauge" }
    func connect() { state = .connected; onStateChange?(state) }
    func disconnect() { state = .idle; onStateChange?(state) }
    func send(_ command: ProgressorCommand) {}
    func startStreaming(cause: StreamStartCause) {}
    func emit(kg: Double, micros: UInt32) {
        onEvent?(.sample(ForceSample(kg: kg, deviceMicros: micros)))
    }
}

private final class RunnerCuePreviewActivity: RunnerActivityPublishing {
    var isRunning: Bool { false }
    func start(routineName: String, plannedReps: Int, setCount: Int,
               state: SessionActivity.ContentState) {}
    func update(_ state: SessionActivity.ContentState) async {}
    func end() async {}
}
#endif
