// SPDX-License-Identifier: MPL-2.0
// Original contributions Copyright 2026 Nuri Bruner.

package run.nuri.getagrip.ble

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.ParcelUuid
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import no.nordicsemi.android.ble.BleManager
import no.nordicsemi.android.ble.observer.ConnectionObserver
import run.nuri.getagrip.engine.L10n
import run.nuri.getagrip.engine.ProgressorCodec
import run.nuri.getagrip.engine.ProgressorCommand
import run.nuri.getagrip.engine.ProgressorEvent
import run.nuri.getagrip.engine.ProgressorGATT
import java.util.UUID

/// The real Tindeq Progressor, over Android BLE.
///
/// **Isolation.** iOS marks the class `@MainActor` and hands CoreBluetooth `queue: .main`,
/// so every delegate callback genuinely arrives on the main thread and there is no
/// isolation boundary anywhere in the stack. Android's scan and GATT callbacks arrive on
/// binder/handler threads, so the same guarantee is kept by hand: every entry point below
/// hops to `Dispatchers.Main.immediate` before it touches state or calls a callback. It is
/// `immediate` and not plain `Main` because a `connect()` that can answer in the same turn
/// (a reattach to a live link) must publish its state before the caller looks.
///
/// TRANSLATION NOTE — **what Nordic's library owns and what stays ours.** The Swift client
/// hand-rolls a write queue because CoreBluetooth silently DISCARDS a `.withoutResponse`
/// write when its buffer is not ready and accepts one `.withResponse` write at a time; the
/// first hardware session (2026-08-03) lost one of the `startWeight`/`tare` pair to exactly
/// that. Nordic's `BleManager` already serializes GATT operations — a queued request is not
/// handed to the stack until the previous one has completed — so the ATT-level pacing moved
/// to the library. **Everything above ATT stayed:** `ControlPointQueue` still owns the
/// serialized tag-0 query channel, the poison latch, the bypass that keeps control commands
/// from queueing behind telemetry, and the tare-integrity ordering. None of those is a
/// radio rule; they exist because the PROTOCOL's replies carry no echo of what they answer,
/// and no transport library can know that.
class LiveProgressorClient(
    context: Context,
    private val scope: CoroutineScope,
    private val clock: HostClock = SystemHostClock,
) : ProgressorClient {

    override var onEvent: ((ProgressorEvent) -> Unit)? = null
    override var onPacketBoundary: ((PacketBoundary) -> Unit)? = null
    override var onStateChange: ((ProgressorConnectionState) -> Unit)? = null
    override var onDiagnostic: ((ProgressorClientDiagnostic) -> Unit)? = null

    override var state: ProgressorConnectionState = ProgressorConnectionState.Idle
        private set(value) {
            val changed = field != value
            field = value
            if (changed) onStateChange?.invoke(value)
        }

    override var deviceName: String? = null
        private set

    private val appContext = context.applicationContext

    private companion object {
        val serviceUUID: UUID = UUID.fromString(ProgressorGATT.serviceUUID)
        val dataUUID: UUID = UUID.fromString(ProgressorGATT.dataCharacteristicUUID)
        val controlUUID: UUID = UUID.fromString(ProgressorGATT.controlPointCharacteristicUUID)

        const val attemptLimit = 5
        const val scanDeadlineMillis = 15_000L
        const val connectTimeoutMillis = 8_000L
        const val backoffMillis = 1_000L
        const val replyDeadlineMillis = 2_000L
        const val sleepFallbackMillis = 1_000L

        /// **REQUEST 517 BEFORE SUBSCRIBING.** A Progressor notification of eight samples
        /// is 2 + 8 × 8 = 66 bytes, and Android's default ATT MTU of 23 gives 20 bytes of
        /// payload — every batch would arrive truncated, and the codec would (correctly)
        /// stop the walk and drop it. iOS negotiates the MTU itself and never had to ask.
        const val requestedMtu = 517

        /// The advertised name is `Progressor_<serial>`, which varies per unit — so the
        /// SCAN filters on the service, exactly as iOS does. This prefix is used only for
        /// the reattach path, where Android offers no service filter at all.
        const val namePrefix = "Progressor"
    }

    private val bluetoothManager =
        appContext.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
    private val adapter: BluetoothAdapter? get() = bluetoothManager?.adapter

    private var manager: ProgressorManager? = null
    private var device: BluetoothDevice? = null

    /// A deliberately cancelled link stays quarantined until the stack delivers its
    /// terminal callback. Reusing it earlier lets callbacks from the old generation satisfy
    /// the new connection attempt.
    ///
    /// TRANSLATION NOTE: iOS holds the retired `CBPeripheral` itself. Nordic's `BleManager`
    /// binds to one device at a time and refuses a second `connect()` while the first is
    /// live, so the quarantine here is a BOOLEAN plus the generation counter — the
    /// breadcrumb vocabulary is unchanged, because the store's ring is read against iOS
    /// logs and the two must stay comparable.
    private var quarantined = false
    private var pendingConnectionStart = false

    private var generation: ULong = 0uL
    private var activeGeneration: ULong? = null
    private var scanGeneration: ULong? = null

    private var attemptsRemaining = 0

    /// Set only by an accepted explicit `connect()`, and retained across radio power loss
    /// so powering Bluetooth back on resumes the same user intent.
    private var wantsConnection = false
    private var refreshBudgetWhenPoweredOn = false

    private var scanDeadlineJob: Job? = null
    private var backoffJob: Job? = null
    private var replyDeadlineJob: Job? = null
    private var sleepFallbackJob: Job? = null

    private var isScanning = false
    private var sleepRequested = false

    private val queue = ControlPointQueue(Transport())

    // MARK: - Radio state

    private var radioReceiver: BroadcastReceiver? = null

    /// TRANSLATION NOTE: CoreBluetooth publishes radio state through
    /// `centralManagerDidUpdateState`, which is also where iOS resumes a connect that was
    /// waiting on the radio. Android has no such callback on the scanner, so this receiver
    /// IS that delegate method — registered lazily with the first `connect()`, for exactly
    /// the reason the central is created lazily there: nothing about Bluetooth happens
    /// until a tap asks for it.
    private fun registerRadioReceiver() {
        if (radioReceiver != null) return
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                if (intent?.action != BluetoothAdapter.ACTION_STATE_CHANGED) return
                onMain { radioStateChanged() }
            }
        }
        radioReceiver = receiver
        appContext.registerReceiver(
            receiver,
            IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED),
        )
    }

    private fun unregisterRadioReceiver() {
        val receiver = radioReceiver ?: return
        radioReceiver = null
        runCatching { appContext.unregisterReceiver(receiver) }
    }

    private fun radioStateChanged() {
        if (adapter?.isEnabled != true) {
            handlePowerUnavailable()
            return
        }
        if (refreshBudgetWhenPoweredOn) {
            attemptsRemaining = attemptLimit
            refreshBudgetWhenPoweredOn = false
        }
        if (wantsConnection) beginAttemptIfPossible()
    }

    private fun radioState(): ProgressorConnectionState = when {
        adapter == null -> ProgressorConnectionState.Unsupported
        adapter?.isEnabled != true -> ProgressorConnectionState.BluetoothOff
        else -> ProgressorConnectionState.Idle
    }

    // MARK: - ProgressorClient

    override fun connect() {
        // `wantsConnection` covers the backoff and radio-off gaps, when the public state is
        // not busy but the original user intent is still active. A duplicate tap must not
        // replenish its retry budget.
        if (wantsConnection || state.isBusy || state.isConnected) return

        wantsConnection = true
        attemptsRemaining = attemptLimit
        refreshBudgetWhenPoweredOn = false

        // LAZY on purpose, the twin of iOS constructing its central here: nothing about
        // Bluetooth is touched until a Connect tap asks for it. On Android the system
        // prompt belongs to `DeviceStore`'s `PermissionGate`, which has already run by the
        // time this is called.
        registerRadioReceiver()
        if (adapter?.isEnabled != true) {
            state = radioState()
            return
        }
        beginAttemptIfPossible()
    }

    override fun disconnect() {
        wantsConnection = false
        refreshBudgetWhenPoweredOn = false
        pendingConnectionStart = false
        attemptsRemaining = 0
        generation += 1uL

        stopScan()
        cancelAllJobs()
        scanGeneration = null
        clearLinkState(clearDeferredStart = true)
        sleepRequested = false

        if (device != null) retireLink()
        device = null
        activeGeneration = null
        deviceName = null
        unregisterRadioReceiver()
        state = ProgressorConnectionState.Disconnected(reason = null)
    }

    override fun sleepDevice() {
        // Sleep is a terminal user intent. Clear reconnect intent before the command enters
        // the queue so a device-initiated shutdown cannot start a rescan.
        wantsConnection = false
        refreshBudgetWhenPoweredOn = false
        pendingConnectionStart = false
        backoffJob?.cancel()
        backoffJob = null

        if (!state.isConnected || sleepRequested) {
            if (!state.isConnected) disconnect()
            return
        }
        sleepRequested = true
        queue.enqueue(ProgressorCommand.enterSleep)
    }

    override fun send(command: ProgressorCommand) {
        // Start writes carry a cause through the one public start funnel. Silently
        // accepting an uncaused start here would make its later hardware breadcrumb a
        // guess, so callers use `startStreaming(cause)` instead.
        if (command == ProgressorCommand.startWeightMeasurement) return
        queue.enqueue(command)
    }

    /// **Never gated on an "is streaming" flag.** That flag can only ever cause the one
    /// command a session depends on to be SKIPPED. Re-sending `startWeight` to a streaming
    /// device is harmless; not sending it is a dead workout. The µs-epoch semantics of a
    /// re-kick — the `streamRestarted` timeline break — belong to the store and the runner;
    /// this client just writes the start again.
    override fun startStreaming(cause: StreamStartCause) {
        queue.enqueue(ProgressorCommand.startWeightMeasurement, startCause = cause)
        manager?.requestStreamingConnectionInterval(streaming = true)
    }

    /// **Hand the radio back when the stream stops.** The high-priority interval is a
    /// sensor-grade request; holding it while nothing is streaming spends battery on a link
    /// nobody is reading — see `requestStreamingConnectionInterval`.
    override fun stopStreaming() {
        send(ProgressorCommand.stopWeightMeasurement)
        manager?.requestStreamingConnectionInterval(streaming = false)
    }

    // MARK: - Connection flow

    private fun beginAttemptIfPossible() {
        if (!wantsConnection || adapter?.isEnabled != true) return
        if (state.isConnected || state.isBusy) return

        if (quarantined) {
            pendingConnectionStart = true
            return
        }
        if (attemptsRemaining <= 0) {
            wantsConnection = false
            state = ProgressorConnectionState.Disconnected(
                L10n.tr("Connection attempts exhausted"),
            )
            return
        }

        pendingConnectionStart = false
        backoffJob?.cancel()
        backoffJob = null
        attemptsRemaining -= 1
        generation += 1uL
        scanGeneration = generation

        // BLE links are owned by the system, not by this process, so after a relaunch the
        // device may already be connected — reattaching is instant and skips the scan
        // entirely.
        //
        // TRANSLATION NOTE: iOS filters `retrieveConnectedPeripherals` by SERVICE. Android's
        // `getConnectedDevices(GATT)` takes no service filter and reports every GATT-linked
        // device on the phone, so the name prefix is the only filter available here. It is
        // never the scan's filter — the scan matches the service, exactly as iOS does.
        val known = try {
            bluetoothManager?.getConnectedDevices(BluetoothProfile.GATT)
                ?.firstOrNull { it.name?.startsWith(namePrefix) == true }
        } catch (_: SecurityException) {
            null
        }
        if (known != null) {
            attach(known, generation)
            return
        }

        state = ProgressorConnectionState.Scanning
        startScan(generation)
    }

    // MARK: - Scanning

    /// **SCAN-RATE DISCIPLINE.** Android returns nothing at all for 30 seconds after five
    /// scan starts in 30 seconds, silently — no error, no callback. The retry ladder here
    /// is five attempts with a one-second backoff, which is exactly the shape that trips
    /// it, so a running scan is REUSED rather than restarted: only the deadline is re-armed.
    /// iOS has no such quota and simply calls `scanForPeripherals` again.
    private fun startScan(generation: ULong) {
        val scanner = adapter?.bluetoothLeScanner
        if (scanner == null) {
            failAttempt(L10n.tr("No Bluetooth"), cancelling = false)
            return
        }
        startScanDeadline(generation)
        if (isScanning) return
        val filter = ScanFilter.Builder()
            .setServiceUuid(ParcelUuid(serviceUUID))
            .build()
        // CALLBACK_TYPE_ALL_MATCHES (the default) rather than FIRST_MATCH: first-match
        // needs offloaded filtering, which not every chipset has, and `startScan` throws
        // outright where it is missing. The `device != null` guard in `didDiscover` is
        // what makes the first hit the only one that matters.
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()
        try {
            scanner.startScan(listOf(filter), settings, scanCallback)
            isScanning = true
        } catch (_: SecurityException) {
            state = ProgressorConnectionState.Unauthorized
        }
    }

    private fun stopScan() {
        if (!isScanning) return
        isScanning = false
        runCatching { adapter?.bluetoothLeScanner?.stopScan(scanCallback) }
    }

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            onMain { didDiscover(result) }
        }

        override fun onBatchScanResults(results: MutableList<ScanResult>) {
            val first = results.firstOrNull() ?: return
            onMain { didDiscover(first) }
        }

        override fun onScanFailed(errorCode: Int) {
            onMain {
                stopScan()
                failAttempt(L10n.tr("No gauge found"), cancelling = false)
            }
        }
    }

    private fun didDiscover(result: ScanResult) {
        // Scanning is already filtered to the Progressor service, so the first hit is the
        // device. (Only one gauge is ever in play.)
        if (adapter?.isEnabled != true || !wantsConnection) return
        if (state != ProgressorConnectionState.Scanning) return
        if (device != null || quarantined) return
        if (scanGeneration != generation) return
        attach(result.device, generation)
    }

    private fun attach(found: BluetoothDevice, generation: ULong) {
        if (!wantsConnection || adapter?.isEnabled != true) return
        if (this.generation != generation || quarantined) return

        stopScan()
        scanDeadlineJob?.cancel()
        scanDeadlineJob = null
        scanGeneration = null
        device = found
        activeGeneration = generation
        deviceName = try {
            found.name
        } catch (_: SecurityException) {
            null
        }
        state = ProgressorConnectionState.Connecting

        val bleManager = manager ?: ProgressorManager(appContext).also {
            it.setConnectionObserver(observer)
            manager = it
        }
        // TRANSLATION NOTE: the 8 s connect deadline is Nordic's own `timeout`, not a
        // hand-rolled task — the library cancels the pending connection itself and reports
        // `onDeviceFailedToConnect` with `REASON_TIMEOUT`. `retry(0)`: the retry ladder
        // above owns the budget, and letting the library retry too would spend five
        // attempts inside one of ours.
        try {
            bleManager.connect(found)
                .useAutoConnect(false)
                .retry(0)
                .timeout(connectTimeoutMillis)
                .enqueue()
        } catch (_: SecurityException) {
            state = ProgressorConnectionState.Unauthorized
        }
    }

    private fun startScanDeadline(generation: ULong) {
        scanDeadlineJob?.cancel()
        scanDeadlineJob = scope.launch(Dispatchers.Main.immediate) {
            delay(scanDeadlineMillis)
            if (this@LiveProgressorClient.generation != generation) return@launch
            if (scanGeneration != generation) return@launch
            if (state != ProgressorConnectionState.Scanning) return@launch
            stopScan()
            failAttempt(L10n.tr("No gauge found"), cancelling = false)
        }
    }

    private fun scheduleRetry() {
        if (!wantsConnection || attemptsRemaining <= 0) {
            wantsConnection = false
            return
        }
        val retryGeneration = generation
        backoffJob?.cancel()
        backoffJob = scope.launch(Dispatchers.Main.immediate) {
            delay(backoffMillis)
            if (generation != retryGeneration) return@launch
            if (!wantsConnection || adapter?.isEnabled != true) return@launch
            beginAttemptIfPossible()
        }
    }

    private fun failAttempt(reason: String, cancelling: Boolean) {
        invalidateAttempt()
        if (cancelling) retireLink()
        state = ProgressorConnectionState.Disconnected(reason)

        if (!wantsConnection || attemptsRemaining <= 0) {
            wantsConnection = false
            return
        }
        scheduleRetry()
    }

    private fun failPermanently(reason: String) {
        wantsConnection = false
        refreshBudgetWhenPoweredOn = false
        pendingConnectionStart = false
        attemptsRemaining = 0
        val hadLink = device != null
        invalidateAttempt()
        if (hadLink) retireLink()
        state = ProgressorConnectionState.Disconnected(reason)
    }

    private fun invalidateAttempt() {
        generation += 1uL
        stopScan()
        cancelAllJobs()
        scanGeneration = null
        activeGeneration = null
        device = null
        deviceName = null
        clearLinkState(clearDeferredStart = true)
    }

    /// There can only be one gauge. If an earlier deliberate cancellation is still awaiting
    /// its terminal callback, never replace its quarantine with another.
    private fun retireLink() {
        if (quarantined) return
        onDiagnostic?.invoke(ProgressorClientDiagnostic.RetiringPeripheral)
        quarantined = true
        manager?.disconnect()?.enqueue()
    }

    private fun releaseQuarantine() {
        if (!quarantined) return
        quarantined = false
        onDiagnostic?.invoke(ProgressorClientDiagnostic.QuarantineReleased)
        if (pendingConnectionStart) {
            pendingConnectionStart = false
            beginAttemptIfPossible()
        }
    }

    private fun handlePowerUnavailable() {
        refreshBudgetWhenPoweredOn = wantsConnection
        attemptsRemaining = 0
        pendingConnectionStart = false
        val hadLink = device != null
        invalidateAttempt()
        replyDeadlineJob?.cancel()
        replyDeadlineJob = null
        sleepFallbackJob?.cancel()
        sleepFallbackJob = null
        if (hadLink) retireLink()
        state = radioState()
    }

    private fun clearLinkState(clearDeferredStart: Boolean) {
        queue.clearLinkState(clearDeferredStart)
        replyDeadlineJob?.cancel()
        replyDeadlineJob = null
        sleepFallbackJob?.cancel()
        sleepFallbackJob = null
        sleepRequested = false
    }

    private fun cancelAllJobs() {
        scanDeadlineJob?.cancel()
        scanDeadlineJob = null
        backoffJob?.cancel()
        backoffJob = null
        replyDeadlineJob?.cancel()
        replyDeadlineJob = null
        sleepFallbackJob?.cancel()
        sleepFallbackJob = null
    }

    private fun completeSleep() {
        if (device == null) return
        sleepFallbackJob?.cancel()
        sleepFallbackJob = null
        sleepRequested = false
        generation += 1uL
        activeGeneration = null
        device = null
        deviceName = null
        clearLinkState(clearDeferredStart = true)
        retireLink()
        state = ProgressorConnectionState.Disconnected(L10n.tr("Device asleep"))
    }

    // MARK: - Link callbacks

    private val observer = object : ConnectionObserver {
        override fun onDeviceConnecting(device: BluetoothDevice) = Unit

        override fun onDeviceConnected(device: BluetoothDevice) = Unit

        override fun onDeviceFailedToConnect(device: BluetoothDevice, reason: Int) {
            onMain {
                if (releasedQuarantine()) return@onMain
                if (!isCurrent(device)) return@onMain
                failAttempt(reasonText(reason), cancelling = false)
            }
        }

        override fun onDeviceReady(device: BluetoothDevice) = Unit

        override fun onDeviceDisconnecting(device: BluetoothDevice) = Unit

        override fun onDeviceDisconnected(device: BluetoothDevice, reason: Int) {
            onMain { handleDisconnect(device, reason) }
        }
    }

    private fun releasedQuarantine(): Boolean {
        if (!quarantined) return false
        releaseQuarantine()
        return true
    }

    private fun handleDisconnect(disconnected: BluetoothDevice, reason: Int) {
        if (releasedQuarantine()) return
        if (!isCurrent(disconnected)) return

        val wasEstablished = state.isConnected
        val sleepCompleted = queue.issuedSleepID != null
        invalidateAttempt()

        // Radio state is authoritative. Its receiver already published the off or
        // unauthorized state, and a late disconnect must not overwrite or rescan it.
        if (adapter?.isEnabled != true) return

        if (sleepCompleted) {
            wantsConnection = false
            state = ProgressorConnectionState.Disconnected(L10n.tr("Device asleep"))
            return
        }

        state = ProgressorConnectionState.Disconnected(reasonText(reason))
        if (!wantsConnection) return

        if (wasEstablished) {
            // A successful notification subscription reset the budget, so a dropped
            // established link begins a fresh five-attempt cycle immediately.
            attemptsRemaining = attemptLimit
            beginAttemptIfPossible()
        } else if (attemptsRemaining > 0) {
            scheduleRetry()
        } else {
            wantsConnection = false
        }
    }

    /// Notifications are on — the exact moment iOS publishes `.connected` from
    /// `didUpdateNotificationStateFor`.
    private fun linkReady() {
        val current = device ?: return
        if (!isCurrent(current)) return
        attemptsRemaining = attemptLimit
        queue.linkEstablished()
        state = ProgressorConnectionState.Connected
        send(ProgressorCommand.getAppVersion)
        send(ProgressorCommand.getBatteryVoltage)
        queue.drain()
    }

    private fun onNotification(bytes: ByteArray, receivedAt: Double) = withPacket(receivedAt) {
        val events = ProgressorCodec.decode(bytes, answering = queue.pendingReplyCommand)
        // A tag-0 reply consumes the one pending query; weight notifications do not.
        if (events.any { it.isCommandReply }) queue.commandReplyReceived()
        for (event in events) onEvent?.invoke(event)
    }

    private fun isCurrent(candidate: BluetoothDevice): Boolean {
        val current = device ?: return false
        if (current != candidate) return false
        return activeGeneration == generation
    }

    private fun onMain(block: () -> Unit) {
        scope.launch(Dispatchers.Main.immediate) { block() }
    }

    private fun reasonText(reason: Int): String = when (reason) {
        ConnectionObserver.REASON_TIMEOUT -> L10n.tr("Connection timed out")
        ConnectionObserver.REASON_NOT_SUPPORTED -> L10n.tr("Not a Progressor")
        ConnectionObserver.REASON_LINK_LOSS -> L10n.tr("Lost the gauge")
        ConnectionObserver.REASON_SUCCESS -> L10n.tr("Disconnected")
        else -> L10n.tr("Couldn't connect")
    }

    // MARK: - The control-point transport

    private inner class Transport : ControlPointTransport {
        override val canWrite: Boolean
            get() = state.isConnected && manager?.controlPoint != null &&
                device?.let { isCurrent(it) } == true

        override val writeType: ControlWriteType
            get() {
                val properties = manager?.controlPoint?.properties ?: 0
                return if (properties and BluetoothGattCharacteristic.PROPERTY_WRITE != 0) {
                    ControlWriteType.withResponse
                } else {
                    ControlWriteType.withoutResponse
                }
            }

        /// Always ready: Nordic's request queue is what paces write-without-response, so
        /// there is no "buffer not ready" state to poll. See the class comment.
        override val isReadyForWriteWithoutResponse: Boolean get() = true

        override fun write(command: ProgressorCommand, withResponse: Boolean) {
            manager?.writeControlPoint(command.encoded, withResponse)
        }

        override fun failPermanently(reason: String) {
            this@LiveProgressorClient.failPermanently(reason)
        }

        override fun completeSleep() {
            this@LiveProgressorClient.completeSleep()
        }

        override fun armReplyDeadline(id: ULong) {
            val replyGeneration = generation
            replyDeadlineJob?.cancel()
            replyDeadlineJob = scope.launch(Dispatchers.Main.immediate) {
                delay(replyDeadlineMillis)
                if (generation != replyGeneration) return@launch
                replyDeadlineJob = null
                queue.replyDeadlineFired(id)
            }
        }

        override fun cancelReplyDeadline() {
            replyDeadlineJob?.cancel()
            replyDeadlineJob = null
        }

        override fun armSleepFallback(id: ULong) {
            val sleepGeneration = generation
            sleepFallbackJob?.cancel()
            sleepFallbackJob = scope.launch(Dispatchers.Main.immediate) {
                delay(sleepFallbackMillis)
                if (generation != sleepGeneration) return@launch
                if (queue.issuedSleepID != id) return@launch
                completeSleep()
            }
        }

        override fun diagnostic(diagnostic: ProgressorClientDiagnostic) {
            onDiagnostic?.invoke(diagnostic)
        }
    }

    // MARK: - Nordic manager

    private inner class ProgressorManager(context: Context) : BleManager(context) {
        var controlPoint: BluetoothGattCharacteristic? = null
            private set
        private var dataCharacteristic: BluetoothGattCharacteristic? = null

        private var tuningGatt: BluetoothGatt? = null
        private val streamingPriority = StreamingConnectionPriority()

        override fun isRequiredServiceSupported(gatt: BluetoothGatt): Boolean {
            tuningGatt = gatt
            val service = gatt.getService(serviceUUID) ?: return false
            val data = service.getCharacteristic(dataUUID) ?: return false
            val control = service.getCharacteristic(controlUUID) ?: return false
            val writable = control.properties and (
                BluetoothGattCharacteristic.PROPERTY_WRITE or
                    BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE
                ) != 0
            val notifies =
                data.properties and BluetoothGattCharacteristic.PROPERTY_NOTIFY != 0
            if (!writable || !notifies) return false
            dataCharacteristic = data
            controlPoint = control
            return true
        }

        override fun initialize() {
            val notificationGeneration = activeGeneration
            // MTU FIRST, before any notification can arrive — see `requestedMtu`.
            requestMtu(requestedMtu).enqueue()
            setNotificationCallback(dataCharacteristic).with { _, packet ->
                val receivedAt = clock.uptimeSeconds()
                val bytes = packet.value ?: ByteArray(0)
                onMain {
                    if (activeGeneration != notificationGeneration || notificationGeneration == null) return@onMain
                    onNotification(bytes, receivedAt)
                }
            }
            enableNotifications(dataCharacteristic)
                .done { onMain { linkReady() } }
                .fail { _, _ ->
                    onMain {
                        failAttempt(
                            L10n.tr("Notification subscription failed"),
                            cancelling = true,
                        )
                    }
                }
                .enqueue()
        }

        override fun onServicesInvalidated() {
            tuningGatt = null
            streamingPriority.reset()
            controlPoint = null
            dataCharacteristic = null
        }

        /// **The connection interval, measured on hardware and not guessed.**
        ///
        /// First real session on the Realme RMX5079 (Android 16), from `adb logcat`: within
        /// five seconds of connecting the phone renegotiated the link from a 7.5 ms interval
        /// to **60 ms with slave latency 6** — OEM power saving — so notifications arrived
        /// every ~180 ms carrying ~14 samples each instead of every ~12 ms carrying one. No
        /// samples were LOST (the Progressor stamps its own microseconds, and the engine
        /// accrues from those deltas, so hang time is exact either way), but the readout and
        /// the trace lag the hand by up to ~0.4 s, and iOS runs the same gauge at 30 ms.
        ///
        /// `CONNECTION_PRIORITY_HIGH` asks for ~11.25–15 ms with no slave latency, which is
        /// the closest Android gets to what CoreBluetooth negotiates by default. It is a
        /// REQUEST — the peripheral and the controller can refuse it, and an aggressive OEM
        /// can renegotiate straight back — so nothing depends on it succeeding.
        ///
        /// Dropped back to BALANCED the moment the stream stops, because the whole cost of
        /// a fast interval is battery on both ends, and a connected-but-idle gauge (the live
        /// gauge screen closed, a session finished) is reading nothing.
        fun requestStreamingConnectionInterval(streaming: Boolean) {
            val gatt = tuningGatt ?: return
            // Connection parameters are a link-layer request, not an ATT write. Calling
            // the platform directly avoids Nordic's queued negotiation holding up tare,
            // start or stop. Failure is harmless; the next request can retry after cooldown.
            streamingPriority.update(streaming, clock.uptimeSeconds()) { active ->
                try {
                    gatt.requestConnectionPriority(
                        if (active) BluetoothGatt.CONNECTION_PRIORITY_HIGH
                        else BluetoothGatt.CONNECTION_PRIORITY_BALANCED,
                    )
                } catch (_: SecurityException) { false }
            }
        }

        fun writeControlPoint(bytes: ByteArray, withResponse: Boolean) {
            val control = controlPoint ?: return
            val type = if (withResponse) {
                BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
            } else {
                BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
            }
            writeCharacteristic(control, bytes, type)
                .done { onMain { queue.writeCompleted(error = null) } }
                .fail { _, status ->
                    onMain { queue.writeCompleted(error = "status $status") }
                }
                .enqueue()
        }
    }
}

/// TRANSLATION NOTE: Swift keeps this as a `private extension ProgressorEvent` beside the
/// client; Kotlin makes it an internal extension property in the same file.
internal val ProgressorEvent.isCommandReply: Boolean
    get() = this is ProgressorEvent.Battery ||
        this is ProgressorEvent.AppVersion ||
        this is ProgressorEvent.ErrorInformation ||
        this is ProgressorEvent.CommandResponse
