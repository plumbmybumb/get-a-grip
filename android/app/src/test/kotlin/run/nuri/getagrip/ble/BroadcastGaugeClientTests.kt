// SPDX-License-Identifier: MPL-2.0
// Original contributions Copyright 2026 Nuri Bruner.

package run.nuri.getagrip.ble

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import run.nuri.getagrip.engine.ForceSample
import run.nuri.getagrip.engine.ProgressorCommand
import run.nuri.getagrip.engine.ProgressorEvent
import run.nuri.getagrip.engine.SyntheticSampleClock
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotSame
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/// Drives the REAL broadcast client, not DeviceStore's already-connected fake. No radio,
/// wall-clock sleep, or native framework mocks are needed to reproduce a wedged scanner.
@OptIn(ExperimentalCoroutinesApi::class)
class BroadcastGaugeClientTests {
    private lateinit var scheduler: TestCoroutineScheduler
    private lateinit var scope: CoroutineScope
    private lateinit var clock: HostClock
    private lateinit var transport: FakeBroadcastScanTransport
    private lateinit var client: BroadcastGaugeClient
    private val samples = mutableListOf<ForceSample>()
    private val states = mutableListOf<ProgressorConnectionState>()
    private val diagnostics = mutableListOf<ProgressorClientDiagnostic>()

    @BeforeEach fun setup() {
        scheduler = TestCoroutineScheduler()
        val dispatcher = UnconfinedTestDispatcher(scheduler)
        Dispatchers.setMain(dispatcher)
        scope = CoroutineScope(SupervisorJob() + dispatcher)
        clock = object : HostClock {
            override fun uptimeSeconds() = 100.0 + scheduler.currentTime / 1_000.0
            override fun wallSeconds() = 1_000.0 + scheduler.currentTime / 1_000.0
        }
        transport = FakeBroadcastScanTransport()
        client = BroadcastGaugeClient(transport, scope, clock)
        client.onEvent = { if (it is ProgressorEvent.Sample) samples += it.sample }
        client.onStateChange = { states += it }
        client.onDiagnostic = { diagnostics += it }
    }

    @AfterEach fun teardown() {
        client.disconnect()
        scope.cancel()
        Dispatchers.resetMain()
    }

    private fun advance(millis: Long) {
        scheduler.advanceTimeBy(millis)
        scheduler.runCurrent()
    }

    private fun advertisement(
        kg: Double = 10.0,
        address: String = "scale-a",
        name: String? = "Test scale",
        at: Double = clock.uptimeSeconds(),
    ): BroadcastAdvertisement {
        val hundredths = (kg * 100).toInt()
        val payload = ByteArray(12)
        payload[10] = (hundredths shr 8).toByte()
        payload[11] = hundredths.toByte()
        return BroadcastAdvertisement(address, name, payload, at)
    }

    private fun emit(kg: Double = 10.0, listener: BroadcastScanTransport.Listener = transport.starts.last()) {
        listener.onAdvertisement(advertisement(kg))
    }

    private fun connectAndEmit(kg: Double = 10.0) {
        client.connect()
        emit(kg)
        assertTrue(client.state.isConnected)
    }

    @Test fun healthyStartsStopsAndTareNeverBounceScanOrInventWrites() {
        connectAndEmit(4.0)
        val listener = transport.starts.single()
        client.send(ProgressorCommand.tare)
        StreamStartCause.entries.forEach(client::startStreaming)
        client.send(ProgressorCommand.stopWeightMeasurement)
        advance(300)
        emit(9.0)
        assertEquals(5.0, samples.last().kg)
        assertEquals(1, transport.starts.size)
        assertTrue(transport.stops.isEmpty())
        assertSame(listener, transport.starts.single())
        assertFalse(diagnostics.any { it is ProgressorClientDiagnostic.StreamStartWritten })
        assertEquals(SyntheticSampleClock.micros(clock.uptimeSeconds()), samples.last().deviceMicros)
        assertTrue(samples.last().isBatchStart)
    }

