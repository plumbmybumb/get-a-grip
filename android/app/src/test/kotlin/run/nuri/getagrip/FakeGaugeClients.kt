// SPDX-License-Identifier: MPL-2.0
// Original contributions Copyright 2026 Nuri Bruner.

package run.nuri.getagrip

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import run.nuri.getagrip.ble.ControlPointTransport
import run.nuri.getagrip.ble.ControlWriteType
import run.nuri.getagrip.ble.ProgressorClient
import run.nuri.getagrip.ble.PacketBoundary
import run.nuri.getagrip.ble.ProgressorClientDiagnostic
import run.nuri.getagrip.ble.ProgressorConnectionState
import run.nuri.getagrip.ble.StreamStartCause
import run.nuri.getagrip.engine.GaugeKind
import run.nuri.getagrip.engine.ProgressorCommand
import run.nuri.getagrip.engine.ProgressorEvent

/// A client that records commands without any Bluetooth, and pushes events on demand.
///
/// TRANSLATION NOTE: iOS has two of these — `RecordingProgressorClient`, fixed to the
/// Progressor, and a private `FakeGaugeClient` that reports whatever kind a test needs.
/// They differ in nothing but that default, so Kotlin has one class with a defaulted
/// `kind`; both Swift names are kept as aliases below so a reader can follow either test
/// file across.
class RecordingProgressorClient(
    override val kind: GaugeKind = GaugeKind.progressor,
) : ProgressorClient {

    override var onEvent: ((ProgressorEvent) -> Unit)? = null
    override var onPacketBoundary: ((PacketBoundary) -> Unit)? = null
    override var onStateChange: ((ProgressorConnectionState) -> Unit)? = null
    override var onDiagnostic: ((ProgressorClientDiagnostic) -> Unit)? = null

    override var state: ProgressorConnectionState = ProgressorConnectionState.Idle
        private set

    override val deviceName: String? = "Test gauge"

    val commands = mutableListOf<ProgressorCommand>()

    override fun connect() {
        setState(ProgressorConnectionState.Connected)
    }

    override fun disconnect() {
        setState(ProgressorConnectionState.Disconnected(reason = null))
    }

    override fun send(command: ProgressorCommand) {
        commands.add(command)
    }

    override fun startStreaming(cause: StreamStartCause) {
        commands.add(ProgressorCommand.startWeightMeasurement)
        onDiagnostic?.invoke(ProgressorClientDiagnostic.StreamStartWritten(cause))
    }

    override fun sleepDevice() {
        send(ProgressorCommand.enterSleep)
    }

    fun setState(next: ProgressorConnectionState) {
        state = next
        onStateChange?.invoke(next)
    }

    fun emit(event: ProgressorEvent) {
        onEvent?.invoke(event)
    }
}

/// The Swift name the multi-gauge tests use. Same object, different sentence.
fun FakeGaugeClient(kind: GaugeKind): RecordingProgressorClient = RecordingProgressorClient(kind)

/// A scope whose `delay` never fires unless a test advances it, so the store's 500 ms
/// freshness watchdog and every deadline job stay armed and inert. The iOS tests get this
/// for free: an `XCTestCase` that never awaits simply never runs a `Task.sleep`.
@OptIn(ExperimentalCoroutinesApi::class)
fun inertScope(): CoroutineScope = CoroutineScope(UnconfinedTestDispatcher())

/// A wall/uptime clock a test drives by hand.
class FakeClock(var wall: Double = 1_000.0, var uptime: Double = 10.0) :
    run.nuri.getagrip.ble.HostClock {
    override fun wallSeconds(): Double = wall
    override fun uptimeSeconds(): Double = uptime
}

/// Records what the control-point queue asked the radio to do. The JVM twin of driving a
/// `CBPeripheral` from a test, which the Simulator could never do at all.
class FakeControlPointTransport(
    override var canWrite: Boolean = true,
    override var writeType: ControlWriteType = ControlWriteType.withResponse,
) : ControlPointTransport {
    override val isReadyForWriteWithoutResponse: Boolean = true

    val writes = mutableListOf<ProgressorCommand>()
    val diagnostics = mutableListOf<ProgressorClientDiagnostic>()
    var permanentFailure: String? = null
    var sleepCompleted = false

    override fun write(command: ProgressorCommand, withResponse: Boolean) {
        writes.add(command)
    }

    override fun failPermanently(reason: String) {
        permanentFailure = reason
    }

    override fun completeSleep() {
        sleepCompleted = true
    }

    override fun armReplyDeadline(id: ULong) = Unit
    var writeDeadlineID: ULong? = null
    override fun armWriteDeadline(id: ULong) { writeDeadlineID = id }
    override fun cancelWriteDeadline() { writeDeadlineID = null }
    override fun cancelReplyDeadline() = Unit
    override fun armSleepFallback(id: ULong) = Unit

    override fun diagnostic(diagnostic: ProgressorClientDiagnostic) {
        diagnostics.add(diagnostic)
    }
}
