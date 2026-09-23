// SPDX-License-Identifier: MPL-2.0 AND BSD-2-Clause
// Original contributions Copyright 2026 Nuri Bruner.

package run.nuri.getagrip.engine

// The NSD PB-700BT. Protocol knowledge ported from hangtime-grip-connect
// (BSD-2-Clause, © 2024 Stevie-Ray Hartog,
// https://github.com/Stevie-Ray/hangtime-grip-connect).
//
// **THIS DEVICE MEASURES ROTATION SPEED, NOT FORCE.** The PB-700BT (NSD Spinner
// Bluetooth) is a gyroscopic hand exerciser — a powerball. Its notifications carry the
// PERIOD of one rotor revolution; the reference converts that to RPM (plausible range
// 800…15000) and pushes it down its single "mass" channel.
//
// So `Decoder.ingest` decodes every frame and returns NO readings. Four-figure RPM in a
// kilogram channel would arm every rep instantly and write a five-figure "max" that sets
// the grip's percentage targets — data corruption, not a unit bug. No number beats a
// confident wrong one. It is also why `GaugeKind.selectable` omits this device.
//
// The parse is kept and tested (`revolutionsPerMinute(from:)`), like the Progressor's
// unconsumed RFD tags, so a future rotation mode is an addition, not a refactor.
//
// Everything in the frame is BIG-ENDIAN.

object PB700BTCodec {
    /// The only notify characteristic the reference subscribes to (its one `rx`):
    /// `0000FFF4` under the ISSC transparent UART service `0000FFF0`. The device also
    /// declares an unknown custom service (`0000FEBA` with `FA10`/`FA11`/`FA13`); if it
    /// has a force or torque channel at all it would live there, and nothing establishes it.
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

    /// Ticks per second of the period counter: the reference's undocumented constant
    /// (`60 * (666666 / period)`, a 1.5 µs tick) — a magic number, not a known clock rate.
    const val timerTicksPerSecond: Double = 666_666.0

    /// The reference discards anything outside this band, so it doubles as a frame
    /// sanity check: a byte-order mistake lands orders of magnitude outside it (a
    /// reversed 4000 RPM frame reads 0.15 RPM).
    val plausibleRPM: ClosedFloatingPointRange<Double> = 800.0..15_000.0

    /// Revolutions per minute from one notification, or nil when the frame is not a
    /// usable rotation sample.
    ///
    /// Layout, big-endian throughout:
    ///   bytes 0…3  uint32  rotor period, in `timerTicksPerSecond` ticks
    ///   bytes 4…7  uint32  the reference's `sampleIndex` (see `sampleIndex(from:)`)
    ///
    /// Eight bytes are required though the period needs four: the reference reads
    /// offset 4 unconditionally, so it never accepted a shorter frame either. A zero
    /// period is refused before the division, not left to the plausibility band.
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

    /// The second word, stored by the reference as `sampleIndex` — a use, not a meaning
    /// (revolution counter, device tick or sample number; the source does not say). **If
    /// hardware shows it is a device clock, this family gains `hasDeviceClock`.**
    fun sampleIndex(data: ByteArray): UInt? {
        if (data.size < 8) return null
        return bigEndianUInt32(data, 4)
    }

    class Decoder : GaugeFrameDecoder {
        /// The most recent rotation speed, in RPM, kept inspectable for a future rotation
        /// feature. Nothing in the force path consumes it.
        var lastRPM: Double? = null
            private set

        /// Always returns no readings — see this file's header. The frame is still
        /// decoded: silence is the fail-closed answer, not the unexamined one.
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
