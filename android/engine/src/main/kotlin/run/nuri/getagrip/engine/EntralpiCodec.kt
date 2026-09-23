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
    /// Two services in the reference declare a notify ("rx") characteristic:
    /// `0000FFF0` / `0000FFF4` (the ISSC transparent UART) and `0000181D` / `0000FFF1`
    /// (Weight Scale, with a non-standard characteristic). The source does not say which
    /// streams, and the evidence is split.
    ///
    /// **We do not pick: the client subscribes to BOTH, as the reference does.** A wrong
    /// guess would connect and never deliver a byte, indistinguishable from a flat
    /// battery. `0000FFF4` is primary, `0000FFF1` the alternate, resolved across every
    /// discovered service by `GattGaugeClient`.
    val profile = GaugeGattProfile(
        serviceUUID = "0000FFF0-0000-1000-8000-00805F9B34FB",
        notifyCharacteristicUUID = "0000FFF4-0000-1000-8000-00805F9B34FB",
        // nil rather than the UART's TX (0000FFF1): there is nothing to write, and an
        // unneeded handle is one a future author will assume was verified.
        writeCharacteristicUUID = null,
        // Empty: the subscription is the start; the reference never writes to it.
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
    /// 2000 lb, the Force Board's bound for the same reason (see
    /// `ForceBoardCodec.maxPlausibleKilograms`): no checksum or framing, and a tighter
    /// ceiling would silently discard everything if the scaling is finer. A `uint16` of
    /// hundredths cannot reach it (max 655.35 kg) — on purpose: it fails closed the
    /// moment somebody widens the read or changes the divisor.
    const val maxPlausibleKilograms: Double = 907.0

    class Decoder : GaugeFrameDecoder {
        /// One notification, one reading: `uint16 big-endian / 100` kilograms.
        ///
        /// Bytes past the first two are IGNORED, as in the reference; fewer than two
        /// decode to nothing rather than a half-read value.
        ///
        /// NOT rounded: the reference's `toFixed(1)` is display formatting that leaked
        /// into the measurement, and here the value feeds gates and recorded maxes that
        /// compare exact kilograms.
        ///
        /// `deviceMicros` stays nil: the device stamps nothing, so the client applies
        /// `SyntheticSampleClock`. A codec that invented a stamp would invent a timeline.
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
