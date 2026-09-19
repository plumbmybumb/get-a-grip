// SPDX-License-Identifier: MPL-2.0
// Original contributions Copyright 2026 Nuri Bruner.

import SwiftUI

// MARK: - The things that change 80× a second

// The runner's LEAF views, lifted out of `RunnerView.swift` whole. Each one reads the
// high-frequency source itself — `DeviceStore.currentKg`, `.trace`, the session's own
// per-sample values — so a sample invalidates the one thing that moved and nothing
// around it. That is the shape the screen's performance rests on: see the file they
// came from for the layout that sits above them.

/// The live kilogram readout, isolated in its OWN view.
///
/// `DeviceStore.currentKg` changes with every force sample. Read from `RunnerView`'s
/// body, that rebuilt the entire screen — counters, prompt, grip line, controls — 80
/// times a second to move one number. A leaf view reading the store directly means the
/// invalidation stops here, at the only thing that actually changed.
struct LiveForceReadout: View {
    @Environment(\.weightUnit) private var weightUnit
    @Environment(DeviceStore.self) private var device
    var tint: Color
    var size: CGFloat
    var unitSize: CGFloat

    var body: some View {
        HStack(alignment: .firstTextBaseline, spacing: 4) {
            Text(weightUnit.number(device.currentKg))
                .font(.system(size: size, weight: .thin))
                    .displayTracking(size)
                .monospacedDigit()
                .lineLimit(1)
                .minimumScaleFactor(0.5)
                // A measurement snaps; only clocks roll.
                .contentTransition(.identity)
                .foregroundStyle(tint)
            Text(weightUnit.symbol)
                .font(.system(size: unitSize))
                .foregroundStyle(Ink.tertiary)
        }
        .accessibilityHidden(true)
    }
}

/// The timer ring updates at 10 Hz; the surrounding numeral, prompts and controls
/// only observe the whole-second snapshot. Its animation never drives app state.
struct LiveTimerRing: View {
    var session: RunnerSession
    var lineWidth: CGFloat
    var tint: Color
    @Environment(\.accessibilityReduceMotion) private var reduceMotion

    var body: some View {
        let fraction = session.phaseRemainingFraction ?? 0
        ZStack {
            Circle().stroke(Ink.tertiary.opacity(0.18), lineWidth: lineWidth)
            if fraction > 0 {
                Circle()
                    .trim(from: 0, to: fraction)
                    .stroke(tint, style: StrokeStyle(lineWidth: lineWidth, lineCap: .round))
                    .rotationEffect(.degrees(-90))
                    .animation(reduceMotion ? nil : Motion.live, value: fraction)
            }
        }
    }
}

/// The exact measured fraction belongs to this leaf alone. Linear settling fills the
/// frames between BLE packets without forecasting credited work or easing to a stop
/// per packet. The caller keys this view to the working phase so a skipped/next pull
/// starts cleanly instead of draining the previous pull's bar backwards.
struct LiveRepProgress: View {
    var session: RunnerSession
    @Environment(\.accessibilityReduceMotion) private var reduceMotion

    var body: some View {
        ProgressView(value: session.repProgress)
            .tint(StatusTint.engaged)
            .animation(reduceMotion ? nil : Motion.measuredProgress, value: session.repProgress)
            .accessibilityHidden(true)
    }
}

/// The target's live state changes with every force sample, so the chip owns that
/// high-frequency observation instead of invalidating the runner screen around it.
struct LiveTargetChip: View {
    @Environment(\.weightUnit) private var weightUnit
    @Environment(DeviceStore.self) private var device
    var band: ClosedRange<Double>
    var isWorking: Bool
    var timerOnly = false

    var body: some View {
        // In a timer-only session the gauge value is zero or stale by definition. Letting
        // it light this instruction chip would claim that an unmeasured pull is engaged.
        let live = !timerOnly && isWorking && band.contains(device.currentKg)
        Text(String(localized: "\(weightUnit.number(band.lowerBound))–\(weightUnit.number(band.upperBound)) \(weightUnit.symbol)"))
            .font(.system(.footnote, weight: .semibold))
            .monospacedDigit()
            .foregroundStyle(live ? Color.white : Ink.secondary)
            .padding(.horizontal, 9)
            .padding(.vertical, 4)
            .background {
                Capsule()
                    .fill(live ? StatusTint.engaged : Color.clear)
                    .overlay(Capsule().stroke(Ink.tertiary.opacity(live ? 0 : 0.6), lineWidth: 1))
            }
            .animation(Motion.live, value: live)
            .accessibilityHidden(true)
    }
}

/// Same reasoning for the trace: `DeviceStore.trace` grows with every sample, so the
/// dependency belongs to the graph alone.
struct LiveTrace: View {
    @Environment(DeviceStore.self) private var device
    @Environment(\.blendedTraceTint) private var blendedTint
    var thresholdKg: Double?
    var targetBand: ClosedRange<Double>?
    var tint: Color
    /// Where the plot sits in the canvas — the card's own clearances unless the trace
    /// is the screen's background.
    var plot: ForceTraceView.PlotInsets = .card
    var lit = false
    var body: some View {
        ForceTraceView(samples: device.trace,
                       thresholdKg: thresholdKg, targetBand: targetBand, tint: blendedTint ?? tint,
                       nominalSampleRate: device.gaugeCapabilities.nominalSampleRate,
                       bridgesSparseDelivery: device.gaugeCapabilities.isBroadcast,
                       diagnostics: device.pipelineDiagnostics,
                       plot: plot, lit: lit)
    }
}
