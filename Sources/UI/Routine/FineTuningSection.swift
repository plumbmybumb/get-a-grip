// SPDX-License-Identifier: MPL-2.0
// Original contributions Copyright 2026 Nuri Bruner.

import SwiftUI

/// FINE TUNING — the two settings that are not one of the things setup asks about,
/// folded away behind a row that still says out loud that they exist.
///
/// Collapsed on EVERY open and never persisted: the Schengen "What this counts"
/// discipline, which hides the WORDS rather than the fact that there is a setting. That
/// is why the row keeps a title and a summary line on its face instead of being a bare
/// chevron — someone who has never opened it still knows what is in there.
struct FineTuningSection: View {
    @Binding var draft: RoutineDraft

    /// View-local and unpersisted BY CONSTRUCTION — the sheet builds a fresh section
    /// every time it opens, so "collapsed on every open" needs no resetting logic.
    @State private var isOpen = false
    @Environment(\.accessibilityReduceMotion) private var reduceMotion

    var body: some View {
        MaterialCard {
            VStack(alignment: .leading, spacing: 18) {
                header

                if isOpen {
                    thresholdBlock
                        .transition(.opacity.combined(with: .move(edge: .top)))
                    bandGateBlock
                        .transition(.opacity.combined(with: .move(edge: .top)))
                    leadInBlock
                        .transition(.opacity.combined(with: .move(edge: .top)))
                }
            }
        }
    }

    // MARK: - The disclosure row

    private var header: some View {
        Button {
            withAnimation(Motion.state(reduceMotion)) {
                isOpen.toggle()
            }
        } label: {
            HStack(spacing: 10) {
                Image(systemName: "slider.horizontal.3")
                    .font(.system(.footnote, weight: .semibold))
                    .foregroundStyle(Accent.graphite)
                    .frame(width: 20)

                VStack(alignment: .leading, spacing: 2) {
                    Text("Fine tuning")
                        .font(.system(.subheadline, weight: .semibold))
                        .foregroundStyle(Ink.primary)
                    if !isOpen {
                        Text("What counts as a pull, whether the range pauses you, and the lead-in.")
                            .font(.system(.caption, weight: .medium))
                            .foregroundStyle(Ink.secondary)
                            .fixedSize(horizontal: false, vertical: true)
                    }
                }

                Spacer(minLength: 8)

                Image(systemName: "chevron.down")
                    .font(.system(.caption, weight: .semibold))
                    .foregroundStyle(Ink.tertiary)
                    .rotationEffect(.degrees(isOpen ? 180 : 0))
            }
            // The label holds a Spacer and draws full-width, so its hit area is the
            // opaque text unless the shape is declared.
            .frame(minHeight: 44)
            .contentShape(Rectangle())
        }
        .buttonStyle(PressFeedbackButtonStyle(scales: false))
        .accessibilityLabel(String(localized: "Fine tuning"))
        .accessibilityHint(isOpen ? String(localized: "Collapse") : String(localized: "Expand"))
        .accessibilityAddTraits(.isButton)
    }

    // MARK: - Threshold

    private var thresholdBlock: some View {
        VStack(alignment: .leading, spacing: 10) {
            Text("A pull counts above")
                .font(.system(.subheadline, weight: .semibold))
                .foregroundStyle(Ink.primary)

            ValueRow(title: String(localized: "A pull counts above"),
                     unit: String(localized: "kg"),
                     value: $draft.plan.thresholdKg,
                     range: 0.5...10, limit: 0.5...SessionPlan.thresholdRange.upperBound,
                     step: 0.5,
                     presets: [1, 2, 3, 5],
                     decimals: 1)

            Text("Below this, the clock stops.")
                .font(.system(.caption, weight: .medium))
                .foregroundStyle(Ink.tertiary)
                .fixedSize(horizontal: false, vertical: true)

            ThresholdGaugeStrip(thresholdKg: draft.plan.thresholdKg)
        }
        .accessibilityElement(children: .contain)
        .accessibilityLabel(String(localized: "A pull counts above"))
    }

    // MARK: - Does the band stop the clock

