// SPDX-License-Identifier: MPL-2.0
// Original contributions Copyright 2026 Nuri Bruner.

package run.nuri.getagrip.runner

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch

/** One owner performs every native audio operation, including teardown. Cancelling a
 * coroutine cannot interrupt JNI: releasing its track on another thread can segfault.
 * Nonblocking writes let this owner observe cancellation without waiting on the route.
 */
internal interface CueAudioOutput {
    fun write(buffer: FloatArray, offset: Int, count: Int): Int
    fun close()
}

internal class CueAudioQueue(
    private val dispatcher: CoroutineDispatcher,
    private val enabled: () -> Boolean,
    private val render: (ToneSynth.Tone) -> FloatArray,
    private val open: () -> CueAudioOutput?,
) {
    private var queue: Channel<ToneSynth.Tone>? = null
    private var worker: Job? = null

    @Synchronized fun begin() {
        if (queue != null) return
        val channel = Channel<ToneSynth.Tone>(16, BufferOverflow.DROP_OLDEST)
        queue = channel
        worker = CoroutineScope(dispatcher).launch {
            // Never share the native handle with callers or a subsequent session.
            var output: CueAudioOutput? = null
            fun close() {
                val previous = output
                output = null
                runCatching { previous?.close() }
            }
            try {
                for (tone in channel) {
                    if (!enabled()) continue
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
            } finally {
                close()
            }
        }
    }

    @Synchronized fun play(tone: ToneSynth.Tone) { queue?.trySend(tone) }

    @Synchronized fun end() {
        queue?.cancel()
        queue = null
        worker?.cancel()
        worker = null
        // The worker's finally block releases only after its last native call returns.
    }
}
