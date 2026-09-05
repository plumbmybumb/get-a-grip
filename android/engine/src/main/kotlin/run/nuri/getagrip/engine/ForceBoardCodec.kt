// SPDX-License-Identifier: MPL-2.0 AND BSD-2-Clause
// Original contributions Copyright 2026 Nuri Bruner.

package run.nuri.getagrip.engine

// The PitchSix Force Board wire protocol — pure value types, no CoreBluetooth.
//
// Protocol knowledge ported from hangtime-grip-connect (BSD-2-Clause, © 2024
// Stevie-Ray Hartog, https://github.com/Stevie-Ray/hangtime-grip-connect).
//
// UNVERIFIED ON HARDWARE BY THIS PROJECT.
//
// Two facts here cost more than the rest of the file put together:
//
// 1. **The board streams POUNDS.** The reference marks it `streamUnit = "lbs"` and
//    its own test asserts a 1000 in the packet means 1000 lbs. Kilograms are the
//    unit everywhere in Doigt, so the conversion happens HERE, at the choke point,
//    and never downstream where a display would have to remember which device it
//    was drawing.
// 2. **The notify and write characteristics live in DIFFERENT SERVICES.** Force
//    data comes out of the Force Board service; the mode command that starts it
//    goes into the Weight service. `GaugeGattProfile` carries one `serviceUUID`,
//    so it names the streaming service and `deviceModeServiceUUID` below names the
//    other one — see the note on `profile`.

object ForceBoardCodec {

    // MARK: - GATT wiring

    /// The service that STREAMS.
    ///
    /// `writeCharacteristicUUID` is the Device Mode characteristic, and it is NOT a
    /// member of `serviceUUID`'s service — it belongs to `deviceModeServiceUUID`.
    /// Characteristic UUIDs are globally unique on this device, so a client that
    /// discovers ALL services and then looks the characteristic UUIDs up across
    /// them finds both; a client that discovers only `serviceUUID` will find the
    /// notify characteristic and silently fail to start the stream.
    ///
    /// **No tare characteristic, deliberately — see `tareCharacteristicUUID` below.**
    ///
    /// Modes, from the reference's command table: 0x04 streaming (what we want),
    /// 0x05 tare-by-mode, 0x06 quick-start (streams only above a threshold —
    /// deliberately unused: a session must see the load fall to zero to know the
    /// climber came off the edge), 0x07 idle.
    val profile = GaugeGattProfile(
        serviceUUID = "9A88D67F-8DF2-4AFE-9E0D-C2BBBE773DD0",
        notifyCharacteristicUUID = "9A88D682-8DF2-4AFE-9E0D-C2BBBE773DD0",
        writeCharacteristicUUID = "467A8517-6E39-11EB-9439-0242AC130002",
        streamStartPayloads = listOf(byteArrayOf(0x04)),
        streamStopPayload = byteArrayOf(0x07),
        tareCharacteristicUUID = null,
        tarePayload = null,
    )

    /// The service holding the Device Mode characteristic that `profile` writes to.
    /// Exposed separately because the profile shape has room for exactly one
    /// service and this device needs two.
    const val deviceModeServiceUUID = "467A8516-6E39-11EB-9439-0242AC130002"

    /// **OPEN hardware check: the board's own tare, kept OUT of the profile.**
    ///
    /// Writing 0x01 here is supposed to zero the board, and the bytes are recorded so a
    /// hardware session can try them — but in the reference that write lives only in
    /// `tareByCharacteristic`, an API its own tare path never calls. ForceBoard does not
    /// override `tare()`, so the reference's shipped behaviour for this device is the base
    /// class's SOFTWARE tare, and `hasHardwareTare` says so.
    ///
    /// The two mechanisms are mutually exclusive in `GattGaugeClient` — a device that
    /// zeroes itself must not also have an app-side offset subtracted — so naming the
    /// characteristic in the profile would REPLACE the working tare with an unexercised
    /// one. If the write is wrong on real firmware, Tare becomes a silent no-op, and the
    /// session flow tares before every start: every reading would then carry the board's
    /// standing offset and every target-band gate would be judged against it. Promote
    /// these two constants into the profile only once a real unit confirms the write.
    const val tareCharacteristicUUID = "9A88D683-8DF2-4AFE-9E0D-C2BBBE773DD0"
    val tarePayload: ByteArray = byteArrayOf(0x01)

    // MARK: - Units

