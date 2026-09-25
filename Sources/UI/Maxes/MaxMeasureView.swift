// SPDX-License-Identifier: MPL-2.0
// Original contributions Copyright 2026 Nuri Bruner.

import SwiftUI

/// **A max test is a visit, not a take** (Nuri, 2026-09-25, after trying Frez's): the
/// gauge connects and reads the moment this opens, every pull is logged as an attempt
/// against the selected hand, and you keep pulling until the number stops climbing.
/// Switch hands with a tap; Review picks the pull each hand keeps and saves.
///
/// The gauge screen's anatomy — the trace IS the screen, the numbers on one Liquid Glass
/// panel over a state wash, every action in one glass dock. The dashed rule is the
/// number to beat: this visit's best on the selected hand, else its saved max.
struct MaxMeasureView: View {
    let grip: GripSpec
    /// The caller owns persistence. A failed save leaves every logged pull intact.
    var onUse: ([MaxMeasurementResult]) -> TemplateStore.MaxSaveReceipt?

    @Environment(DeviceStore.self) private var device
    @Environment(TemplateStore.self) private var templates
    @Environment(\.dismiss) private var dismiss
    @Environment(\.weightUnit) private var weightUnit
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @Environment(\.dynamicTypeSize) private var typeSize

    @ScaledMetric(relativeTo: .largeTitle) private var heroSize: CGFloat = 78
    @ScaledMetric(relativeTo: .title3) private var unitSize: CGFloat = 22

    @State private var session: LiveMaxSession
    @State private var traceGeometry = BackgroundTraceGeometry()
    @State private var waitingForSignal = false
    @State private var hasStarted = false
    @State private var reviewing = false
    @State private var saveFailed = false
    @State private var receipt: TemplateStore.MaxSaveReceipt?
    @State private var pendingReceipt: TemplateStore.MaxSaveReceipt?
    @State private var committed = false
    @State private var selectionTick = 0
    @State private var tareTick = 0
    @State private var promptedKg = 0.0
    @State private var promptedEpoch: UInt64 = 0
    @State private var showingTareConfirmation = false

    private static let dockSpacing: CGFloat = 8

    init(grip: GripSpec, initialSide: Side = .left,
         onUse: @escaping ([MaxMeasurementResult]) -> TemplateStore.MaxSaveReceipt?) {
        self.grip = grip
        self.onUse = onUse
        _session = State(initialValue: LiveMaxSession(bothTogether: initialSide == .both,
                                                      side: initialSide))
    }

