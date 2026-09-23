// SPDX-License-Identifier: MPL-2.0
// Original contributions Copyright 2026 Nuri Bruner.

import SwiftUI

/// The hand, as a pure drawing — **the one glyph the app and the widget both compile.**
///
/// `FingerGlyph` lives in `Sources/` with app-only ink tokens, and a Live Activity only
/// sees `Shared/`, so this takes its colour from the caller. Same proportions and
/// capsules as the in-app glyph and the island hand: recognisably one mark.
struct HandMark: View {
    var fingers: FingerSet
    var position: GripPosition = .halfCrimp
    /// Facing a LEFT palm the thumb is on the right, and the index finger is therefore
    /// the RIGHTMOST bar. The whole hand mirrors — drawing only the thumb on the other
    /// side renders a front-2 grip on the little-finger side and reads as back-2.
    var side: Side = .both
    var barWidth: CGFloat = 8
    var tint: Color = .primary

    private var gap: CGFloat { barWidth * 0.42 }
    private var barLength: CGFloat { barWidth * HandGeometry.barAspect }
    /// A full capsule, identical to the island hand and `FingerGlyph`. `position` stays in
    /// the signature because the widget still reads it for the spoken grip.
    private var radius: CGFloat { barWidth / 2 }
    private var mirrored: Bool { side != .right }

    var body: some View {
        HStack(alignment: .bottom, spacing: gap) {
            ForEach(0..<4, id: \.self) { slot in
                let anatomical = mirrored ? 3 - slot : slot
                RoundedRectangle(cornerRadius: radius, style: .continuous)
                    .fill(tint.opacity(fingers.contains(FingerSet.allFingers[anatomical]) ? 1 : 0.22))
                    .frame(width: barWidth,
                           height: barLength * HandGeometry.lengthFactor[anatomical])
            }
        }
        // The thumb sits on the same side it does on the island, and only when the grip
        // actually calls for one.
        .overlay(alignment: mirrored ? .bottomTrailing : .bottomLeading) {
            if fingers.hasThumb {
                Capsule()
                    .fill(tint)
                    .frame(width: barWidth * 1.6, height: barWidth * 0.9)
                    .rotationEffect(.degrees(mirrored ? 34 : -34))
                    .offset(x: mirrored ? barWidth * 1.5 : -barWidth * 1.5,
                            y: barWidth * 0.5)
            }
        }
        .accessibilityHidden(true)
    }
}
