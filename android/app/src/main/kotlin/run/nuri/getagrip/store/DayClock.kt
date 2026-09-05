// SPDX-License-Identifier: MPL-2.0
// Original contributions Copyright 2026 Nuri Bruner.

package run.nuri.getagrip.store

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.ProvidableCompositionLocal
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.core.content.ContextCompat
import run.nuri.getagrip.engine.DayStamp

/// The app's single source of "today".
///
/// Reading `DayStamp.today()` straight from a composable only recomposes when Compose
/// happens to invalidate that composable — so an app left open across local midnight
/// keeps rendering yesterday's day: the ritual screen still says "1 of 2 today" for a
/// session trained before midnight, and the streak strip still fills yesterday's cell.
/// The day therefore gets observable state of its own, and every surface recomputes
/// together.
///
/// TRANSLATION NOTE (Sources/Store/DayClock.swift): UIKit posts ONE notification
/// (`significantTimeChange`) at local midnight, for a manual clock change and for a
/// time-zone crossing. Android splits that into three broadcasts —
/// `ACTION_DATE_CHANGED` (local midnight), `ACTION_TIME_CHANGED` (the clock was set) and
/// `ACTION_TIMEZONE_CHANGED` — so `DayClockReceiver` listens for all three and they land
/// in the same `refresh()`. They are all protected, implicitly-broadcast system actions
/// and must be registered at RUNTIME rather than in the manifest.
@Stable
class DayClock(today: DayStamp = DayStamp.today()) {

    /// The `today` parameter is a test seam. Crossing midnight is the single most
    /// expensive behaviour in the app to verify by waiting for it.
    var today: DayStamp by mutableStateOf(today)
        private set

    /// Also called on resume: a device that was asleep across midnight may not deliver
    /// the broadcast until the app is in the foreground again.
    fun refresh() {
        val now = DayStamp.today()
        if (now != today) today = now
    }

    /// Tests only in practice — nothing in the app calls it. Kept out of `refresh`'s path
    /// deliberately, so no shipping code can pin the day to a value the system clock
    /// disagrees with.
    fun advance(to: DayStamp) {
        if (to != today) today = to
    }
}

/// Registered by the Application for the life of the process.
///
/// **It refreshes the clock and THEN asks the store to recompute — never the other way
/// round, and never the store on its own.** The store REACTS to the clock; pushing the
/// clock from inside the store re-pins "today" to the system date on every call, which
/// silently defeats `DayClock.advance(to:)` — the seam that makes crossing midnight
/// testable at all. This is the same order `DoigtApp` uses on `scenePhase == .active`.
class DayClockReceiver(
    private val clock: DayClock,
    private val onDayMayHaveChanged: () -> Unit,
) : BroadcastReceiver() {

    override fun onReceive(context: Context?, intent: Intent?) {
        clock.refresh()
        onDayMayHaveChanged()
    }

    /// `ContextCompat`, not `Context.registerReceiver(_:_:Int)` directly: the flags
    /// overload has existed since API 26 but `RECEIVER_NOT_EXPORTED` only means what it
    /// says from API 33, and minSdk here is 31. Not exported is right regardless — these
    /// are protected system broadcasts and nothing else may fire them.
    fun register(context: Context) {
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_DATE_CHANGED)
            addAction(Intent.ACTION_TIME_CHANGED)
            addAction(Intent.ACTION_TIMEZONE_CHANGED)
        }
        ContextCompat.registerReceiver(
            context.applicationContext, this, filter, ContextCompat.RECEIVER_NOT_EXPORTED,
        )
    }
}

/// See `LocalDeviceStore` for why this is `staticCompositionLocalOf`.
val LocalDayClock: ProvidableCompositionLocal<DayClock> = staticCompositionLocalOf {
    error("LocalDayClock was read outside a CompositionLocalProvider")
}
