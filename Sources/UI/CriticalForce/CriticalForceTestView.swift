// SPDX-License-Identifier: MPL-2.0
// Original contributions Copyright 2026 Nuri Bruner.

import SwiftData
import SwiftUI

/// What opens the test: a grip and a hand, fixed at the tap.
struct CriticalForceTestRequest: Identifiable {
    let grip: GripSpec
    let side: Side
    let id = UUID()
}

/// The critical force test, start to saved. A full-screen cover like the max test: the
/// phone is on a bench and both hands are on the edge.
///
/// Four screens in one place. SETUP (grip, hand, body weight once), the TEST (one
/// countdown, the trace, the plateau forming), the RESULT, and the words for a test that
/// ended without one. The test never pauses; see `CriticalForceTest`.
struct CriticalForceTestView: View {
    @Environment(DeviceStore.self) private var device
    @Environment(TemplateStore.self) private var templates
    @Environment(SettingsStore.self) private var settings
    @Environment(\.dismiss) private var dismiss
    @Environment(\.weightUnit) private var weightUnit
    @Environment(\.scenePhase) private var scenePhase
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @Environment(\.dynamicTypeSize) private var typeSize

    @Query(sort: [SortDescriptor(\CriticalForceRecord.recordedAt)])
    private var records: [CriticalForceRecord]

    private enum Stage: Equatable {
        case setup
        case testing
        case result(CriticalForceResult, trace: Data)
        case ended(String)
    }

    @State private var stage: Stage = .setup
    @State private var grip: GripSpec
    @State private var side: Side
    @State private var editingGrip = false
    /// The first test asks; afterwards the value lives in Settings.
    @State private var bodyWeightDraft: Double = 70
    @State private var session = CriticalForceSession()
    @State private var alsoSaveMax = true
    @State private var saveFailed = false
    @State private var startTick = 0
    @State private var savedTick = 0
    /// The open region's top edge on screen, where the phase wash fades out.
    @State private var openTop: CGFloat = 0

    init(grip: GripSpec, side: Side = .both) {
        _grip = State(initialValue: grip)
        _side = State(initialValue: side)
    }

