// SPDX-License-Identifier: MPL-2.0
// Original contributions Copyright 2026 Nuri Bruner.

import SwiftData
import SwiftUI

/// What opens the test: a grip and how the hands take it, fixed at the tap.
struct CriticalForceTestRequest: Identifiable {
    let grip: GripSpec
    let hands: CriticalForceHands
    let id = UUID()
}

/// One hand's finished test, before it is saved.
struct CriticalForceHandResult: Equatable {
    let side: Side
    let result: CriticalForceResult
    let trace: Data
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
        case result
        case ended(String)
    }

    /// The setup's choice, in the routine builder's words. See `CriticalForceHands`.
    private enum HandChoice: Hashable { case oneAtATime, bothHands, single }

    @State private var stage: Stage = .setup
    @State private var grip: GripSpec
    @State private var handChoice: HandChoice
    /// Which hand starts (one at a time), or the hand (one hand).
    @State private var pickedSide: Side
    /// Index into `hands.sides` of the hand on the gauge now.
    @State private var handIndex = 0
    /// Between hands: the first hand is done and the next is NOT armed until you tap
    /// Start. Moving the gauge or block to the other hand loads it, and an armed test
    /// would take that as the first pull (Nuri, 2026-09-25).
    @State private var awaitingNextHand = false
    @State private var results: [CriticalForceHandResult] = []
    /// What happened to a hand that produced no result, said on the result screen.
    @State private var notes: [String] = []
    /// Which hand's pulls the result screen is showing.
    @State private var shownSide: Side = .left
    @State private var editingGrip = false
    @State private var showingAbout = false
    @State private var session = CriticalForceSession()
    @State private var alsoSaveMaxes = true
    @State private var saveFailed = false
    @State private var startTick = 0
    @State private var savedTick = 0
    /// The open region's top edge on screen, where the phase wash fades out.
    @State private var openTop: CGFloat = 0

    init(grip: GripSpec, hands: CriticalForceHands = .oneAtATime(first: .left)) {
        _grip = State(initialValue: grip)
        switch hands {
        case .oneAtATime(let first):
            _handChoice = State(initialValue: .oneAtATime)
            _pickedSide = State(initialValue: first == .right ? .right : .left)
        case .bothHands:
            _handChoice = State(initialValue: .bothHands)
            _pickedSide = State(initialValue: .left)
        case .single(let side):
            _handChoice = State(initialValue: .single)
            _pickedSide = State(initialValue: side == .right ? .right : .left)
        }
    }

    private var hands: CriticalForceHands {
        switch handChoice {
        case .oneAtATime: .oneAtATime(first: pickedSide)
        case .bothHands: .bothHands
        case .single: .single(pickedSide)
        }
    }

    /// The hand on the gauge now.
    private var side: Side { hands.sides[min(handIndex, hands.sides.count - 1)] }

    var body: some View {
        Group {
            switch stage {
            case .testing: testingScreen
            case .result: resultScreen
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
            #if DEBUG
            if ProcessInfo.processInfo.arguments.contains("-startCriticalForce"),
               !device.state.isConnected { device.connect() }
            #endif
        }
        .onChange(of: session.phase) { _, phase in finishIfDone(phase) }
        .onChange(of: scenePhase) { _, phase in
            // Between hands nothing is measuring, so leaving the app costs nothing.
            guard stage == .testing, !awaitingNextHand else { return }
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
            if !connected, stage == .testing, !awaitingNextHand { session.interrupt(.lostGauge) }
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
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    if stage == .setup {
                        Button("Cancel") { dismiss() }
                            .accessibilityIdentifier("cf.cancel")
                    }
                }
                ToolbarItem(placement: .topBarTrailing) {
                    if stage == .setup {
                        Button("About the test", systemImage: "info.circle") { showingAbout = true }
                            .labelStyle(.iconOnly)
                            .tint(Accent.graphite)
                            .accessibilityIdentifier("cf.about")
                    }
                }
            }
            .sheet(isPresented: $showingAbout) { CriticalForceAboutSheet() }
        }
    }

    /// **The test is the runner's screen**: the trace IS the screen, the numbers float on
    /// Liquid Glass above it, the pulls ride in their own glass pill, and the one control
    /// sits in a glass dock. The same parts as `RunnerView`'s stacked layout
    /// (`LiveTrace` lit, `PhaseWash`, `RunnerGlass`), so the two cannot drift apart.
    private var testingScreen: some View {
        fullScreen(washTint: CriticalForceTint.of(session.phase)) {
            CriticalForcePanel(session: session, grip: grip, side: side,
                               nextHandWord: awaitingNextHand
                                   ? String(localized: "\(side.prompt) HAND NEXT") : nil)
            openRegion {
                LiveTrace(thresholdKg: session.phase == .armed ? CriticalForceRules.startKg : nil,
                          tint: CriticalForceTint.of(session.phase),
                          plot: ForceTraceView.PlotInsets(top: 12, bottom: 6, trailing: 8),
                          lit: true)
                    .accessibilityHidden(true)
            }
            // Between hands the pill is the NEXT hand's: empty, nothing in progress.
            CriticalForcePlateau(means: awaitingNextHand ? [] : session.repMeans,
                                 total: session.proto.reps,
                                 current: !awaitingNextHand && session.phase.isRunning ? session.pullNumber : nil)
            testingDock
        }
    }

    /// The result on the same stage: the numbers on glass, the pulls that made them in
    /// the open, the decision in the dock. With two hands the two numbers ARE the switch:
    /// tap a hand to see its pulls.
    private var resultScreen: some View {
        let summaries = results.map { hand in
            (side: hand.side,
             summary: CriticalForceSummary(hand.result, maxKg: templates.maxTable.max(grip: grip.key, side: hand.side),
                                           bodyMassKg: settings.bodyWeightKg))
        }
        let shown = summaries.first { $0.side == shownSide } ?? summaries.first
        return fullScreen(washTint: StatusTint.engaged) {
            Group {
                if summaries.count > 1 {
                    VStack(spacing: 14) {
                        CapsLabel(String(localized: "CRITICAL FORCE"))
                        HStack(spacing: 8) {
                            ForEach(summaries, id: \.side) { item in
                                CriticalForceHandColumn(side: item.side, summary: item.summary,
                                                        selected: item.side == shown?.side) {
                                    withAnimation(Motion.state(reduceMotion)) { shownSide = item.side }
                                }
                            }
                        }
                        if let shown { CriticalForceStatsRow(summary: shown.summary) }
                    }
                } else if let shown {
                    CriticalForceHeadline(summary: shown.summary)
                }
            }
            .padding(16)
            .frame(maxWidth: .infinity)
            .accessibleGlass(nil, in: RunnerGlass.surfaceShape)
            .runnerFloatingShadow()
            openRegion {
                if let shown {
                    CriticalForcePullChart(summary: shown.summary)
                        .padding(.horizontal, Metrics.hPadding)
                        .padding(.vertical, 8)
                        .id(shown.side)
                }
            }
            resultDock
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
                    // The max test's dock, so the two measurement screens share one shape.
                    AdaptiveActionRow(spacing: 8) {
                        GaugeTareDockButton()
                        DockTintedButton(String(localized: "Start"), systemImage: "play.fill", tint: .bleu) { start() }
                            .accessibilityIdentifier("cf.start")
                    }
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
            if awaitingNextHand {
                Text("\(hands.sides[0].name) hand done. Get set on your \(side.name.lowercased()) hand, then start.")
                    .font(.system(.footnote, weight: .medium))
                    .foregroundStyle(Ink.secondary)
                    .multilineTextAlignment(.center)
                    .fixedSize(horizontal: false, vertical: true)
                    .padding(.top, 6)
                AdaptiveActionRow(spacing: 8) {
                    GaugeTareDockButton()
                    DockTintedButton(String(localized: "Start \(side.name.lowercased()) hand"),
                                     systemImage: "play.fill", tint: .bleu,
                                     enabled: device.state.isConnected) { startNextHand() }
                        .accessibilityIdentifier("cf.startNextHand")
                }
                DockButton(String(localized: "Finish with \(hands.sides[0].name.lowercased()) hand only")) {
                    finishEarlyBetweenHands()
                }
                .accessibilityIdentifier("cf.finishFirstHand")
            } else if session.phase == .armed {
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

    /// Hands whose hardest pull beats that hand's own max on file (or has none).
    private var maxOffers: [TemplateStore.MaxSave] {
        results.compactMap { hand in
            beatsMax(hand.result, side: hand.side)
                ? .init(grip: grip, side: hand.side, kg: hand.result.peakKg, source: .measured) : nil
        }
    }

    private var resultDock: some View {
        VStack(spacing: 8) {
            if !maxOffers.isEmpty {
                Toggle(isOn: $alsoSaveMaxes) {
                    VStack(alignment: .leading, spacing: 2) {
                        Text(maxOffers.count == 1 ? "Save your hardest pull as a max" : "Save your hardest pulls as maxes")
                            .font(.system(.subheadline, weight: .semibold))
                        Text(maxOffers.map(offerLine).joined(separator: "\n"))
                            .font(.system(.footnote))
                            .foregroundStyle(Ink.secondary)
                            .fixedSize(horizontal: false, vertical: true)
                    }
                }
                .tint(Accent.bleu)
                .padding(.horizontal, 12)
                .padding(.vertical, 6)
                .accessibilityIdentifier("cf.alsoMax")
            }
            ForEach(notes, id: \.self) { note in
                Text(note)
                    .font(.system(.footnote))
                    .foregroundStyle(StatusTint.armed)
                    .multilineTextAlignment(.center)
                    .fixedSize(horizontal: false, vertical: true)
                    .padding(.horizontal, 12)
            }
            if saveFailed {
                Text("Couldn’t save. Your result is still here — try again.")
                    .font(.footnote)
                    .foregroundStyle(Accent.alarm)
                    .multilineTextAlignment(.center)
            }
            AdaptiveActionRow(spacing: 8) {
                DockButton(String(localized: "Don’t save")) { dismiss() }
                DockTintedButton(String(localized: "Save"), systemImage: "checkmark", tint: .graphite) { save() }
                    .accessibilityIdentifier("cf.save")
            }
        }
        .padding(8)
        .accessibleGlass(nil, in: RunnerGlass.surfaceShape)
        .runnerFloatingShadow()
        .accessibilityElement(children: .contain)
    }

    private func offerLine(_ offer: TemplateStore.MaxSave) -> String {
        let hand = offer.side == .both ? String(localized: "Both hands") : offer.side.name
        if let old = templates.maxTable.exact(grip: grip.key, side: offer.side) {
            return String(localized: "\(hand): \(weightUnit.text(offer.kg)), up from \(weightUnit.text(old))")
        }
        return String(localized: "\(hand): \(weightUnit.text(offer.kg)), the first max on this grip")
    }

    // MARK: - Setup

    @ViewBuilder
    private var setup: some View {
        // **Glanceable, not a manual** (Nuri: "look at all that text"). The protocol is
        // one line, the instruction one more; everything else is behind the ⓘ.
        VStack(alignment: .leading, spacing: 6) {
            Text("24 pulls · 7 s on · 3 s off")
                .font(.system(.title2, weight: .semibold))
                .foregroundStyle(Ink.primary)
            Text("Pull as hard as you can. Let go at the bell.")
                .font(.system(.subheadline))
                .foregroundStyle(Ink.secondary)
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .padding(.bottom, 4)

        gripRow
        if editingGrip { gripEditor }

        handPicker

        if settings.bodyWeightKg == nil { bodyWeightRow }
        lastTestLine
    }

    /// The routine builder's words for the hands, so "both" means one thing everywhere:
    /// both hands pulling together. One at a time is 24 pulls on one hand, then 24 on the
    /// other.
    private var handPicker: some View {
        VStack(alignment: .leading, spacing: 8) {
            HStack(alignment: .firstTextBaseline) {
                CapsLabel(String(localized: "HANDS"))
                Spacer(minLength: 8)
                // Which hand goes first (or which hand), as a quiet menu in the label's
                // row rather than a second segmented control.
                if handChoice != .bothHands {
                    Menu {
                        Picker("Hand", selection: $pickedSide) {
                            Text(handChoice == .oneAtATime ? "Left first" : "Left").tag(Side.left)
                            Text(handChoice == .oneAtATime ? "Right first" : "Right").tag(Side.right)
                        }
                    } label: {
                        HStack(spacing: 4) {
                            Text(sideMenuTitle)
                            Image(systemName: "chevron.up.chevron.down").font(.caption2)
                        }
                        .font(.system(.subheadline, weight: .semibold))
                        .foregroundStyle(Accent.graphite)
                        .frame(minHeight: 44)
                        .contentShape(.rect)
                    }
                    .accessibilityIdentifier("cf.side")
                }
            }
            Picker("Hands", selection: $handChoice) {
                Text("One at a time").tag(HandChoice.oneAtATime)
                Text("Both hands").tag(HandChoice.bothHands)
                Text("One hand").tag(HandChoice.single)
            }
            .pickerStyle(.segmented)
            .accessibilityIdentifier("cf.hands")
            .accessibilityHint(handExplainer)
        }
    }

    private var sideMenuTitle: String {
        switch handChoice {
        case .oneAtATime: pickedSide == .right ? String(localized: "Right first") : String(localized: "Left first")
        default: pickedSide.name
        }
    }

    private var handExplainer: String {
        switch handChoice {
        case .oneAtATime:
            String(localized: "All 24 pulls on one hand, then all 24 on the other. About 8 minutes, and each hand gets its own number.")
        case .bothHands:
            String(localized: "Both hands pulling together through the gauge, on a hangboard or a two-handed block. One number.")
        case .single:
            String(localized: "Just one hand, for when only one needs testing.")
        }
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

    /// Only until it is set: asked once, then it lives in Settings. Typed, never a
    /// slider. Left empty, the test saves without it and asks again next time.
    private var bodyWeightRow: some View {
        @Bindable var settings = settings
        return VStack(alignment: .leading, spacing: 4) {
            BodyWeightField(kilograms: $settings.bodyWeightKg)
            Text("Asked once; change it in Settings.")
                .font(.system(.footnote))
                .foregroundStyle(Ink.tertiary)
        }
    }

    /// One quiet line: "Last: L 17.5 · R 16.3 kg · last week".
    @ViewBuilder
    private var lastTestLine: some View {
        let lasts = hands.sides.compactMap { side in
            records.last(where: { $0.testKey == MaxTable.key(grip: grip.key, side: side) })
        }
        if let newest = lasts.max(by: { $0.recordedAt < $1.recordedAt }) {
            let values = lasts.count > 1
                ? lasts.map { "\($0.side.initial) \(weightUnit.number($0.criticalForceKg))" }.joined(separator: " · ")
                    + " \(weightUnit.symbol)"
                : weightUnit.text(newest.criticalForceKg)
            Text("Last: \(values) · \(newest.recordedAt.formatted(.relative(presentation: .named)))")
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
    private func beatsMax(_ result: CriticalForceResult, side: Side) -> Bool {
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
        handIndex = 0
        results = []
        notes = []
        device.setMockProfile(.allOut)
        device.resetPeak()
        device.startStreaming(cause: .manualMeasurement)
        arm(CriticalForceSession())
        withAnimation(Motion.state(reduceMotion)) { stage = .testing }
        startTick += 1
    }

    /// A fresh test for the hand now on the gauge. The stream keeps running between
    /// hands; only the reading callback moves to the new session.
    private func arm(_ next: CriticalForceSession) {
        session.end()
        session = next
        next.arm()
        device.onTracePoint = { [next] point in next.receive(kg: point.kg, at: point.t) }
    }

    private func finishIfDone(_ phase: CriticalForceTest.Phase) {
        guard stage == .testing else { return }
        let hand = side == .both ? String(localized: "Both hands") : side.name
        switch phase {
        case .finished:
            guard let (outcome, trace) = session.outcome() else { return }
            switch outcome {
            case .success(let result):
                results.append(CriticalForceHandResult(side: side, result: result, trace: trace))
            case .failure(let failure):
                notes.append("\(hand): \(Self.words(for: failure))")
            }
            nextHandOrFinish(canContinue: true)
        case .voided(let reason):
            // Stopping by hand, losing the gauge or leaving the app ends the VISIT: the
            // next hand would start on a gauge that is gone or a climber who said stop.
            if results.isEmpty {
                stopStream(cause: .userStopped)
                withAnimation(Motion.state(reduceMotion)) { stage = .ended(Self.words(for: reason)) }
            } else {
                notes.append("\(hand): \(Self.words(for: reason))")
                nextHandOrFinish(canContinue: false)
            }
        default:
            break
        }
    }

    private func nextHandOrFinish(canContinue: Bool) {
        if canContinue, handIndex + 1 < hands.sides.count {
            handIndex += 1
            // Wait for the climber: the next hand starts from its own Start tap.
            session.end()
            device.onTracePoint = nil
            awaitingNextHand = true
            return
        }
        showResults()
    }

    /// "Finish with the first hand only", from between the hands.
    private func finishEarlyBetweenHands() {
        awaitingNextHand = false
        showResults()
    }

    /// The next hand's Start. A fresh stream for the fresh hand: harmless on a gauge (the
    /// same re-kick the runner sends), and the demo gauge replays its test from the start.
    private func startNextHand() {
        guard awaitingNextHand, device.state.isConnected else { return }
        awaitingNextHand = false
        if device.isStreaming { device.stopStreaming(cause: .measurementComplete) }
        device.resetPeak()
        device.startStreaming(cause: .manualMeasurement)
        arm(CriticalForceSession())
        startTick += 1
    }

    private func showResults() {
        stopStream(cause: .measurementComplete)
        withAnimation(Motion.state(reduceMotion)) {
            if results.isEmpty {
                stage = .ended(notes.isEmpty ? Self.words(for: .tooFewReps) : notes.joined(separator: "\n\n"))
            } else {
                shownSide = results[0].side
                stage = .result
            }
        }
    }

    private func save() {
        guard stage == .result, !results.isEmpty else { return }
        let saves = results.map { TemplateStore.CriticalForceSave(side: $0.side, result: $0.result, trace: $0.trace) }
        guard templates.recordCriticalForces(saves, grip: grip, bodyMassKg: settings.bodyWeightKg,
                                             alsoMaxes: alsoSaveMaxes ? maxOffers : []) != nil else {
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
        if stage == .testing, !awaitingNextHand { session.interrupt(.leftApp) }
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
    /// Between hands, the panel names the next hand instead of the finished test's DONE.
    var nextHandWord: String? = nil

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

    /// Before the first pull the clock shows the full pull it is waiting to start; once
    /// the last bell has rung, nothing is left.
    private var clock: String {
        if nextHandWord != nil { return "\(Int(session.proto.workSeconds))" }
        if let seconds { return "\(seconds)" }
        return session.phase == .armed ? "\(Int(session.proto.workSeconds))" : "0"
    }

    private var word: String {
        if let nextHandWord { return nextHandWord }
        return switch session.phase {
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
        let pull = nextHandWord != nil ? 1 : min(session.pullNumber, session.proto.reps)
        return String(localized: "Pull \(pull) of \(session.proto.reps)")
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

/// Everything the setup screen no longer says, for whoever wants it: what the test
/// measures, how to keep tests comparable, and what "One at a time" means.
struct CriticalForceAboutSheet: View {
    @Environment(\.dismiss) private var dismiss

    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(alignment: .leading, spacing: 20) {
                    section(String(localized: "What it measures"),
                            String(localized: "Critical force is the force your fingers can keep producing once the fast reserve is spent: your endurance ceiling. The test drains that reserve with 24 all-out pulls, and your force levels off at your critical force."))
                    section(String(localized: "The clock never waits"),
                            String(localized: "Seven seconds on, three off, every time. The result depends on that rhythm, so the rest does not wait for you to let go. Force pulled after the bell isn’t counted."))
                    section(String(localized: "Keep tests comparable"),
                            String(localized: "Warm up first. Use the same grip, hand and arm position each time, and leave a few weeks between tests. Your first test is partly practice."))
                    section(String(localized: "Hands"),
                            String(localized: "One at a time runs all 24 pulls on one hand, then all 24 on the other, and each hand gets its own number. Both hands means both pulling together through the gauge. The hands never alternate pull by pull, because that changes the rhythm and the result."))
                }
                .padding(.horizontal, Metrics.hPadding)
                .padding(.vertical, 20)
                .frame(maxWidth: Metrics.maxContentWidth)
                .frame(maxWidth: .infinity)
            }
            .background { AppBackground() }
            .navigationTitle("About the test")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .confirmationAction) { Button("Done") { dismiss() } }
            }
        }
        .presentationDetents([.medium, .large])
    }

    private func section(_ title: String, _ body: String) -> some View {
        VStack(alignment: .leading, spacing: 6) {
            Text(title).font(.system(.headline))
            Text(body)
                .font(.system(.subheadline))
                .foregroundStyle(Ink.secondary)
                .fixedSize(horizontal: false, vertical: true)
        }
    }
}
