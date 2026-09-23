// SPDX-License-Identifier: MPL-2.0
// Original contributions Copyright 2026 Nuri Bruner.

import SwiftUI
import UIKit

/// A pan that REFUSES TO BEGIN unless the first movement is more horizontal than
/// vertical — the only way a full-width drag strip can live inside a vertical
/// `ScrollView` without eating page scrolls.
///
/// `DragGesture` cannot express this: `minimumDistance` is a DISTANCE, not a direction
/// (measured 2026-08-10: `highPriorityGesture(DragGesture(minimumDistance: 10))` still
/// killed scrolling). With the axis check in `gestureRecognizerShouldBegin`, a vertical
/// touch belongs to the ScrollView from its first point, like a system `Slider`.
///
/// Shared by `BandTrimmer` and `DialTrack`. **Also why the set editor is a vertical list,
/// not a horizontal pager**: the gate works only because the two gestures are on
/// DIFFERENT AXES.
struct HorizontalPan: UIGestureRecognizerRepresentable {
    /// The touch-DOWN x, in the view's own space — recovered by subtracting the
    /// translation already accumulated when `.began` fires.
    var began: (CGFloat) -> Void
    /// `(current x, translation x)`: a trimmer wants the translation so a grabbed band keeps
    /// its offset, a dial the absolute position so the value lands under the finger.
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