    var body: some View {
        Group {
            switch stage {
            case .testing: testingScreen
            case .result(let result, _): resultScreen(result)
            case .setup, .ended: formScreen
            }
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        // The field under everything, edge to edge: a cover's own backdrop is white.
        .background { AppBackground() }
        .interactiveDismissDisabled(stage == .testing)
        .sensoryFeedback(.impact(weight: .medium, intensity: 0.7), trigger: startTick)
        .sensoryFeedback(.success, trigger: savedTick)
        .keepsScreenAwake()
        .onAppear {
            if let kg = settings.bodyWeightKg { bodyWeightDraft = kg }
            #if DEBUG
            if ProcessInfo.processInfo.arguments.contains("-startCriticalForce"),
               !device.state.isConnected { device.connect() }
            #endif
        }
        .onChange(of: session.phase) { _, phase in finishIfDone(phase) }
        .onChange(of: scenePhase) { _, phase in
            guard stage == .testing else { return }
            if phase == .active {
                // Bin what the radio buffered while away: it cannot be DRAWN. The test's
                // own readings are untouched; see `DeviceStore.dropStaleTrace`.
                device.dropStaleTrace()
            } else if BackgroundPausePolicy.pausesOnLeavingForeground(
                isBackground: phase == .background,
                isConnected: device.state.isConnected,
                sustainsBackgroundStreaming: device.gaugeCapabilities.sustainsBackgroundStreaming) {
                // The runner's own rule: a connected gauge keeps the app alive in the
                // background, so the test runs on (the cues still sound). Only when the
                // readings are about to stop does the test end.
                session.interrupt(.leftApp)
            }
        }
        .onChange(of: device.state.isConnected, initial: true) { _, connected in
            if !connected, stage == .testing { session.interrupt(.lostGauge) }
            #if DEBUG
            // Headless: `simctl` cannot tap Start. See `-previewCriticalForce` on Today.
            if connected, stage == .setup,
               ProcessInfo.processInfo.arguments.contains("-startCriticalForce") {
                start()
            }
            #endif
        }
        .onDisappear { teardown() }
    }

    // MARK: - Screens

    /// Setup and the words for a test with no result: a form, like every other editor.
    private var formScreen: some View {
        NavigationStack {
            ScrollView {
                VStack(spacing: 18) {
                    switch stage {
                    case .ended(let message): ended(message)
                    default: setup
                    }
                }
                .padding(.top, 12)
                .padding(.horizontal, Metrics.hPadding)
                .padding(.bottom, Metrics.spacing)
                .frame(maxWidth: Metrics.maxContentWidth)
                .frame(maxWidth: .infinity)
            }
            .scrollBounceBehavior(.basedOnSize)
            .background { AppBackground() }
            .safeAreaInset(edge: .bottom) {
                formDock
                    .padding(.horizontal, Metrics.hPadding)
                    .padding(.bottom, 8)
                    .frame(maxWidth: Metrics.maxContentWidth)
                    .frame(maxWidth: .infinity)
            }
            .navigationTitle("Critical force")
            .navigationBarTitleDisplayMode(.inline)
            .navigationSubtitle(subtitle)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    if stage == .setup {
                        Button("Cancel") { dismiss() }
                            .accessibilityIdentifier("cf.cancel")
                    }
                }
            }
        }
    }

    /// **The test is the runner's screen**: the trace IS the screen, the numbers float on
    /// Liquid Glass above it, the pulls ride in their own glass pill, and the one control
    /// sits in a glass dock. The same parts as `RunnerView`'s stacked layout
    /// (`LiveTrace` lit, `PhaseWash`, `RunnerGlass`), so the two cannot drift apart.
    private var testingScreen: some View {
        fullScreen(washTint: CriticalForceTint.of(session.phase)) {
            CriticalForcePanel(session: session, grip: grip, side: side)
            openRegion {
                LiveTrace(thresholdKg: session.phase == .armed ? CriticalForceRules.startKg : nil,
                          tint: CriticalForceTint.of(session.phase),
                          plot: ForceTraceView.PlotInsets(top: 12, bottom: 6, trailing: 8),
                          lit: true)
                    .accessibilityHidden(true)
            }
            CriticalForcePlateau(means: session.repMeans, total: session.proto.reps,
                                 current: session.phase.isRunning ? session.pullNumber : nil)
            testingDock
        }
    }

    /// The result on the same stage: the number on glass, the pulls that made it in the
    /// open, the decision in the dock.
    private func resultScreen(_ result: CriticalForceResult) -> some View {
        let summary = CriticalForceSummary(result, maxKg: templates.maxTable.max(grip: grip.key, side: side),
                                           bodyMassKg: settings.bodyWeightKg)
        return fullScreen(washTint: StatusTint.engaged) {
            CriticalForceHeadline(summary: summary)
                .padding(16)
                .frame(maxWidth: .infinity)
                .accessibleGlass(nil, in: RunnerGlass.surfaceShape)
                .runnerFloatingShadow()
            openRegion {
                CriticalForcePullChart(summary: summary)
                    .padding(.horizontal, Metrics.hPadding)
                    .padding(.vertical, 8)
            }
            resultDock(result)
        }
    }

    /// The shared frame: a column at the phone's width, the phase wash hanging from the
    /// top of the screen to the open region, one `GlassEffectContainer` so every glass
    /// surface renders in a single pass. Scrolls only when accessibility text needs it.
    private func fullScreen<Content: View>(washTint: Color,
                                           @ViewBuilder content: () -> Content) -> some View {
        // Container spacing BELOW the column's 12 pt gaps: at 24 Liquid Glass read the
        // pill and the dock as one surface and bridged them.
        let column = GlassEffectContainer(spacing: 4) {
            VStack(spacing: 12) { content() }
        }
        .padding(.horizontal, Metrics.hPadding)
        .padding(.top, 8)
        .padding(.bottom, Metrics.spacing)
        .frame(maxWidth: Metrics.maxContentWidth)
        .frame(maxWidth: .infinity)
        // A scroll view ONLY at accessibility sizes, as in the runner: at ordinary sizes
        // it fits, and iOS 26's scroll edge effect painted a pale band under the dock.
        return Group {
            if typeSize.isAccessibilitySize {
                GeometryReader { geometry in
                    ScrollView {
                        column.frame(minHeight: geometry.size.height)
                    }
                    .scrollBounceBehavior(.basedOnSize)
                }
            } else {
                column
            }
        }
        // Claim the whole screen, so the backgrounds' `ignoresSafeArea` has an edge to
        // extend from: sized to the column, the home-indicator strip stayed white.
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .background {
            PhaseWash(tint: washTint, length: openTop)
                .ignoresSafeArea()
        }
    }

    /// Where the curve runs in the clear, stretched to the screen's edges. Its top edge is
    /// where the phase wash fades out.
    private func openRegion<Content: View>(@ViewBuilder content: () -> Content) -> some View {
        let drawing = content()
        return Color.clear
            .frame(minHeight: 200, maxHeight: .infinity)
            .background { drawing.padding(.horizontal, -Metrics.hPadding) }
            .onGeometryChange(for: CGFloat.self) { $0.frame(in: .global).minY } action: { openTop = $0 }
    }

    // MARK: - Docks

    private var formDock: some View {
        VStack(spacing: 8) {
            switch stage {
            case .ended:
                DockTintedButton(String(localized: "Close"), tint: .graphite) { dismiss() }
            default:
                if !device.state.isConnected {
                    Text("Connect your gauge to test.")
                        .font(.system(.footnote, weight: .medium))
                        .foregroundStyle(Ink.secondary)
                        .padding(.top, 4)
                    GaugeConnectButton(connectTitle: String(localized: "Connect"))
                } else {
                    GaugeZeroButton(canTare: true)
                    DockTintedButton(String(localized: "Start"), systemImage: "play.fill", tint: .bleu) { start() }
                        .accessibilityIdentifier("cf.start")
                }
            }
        }
        .padding(8)
        .accessibleGlass(nil, in: RunnerGlass.surfaceShape)
        .runnerFloatingShadow()
        .accessibilityElement(children: .contain)
    }

    private var testingDock: some View {
        VStack(spacing: 8) {
            if session.phase == .armed {
                Text("The test starts the moment you pull. Pull as hard as you can.")
                    .font(.system(.footnote, weight: .medium))
                    .foregroundStyle(Ink.secondary)
                    .multilineTextAlignment(.center)
                    .fixedSize(horizontal: false, vertical: true)
                    .padding(.top, 6)
                DockButton(String(localized: "Back"), systemImage: "chevron.left") { backToSetup() }
                    .accessibilityIdentifier("cf.back")
            } else {
                CriticalForceStopButton(canKeep: session.canFinishEarly) { session.stop() }
            }
        }
        .padding(8)
        .accessibleGlass(nil, in: RunnerGlass.surfaceShape)
        .runnerFloatingShadow()
        .accessibilityElement(children: .contain)
        .accessibilityIdentifier("cf.dock")
    }

    private func resultDock(_ result: CriticalForceResult) -> some View {
        VStack(spacing: 8) {
            if beatsMax(result) {
                let maxKg = templates.maxTable.exact(grip: grip.key, side: side)
                Toggle(isOn: $alsoSaveMax) {
                    VStack(alignment: .leading, spacing: 2) {
                        Text("Save \(weightUnit.text(result.peakKg)) as your max")
                            .font(.system(.subheadline, weight: .semibold))
                        Text(maxKg.map { String(localized: "Your hardest pull beat your max of \(weightUnit.text($0)).") }
                             ?? String(localized: "No max on this grip and hand yet."))
                            .font(.system(.footnote))
                            .foregroundStyle(Ink.secondary)
                    }
                }
                .tint(Accent.bleu)
                .padding(.horizontal, 12)
                .padding(.vertical, 6)
                .accessibilityIdentifier("cf.alsoMax")
            }
            if saveFailed {
                Text("Couldn’t save. Your result is still here — try again.")
                    .font(.footnote)
                    .foregroundStyle(Accent.alarm)
                    .multilineTextAlignment(.center)
            }
            AdaptiveActionRow(spacing: 8) {
                DockButton(String(localized: "Don’t save")) { dismiss() }
                DockTintedButton(String(localized: "Save"), systemImage: "checkmark", tint: .graphite) { save(result) }
                    .accessibilityIdentifier("cf.save")
            }
        }
        .padding(8)
        .accessibleGlass(nil, in: RunnerGlass.surfaceShape)
        .runnerFloatingShadow()
        .accessibilityElement(children: .contain)
    }

    private var subtitle: String {
        "\(grip.displayName) · \(side.name)"
    }

    // MARK: - Setup

    @ViewBuilder
    private var setup: some View {
        VStack(alignment: .leading, spacing: 10) {
            CapsLabel(String(localized: "THE TEST"))
            Text("24 all-out pulls: 7 seconds on, 3 off, about 4 minutes. The clock never waits. Pull as hard as you can every time, and let go at the bell.")
                .font(.system(.body))
                .foregroundStyle(Ink.primary)
                .fixedSize(horizontal: false, vertical: true)
            Text("Warm up first. Test on the same grip, hand and arm position each time so results compare, and leave a few weeks between tests.")
                .font(.system(.footnote))
                .foregroundStyle(Ink.secondary)
                .fixedSize(horizontal: false, vertical: true)
        }
        .frame(maxWidth: .infinity, alignment: .leading)

        gripRow
        if editingGrip { gripEditor }

        VStack(alignment: .leading, spacing: 8) {
            CapsLabel(String(localized: "HAND"))
            Picker("Hand", selection: $side) {
                Text("Left").tag(Side.left)
                Text("Right").tag(Side.right)
                Text("Both").tag(Side.both)
            }
            .pickerStyle(.segmented)
            .accessibilityIdentifier("cf.hand")
        }

        bodyWeightRow
        lastTestLine
    }

    private var gripRow: some View {
        Button {
            withAnimation(Motion.state(reduceMotion)) { editingGrip.toggle() }
        } label: {
            HStack(spacing: 12) {
                FingerGlyph(fingers: grip.fingers, position: grip.position, dot: 8, gap: 3)
                    .accessibilityHidden(true)
                VStack(alignment: .leading, spacing: 2) {
                    CapsLabel(String(localized: "GRIP"))
                    Text(grip.displayName)
                        .font(.system(.headline))
                        .foregroundStyle(Ink.primary)
                }
                Spacer(minLength: 8)
                Text(editingGrip ? "Done" : "Change")
                    .font(.system(.subheadline, weight: .semibold))
                    .foregroundStyle(Accent.graphite)
            }
            .padding(14)
            .frame(minHeight: 60)
            .background(Ink.primary.opacity(0.05),
                        in: RoundedRectangle(cornerRadius: Metrics.radiusInner, style: .continuous))
            .contentShape(.rect(cornerRadius: Metrics.radiusInner))
        }
        .buttonStyle(PressFeedbackButtonStyle())
        .accessibilityIdentifier("cf.grip")
    }

    /// The same three controls as a new max: a grip is a value, built here, never picked
    /// from a library.
    private var gripEditor: some View {
        VStack(alignment: .leading, spacing: 18) {
            if !gripChoices.isEmpty {
                ScrollView(.horizontal) {
                    HStack(spacing: 8) {
                        ForEach(gripChoices, id: \.key) { candidate in
                            let selected = candidate.key == grip.key
                            Button {
                                withAnimation(Motion.state(reduceMotion)) { grip = candidate }
                            } label: {
                                HStack(spacing: 8) {
                                    FingerGlyph(fingers: candidate.fingers, position: candidate.position,
                                                dot: 5.5, gap: 2)
                                    Text(candidate.shortName)
                                        .font(.system(.caption, weight: .semibold))
                                        .fixedSize()
                                }
                                .foregroundStyle(selected ? Ink.primary : Ink.secondary)
                                .padding(.horizontal, 12)
                                .frame(minHeight: 44)
                                .background(Ink.primary.opacity(selected ? 0.10 : 0.04),
                                            in: RoundedRectangle(cornerRadius: Metrics.radiusInner, style: .continuous))
                                .contentShape(.rect(cornerRadius: Metrics.radiusInner))
                            }
                            .buttonStyle(PressFeedbackButtonStyle())
                            .accessibilityLabel(candidate.spoken)
                            .accessibilityAddTraits(selected ? [.isSelected] : [])
                        }
                    }
                    .padding(.vertical, 2)
                }
                .scrollIndicators(.hidden)
                .scrollBounceBehavior(.basedOnSize)
            }
            IntValueRow(title: String(localized: "Edge"), unit: String(localized: "mm"),
                        value: $grip.edgeMM, range: 4...45, limit: GripSpec.edgeRange,
                        presets: [6, 10, 20, 30])
            VStack(alignment: .leading, spacing: 8) {
                CapsLabel(String(localized: "FINGERS"))
                FingerPips(fingers: $grip.fingers, position: grip.position)
            }
            VStack(alignment: .leading, spacing: 8) {
                CapsLabel(String(localized: "POSITION"))
                PositionChipRow(selection: $grip.position)
            }
        }
        .transition(.opacity)
    }

    /// Grips already tested come first, then the ones your routines train.
    private var gripChoices: [GripSpec] {
        var seen = Set<String>()
        var out: [GripSpec] = []
        for g in records.reversed().map(\.grip) + templates.recentGrips where seen.insert(g.key).inserted {
            out.append(g)
        }
        return out
    }

    @ViewBuilder
    private var bodyWeightRow: some View {
        if let kg = settings.bodyWeightKg {
            HStack {
                CapsLabel(String(localized: "BODY WEIGHT"))
                Spacer()
                Text(weightUnit.text(kg))
                    .font(.system(.subheadline, weight: .medium))
                    .monospacedDigit()
                    .foregroundStyle(Ink.secondary)
            }
            .accessibilityElement(children: .combine)
            .accessibilityHint("Change it in Settings")
        } else {
            VStack(alignment: .leading, spacing: 6) {
                ValueRow(title: String(localized: "Body weight"), unit: weightUnit.symbol,
                         value: weightUnit.binding($bodyWeightDraft),
                         range: weightUnit.sliderRangeFromKg(40...110), limit: weightUnit.rangeFromKg(25...250),
                         step: 0.5, decimals: 1)
                Text("Asked once. Critical force as a share of body weight is the best-studied way to compare. Change it later in Settings.")
                    .font(.system(.footnote))
                    .foregroundStyle(Ink.tertiary)
                    .fixedSize(horizontal: false, vertical: true)
            }
        }
    }

    @ViewBuilder
    private var lastTestLine: some View {
        let key = MaxTable.key(grip: grip.key, side: side)
        if let last = records.last(where: { $0.testKey == key }) {
            Text("Last test: \(weightUnit.text(last.criticalForceKg)), \(last.recordedAt.formatted(.relative(presentation: .named)))")
                .font(.system(.footnote))
                .foregroundStyle(Ink.tertiary)
                .frame(maxWidth: .infinity, alignment: .leading)
        }
    }

    /// Nothing has been measured while armed, so leaving is free.
    private func backToSetup() {
        stopStream(cause: .userStopped)
        session = CriticalForceSession()
        withAnimation(Motion.state(reduceMotion)) { stage = .setup }
    }

    /// The exact hand's max, not the both-hands fallback: a one-handed test beating a
    /// two-handed max says nothing.
    private func beatsMax(_ result: CriticalForceResult) -> Bool {
        guard let exact = templates.maxTable.exact(grip: grip.key, side: side) else {
            return result.peakKg >= MaxAttempt.releaseKg
        }
        return result.peakKg > exact
    }

    private func ended(_ message: String) -> some View {
        VStack(spacing: 18) {
            Spacer(minLength: 60)
            Image(systemName: "stopwatch")
                .font(.system(.largeTitle))
                .foregroundStyle(Ink.tertiary)
                .accessibilityHidden(true)
            Text(message)
                .font(.system(.body))
                .foregroundStyle(Ink.primary)
                .multilineTextAlignment(.center)
                .fixedSize(horizontal: false, vertical: true)
        }
    }

    // MARK: - Flow

    private func start() {
        guard device.state.isConnected else { return }
        if settings.bodyWeightKg == nil { settings.bodyWeightKg = bodyWeightDraft }
        session.arm()
        device.setMockProfile(.allOut)
        device.resetPeak()
        device.onTracePoint = { [session] point in session.receive(kg: point.kg, at: point.t) }
        device.startStreaming(cause: .manualMeasurement)
        withAnimation(Motion.state(reduceMotion)) { stage = .testing }
        startTick += 1
    }

    private func finishIfDone(_ phase: CriticalForceTest.Phase) {
        guard stage == .testing else { return }
        switch phase {
        case .finished:
            stopStream(cause: .measurementComplete)
            guard let (outcome, trace) = session.outcome() else { return }
            withAnimation(Motion.state(reduceMotion)) {
                switch outcome {
                case .success(let result): stage = .result(result, trace: trace)
                case .failure(let failure): stage = .ended(Self.words(for: failure))
                }
            }
        case .voided(let reason):
            stopStream(cause: .userStopped)
            withAnimation(Motion.state(reduceMotion)) { stage = .ended(Self.words(for: reason)) }
        default:
            break
        }
    }

    private func save(_ result: CriticalForceResult) {
        guard case .result(_, let trace) = stage else { return }
        let alsoMax: TemplateStore.MaxSave? = beatsMax(result) && alsoSaveMax
            ? .init(grip: grip, side: side, kg: result.peakKg, source: .measured) : nil
        guard templates.recordCriticalForce(result, trace: trace, grip: grip, side: side,
                                            bodyMassKg: settings.bodyWeightKg, alsoMax: alsoMax) != nil else {
            saveFailed = true
            return
        }
        savedTick += 1
        dismiss()
    }

    private func stopStream(cause: StreamStopCause) {
        device.onTracePoint = nil
        if device.isStreaming { device.stopStreaming(cause: cause) }
        device.setMockProfile(DeviceStore.mockProfileRequestedAtLaunch)
        session.end()
    }

    private func teardown() {
        if stage == .testing { session.interrupt(.leftApp) }
        stopStream(cause: .screenClosed)
    }

    static func words(for reason: CriticalForceTest.VoidReason) -> String {
        switch reason {
        case .tooFewReps:
            String(localized: "Stopped before pull 16, before your force had levelled off, so there’s no result. Rest at least half an hour before trying again, or test another day.")
        case .lostGauge:
            String(localized: "The gauge dropped before pull 16, so there’s no result. A paused test measures something else, because the reserve refills while you wait. Rest at least half an hour, then test again.")
        case .leftApp:
            String(localized: "The test stopped when you left the app before pull 16, so there’s no result. Rest at least half an hour, then test again.")
        }
    }

    static func words(for failure: CriticalForceFailure) -> String {
        switch failure {
        case .tooFewReps:
            String(localized: "Stopped before pull 16, so there’s no result.")
        case .tooLittleData:
            String(localized: "The gauge’s readings had too many gaps in the last pulls to give a result. Keep the phone close to the gauge next time.")
        case .noPull:
            String(localized: "No pulls were recorded. Check the gauge is zeroed and that the pull goes through it.")
        }
    }
}

