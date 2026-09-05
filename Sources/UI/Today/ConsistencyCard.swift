// SPDX-License-Identifier: MPL-2.0
// Original contributions Copyright 2026 Nuri Bruner.

import SwiftUI

/// Fourteen days, oldest to newest, ending today.
///
/// No streak, no badge, no score, no praise — and deliberately **no aggregate number in
/// the header**: "11 of 14 days" is a score, and a score is the first step toward a
/// streak. The strip IS the summary. The aggregate exists only in the VoiceOver value,
/// where it has to be a description because a picture cannot be spoken.
struct ConsistencyCard: View {
    /// Exactly what `TemplateStore.consistency` publishes: 14 records, oldest first.
    let days: [DayRecord]
    /// Logging a climb lives HERE rather than in the routine card's ⋯ menu, which is for
    /// managing the routine — a gym session is not a fact about the routine. It sits on
    /// the strip because the strip is what it changes: the control is next to the thing
    /// it affects, which is the whole of good mapping.
    var onLogClimb: () -> Void

    var body: some View {
        // 12 rather than the house 16 vertically: this is the least load-bearing card on
        // Today, and it is where the last few points came from when the page had to fit
        // under a large title without scrolling.
        MaterialCard(verticalPadding: 12) {
            VStack(alignment: .leading, spacing: 8) {
                HStack(alignment: .firstTextBaseline) {
                    CapsLabel(String(localized: "Last 14 days"))
                    Spacer(minLength: 8)
                    logClimbButton
                }

                ConsistencyStrip(days: days)

                if let first = days.first {
                    HStack(spacing: 0) {
                        CapsLabel(first.day.formatted())
                        Spacer(minLength: 8)
                        CapsLabel(String(localized: "Today"))
                    }
                    // The strip speaks the whole fortnight in one sentence; the axis is
                    // a visual aid to it, not a second element to swipe through.
                    .accessibilityHidden(true)
                }

                if nothingLoggedYet {
                    Text("Your sessions will show up here.")
                        .font(.system(.footnote))
                        .foregroundStyle(Ink.tertiary)
                }
            }
        }
    }

    /// Glass INSIDE the label, then the content shape, then the button style outside —
    /// the house shape, and the 44 pt frame is what makes the whole capsule live rather
    /// than just the glyph and the word.
    private var logClimbButton: some View {
        Button(action: onLogClimb) {
            HStack(spacing: 5) {
                Image(systemName: "figure.climbing")
                Text("Log a session")
            }
            .font(.system(.footnote, weight: .semibold))
            .foregroundStyle(Accent.graphite)
            .padding(.horizontal, 12)
            .frame(minHeight: 44)
            .accessibleGlass(nil, in: .capsule)
            .contentShape(.capsule)
        }
        .buttonStyle(PressFeedbackButtonStyle())
        // Not "at the climbing gym" any more: this sheet also logs hangs done away from
        // the gauge, and a label naming only one of them hides the other entirely from
        // anyone who never sees the button's own text.
        .accessibilityLabel(String(localized: "Log a session you did elsewhere — climbing, or hangs off the gauge"))
    }

    private var nothingLoggedYet: Bool {
        days.allSatisfy { $0.completed == 0 && $0.climb == nil && !$0.benchmarked }
    }
}

// MARK: - The strip

/// How one day draws. Every state is SHAPE-encoded, never colour-only, so the strip
/// survives Reduce Transparency, greyscale and colourblindness intact.
private enum DayMark: Equatable {
    /// Earlier than any routine existed. A hairline, not a hole — drawing those days as
    /// empty circles would tell someone they failed on days they did not own the app.
    /// This is the most important honesty detail on the screen.
    case beforeHistory
    case missed
    /// At least one session, under target. The fill is the CONTINUOUS fraction, which
    /// reduces to exactly full/half/empty at two sessions a day and stays truthful at
    /// three — a lighter tint would vanish in greyscale.
    case partial(Double)
    case full
    /// A day spent at the climbing gym. FULL — a climb completes the day — but drawn
    /// with a notch so it is not mistaken for a hangboard day. See `ClimbNotch`.
    case climbed
    /// A max-testing day. FULL, bleu, and bored — the same glyph History's grid draws,
    /// so the vocabulary is learned once. See `BenchmarkBore`.
    case benchmarked
}

private func mark(for record: DayRecord) -> DayMark {
    guard record.tracked else { return .beforeHistory }
    // Asked FIRST: a climb settles the day whatever the hang count beside it, so a
    // climb-plus-nothing day must never fall through to `.missed`.
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

    /// CAPPED, and the cap is load-bearing. Each cell's ideal width is this dot, so
    /// fourteen of them at an unclamped accessibility size come to ~406pt against a
    /// 402pt screen — and because the enclosing VStack sizes itself from its children's
    /// ideals, that widened the entire content column and clipped the device chip, the
    /// routine title and the summary row off BOTH edges of the screen.
    ///
    /// A strip is a sparkline: it exists to be glanced at, and it has to FIT. Anyone who
    /// needs the detail at a readable size has History's month grid, which is built for
    /// exactly that.
    private var dot: CGFloat { min(scaledDot, 22) }

    var body: some View {
        // Spacing 0 with equal-width cells: the pitch DERIVES from the available width,
        // so the strip fits every device and every type size without a magic number.
        HStack(spacing: 0) {
            ForEach(Array(days.enumerated()), id: \.element.id) { index, record in
                cell(record, isToday: index == days.count - 1)
                    .frame(maxWidth: .infinity)
            }
        }
        .frame(height: dot + 6)
        // ONE element. Fourteen focusable dots is swipe torture, and day-by-day detail
        // belongs to History's month view.
        .accessibilityElement(children: .ignore)
        .accessibilityLabel(String(localized: "Last 14 days"))
        .accessibilityValue(spokenSummary)
    }

    private func cell(_ record: DayRecord, isToday: Bool) -> some View {
        // Top-aligned so today's under-tick lands exactly on the strip's bottom edge
        // rather than overhanging into the axis row below it.
        VStack(spacing: 0) {
            glyph(for: mark(for: record))
                .frame(width: dot, height: dot)
                .overlay(alignment: .bottom) {
                    if isToday {
                        // An under-tick, not a halo: today is still winnable, and at 0 of
                        // 2 at 8 a.m. it must not read as a failure. A halo would also
                        // fight the fractional fill sitting inside it.
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

    /// The only place an aggregate is allowed to exist, and it is phrased as a
    /// description rather than a score. Days before the routine existed are excluded
    /// from the missed count here exactly as they are in the drawing.
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
        // Named, not folded into "complete": the notch is a distinction the drawing
        // makes, so the spoken version has to make it too.
        if climbed > 0 { counts.append(String(localized: "\(climbed) at the climbing gym")) }
        if benchmarked > 0 { counts.append(String(localized: "\(benchmarked) max testing")) }
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
