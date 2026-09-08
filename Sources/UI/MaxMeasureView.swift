// SPDX-License-Identifier: MPL-2.0
// Original contributions Copyright 2026 Nuri Bruner.

import SwiftUI

private enum MaxMeasurePhase { case ready, measuring, done }

/// Measuring a max on the gauge, as its own full screen.
///
/// **Why it takes the whole screen rather than sitting in the composer sheet:** during
/// the one moment this view exists for, the phone is propped on a bench and you are
/// hanging off a fingerboard with both hands. A live number inside a scrolling form is
/// unreadable from there, and it can be scrolled away by the same finger that started
/// it. Everything here is sized to be read at arm's length.
///
/// The rule — the hardest the gauge saw — lives in `MaxAttempt`, tested away from any of
/// this, along with the reason it is no longer a sustained hold. The result is drawn
/// across the trace as the dashed rule, so the shape of the pull and the number it
/// produced are one picture rather than two things to reconcile.
struct MaxMeasureView: View {
    let grip: GripSpec
    /// Handed the measured result when it is accepted. The caller owns saving — this
    /// screen never writes to the store, so "measure" and "record" stay separable and
    /// the number lands in the same field a typed one would.
    var onUse: (Double) -> Void

    @Environment(DeviceStore.self) private var device
    @Environment(\.dismiss) private var dismiss

    @State private var measurement = MaxMeasurement()
    @State private var frozenTrace: [DeviceStore.TracePoint]?
    @State private var phase: MaxMeasurePhase = .ready
    @State private var timeout: Task<Void, Never>?

    /// Long enough for a full attempt including a slow set-up on the edge; short enough
    /// that a screen left open cannot flatten the gauge's battery. The same guard the
    /// builder's threshold check uses, sized for a longer job.
    private static let timeoutSeconds = 45

    var body: some View {
        NavigationStack {
            GeometryReader { geometry in
                ScrollView {
                    VStack(spacing: 16) {
                        header
                        hero
                        trace
                        guidance
                        Spacer(minLength: 0)
                        controls
                    }
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
                }
            }
        }
        // The moment a result exists is worth feeling: you are looking at the edge, not
        // at the phone.
        .sensoryFeedback(.success, trigger: measurement.isComplete)
        .onChange(of: measurement.isComplete) { _, complete in
            if complete { stop(cause: .measurementComplete) }
        }
        // A disconnect clears DeviceStore.isStreaming. Keep the attempt and its callback
        // alive, then explicitly restart the stream when auto-reconnect restores the link.
        .onChange(of: device.state.isConnected) { _, connected in
            if connected, phase == .measuring {
                device.startStreaming(cause: .reconnect)
            }
        }
        .onDisappear { teardown() }
    }

    // MARK: - Face

    private var header: some View {
        CapsLabel(phase == .done ? String(localized: "YOUR MAX ON THIS GRIP") : String(localized: "HARDEST PULL"),
                  tint: Ink.tertiary)
            .frame(maxWidth: .infinity)
    }

    /// THE NUMBER THAT WILL BE SAVED — the peak, which is what climbs and then holds
    /// still. The live reading stays demoted to the line underneath: it falls away the
    /// instant you ease off, and watching the figure you are about to record drop back
    /// toward zero is not what anyone wants at the end of a max effort.
    private var hero: some View {
        MaxMeasurementHero(measurement: measurement, phase: phase)
    }

    /// The shape of the pull, with the result drawn across it as the dashed rule — so
    /// the number and the effort that produced it are one picture.
    private var trace: some View {
        MaxMeasurementTrace(measurement: measurement, isMeasuring: phase == .measuring, frozenTrace: frozenTrace)
    }

    /// No peak-versus-held footnote any more: with the result BEING the peak there is no
    /// gap left to explain, and the line that explained it went with the rule.
    private var guidance: some View {
        MaxMeasurementGuidance(measurement: measurement, phase: phase)
    }

    // MARK: - Controls

