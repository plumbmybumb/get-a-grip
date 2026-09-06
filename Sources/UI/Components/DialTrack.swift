// SPDX-License-Identifier: MPL-2.0
// Original contributions Copyright 2026 Nuri Bruner.

import SwiftUI

/// A number chosen from a LADDER, not from a continuum.
///
/// The control this replaces was a `Slider` with four preset chips underneath, and it was
/// the wrong control twice over. Nielsen Norman is unusually blunt about the first: use a
/// slider only when the precise value will not matter and only the approximate range will.
/// Every number in a routine is exact — a 3 s hold, 4 pulls, a 5 s rest — so a continuum
/// was never what any of them wanted, which is why each row needed chips bolted underneath
/// to reach the values people actually use.
///
/// The second is arithmetic. A hold slider spanning 3…45 in steps of five can land on 3, 8,
/// 13, 18 — so 5, 7, 10 and 12 were unreachable by dragging and existed only as chips, and
/// a ladder of four chips can hold four numbers. The C4 protocol wants a 3 s hold, a 5 s
/// rest and 4 pulls; not one of those was a chip, so every one of them fell through to a
/// drag against a step it did not divide.
///
/// **The dial's positions are the ladder's INDICES, not the value's magnitude.** Detents
/// are evenly spaced whatever the numbers are, so 3 and 30 are equally easy to hit, and the
/// scale underneath states every value the control can produce. Tap-to-type stays on the
/// row above and is not optional — Zwift's workout editor is drag-only and its own power
/// users hand-edit exported XML to get values the graphical editor cannot express.
struct DialTrack: View {
    @Binding var value: Double
    /// Ascending, and FIXED — the same stops whatever `value` currently is.
    ///
    /// The caller used to splice the current value in so a typed number "kept its own
    /// detent". Because positions come from the INDEX, that re-spaced every other stop the
    /// moment you typed, and re-spaced them again on the first drag. The scale is now
    /// immovable and an off-ladder value gets `offLadderMark` instead.
    var values: [Double]
    var format: (Double) -> String
    var spokenUnit: String
    /// An unanswered scale is a real state, not a value below the ladder. The caller
    /// still supplies a binding for the drag to write into, but this flag keeps that
    /// temporary value from drawing a misleading detent or off-ladder mark.
    var isUnset: Bool = false

    enum RenderingMark: Equatable {
        case detent(Int)
        case offLadder
    }

    /// Fires the per-detent click. A tick has to name its cause, so it is bumped by a
    /// LANDING, never by the value — a typed number is not a detent.
    @State private var landings = 0

    /// What the ticks DRAW into.
    private static let trackHeight: CGFloat = 30
    /// What the finger gets. The drawing is 30 pt and that is right — a taller tick reads
    /// as a fence — but the live strip must clear the 44 pt floor, exactly as
    /// `BandTrimmer` does ("≥44 pt of live edge each side"). Hit area LARGER than the
    /// drawing is the safe direction; the reverse is the "four points is exactly the kind
    /// of miss that reads as the tap didn't register" bug this codebase has already paid
    /// for once, in `ValueRow`'s preset row.
    private static let hitHeight: CGFloat = 44

    var body: some View {
        VStack(alignment: .leading, spacing: 5) {
            GeometryReader { geo in
                let width = geo.size.width
                ZStack(alignment: .leading) {
                    Capsule()
                        .fill(Ink.tertiary.opacity(0.16))
                        .frame(height: 2)
                        .frame(maxHeight: .infinity, alignment: .center)

                    ForEach(values.indices, id: \.self) { index in
                        detent(at: index, width: width)
                    }
                    if renderingMark == .offLadder, let x = offLadderX(in: width) {
                        offLadderMark(at: x)
                    }
                }
                .frame(height: Self.hitHeight)
                // The whole strip is live, not just the ticks: a 2 pt bar is not a target,
                // and the gap between two of them is the most natural place to aim.
                .contentShape(.rect)
                .onTapGesture { location in land(at: location.x, width: width) }
                // Absolute tracking, not translation: on a ladder this short the value
                // belongs under the finger, and there is no offset worth preserving.
                .gesture(HorizontalPan(
                    began: { x in land(at: x, width: width) },
                    changed: { x, _ in land(at: x, width: width) },
                    ended: {}))
            }
            .frame(height: Self.hitHeight)

            scale
        }
        .sensoryFeedback(.selection, trigger: landings)
        // ONE adjustable element rather than a mute picture, stepping the same detents the
        // drag lands on.
        .accessibilityElement(children: .ignore)
        .accessibilityValue(isUnset ? String(localized: "Not set") : String(localized: "\(format(value)) \(spokenUnit)"))
        .accessibilityAdjustableAction { direction in
            guard let next = Self.adjustedIndex(value: value, values: values, isUnset: isUnset,
                                                increasing: direction == .increment) else { return }
            value = values[next]
            landings += 1
        }
    }

