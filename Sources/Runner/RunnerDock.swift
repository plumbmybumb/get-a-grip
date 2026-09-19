// SPDX-License-Identifier: MPL-2.0
// Original contributions Copyright 2026 Nuri Bruner.

import SwiftUI

// The runner's two GLASS SURFACES and the wells inside them — shared because the panel
// and the dock are siblings by design, and a second copy of either value is how two
// siblings stop matching.

/// The glass vocabulary the runner's floating surfaces share.
enum RunnerGlass {
    /// A context-aware shadow: these surfaces float over a MOVING curve, not a plain
    /// field, and Apple's own rule for glass is a heavier shadow over busy content than
    /// over calm. Light enough that the glass still reads as thin.
    static let floatingShadowOpacity = 0.16

    /// Sheet radius, not card radius: a glass surface floating over content is the
    /// system's sheet vocabulary, and beside 56 pt capsules a 22 pt corner reads tight.
    /// ONE shape for the panel and the dock, so the two cannot drift into different
    /// radius families while both claiming to be siblings.
    static var surfaceShape: RoundedRectangle {
        RoundedRectangle(cornerRadius: Metrics.radiusSheet, style: .continuous)
    }
}

extension View {
    /// The shadow both floating surfaces cast — see `RunnerGlass.floatingShadowOpacity`.
    func runnerFloatingShadow() -> some View {
        shadow(color: .black.opacity(RunnerGlass.floatingShadowOpacity), radius: 22, y: 10)
    }
}

extension View {
    /// A runner action's surface: its own glass capsule where it floats on the screen
    /// (the wide layout), a quiet ink well where it sits inside the dock — the same
    /// fill the house uses for an inset well, and never glass on glass.
    @ViewBuilder
    func runnerActionSurface(docked: Bool) -> some View {
        if docked {
            background(Capsule().fill(Ink.primary.opacity(0.05)))
        } else {
            accessibleGlass(nil, in: .capsule)
        }
    }
}
