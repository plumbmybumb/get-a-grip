// SPDX-License-Identifier: MPL-2.0 AND BSD-2-Clause
// Original contributions Copyright 2026 Nuri Bruner.

package run.nuri.getagrip.engine

// The Jlyscales CTS500 wire protocol — pure value types, no CoreBluetooth.
//
// Protocol knowledge ported from hangtime-grip-connect (BSD-2-Clause, © 2024
// Stevie-Ray Hartog, https://github.com/Stevie-Ray/hangtime-grip-connect).
//
// UNVERIFIED ON HARDWARE BY THIS PROJECT.
//
// A generic MY-BT102 UART bridge in front of a crane-scale board, so the protocol
// is the scale's, not Bluetooth's: fixed-length frames behind a 0x05 header with an
// additive checksum, and ONE channel carrying commands, command echoes and weight
// uploads together. Nothing echoes which command it answers — the same problem the
// Progressor has with its tag-0 replies — but here the fix is cheaper: a frame's
// SHAPE identifies it, so the decoder needs no pending-command state and never
// serializes anything.
//
// Frames, all of them checksummed:
//   6 bytes  `05 <opcode> p0 p1 p2 ck`   command, and the device's echo of it
//   7 bytes  `05 80 <opcode> .. .. .. ck` typed reply to a query (battery, temperature)
//   7 bytes  `05 <b1> w3 w2 w1 w0 ck`     weight upload, big-endian centi-kilograms
//
// TRANSLATION NOTE: every `[UInt8]` here becomes an `IntArray` of UNSIGNED byte
// values (0…255), and every `UInt8` an `Int` in the same range. Kotlin's `Byte` is
// signed, so `frame[1] != responseFlag` against a raw `Byte` would compare 0x80 to
// −128 and quietly misclassify exactly the frames this file exists to tell apart.
// The narrowing to real bytes happens once, in `command`, and the widening once, in
// `Decoder.ingest`.

object CTS500Codec {

    // MARK: - Protocol constants

    const val header: Int = 0x05

    /// Byte 1 of a TYPED reply. Byte 2 then carries the opcode being answered, which
    /// is the one place in this protocol where a reply names its question.
    const val responseFlag: Int = 0x80
    const val ackFrameLength = 6
    const val dataFrameLength = 7

    /// Every opcode the reference knows. The set matters far more than the
    /// individual values: `isWeightFrame` identifies a weight upload partly by its
    /// byte 1 NOT being an opcode, so an opcode missing from this list would make
    /// that device's command echo decode as a load of several thousand kilograms.
    object Opcode {
        const val setRange: Int = 0x81
        const val setDivision: Int = 0x82
        const val setFirstCalibrationWeight: Int = 0x83
        const val setSecondCalibrationWeight: Int = 0x84
        const val powerOnReset: Int = 0x85
        const val zeroScale: Int = 0x86
        const val runFirstCalibration: Int = 0xA1
        const val runSecondCalibration: Int = 0xA2
        const val getFirmwareVersion: Int = 0xA4
        const val tareScale: Int = 0xA6
        const val noLoadCalibration: Int = 0xA7
        const val getWeight: Int = 0xA9
        const val startWeightMeasurement: Int = 0xAA
        const val stopWeightMeasurement: Int = 0xAB
        const val setBaudRate: Int = 0xC0
        const val setSamplingRate: Int = 0xC1
        const val setShutdownTime: Int = 0xC3
        const val getBatteryVoltage: Int = 0xC4
        const val getTemperature: Int = 0xC5
        const val setUpperTemperatureLimit: Int = 0xC6
        const val setLowerTemperatureLimit: Int = 0xC7
        const val peakMode: Int = 0xCA
        const val setMaxWeightLimit: Int = 0xD1
        const val setMinWeightLimit: Int = 0xD2
        const val setWeightAlarmMode: Int = 0xD3
        const val setAlarmOutput: Int = 0xD4
    }

    val commandOpcodes: Set<Int> = setOf(
        Opcode.setRange, Opcode.setDivision, Opcode.setFirstCalibrationWeight,
        Opcode.setSecondCalibrationWeight, Opcode.powerOnReset, Opcode.zeroScale,
        Opcode.runFirstCalibration, Opcode.runSecondCalibration, Opcode.getFirmwareVersion,
        Opcode.tareScale, Opcode.noLoadCalibration, Opcode.getWeight,
        Opcode.startWeightMeasurement, Opcode.stopWeightMeasurement, Opcode.setBaudRate,
        Opcode.setSamplingRate, Opcode.setShutdownTime, Opcode.getBatteryVoltage,
        Opcode.getTemperature, Opcode.setUpperTemperatureLimit, Opcode.setLowerTemperatureLimit,
        Opcode.peakMode, Opcode.setMaxWeightLimit, Opcode.setMinWeightLimit,
        Opcode.setWeightAlarmMode, Opcode.setAlarmOutput,
    )

    /// A/D sampling-rate payload codes: 0x00 = 10 Hz, 0x01 = 20, 0x02 = 40,
    /// 0x03 = 80, 0x04 = 160, 0x05 = 320.
    const val samplingRate40HzCode: Int = 0x02