    var body: some View {
        NavigationStack {
            GeometryReader { geometry in
                if typeSize.isAccessibilitySize {
                    ScrollView {
                        content.frame(minHeight: geometry.size.height, alignment: .top)
                    }
                    .scrollBounceBehavior(.basedOnSize)
                } else {
                    content.frame(width: geometry.size.width, height: geometry.size.height)
                }
            }
            .background { AppBackground() }
            .navigationTitle("Measure a max")
            .navigationBarTitleDisplayMode(.inline)
            .navigationSubtitle(grip.displayName)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("Cancel") { dismiss() }
                        .tint(Accent.graphite)
                        .accessibilityIdentifier("max.measure.cancel")
                }
            }
        }
        .sensoryFeedback(.success, trigger: session.newBestTick)
        .sensoryFeedback(.selection, trigger: selectionTick)
        .sensoryFeedback(.impact(weight: .medium, intensity: 0.7), trigger: tareTick)
        .alert("Zero the gauge?", isPresented: $showingTareConfirmation) {
            Button("Zero it", role: .destructive) { confirmTare() }
            Button("Cancel", role: .cancel) {}
        } message: {
            Text(String(localized: "There's \(weightUnit.number(promptedKg)) \(weightUnit.symbol) on the gauge. Zero it?"))
        }
        .onAppear { begin() }
        .onChange(of: device.state.isConnected) { _, connected in
            if connected { startReading() } else { session.close() }
        }
        .task(id: device.isStreaming) {
            waitingForSignal = false
            guard device.isStreaming else { return }
            try? await Task.sleep(for: .seconds(1.5))
            guard !Task.isCancelled else { return }
            waitingForSignal = true
        }
        .keepsScreenAwake()
        .onDisappear { teardown() }
        .sheet(isPresented: $reviewing, onDismiss: {
            if let pendingReceipt { receipt = pendingReceipt; self.pendingReceipt = nil }
            else if committed { dismiss() }
        }) {
            MaxAttemptReviewSheet(session: session, saveFailed: saveFailed,
                                  onSave: save, onClose: { reviewing = false })
        }
        .sheet(item: $receipt, onDismiss: { dismiss() }) { saved in
            MaxSaveReceiptView(receipt: saved) { receipt = nil }
        }
    }

    // MARK: - Layout

    private var content: some View {
        GlassEffectContainer(spacing: 24) {
            VStack(spacing: 12) {
                infoPanel
                graphRegion
                dock
            }
        }
        .padding(.horizontal, Metrics.hPadding)
        .padding(.top, 8)
        .padding(.bottom, Metrics.spacing)
        .frame(maxWidth: Metrics.maxContentWidth)
        .frame(maxWidth: .infinity)
        .background { wash }
    }

    /// Bleu while a pull is under way, steel between pulls — so the top of the phone
    /// says "that one is counting" before a number is read.
    private var wash: some View {
        PhaseWash(tint: session.isPulling ? StatusTint.engaged : StatusTint.calm,
                  edge: .top, length: traceGeometry.regionTopInCanvas)
            .onGeometryChange(for: CGRect.self) { $0.frame(in: .global) } action: {
                traceGeometry.canvas = $0
            }
            .ignoresSafeArea()
            .allowsHitTesting(false)
    }

    private var infoPanel: some View {
        VStack(spacing: 14) {
            if session.snapshot.bothTogether {
                CapsLabel(String(localized: "Both hands together"))
            } else {
                MaxHandSwitch(session: session, grip: grip) { side in
                    guard side != session.side else { return }
                    withAnimation(Motion.state(reduceMotion)) { session.select(side) }
                    selectionTick += 1
                }
            }
            MaxLiveHero(session: session, heroSize: heroSize, unitSize: unitSize)
        }
        .padding(.horizontal, 12)
        .padding(.top, 12)
        .padding(.bottom, 14)
        .frame(maxWidth: .infinity)
        .accessibleGlass(nil, in: RunnerGlass.surfaceShape)
        .runnerFloatingShadow()
        .accessibilityElement(children: .contain)
        .accessibilityIdentifier("max.measure.panel")
    }

    private var graphRegion: some View {
        ZStack {
            Color.clear
            if !device.state.isConnected {
                notice(device.state.isBusy
                       ? String(localized: "Connecting to your gauge…")
                       : String(localized: "Connect your gauge to measure. Every pull counts."))
            } else if device.isStreaming, waitingForSignal, !device.isSignalFresh {
                notice(String(localized: "Waiting for the gauge. It's connected but not sending — try Wake."))
            }
        }
        .frame(minHeight: typeSize.isAccessibilitySize ? 200 : nil, maxHeight: .infinity)
        .background {
            MaxLiveTrace(session: session, grip: grip,
                         plot: ForceTraceView.PlotInsets(top: 12, bottom: 6, trailing: 8))
                .padding(.horizontal, -Metrics.hPadding)
        }
        .onGeometryChange(for: CGRect.self) { $0.frame(in: .global) } action: {
            traceGeometry.region = $0
        }
        .accessibilityElement(children: .contain)
        .accessibilityIdentifier("max.measure.graph")
    }

    private func notice(_ text: String) -> some View {
        Text(text)
            .font(.system(.subheadline))
            .foregroundStyle(Ink.secondary)
            .multilineTextAlignment(.center)
            .fixedSize(horizontal: false, vertical: true)
            .padding(.horizontal, 24)
    }

    // MARK: - Dock

    private var dock: some View {
        let attempts = session.snapshot.log.attempts.count
        return VStack(spacing: Self.dockSpacing) {
            if device.state.isConnected {
                AdaptiveActionRow(spacing: Self.dockSpacing) {
                    DockButton(device.isReadingLive ? String(localized: "Tare") : String(localized: "Wake"),
                               systemImage: device.isReadingLive ? "arrow.counterclockwise" : "arrow.clockwise",
                               enabled: !session.isPulling,
                               disabledReason: String(localized: "Let go of the edge first."),
                               fillsRowHeight: true) { requestTare() }
                        .accessibilityIdentifier("max.measure.tare")
                    reviewButton(attempts)
                }
            } else {
                DockTintedButton(connectTitle,
                                 systemImage: device.canCancelBroadcastSearch ? "xmark" : "dot.radiowaves.left.and.right",
                                 tint: .bleu,
                                 enabled: !device.state.isBusy || device.canCancelBroadcastSearch) {
                    if device.canCancelBroadcastSearch { device.disconnect() }
                    else if !device.state.isBusy { device.connect() }
                }
                .accessibilityIdentifier("max.measure.connect")
                if attempts > 0 {
                    // Logged pulls are local data: a lost link cannot hide their save.
                    reviewButton(attempts)
                } else {
                    DockButton(device.isMock ? String(localized: "Leave demo mode") : String(localized: "Try demo mode"),
                               tint: Ink.secondary) { device.useMockDevice(!device.isMock) }
                }
            }
        }
        .padding(Self.dockSpacing)
        .accessibleGlass(nil, in: RunnerGlass.surfaceShape)
        .runnerFloatingShadow()
        .accessibilityElement(children: .contain)
        .accessibilityIdentifier("max.measure.dock")
    }

    private func reviewButton(_ attempts: Int) -> some View {
        DockTintedButton(attempts == 0 ? String(localized: "Save")
                         : String(localized: "Review \(attempts) pulls"),
                         systemImage: "checkmark", tint: .bleu, enabled: attempts > 0) {
            session.close()
            saveFailed = false
            reviewing = true
        }
        .accessibilityIdentifier("max.measure.save")
    }

    private var connectTitle: String {
        if device.canCancelBroadcastSearch { return String(localized: "Cancel") }
        return device.state.isBusy ? device.state.label : String(localized: "Connect gauge")
    }

    // MARK: - Reading

    /// Connect if needed and read at once — no Start. A gauge already connected from
    /// Today streams immediately; otherwise the first connection starts the stream.
    private func begin() {
        guard !hasStarted else { return }
        hasStarted = true
        // Capture the session object, never a changing lookup through `self`.
        let session = session
        device.onTracePoint = { point in session.receive(point) }
        if device.state.isConnected {
            startReading()
        } else if !device.state.isBusy {
            device.connect()
        }
    }

    private func startReading() {
        guard hasStarted, device.state.isConnected, !device.isStreaming else { return }
        device.resetPeak()
        device.startStreaming(cause: session.snapshot.hasAttempts ? .reconnect : .manualMeasurement)
    }

    private func teardown() {
        device.onTracePoint = nil
        if device.isStreaming { device.stopStreaming(cause: .screenClosed) }
    }

    private func save() {
        session.close()
        let results = session.snapshot.results
        guard !committed, !results.isEmpty else { return }
        guard let saved = onUse(results) else { saveFailed = true; return }
        committed = true
        teardown()
        if saved.hasDetails { pendingReceipt = saved }
        reviewing = false
    }

    // MARK: - Tare

    private func requestTare() {
        guard device.state.isConnected, !session.isPulling else { return }
        guard device.isReadingLive,
              TarePolicy.isSafeToTareNow(sampleAge: device.secondsSinceLastSample(),
                                        maxAgeSeconds: device.tareReadingMaxAge) else {
            device.startStreaming(cause: .manualWake)
            return
        }
        if TarePolicy.shouldConfirm(readingKg: device.currentKg) { promptTare() }
        else { device.tare(); tareTick += 1 }
    }

    private func promptTare() {
        promptedKg = device.currentKg
        promptedEpoch = device.connectionEpoch
        showingTareConfirmation = true
    }

    private func confirmTare() {
        switch TarePolicy.confirmationDecision(
            promptedKg: promptedKg, currentKg: device.currentKg,
            promptedEpoch: promptedEpoch, currentEpoch: device.connectionEpoch,
            isConnected: device.state.isConnected, sampleAge: device.secondsSinceLastSample(),
            phase: .idle, maxAgeSeconds: device.tareReadingMaxAge
        ) {
        case .reject: return
        case .tare: device.tare(); tareTick += 1
        case .reask:
            Task { @MainActor in
                await Task.yield()
                guard device.state.isConnected else { return }
                promptTare()
            }
        }
    }
}

