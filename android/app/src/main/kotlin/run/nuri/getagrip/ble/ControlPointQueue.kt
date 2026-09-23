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
/// TRANSLATION NOTE: on iOS this is private state inside `LiveProgressorClient`. Every rule
/// below — serialized query channel, control-command bypass, poison latch, tare-integrity
/// ordering — is app logic, not radio logic, so this three-method seam makes it
/// JVM-testable without a `BluetoothGatt`. The live client is the only production
/// implementation.
interface ControlPointTransport {
    /// The link is up and the control point has been discovered.
    val canWrite: Boolean

    /// `withResponse` when the characteristic declares WRITE. Read once per drain, as
    /// the Swift does.
    val writeType: ControlWriteType

    /// CoreBluetooth's `canSendWriteWithoutResponse`.
    ///
    /// TRANSLATION NOTE: Nordic's `BleManager` hands a queued write to the stack only after
    /// the previous operation completes, so there is no silent discard and the live client
    /// returns true. The property survives because its RULE — one command per turn, or one
    /// is lost (first hardware session, 2026-08-03) — still holds; only the enforcer moved.
    val isReadyForWriteWithoutResponse: Boolean

    fun write(command: ProgressorCommand, withResponse: Boolean)

    /// A tare that cannot be acknowledged, or a second failure of one write. Terminal.
    fun failPermanently(reason: String)

    /// The gauge acknowledged (or is presumed to have accepted) `enterSleep`.
    fun completeSleep()

    /// Arm/disarm the 2 s reply deadline for the one outstanding query.
    fun armReplyDeadline(id: ULong)
    fun cancelReplyDeadline()
    fun armWriteDeadline(id: ULong)
    fun cancelWriteDeadline()

    /// Arm the 1 s fallback that completes a sleep the device never acknowledges.
    fun armSleepFallback(id: ULong)

    fun diagnostic(diagnostic: ProgressorClientDiagnostic)
}

/// The Tindeq control point: a paced write queue, a SERIALIZED tag-0 query channel and the
/// tare-integrity ordering, each earned by a specific hardware failure.
///
/// - **Commands are queued and paced.** The gauge takes one command per turn; the runner
///   sends `startWeight` and `tare` in the same turn.
/// - **Tag-0 replies carry no echo, so queries are serialized — one outstanding, ever.** A
///   single pending-query slot cross-paired the connect-time (version, battery) pair on
///   real hardware: the version's ASCII reply was parsed as battery MILLIVOLTS. A deeper
///   FIFO just moved the desync to dropped replies.
/// - **A ~2 s reply timeout POISONS the query channel for that physical connection.**
///   Queries refuse until reconnect, so a late reply pairs with nothing.
/// - **Control commands (tare/start/stop/sleep) bypass waiting queries**, so safety never
///   queues behind telemetry.
/// - **All reply state dies with the link.** An old connection's owed replies must never
///   pair with the next one's queries.
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

    /// Each entry keeps its identity across its single retry, so a failed query removes
    /// exactly its own pending-reply slot.
    fun enqueue(command: ProgressorCommand, startCause: StreamStartCause? = null) {
        if (!transport.canWrite || command == ProgressorCommand.addCalibrationPoint) return
        nextWriteID += 1uL
        val entry = WriteEntry(id = nextWriteID, command = command, startCause = startCause)
        if (command == ProgressorCommand.stopWeightMeasurement || command == ProgressorCommand.enterSleep) {
            deferredStart = null
            writeQueue.removeAll { it.command == ProgressorCommand.startWeightMeasurement }
        } else if (command == ProgressorCommand.startWeightMeasurement) {
            writeQueue.removeAll { it.command == ProgressorCommand.stopWeightMeasurement }
        }

        if (command == ProgressorCommand.tare) {
            // Flip the latch first; any start already waiting is pulled into the one
            // deferred slot before this tare is appended.
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

            // A waiting query must not hold control commands behind it: bypass only
            // serialized query entries, keeping non-query order.
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
            if (type == ControlWriteType.withResponse) {
                inFlightWrite = entry
                transport.armWriteDeadline(entry.id)
            }
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

    /// A `withResponse` write completed. `error` is null on success. The FIRST failure puts
    /// the entry back at the head with its identity; a second is terminal — a control point
    /// refusing the same write twice cannot be trusted by a session.
    fun writeCompleted(error: String?) {
        val entry = inFlightWrite ?: return
        inFlightWrite = null
        transport.cancelWriteDeadline()

        if (error != null) {
            val replyIndex = pendingReplies.indexOfFirst { it.id == entry.id }
            if (replyIndex >= 0) {
                pendingReplies.removeAt(replyIndex)
                transport.cancelReplyDeadline()
            }
            if (issuedSleepID == entry.id) issuedSleepID = null

            // A failed old stream intent must not delay or revive its replacement.
            val superseded = (entry.command == ProgressorCommand.startWeightMeasurement &&
                writeQueue.any { it.command == ProgressorCommand.stopWeightMeasurement || it.command == ProgressorCommand.enterSleep }) ||
                (entry.command == ProgressorCommand.stopWeightMeasurement &&
                    (deferredStart != null || writeQueue.any { it.command == ProgressorCommand.startWeightMeasurement }))
            if (superseded) { drain(); return }
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

    /// A tag-0 reply consumes the one pending query. Weight notifications do not, which is
    /// why the caller decides.
    fun commandReplyReceived() {
        if (pendingReplies.isEmpty()) return
        pendingReplies.removeFirst()
        transport.cancelReplyDeadline()
        drain()
    }

    /// The 2 s deadline for `id` expired. Only the HEAD can time out; any other deadline is
    /// stale.
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

    fun writeDeadlineFired(id: ULong) {
        if (inFlightWrite?.id != id) return
        // Completion is unknown, and a retry could let a late ACK authorize the wrong
        // command: clear this link instead.
        clearLinkState(clearDeferredStart = true)
        transport.failPermanently(L10n.tr("Gauge did not acknowledge the command. Reconnect and try again."))
    }

    // MARK: - Link lifecycle

    /// A fresh link may query again. Called when notifications are on — where Swift
    /// publishes `.connected`.
    fun linkEstablished() {
        queryChannelPoisoned = false
    }

    fun clearLinkState(clearDeferredStart: Boolean) {
        pendingReplies.clear()
        writeQueue.clear()
        inFlightWrite = null
        transport.cancelReplyDeadline()
        transport.cancelWriteDeadline()
        issuedSleepID = null
        if (clearDeferredStart) deferredStart = null
        // Link-local: a latch that survived its queue and ACK made every later start
        // impossible, since only the retired link could release it.
        tareIntegrityLatch.clearForNewLink()
    }
}

/// TRANSLATION NOTE: a `private extension ProgressorCommand` in Swift; internal here
/// because Kotlin cannot add a private extension to another module's enum.
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
