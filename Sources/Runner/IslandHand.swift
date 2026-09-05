// SPDX-License-Identifier: MPL-2.0
// Original contributions Copyright 2026 Nuri Bruner.

import SwiftUI

/// **The Dynamic Island as the palm, with your hand hanging off it** — the runner's
/// grip display on any device that has one.
///
/// Nuri's idea (2026-08-09), shipped as the default the same day. It began behind an
/// `-islandHand` launch flag so it could be compared against the in-content glyph and
/// thrown away without a trace; it won, and a flag nobody sets is just a way to lose the
/// feature (which is exactly what happened the first time the app was launched without
/// it). What replaced the flag is `isSupported` — a question about the DEVICE, which is
/// the only thing that was ever really in question.
///
/// **The trick that makes it work:** the island is a hardware CUTOUT, not a view. No app
/// may draw inside it — but it is always pure black, always the same capsule, and always
/// in the same place. Drawing black capsules just below it with a matched corner radius
/// makes one object out of two, and the phone finishes the drawing.
///
/// **The geometry is hardcoded, and has to be.** There is no public API for the island's
/// frame. It is 126 × 37.33 pt, centred, 11 pt from the top on every device that has one,
/// and the only reliable signal that a device HAS one is a top safe-area inset of 59 pt
/// (a notch is 47–48, everything else ≤ 24). So this draws on island devices and quietly
/// draws nothing anywhere else — a hand hanging off a notch is a smear, and off a flat
/// top edge it is four bars stuck to the ceiling. The app is portrait-locked
/// (`UISupportedInterfaceOrientations`), which is what lets a fixed rect be correct.
struct IslandHand: View {
    let grip: GripSpec
    /// Which hand is pulling. **The whole hand mirrors with it** — see `mirrored`.
    let side: Side
    /// Dimmed while resting, so the island says "this is what's COMING" rather than
    /// "pull this now".
    var isActive: Bool = true
    var emphasized: Bool = false

    @Environment(\.accessibilityReduceMotion) private var reduceMotion

    /// The hand currently DRAWN, which lags `side` by one beat while the thumb
    /// retracts — see `swapSides`.
    @State private var shown: Side?
    /// 0 = thumb fully out, 1 = fully drawn into the palm.
    @State private var retract: CGFloat = 0

    // The island, as Apple builds it. Not discoverable at runtime; measured.
    private static let islandWidth: CGFloat = 126
    private static let islandTop: CGFloat = 11
    private static let islandHeight: CGFloat = 37.33
    private static var islandBottom: CGFloat { islandTop + islandHeight }
    /// Only a Dynamic Island reports 59. A notch reports 47–48.
    private static let islandInset: CGFloat = 55

    /// Four bars inside the island's own width, so they hang from within its footprint
    /// rather than splaying past its edges.
    private static let barWidth: CGFloat = 22
    private static let barGap: CGFloat = 8
    /// A hand's proportions, INDEX → LITTLE. Mirrored along with the fingers, so the
    /// middle finger is longest whichever way round the hand is drawn.
    private static let lengthFactor: [CGFloat] = [0.86, 1.0, 0.94, 0.80]
    private static let baseLength: CGFloat = 38
    /// The clearance every part of the hand keeps from the island — fingers and thumb
    /// alike, so the whole drawing reads as one family of detached shapes.
    private static let gap: CGFloat = 6

    /// Sized against a FINGER (22 pt wide) rather than as a stub: a thumb is the
    /// thickest digit on a hand, so a thin tab beside four fat bars reads as a mistake.
    private static let thumbLength: CGFloat = 38
    private static let thumbThickness: CGFloat = 22
    /// Shallow, not diagonal. A steeper thumb read as a detached pill lying at an angle,
    /// and it is the status bar that forces the choice: the cellular dots and the wifi
    /// glyph sit at the island's waist, so anything leaving from higher up runs straight
    /// into them. Low and shallow passes safely underneath.
    private static let thumbAngle: Double = 26

    /// **Does this device have a Dynamic Island?** The runner asks too — the layout
    /// underneath changes shape around the hand — so it is one answer, in one place.
    ///
    /// Read from the WINDOW, not from a `GeometryReader`. A reader nested inside a view
    /// whose parent has already applied the safe area reports insets of zero — which is
    /// why the first version of this drew nothing at all. Call it from `onAppear` or
    /// later: before the view is in a window there is no key window to ask, and the
    /// honest answer to an unanswerable question is "no island".
    @MainActor
    static var isSupported: Bool { windowTopInset >= islandInset }

    @MainActor
    private static var windowTopInset: CGFloat {
        UIApplication.shared.connectedScenes
            .compactMap { $0 as? UIWindowScene }
            .flatMap(\.windows)
            .first { $0.isKeyWindow }?
            .safeAreaInsets.top ?? 0
    }

    /// The hand being drawn, before the first `onChange` has ever run.
    private var drawn: Side { shown ?? side }

    /// **Facing a LEFT palm, the thumb is on the right — and so the index finger is the
    /// RIGHTMOST bar, not the leftmost.** Moving only the thumb was the bug: with the
    /// thumb switched but the fingers left alone, a front-2 grip rendered on the little
    /// side and read as back-2 (Nuri spotted it, 2026-08-09). The whole hand mirrors.
    private var mirrored: Bool { drawn != .right }

