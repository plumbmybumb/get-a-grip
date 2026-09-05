// SPDX-License-Identifier: MPL-2.0
// Original contributions Copyright 2026 Nuri Bruner.

import SwiftUI

/// **The grip picker, with the Dynamic Island as the palm of your hand.**
///
/// Nuri's idea (2026-08-11), and the runner's own trick made interactive: `IslandHand`
/// already hangs black capsules under the island during a session so the cutout reads as a
/// palm. **No app may draw INSIDE the island** — it is hardware, it renders only Live
/// Activities, and those are declarative and non-interactive — so this draws a WHITE palm
/// around it. The island's black sits in the middle of that white lozenge like the hollow
/// of a hand, and four tappable fingers hang off it.
///
/// **The fingers are the control.** Black with a hairline outline while they are off the
/// edge, solid white when they are on it, exactly the reading the runner gives you at arm's
/// length. Nothing else in the app says "which fingers" as fast as this does.
///
/// **It hangs BELOW the safe area for the card, and above it for the hand.** An earlier
/// build ran a black panel to y = 0 so its black met the island's black. It worked — but a
/// panel under the status bar needs light status-bar text, which means
/// `preferredColorScheme(.dark)`, and **that propagates to the WINDOW**: the builder sheet
/// behind it turned dark and STAYED dark after dismissal. The palm is narrow and centred,
/// so it clears the clock on the left and the battery on the right and needs no such trick.
///
/// **A `fullScreenCover`, not a sheet.** The builder is itself a sheet whose top edge sits
/// ~50 pt below the island, so nothing in that view tree can reach up there.
struct GripIslandPanel: View {
    @Binding var grip: GripSpec
    var onClose: () -> Void

    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @State private var shown = false

    /// Rounded at the BOTTOM only. The top is square and flush with the screen edge, so
    /// the panel and the island are one black object.
    private static let bottomRadius: CGFloat = 42
    /// Everything above this belongs to the hand.
    private static let handClearance: CGFloat = 148

    var body: some View {
        GeometryReader { geo in
            ZStack(alignment: .top) {
                // Darker than a normal scrim: the unselected fingers are BLACK, and they
                // have to read against whatever routine is behind them.
                Color.black
                    .opacity(shown ? 0.72 : 0)
                    .ignoresSafeArea()
                    .contentShape(.rect)
                    .onTapGesture { close() }
                    .accessibilityLabel(String(localized: "Close the grip picker"))
                    .accessibilityAddTraits(.isButton)

                if shown {
                    ZStack(alignment: .top) {
                        card
                        IslandHandPicker(fingers: $grip.fingers,
                                         position: grip.position,
                                         screenWidth: geo.size.width)
                    }
                    .transition(.move(edge: .top).combined(with: .opacity))
                }
            }
        }
        .ignoresSafeArea()
        // HIDDEN, not re-themed. The panel runs to y = 0 and the clock would be dark text
        // on black. `preferredColorScheme(.dark)` fixes that and propagates to the WINDOW —
        // measured 2026-08-11, the builder sheet behind turned dark and STAYED dark after
        // dismissal. Hiding the bar for the few seconds this is open costs nothing and
        // leaks nothing; the island is hardware and stays black either way.
        .statusBarHidden(true)
        .onAppear {
            withAnimation(Motion.state(reduceMotion)) { shown = true }
        }
    }

    private var card: some View {
        VStack(alignment: .leading, spacing: 12) {
            name
            block(String(localized: "GRIP")) {
                PositionChipRow(selection: $grip.position)
            }
            IntValueRow(title: String(localized: "Edge"), unit: String(localized: "mm"), value: $grip.edgeMM,
                        range: 4...45, limit: GripSpec.edgeRange,
                        control: .dial([6, 10, 15, 20, 25, 30, 35, 45]))
            // Said in words, mirroring `FingerPips` — the locked thumb bar dims and
            // refuses taps with nothing else on screen to say why, and this is the
            // primary place a grip gets edited. A control that looks live and refuses
            // the tap is the exact failure the hit-target rule exists to prevent.
            if grip.position == .pinch {
                Text("A pinch always includes the thumb.")
                    .font(.system(.caption, weight: .medium))
                    .foregroundStyle(Ink.tertiary)
                    .fixedSize(horizontal: false, vertical: true)
            }
            done
        }
        .padding(.horizontal, Metrics.hPadding)
        // Clears the hand hanging off the island above it.
        .padding(.top, Self.handClearance)
        .padding(.bottom, 20)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background {
            UnevenRoundedRectangle(bottomLeadingRadius: Self.bottomRadius,
                                   bottomTrailingRadius: Self.bottomRadius,
                                   style: .continuous)
                .fill(Color.black)
                .ignoresSafeArea(edges: .top)
        }
        .ignoresSafeArea(edges: .top)
        // Scoped, NOT `preferredColorScheme`: every adaptive token resolves dark inside the
        // card so `Accent.graphite` comes out near-white on black, and nothing leaks to the
        // window behind it.
        .environment(\.colorScheme, .dark)
    }

