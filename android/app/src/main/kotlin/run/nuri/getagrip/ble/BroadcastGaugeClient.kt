// SPDX-License-Identifier: MPL-2.0 AND BSD-2-Clause
// Original contributions Copyright 2026 Nuri Bruner.

package run.nuri.getagrip.ble

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
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
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import run.nuri.getagrip.engine.ForceSample
import run.nuri.getagrip.engine.GaugeKind
import run.nuri.getagrip.engine.L10n
import run.nuri.getagrip.engine.ProgressorCommand
import run.nuri.getagrip.engine.ProgressorEvent
import run.nuri.getagrip.engine.SyntheticSampleClock
import run.nuri.getagrip.engine.WHC06Codec

/// The WH-C06 crane scale, which never connects to anything.
///
/// It is a broadcast-only device: the weight is inside its BLE ADVERTISEMENT, so there is
/// no GATT link, no characteristic to subscribe to and nothing to write. Everything this
/// client does is a scan, and the words "connected" and "streaming" have to be
/// reinterpreted around that:
///
/// - **`connect()` starts scanning; the first matching advertisement IS the connection.**
///   There is no handshake to wait for and nothing to fail halfway.
/// - **`disconnect()` stops scanning**, and silence does the same thing on its own:
///   `WHC06Codec.advertisementSilenceSeconds` without a frame is treated as a dropped
///   link, exactly as the reference does with its own 10-second timer.
/// - **Every advertisement is a reading**, so the scan asks for all of them:
///   `CALLBACK_TYPE_ALL_MATCHES` with `MATCH_MODE_AGGRESSIVE` and no report delay. That is
///   also the reason `sustainsBackgroundStreaming` is false for this kind — Android
///   throttles and coalesces a backgrounded scan whatever the app asks for, so a
///   backgrounded session would silently stop measuring; the runner pauses instead.
/// - **No service filter**, because the scale advertises no services at all. The filter is
///   the manufacturer-data check inside `WHC06Codec`.
///
/// Tare is app-side arithmetic (`SoftwareTare`): the scale has no tare command, and its
/// reading includes whatever sling and hardware is hanging from it.
///
/// TRANSLATION NOTE: iOS asks for `CBCentralManagerScanOptionAllowDuplicatesKey`. Android's
/// equivalent is a scan whose callback type is ALL_MATCHES and whose report delay is zero —
/// the default for both — plus `MATCH_MODE_AGGRESSIVE`, which lowers the signal threshold a
/// hardware-offloaded filter needs before it will report a hit at all.
///
/// Protocol knowledge ported from hangtime-grip-connect (BSD-2-Clause, © 2024
/// Stevie-Ray Hartog, https://github.com/Stevie-Ray/hangtime-grip-connect).
class BroadcastGaugeClient(
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

    override val kind: GaugeKind = GaugeKind.whc06

    private val appContext = context.applicationContext

    private companion object {
        /// How long a scan waits for the FIRST advertisement before giving up. Same 15 s the
        /// other clients allow a scan, so "Searching…" never spins forever.
        const val firstFrameDeadlineMillis = 15_000L

        /// The silence watchdog's poll interval — see `startSilenceWatchdog`.
        const val silencePollMillis = 1_000L
    }

    private val bluetoothManager =
        appContext.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
    private val adapter: BluetoothAdapter? get() = bluetoothManager?.adapter

    /// Set only by an accepted explicit `connect()`, and kept across radio power loss so
    /// switching Bluetooth back on resumes the same intent.
    private var wantsConnection = false

    /// **The scale we are listening to, locked on the first frame.** Two of these in one gym
    /// would otherwise interleave their weights into one trace, and nothing in the
    /// advertisement says which pull belongs to which climber. Released on a link loss, so
    /// the next matching advertiser can take over.
    ///
    /// First-come, and there is nothing better available: the scale's company identifier
    /// (0x0100) is shared with TomTom, and the reference's own name filter is commented out
    /// because these units advertise inconsistently — so the codec's length-and-capacity
    /// check is the whole filter. **Known limitation, not a solved problem:** another device
    /// on that company ID whose payload happens to fit could take the lock, and the only
    /// cure is that it goes quiet for ten seconds or the user disconnects.
    ///
    /// TRANSLATION NOTE: iOS locks on `CBPeripheral.identifier`, a per-install UUID. The
    /// Android twin is the hardware address string from `ScanResult.device.address`, which
    /// is the resolvable-private address the scale is currently advertising — stable for the
    /// life of one link, which is all the lock needs.
    private var lockedAddress: String? = null

    private var silenceJob: Job? = null
    private var firstFrameJob: Job? = null

    /// Host MONOTONIC uptime of the newest frame — never wall time, which steps under NTP
    /// and could make a live scale look ten seconds gone.
    private var lastFrameUptime: Double? = null
    private var generation: ULong = 0uL
    private var isScanning = false

    private val softwareTare = SoftwareTare()

    private var radioReceiver: BroadcastReceiver? = null

    // MARK: - ProgressorClient

    override fun connect() {
        if (wantsConnection || state.isBusy || state.isConnected) return
        wantsConnection = true

        // LAZY on purpose, same as every other client: nothing about Bluetooth is touched
        // until a Connect tap asks for it, and the permission prompt belongs to that tap.
        registerRadioReceiver()
        if (adapter?.isEnabled != true) {
            state = radioState()
            return
        }
        beginScan(ScanReason.userInitiated)
    }

    override fun disconnect() {
        wantsConnection = false
        generation += 1uL
        stopScan()
        cancelAllJobs()
        lockedAddress = null
        softwareTare.reset()
        deviceName = null
        unregisterRadioReceiver()
        state = ProgressorConnectionState.Disconnected(reason = null)
    }

    /// Nothing to put to sleep: the scale runs its own power-down timer and has no command
    /// surface to ask. Stopping the scan is the whole of what this app can do, and it is
    /// also all that saves any battery on THIS phone.
    override fun sleepDevice() {
        disconnect()
    }

    override fun send(command: ProgressorCommand) {
        when (command) {
            ProgressorCommand.tare ->
                // App-side zero. Captures the newest reading; with none observed the offset
                // is left alone — see `SoftwareTare`.
                softwareTare.capture()

            ProgressorCommand.enterSleep -> sleepDevice()

            ProgressorCommand.stopWeightMeasurement -> {
                // **Deliberately a no-op.** The scan IS the link here, so stopping it on a
                // stream stop would read as a disconnect and then need a reconnect to undo.
                // The store's own `isStreaming` flag is what governs recording; the scale
                // goes on broadcasting either way, which is the truth this screen should
                // show.
            }

            else -> {
                // No command surface at all: there is nothing to write to. The scale reports
                // no battery either, which is why `hasStandardBattery` is false for this
                // kind.
            }
        }
    }

    /// Re-arms the scan. There is no start command to write, but a wedged scan is the one
    /// failure this can actually repair — which makes the store's silence watchdog useful
    /// here for exactly the same reason it is useful on a Tindeq.
    ///
    /// It reports the start as WRITTEN because scanning is the only act that can make data
    /// flow: the alternative is a breadcrumb ring showing a start requested and never
    /// delivered, which reads as a stall in the log that exists to explain stalls.
    override fun startStreaming(cause: StreamStartCause) {
        if (!wantsConnection || adapter?.isEnabled != true) {
            onDiagnostic?.invoke(ProgressorClientDiagnostic.StreamStartDeferred(cause))
            return
        }
        // **An active scan IS the stream — never bounce it.** Restarting buys nothing (there
        // is no device-side state to re-arm) and costs a dead window while the radio spins
        // back up. On real hardware (Nuri, 2026-08-17) that dead window fed the very silence
        // the runner's watchdog re-kicks on: each re-kick bounced the scan, the bounce caused
        // the next gap, the gap the next re-kick — his "blips of signal" loop. Broadcast
        // delivery is best-effort and BURSTY; while the scan is running, a re-kick's job is
        // already being done.
        //
        // On Android this rule is doubly load-bearing: five scan starts inside 30 seconds
        // silences the scanner for the next 30, with no error and no callback at all.
        if (isScanning) {
            onDiagnostic?.invoke(ProgressorClientDiagnostic.StreamStartWritten(cause))
            return
        }
        beginScan(ScanReason.rekick)
        onDiagnostic?.invoke(ProgressorClientDiagnostic.StreamStartWritten(cause))
    }

    // MARK: - Scanning

    /// Why a scan is starting, which is the only thing that differs between the three.
    private enum class ScanReason {
        /// A Connect tap. Somebody is watching "Searching…", so this one gives up after
        /// `firstFrameDeadlineMillis` rather than spinning forever.
        userInitiated,

        /// An established link went quiet. Waits INDEFINITELY on purpose — the same rule a
        /// dropped Tindeq gets: a session waits for the gauge, it never decides the workout
        /// is over. These scales also power themselves down between uses, so "gone for
        /// twenty seconds" is an ordinary event here, not a failure.
        reacquire,

        /// A stream re-kick while frames are still arriving. Repairs a wedged scan without
        /// touching the published state or the deadlines.
        rekick,
    }

    /// **Does not bump `generation`** — that counter tracks the LINK, not the scan, and a
    /// re-kick while connected must leave the silence deadline armed. Bumping it here made
    /// every stream re-kick disarm the one watchdog that can notice a scale going quiet, so
    /// a wedged scan would have sat "connected" with no data forever.
    private fun beginScan(reason: ScanReason) {
        if (!wantsConnection) return
        val scanner = adapter?.bluetoothLeScanner ?: return
        if (reason != ScanReason.rekick) state = ProgressorConnectionState.Scanning
        if (!isScanning) {
            val settings = ScanSettings.Builder()
                .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
                .setMatchMode(ScanSettings.MATCH_MODE_AGGRESSIVE)
                .setCallbackType(ScanSettings.CALLBACK_TYPE_ALL_MATCHES)
                .setReportDelay(0)
                .build()
            try {
                // No filter list at all — the scale advertises no services, and its company
                // ID is not unique enough to be one. `WHC06Codec` is the filter.
                scanner.startScan(emptyList(), settings, scanCallback)
                isScanning = true
            } catch (_: SecurityException) {
                state = ProgressorConnectionState.Unauthorized
                return
            }
        }
        if (reason == ScanReason.userInitiated) startFirstFrameDeadline(generation)
    }

    private fun stopScan() {
        if (!isScanning) return
        isScanning = false
        runCatching { adapter?.bluetoothLeScanner?.stopScan(scanCallback) }
    }

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            onMain { didAdvertise(result) }
        }

        override fun onBatchScanResults(results: MutableList<ScanResult>) {
            onMain { results.forEach { didAdvertise(it) } }
        }

        override fun onScanFailed(errorCode: Int) {
            onMain {
                isScanning = false
                state = ProgressorConnectionState.Disconnected(L10n.tr("No scale found"))
            }
        }
    }

    private fun didAdvertise(result: ScanResult) {
        if (adapter?.isEnabled != true || !wantsConnection) return

        // **The company-ID prefix has to be put BACK.** `WHC06Codec` documents its offsets
        // against CoreBluetooth's manufacturer-data value, which INCLUDES the two-byte
        // little-endian company ID; Android's `getManufacturerSpecificData(id)` strips it.
        // Handing the codec the stripped payload shifts every offset by two and yields a
        // plausible WRONG load — which is exactly the failure the codec's own header warns
        // about.
        val record = result.scanRecord ?: return
        val payload = record.getManufacturerSpecificData(WHC06Codec.companyID) ?: return
        val framed = ByteArray(payload.size + 2)
        framed[0] = (WHC06Codec.companyID and 0xFF).toByte()
        framed[1] = ((WHC06Codec.companyID shr 8) and 0xFF).toByte()
        payload.copyInto(framed, 2)
        val rawKg = WHC06Codec.kilogramsFromManufacturerData(framed) ?: return

        // Stay with one scale for the life of the link.
        val address = result.device.address
        val locked = lockedAddress
        if (locked != null && locked != address) return
        lockedAddress = address

        val advertised = record.deviceName
        if (advertised != null) {
            deviceName = advertised
        } else if (deviceName == null) {
            // The scale is documented to advertise as `IF_B7` on some units, so the name is
            // never a filter — but it is worth showing whatever it gives.
            deviceName = try {
                result.device.name
            } catch (_: SecurityException) {
                null
            }
        }

        if (!state.isConnected) {
            firstFrameJob?.cancel()
            firstFrameJob = null
            state = ProgressorConnectionState.Connected
        }

        // One uptime read, used for both jobs: the sample's stamp and the silence watchdog's
        // "when did we last hear anything".
        val uptime = clock.uptimeSeconds()
        lastFrameUptime = uptime
        startSilenceWatchdog()

        // One advertisement is one sample and therefore one batch. The stamp is synthetic:
        // the scale sends no clock, and the runner clamps per-sample credit for exactly this
        // reason — an RF gap between advertisements is a radio fact, not a measurement of
        // somebody's fingers.
        val kg = softwareTare.value(rawKg)
        withPacket(uptime) {
        onEvent?.invoke(
            ProgressorEvent.Sample(
                ForceSample(
                    kg = kg,
                    deviceMicros = SyntheticSampleClock.micros(uptime),
                    isBatchStart = true,
                ),
            ),
        )
        }
    }

    private fun startFirstFrameDeadline(generation: ULong) {
        firstFrameJob?.cancel()
        firstFrameJob = scope.launch(Dispatchers.Main.immediate) {
            delay(firstFrameDeadlineMillis)
            if (this@BroadcastGaugeClient.generation != generation) return@launch
            if (state != ProgressorConnectionState.Scanning) return@launch
            // No retry budget, unlike the connected clients: with no handshake to establish,
            // a second attempt is byte-for-byte the same scan. Five identical scans would
            // only spend five times the radio — and on Android would trip the scan quota
            // outright.
            wantsConnection = false
            stopScan()
            cancelAllJobs()
            state = ProgressorConnectionState.Disconnected(L10n.tr("No scale found"))
        }
    }

    /// Ten seconds without a frame is a dropped link. It is the reference's own rule, and it
    /// is the ONLY disconnect signal a broadcast device can give: switched off, out of range
    /// and battery-flat are indistinguishable from here.
    ///
    /// **One job for the whole link, polling a stored timestamp** — deliberately not a fresh
    /// ten-second job per advertisement. Frames arrive around eight times a second, so
    /// cancel-and-restart would allocate eight coroutines a second and throw each away a
    /// frame later. The cost is that the disconnect lands within a second of the deadline
    /// instead of on it, which nothing here can tell apart.
    private fun startSilenceWatchdog() {
        if (silenceJob != null) return
        val watchGeneration = generation
        silenceJob = scope.launch(Dispatchers.Main.immediate) {
            while (isActive) {
                delay(silencePollMillis)
                if (generation != watchGeneration || !state.isConnected) return@launch
                val last = lastFrameUptime ?: continue
                if (clock.uptimeSeconds() - last < WHC06Codec.advertisementSilenceSeconds) continue
                handleSilence()
                return@launch
            }
        }
    }

    private fun handleSilence() {
        silenceJob?.cancel()
        silenceJob = null
        lastFrameUptime = null
        // A new link generation: nothing owed by the old one may fire against the next.
        generation += 1uL
        // The zero was captured against a link that is gone; the sling may not even be on
        // the next one.
        softwareTare.reset()
        lockedAddress = null
        deviceName = null
        state = ProgressorConnectionState.Disconnected(L10n.tr("Scale stopped broadcasting"))
        // And straight back to scanning, because the intent has not changed and the scale
        // coming back is the likely case.
        //
        // TRANSLATION NOTE: iOS stops the scan and starts a fresh one, which is also its
        // repair for a wedged scan. Android's scan quota makes a stop/start pair the most
        // expensive thing available, and the scan never actually stopped here — nothing
        // told it to — so the running scan is kept and only the link state is reset. The
        // wedged-scan repair remains reachable through `startStreaming`, whose own guard
        // starts a scan only when there is genuinely none running.
        beginScan(ScanReason.reacquire)
    }

    /// Every caller of this is a link ending, so the watchdog's timestamp goes with the
    /// watchdog: a stale "last heard" would let the next link inherit a ten-second-old
    /// deadline and disconnect itself on its first tick.
    private fun cancelAllJobs() {
        silenceJob?.cancel()
        silenceJob = null
        lastFrameUptime = null
        firstFrameJob?.cancel()
        firstFrameJob = null
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
                    // The radio came back with the user's original intent still standing, so
                    // this is that same Connect tap resuming — deadline included.
                    if (wantsConnection) beginScan(ScanReason.userInitiated)
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

    private fun handlePowerUnavailable() {
        stopScan()
        cancelAllJobs()
        generation += 1uL
        lockedAddress = null
        softwareTare.reset()
        deviceName = null
        state = radioState()
    }

    private fun radioState(): ProgressorConnectionState = when {
        adapter == null -> ProgressorConnectionState.Unsupported
        adapter?.isEnabled != true -> ProgressorConnectionState.BluetoothOff
        else -> ProgressorConnectionState.Idle
    }

    private fun onMain(block: () -> Unit) {
        scope.launch(Dispatchers.Main.immediate) { block() }
    }
}
