// SPDX-License-Identifier: MPL-2.0
// Original contributions Copyright 2026 Nuri Bruner.

package run.nuri.getagrip

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import run.nuri.getagrip.ble.MockProgressorClient
import run.nuri.getagrip.ble.StreamStartCause
import run.nuri.getagrip.engine.ForceSample
import run.nuri.getagrip.engine.ProgressorCommand
import run.nuri.getagrip.engine.ProgressorEvent
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class MockProgressorClientTests {
    @Test fun repeatedTareZeroesTheCurrentLoadWithoutAccumulatingOffsets() = runTest {
        val client = MockProgressorClient(backgroundScope, clock = FakeClock())
        val samples = mutableListOf<ForceSample>()
        client.onEvent = { if (it is ProgressorEvent.Sample) samples += it.sample }
        client.connect()
        advanceTimeBy(450)
        runCurrent()
        assertTrue(client.state.isConnected)
        client.startStreaming(StreamStartCause.initial)
        advanceTimeBy(100)
        runCurrent()
        assertTrue(samples.last().kg > 1)

        val next = samples.size
        client.send(ProgressorCommand.tare)
        client.send(ProgressorCommand.tare)
        client.startStreaming(StreamStartCause.tareRecovery)
        advanceTimeBy(100)
        runCurrent()

        assertEquals(0.0, samples[next].kg, 1e-9,
            "Taring twice against one raw load must still mean zero")
        assertEquals(0u, samples[next].deviceMicros, "A real re-kick may reset the device epoch")
        samples.forEachIndexed { index, sample -> assertEquals(index % 8 == 0, sample.isBatchStart) }
        client.disconnect()
    }

    @Test fun secondSessionStartsUnloadedAfterEndingOnTheLoadedProfile() = runTest {
        val client = MockProgressorClient(backgroundScope, clock = FakeClock())
        val samples = mutableListOf<ForceSample>()
        client.onEvent = { if (it is ProgressorEvent.Sample) samples += it.sample }
        client.connect()
        advanceTimeBy(450)
        runCurrent()
        client.send(ProgressorCommand.tare)
        client.startStreaming(StreamStartCause.initial)
        advanceTimeBy(100)
        runCurrent()
        assertTrue(samples.last().kg > 1)
        client.send(ProgressorCommand.stopWeightMeasurement)

        val next = samples.size
        client.send(ProgressorCommand.tare)
        client.startStreaming(StreamStartCause.initial)
        runCurrent()

        assertEquals(0.0, samples[next].kg, 1e-9)
        assertEquals(0u, samples[next].deviceMicros)
        assertTrue(samples.drop(next).all { it.kg >= 0 },
            "The old synthetic load must not become a negative offset")
        client.disconnect()
    }
}