    /// The grip, said in one line — the sentence that proves the hand above means what you
    /// think it does.
    private var name: some View {
        Text("\(grip.edgeMM) mm · \(grip.fingers.name) · \(grip.position.name)")
            .font(.system(.headline, weight: .semibold))
            .monospacedDigit()
            .foregroundStyle(Ink.primary)
            .lineLimit(1)
            .minimumScaleFactor(0.7)
            .contentTransition(.numericText())
            .animation(Motion.state(reduceMotion), value: grip)
    }

    private var done: some View {
        Button(action: close) {
            Text("Done")
                .font(.system(.body, weight: .semibold))
                .foregroundStyle(Color.black)
                .frame(maxWidth: .infinity, minHeight: 46)
                .background { Capsule().fill(Ink.primary) }
                .contentShape(.capsule)
        }
        .buttonStyle(PressFeedbackButtonStyle())
        .padding(.top, 2)
    }

    @ViewBuilder
    private func block<Content: View>(_ label: String,
                                      @ViewBuilder content: () -> Content) -> some View {
        VStack(alignment: .leading, spacing: 8) {
            CapsLabel(label)
            content()
        }
    }

    private func close() {
        withAnimation(Motion.state(reduceMotion)) { shown = false }
        // Let the panel travel before the cover goes, or it vanishes instead of retracting.
        Task { @MainActor in
            try? await Task.sleep(for: .milliseconds(reduceMotion ? 200 : 280))
            onClose()
        }
    }
}

// MARK: - The hand

/// A white palm around the island, with tappable fingers hanging off it.
///
/// The geometry MIRRORS `IslandHand` and has to: the island is 126 × 37.33 pt, 11 pt from
/// the top, on every device that has one, and there is no public API for its frame. What
/// differs is the finger PITCH. The runner keeps its bars inside the island's own width
/// with an 8 pt gap, which is right for a drawing nobody touches — but a 30 pt pitch is an
/// illegal tap target. These sit at 44 pt centres so each finger is a control, and the palm
/// widens to match. Everything else — the capsule radius, the hand's length ratios — is the
/// same object the runner draws.
private struct IslandHandPicker: View {
    @Binding var fingers: FingerSet
    var position: GripPosition
    var screenWidth: CGFloat

    @State private var tapTick = 0

    // The island, as Apple builds it. Not discoverable at runtime; measured. See
    // `IslandHand`, which must agree with these.
    private static let islandWidth: CGFloat = 126
    private static let islandTop: CGFloat = 11
    private static let islandHeight: CGFloat = 37.33

    /// **The island IS the palm.** The panel behind it is black and runs to the top of the
    /// screen, so the cutout and the panel are one object and the fingers hang straight off
    /// it — exactly what `IslandHand` does during a session. A white lozenge drawn around
    /// the island was tried first and thrown out (Nuri, 2026-08-11: "kinda looks like
    /// shit"): it cut the black in half at precisely the point the eye needs it continuous.
    private static var palmBottom: CGFloat { islandTop + islandHeight }

    private static let barWidth: CGFloat = 26
    /// 44 pt centres. The hit target IS the pitch, so the fingers cannot overlap and none
    /// of them is under the floor.
    private static let pitch: CGFloat = 44
    private static let baseLength: CGFloat = 46
    /// A hand's proportions, index → little. The same array the runner uses.
    private static let lengthFactor: [CGFloat] = [0.86, 1.0, 0.94, 0.80]
    /// The clearance every part of the hand keeps from the palm.
    private static let gap: CGFloat = 6

    /// **The thumb is a HORIZONTAL BAR under the fingers**, spanning about two columns —
    /// the same shape `FingerPips` draws, for the reason already written down there: a
    /// thumb drawn vertical is just a short fifth finger, and it sits under the hand,
    /// where a thumb goes when a hand pinches. The first build angled it off the palm's
    /// side like the runner's, which reads as a hand only when it is ATTACHED to the
    /// island — out here at a 44 pt finger pitch the hand is wider than the palm, so the
    /// thumb ended up floating clear of everything and looked like a stray pill.
    private static let thumbWidth: CGFloat = 70
    private static let thumbThickness: CGFloat = 26
    private static var thumbTop: CGFloat { fingersTop + rowHeight + 6 }

