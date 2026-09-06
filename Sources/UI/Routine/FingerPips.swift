// SPDX-License-Identifier: MPL-2.0
// Original contributions Copyright 2026 Nuri Bruner.

import SwiftUI

/// Four tappable bars — index to little — that say which fingers are on the edge.
///
/// This is the control that settles the app's own worst ambiguity: "front 2" and
/// "middle 2" are one word apart and two different grips, and a picture settles what a
/// label flips a coin on. It beats a ten-chip grid on discoverability and on
/// composability (any combination, no enumeration), and under the no-emoji rule it
/// doubles as the app's iconography.
///
/// The bars are drawn as a hand — middle tallest, little shortest — but each one is
/// TAPPED through a full-height container, so the target stays ≥44pt even where the bar
/// is short.
struct FingerPips: View {
    @Binding var fingers: FingerSet
    var position: GripPosition
    /// Drops the name line. For the setup deck's grip card, where the navigation
    /// subtitle already carries the full grip name and the card is fighting for every
    /// point of height — two copies of "4 fingers" one above the other is the cheapest
    /// 32 pt in the whole layout.
    var showsName: Bool = true

    /// **The DRAWN bar, at the island hand's proportions** — see `barLengthRatio`. Genuinely
    /// fixed geometry, so it scales the sanctioned way rather than through a bare
    /// `.system(size:)`. The compact width is for the setup deck's grip card, which is
    /// fighting for every point of height.
    ///
    /// **CLAMPED**, the trap `ConsistencyCard`'s dot already fixed: four uncapped bars at
    /// 10 pt spacing plus the thumb container (`hitWidth * 4 + 30`) demand roughly 350 pt
    /// at accessibility3 against ~360 pt of usable width, on a screen (the max composer's
    /// FINGERS block) with no horizontal fallback and no sentence escape. A picker that
    /// overflows offscreen while building a custom grip is worse than one that stops
    /// growing.
    @ScaledMetric(relativeTo: .body) private var rawBarWidth: CGFloat = 34
    @ScaledMetric(relativeTo: .body) private var rawCompactBarWidth: CGFloat = 28
    private var barWidth: CGFloat { min(rawBarWidth, 48) }
    private var compactBarWidth: CGFloat { min(rawCompactBarWidth, 40) }

    /// 22 pt wide × 38 pt long, straight off `IslandHand`. Matching the RADIUS RULE alone
    /// was not enough: at the old 40 × 52 a full-capsule bar came out as a fat oval, which
    /// is not the shape hanging off the island and not a finger either. A drawing is its
    /// proportions as much as its corners.
    private static let barLengthRatio: CGFloat = 38.0 / 22.0

    private var width: CGFloat { showsName ? barWidth : compactBarWidth }
    private var height: CGFloat { width * Self.barLengthRatio }
    /// The tap target, which is NOT the drawing: a 34 pt bar is under the 44 pt floor, and
    /// the transparent container around it is what makes the control legal.
    private var hitWidth: CGFloat { max(44, width) }

    @Environment(\.accessibilityReduceMotion) private var reduceMotion

    /// Index, middle, ring, little — same order as `FingerSet.allFingers`, so the
    /// drawing can never disagree with the token.
    private static let names = [String(localized: "Index"), String(localized: "Middle"),
                                 String(localized: "Ring"), String(localized: "Little")]
    /// A hand's proportions, not a bar chart's. Applied to the DRAWN bar only.
    private static let heightFactor: [CGFloat] = [0.86, 1.0, 0.94, 0.80]

    var body: some View {
        VStack(alignment: .leading, spacing: showsName ? 8 : 6) {
            // The canonical name, live: the same string every other surface in the app
            // uses for this grip, so the picture and the words are never two answers.
            if showsName {
                Text(fingers.name)
                    .font(.system(.subheadline, weight: .semibold))
                    .foregroundStyle(Ink.primary)
                    .animation(Motion.state(reduceMotion),
                               value: fingers)
            }

            HStack(spacing: 10) {
                // By index: the bit and its spoken NAME live in two parallel arrays that
                // have to stay in step, and the index is what keeps them honest.
                ForEach(FingerSet.allFingers.indices, id: \.self) { index in
                    pip(index: index, finger: FingerSet.allFingers[index])
                }
            }
            .accessibilityElement(children: .contain)

            thumbBar

            // Said in words, because the locked bar cannot say it by drawing. A control
            // that looks live and refuses the tap is the exact failure this codebase has
            // a rule about; disabling it stops the false press feedback, and this line is
            // what stops "disabled" reading as "broken".
            if locksThumb {
                Text("A pinch always includes the thumb.")
                    .font(.system(.caption, weight: .medium))
                    .foregroundStyle(Ink.tertiary)
                    .fixedSize(horizontal: false, vertical: true)
            }
        }
        // TRIGGERED BY THE TAP, not by the value.
        //
        // On `fingers`, one tap fired TWO haptics: choosing Pinch changes `position`
        // (which `PositionChipRow` already ticks for) and the model then forces the thumb
        // in, changing `fingers` and ticking again. A double tick for a single action is
        // the feedback rule's own failure mode — a tick has to name its cause, and
        // nothing here caused two.
        .sensoryFeedback(.selection, trigger: tapTick)
    }

