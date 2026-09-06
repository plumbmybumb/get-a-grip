// SPDX-License-Identifier: MPL-2.0
// Original contributions Copyright 2026 Nuri Bruner.

package run.nuri.getagrip.runner

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import run.nuri.getagrip.RecordingProgressorClient
import run.nuri.getagrip.ble.HostClock
import run.nuri.getagrip.ble.ProgressorConnectionState
import run.nuri.getagrip.engine.ForceSample
import run.nuri.getagrip.engine.GaugeKind
import run.nuri.getagrip.engine.ProgressorEvent
import run.nuri.getagrip.engine.SessionPlan
import run.nuri.getagrip.engine.SetPlan
import run.nuri.getagrip.engine.RunnerEvent
import run.nuri.getagrip.store.DeviceStore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class RunnerTareRecoveryTests {
    @Test fun refusedTareDoesNotWriteOrBreakTheTimeline() = runTest {
        for (isWorking in listOf(false, true)) {
            val clock = object : HostClock {
                override fun uptimeSeconds() = testScheduler.currentTime / 1_000.0
                override fun wallSeconds() = 1_000 + uptimeSeconds()
            }
            val client = RecordingProgressorClient()
            val device = DeviceStore(client, scope = backgroundScope, clock = clock)
            client.setState(ProgressorConnectionState.Connected)
            val session = RunnerSession(
                plan = SessionPlan(sets = listOf(SetPlan()), holdSeconds = 60, leadInSeconds = 0),
                routineName = "Refused tare", device = device, scope = backgroundScope, clock = clock,
            )
            session.begin()
            repeat(80) { index ->
                val event = RunnerEvent.Sample(ForceSample(if (isWorking) 10.0 else 0.0,
                    50_000_000u + index.toUInt() * 12_500u))
                if (isWorking) client.emit(ProgressorEvent.Sample(event.sample)) else session.send(event)
            }
            val commands = client.commands.size
            session.tare()
            assertEquals(commands, client.commands.size)
            session.send(RunnerEvent.Sample(ForceSample(0.0, 100_000u, isBatchStart = true)))
            assertTrue(session.snapshot.isRejectingStaleBatches,
                "Refusing a stale/working tare must preserve the epoch high-water mark")
            session.end()
        }
    }

    @Test fun tareRecoversOnceWhenBufferedSamplesAnchorTheNewDeviceEpoch() = runTest {
        for (kind in listOf(GaugeKind.progressor, GaugeKind.whc06)) {
            val clock = object : HostClock {
                override fun uptimeSeconds() = testScheduler.currentTime / 1_000.0
                override fun wallSeconds() = 1_000 + uptimeSeconds()
            }
            val client = RecordingProgressorClient(kind)
            val device = DeviceStore(client, scope = backgroundScope, clock = clock)
            client.setState(ProgressorConnectionState.Connected)
            val session = RunnerSession(
                plan = SessionPlan(sets = listOf(SetPlan()), leadInSeconds = 0),
                routineName = "Tare recovery", device = device, scope = backgroundScope, clock = clock,
            )
            session.begin()
            runCurrent()
            fun sample(micros: UInt) = client.emit(
                ProgressorEvent.Sample(ForceSample(0.0, micros, isBatchStart = true)),
            )
            // Keep a healthy stream until the first-start recovery arm has expired.
            repeat(60) { index ->
                sample(10_000_000u + index.toUInt() * 100_000u)
                advanceTimeBy(100)
                runCurrent()
            }
            session.tare()
            sample(50_000_000u) // queued pre-tare batch anchors first
            sample(500_000u) // genuine restarted epoch is initially rejected
            assertTrue(session.snapshot.isRejectingStaleBatches)
            repeat(12) { index ->
                sample(600_000u + index.toUInt() * 100_000u)
                advanceTimeBy(100)
                runCurrent()
            }
            sample(1_900_000u)
            assertEquals(!kind.capabilities.hasDeviceClock, session.snapshot.isRejectingStaleBatches)
            assertEquals(0f, session.repProgress, "Recovery never invents held time")

            // Replay another backwards epoch without a new start/tare. Deliver it past
            // the rate limit to prove the first heal consumed its one permission.
            repeat(30) { index ->
                sample(100_000u + index.toUInt() * 1_000u)
                advanceTimeBy(100)
                runCurrent()
            }
            assertTrue(session.snapshot.isRejectingStaleBatches)
            session.end()
        }
    }
}
