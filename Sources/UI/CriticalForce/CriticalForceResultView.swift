// SPDX-License-Identifier: MPL-2.0
// Original contributions Copyright 2026 Nuri Bruner.

import Charts
import SwiftUI

/// What a critical force test says, as a value: shared by the result screen and a saved
/// test's detail, so the two can never describe one test differently.
struct CriticalForceSummary: Equatable {
    var criticalForceKg: Double
    var wPrimeKgS: Double
    var peakKg: Double
    var repMeans: [Double?]
    var criticalForceReps: ClosedRange<Int>
    var restsKept: Int
    var restsTotal: Int
    var percentOfMax: Double?
    var percentOfBodyMass: Double?

    init(_ result: CriticalForceResult, maxKg: Double?, bodyMassKg: Double?) {
        criticalForceKg = result.criticalForceKg
        wPrimeKgS = result.wPrimeKgS
        peakKg = result.peakKg
        repMeans = result.reps.map(\.meanKg)
        criticalForceReps = result.criticalForceReps
        restsKept = result.restsKept
        restsTotal = result.restsTotal
        percentOfMax = result.percentOf(maxKg)
        percentOfBodyMass = result.percentOf(bodyMassKg)
    }

    init(_ record: CriticalForceRecord) {
        criticalForceKg = record.criticalForceKg
        wPrimeKgS = record.wPrimeKgS
        peakKg = record.peakKg
        repMeans = record.reps.map(\.meanKg)
        let last = max(1, record.repsRun)
        criticalForceReps = max(1, last - CriticalForceRules.criticalForceReps + 1)...last
        restsKept = record.restsKept
        restsTotal = record.restsTotal
        percentOfMax = record.percentOfMax
        percentOfBodyMass = record.percentOfBodyMass
    }
}

/// A saved test, stacked: the headline, then the pulls in a well. The history detail;
/// the live result places the same two pieces on glass over the open screen instead.
struct CriticalForceResultView: View {
    let summary: CriticalForceSummary

    var body: some View {
        VStack(spacing: 16) {
            CriticalForceHeadline(summary: summary)
            CriticalForcePullChart(summary: summary)
                .padding(14)
                .background(Ink.primary.opacity(0.05),
                            in: RoundedRectangle(cornerRadius: Metrics.radiusInner, style: .continuous))
        }
    }
}

/// One number, then what it means. The hero is CF; everything else is in secondary ink,
/// because the other apps' failure was to give every figure the same voice.
struct CriticalForceHeadline: View {
    let summary: CriticalForceSummary

    @Environment(\.weightUnit) private var weightUnit
    @ScaledMetric(relativeTo: .largeTitle) private var heroSize: CGFloat = 76
    @ScaledMetric(relativeTo: .title3) private var unitSize: CGFloat = 21

    var body: some View {
        VStack(spacing: 14) {
            VStack(spacing: 6) {
                CapsLabel(String(localized: "CRITICAL FORCE"))
                HStack(alignment: .firstTextBaseline, spacing: 6) {
                    Text(weightUnit.number(summary.criticalForceKg))
                        .font(.system(size: heroSize, weight: .thin))
                        .displayTracking(heroSize)
                        .monospacedDigit()
                    Text(weightUnit.symbol)
                        .font(.system(size: unitSize))
                        .foregroundStyle(Ink.tertiary)
                }
                .foregroundStyle(Ink.primary)
                .lineLimit(1)
                .minimumScaleFactor(0.6)
                if let ratios = ratioLine {
                    Text(ratios)
                        .font(.system(.subheadline, weight: .medium))
                        .foregroundStyle(Ink.secondary)
                        .multilineTextAlignment(.center)
                }
            }
            .frame(maxWidth: .infinity)
            .accessibilityElement(children: .ignore)
            .accessibilityLabel(spokenHero)

            HStack(spacing: 0) {
                stat(String(localized: "RESERVE (W′)"),
                     value: weightUnit.number(summary.wPrimeKgS, decimals: 0),
                     unit: "\(weightUnit.symbol)·s")
                Divider().frame(height: 36)
                stat(String(localized: "HARDEST PULL"),
                     value: weightUnit.number(summary.peakKg), unit: weightUnit.symbol)
            }
            .padding(.vertical, 10)
            .background(Ink.primary.opacity(0.05),
                        in: RoundedRectangle(cornerRadius: Metrics.radiusInner, style: .continuous))
        }
    }