// MARK: - Hands

/// Left and right as two wells inside the panel — never glass on glass. Each states the
/// hand's best this visit and how many pulls it took; the selected one is the bleu well.
/// Reads only `snapshot`, so it rebuilds when a pull is logged, not per sample.
private struct MaxHandSwitch: View {
    let session: LiveMaxSession
    let grip: GripSpec
    var onSelect: (Side) -> Void

    @Environment(TemplateStore.self) private var templates
    @Environment(\.weightUnit) private var weightUnit

    var body: some View {
        let draft = session.snapshot
        HStack(spacing: 8) {
            ForEach([Side.left, .right], id: \.self) { side in
                tile(side, draft: draft)
            }
        }
        .disabled(session.isPulling)
    }

    private func tile(_ side: Side, draft: MaxMeasurementDraft) -> some View {
        let selected = side == draft.log.side
        let pulls = draft.log.attempts(for: side).count
        let peak = draft.peak(for: side)
        let shape = RoundedRectangle(cornerRadius: Metrics.radiusInner, style: .continuous)
        return Button { onSelect(side) } label: {
            VStack(spacing: 2) {
                Text(side.name)
                    .font(.system(.subheadline, weight: .semibold))
                HStack(alignment: .firstTextBaseline, spacing: 3) {
                    Text(peak.map { weightUnit.number($0) } ?? "—")
                        .font(.system(.title3, weight: .semibold))
                        .monospacedDigit()
                        .contentTransition(.identity)
                    if peak != nil {
                        Text(weightUnit.symbol).font(.system(.caption))
                    }
                }
                Text(caption(side, pulls: pulls))
                    .font(.system(.caption))
                    .foregroundStyle(selected ? GlassTint.bleu.text : Ink.secondary)
            }
            .foregroundStyle(selected ? GlassTint.bleu.text : Ink.secondary)
            .lineLimit(1)
            .minimumScaleFactor(0.75)
            .padding(.vertical, 10)
            .padding(.horizontal, 8)
            .frame(maxWidth: .infinity)
            .background(shape.fill(selected ? (GlassTint.bleu.glass ?? Accent.bleu).opacity(0.22)
                                            : Ink.primary.opacity(0.05)))
            .contentShape(shape)
        }
        .buttonStyle(PressFeedbackButtonStyle())
        .accessibilityLabel(side == .left ? String(localized: "Left hand") : String(localized: "Right hand"))
        .accessibilityValue(peak.map {
            String(localized: "\(weightUnit.number($0)) \(weightUnit.spokenName), best of \(pulls) pulls")
        } ?? String(localized: "Not measured"))
        .accessibilityAddTraits(selected ? [.isSelected] : [])
        .accessibilityIdentifier(side == .left ? "max.measure.left" : "max.measure.right")
    }

