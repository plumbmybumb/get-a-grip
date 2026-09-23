// SPDX-License-Identifier: MPL-2.0
// Original contributions Copyright 2026 Nuri Bruner.

import SwiftUI

private enum MaxMeasurePhase { case ready, measuring, done }

struct MaxMeasurementResult: Equatable, Sendable {
    let side: Side
    let kg: Double
    var source: MaxSource = .measured
}

/// Transient measurements, with a hand fixed before the first sample arrives. Nothing
/// here changes a working benchmark until the caller successfully saves the results.
struct MaxMeasurementDraft {
    let bothTogether: Bool
    private(set) var activeSide: Side?
    private var peaks: [Side: Double] = [:]
    private var corrections: [Side: Double] = [:]

    init(bothTogether: Bool = false) {
        self.bothTogether = bothTogether
    }

    var results: [MaxMeasurementResult] {
        guard activeSide == nil else { return [] }
        return [Side.left, .right, .both].compactMap { side in
            peaks[side].map {
                MaxMeasurementResult(side: side, kg: corrections[side] ?? $0,
                                     source: corrections[side] == nil ? .measured : .manual)
            }
        }
    }

    func peak(for side: Side) -> Double? { corrections[side] ?? peaks[side] }
    func measuredPeak(for side: Side) -> Double? { peaks[side] }

    /// Corrections belong to this unsaved draft. Returning to the exact captured
    /// peak restores measured provenance; a correction never fabricates another hand.
    @discardableResult
    mutating func correct(_ values: [MaxMeasurementResult]) -> Bool {
        guard activeSide == nil, !values.isEmpty,
              Set(values.map(\.side)).count == values.count,
              values.allSatisfy({ peaks[$0.side] != nil && $0.kg.isFinite && $0.kg > 0 }) else { return false }
        for value in values {
            corrections[value.side] = value.kg == peaks[value.side] ? nil : value.kg
        }
        return true
    }

    /// A retry reserves its hand, but the last valid result stays staged until a
    /// replacement exists. A disconnected or empty attempt must not erase an effort.
    mutating func begin(side: Side) -> Bool {
        guard activeSide == nil, (side == .both) == bothTogether else { return false }
        activeSide = side
        return true
    }

    @discardableResult
    mutating func finish(peakKg: Double) -> Side? {
        guard let side = activeSide else { return nil }
        activeSide = nil
        if peakKg.isFinite, peakKg >= MaxAttempt.releaseKg {
            peaks[side] = peakKg
            corrections[side] = nil
        }
        return side
    }
}

/// One visit to the gauge can capture either hand or both in turn. Combined-hand
/// testing is an explicit entry mode, never inferred from two separate measurements.
struct MaxMeasureView: View {
    let grip: GripSpec
    /// The caller owns persistence. A failed save leaves both measured peaks intact.
    var onUse: ([MaxMeasurementResult]) -> TemplateStore.MaxSaveReceipt?

    @Environment(DeviceStore.self) private var device
    @Environment(\.dismiss) private var dismiss
    @Environment(\.weightUnit) private var weightUnit
    @Environment(\.accessibilityReduceMotion) private var reduceMotion

    @State private var selectedSide: Side
    @State private var draft: MaxMeasurementDraft
    @State private var leftMeasurement = MaxMeasurement()
    @State private var rightMeasurement = MaxMeasurement()
    @State private var bothMeasurement = MaxMeasurement()
    @State private var traces: [Side: [DeviceStore.TracePoint]] = [:]
    @State private var completedMeasurements: [Side: MaxMeasurement] = [:]
    @State private var keptPreviousAfterRetry = false
    @State private var phase: MaxMeasurePhase = .ready
    @State private var timeout: Task<Void, Never>?
    @State private var saveFailed = false
    @State private var selectionTick = 0
    @State private var completionTick = 0
    @State private var adjusting = false
    @State private var receipt: TemplateStore.MaxSaveReceipt?
    @State private var committed = false

    private static let timeoutSeconds = 45

    init(grip: GripSpec, initialSide: Side = .left,
         onUse: @escaping ([MaxMeasurementResult]) -> TemplateStore.MaxSaveReceipt?) {
        self.grip = grip
        self.onUse = onUse
        _selectedSide = State(initialValue: initialSide)
        _draft = State(initialValue: MaxMeasurementDraft(bothTogether: initialSide == .both))
    }