    private func stat(_ label: String, value: String, unit: String) -> some View {
        VStack(spacing: 4) {
            CapsLabel(label)
            HStack(alignment: .firstTextBaseline, spacing: 3) {
                Text(value)
                    .font(.system(.title3, weight: .medium))
                    .monospacedDigit()
                Text(unit)
                    .font(.system(.caption))
                    .foregroundStyle(Ink.tertiary)
            }
            .foregroundStyle(Ink.primary)
        }
        .frame(maxWidth: .infinity)
        .accessibilityElement(children: .combine)
    }

    private var ratioLine: String? {
        var parts: [String] = []
        if let pct = summary.percentOfMax {
            parts.append(String(localized: "\(Int(pct.rounded())) % of your max"))
        }
        if let pct = summary.percentOfBodyMass {
            parts.append(String(localized: "\(Int(pct.rounded())) % of body weight"))
        }
        return parts.isEmpty ? nil : parts.joined(separator: " · ")
    }

    private var spokenHero: String {
        let base = String(localized: "Critical force \(weightUnit.number(summary.criticalForceKg)) \(weightUnit.spokenName)")
        return [base, ratioLine].compactMap { $0 }.joined(separator: ". ")
    }
}

/// Every pull's average as a column, the CF pulls in bleu, CF drawn across them: the
/// plateau the number came from.
struct CriticalForcePullChart: View {
    let summary: CriticalForceSummary
    var showsCaptions = true

    @Environment(\.weightUnit) private var weightUnit
    @ScaledMetric(relativeTo: .body) private var chartHeight: CGFloat = 150

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            chart
                .frame(minHeight: chartHeight, maxHeight: .infinity)
            if showsCaptions {
                Text("Each bar is one pull’s average. Critical force is the mean of pulls \(summary.criticalForceReps.lowerBound)–\(summary.criticalForceReps.upperBound), where your force levels off.")
                    .font(.system(.footnote))
                    .foregroundStyle(Ink.secondary)
                    .fixedSize(horizontal: false, vertical: true)
                if !restsLine.isEmpty {
                    Text(restsLine)
                        .font(.system(.footnote))
                        .foregroundStyle(summary.restsKept == summary.restsTotal ? Ink.tertiary : StatusTint.armed)
                        .fixedSize(horizontal: false, vertical: true)
                }
            }
        }
    }

    private var chart: some View {
        Chart {
            ForEach(Array(summary.repMeans.enumerated()), id: \.offset) { index, mean in
                // No `width: .ratio`: on a NUMERIC axis a ratio has no band to be a
                // fraction of and the bars drew zero wide (measured, 2026-09-25).
                BarMark(x: .value("Pull", index + 1),
                        y: .value("Average", weightUnit.fromKg(mean ?? 0)))
                    .foregroundStyle(summary.criticalForceReps.contains(index + 1)
                                     ? StatusTint.engaged : Ink.tertiary.opacity(0.45))
                    .cornerRadius(2)
            }
            RuleMark(y: .value("Critical force", weightUnit.fromKg(summary.criticalForceKg)))
                .foregroundStyle(StatusTint.engaged)
                .lineStyle(StrokeStyle(lineWidth: 1.5, dash: [4, 3]))
        }
        .chartXScale(domain: 0.5...(Double(max(1, summary.repMeans.count)) + 0.5))
        .chartXAxis {
            AxisMarks(values: [1, 8, 16, 24].filter { $0 <= max(1, summary.repMeans.count) }) { _ in
                AxisValueLabel()
            }
        }
        .chartYAxis {
            AxisMarks(position: .leading, values: .automatic(desiredCount: 3)) { _ in
                AxisGridLine().foregroundStyle(Ink.tertiary.opacity(0.2))
                AxisValueLabel()
            }
        }
        .accessibilityLabel("Average force per pull")
        .accessibilityValue(spokenPlateau)
    }

    private var restsLine: String {
        let missed = summary.restsTotal - summary.restsKept
        guard summary.restsTotal > 0 else { return "" }
        if missed == 0 { return String(localized: "Every rest kept on the beat.") }
        return String(localized: "Still on the edge after the bell in \(missed) of \(summary.restsTotal) rests. Force after the bell isn’t counted, but it eats into your rest.")
    }

    private var spokenPlateau: String {
        let first = summary.repMeans.first.flatMap { $0 }
        let last = summary.repMeans.last.flatMap { $0 }
        guard let first, let last else { return "" }
        return String(localized: "From \(weightUnit.number(first)) on the first pull to \(weightUnit.number(last)) on the last")
    }
}

extension WeightUnit {
    /// `number` at a chosen precision, for a figure like W′ where a decimal is noise.
    func number(_ kilograms: Double, decimals: Int) -> String {
        fromKg(kilograms).formatted(.number.precision(.fractionLength(decimals)))
    }
}
