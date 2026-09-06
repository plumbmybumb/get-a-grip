// SPDX-License-Identifier: MPL-2.0
// Original contributions Copyright 2026 Nuri Bruner.

package run.nuri.getagrip

import kotlin.test.*
import run.nuri.getagrip.ble.StreamStartCause
import run.nuri.getagrip.ble.PacketBoundary
import run.nuri.getagrip.ble.StreamingConnectionPriority
import run.nuri.getagrip.ble.withPacket
import run.nuri.getagrip.engine.ForceSample
import run.nuri.getagrip.engine.ProgressorEvent
import run.nuri.getagrip.store.DeviceStore
import run.nuri.getagrip.store.PipelineDiagnostics

class BluetoothPipelineTests {
    @Test fun packetPublishesOnceWhileAllRawSamplesRemainImmediate() {
        val client = RecordingProgressorClient()
        val store = DeviceStore(client = client, scope = inertScope(), clock = FakeClock())
        client.connect()
        store.startStreaming(StreamStartCause.manualMeasurement)
        val revision = store.sampleRevision
        val received = mutableListOf<ForceSample>()
        store.onSample = { sample ->
            received.add(sample)
            assertEquals(sample.kg, store.currentKg)
            assertEquals(sample, store.lastSample)
            assertEquals(revision, store.sampleRevision)
        }
        val samples = listOf(9.0, 2.0, 0.0).mapIndexed { i, kg ->
            ForceSample(kg, (UInt.MAX_VALUE - 1000u) + (i * 12500).toUInt(), isBatchStart = i == 0)
        }
        client.withPacket(0.0) { samples.forEach { client.onEvent?.invoke(ProgressorEvent.Sample(it)) } }
        assertEquals(samples, received)
        assertEquals(revision + 1, store.sampleRevision)
        assertEquals(0.0, store.currentKg)
        assertEquals(9.0, store.peakKg)
        assertFalse(store.isLoadedForTare)
        assertEquals(listOf(9.0, 2.0, 0.0), store.trace.map { it.kg })
        assertEquals(3, store.pipelineDiagnostics.snapshot().first().samples)
    }

    @Test fun packetAlwaysClosesEvenIfAConsumerThrows() {
        val client = RecordingProgressorClient()
        val boundaries = mutableListOf<PacketBoundary>()
        client.onPacketBoundary = { boundaries.add(it) }
        assertFailsWith<IllegalStateException> { client.withPacket(1.0) { error("consumer") } }
        assertEquals(listOf(PacketBoundary.Began(1.0), PacketBoundary.Ended), boundaries)
    }

    @Test fun timingStagesExcludeHiddenGraphTimeAndRecordOnlyFirstDraw() {
        val timing = PipelineDiagnostics()
        timing.begin(10.0, 10.004); timing.sample(); timing.end(10.006)
        timing.graphOpened(); timing.drawing(20.0)
        assertNull(timing.snapshot().first().drawMs)
        timing.begin(20.0, 20.003); timing.sample(); timing.sample(); timing.end(20.005)
        timing.drawing(20.016)
        val packet = timing.snapshot().last()
        assertEquals(3.0, packet.hopMs, .001)
        assertEquals(2.0, packet.processingMs, .001)
        assertEquals(16.0, packet.drawMs!!, .001)
        assertEquals(2, packet.samples)
        timing.drawing(30.0)
        assertEquals(16.0, timing.snapshot().last().drawMs!!, .001)
    }

    @Test fun diagnosticsStayBoundedAndForgetThePreviousLinkClock() {
        val timing = PipelineDiagnostics()
        repeat(300) { timing.begin(it.toDouble(), it.toDouble()); timing.sample(); timing.end(it.toDouble()) }
        assertEquals(128, timing.snapshot().size)
        timing.reset()
        assertTrue(timing.snapshot().isEmpty())
        timing.begin(1000.0, 1000.0); timing.sample(); timing.end(1000.0)
        assertNull(timing.snapshot().first().gapMs)
    }

    @Test fun linkTuningDeduplicatesAcceptedRequestsAndResetsWithLink() {
        val tuning = StreamingConnectionPriority()
        val requests = mutableListOf<Boolean>()
        val request: (Boolean) -> Boolean = { requests.add(it); true }
        tuning.update(true, 0.0, request)
        repeat(20) { tuning.update(true, it.toDouble(), request) }
        tuning.update(false, 21.0, request)
        tuning.update(false, 22.0, request)
        tuning.reset()
        tuning.update(true, 23.0, request)
        assertEquals(listOf(true, false, true), requests)
    }

    @Test fun rejectedTuningIsRateLimitedButStopCanImmediatelyRestoreBalanced() {
        val tuning = StreamingConnectionPriority()
        val requests = mutableListOf<Boolean>()
        val reject: (Boolean) -> Boolean = { requests.add(it); false }
        tuning.update(true, 0.0, reject)
        tuning.update(true, 1.0, reject)
        tuning.update(true, 5.0, reject)
        tuning.update(false, 5.1, reject)
        assertEquals(listOf(true, true, false), requests)
    }
}
