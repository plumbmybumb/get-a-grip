// SPDX-License-Identifier: MPL-2.0
// Original contributions Copyright 2026 Nuri Bruner.

package run.nuri.getagrip.ble

import run.nuri.getagrip.engine.L10n
import run.nuri.getagrip.engine.ProgressorCommand

/// How the control point wants to be written to.
enum class ControlWriteType {
    /// The characteristic declares WRITE, so every write is acknowledged. This is the
    /// only mode a tare may be issued in — see `ControlPointQueue.drain`.
    withResponse,

    /// WRITE_NO_RESPONSE only.
    withoutResponse,
}

/// What the queue needs from the radio, and nothing else.
///
/// TRANSLATION NOTE: on iOS this whole file is private state inside
/// `LiveProgressorClient`, because `CBPeripheral` is directly reachable from the same
/// main-actor class and a Simulator test could never exercise any of it anyway. Android
/// has real JVM unit tests, and every rule below — the serialized query channel, the
/// bypass for control commands, the poison latch, the tare-integrity ordering — is app
/// logic rather than radio logic. Splitting it behind this three-method seam is what
/// makes it testable without a `BluetoothGatt`; the live client is the only production
/// implementation.
interface ControlPointTransport {
    /// The link is up and the control point has been discovered.
    val canWrite: Boolean

    /// `withResponse` when the characteristic declares WRITE. Read once per drain, as
    /// the Swift does.
    val writeType: ControlWriteType

    /// CoreBluetooth's `canSendWriteWithoutResponse`.
    ///
    /// TRANSLATION NOTE: Nordic's `BleManager` owns the ATT-level pacing — a queued
    /// `WriteRequest` is not handed to the stack until the previous operation has
    /// completed, so there is no "buffer not ready, write silently discarded" failure to
    /// guard against and the live client returns true here. The property survives because
    /// the RULE it encodes is the one the first hardware session taught (2026-08-03): a
    /// gauge takes one command per turn, and firing two in one runloop turn loses one.
    /// Who enforces it moved; that it is enforced did not.
    val isReadyForWriteWithoutResponse: Boolean

    fun write(command: ProgressorCommand, withResponse: Boolean)

    /// A tare that cannot be acknowledged, or a second failure of one write. Terminal.
    fun failPermanently(reason: String)

    /// The gauge acknowledged (or is presumed to have accepted) `enterSleep`.
    fun completeSleep()

    /// Arm/disarm the 2 s reply deadline for the one outstanding query.
    fun armReplyDeadline(id: ULong)
    fun cancelReplyDeadline()

    /// Arm the 1 s fallback that completes a sleep the device never acknowledges.
    fun armSleepFallback(id: ULong)

    fun diagnostic(diagnostic: ProgressorClientDiagnostic)
}

/// The Tindeq control point: a paced write queue, a SERIALIZED tag-0 query channel and
/// the tare-integrity ordering, all of which were earned by specific hardware failures.
///
/// - **Commands are queued and paced, never written straight through.** The gauge takes
///   one command per turn; the runner sends `startWeight` and `tare` in the same turn.
/// - **Tag-0 replies carry no echo of the command they answer, so queries are
///   serialized — one outstanding, ever.** A single pending-query slot cross-paired the
///   connect-time (version, battery) pair on real hardware: the version's ASCII reply was
///   parsed as battery MILLIVOLTS. A deeper FIFO just moved the same desync to dropped
///   replies.
/// - **A ~2 s reply timeout POISONS the query channel for that physical connection.**
///   Queries refuse until reconnect, so a late reply then pairs with nothing.
/// - **Control commands (tare/start/stop/sleep) bypass waiting queries**, so safety never
///   queues behind telemetry.
/// - **All reply state dies with the link.** Replies owed by an old connection must never
///   pair against the next one's queries.
class ControlPointQueue(private val transport: ControlPointTransport) {

    class WriteEntry(
        val id: ULong,
        val command: ProgressorCommand,
        val startCause: StreamStartCause?,
        var retryCount: Int = 0,
    )

    private class PendingReply(val id: ULong, val command: ProgressorCommand)

    private val writeQueue = ArrayDeque<WriteEntry>()
    private var inFlightWrite: WriteEntry? = null
    private var nextWriteID: ULong = 0uL

