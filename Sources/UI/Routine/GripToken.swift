// SPDX-License-Identifier: MPL-2.0
// Original contributions Copyright 2026 Nuri Bruner.

import SwiftUI

/// A grip, as ONE control.
///
/// It replaces three stacked ones — an edge slider, the finger pad and a row of position
/// chips, about 400 pt of the set editor — with a 60 pt row you tap. Measured on the
/// pinned sim 2026-08-11: building the six-grip daily routine from blank spent roughly
/// three interactions in five on constructing grips, and the EDGE slider came first in
/// that stack even though the edge is 20 mm on every set of that routine and never
/// changes. You scrolled past the one control you never touch, six times.
///
/// Constructing a grip is still possible — a grip is genuinely parametric and 22 mm has to
/// stay expressible — it just happens in `GripIslandPanel` now, hanging off the Dynamic
/// Island, rather than inline on the row. What moved is the DEFAULT: every other training
/// app in this category treats the exercise as something you PICK from a list of the ones
/// you use, and this was the only one that made you build it out of parameters on every
/// row.
///
/// **It only DISPLAYS and asks.** The panel it opens hangs off the Dynamic Island, and
/// nothing this deep in a scrolling set row can reach the top of the screen — so the panel
/// is hoisted to the builder's root and this reports the tap upward. A plain value in, a
/// callback out, which also keeps the row previewable.
struct GripToken: View {
    let grip: GripSpec
    var onEdit: () -> Void

    var body: some View {
        Button(action: onEdit) {
            HStack(spacing: 12) {
                FingerGlyph(fingers: grip.fingers, position: grip.position, dot: 7, gap: 3)

                VStack(alignment: .leading, spacing: 2) {
                    Text("\(grip.edgeMM) mm · \(grip.fingers.name)")
                        .font(.system(.body, weight: .semibold))
                        .monospacedDigit()
                        .foregroundStyle(Ink.primary)
                    Text(grip.position.name.lowercased())
                        .font(.system(.footnote))
                        .foregroundStyle(Ink.secondary)
                }

                Spacer(minLength: 8)

                Image(systemName: "chevron.right")
                    .font(.system(.footnote, weight: .semibold))
                    .foregroundStyle(Ink.tertiary)
            }
            .padding(.horizontal, 14)
            .padding(.vertical, 10)
            .frame(maxWidth: .infinity, minHeight: 60, alignment: .leading)
            .background {
                RoundedRectangle(cornerRadius: Metrics.radiusInner, style: .continuous)
                    .fill(Ink.tertiary.opacity(0.12))
            }
            // MANDATORY: the label holds a Spacer and draws full width, and SwiftUI's
            // default hit area is the label's OPAQUE content — the fill and the padding
            // contribute nothing to it.
            .contentShape(RoundedRectangle(cornerRadius: Metrics.radiusInner, style: .continuous))
        }
        .buttonStyle(PressFeedbackButtonStyle())
        .accessibilityLabel(String(localized: "Grip"))
        .accessibilityValue(grip.spoken)
        .accessibilityHint(String(localized: "Opens the grip picker"))
    }
}


#Preview {
    VStack(spacing: 20) {
        GripToken(grip: GripSpec(edgeMM: 20, fingers: .frontTwo, position: .openHand)) {}
    }
    .padding(20)
    .background { AppBackground() }
}