    private func caption(_ side: Side, pulls: Int) -> String {
        if pulls > 0 { return String(localized: "\(pulls) pulls") }
        if let saved = templates.currentMax(for: grip, side: side) {
            return String(localized: "Max \(weightUnit.number(saved))")
        }
        return String(localized: "Not measured")
    }
}

// MARK: - Per-sample leaves

/// The hero: THIS pull's peak while it climbs, then the last pull's result until the next
/// one starts. The peak, not the live reading — watching the number you are about to log
/// fall away as you let go is not what anyone wants at the end of a max effort. The live
/// reading is the caption under it.
private struct MaxLiveHero: View {
    let session: LiveMaxSession
    var heroSize: CGFloat
    var unitSize: CGFloat
    @Environment(\.weightUnit) private var weightUnit

    var body: some View {
        let pulling = session.pullPeakKg
        let shown = pulling ?? session.lastAttempt?.peakKg
        VStack(spacing: 4) {
            CapsLabel(label(pulling: pulling != nil))
            HStack(alignment: .firstTextBaseline, spacing: 6) {
                Text(shown.map { weightUnit.number($0) } ?? "—")
                    .font(.system(size: heroSize, weight: .thin))
                    .displayTracking(heroSize)
                    .monospacedDigit()
                    // A measurement snaps; see RunnerView.readout.
                    .contentTransition(.identity)
                Text(weightUnit.symbol)
                    .font(.system(size: unitSize, weight: .regular))
                    .foregroundStyle(Ink.tertiary)
            }
            .foregroundStyle(pulling != nil ? StatusTint.engaged : shown == nil ? Ink.tertiary : Ink.primary)
            .lineLimit(1)
            .minimumScaleFactor(0.6)
            MaxLiveReadout()
        }
        .frame(maxWidth: .infinity)
        .accessibilityElement(children: .ignore)
        .accessibilityLabel(spoken(shown, pulling: pulling != nil))
        .accessibilityIdentifier("max.measure.hero")
    }

