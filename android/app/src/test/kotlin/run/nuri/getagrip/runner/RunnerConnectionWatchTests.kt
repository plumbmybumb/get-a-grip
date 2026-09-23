// SPDX-License-Identifier: MPL-2.0
// Original contributions Copyright 2026 Nuri Bruner.

package run.nuri.getagrip.runner

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import run.nuri.getagrip.FakeClock
import run.nuri.getagrip.RecordingProgressorClient
import run.nuri.getagrip.ble.ProgressorConnectionState
import run.nuri.getagrip.engine.GripSpec
import run.nuri.getagrip.engine.ProgressorCommand
import run.nuri.getagrip.engine.SessionPlan
import run.nuri.getagrip.engine.SetPlan
import run.nuri.getagrip.inertScope
import run.nuri.getagrip.store.DeviceStore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/// **A session hears the link drop and return with no screen involved.**
///
/// It used to learn about the link from a `LaunchedEffect` on the runner screen, which stops
/// with the Activity — so the one session the foreground service exists for, running behind
/// a locked screen, never got `ConnectionLost`/`ConnectionRestored`: no timeline break, no
/// stream re-kick, nothing. Nothing here composes anything; the session follows
/// `DeviceStore.link` on its own scope.
@OptIn(ExperimentalCoroutinesApi::class)
class RunnerConnectionWatchTests {

    private val plan = SessionPlan(
        name = "Daily",
        sets = listOf(SetPlan(grip = GripSpec(), repsPerSide = 2)),
        holdSeconds = 3, restSeconds = 5, leadInSeconds = 3,
    )

    private fun starts(client: RecordingProgressorClient) =
        client.commands.count { it == ProgressorCommand.startWeightMeasurement }

    @Test
    fun aDroppedAndRestoredLinkReachesTheEngineWithNoScreen() {
        val client = RecordingProgressorClient()
        val device = DeviceStore(client = client, scope = inertScope(), clock = FakeClock())
        client.setState(ProgressorConnectionState.Connected)
        val session = RunnerSession(plan = plan, routineName = "Daily", device = device,
            scope = CoroutineScope(UnconfinedTestDispatcher()), clock = FakeClock())
        session.begin()
        val before = starts(client)

        client.setState(ProgressorConnectionState.Disconnected(reason = null))
        assertTrue(session.snapshot.linkIsDown, "the engine heard the drop")

        client.setState(ProgressorConnectionState.Connected)
        assertFalse(session.snapshot.linkIsDown, "and the return")
        assertEquals(before + 1, starts(client), "which re-kicks the stream once")

        // A caller repeating what the watcher already reported changes nothing.
        session.connectionChanged(true)
        assertEquals(before + 1, starts(client))
        session.end()
    }

    /// The flow conflates: a drop and a reconnect between two collections look like "still
    /// connected". The epoch is what shows a NEW link, and the engine still gets its break.
    @Test
    fun aReconnectTheFlowConflatedIsStillReportedAsADropAndAReturn() {
        val scheduler = TestCoroutineScheduler()
        val client = RecordingProgressorClient()
        val device = DeviceStore(client = client, scope = inertScope(), clock = FakeClock())
        client.setState(ProgressorConnectionState.Connected)
        val session = RunnerSession(plan = plan, routineName = "Daily", device = device,
            scope = CoroutineScope(StandardTestDispatcher(scheduler)), clock = FakeClock())
        session.begin()
        scheduler.runCurrent()
        val before = starts(client)

        client.setState(ProgressorConnectionState.Disconnected(reason = null))
        client.setState(ProgressorConnectionState.Connected)
        scheduler.runCurrent()

        assertEquals(before + 1, starts(client), "the new link was re-kicked")
        assertFalse(session.snapshot.linkIsDown)
        session.end()
    }

    /// A link arriving DURING `begin()` (the fake connects synchronously) is reported once
    /// the session is set up, rather than being taken as the baseline and never reported.
    @Test
    fun aLinkThatArrivesDuringBeginIsReported() {
        val client = RecordingProgressorClient()
        val device = DeviceStore(client = client, scope = inertScope(), clock = FakeClock())
        val session = RunnerSession(plan = plan, routineName = "Daily", device = device,
            scope = CoroutineScope(UnconfinedTestDispatcher()), clock = FakeClock())
        session.begin()
        assertTrue(client.state.isConnected)
        assertEquals(1, starts(client), "connect-and-tare ran without anyone calling it")
        session.end()
    }
}