    @Test fun pendingScanDiagnosticsNeverClaimToBeWaitingForAHardwareTare() {
        client.startStreaming(StreamStartCause.manualWake)
        client.connect()
        transport.starts.single().onFailure(6)
        client.startStreaming(StreamStartCause.manualWake)
        transport.changeRadio(ProgressorConnectionState.BluetoothOff)
        client.startStreaming(StreamStartCause.manualWake)
        assertFalse(diagnostics.any { it is ProgressorClientDiagnostic.StreamStartDeferred })
        assertFalse(diagnostics.any { it is ProgressorClientDiagnostic.StreamStartWritten })
        val events = diagnostics.filterIsInstance<ProgressorClientDiagnostic.BroadcastScan>().map { it.event }
        assertTrue(events.any { "retry pending" in it })
        assertTrue(events.any { "radio unavailable" in it })
    }

    @Test fun ordinaryBroadcastGapsPreserveTheScanAndTare() {
        connectAndEmit(3.0)
        client.send(ProgressorCommand.tare)
        advance(3_500)
        client.startStreaming(StreamStartCause.watchdog)
        advance(6_400)
        assertTrue(client.state.isConnected)
        emit(8.0)
        advance(9_900)
        client.startStreaming(StreamStartCause.manualWake)
        emit(9.0)
        assertEquals(6.0, samples.last().kg)
        assertEquals(1, transport.starts.size)
        assertTrue(transport.stops.isEmpty())
    }

    @Test fun fiveHealthyRenewalsPreserveTareHandTimingAndConnectionWithoutFakeSamples() {
        connectAndEmit(3.0)
        client.send(ProgressorCommand.tare)
        val leaseSeconds = (BroadcastGaugeClient.healthyScanRenewalMillis / 1_000).toInt()
        repeat(leaseSeconds * 5) { index ->
            val second = index + 1
            val previous = transport.starts.last()
            val lastMicros = samples.last().deviceMicros
            advance(1_000)
            assertEquals(second, samples.size, "Renewal cannot emit a made-up force reading")
            if (second % leaseSeconds == 0) {
                assertNotSame(previous, transport.starts.last())
                // Retired callbacks and another scale still cannot contaminate this link.
                previous.onAdvertisement(advertisement(100.0))
                transport.starts.last().onAdvertisement(advertisement(50.0, address = "other-scale"))
                assertEquals(second, samples.size)
            }
            emit(8.0)
            assertEquals(5.0, samples.last().kg, "Software tare must survive every healthy renewal")
            assertEquals(1_000_000u, samples.last().deviceMicros - lastMicros)
            assertEquals(1 + second / leaseSeconds, transport.starts.size)
        }
        assertEquals(6, transport.starts.size, "Healthy renewal cannot consume an acquisition retry budget")
        assertEquals(5, transport.stops.size)
        assertEquals(listOf(ProgressorConnectionState.Scanning, ProgressorConnectionState.Connected), states)
        assertFalse(diagnostics.any { it is ProgressorClientDiagnostic.StreamStartWritten })
    }

    @Test fun renewalStartsFromActualRegistrationTimeAndOldTimersDieOnDisconnect() {
        connectAndEmit()
        repeat(120) { advance(1_000); emit() }
        client.disconnect()
        client.connect()
        emit()
        repeat(120) { advance(1_000); emit() }
        assertEquals(2, transport.starts.size, "The retired scan's four-minute timer cannot renew this scan")
        repeat(120) { advance(1_000); emit() }
        assertEquals(3, transport.starts.size, "Replacement renews four minutes after its own start")
        client.disconnect()
        advance(BroadcastGaugeClient.healthyScanRenewalMillis * 2)
        assertEquals(3, transport.starts.size)
    }

