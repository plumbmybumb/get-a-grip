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
/// One client for six devices, because after the codec there is nothing device-specific
/// left in the wire handling: discover the profile's service, subscribe to its notify
/// characteristic (plus any `alternateNotifyCharacteristicUUIDs`), write
/// `oneTimeSetupPayloads` once, write `streamStartPayloads` in order — paced by
/// `startPayloadDelaySeconds` where a device needs it — and hand every notification to
/// `kind.makeFrameDecoder()`. What differs between an Entralpi and a Motherboard lives
/// entirely in `GaugeGattProfile` and the codec, which is the point of freezing those two
/// shapes.
///
/// **Deliberately simpler than `LiveProgressorClient`.** The Tindeq client carries three
/// mechanisms this one must not copy: serialized queries (its tag-0 replies carry no echo
/// of the command they answer, so one outstanding query is the only safe number), the
/// tare-integrity latch, and the peripheral quarantine. None of them applies here. A GATT
/// read's reply names its own characteristic, so nothing can cross-pair; a tare is either
/// one plain write or app-side arithmetic; and every one of these devices is a PORT of
/// hangtime-grip-connect's documented protocol that this project has never held in its
/// hands, so the honest shape is the small one, with the hard-won Tindeq machinery left
/// where it was earned.
///
/// Protocol knowledge ported from hangtime-grip-connect (BSD-2-Clause, © 2024
/// Stevie-Ray Hartog, https://github.com/Stevie-Ray/hangtime-grip-connect).
class GattGaugeClient(
    context: Context,
    private val scope: CoroutineScope,
    override val kind: GaugeKind,
    private val profile: GaugeGattProfile,
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
    private val capabilities = kind.capabilities

    private val serviceUUID: UUID = UUID.fromString(profile.serviceUUID)
    private val notifyUUID: UUID = UUID.fromString(profile.notifyCharacteristicUUID)

    /// Extra characteristics to subscribe ALONGSIDE `notifyUUID` — the Entralpi's second
    /// "rx", which the reference also subscribes to because its source cannot say which of
    /// the two actually streams.
    private val alternateNotifyUUIDs: List<UUID> =
        profile.alternateNotifyCharacteristicUUIDs.map { UUID.fromString(it) }
    private val writeUUID: UUID? = profile.writeCharacteristicUUID?.let { UUID.fromString(it) }
    private val tareUUID: UUID? = profile.tareCharacteristicUUID?.let { UUID.fromString(it) }

    private companion object {
        /// The standard Battery Service. Read once at connect for the kinds whose
        /// capabilities claim it; a device that does not advertise it simply has no battery
        /// characteristic to find and the row stays blank, which is the honest answer.
        val batteryLevelUUID: UUID = UUID.fromString("00002A19-0000-1000-8000-00805F9B34FB")

        /// Firmware Revision String, in Device Information (0x180A). Every ported device's
        /// service table in the reference lists it, so Settings' Firmware row can be filled
        /// for free — one read, one event, and nothing depends on it arriving.
        val firmwareRevisionUUID: UUID =
            UUID.fromString("00002A26-0000-1000-8000-00805F9B34FB")

        const val attemptLimit = 5
        const val scanDeadlineMillis = 15_000L
        const val connectTimeoutMillis = 8_000L
        const val backoffMillis = 1_000L

        /// **A `withResponse` write that is never acknowledged must not wedge the queue for
        /// the life of the link.** One lost ATT response left tare, stop and every later
        /// re-kick undeliverable while notifications kept arriving perfectly — the store's
        /// freshness watchdog and the runner's silence watchdog both see a healthy stream
        /// and neither can repair this. Two seconds, the same deadline
        /// `LiveProgressorClient` gives a query reply.
        const val writeResponseDeadlineMillis = 2_000L

        /// See `LiveProgressorClient.requestedMtu`. These devices send smaller frames than
        /// a Progressor batch, but the Motherboard splits one frame across notifications
        /// and the CTS500 sends checksummed frames — a 20-byte payload ceiling is the wrong
        /// bet on any of them, and asking costs one round trip per link.
        const val requestedMtu = 517

        /// **Service UUIDs that are evidence of a SERIAL MODULE, not of a device.** These
        /// are the stock 16-bit vendor services (HM-10/JDY `FFF0` and `FFE0`) and the two
        /// ubiquitous UART profiles (Nordic, Microchip), all of which ship on countless
        /// unrelated products — this repo proves it: `entralpi` and `pb700bt` declare the
        /// same `FFF0` AND the same `FFF4` notify characteristic. Matching a scan hit on
        /// one of them alone would adopt a stranger's serial module and hand its bytes to a
        /// codec that turns two of them into kilograms, which is the harm
        /// `GaugeKind.selectable`'s PB-700BT exclusion exists to prevent. For these, the
        /// advertised NAME has to agree as well; a long-form vendor-unique service (the
        /// Force Board's) remains proof on its own.
        val wellKnownServiceUUIDs: Set<String> = setOf(
            "0000FFF0-0000-1000-8000-00805F9B34FB", // HM-10 / JDY BLE-serial
            "0000FFE0-0000-1000-8000-00805F9B34FB", // the same family's other service
            "6E400001-B5A3-F393-E0A9-E50E24DCCA9E", // Nordic UART
            "49535343-FE7D-4AE5-8FA9-9FAFD205E455", // Microchip Transparent UART
        )

        /// **Every ported device in the reference is filtered by NAME rather than by
        /// advertised service.** Web Bluetooth's `requestDevice` takes `{ name }` /
        /// `{ namePrefix }` filters, which is what hangtime-grip-connect uses for all six of
        /// these; whether any of them also puts its primary service UUID in the
        /// advertisement packet is UNVERIFIED. A service-filtered scan would therefore
        /// silently find nothing on a device that keeps its service private, so the scan is
        /// unfiltered and each hit is matched two ways: advertised service UUID, or
        /// advertised name against the reference's own filter strings.
        fun nameHints(kind: GaugeKind): List<String> = when (kind) {
            GaugeKind.entralpi -> listOf("ENTRALPI")
            GaugeKind.forceboard -> listOf("Force Board")
            GaugeKind.climbro -> listOf("Climbro")
            GaugeKind.motherboard -> listOf("Motherboard")
            // The reference accepts both, and the two model lines share this protocol.
            GaugeKind.cts500 -> listOf("CTS500", "CTS-300")
            GaugeKind.pb700bt -> listOf("NSD Workout")
            // Not driven by this client: the Progressor has its own, and the WH-C06 is
            // matched on manufacturer data by `BroadcastGaugeClient`.
            GaugeKind.progressor, GaugeKind.whc06 -> emptyList()
        }
    }

    private enum class WriteTarget { stream, tare }

    private class WriteEntry(
        val payload: ByteArray,
        val target: WriteTarget,
        /// Set on the LAST payload of a start sequence, so the ring's "start written"
        /// breadcrumb means the whole sequence reached the device rather than its first
        /// byte.
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
    /// switching Bluetooth back on resumes the same user intent — parity with
    /// `LiveProgressorClient.wantsConnection`.
    private var wantsConnection = false
    private var refreshBudgetWhenPoweredOn = false

    private var scanDeadlineJob: Job? = null
    private var backoffJob: Job? = null
    private var writeDeadlineJob: Job? = null

    /// A paced start sequence in flight. See `beginStartSequence`.
    private var startSequenceJob: Job? = null

    private var isScanning = false

    private val writeQueue = ArrayDeque<WriteEntry>()
    private var inFlightWrite: WriteEntry? = null

    /// Written once per LINK, after subscribing and before any start payload.
    private var oneTimeSetupWritten = false

    /// Link-local, exactly as the protocol requires: a decoder holding half a reassembled
    /// frame must never meet the next connection's bytes.
    private var decoder: GaugeFrameDecoder? = null
    private var publishedBatteryFraction: Double? = null

    /// Used only when the profile names no hardware tare. Reset with the link, because an
    /// offset captured against one connection's zero is meaningless on the next.
    private val softwareTare = SoftwareTare()

    private var radioReceiver: BroadcastReceiver? = null

    // MARK: - ProgressorClient

    override fun connect() {
        // Same guard as the Tindeq client: `wantsConnection` covers the backoff and
        // radio-off gaps, where the public state is not busy but the intent is live. A
        // second tap must not replenish the retry budget.
        if (wantsConnection || state.isBusy || state.isConnected) return

        wantsConnection = true
        attemptsRemaining = attemptLimit
        refreshBudgetWhenPoweredOn = false

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

    /// **A plain disconnect.** No ported device documents a sleep opcode, and inventing a
    /// write for one would be guessing at bytes on somebody else's hardware. Dropping the
    /// link is also what actually saves the battery on these devices: they idle down on
    /// their own schedule once nobody is subscribed.
    override fun sleepDevice() {
        disconnect()
    }

    override fun send(command: ProgressorCommand) {
        when (command) {
            ProgressorCommand.tare -> tareNow()

            ProgressorCommand.startWeightMeasurement -> {
                // Starts carry a cause through one funnel, so a later breadcrumb can never
                // be a guess about who asked. `startStreaming(cause)` is the only start
                // path.
            }

            ProgressorCommand.stopWeightMeasurement -> {
                // The radio goes back to BALANCED whether or not this device has a stop
                // payload to write — an Entralpi streams for as long as it is subscribed, so
                // "stopped" here means "nobody is reading", which is exactly when the fast
                // interval stops being worth its battery.
                profile.streamStopPayload?.let { enqueue(it, WriteTarget.stream) }
                manager?.requestStreamingConnectionInterval(streaming = false)
            }

            ProgressorCommand.enterSleep -> sleepDevice()

            ProgressorCommand.getBatteryVoltage -> manager?.readStandardBatteryLevel()

            else -> {
                // Tindeq control-point commands with no counterpart on any ported device.
                // Silently ignored rather than mapped onto a plausible-looking write: these
                // protocols are ports, and a speculative command is a write to hardware
                // nobody here has tested.
            }
        }
    }

    /// **Never gated on an "is streaming" flag.** That flag can only ever cause the one
    /// command a session depends on to be skipped, and re-sending a start to a device
    /// already streaming is harmless — the rule the first hardware session taught the
    /// Tindeq client, which applies identically here.
    ///
    /// **No engine timeline break belongs to a re-kick of THIS client.** The runner sends
    /// `RunnerEvent.StreamRestarted` around the call only for a gauge with a clock of its
    /// own; a synthetic stamp is host uptime, which no device restart can rewind, so there
    /// is no epoch here to break — and the break would clear the accrual anchor and the
    /// arming debounce for nothing.
    override fun startStreaming(cause: StreamStartCause) {
        val notifying = manager?.notifyingCount ?: 0
        if (!state.isConnected || notifying == 0) {
            onDiagnostic?.invoke(ProgressorClientDiagnostic.StreamStartDeferred(cause))
            return
        }
        // Out-of-band link tuning cannot occupy the ATT command queue. Repeated
        // watchdog starts are deduplicated for the lifetime of this connection.
        manager?.requestStreamingConnectionInterval(streaming = true)
        if (profile.streamStartPayloads.isEmpty()) {
            // Subscribing IS the start on these devices (the Entralpi streams the moment
            // notifications are on). The ring records the start as having reached the
            // device, because it has: the subscription is the act that starts it. Staying
            // silent instead would leave a request with no write in the one log that exists
            // to explain a stalled stream.
            onDiagnostic?.invoke(ProgressorClientDiagnostic.StreamStartWritten(cause))
            return
        }

        if (profile.startPayloadDelaySeconds > 0) {
            // **A SEQUENCE in flight absorbs further starts, and that is NOT the "never
            // gate the start on isStreaming" mistake.** That rule is about a STATE FLAG,
            // which can go stale and then skip the one command a session depends on
            // forever. This is a time-bounded window that always runs to completion — every
            // payload is written or the link is gone — so folding delays a redundant write
            // by at most `startPayloadDelaySeconds`, and the Motherboard's whole reason for
            // pacing is that the 500 ms watchdog would otherwise re-ask for the calibration
            // table four times inside the 2.5 s it takes to arrive.
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

    /// Writes the start payloads with the profile's own wait between them.
    ///
    /// The Motherboard is why: its reference writes "C", waits up to 2500 ms for the
    /// calibration dump, and only then writes "S30". Back-to-back through the paced queue
    /// the two land milliseconds apart, which can put the start command in the middle of
    /// the device's own reply.
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

    /// Enqueue one start payload, COALESCED against the queue.
    ///
    /// A byte-identical start payload still sitting unwritten is the write this cause is
    /// asking for, so a second copy would only spend the radio twice: the watchdog re-kicks
    /// every 500 ms while a stream is silent, and on the Motherboard that meant asking for
    /// the calibration table again before the first ask had left the queue. The breadcrumb
    /// is still recorded — the ring must not show a start requested with nothing delivering
    /// it, when the queued write is what delivers it.
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

    /// Configuration written ONCE per link, right after subscribing and before any start
    /// payload can be enqueued.
    ///
    /// `streamStartPayloads` is re-sent on every re-kick by design, so a device
    /// configuration folded into it gets re-issued roughly 1500 times across a silent
    /// twenty-minute session. The CTS500's sampling-rate command is EEPROM-class and
    /// plausibly resets the ADC, which would make each re-kick prevent the stream it is
    /// trying to revive.
    private fun writeOneTimeSetupPayloadsIfNeeded() {
        if (oneTimeSetupWritten || profile.oneTimeSetupPayloads.isEmpty()) return
        oneTimeSetupWritten = true
        for (payload in profile.oneTimeSetupPayloads) enqueue(payload, WriteTarget.stream)
    }

    // MARK: - Tare

    /// Hardware tare when the profile names one, app-side arithmetic otherwise. **The two
    /// are mutually exclusive on purpose:** a device that zeroes itself must not also have
    /// an app-side offset subtracted, or the next reading is short by the load that was on
    /// it — the same double-adjust the reference guards with `clearTareOffset()`.
    private fun tareNow() {
        val payload = profile.tarePayload
        if (manager?.tareCharacteristic != null && payload != null) {
            softwareTare.reset()
            enqueue(payload, WriteTarget.tare)
            return
        }
        // Captures the newest reading as the offset. The reference averages five seconds of
        // samples instead; that is wrong for this app, where Tare is a button whose effect
        // must be visible in the frame it is tapped, and `TarePolicy` already refuses to
        // tare against a reading that is not live. With no reading yet the offset is LEFT
        // ALONE rather than zeroed: a fresh link has an offset of zero already, and
        // silently discarding a good offset because the stream went quiet would move every
        // later reading by the load that was on the gauge.
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

    /// Queued and PACED, never fired back to back. The Motherboard's start is a text
    /// command and the CTS500's is a checksummed frame; both would lose a payload if two
    /// went out in one turn.
    ///
    /// TRANSLATION NOTE: on iOS this loop also has to poll `canSendWriteWithoutResponse`,
    /// because CoreBluetooth silently discards an unbuffered write. Nordic's request queue
    /// removes that hazard; what remains is the app-level rule — ONE outstanding
    /// acknowledged write, so a lost response cannot be mistaken for a delivered one.
    private fun drainWriteQueue() {
        val bleManager = manager ?: return
        if (!state.isConnected) return
        val current = device ?: return
        if (!isCurrent(current)) return

        while (writeQueue.isNotEmpty()) {
            val next = writeQueue.first()
            val characteristic = bleManager.characteristic(next.target)
            if (characteristic == null) {
                // The link does not have what this write needs. Dropping it is better than
                // holding the queue: the tare falls back to arithmetic and a stop payload
                // for a missing characteristic was never going to arrive.
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

    /// The one recovery from a write response that never comes. Expiry treats the write as
    /// lost and drains, exactly as an acknowledgement would: a failed write is dropped
    /// rather than retried here, and a lost start is re-sent by the store's silence
    /// watchdog anyway.
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

        // BLE links belong to the system, not to this process, so after a relaunch the
        // gauge may already be connected. Worth a try even though these devices may not
        // advertise the service.
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

        state = ProgressorConnectionState.Scanning
        startScan(generation)
    }

    /// Unfiltered — see `nameHints`. **A running scan is REUSED, never restarted**, which
    /// on Android is not a nicety: five scan starts in 30 seconds silences the scanner for
    /// the next 30, with no error and no callback, and the retry ladder here is exactly the
    /// shape that trips it.
    private fun startScan(generation: ULong) {
        val scanner = adapter?.bluetoothLeScanner
        if (scanner == null) {
            failAttempt(L10n.tr("No Bluetooth"))
            return
        }
        startScanDeadline(generation)
        if (isScanning) return
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()
        try {
            scanner.startScan(emptyList(), settings, scanCallback)
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
            onMain { results.forEach { didDiscover(it) } }
        }

        override fun onScanFailed(errorCode: Int) {
            onMain {
                stopScan()
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

    /// The scan is unfiltered, so THIS is the filter: the profile's service if the device
    /// advertises it, otherwise the reference's own name filters.
    ///
    /// **A service-UUID match alone is only proof for a LONG-FORM vendor-unique service.**
    /// The 16-bit serial services and the two UART profiles are shared by half the BLE
    /// modules in existence (see `wellKnownServiceUUIDs`), so for those kinds the advertised
    /// name has to agree as well — otherwise a stranger's HM-10 in range is adopted as an
    /// Entralpi and its arbitrary bytes are decoded as kilograms, which then arm reps, bank
    /// hang time and set a grip's percentage targets from a fabricated max.
    private fun matches(result: ScanResult): Boolean {
        val record = result.scanRecord
        val advertisedName = record?.deviceName ?: try {
            result.device.name
        } catch (_: SecurityException) {
            null
        }
        val hints = nameHints(kind)
        // Prefix, not equality: the reference uses `namePrefix` for the Climbro and exact
        // names elsewhere, and an exact name is its own prefix. A unit that appends a serial
        // ("Force Board 214") still matches.
        val nameMatches = advertisedName?.lowercase()?.let { folded ->
            hints.any { folded.startsWith(it.lowercase()) }
        } ?: false

        val advertisesService =
            record?.serviceUuids?.any { it.uuid == serviceUUID } == true
        if (advertisesService) {
            // With no name filter to lean on there is nothing better than the service, so
            // it stands; that is only reachable by a kind this client does not drive.
            if (hints.isEmpty()) return true
            if (!wellKnownServiceUUIDs.contains(profile.serviceUUID.uppercase())) return true
            return nameMatches
        }
        return nameMatches
    }

    private fun attach(found: BluetoothDevice, generation: ULong) {
        if (!wantsConnection || adapter?.isEnabled != true) return
        if (this.generation != generation) return

        stopScan()
        scanDeadlineJob?.cancel()
        scanDeadlineJob = null
        scanGeneration = null
        device = found
        activeGeneration = generation
        // Never overwrite an advertised local name with a null device name: the
        // advertisement is what the scan matched on, and on these devices it is often the
        // only name there is until the link is up.
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
        // One second, which is also what keeps this client honest without the Tindeq's
        // quarantine slot: a cancelled link's terminal callback belongs to a superseded
        // generation and is ignored, and the backoff means a rescan never races the
        // cancellation it just issued.
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
        // The paced sequence belongs to this link: its remaining payloads mean nothing on
        // the next one, and a job left in the slot would fold every future start into a
        // sequence that has already returned.
        startSequenceJob?.cancel()
        startSequenceJob = null
        clearInFlightWrite()
        // Both die with the link, and for the same reason: a half-reassembled frame and a
        // captured zero are facts about one connection only.
        decoder = null
        publishedBatteryFraction = null
        softwareTare.reset()
    }

    private fun cancelAllJobs() {
        scanDeadlineJob?.cancel()
        scanDeadlineJob = null
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

    /// Every notify candidate has answered — the moment iOS's `pendingSubscriptions`
    /// reaches zero.
    ///
    /// **The link is established when the primary OR any alternate is notifying.** One of
    /// the Entralpi's two candidates may well refuse or stay mute; only ALL of them failing
    /// is a device that cannot stream.
    private fun subscriptionsSettled(settled: BluetoothDevice) {
        if (!isCurrent(settled)) return
        val bleManager = manager ?: return
        if (bleManager.notifyingCount == 0) {
            failAttempt(L10n.tr("Notification subscription failed"), cancelling = true)
            return
        }
        attemptsRemaining = attemptLimit
        // BEFORE publishing `Connected`, because that publish runs synchronously into
        // `DeviceStore` and can reach `startStreaming` in the same turn — the setup writes
        // have to be in the queue ahead of any start payload.
        writeOneTimeSetupPayloadsIfNeeded()
        state = ProgressorConnectionState.Connected

        // Both reads are fire-and-forget and NOT serialized: unlike the Tindeq control
        // point, a GATT read's reply names the characteristic it came from, so two
        // outstanding reads cannot cross-pair the way a version reply once parsed as
        // battery millivolts.
        bleManager.readStandardBatteryLevel()
        bleManager.readFirmwareRevision()
        drainWriteQueue()
    }

    private fun handleDisconnect(disconnected: BluetoothDevice, reason: Int) {
        if (!isCurrent(disconnected)) return

        val wasEstablished = state.isConnected
        invalidateAttempt()

        // Radio state is authoritative: its own receiver already published the off state,
        // and a late disconnect must not overwrite it or rescan.
        if (adapter?.isEnabled != true) return

        state = ProgressorConnectionState.Disconnected(reasonText(reason))
        if (!wantsConnection) return

        if (wasEstablished) {
            // Subscribing successfully reset the budget, so a dropped established link
            // begins a fresh cycle immediately.
            attemptsRemaining = attemptLimit
            beginAttemptIfPossible()
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

        // **One stamp per NOTIFICATION, shared by every reading it carried.** These devices
        // tell us nothing about the spacing of samples inside a frame, and spreading them at
        // the nominal rate would be inventing timing the engine then credits as hang time.
        // Sharing the stamp keeps the arithmetic honest: interior deltas are zero and
        // accrue nothing, and the next notification's delta carries the whole elapsed
        // interval, so the SUM — which is what the engine accrues — is exactly the time that
        // passed. `isBatchStart` marks the first reading, matching the Tindeq's meaning of
        // one notification, one batch.
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

    /// **Discovery walks EVERY service, not just the profile's one**: the ForceBoard's write
    /// and tare characteristics live in other services, and the Battery Service is a
    /// separate service by definition. `GaugeGattProfile` names characteristics, not the
    /// services that hold them, so the only way to honour it is to look everywhere. It costs
    /// one extra round of discovery on a connect that happens twice a day.
    private inner class GaugeManager(context: Context) : BleManager(context) {
        var notifyCharacteristic: BluetoothGattCharacteristic? = null
        var controlCharacteristic: BluetoothGattCharacteristic? = null
        var tareCharacteristic: BluetoothGattCharacteristic? = null
        private var batteryCharacteristic: BluetoothGattCharacteristic? = null
        private var firmwareCharacteristic: BluetoothGattCharacteristic? = null
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

            // Notify and write PREFER the profile's own service and fall back to a match
            // anywhere: a 16-bit characteristic UUID like `fff4` is not unique across a
            // device's service table, and subscribing to a same-numbered characteristic in
            // the wrong service would look like a device that connects and never speaks.
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
                    // Alternates are matched ANYWHERE outright: the Entralpi's second
                    // candidate is declared under the Weight Scale service, not under the
                    // UART one, which is the whole reason the reference subscribes to both.
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
                }
            }

            if (notifyCharacteristic == null) notifyCharacteristic = notifyElsewhere
            if (controlCharacteristic == null) controlCharacteristic = writeElsewhere

            // The profile's own notify characteristic FIRST, then the alternates, keeping
            // only the ones that can actually push. A characteristic present but mute is not
            // a candidate, and on a device with alternates it is not a failure either.
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
            // The decoder is minted HERE, one per link, and dropped by `clearLinkState`.
            decoder = kind.makeFrameDecoder()
            return true
        }

        override fun initialize() {
            val notificationGeneration = activeGeneration
            requestMtu(requestedMtu).enqueue()
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
            streamCharacteristics = emptyList()
            notifyingCount = 0
        }

        /// **The connection interval, measured on hardware and not guessed.**
        ///
        /// First real session on the Realme RMX5079 (Android 16), from `adb logcat`: within
        /// five seconds of connecting the phone renegotiated the link from a 7.5 ms interval
        /// to **60 ms with slave latency 6** — OEM power saving — so notifications arrived
        /// every ~180 ms in larger batches, and the readout lagged the hand by up to ~0.4 s.
        /// iOS runs the same gauge at 30 ms.
        ///
        /// **It matters MORE here than it does for a Progressor.** These devices have no
        /// clock of their own: their samples are stamped from host uptime at ingestion, so
        /// arrival jitter is not just a display lag — it is the timebase the engine accrues
        /// hang time from (`SamplePacing.syntheticClockGapCapSeconds` exists because of it).
        /// A steadier interval is a more honest measurement.
        ///
        /// A REQUEST, never a guarantee: the peripheral and the controller can refuse it and
        /// an aggressive OEM can renegotiate straight back, so nothing depends on it.
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
                // A failed write is dropped rather than retried. The Tindeq client retries
                // because its tare must be acknowledged before a stream may start; here a
                // lost start is recovered by the store's silence watchdog, which re-sends
                // it — one recovery path instead of two that can disagree.
                .done { onMain { clearInFlightWrite(); drainWriteQueue() } }
                .fail { _, _ -> onMain { clearInFlightWrite(); drainWriteQueue() } }
                .enqueue()
        }

        fun readStandardBatteryLevel() {
            val characteristic = batteryCharacteristic ?: return
            readCharacteristic(characteristic).with { _, packet ->
                // 0x2A19 is one byte of PERCENT, 0…100. Truncation-safe like every other
                // decode here: an empty read is simply not a battery level.
                val percent = packet.value?.firstOrNull() ?: return@with
                val fraction = min(1.0, max(0.0, (percent.toInt() and 0xFF) / 100.0))
                onMain {
                    publishedBatteryFraction = fraction
                    onEvent?.invoke(ProgressorEvent.BatteryFraction(fraction))
                }
            }.enqueue()
        }

        fun readFirmwareRevision() {
            val characteristic = firmwareCharacteristic ?: return
            readCharacteristic(characteristic).with { _, packet ->
                val bytes = packet.value ?: return@with
                val text = String(bytes, Charsets.UTF_8).trim { it <= ' ' }
                if (text.isEmpty()) return@with
                onMain { onEvent?.invoke(ProgressorEvent.AppVersion(text)) }
            }.enqueue()
        }
    }
}
