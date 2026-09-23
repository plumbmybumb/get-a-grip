// SPDX-License-Identifier: MPL-2.0
// Original contributions Copyright 2026 Nuri Bruner.

import SwiftUI

/// Four tappable bars — index to little — that say which fingers are on the edge.
///
/// "Front 2" and "middle 2" are one word apart and two grips; a picture settles what a
/// label flips a coin on. It beats a ten-chip grid (any combination, no enumeration) and
/// doubles as the app's iconography under the no-emoji rule.
///
/// Drawn as a hand, but each bar is TAPPED through a full-height container, so the target
/// stays ≥44pt where the bar is short.
struct FingerPips: View {
    @Binding var fingers: FingerSet
    var position: GripPosition
    /// Drops the name line where a navigation subtitle already carries the grip name and
    /// height is scarce.
    var showsName: Bool = true

    /// **The DRAWN bar, at the island hand's proportions** — see `barLengthRatio`. Fixed
    /// geometry, so it scales the sanctioned way rather than via a bare `.system(size:)`.
    ///
    /// **CLAMPED**, like `ConsistencyCard`'s dot: uncapped, four bars plus the thumb
    /// container (`hitWidth * 4 + 30`) want ~350 pt at accessibility3 against ~360 usable,
    /// on the max composer with no horizontal fallback. A picker that stops growing beats
    /// one that overflows offscreen.
    @ScaledMetric(relativeTo: .body) private var rawBarWidth: CGFloat = 34
    @ScaledMetric(relativeTo: .body) private var rawCompactBarWidth: CGFloat = 28
    private var barWidth: CGFloat { min(rawBarWidth, 48) }
    private var compactBarWidth: CGFloat { min(rawCompactBarWidth, 40) }

    /// 22 pt wide × 38 pt long, straight off `IslandHand`. The radius rule alone was not
    /// enough: at 40 × 52 a full capsule was a fat oval. A drawing is its proportions as
    /// much as its corners.
    private static let barLengthRatio: CGFloat = 38.0 / 22.0

    private var width: CGFloat { showsName ? barWidth : compactBarWidth }
    private var height: CGFloat { width * Self.barLengthRatio }
    /// The tap target, NOT the drawing: a 34 pt bar is under the 44 pt floor.
    private var hitWidth: CGFloat { max(44, width) }

    @Environment(\.accessibilityReduceMotion) private var reduceMotion

    /// Same order as `FingerSet.allFingers`, so the drawing never disagrees with the token.
    private static let names = [String(localized: "Index"), String(localized: "Middle"),
                                 String(localized: "Ring"), String(localized: "Little")]
    var body: some View {
        VStack(alignment: .leading, spacing: showsName ? 8 : 6) {
            // The canonical name, live, so the picture and the words are one answer.
            if showsName {
                Text(fingers.name)
                    .font(.system(.subheadline, weight: .semibold))
                    .foregroundStyle(Ink.primary)
                    .animation(Motion.state(reduceMotion),
                               value: fingers)
            }

            HStack(spacing: 10) {
                // By index: the bit and its spoken NAME are parallel arrays kept in step.
                ForEach(FingerSet.allFingers.indices, id: \.self) { index in
                    pip(index: index, finger: FingerSet.allFingers[index])
                }
            }
            .accessibilityElement(children: .contain)

            thumbBar

            // Said in words, because the locked bar cannot. Disabling stops the false
            // press feedback; this line stops "disabled" reading as "broken".
            if locksThumb {
                Text("A pinch always includes the thumb.")
                    .font(.system(.caption, weight: .medium))
                    .foregroundStyle(Ink.tertiary)
                    .fixedSize(horizontal: false, vertical: true)
            }
        }
        // TRIGGERED BY THE TAP, not by the value: on `fingers`, choosing Pinch
        // ticked twice (once for `position` in `PositionChipRow`, again when the
        // model forces the thumb in). A tick names its cause, and one tap caused one.
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
                // The full-size container is the hit area; without it the little finger's
                // target would be 42pt.
                Color.clear
                RoundedRectangle(cornerRadius: cornerRadius, style: .continuous)
                    .fill(isOn ? Accent.graphite : Color.clear)
                    .overlay {
                        if !isOn {
                            RoundedRectangle(cornerRadius: cornerRadius, style: .continuous)
                                .strokeBorder(Ink.tertiary.opacity(0.45), lineWidth: 1)
                        }
                    }
                    // A hand's proportions (`HandGeometry`), on the DRAWN bar only.
                    .frame(width: width, height: height * HandGeometry.lengthFactor[index])
            }
            .frame(width: hitWidth, height: height)
            .contentShape(.rect)
        }
        .buttonStyle(PressFeedbackButtonStyle())
        .accessibilityLabel(String(localized: "\(name) finger, \(isOn ? String(localized: "included") : String(localized: "not included"))"))
        .accessibilityAddTraits(isOn ? [.isSelected] : [])
    }

    /// **A FULL CAPSULE, like `FingerGlyph` and the island hand.** The bar you tap while
    /// building must be the object you see hanging off the island while training (Nuri,
    /// 2026-08-09); a control with its own dialect of the app's only glyph feels assembled.
    private var cornerRadius: CGFloat { width / 2 }

    /// A set with no fingers on the edge is not a grip, so tapping the last engaged pip
    /// is a no-op rather than an empty state to recover from.
    /// The thumb, as a horizontal bar under the fingers, where a pinching thumb sits. Not a
    /// fifth column: drawn vertical, a thumb is just a short fifth finger.
    private var thumbBar: some View {
        let isOn = fingers.hasThumb
        // Spans the first two columns — the side a thumb actually opposes from.
        let thumbLength = hitWidth * 2 + 10
        // The THICKEST digit, so thicker than a finger — as in `IslandHand`, where a
        // slim tab beside four fat bars read as a mistake.
        let thumbThickness = width * 1.05

        return Button {
            toggle(.thumb)
        } label: {
            ZStack(alignment: .leading) {
                Color.clear
                // A `Capsule`: its half-height is the radius, whatever the length.
                Capsule()
                    .fill(isOn ? Accent.graphite : Color.clear)
                    .overlay {
                        if !isOn {
                            Capsule().strokeBorder(Ink.tertiary.opacity(0.45), lineWidth: 1)
                        }
                    }
                    .frame(width: thumbLength, height: thumbThickness)
            }
            // The full pip-row width, 44pt tall: the drawn bar alone is too small a target.
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
