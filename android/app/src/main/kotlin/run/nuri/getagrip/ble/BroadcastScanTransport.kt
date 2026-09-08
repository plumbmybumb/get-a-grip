// SPDX-License-Identifier: MPL-2.0 AND BSD-2-Clause
// Original contributions Copyright 2026 Nuri Bruner.

package run.nuri.getagrip.ble

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import run.nuri.getagrip.engine.WHC06Codec

/// Android plumbing only. The client owns retry policy, link identity and decoding, so
/// those rules can be tested without a phone or a mocked Bluetooth framework.
internal interface BroadcastScanTransport {
    /// Idle means the radio is available; other values explain why it is not.
    val radioState: ProgressorConnectionState
    fun observeRadio(onChanged: () -> Unit)
    fun stopObservingRadio()
    fun start(listener: Listener): Boolean
    fun stop(listener: Listener)
    interface Listener {
        fun onAdvertisement(advertisement: BroadcastAdvertisement)
        fun onFailure(errorCode: Int)
    }
}

internal data class BroadcastAdvertisement(
    val address: String,
    val name: String?,
    /// Android strips the two-byte company identifier from this value.
    val manufacturerPayload: ByteArray,
    /// ScanResult uses the same elapsed-realtime clock as SystemHostClock.
    val observedUptimeSeconds: Double,
)

/// COARSE filter only: the client's codec and address lock still reject other devices.
/// Non-null empty data filters on company ID, avoiding screen-off suspension of unfiltered
/// scans. Sharing a company ID does not prevent using it as a first filter.
internal fun whc06ScanFilters(): List<ScanFilter> = listOf(
    ScanFilter.Builder().setManufacturerData(WHC06Codec.companyID, byteArrayOf()).build(),
)

internal class AndroidBroadcastScanTransport(context: Context) : BroadcastScanTransport {
    private val appContext = context.applicationContext
    private val manager = appContext.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
    private val adapter: BluetoothAdapter? get() = manager?.adapter
    private var receiver: BroadcastReceiver? = null
    private data class Registration(val scanner: BluetoothLeScanner, val callback: ScanCallback)
    private val registrations = mutableMapOf<BroadcastScanTransport.Listener, Registration>()

    override val radioState: ProgressorConnectionState
        get() = try {
            when {
                adapter == null -> ProgressorConnectionState.Unsupported
                adapter?.isEnabled != true -> ProgressorConnectionState.BluetoothOff
                else -> ProgressorConnectionState.Idle
            }
        } catch (_: SecurityException) {
            ProgressorConnectionState.Unauthorized
        }

    override fun observeRadio(onChanged: () -> Unit) {
        if (receiver != null) return
        val next = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                if (intent?.action == BluetoothAdapter.ACTION_STATE_CHANGED) onChanged()
            }
        }
        appContext.registerReceiver(next, IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED))
        receiver = next
    }

    override fun stopObservingRadio() {
        val previous = receiver ?: return
        receiver = null
        runCatching { appContext.unregisterReceiver(previous) }
    }

    override fun start(listener: BroadcastScanTransport.Listener): Boolean {
        val scanner = adapter?.bluetoothLeScanner ?: return false
        // A new callback for each registration lets the client reject retired callbacks.
        val callback = object : ScanCallback() {
            private fun deliver(result: ScanResult) {
                val record = result.scanRecord ?: return
                val payload = record.getManufacturerSpecificData(WHC06Codec.companyID) ?: return
                val name = record.deviceName ?: try { result.device.name } catch (_: SecurityException) { null }
                listener.onAdvertisement(BroadcastAdvertisement(
                    address = result.device.address,
                    name = name,
                    manufacturerPayload = payload.copyOf(),
                    observedUptimeSeconds = result.timestampNanos / 1_000_000_000.0,
                ))
            }
            override fun onScanResult(callbackType: Int, result: ScanResult) = deliver(result)
            override fun onBatchScanResults(results: MutableList<ScanResult>) = results.forEach(::deliver)
            override fun onScanFailed(errorCode: Int) = listener.onFailure(errorCode)
        }
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .setMatchMode(ScanSettings.MATCH_MODE_AGGRESSIVE)
            .setCallbackType(ScanSettings.CALLBACK_TYPE_ALL_MATCHES)
            .setReportDelay(0)
            .build()
        registrations[listener] = Registration(scanner, callback)
        try {
            scanner.startScan(whc06ScanFilters(), settings, callback)
        } catch (error: RuntimeException) {
            registrations.remove(listener)
            runCatching { scanner.stopScan(callback) }
            throw error
        }
        return true
    }

    override fun stop(listener: BroadcastScanTransport.Listener) {
        val previous = registrations.remove(listener) ?: return
        runCatching { previous.scanner.stopScan(previous.callback) }
    }
}
