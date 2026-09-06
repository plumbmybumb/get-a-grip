// SPDX-License-Identifier: MPL-2.0
// Original contributions Copyright 2026 Nuri Bruner.

import SwiftUI

/// THE END OF THE DECK — the routine you just built, drawn whole.
///
/// A deck answers one question per card, which is what makes it easy; the cost is that
/// nobody ever sees the thing they authored. This card pays that back: every grip in
/// order with what it actually costs, the totals, and the one button. It is the only
/// place in the setup flow where the routine exists as a SHAPE rather than as a form.
///
/// It draws the RESOLVED numbers — the inherited hold, the percentage baked to kilograms
/// against each grip's own max — because "following the routine" is a fact about
/// authoring, and this card is about what will happen to your fingers.
struct RoutineWrapUpCard: View {
    let plan: SessionPlan
    let sessionsPerDay: Int
    /// Every max on file, by grip AND hand — so a set whose two hands pull different
    /// loads says both here, on the last screen before Save.
    var maxes: MaxTable

    var body: some View {
        VStack(alignment: .leading, spacing: 18) {
            ladder
            Divider().overlay(Ink.tertiary.opacity(0.22))
            totals
        }
    }

    // MARK: - The grips

    private var ladder: some View {
        VStack(alignment: .leading, spacing: 10) {
            CapsLabel(String(localized: "YOUR ROUTINE"))
            ForEach(Array(plan.executable.sets.enumerated()), id: \.element.id) { index, set in
                row(set, index: index)
                if index < plan.executable.sets.count - 1 {
                    Divider().overlay(Ink.tertiary.opacity(0.14))
                }
            }
        }
    }

    private func row(_ set: SetPlan, index: Int) -> some View {
        HStack(alignment: .top, spacing: 12) {
            FingerGlyph(fingers: set.grip.fingers, position: set.grip.position,
                        dot: 7, gap: 3)
                .padding(.top, 3)

            VStack(alignment: .leading, spacing: 3) {
                Text(set.grip.line)
                    .font(.system(.subheadline, weight: .semibold))
                    .monospacedDigit()
                    .foregroundStyle(Ink.primary)
                    .lineLimit(2)
                Text(detail(set))
                    .font(.system(.footnote))
                    .monospacedDigit()
                    .foregroundStyle(Ink.secondary)
                    .fixedSize(horizontal: false, vertical: true)
            }

            Spacer(minLength: 8)

            Text(PlanMath.clockText(PlanMath.setSeconds(set, in: plan)))
                .font(.system(.subheadline, weight: .medium))
                .monospacedDigit()
                .foregroundStyle(Ink.tertiary)
        }
        .accessibilityElement(children: .ignore)
        .accessibilityLabel(String(localized: "\(set.grip.spoken). \(detail(set))."))
    }

    /// RESOLVED, not authored: the hold this set will actually run and the load it will
    /// actually ask for, whether either came from the routine or from the set.
    private func detail(_ set: SetPlan) -> String {
        var parts = [String(localized: "\(set.repsPerSide) per side"),
                     String(localized: "\(PlanMath.hold(set, in: plan)) s hold")]
        // Per HAND when the two differ — see `PlanMath.targetText`, which is the one
        // place that copy is decided.
        if let load = PlanMath.targetText(set, in: plan, maxes: maxes) {
            parts.append(load)
        }
        return parts.joined(separator: " · ")
    }

    // MARK: - The price

    private var totals: some View {
        VStack(alignment: .leading, spacing: 6) {
            Text(PlanMath.totalsLine(plan))
                .font(.system(.subheadline, weight: .semibold))
                .monospacedDigit()
                .contentTransition(.numericText())
                .foregroundStyle(Ink.primary)
            Text(cadenceLine)
                .font(.system(.footnote))
                .monospacedDigit()
                .foregroundStyle(Ink.secondary)
            if let perSide = PlanMath.perSideLine(plan) {
                Text(perSide)
                    .font(.system(.footnote))
                    .monospacedDigit()
                    .foregroundStyle(Ink.tertiary)
            }
            if plan.executable.sets.contains(where: { $0.targetBand == nil && PlanMath.targetPercent($0, in: plan) != nil }) {
                Text("Percentage targets use your saved maxes. These may no longer reflect your current strength.")
                    .font(.system(.footnote))
                    .foregroundStyle(Ink.tertiary)
                    .fixedSize(horizontal: false, vertical: true)
            }
            if untargeted > 0 {
                // Stated here rather than only on the LOAD card, because this is the last
                // moment before Save and a missing max is silent at run time.
                Label(String(localized: "\(untargeted) \(untargeted == 1 ? String(localized: "grip has") : String(localized: "grips have")) no max on file, so \(untargeted == 1 ? String(localized: "it shows") : String(localized: "they show")) no target."),
                      systemImage: "exclamationmark.circle")
                    .font(.system(.footnote, weight: .medium))
                    .foregroundStyle(StatusTint.armed)
                    .fixedSize(horizontal: false, vertical: true)
                    .padding(.top, 2)
            }
            if PlanMath.totalSeconds(plan) > 3600 {
                Label("That's over an hour. Fine if you mean it.", systemImage: "clock")
                    .font(.system(.footnote, weight: .medium))
                    .foregroundStyle(StatusTint.armed)
                    .padding(.top, 2)
            }
        }
    }

    private var cadenceLine: String {
        let each = PlanMath.durationText(PlanMath.totalSeconds(plan))
        switch sessionsPerDay {
        case 1:  return String(localized: "Once a day · \(each)")
        case 2:  return String(localized: "Twice a day · \(each) each")
        default: return String(localized: "\(sessionsPerDay)× a day · \(each) each")
        }
    }

    /// Distinct grips with a percentage target that cannot resolve — counted PER HAND,
    /// so a grip with a left max and no right one is reported rather than passing as
    /// fully loaded.
    private var untargeted: Int {
        PlanMath.missingBenchmarkGripCount(plan, maxes: maxes)
    }

    private func kgText(_ kg: Double) -> String {
        kg.formatted(.number.precision(.fractionLength(1)))
    }
}
