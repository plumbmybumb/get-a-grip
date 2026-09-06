// SPDX-License-Identifier: MPL-2.0
// Original contributions Copyright 2026 Nuri Bruner.

package run.nuri.getagrip.ble

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import run.nuri.getagrip.engine.ForceSample
import run.nuri.getagrip.engine.L10n
import run.nuri.getagrip.engine.ProgressorCommand
import run.nuri.getagrip.engine.ProgressorEvent
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/// A synthetic Progressor.
///
/// This is not a convenience — it is the only way to run the app anywhere other than a
/// physical phone with the real gauge attached, because **the emulator has no working
/// Bluetooth stack**. It is also what demo mode uses, so someone without hardware (a Play
/// reviewer, a curious climber) can see a whole session run.
///
/// **Always compiled in.** A debug-only mock would leave anyone without hardware stuck on
/// a screen that never connects, which is why it is reached two ways on purpose: the
/// `--ez mockDevice true` launch extra (the twin of iOS's `-mockDevice` argument, read by
/// `DeviceStore`) and the always-present "Try demo mode" button in the gauge's
/// disconnected state.
///
/// TRANSLATION NOTE: Swift's `Task` inherits the class's `@MainActor` isolation; Kotlin
/// takes the scope explicitly, and the app hands it one on `Dispatchers.Main.immediate`
/// so every callback still arrives on the main thread. A test hands it a test scope,
/// which is also why the scope is a constructor parameter rather than a field built here.
class MockProgressorClient(
    private val scope: CoroutineScope,
    var profile: MockForceProfile = MockForceProfile.clean,
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

    override val deviceName: String? = "Progressor_MOCK"

    private companion object {
        /// The device streams 80 samples/sec, delivered in batches of ~8.
        const val sampleHz: Double = 80.0
        const val batchSize: Int = 8
        val microsPerSample: UInt = (1_000_000 / 80).toUInt()
        val batteryMillivolts: UInt = 3980u
        const val firmware = "mock-1.0"
    }

    private var connectJob: Job? = null
    private var connectionGeneration: ULong = 0uL
    private var pump: Job? = null
    private var elapsedSamples: ULong = 0uL
    private var deviceMicros: UInt = 0u
    private var tareOffsetKg: Double = 0.0

    // MARK: - ProgressorClient

    override fun connect() {
        if (state.isConnected || state.isBusy) return
        connectionGeneration += 1uL
        val generation = connectionGeneration
        state = ProgressorConnectionState.Scanning
        connectJob = scope.launch {
            // A beat of latency so the connecting UI is actually exercised rather than
            // skipped past in a single frame.
            delay(300)
            if (connectionGeneration != generation) return@launch
            state = ProgressorConnectionState.Connecting

            delay(150)
            if (connectionGeneration != generation ||
                state != ProgressorConnectionState.Connecting
            ) {
                return@launch
            }
            connectJob = null
            state = ProgressorConnectionState.Connected
            onEvent?.invoke(ProgressorEvent.AppVersion(firmware))
            onEvent?.invoke(ProgressorEvent.Battery(batteryMillivolts))
        }
    }

    override fun disconnect() {
        connectionGeneration += 1uL
        connectJob?.cancel()
        connectJob = null
        stopPump()
        state = ProgressorConnectionState.Disconnected(reason = null)
    }

    override fun send(command: ProgressorCommand) {
        if (!state.isConnected) return
        when (command) {
            ProgressorCommand.tare ->
                // Tare zeroes whatever is on the gauge right now — the same trap as the
                // real device: tare under load and every reading after it is wrong.
                tareOffsetKg = rawForceNow()

            ProgressorCommand.startWeightMeasurement -> {
                // Starts need a cause so the diagnostic ring cannot claim a reason the
                // caller never supplied; `startStreaming(cause)` is the only start path.
            }

            ProgressorCommand.stopWeightMeasurement -> stopPump()

            ProgressorCommand.getBatteryVoltage ->
                onEvent?.invoke(ProgressorEvent.Battery(batteryMillivolts))

            ProgressorCommand.getAppVersion ->
                onEvent?.invoke(ProgressorEvent.AppVersion(firmware))

            ProgressorCommand.enterSleep -> {
                stopPump()
                state = ProgressorConnectionState.Disconnected(L10n.tr("Device asleep"))
            }

            else -> Unit
        }
    }

    override fun startStreaming(cause: StreamStartCause) {
        if (!state.isConnected) return
        onDiagnostic?.invoke(ProgressorClientDiagnostic.StreamStartWritten(cause))
        deviceMicros = 0u
        startPump()
    }

    // MARK: - Sample pump

    private fun startPump() {
        if (pump != null) return
        elapsedSamples = 0uL
        deviceMicros = 0u
        pump = scope.launch {
            while (isActive) {
                emitBatch()
                delay((1000 * batchSize / sampleHz).toLong())
            }
        }
    }

    private fun stopPump() {
        pump?.cancel()
        pump = null
        // A new demo stream replays from zero. Release the synthetic load before the
        // next pre-start tare so it cannot capture the last session's loaded plateau.
        elapsedSamples = 0uL
        tareOffsetKg = 0.0
    }

    /// One notification's worth of samples, exactly as the device batches them — which is
    /// what makes the runner's "accrue from device timestamps, not arrival time" rule
    /// testable against something realistic.
    private fun emitBatch() = withPacket(clock.uptimeSeconds()) {
        for (index in 0 until batchSize) {
            val seconds = elapsedSamples.toDouble() / sampleHz
            val kg = MockForceProfile.force(seconds, profile) - tareOffsetKg
            onEvent?.invoke(
                ProgressorEvent.Sample(
                    ForceSample(kg = kg, deviceMicros = deviceMicros, isBatchStart = index == 0),
                ),
            )
            elapsedSamples += 1uL
            // Wrapping on purpose: over a long session the real device's UInt32 µs clock
            // rolls over at ~71.6 minutes, and the app must survive it. Kotlin's unsigned
            // arithmetic wraps natively, so this IS Swift's `&+`.
            deviceMicros += microsPerSample
        }
    }

    private fun rawForceNow(): Double =
        MockForceProfile.force(elapsedSamples.toDouble() / sampleHz, profile)
}

