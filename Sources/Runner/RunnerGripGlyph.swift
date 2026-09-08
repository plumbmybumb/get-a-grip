// SPDX-License-Identifier: MPL-2.0
// Original contributions Copyright 2026 Nuri Bruner.

import SwiftUI

/// The in-content glyph on phones without an island. At accessibility sizes its
/// allocated height includes the same scaling as its bars, thumb and emphasis.
struct RunnerGripGlyph: View {
    let grip: GripSpec
    let emphasized: Bool
    @Environment(\.dynamicTypeSize) private var typeSize
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @ScaledMetric(relativeTo: .caption) private var scaledDot: CGFloat = 18
    @ScaledMetric(relativeTo: .caption) private var scaledGap: CGFloat = 7

    var body: some View {
        FingerGlyph(fingers: grip.fingers, position: grip.position, dot: 18, gap: 7,
                    tint: emphasized ? StatusTint.armed : Accent.graphite)
            .scaleEffect(emphasized && !reduceMotion ? 1.25 : 1)
            .frame(height: typeSize.isAccessibilitySize ? accessibleHeight : 44)
    }

    private var accessibleHeight: CGFloat {
        let thumb = grip.fingers.hasThumb ? max(1.5, scaledGap * 0.6) + scaledDot * 0.62 : 0
        // Always reserve the emphasis size so its arrival does not move the name.
        return max(44, (scaledDot * 1.75 + thumb) * 1.25)
    }
}
