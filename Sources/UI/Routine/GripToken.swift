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

    @Environment(\.dynamicTypeSize) private var typeSize

    var body: some View {
        Button(action: onEdit) {
            // Accessibility sizes STACK, exactly like the set-row header above it: beside
            // the glyph and the chevron the name kept a third of the button at AX3 and
            // broke inside its words ("20 / m / m · / Fro / nt").
            Group {
                if typeSize.isAccessibilitySize { stackedFace } else { compactFace }
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

    private var compactFace: some View {
        HStack(spacing: 12) {
            glyph
            words
            Spacer(minLength: 8)
            chevron
        }
    }

    /// Glyph and chevron on one line, then the words with the full width to wrap in.
    private var stackedFace: some View {
        VStack(alignment: .leading, spacing: 6) {
            HStack {
                glyph
                Spacer(minLength: 8)
                chevron
            }
            words
                .fixedSize(horizontal: false, vertical: true)
        }
    }

    private var glyph: some View {
        FingerGlyph(fingers: grip.fingers, position: grip.position, dot: 7, gap: 3)
    }

    private var words: some View {
        VStack(alignment: .leading, spacing: 2) {
            Text("\(grip.edgeMM) mm · \(grip.fingers.name)")
                .font(.system(.body, weight: .semibold))
                .monospacedDigit()
                .foregroundStyle(Ink.primary)
            Text(grip.position.name.lowercased())
                .font(.system(.footnote))
                .foregroundStyle(Ink.secondary)
        }
    }

    private var chevron: some View {
        Image(systemName: "chevron.right")
            .font(.system(.footnote, weight: .semibold))
            .foregroundStyle(Ink.tertiary)
    }
}


#Preview {
    VStack(spacing: 20) {
        GripToken(grip: GripSpec(edgeMM: 20, fingers: .frontTwo, position: .openHand)) {}
    }
    .padding(20)
    .background { AppBackground() }
}