    /// Anything above this is a corrupt frame, not a pull.
    ///
    /// The weight field is read UNSIGNED, exactly as the reference reads it, so a
    /// firmware that sends small negative drift in two's complement would produce
    /// tens of millions of kilograms. Dropping those is the fail-closed direction:
    /// a missing sample costs a tenth of a second of trace, while one absurd sample
    /// sets the session peak and rescales the graph. It also means a genuinely
    /// negative reading is DISCARDED rather than mis-reported — the sign convention
    /// is unverified.
    ///
    /// **DOUBLE the 500 kg this scale family claims, and the doubling is the point.**
    /// The centi-kilogram scaling is corroborated by the device's own division presets
    /// but not verified on hardware here, so a ceiling set at the rated capacity would
    /// silently discard EVERY reading if the real scaling is finer — total silence being
    /// far harder to diagnose than a number visibly out by 10×. One rated capacity of
    /// headroom rejects everything a bad frame can express and refuses nothing a scale in
    /// this family can report. Same reasoning, same order of magnitude, as the Force
    /// Board's and the Entralpi's 907 kg.
    const val maxPlausibleKilograms: Double = 1000.0

    // MARK: - Command building

    /// Additive checksum over every byte before the checksum slot, truncated to a
    /// byte. Weak — it cannot see a transposition — which is why the plausibility
    /// ceiling above exists on top of it.
    ///
    /// TRANSLATION NOTE: Swift's `&+` on `UInt8` is the `and 0xFF` here; an `Int`
    /// sum cannot overflow at these lengths, so the mask is the whole truncation.
    fun checksum(bytes: IntArray): Int = bytes.fold(0) { acc, b -> (acc + b) and 0xFF }

    /// `05 <opcode> p0 p1 p2 ck`. The reference's `buildCommand`, port for port.
    ///
    /// TRANSLATION NOTE: Swift's defaulted 3-tuple payload becomes three defaulted
    /// parameters — Kotlin has no tuple, and a `Triple` would read worse at every
    /// call site than the three bytes it stands for.
    fun command(opcode: Int, p0: Int = 0x00, p1: Int = 0x00, p2: Int = 0x00): ByteArray {
        val body = intArrayOf(header, opcode, p0, p1, p2)
        return (body + checksum(body)).map { it.toByte() }.toByteArray()
    }

    // MARK: - GATT wiring

    /// The MY-BT102's transparent UART: notify on 0xFFE1, write on 0xFFE2.
    ///
    /// **40 Hz is asked for ONCE PER LINK, not on every start.** The reference never sends
    /// `SET_SAMPLING_RATE` at all — it exposes it as an API and leaves the device at
    /// whatever it was configured with, and the lowest code is 10 Hz — so without this
    /// write the sample rate is unknown, and 10 Hz would put barely one sample inside the
    /// runner's 100 ms engage debounce. Asking makes the capability table's advertised rate
    /// a fact instead of a hope. Some firmwares apply rate changes without echoing a
    /// confirmation, which costs us nothing here: nothing waits for the echo, and the start
    /// command follows regardless.
    ///
    /// It lives in `oneTimeSetupPayloads` because `streamStartPayloads` is re-sent on
    /// EVERY re-kick by design — the watchdog every 500 ms of silence, a tare recovery, the
    /// foreground return — and 0xC1 is a scale-configuration command on a board whose
    /// rate and baud settings are EEPROM-backed. Re-sending START (0xAA) to a live device
    /// is harmless, which is the house rule; re-writing a config that plausibly resets the
    /// ADC is not the same thing, and roughly 1500 times across a silent twenty-minute
    /// session it could prevent the very stream it was trying to revive.
    ///
    /// The tare is the DEVICE's own (`TARE_SCALE`), written to the same
    /// characteristic as everything else — this device genuinely has a hardware
    /// tare, and the reference's own `tare()` override sends it while clearing the
    /// app-side offset.
    val profile = GaugeGattProfile(
        serviceUUID = "0000FFE0-0000-1000-8000-00805F9B34FB",
        notifyCharacteristicUUID = "0000FFE1-0000-1000-8000-00805F9B34FB",
        writeCharacteristicUUID = "0000FFE2-0000-1000-8000-00805F9B34FB",
        streamStartPayloads = listOf(command(Opcode.startWeightMeasurement)),
        streamStopPayload = command(Opcode.stopWeightMeasurement),
        oneTimeSetupPayloads = listOf(
            command(Opcode.setSamplingRate, 0x00, 0x00, samplingRate40HzCode),
        ),
        tareCharacteristicUUID = "0000FFE2-0000-1000-8000-00805F9B34FB",
        tarePayload = command(Opcode.tareScale),
    )

    // MARK: - Frame classification

    fun isValidFrame(frame: IntArray): Boolean {
        if (frame.size < ackFrameLength || frame[0] != header) return false
        return checksum(frame.copyOfRange(0, frame.size - 1)) == frame[frame.size - 1]
    }