    /// One pound of force in kilograms of force. The reference converts through
    /// newtons (4.4482216152605 / 9.80665); that ratio is exactly the avoirdupois
    /// pound in kilograms, so the single multiply below is the same number without
    /// two divisions of rounding.
    ///
    /// That a raw count is WHOLE pounds is the one fact here with no second source:
    /// the reference asserts it only against its own fixture builder, whose sample
    /// values (1000, 1200 "lbs") are nothing a fingerboard ever sees. If the board
    /// actually streams tenths of a pound, every reading is off by a decimal factor
    /// — confirm against a known hanging weight before trusting the figures.
    const val poundsToKilograms: Double = 0.45359237

    /// Anything above this is a misframed packet, not a pull.
    ///
    /// **A GENEROUS ceiling rather than none**, which is the whole argument. The
    /// objection to a window here was that a bound picked for whole pounds would
    /// silently discard EVERY reading if the scaling turns out finer — true, and answered
    /// by 2000 lb (907 kg): it survives a 10× scaling surprise and still rejects what a
    /// bad frame can express, which is 255·32768 + 255·256 + 255 = 8,421,375 lb, i.e.
    /// 3.8 MILLION kilograms.
    ///
    /// The blast radius is why none was not an option. This protocol has no checksum, no
    /// header magic and no sequence field, and the decoder holds no reassembly buffer — so
    /// a packet over the ATT MTU has its continuation decoded as a fresh packet, bytes 0–1
    /// read as a sample count. `DeviceStore.peakKg` is `max(peakKg, sample.kg)` with no
    /// clamp and `MaxAttempt`'s peak is a running maximum, so ONE such reading sets the
    /// session peak, rescales the trace for the rest of the session and, inside a max
    /// attempt, becomes the recorded max that then sets every percentage target for that
    /// grip. Same house rule as the Progressor's −10…165 kg window: garbage is rejected AT
    /// THE CODEC, before it can reach the runner, trace, store or recorded maxes.
    const val maxPlausibleKilograms: Double = 907.0

    /// One 3-byte sample in pounds.
    ///
    ///     value = b0 * 32768 + b1 * 256 + b2
    ///
    /// The high byte is worth 2^15, not 2^16, so the middle byte only ever carries
    /// 7 bits. The reference's own fixture builder packs it that way
    /// (`floor(sample / 32768)`, `floor((sample % 32768) / 256)`), which is the only
    /// evidence we have of the layout, so it is ported to the digit rather than
    /// "corrected" into a 24-bit read. A naive `b0 << 16` decode agrees with this
    /// one for every sample under 32768 and then quietly doubles.
    ///
    /// Values are UNSIGNED: this frame cannot express a negative load at all, so
    /// drift below the board's own zero reads as zero. The Tare button still means
    /// something because the offset it applies is the app's, or the board's own via
    /// `tareCharacteristicUUID`.
    ///
    /// TRANSLATION NOTE: Swift takes a 3-tuple of `UInt8`; Kotlin has no tuple, so
    /// the three bytes are three `Int` parameters holding UNSIGNED byte values.
    fun pounds(b0: Int, b1: Int, b2: Int): Double =
        b0.toDouble() * 32768 + b1.toDouble() * 256 + b2.toDouble()

    // MARK: - Decoder

    class Decoder : GaugeFrameDecoder {

        /// Frame: `[count_hi, count_lo]` then `count` × 3-byte samples, all
        /// big-endian. The sample count is the device's promise about the rest of the
        /// packet; the sample arithmetic itself lives in `ForceBoardCodec.pounds`,
        /// where its oddity is documented.
        ///
        /// Nothing here spans notifications — every packet is self-describing — so
        /// unlike the Motherboard and CTS500 decoders this one holds no buffer, and a
        /// short read costs only the samples that were cut off.
        override fun ingest(data: ByteArray): List<GaugeReading> {
            // Two header bytes and at least one whole sample, or there is nothing to
            // say. A count-only packet is legal and means zero samples.
            if (data.size < 5) return emptyList()

            fun byteAt(index: Int): Int = data[index].toInt() and 0xFF

            val declared = (byteAt(0) shl 8) or byteAt(1)
            val readings = ArrayList<GaugeReading>(minOf(declared, (data.size - 2) / 3))

            for (index in 0 until declared) {
                val offset = 2 + index * 3
                // A packet claiming more samples than it carries stops here rather
                // than reading past the end. Real BLE delivers short reads.
                if (offset + 2 >= data.size) break

                val pounds = pounds(byteAt(offset), byteAt(offset + 1), byteAt(offset + 2))
                val kg = pounds * poundsToKilograms
                // Per SAMPLE, not per packet: each 3-byte sample is self-describing, so an
                // absurd one costs a fortieth of a second of trace while its neighbours
                // still stand. See `maxPlausibleKilograms` for what one of them would cost
                // if it got through.
                if (kg > maxPlausibleKilograms) continue
                readings.add(GaugeReading(kg = kg))
            }

            return readings
        }
    }
}
