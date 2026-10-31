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
                      accessibilityHint: "Press and hold to end. Completed pulls are kept.",
                      action: { firedTick += 1; action() }) { isHolding, progress in
            title(isHolding: isHolding)
                .font(.system(.subheadline, weight: .semibold))
                .actionLabelLayout(fullWidth: true, fillsRowHeight: true)
                .background {
                    HoldFill(progress: progress, tint: Accent.alarm, track: 0.16, fill: 0.42)
                }
        }
        .sensoryFeedback(.impact(weight: .heavy, intensity: 0.9), trigger: firedTick)
        // In the dock the title shrinks rather than wraps, so it tells the row its floor:
        // a cell that cannot hold "Hold to end" at 0.8 drops the row to fewer columns.
        .actionLabelFit(allowsScrolling ? .wraps : .shrinks(minimumScale: Self.restingScale))
    }

    /// One line in the dock: a third of the row is narrow, and "Hold / to end" on two
    /// lines reads as two labels.
    ///
    /// **The RESTING title sizes the button; "Keep holding…" fits itself inside it.** Both
    /// used to be reserved, so the row measured the longer, momentary one — which at
    /// `large` on a 17 Pro is already past 0.8 of a third of the dock, and the measured
    /// three-across dock would have had to break for a word shown for 0.6 s. Laid over a
    /// cell-wide copy of the resting title, the hold title is offered exactly what the
    /// row gave, and its lower floor means it never truncates where the resting title fit
    /// (English 106 : 82 pt, so 0.8 × 82 ÷ 106 ≈ 0.62; French's "Maintiens…" is the
    /// SHORTER of its pair). Neither title can reflow the row when a hold begins.
    ///
    /// Accessibility sizes (a scrolling dock) still WRAP rather than shrink, with both
    /// titles reserved so the height cannot change mid-hold.
    @ViewBuilder
    private func title(isHolding: Bool) -> some View {
        if allowsScrolling {
            ZStack {
                Text("Keep holding…").hidden().accessibilityHidden(true)
                Text("Hold to end").hidden().accessibilityHidden(true)
                visibleTitle(isHolding: isHolding)
            }
        } else {
            Text("Hold to end")
                .hidden()
                .accessibilityHidden(true)
                .frame(maxWidth: .infinity)
                .overlay {
                    visibleTitle(isHolding: isHolding)
                        .minimumScaleFactor(isHolding ? Self.holdingScale : Self.restingScale)
                }
                .lineLimit(1)
                .minimumScaleFactor(Self.restingScale)
        }
    }

    private func visibleTitle(isHolding: Bool) -> some View {
        Text(isHolding ? "Keep holding…" : "Hold to end")
            .foregroundStyle(Accent.alarm)
            .contentTransition(.identity)
            .animation(nil, value: isHolding)
    }

    static let restingScale: CGFloat = 0.8
    static let holdingScale: CGFloat = 0.6
}