    /// Whether leaving the target range pauses the rep.
    ///
    /// Sits between the threshold and the lead-in on purpose: all three answer "what
    /// counts as a pull", and this one is the range's half of that question where the
    /// threshold above is the floor's half. Phrased as the thing you'd turn ON — nobody
    /// looks for "pausesOutsideTargetBand" — with the consequence said underneath either
    /// way, because a switch whose off-state is silent makes you flip it to find out.
    private var bandGateBlock: some View {
        VStack(alignment: .leading, spacing: 6) {
            Toggle("Pause when I'm out of range",
                   isOn: $draft.plan.pausesOutsideTargetBand)
                .font(.system(.subheadline, weight: .medium))
                .foregroundStyle(Ink.primary)
                .tint(Accent.graphite)

            Text(draft.plan.pausesOutsideTargetBand
                 ? "The clock only runs while you are inside the target range."
                 : "The clock runs whenever you are on the edge, whatever the load — the range is still drawn, it just stops judging. Letting go still stops the rep.")
                .font(.system(.caption, weight: .medium))
                .foregroundStyle(Ink.tertiary)
                .fixedSize(horizontal: false, vertical: true)
        }
        .accessibilityElement(children: .contain)
    }

    // MARK: - Lead-in

    private var leadInBlock: some View {
        VStack(alignment: .leading, spacing: 10) {
            Text("Lead-in before each set")
                .font(.system(.subheadline, weight: .semibold))
                .foregroundStyle(Ink.primary)

            IntValueRow(title: String(localized: "Lead-in before each set"),
                        unit: String(localized: "s"),
                        value: $draft.plan.leadInSeconds,
                        range: 0...20, limit: 0...60,
                        step: 5,
                        presets: [0, 3, 5, 10])

            Text("Time to get your fingers on the edge.")
                .font(.system(.caption, weight: .medium))
                .foregroundStyle(Ink.tertiary)
                .fixedSize(horizontal: false, vertical: true)
        }
        .accessibilityElement(children: .contain)
        .accessibilityLabel(String(localized: "Lead-in before each set"))
    }
}

// MARK: - The one place the builder touches BLE

/// A live force bar with the threshold marked, so "2 kg" can be FELT instead of
/// guessed at.
///
/// Deliberately its own small view: this is the only Bluetooth in the whole builder,
/// and keeping it in one place means it can be deleted or moved without opening the
/// document. Everything else in the sheet is pure editing of a value type.
private struct ThresholdGaugeStrip: View {
    var thresholdKg: Double

    @Environment(DeviceStore.self) private var device

    @State private var checking = false
    @State private var countdown: Task<Void, Never>?

    /// Long enough to take the load, let go and try again; short enough that a
    /// forgotten check cannot flatten the gauge's battery.
    private static let checkSeconds = 15

    var body: some View {
        VStack(alignment: .leading, spacing: 10) {
            if device.state.isConnected {
                if checking {
                    // LEAVES, deliberately — see `ThresholdReadout`/`ThresholdBar`.
                    // `isOver`/`readout`/`bar` used to be computed properties of THIS
                    // struct, which meant reading `device.currentKg` for any of them
                    // re-evaluated this whole body — the Stop button and the static
                    // caption included — at sample rate for the 15 s a check runs. The
                    // same miss `GaugeView` documents having fixed at its own
                    // `GaugeHero`/`GaugeTrace`, applied everywhere except here.
                    ThresholdReadout(thresholdKg: thresholdKg)
                    ThresholdBar(thresholdKg: thresholdKg)
                    Text("Pull — anything above the line counts.")
                        .font(.system(.caption, weight: .medium))
                        .foregroundStyle(Ink.tertiary)
                        .fixedSize(horizontal: false, vertical: true)
                    SecondaryGlassButton(title: String(localized: "Stop"), systemImage: "stop.fill") {
                        stop(cause: .userStopped)
                    }
                } else {
                    SecondaryGlassButton(title: String(localized: "Check on the gauge"),
                                         systemImage: "waveform.path.ecg") { start() }
                }
            } else {
                // SHOWN, not a disabled button: a control you cannot use teaches
                // nothing, and the reason plus the reassurance is the whole content.
                Text("Connect your gauge to try it — you can change this any time.")
                    .font(.system(.caption, weight: .medium))
                    .foregroundStyle(Ink.tertiary)
                    .fixedSize(horizontal: false, vertical: true)
            }
        }
        .onDisappear {
            // UNCONDITIONAL, and gated on the DEVICE's own truth rather than on
            // `checking`: a Progressor left streaming behind a dismissed sheet is a dead
            // battery the user blames on the app. Same precedent as GaugeView.
            countdown?.cancel()
            countdown = nil
            checking = false
            if device.isStreaming { device.stopStreaming(cause: .screenClosed) }
        }
    }