    private static var handWidth: CGFloat { pitch * 3 + barWidth }
    private static var fingersTop: CGFloat { palmBottom + gap }
    /// ONE box height for all four, so they share a top edge and the hit areas line up.
    private static var rowHeight: CGFloat { max(44, baseLength) }

    private static let names = [String(localized: "Index"), String(localized: "Middle"),
                                 String(localized: "Ring"), String(localized: "Little")]

    /// A pinch IS thumb opposition, so under it the thumb is not a choice — `GripSpec`
    /// enforces the same rule in the model.
    private var locksThumb: Bool { position == .pinch }

    var body: some View {
        ZStack(alignment: .topLeading) {
            ForEach(FingerSet.allFingers.indices, id: \.self) { index in
                finger(index)
            }
            thumb
        }
        .frame(width: screenWidth, alignment: .topLeading)
        .sensoryFeedback(.selection, trigger: tapTick)
        .accessibilityElement(children: .contain)
        .accessibilityLabel(String(localized: "Fingers on the edge"))
    }

    private var mid: CGFloat { screenWidth / 2 }

    private func finger(_ index: Int) -> some View {
        let isOn = fingers.contains(FingerSet.allFingers[index])
        let length = Self.baseLength * Self.lengthFactor[index]
        let centreX = mid - Self.handWidth / 2 + Self.barWidth / 2 + CGFloat(index) * Self.pitch

        return Button {
            toggle(FingerSet.allFingers[index])
        } label: {
            // TOP-ALIGNED, and every box the same height: fingers HANG from a palm, so
            // they share a top edge and differ at the tip. Centring each capsule in its
            // own hit box (the first build) floated the little finger in mid-air and read
            // as four unrelated pills rather than a hand.
            ZStack(alignment: .top) {
                // The hit area is the PITCH, not the bar — a 26 pt capsule is a drawing,
                // not a target.
                Color.clear.frame(width: Self.pitch, height: Self.rowHeight)
                Capsule()
                    .fill(isOn ? Color.white : Color.black)
                    .overlay {
                        if !isOn {
                            Capsule().strokeBorder(Color.white.opacity(0.5), lineWidth: 1.5)
                        }
                    }
                    .frame(width: Self.barWidth, height: length)
            }
            .contentShape(.rect)
        }
        .buttonStyle(PressFeedbackButtonStyle())
        .position(x: centreX, y: Self.fingersTop + Self.rowHeight / 2)
        .accessibilityLabel(String(localized: "\(Self.names[index]) finger, \(isOn ? String(localized: "included") : String(localized: "not included"))"))
        .accessibilityAddTraits(isOn ? [.isSelected] : [])
    }

    private var thumb: some View {
        let isOn = fingers.hasThumb
        return Button {
            toggle(.thumb)
        } label: {
            ZStack {
                Color.clear.frame(width: Self.thumbWidth, height: 44)
                Capsule()
                    .fill(isOn ? Color.white : Color.black)
                    .overlay {
                        if !isOn {
                            Capsule().strokeBorder(Color.white.opacity(0.5), lineWidth: 1.5)
                        }
                    }
                    .frame(width: Self.thumbWidth, height: Self.thumbThickness)
            }
            .contentShape(.rect)
        }
        .buttonStyle(PressFeedbackButtonStyle())
        .disabled(locksThumb)
        .opacity(locksThumb ? 0.55 : 1)
        .position(x: mid - Self.handWidth / 2 + Self.thumbWidth / 2,
                  y: Self.thumbTop + 22)
        .accessibilityLabel(String(localized: "Thumb, \(isOn ? String(localized: "included") : String(localized: "not included"))"))
        .accessibilityHint(locksThumb ? String(localized: "A pinch always includes the thumb") : "")
        .accessibilityAddTraits(isOn ? [.isSelected] : [])
    }

    /// A set with no fingers on the edge is not a grip, so tapping the last engaged bar is
    /// a no-op — and gets no tick either, because confirming a refusal is how feedback
    /// stops meaning anything.
    private func toggle(_ finger: FingerSet) {
        if fingers.contains(finger) {
            guard fingers.count > 1 else { return }
            fingers.subtract(finger)
        } else {
            fingers.formUnion(finger)
        }
        tapTick += 1
    }
}
