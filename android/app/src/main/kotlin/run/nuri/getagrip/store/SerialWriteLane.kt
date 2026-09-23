// SPDX-License-Identifier: MPL-2.0
// Original contributions Copyright 2026 Nuri Bruner.

package run.nuri.getagrip.store

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/// **One writer at a time, in submission order** — for `SettingsStore`'s writes and
/// `TemplateStore.replanLane`.
///
/// Not `limitedParallelism(1)`: one THREAD still interleaves at every suspension, and
/// `DataStore.edit` suspends, so writes could commit out of order. Here a second write
/// waits for the first's `apply` to return.
///
/// Values queued during a write are applied as the NEXT batch, in order, so a burst (a
/// draft stash per keystroke) costs one file write and still ends on the last value. The
/// drain coroutine exists only while there is work, never parked on the store's scope, so a
/// scope's children finishing tells a caller (or test) the writes landed.
internal class SerialWriteLane<T>(
    private val scope: CoroutineScope,
    private val apply: suspend (List<T>) -> Unit,
) {
    private val lock = Any()
    private val pending = ArrayList<T>()
    private var draining = false

    fun submit(value: T) {
        val startDrain = synchronized(lock) {
            pending.add(value)
            if (draining) false else true.also { draining = true }
        }
        if (startDrain) scope.launch { drain() }
    }

    private suspend fun drain() {
        try {
            while (true) {
                val batch = synchronized(lock) {
                    // Cleared under the SAME lock that saw the queue empty; clearing after
                    // left a gap where a `submit` queued behind a finishing drain and
                    // nothing ever wrote it.
                    if (pending.isEmpty()) { draining = false; return }
                    ArrayList(pending).also { pending.clear() }
                }
                try {
                    apply(batch)
                } catch (error: CancellationException) {
                    throw error
                } catch (_: Throwable) {
                    // A failed write must not kill the lane, or every later write is
                    // silently dropped.
                }
            }
        } catch (error: Throwable) {
            // Only cancellation reaches here; pending values wait for the next `submit`.
            synchronized(lock) { draining = false }
            throw error
        }
    }
}