    // MARK: - Streaming

    private func start() {
        guard device.state.isConnected else { return }
        // The bar's scale is peak-relative, so a peak left over from an earlier check
        // would otherwise draw this pull as a stub.
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

    /// The cause travels from the TRIGGER: this is reached from the Stop button and from
    /// the countdown running out, and labelling both the same would put a timeout in the
    /// log for a pull the user ended deliberately.
    private func stop(cause: StreamStopCause) {
        countdown?.cancel()
        countdown = nil
        checking = false
        if device.isStreaming { device.stopStreaming(cause: cause) }
    }
}

/// The live numeral and Counting/Stopped word. Its own leaf so `device.currentKg`
/// invalidates only this, not the Stop button and static caption beside it — see
/// `ThresholdGaugeStrip.body`.
private struct ThresholdReadout: View {
    var thresholdKg: Double

    @Environment(DeviceStore.self) private var device

    /// HYSTERETIC, not a bare `>=`. The whole point of this check is to park a load AT
    /// the threshold, which is exactly where sensor noise flips a bare comparison many
    /// times a second — a continuous buzz from the haptic and a flickering word. Same
    /// remedy as the runner's release band ("so hovering cannot chatter it on and
    /// off"): crossing up happens at the threshold, crossing back down only 5 % below
    /// it, clamped 0.5–2.0 kg.
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
            Text(device.currentKg, format: .number.precision(.fractionLength(1)))
                .font(.system(.subheadline, weight: .medium))
                .monospacedDigit()
                .foregroundStyle(crossed ? StatusTint.engaged : Ink.primary)
            Text("kg")
                .font(.system(.caption))
                .foregroundStyle(Ink.tertiary)
            Spacer(minLength: 8)
            // A WORD as well as a colour: the state has to survive greyscale.
            CapsLabel(crossed ? String(localized: "Counting") : String(localized: "Stopped"),
                      tint: crossed ? StatusTint.engaged : Ink.tertiary)
        }
        // A numeral changing 80×/sec is unusable under VoiceOver; the crossing itself
        // is felt instead — a VoiceOver user checking the threshold has no other
        // channel to learn whether a pull crossed the line, since nothing here fires
        // an audio/haptic cue the way the runner does.
        .accessibilityHidden(true)
        .sensoryFeedback(.selection, trigger: crossed)
        .onChange(of: device.currentKg) { _, kg in updateCrossing(kg) }
    }
}

/// The live force bar with the threshold marked. Its own leaf for the same
/// invalidation reason as `ThresholdReadout`.
private struct ThresholdBar: View {
    var thresholdKg: Double

    @Environment(DeviceStore.self) private var device
    @ScaledMetric(relativeTo: .body) private var barHeight: CGFloat = 26

    private var isOver: Bool { device.currentKg >= thresholdKg }

    /// Peak-relative, and PEAK is monotonic within one check, so the scale only ever
    /// settles outward — a ceiling keyed to the live reading would jitter the threshold
    /// marker on every sample, which is the one thing on screen that must hold still.
    private var ceiling: Double {
        max(10, device.peakKg * 1.25, thresholdKg * 1.6)
    }

    var body: some View {
        GeometryReader { geo in
            let fraction = min(max(device.currentKg, 0) / ceiling, 1)
            let markerX = min(max(thresholdKg / ceiling, 0), 1) * geo.size.width

            ZStack(alignment: .leading) {
                Capsule().fill(Ink.tertiary.opacity(0.18))
                // A full-bleed capsule MASKED to the fraction, not a second capsule at
                // partial width — same fix as the hold-to-end fill (2026-08-17): a
                // width-constrained capsule degenerates to a blob at small fractions
                // and its advancing edge is rounded like an end, so the fill reads as
                // a different shape than the track it sits in.
                Capsule()
                    .fill(isOver ? StatusTint.engaged : StatusTint.calm)
                    .mask(alignment: .leading) {
                        Rectangle()
                            .frame(width: max(2, fraction * geo.size.width))
                            .frame(maxWidth: .infinity, alignment: .leading)
                    }
                // The line itself, drawn over the fill so it stays visible once the
                // pull has passed it.
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