    private var measurement: MaxMeasurement {
        switch selectedSide {
        case .left: leftMeasurement
        case .right: rightMeasurement
        case .both: bothMeasurement
        }
    }

    var body: some View {
        NavigationStack {
            GeometryReader { geometry in
                ScrollView {
                    VStack(spacing: 16) {
                        handSelection
                        CapsLabel(phase == .done && draft.peak(for: selectedSide) != nil ? String(localized: "Measured") : String(localized: "HARDEST PULL"),
                                  tint: Ink.tertiary)
                        MaxMeasurementHero(measurement: measurement, phase: phase)
                        MaxMeasurementTrace(measurement: measurement,
                                            isMeasuring: phase == .measuring,
                                            frozenTrace: phase == .measuring ? nil : traces[selectedSide] ?? [])
                        MaxMeasurementGuidance(measurement: measurement, phase: phase,
                                               hasOtherResult: !draft.bothTogether && draft.peak(for: selectedSide.other) != nil,
                                               bothTogether: draft.bothTogether,
                                               keptPreviousAfterRetry: keptPreviousAfterRetry)
                        Spacer(minLength: 0)
                        controls
                    }
                    .padding(.top, 12)
                    .frame(minHeight: geometry.size.height)
                }
                .scrollBounceBehavior(.basedOnSize)
            }
            .padding(.horizontal, Metrics.hPadding)
            .padding(.bottom, Metrics.spacing)
            .frame(maxWidth: Metrics.maxContentWidth)
            .frame(maxWidth: .infinity)
            .background { AppBackground() }
            .navigationTitle("Measure a max")
            .navigationBarTitleDisplayMode(.inline)
            .navigationSubtitle(grip.displayName)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("Cancel") { dismiss() }
                        .accessibilityIdentifier("max.measure.cancel")
                }
            }
        }
        .sensoryFeedback(.success, trigger: completionTick)
        .sensoryFeedback(.selection, trigger: selectionTick)
        .onChange(of: measurement.isComplete) { _, complete in
            if complete { stop(cause: .measurementComplete) }
        }
        .onChange(of: device.state.isConnected) { _, connected in
            if connected, phase == .measuring {
                device.startStreaming(cause: .reconnect)
            }
        }
        .onDisappear { teardown() }
        .sheet(isPresented: $adjusting) {
            MaxMeasurementAdjustmentSheet(results: draft.results,
                                          measuredPeaks: Dictionary(uniqueKeysWithValues: draft.results.compactMap { result in
                draft.measuredPeak(for: result.side).map { (result.side, $0) }
            }), onApply: { values in
                if draft.correct(values) { adjusting = false; saveFailed = false }
            }, onCancel: { adjusting = false })
        }
        .sheet(item: $receipt, onDismiss: { dismiss() }) { saved in
            MaxSaveReceiptView(receipt: saved) { receipt = nil }
        }
    }

    // MARK: - Hands

    @ViewBuilder
    private var handSelection: some View {
        if draft.bothTogether {
            VStack(spacing: 6) {
                Text("Both hands together")
                    .font(.system(.title3, weight: .semibold))
                Text("One combined measurement")
                    .font(.footnote)
                    .foregroundStyle(Ink.secondary)
            }
            .frame(maxWidth: .infinity)
        } else {
            HStack(spacing: 12) {
                handButton(.left)
                handButton(.right)
            }
            .disabled(phase == .measuring)
        }
    }

    private func handButton(_ side: Side) -> some View {
        let selected = side == selectedSide
        let peak = draft.peak(for: side)
        let shape = RoundedRectangle(cornerRadius: Metrics.radiusInner, style: .continuous)
        return Button { select(side) } label: {
            VStack(spacing: 6) {
                HStack(spacing: 6) {
                    Text(side.name)
                    if peak != nil {
                        Image(systemName: "checkmark.circle.fill")
                            .font(.caption)
                    }
                }
                .font(.system(.headline, weight: .semibold))
                Text(peak.map { "\(weightUnit.number($0)) \(weightUnit.symbol)" } ?? "—")
                    .font(.system(.title3, weight: .medium))
                    .monospacedDigit()
                    .contentTransition(.identity)
            }
            .foregroundStyle(selected ? Ink.primary : Ink.secondary)
            .lineLimit(1)
            .minimumScaleFactor(0.75)
            .padding(.horizontal, 12)
            .padding(.vertical, 12)
            .frame(maxWidth: .infinity, minHeight: 72)
            .background(selected ? Accent.bleu.opacity(0.12) : Ink.primary.opacity(0.04), in: shape)
            .overlay { shape.strokeBorder(selected ? Accent.bleu : Ink.tertiary.opacity(0.2), lineWidth: selected ? 1.5 : 1) }
            .contentShape(shape)
        }
        .buttonStyle(PressFeedbackButtonStyle())
        .accessibilityLabel(side == .left ? String(localized: "Left hand") : String(localized: "Right hand"))
        .accessibilityValue(peak.map { String(localized: "\(weightUnit.number($0)) \(weightUnit.spokenName), ready to save") }
                            ?? String(localized: "Not measured"))
        .accessibilityAddTraits(selected ? [.isSelected] : [])
        .accessibilityIdentifier(side == .left ? "max.measure.left" : "max.measure.right")
    }

    private func select(_ side: Side) {
        guard phase != .measuring, side != selectedSide else { return }
        withAnimation(Motion.state(reduceMotion)) {
            selectedSide = side
            phase = draft.peak(for: side) == nil ? .ready : .done
            keptPreviousAfterRetry = false
            saveFailed = false
        }
        selectionTick += 1
    }

    // MARK: - Controls

    private var controls: some View {
        VStack(spacing: 12) {
            if !device.state.isConnected, phase == .ready {
                Text("Connect your gauge to measure.")
                    .font(.system(.footnote, weight: .medium))
                    .foregroundStyle(Ink.tertiary)
                    .multilineTextAlignment(.center)
                    .fixedSize(horizontal: false, vertical: true)
                GaugeConnectButton(connectTitle: String(localized: "Connect"))
            } else {
                attemptControls
            }

            // A captured peak is local data. Connection loss or selecting an unmeasured
            // hand cannot hide the action that saves the hand already completed.
            if phase != .measuring, !draft.results.isEmpty {
                SecondaryGlassButton(title: String(localized: "Adjust values"), systemImage: "pencil") {
                    adjusting = true
                }
                .accessibilityIdentifier("max.measure.adjust")
                PrimaryGlassButton(title: draft.results.count == 1 ? String(localized: "Save max") : String(localized: "Save maxes"),
                                   systemImage: "checkmark", tint: Accent.graphite) { save() }
                    .accessibilityIdentifier("max.measure.save")
                Text(draft.results.contains { $0.source == .manual }
                     ? String(localized: "Adjusted values are saved as manual entries. Your recorded trace stays unchanged.")
                     : String(localized: "Saved maxes update percentage targets. Weight targets stay as entered."))
                    .font(.caption)
                    .foregroundStyle(Ink.tertiary)
                    .multilineTextAlignment(.center)
                    .fixedSize(horizontal: false, vertical: true)
            }
            if saveFailed {
                Text("Couldn’t save. Your measurements are still here — try again.")
                    .font(.footnote)
                    .foregroundStyle(Accent.alarm)
                    .multilineTextAlignment(.center)
            }
        }
    }

    @ViewBuilder
    private var attemptControls: some View {
        switch phase {
        case .ready:
            MaxTareButton(phase: $phase)
            PrimaryGlassButton(title: startTitle, systemImage: "play.fill", tint: Accent.bleu) { start() }
                .accessibilityIdentifier("max.measure.start")
        case .measuring:
            PrimaryGlassButton(title: String(localized: "Done"), systemImage: "stop.fill", tint: Accent.alarm) {
                finishByHand(cause: .userStopped)
            }
            .accessibilityIdentifier("max.measure.finish")
        case .done:
            if !draft.bothTogether, draft.peak(for: selectedSide.other) == nil {
                SecondaryGlassButton(title: selectedSide == .left ? String(localized: "Measure right hand") : String(localized: "Measure left hand"),
                                     systemImage: "arrow.right") { select(selectedSide.other) }
                    .accessibilityIdentifier("max.measure.other")
            }
            SecondaryGlassButton(title: String(localized: "Try again"), systemImage: "arrow.counterclockwise") {
                // Return to setup so the gauge can be zeroed before the replacement attempt.
                phase = .ready
                saveFailed = false
            }
            .accessibilityIdentifier("max.measure.retry")
        }
    }

    private var startTitle: String {
        switch selectedSide {
        case .left: String(localized: "Measure left hand")
        case .right: String(localized: "Measure right hand")
        case .both: String(localized: "Start")
        }
    }

    private func save() {
        let results = draft.results
        guard !committed, !results.isEmpty else { return }
        guard let saved = onUse(results) else { saveFailed = true; return }
        committed = true
        if saved.hasDetails { receipt = saved }
        else { dismiss() }
    }

    // MARK: - Running

    private func start() {
        guard device.state.isConnected, draft.begin(side: selectedSide) else { return }
        // Retain the completed object and trace until this fresh attempt earns a
        // replacement. Resetting the old object would also reset the saved hero.
        let attempt = MaxMeasurement()
        setMeasurement(attempt, for: selectedSide)
        keptPreviousAfterRetry = false
        saveFailed = false
        device.resetPeak()
        // Capture the attempt object, never a changing selected-hand lookup.
        device.onTracePoint = { point in attempt.receive(point) }
        device.startStreaming(cause: .manualMeasurement)
        phase = .measuring

        timeout?.cancel()
        timeout = Task {
            try? await Task.sleep(for: .seconds(Self.timeoutSeconds))
            guard !Task.isCancelled else { return }
            finishByHand(cause: .timedOut)
        }
    }

    private func finishByHand(cause: StreamStopCause) {
        measurement.finish()
        stop(cause: cause)
    }

    private func stop(cause: StreamStopCause) {
        guard phase == .measuring else { return }
        timeout?.cancel()
        timeout = nil
        device.onTracePoint = nil
        if device.isStreaming { device.stopStreaming(cause: cause) }
        let attempt = measurement
        if let side = draft.finish(peakKg: attempt.peakKg) {
            if attempt.hasResult, attempt.peakKg.isFinite {
                completedMeasurements[side] = attempt
                traces[side] = device.trace
                completionTick += 1
            } else if let previous = completedMeasurements[side] {
                setMeasurement(previous, for: side)
                keptPreviousAfterRetry = true
            } else {
                traces[side] = device.trace
            }
        }
        phase = .done
    }

    private func setMeasurement(_ value: MaxMeasurement, for side: Side) {
        switch side {
        case .left: leftMeasurement = value
        case .right: rightMeasurement = value
        case .both: bothMeasurement = value
        }
    }

    private func teardown() {
        timeout?.cancel()
        timeout = nil
        device.onTracePoint = nil
        if device.isStreaming { device.stopStreaming(cause: .screenClosed) }
    }
}