    /// A weight upload is a 7-byte frame whose byte 1 is neither the reply flag nor
    /// any command opcode. Byte 1 is 0x01 in the reference's own fixture and its
    /// meaning is NOT established — a status or stability flag is the obvious guess,
    /// and it is deliberately not treated as one here.
    fun isWeightFrame(frame: IntArray): Boolean =
        frame.size == dataFrameLength &&
            frame[0] == header &&
            frame[1] != responseFlag &&
            !commandOpcodes.contains(frame[1]) &&
            isValidFrame(frame)

    fun isTypedResponse(frame: IntArray, opcode: Int): Boolean =
        frame.size == dataFrameLength &&
            frame[0] == header &&
            frame[1] == responseFlag &&
            frame[2] == opcode &&
            isValidFrame(frame)

    /// Bytes 2…5 are BIG-endian hundredths of a kilogram. Big-endian on a
    /// little-endian protocol neighbourhood is exactly the kind of detail that
    /// produces a plausible wrong number rather than a crash, which is why there is
    /// a test for a value whose two readings differ by six orders of magnitude.
    ///
    /// TRANSLATION NOTE: Swift's argument label folds into the name here —
    /// `kilograms(fromWeightFrame:)` becomes `kilogramsFromWeightFrame`, since Kotlin
    /// has no labels and a bare `kilograms(frame)` would lose which frame kind it takes.
    fun kilogramsFromWeightFrame(frame: IntArray): Double? {
        if (!isWeightFrame(frame)) return null
        val centi = (frame[2].toUInt() shl 24) or
            (frame[3].toUInt() shl 16) or
            (frame[4].toUInt() shl 8) or
            frame[5].toUInt()
        val kg = centi.toDouble() / 100
        if (kg > maxPlausibleKilograms) return null
        return kg
    }

    // MARK: - Decoder

    class Decoder : GaugeFrameDecoder {

        private sealed interface Scan {
            class Frame(val bytes: IntArray) : Scan

            /// Not enough bytes yet — wait for the next notification.
            data object Incomplete : Scan

            /// A byte was dropped to hunt for the real frame boundary.
            data object Resynchronised : Scan
        }

        private val buffer: MutableList<Int> = mutableListOf()

        /// Battery arrives only if something asks (opcode 0xC4), and nothing in the
        /// app does today; parsed anyway so a manual query is not silently swallowed.
        /// NOT turned into `batteryFraction`: volts → percent needs this cell's
        /// discharge curve, and the Progressor's curve is the Progressor's.
        var latestBatteryVolts: Double? = null
            private set

        override fun ingest(data: ByteArray): List<GaugeReading> {
            for (byte in data) buffer.add(byte.toInt() and 0xFF)

            val readings = mutableListOf<GaugeReading>()
            while (buffer.size >= ackFrameLength) {
                val headerIndex = buffer.indexOf(header)
                if (headerIndex < 0) {
                    // No header anywhere: none of these bytes can begin a frame.
                    buffer.clear()
                    break
                }
                if (headerIndex > 0) {
                    buffer.subList(0, headerIndex).clear()
                    continue
                }

                when (val scan = takeFrame()) {
                    is Scan.Frame -> interpret(scan.bytes)?.let { readings.add(it) }
                    Scan.Incomplete -> return readings
                    Scan.Resynchronised -> continue
                }
            }
            return readings
        }

        /// Pull the next frame off the front of the buffer, which begins with a
        /// header byte.
        ///
        /// A 6-byte echo and a 7-byte data frame share that header, so byte 1 breaks
        /// the tie: a known opcode means an echo. Both candidates must pass their
        /// checksum, and if neither does, the header was a coincidence inside some
        /// other frame's payload — drop one byte and hunt again.
        ///
        /// **Resynchronising continues the walk**, where the reference returns from
        /// the notification entirely. At 40 Hz its version would spend one whole
        /// notification per bad byte, so a single corrupted frame could stall the
        /// stream for a noticeable fraction of a second. Every pass here either
        /// consumes a frame or drops at least one byte, so the walk still terminates.
        private fun takeFrame(): Scan {
            if (commandOpcodes.contains(buffer[1])) {
                val candidate = take(ackFrameLength)
                if (isValidFrame(candidate)) {
                    buffer.subList(0, ackFrameLength).clear()
                    return Scan.Frame(candidate)
                }
            }

            if (buffer.size < dataFrameLength) return Scan.Incomplete

            val candidate = take(dataFrameLength)
            if (isValidFrame(candidate)) {
                buffer.subList(0, dataFrameLength).clear()
                return Scan.Frame(candidate)
            }

            buffer.subList(0, 1).clear()
            return Scan.Resynchronised
        }

        private fun take(count: Int): IntArray = IntArray(count) { buffer[it] }

        private fun interpret(frame: IntArray): GaugeReading? {
            kilogramsFromWeightFrame(frame)?.let { return GaugeReading(kg = it) }
            if (isTypedResponse(frame, Opcode.getBatteryVoltage)) {
                latestBatteryVolts = ((frame[4] shl 8) or frame[5]).toDouble() / 100
            }
            // Everything else is a command echo or a reply we do not consume. It is
            // not an error and must not stop the walk: a session's own start and
            // tare writes come back as echoes.
            return null
        }
    }
}
