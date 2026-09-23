// SPDX-License-Identifier: MPL-2.0
// Original contributions Copyright 2026 Nuri Bruner.
package run.nuri.getagrip.runner

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
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

    // --- The keep-alive feed: what makes the track behave like the iPhone's running engine.

    private class FakeTrack : CueAudioOutput {
        val writes = mutableListOf<Int>()
        val silenceWrites get() = writes.count { it == SILENCE }
        var queued = 0
        var closes = 0
        override fun write(buffer: FloatArray, offset: Int, count: Int): Int {
            writes += if (buffer.all { it == 0f }) SILENCE else buffer[offset].toInt()
            queued += count
            return count
        }
        override fun close() { closes++ }
        override fun queuedFrames(): Int = queued
        companion object { const val SILENCE = -1 }
    }

    private val feed = CueKeepAlive(silence = FloatArray(20), lowWaterFrames = 30, pollMillis = 8)

    @Test fun theOutputIsOpenAndFedBeforeTheFirstCue() = runTest {
        val track = FakeTrack()
        var opens = 0
        val queue = CueAudioQueue(StandardTestDispatcher(testScheduler), { true },
            { floatArrayOf(7f) }, { opens++; track }, feed)
        queue.begin()
        advanceTimeBy(20); runCurrent()
        // Opened with nothing to play, and topped up to the lead — the route is awake
        // before "3" is ever asked for, as the iPhone's engine is from `begin()`.
        assertEquals(1, opens)
        assertEquals(2, track.silenceWrites)
        queue.end(); runCurrent()
        assertEquals(1, track.closes)
    }

    @Test fun aFedTrackStopsWritingSilenceOnceTheLeadIsQueued() = runTest {
        val track = FakeTrack()
        val queue = CueAudioQueue(StandardTestDispatcher(testScheduler), { true },
            { floatArrayOf(7f) }, { track }, feed)
        queue.begin()
        advanceTimeBy(200); runCurrent()
        // Nothing plays in the fake, so the lead never drains: two chunks and no more.
        assertEquals(2, track.silenceWrites)
        track.queued = 0 // the device played it all
        advanceTimeBy(9); runCurrent()
        assertEquals(3, track.silenceWrites)
        queue.end(); runCurrent()
    }

    @Test fun aCueArrivingWhileIdleIsWrittenOnTheNextPoll() = runTest {
        val track = FakeTrack()
        val queue = CueAudioQueue(StandardTestDispatcher(testScheduler), { true },
            { floatArrayOf(it.ordinal.toFloat() + 1) }, { track }, feed)
        queue.begin()
        advanceTimeBy(50); runCurrent()
        queue.play(ToneSynth.Tone.go)
        advanceTimeBy(9); runCurrent()
        assertEquals(ToneSynth.Tone.go.ordinal + 1, track.writes.last())
        queue.end(); runCurrent()
    }

    @Test fun anOutputThatWillNotOpenIsRetriedEveryTwoSecondsNotEveryPoll() = runTest {
        var opens = 0
        val queue = CueAudioQueue(StandardTestDispatcher(testScheduler), { true },
            { floatArrayOf(1f) }, { opens++; null }, feed)
        queue.begin()
        advanceTimeBy(1_900); runCurrent()
        assertEquals(1, opens)
        advanceTimeBy(200); runCurrent()
        assertEquals(2, opens)
        queue.end(); runCurrent()
    }
}
