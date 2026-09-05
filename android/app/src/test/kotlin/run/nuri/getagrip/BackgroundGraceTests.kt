// SPDX-License-Identifier: MPL-2.0
// Original contributions Copyright 2026 Nuri Bruner.

package run.nuri.getagrip

import run.nuri.getagrip.ble.ProgressorConnectionState
import run.nuri.getagrip.ble.StreamStartCause
import run.nuri.getagrip.engine.GaugeKind
import run.nuri.getagrip.store.BackgroundGraceAction
import run.nuri.getagrip.store.BackgroundGracePolicy
import run.nuri.getagrip.store.DeviceStore
import run.nuri.getagrip.store.DiagnosticBreadcrumb
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/// What leaving the foreground does to the gauge's link.
///
/// The rule is a pure function so it can be asserted whole, without a radio, a coroutine or
/// a lifecycle owner — the same split the runner's own policies got in `RunnerPolicies.kt`.
/// The store-level tests that drive `beginBackgroundGrace` itself live in
/// `BLELifecycleTests` and `MultiGaugeStoreTests`, beside the rest of the link lifecycle.
class BackgroundGraceTests {

    /// **A gauge that cannot stream in the background gets no grace at all**, from either
    /// entry state. `isBusy` is in the rule because by the time anything looks at a
    /// broadcast scale's link the scan has usually already re-armed itself.
    @Test
    fun noGraceForABroadcastGauge() {
        for (busy in listOf(false, true)) {
            assertEquals(
                BackgroundGraceAction.disconnectNow,
                BackgroundGracePolicy.onLeavingForeground(
                    isConnected = !busy,
                    isBusy = busy,
                    isStreaming = false,
                    sustainsBackgroundStreaming = false,
                ),
                "a scan is the most power-hungry BLE mode there is, and the OS silences it anyway",
            )
        }
    }

    /// **A session streaming suppresses the window entirely.** What keeps such a session
    /// alive is `SessionForegroundService`; scheduling a disconnect under a running stream
    /// would be a race against the workout.
    @Test
    fun aStreamingSessionSuppressesTheGrace() {
        assertEquals(
            BackgroundGraceAction.none,
            BackgroundGracePolicy.onLeavingForeground(
                isConnected = true,
                isBusy = false,
                isStreaming = true,
                sustainsBackgroundStreaming = true,
            ),
        )
    }

    /// The case the window exists for: a gauge left connected by a screen somebody walked
    /// away from.
    @Test
    fun anIdleConnectedGaugeGetsTheWindow() {
        assertEquals(
            BackgroundGraceAction.scheduleDisconnect,
            BackgroundGracePolicy.onLeavingForeground(
                isConnected = true,
                isBusy = false,
                isStreaming = false,
                sustainsBackgroundStreaming = true,
            ),
        )
    }

    /// Nothing connected is nothing to schedule. A `disconnectNow` here would be a
    /// breadcrumb about a link that never existed.
    @Test
    fun nothingConnectedSchedulesNothing() {
        assertEquals(
            BackgroundGraceAction.none,
            BackgroundGracePolicy.onLeavingForeground(
                isConnected = false,
                isBusy = false,
                isStreaming = false,
                sustainsBackgroundStreaming = true,
            ),
        )
    }

    /// 45 seconds, the same window iOS opens: long enough that an app switch is free, short
    /// enough that a phone in a bag does not leave the gauge awake for the ten minutes it
    /// takes to self-sleep after a disconnect. **Pinned, because it was tuned against a
    /// reported bug** — firing at once could not tell a two-second "hey Siri" from a phone
    /// put down, and charged both a 5–6 s reconnect.
    @Test
    fun theWindowIsFortyFiveSeconds() {
        assertEquals(45L, BackgroundGracePolicy.graceSeconds)
    }

    /// The store's realisation, end to end: opened on the way out, called off on the way in,
    /// and the link untouched throughout.
    @Test
    fun theStoreOpensAndCancelsTheWindow() {
        val client = RecordingProgressorClient()
        val device = DeviceStore(client = client, scope = inertScope(), clock = FakeClock())
        client.setState(ProgressorConnectionState.Connected)

        device.beginBackgroundGrace()
        device.cancelBackgroundGrace()

        assertTrue(device.state.isConnected)
        assertTrue(
            device.diagnosticEntries.any {
                it.event == DiagnosticBreadcrumb.BackgroundDisconnectScheduled
            },
        )
        assertTrue(
            device.diagnosticEntries.any {
                it.event == DiagnosticBreadcrumb.BackgroundDisconnectCancelled
            },
        )
    }

    /// A streaming session leaves the ring clean: no schedule, no cancellation, no link
    /// change. "Signal became stale" must never be explained by a grace that was never armed.
    @Test
    fun aStreamingSessionLeavesTheRingClean() {
        val client = RecordingProgressorClient()
        val device = DeviceStore(client = client, scope = inertScope(), clock = FakeClock())
        client.setState(ProgressorConnectionState.Connected)
        device.startStreaming(StreamStartCause.initial)

        device.beginBackgroundGrace()

        assertTrue(device.state.isConnected)
        assertFalse(
            device.diagnosticEntries.any {
                it.event == DiagnosticBreadcrumb.BackgroundDisconnectScheduled
            },
        )
    }

    /// The broadcast scan stands itself back up on the way in — without it every app switch
    /// costs a manual Connect tap, which is the same reconnect churn the window exists to
    /// avoid, solved the opposite way round because the radio cost inverts.
    @Test
    fun theBroadcastScanResumesOnForeground() {
        val client = RecordingProgressorClient(GaugeKind.whc06)
        val device = DeviceStore(client = client, scope = inertScope(), clock = FakeClock())
        client.setState(ProgressorConnectionState.Connected)

        device.beginBackgroundGrace()
        assertFalse(device.state.isConnected)

        device.cancelBackgroundGrace()
        assertTrue(device.state.isConnected, "the scan must stand back up without a tap")
    }
}
