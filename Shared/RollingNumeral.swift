// SPDX-License-Identifier: MPL-2.0
// Original contributions Copyright 2026 Nuri Bruner.

import SwiftUI

/// A clock numeral that ROLLS without a blur.
///
/// `.contentTransition(.numericText())` is the obvious way to roll a countdown, and it
/// renders every animated frame of the roll through a CPU Gaussian blur. On the runner
/// that is two numerals rolling once a second for the whole session — the hero seconds
/// and the big rest countdown — and it measured at 14 points of a core (27 % of the
/// app's time in `vSepConvolve…`, Nuri's phone, real session path, 2026-09-19); with the
/// digits snapping instead the blur vanished and the app fell from 51 % to 37 % of a core.
/// The house rule stands — a clock rolls, because a number that moves is counting — so
/// the roll is done the cheap way: the numeral is keyed by its value, the outgoing one
/// slides a quarter of its height in the counting direction while fading out and the
/// incoming one arrives the same way. Offset and opacity are composited on the GPU;
/// nothing is blurred. `rolls == false` (Low Power Mode, Reduce Motion) bypasses the
/// keying entirely, so a snapping readout costs exactly what a plain `Text` does.
struct RollingNumeral<Key: Hashable, Label: View>: View {
    var value: Key
    /// True for a countdown: the digits move downward, as an odometer counting down.
    var countsDown = true
    var rolls = true
    /// How far the numerals travel, in points — about a quarter of the type size reads
    /// as a roll rather than a swap.
    var shift: CGFloat
    @ViewBuilder var label: (Key) -> Label

    var body: some View {
        if rolls {
            ZStack {
                label(value)
                    .id(value)
                    .transition(.asymmetric(
                        insertion: .modifier(active: RollShift(dy: countsDown ? -shift : shift, opacity: 0),
                                             identity: RollShift(dy: 0, opacity: 1)),
                        removal: .modifier(active: RollShift(dy: countsDown ? shift : -shift, opacity: 0),
                                           identity: RollShift(dy: 0, opacity: 1))))
            }
            .animation(Motion.live, value: value)
        } else {
            label(value)
        }
    }
}

private struct RollShift: ViewModifier {
    var dy: CGFloat
    var opacity: Double
    func body(content: Content) -> some View {
        content.offset(y: dy).opacity(opacity)
    }
}
