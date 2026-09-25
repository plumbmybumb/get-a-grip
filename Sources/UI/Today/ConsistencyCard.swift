// SPDX-License-Identifier: MPL-2.0
// Original contributions Copyright 2026 Nuri Bruner.

import SwiftUI

/// Fourteen days, oldest to newest, ending today.
///
/// No streak, no badge, no score, no praise — and **no aggregate number in the header**:
/// "11 of 14 days" is a score, the first step toward a streak. The strip IS the summary;
/// the aggregate exists only in the VoiceOver value, because a picture cannot be spoken.
struct ConsistencyCard: View {
    /// Exactly what `TemplateStore.consistency` publishes: 14 records, oldest first.
    let days: [DayRecord]
    /// Logging a climb lives HERE, not in the routine card's ⋯ menu: a gym session is not a
    /// fact about the routine, and the control sits next to the strip it changes.
    var onLogClimb: () -> Void
    var onShowHistory: () -> Void = {}

    var body: some View {
        // 12 rather than 16 vertically: the least load-bearing card on Today, where
        // the last points came from to fit under a large title.
        MaterialCard(verticalPadding: 12) {
            VStack(alignment: .leading, spacing: 8) {
                HStack(alignment: .firstTextBaseline) {
                    Button(action: onShowHistory) {
                        HStack(spacing: 6) {
                            CapsLabel(String(localized: "Last 14 days"))
                            Image(systemName: "chevron.right")
                                .font(.caption2.weight(.semibold))
                                .foregroundStyle(Ink.tertiary)
                            Spacer(minLength: 0)
                        }
                        .frame(minHeight: 44)
                        .contentShape(.rect)
                    }
                    .buttonStyle(PressFeedbackButtonStyle())
                    .accessibilityLabel("History")
                    logClimbButton
                }

                Button(action: onShowHistory) {
                    VStack(alignment: .leading, spacing: 8) {
                        ConsistencyStrip(days: days)
                        if let first = days.first {
                            HStack(spacing: 0) {
                                CapsLabel(first.day.formatted())
                                Spacer(minLength: 8)
                                CapsLabel(String(localized: "Today"))
                            }
                            .accessibilityHidden(true)
                        }
                        if ConsistencyEmptyState.showsFirstUseHint(days) {
                            Text("Your sessions will show up here.")
                                .font(.system(.footnote))
                                .foregroundStyle(Ink.tertiary)
                        }
                    }
                    .frame(maxWidth: .infinity, minHeight: 44, alignment: .leading)
                    .contentShape(.rect)
                }
                .buttonStyle(PressFeedbackButtonStyle())
                .accessibilityIdentifier("consistency.history")
                .accessibilityHint("Opens History.")
            }
        }
    }

    /// Glass INSIDE the label, then the content shape, then the button style — the house
    /// shape; the 44 pt frame makes the whole capsule live.
    private var logClimbButton: some View {
        Button(action: onLogClimb) {
            HStack(spacing: 5) {
                Image(systemName: "figure.climbing")
                Text("Log a session")
            }
            .font(.system(.footnote, weight: .semibold))
            .foregroundStyle(Accent.graphite)
            .actionLabelLayout(minHeight: 44)
            .accessibleGlass(nil, in: .capsule)
            .contentShape(.capsule)
        }
        .buttonStyle(PressFeedbackButtonStyle())
        .accessibilityIdentifier("consistency.log")
        // The sheet also logs hangs done away from the gauge, so the label must not
        // name only climbing.
        .accessibilityLabel(String(localized: "Log a session you did elsewhere — climbing, or hangs off the gauge"))
    }

}

// MARK: - The strip

/// How one day draws. Every state is SHAPE-encoded, never colour-only, so the strip
/// survives Reduce Transparency, greyscale and colourblindness intact.
private enum DayMark: Equatable {
    /// Earlier than any routine existed. A hairline, not a hole: empty circles would say
    /// someone failed on days they did not own the app — the screen's key honesty detail.
    case beforeHistory
    case missed
    /// At least one session, under target. The fill is the CONTINUOUS fraction (exactly
    /// full/half/empty at two a day, truthful at three); a lighter tint would vanish in
    /// greyscale.
    case partial(Double)
    case full
    /// A climbing-gym day: FULL (a climb completes the day), notched so it is not a
    /// hangboard day. See `ClimbNotch`.
    case climbed
    /// A max-testing day: FULL, bleu, and bored — History's glyph. See `BenchmarkBore`.
    case benchmarked
}

private func mark(for record: DayRecord) -> DayMark {
    guard record.tracked else { return .beforeHistory }
    // FIRST: a climb settles the day whatever the hang count, so it must never
    // fall through to `.missed`.
    if record.climb != nil { return .climbed }
    if record.benchmarked { return .benchmarked }
    if record.completed == 0 { return .missed }
    if record.fraction >= 1 { return .full }
    return .partial(record.fraction)
}

