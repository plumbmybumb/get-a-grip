// SPDX-License-Identifier: MPL-2.0 AND BSD-2-Clause
// Original contributions Copyright 2026 Nuri Bruner.

package run.nuri.getagrip.engine

// The Climbro wire protocol — pure value types, no CoreBluetooth (see the rule in
// ProgressorCodec.kt; everything in the engine has to decode on any machine).
//
// Protocol knowledge ported from hangtime-grip-connect (BSD-2-Clause, © 2024
// Stevie-Ray Hartog, https://github.com/Stevie-Ray/hangtime-grip-connect).
//
// UNVERIFIED ON HARDWARE BY THIS PROJECT. Everything below is a transcription of
// that client's `climbro.model.ts`, not something we have watched a Climbro send.
//
// The transport is Microchip's Transparent UART (an RN487x module): no framing, length
// or checksum. Each byte means whatever the last MARKER byte said, so the decoder is
// stateful — a notification can end mid-channel and the next one continues it.

object ClimbroCodec {

    // MARK: - GATT wiring

    /// Microchip Transparent UART. The datasheet's "UART Transmit" is the one the HOST
    /// subscribes to (device → phone), hence the reference's `rx`. Do not swap them:
    /// writing to the notify characteristic silently does nothing.
    ///
    /// No start payload: the reference defines no commands, and a Climbro pushes sensor
    /// bytes as soon as you subscribe. The write characteristic is recorded as a device
    /// fact; nothing writes to it today.
    val profile = GaugeGattProfile(
        serviceUUID = "49535343-FE7D-4AE5-8FA9-9FAFD205E455",
        notifyCharacteristicUUID = "49535343-1E4D-4BD9-BA61-23C647249616",
        writeCharacteristicUUID = "49535343-8841-43F4-A8D4-ECBE34729BB3",
        streamStartPayloads = emptyList(),
        streamStopPayload = null,
        tareCharacteristicUUID = null,
        tarePayload = null,
    )

    // MARK: - Protocol constants

    /// Every byte after this one is battery, until the next marker.
    const val batteryMarker: Int = 0xF0

    /// Every byte after this one is force, until the next marker.
    const val sensorMarker: Int = 0xF5

    /// 0xF6 is a RESERVED WORD meaning 36 kg, not the number 246.
    ///
    /// The stream cannot send the marker values as data, so it needs an escape near
    /// them — which also shows the sensor byte is KILOGRAMS with a 1 kg LSB, since an
    /// escape would be meaningless for raw ADC counts.
    const val reserved36kgMarker: Int = 0xF6
    const val reserved36kgValue: Int = 36

    /// Battery: a raw discharge byte between the reference's `minBatteryDisc` and
    /// `maxBatteryDisc` (its `batLevelCoef` is `100 / (230 - 112)`, as a percentage).
    const val batteryEmptyByte: Double = 112.0
    const val batteryFullByte: Double = 230.0

    /// 0…1 from one battery byte. CLAMPED, unlike the reference (−94 % for a byte of 0):
    /// a reading outside the known span saturates rather than lies.
    ///
    /// TRANSLATION NOTE: the parameter is an `Int` holding an UNSIGNED byte value,
    /// as everywhere in the ported codecs.
    fun batteryFraction(byte: Int): Double {
        val span = batteryFullByte - batteryEmptyByte
        return minOf(1.0, maxOf(0.0, (byte.toDouble() - batteryEmptyByte) / span))
    }

    // MARK: - Decoder

    class Decoder : GaugeFrameDecoder {

        /// Which channel the bytes belong to (the reference's `flagSynchro`, starting
        /// at neither marker), so bytes before the first marker are DROPPED, not guessed.
        private enum class Channel { unknown, battery, sensor }

        private var channel: Channel = Channel.unknown

        /// Battery arrives INSIDE the stream here (there is no 0x180F service on
        /// this device), which is why `GaugeFrameDecoder` has this member at all.
        override var batteryFraction: Double? = null
            private set

        /// Walk the bytes. A short read simply decodes fewer, and the channel survives
        /// into the next notification because the radio splits the stream anywhere.
        override fun ingest(data: ByteArray): List<GaugeReading> {
            val readings = ArrayList<GaugeReading>(data.size)

            // Iterating mirrors Swift, where subscripting a sliced `Data` can trap;
            // `ByteArray` is zero-based, so here it is simply the clearer loop.
            for (raw in data) {
                val byte = raw.toInt() and 0xFF

                if (byte == batteryMarker) {
                    channel = Channel.battery
                    continue
                }
                if (byte == sensorMarker) {
                    channel = Channel.sensor
                    continue
                }

                // Substituted BEFORE the channel switch, as in the reference, so a 0xF6
                // in the battery channel reads as 36. Indistinguishable on the wire;
                // one harmless wrong battery reading beats inventing a rule.
                val value = if (byte == reserved36kgMarker) reserved36kgValue else byte

                when (channel) {
                    // Qualified: the property and the codec's function share a name,
                    // which Swift disambiguates by call shape and Kotlin does not.
                    Channel.battery -> batteryFraction = ClimbroCodec.batteryFraction(value)
                    Channel.sensor ->
                        // The byte IS kilograms. No scaling, no calibration table, and a
                        // 1 kg quantisation the device cannot express more finely.
                        readings.add(GaugeReading(kg = value.toDouble()))
                    Channel.unknown -> continue
                }
            }

            return readings
        }
    }
}