    private func detent(at index: Int, width: CGFloat) -> some View {
        // Only when the value is EXACTLY here. Off the ladder, no detent is "current" and
        // `offLadderMark` carries the reading instead — a highlighted 20 while the face
        // says 22 is the control disagreeing with the number above it.
        let isCurrent = renderingMark == .detent(index)
        return Capsule()
            .fill(isCurrent ? Accent.graphite : Ink.tertiary.opacity(0.5))
            .frame(width: isCurrent ? 4 : 2,
                   height: isCurrent ? Self.trackHeight : 11)
            .position(x: x(of: index, in: width), y: Self.hitHeight / 2)
    }

    /// A typed value that is not on the ladder, drawn WHERE IT ACTUALLY FALLS — between
    /// its two neighbouring stops, proportionally. Hollow rather than solid so it reads as
    /// "here, but not a stop": the next drag will land on a detent, and the mark should
    /// say so before it happens rather than after.
    private func offLadderMark(at x: CGFloat) -> some View {
        Capsule()
            .strokeBorder(Accent.graphite, lineWidth: 1.5)
            .frame(width: 6, height: Self.trackHeight)
            .position(x: x, y: Self.hitHeight / 2)
    }

    /// The ladder, stated. It is what makes the control quotable — you can read every
    /// value it will produce without touching it, which a slider never allowed.
    private var scale: some View {
        HStack(spacing: 0) {
            ForEach(values.indices, id: \.self) { index in
                let isCurrent = renderingMark == .detent(index)
                Text(format(values[index]))
                    .font(.system(.caption2, weight: isCurrent ? .semibold : .regular))
                    .monospacedDigit()
                    .foregroundStyle(isCurrent ? Ink.primary : Ink.tertiary)
                    .lineLimit(1)
                    .minimumScaleFactor(0.7)
                    .frame(maxWidth: .infinity)
            }
        }
        .accessibilityHidden(true)
    }

    // MARK: - Geometry

    /// The detent the value sits exactly on, or nil when it falls between two.
    private var exactIndex: Int? {
        values.firstIndex { abs($0 - value) < 0.001 }
    }

    /// **The single decision about what this dial draws** — the detent highlight, the
    /// scale's bold label and the off-ladder mark all read it, so a test on this
    /// property is a test of the real rendering path rather than a parallel copy of the
    /// rule that could pass while the drawing regressed.
    ///
    /// The two marks are mutually exclusive, and both disappear while the answer is
    /// unset.
    var renderingMark: RenderingMark? {
        guard !isUnset else { return nil }
        if let exactIndex { return .detent(exactIndex) }
        return values.count > 1 ? .offLadder : nil
    }

    /// A typed value between detents steps to its immediate neighbour in the requested
    /// direction. Rounding first skipped a stop (23 mm → 30 mm instead of 25 mm).
    static func adjustedIndex(value: Double, values: [Double], isUnset: Bool,
                              increasing: Bool) -> Int? {
        guard !values.isEmpty else { return nil }
        if isUnset {
            return increasing ? values.startIndex : values.index(before: values.endIndex)
        }
        return increasing ? values.firstIndex(where: { $0 > value })
                          : values.lastIndex(where: { $0 < value })
    }

    /// Where an off-ladder value falls, interpolated between its neighbours so the mark
    /// lands in the gap it belongs to. nil when the value IS a detent, or sits outside the
    /// ladder entirely — past either end there is no gap to interpolate into, so the mark
    /// parks on the end stop rather than floating off the track.
    ///
    /// Pure GEOMETRY: whether the mark is drawn at all is `renderingMark`'s call, and
    /// the caller has already made it.
    private func offLadderX(in width: CGFloat) -> CGFloat? {
        guard width > 0 else { return nil }
        guard let upper = values.firstIndex(where: { $0 > value }) else {
            return x(of: values.count - 1, in: width)
        }
        guard upper > 0 else { return x(of: 0, in: width) }
        let lo = values[upper - 1], hi = values[upper]
        let t = (value - lo) / (hi - lo)
        return x(of: upper - 1, in: width) + t * slotWidth(width)
    }

    private func slotWidth(_ width: CGFloat) -> CGFloat {
        values.isEmpty ? width : width / CGFloat(values.count)
    }

    private func x(of index: Int, in width: CGFloat) -> CGFloat {
        (CGFloat(index) + 0.5) * slotWidth(width)
    }

    private func land(at x: CGFloat, width: CGFloat) {
        guard width > 0, !values.isEmpty else { return }
        let slot = Int(floor(x / slotWidth(width)))
        let index = min(values.count - 1, max(0, slot))
        guard isUnset || values[index] != value else { return }
        value = values[index]
        landings += 1
    }
}

#Preview {
    @Previewable @State var hold: Double = 10
    VStack(alignment: .leading, spacing: 30) {
        DialTrack(value: $hold, values: [3, 5, 7, 10, 12, 15, 20, 30],
                  format: { $0.formatted(.number.precision(.fractionLength(0))) },
                  spokenUnit: "seconds")
    }
    .padding(24)
    .background { AppBackground() }
}