extension CriticalForceTest.Phase {
    var isRunning: Bool {
        switch self {
        case .pulling, .resting, .settling: true
        default: false
        }
    }
}

// MARK: - Live leaves

/// One tint ladder for the test, the runner's: amber while it waits on you, bleu while a
/// pull's clock runs, steel at rest.
enum CriticalForceTint {
    static func of(_ phase: CriticalForceTest.Phase) -> Color {
        switch phase {
        case .armed: StatusTint.armed
        case .pulling: StatusTint.engaged
        default: StatusTint.calm
        }
    }
}

/// **The information panel**, on glass over the graph like the runner's: the word for
/// what to do, then live force and the clock side by side at equal weight, exactly the
/// runner's hero, then where you are in the 24. The clock reads the session's
/// once-a-second values; the kilograms live in the runner's own leaf.
struct CriticalForcePanel: View {
    let session: CriticalForceSession
    let grip: GripSpec
    let side: Side

    @Environment(\.accessibilityReduceMotion) private var reduceMotion

    /// The runner's own hero proportions, so the two screens match.
    @ScaledMetric(relativeTo: .largeTitle) private var heroSize: CGFloat = 76
    @ScaledMetric(relativeTo: .title3) private var unitSize: CGFloat = 22
    @ScaledMetric(relativeTo: .largeTitle) private var promptSize: CGFloat = 34

