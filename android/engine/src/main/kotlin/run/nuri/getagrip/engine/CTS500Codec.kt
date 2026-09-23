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
// A generic MY-BT102 UART bridge in front of a crane-scale board: fixed-length frames
// behind a 0x05 header with an additive checksum, and ONE channel carrying commands,
// echoes and weight uploads. Replies do not name their command (like the Progressor's
// tag 0), but a frame's SHAPE identifies it, so no pending-command state is needed.
//
// Frames, all of them checksummed:
//   6 bytes  `05 <opcode> p0 p1 p2 ck`   command, and the device's echo of it
//   7 bytes  `05 80 <opcode> .. .. .. ck` typed reply to a query (battery, temperature)
//   7 bytes  `05 <b1> w3 w2 w1 w0 ck`     weight upload, big-endian centi-kilograms
//
// TRANSLATION NOTE: every `[UInt8]` is an `IntArray` of UNSIGNED byte values (0…255).
// Kotlin's `Byte` is signed, so comparing 0x80 against a raw `Byte` (−128) would
// misclassify exactly the frames this file tells apart. Narrowing happens once, in
// `command`; widening once, in `Decoder.ingest`.

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

    /// Anything above this is a corrupt frame, not a pull. The weight is read UNSIGNED,
    /// as the reference reads it, so two's-complement negative drift would read as tens
    /// of millions of kilograms. Dropping it is fail-closed: a missing sample costs a
    /// tenth of a second, one absurd sample sets the session peak. (A genuinely negative
    /// reading is DISCARDED — the sign convention is unverified.)
    ///
    /// **DOUBLE the family's rated 500 kg, on purpose.** The centi-kilogram scaling is
    /// unverified here, and a ceiling at rated capacity would silently discard EVERY
    /// reading if the real scaling is finer — silence is far harder to diagnose than a
    /// number out by 10×. Same reasoning as the Force Board's and Entralpi's 907 kg.
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
    /// TRANSLATION NOTE: Swift's defaulted 3-tuple payload is three defaulted parameters.
    fun command(opcode: Int, p0: Int = 0x00, p1: Int = 0x00, p2: Int = 0x00): ByteArray {
        val body = intArrayOf(header, opcode, p0, p1, p2)
        return (body + checksum(body)).map { it.toByte() }.toByteArray()
    }

    // MARK: - GATT wiring

    /// The MY-BT102's transparent UART: notify on 0xFFE1, write on 0xFFE2.
    ///
    /// **40 Hz is asked for ONCE PER LINK.** The reference never sends
    /// `SET_SAMPLING_RATE`, leaving the rate unknown, and at the lowest code (10 Hz)
    /// barely one sample fits inside the runner's 100 ms engage debounce. Nothing waits
    /// for an echo, since some firmwares apply the change without one.
    ///
    /// It lives in `oneTimeSetupPayloads` because start payloads are re-sent on EVERY
    /// re-kick, and 0xC1 configures an EEPROM-backed board. Re-sending START is harmless;
    /// rewriting a config that plausibly resets the ADC ~1500 times across a silent
    /// session could kill the very stream it was trying to revive.
    ///
    /// The tare is the DEVICE's own (`TARE_SCALE`), on the same characteristic; the
    /// reference's `tare()` sends it too, while clearing the app-side offset.
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

    /// Bytes 2…5 are BIG-endian hundredths of a kilogram — among little-endian
    /// neighbours, the kind of detail that yields a plausible wrong number, hence a test
    /// whose two byte orders differ by six orders of magnitude.
    ///
    /// TRANSLATION NOTE: Swift's `kilograms(fromWeightFrame:)`, its label folded into the name.
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

        /// Battery arrives only when asked (0xC4, which the app does not do today); parsed
        /// anyway. NOT a `batteryFraction`: volts → percent needs this cell's own curve.
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
        /// **Resynchronising continues the walk**, where the reference abandons the
        /// notification — spending one notification per bad byte, a visible stall at
        /// 40 Hz. Every pass consumes a frame or drops a byte, so the walk terminates.
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
