// SPDX-License-Identifier: MPL-2.0
// Original contributions Copyright 2026 Nuri Bruner.

import SwiftUI

/// A range set by DRAGGING A BAND — the clip-trimmer gesture: grab the middle to slide,
/// an end to stretch. Replaced two steppers that made "80 to 90" a dozen taps (Nuri,
/// 2026-08-10); now it is one drag with a detent click at every step.
///
/// Values snap DURING the drag, never on release: what settles is what you get, and each
/// snap clicks. The numbers ride on the band — quotable, since the steps are the 5 % /
/// 0.5 kg resolution targets round to anyway.
struct BandTrimmer: View {
    @Binding var lo: Double
    @Binding var hi: Double
    /// The full scale the track represents.
    var scale: ClosedRange<Double>
    /// Snap resolution — 0.05 for percent, 0.5 for kilograms.
    var step: Double
    /// Renders a value for the band label and the scale's end captions.
    var format: (Double) -> String
    /// Spoken unit for the two adjustable accessibility elements.
    var spokenUnit: String

    /// Which part of the band the drag owns — decided ONCE at first touch, so a finger
    /// drifting across an edge cannot switch jobs.
    private enum Grab { case lower, upper, whole }
    @State private var grab: Grab?
    @State private var startLo: Double = 0
    @State private var startHi: Double = 0
    /// Trigger for the per-detent click.
    @State private var snapTick = 0

    /// ≥44 pt of live edge each side; the visible handle bars are just the affordance.
    private static let edgeGrabWidth: CGFloat = 44
    private static let trackHeight: CGFloat = 44

