// SPDX-License-Identifier: MPL-2.0
// Original contributions Copyright 2026 Nuri Bruner.

import SwiftUI

/// The live force gauge — the runner's screen with no routine on it.
///
/// Some people use nothing else (2026-09-20), so it is one tap from Today and looks like
/// the app's working screen. Same anatomy as `RunnerView`'s stacked layout: the trace IS
/// the screen, numbers on one Liquid Glass panel over a state-coloured wash (bleu while
/// reading, steel otherwise), actions in one glass dock. It still shows the raw truth
/// (current, peak, one-second mean, battery): this screen retires every hardware risk.
///
/// A full-screen cover from Today's bar; `presentedAsCover` adds Done. The DEBUG
/// `-previewGauge` launch pushes it bare for screenshots.
struct GaugeView: View {
    var presentedAsCover = false

    @Environment(\.weightUnit) private var weightUnit
    @Environment(DeviceStore.self) private var device
    @Environment(\.dismiss) private var dismiss
    @Environment(\.dynamicTypeSize) private var typeSize

    /// Scaled, never a bare point size, or it stays fixed while the controls around it grow.
    @ScaledMetric(relativeTo: .largeTitle) private var heroSize: CGFloat = 78
    @ScaledMetric(relativeTo: .title3) private var unitSize: CGFloat = 22

    /// Bumped per tare so `.sensoryFeedback` has a value to react to — the house pattern.
    @State private var tareTick = 0
    @State private var promptedKg = 0.0
    @State private var promptedEpoch: UInt64 = 0
    @State private var showingTareConfirmation = false
    /// Where the open graph and canvas lie, so the wash hangs down to the graph's upper edge.
    @State private var traceGeometry = BackgroundTraceGeometry()
    /// Armed a beat after Start, or "Waiting for the gauge" flashes on every tap before the
    /// first packet.
    @State private var waitingForSignal = false

    private static let dockSpacing: CGFloat = 8

    var body: some View {
        GeometryReader { geometry in
            if typeSize.isAccessibilitySize {
                // Scroll rather than clip labels or shrink type — the runner's rule.
                ScrollView {
                    content.frame(minHeight: geometry.size.height, alignment: .top)
                }
                .scrollBounceBehavior(.basedOnSize)
            } else {
                content.frame(width: geometry.size.width, height: geometry.size.height)
            }
        }
        .background { AppBackground() }
        .navigationTitle("Gauge")
        .navigationBarTitleDisplayMode(.inline)
        .toolbar {
            if presentedAsCover {
                ToolbarItem(placement: .cancellationAction) {
                    Button("Done") { dismiss() }
                        .tint(Accent.graphite)
                        .accessibilityIdentifier("gauge.done")
                }
            }
        }
        .sensoryFeedback(.impact(weight: .medium, intensity: 0.7), trigger: tareTick)
        .alert("Zero the gauge?", isPresented: $showingTareConfirmation) {
            Button("Zero it", role: .destructive) { confirmTare() }
            Button("Cancel", role: .cancel) {}
        } message: {
            Text(String(localized: "There's \(weightUnit.number(promptedKg)) \(weightUnit.symbol) on the gauge. Zero it?"))
        }
        .task(id: device.isStreaming) {
            waitingForSignal = false
            guard device.isStreaming else { return }
            try? await Task.sleep(for: .seconds(1.5))
            guard !Task.isCancelled else { return }
            waitingForSignal = true
        }
        .keepsScreenAwake()
        .onDisappear {
            // Never leave the device streaming: it drains its battery and the radio.
            if device.isStreaming { device.stopStreaming(cause: .screenClosed) }
        }
    }

    // MARK: - The stacked layout: the graph is the screen, the numbers are glass

