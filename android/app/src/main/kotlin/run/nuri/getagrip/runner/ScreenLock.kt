// SPDX-License-Identifier: MPL-2.0
// Original contributions Copyright 2026 Nuri Bruner.

package run.nuri.getagrip.runner

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.platform.LocalView

/// Keeps the screen awake for as long as anything still needs it.
///
/// TRANSLATION NOTE (Sources/Runner/IdleTimerLock.swift): iOS's `isIdleTimerDisabled` is
/// GLOBAL, so a boolean set on entry and cleared on exit breaks with more than one exit
/// path (a session has four, plus whatever sits on top). Left stuck on, the whole phone
/// stops sleeping with nothing on screen to say why.
///
/// `View.keepScreenOn` has the same shape and the same fix: count holders, clear at zero.
/// The count clamps at zero: a negative count would make the NEXT balanced pair leave the
/// flag set.
///
/// **This is the shared one.** `GaugeScreen` has an identical private copy of this and
/// `KeepScreenOn` (from before the runner) that should be deleted in favour of these next
/// time it is touched — two counters for one global flag is the bug the counter prevents.
object ScreenWakeLock {
    private var holders = 0

    /// Exposed like iOS's `depth`: the reference count IS the point, so it must be
    /// assertable.
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
