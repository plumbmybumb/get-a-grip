// SPDX-License-Identifier: MPL-2.0
// Original contributions Copyright 2026 Nuri Bruner.

import SwiftUI

/// Ending a session takes a deliberate HOLD, not a tap plus a dialog.
///
/// A confirmation sheet mid-workout is two taps with chalk on your hands, and the second
/// one is the reflex you learn to fire without reading. A hold carries the same "are you
/// sure" in the gesture itself: the button fills while you mean it, and letting go early
/// costs nothing. Nothing is destroyed either way — everything already done is kept.
/// The gesture is `HoldToConfirm`'s.
struct HoldToEndButton: View {
    var allowsScrolling = false
    var action: () -> Void

    @State private var firedTick = 0

    var body: some View {
        // In a scrolling dock (accessibility text sizes) any drift cancels, so the page
        // still scrolls; otherwise a thumb may wander as long as it stays on the button.
        HoldToConfirm(cancel: allowsScrolling ? .drift(10) : .leavingBounds(slop: 24),
                      accessibilityLabel: "End session",
                      accessibilityHint: "Press and hold to end. Everything you've already done is kept.",
                      action: { firedTick += 1; action() }) { isHolding, progress in
            ZStack {
                // Reserve both titles so beginning a hold cannot reflow the action row.
                Text("Keep holding…").hidden().accessibilityHidden(true)
                Text("Hold to end").hidden().accessibilityHidden(true)
                Text(isHolding ? "Keep holding…" : "Hold to end")
                    .foregroundStyle(Accent.alarm)
                    .contentTransition(.identity)
                    .animation(nil, value: isHolding)
            }
            .font(.system(.subheadline, weight: .semibold))
            .actionLabelLayout(fullWidth: true, fillsRowHeight: true)
            .background {
                HoldFill(progress: progress, tint: Accent.alarm, track: 0.16, fill: 0.42)
            }
        }
        .sensoryFeedback(.impact(weight: .heavy, intensity: 0.9), trigger: firedTick)
    }
}

