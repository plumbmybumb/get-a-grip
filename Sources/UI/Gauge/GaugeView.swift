// SPDX-License-Identifier: MPL-2.0
// Original contributions Copyright 2026 Nuri Bruner.

import SwiftUI

/// The live force gauge — the runner's screen with no routine on it.
///
/// Some people use nothing else (feedback relayed by Nuri, 2026-09-20), so it has to be
/// one tap from Today and look like the app's working screen rather than the M1 proof
/// of hardware it started as. Same anatomy as `RunnerView`'s stacked layout, on purpose:
/// the trace IS the screen, the numbers sit on one Liquid Glass panel over a wash that
/// takes the colour of the state — bleu while reading, steel otherwise — and the actions
/// share one glass dock. It still shows the raw truth (current, peak, the one-second
/// mean, battery) because this is also the screen that retires every hardware risk in
/// the project.
///
/// Reached from the gauge button on Today's bar, as a full-screen cover —
/// `presentedAsCover` is what adds the Done item. The Settings row it used to sit behind
/// is gone (Nuri, 2026-09-20): one door, on the screen that opens every day. The DEBUG
/// `-previewGauge` launch still pushes it bare for screenshots.
struct GaugeView: View {
    var presentedAsCover = false

    @Environment(\.weightUnit) private var weightUnit
    @Environment(DeviceStore.self) private var device
    @Environment(\.dismiss) private var dismiss
    @Environment(\.dynamicTypeSize) private var typeSize

    /// Scaled, never a bare point size: a fixed number renders pixel-identical at
    /// every accessibility setting while the controls around it grow.
    @ScaledMetric(relativeTo: .largeTitle) private var heroSize: CGFloat = 78
    @ScaledMetric(relativeTo: .title3) private var unitSize: CGFloat = 22

    /// Bumped on each tare so `.sensoryFeedback` has a value to react to — the house
    /// pattern, rather than calling a feedback generator by hand.
    @State private var tareTick = 0
    @State private var promptedKg = 0.0
    @State private var promptedEpoch: UInt64 = 0
    @State private var showingTareConfirmation = false
    /// Where the open graph and the canvas lie, so the wash can hang from the top of the
    /// screen down to the graph's upper edge — the runner's own measurement.
    @State private var traceGeometry = BackgroundTraceGeometry()
    /// Armed a beat after Start: a "Waiting for the gauge" that appeared the instant the
    /// stream was asked for would flash on every tap before the first packet landed.
    @State private var waitingForSignal = false

    private static let dockSpacing: CGFloat = 8

    var body: some View {
        GeometryReader { geometry in
            if typeSize.isAccessibilitySize {
                // Keep every action reachable by scrolling rather than clipping labels
                // or shrinking the chosen type — the runner's rule.
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
        .onDisappear {
            // Never leave the device streaming behind us: it drains its own battery
            // and keeps the radio busy.
            if device.isStreaming { device.stopStreaming(cause: .screenClosed) }
        }
    }

    // MARK: - The stacked layout: the graph is the screen, the numbers are glass

    /// One `GlassEffectContainer` for the two glass surfaces, so Liquid Glass renders
    /// them in a single pass rather than blurring the live canvas twice.
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

    /// The state colour under the glass, from the top of the screen down to the open
    /// graph — measured INSIDE `ignoresSafeArea`, as the runner's is.
    private var wash: some View {
        PhaseWash(tint: tint, edge: .top, length: traceGeometry.regionTopInCanvas)
            .onGeometryChange(for: CGRect.self) { $0.frame(in: .global) } action: {
                traceGeometry.canvas = $0
            }
            .ignoresSafeArea()
            .allowsHitTesting(false)
    }

    /// Bleu while a reading is live, steel otherwise. Never alarm: a gauge that is not
    /// connected on the screen you connect it from is the starting state, not a fault.
    private var tint: Color {
        device.isStreaming ? StatusTint.engaged : StatusTint.calm
    }

    /// **The information panel** — hero, state, the three readouts and the calibration
    /// line on one glass surface. `accessibleGlass`, never raw `.glassEffect`: under
    /// Reduce Transparency it becomes an opaque card, the only way the numbers stay
    /// legible over a live curve.
    private var infoPanel: some View {
        VStack(spacing: 14) {
            // LEAVES, deliberately. `currentKg`, `peakKg` and `trace` all mutate on every
            // sample, and read from THIS body they would invalidate the whole screen
            // 80×/second — both glass surfaces included.
            GaugeHero(heroSize: heroSize, unitSize: unitSize)
            GaugeReadouts()
            // A remotely calibrated gauge can be connected and still have no force to
            // show. Frez's rule is to say why rather than display a guess, and this is
            // the one line that does so — only for the gauge that has a why.
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

    /// **The open graph** — the stretch between the panel and the dock where the curve
    /// runs in the clear, edge to edge sideways. It draws nothing itself beyond the two
    /// notices that belong on a graph: what to do when nothing is connected, and the
    /// warning when a connected gauge is not sending.
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

    /// **The dock** — every action on ONE glass surface, each in a quiet ink well, with
    /// the single tinted item the primary one: Start measuring in bleu, Stop in alarm,
    /// Connect in bleu while nothing is connected. Glass on glass is the one layering
    /// Liquid Glass asks you not to do, which is why the old `PrimaryGlassButton` is not
    /// in here.
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
                // cancellable here as everywhere connection is offered; every other
                // attempt keeps its busy state.
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

                // Always compiled in, never DEBUG-only: without hardware — in the
                // Simulator, or in App Review — this is the only way to see the app
                // actually work.
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
        // `.contain`, explicitly: an identifier on a bare container makes SwiftUI
        // COMBINE its children into one element — the runner's dock learned this.
        .accessibilityElement(children: .contain)
        .accessibilityIdentifier("gauge.dock")
    }

    private var connectTitle: String {
        if device.canCancelBroadcastSearch { return String(localized: "Cancel") }
        return device.state.isBusy ? device.state.label : String(localized: "Connect gauge")
    }

    /// Nil for every gauge that reports kilograms itself, and for a calibrated one once
    /// its coefficient is in hand — the readout is the answer then.
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
            // A monospaced decimal has no whitespace to wrap on, so at accessibility3 it
            // clipped rather than shrinking without this floor.
            .lineLimit(1)
            .minimumScaleFactor(0.6)

            CapsLabel(device.isStreaming ? String(localized: "Live") : String(localized: "Idle"))
        }
        .frame(maxWidth: .infinity)
        // A numeral changing 80×/sec is unusable under VoiceOver; the accessible
        // channel is the summary below, which announces on demand.
        .accessibilityHidden(true)
    }
}

/// The trace. `device.trace` grows on every sample; nothing else here does. Drawn LIT,
/// as the runner's is: on an open screen a flat line and dashed rules read as chart
/// furniture, and a curve you look at while you pull should look like the thing being
/// measured.
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

/// Peak, the one-second mean and battery, as three quiet columns inside the panel. The
/// mean walks the whole trace, so it very much wants to be alone in here.
private struct GaugeReadouts: View {
    @Environment(\.weightUnit) private var weightUnit
    @Environment(DeviceStore.self) private var device

    /// Rolling one-second average of the live stream — the number you actually read
    /// when checking a steady hold. nil when idle, so the column shows "—" rather than
    /// a stale mean frozen from the last stream.
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
            // The one-second mean is the reading the flickering hero number can't give
            // you: hang steady, read the average. (The firmware version used to sit here
            // — diagnostics trivia on a screen you open to MEASURE things; it still lives
            // in Settings.)
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
