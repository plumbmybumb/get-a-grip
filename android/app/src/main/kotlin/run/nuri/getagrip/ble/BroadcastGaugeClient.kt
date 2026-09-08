// SPDX-License-Identifier: MPL-2.0 AND BSD-2-Clause
// Original contributions Copyright 2026 Nuri Bruner.

package run.nuri.getagrip.ble

import android.content.Context
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
import kotlin.math.ceil
import kotlin.math.max

/// WH-C06 sends weight in advertisements: no GATT link and nothing to write. Ordinary
/// gaps, tare and stream starts leave its healthy scan alone. Ten seconds of real silence
/// retires the link and replaces its scan, with bounded retries rather than waiting forever
/// on a wedged scanner. Each registration has a fresh listener; a link owns its address
/// lock, tare and watchdog. The scan quota survives disconnect/reconnect and radio-off.
/// Healthy registrations are renewed before Android's long-scan downgrade. This changes
/// only the Android registration, never the live measurement's state, tare or timestamps.
///
/// Protocol knowledge ported from hangtime-grip-connect (BSD-2-Clause, © 2024
/// Stevie-Ray Hartog, https://github.com/Stevie-Ray/hangtime-grip-connect).
class BroadcastGaugeClient internal constructor(
    private val transport: BroadcastScanTransport,
    private val scope: CoroutineScope,
    private val clock: HostClock = SystemHostClock,
) : ProgressorClient {
    constructor(context: Context, scope: CoroutineScope, clock: HostClock = SystemHostClock) :
        this(AndroidBroadcastScanTransport(context), scope, clock)

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

    internal companion object {
        const val firstFrameDeadlineMillis = 15_000L
        const val silencePollMillis = 1_000L
        const val maximumAcquisitionAttempts = 3
        // Below Android's five-start limit. An extra second avoids boundary rounding races.
        const val scanRateWindowSeconds = 31.0
        const val maximumScanStartsPerWindow = 4
        const val scanTooFrequentError = 6
        // AOSP defaults to ten minutes, but the verified Realme uses five. Renew with a
        // one-minute margin; device-config access is privileged, so no permission or hidden
        // API dependency belongs in this app. Short stream watchdog checks never renew.
        const val healthyScanRenewalMillis = 4 * 60 * 1_000L
    }

    private var wantsConnection = false
    private var generation = 0uL
    private var activeScan: BroadcastScanTransport.Listener? = null
    private var lockedAddress: String? = null
    private var lastFrameUptime: Double? = null
    private var lastAdvertisementUptime: Double? = null
    private val softwareTare = SoftwareTare()
    private var silenceJob: Job? = null
    private var firstFrameJob: Job? = null
    private var retryJob: Job? = null
    private var renewalJob: Job? = null
    private var acquisitionAttempts = 0
    private var acquisitionReason = "initial"
    private val recentScanStarts = ArrayDeque<Double>()
    private var scanCooldownUntil = 0.0

    override fun connect() {
        if (wantsConnection || state.isBusy || state.isConnected) return
        wantsConnection = true
        transport.observeRadio { onMain { radioChanged() } }
        if (!radioAvailable()) return
        beginAcquisition("initial")
    }

    override fun disconnect() {
        wantsConnection = false
        generation += 1uL
        cancelAllJobs()
        retireScan("disconnect")
        resetLink()
        transport.stopObservingRadio()
        state = ProgressorConnectionState.Disconnected()
    }

    override fun sleepDevice() = disconnect()

    override fun send(command: ProgressorCommand) {
        when (command) {
            ProgressorCommand.tare -> softwareTare.capture()
            ProgressorCommand.enterSleep -> disconnect()
            // Stopping measurement does not stop its scan/link. The store decides which
            // readings to record. WH-C06 has no hardware commands of any kind.
            else -> Unit
        }
    }

    override fun startStreaming(cause: StreamStartCause) {
        if (!wantsConnection) {
            diagnostic("scan not requested (${cause.label}, disconnected)")
            return
        }
        if (!radioAvailable()) {
            diagnostic("scan unavailable (${cause.label}, radio unavailable)")
            return
        }
        when {
            activeScan != null -> diagnostic("scan already active (${cause.label})")
            retryJob != null -> diagnostic("scan retry pending (${cause.label})")
            else -> requestScan()
        }
        // No tare-deferred/write diagnostics: this gauge has no command surface. Even startScan's
        // return does not confirm the platform's asynchronous registration succeeded.
    }

    private fun beginAcquisition(reason: String) {
        acquisitionAttempts = 0
        acquisitionReason = reason
        state = ProgressorConnectionState.Scanning
        requestScan()
    }

    private fun requestScan(minimumDelaySeconds: Double = 0.0) {
        if (!wantsConnection || activeScan != null || !radioAvailable()) return
        retryJob?.cancel()
        retryJob = null
        val waitSeconds = max(minimumDelaySeconds, scanStartDelaySeconds())
        if (waitSeconds > 0) {
            val watchGeneration = generation
            diagnostic("scan retry in ${ceil(waitSeconds).toInt()} s")
            retryJob = scope.launch(Dispatchers.Main.immediate) {
                delay(ceil(waitSeconds * 1_000).toLong())
                if (generation != watchGeneration || !wantsConnection) return@launch
                retryJob = null
                requestScan()
            }
            return
        }
        startScanAttempt(clock.uptimeSeconds())
    }

    /// Shared by acquisition and healthy renewal. Check BEFORE retiring a healthy scan,
    /// so a quota/cooldown wait cannot manufacture ten seconds of missing measurements.
    private fun scanStartDelaySeconds(): Double {
        val now = clock.uptimeSeconds()
        while (recentScanStarts.isNotEmpty() && now - recentScanStarts.first() >= scanRateWindowSeconds) {
            recentScanStarts.removeFirst()
        }
        val quotaDelay = if (recentScanStarts.size >= maximumScanStartsPerWindow) {
            recentScanStarts.first() + scanRateWindowSeconds - now
        } else 0.0
        return max(0.0, max(quotaDelay, scanCooldownUntil - now))
    }

    private fun startScanAttempt(startedAt: Double) {
        if (acquisitionAttempts >= maximumAcquisitionAttempts) {
            finishFailure("scan recovery exhausted")
            return
        }
        acquisitionAttempts += 1
        recentScanStarts.addLast(startedAt)
        val listener = object : BroadcastScanTransport.Listener {
            override fun onAdvertisement(advertisement: BroadcastAdvertisement) {
                onMain { if (activeScan === this) didAdvertise(advertisement, startedAt) }
            }
            override fun onFailure(errorCode: Int) {
                onMain { if (activeScan === this) scanFailed(errorCode) }
            }
        }
        activeScan = listener
        diagnostic("scan requested ($acquisitionReason, attempt $acquisitionAttempts)")
        try {
            val started = transport.start(listener)
            // A synchronous failure/result is permitted; never arm a retired attempt.
            if (activeScan !== listener) return
            if (!started) {
                attemptFailed("scanner unavailable")
                return
            }
        } catch (_: SecurityException) {
            finishFailure("scan permission denied", ProgressorConnectionState.Unauthorized)
            return
        } catch (_: IllegalStateException) {
            attemptFailed("scanner unavailable")
            return
        }
        if (!state.isConnected) startFirstFrameDeadline(listener)
        scheduleScanRenewal(listener, startedAt + healthyScanRenewalMillis / 1_000.0)
    }

    private fun scheduleScanRenewal(listener: BroadcastScanTransport.Listener, dueAt: Double) {
        renewalJob?.cancel()
        val watchGeneration = generation
        renewalJob = scope.launch(Dispatchers.Main.immediate) {
            delay(max(1L, ceil((dueAt - clock.uptimeSeconds()) * 1_000).toLong()))
            if (generation != watchGeneration || !wantsConnection || activeScan !== listener) return@launch
            renewalJob = null
            if (!state.isConnected || !radioAvailable()) return@launch
            val waitSeconds = scanStartDelaySeconds()
            if (waitSeconds > 0) {
                diagnostic("scan renewal delayed ${ceil(waitSeconds).toInt()} s")
                scheduleScanRenewal(listener, clock.uptimeSeconds() + waitSeconds)
                return@launch
            }
            // This is scanner maintenance, not a lost scale. Keep the link generation,
            // address, tare, last sample and silence watchdog. A failed replacement flows
            // through attemptFailed, which DOES publish an actual link loss before retry.
            retireScan("scheduled renewal")
            acquisitionAttempts = 0
            acquisitionReason = "renewal"
            requestScan()
        }
    }

    private fun scanFailed(errorCode: Int) {
        if (errorCode == scanTooFrequentError) {
            scanCooldownUntil = max(scanCooldownUntil, clock.uptimeSeconds() + scanRateWindowSeconds)
        }
        attemptFailed("scan failed (code $errorCode)")
    }

    private fun attemptFailed(reason: String) {
        diagnostic(reason)
        firstFrameJob?.cancel()
        firstFrameJob = null
        retireScan("failed attempt")
        if (state.isConnected) {
            // onScanFailed normally precedes results. If sent later, publish a real link
            // break before retrying so the runner cannot accrue across the missing link.
            generation += 1uL
            cancelAllJobs()
            resetLink()
            state = ProgressorConnectionState.Disconnected(L10n.tr("Scale stopped broadcasting"))
            if (!wantsConnection) return
            acquisitionAttempts = 0
            acquisitionReason = "recovery"
        }
        if (acquisitionAttempts >= maximumAcquisitionAttempts) {
            finishFailure("scan recovery exhausted")
            return
        }
        state = ProgressorConnectionState.Scanning
        requestScan(if (acquisitionAttempts <= 1) 2.0 else 5.0)
    }

    private fun startFirstFrameDeadline(listener: BroadcastScanTransport.Listener) {
        firstFrameJob?.cancel()
        firstFrameJob = scope.launch(Dispatchers.Main.immediate) {
            delay(firstFrameDeadlineMillis)
            if (activeScan !== listener || state.isConnected || !wantsConnection) return@launch
            firstFrameJob = null
            attemptFailed("scan timed out")
        }
    }

    private fun didAdvertise(advertisement: BroadcastAdvertisement, scanStartedAt: Double) {
        if (!wantsConnection || !radioAvailable()) return
        val receivingScan = activeScan
        // Listener identity rejects retired callbacks; native receipt time additionally
        // rejects buffered pre-scan results delivered through the NEW callback.
        val observedAt = advertisement.observedUptimeSeconds
        if (!observedAt.isFinite() || observedAt < scanStartedAt) return
        if (lastAdvertisementUptime?.let { observedAt <= it } == true) return
        val payload = advertisement.manufacturerPayload
        val framed = ByteArray(payload.size + 2)
        framed[0] = (WHC06Codec.companyID and 0xFF).toByte()
        framed[1] = ((WHC06Codec.companyID shr 8) and 0xFF).toByte()
        payload.copyInto(framed, 2)
        val rawKg = WHC06Codec.kilogramsFromManufacturerData(framed) ?: return
        if (lockedAddress?.let { it != advertisement.address } == true) return
        lockedAddress = advertisement.address
        lastAdvertisementUptime = observedAt
        if (advertisement.name != null) deviceName = advertisement.name
        val uptime = clock.uptimeSeconds()
        lastFrameUptime = uptime
        if (!state.isConnected) {
            firstFrameJob?.cancel()
            firstFrameJob = null
            acquisitionAttempts = 0
            diagnostic("scale advertisements received")
            state = ProgressorConnectionState.Connected
        }
        // State observers may immediately disconnect (for example on backgrounding).
        // Do not publish the just-arrived sample after that decision ended its link.
        if (!wantsConnection || activeScan !== receivingScan) return
        startSilenceWatchdog()
        val kg = softwareTare.value(rawKg)
        withPacket(uptime) {
            onEvent?.invoke(ProgressorEvent.Sample(
                ForceSample(kg, SyntheticSampleClock.micros(uptime), isBatchStart = true),
            ))
        }
    }

    private fun startSilenceWatchdog() {
        if (silenceJob != null) return
        val watchGeneration = generation
        silenceJob = scope.launch(Dispatchers.Main.immediate) {
            while (isActive) {
                delay(silencePollMillis)
                if (generation != watchGeneration || !state.isConnected) return@launch
                val last = lastFrameUptime ?: continue
                if (clock.uptimeSeconds() - last < WHC06Codec.advertisementSilenceSeconds) continue
                silenceJob = null
                handleSilence()
                return@launch
            }
        }
    }

    private fun handleSilence() {
        generation += 1uL
        cancelAllJobs()
        retireScan("advertisement silence")
        resetLink()
        state = ProgressorConnectionState.Disconnected(L10n.tr("Scale stopped broadcasting"))
        if (wantsConnection) beginAcquisition("recovery")
    }

    private fun retireScan(reason: String) {
        renewalJob?.cancel()
        renewalJob = null
        val previous = activeScan ?: return
        activeScan = null
        runCatching { transport.stop(previous) }
        diagnostic("scan stopped ($reason)")
    }

    private fun resetLink() {
        lockedAddress = null
        lastFrameUptime = null
        lastAdvertisementUptime = null
        deviceName = null
        softwareTare.reset()
    }

    private fun cancelAllJobs() {
        silenceJob?.cancel()
        silenceJob = null
        firstFrameJob?.cancel()
        firstFrameJob = null
        retryJob?.cancel()
        retryJob = null
        renewalJob?.cancel()
        renewalJob = null
    }

    private fun finishFailure(
        reason: String,
        terminalState: ProgressorConnectionState = ProgressorConnectionState.Disconnected(L10n.tr("No scale found")),
    ) {
        diagnostic(reason)
        wantsConnection = false
        generation += 1uL
        cancelAllJobs()
        retireScan("giving up")
        resetLink()
        transport.stopObservingRadio()
        state = terminalState
    }

    private fun radioAvailable(): Boolean {
        val radio = transport.radioState
        if (radio == ProgressorConnectionState.Idle) return true
        if (radio == ProgressorConnectionState.Unauthorized || radio == ProgressorConnectionState.Unsupported) {
            finishFailure("radio unavailable", radio)
        } else {
            generation += 1uL
            cancelAllJobs()
            retireScan("radio unavailable")
            resetLink()
            state = radio
        }
        return false
    }

    private fun radioChanged() {
        if (!wantsConnection || !radioAvailable()) return
        if (activeScan == null && retryJob == null) beginAcquisition("radio restored")
    }

    private fun diagnostic(event: String) {
        onDiagnostic?.invoke(ProgressorClientDiagnostic.BroadcastScan(event))
    }

    private fun onMain(block: () -> Unit) {
        scope.launch(Dispatchers.Main.immediate) { block() }
    }
}
