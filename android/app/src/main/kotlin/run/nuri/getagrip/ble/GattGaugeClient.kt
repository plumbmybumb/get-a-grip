// SPDX-License-Identifier: MPL-2.0 AND BSD-2-Clause
// Original contributions Copyright 2026 Nuri Bruner.

package run.nuri.getagrip.ble

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import no.nordicsemi.android.ble.BleManager
import no.nordicsemi.android.ble.observer.ConnectionObserver
import run.nuri.getagrip.engine.ForceSample
import run.nuri.getagrip.engine.FrezDynoCodec
import run.nuri.getagrip.engine.GaugeFrameDecoder
import run.nuri.getagrip.engine.GaugeGattProfile
import run.nuri.getagrip.engine.GaugeKind
import run.nuri.getagrip.engine.L10n
import run.nuri.getagrip.engine.ProgressorCommand
import run.nuri.getagrip.engine.ProgressorEvent
import run.nuri.getagrip.engine.SyntheticSampleClock
import java.util.UUID
import kotlin.math.max
import kotlin.math.min

/// Every connected gauge that is NOT a Tindeq Progressor.
///
/// One client for six devices: after the codec nothing device-specific is left. Discover
/// the profile's service, subscribe to its notify characteristic (plus any
/// `alternateNotifyCharacteristicUUIDs`), write `oneTimeSetupPayloads` once, write
/// `streamStartPayloads` in order (paced by `startPayloadDelaySeconds` where needed), and
/// hand every notification to `kind.makeFrameDecoder()`. The differences live in
/// `GaugeGattProfile` and the codec.
///
/// **Deliberately simpler than `LiveProgressorClient`.** It must not copy the Tindeq's
/// serialized queries (tag-0 replies carry no echo), tare-integrity latch or peripheral
/// quarantine. A GATT read's reply names its characteristic, so nothing can cross-pair; a
/// tare is one plain write or app-side arithmetic; and every device here is a PORT of a
/// documented protocol never held in hand, so the honest shape is the small one.
///
/// Protocol knowledge ported from hangtime-grip-connect (BSD-2-Clause, © 2024 Stevie-Ray
/// Hartog, https://github.com/Stevie-Ray/hangtime-grip-connect).
class GattGaugeClient(
    context: Context,
    private val scope: CoroutineScope,
    override val kind: GaugeKind,
    private val profile: GaugeGattProfile,
    private val clock: HostClock = SystemHostClock,
    /// Answers the coefficient question for a gauge that `requiresRemoteCalibration`; null
    /// for every other kind. See `FrezCalibration.kt` for the rules keeping this the app's
    /// only non-platform network call.
    private val calibration: GaugeCalibrationResolver? = null,
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
    private val capabilities = kind.capabilities

    private val serviceUUID: UUID = UUID.fromString(profile.serviceUUID)
    private val notifyUUID: UUID = UUID.fromString(profile.notifyCharacteristicUUID)

    /// Extra characteristics subscribed ALONGSIDE `notifyUUID` — the Entralpi's second
    /// "rx", which the reference also subscribes to because its source cannot say which one
    /// streams.
    private val alternateNotifyUUIDs: List<UUID> =
        profile.alternateNotifyCharacteristicUUIDs.map { UUID.fromString(it) }
    private val writeUUID: UUID? = profile.writeCharacteristicUUID?.let { UUID.fromString(it) }
    private val tareUUID: UUID? = profile.tareCharacteristicUUID?.let { UUID.fromString(it) }

    private companion object {
        /// The standard Battery Service, read once at connect for kinds that claim it. A
        /// device without it leaves the row blank, which is the honest answer.
        val batteryLevelUUID: UUID = UUID.fromString("00002A19-0000-1000-8000-00805F9B34FB")

        /// Firmware Revision String (Device Information, 0x180A). Every ported device lists
        /// it; one read, and nothing depends on it arriving.
        val firmwareRevisionUUID: UUID =
            UUID.fromString("00002A26-0000-1000-8000-00805F9B34FB")

        /// Software Revision String, read only when there is no Firmware Revision — Frez
        /// publishes the Dyno's version here.
        val softwareRevisionUUID: UUID =
            UUID.fromString("00002A28-0000-1000-8000-00805F9B34FB")

        /// Serial Number String, read only for a gauge whose per-device coefficient is
        /// keyed by it.
        val serialNumberUUID: UUID =
            UUID.fromString("00002A25-0000-1000-8000-00805F9B34FB")

        const val attemptLimit = 5
        const val scanDeadlineMillis = 15_000L
        const val connectTimeoutMillis = 8_000L
        const val backoffMillis = 1_000L

        /// **A `withResponse` write that is never acknowledged must not wedge the queue for
        /// the life of the link.** One lost ATT response left tare, stop and every re-kick
        /// undeliverable while notifications flowed normally, so neither silence watchdog
        /// could repair it. Two seconds, like `LiveProgressorClient`'s query reply.
        const val writeResponseDeadlineMillis = 2_000L

        /// See `LiveProgressorClient.requestedMtu`. The Motherboard splits frames across
        /// notifications and the CTS500 sends checksummed frames, so a 20-byte ceiling is
        /// the wrong bet; asking costs one round trip.
        const val requestedMtu = 517

        /// **A gauge whose maker names an MTU gets that one.** Frez asks for 85 (a v1 Dyno
        /// notification is 74 bytes); 517 is for boards with no documented framing. The
        /// peripheral answers with what it supports and nothing depends on it.
        fun preferredMtu(kind: GaugeKind): Int = when (kind) {
            GaugeKind.frezdyno -> FrezDynoCodec.preferredMTU
            else -> requestedMtu
        }

        /// **Service UUIDs that are evidence of a SERIAL MODULE, not a device**: the stock
        /// 16-bit vendor services (HM-10/JDY `FFF0`, `FFE0`) and the Nordic/Microchip UART
        /// profiles, shipped on countless products — `entralpi` and `pb700bt` even share
        /// `FFF0` and `FFF4`. Matching on one alone would adopt a stranger's module and
        /// decode its bytes as kilograms (the harm `GaugeKind.selectable`'s PB-700BT
        /// exclusion prevents), so the advertised NAME must agree too. A long-form
        /// vendor-unique service (the Force Board's) is proof on its own.
        val wellKnownServiceUUIDs: Set<String> = setOf(
            "0000FFF0-0000-1000-8000-00805F9B34FB", // HM-10 / JDY BLE-serial
            "0000FFE0-0000-1000-8000-00805F9B34FB", // the same family's other service
            "6E400001-B5A3-F393-E0A9-E50E24DCCA9E", // Nordic UART
            "49535343-FE7D-4AE5-8FA9-9FAFD205E455", // Microchip Transparent UART
        )

        /// **The reference filters every ported device by NAME, not advertised service**
        /// (Web Bluetooth `{ name }` / `{ namePrefix }`); whether any advertises its
        /// primary service UUID is UNVERIFIED. A service-filtered scan could silently find
        /// nothing, so the scan is unfiltered and each hit matches on advertised service
        /// UUID or on the reference's name filters.
        fun nameHints(kind: GaugeKind): List<String> = when (kind) {
            GaugeKind.entralpi -> listOf("ENTRALPI")
            GaugeKind.forceboard -> listOf("Force Board")
            GaugeKind.climbro -> listOf("Climbro")
            GaugeKind.motherboard -> listOf("Motherboard")
            // The reference accepts both, and the two model lines share this protocol.
            GaugeKind.cts500 -> listOf("CTS500", "CTS-300")
            GaugeKind.pb700bt -> listOf("NSD Workout")
            // Frez's own rule: "a device whose advertised name starts with FrezDyno-".
            GaugeKind.frezdyno -> listOf(FrezDynoCodec.advertisedNamePrefix)
            // Not driven by this client: the Progressor has its own, and the WH-C06 is
            // matched on manufacturer data by `BroadcastGaugeClient`.
            GaugeKind.progressor, GaugeKind.whc06 -> emptyList()
        }
    }

    private enum class WriteTarget { stream, tare }

    private class WriteEntry(
        val payload: ByteArray,
        val target: WriteTarget,
        /// Set on the LAST payload of a start sequence, so "start written" means the whole
        /// sequence reached the device.
        val startCause: StreamStartCause?,
    )

    private val bluetoothManager =
        appContext.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
    private val adapter: BluetoothAdapter? get() = bluetoothManager?.adapter

    private var manager: GaugeManager? = null
    private var device: BluetoothDevice? = null

    private var generation: ULong = 0uL
    private var activeGeneration: ULong? = null
    private var scanGeneration: ULong? = null

    private var attemptsRemaining = 0

    /// Set only by an accepted explicit `connect()`, and kept across radio power loss so
    /// Bluetooth coming back resumes the intent — parity with
    /// `LiveProgressorClient.wantsConnection`.
    private var wantsConnection = false
    private var refreshBudgetWhenPoweredOn = false

    private var scanDeadlineJob: Job? = null
    private var backoffJob: Job? = null
    private var writeDeadlineJob: Job? = null

    /// A paced start sequence in flight. See `beginStartSequence`.
    private var startSequenceJob: Job? = null

    /// The lookup in flight for THIS link, cancelled with it: a coefficient for a gone
    /// connection must not mint the next one's decoder.
    private var calibrationJob: Job? = null

    private var isScanning = false

    /// Reconnecting without a scan — see `RememberedGauge`. More urgent than for the
    /// Progressor: this client's scan is UNFILTERED, and Android pauses unfiltered scans
    /// while the screen is off, so a link lost behind a locked screen could never be
    /// rescanned.
    private val remembered = RememberedGauge<BluetoothDevice>()
    private val scanStarts = BudgetedScanStart(scope, scanBudget, clock)

    private val writeQueue = ArrayDeque<WriteEntry>()
    private var inFlightWrite: WriteEntry? = null

    /// Written once per LINK, after subscribing and before any start payload.
    private var oneTimeSetupWritten = false

    /// Link-local: a decoder holding half a reassembled frame must never meet the next
    /// connection's bytes.
    private var decoder: GaugeFrameDecoder? = null
    private var publishedBatteryFraction: Double? = null

    /// Used only when the profile names no hardware tare. Reset with the link: an offset
    /// from one connection's zero is meaningless on the next.
    private val softwareTare = SoftwareTare()

    private var radioReceiver: BroadcastReceiver? = null

    // MARK: - ProgressorClient

    override fun connect() {
        // Same guard as the Tindeq client: `wantsConnection` covers backoff and radio-off
        // gaps where the intent is live; a second tap must not replenish the retry budget.
        if (wantsConnection || state.isBusy || state.isConnected) return

        wantsConnection = true
        attemptsRemaining = attemptLimit
        refreshBudgetWhenPoweredOn = false
        remembered.connectRequested()

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
        remembered.released()
        attemptsRemaining = 0
        generation += 1uL

        stopScan()
        cancelAllJobs()
        scanGeneration = null
        clearLinkState()

        if (device != null) manager?.disconnect()?.enqueue()
        device = null
        activeGeneration = null
        deviceName = null
        unregisterRadioReceiver()
        state = ProgressorConnectionState.Disconnected(reason = null)
    }

    /// **A plain disconnect.** No ported device documents a sleep opcode, and inventing one
    /// guesses at bytes on somebody else's hardware. Dropping the link is what saves their
    /// battery anyway: they idle down once nobody is subscribed.
    override fun sleepDevice() {
        disconnect()
    }

    override fun send(command: ProgressorCommand) {
        when (command) {
            ProgressorCommand.tare -> tareNow()

            ProgressorCommand.startWeightMeasurement -> {
                // Starts carry a cause through one funnel (`startStreaming(cause)`), so a
                // breadcrumb is never a guess about who asked.
            }

            ProgressorCommand.stopWeightMeasurement -> {
                // The radio returns to BALANCED whether or not there is a stop payload: an
                // Entralpi streams while subscribed, so "stopped" means "nobody is
                // reading", when the fast interval stops being worth its battery.
                profile.streamStopPayload?.let { enqueue(it, WriteTarget.stream) }
                manager?.requestStreamingConnectionInterval(streaming = false)
            }

            ProgressorCommand.enterSleep -> sleepDevice()

            ProgressorCommand.getBatteryVoltage -> manager?.readStandardBatteryLevel()

            else -> {
                // Tindeq commands with no counterpart on any ported device. Ignored, not
                // mapped onto a plausible write: a speculative command is a write to
                // untested hardware.
            }
        }
    }

    /// **Never gated on an "is streaming" flag** — that flag can only skip the one command
    /// a session depends on, and re-sending a start is harmless (see `DeviceStore.tare`).
    ///
    /// **No engine timeline break belongs to a re-kick of THIS client.** The runner sends
    /// `RunnerEvent.StreamRestarted` only for a gauge with its own clock; a synthetic stamp
    /// is host uptime, which no device restart rewinds, and a break would clear the accrual
    /// anchor and arming debounce for nothing.
    override fun startStreaming(cause: StreamStartCause) {
        val notifying = manager?.notifyingCount ?: 0
        if (!state.isConnected || notifying == 0) {
            onDiagnostic?.invoke(ProgressorClientDiagnostic.StreamStartDeferred(cause))
            return
        }
        // Link tuning is out of band and cannot occupy the ATT queue; repeated watchdog
        // starts are deduplicated per connection.
        manager?.requestStreamingConnectionInterval(streaming = true)
        if (profile.streamStartPayloads.isEmpty()) {
            // Subscribing IS the start on these devices (the Entralpi streams once
            // notifications are on), so the ring records the start as written — otherwise
            // the log that explains a stalled stream shows a request with no write.
            onDiagnostic?.invoke(ProgressorClientDiagnostic.StreamStartWritten(cause))
            return
        }

        if (profile.startPayloadDelaySeconds > 0) {
            // **A SEQUENCE in flight absorbs further starts; this is NOT the "never gate on
            // isStreaming" mistake.** That rule is about a STATE FLAG that can go stale and
            // skip the start forever. This window always completes (every payload written,
            // or the link gone), so folding delays a redundant write by at most
            // `startPayloadDelaySeconds` — and without it the 500 ms watchdog would re-ask
            // the Motherboard for its calibration table four times in the 2.5 s it takes to
            // arrive.
            if (startSequenceJob != null) {
                onDiagnostic?.invoke(ProgressorClientDiagnostic.StreamStartWritten(cause))
                return
            }
            beginStartSequence(cause)
            return
        }

        profile.streamStartPayloads.forEachIndexed { index, payload ->
            val isLast = index == profile.streamStartPayloads.size - 1
            enqueueStart(payload, if (isLast) cause else null)
        }
    }

    /// Writes the start payloads with the profile's own wait between them. The
    /// Motherboard's reference writes "C", waits up to 2500 ms for the calibration dump,
    /// then writes "S30"; back to back, the start could land in the middle of the device's
    /// reply.
    private fun beginStartSequence(cause: StreamStartCause) {
        val payloads = profile.streamStartPayloads
        val delaySeconds = profile.startPayloadDelaySeconds
        startSequenceJob = scope.launch(Dispatchers.Main.immediate) {
            payloads.forEachIndexed { index, payload ->
                if (index > 0) delay((delaySeconds * 1000).toLong())
                if (!state.isConnected) return@launch
                enqueueStart(payload, if (index == payloads.size - 1) cause else null)
            }
            startSequenceJob = null
        }
    }

    /// Enqueue one start payload, COALESCED against the queue. A byte-identical payload
    /// still unwritten is already this write; the 500 ms watchdog re-kick would otherwise
    /// re-ask the Motherboard for its calibration table before the first ask left. The
    /// breadcrumb is still recorded, since the queued write is what delivers it.
    private fun enqueueStart(payload: ByteArray, cause: StreamStartCause?) {
        val alreadyQueued = writeQueue.any {
            it.target == WriteTarget.stream && it.payload.contentEquals(payload)
        }
        if (alreadyQueued) {
            cause?.let { onDiagnostic?.invoke(ProgressorClientDiagnostic.StreamStartWritten(it)) }
            return
        }
        enqueue(payload, WriteTarget.stream, cause)
    }

    /// Configuration written ONCE per link, after subscribing and before any start payload.
    ///
    /// `streamStartPayloads` is re-sent on every re-kick (~1500 times across a silent
    /// twenty-minute session). The CTS500's sampling-rate command is EEPROM-class and
    /// plausibly resets the ADC, so folded into the start it could prevent the stream it
    /// tries to revive.
    private fun writeOneTimeSetupPayloadsIfNeeded() {
        if (oneTimeSetupWritten || profile.oneTimeSetupPayloads.isEmpty()) return
        oneTimeSetupWritten = true
        for (payload in profile.oneTimeSetupPayloads) enqueue(payload, WriteTarget.stream)
    }

    // MARK: - Tare

    /// Hardware tare when the profile names one, app-side arithmetic otherwise. **Mutually
    /// exclusive:** a device that zeroes itself must not also have an offset subtracted, or
    /// readings come out short by the load that was on it (the reference's
    /// `clearTareOffset()` guards the same double-adjust).
    private fun tareNow() {
        val payload = profile.tarePayload
        if (manager?.tareCharacteristic != null && payload != null) {
            softwareTare.reset()
            enqueue(payload, WriteTarget.tare)
            return
        }
        // Captures the newest reading as the offset. The reference averages five seconds;
        // here Tare must take effect in the frame it is tapped, and `TarePolicy` already
        // refuses a reading that is not live. With no reading the offset is LEFT ALONE, not
        // zeroed: a fresh link is already zero, and discarding a good offset would shift
        // every later reading.
        softwareTare.capture()
    }

    // MARK: - Writes

    private fun enqueue(
        payload: ByteArray,
        target: WriteTarget,
        startCause: StreamStartCause? = null,
    ) {
        writeQueue.addLast(WriteEntry(payload, target, startCause))
        drainWriteQueue()
    }

    /// Queued and PACED, never back to back: the Motherboard's text start and the CTS500's
    /// checksummed frame would lose a payload if two went out in one turn.
    ///
    /// TRANSLATION NOTE: iOS also polls `canSendWriteWithoutResponse` because CoreBluetooth
    /// silently discards unbuffered writes; Nordic's request queue removes that. What
    /// remains is ONE outstanding acknowledged write, so a lost response is never mistaken
    /// for delivery.
    private fun drainWriteQueue() {
        val bleManager = manager ?: return
        if (!state.isConnected) return
        val current = device ?: return
        if (!isCurrent(current)) return

        while (writeQueue.isNotEmpty()) {
            val next = writeQueue.first()
            val characteristic = bleManager.characteristic(next.target)
            if (characteristic == null) {
                // The link lacks this write's characteristic. Drop rather than hold the
                // queue: the tare falls back to arithmetic, and the stop could never
                // arrive.
                writeQueue.removeFirst()
                continue
            }

            val withResponse = characteristic.properties and
                BluetoothGattCharacteristic.PROPERTY_WRITE != 0
            if (withResponse && inFlightWrite != null) return

            val entry = writeQueue.removeFirst()
            if (withResponse) {
                inFlightWrite = entry
                armInFlightWriteDeadline()
            }
            bleManager.writePayload(characteristic, entry.payload, withResponse)
            entry.startCause?.let {
                onDiagnostic?.invoke(ProgressorClientDiagnostic.StreamStartWritten(it))
            }
            if (withResponse) return
        }
    }

    /// The one recovery from a write response that never comes: treat it as lost and drain,
    /// like an acknowledgement. Not retried here; the store's silence watchdog re-sends a
    /// lost start.
    private fun armInFlightWriteDeadline() {
        val writeGeneration = generation
        writeDeadlineJob?.cancel()
        writeDeadlineJob = scope.launch(Dispatchers.Main.immediate) {
            delay(writeResponseDeadlineMillis)
            if (generation != writeGeneration || inFlightWrite == null) return@launch
            inFlightWrite = null
            writeDeadlineJob = null
            drainWriteQueue()
        }
    }

    private fun clearInFlightWrite() {
        inFlightWrite = null
        writeDeadlineJob?.cancel()
        writeDeadlineJob = null
    }

    // MARK: - Connection flow

    private fun beginAttemptIfPossible() {
        if (!wantsConnection || adapter?.isEnabled != true) return
        if (state.isConnected || state.isBusy) return

        if (attemptsRemaining <= 0) {
            wantsConnection = false
            state = ProgressorConnectionState.Disconnected(
                L10n.tr("Connection attempts exhausted"),
            )
            return
        }

        backoffJob?.cancel()
        backoffJob = null
        attemptsRemaining -= 1
        generation += 1uL
        scanGeneration = generation

        // BLE links belong to the system, so after a relaunch the gauge may already be
        // connected. Worth trying even though these devices may not advertise the service.
        val hints = nameHints(kind)
        val known = try {
            bluetoothManager?.getConnectedDevices(BluetoothProfile.GATT)?.firstOrNull { candidate ->
                val name = candidate.name?.lowercase()
                name != null && hints.any { name.startsWith(it.lowercase()) }
            }
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

    /// Unfiltered — see `nameHints`. **A running scan is REUSED, never restarted:** five
    /// starts in 30 seconds silences Android's scanner for the next 30 with no error or
    /// callback, and the retry ladder is exactly that shape.
    ///
    /// A start the shared budget cannot afford waits — see `BudgetedScanStart`.
    private fun startScan(generation: ULong) {
        val scanner = adapter?.bluetoothLeScanner
        if (scanner == null) {
            failAttempt(L10n.tr("No Bluetooth"))
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
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()
        try {
            scanner.startScan(emptyList(), settings, scanCallback)
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
            onMain { results.forEach { didDiscover(it) } }
        }

        override fun onScanFailed(errorCode: Int) {
            onMain {
                stopScan()
                scanStarts.failed(errorCode)
                failAttempt(L10n.tr("No %s found", kind.displayName))
            }
        }
    }

    private fun didDiscover(result: ScanResult) {
        if (adapter?.isEnabled != true || !wantsConnection) return
        if (state != ProgressorConnectionState.Scanning) return
        if (device != null || scanGeneration != generation) return
        if (!matches(result)) return
        result.scanRecord?.deviceName?.let { deviceName = it }
        attach(result.device, generation)
    }

    /// The scan is unfiltered, so THIS is the filter: the profile's service if advertised,
    /// otherwise the reference's name filters.
    ///
    /// **A service-UUID match alone proves only a LONG-FORM vendor-unique service.** For
    /// the shared serial/UART services (`wellKnownServiceUUIDs`) the name must agree too,
    /// or a stranger's HM-10 is adopted as an Entralpi and its bytes decoded as kilograms —
    /// arming reps, banking hang time, setting targets from a fabricated max.
    private fun matches(result: ScanResult): Boolean {
        val record = result.scanRecord
        val advertisedName = record?.deviceName ?: try {
            result.device.name
        } catch (_: SecurityException) {
            null
        }
        val hints = nameHints(kind)
        // Prefix, not equality: the reference uses `namePrefix` for the Climbro and exact
        // names elsewhere, and a unit appending a serial ("Force Board 214") still matches.
        val nameMatches = advertisedName?.lowercase()?.let { folded ->
            hints.any { folded.startsWith(it.lowercase()) }
        } ?: false

        val advertisesService =
            record?.serviceUuids?.any { it.uuid == serviceUUID } == true
        if (advertisesService) {
            // With no name filter the service stands; only reachable by a kind this client
            // does not drive.
            if (hints.isEmpty()) return true
            if (!wellKnownServiceUUIDs.contains(profile.serviceUUID.uppercase())) return true
            return nameMatches
        }
        return nameMatches
    }

    /// `autoConnect`: see `AttemptRoute.awaitInRange` — no timeout, by design.
    private fun attach(found: BluetoothDevice, generation: ULong, autoConnect: Boolean = false) {
        if (!wantsConnection || adapter?.isEnabled != true) return
        if (this.generation != generation) return

        stopScan()
        scanDeadlineJob?.cancel()
        scanDeadlineJob = null
        scanGeneration = null
        device = found
        activeGeneration = generation
        // Never overwrite an advertised name with a null device name: it is what the scan
        // matched on, and often the only name until the link is up.
        deviceName = try {
            found.name
        } catch (_: SecurityException) {
            null
        } ?: deviceName
        state = ProgressorConnectionState.Connecting

        val bleManager = manager ?: GaugeManager(appContext).also {
            it.setConnectionObserver(observer)
            manager = it
        }
        remembered.attaching(autoConnect)
        try {
            val request = bleManager.connect(found)
                .useAutoConnect(autoConnect)
                .retry(0)
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
            if (this@GattGaugeClient.generation != generation) return@launch
            if (scanGeneration != generation) return@launch
            if (state != ProgressorConnectionState.Scanning) return@launch
            stopScan()
            failAttempt(L10n.tr("No %s found", kind.displayName))
        }
    }

    private fun scheduleRetry() {
        if (!wantsConnection || attemptsRemaining <= 0) {
            wantsConnection = false
            return
        }
        val retryGeneration = generation
        backoffJob?.cancel()
        // One second. This is also why no Tindeq-style quarantine slot is needed: a
        // cancelled link's terminal callback belongs to a superseded generation and is
        // ignored, and the backoff keeps a rescan from racing its own cancellation.
        backoffJob = scope.launch(Dispatchers.Main.immediate) {
            delay(backoffMillis)
            if (generation != retryGeneration) return@launch
            if (!wantsConnection || adapter?.isEnabled != true) return@launch
            beginAttemptIfPossible()
        }
    }

    private fun failAttempt(reason: String, cancelling: Boolean = false) {
        val active = device
        if (cancelling && (active == null || !isCurrent(active))) return
        invalidateAttempt()
        if (cancelling) manager?.disconnect()?.enqueue()
        state = ProgressorConnectionState.Disconnected(reason)
        if (!wantsConnection || attemptsRemaining <= 0) {
            wantsConnection = false
            return
        }
        scheduleRetry()
    }

    private fun invalidateAttempt() {
        generation += 1uL
        stopScan()
        cancelAllJobs()
        scanGeneration = null
        activeGeneration = null
        device = null
        deviceName = null
        clearLinkState()
    }

    private fun handlePowerUnavailable() {
        refreshBudgetWhenPoweredOn = wantsConnection
        attemptsRemaining = 0
        val hadLink = device != null
        invalidateAttempt()
        if (hadLink) manager?.disconnect()?.enqueue()
        state = radioState()
    }

    private fun isCurrent(candidate: BluetoothDevice): Boolean {
        val current = device ?: return false
        if (current != candidate) return false
        return activeGeneration == generation
    }

    private fun clearLinkState() {
        manager?.forgetCharacteristics()
        oneTimeSetupWritten = false
        writeQueue.clear()
        // The paced sequence belongs to this link; a job left in the slot would fold every
        // future start into a finished sequence.
        startSequenceJob?.cancel()
        startSequenceJob = null
        // A coefficient still in flight belongs to the link that asked for it.
        calibrationJob?.cancel()
        calibrationJob = null
        clearInFlightWrite()
        // Both die with the link: a half-reassembled frame and a captured zero are facts
        // about one connection.
        decoder = null
        publishedBatteryFraction = null
        softwareTare.reset()
    }

    private fun cancelAllJobs() {
        scanDeadlineJob?.cancel()
        scanDeadlineJob = null
        scanStarts.cancel()
        backoffJob?.cancel()
        backoffJob = null
        writeDeadlineJob?.cancel()
        writeDeadlineJob = null
    }

    // MARK: - Radio state

    private fun registerRadioReceiver() {
        if (radioReceiver != null) return
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                if (intent?.action != BluetoothAdapter.ACTION_STATE_CHANGED) return
                onMain {
                    if (adapter?.isEnabled != true) {
                        handlePowerUnavailable()
                        return@onMain
                    }
                    if (refreshBudgetWhenPoweredOn) {
                        attemptsRemaining = attemptLimit
                        refreshBudgetWhenPoweredOn = false
                    }
                    if (wantsConnection) beginAttemptIfPossible()
                }
            }
        }
        radioReceiver = receiver
        appContext.registerReceiver(receiver, IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED))
    }

    private fun unregisterRadioReceiver() {
        val receiver = radioReceiver ?: return
        radioReceiver = null
        runCatching { appContext.unregisterReceiver(receiver) }
    }

    private fun radioState(): ProgressorConnectionState = when {
        adapter == null -> ProgressorConnectionState.Unsupported
        adapter?.isEnabled != true -> ProgressorConnectionState.BluetoothOff
        else -> ProgressorConnectionState.Idle
    }

    // MARK: - Link callbacks

    private val observer = object : ConnectionObserver {
        override fun onDeviceConnecting(device: BluetoothDevice) = Unit
        override fun onDeviceConnected(device: BluetoothDevice) = Unit

        override fun onDeviceFailedToConnect(device: BluetoothDevice, reason: Int) {
            onMain {
                if (!isCurrent(device)) return@onMain
                invalidateAttempt()
                state = ProgressorConnectionState.Disconnected(reasonText(reason))
                if (!wantsConnection || attemptsRemaining <= 0) {
                    wantsConnection = false
                    return@onMain
                }
                scheduleRetry()
            }
        }

        override fun onDeviceReady(device: BluetoothDevice) {
            onMain { subscriptionsSettled(device) }
        }

        override fun onDeviceDisconnecting(device: BluetoothDevice) = Unit

        override fun onDeviceDisconnected(device: BluetoothDevice, reason: Int) {
            onMain { handleDisconnect(device, reason) }
        }
    }

    /// Every notify candidate has answered (iOS: `pendingSubscriptions` reaches zero).
    /// **Established when the primary OR any alternate is notifying**; one Entralpi
    /// candidate may refuse or stay mute, and only ALL failing is a device that cannot
    /// stream.
    private fun subscriptionsSettled(settled: BluetoothDevice) {
        if (!isCurrent(settled)) return
        val bleManager = manager ?: return
        if (bleManager.notifyingCount == 0) {
            failAttempt(L10n.tr("Notification subscription failed"), cancelling = true)
            return
        }
        attemptsRemaining = attemptLimit
        remembered.established(settled)
        // BEFORE publishing `Connected`, which runs synchronously into `DeviceStore` and
        // can reach `startStreaming` in the same turn: setup writes must be queued ahead of
        // any start.
        writeOneTimeSetupPayloadsIfNeeded()
        state = ProgressorConnectionState.Connected

        // Fire-and-forget and NOT serialized: a GATT read's reply names its characteristic,
        // so two reads cannot cross-pair the way a Tindeq version reply once parsed as
        // battery millivolts.
        bleManager.readStandardBatteryLevel()
        bleManager.readVersionString()
        beginCalibrationIfNeeded()
        drainWriteQueue()
    }

    // MARK: - Remote calibration

    /// Frez's connection order, honoured exactly: subscribe, read the serial, fetch the
    /// coefficient, only then turn counts into kilograms. The start may already be queued
    /// (nothing gates it), and until the decoder exists `ingest` drops its notifications —
    /// the fail-closed answer Frez asks for.
    private fun beginCalibrationIfNeeded() {
        if (!capabilities.requiresRemoteCalibration) return
        val bleManager = manager ?: return
        if (!bleManager.hasSerialCharacteristic) {
            reportCalibration(
                GaugeCalibrationStatus.Failed(null, GaugeCalibrationFailure.MissingSerial),
            )
            return
        }
        reportCalibration(GaugeCalibrationStatus.WaitingForSerial)
        val readGeneration = generation
        // The serial's FAILURE must be reported, or a calibrated gauge sits at "connected"
        // with no force and no explanation.
        bleManager.readSerialNumber { serial ->
            if (generation != readGeneration || !state.isConnected) return@readSerialNumber
            resolveCalibration(serial)
        }
    }

    /// The serial arrived (or failed). One lookup per link, tied to the asking generation,
    /// so an answer for a gone connection mints nothing.
    private fun resolveCalibration(rawSerial: String?) {
        val serial = rawSerial?.trim { it.isWhitespace() || it == '\u0000' } ?: ""
        if (serial.isEmpty()) {
            reportCalibration(
                GaugeCalibrationStatus.Failed(null, GaugeCalibrationFailure.MissingSerial),
            )
            return
        }
        val resolver = calibration
        if (resolver == null) {
            reportCalibration(
                GaugeCalibrationStatus.Failed(serial, GaugeCalibrationFailure.NoAccessKey),
            )
            return
        }
        reportCalibration(GaugeCalibrationStatus.Resolving(serial))
        val resolveGeneration = generation
        calibrationJob?.cancel()
        calibrationJob = scope.launch(Dispatchers.Main.immediate) {
            val answer = resolver.calibration(serial)
            if (generation != resolveGeneration || !state.isConnected) return@launch
            calibrationJob = null
            when (answer) {
                is GaugeCalibrationAnswer.Resolved -> {
                    decoder = kind.makeCalibratedFrameDecoder(answer.calibration.coefficient)
                    reportCalibration(
                        GaugeCalibrationStatus.Ready(serial, answer.calibration),
                    )
                }
                is GaugeCalibrationAnswer.Unavailable ->
                    reportCalibration(GaugeCalibrationStatus.Failed(serial, answer.failure))
            }
        }
    }

    private fun reportCalibration(status: GaugeCalibrationStatus) {
        onDiagnostic?.invoke(ProgressorClientDiagnostic.Calibration(status))
    }

    private fun handleDisconnect(disconnected: BluetoothDevice, reason: Int) {
        if (!isCurrent(disconnected)) return

        val wasEstablished = state.isConnected
        val wasAutoConnect = remembered.linkLost()
        invalidateAttempt()
        // Close a lost autoConnect link (cancelling a pending connection answers at once,
        // and the superseded generation ignores it) and let the one-second backoff follow.
        // Why this differs from `LiveProgressorClient`: see `RememberedGauge`.
        if (wasAutoConnect) manager?.disconnect()?.enqueue()

        // Radio state is authoritative: its receiver already published off, and a late
        // disconnect must not overwrite it or rescan.
        if (adapter?.isEnabled != true) return

        state = ProgressorConnectionState.Disconnected(reasonText(reason))
        if (!wantsConnection) return

        if (wasEstablished) {
            // Subscribing reset the budget, so a dropped established link starts a fresh
            // cycle, waiting for the SAME gauge.
            attemptsRemaining = attemptLimit
            remembered.awaitReturn()
            if (wasAutoConnect) scheduleRetry() else beginAttemptIfPossible()
        } else if (attemptsRemaining > 0) {
            scheduleRetry()
        } else {
            wantsConnection = false
        }
    }

    private fun reasonText(reason: Int): String = when (reason) {
        ConnectionObserver.REASON_TIMEOUT -> L10n.tr("Connection timed out")
        ConnectionObserver.REASON_NOT_SUPPORTED ->
            L10n.tr("Not a %s", kind.displayName)
        ConnectionObserver.REASON_SUCCESS -> L10n.tr("Disconnected")
        else -> L10n.tr("Couldn't connect")
    }

    // MARK: - Sample ingestion

    private fun ingest(data: ByteArray, receivedAt: Double) = withPacket(receivedAt) {
        val working = decoder ?: return@withPacket
        val readings = working.ingest(data)

        val fraction = working.batteryFraction
        if (fraction != null && fraction != publishedBatteryFraction) {
            // Climbro carries battery inside the data stream rather than in 0x180F.
            publishedBatteryFraction = fraction
            onEvent?.invoke(ProgressorEvent.BatteryFraction(min(1.0, max(0.0, fraction))))
        }

        if (readings.isEmpty()) return@withPacket

        // **One stamp per NOTIFICATION, shared by every reading in it.** These devices say
        // nothing about spacing inside a frame, and spreading at the nominal rate would
        // invent timing the engine credits as hang time. Interior deltas are zero and the
        // next notification's delta carries the whole interval, so the SUM the engine
        // accrues is exactly the time that passed. `isBatchStart` marks the first reading
        // (one notification, one batch, as on the Tindeq).
        val arrival = SyntheticSampleClock.micros(clock.uptimeSeconds())
        readings.forEachIndexed { index, reading ->
            val kg = softwareTare.value(reading.kg)
            onEvent?.invoke(
                ProgressorEvent.Sample(
                    ForceSample(
                        kg = kg,
                        deviceMicros = reading.deviceMicros ?: arrival,
                        isBatchStart = index == 0,
                    ),
                ),
            )
        }
    }

    private fun onMain(block: () -> Unit) {
        scope.launch(Dispatchers.Main.immediate) { block() }
    }

    // MARK: - Nordic manager

    /// **Discovery walks EVERY service**: the ForceBoard's write and tare characteristics
    /// live in other services, and Battery is its own service. `GaugeGattProfile` names
    /// characteristics, not services, so look everywhere; one extra discovery round, twice
    /// a day.
    private inner class GaugeManager(context: Context) : BleManager(context) {
        var notifyCharacteristic: BluetoothGattCharacteristic? = null
        var controlCharacteristic: BluetoothGattCharacteristic? = null
        var tareCharacteristic: BluetoothGattCharacteristic? = null
        private var batteryCharacteristic: BluetoothGattCharacteristic? = null
        private var firmwareCharacteristic: BluetoothGattCharacteristic? = null
        private var softwareRevisionCharacteristic: BluetoothGattCharacteristic? = null
        private var serialCharacteristic: BluetoothGattCharacteristic? = null
        private var streamCharacteristics: List<BluetoothGattCharacteristic> = emptyList()

        var notifyingCount: Int = 0
            private set

        private var tuningGatt: BluetoothGatt? = null
        private val streamingPriority = StreamingConnectionPriority()

        override fun isRequiredServiceSupported(gatt: BluetoothGatt): Boolean {
            forgetCharacteristics()
            tuningGatt = gatt
            val services = gatt.services ?: emptyList()
            if (services.none { it.uuid == serviceUUID }) return false

            // Notify and write PREFER the profile's own service and fall back to anywhere:
            // a 16-bit UUID like `fff4` is not unique across a service table, and the wrong
            // one looks like a device that connects and never speaks.
            var notifyElsewhere: BluetoothGattCharacteristic? = null
            var writeElsewhere: BluetoothGattCharacteristic? = null
            val alternates = ArrayList<BluetoothGattCharacteristic>()

            for (service in services) {
                val isProfileService = service.uuid == serviceUUID
                for (characteristic in service.characteristics ?: emptyList()) {
                    val uuid = characteristic.uuid
                    if (uuid == notifyUUID) {
                        if (isProfileService) {
                            notifyCharacteristic = characteristic
                        } else if (notifyElsewhere == null) {
                            notifyElsewhere = characteristic
                        }
                    }
                    if (writeUUID != null && uuid == writeUUID) {
                        if (isProfileService) {
                            controlCharacteristic = characteristic
                        } else if (writeElsewhere == null) {
                            writeElsewhere = characteristic
                        }
                    }
                    // Alternates match ANYWHERE: the Entralpi's second candidate sits under
                    // the Weight Scale service, not the UART one — why the reference
                    // subscribes to both.
                    if (alternateNotifyUUIDs.contains(uuid) && alternates.none { it === characteristic }) {
                        alternates.add(characteristic)
                    }
                    if (tareUUID != null && uuid == tareUUID && tareCharacteristic == null) {
                        tareCharacteristic = characteristic
                    }
                    if (capabilities.hasStandardBattery && uuid == batteryLevelUUID &&
                        batteryCharacteristic == null
                    ) {
                        batteryCharacteristic = characteristic
                    }
                    if (uuid == firmwareRevisionUUID && firmwareCharacteristic == null) {
                        firmwareCharacteristic = characteristic
                    }
                    if (uuid == softwareRevisionUUID && softwareRevisionCharacteristic == null) {
                        softwareRevisionCharacteristic = characteristic
                    }
                    if (capabilities.requiresRemoteCalibration && uuid == serialNumberUUID &&
                        serialCharacteristic == null
                    ) {
                        serialCharacteristic = characteristic
                    }
                }
            }

            if (notifyCharacteristic == null) notifyCharacteristic = notifyElsewhere
            if (controlCharacteristic == null) controlCharacteristic = writeElsewhere

            // The profile's notify characteristic FIRST, then alternates, keeping only
            // those that can push. A present-but-mute characteristic is not a candidate,
            // nor a failure on a device with alternates.
            val candidates = ArrayList<BluetoothGattCharacteristic>()
            for (candidate in listOfNotNull(notifyCharacteristic) + alternates) {
                val pushes = candidate.properties and (
                    BluetoothGattCharacteristic.PROPERTY_NOTIFY or
                        BluetoothGattCharacteristic.PROPERTY_INDICATE
                    ) != 0
                if (!pushes) continue
                if (candidates.none { it === candidate }) candidates.add(candidate)
            }
            if (candidates.isEmpty()) return false
            if (profile.streamStartPayloads.isNotEmpty() && controlCharacteristic == null) {
                return false
            }
            streamCharacteristics = candidates
            // Minted HERE, one per link, dropped by `clearLinkState` — except for a gauge
            // needing a coefficient, which gets its decoder in `resolveCalibration` and
            // none before: without a slope it could only invent numbers, and Frez's rule is
            // no calibrated force until the lookup succeeds.
            decoder = if (capabilities.requiresRemoteCalibration) null else kind.makeFrameDecoder()
            return true
        }

        override fun initialize() {
            val notificationGeneration = activeGeneration
            requestMtu(preferredMtu(kind)).enqueue()
            notifyingCount = 0
            for (candidate in streamCharacteristics) {
                setNotificationCallback(candidate).with { _, packet ->
                    val receivedAt = clock.uptimeSeconds()
                    val bytes = packet.value ?: ByteArray(0)
                    onMain {
                        if (activeGeneration != notificationGeneration || notificationGeneration == null) return@onMain
                        ingest(bytes, receivedAt)
                    }
                }
                val indicates = candidate.properties and
                    BluetoothGattCharacteristic.PROPERTY_NOTIFY == 0
                val request = if (indicates) {
                    enableIndications(candidate)
                } else {
                    enableNotifications(candidate)
                }
                request.done { notifyingCount += 1 }.enqueue()
            }
        }

        override fun onServicesInvalidated() {
            tuningGatt = null
            streamingPriority.reset()
            forgetCharacteristics()
        }

        fun forgetCharacteristics() {
            tuningGatt = null
            streamingPriority.reset()
            notifyCharacteristic = null
            controlCharacteristic = null
            tareCharacteristic = null
            batteryCharacteristic = null
            firmwareCharacteristic = null
            softwareRevisionCharacteristic = null
            serialCharacteristic = null
            streamCharacteristics = emptyList()
            notifyingCount = 0
        }

        /// The high-priority connection interval, requested while streaming — measured on
        /// hardware and explained at
        /// `LiveProgressorClient.ProgressorManager.requestStreamingConnectionInterval`.
        ///
        /// **It matters MORE here than for a Progressor.** These devices have no clock;
        /// samples are stamped from host uptime, so arrival jitter IS the timebase hang
        /// time accrues from (hence `SamplePacing.syntheticClockGapCapSeconds`). A request
        /// only; nothing depends on it.
        fun requestStreamingConnectionInterval(streaming: Boolean) {
            val gatt = tuningGatt ?: return
            // Platform call, not an ATT write — see the Progressor twin.
            streamingPriority.update(streaming, clock.uptimeSeconds()) { active ->
                try {
                    gatt.requestConnectionPriority(
                        if (active) BluetoothGatt.CONNECTION_PRIORITY_HIGH
                        else BluetoothGatt.CONNECTION_PRIORITY_BALANCED,
                    )
                } catch (_: SecurityException) { false }
            }
        }

        fun characteristic(target: WriteTarget): BluetoothGattCharacteristic? = when (target) {
            WriteTarget.stream -> controlCharacteristic
            WriteTarget.tare -> tareCharacteristic
        }

        fun writePayload(
            characteristic: BluetoothGattCharacteristic,
            payload: ByteArray,
            withResponse: Boolean,
        ) {
            val type = if (withResponse) {
                BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
            } else {
                BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
            }
            writeCharacteristic(characteristic, payload, type)
                // Dropped, not retried. The Tindeq client retries because its tare must be
                // ACKed before a stream may start; here the store's silence watchdog
                // re-sends a lost start — one recovery path, not two that can disagree.
                .done { onMain { clearInFlightWrite(); drainWriteQueue() } }
                .fail { _, _ -> onMain { clearInFlightWrite(); drainWriteQueue() } }
                .enqueue()
        }

        fun readStandardBatteryLevel() {
            val characteristic = batteryCharacteristic ?: return
            readCharacteristic(characteristic).with { _, packet ->
                // 0x2A19 is one byte of PERCENT, 0…100. An empty read is simply not a
                // battery level.
                val percent = packet.value?.firstOrNull() ?: return@with
                val fraction = min(1.0, max(0.0, (percent.toInt() and 0xFF) / 100.0))
                onMain {
                    publishedBatteryFraction = fraction
                    onEvent?.invoke(ProgressorEvent.BatteryFraction(fraction))
                }
            }.enqueue()
        }

        /// Firmware Revision, or Software Revision for a device that puts its version
        /// there. One read; nothing depends on it.
        fun readVersionString() {
            val characteristic = firmwareCharacteristic ?: softwareRevisionCharacteristic ?: return
            readCharacteristic(characteristic).with { _, packet ->
                val bytes = packet.value ?: return@with
                val text = String(bytes, Charsets.UTF_8).trim { it <= ' ' }
                if (text.isEmpty()) return@with
                onMain { onEvent?.invoke(ProgressorEvent.AppVersion(text)) }
            }.enqueue()
        }

        val hasSerialCharacteristic: Boolean get() = serialCharacteristic != null

        /// The serial, or null for a failed or empty read. **Answers either way**: the
        /// caller can show no force until this lands, and a read that never calls back is a
        /// screen stuck on "connected".
        fun readSerialNumber(onAnswer: (String?) -> Unit) {
            val characteristic = serialCharacteristic ?: return onAnswer(null)
            readCharacteristic(characteristic)
                .with { _, packet ->
                    val text = packet.value?.let { String(it, Charsets.UTF_8) }
                    onMain { onAnswer(text) }
                }
                .fail { _, _ -> onMain { onAnswer(null) } }
                .enqueue()
        }
    }
}
