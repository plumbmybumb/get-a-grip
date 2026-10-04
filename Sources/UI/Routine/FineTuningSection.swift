// SPDX-License-Identifier: MPL-2.0
// Original contributions Copyright 2026 Nuri Bruner.

import SwiftUI

/// FINE TUNING — the settings setup does not ask about, laid OPEN at the foot of the
/// Schedule page (Nuri, 2026-10-04).
///
/// It was a disclosure, collapsed on every open, and the three controls it hid were the
/// ones people went looking for. Open costs no page and no step in the create walk:
/// it sits below everything a new routine needs, so scrolling past it is free.
struct FineTuningSection: View, Equatable {
    @Environment(\.weightUnit) private var weightUnit
    /// The write path. Everything drawn comes from `defaults` — see `BuilderInputs`.
    let access: DraftAccess
    /// `plan.routineLevel`, as a value, so the card compares on what it shows
    /// (`fineTuningKey`). The one card that shows the pull threshold.
    let defaults: SessionPlan

    nonisolated static func == (a: Self, b: Self) -> Bool {
        a.defaults.fineTuningKey == b.defaults.fineTuningKey
    }

    @Environment(\.dynamicTypeSize) private var typeSize

    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            // The same plain caps row as HOW OFTEN above it.
            CapsLabel(String(localized: "FINE TUNING"))
                .padding(.leading, 6)

            MaterialCard(surface: .flat) {
                VStack(alignment: .leading, spacing: 18) {
                    thresholdBlock
                    bandGateBlock
                    leadInBlock
                }
            }
        }
    }

    // MARK: - Threshold

    private var thresholdBlock: some View {
        VStack(alignment: .leading, spacing: 10) {
            Text("A pull counts above")
                .font(.system(.subheadline, weight: .semibold))
                .foregroundStyle(Ink.primary)

            ValueRow(title: String(localized: "A pull counts above"),
                     unit: weightUnit.symbol,
                     value: weightUnit.binding(access.binding(\.plan.thresholdKg, current: defaults.thresholdKg)),
                     range: weightUnit.sliderRangeFromKg(0.5...10), limit: weightUnit.rangeFromKg(0.5...SessionPlan.thresholdRange.upperBound),
                     step: 0.5,
                     presets: weightUnit == .kg ? [1, 2, 3, 5] : [2, 5, 7, 10],
                     decimals: 1)

            ThresholdGaugeStrip(thresholdKg: defaults.thresholdKg)
        }
        .accessibilityElement(children: .contain)
        .accessibilityLabel(String(localized: "A pull counts above"))
    }

    // MARK: - Does the band stop the clock

    /// Which edges of the target range pause the rep.
    ///
    /// Between the threshold and the lead-in: all three answer "what counts as a pull", and
    /// this is the range's half where the threshold is the floor's. Three choices, not a
    /// switch (GitHub issue, 2026-10-01): "below" lets an overshoot count without giving up
    /// the floor. One native segmented control; the wrapping-label menu at accessibility
    /// sizes, where three segments would truncate.
    private var bandGateBlock: some View {
        let selection = access.binding(\.plan.targetBandGate, current: defaults.targetBandGate)
        return VStack(alignment: .leading, spacing: 10) {
            Text("Pause the clock")
                .font(.system(.subheadline, weight: .semibold))
                .foregroundStyle(Ink.primary)
            if typeSize.isAccessibilitySize {
                Picker(String(localized: "Pause the clock"), selection: selection) {
                    ForEach(TargetBandGate.allCases, id: \.self) { Text($0.title).tag($0) }
                }
                .pickerStyle(.menu)
                .tint(Accent.graphite)
            } else {
                Picker(String(localized: "Pause the clock"), selection: selection) {
                    ForEach(TargetBandGate.allCases, id: \.self) { Text($0.title).tag($0) }
                }
                .pickerStyle(.segmented)
            }
        }
        .sensoryFeedback(.selection, trigger: defaults.targetBandGate)
    }

    // MARK: - Lead-in

    private var leadInBlock: some View {
        VStack(alignment: .leading, spacing: 10) {
            Text("Lead-in before each set")
                .font(.system(.subheadline, weight: .semibold))
                .foregroundStyle(Ink.primary)

            IntValueRow(title: String(localized: "Lead-in before each set"),
                        unit: String(localized: "s"),
                        value: access.binding(\.plan.leadInSeconds, current: defaults.leadInSeconds),
                        range: 0...20, limit: 0...60,
                        step: 5,
                        presets: [0, 3, 5, 10])
        }
        .accessibilityElement(children: .contain)
        .accessibilityLabel(String(localized: "Lead-in before each set"))
    }
}

// MARK: - The one place the builder touches BLE

/// A live force bar with the threshold marked, so "2 kg" can be FELT, not guessed.
///
/// Its own small view: the only Bluetooth in the builder, deletable or movable without
/// opening the document.
private struct ThresholdGaugeStrip: View {
    var thresholdKg: Double

    @Environment(DeviceStore.self) private var device

    @State private var checking = false
    @State private var countdown: Task<Void, Never>?

    /// Long enough to load, let go and retry; short enough that a forgotten check cannot
    /// flatten the battery.
    private static let checkSeconds = 15

