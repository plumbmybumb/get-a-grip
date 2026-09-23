// SPDX-License-Identifier: MPL-2.0 AND BSD-2-Clause
// Original contributions Copyright 2026 Nuri Bruner.

package run.nuri.getagrip.engine

// The Griptonite Motherboard wire protocol — pure value types, no CoreBluetooth.
//
// Protocol knowledge ported from hangtime-grip-connect (BSD-2-Clause, © 2024
// Stevie-Ray Hartog, https://github.com/Stevie-Ray/hangtime-grip-connect).
//
// UNVERIFIED ON HARDWARE BY THIS PROJECT.
//
// This is the only ported device whose stream is TEXT. Nordic UART carries
// LF-terminated ASCII lines, and a line is one of three things: a 32-character hex
// packet (three sensor samples), a comma-separated calibration row answering the
// "C" command, or something we do not handle. So the decoder does two jobs the
// others do not — it reassembles lines across notifications, and it holds the
// calibration table that turns raw ADC counts into kilograms.
//
// **A packet is THREE SENSORS, not three moments.** Left, centre and right are
// summed into ONE reading per packet. The reference's `samplesNumber = 3` counts
// load cells; anything that reads it as a sample rate is off by 3×.
//
// TRANSLATION NOTE: as in `CTS500Codec`, every `[UInt8]` is an `IntArray` of
// UNSIGNED byte values — Kotlin's `Byte` is signed and a 24-bit sample assembled
// out of signed bytes is wrong for every byte over 0x7F, which is most of a
// negative sample.

object MotherboardCodec {

    // MARK: - GATT wiring

    /// Nordic UART. As everywhere in the reference, `rx` is the characteristic the
    /// HOST subscribes to and `tx` the one it writes.
    ///
    /// Start is "C" then "S30": raw counts mean nothing without the calibration table
    /// "C" requests. The reference waits up to 2.5 s between them while rows arrive, and
    /// `startPayloadDelaySeconds` is that wait, so the start cannot race the table home.
    /// A packet that arrives early yields NO readings rather than uncalibrated counts
    /// wrong by orders of magnitude.
    ///
    /// STOP is "#", the serial query. The reference stops with an EMPTY write ("all
    /// commands will stop the data stream"), but a zero-length GATT write is a no-op on
    /// some stacks, so the most harmless real command does it. Its reply is neither hex
    /// nor a calibration row, so the decoder drops it.
    val profile = GaugeGattProfile(
        serviceUUID = "6E400001-B5A3-F393-E0A9-E50E24DCCA9E",
        notifyCharacteristicUUID = "6E400003-B5A3-F393-E0A9-E50E24DCCA9E",
        writeCharacteristicUUID = "6E400002-B5A3-F393-E0A9-E50E24DCCA9E",
        streamStartPayloads = listOf(
            "C".toByteArray(Charsets.UTF_8),
            "S30".toByteArray(Charsets.UTF_8),
        ),
        streamStopPayload = "#".toByteArray(Charsets.UTF_8),
        startPayloadDelaySeconds = 2.5,
        tareCharacteristicUUID = null,
        tarePayload = null,
    )

    // MARK: - Protocol constants

    /// A streaming packet is exactly this many hex CHARACTERS (16 bytes).
    const val hexPacketLength = 32

    /// Sensors decoded per packet: left, centre, right.
    const val sensorCount = 3

    /// Calibration slots the device answers with. Four, though only three are ever
    /// consumed — ported as four so a row addressed to sensor 3 is stored rather
    /// than dropped, exactly as the reference stores it.
    const val calibrationSlotCount = 4

    /// A line that never ends is not a Motherboard line. The reference buffers without
    /// bound; here a twenty-minute session with no LF would grow the buffer for all of
    /// it. 1 KB is thirty lines of slack.
    const val maxBufferedBytes = 1024

    private const val lineFeed = 0x0A
    private const val carriageReturn = 0x0D

    // MARK: - Calibration