    private func label(pulling: Bool) -> String {
        if pulling { return String(localized: "This pull") }
        return session.lastAttempt == nil ? String(localized: "Pull when ready") : String(localized: "Last pull")
    }

    private func spoken(_ kg: Double?, pulling: Bool) -> String {
        guard let kg else { return String(localized: "No pull yet") }
        return pulling ? String(localized: "Pulling, \(weightUnit.number(kg)) \(weightUnit.spokenName) so far")
                       : String(localized: "Last pull \(weightUnit.number(kg)) \(weightUnit.spokenName)")
    }
}

/// The instantaneous reading, in a leaf because `currentKg` changes ~80×/s.
private struct MaxLiveReadout: View {
    @Environment(\.weightUnit) private var weightUnit
    @Environment(DeviceStore.self) private var device
    private var isLive: Bool { device.isStreaming && device.isSignalFresh }

    var body: some View {
        HStack(alignment: .firstTextBaseline, spacing: 3) {
            Text(isLive ? String(localized: "now") : String(localized: "No live reading"))
                .font(.system(.caption, weight: .medium))
                .foregroundStyle(Ink.tertiary)
            if isLive {
                Text(weightUnit.number(device.currentKg))
                    .font(.system(.subheadline, weight: .semibold))
                    .monospacedDigit()
                    .contentTransition(.identity)
                    .foregroundStyle(Ink.secondary)
                Text(weightUnit.symbol)
                    .font(.system(.caption))
                    .foregroundStyle(Ink.tertiary)
            }
        }
        .accessibilityHidden(true)
    }
}

/// The lit trace, with the number to beat as its dashed rule: this visit's best on the
/// selected hand, else that hand's saved max.
private struct MaxLiveTrace: View {
    let session: LiveMaxSession
    let grip: GripSpec
    var plot: ForceTraceView.PlotInsets
    @Environment(DeviceStore.self) private var device
    @Environment(TemplateStore.self) private var templates

    var body: some View {
        let draft = session.snapshot
        let side = draft.log.side
        let toBeat = draft.log.best(for: side)?.peakKg ?? templates.currentMax(for: grip, side: side)
        ForceTraceView(samples: device.trace,
                       thresholdKg: toBeat,
                       tint: device.isStreaming ? StatusTint.engaged : Ink.tertiary,
                       nominalSampleRate: device.gaugeCapabilities.nominalSampleRate,
                       bridgesSparseDelivery: device.gaugeCapabilities.isBroadcast,
                       diagnostics: device.pipelineDiagnostics,
                       plot: plot, lit: true)
    }
}