    var body: some View {
        VStack(spacing: 12) {
            CapsLabel(side == .both ? grip.shortName : "\(grip.shortName) · \(side.name)")
            Text(word)
                .font(.system(size: promptSize, weight: .heavy))
                .foregroundStyle(CriticalForceTint.of(session.phase))
                .lineLimit(1)
                .minimumScaleFactor(0.6)
                .contentTransition(.identity)
            // **The runner's hero: force and clock side by side, equal weight.** The
            // measurement snaps (`LiveForceReadout`); the clock rolls (`RollingNumeral`).
            HStack(alignment: .lastTextBaseline, spacing: 18) {
                LiveForceReadout(tint: CriticalForceTint.of(session.phase) == StatusTint.engaged
                                        ? StatusTint.engaged : Ink.primary,
                                 size: heroSize, unitSize: unitSize)
                HStack(alignment: .lastTextBaseline, spacing: 4) {
                    RollingNumeral(value: clock, countsDown: true, rolls: !reduceMotion,
                                   shift: heroSize * 0.25) { value in
                        Text(value).lineLimit(1).minimumScaleFactor(0.5)
                    }
                    .font(.system(size: heroSize, weight: .thin))
                    .displayTracking(heroSize)
                    .monospacedDigit()
                    .foregroundStyle(Ink.primary)
                    Text("s")
                        .font(.system(size: unitSize))
                        .foregroundStyle(Ink.tertiary)
                }
            }
            .frame(maxWidth: .infinity)
            .accessibilityHidden(true)
            CapsLabel(countLine, size: 14)
        }
        .padding(.horizontal, 16)
        .padding(.top, 14)
        .padding(.bottom, 12)
        .frame(maxWidth: .infinity)
        .accessibleGlass(nil, in: RunnerGlass.surfaceShape)
        .runnerFloatingShadow()
        .accessibilityElement(children: .ignore)
        .accessibilityLabel(seconds.map { "\(word), \($0) seconds. \(countLine)" } ?? "\(word). \(countLine)")
        .accessibilityIdentifier("cf.panel")
    }