    @ViewBuilder
    private var controls: some View {
        // A completed attempt is local data: disconnecting cannot take away its save action.
        if !device.state.isConnected, phase != .done {
            // SHOWN rather than a disabled button: a control you cannot use teaches
            // nothing, and the way out is what matters here.
            VStack(spacing: 12) {
                Text("Connect your gauge to measure. You can always type a max in instead.")
                    .font(.system(.footnote, weight: .medium))
                    .foregroundStyle(Ink.tertiary)
                    .multilineTextAlignment(.center)
                    .fixedSize(horizontal: false, vertical: true)
                GaugeConnectButton(connectTitle: String(localized: "Connect"))
            }
        } else {
            switch phase {
            case .ready:
                VStack(spacing: 12) {
                    // Zeroing belongs BEFORE the pull and nowhere else: taring mid-attempt
                    // would zero out the load already on the edge and silently rewrite the
                    // result. It is offered here because a hanging sling or a mounted
                    // block reads as several kilograms the gauge would otherwise count.
                    MaxTareButton(phase: $phase)
                    PrimaryGlassButton(title: String(localized: "Start"), systemImage: "play.fill",
                                       tint: Accent.bleu) { start() }
                }
            case .measuring:
                PrimaryGlassButton(title: String(localized: "Done"), systemImage: "stop.fill",
                                   tint: Accent.alarm) { finishByHand(cause: .userStopped) }
            case .done:
                VStack(spacing: 12) {
                    SecondaryGlassButton(title: String(localized: "Try again"),
                                         systemImage: "arrow.counterclockwise") { start() }
                        .disabled(!device.state.isConnected)
                    MaxMeasurementUseButton(measurement: measurement,
                                            onUse: onUse,
                                            onDismiss: { dismiss() })
                }
            }
        }
    }

    // MARK: - Running

    private func start() {
        guard device.state.isConnected else { return }
        measurement.reset()
        // The trace is the attempt's own picture; leftovers from a previous go would be
        // drawn as part of this one, and the axis is latched off what it has seen.
        device.resetPeak()
        device.onTracePoint = { point in measurement.receive(point) }
        device.startStreaming(cause: .manualMeasurement)
        frozenTrace = nil
        phase = .measuring

        timeout?.cancel()
        timeout = Task {
            try? await Task.sleep(for: .seconds(Self.timeoutSeconds))
            guard !Task.isCancelled else { return }
            finishByHand(cause: .timedOut)
        }
    }

    /// The cause travels from the TRIGGER, because this is reached from two of them:
    /// the Done button and the timeout. Labelling both "measurement finished" would put a
    /// completion in the log for a pull that never completed.
    private func finishByHand(cause: StreamStopCause) {
        measurement.finish()
        stop(cause: cause)
    }

    /// Stop the stream but KEEP the result on screen — this is the transition into
    /// `.done`, not a teardown.
    private func stop(cause: StreamStopCause) {
        guard phase == .measuring else { return }
        timeout?.cancel()
        timeout = nil
        device.onTracePoint = nil
        if device.isStreaming { device.stopStreaming(cause: cause) }
        frozenTrace = device.trace
        phase = .done
    }

    /// UNCONDITIONAL, and gated on the DEVICE's own truth rather than on `phase`: a
    /// Progressor left streaming behind a dismissed screen is a dead battery the user
    /// blames on the app. Same precedent as GaugeView and the builder's gauge strip.
    private func teardown() {
        timeout?.cancel()
        timeout = nil
        device.onTracePoint = nil
        if device.isStreaming { device.stopStreaming(cause: .screenClosed) }
    }
}

/// Keep sample-dependent tare liveness out of the full measurement screen.
private struct MaxTareButton: View {
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
            Text("There's \(promptedKg.formatted(.number.precision(.fractionLength(1)))) kg on the gauge. Zero it?")
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
    @Environment(DeviceStore.self) private var device
    let measurement: MaxMeasurement
    let phase: MaxMeasurePhase

    /// Scaled, never a bare point size — a fixed number renders pixel-identical at every
    /// accessibility setting while everything around it grows.
    @ScaledMetric(relativeTo: .largeTitle) private var heroSize: CGFloat = 76
    @ScaledMetric(relativeTo: .title3) private var unitSize: CGFloat = 21

