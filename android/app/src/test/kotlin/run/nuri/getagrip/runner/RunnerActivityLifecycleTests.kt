// SPDX-License-Identifier: MPL-2.0
// Original contributions Copyright 2026 Nuri Bruner.

package run.nuri.getagrip.runner

import run.nuri.getagrip.FakeClock
import run.nuri.getagrip.RecordingProgressorClient
import run.nuri.getagrip.ble.ProgressorConnectionState
import run.nuri.getagrip.ble.StreamStartCause
import run.nuri.getagrip.engine.ForceSample
import run.nuri.getagrip.engine.HandMode
import run.nuri.getagrip.engine.RunnerEvent
import run.nuri.getagrip.engine.RunnerPhase
import run.nuri.getagrip.engine.SessionPlan
import run.nuri.getagrip.engine.SetPlan
import run.nuri.getagrip.inertScope
import run.nuri.getagrip.store.DeviceStore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RunnerActivityLifecycleTests {
    private class ActivityRecorder : ActivityPublisher {
        override var isRunning = false
        var starts = 0
        var ends = 0
        val states = mutableListOf<SessionActivityState>()
        override fun start(routineName: String, plannedReps: Int, setCount: Int, state: SessionActivityState) {
            starts++
            isRunning = true
            states += state
        }
        override fun update(state: SessionActivityState) { states += state }
        override fun end() {
            if (!isRunning) return
            isRunning = false
            ends++
        }
    }
    private class ServiceRecorder : SessionServiceController {
        var starts = 0
        var ends = 0
        override fun begin() { starts++ }
        override fun end() { ends++ }
    }

    @Test fun releaseRestPauseAndFinishPublishTheirActualLifecycle() {
        val client = RecordingProgressorClient()
        val device = DeviceStore(client, scope = inertScope(), clock = FakeClock())
        client.setState(ProgressorConnectionState.Connected)
        val activity = ActivityRecorder()
        val service = ServiceRecorder()
        val session = RunnerSession(
            plan = SessionPlan(sets = listOf(SetPlan(repsPerSide = 2)), handMode = HandMode.bothHands,
                holdSeconds = 1, restSeconds = 20, leadInSeconds = 0, waitForReleaseBeforeRest = true),
            routineName = "Activity lifecycle", device = device, scope = inertScope(), clock = FakeClock(),
            activity = activity, service = service,
        )
        session.begin()
        for (index in 1..120) session.send(RunnerEvent.Sample(ForceSample(10.0, (index * 12_500).toUInt())))
        assertTrue(session.snapshot.phase is RunnerPhase.Releasing)
        assertEquals(SessionActivityPhase.releasing, activity.states.last().phase)
        assertNull(activity.states.last().endsAtEpochMillis)
        session.send(RunnerEvent.Pause)
        assertEquals(SessionActivityPhase.paused, activity.states.last().phase)
        assertNull(activity.states.last().endsAtEpochMillis)
        session.send(RunnerEvent.Resume)
        for (index in 121..160) session.send(RunnerEvent.Sample(ForceSample(0.0, (index * 12_500).toUInt())))
        assertEquals(SessionActivityPhase.resting, activity.states.last().phase)
        assertTrue(activity.states.last().endsAtEpochMillis!! - System.currentTimeMillis() > 18_000)
        val published = activity.states.size
        session.send(RunnerEvent.Abort)
        assertEquals(1, activity.ends, "End on the summary, before save/discard")
        assertEquals(1, service.ends)
        assertEquals(published, activity.states.size, "Finish must not publish a phantom rest")

        val commands = client.commands.size
        session.startIfReady(StreamStartCause.foreground)
        session.connectionChanged(true)
        session.wakeStream()
        session.tickNow()
        assertEquals(commands, client.commands.size)
        assertEquals(1, activity.starts)
        assertEquals(1, service.starts)
        assertEquals(published, activity.states.size)
        session.end()
        assertEquals(1, activity.ends)
        assertEquals(1, service.ends)
    }
}
