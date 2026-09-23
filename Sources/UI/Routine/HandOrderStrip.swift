// SPDX-License-Identifier: MPL-2.0
// Original contributions Copyright 2026 Nuri Bruner.

import SwiftUI

/// The real L R L R sequence the Hands choice produces for the first set — or R L R L
/// when the routine starts on the right.
///
/// Fill-vs-outline, never two hues: it must survive Reduce Transparency and
/// colourblindness, and a colour legend would defeat a control that exists to remove one.
struct HandOrderStrip: View {
    let mode: HandMode
    var startingHand: Side = .left
    let repsPerSide: Int

    @ScaledMetric(relativeTo: .caption) private var capsuleWidth: CGFloat = 9

    var body: some View {
        // Past twelve pulls capsules stop being countable and become texture, so a
        // sentence takes over; ViewThatFits catches twelve SCALED capsules
        // overflowing at accessibility3.
        //
        // Count cases are split out, not an `if` INSIDE ViewThatFits: an absent
        // candidate measures zero, fits and wins, drawing nothing for an empty set.
        Group {
            if totalPulls == 0 || totalPulls > 12 {
                sentenceText
            } else {
                ViewThatFits(in: .horizontal) {
                    strip
                    sentenceText
                }
            }
        }
        // ONE element, one sentence, not twelve swipes.
        .accessibilityElement(children: .ignore)
        .accessibilityLabel(sentence)
    }

    private var strip: some View {
        // Resolved ONCE: `sequence` rebuilds its probe on every read.
        let sides = sequence
        return HStack(spacing: capsuleWidth * 0.55) {
            ForEach(sides.indices, id: \.self) { index in
                capsule(sides[index])
            }
        }
    }

    @ViewBuilder
    private func capsule(_ side: Side) -> some View {
        switch side {
        case .left:
            Capsule().fill(Accent.graphite)
                .frame(width: capsuleWidth, height: capsuleWidth * 2)
        case .right:
            Capsule().strokeBorder(Ink.tertiary.opacity(0.6), lineWidth: 1.5)
                .frame(width: capsuleWidth, height: capsuleWidth * 2)
        case .both:
            // Two hands on the edge is ONE event, drawn wide; side by side would read
            // as two pulls.
            Capsule().fill(Accent.graphite)
                .frame(width: capsuleWidth * 2.2, height: capsuleWidth * 2)
        }
    }

    private var sentenceText: some View {
        Text(sentence)
            .font(.system(.footnote))
            .monospacedDigit()
            .foregroundStyle(Ink.secondary)
            .fixedSize(horizontal: false, vertical: true)
    }

    // MARK: Derived

    private var totalPulls: Int {
        PlanMath.repCount(SetPlan(repsPerSide: max(0, repsPerSide)), mode: mode)
    }

    /// A throwaway `SetPlan` so the count comes from `PlanMath.repCount`, the single ×2
    /// resolver; multiplying here is how a routine grows twice as long as its summary.
    private var sequence: [Side] {
        var plan = SessionPlan()
        plan.handMode = mode
        plan.startingHand = startingHand
        return PlanMath.handSequence(SetPlan(repsPerSide: max(0, repsPerSide)), in: plan)
    }

    private var sentence: String {
        let perSide = max(0, repsPerSide)
        let startsRight = startingHand == .right
        switch mode {
        case .alternateEachRep:
            return startsRight
                ? String(localized: "Right, left, right, left — \(pulls(totalPulls))")
                : String(localized: "Left, right, left, right — \(pulls(totalPulls))")
        case .alternateEachSet:
            return startsRight
                ? String(localized: "All \(perSide) on the right, then all \(perSide) on the left")
                : String(localized: "All \(perSide) on the left, then all \(perSide) on the right")
        case .bothHands:
            return String(localized: "One pull, both hands — \(pulls(totalPulls))")
        }
    }

    /// Avoids "1 pulls" in the one mode where the count can be odd.
    private func pulls(_ n: Int) -> String {
        String(localized: "\(n) \(n == 1 ? String(localized: "pull") : String(localized: "pulls"))")
    }
}