    /// Whether the band can carry its own numbers. Fraction-of-scale, not pixels, so the
    /// answer is stable across widths without a geometry read.
    private var bandIsNarrow: Bool {
        let span = scale.upperBound - scale.lowerBound
        guard span > 0 else { return true }
        return (hi - lo) / span < 0.28
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 6) {
            // RESERVED line for the numbers when the band is too narrow: a label
            // floated above the track collided with whatever sat above. Always present
            // so the layout never jumps mid-drag.
            Text(bandIsNarrow ? "\(format(lo))–\(format(hi))" : " ")
                .font(.system(.caption, weight: .semibold))
                .monospacedDigit()
                .foregroundStyle(Ink.primary)
                .frame(maxWidth: .infinity, alignment: .trailing)
                .accessibilityHidden(true)
            GeometryReader { geo in
                let width = geo.size.width
                let loX = x(of: lo, in: width)
                let hiX = x(of: hi, in: width)
                ZStack(alignment: .leading) {
                    ZStack(alignment: .leading) {
                        // The full scale, quiet.
                        Capsule()
                            .fill(Ink.tertiary.opacity(0.16))
                            .frame(height: 10)
                            .frame(maxHeight: .infinity, alignment: .center)

                        // Graphite, not bleu: an instruction being authored, not a measurement.
                        RoundedRectangle(cornerRadius: 10, style: .continuous)
                            .fill(Accent.graphite.opacity(0.85))
                            .frame(width: max(hiX - loX, 24), height: Self.trackHeight - 12)
                            .overlay {
                                // The grab bars, INSIDE the ends — readable at any band width.
                                HStack {
                                    grabBar
                                    Spacer(minLength: 0)
                                    grabBar
                                }
                                .padding(.horizontal, 5)
                            }
                            .overlay {
                                // Rides the band while it fits; the reserved line above
                                // carries it once the band is too narrow.
                                if !bandIsNarrow {
                                    Text("\(format(lo))–\(format(hi))")
                                        .font(.system(.caption, weight: .semibold))
                                        .monospacedDigit()
                                        .foregroundStyle(Color.white)
                                        .lineLimit(1)
                                        .fixedSize()
                                }
                            }
                            .offset(x: loX)
                            .frame(maxHeight: .infinity, alignment: .center)
                    }
                    .contentShape(.rect)
                    // A tap is not a drag, so tap-to-jump snaps the nearer edge there.
                    .onTapGesture { location in
                        jumpNearestEdge(to: location.x, width: width)
                    }
                    // A UIKit recognizer, not a DragGesture — see `HorizontalPan`. TRANSLATION,
                    // not absolute position: a band grabbed by its middle keeps its grab offset
                    // instead of jumping its centre under the finger.
                    .gesture(HorizontalPan(
                        began: { startX in
                            grab = grabTarget(at: startX, loX: loX0(width), hiX: hiX0(width))
                            startLo = lo
                            startHi = hi
                        },
                        changed: { _, translation in
                            drag(by: translation, width: width)
                        },
                        ended: { grab = nil }))
                    // Hidden as a whole: VoiceOver drives the two handle proxies below, so the
                    // drag surface is not also an unlabelled stop.
                    .accessibilityHidden(true)

                    // TWO INDEPENDENTLY ADJUSTABLE ELEMENTS, one per handle: the control
                    // chooses a RANGE, and one combined element could only slide the band,
                    // never widen or narrow it. Each sits at its handle's x, so focus lands
                    // where a drag would grab, and steps by the drag's own `step`.
                    Color.clear
                        .frame(width: 44, height: Self.trackHeight)
                        .contentShape(.rect)
                        // Accessibility-tree only. Hit-testable, these proxies sat over the two
                        // grab bars as 44 pt touch regions and killed tap-to-jump and handle drags.
                        .allowsHitTesting(false)
                        .position(x: loX, y: Self.trackHeight / 2)
                        .accessibilityElement()
                        .accessibilityLabel(String(localized: "Lower bound"))
                        .accessibilityValue(String(localized: "\(format(lo)) \(spokenUnit)"))
                        .accessibilityAdjustableAction { direction in
                            setLo(lo + (direction == .increment ? step : -step))
                        }
                    Color.clear
                        .frame(width: 44, height: Self.trackHeight)
                        .contentShape(.rect)
                        .allowsHitTesting(false)  // see the lower-bound proxy
                        .position(x: hiX, y: Self.trackHeight / 2)
                        .accessibilityElement()
                        .accessibilityLabel(String(localized: "Upper bound"))
                        .accessibilityValue(String(localized: "\(format(hi)) \(spokenUnit)"))
                        .accessibilityAdjustableAction { direction in
                            setHi(hi + (direction == .increment ? step : -step))
                        }
                }
            }
            .frame(height: Self.trackHeight)

            // The scale's ends, so the band has a ruler to be read against.
            HStack {
                Text(format(scale.lowerBound))
                Spacer(minLength: 8)
                Text(format(scale.upperBound))
            }
            .font(.system(.caption2))
            .monospacedDigit()
            .foregroundStyle(Ink.tertiary)
            .accessibilityHidden(true)
        }
        .sensoryFeedback(.selection, trigger: snapTick)
        // CONTAIN: the two per-handle adjustable elements live in the track above.
        .accessibilityElement(children: .contain)
        // The group needs a NAME, or "Lower bound"/"Upper bound" bound nothing.
        .accessibilityLabel(String(localized: "Target range"))
    }

    private var grabBar: some View {
        Capsule()
            .fill(Color.white.opacity(0.55))
            .frame(width: 3, height: 14)
            .blendMode(.plusLighter)
    }

    // MARK: - Geometry

    private func fraction(of value: Double) -> Double {
        let span = scale.upperBound - scale.lowerBound
        guard span > 0 else { return 0 }
        return ((value - scale.lowerBound) / span).clamped(to: 0...1)
    }

    private func x(of value: Double, in width: CGFloat) -> CGFloat {
        CGFloat(fraction(of: value)) * width
    }

    private func loX0(_ width: CGFloat) -> CGFloat { x(of: startLoValue, in: width) }
    private func hiX0(_ width: CGFloat) -> CGFloat { x(of: startHiValue, in: width) }
    private var startLoValue: Double { grab == nil ? lo : startLo }
    private var startHiValue: Double { grab == nil ? hi : startHi }

    /// Edges win within their grab zone, the body between them; a tap on bare track jumps
    /// THE NEARER EDGE there.
    private func grabTarget(at startX: CGFloat, loX: CGFloat, hiX: CGFloat) -> Grab {
        let nearLower = abs(startX - loX) <= Self.edgeGrabWidth / 2
        let nearUpper = abs(startX - hiX) <= Self.edgeGrabWidth / 2
        switch (nearLower, nearUpper) {
        case (true, true):
            // Overlapping zones on a narrow band: the side of centre decides.
            return startX < (loX + hiX) / 2 ? .lower : .upper
        case (true, false):  return .lower
        case (false, true):  return .upper
        case (false, false): return startX > loX && startX < hiX
            ? .whole
            : (abs(startX - loX) < abs(startX - hiX) ? .lower : .upper)
        }
    }

    private func drag(by translation: CGFloat, width: CGFloat) {
        guard width > 0 else { return }
        let span = scale.upperBound - scale.lowerBound
        let delta = Double(translation / width) * span
        switch grab {
        case .lower:
            setLo(startLo + delta)
        case .upper:
            setHi(startHi + delta)
        case .whole:
            // The band keeps its width against BOTH walls.
            let moved = BandTrimmerMath.translated(lower: startLo, upper: startHi,
                delta: delta, scale: scale, step: step)
            apply(lo: moved.lowerBound, hi: moved.upperBound)
        case nil:
            break
        }
    }

    // Typed bands may be narrower than one drag step, including at either scale edge.
    private func setLo(_ value: Double) {
        apply(lo: snap(value).clamped(to: scale.lowerBound...max(scale.lowerBound, hi - step)), hi: hi)
    }

    private func setHi(_ value: Double) {
        apply(lo: lo, hi: snap(value).clamped(to: min(lo + step, scale.upperBound)...scale.upperBound))
    }

    private func apply(lo newLo: Double, hi newHi: Double) {
        guard newLo != lo || newHi != hi else { return }
        lo = newLo
        hi = newHi
        snapTick += 1
    }

    private func snap(_ value: Double) -> Double {
        (value / step).rounded() * step
    }

    /// A tap moves THE NEARER EDGE to the tapped value. Taps inside the band do nothing;
    /// the band moves by dragging.
    private func jumpNearestEdge(to x: CGFloat, width: CGFloat) {
        guard width > 0 else { return }
        let span = scale.upperBound - scale.lowerBound
        let value = scale.lowerBound + Double(x / width) * span
        guard value < lo || value > hi else { return }
        if abs(value - lo) < abs(value - hi) {
            setLo(value)
        } else {
            setHi(value)
        }
    }
}

private extension Comparable {
    func clamped(to range: ClosedRange<Self>) -> Self {
        min(max(self, range.lowerBound), range.upperBound)
    }
}

#Preview {
    @Previewable @State var lo = 0.80
    @Previewable @State var hi = 0.90
    VStack(spacing: 30) {
        BandTrimmer(lo: $lo, hi: $hi, scale: 0...1, step: 0.05,
                    format: { "\(Int(($0 * 100).rounded())) %" }, spokenUnit: "percent")
    }
    .padding(24)
    .background { AppBackground() }
}


// Incoming bands can come from legacy drafts. Fit an oversized band only on interaction.
enum BandTrimmerMath {
    static func translated(lower: Double, upper: Double, delta: Double,
                           scale: ClosedRange<Double>, step: Double) -> ClosedRange<Double> {
        let width = min(max(upper - lower, 0), scale.upperBound - scale.lowerBound)
        let lastLower = max(scale.lowerBound, scale.upperBound - width)
        let proposed = min(max(lower + delta, scale.lowerBound), lastLower)
        let snapped = (proposed / step).rounded() * step
        let result = min(max(snapped, scale.lowerBound), lastLower)
        return result...(result + width)
    }
}