    private val pendingReplies = ArrayDeque<PendingReply>()
    private var queryChannelPoisoned = false

    private val tareIntegrityLatch = TareIntegrityLatch()
    private var deferredStart: WriteEntry? = null

    var issuedSleepID: ULong? = null
        private set

    /// The command the one outstanding query is waiting on — what `ProgressorCodec.decode`
    /// needs as `answering`, since tag 0 carries no echo of what it answers.
    val pendingReplyCommand: ProgressorCommand?
        get() = pendingReplies.firstOrNull()?.command

    /// Test/diagnostic windows into the state the rules above are about.
    val isQueryChannelPoisoned: Boolean get() = queryChannelPoisoned
    val hasDeferredStart: Boolean get() = deferredStart != null
    val queuedCommands: List<ProgressorCommand> get() = writeQueue.map { it.command }

    // MARK: - Enqueue

    /// Each entry keeps its identity across its single retry so a failed query can remove
    /// exactly its own pending-reply slot without shifting the FIFO.
    fun enqueue(command: ProgressorCommand, startCause: StreamStartCause? = null) {
        nextWriteID += 1uL
        val entry = WriteEntry(id = nextWriteID, command = command, startCause = startCause)

        if (command == ProgressorCommand.tare) {
            // Flip the latch before touching the queue. Any start already waiting to
            // drain is pulled into the one deferred slot before this tare is appended.
            tareIntegrityLatch.tareEnqueued(entry.id)
            for (queued in writeQueue) {
                if (queued.command != ProgressorCommand.startWeightMeasurement) continue
                deferredStart = queued
                queued.startCause?.let {
                    transport.diagnostic(ProgressorClientDiagnostic.StreamStartDeferred(it))
                }
            }
            writeQueue.removeAll { it.command == ProgressorCommand.startWeightMeasurement }
        } else if (command == ProgressorCommand.startWeightMeasurement &&
            tareIntegrityLatch.startDecision == TareStartDecision.deferred
        ) {
            deferredStart = entry // latest wins
            startCause?.let {
                transport.diagnostic(ProgressorClientDiagnostic.StreamStartDeferred(it))
            }
            return
        }

        if (command.expectsResponse && queryChannelPoisoned) return
        writeQueue.addLast(entry)
        drain()
    }

    // MARK: - Drain

    fun drain() {
        if (!transport.canWrite) return
        val type = transport.writeType

        while (writeQueue.isNotEmpty()) {
            if (queryChannelPoisoned) {
                writeQueue.removeAll { it.command.expectsResponse }
                if (writeQueue.isEmpty()) return
            }

            // A waiting query must not hold safety/control commands behind it. Keep
            // non-query order intact while bypassing only the serialized query entries.
            val candidateIndex: Int
            if (pendingReplies.isEmpty()) {
                candidateIndex = 0
            } else {
                val firstControl = writeQueue.indexOfFirst { !it.command.expectsResponse }
                if (firstControl < 0) return
                candidateIndex = firstControl
            }
            val candidate = writeQueue[candidateIndex]

            if (candidate.command == ProgressorCommand.tare &&
                type != ControlWriteType.withResponse
            ) {
                transport.failPermanently(
                    L10n.tr("Gauge control point cannot acknowledge tare writes"),
                )
                return
            }

            if (type == ControlWriteType.withResponse) {
                if (inFlightWrite != null) return
            } else {
                if (!transport.isReadyForWriteWithoutResponse) return
            }

            val entry = writeQueue.removeAt(candidateIndex)
            if (entry.command.expectsResponse) {
                pendingReplies.addLast(PendingReply(entry.id, entry.command))
                transport.armReplyDeadline(entry.id)
            }
            if (type == ControlWriteType.withResponse) inFlightWrite = entry
            if (entry.command == ProgressorCommand.enterSleep) issuedSleepID = entry.id

            transport.write(entry.command, type == ControlWriteType.withResponse)
            if (entry.command == ProgressorCommand.startWeightMeasurement) {
                entry.startCause?.let {
                    transport.diagnostic(ProgressorClientDiagnostic.StreamStartWritten(it))
                }
            }

            if (type == ControlWriteType.withResponse) return
            if (entry.command == ProgressorCommand.enterSleep) {
                transport.armSleepFallback(entry.id)
                return
            }
        }
    }

    // MARK: - Acknowledgements

