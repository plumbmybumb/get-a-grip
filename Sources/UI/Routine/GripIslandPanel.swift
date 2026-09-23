// SPDX-License-Identifier: MPL-2.0
// Original contributions Copyright 2026 Nuri Bruner.

import SwiftUI

/// The grip picker overlays the full-screen builder with a black panel flush to y = 0.
/// Square top corners meet the Dynamic Island; rounded bottom corners finish the card.
/// The status bar is hidden while this overlay is visible, without changing the window's
/// color scheme. Filled white fingers are selected; outlined fingers remain off the edge.
/// A white palm was tried and retired because it separated the hand from the black cutout.
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
    /// On a regular-width screen the panel is a centred black TAB from the top edge, not a
    /// slab across a 13-inch display. Every phone is narrower, so there it is full width.
    private static let widthCap: CGFloat = 520

    var body: some View {
        GeometryReader { geo in
            let panelWidth = min(geo.size.width, Self.widthCap)
            ZStack(alignment: .top) {
                // Darker than a normal scrim: the unselected fingers are BLACK.
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
                                         screenWidth: panelWidth)
                    }
                    .frame(width: panelWidth)
                    .frame(maxWidth: .infinity)
                    .transition(.move(edge: .top).combined(with: .opacity))
                }
            }
        }
        .ignoresSafeArea()
        // HIDDEN, not re-themed: the clock would be dark text on black, and
        // `preferredColorScheme(.dark)` propagates to the WINDOW (measured
        // 2026-08-11: the builder turned dark and STAYED dark after dismissal).
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
            // Said in words, mirroring `FingerPips`: the locked thumb bar refuses taps,
            // and a control that looks live and refuses with no reason on screen is the
            // failure the hit-target rule exists to prevent.
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
        // Scoped, NOT `preferredColorScheme`: adaptive tokens resolve dark inside
        // the card (`Accent.graphite` near-white on black) without touching the window.
        .environment(\.colorScheme, .dark)
    }

    /// The grip in one line — proof the hand above means what you think.
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
        guard shown else { return }
        // Follow the transition's completion, not a timed sleep; repeated taps
        // must not queue closes into the next presentation.
        withAnimation(Motion.state(reduceMotion), completionCriteria: .logicallyComplete) {
            shown = false
        } completion: {
            onClose()
        }
    }
}

// MARK: - The hand

/// The island as the palm, with tappable fingers hanging off it.
///
/// The geometry MIRRORS `IslandHand`: the island is 126 × 37.33 pt, 11 pt from the top,
/// on every device that has one, with no public API for its frame. What differs is the
/// finger PITCH: the runner's 30 pt pitch is right for a drawing and an illegal tap
/// target, so these sit at 44 pt centres. Capsule radius and length ratios are the same.
private struct IslandHandPicker: View {
    @Binding var fingers: FingerSet
    var position: GripPosition
    var screenWidth: CGFloat

    @State private var tapTick = 0

    // The island, measured (not discoverable at runtime). `IslandHand` must agree.
    private static let islandWidth: CGFloat = 126
    private static let islandTop: CGFloat = 11
    private static let islandHeight: CGFloat = 37.33

    /// **The island IS the palm.** The black panel runs to the top, so cutout and panel are
    /// one object with the fingers hanging off it, as in `IslandHand`. A white lozenge palm
    /// was thrown out (Nuri, 2026-08-11): it cut the black exactly where the eye needs it
    /// continuous.
    private static var palmBottom: CGFloat { islandTop + islandHeight }

    private static let barWidth: CGFloat = 26
    /// 44 pt centres: the hit target IS the pitch, so no finger overlaps or falls under the
    /// floor.
    private static let pitch: CGFloat = 44
    private static let baseLength: CGFloat = 46
    /// The clearance every part of the hand keeps from the palm.
    private static let gap: CGFloat = 6

    /// **The thumb is a HORIZONTAL BAR under the fingers**, about two columns wide, as in
    /// `FingerPips`. Angled off the palm like the runner's, it only reads as a hand when
    /// ATTACHED to the island; at a 44 pt pitch the hand is wider than the palm, and the
    /// thumb floated like a stray pill.
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
        let length = Self.baseLength * HandGeometry.lengthFactor[index]
        let centreX = mid - Self.handWidth / 2 + Self.barWidth / 2 + CGFloat(index) * Self.pitch

        return Button {
            toggle(FingerSet.allFingers[index])
        } label: {
            // TOP-ALIGNED, every box the same height: fingers HANG from a palm, sharing
            // a top edge. Centred per box, the little finger floated and the four read
            // as unrelated pills.
            ZStack(alignment: .top) {
                // The hit area is the PITCH, not the 26 pt capsule.
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

    /// No fingers on the edge is not a grip, so tapping the last engaged bar is a no-op,
    /// with no tick: confirming a refusal is how feedback stops meaning anything.
    private func toggle(_ finger: FingerSet) {
        let next = FingerSelection.toggling(finger, in: fingers)
        guard next != fingers else { return }
        fingers = next
        tapTick += 1
    }
}
