// SPDX-License-Identifier: MPL-2.0
// Original contributions Copyright 2026 Nuri Bruner.

package run.nuri.getagrip.engine

// The Frez Dyno wire protocol, v1 — pure value types, no Bluetooth, no network.
//
// Written from Frez's own published Dyno API, not from anybody's reverse engineering,
// which is a better witness than the ported gauges have — and still UNVERIFIED ON
// HARDWARE BY THIS PROJECT until a Dyno has pulled on this app.
//
// The device separates transport from calibration on purpose. It streams SIGNED RAW
// ADC counts with its own clock, nine to a notification at 250 Hz, and turning them
// into kilograms is the client's job:
//
//     tare_adc  = average(first 100 unloaded samples)
//     weight_kg = a × (raw_adc − tare_adc)
//
// where `a` is the per-device slope Frez's coefficient API returns for the serial.
// Both halves of that arithmetic live HERE, so the Bluetooth client is only bytes in,
// readings out, and both platforms assert the same fixtures against it.
//
// TRANSLATION NOTE: Swift's `Data` is a `ByteArray`, `UInt32` a `UInt` (whose
// arithmetic wraps natively, so `&*` and `&-` are the plain operators here), and
// `Int32` a Kotlin `Int`. The signed count is assembled through an `Int` exactly as
// Swift assembles a `UInt32` and re-reads its bit pattern — same 32 bits either way.

object FrezDynoCodec {

    // MARK: - Protocol constants

    const val serviceUUID = "DA8A6C41-154B-4B9A-9B00-2F84DFCEBFE9"
    const val notifyCharacteristicUUID = "DA8A6C42-154B-4B9A-9B00-2F84DFCEBFE9"
    const val writeCharacteristicUUID = "DA8A6C43-154B-4B9A-9B00-2F84DFCEBFE9"

    /// Device Information (0x180A) › Serial Number String, `FrezDyno-######`. The one
    /// read this gauge needs that no other does: the serial is the key to the coefficient.
    const val serialNumberCharacteristicUUID = "2A25"

    /// Device Information › Software Revision String. Frez documents the firmware/API
    /// version here (0x2A28), not in Firmware Revision (0x2A26) like the ported boards.
    const val softwareRevisionCharacteristicUUID = "2A28"
    const val advertisedNamePrefix = "FrezDyno-"

    /// Frez asks for MTU 85 where the platform allows it; a v1 notification is 74 bytes.
    /// Android can ask (`GattGaugeClient`), iOS negotiates for itself.
    const val preferredMTU = 85

    /// Two-byte command frames: the opcode, then a reserved zero.
    object Command {
        /// Starts a new session and resets the device's elapsed time to zero.
        val start: ByteArray get() = byteArrayOf(0x01, 0x00)
        val stop: ByteArray get() = byteArrayOf(0x02, 0x00)

        /// Deliberately never sent: it powers the device OFF and costs a physical
        /// button press to wake, the same wrong price the Tindeq's sleep opcode carries.
        val powerOff: ByteArray get() = byteArrayOf(0xFF.toByte(), 0x00)
    }

    const val bulkResponseCode: Int = 0x01
    const val reservedByte: Int = 0x00
    const val headerLength = 2
    const val recordLength = 8
    const val recordsPerFrame = 9

    /// 2 + 9 × 8. Frez: "treat a truncated frame as an error instead of parsing partial
    /// samples", so anything else is rejected whole.
    const val frameLength = headerLength + recordLength * recordsPerFrame
    const val nominalSampleRate: Double = 250.0
    const val standardGravity = 9.80665

    /// Frez's own number: keep the Dyno unloaded while the first 100 samples (0.4 s)
    /// establish the zero.
    const val defaultTareSampleCount = 100

    /// The device clock restarting on a Start command — which the app re-sends routinely
    /// (watchdog, foreground, tare recovery) — looks like a jump BACK of at least this
    /// much that lands under it: the previous record was a quarter second or more into
    /// the old session, the next is within a quarter second of zero. A restart's first
    /// record arrives within one notification (36 ms) of the Start, so the margin is
    /// generous; a small backwards step mid-stream satisfies neither half and is dropped,
    /// as Frez's defensive-client notes ask. The one case this refuses is a Start re-sent
    /// within a quarter second of the previous one, which then drops the handful of
    /// records until the new clock passes the old one — bounded by the threshold itself.
    val restartThresholdMs: UInt = 250u

    /// Nothing a hand does; a count the coefficient turns into more than this is a
    /// corrupt record. The same fail-closed headroom the other codecs use.
    const val maxPlausibleKilograms: Double = 1000.0

    // MARK: - GATT wiring

    /// Subscribe, then write Start; Stop on the way out. No one-time setup and no
    /// hardware tare — the zero is arithmetic, see `Decoder`.
    val profile = GaugeGattProfile(
        serviceUUID = serviceUUID,
        notifyCharacteristicUUID = notifyCharacteristicUUID,
        writeCharacteristicUUID = writeCharacteristicUUID,
        streamStartPayloads = listOf(Command.start),
        streamStopPayload = Command.stop,
    )

