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
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/// A synthetic Progressor — the only way to run the app without a phone and a real gauge
/// (**the emulator has no working Bluetooth**), and what demo mode uses, so someone without
/// hardware can watch a whole session.
///
/// **Always compiled in**, and reached two ways: the `--ez mockDevice true` launch extra
/// (iOS's `-mockDevice`, read by `DeviceStore`) and the "Try demo mode" button in the
/// gauge's disconnected state. A debug-only mock would strand anyone without hardware.
///
/// TRANSLATION NOTE: Swift's `Task` inherits `@MainActor`; Kotlin takes the scope
/// explicitly (the app passes `Dispatchers.Main.immediate`, a test its test scope), so it
/// is a constructor parameter.
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
            // A beat of latency so the connecting UI is exercised rather than skipped in
            // one frame.
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
                // Tare zeroes whatever is on the gauge now — the real device's trap: tare
                // under load and every later reading is wrong.
                tareOffsetKg = rawForceNow()

            ProgressorCommand.startWeightMeasurement -> {
                // Starts need a cause; `startStreaming(cause)` is the only start path.
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
        // A new demo stream replays from zero; release the synthetic load first so the
        // pre-start tare cannot capture the last plateau.
        elapsedSamples = 0uL
        tareOffsetKg = 0.0
    }

    /// One notification's worth of samples, batched as the device does, so "accrue from
    /// device timestamps, not arrival time" is tested against something realistic.
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
            // Wraps on purpose: the device's UInt32 µs clock rolls over at ~71.6 minutes.
            // Kotlin unsigned arithmetic wraps natively (Swift's `&+`).
            deviceMicros += microsPerSample
        }
    }

    private fun rawForceNow(): Double =
        MockForceProfile.force(elapsedSamples.toDouble() / sampleHz, profile)
}

// MARK: - Force profiles

/// Scripted force traces as pure functions of elapsed time: tests sample the curve without
/// timers, and the same peak recurs run to run (correct, not a bug).
enum class MockForceProfile(val rawValue: String) {
    /// Textbook: sharp ramp, steady plateau, clean release.
    clean("clean"),

    /// Wobbles across the threshold and briefly drops — tests hysteresis and dropout
    /// handling.
    shaky("shaky"),

    /// Fades through the hold, the way a real set's last rep does.
    weak("weak"),

    /// Nothing on the gauge. For checking idle/zero-drift behaviour.
    idle("idle"),

    /// A critical force test done properly: all-out 7 s pulls on a 10 s cycle, decaying
    /// from about 35 kg to an 18 kg plateau, with every fifth pull held a little past the
    /// bell. The demo gauge switches to it while a test runs, so demo mode (store review
    /// included) sees a real-looking plateau rather than the routine's 10-on/20-off shape.
    allOut("allOut");

    companion object {
        /// Matches the default no-hang shape (10 s on, 20 s off) so a mock run lines up
        /// with a real routine.
        const val workSeconds: Double = 10.0
        const val restSeconds: Double = 20.0

        fun fromRaw(raw: String): MockForceProfile? = entries.firstOrNull { it.rawValue == raw }

        fun force(seconds: Double, profile: MockForceProfile): Double {
            val jitter = sin(seconds * 37.7) * 0.18 + sin(seconds * 13.1) * 0.1
            if (profile == idle) return max(0.0, 0.15 + jitter * 0.3)
            if (profile == allOut) return allOutForce(seconds, jitter)

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
                idle, allOut -> 0.0
            }
            return max(0.0, plateau * envelope)
        }

        private fun allOutForce(elapsed: Double, jitter: Double): Double {
            // Three seconds of setting up first, so the armed PULL TO START state (and,
            // between hands, "Right hand next") is on screen before the first pull.
            val seconds = elapsed - 3
            if (seconds < 0) return max(0.0, 0.2 + jitter * 0.3)
            val rep = (seconds / 10).toInt()
            val phase = seconds - rep.toDouble() * 10
            val hold = if (rep % 5 == 2) 7.8 else 6.9
            if (phase >= hold) return max(0.0, 0.2 + jitter * 0.3)
            val start = 18 + 17 * exp(-rep.toDouble() / 4.5)
            // Each pull fades within itself, as a real all-out effort does.
            val level = start * (1 - 0.14 * phase / 7) + jitter * 2
            val envelope = minOf(1.0, phase / 0.25, (hold - phase) / 0.2)
            return max(0.0, level * envelope)
        }
    }
}