    /// Map one raw sample through one sensor's calibration table.
    ///
    /// A table row is `[index, force, raw]` — the reference reads `[1]` as the
    /// calibrated force and `[2]` as the raw count it corresponds to. The mapping is
    /// piecewise linear between consecutive rows.
    ///
    /// Two ported quirks and one deliberate departure:
    ///
    /// - Reflection below the zero point is `-sample`, NOT `2 * zero - sample`
    ///   (which is present in the reference, commented out). The two agree only when
    ///   the zero row's raw count is 0. Kept as the reference computes it, because
    ///   that is the arithmetic its own tests pin.
    /// - The sign is applied at the END, so a negative sample is mapped through the
    ///   positive side of the table and then negated.
    /// - A sample ABOVE the table's last raw count returns nil, where the reference
    ///   returns 0 — which would tell the runner the climber let go at the moment they
    ///   pulled hardest. Fail closed instead.
    fun applyCalibration(sample: Double, table: List<List<Double>>): Double? {
        val zeroRow = table.firstOrNull() ?: return null
        if (zeroRow.size < 3 || table.size < 2) return null

        var value = sample
        var sign = 1.0
        if (value < zeroRow[2]) {
            sign = -1.0
            value = -value
        }

        for (index in 1 until table.size) {
            val lower = table[index - 1]
            val upper = table[index]
            if (lower.size < 3 || upper.size < 3) return null

            val start = lower[2]
            val end = upper[2]
            if (value >= end) continue
            // A zero-width segment would divide by zero into a NaN kilogram that poisons
            // every average and peak. Skip it; a later segment answers, or none does.
            if (end == start) continue

            val fraction = (value - start) / (end - start)
            val force = lower[1] + fraction * (upper[1] - lower[1])
            if (!force.isFinite()) return null
            return sign * force
        }

        return null
    }

    // MARK: - Decoder

    class Decoder : GaugeFrameDecoder {

        /// Bytes waiting for their LF. A notification splits wherever the radio
        /// decides to split it, so half a packet is normal, not an error.
        private val buffer: MutableList<Int> = mutableListOf()

        /// One table per sensor slot, filled from "C" replies. Empty until the
        /// device answers, and no reading is emitted while it is.
        private val calibration: List<MutableList<List<Double>>> =
            List(calibrationSlotCount) { mutableListOf() }

        /// The device's own packet counter (uint16 LE, byte 0). Not used for timing —
        /// the client stamps arrival with `SyntheticSampleClock` — but it is the only
        /// way to notice dropped packets, so it is kept for diagnostics.
        ///
        /// TRANSLATION NOTE: Swift's `UInt16?` is an `Int?` in 0…65535 here. The value
        /// is never arithmetic, only reported, so the narrower type bought nothing and
        /// cost a cast at every call site.
        var latestSampleIndex: Int? = null
            private set

        /// Raw battery field (uint16 LE, byte 2). Deliberately NOT published as
        /// `batteryFraction`: 0x012C in the reference's fixture is plainly not a
        /// percentage, its scaling is undocumented, and this device carries the
        /// standard 0x180F Battery Service that the client reads properly.
        var latestBatteryRaw: Int? = null
            private set

        /// Whether every sensor we decode has a usable table yet.
        val hasCalibration: Boolean
            get() = (0 until sensorCount).all { calibration[it].size >= 2 }

        /// Rows held per slot. Exposed for the one property that is otherwise invisible:
        /// a second dump must REPLACE a slot, not stack on it — see `ingestCalibration`.
        val calibrationRowCounts: List<Int>
            get() = calibration.map { it.size }

        override fun ingest(data: ByteArray): List<GaugeReading> {
            for (byte in data) buffer.add(byte.toInt() and 0xFF)

            val readings = mutableListOf<GaugeReading>()
            while (true) {
                val lineFeedIndex = buffer.indexOf(lineFeed)
                if (lineFeedIndex < 0) break

                val line = ArrayList(buffer.subList(0, lineFeedIndex))
                buffer.subList(0, lineFeedIndex + 1).clear()
                // CRLF is stripped to CR-then-nothing; the reference does the same,
                // and a stray CR would fail the all-hex test on every packet.
                if (line.lastOrNull() == carriageReturn) line.removeAt(line.size - 1)
                handle(line.toIntArray())?.let { readings.add(it) }
            }

            if (buffer.size > maxBufferedBytes) buffer.clear()
            return readings
        }

