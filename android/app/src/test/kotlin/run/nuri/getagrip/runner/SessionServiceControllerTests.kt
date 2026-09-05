// SPDX-License-Identifier: MPL-2.0
// Original contributions Copyright 2026 Nuri Bruner.

package run.nuri.getagrip.runner

import run.nuri.getagrip.FakeClock
import run.nuri.getagrip.RecordingProgressorClient
import run.nuri.getagrip.ble.ProgressorConnectionState
import run.nuri.getagrip.engine.GaugeKind
import run.nuri.getagrip.engine.GripSpec
import run.nuri.getagrip.engine.SessionPlan
import run.nuri.getagrip.engine.SetPlan
import run.nuri.getagrip.inertScope
import run.nuri.getagrip.store.DeviceStore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/// **When a session asks the OS to keep it alive, and when it must not.**
///
/// There is no iOS twin: `bluetooth-central` is a capability declared in a plist, so on iOS
/// there is nothing to start or stop and nothing to get wrong. Android's counterpart is a
/// `connectedDevice` foreground service with a visible ongoing notification, which is
/// exactly the kind of thing that must not appear for a session that cannot use it — and
/// must not be left running after one ends.
///
/// The seam is why these run at all: `SessionServiceController` keeps the decision in
/// `RunnerSession` and the `Service` out of the JVM.
class SessionServiceControllerTests {

    private class Recorder : SessionServiceController {
        var begins = 0
        var ends = 0
        override fun begin() { begins += 1 }
        override fun end() { ends += 1 }
    }

    private val plan = SessionPlan(
        name = "Daily",
        sets = listOf(SetPlan(grip = GripSpec(), repsPerSide = 2)),
        holdSeconds = 3,
        restSeconds = 5,
        leadInSeconds = 3,
    )

    private fun session(
        client: RecordingProgressorClient,
        service: SessionServiceController,
        timerOnly: Boolean = false,
        isMock: Boolean = false,
    ): Pair<RunnerSession, DeviceStore> {
        val device = DeviceStore(client = client, scope = inertScope(), clock = FakeClock(), isMock = isMock)
        val session = RunnerSession(
            plan = plan,
            routineName = "Daily",
            device = device,
            timerOnly = timerOnly,
            scope = inertScope(),
            clock = FakeClock(),
            service = service,
        )
        return session to device
    }

    /// The ordinary case: a measured session on a connected Progressor keeps the process
    /// alive, because without the service the app is frozen the moment the screen locks and
    /// the rep silently stalls.
    @Test
    fun aConnectedMeasuredSessionStartsTheService() {
        val client = RecordingProgressorClient()
        val service = Recorder()
        val (session, _) = session(client, service)
        client.setState(ProgressorConnectionState.Connected)

        session.begin()

        assertEquals(1, service.begins)
        session.end()
        assertEquals(1, service.ends, "the service's life is the session's life")
    }

    /// **A gauge-free session has nothing to keep alive.** No samples are coming, so
    /// `RunnerLifecycle` pauses it outright on the way out — and a foreground service with a
    /// permanent notification for a stopwatch would be the app holding the radio open for
    /// nothing.
    @Test
    fun aTimerOnlySessionStartsNoService() {
        val client = RecordingProgressorClient()
        val service = Recorder()
        val (session, _) = session(client, service, timerOnly = true)
        client.setState(ProgressorConnectionState.Connected)

        session.begin()

        assertEquals(0, service.begins)
    }

    @Test
    fun demoSessionNeverRequestsABluetoothServiceEvenAfterReconnect() {
        val client = RecordingProgressorClient()
        val service = Recorder()
        val (session, device) = session(client, service, isMock = true)
        client.setState(ProgressorConnectionState.Connected)
        assertFalse(device.gaugeCapabilities.sustainsBackgroundStreaming)
        assertTrue(run.nuri.getagrip.engine.BackgroundPausePolicy.pausesOnLeavingForeground(
            isBackground = true, isConnected = true,
            sustainsBackgroundStreaming = device.gaugeCapabilities.sustainsBackgroundStreaming,
        ))
        session.begin()
        session.connectionChanged(false)
        session.connectionChanged(true)
        session.end()
        assertEquals(0, service.begins)
        assertEquals(0, service.ends)
    }

    /// A broadcast scale cannot stream backgrounded whatever we do — the OS silences the
    /// scan — so the capability, not the kind, is what decides.
    @Test
    fun aBroadcastGaugeStartsNoService() {
        val client = RecordingProgressorClient(GaugeKind.whc06)
        val service = Recorder()
        val (session, device) = session(client, service)
        client.setState(ProgressorConnectionState.Connected)
        assertFalse(device.gaugeCapabilities.sustainsBackgroundStreaming)

        session.begin()

        assertEquals(0, service.begins)
    }

    /// **Start on Today is deliberately enabled while the gauge is still asleep**, so the
    /// session's real first phase is connect-and-tare and there is often no link to keep
    /// alive at `begin()`. The service has to catch up when one arrives.
    @Test
    fun theServiceStartsWhenTheLinkArrivesLater() {
        val client = RecordingProgressorClient()
        val service = Recorder()
        val (session, _) = session(client, service)

        session.begin()
        // `begin()` asks the store to connect, and this fake answers synchronously — so the
        // state is already Connected here and only the session has not been told.
        assertTrue(client.state.isConnected)

        session.connectionChanged(true)

        assertEquals(1, service.begins)
    }

    /// **Started once, never twice.** `begin()` and every later `connectionChanged` route
    /// through the same guard, so a link that drops and returns mid-session cannot stack a
    /// second service.
    @Test
    fun aReconnectDoesNotStartASecondService() {
        val client = RecordingProgressorClient()
        val service = Recorder()
        val (session, _) = session(client, service)
        client.setState(ProgressorConnectionState.Connected)

        session.begin()
        session.connectionChanged(false)
        session.connectionChanged(true)

        assertEquals(1, service.begins)
    }
}
