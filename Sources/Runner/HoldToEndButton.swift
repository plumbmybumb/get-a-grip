// SPDX-License-Identifier: MPL-2.0
// Original contributions Copyright 2026 Nuri Bruner.

import SwiftUI

/// Ending a session takes a deliberate HOLD, not a tap plus a dialog.
///
/// A confirmation sheet mid-workout is two taps with chalk on your hands, and the second
/// one is the reflex you learn to fire without reading. A hold carries the same "are you
/// sure" in the gesture itself: the button fills while you mean it, and letting go early
/// costs nothing. Nothing is destroyed either way — everything already done is kept.
struct HoldToEndButton: View {
    var allowsScrolling = false
    var action: () -> Void

    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @State private var progress: Double = 0
    /// SEPARATE from `progress`, and that is the entire fix for the overlap.
    ///
    /// The label used to read `progress > 0`, so it changed INSIDE the 0.9 s
    /// `withAnimation` that drives the fill — and SwiftUI cross-fades a Text whose
    /// content changes under an animation. Two strings of different widths, both
    /// half-opaque, sat on top of each other for the whole hold. Flipping a plain Bool
    /// outside the transaction swaps the label instantly instead.
    @State private var isHolding = false
    @State private var holdTask: Task<Void, Never>?
    @State private var firedTick = 0
    @State private var slidOff = false
    @State private var hitFrame: CGRect = .zero

    /// Long enough to be deliberate, short enough not to feel like a punishment.
    private static let holdSeconds: Double = 0.9
    private static let slideSlop: CGFloat = 24

    var body: some View {
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
            GeometryReader { geo in
                ZStack {
                    Capsule().fill(Accent.alarm.opacity(0.16))
                    Capsule()
                        .fill(Accent.alarm.opacity(0.42))
                        .mask(alignment: .leading) {
                            Rectangle()
                                .frame(width: geo.size.width * progress)
                                .frame(maxWidth: .infinity, alignment: .leading)
                        }
                }
            }
        }
        .contentShape(.capsule)
        .onGeometryChange(for: CGRect.self) { $0.frame(in: .global) } action: { hitFrame = $0 }
        .simultaneousGesture(
            DragGesture(minimumDistance: 0, coordinateSpace: .global)
                .onChanged {
                    if allowsScrolling,
                       abs($0.translation.width) > 10 || abs($0.translation.height) > 10 {
                        slidOff = true
                        cancelHold()
                    } else {
                        let localPoint = CGPoint(x: $0.location.x - hitFrame.minX,
                                                 y: $0.location.y - hitFrame.minY)
                        updateHold(at: localPoint, in: hitFrame.size)
                    }
                }
                .onEnded { _ in endHold() }
        )
        .onDisappear { endHold() }
        .sensoryFeedback(.impact(weight: .heavy, intensity: 0.9), trigger: firedTick)
        .accessibilityElement()
        .accessibilityLabel("End session")
        .accessibilityHint("Press and hold to end. Everything you've already done is kept.")
        .accessibilityAddTraits(.isButton)
        // VoiceOver cannot express a hold, so an activation ends it outright — the
        // gesture is the safeguard for a thumb, not a substitute for the action.
        .accessibilityAction { action() }
    }

    private func updateHold(at location: CGPoint, in size: CGSize) {
        guard !slidOff else { return }
        let bounds = CGRect(origin: .zero, size: size)
            .insetBy(dx: -Self.slideSlop, dy: -Self.slideSlop)
        guard bounds.contains(location) else {
            slidOff = true
            cancelHold()
            return
        }
        beginHold()
    }

    private func endHold() {
        cancelHold()
        slidOff = false
    }

    private func beginHold() {
        guard holdTask == nil else { return }
        // OUTSIDE the animation, deliberately — see `isHolding`.
        isHolding = true
        // UNCONDITIONAL — deliberately not gated on `reduceMotion`, and that is a
        // decision rather than an oversight (audit rank 28). This fill is the
        // functional progress readout for a 0.9 s hold-to-confirm gesture — how much
        // longer to keep holding — not decorative motion; snapping straight to a full
        // bar under Reduce Motion would remove the one signal that the hold is
        // registering at all, while the gesture itself still takes exactly 0.9 s
        // either way. It also has to match `holdTask`'s real sleep, which no token
        // can express.
        withAnimation(.linear(duration: Self.holdSeconds)) { progress = 1 }
        holdTask = Task { @MainActor in
            try? await Task.sleep(for: .seconds(Self.holdSeconds))
            guard !Task.isCancelled else { return }
            firedTick += 1
            action()
        }
    }

    private func cancelHold() {
        holdTask?.cancel()
        holdTask = nil
        isHolding = false
        // `Motion.state(reduceMotion)` already resolves to `Motion.reduced` when the
        // flag is set — the ternary was redundant and produced a second, divergent,
        // untokenised reduced-motion curve.
        withAnimation(Motion.state(reduceMotion)) { progress = 0 }
    }
}
