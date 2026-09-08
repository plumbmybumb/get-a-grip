// SPDX-License-Identifier: MPL-2.0
// Original contributions Copyright 2026 Nuri Bruner.

import SwiftUI

/// Lives at the full-screen runner root so the system's window container supplies
/// the real corner shape. Safe-area insets are content margins, not corner radii.
struct RunnerScreenBorder: View {
    let cue: RunnerScreenCue

    private var tint: Color {
        switch cue {
        case .pulling: StatusTint.engaged
        case .releasing: StatusTint.armed
        case .warning: StatusTint.alarm
        case .resting: Ink.tertiary.opacity(0.65)
        }
    }

    var body: some View {
        let width = cue.lineWidth
        // ConcentricRectangle resolves each corner from the enclosing iOS 26
        // container, including square corners and resized windows. The stroke is
        // centered on an inset path: its outer half lands exactly on the boundary.
        ConcentricRectangle()
            .stroke(tint, lineWidth: width)
            .padding(width / 2)
            .ignoresSafeArea()
            .transaction { $0.animation = nil }
            .allowsHitTesting(false)
            .accessibilityHidden(true)
    }
}
