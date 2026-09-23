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
/// **Isolation.** iOS runs CoreBluetooth on `queue: .main`, so every callback is on the
/// main thread. Android's scan and GATT callbacks arrive on binder/handler threads, so
/// every entry point below hops to `Dispatchers.Main.immediate` before touching state —
/// `immediate` because a `connect()` that answers in the same turn (reattach to a live
/// link) must publish before the caller looks.
///
/// TRANSLATION NOTE — **what Nordic's library owns and what stays ours.** iOS hand-rolls a
/// write queue because CoreBluetooth silently DISCARDS an unready `.withoutResponse` write
/// and takes one `.withResponse` write at a time (the first hardware session lost one of
/// `startWeight`/`tare` that way). Nordic's `BleManager` serializes GATT operations, so ATT
/// pacing moved to the library. **Everything above ATT stayed:** `ControlPointQueue` owns
/// the serialized tag-0 query channel, the poison latch, the control-command bypass and
/// tare-integrity ordering — protocol rules (replies carry no echo), which no transport
/// library can know.
class LiveProgressorClient(
    context: Context,
    private val scope: CoroutineScope,
    private val clock: HostClock = SystemHostClock,
    /// The app's shared count of scan starts — see `ScanStartBudget`.
    private val scanBudget: ScanStartBudget = ScanStartBudget(),
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
        const val quarantineFallbackMillis = 3_000L

        /// **REQUEST 517 BEFORE SUBSCRIBING.** An eight-sample notification is 2 + 8 × 8 =
        /// 66 bytes; Android's default MTU of 23 leaves 20 bytes of payload, so every batch
        /// would arrive truncated and be dropped by the codec. iOS negotiates this itself.
        const val requestedMtu = 517

        /// The advertised name is `Progressor_<serial>`, so the SCAN filters on the
        /// service, as on iOS. The prefix serves only the reattach path, where Android
        /// offers no service filter.
        const val namePrefix = "Progressor"
    }

    private val bluetoothManager =
        appContext.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
    private val adapter: BluetoothAdapter? get() = bluetoothManager?.adapter

    private var manager: ProgressorManager? = null
    private var device: BluetoothDevice? = null

    /// A deliberately cancelled link stays quarantined until the stack delivers its
    /// terminal callback; reusing it earlier lets old-generation callbacks satisfy the new
    /// attempt.
    ///
    /// TRANSLATION NOTE: iOS holds the retired `CBPeripheral`. Nordic's `BleManager` binds
    /// one device at a time, so here it is a BOOLEAN plus the generation counter; the
    /// breadcrumb vocabulary is unchanged so the ring stays comparable with iOS logs.
    private var quarantined = false
    private var pendingConnectionStart = false

    private var generation: ULong = 0uL
    private var activeGeneration: ULong? = null
    private var scanGeneration: ULong? = null

    private var attemptsRemaining = 0

    /// Set only by an accepted explicit `connect()`, and retained across radio power loss
    /// so Bluetooth coming back resumes the intent.
    private var wantsConnection = false
    private var refreshBudgetWhenPoweredOn = false

    private var scanDeadlineJob: Job? = null
    private var backoffJob: Job? = null
    private var replyDeadlineJob: Job? = null
    private var writeDeadlineJob: Job? = null
    private var sleepFallbackJob: Job? = null

    private var isScanning = false
    private var sleepRequested = false

    /// Reconnecting without a scan — see `RememberedGauge`.
    private val remembered = RememberedGauge<BluetoothDevice>()
    private val scanStarts = BudgetedScanStart(scope, scanBudget, clock)

    private var quarantineFallbackJob: Job? = null

    private val queue = ControlPointQueue(Transport())

    // MARK: - Radio state

    private var radioReceiver: BroadcastReceiver? = null

    /// TRANSLATION NOTE: this receiver IS iOS's `centralManagerDidUpdateState` (where iOS
    /// also resumes a connect waiting on the radio). Registered lazily with the first
    /// `connect()`, as the iOS central is: nothing about Bluetooth happens until a tap asks
    /// for it.
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
        // `wantsConnection` covers backoff and radio-off gaps where the intent is still
        // live; a duplicate tap must not replenish the retry budget.
        if (wantsConnection || state.isBusy || state.isConnected) return

        wantsConnection = true
        attemptsRemaining = attemptLimit
        refreshBudgetWhenPoweredOn = false
        remembered.connectRequested()

        // LAZY, the twin of iOS constructing its central here. The permission prompt
        // belongs to `DeviceStore`'s `PermissionGate`, which has already run.
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
        remembered.released()
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
        // Sleep is a terminal intent. Clear reconnect intent before queueing so the
        // device's shutdown cannot start a rescan.
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
        // Starts go through `startStreaming(cause)`, so a later hardware breadcrumb is
        // never a guess about who asked.
        if (command == ProgressorCommand.startWeightMeasurement) return
        queue.enqueue(command)
    }

    /// **Never gated on an "is streaming" flag**: it can only SKIP the command a session
    /// depends on, and re-sending `startWeight` is harmless. The µs-epoch semantics of a
    /// re-kick (`streamRestarted`) belong to the store and runner; this just writes the
    /// start.
    override fun startStreaming(cause: StreamStartCause) {
        queue.enqueue(ProgressorCommand.startWeightMeasurement, startCause = cause)
        manager?.requestStreamingConnectionInterval(streaming = true)
    }

    /// **Hand the radio back when the stream stops**: holding the high-priority interval
    /// with nothing streaming spends battery on a link nobody reads — see
    /// `requestStreamingConnectionInterval`.
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

        // BLE links are owned by the system, so after a relaunch the device may already be
        // connected; reattaching skips the scan.
        //
        // TRANSLATION NOTE: iOS filters `retrieveConnectedPeripherals` by SERVICE.
        // Android's `getConnectedDevices(GATT)` has no service filter, so the name prefix
        // is all there is here. The scan still matches the service.
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

        val target = remembered.device
        when (remembered.route()) {
            AttemptRoute.awaitInRange -> attach(target!!, generation, autoConnect = true)
            AttemptRoute.direct -> attach(target!!, generation)
            AttemptRoute.scan -> {
                state = ProgressorConnectionState.Scanning
                startScan(generation)
            }
        }
    }

    // MARK: - Scanning

    /// **SCAN-RATE DISCIPLINE.** Android silently returns nothing for 30 s after five scan
    /// starts in 30 s. The retry ladder (five attempts, one-second backoff) is exactly that
    /// shape, so a running scan is REUSED and only its deadline re-armed. iOS has no such
    /// quota.
    ///
    /// A scan that must start asks the shared budget first — see `BudgetedScanStart`.
    private fun startScan(generation: ULong) {
        val scanner = adapter?.bluetoothLeScanner
        if (scanner == null) {
            failAttempt(L10n.tr("No Bluetooth"), cancelling = false)
            return
        }
        if (isScanning) {
            startScanDeadline(generation)
            return
        }
        val deferred = scanStarts.deferIfOverBudget(
            stillWanted = { this.generation == generation && scanGeneration == generation },
        ) { startScan(generation) }
        if (deferred) return
        startScanDeadline(generation)
        val filter = ScanFilter.Builder()
            .setServiceUuid(ParcelUuid(serviceUUID))
            .build()
        // ALL_MATCHES (the default), not FIRST_MATCH: first-match needs offloaded filtering
        // some chipsets lack, where `startScan` throws. The `device != null` guard in
        // `didDiscover` makes the first hit the only one that matters.
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()
        try {
            scanner.startScan(listOf(filter), settings, scanCallback)
            isScanning = true
            scanStarts.started()
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
                scanStarts.failed(errorCode)
                failAttempt(L10n.tr("No gauge found"), cancelling = false)
            }
        }
    }

    private fun didDiscover(result: ScanResult) {
        // The scan is filtered to the Progressor service, so the first hit is the device
        // (only one gauge is ever in play).
        if (adapter?.isEnabled != true || !wantsConnection) return
        if (state != ProgressorConnectionState.Scanning) return
        if (device != null || quarantined) return
        if (scanGeneration != generation) return
        attach(result.device, generation)
    }

    /// `autoConnect` lets the OS connect whenever the device is next in range, with no
    /// timeout — see `AttemptRoute.awaitInRange`. Nordic tries a direct connection first,
    /// so a gauge already present costs nothing extra.
    private fun attach(found: BluetoothDevice, generation: ULong, autoConnect: Boolean = false) {
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
        // TRANSLATION NOTE: the 8 s connect deadline is Nordic's `timeout`, which cancels
        // and reports `REASON_TIMEOUT` itself. `retry(0)`: the ladder above owns the
        // budget; library retries would spend five attempts inside one of ours.
        remembered.attaching(autoConnect)
        try {
            val request = bleManager.connect(found)
                .useAutoConnect(autoConnect)
                .retry(0)
            // No deadline while waiting for the gauge to come back into range: the wait is
            // the point, as a rep never ends itself for a lost link.
            if (!autoConnect) request.timeout(connectTimeoutMillis)
            request.enqueue()
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

    /// There can only be one gauge. A cancellation still awaiting its terminal callback
    /// keeps its quarantine; never replace it.
    private fun retireLink() {
        if (quarantined) return
        onDiagnostic?.invoke(ProgressorClientDiagnostic.RetiringPeripheral)
        quarantined = true
        manager?.disconnect()?.enqueue()
        // Backstop for the terminal callback. Retiring a link already DOWN (an autoConnect
        // link Nordic was reconnecting) tears nothing down, and a stack that never calls
        // back would hold the quarantine, and every reconnect behind it, forever.
        quarantineFallbackJob?.cancel()
        quarantineFallbackJob = scope.launch(Dispatchers.Main.immediate) {
            delay(quarantineFallbackMillis)
            quarantineFallbackJob = null
            releaseQuarantine()
        }
    }

    private fun releaseQuarantine() {
        if (!quarantined) return
        quarantineFallbackJob?.cancel()
        quarantineFallbackJob = null
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
        scanStarts.cancel()
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
        val wasAutoConnect = remembered.linkLost()
        invalidateAttempt()
        // Close a lost autoConnect link; the quarantine holds the next attempt until the
        // stack confirms. Why this differs from `GattGaugeClient`: see `RememberedGauge`.
        if (wasAutoConnect) retireLink()

        // Radio state is authoritative: its receiver already published off/unauthorized,
        // and a late disconnect must not overwrite it or rescan.
        if (adapter?.isEnabled != true) return

        if (sleepCompleted) {
            wantsConnection = false
            state = ProgressorConnectionState.Disconnected(L10n.tr("Device asleep"))
            return
        }

        state = ProgressorConnectionState.Disconnected(reasonText(reason))
        if (!wantsConnection) return

        if (wasEstablished) {
            // Subscribing reset the budget, so a dropped established link begins a fresh
            // five-attempt cycle, waiting for the SAME gauge (`AttemptRoute.awaitInRange`).
            attemptsRemaining = attemptLimit
            remembered.awaitReturn()
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
        remembered.established(current)
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

        /// Always ready: Nordic's request queue paces write-without-response. See the class
        /// comment.
        override val isReadyForWriteWithoutResponse: Boolean get() = true

        override fun write(command: ProgressorCommand, withResponse: Boolean) {
            command.encoded?.let { manager?.writeControlPoint(it, withResponse) }
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

        override fun armWriteDeadline(id: ULong) {
            val writeGeneration = generation
            cancelWriteDeadline()
            writeDeadlineJob = scope.launch(Dispatchers.Main.immediate) {
                delay(3_000)
                if (generation != writeGeneration) return@launch
                writeDeadlineJob = null
                queue.writeDeadlineFired(id)
            }
        }

        override fun cancelWriteDeadline() {
            writeDeadlineJob?.cancel()
            writeDeadlineJob = null
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
                BluetoothGattCharacteristic.PROPERTY_WRITE
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

        /// **The connection interval, measured on hardware, not guessed.** On a Realme
        /// RMX5079 (Android 16) the phone renegotiated from 7.5 ms to **60 ms with slave
        /// latency 6** within five seconds (OEM power saving): notifications every ~180 ms
        /// carrying ~14 samples. Nothing was LOST (hang time accrues from the Progressor's
        /// own µs deltas), but readout and trace lagged the hand by up to ~0.4 s; iOS runs
        /// the same gauge at 30 ms.
        ///
        /// `CONNECTION_PRIORITY_HIGH` asks for ~11.25–15 ms with no slave latency, the
        /// closest to CoreBluetooth's default. A REQUEST: peripheral, controller or OEM can
        /// refuse or renegotiate back, so nothing depends on it.
        ///
        /// Dropped to BALANCED when the stream stops: a fast interval costs battery on both
        /// ends, and an idle connected gauge reads nothing.
        fun requestStreamingConnectionInterval(streaming: Boolean) {
            val gatt = tuningGatt ?: return
            // A link-layer request, not an ATT write: calling the platform directly keeps
            // Nordic's queued negotiation from holding up tare, start or stop. Failure is
            // harmless; retry after cooldown.
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

/// TRANSLATION NOTE: a `private extension ProgressorEvent` beside the Swift client.
internal val ProgressorEvent.isCommandReply: Boolean
    get() = this is ProgressorEvent.Battery ||
        this is ProgressorEvent.AppVersion ||
        this is ProgressorEvent.ErrorInformation ||
        this is ProgressorEvent.CommandResponse