        private fun handle(line: IntArray): GaugeReading? {
            if (line.size == hexPacketLength) {
                val bytes = hexDecode(line)
                if (bytes != null) return decodePacket(bytes)
            }
            ingestCalibration(line)
            return null
        }

        /// A packet is 16 bytes: `sampleIndex` (uint16 LE), `battRaw` (uint16 LE),
        /// then three 24-bit LITTLE-endian samples at bytes 4, 7 and 10. Bytes 13…15
        /// are unused by the reference and by us.
        ///
        /// Each sample is signed by RANGE: `>= 0x7FFFFF` means subtract 0x1000000. The
        /// boundary is the reference's, one off the textbook 0x800000; a sample exactly
        /// on it is garbage either way.
        ///
        /// Centre and right are INVERTED after calibration, then all three sum to the
        /// total load. Left is not inverted; negating all three would look tidier and
        /// be wrong.
        private fun decodePacket(bytes: IntArray): GaugeReading? {
            if (bytes.size < 4 + 3 * sensorCount) return null

            latestSampleIndex = bytes[0] or (bytes[1] shl 8)
            latestBatteryRaw = bytes[2] or (bytes[3] shl 8)

            var total = 0.0
            for (sensor in 0 until sensorCount) {
                val start = 4 + 3 * sensor
                var raw = bytes[start] or (bytes[start + 1] shl 8) or (bytes[start + 2] shl 16)
                if (raw >= 0x7FFFFF) raw -= 0x1000000

                val force = applyCalibration(sample = raw.toDouble(), table = calibration[sensor])
                    ?: return null

                total += if (sensor == 0) force else -force
            }

            if (!total.isFinite()) return null
            return GaugeReading(kg = total)
        }

        /// Calibration rows arrive as `sensor,index,force,raw`. Anything with a
        /// different shape is ignored: a hex packet has no commas, so the two line
        /// kinds cannot be confused, and unlike the reference we do not need to
        /// remember which command was written last to tell them apart.
        ///
        /// **A NEW DUMP REPLACES ITS SLOT.** "C" is re-sent on every re-kick (watchdog,
        /// tare recovery, foreground) while the decoder lives for the whole LINK, so
        /// appending grew the table without bound into a non-monotonic concatenation.
        /// Row INDEX 0 marks the start of a dump, the same signal the reference uses.
        private fun ingestCalibration(line: IntArray) {
            val text = String(ByteArray(line.size) { line[it].toByte() }, Charsets.UTF_8)
            val parts = text.split(",")
            if (parts.size != 4) return

            val numbers = parts.mapNotNull { it.trim().toDoubleOrNull() }
            if (numbers.size != 4) return

            // TRANSLATION NOTE: Swift guards with `Int(exactly:)` (nil for NaN, ±inf,
            // fractions and out-of-range; a plain `Int(_:)` trapped). `toInt()` saturates
            // instead, and the equality guard below rejects the same rows. `toDoubleOrNull`
            // refuses "nan"/"inf" outright, which only drops those one guard earlier.
            val slot = numbers[0].toInt()
            if (numbers[0] != slot.toDouble() || slot < 0 || slot >= calibrationSlotCount) return

            if (numbers[1] == 0.0) calibration[slot].clear()
            calibration[slot].add(numbers.subList(1, numbers.size).toList())
        }
    }

    // MARK: - Hex

    /// ASCII hex → bytes, or nil the moment a character is not hex. The reference
    /// tests the same thing with a regex before decoding; folding the two together
    /// means a line is parsed once.
    fun hexDecode(ascii: IntArray): IntArray? {
        if (ascii.size % 2 != 0 || ascii.isEmpty()) return null

        val bytes = IntArray(ascii.size / 2)
        var index = 0
        while (index < ascii.size) {
            val high = nibble(ascii[index]) ?: return null
            val low = nibble(ascii[index + 1]) ?: return null
            bytes[index / 2] = (high shl 4) or low
            index += 2
        }
        return bytes
    }

    private fun nibble(character: Int): Int? = when (character) {
        in 0x30..0x39 -> character - 0x30           // 0-9
        in 0x41..0x46 -> character - 0x41 + 10      // A-F
        in 0x61..0x66 -> character - 0x61 + 10      // a-f
        else -> null
    }
}
