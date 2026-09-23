// SPDX-License-Identifier: MPL-2.0
// Original contributions Copyright 2026 Nuri Bruner.

import SwiftUI

/// A grip drawn as four BARS — index to little, filled when that finger is on the edge
/// and hairline when it is not.
///
/// Bars, not dots: as full-radius squares these rendered as CIRCLES, indistinguishable
/// from the session dots above and the consistency strip below. A finger is taller than
/// wide; drawing it so makes the collision impossible, and matches the app icon.
///
/// The app's only iconography under the no-emoji rule, and **the same drawing as the
/// hand hanging off the Dynamic Island** — see `radius`.
struct FingerGlyph: View {
    let fingers: FingerSet
    var position: GripPosition = .halfCrimp
    /// `@ScaledMetric`, so the glyph grows with its text instead of shrinking to a speck.
    /// Callers pass the design size (`dot: 12`) and get it scaled.
    @ScaledMetric(relativeTo: .caption) var dot: CGFloat = 5.5
    @ScaledMetric(relativeTo: .caption) var gap: CGFloat = 3
    var tint: Color = Accent.graphite

    /// Taller than wide — `HandGeometry.barAspect` stops a bar ever reading as a dot.
    private var barHeight: CGFloat { dot * HandGeometry.barAspect }

    /// **A FULL CAPSULE — the island hand's rule exactly** (`IslandHand.radius`; Nuri,
    /// 2026-08-09: "identical radii so it's clear that they're the same fingers").
    ///
    /// It used to square off with `GripPosition.closure`, which separated the two front-2
    /// sets at a glance. That signal is spent on purpose: one mark drawn one way everywhere
    /// beats a hint only its author could read. The position is spoken in words wherever
    /// the glyph appears.
    private var radius: CGFloat { dot / 2 }

    var body: some View {
        VStack(alignment: .leading, spacing: max(1.5, gap * 0.6)) {
            // BOTTOM-aligned from a common knuckle line, like fingers; top-aligned they
            // read as a chart.
            HStack(alignment: .bottom, spacing: gap) {
                ForEach(Array(fingers.occupied.indices), id: \.self) { index in
                    pip(on: fingers.occupied[index], index: index)
                }
            }
            if fingers.hasThumb {
                // The thumb: horizontal, under the index side, where a pinching thumb sits
                // (Nuri: "a little rectangle under the 4"). BARS are digits; this one lies down.
                RoundedRectangle(cornerRadius: radius, style: .continuous)
                    .fill(tint)
                    .frame(width: dot * 2 + gap, height: dot * 0.62)
            }
        }
        // The parent speaks the whole grip as one sentence (`GripSpec.spoken`).
        .accessibilityHidden(true)
    }

    @ViewBuilder
    private func pip(on: Bool, index: Int) -> some View {
        let shape = RoundedRectangle(cornerRadius: radius, style: .continuous)
        Group {
            if on {
                shape.fill(tint)
            } else {
                // Stroked, not a lighter fill: fill-vs-outline survives greyscale, Reduce
                // Transparency and colourblindness. FULL tertiary, measured: 0.45 gave 2.3:1
                // and 0.7 only 2.6:1 on the dark well, since a 1 pt hairline is nearly all
                // antialiased edge. Which fingers are OFF is half the grip; it must clear 3:1.
                shape.strokeBorder(Ink.tertiary, lineWidth: 1)
            }
        }
        .frame(width: dot, height: barHeight * HandGeometry.lengthFactor[index])
    }
}

#Preview {
    VStack(alignment: .leading, spacing: 18) {
        ForEach(GripPosition.known, id: \.rawValue) { position in
            HStack(spacing: 16) {
                FingerGlyph(fingers: .four, position: position, dot: 14, gap: 6)
                FingerGlyph(fingers: .frontThree, position: position, dot: 14, gap: 6)
                FingerGlyph(fingers: .frontTwo, position: position, dot: 14, gap: 6)
                FingerGlyph(fingers: .backTwo, position: position, dot: 14, gap: 6)
                Text(position.name)
                    .font(.system(.footnote, weight: .medium))
                    .foregroundStyle(Ink.secondary)
            }
        }
    }
    .padding()
    .background { AppBackground() }
}
