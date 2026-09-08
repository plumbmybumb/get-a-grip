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
/// stops at the requested phase; only the fake gauge continues drawing a flat trace.
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
            .environment(preview.tour)
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
    let tour = TourController()
    let settings = SettingsStore()
    private let client = RunnerCuePreviewClient()
    private var pump: Task<Void, Never>?

    init(phase: RunnerCuePreviewPhase, timerOnly: Bool, paused: Bool) {
        let container = try! ModelContainer(for: SessionTemplate.self, WorkoutLog.self, MaxRecord.self,
            configurations: ModelConfiguration(isStoredInMemoryOnly: true, cloudKitDatabase: .none))
        self.container = container
        var draft = RoutineDraft.blank(named: "Training cue preview")
        draft.plan.handMode = .alternateEachRep
        draft.plan.sets = [SetPlan(grip: GripSpec(), repsPerSide: 3)]
        let working = phase == .working || phase == .warning
        draft.plan.holdSeconds = working ? 10 : 2
        draft.plan.restSeconds = 20
        draft.plan.leadInSeconds = 0
        draft.plan.waitForReleaseBeforeRest = true
        template = SessionTemplate(draft: draft, sortIndex: 0)
        store = TemplateStore(context: container.mainContext, clock: DayClock(),
                              settings: settings, storageMode: .localOnly)
        device = DeviceStore(client: client)
        device.connect()
        session = RunnerSession(template: template, device: device, timerOnly: timerOnly,
                                liveActivity: RunnerCuePreviewActivity())
        session.begin()
        if timerOnly {
            if !working { session.send(.skipRep) }
        } else {
            for index in 0...(working ? 3 : 24) {
                client.emit(kg: 12, micros: UInt32(index * 100_000))
            }
            if phase == .warning { client.emit(kg: 0, micros: 400_000) }
            if phase == .resting { client.emit(kg: 0, micros: 2_500_000) }
        }
        if paused { session.send(.pause) }
        // Stop the ticker and remove its sample callback, preserving the snapshot.
        // The independent fixture gauge below never advances the frozen runner.
        session.end()
        if !timerOnly {
            device.startStreaming(cause: .manualMeasurement)
            let kg = phase == .releasing || phase == .working ? 12.0 : 0.0
            pump = Task { [weak self] in
                var micros: UInt32 = 2_600_000
                while !Task.isCancelled {
                    guard let self else { return }
                    self.client.emit(kg: kg, micros: micros)
                    micros &+= 100_000
                    do { try await Task.sleep(for: .milliseconds(100)) }
                    catch { return }
                }
            }
        }
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