// MARK: - Force profiles

/// Scripted force traces. Pure functions of elapsed time, so a test can sample the same
/// curve the UI sees without running any timers — and so the same peak recurs run to run,
/// which is correct, not a bug.
enum class MockForceProfile(val rawValue: String) {
    /// Textbook: sharp ramp, steady plateau, clean release.
    clean("clean"),

    /// Wobbles across the threshold and briefly drops — the case that decides whether
    /// hysteresis and dropout grace are tuned right.
    shaky("shaky"),

    /// Fades through the hold, the way a real set's last rep does.
    weak("weak"),

    /// Nothing on the gauge. For checking idle/zero-drift behaviour.
    idle("idle");

    companion object {
        /// Work + rest cycle, chosen to match the default no-hang shape (10 s on, 20 s
        /// off) so a mock run lines up with a real routine.
        const val workSeconds: Double = 10.0
        const val restSeconds: Double = 20.0

        fun fromRaw(raw: String): MockForceProfile? = entries.firstOrNull { it.rawValue == raw }

        fun force(seconds: Double, profile: MockForceProfile): Double {
            val jitter = sin(seconds * 37.7) * 0.18 + sin(seconds * 13.1) * 0.1
            if (profile == idle) return max(0.0, 0.15 + jitter * 0.3)

            val cycle = workSeconds + restSeconds
            val phase = seconds % cycle
            if (phase >= workSeconds) return max(0.0, 0.2 + jitter * 0.3)

            // Ramp on and off so the trace has real edges to detect.
            val rampIn = min(1.0, phase / 0.45)
            val rampOut = min(1.0, (workSeconds - phase) / 0.35)
            val envelope = min(rampIn, rampOut)

            val plateau = when (profile) {
                clean -> 22 + jitter
                shaky -> {
                    // Rides around the threshold with a genuine dip near the middle.
                    val wobble = sin(phase * 2.9) * 3.5
                    val dip = if (phase > 5.2 && phase < 5.7) -14.0 else 0.0
                    20 + wobble + dip + jitter
                }
                weak -> 24 - (phase / workSeconds) * 12 + jitter
                idle -> 0.0
            }
            return max(0.0, plateau * envelope)
        }
    }
}