private struct ConsistencyStrip: View {
    let days: [DayRecord]

    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    /// ~19pt at accessibility3 — 14 × 19 still fits the 330pt card interior.
    @ScaledMetric(relativeTo: .caption) private var scaledDot: CGFloat = 12

    /// CAPPED, and the cap is load-bearing: fourteen unclamped cells at accessibility sizes
    /// come to ~406pt against a 402pt screen, and since the VStack sizes from its children's
    /// ideals, that widened the whole column and clipped the chip, title and summary off
    /// BOTH edges. A strip is a sparkline and must FIT; History's month grid has the detail.
    private var dot: CGFloat { min(scaledDot, 22) }

    var body: some View {
        // Spacing 0 with equal-width cells: the pitch derives from the width, so it
        // fits every device and type size without a magic number.
        HStack(spacing: 0) {
            ForEach(Array(days.enumerated()), id: \.element.id) { index, record in
                cell(record, isToday: index == days.count - 1)
                    .frame(maxWidth: .infinity)
            }
        }
        .frame(height: dot + 6)
        // ONE element: fourteen focusable dots is swipe torture.
        .accessibilityElement(children: .ignore)
        .accessibilityLabel(String(localized: "Last 14 days"))
        .accessibilityValue(spokenSummary)
    }

    private func cell(_ record: DayRecord, isToday: Bool) -> some View {
        // Top-aligned so today's under-tick sits on the strip's bottom edge, not in
        // the axis row.
        VStack(spacing: 0) {
            glyph(for: mark(for: record))
                .frame(width: dot, height: dot)
                .overlay(alignment: .bottom) {
                    if isToday {
                        // An under-tick, not a halo: at 0 of 2 at 8 a.m. today must not read as a
                        // failure, and a halo would fight the fractional fill.
                        Capsule()
                            .fill(Accent.graphite.opacity(0.6))
                            .frame(width: dot, height: 3)
                            .offset(y: 6)
                    }
                }
            Spacer(minLength: 0)
        }
        .animation(Motion.state(reduceMotion),
                   value: record.fraction)
    }

    @ViewBuilder
    private func glyph(for mark: DayMark) -> some View {
        switch mark {
        case .beforeHistory:
            Capsule()
                .fill(Ink.tertiary.opacity(0.22))
                .frame(width: dot, height: 1.5)
        case .missed:
            // Visible, not accusatory. Never a red X.
            Circle()
                .strokeBorder(Ink.tertiary.opacity(0.45), lineWidth: 1.2)
        case .partial(let fraction):
            ZStack {
                Circle().strokeBorder(Ink.tertiary.opacity(0.45), lineWidth: 1.2)
                Circle()
                    .fill(Accent.graphite)
                    .mask(alignment: .leading) {
                        Rectangle().frame(width: dot * fraction)
                    }
            }
        case .full:
            Circle().fill(Accent.graphite)
        case .climbed:
            Circle().fill(Accent.graphite).climbNotch(true)
        case .benchmarked:
            Circle().fill(Accent.bleu).benchmarkBore(true, size: dot * 0.38)
        }
    }

    /// The only place an aggregate may exist, phrased as a description, not a score.
    /// Pre-routine days are excluded from "missed" as in the drawing.
    private var spokenSummary: String {
        var complete = 0, partial = 0, missed = 0, untracked = 0, climbed = 0, benchmarked = 0
        for record in days.dropLast() {
            switch mark(for: record) {
            case .beforeHistory: untracked += 1
            case .missed: missed += 1
            case .partial: partial += 1
            case .full: complete += 1
            case .climbed: climbed += 1
            case .benchmarked: benchmarked += 1
            }
        }

        var counts: [String] = []
        if complete > 0 { counts.append(String(localized: "\(complete) \(complete == 1 ? String(localized: "day") : String(localized: "days")) complete")) }
        // Named: the notch is a distinction the drawing makes, so speech must too.
        if climbed > 0 { counts.append(String(localized: "\(climbed) at the climbing gym")) }
        if benchmarked > 0 { counts.append(String(localized: "\(benchmarked) testing")) }
        if partial > 0 { counts.append(String(localized: "\(partial) partial")) }
        if missed > 0 { counts.append(String(localized: "\(missed) missed")) }

        var sentences = [counts.isEmpty ? String(localized: "No sessions in the last two weeks") : counts.joined(separator: ", ")]
        if untracked > 0 {
            sentences.append(String(localized: "\(untracked) \(untracked == 1 ? String(localized: "day") : String(localized: "days")) before this routine existed"))
        }
        if let today = days.last {
            let target = max(1, today.target)
            sentences.append(String(localized: "Today so far: \(today.completed) of \(target) \(target == 1 ? String(localized: "session") : String(localized: "sessions"))"))
        }
        return sentences.joined(separator: ". ") + "."
    }
}


enum ConsistencyEmptyState {
    static func showsFirstUseHint(_ days: [DayRecord]) -> Bool {
        days.contains { !$0.tracked }
            && days.allSatisfy { $0.completed == 0 && $0.climb == nil && !$0.benchmarked }
    }
}