/// Keep sample-dependent tare liveness out of the full measurement screen.
private struct MaxTareButton: View {
    @Environment(\.weightUnit) private var weightUnit
    @Binding var phase: MaxMeasurePhase
    @Environment(DeviceStore.self) private var device
    @State private var showingConfirmation = false
    @State private var promptedKg = 0.0
    @State private var promptedEpoch: UInt64 = 0
    @State private var tareTick = 0
    @State private var reaskTask: Task<Void, Never>?

    var body: some View {
        SecondaryGlassButton(
            title: device.isReadingLive ? String(localized: "Zero the gauge") : String(localized: "Wake"),
            systemImage: device.isReadingLive ? "arrow.counterclockwise" : "arrow.clockwise"
        ) { requestTare() }
        .onDisappear { reaskTask?.cancel() }
        .sensoryFeedback(.impact(weight: .medium, intensity: 0.7), trigger: tareTick)
        .alert("Zero the gauge?", isPresented: $showingConfirmation) {
            Button("Zero it", role: .destructive) { confirmTare() }
            Button("Cancel", role: .cancel) {}
        } message: {
            Text(String(localized: "There's \(weightUnit.number(promptedKg)) \(weightUnit.symbol) on the gauge. Zero it?"))
        }
    }

    private func requestTare() {
        guard phase == .ready, device.state.isConnected else { return }
        guard device.isReadingLive,
              TarePolicy.isSafeToTareNow(sampleAge: device.secondsSinceLastSample(),
                                        maxAgeSeconds: device.tareReadingMaxAge) else {
            device.startStreaming(cause: .manualWake)
            return
        }
        if TarePolicy.shouldConfirm(readingKg: device.currentKg) { promptTare() }
        else { performTare() }
    }

