// SPDX-License-Identifier: MPL-2.0
// Original contributions Copyright 2026 Nuri Bruner.

import SwiftUI

/// The press-and-hold gesture behind every "this cannot be undone" control (ending a
/// session, discarding one on phone or wrist) — one implementation, so hold length,
/// cancellation and VoiceOver cannot drift; each button keeps its own look and words.
///
/// The caller draws the label from `isHolding`, which flips OUTSIDE any animation, and
/// `progress`, which animates 0 → 1. A label reading `progress > 0` would change INSIDE
/// the fill's animation, and SwiftUI would cross-fade two strings over each other for
/// the whole hold.
struct HoldToConfirm<Label: View>: View {
    /// How a hold decides the finger has stopped meaning it.
    enum Cancel {
        /// Movement past this many points in GLOBAL space. A page scrolling under a parked finger
        /// moves it through the window but not across the control's own coordinates, so only
        /// global space sees it — for holds inside a scroll view. 10 pt matches `RepeatingStep`.
        case drift(CGFloat)
        /// Leaving the control's frame, grown by this much: a thumb may wander while on the button.
        case leavingBounds(slop: CGFloat)
    }

    /// Long enough to be deliberate, short enough not to feel like a punishment.
    static var defaultSeconds: Double { 0.9 }

    var seconds: Double = Self.defaultSeconds
    var cancel: Cancel
    var accessibilityLabel: LocalizedStringKey
    var accessibilityHint: LocalizedStringKey
    var action: () -> Void
    @ViewBuilder var label: (_ isHolding: Bool, _ progress: Double) -> Label

    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @Environment(\.isEnabled) private var isEnabled
    @State private var progress: Double = 0
    @State private var isHolding = false
    @State private var holdTask: Task<Void, Never>?
    @State private var slidOff = false
    @State private var hitFrame: CGRect = .zero

    var body: some View {
        label(isHolding, progress)
            .contentShape(.capsule)
            .onGeometryChange(for: CGRect.self) { $0.frame(in: .global) } action: { hitFrame = $0 }
            // Simultaneous, so a drag that starts here still scrolls whatever is around it.
            .simultaneousGesture(
                DragGesture(minimumDistance: 0, coordinateSpace: .global)
                    .onChanged { updateHold($0) }
                    .onEnded { _ in endHold() }
            )
            .onDisappear { endHold() }
            .accessibilityElement()
            .accessibilityLabel(accessibilityLabel)
            .accessibilityHint(accessibilityHint)
            .accessibilityAddTraits(.isButton)
            // VoiceOver cannot express a hold, so an activation confirms outright; the
            // gesture guards a thumb, it is not the safeguard itself.
            .accessibilityAction { if isEnabled { action() } }
    }

    private func updateHold(_ value: DragGesture.Value) {
        guard isEnabled, !slidOff else { return }
        let stillMeant: Bool
        switch cancel {
        case .drift(let slop):
            stillMeant = abs(value.translation.width) <= slop
                && abs(value.translation.height) <= slop
        case .leavingBounds(let slop):
            let local = CGPoint(x: value.location.x - hitFrame.minX,
                                y: value.location.y - hitFrame.minY)
            stillMeant = CGRect(origin: .zero, size: hitFrame.size)
                .insetBy(dx: -slop, dy: -slop)
                .contains(local)
        }
        guard stillMeant else {
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
        // OUTSIDE the animation, deliberately — see the type's note.
        isHolding = true
        // UNCONDITIONAL, not gated on `reduceMotion`: the fill is the functional
        // "how much longer" readout, not decoration, and snapping it full would
        // hide that the hold is registering. It must also match `holdTask`'s real
        // sleep, which no token can express.
        withAnimation(.linear(duration: seconds)) { progress = 1 }
        holdTask = Task { @MainActor in
            try? await Task.sleep(for: .seconds(seconds))
            guard !Task.isCancelled else { return }
            action()
        }
    }

    private func cancelHold() {
        holdTask?.cancel()
        holdTask = nil
        isHolding = false
        // `Motion.state` already resolves to `Motion.reduced` under Reduce Motion.
        withAnimation(Motion.state(reduceMotion)) { progress = 0 }
    }
}

/// The capsule a hold fills from the leading edge, drawn behind a `HoldToConfirm` label.
struct HoldFill: View {
    var progress: Double
    var tint: Color
    /// The resting capsule's opacity, and the fill's.
    var track: Double
    var fill: Double

    var body: some View {
        GeometryReader { geo in
            ZStack {
                Capsule().fill(tint.opacity(track))
                Capsule()
                    .fill(tint.opacity(fill))
                    .mask(alignment: .leading) {
                        Rectangle()
                            .frame(width: geo.size.width * progress)
                            .frame(maxWidth: .infinity, alignment: .leading)
                    }
            }
        }
    }
}