    var body: some View {
        VStack(spacing: 4) {
            HStack(alignment: .firstTextBaseline, spacing: 6) {
                Text(measurement.hasResult
                     ? measurement.peakKg.formatted(.number.precision(.fractionLength(1)))
                     : "—")
                    .font(.system(size: heroSize, weight: .thin))
                    .displayTracking(heroSize)
                    .monospacedDigit()
                    // A measurement SNAPS rather than rolling: a numeric transition on a
                    // figure derived from a live signal reads as the app animating a
                    // number it is unsure of.
                    .contentTransition(.identity)
                Text("kg")
                    .font(.system(size: unitSize, weight: .regular))
                    .foregroundStyle(Ink.tertiary)
            }
            .foregroundStyle(measurement.hasResult ? Ink.primary : Ink.tertiary)
            .lineLimit(1)
            .minimumScaleFactor(0.6)

            LiveReadout()
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
        return String(localized: "\(measurement.peakKg.formatted(.number.precision(.fractionLength(1)))) kilograms, your hardest pull")
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
            return measurement.hasResult
                ? String(localized: "Save this as your max on this grip, or try again.")
                : String(localized: "No pull was recorded. You can close this or try again.")
        }
    }
}

/// The result is read at activation inside this leaf, never by the parent controls tree.
private struct MaxMeasurementUseButton: View {
    let measurement: MaxMeasurement
    var onUse: (Double) -> Void
    var onDismiss: () -> Void

    var body: some View {
        PrimaryGlassButton(title: String(localized: "Use this max"), systemImage: "checkmark",
                           tint: Accent.graphite) {
            onUse(measurement.peakKg)
            onDismiss()
        }
        .disabled(!measurement.hasResult)
    }
}

// MARK: - The live number

/// The instantaneous reading, isolated in its own view for ONE reason: `currentKg`
/// changes ~80 times a second, so every view that reads it re-renders at that rate.
/// Keeping it in a leaf means the hero, the controls and the guidance — none of which
/// change during a pull — are not dragged along with it.
private struct LiveReadout: View {
    @Environment(DeviceStore.self) private var device
    private var isLive: Bool { device.isStreaming && device.isSignalFresh }

    var body: some View {
        HStack(alignment: .firstTextBaseline, spacing: 3) {
            Text(isLive ? String(localized: "now") : String(localized: "No live reading"))
                .font(.system(.caption, weight: .medium))
                .foregroundStyle(Ink.tertiary)
            Text(isLive ? device.currentKg.formatted(.number.precision(.fractionLength(1))) : "—")
                .font(.system(.subheadline, weight: .semibold))
                .monospacedDigit()
                .contentTransition(.identity)
                .foregroundStyle(isLive ? StatusTint.engaged : Ink.tertiary)
            Text("kg")
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
/// The attempt itself is `@ObservationIgnored` and the display values are written ONLY
/// when they actually change. That matters more than it looks: samples arrive ~80 times
/// a second, and holding the attempt in `@State` would invalidate the view on every one
/// of them — the exact pattern that made the routine deck feel laggy. The peak climbs
/// during the ramp and then holds still, so the screen settles the moment the pull does.
@Observable
@MainActor
final class MaxMeasurement {
    private(set) var peakKg: Double = 0
    private(set) var isComplete = false

    @ObservationIgnored private var attempt = MaxAttempt()

    /// Mirrors `MaxAttempt.hasResult` off the same constant — a screen that offered to
    /// save a number the engine does not consider a pull would be the two disagreeing.
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

    /// Equality-guarded: Observation fires on every SET, not on every CHANGE, so an
    /// unguarded mirror would invalidate at the full sample rate and undo the whole
    /// point of this class.
    private func publish() {
        if peakKg != attempt.peakKg { peakKg = attempt.peakKg }
        if isComplete != attempt.isComplete { isComplete = attempt.isComplete }
    }
}