    private func promptTare() {
        promptedKg = device.currentKg
        promptedEpoch = device.connectionEpoch
        showingConfirmation = true
    }

    private func confirmTare() {
        guard phase == .ready else { return }
        switch TarePolicy.confirmationDecision(
            promptedKg: promptedKg, currentKg: device.currentKg,
            promptedEpoch: promptedEpoch, currentEpoch: device.connectionEpoch,
            isConnected: device.state.isConnected,
            sampleAge: device.secondsSinceLastSample(), phase: .idle,
            maxAgeSeconds: device.tareReadingMaxAge
        ) {
        case .reject: return
        case .reask:
            reaskTask?.cancel()
            reaskTask = Task { @MainActor in
                await Task.yield()
                guard !Task.isCancelled, phase == .ready, device.state.isConnected else { return }
                promptTare()
            }
        case .tare: performTare()
        }
    }

    private func performTare() {
        device.tare()
        tareTick += 1
    }
}

// MARK: - Per-sample leaves

/// The peak climbs with the live pull. Keeping every peak/result read here prevents the
/// surrounding navigation, guidance, controls and connection UI from rebuilding with it.
private struct MaxMeasurementHero: View {
    @Environment(\.weightUnit) private var weightUnit
    @Environment(DeviceStore.self) private var device
    let measurement: MaxMeasurement
    let phase: MaxMeasurePhase

