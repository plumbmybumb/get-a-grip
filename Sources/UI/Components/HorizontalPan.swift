// SPDX-License-Identifier: MPL-2.0
// Original contributions Copyright 2026 Nuri Bruner.

import SwiftUI
import UIKit

/// A pan that REFUSES TO BEGIN unless the first movement is more horizontal than
/// vertical — the only way a full-width drag strip can live inside a vertical
/// `ScrollView` without eating page scrolls.
///
/// SwiftUI's `DragGesture` cannot express this. Its `minimumDistance` is a DISTANCE, not a
/// direction, so any threshold still claims vertical moves and the page goes dead under
/// the strip. Measured 2026-08-10: `highPriorityGesture(DragGesture(minimumDistance: 10))`
/// did not restore scrolling, because a 10 pt vertical drag still crosses 10 pt.
///
/// With the axis check in `gestureRecognizerShouldBegin`, a vertical touch belongs to the
/// ScrollView from its very first point, and a horizontal one adjusts the control without
/// the scroll stealing it mid-drag — the same civility a system `Slider` shows.
///
/// Extracted from `BandTrimmer` 2026-08-11 when `DialTrack` needed the identical rule.
/// **This is also the reason the set editor is a vertical list and not a horizontal
/// pager**: the gate works because the two gestures are on DIFFERENT AXES. Inside a
/// horizontal pager both want left-to-right, and no rule can tell them apart.
struct HorizontalPan: UIGestureRecognizerRepresentable {
    /// The touch-DOWN x, in the view's own space — recovered by subtracting the
    /// translation already accumulated when `.began` fires.
    var began: (CGFloat) -> Void
    /// `(current x, translation x)`. A trimmer wants the translation so a grabbed band
    /// keeps its offset; a dial wants the absolute position so the value lands under the
    /// finger. Both are cheap to hand over, and neither can be derived from the other
    /// without the caller storing state it should not have to.
    var changed: (CGFloat, CGFloat) -> Void
    var ended: () -> Void

    func makeUIGestureRecognizer(context: Context) -> UIPanGestureRecognizer {
        let pan = UIPanGestureRecognizer()
        pan.maximumNumberOfTouches = 1
        pan.delegate = context.coordinator
        return pan
    }

    func handleUIGestureRecognizerAction(_ recognizer: UIPanGestureRecognizer,
                                         context: Context) {
        guard let view = recognizer.view else { return }
        let location = recognizer.location(in: view)
        let translation = recognizer.translation(in: view)
        switch recognizer.state {
        case .began:
            began(location.x - translation.x)
            changed(location.x, translation.x)
        case .changed:
            changed(location.x, translation.x)
        case .ended, .cancelled, .failed:
            ended()
        default:
            break
        }
    }

    func makeCoordinator(converter: CoordinateSpaceConverter) -> Coordinator {
        Coordinator()
    }

    final class Coordinator: NSObject, UIGestureRecognizerDelegate {
        func gestureRecognizerShouldBegin(_ gesture: UIGestureRecognizer) -> Bool {
            guard let pan = gesture as? UIPanGestureRecognizer,
                  let view = pan.view else { return false }
            let velocity = pan.velocity(in: view)
            return abs(velocity.x) > abs(velocity.y)
        }
    }
}
