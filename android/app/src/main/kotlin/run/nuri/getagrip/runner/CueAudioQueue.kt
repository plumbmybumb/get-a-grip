// SPDX-License-Identifier: MPL-2.0
// Original contributions Copyright 2026 Nuri Bruner.

package run.nuri.getagrip.runner

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ChannelResult
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.onTimeout
import kotlinx.coroutines.selects.select

/** One owner performs every native audio operation, including teardown. Cancelling a
 * coroutine cannot interrupt JNI: releasing its track on another thread can segfault.
 * Nonblocking writes let this owner observe cancellation without waiting on the route.
 */
internal interface CueAudioOutput {
    fun write(buffer: FloatArray, offset: Int, count: Int): Int
    fun close()

    /// Frames written but not yet played, or null when unknown — the keep-alive is then
    /// skipped, not guessed.
    fun queuedFrames(): Int? = null
}

/** Keeps the output FED between cues, which is what the iPhone gets for free.
 *
 * iOS schedules cue buffers on an `AVAudioPlayerNode` whose engine renders continuously,
 * silence included, from `begin()` to `end()` — so the route is awake before the first
 * tick and stays awake through a two-minute rest. A streaming `AudioTrack` fed only cue
 * samples does the opposite: it starves between cues, the output drops to standby after a
 * few seconds, and the next cue pays the wake-up — clipped on the speaker, often lost
 * entirely on Bluetooth, where waking A2DP costs longer than a 60 ms tick lasts. A brand
 * new track is worse still: it does not start until its start threshold fills, so the
 * first "3" of a countdown sat in the buffer until the "2" pushed it out, and the two
 * sounded together. Feeding silence at a small lead fixes both, at the price iOS already
 * pays: an audio path held open for the session.
 */
internal class CueKeepAlive(
    /// One chunk of silence, written whenever the queued lead drops below [lowWaterFrames].
    val silence: FloatArray,
    /// The lead kept queued while idle. Small, because every cue queues BEHIND it: tens of
    /// milliseconds, not hundreds.
    val lowWaterFrames: Int,
    /// How often an idle output is checked; a queued cue wakes the worker at once.
    val pollMillis: Long,
) {
    /// An empty chunk feeds nothing, so the worker waits on cues alone (the queue tests'
    /// shape).
    val feeds: Boolean get() = silence.isNotEmpty() && pollMillis > 0
}

internal class CueAudioQueue(
    private val dispatcher: CoroutineDispatcher,
    private val enabled: () -> Boolean,
    private val render: (ToneSynth.Tone) -> FloatArray,
    private val open: () -> CueAudioOutput?,
    private val keepAlive: CueKeepAlive,
) {
    private var queue: Channel<ToneSynth.Tone>? = null
    private var worker: Job? = null

    @Synchronized fun begin() {
        if (queue != null) return
        val channel = Channel<ToneSynth.Tone>(16, BufferOverflow.DROP_OLDEST)
        queue = channel
        // A new worker per session: the native handle is never shared with callers or a
        // later session.
        val owner = Worker(channel, enabled, render, open, keepAlive)
        worker = CoroutineScope(dispatcher).launch { owner.run() }
    }

    @Synchronized fun play(tone: ToneSynth.Tone) { queue?.trySend(tone) }

    @Synchronized fun end() {
        queue?.cancel()
        queue = null
        worker?.cancel()
        worker = null
        // The worker's finally block releases only after its last native call returns.
    }

    /** The one owner of a session's output: every write, every top-up and the release. */
    private class Worker(
        private val channel: ReceiveChannel<ToneSynth.Tone>,
        private val enabled: () -> Boolean,
        private val render: (ToneSynth.Tone) -> FloatArray,
        private val open: () -> CueAudioOutput?,
        private val feed: CueKeepAlive,
    ) {
        private var output: CueAudioOutput? = null

        /// Polls to skip before reopening after a failed open, so a dead route is not a
        /// hundred native calls a second. Two seconds, the iPhone's dead-engine retry.
        private var openBackoff = 0

        /// ONE loop: wait for a cue or the poll, whichever first. A cue wakes it at once;
        /// an idle output is topped up each poll.
        @OptIn(ExperimentalCoroutinesApi::class)
        suspend fun run() {
            try {
                while (true) {
                    val next = select<ChannelResult<ToneSynth.Tone>?> {
                        channel.onReceiveCatching { it }
                        if (feed.feeds) onTimeout(feed.pollMillis) { null }
                    }
                    when {
                        next == null -> topUp()
                        next.isClosed -> break
                        else -> {
                            val tone = next.getOrNull() ?: continue
                            if (enabled()) writeTone(tone)
                        }
                    }
                }
            } finally {
                close()
            }
        }

        /// One tone, written in full: partial writes completed, a failed route rebuilt
        /// once, samples already written kept.
        private suspend fun writeTone(tone: ToneSynth.Tone) {
            val buffer = render(tone)
            var offset = 0
            var stalls = 0
            var rebuilt = false
            while (offset < buffer.size && enabled()) {
                currentCoroutineContext().ensureActive()
                if (output == null) output = runCatching(open).getOrNull()
                val active = output ?: break
                val written = runCatching { active.write(buffer, offset, buffer.size - offset) }.getOrDefault(-1)
                when {
                    written > 0 && written <= buffer.size - offset -> {
                        offset += written
                        stalls = 0
                    }
                    written == 0 && ++stalls < 200 -> delay(5)
                    else -> {
                        close()
                        if (rebuilt || written == 0) break
                        rebuilt = true
                    }
                }
            }
        }

        /// Idle: top the lead up with silence. One nonblocking attempt (a short write is
        /// just less silence); a failing route closes so the next pass opens a fresh one.
        private fun topUp() {
            if (!enabled()) return
            if (output == null) {
                if (openBackoff > 0) { openBackoff--; return }
                output = runCatching(open).getOrNull()
                if (output == null) openBackoff = (2_000 / feed.pollMillis.coerceAtLeast(1)).toInt()
            }
            val active = output ?: return
            val queued = runCatching { active.queuedFrames() }.getOrNull() ?: return
            if (queued >= feed.lowWaterFrames) return
            val written = runCatching { active.write(feed.silence, 0, feed.silence.size) }.getOrDefault(-1)
            if (written < 0) close()
        }

        private fun close() {
            val previous = output
            output = null
            runCatching { previous?.close() }
        }
    }
}