    var body: some View {
        VStack(alignment: .leading, spacing: 10) {
            if device.state.isConnected {
                if checking {
                    // LEAVES — see `ThresholdReadout`/`ThresholdBar`. As computed properties
                    // here, reading `device.currentKg` re-ran this whole body at sample rate
                    // for the 15 s of a check (the miss `GaugeView` fixed in its own leaves).
                    ThresholdReadout(thresholdKg: thresholdKg)
                    ThresholdBar(thresholdKg: thresholdKg)
                    SecondaryGlassButton(title: String(localized: "Stop"), systemImage: "stop.fill") {
                        stop(cause: .userStopped)
                    }
                } else {
                    SecondaryGlassButton(title: String(localized: "Check on the gauge"),
                                         systemImage: "waveform.path.ecg") { start() }
                }
            } else {
                // SHOWN, not a disabled button: a control you cannot use teaches nothing.
                Text("Connect your gauge to check it.")
                    .font(.system(.caption, weight: .medium))
                    .foregroundStyle(Ink.tertiary)
                    .fixedSize(horizontal: false, vertical: true)
            }
        }
        .onDisappear {
            // UNCONDITIONAL, gated on the DEVICE's own truth rather than `checking`: a
            // Progressor streaming behind a dismissed sheet is a dead battery blamed on
            // the app.
            countdown?.cancel()
            countdown = nil
            checking = false
            if device.isStreaming { device.stopStreaming(cause: .screenClosed) }
        }
    }

    // MARK: - Streaming

    private func start() {
        guard device.state.isConnected else { return }
        // The bar is peak-relative; a stale peak would draw this pull as a stub.
        device.resetPeak()
        device.startStreaming(cause: .manualMeasurement)
        checking = true

        countdown?.cancel()
        countdown = Task {
            try? await Task.sleep(for: .seconds(Self.checkSeconds))
            guard !Task.isCancelled else { return }
            stop(cause: .timedOut)
        }
    }

    /// The cause travels from the TRIGGER (Stop button or countdown), or a deliberate stop
    /// would be logged as a timeout.
    private func stop(cause: StreamStopCause) {
        countdown?.cancel()
        countdown = nil
        checking = false
        if device.isStreaming { device.stopStreaming(cause: cause) }
    }
}

/// The live numeral and Counting/Stopped word, a leaf so `device.currentKg` invalidates
/// only this.
private struct ThresholdReadout: View {
    @Environment(\.weightUnit) private var weightUnit
    var thresholdKg: Double

    @Environment(DeviceStore.self) private var device

    /// HYSTERETIC, not a bare `>=`: the check parks a load AT the threshold, where noise
    /// flips a bare comparison many times a second (a buzzing haptic, a flickering word).
    /// The runner's release-band remedy: cross up at the threshold, back down only 5 %
    /// below, clamped 0.5–2.0 kg.
    @State private var crossed = false

    private func updateCrossing(_ kg: Double) {
        let band = min(max(thresholdKg * 0.05, 0.5), 2.0)
        if kg >= thresholdKg {
            crossed = true
        } else if kg < thresholdKg - band {
            crossed = false
        }
    }

    var body: some View {
        HStack(alignment: .firstTextBaseline, spacing: 4) {
            Text(weightUnit.number(device.currentKg))
                .font(.system(.subheadline, weight: .medium))
                .monospacedDigit()
                .foregroundStyle(crossed ? StatusTint.engaged : Ink.primary)
            Text(weightUnit.symbol)
                .font(.system(.caption))
                .foregroundStyle(Ink.tertiary)
            Spacer(minLength: 8)
            // A WORD as well as a colour: the state has to survive greyscale.
            CapsLabel(crossed ? String(localized: "Counting") : String(localized: "Stopped"),
                      tint: crossed ? StatusTint.engaged : Ink.tertiary)
        }
        // A numeral changing 80×/sec is unusable under VoiceOver; the crossing is
        // felt instead — the only channel here, since no audio cue fires.
        .accessibilityHidden(true)
        .sensoryFeedback(.selection, trigger: crossed)
        .onChange(of: device.currentKg) { _, kg in updateCrossing(kg) }
    }
}

/// The live force bar with the threshold marked; a leaf, like `ThresholdReadout`.
private struct ThresholdBar: View {
    var thresholdKg: Double

    @Environment(DeviceStore.self) private var device
    @ScaledMetric(relativeTo: .body) private var barHeight: CGFloat = 26

    private var isOver: Bool { device.currentKg >= thresholdKg }

    /// Peak-relative, and PEAK only grows within a check, so the scale only settles outward;
    /// keyed to the live reading it would jitter the threshold marker, which must hold still.
    private var ceiling: Double {
        max(10, device.peakKg * 1.25, thresholdKg * 1.6)
    }

    var body: some View {
        GeometryReader { geo in
            let fraction = min(max(device.currentKg, 0) / ceiling, 1)
            let markerX = min(max(thresholdKg / ceiling, 0), 1) * geo.size.width

            ZStack(alignment: .leading) {
                Capsule().fill(Ink.tertiary.opacity(0.18))
                // A full-bleed capsule MASKED to the fraction, not a narrower capsule (as
                // with the hold-to-end fill): a width-constrained capsule degenerates to a
                // blob at small fractions and reads as a different shape from the track.
                Capsule()
                    .fill(isOver ? StatusTint.engaged : StatusTint.calm)
                    .mask(alignment: .leading) {
                        Rectangle()
                            .frame(width: max(2, fraction * geo.size.width))
                            .frame(maxWidth: .infinity, alignment: .leading)
                    }
                // Over the fill, so it stays visible once passed.
                Rectangle()
                    .fill(Ink.primary.opacity(0.8))
                    .frame(width: 2)
                    .offset(x: markerX - 1)
            }
            .animation(Motion.live, value: device.currentKg)
        }
        .frame(height: barHeight)
        .accessibilityHidden(true)
    }
}

extension TargetBandGate {
    /// The segment's words, under the "Pause the clock" label.
    var title: String {
        switch self {
        case .outside: String(localized: "Out of range")
        case .below:   String(localized: "Below range")
        case .off:     String(localized: "Never")
        }
    }
}