    var body: some View {
        // The reader is here for the screen WIDTH only — the island is centred, so the
        // hand's origin depends on it. Whether to draw at all was already decided by the
        // caller, from `isSupported`.
        GeometryReader { geo in
            hand(width: geo.size.width)
                .frame(height: 100, alignment: .top)
                // Keep the roots below the physical island; only the drawing grows.
                .scaleEffect(emphasized && !reduceMotion ? 1.22 : 1,
                             anchor: UnitPoint(x: 0.5, y: 0.5433))
        }
        .ignoresSafeArea()
        // Decoration over the status bar: it must never eat a touch, and VoiceOver
        // already hears the grip from the runner's own line.
        .allowsHitTesting(false)
        .accessibilityHidden(true)
        .onChange(of: side) { _, new in swapSides(to: new) }
        .onAppear { shown = side }
    }

    /// THE HAND SWAP: the thumb draws into the palm, the hand mirrors while it is hidden,
    /// and it grows back out of the other side. Hiding the mirror inside the retraction
    /// is what stops four bars visibly sliding past each other — the fingers are
    /// symmetric in outline, so with the thumb gone the flip is invisible and the whole
    /// change reads as one gesture.
    private func swapSides(to new: Side) {
        guard !reduceMotion else {
            shown = new
            return
        }
        withAnimation(.easeIn(duration: 0.16)) {
            retract = 1
        } completion: {
            shown = new
            withAnimation(.easeOut(duration: 0.24)) { retract = 0 }
        }
    }

    private func hand(width: CGFloat) -> some View {
        let total = Self.barWidth * 4 + Self.barGap * 3
        let originX = (width - total) / 2

        return ZStack(alignment: .topLeading) {
            ForEach(0..<4, id: \.self) { slot in
                finger(slot: slot, originX: originX)
            }
            if grip.fingers.hasThumb {
                thumb(width: width)
            }
        }
        .frame(width: width, alignment: .topLeading)
    }

    /// `slot` is the position ON SCREEN, left to right. Which finger lives there depends
    /// on the hand.
    private func finger(slot: Int, originX: CGFloat) -> some View {
        let anatomical = mirrored ? 3 - slot : slot
        let on = grip.fingers.contains(FingerSet.allFingers[anatomical])
        let length = Self.baseLength * Self.lengthFactor[anatomical]

        return RoundedRectangle(cornerRadius: Self.radius, style: .continuous)
            // **PURE BLACK, not the app's graphite.** Everywhere else `Accent.graphite`
            // is the ink; here the whole point is to be indistinguishable from the
            // hardware, and the island renders true black. A near-black bar beside a
            // black cutout reads as two materials, which is the tell that breaks it.
            .fill(on ? Color.black.opacity(isActive || emphasized ? 1 : 0.4)
                     : Color.black.opacity(0.12))
            .frame(width: Self.barWidth, height: length)
            .offset(x: originX + CGFloat(slot) * (Self.barWidth + Self.barGap),
                    y: Self.islandBottom + Self.gap)
            // STAGGERED from the thumb side inward, so a grip change ripples across the
            // hand instead of all four bars blinking at once. 45 ms is enough to read as
            // a sequence and short enough that the last finger is not visibly late.
            .animation(reduceMotion ? Motion.reduced
                                    : Motion.state(false).delay(Double(slot) * 0.045),
                       value: grip)
    }

    /// The island is a capsule — fully rounded, radius = half its height. The fingers
    /// borrow that language rather than the app's closure-driven radius, because
    /// cohesion with the hardware is the whole idea here and a squared-off full-crimp
    /// bar beside a capsule cutout looks like a different drawing.
    private static var radius: CGFloat { barWidth / 2 }

    private func thumb(width: CGFloat) -> some View {
        let islandLeft = (width - Self.islandWidth) / 2
        let onRight = mirrored

        // DETACHED, with the same clearance the fingers have. An earlier version pivoted
        // INSIDE the island and sat above its bottom edge, so it grew straight out of the
        // hardware — and a thumb welded to the palm is the one part of a hand nobody
        // draws that way.
        let pivotX = onRight ? islandLeft + Self.islandWidth + 2
                             : islandLeft - 2
        let pivotY = Self.islandBottom + Self.gap + 4

        return Capsule()
            .fill(Color.black.opacity(isActive || emphasized ? 1 : 0.4))
            .frame(width: Self.thumbLength, height: Self.thumbThickness)
            // Drawn INTO the palm on a hand swap: scaling along its own length toward the
            // root makes it disappear at the knuckle rather than shrinking to a dot.
            .scaleEffect(x: 1 - retract, y: 1,
                         anchor: onRight ? .leading : .trailing)
            // Anchored at the palm end so the rotation swings the TIP away and the root
            // stays where the geometry above put it.
            .rotationEffect(.degrees(onRight ? Self.thumbAngle : -Self.thumbAngle),
                            anchor: onRight ? .leading : .trailing)
            .offset(x: onRight ? pivotX : pivotX - Self.thumbLength,
                    y: pivotY - Self.thumbThickness / 2)
            .animation(reduceMotion ? Motion.reduced : Motion.state(false), value: grip)
    }
}

extension View {
    /// Hangs the current grip off the Dynamic Island.
    ///
    /// `enabled` is the CALLER's copy of `IslandHand.isSupported`, resolved once in
    /// `onAppear` rather than re-probed here: the runner's layout branches on the same
    /// answer, and a probe evaluated during the first body pass — before there is a key
    /// window to ask — would say "no island" and leave the screen laid out for a hand
    /// that then appears anyway.
    @ViewBuilder
    func islandHand(grip: GripSpec?, side: Side?, isActive: Bool, enabled: Bool, emphasized: Bool = false) -> some View {
        if enabled, let grip {
            overlay(alignment: .top) {
                IslandHand(grip: grip, side: side ?? .both, isActive: isActive, emphasized: emphasized)
            }
        } else {
            self
        }
    }
}