    /// One `GlassEffectContainer` for both glass surfaces, so the live canvas is blurred in
    /// one pass, not twice.
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
        // The phone's width even on a regular-width screen — the runner's column rule.
        .frame(maxWidth: Metrics.maxContentWidth)
        .frame(maxWidth: .infinity)
        .background { wash }
    }

    /// The state colour under the glass, from the screen's top to the open graph — measured
    /// INSIDE `ignoresSafeArea`, as the runner's is.
    private var wash: some View {
        PhaseWash(tint: tint, edge: .top, length: traceGeometry.regionTopInCanvas)
            .onGeometryChange(for: CGRect.self) { $0.frame(in: .global) } action: {
                traceGeometry.canvas = $0
            }
            .ignoresSafeArea()
            .allowsHitTesting(false)
    }

    /// Bleu while a reading is live, steel otherwise. Never alarm: not connected is the
    /// starting state here, not a fault.
    private var tint: Color {
        device.isStreaming ? StatusTint.engaged : StatusTint.calm
    }

    /// **The information panel** — hero, state, three readouts and the calibration line on
    /// one glass surface. `accessibleGlass`, never raw `.glassEffect`: under Reduce
    /// Transparency it goes opaque, the only way the numbers stay legible over a live curve.
    private var infoPanel: some View {
        VStack(spacing: 14) {
            // LEAVES: `currentKg`, `peakKg` and `trace` mutate per sample, and read from
            // THIS body would invalidate the whole screen 80×/second.
            GaugeHero(heroSize: heroSize, unitSize: unitSize)
            GaugeReadouts()
            // A remotely calibrated gauge can be connected with no force to show.
            // Frez's rule is to say why rather than guess.
            if let calibrationNote {
                Text(calibrationNote)
                    .font(.system(.footnote))
                    .foregroundStyle(device.calibrationStatus.isReady ? Ink.tertiary : StatusTint.armed)
                    .multilineTextAlignment(.center)
                    .fixedSize(horizontal: false, vertical: true)
                    .frame(maxWidth: .infinity)
                    .accessibilityIdentifier("gauge.calibration")
            }
        }
        .padding(.horizontal, 16)
        .padding(.top, 14)
        .padding(.bottom, 14)
        .frame(maxWidth: .infinity)
        .accessibleGlass(nil, in: RunnerGlass.surfaceShape)
        .runnerFloatingShadow()
        .accessibilityElement(children: .contain)
        .accessibilityIdentifier("gauge.panel")
    }

    /// **The open graph** — between panel and dock, the curve runs in the clear. It draws
    /// only the two notices that belong on a graph: what to do when nothing is connected,
    /// and the warning when a connected gauge is silent.
    private var graphRegion: some View {
        ZStack {
            Color.clear
            if !device.state.isConnected {
                notice(String(localized: "Connect a gauge and pull — the force draws here."))
            } else if device.isStreaming, waitingForSignal, !device.isSignalFresh {
                notice(String(localized: "Waiting for the gauge. It's connected but not sending — try Wake."))
                    .accessibilityIdentifier("gauge.signalWarning")
            }
        }
        .frame(minHeight: typeSize.isAccessibilitySize ? 200 : nil, maxHeight: .infinity)
        .background {
            GaugeTrace(plot: ForceTraceView.PlotInsets(top: 12, bottom: 6, trailing: 8))
                .padding(.horizontal, -Metrics.hPadding)
        }
        .onGeometryChange(for: CGRect.self) { $0.frame(in: .global) } action: {
            traceGeometry.region = $0
        }
        .accessibilityElement(children: .contain)
        .accessibilityIdentifier("gauge.graph")
    }

    private func notice(_ text: String) -> some View {
        Text(text)
            .font(.system(.subheadline))
            .foregroundStyle(Ink.secondary)
            .multilineTextAlignment(.center)
            .fixedSize(horizontal: false, vertical: true)
            .padding(.horizontal, 24)
    }

    // MARK: - The dock

    /// **The dock** — every action on ONE glass surface, each in a quiet ink well, the
    /// primary one tinted (Start in bleu, Stop in alarm, Connect in bleu). No
    /// `PrimaryGlassButton` in here: glass on glass is the layering Liquid Glass forbids.
    private var dock: some View {
        VStack(spacing: Self.dockSpacing) {
            if device.state.isConnected {
                AdaptiveActionRow(spacing: Self.dockSpacing) {
                    DockButton(device.isReadingLive ? String(localized: "Tare") : String(localized: "Wake"),
                               systemImage: device.isReadingLive ? "arrow.counterclockwise" : "arrow.clockwise",
                               fillsRowHeight: true) {
                        requestTare()
                    }
                    .accessibilityIdentifier("gauge.tare")
                    DockButton(String(localized: "Disconnect"), fillsRowHeight: true) { device.disconnect() }
                        .accessibilityIdentifier("gauge.disconnect")
                }
                DockTintedButton(device.isStreaming ? String(localized: "Stop") : String(localized: "Start measuring"),
                                 systemImage: device.isStreaming ? "stop.fill" : "play.fill",
                                 tint: device.isStreaming ? .alarm : .bleu) {
                    device.isStreaming
                        ? device.stopStreaming(cause: .userStopped)
                        : device.startStreaming(cause: .manualMeasurement)
                }
                .accessibilityIdentifier("gauge.measure")
            } else {
                // A broadcast search can wait for the scale to wake, so it stays
                // cancellable; every other attempt keeps its busy state.
                DockTintedButton(connectTitle,
                                 systemImage: device.canCancelBroadcastSearch ? "xmark" : "dot.radiowaves.left.and.right",
                                 tint: .bleu,
                                 enabled: !device.state.isBusy || device.canCancelBroadcastSearch) {
                    if device.canCancelBroadcastSearch {
                        device.disconnect()
                    } else if !device.state.isBusy, !device.state.isConnected {
                        device.connect()
                    }
                }
                .accessibilityLabel(connectTitle)
                .accessibilityValue(device.canCancelBroadcastSearch ? device.state.label : "")
                .accessibilityIdentifier("gauge.connectionAction")

                // Always compiled in, never DEBUG-only: without hardware (Simulator, App
                // Review) this is the only way to see the app work.
                DockButton(device.isMock ? String(localized: "Leave demo mode") : String(localized: "Try demo mode"),
                           tint: Ink.secondary) {
                    device.useMockDevice(!device.isMock)
                }

                if case .unsupported = device.state {
                    Text("This device has no Bluetooth radio. Use demo mode to look around.")
                        .font(.system(.footnote))
                        .foregroundStyle(Ink.tertiary)
                        .multilineTextAlignment(.center)
                        .fixedSize(horizontal: false, vertical: true)
                        .padding(.horizontal, 8)
                        .padding(.bottom, 4)
                }
            }
        }
        .padding(Self.dockSpacing)
        .accessibleGlass(nil, in: RunnerGlass.surfaceShape)
        .runnerFloatingShadow()
        // `.contain`, explicitly: an identifier on a bare container COMBINES its
        // children into one element.
        .accessibilityElement(children: .contain)
        .accessibilityIdentifier("gauge.dock")
    }

    private var connectTitle: String {
        if device.canCancelBroadcastSearch { return String(localized: "Cancel") }
        return device.state.isBusy ? device.state.label : String(localized: "Connect gauge")
    }

    /// Nil for gauges that report kilograms, and for a calibrated one once its coefficient
    /// is in hand.
    private var calibrationNote: String? {
        switch device.calibrationStatus {
        case .notRequired, .ready:
            nil
        case .waitingForSerial, .resolving:
            String(localized: "Looking up this Dyno's calibration…")
        case .failed(_, let failure):
            failure.label
        }
    }

    // MARK: - Tare

    private func requestTare() {
        guard device.state.isConnected else { return }
        guard TarePolicy.isSafeToTareNow(sampleAge: device.secondsSinceLastSample(),
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

// MARK: - Live leaves

/// The big number. Its own view so a sample redraws THIS and nothing around it.
private struct GaugeHero: View {
    @Environment(\.weightUnit) private var weightUnit
    @Environment(DeviceStore.self) private var device
    var heroSize: CGFloat
    var unitSize: CGFloat

    var body: some View {
        VStack(spacing: 4) {
            HStack(alignment: .firstTextBaseline, spacing: 6) {
                Text(weightUnit.number(device.currentKg))
                    .font(.system(size: heroSize, weight: .thin))
                    .displayTracking(heroSize)
                    .monospacedDigit()
                    // A measurement snaps; see RunnerView.readout.
                    .contentTransition(.identity)
                Text(weightUnit.symbol)
                    .font(.system(size: unitSize, weight: .regular))
                    .foregroundStyle(Ink.tertiary)
            }
            .foregroundStyle(device.isStreaming ? StatusTint.engaged : Ink.primary)
            .animation(Motion.live, value: device.currentKg)
            // A monospaced decimal cannot wrap, so without this floor it clipped at AX3.
            .lineLimit(1)
            .minimumScaleFactor(0.6)

            CapsLabel(device.isStreaming ? String(localized: "Live") : String(localized: "Idle"))
        }
        .frame(maxWidth: .infinity)
        // A numeral changing 80×/sec is unusable under VoiceOver; the summary below
        // is the accessible channel.
        .accessibilityHidden(true)
    }
}

/// The trace. `device.trace` grows per sample; nothing else here does. Drawn LIT, like
/// the runner's — see `ForceTraceView.lit`.
private struct GaugeTrace: View {
    @Environment(DeviceStore.self) private var device
    var plot: ForceTraceView.PlotInsets

    var body: some View {
        ForceTraceView(samples: device.trace,
                       tint: device.isStreaming ? StatusTint.engaged : Ink.tertiary,
                       nominalSampleRate: device.gaugeCapabilities.nominalSampleRate,
                       bridgesSparseDelivery: device.gaugeCapabilities.isBroadcast,
                       diagnostics: device.pipelineDiagnostics,
                       plot: plot, lit: true)
    }
}

/// Peak, one-second mean and battery, as three quiet columns. The mean walks the whole
/// trace, so it wants to be alone in here.
private struct GaugeReadouts: View {
    @Environment(\.weightUnit) private var weightUnit
    @Environment(DeviceStore.self) private var device

    /// Rolling one-second average — the number you read when checking a steady hold. nil
    /// when idle, so the column shows "—" rather than a stale mean.
    private var averageKg: Double? {
        guard device.isStreaming, let newest = device.trace.last else { return nil }
        var sum = 0.0, count = 0.0
        for sample in device.trace.reversed() {
            // Playback-time age, same convention as the trace's own drawing.
            guard newest.t - sample.t <= 1.0 else { break }
            sum += sample.kg
            count += 1
        }
        return count > 0 ? sum / count : nil
    }

    var body: some View {
        let average = averageKg
        return HStack(spacing: 8) {
            readout(String(localized: "Peak"), value: weightUnit.number(device.peakKg), unit: weightUnit.symbol)
            // The reading the flickering hero can't give: hang steady, read the
            // average. (Firmware version lives in Settings.)
            readout(String(localized: "Average"),
                    value: average.map { weightUnit.number($0) } ?? "—",
                    unit: average == nil ? "" : weightUnit.symbol)
            readout(String(localized: "Battery"),
                    value: device.batteryFraction.map { "\(BatteryDisplay.percentage($0))" } ?? "—",
                    unit: device.batteryFraction == nil ? "" : String(localized: "%"))
        }
        .accessibilityElement(children: .combine)
        .accessibilityLabel("""
            Current \(weightUnit.number(device.currentKg)) \(weightUnit.spokenName), \
            peak \(weightUnit.number(device.peakKg)) \(weightUnit.spokenName)\
            \(average.map { String(localized: ", one-second average \(weightUnit.number($0)) \(weightUnit.spokenName)") } ?? "")
            """)
    }

    private func readout(_ title: String, value: String, unit: String) -> some View {
        VStack(spacing: 4) {
            CapsLabel(title)
            HStack(alignment: .firstTextBaseline, spacing: 2) {
                Text(value)
                    .font(.system(.title3, weight: .medium))
                    .monospacedDigit()
                    // A measurement snaps; see RunnerView.readout.
                    .contentTransition(.identity)
                if !unit.isEmpty {
                    Text(unit).font(.system(.caption)).foregroundStyle(Ink.tertiary)
                }
            }
            .foregroundStyle(Ink.primary)
            .lineLimit(1)
            .minimumScaleFactor(0.7)
        }
        .frame(maxWidth: .infinity)
    }
}
