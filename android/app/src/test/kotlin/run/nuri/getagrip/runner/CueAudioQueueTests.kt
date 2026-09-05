// SPDX-License-Identifier: MPL-2.0
// Original contributions Copyright 2026 Nuri Bruner.
package run.nuri.getagrip.runner

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class CueAudioQueueTests {
    @Test fun burstCuesStayInOrderAndPartialWritesAreCompleted() = runTest {
        val samples = mutableListOf<Float>()
        var closes = 0
        val queue = CueAudioQueue(StandardTestDispatcher(testScheduler), { true },
            { floatArrayOf(it.ordinal.toFloat(), it.ordinal.toFloat()) }, {
                object : CueAudioOutput {
                    override fun write(buffer: FloatArray, offset: Int, count: Int): Int {
                        samples += buffer[offset]; return 1
                    }
                    override fun close() { closes++ }
                }
            })
        queue.begin()
        val tones = listOf(ToneSynth.Tone.repComplete, ToneSynth.Tone.setComplete, ToneSynth.Tone.gripChange)
        tones.forEach(queue::play)
        runCurrent()
        assertEquals(tones.flatMap { listOf(it.ordinal.toFloat(), it.ordinal.toFloat()) }, samples)
        queue.end(); runCurrent()
        assertEquals(1, closes)
    }

    @Test fun endingNeverReleasesATrackWhileItsNativeWriteIsInFlight() {
        val entered = CountDownLatch(1)
        val returnFromWrite = CountDownLatch(1)
        val closed = CountDownLatch(1)
        val queue = CueAudioQueue(Dispatchers.IO, { true }, { floatArrayOf(1f, 2f) }, {
            object : CueAudioOutput {
                override fun write(buffer: FloatArray, offset: Int, count: Int): Int {
                    entered.countDown()
                    check(returnFromWrite.await(5, TimeUnit.SECONDS))
                    return 1
                }
                override fun close() { closed.countDown() }
            }
        })
        try {
            queue.begin(); queue.play(ToneSynth.Tone.go)
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            queue.end()
            assertEquals(1, closed.count, "Native handle was released during write")
            returnFromWrite.countDown()
            assertTrue(closed.await(5, TimeUnit.SECONDS))
        } finally { returnFromWrite.countDown(); queue.end() }
    }

    @Test fun routeFailureRebuildsOnceAndKeepsAlreadyWrittenSamples() = runTest {
        var opens = 0
        var closes = 0
        val received = mutableListOf<Float>()
        val queue = CueAudioQueue(StandardTestDispatcher(testScheduler), { true }, { floatArrayOf(1f, 2f, 3f) }, {
            val generation = ++opens
            object : CueAudioOutput {
                override fun write(buffer: FloatArray, offset: Int, count: Int): Int {
                    if (generation == 1 && offset == 1) return -6
                    received += buffer[offset]; return 1
                }
                override fun close() { closes++ }
            }
        })
        queue.begin(); queue.play(ToneSynth.Tone.go); runCurrent()
        assertEquals(listOf(1f, 2f, 3f), received)
        assertEquals(2, opens)
        queue.end(); runCurrent(); assertEquals(2, closes)
    }

    @Test fun anUnwritableRouteIsBoundedAndCanBeStopped() = runTest {
        var writes = 0
        var closes = 0
        val queue = CueAudioQueue(StandardTestDispatcher(testScheduler), { true }, { floatArrayOf(1f) }, {
            object : CueAudioOutput {
                override fun write(buffer: FloatArray, offset: Int, count: Int): Int { writes++; return 0 }
                override fun close() { closes++ }
            }
        })
        queue.begin(); queue.play(ToneSynth.Tone.go); advanceUntilIdle()
        assertEquals(200, writes); assertEquals(1, closes)
        queue.end(); runCurrent(); assertEquals(1, closes)
    }

    @Test fun endDropsQueuedCuesAndRestartUsesANewHandle() = runTest {
        var writes = 0
        var opens = 0
        var closes = 0
        val queue = CueAudioQueue(StandardTestDispatcher(testScheduler), { true }, { floatArrayOf(1f) }, {
            opens++
            object : CueAudioOutput {
                override fun write(buffer: FloatArray, offset: Int, count: Int): Int { writes++; return count }
                override fun close() { closes++ }
            }
        })
        queue.begin(); queue.play(ToneSynth.Tone.go); runCurrent()
        repeat(10) { queue.play(ToneSynth.Tone.alarm) }
        queue.end(); runCurrent()
        assertEquals(1, writes); assertEquals(1, closes)
        queue.begin(); queue.play(ToneSynth.Tone.go); runCurrent()
        queue.end(); runCurrent()
        assertEquals(2, opens); assertEquals(2, writes); assertEquals(2, closes)
    }

    @Test fun mutingBeforePlaybackAvoidsOpeningTheAudioDevice() = runTest {
        var enabled = true
        val opens = AtomicInteger()
        val queue = CueAudioQueue(StandardTestDispatcher(testScheduler), { enabled }, { floatArrayOf(1f) }, {
            opens.incrementAndGet(); null
        })
        queue.begin(); queue.play(ToneSynth.Tone.go); enabled = false; runCurrent()
        assertEquals(0, opens.get())
        queue.end(); runCurrent()
    }
}