    @Test fun renewalRateLimitFailureBreaksConnectionAndHonorsCooldown() {
        connectAndEmit(3.0)
        client.send(ProgressorCommand.tare)
        transport.onStart = { if (transport.starts.size == 2) it.onFailure(6) }
        repeat(239) { advance(1_000); emit(8.0) }
        val previous = transport.starts.last()
        advance(1_000)
        assertEquals(2, transport.starts.size)
        assertTrue(states[states.lastIndex - 1] is ProgressorConnectionState.Disconnected)
        assertEquals(ProgressorConnectionState.Scanning, client.state)
        assertNull(client.deviceName)
        client.startStreaming(StreamStartCause.manualWake)
        advance(30_999)
        assertEquals(2, transport.starts.size)
        advance(1)
        assertEquals(3, transport.starts.size)
        previous.onFailure(6)
        emit(8.0)
        assertTrue(client.state.isConnected)
        assertEquals(8.0, samples.last().kg, "A failed registration is a real link break, unlike healthy renewal")
    }

    @Test fun throwingRenewalRegistrationUsesRealLossAndBoundedRecovery() {
        connectAndEmit(3.0)
        client.send(ProgressorCommand.tare)
        transport.onStart = { if (transport.starts.size == 2) throw IllegalStateException("Scanner unavailable") }
        repeat(239) { advance(1_000); emit(8.0) }
        advance(1_000)
        assertTrue(states[states.lastIndex - 1] is ProgressorConnectionState.Disconnected)
        assertEquals(ProgressorConnectionState.Scanning, client.state)
        advance(1_999)
        assertEquals(2, transport.starts.size)
        advance(1)
        assertEquals(3, transport.starts.size)
        emit(8.0)
        assertEquals(8.0, samples.last().kg)
    }

    @Test fun silentRenewalStillUsesTheExistingTenSecondLossWatchdog() {
        connectAndEmit(3.0)
        client.send(ProgressorCommand.tare)
        repeat(239) { advance(1_000); emit(8.0) }
        advance(1_000) // registration renewed; no new advertisement arrives
        assertEquals(2, transport.starts.size)
        assertTrue(client.state.isConnected)
        advance(9_000) // ten seconds since the last actual sample
        assertEquals(3, transport.starts.size)
        assertTrue(states[states.lastIndex - 1] is ProgressorConnectionState.Disconnected)
        assertEquals(ProgressorConnectionState.Scanning, client.state)
        emit(8.0)
        assertEquals(8.0, samples.last().kg)
    }

    @Test fun radioOffCancelsHealthyRenewal() {
        connectAndEmit()
        transport.changeRadio(ProgressorConnectionState.BluetoothOff)
        advance(BroadcastGaugeClient.healthyScanRenewalMillis * 2)
        assertEquals(1, transport.starts.size)
        assertEquals(ProgressorConnectionState.BluetoothOff, client.state)
        transport.changeRadio(ProgressorConnectionState.Idle)
        emit()
        assertEquals(2, transport.starts.size)
    }

    @Test fun tenSecondSilenceActuallyReplacesScanThenReceivesAgain() {
        connectAndEmit(3.0)
        client.send(ProgressorCommand.tare)
        val previous = transport.starts.single()
        advance(10_000)
        assertEquals(listOf(previous), transport.stops)
        assertEquals(2, transport.starts.size)
        assertNotSame(previous, transport.starts.last())
        assertEquals(ProgressorConnectionState.Scanning, client.state)
        assertTrue(states[states.lastIndex - 1] is ProgressorConnectionState.Disconnected)
        emit(8.0)
        assertTrue(client.state.isConnected)
        assertEquals(8.0, samples.last().kg, "A real link break resets software tare")
        advance(10_000)
        assertEquals(3, transport.starts.size, "The recovered link has a fresh watchdog")
    }

    @Test fun aSilentRecoveryHasDeadlinesAndEventuallyAllowsAnotherConnect() {
        connectAndEmit()
        advance(10_000) // recovery 1
        advance(15_000) // timeout, retry in 2s
        advance(2_000) // recovery 2
        advance(15_000)
        advance(5_000) // recovery 3
        advance(15_000)
        assertEquals(4, transport.starts.size)
        assertTrue(client.state is ProgressorConnectionState.Disconnected)
        assertNull(transport.radioObserver)
        advance(100_000)
        assertEquals(4, transport.starts.size, "Exhausted retries stay stopped")
        client.connect()
        assertEquals(5, transport.starts.size)
        emit()
        assertTrue(client.state.isConnected)
    }

