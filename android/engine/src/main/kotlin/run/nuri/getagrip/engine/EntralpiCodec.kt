// SPDX-License-Identifier: MPL-2.0 AND BSD-2-Clause
// Original contributions Copyright 2026 Nuri Bruner.

package run.nuri.getagrip.engine

// The Entralpi force plate (and the scales it is built from — Lefu, Unique CW275).
// Protocol knowledge ported from hangtime-grip-connect (BSD-2-Clause,
// © 2024 Stevie-Ray Hartog, https://github.com/Stevie-Ray/hangtime-grip-connect).
//
// The simplest protocol of the lot: subscribing to the notify characteristic IS the
// start command — there is no opcode to write, no handshake and no stop — and each
// notification carries exactly one reading in its first two bytes, BIG-ENDIAN, in
// hundredths of a kilogram. Nothing here can fail except a short read.

object EntralpiCodec {
    /// Two services in the reference declare a notify ("rx") characteristic, and its
    /// generic connect path subscribes to BOTH: `0000FFF0` / `0000FFF4` (the ISSC
    /// transparent UART the CC254x-class hardware exposes) and `0000181D` / `0000FFF1`
    /// (the standard Weight Scale service, carrying a non-standard characteristic —
    /// a real 0x181D service would notify 0x2A9D). The same handler parses whichever
    /// one speaks, so the source does not say which actually streams.
    ///
    /// **We do not pick: the client subscribes to BOTH, exactly as the reference does.**
    /// The evidence is genuinely split — the fff0 service labels fff4 "RX" (their
    /// convention for the subscribe side) while the 0x181D entry names fff1 literally
    /// "notify", and fff0 lists that same fff1 as the UART's *TX*. A port that guessed
    /// wrong would connect, subscribe, and never deliver a byte, with nothing in the app
    /// able to tell that from a flat battery. The same decoder parses whichever one
    /// speaks, so the second subscription costs nothing and removes the coin flip:
    /// `0000FFF4` under `0000FFF0` is the primary, `0000FFF1` the alternate, resolved
    /// across every discovered service by `GattGaugeClient`.
    val profile = GaugeGattProfile(
        serviceUUID = "0000FFF0-0000-1000-8000-00805F9B34FB",
        notifyCharacteristicUUID = "0000FFF4-0000-1000-8000-00805F9B34FB",
        // Deliberately nil rather than the UART's TX (0000FFF1): there is nothing to
        // write. A write handle the client never needs is a handle a future author
        // will assume was verified.
        writeCharacteristicUUID = null,
        // Empty: the subscription is the start. The reference writes nothing to this
        // device anywhere — its only non-stream traffic is READS of Device
        // Information and the standard Battery Service.
        streamStartPayloads = emptyList(),
        streamStopPayload = null,
        alternateNotifyCharacteristicUUIDs = listOf(
            "0000FFF1-0000-1000-8000-00805F9B34FB",
        ),
        // No hardware tare exists, so the client subtracts a captured baseline.
        tareCharacteristicUUID = null,
        tarePayload = null,
    )

    /// Anything above this is not a pull, whatever the bytes say.
    ///
    /// 2000 lb, which is the same generous bound the Force Board carries and for the same
    /// reason: this protocol has no checksum, no framing and no header magic, so the only
    /// thing standing between two arbitrary bytes and a recorded max is a plausibility
    /// window. A ceiling picked around a human maximum would silently discard every
    /// reading if the scaling turns out finer than the documented hundredths, and total
    /// silence is far harder to diagnose than a number visibly out by 10×.
    ///
    /// **With the field as the reference reads it — one big-endian `uint16` of
    /// hundredths — nothing can reach this**, since 0xFFFF is 655.35 kg. That is the
    /// point rather than an oversight: the guard costs one comparison and is what fails
    /// closed the moment somebody widens the read or changes the divisor.
    const val maxPlausibleKilograms: Double = 907.0

    class Decoder : GaugeFrameDecoder {
        /// One notification, one reading: `uint16 big-endian / 100` kilograms.
        ///
        /// Anything past the first two bytes is IGNORED, exactly as the reference
        /// does — it reads offset 0 and nothing else, so a longer notification is not
        /// evidence of more samples we could be dropping. A notification shorter than
        /// two bytes decodes to nothing rather than to a half-read value; real BLE
        /// delivers short reads.
        ///
        /// The value is NOT rounded. The reference passes it through
        /// `toFixed(1)` — a string round-trip that quantises the device's own 0.01 kg
        /// resolution to 0.1 kg before anything else sees it. That is display
        /// formatting that leaked into the measurement: here the same number feeds a
        /// release band, a target-band gate and a recorded max, all of which compare
        /// exact kilograms, so the honest port keeps the resolution the device sent.
        ///
        /// `deviceMicros` stays nil: this device stamps nothing, so the client applies
        /// `SyntheticSampleClock` at ingestion and the runner clamps per-sample credit
        /// accordingly. A codec that invented a stamp would be inventing a timeline.
        override fun ingest(data: ByteArray): List<GaugeReading> {
            if (data.size < 2) return emptyList()

            val raw = ((data[0].toInt() and 0xFF) shl 8) or (data[1].toInt() and 0xFF)
            val kg = raw.toDouble() / 100
            // The choke point, same as the CTS500's: garbage must never reach the runner,
            // the trace, or a recorded max — see `maxPlausibleKilograms`.
            if (kg > maxPlausibleKilograms) return emptyList()
            return listOf(GaugeReading(kg = kg))
        }
    }
}
