// SPDX-License-Identifier: MPL-2.0
// Original contributions Copyright 2026 Nuri Bruner.

import SwiftUI

/// A clock numeral that ROLLS without a blur.
///
/// `.contentTransition(.numericText())` blurs every frame of the roll on the CPU; two
/// numerals rolling each second measured 14 points of a core (27 % of the app's time in
/// `vSepConvolve…`, 2026-09-19). Clocks still roll, the cheap way: keyed by value, the
/// outgoing numeral slides and fades a quarter-height in the counting direction as the
/// next arrives — GPU offset and opacity, no blur. `rolls == false` (Low Power Mode,
/// Reduce Motion) skips the keying, costing what a plain `Text` does.
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
