// SPDX-License-Identifier: MPL-2.0
// Original contributions Copyright 2026 Nuri Bruner.
#if DEBUG
import SwiftUI
import SwiftData

/// In-memory, interactive QA entry point. No sample logs or maxes reach the real store.
struct DebugSummaryPreview: View {
    @State private var preview = SummaryPreviewState()
    var onDone: () -> Void

    @State private var showLog = ProcessInfo.processInfo.arguments.contains("-previewLog")

    var body: some View {
        SessionSummaryView(template: preview.template, plan: preview.plan, reps: preview.reps,
            startedAt: preview.finishedAt.addingTimeInterval(-180), finishedAt: preview.finishedAt,
            didAnyWork: true, onDone: onDone)
            .sheet(isPresented: $showLog) {
                SessionLogSheet { showLog = false }
            }
            .environment(preview.store)
            .modelContainer(preview.container)
            .background { AppBackground() }
    }
}

/// Retain the context and its container together across SwiftUI view updates.
@MainActor
private final class SummaryPreviewState {
    let container: ModelContainer
    let template: SessionTemplate
    let plan: SessionPlan
    let reps: [RepSummary]
    let store: TemplateStore
    let finishedAt = Date()

    init() {
        let container = try! ModelContainer(for: SessionTemplate.self, WorkoutLog.self, MaxRecord.self,
            configurations: ModelConfiguration(isStoredInMemoryOnly: true, cloudKitDatabase: .none))
        self.container = container
        let grips = [
            GripSpec(edgeMM: 20, fingers: .four, position: .halfCrimp),
            GripSpec(edgeMM: 20, fingers: .frontThree, position: .halfCrimp),
            GripSpec(edgeMM: 20, fingers: .middleTwo, position: .openHand),
            GripSpec(edgeMM: 15, fingers: .four, position: .fullCrimp),
            GripSpec(edgeMM: 20, fingers: .four, position: .drag),
            GripSpec(edgeMM: 45, fingers: .four, position: .pinch)
        ]
        var draft = RoutineDraft.blank(named: "Daily routine · preview")
        draft.plan.sets = grips.map { SetPlan(grip: $0, repsPerSide: 1) }
        draft.plan.handMode = .bothHands
        plan = draft.plan
        template = SessionTemplate(draft: draft, sortIndex: 0)
        reps = grips.enumerated().map { index, grip in
            var rep = RepSummary()
            rep.setIndex = index
            rep.grip = grip
            rep.side = .both
            rep.heldSeconds = 10
            rep.peakKg = Double(12 + index)
            rep.avgKg = Double(10 + index)
            rep.outcome = .completed
            return rep
        }
        store = TemplateStore(context: container.mainContext,
            clock: DayClock(), settings: SettingsStore(), storageMode: .localOnly)
    }
}
#endif