    /// Bumped by `toggle` so the haptic fires for a FINGER TAP and nothing else.
    @State private var tapTick = 0

    /// A pinch IS thumb opposition, so under it the thumb is not a choice — see
    /// `GripSpec.fingers`, which enforces the same rule in the model.
    private var locksThumb: Bool { position == .pinch }

    private func pip(index: Int, finger: FingerSet) -> some View {
        let isOn = fingers.contains(finger)
        let name = Self.names[index]

        return Button {
            toggle(finger)
        } label: {
            ZStack(alignment: .bottom) {
                // The full-size container is the hit area; the bar inside is the
                // drawing. Without this the little finger's target would be 42pt.
                Color.clear
                RoundedRectangle(cornerRadius: cornerRadius, style: .continuous)
                    .fill(isOn ? Accent.graphite : Color.clear)
                    .overlay {
                        if !isOn {
                            RoundedRectangle(cornerRadius: cornerRadius, style: .continuous)
                                .strokeBorder(Ink.tertiary.opacity(0.45), lineWidth: 1)
                        }
                    }
                    .frame(width: width, height: height * Self.heightFactor[index])
            }
            .frame(width: hitWidth, height: height)
            .contentShape(.rect)
        }
        .buttonStyle(PressFeedbackButtonStyle())
        .accessibilityLabel(String(localized: "\(name) finger, \(isOn ? String(localized: "included") : String(localized: "not included"))"))
        .accessibilityAddTraits(isOn ? [.isSelected] : [])
    }

    /// **A FULL CAPSULE, exactly like `FingerGlyph` and the island hand.** The bar you tap
    /// while building a routine has to be the same object you see hanging off the island
    /// while doing it (Nuri, 2026-08-09) — a builder control that draws its own dialect of
    /// the app's only glyph is the thing that makes an interface feel assembled.
    private var cornerRadius: CGFloat { width / 2 }

    /// A set with no fingers on the edge is not a grip, so tapping the last engaged pip
    /// is a no-op rather than an empty state to recover from.
    /// The thumb, as the horizontal bar it is — under the fingers, where a thumb sits
    /// when a hand pinches. Its own control rather than a fifth column because a thumb
    /// drawn vertical would just be a short fifth finger, and the whole point of the
    /// glyph language is that you read the hand before you read a word.
    private var thumbBar: some View {
        let isOn = fingers.hasThumb
        // Spans the first two columns — the side a thumb actually opposes from.
        let thumbLength = hitWidth * 2 + 10
        // A thumb is the THICKEST digit, so it is drawn thicker than a finger rather than
        // thinner — the same call `IslandHand` makes, where a slim tab beside four fat
        // bars read as a mistake.
        let thumbThickness = width * 1.05

        return Button {
            toggle(.thumb)
        } label: {
            ZStack(alignment: .leading) {
                Color.clear
                // A `Capsule`, like the island's thumb and every finger here: its own
                // half-height is the radius, whatever the length.
                Capsule()
                    .fill(isOn ? Accent.graphite : Color.clear)
                    .overlay {
                        if !isOn {
                            Capsule().strokeBorder(Ink.tertiary.opacity(0.45), lineWidth: 1)
                        }
                    }
                    .frame(width: thumbLength, height: thumbThickness)
            }
            // The full pip-row width is the hit area, 44pt tall — the drawn bar alone
            // would be a sub-standard target and a leading-only one.
            .frame(width: hitWidth * 4 + 30, height: max(44, thumbThickness), alignment: .leading)
            .contentShape(.rect)
        }
        .buttonStyle(PressFeedbackButtonStyle())
        .disabled(locksThumb)
        .accessibilityLabel(String(localized: "Thumb, \(isOn ? String(localized: "included") : String(localized: "not included"))"))
        .accessibilityHint(locksThumb ? String(localized: "A pinch always includes the thumb") : "")
        .accessibilityAddTraits(isOn ? [.isSelected] : [])
    }

    private func toggle(_ finger: FingerSet) {
        let next = FingerSelection.toggling(finger, in: fingers)
        guard next != fingers else { return }
        fingers = next
        tapTick += 1
    }
}

/// Both hand pickers keep a real finger on the edge; the thumb cannot replace it.
enum FingerSelection {
    static func toggling(_ finger: FingerSet, in fingers: FingerSet) -> FingerSet {
        let next = fingers.contains(finger) ? fingers.subtracting(finger) : fingers.union(finger)
        return next.intersection(.four).isEmpty ? fingers : next
    }
}
