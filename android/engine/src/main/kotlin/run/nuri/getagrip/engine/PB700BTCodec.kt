// SPDX-License-Identifier: MPL-2.0 AND BSD-2-Clause
// Original contributions Copyright 2026 Nuri Bruner.

package run.nuri.getagrip.engine

// The NSD PB-700BT. Protocol knowledge ported from hangtime-grip-connect
// (BSD-2-Clause, © 2024 Stevie-Ray Hartog,
// https://github.com/Stevie-Ray/hangtime-grip-connect).
//
// **THIS DEVICE MEASURES ROTATION SPEED, NOT FORCE.** The PB-700BT (sold as the NSD
// Spinner Bluetooth) is a gyroscopic hand exerciser — a powerball. Its notifications
// carry the PERIOD of one rotor revolution; the reference converts that to RPM and
// pushes the figure down the same "mass" channel it uses for load cells, because its
// device interface has exactly one numeric channel. The reference's own
// documentation calls it a "Bluetooth gyroscopic hand exerciser", and its plausible
// range for the value is 800…15000 — revolutions per minute, not kilograms.
//
// So `Decoder.ingest` decodes every frame and returns NO readings. There is no force
// in this protocol to port. Feeding four-figure RPM into a kilogram channel would
// arm every rep instantly, bank hang time at a load nobody pulled, and write a
// five-figure "max" into the `MaxTable` that then sets the percentage targets for
// that grip — a data-corruption path, not a cosmetic unit bug. The codebase's
// standing rule is that no number is better than a confident wrong one.
//
// The parse itself is kept, tested, and exposed as `revolutionsPerMinute(from:)` for
// the same reason the Progressor codec decodes the RFD tags it never consumes: a
// future rotation mode should be an addition, not a refactor. What it must not be is
// a silent reinterpretation of somebody's training history.
//
// Everything in the frame is BIG-ENDIAN.

object PB700BTCodec {
    /// The only notify characteristic the reference actually subscribes to: its
    /// generic connect path starts notifications on every declared characteristic
    /// whose id is `rx`, and for this device that is exactly one — `0000FFF4` under
    /// the ISSC transparent UART service `0000FFF0`. Extracted from the source rather
    /// than guessed off the UUID list, which also declares an unknown custom service
    /// (`0000FEBA` with `0000FA10`/`FA11`/`FA13`), five further unnamed UART
    /// characteristics, Device Information and the standard Battery Service. None of
    /// those is read for streaming, and the source says nothing about what the custom
    /// service does — if this device has a force or torque channel at all, that is
    /// where it would live, and nothing here establishes it.
    val profile = GaugeGattProfile(
        serviceUUID = "0000FFF0-0000-1000-8000-00805F9B34FB",
        notifyCharacteristicUUID = "0000FFF4-0000-1000-8000-00805F9B34FB",
        writeCharacteristicUUID = null,
        // Empty: the reference writes nothing to this device — no enable opcode, no
        // handshake. Subscribing is all there is.
        streamStartPayloads = emptyList(),
        streamStopPayload = null,
        tareCharacteristicUUID = null,
        tarePayload = null,
    )

    /// Ticks per second of whatever counter produces the period field. 666,666 is the
    /// reference's constant (`60 * (666666 / period)`), a 1.5 µs tick; it is not
    /// derived from anything documented, so treat it as the magic number that
    /// reproduces NSD's own RPM figures rather than as a known clock rate.
    const val timerTicksPerSecond: Double = 666_666.0

    /// The reference discards anything outside this band before reporting it. A
    /// powerball idles far above zero and tops out well below the range's ceiling, so
    /// the band doubles as the frame-sanity check: a byte-order mistake or a stray
    /// notification lands orders of magnitude outside it (reversing the period bytes
    /// of a 4000 RPM frame yields 0.15 RPM).
    val plausibleRPM: ClosedFloatingPointRange<Double> = 800.0..15_000.0

    /// Revolutions per minute from one notification, or nil when the frame is not a
    /// usable rotation sample.
    ///
    /// Layout, big-endian throughout:
    ///   bytes 0…3  uint32  rotor period, in `timerTicksPerSecond` ticks
    ///   bytes 4…7  uint32  the reference's `sampleIndex` (see `sampleIndex(from:)`)
    ///
    /// Eight bytes are required even though the period alone occupies four: the
    /// reference reads offset 4 unconditionally on every frame it accepts, so in a
    /// browser a 4…7-byte read throws out of the handler and yields nothing. Refusing
    /// it here reproduces that behaviour deliberately instead of accepting a frame the
    /// reference never accepted.
    ///
    /// A zero period is refused before the division rather than after: `60 * (x / 0)`
    /// is `.infinity`, which the plausibility band would also reject, but relying on
    /// that leaves a divide-by-zero one edit away from being the answer.
    ///
    /// Rounded to whole RPM, as the reference does.
    ///
    /// TRANSLATION NOTE: Swift's `Double.rounded()` is ties-AWAY-from-zero, while
    /// Kotlin's `kotlin.math.round` is ties-to-even. `Math.round`, i.e. floor(x + 0.5),
    /// agrees with Swift for every value this band can hold (all strictly positive).
    fun revolutionsPerMinute(data: ByteArray): Double? {
        if (data.size < 8) return null
        val period = bigEndianUInt32(data, 0) ?: return null
        if (period == 0u) return null

        val rpm = 60 * (timerTicksPerSecond / period.toDouble())
        if (!rpm.isFinite() || rpm !in plausibleRPM) return null
        return Math.round(rpm).toDouble()
    }

    /// The second word of the frame. The reference stores it as the packet's
    /// `sampleIndex`, which is a use, not a meaning: it could be a revolution
    /// counter, a device tick, or a session sample number, and the source does not
    /// say. **If it turns out to be a device clock this family gains a real
    /// `hasDeviceClock`** — until somebody establishes that on hardware, the client
    /// keeps stamping synthetically.
    fun sampleIndex(data: ByteArray): UInt? {
        if (data.size < 8) return null
        return bigEndianUInt32(data, 4)
    }

    class Decoder : GaugeFrameDecoder {
        /// The most recent decoded rotation speed, in RPM. Held so the parse is a
        /// real decode with an inspectable result rather than a discarded one, and so
        /// a future rotation feature has somewhere to read from. Nothing in the force
        /// path consumes it.
        var lastRPM: Double? = null
            private set

        /// Always returns no readings — see this file's header. The frame is decoded
        /// first: a device this app cannot honestly measure is still a device whose
        /// bytes we understand, and silence is the fail-closed answer, not the
        /// unexamined one.
        override fun ingest(data: ByteArray): List<GaugeReading> {
            revolutionsPerMinute(data)?.let { lastRPM = it }
            return emptyList()
        }
    }

    private fun bigEndianUInt32(bytes: ByteArray, offset: Int): UInt? {
        if (offset < 0 || offset + 4 > bytes.size) return null
        return ((bytes[offset].toInt() and 0xFF).toUInt() shl 24) or
            ((bytes[offset + 1].toInt() and 0xFF).toUInt() shl 16) or
            ((bytes[offset + 2].toInt() and 0xFF).toUInt() shl 8) or
            (bytes[offset + 3].toInt() and 0xFF).toUInt()
    }
}
