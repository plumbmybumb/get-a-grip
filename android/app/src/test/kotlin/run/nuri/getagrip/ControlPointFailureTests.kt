// SPDX-License-Identifier: MPL-2.0
// Original contributions Copyright 2026 Nuri Bruner.
package run.nuri.getagrip
import run.nuri.getagrip.ble.ControlPointQueue
import run.nuri.getagrip.ble.StreamStartCause
import run.nuri.getagrip.engine.ProgressorCommand
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ControlPointFailureTests {
    @Test fun disconnectedTareCannotReachANewLink() {
        val transport = FakeControlPointTransport(canWrite = false)
        val queue = ControlPointQueue(transport)
        queue.enqueue(ProgressorCommand.tare)
        queue.enqueue(ProgressorCommand.startWeightMeasurement)
        transport.canWrite = true
        queue.linkEstablished()
        queue.enqueue(ProgressorCommand.startWeightMeasurement)
        assertEquals(listOf(ProgressorCommand.startWeightMeasurement), transport.writes)
        assertFalse(queue.hasDeferredStart)
    }
    @Test fun unknownWriteCompletionEndsTheLinkAndLateAckCannotReleaseStart() {
        val transport = FakeControlPointTransport()
        val queue = ControlPointQueue(transport)
        queue.enqueue(ProgressorCommand.tare)
        val deadline = transport.writeDeadlineID!!
        queue.enqueue(ProgressorCommand.startWeightMeasurement, StreamStartCause.initial)
        queue.writeDeadlineFired(deadline)
        assertNotNull(transport.permanentFailure)
        assertNull(transport.writeDeadlineID)
        queue.writeCompleted(null)
        assertEquals(listOf(ProgressorCommand.tare), transport.writes)
        assertFalse(queue.hasDeferredStart)
        queue.linkEstablished()
        queue.enqueue(ProgressorCommand.startWeightMeasurement, StreamStartCause.reconnect)
        val newDeadline = transport.writeDeadlineID
        queue.writeDeadlineFired(deadline)
        assertEquals(newDeadline, transport.writeDeadlineID)
    }
    @Test fun stopCancelsTheStartWaitingForTareAcknowledgement() {
        val transport = FakeControlPointTransport()
        val queue = ControlPointQueue(transport)
        queue.enqueue(ProgressorCommand.tare)
        queue.enqueue(ProgressorCommand.startWeightMeasurement, StreamStartCause.initial)
        queue.enqueue(ProgressorCommand.stopWeightMeasurement)
        queue.writeCompleted(null)
        assertEquals(listOf(ProgressorCommand.tare, ProgressorCommand.stopWeightMeasurement), transport.writes)
        assertFalse(queue.hasDeferredStart)
    }
    @Test fun failedSupersededStreamIntentIsNotRetried() {
        for ((old, replacement) in listOf(
            ProgressorCommand.startWeightMeasurement to ProgressorCommand.stopWeightMeasurement,
            ProgressorCommand.startWeightMeasurement to ProgressorCommand.enterSleep,
            ProgressorCommand.stopWeightMeasurement to ProgressorCommand.startWeightMeasurement,
        )) {
            val transport = FakeControlPointTransport()
            val queue = ControlPointQueue(transport)
            queue.enqueue(old)
            queue.enqueue(replacement)
            queue.writeCompleted("ATT failure")
            assertEquals(listOf(old, replacement), transport.writes)
            assertNull(transport.permanentFailure)
        }
    }
    @Test fun incompleteCalibrationCommandHasNoPayloadAndCannotBeQueued() {
        val transport = FakeControlPointTransport()
        val queue = ControlPointQueue(transport)
        assertNull(ProgressorCommand.addCalibrationPoint.encoded)
        queue.enqueue(ProgressorCommand.addCalibrationPoint)
        assertTrue(transport.writes.isEmpty())
        assertTrue(queue.queuedCommands.isEmpty())
    }
}
