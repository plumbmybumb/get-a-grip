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
// The transport is Microchip's Transparent UART service (an RN487x module, which
// the device's own Model Number String confirms), so there is no framing, no
// length field and no checksum: the notification is a raw byte stream and each
// byte means whatever the last MARKER byte said it means. That is the whole
// protocol, and it is why the decoder is stateful — a notification can end
// mid-channel and the next one continues it.

object ClimbroCodec {

    // MARK: - GATT wiring

    /// Microchip Transparent UART. The characteristic named "UART Transmit" in the
    /// module's own datasheet is the one the HOST subscribes to (data flows
    /// device → phone), which is why the reference labels it `rx` — and `rx` is the
    /// id its base class subscribes to. Do not swap these two: writing to the
    /// notify characteristic silently does nothing.
    ///
    /// `streamStartPayloads` is empty on purpose. The reference implements no
    /// `stream()` for this device and defines no commands at all: a Climbro pushes
    /// sensor bytes as soon as you subscribe, so subscribing IS the start. The write
    /// characteristic is recorded anyway because it is a fact about the device, not
    /// because anything writes to it today.
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
    /// The reason is visible in the two markers above: a bare byte stream cannot
    /// send 240 or 245 as data, so the device needs an escape for at least one
    /// value in that neighbourhood. It also tells us the sensor byte is
    /// KILOGRAMS with a 1 kg LSB — 0xF6 would be meaningless as an escape if the
    /// byte were raw ADC counts.
    const val reserved36kgMarker: Int = 0xF6
    const val reserved36kgValue: Int = 36

    /// Battery: the byte is a raw discharge reading, and these two ends of it are
    /// the reference's `minBatteryDisc` / `maxBatteryDisc`. Its `batLevelCoef` is
    /// `100 / (230 - 112)`; we want a 0…1 fraction, so the same span divides
    /// instead of scaling to percent — one arithmetic step fewer, same number.
    const val batteryEmptyByte: Double = 112.0
    const val batteryFullByte: Double = 230.0

    /// 0…1 from one battery byte. CLAMPED, which the reference is not: it happily
    /// reports −94 % for a byte of 0. A fraction is drawn as a ring somewhere, and
    /// a ring cannot be negative; a battery reading outside the known span is a
    /// reading we do not understand, so it saturates rather than lies.
    ///
    /// TRANSLATION NOTE: the parameter is an `Int` holding an UNSIGNED byte value,
    /// as everywhere in the ported codecs.
    fun batteryFraction(byte: Int): Double {
        val span = batteryFullByte - batteryEmptyByte
        return minOf(1.0, maxOf(0.0, (byte.toDouble() - batteryEmptyByte) / span))
    }

    // MARK: - Decoder

    class Decoder : GaugeFrameDecoder {

        /// Which channel the bytes currently belong to. The reference calls this
        /// `flagSynchro` and initialises it to 0 — neither marker — so bytes
        /// arriving before the first marker are DROPPED rather than guessed at.
        /// Kept as its own case for exactly that reason.
        private enum class Channel { unknown, battery, sensor }

        private var channel: Channel = Channel.unknown

        /// Battery arrives INSIDE the stream here (there is no 0x180F service on
        /// this device), which is why `GaugeFrameDecoder` has this member at all.
        override var batteryFraction: Double? = null
            private set

        /// Walk the bytes. There is nothing to truncate — no length fields, no
        /// checksums — so a short read simply decodes fewer bytes, and the channel
        /// survives into the next notification because a real stream splits
        /// wherever the radio decides to split it.
        override fun ingest(data: ByteArray): List<GaugeReading> {
            val readings = ArrayList<GaugeReading>(data.size)

            // Iterate the SEQUENCE, never subscripts: on iOS a `Data` sliced upstream
            // keeps its parent's indices and `data[0]` on such a slice traps. Kotlin's
            // `ByteArray` is always zero-based, so the hazard is Swift-only — the
            // iteration stays as it was because it is also the clearer loop.
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

                // The reserved-word substitution happens BEFORE the channel switch
                // in the reference, so a 0xF6 landing in the battery channel is read
                // as the number 36. Ported as-is: the two cases are indistinguishable
                // on the wire, and inventing a rule the device does not follow would
                // be worse than reproducing one harmless wrong battery reading.
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
