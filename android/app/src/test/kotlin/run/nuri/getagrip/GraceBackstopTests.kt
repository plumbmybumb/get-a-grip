// SPDX-License-Identifier: MPL-2.0
// Original contributions Copyright 2026 Nuri Bruner.

package run.nuri.getagrip

import run.nuri.getagrip.ble.ProgressorConnectionState
import run.nuri.getagrip.ble.StreamStartCause
import run.nuri.getagrip.store.BackgroundGraceBackstop
import run.nuri.getagrip.store.DeviceStore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/// **The background grace has a second clock that a frozen process cannot stop.**
///
/// The 45 s window is a coroutine `delay`, and Android's cached-apps freezer stops a
/// backgrounded app's threads while its GATT link stays up — so the delay never elapsed and
/// the gauge stayed awake. An alarm is armed alongside it; these pin when it is armed, when
/// it is called off, and that its arrival re-checks everything the timer does and more.
class GraceBackstopTests {

    private class RecordingBackstop : BackgroundGraceBackstop {
        var armedFor: Long? = null
        var arms = 0
        var cancels = 0
        override fun arm(afterMillis: Long) { arms += 1; armedFor = afterMillis }
        override fun cancel() { cancels += 1; armedFor = null }
    }

    private fun store(): Triple<RecordingProgressorClient, DeviceStore, RecordingBackstop> {
        val client = RecordingProgressorClient()
        val backstop = RecordingBackstop()
        val device = DeviceStore(client = client, scope = inertScope(), clock = FakeClock(),
            graceBackstop = backstop)
        client.setState(ProgressorConnectionState.Connected)
        return Triple(client, device, backstop)
    }

    @Test
    fun theWindowArmsTheAlarmAndTheForegroundCallsItOff() {
        val (_, device, backstop) = store()
        device.beginBackgroundGrace()
        assertEquals(45_000L, backstop.armedFor, "the same window the timer runs")

        device.cancelBackgroundGrace()
        assertEquals(null, backstop.armedFor)
        assertTrue(device.state.isConnected)
    }

    /// The frozen case: the coroutine never ran, the alarm did. It disconnects.
    @Test
    fun theAlarmDisconnectsAnIdleGaugeTheFrozenTimerNeverReached() {
        val (_, device, _) = store()
        device.beginBackgroundGrace()

        device.backgroundGraceBackstopFired()

        assertFalse(device.state.isConnected)
    }

    /// A late alarm must never drop a link from under a screen in use, nor a stream a
    /// session started inside the window.
    @Test
    fun theAlarmRechecksTheForegroundAndTheStream() {
        val (_, foregrounded, _) = store()
        foregrounded.beginBackgroundGrace()
        foregrounded.cancelBackgroundGrace()
        foregrounded.backgroundGraceBackstopFired()
        assertTrue(foregrounded.state.isConnected, "back in the app: the alarm lost the race")

        val (_, streaming, _) = store()
        streaming.beginBackgroundGrace()
        streaming.startStreaming(StreamStartCause.initial)
        streaming.backgroundGraceBackstopFired()
        assertTrue(streaming.state.isConnected, "a session began inside the window")
    }

    /// Whichever clock fires first ends it: the timer firing calls the alarm off.
    @Test
    fun theTimerFiringCallsTheAlarmOff() {
        val (_, device, backstop) = store()
        device.beginBackgroundGrace()
        device.disconnectAfterGrace()
        assertEquals(null, backstop.armedFor)
        assertFalse(device.state.isConnected)
    }

    /// A reconnect that finishes while the app is away holds the gauge open exactly like
    /// the link it replaced, so it gets the same window.
    @Test
    fun aLinkThatArrivesInTheBackgroundGetsTheGrace() {
        val (client, device, backstop) = store()
        device.beginBackgroundGrace()
        client.setState(ProgressorConnectionState.Disconnected(reason = null))
        device.disconnectAfterGrace()
        val armsBefore = backstop.arms

        client.setState(ProgressorConnectionState.Connected)

        assertEquals(armsBefore + 1, backstop.arms)
        device.backgroundGraceBackstopFired()
        assertFalse(device.state.isConnected)
    }
}
