// SPDX-License-Identifier: MPL-2.0
// Original contributions Copyright 2026 Nuri Bruner.

import UIKit

/// Keeps the screen awake for as long as anything still needs it.
///
/// `UIApplication.isIdleTimerDisabled` is GLOBAL, so a bare boolean set true on entry
/// and false on exit is wrong the moment there is more than one exit path — and a
/// session has four (back, tab switch, backgrounding, completion) plus the sheet that
/// can sit on top of it. Whoever runs last wins: leave it stuck `true` and the user's
/// whole phone stops sleeping, forever, with nothing on screen to explain why.
///
/// So: count the holders, and clear the flag only when the count is back to zero.
/// The count clamps at zero because the failure mode of an unbalanced release is
/// worse than the bug it hides — a negative count would make the NEXT balanced pair
/// leave the flag set.
@MainActor
enum IdleTimerLock {

    private static var holders = 0

    static func acquire() {
        holders += 1
        UIApplication.shared.isIdleTimerDisabled = true
    }

    static func release() {
        #if DEBUG
        // Not a crash in release: a stuck-awake phone is bad, but a crash on the exit
        // path of a workout is worse. In DEBUG it should be loud — an unbalanced pair
        // is a real bug that only shows up hours later as battery drain.
        assert(holders > 0, "IdleTimerLock.release() without a matching acquire()")
        #endif
        holders = max(0, holders - 1)
        if holders == 0 { UIApplication.shared.isIdleTimerDisabled = false }
    }

    #if DEBUG
    /// Test seam — the reference count is the whole point of this type, so it has to
    /// be assertable from outside it.
    static var depth: Int { holders }
    #endif
}
