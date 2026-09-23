// SPDX-License-Identifier: MPL-2.0
// Original contributions Copyright 2026 Nuri Bruner.

package run.nuri.getagrip.store

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlin.test.Test
import kotlin.test.assertEquals

/// **Settings writes land in the order they were made.** Each one used to be its own
/// `launch` on the IO pool, and an older write finishing last is how a cleared draft stash
/// came back from the dead and how a stale reminder-id list overwrote the current one.
class SerialWriteLaneTests {

    @Test
    fun writesAreAppliedInSubmissionOrderEvenWhileOneIsSuspended() {
        val scope = TestScope(StandardTestDispatcher())
        val written = mutableListOf<String>()
        val firstGate = CompletableDeferred<Unit>()
        var calls = 0
        val lane = SerialWriteLane<String>(scope) { batch ->
            calls += 1
            // The first write stalls mid-edit, exactly as a DataStore `edit` suspends.
            if (calls == 1) firstGate.await()
            written.addAll(batch)
        }

        lane.submit("stash")
        scope.runCurrent()
        lane.submit("cleared")
        lane.submit("final")
        scope.runCurrent()
        assertEquals(emptyList(), written, "nothing overtakes the write in flight")

        firstGate.complete(Unit)
        scope.advanceUntilIdle()
        assertEquals(listOf("stash", "cleared", "final"), written)
        assertEquals(2, calls, "what queued behind the stalled write is one batch")
    }

    @Test
    fun aFailedWriteDoesNotStopTheLane() {
        val scope = TestScope(StandardTestDispatcher())
        val written = mutableListOf<Int>()
        val lane = SerialWriteLane<Int>(scope) { batch ->
            if (batch.contains(1)) error("disk full")
            written.addAll(batch)
        }
        lane.submit(1)
        scope.advanceUntilIdle()
        lane.submit(2)
        scope.advanceUntilIdle()
        assertEquals(listOf(2), written)
    }
}