    @Test fun initialNoResultsAlsoStopsAfterBoundedAttempts() {
        client.connect()
        advance(15_000)
        advance(2_000)
        advance(15_000)
        advance(5_000)
        advance(15_000)
        assertEquals(3, transport.starts.size)
        assertEquals(3, transport.stops.size)
        assertTrue(client.state is ProgressorConnectionState.Disconnected)
        client.connect()
        assertEquals(4, transport.starts.size)
    }

    @Test fun startupErrorsRetryWithBackoffThenLeaveConnectUsable() {
        client.connect()
        transport.starts.last().onFailure(3)
        advance(1_999)
        assertEquals(1, transport.starts.size)
        advance(1)
        transport.starts.last().onFailure(2)
        advance(4_999)
        assertEquals(2, transport.starts.size)
        advance(1)
        transport.starts.last().onFailure(5)
        assertTrue(client.state is ProgressorConnectionState.Disconnected)
        assertEquals(3, transport.stops.size)
        client.connect()
        assertEquals(4, transport.starts.size)
        emit()
        assertTrue(client.state.isConnected)
        assertTrue(diagnostics.filterIsInstance<ProgressorClientDiagnostic.BroadcastScan>()
            .any { "code 5" in it.event })
    }

    @Test fun rateLimitErrorWaitsThirtyOneSecondsEvenAcrossManualReconnect() {
        client.connect()
        transport.starts.single().onFailure(6)
        client.disconnect()
        client.connect()
        client.startStreaming(StreamStartCause.manualWake)
        advance(30_999)
        assertEquals(1, transport.starts.size)
        assertEquals(ProgressorConnectionState.Scanning, client.state)
        advance(1)
        assertEquals(2, transport.starts.size)
        emit()
        assertTrue(client.state.isConnected)
    }

    @Test fun rateLimitFailureAfterConnectedBreaksTheLinkAndClearsTareBeforeRecovery() {
        connectAndEmit(3.0)
        client.send(ProgressorCommand.tare)
        advance(1)
        emit(8.0)
        assertEquals(5.0, samples.last().kg)
        val retired = transport.starts.single()
        states.clear()

        retired.onFailure(6)
        assertEquals(listOf(retired), transport.stops)
        assertEquals(2, states.size)
        assertTrue(states.first() is ProgressorConnectionState.Disconnected)
        assertEquals(ProgressorConnectionState.Scanning, states.last())
        assertNull(client.deviceName)
        client.startStreaming(StreamStartCause.manualWake)
        advance(30_999)
        assertEquals(1, transport.starts.size, "A connected scan failure still observes the full cooldown")
        advance(1)
        assertEquals(2, transport.starts.size)

        retired.onAdvertisement(advertisement(100.0))
        assertEquals(2, samples.size, "A retired callback cannot reconnect during recovery")
        emit(8.0)
        assertTrue(client.state.isConnected)
        assertEquals(8.0, samples.last().kg, "The actual lost link's tare cannot carry into its replacement")
        advance(11_000)
        assertEquals(3, transport.starts.size, "The recovered link starts a new silence watchdog")
    }

    @Test fun fastConnectTapsAndForegroundCyclesCannotExceedScanQuota() {
        repeat(4) {
            client.connect()
            client.disconnect()
        }
        repeat(8) {
            client.connect()
            client.disconnect()
        }
        client.connect()
        advance(30_999)
        assertEquals(4, transport.starts.size)
        advance(1)
        assertEquals(5, transport.starts.size)
    }

    @Test fun disconnectCancelsPendingRetryAndLateRadioEvents() {
        client.connect()
        val oldObserver = transport.radioObserver!!
        transport.starts.last().onFailure(3)
        client.disconnect()
        oldObserver()
        advance(100_000)
        assertEquals(1, transport.starts.size)
        assertTrue(client.state is ProgressorConnectionState.Disconnected)
    }

