// SPDX-License-Identifier: MPL-2.0
// Original contributions Copyright 2026 Nuri Bruner.

package run.nuri.getagrip.runner

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.platform.LocalView

/// Keeps the screen awake for as long as anything still needs it.
///
/// TRANSLATION NOTE (from Sources/Runner/IdleTimerLock.swift): iOS toggles
/// `UIApplication.isIdleTimerDisabled`, which is GLOBAL — so a bare boolean set true on
/// entry and false on exit is wrong the moment there is more than one exit path, and a
/// session has four (back, tab switch, backgrounding, completion) plus whatever sits on top
/// of it. Whoever runs last wins: leave it stuck on and the user's whole phone stops
/// sleeping, forever, with nothing on screen to explain why.
///
/// `View.keepScreenOn` has exactly the same shape, so it gets exactly the same fix: count
/// the holders, and clear the flag only when the count is back to zero. The count clamps at
/// zero because the failure mode of an unbalanced release is worse than the bug it hides — a
/// negative count would make the NEXT balanced pair leave the flag set.
///
/// **This is the shared one.** `GaugeScreen` carries a private copy of both this object and
/// `KeepScreenOn` (added before the runner existed); they are identical, and that file's
/// pair should be deleted in favour of these the next time it is touched — two counters for
/// one global flag is precisely the bug the counter exists to prevent.
object ScreenWakeLock {
    private var holders = 0

    /// Exposed for the same reason iOS exposes `depth`: the reference count IS the point of
    /// this type, so it has to be assertable from outside it.
    val depth: Int get() = holders

    fun acquire(apply: (Boolean) -> Unit) {
        holders += 1
        if (holders == 1) apply(true)
    }

    fun release(apply: (Boolean) -> Unit) {
        holders = maxOf(0, holders - 1)
        if (holders == 0) apply(false)
    }
}

/// Hold the screen awake while `active`. Every caller pairs its request with a release
/// through `DisposableEffect`, so leaving the screen by ANY route puts the flag back.
@Composable
fun KeepScreenOn(active: Boolean) {
    val view = LocalView.current
    DisposableEffect(active, view) {
        if (!active) return@DisposableEffect onDispose { }
        ScreenWakeLock.acquire { view.keepScreenOn = it }
        onDispose { ScreenWakeLock.release { view.keepScreenOn = it } }
    }
}
