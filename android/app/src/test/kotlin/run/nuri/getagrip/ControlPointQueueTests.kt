// SPDX-License-Identifier: MPL-2.0
// Original contributions Copyright 2026 Nuri Bruner.

package run.nuri.getagrip

import run.nuri.getagrip.ble.ControlPointQueue
import run.nuri.getagrip.ble.ControlWriteType
import run.nuri.getagrip.engine.ProgressorCommand
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/// The Tindeq control point's own rules, which have no Swift test file of their own
/// because on iOS they are private state inside `LiveProgressorClient` and the Simulator
/// has no CoreBluetooth to drive them with.
///
/// They are ALL rules that hardware taught, each one written up in the root CLAUDE.md, and
/// splitting them behind `ControlPointTransport` is what finally makes them assertable —
/// which is the whole reason the split exists. Every case below is a failure that actually
/// happened on a real Progressor.
class ControlPointQueueTests {

    /// **Tag-0 replies carry no echo of the command they answer, so queries are
    /// SERIALIZED — one outstanding, ever.** A single pending-query slot cross-paired the
    /// connect-time (version, battery) pair on real hardware: the version's ASCII reply was
    /// parsed as battery MILLIVOLTS.
    @Test
    fun onlyOneQueryIsEverOutstanding() {
        val transport = FakeControlPointTransport()
        val queue = ControlPointQueue(transport)

        queue.enqueue(ProgressorCommand.getAppVersion)
        queue.enqueue(ProgressorCommand.getBatteryVoltage)

        assertEquals(listOf(ProgressorCommand.getAppVersion), transport.writes)
        assertEquals(ProgressorCommand.getAppVersion, queue.pendingReplyCommand)

        // The write is acknowledged, but the REPLY is what frees the channel — an ACK only
        // says the bytes left the phone.
        queue.writeCompleted(error = null)
        assertEquals(listOf(ProgressorCommand.getAppVersion), transport.writes)

        queue.commandReplyReceived()
        assertEquals(
            listOf(ProgressorCommand.getAppVersion, ProgressorCommand.getBatteryVoltage),
            transport.writes,
        )
    }

    /// **Control commands bypass waiting queries, so safety never queues behind
    /// telemetry.** Non-query order is otherwise untouched.
    @Test
    fun aWaitingQueryDoesNotHoldBackAControlCommand() {
        val transport = FakeControlPointTransport()
        val queue = ControlPointQueue(transport)

        queue.enqueue(ProgressorCommand.getBatteryVoltage)
        queue.writeCompleted(error = null) // sent, still owed a reply
        queue.enqueue(ProgressorCommand.stopWeightMeasurement)

        assertEquals(
            listOf(ProgressorCommand.getBatteryVoltage, ProgressorCommand.stopWeightMeasurement),
            transport.writes,
            "a stop must not wait behind a battery reading",
        )
    }

    /// **A ~2 s reply timeout POISONS the query channel for that physical connection**, so a
    /// late reply pairs with nothing. It is cleared only by a new link.
    @Test
    fun aTimedOutReplyPoisonsTheQueryChannelUntilTheNextLink() {
        val transport = FakeControlPointTransport()
        val queue = ControlPointQueue(transport)

        queue.enqueue(ProgressorCommand.getAppVersion)
        queue.writeCompleted(error = null)
        queue.replyDeadlineFired(1uL)
        assertTrue(queue.isQueryChannelPoisoned)

        queue.enqueue(ProgressorCommand.getBatteryVoltage)
        assertEquals(
            listOf(ProgressorCommand.getAppVersion),
            transport.writes,
            "queries refuse until reconnect",
        )

        // Control commands are unaffected: the poison is about replies, not about writing.
        queue.enqueue(ProgressorCommand.tare)
        assertEquals(
            listOf(ProgressorCommand.getAppVersion, ProgressorCommand.tare),
            transport.writes,
        )

        queue.clearLinkState(clearDeferredStart = true)
        queue.linkEstablished()
        assertFalse(queue.isQueryChannelPoisoned)
        queue.enqueue(ProgressorCommand.getBatteryVoltage)
        assertEquals(
            listOf(
                ProgressorCommand.getAppVersion,
                ProgressorCommand.tare,
                ProgressorCommand.getBatteryVoltage,
            ),
            transport.writes,
        )
    }

    /// **A tare must be an ACKNOWLEDGED write.** Taring under load corrupts every reading
    /// after it, so a control point that cannot confirm the tare landed is a link this app
    /// refuses rather than one it guesses on.
    @Test
    fun aTareIsRefusedOnAControlPointThatCannotAcknowledge() {
        val transport = FakeControlPointTransport(writeType = ControlWriteType.withoutResponse)
        val queue = ControlPointQueue(transport)

        queue.enqueue(ProgressorCommand.tare)

        assertTrue(transport.writes.isEmpty())
        assertNotNull(transport.permanentFailure)
    }

    /// A failed write is retried EXACTLY once, keeping its place at the head of the queue so
    /// command order survives the retry; the second failure is terminal.
    @Test
    fun aFailedWriteIsRetriedOnceAndThenGivesUp() {
        val transport = FakeControlPointTransport()
        val queue = ControlPointQueue(transport)

        queue.enqueue(ProgressorCommand.tare)
        assertEquals(listOf(ProgressorCommand.tare), transport.writes)

        queue.writeCompleted(error = "boom")
        assertEquals(
            listOf(ProgressorCommand.tare, ProgressorCommand.tare),
            transport.writes,
            "the same entry goes back to the head of the queue",
        )

        queue.writeCompleted(error = "boom")
        assertNotNull(transport.permanentFailure)
    }

    /// The latch is LINK-LOCAL: a tare enqueued but never ACKed blocks the start on the
    /// connection that owns both, and link cleanup resets it. The old surviving latch made
    /// an untared stream impossible by making EVERY stream impossible after one badly-timed
    /// drop.
    @Test
    fun aDeferredStartDiesWithItsLinkRatherThanBlockingTheNextOne() {
        val transport = FakeControlPointTransport()
        val queue = ControlPointQueue(transport)

        queue.enqueue(ProgressorCommand.tare)
        queue.enqueue(ProgressorCommand.startWeightMeasurement)
        assertTrue(queue.hasDeferredStart)

        // The ACK never arrives; the link goes away instead.
        queue.clearLinkState(clearDeferredStart = true)
        assertFalse(queue.hasDeferredStart)

        queue.enqueue(ProgressorCommand.startWeightMeasurement)
        assertEquals(
            listOf(ProgressorCommand.tare, ProgressorCommand.startWeightMeasurement),
            transport.writes,
            "the next link writes its own start",
        )
    }
}