    @Test fun disconnectCancelsActiveDeadlineAndSilenceWatchdog() {
        connectAndEmit()
        client.disconnect()
        advance(100_000)
        assertEquals(1, transport.starts.size)
        client.connect()
        client.disconnect() // this scan is waiting for its first frame
        advance(100_000)
        assertEquals(2, transport.starts.size)
    }

    @Test fun radioOffCancelsRetriesAndRadioOnResumesOnlyExistingIntent() {
        client.connect()
        transport.starts.single().onFailure(3)
        transport.changeRadio(ProgressorConnectionState.BluetoothOff)
        advance(50_000)
        assertEquals(1, transport.starts.size)
        assertEquals(ProgressorConnectionState.BluetoothOff, client.state)
        transport.changeRadio(ProgressorConnectionState.Idle)
        assertEquals(2, transport.starts.size)
        emit()
        val observer = transport.radioObserver!!
        client.disconnect()
        observer()
        assertEquals(2, transport.starts.size)
    }

    @Test fun redundantRadioOnDoesNotBounceHealthyScan() {
        connectAndEmit()
        repeat(3) { transport.changeRadio(ProgressorConnectionState.Idle) }
        assertEquals(1, transport.starts.size)
    }

    @Test fun retiredCallbacksCannotReconnectOrBreakTheNewLink() {
        connectAndEmit(3.0)
        val previous = transport.starts.single()
        client.disconnect()
        advance(1)
        client.connect()
        previous.onAdvertisement(advertisement(70.0))
        previous.onFailure(6)
        assertEquals(1, samples.size)
        assertEquals(ProgressorConnectionState.Scanning, client.state)
        emit(4.0)
        client.send(ProgressorCommand.tare)
        advance(1)
        previous.onAdvertisement(advertisement(100.0))
        previous.onFailure(3)
        emit(9.0)
        assertTrue(client.state.isConnected)
        assertEquals(5.0, samples.last().kg)
        advance(11_000) // one-second watchdog polling may straddle the last 1ms sample
        assertEquals(3, transport.starts.size, "Old error6 did not arm a cooldown")
    }

    @Test fun newCallbackRejectsBufferedPreScanAndDuplicateNativeTimestamps() {
        connectAndEmit()
        val oldTimestamp = clock.uptimeSeconds()
        advance(10_000)
        val current = transport.starts.last()
        current.onAdvertisement(advertisement(99.0, at = oldTimestamp))
        assertEquals(1, samples.size)
        assertEquals(ProgressorConnectionState.Scanning, client.state)
        emit(12.0)
        current.onAdvertisement(advertisement(99.0)) // same native timestamp
        current.onAdvertisement(advertisement(99.0, at = clock.uptimeSeconds() - 0.1))
        assertEquals(2, samples.size)
        assertEquals(12.0, samples.last().kg)
    }

    @Test fun rejectedFramesDoNotAcquireOrKeepAConnectionAlive() {
        client.connect()
        val listener = transport.starts.single()
        listener.onAdvertisement(advertisement().copy(manufacturerPayload = byteArrayOf(1, 2)))
        listener.onAdvertisement(advertisement(301.0))
        assertEquals(ProgressorConnectionState.Scanning, client.state)
        listener.onAdvertisement(advertisement(0.0, name = null))
        assertTrue(client.state.isConnected, "Zero and unnamed scales are valid")
        assertNull(client.deviceName)
        advance(9_000)
        listener.onAdvertisement(advertisement(50.0, address = "other-scale"))
        listener.onAdvertisement(advertisement(500.0))
        advance(1_000)
        assertEquals(2, transport.starts.size, "Rejected data cannot extend liveness")
        assertEquals(1, samples.size)
    }

    @Test fun lossReleasesTheOldScaleAddressLock() {
        connectAndEmit()
        advance(10_000)
        transport.starts.last().onAdvertisement(advertisement(15.0, address = "next-scale"))
        assertEquals(15.0, samples.last().kg)
        assertTrue(client.state.isConnected)
    }

