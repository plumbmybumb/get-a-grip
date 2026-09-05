// SPDX-License-Identifier: MPL-2.0
// Original contributions Copyright 2026 Nuri Bruner.

import SwiftUI

/// The real L R L R sequence the Hands choice produces for the first set.
///
/// Fill-vs-outline, never two hues: it has to survive Reduce Transparency and
/// colourblindness, and a legend explaining which colour is which hand would be a
/// legend for a control that exists to remove one.
struct HandOrderStrip: View {
    let mode: HandMode
    let repsPerSide: Int

    @ScaledMetric(relativeTo: .caption) private var capsuleWidth: CGFloat = 9

    var body: some View {
        // Past twelve pulls a row of capsules stops being countable at a glance and
        // becomes a texture, so the sentence takes over — and ViewThatFits still catches
        // the case where twelve SCALED capsules overflow at accessibility3.
        //
        // The count cases are split out rather than left as an `if` INSIDE ViewThatFits:
        // an absent candidate measures zero, fits, and wins, which would draw nothing at
        // all for a set with no pulls in it.
        Group {
            if sequence.isEmpty || sequence.count > 12 {
                sentenceText
            } else {
                ViewThatFits(in: .horizontal) {
                    strip
                    sentenceText
                }
            }
        }
        // ONE element with one spoken sentence: twelve focusable capsules is twelve
        // swipes to learn something a sentence says once.
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
            // One pull with two hands on the edge is ONE event, drawn wide rather than
            // as a left and a right side by side — which would read as two pulls.
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

    /// A throwaway `SetPlan` so the count comes out of `PlanMath.repCount` — the single
    /// ×2 resolver. Multiplying `repsPerSide` by `sideCount` here instead is exactly the
    /// shortcut that makes a routine twice as long as its own summary claims.
    private var sequence: [Side] {
        var plan = SessionPlan()
        plan.handMode = mode
        return PlanMath.handSequence(SetPlan(repsPerSide: max(0, repsPerSide)), in: plan)
    }

    private var sentence: String {
        let perSide = max(0, repsPerSide)
        switch mode {
        case .alternateEachRep:
            return String(localized: "Left, right, left, right — \(pulls(sequence.count))")
        case .alternateEachSet:
            return String(localized: "All \(perSide) on the left, then all \(perSide) on the right")
        case .bothHands:
            return String(localized: "One pull, both hands — \(pulls(sequence.count))")
        }
    }

    /// The frozen copy reads "{2k} pulls"; a one-pull set would otherwise be spoken
    /// "1 pulls" in the one mode where that count can be odd.
    private func pulls(_ n: Int) -> String {
        String(localized: "\(n) \(n == 1 ? String(localized: "pull") : String(localized: "pulls"))")
    }
}
