// SPDX-License-Identifier: MPL-2.0
// Original contributions Copyright 2026 Nuri Bruner.

import SwiftUI

/// A grip, as ONE control.
///
/// It replaces an edge slider, the finger pad and position chips (~400 pt of the set
/// editor) with a 60 pt row. Measured 2026-08-11: building the six-grip daily routine
/// spent roughly three interactions in five on constructing grips, with the EDGE slider
/// first although the edge never changed — the one control you never touch, six times.
///
/// Constructing is still possible (22 mm must stay expressible), in `GripIslandPanel`.
/// What moved is the DEFAULT: other apps in this category let you PICK the exercise;
/// this was the only one making you build it from parameters on every row.
///
/// **It only DISPLAYS and asks.** Nothing this deep in a scrolling row can reach the top
/// of the screen, so the panel is hoisted to the builder's root and this reports the tap.
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
            // MANDATORY: a full-width Spacer label hit-tests only its opaque content.
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
