// SPDX-License-Identifier: MPL-2.0
// Original contributions Copyright 2026 Nuri Bruner.

package run.nuri.getagrip.store

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/// **One writer at a time, in submission order.** The fix for fire-and-forget writes that
/// raced each other on a multi-threaded pool — see `SettingsStore` — and the one ordering
/// mechanism for reminder replans — see `TemplateStore.replanLane`.
///
/// Not `limitedParallelism(1)`: a lane of one THREAD still interleaves at every suspension
/// point, and `DataStore.edit` suspends, so two launched writes could still commit out of
/// order. Here a second write is not even looked at until the first one's `apply` returns.
///
/// Whatever queued up while one batch was being written is applied as the NEXT batch, in
/// order — so a burst (a draft stash per keystroke) costs one file write rather than one
/// per change, and the file still ends up holding the last value written. The drain is a
/// coroutine that exists only while there is something to write, never a consumer parked
/// forever on the store's scope: a scope's children finishing is how a caller (and a test)
/// knows the writes it made have landed.
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
                    // Cleared under the SAME lock that saw the queue empty. Clearing it
                    // afterwards left a gap in which a `submit` saw a drain still running,
                    // queued its value and started nothing — and nothing ever wrote it.
                    if (pending.isEmpty()) { draining = false; return }
                    ArrayList(pending).also { pending.clear() }
                }
                try {
                    apply(batch)
                } catch (error: CancellationException) {
                    throw error
                } catch (_: Throwable) {
                    // A failed write must not take the lane down with it: every later write
                    // would then be silently dropped for the life of the process.
                }
            }
        } catch (error: Throwable) {
            // Only cancellation reaches here. Whatever is still pending stays queued for
            // the next `submit` to drain.
            synchronized(lock) { draining = false }
            throw error
        }
    }
}