    /// Scaled, never a bare point size, or it stays fixed while everything else grows.
    @ScaledMetric(relativeTo: .largeTitle) private var heroSize: CGFloat = 76
    @ScaledMetric(relativeTo: .title3) private var unitSize: CGFloat = 21

    var body: some View {
        VStack(spacing: 4) {
            HStack(alignment: .firstTextBaseline, spacing: 6) {
                Text(measurement.hasResult
                     ? weightUnit.number(measurement.peakKg)
                     : "—")
                    .font(.system(size: heroSize, weight: .thin))
                    .displayTracking(heroSize)
                    .monospacedDigit()
                    // SNAPS, not rolls: a numeric transition on a live-derived figure reads as
                    // the app animating a number it is unsure of.
                    .contentTransition(.identity)
                Text(weightUnit.symbol)
                    .font(.system(size: unitSize, weight: .regular))
                    .foregroundStyle(Ink.tertiary)
            }
            .foregroundStyle(measurement.hasResult ? Ink.primary : Ink.tertiary)
            .lineLimit(1)
            .minimumScaleFactor(0.6)

            if phase != .done { LiveReadout() }
        }
        .frame(maxWidth: .infinity)
        .accessibilityElement(children: .ignore)
        .accessibilityLabel(spokenState)
    }

    private var spokenState: String {
        let signal = phase == .measuring && (!device.isStreaming || !device.isSignalFresh)
            ? ". " + String(localized: "No live reading") : ""
        return measurementSpokenState + signal
    }

    private var measurementSpokenState: String {
        guard measurement.hasResult else {
            return phase == .measuring ? String(localized: "No pull yet") : String(localized: "No measurement yet")
        }
        return String(localized: "\(weightUnit.number(measurement.peakKg)) \(weightUnit.spokenName), your hardest pull")
    }
}

/// Reads the high-frequency trace itself, so an ~80 Hz append invalidates only the graph.
private struct MaxMeasurementTrace: View {
    let measurement: MaxMeasurement
    let isMeasuring: Bool
    let frozenTrace: [DeviceStore.TracePoint]?

    @Environment(DeviceStore.self) private var device

    var body: some View {
        ForceTraceView(samples: frozenTrace ?? device.trace,
                       thresholdKg: measurement.hasResult ? measurement.peakKg : nil,
                       tint: isMeasuring ? StatusTint.engaged : Ink.tertiary,
                       nominalSampleRate: device.gaugeCapabilities.nominalSampleRate,
                       bridgesSparseDelivery: device.gaugeCapabilities.isBroadcast,
                       diagnostics: device.pipelineDiagnostics,
                       frozenAt: frozenTrace?.last?.t)
            .frame(height: 168)
            .padding(.horizontal, 4)
            .background(.regularMaterial,
                        in: RoundedRectangle(cornerRadius: Metrics.radiusCard, style: .continuous))
    }
}

