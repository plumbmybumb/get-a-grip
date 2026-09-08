// SPDX-License-Identifier: MPL-2.0
// Original contributions Copyright 2026 Nuri Bruner.

import SwiftUI

/// The live force gauge — M1's proof that the whole hardware path works: connect,
/// tare, stream, decode, draw.
///
/// It is also the screen that retires every hardware risk in the project, so it
/// deliberately shows the raw truth (current, peak, firmware, battery) rather than
/// a prettified summary.
struct GaugeView: View {
    @Environment(\.weightUnit) private var weightUnit
    @Environment(DeviceStore.self) private var device
    @Environment(\.dismiss) private var dismiss

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

    var body: some View {
        VStack(spacing: 18) {
            // LEAVES, deliberately. `currentKg`, `peakKg` and `trace` all mutate on every
            // sample, and read from THIS body they invalidated the whole screen 80×/second
            // — three material cards and the glass controls included. `RunnerView` already
            // solved exactly this with `LiveForceReadout`/`LiveTrace`; this is the same
            // fix, applied where it was missed.
            GaugeHero(heroSize: heroSize, unitSize: unitSize)
            GaugeTrace()
            GaugeReadouts()
            Spacer(minLength: 0)
            controls
        }
        .padding(.horizontal, Metrics.hPadding)
        .padding(.bottom, Metrics.spacing)
        .frame(maxWidth: Metrics.maxContentWidth)
        .frame(maxWidth: .infinity)
        .background { AppBackground() }
        .navigationTitle("Gauge")
        .navigationBarTitleDisplayMode(.inline)
        .sensoryFeedback(.impact(weight: .medium, intensity: 0.7), trigger: tareTick)
        .alert("Zero the gauge?", isPresented: $showingTareConfirmation) {
            Button("Zero it", role: .destructive) { confirmTare() }
            Button("Cancel", role: .cancel) {}
        } message: {
            Text(String(localized: "There's \(weightUnit.number(promptedKg)) \(weightUnit.symbol) on the gauge. Zero it?"))
        }
        .onDisappear {
            // Never leave the device streaming behind us: it drains its own battery
            // and keeps the radio busy.
            if device.isStreaming { device.stopStreaming(cause: .screenClosed) }
        }
    }

    // MARK: - Controls

    @ViewBuilder
    private var controls: some View {
        if device.state.isConnected {
            VStack(spacing: 12) {
                HStack(spacing: 12) {
                    SecondaryGlassButton(title: device.isReadingLive ? String(localized: "Tare") : String(localized: "Wake"),
                                         systemImage: "arrow.counterclockwise") { requestTare() }
                    Spacer(minLength: 0)
                    SecondaryGlassButton(title: String(localized: "Disconnect")) { device.disconnect() }
                }
                PrimaryGlassButton(
                    title: device.isStreaming ? String(localized: "Stop") : String(localized: "Start measuring"),
                    systemImage: device.isStreaming ? "stop.fill" : "play.fill",
                    tint: device.isStreaming ? Accent.alarm : Accent.bleu
                ) {
                    device.isStreaming
                        ? device.stopStreaming(cause: .userStopped)
                        : device.startStreaming(cause: .manualMeasurement)
                }
            }
        } else {
            VStack(spacing: 12) {
                GaugeConnectButton(connectTitle: String(localized: "Connect gauge"))

                if device.isMock {
                    Button("Leave demo mode") { device.useMockDevice(false) }
                        .buttonStyle(PressFeedbackButtonStyle())
                        .font(.system(.subheadline, weight: .semibold))
                        .foregroundStyle(Ink.secondary)
                        .frame(maxWidth: .infinity, minHeight: 44)
                        .contentShape(.rect)
                } else {
                    // Always compiled in, never DEBUG-only: without hardware — in the
                    // Simulator, or in App Review — this is the only way to see the
                    // app actually work.
                    Button("Try demo mode") { device.useMockDevice(true) }
                        .buttonStyle(PressFeedbackButtonStyle())
                        .font(.system(.subheadline, weight: .semibold))
                        .foregroundStyle(Ink.secondary)
                        .frame(maxWidth: .infinity, minHeight: 44)
                        .contentShape(.rect)
                }

                if case .unsupported = device.state {
                    Text("This device has no Bluetooth radio. Use demo mode to look around.")
                        .font(.system(.footnote))
                        .foregroundStyle(Ink.tertiary)
                        .multilineTextAlignment(.center)
                }
            }
        }
    }

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
        VStack(spacing: 2) {
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
            // The one hero in the app with no shrink floor before this: a monospaced
            // decimal has no whitespace to wrap on, so at accessibility3 it clipped
            // rather than shrinking. `MaxMeasurementHero` and `GaugeReadouts.readout()`
            // in this same file both already guard the identical job.
            .lineLimit(1)
            .minimumScaleFactor(0.6)

            CapsLabel(device.isStreaming ? String(localized: "Live") : String(localized: "Idle"))
        }
        .frame(maxWidth: .infinity)
        .padding(.top, 8)
        // A numeral changing 80×/sec is unusable under VoiceOver; the accessible
        // channel is the summary below, which announces on demand.
        .accessibilityHidden(true)
    }
}

/// The trace. `device.trace` grows on every sample; nothing else here does.
private struct GaugeTrace: View {
    @Environment(DeviceStore.self) private var device

    var body: some View {
        ForceTraceView(samples: device.trace,
                       tint: device.isStreaming ? StatusTint.engaged : Ink.tertiary,
                       nominalSampleRate: device.gaugeCapabilities.nominalSampleRate,
                       bridgesSparseDelivery: device.gaugeCapabilities.isBroadcast,
                       diagnostics: device.pipelineDiagnostics)
            .frame(height: 190)
            .padding(.horizontal, 4)
            .background(.regularMaterial,
                        in: RoundedRectangle(cornerRadius: Metrics.radiusCard, style: .continuous))
    }
}

/// Peak, battery and the one-second mean. The mean walks the whole trace, so it very
/// much wants to be alone in here.
private struct GaugeReadouts: View {
    @Environment(\.weightUnit) private var weightUnit
    @Environment(DeviceStore.self) private var device

    /// Rolling one-second average of the live stream — the number you actually read
    /// when checking a steady hold. nil when idle, so the box shows "—" rather than
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
        return HStack(spacing: 10) {
            readout(String(localized: "Peak"), value: weightUnit.number(device.peakKg), unit: weightUnit.symbol)
            readout(String(localized: "Battery"),
                    value: device.batteryFraction.map { "\(BatteryDisplay.percentage($0))" } ?? "—",
                    unit: device.batteryFraction == nil ? "" : String(localized: "%"))
            // Was the firmware version — diagnostics trivia on a screen you open to
            // MEASURE things (and it still lives in Settings). The one-second mean is
            // the reading the flickering hero number can't give you: hang steady,
            // read the average.
            readout(String(localized: "Average"),
                    value: average.map { weightUnit.number($0) } ?? "—",
                    unit: average == nil ? "" : weightUnit.symbol)
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
        .padding(.vertical, 12)
        .background(.regularMaterial,
                    in: RoundedRectangle(cornerRadius: Metrics.radiusInner, style: .continuous))
    }
}
