// SPDX-License-Identifier: MPL-2.0
// Original contributions Copyright 2026 Nuri Bruner.
package run.nuri.getagrip.ui

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test
import run.nuri.getagrip.ui.components.SubmissionState
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class SubmissionStateTests {
    @Test fun cancelledBeforeStartReleasesTheSubmissionLatch() = runTest {
        val job = Job()
        val scope = CoroutineScope(coroutineContext + job)
        val state = SubmissionState()
        var writes = 0
        state.launch(scope) { writes++ }
        job.cancel()
        runCurrent()
        assertEquals(0, writes)
        assertFalse(state.isRunning)
    }

    @Test fun rapidSaveTapsSubmitOnceAndFailedSaveCanRetry() = runTest {
        val state = SubmissionState()
        val pendingWrite = CompletableDeferred<Boolean>()
        var writes = 0
        var failed = false
        fun save() = state.launch(this) {
            writes++
            failed = !pendingWrite.await()
        }
        repeat(20) { save() }
        assertTrue(state.isRunning)
        assertEquals(0, writes) // The latch is set before the coroutine starts.
        runCurrent()
        assertEquals(1, writes)
        repeat(20) { save() }
        runCurrent()
        assertEquals(1, writes)
        pendingWrite.complete(false)
        runCurrent()
        assertFalse(state.isRunning)
        assertTrue(failed)
        state.launch(this) { writes++; failed = false }
        runCurrent()
        assertEquals(2, writes)
        assertFalse(state.isRunning)
        assertFalse(failed)
    }
}