    // MARK: - Frame parsing

    /// One record of the bulk notification: a signed count and the device's elapsed
    /// milliseconds since the latest Start — cumulative, never a delta.
    data class RawSample(
        val rawADC: Int,
        val elapsedMs: UInt,
    )

    /// `[0x01] [0x00] + 9 × [int32 LE raw_adc] [uint32 LE elapsed_ms]`, or nil.
    ///
    /// All-or-nothing on purpose: a short read is an MTU problem to surface, not a
    /// frame to salvage, and a frame that fails the header is a different protocol.
    fun parse(data: ByteArray): List<RawSample>? {
        if (data.size != frameLength) return null
        if (byteAt(data, 0) != bulkResponseCode || byteAt(data, 1) != reservedByte) return null

        val samples = ArrayList<RawSample>(recordsPerFrame)
        var offset = headerLength
        repeat(recordsPerFrame) {
            val raw = byteAt(data, offset) or
                (byteAt(data, offset + 1) shl 8) or
                (byteAt(data, offset + 2) shl 16) or
                (byteAt(data, offset + 3) shl 24)
            val elapsed = byteAt(data, offset + 4).toUInt() or
                (byteAt(data, offset + 5).toUInt() shl 8) or
                (byteAt(data, offset + 6).toUInt() shl 16) or
                (byteAt(data, offset + 7).toUInt() shl 24)
            samples.add(RawSample(rawADC = raw, elapsedMs = elapsed))
            offset += recordLength
        }
        return samples
    }

    /// Kotlin's `Byte` is SIGNED, so every byte is widened through `and 0xFF` before it
    /// enters any arithmetic — the same trap `CTS500Codec` documents at length.
    private fun byteAt(data: ByteArray, index: Int): Int = data[index].toInt() and 0xFF

    /// The device clock in the engine's unit. Wrapping multiply on purpose: `elapsed_ms`
    /// wraps at 49 days, the product at the same ~71.6 minutes the Tindeq clock does, and
    /// every consumer already subtracts with wrapping subtraction.
    fun deviceMicros(elapsedMs: UInt): UInt = elapsedMs * 1000u

    // MARK: - Decoder

    /// Raw counts in, kilograms out — once it has a coefficient and a zero.
    ///
    /// **One tare per LINK, never per Start.** The app re-sends Start on every silence,
    /// foreground return and tare recovery, and Start resets the device clock, so a tare
    /// keyed to "the first samples after Start" would re-zero the gauge under whatever
    /// load was on it at the time — a hang, typically. The count offset is a property of
    /// the load cell, not of the session, so the zero taken when the link came up stays
    /// good; the Tare button's app-side capture sits on top of it for anything drifting.
    /// A new connection is a new decoder and a fresh zero, which is also what Frez asks
    /// for after an unexpected disconnect.
    ///
    /// TRANSLATION NOTE: Swift's `struct Decoder` with `mutating func ingest` is a class
    /// here, exactly as every other `GaugeFrameDecoder` conformer is — the client mints
    /// one per link either way.
    class Decoder(
        val coefficient: Double,
        tareSampleCount: Int = defaultTareSampleCount,
    ) : GaugeFrameDecoder {

        val tareSampleCount: Int = maxOf(1, tareSampleCount)

        private var tareSum: Double = 0.0
        private var tareCollected = 0
        var tareADC: Double? = null
            private set
        private var lastElapsedMs: UInt? = null

        /// Whether the zero has been established — the readout shows nothing before it.
        val isTared: Boolean get() = tareADC != null

        override fun ingest(data: ByteArray): List<GaugeReading> {
            val samples = parse(data) ?: return emptyList()
            val readings = mutableListOf<GaugeReading>()
            for (sample in samples) {
                if (!advanceClock(sample.elapsedMs)) continue
                if (tareADC == null) {
                    tareSum += sample.rawADC.toDouble()
                    tareCollected += 1
                    if (tareCollected == tareSampleCount) {
                        tareADC = tareSum / tareCollected.toDouble()
                    }
                    continue
                }
                val zero = tareADC ?: continue
                val kg = coefficient * (sample.rawADC.toDouble() - zero)
                if (!kg.isFinite() || kotlin.math.abs(kg) > maxPlausibleKilograms) continue
                readings.add(
                    GaugeReading(kg = kg, deviceMicros = deviceMicros(sample.elapsedMs)),
                )
            }
            return readings
        }

        /// Frez: "Reject duplicate or decreasing elapsed time within a measurement
        /// session." A jump back of at least `restartThresholdMs` that lands under it is
        /// the next session, not a fault; any other backwards step is dropped.
        private fun advanceClock(elapsedMs: UInt): Boolean {
            val last = lastElapsedMs
            if (last != null && elapsedMs <= last) {
                val threshold = restartThresholdMs
                val isRestart = elapsedMs < threshold && last - elapsedMs >= threshold
                if (!isRestart) return false
            }
            lastElapsedMs = elapsedMs
            return true
        }
    }
}