/// Result-dependent copy is its own observation island: once the pull crosses the result
/// threshold, later peak changes cannot rebuild the rest of the measurement screen.
private struct MaxMeasurementGuidance: View {
    let measurement: MaxMeasurement
    let phase: MaxMeasurePhase
    var hasOtherResult: Bool = false
    var bothTogether: Bool = false
    var keptPreviousAfterRetry: Bool = false

    var body: some View {
        Text(guidanceText)
            .font(.system(.subheadline, weight: .medium))
            .foregroundStyle(Ink.secondary)
            .multilineTextAlignment(.center)
            .fixedSize(horizontal: false, vertical: true)
            .frame(maxWidth: .infinity)
    }

    private var guidanceText: String {
        switch phase {
        case .ready:
            return String(localized: "Build force gradually and stop if it hurts. This measures a peak, not a safe training limit.")
        case .measuring:
            return measurement.hasResult
                ? String(localized: "Let go when you are ready to finish.")
                : String(localized: "Pull…")
        case .done:
            if keptPreviousAfterRetry {
                return String(localized: "No new pull recorded. Your previous measurement is still ready to save.")
            }
            return measurement.hasResult
                ? (bothTogether ? String(localized: "Save this combined max, or try again.") :
                    hasOtherResult ? String(localized: "Both hands are ready to save.") : String(localized: "Save this max, or measure your other hand."))
                : String(localized: "No pull was recorded. You can close this or try again.")
        }
    }
}

// MARK: - The live number

/// The instantaneous reading, in a leaf because `currentKg` changes ~80×/s; the hero,
/// controls and guidance are not dragged along.
private struct LiveReadout: View {
    @Environment(\.weightUnit) private var weightUnit
    @Environment(DeviceStore.self) private var device
    private var isLive: Bool { device.isStreaming && device.isSignalFresh }

    var body: some View {
        HStack(alignment: .firstTextBaseline, spacing: 3) {
            Text(isLive ? String(localized: "now") : String(localized: "No live reading"))
                .font(.system(.caption, weight: .medium))
                .foregroundStyle(Ink.tertiary)
            Text(isLive ? weightUnit.number(device.currentKg) : "—")
                .font(.system(.subheadline, weight: .semibold))
                .monospacedDigit()
                .contentTransition(.identity)
                .foregroundStyle(isLive ? StatusTint.engaged : Ink.tertiary)
            Text(weightUnit.symbol)
                .font(.system(.caption))
                .foregroundStyle(Ink.tertiary)
        }
        // A numeral changing 80×/sec is unusable under VoiceOver; the hero carries the
        // accessible summary.
        .accessibilityHidden(true)
    }
}

// MARK: - State

/// Owns the `MaxAttempt` and publishes only what the screen draws.
///
/// The attempt is `@ObservationIgnored` and display values are written ONLY on change:
/// samples arrive ~80×/s, and holding the attempt in `@State` would invalidate the view
/// on each one. The peak climbs then holds, so the screen settles when the pull does.
@Observable
@MainActor
final class MaxMeasurement {
    private(set) var peakKg: Double = 0
    private(set) var isComplete = false

    @ObservationIgnored private var attempt = MaxAttempt()

    /// Mirrors `MaxAttempt.hasResult` off the same constant, so the screen never offers to
    /// save what the engine does not consider a pull.
    var hasResult: Bool { peakKg >= MaxAttempt.releaseKg }

    func receive(_ point: DeviceStore.TracePoint) {
        attempt.add(point.kg, at: point.t)
        publish()
    }

    func finish() {
        attempt.finish()
        publish()
    }

    func reset() {
        attempt = MaxAttempt()
        peakKg = 0
        isComplete = false
    }

    /// Equality-guarded: Observation fires on every SET, not every CHANGE, so an unguarded
    /// mirror would invalidate at the full sample rate.
    private func publish() {
        if peakKg != attempt.peakKg { peakKg = attempt.peakKg }
        if isComplete != attempt.isComplete { isComplete = attempt.isComplete }
    }
}
