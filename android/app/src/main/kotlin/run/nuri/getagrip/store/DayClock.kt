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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import run.nuri.getagrip.engine.DayStamp
import java.time.Instant

/// The app's single source of "today".
///
/// `DayStamp.today()` read in a composable only updates when Compose happens to recompose
/// it, so an app open across midnight kept showing yesterday ("1 of 2 today", yesterday's
/// strip cell). The day is observable state of its own, and every surface recomputes
/// together.
///
/// TRANSLATION NOTE (Sources/Store/DayClock.swift): UIKit's one `significantTimeChange`
/// becomes three Android broadcasts — `ACTION_DATE_CHANGED`, `ACTION_TIME_CHANGED`,
/// `ACTION_TIMEZONE_CHANGED` — all landing in `refresh()`. They are protected implicit
/// broadcasts, so registered at RUNTIME, not in the manifest.
@Stable
class DayClock(today: DayStamp = DayStamp.today()) {

    /// `today` is a test seam: crossing midnight is the most expensive behaviour to verify
    /// by waiting.
    var today: DayStamp by mutableStateOf(today)
        private set

    /// Also called on resume: a device asleep across midnight may not deliver the broadcast
    /// until foregrounded.
    fun refresh() {
        val now = DayStamp.today()
        if (now != today) today = now
    }

    /// Tests only. Kept off `refresh`'s path so no shipping code can pin the day against
    /// the system clock.
    fun advance(to: DayStamp) {
        if (to != today) today = to
    }

    /// The training day turns at `DayStamp.ROLLOVER_HOUR` (04:00), which no broadcast
    /// marks. So the clock sleeps until the next rollover, refreshes, tells the store, and
    /// repeats for the process's life; a dead process is caught by the resume refresh. iOS:
    /// `DayClock.armRolloverRefresh`.
    fun scheduleRolloverRefresh(scope: CoroutineScope, onDayMayHaveChanged: () -> Unit): Job =
        scope.launch {
            while (isActive) {
                val wait = DayStamp.nextRollover(Instant.now()).toEpochMilli() - System.currentTimeMillis()
                delay(maxOf(1_000L, wait))
                refresh()
                onDayMayHaveChanged()
            }
        }
}

/// Registered by the Application for the life of the process. **Refreshes the clock, THEN
/// asks the store to recompute** — the store only REACTS to the clock (see
/// `TemplateStore.refreshIfDayChanged`). `DoigtApp`'s `scenePhase == .active` order.
class DayClockReceiver(
    private val clock: DayClock,
    private val onDayMayHaveChanged: () -> Unit,
) : BroadcastReceiver() {

    override fun onReceive(context: Context?, intent: Intent?) {
        clock.refresh()
        onDayMayHaveChanged()
    }

    /// `ContextCompat`: `RECEIVER_NOT_EXPORTED` only means what it says from API 33 (minSdk
    /// is 31). Not exported regardless — protected system broadcasts only.
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