    /// A `withResponse` write completed — the exact entry is either acknowledged or
    /// reinserted at the front once, preserving command order across the retry.
    ///
    /// `error` is nil on success. On the FIRST failure the entry goes back to the head of
    /// the queue with its identity intact; a second failure is terminal, because a control
    /// point that refuses the same write twice is not a link a session can trust.
    fun writeCompleted(error: String?) {
        val entry = inFlightWrite ?: return
        inFlightWrite = null

        if (error != null) {
            val replyIndex = pendingReplies.indexOfFirst { it.id == entry.id }
            if (replyIndex >= 0) {
                pendingReplies.removeAt(replyIndex)
                transport.cancelReplyDeadline()
            }
            if (issuedSleepID == entry.id) issuedSleepID = null

            if (entry.retryCount == 0) {
                entry.retryCount = 1
                writeQueue.addFirst(entry)
                drain()
            } else {
                transport.failPermanently(
                    L10n.tr(
                        "Write failed twice for %s: %s",
                        entry.command.writeFailureName,
                        error,
                    ),
                )
            }
            return
        }

        if (entry.command == ProgressorCommand.tare &&
            tareIntegrityLatch.tareAcknowledged(entry.id)
        ) {
            deferredStart?.let {
                deferredStart = null
                writeQueue.addFirst(it)
            }
        }

        if (entry.command == ProgressorCommand.enterSleep) {
            transport.completeSleep()
            return
        }
        drain()
    }

    /// A tag-0 reply landed: it consumes the one pending query. Weight notifications do
    /// not, which is why the caller decides and this is not folded into the decode.
    fun commandReplyReceived() {
        if (pendingReplies.isEmpty()) return
        pendingReplies.removeFirst()
        transport.cancelReplyDeadline()
        drain()
    }

    /// The 2 s deadline for `id` expired. Only the HEAD of the queue can time out — a
    /// deadline belonging to anything else is stale and ignored.
    fun replyDeadlineFired(id: ULong) {
        if (pendingReplies.firstOrNull()?.id != id) return
        pendingReplies.removeFirst()
        queryChannelPoisoned = true
        writeQueue.removeAll { it.command.expectsResponse }
        drain()
    }

    /// The buffer drained, or Nordic's queue came free.
    fun readyForWriteWithoutResponse() {
        drain()
    }

    // MARK: - Link lifecycle

    /// A fresh link may query again. Called when notifications are confirmed on, which is
    /// the moment the Swift publishes `.connected`.
    fun linkEstablished() {
        queryChannelPoisoned = false
    }

    fun clearLinkState(clearDeferredStart: Boolean) {
        pendingReplies.clear()
        writeQueue.clear()
        inFlightWrite = null
        transport.cancelReplyDeadline()
        issuedSleepID = null
        if (clearDeferredStart) deferredStart = null
        // This latch is link-local: preserving it after the queue and its ACK died made
        // every later start impossible, because only the retired link could release it.
        tareIntegrityLatch.clearForNewLink()
    }
}

/// TRANSLATION NOTE: Swift keeps this as a `private extension ProgressorCommand` beside
/// the client. Kotlin has no private extensions on an enum from another module, so it is
/// an internal extension property here — same text, same single call site.
internal val ProgressorCommand.writeFailureName: String
    get() = when (this) {
        ProgressorCommand.tare -> L10n.tr("tare")
        ProgressorCommand.startWeightMeasurement -> L10n.tr("start measurement")
        ProgressorCommand.stopWeightMeasurement -> L10n.tr("stop measurement")
        ProgressorCommand.startPeakRFDMeasurement -> L10n.tr("peak RFD measurement")
        ProgressorCommand.startPeakRFDSeries -> L10n.tr("RFD series measurement")
        ProgressorCommand.addCalibrationPoint -> L10n.tr("calibration point")
        ProgressorCommand.saveCalibration -> L10n.tr("calibration save")
        ProgressorCommand.getAppVersion -> L10n.tr("version query")
        ProgressorCommand.getErrorInformation -> L10n.tr("error query")
        ProgressorCommand.clearErrorInformation -> L10n.tr("error clear")
        ProgressorCommand.enterSleep -> L10n.tr("sleep")
        ProgressorCommand.getBatteryVoltage -> L10n.tr("battery query")
    }