    @Test fun synchronousFailureDoesNotArmDeadlinesForRetiredAttempts() {
        transport.onStart = { it.onFailure(3) }
        client.connect()
        advance(2_000)
        advance(5_000)
        assertEquals(3, transport.starts.size)
        assertTrue(client.state is ProgressorConnectionState.Disconnected)
        advance(100_000)
        assertEquals(3, transport.starts.size)
        assertFalse(diagnostics.any { it is ProgressorClientDiagnostic.StreamStartWritten })
    }

    @Test fun synchronousFirstFrameDoesNotLeaveAnAcquisitionDeadline() {
        transport.onStart = { it.onAdvertisement(advertisement()) }
        client.connect()
        advance(8_000)
        emit()
        advance(8_000)
        assertTrue(client.state.isConnected)
        assertEquals(1, transport.starts.size)
    }

    @Test fun permissionsFailureCleansEverythingAndCanBeRetried() {
        transport.onStart = { throw SecurityException("Denied") }
        client.connect()
        assertEquals(ProgressorConnectionState.Unauthorized, client.state)
        assertNull(transport.radioObserver)
        advance(100_000)
        transport.onStart = null
        client.connect()
        emit()
        assertTrue(client.state.isConnected)
    }

    @Test fun missingScannerUsesBoundedRetriesInsteadOfGettingStuck() {
        transport.startAvailable = false
        client.connect()
        advance(2_000)
        advance(5_000)
        assertEquals(3, transport.starts.size)
        assertTrue(client.state is ProgressorConnectionState.Disconnected)
        transport.startAvailable = true
        client.connect()
        emit()
        assertTrue(client.state.isConnected)
    }

    @Test fun synchronousReconnectAfterSilencePublishesBreakBeforeSamples() {
        val order = mutableListOf<String>()
        client.onStateChange = { order += it.toString() }
        client.onEvent = { order += "sample" }
        transport.onStart = { it.onAdvertisement(advertisement()) }
        client.connect()
        order.clear()
        advance(10_000)
        assertTrue(order[0].startsWith("Disconnected"))
        assertEquals("Scanning", order[1])
        assertEquals("Connected", order[2])
        assertEquals("sample", order[3])
    }

    @Test fun disconnectInAConnectionObserverDoesNotLeakTheFirstSample() {
        client.onStateChange = { if (it.isConnected) client.disconnect() }
        client.connect()
        emit()
        assertTrue(samples.isEmpty())
        assertTrue(client.state is ProgressorConnectionState.Disconnected)
        advance(100_000)
        assertEquals(1, transport.starts.size)
    }

    @Test fun disconnectInALossObserverPreventsRecovery() {
        connectAndEmit()
        client.onStateChange = { if (it is ProgressorConnectionState.Disconnected) client.disconnect() }
        advance(10_000)
        assertEquals(1, transport.starts.size)
        assertTrue(client.state is ProgressorConnectionState.Disconnected)
        advance(100_000)
        assertEquals(1, transport.starts.size)
    }
}

internal class FakeBroadcastScanTransport : BroadcastScanTransport {
    override var radioState: ProgressorConnectionState = ProgressorConnectionState.Idle
    var radioObserver: (() -> Unit)? = null
    val starts = mutableListOf<BroadcastScanTransport.Listener>()
    val stops = mutableListOf<BroadcastScanTransport.Listener>()
    var onStart: ((BroadcastScanTransport.Listener) -> Unit)? = null
    var startAvailable = true
    override fun observeRadio(onChanged: () -> Unit) { radioObserver = onChanged }
    override fun stopObservingRadio() { radioObserver = null }
    override fun start(listener: BroadcastScanTransport.Listener): Boolean {
        starts += listener
        onStart?.invoke(listener)
        return startAvailable
    }
    override fun stop(listener: BroadcastScanTransport.Listener) { stops += listener }
    fun changeRadio(state: ProgressorConnectionState) {
        radioState = state
        radioObserver?.invoke()
    }
}