    /// Before the first pull the clock shows the full pull it is waiting to start.
    private var clock: String {
        "\(seconds ?? Int(session.proto.workSeconds))"
    }

    private var word: String {
        switch session.phase {
        case .armed: String(localized: "PULL TO START")
        case .pulling: String(localized: "PULL")
        case .resting: String(localized: "REST")
        case .settling, .finished: String(localized: "DONE")
        case .voided: String(localized: "STOPPED")
        }
    }

    private var seconds: Int? {
        switch session.phase {
        case .pulling, .resting: session.secondsLeft
        default: nil
        }
    }

    private var countLine: String {
        String(localized: "Pull \(min(session.pullNumber, session.proto.reps)) of \(session.proto.reps)")
    }
}

/// **The 24 pulls, in a pill of their own.** One column per pull, filling in as each
/// window closes, so you watch the plateau form. Squared columns, a CHART, so they never
/// read as the capsule fingers.
struct CriticalForcePlateau: View {
    let means: [Double?]
    let total: Int
    /// 1-based pull in progress, outlined.
    let current: Int?

    var body: some View {
        let top = max(1, means.compactMap { $0 }.max() ?? 1)
        HStack(alignment: .bottom, spacing: 3) {
            ForEach(0..<total, id: \.self) { index in
                let mean = index < means.count ? means[index] : nil
                RoundedRectangle(cornerRadius: 2, style: .continuous)
                    .fill(mean == nil ? Ink.primary.opacity(0.08) : StatusTint.engaged.opacity(0.8))
                    .overlay {
                        if current == index + 1 {
                            RoundedRectangle(cornerRadius: 2, style: .continuous)
                                .strokeBorder(Ink.secondary, lineWidth: 1.5)
                        }
                    }
                    .frame(maxWidth: .infinity)
                    .frame(height: max(4, 36 * CGFloat((mean ?? top * 0.14) / top)))
            }
        }
        .frame(height: 36, alignment: .bottom)
        .padding(.horizontal, 22)
        .padding(.vertical, 14)
        .accessibleGlass(nil, in: .capsule)
        .runnerFloatingShadow()
        .accessibilityElement(children: .ignore)
        .accessibilityLabel("Average per pull")
        .accessibilityValue("\(means.count) of \(total) pulls done")
        .accessibilityIdentifier("cf.plateau")
    }
}

/// Ending the test is a hold, like ending a session. Before pull 16 it throws the test
/// away, and the label says so; after, it keeps what was run.
private struct CriticalForceStopButton: View {
    let canKeep: Bool
    let action: () -> Void

    var body: some View {
        HoldToConfirm(cancel: .leavingBounds(slop: 24),
                      accessibilityLabel: canKeep ? "Finish test now" : "Stop test",
                      accessibilityHint: canKeep ? "Press and hold to finish with the pulls done so far."
                                                 : "Press and hold to stop. Before pull 16 there is no result.",
                      action: action) { isHolding, progress in
            let tint = canKeep ? Accent.graphite : Accent.alarm
            Text(isHolding ? "Keep holding…" : (canKeep ? "Hold to finish now" : "Hold to stop"))
                .foregroundStyle(tint)
                .font(.system(.subheadline, weight: .semibold))
                .contentTransition(.identity)
                .actionLabelLayout(fullWidth: true)
                .background { HoldFill(progress: progress, tint: tint, track: 0.16, fill: 0.42) }
        }
    }
}
