// SPDX-License-Identifier: MPL-2.0
// Original contributions Copyright 2026 Nuri Bruner.

package run.nuri.getagrip.engine

// The Tindeq Progressor wire protocol — pure value types and pure functions.
//
// NOTHING in this file may import CoreBluetooth: the codec has to be testable
// byte-for-byte on any machine, and the Simulator has no Bluetooth stack at all.
// The GATT identifiers live here as strings; the BLE layer turns them into CBUUIDs.
// (Android's twin of that rule: `:engine` has no Android dependency at all, so the
// UUID strings become `java.util.UUID` in the `:app` BLE layer and nowhere else.)
//
// Source: Tindeq's published Progressor API (tindeq.com/progressor_api), whose
// opcode/response tables are images; the constants below are cross-checked against
// the open-source clients that transcribe them (blims/Tindeq-Progressor-API,
// Stevie-Ray/hangtime-grip-connect).
//
// Everything on the wire is LITTLE-ENDIAN.

// MARK: - GATT identifiers

object ProgressorGATT {
    /// The custom "Progressor" service. Scan by THIS rather than by name: the
    /// advertised name is `Progressor_<serial>`, which varies per unit.
    const val serviceUUID = "7E4E1701-1EA6-40C9-9DCC-13D34FFEAD57"

    /// Notifications land here. The client must subscribe before any measurement.
    const val dataCharacteristicUUID = "7E4E1702-1EA6-40C9-9DCC-13D34FFEAD57"

    /// Commands are written here.
    const val controlPointCharacteristicUUID = "7E4E1703-1EA6-40C9-9DCC-13D34FFEAD57"
}

// MARK: - Commands

/// TRANSLATION NOTE: Swift's raw type is `UInt8`; Kotlin carries the opcode as an
/// `Int` in 0…255 and narrows once, at the byte boundary in `encoded`. Every
/// comparison in this file then reads as arithmetic instead of as a cast.
enum class ProgressorCommand(val rawValue: Int) {
    tare(100),
    startWeightMeasurement(101),
    stopWeightMeasurement(102),
    startPeakRFDMeasurement(103),
    startPeakRFDSeries(104),
    addCalibrationPoint(105),
    saveCalibration(106),
    getAppVersion(107),
    getErrorInformation(108),
    clearErrorInformation(109),
    enterSleep(110),
    getBatteryVoltage(111);

    /// Commands whose reply arrives as a `.commandResponse` (tag 0) and therefore
    /// needs the pending command to be interpreted.
    val expectsResponse: Boolean
        get() = when (this) {
            getAppVersion, getErrorInformation, getBatteryVoltage -> true
            else -> false
        }

    /// The bytes to write to the control point.
    ///
    /// Zero-payload commands go out as a BARE OPCODE, not as a 2-byte `[opcode, 0]`
    /// TLV — that is what every field-proven open-source client sends and what the
    /// firmware answers. Only `addCalibrationPoint` carries a payload.
    val encoded: ByteArray get() = byteArrayOf(rawValue.toByte())

    companion object {
        /// Calibration is the one command with a payload: `[opcode, length, float32]`.
        ///
        /// UNVERIFIED against hardware — v1 never calibrates, and a wrong write here
        /// could corrupt a device's calibration table. Confirm the exact layout against
        /// Tindeq's own tables before exposing any calibration UI.
        ///
        /// TRANSLATION NOTE: named `addCalibrationPointPayload`, not
        /// `addCalibrationPoint`, because Kotlin puts an enum entry and a companion
        /// function of the same name in one lookup scope — Swift's `static func` and
        /// `case` of the same name do not collide, Kotlin's would.
        fun addCalibrationPointPayload(knownWeightKg: Float): ByteArray {
            val bits = knownWeightKg.toRawBits()
            return byteArrayOf(
                ProgressorCommand.addCalibrationPoint.rawValue.toByte(), 4,
                (bits and 0xFF).toByte(),
                ((bits ushr 8) and 0xFF).toByte(),
                ((bits ushr 16) and 0xFF).toByte(),
                ((bits ushr 24) and 0xFF).toByte(),
            )
        }
    }
}

// MARK: - Samples and events

/// One force reading straight off the device.
///
/// `deviceMicros` is the device's own microsecond clock since the measurement
/// started — NOT a host timestamp. All work-phase timing uses deltas of this,
/// because BLE delivery jitter (samples arrive ~80/s batched into ~10 packets/s)
/// makes host arrival time useless for measuring how long someone actually pulled.
///
/// It is a `UInt32`, so it WRAPS every 2^32 µs ≈ 71.6 minutes. Always subtract with
/// `&-` (see `microsSince`), never compare absolute values across a long span.
data class ForceSample(
    val kg: Double,
    val deviceMicros: UInt,
    /// True on the first force sample emitted by one BLE notification. The runner uses
    /// this boundary to reject a retransmitted batch as a unit rather than accepting its
    /// later, apparently-forward samples one by one.
    val isBatchStart: Boolean = true,
) {
    /// Elapsed microseconds from `earlier` to this sample, correct across the
    /// UInt32 wrap. Consecutive samples are ~12.5 ms apart against a 71.6-minute
    /// period, so at most one wrap can ever separate two of them.
    ///
    /// TRANSLATION NOTE: plain `-` on `UInt` is Swift's `&-`. Kotlin's unsigned
    /// arithmetic wraps by definition and has no trapping variant to pick by mistake.
    fun microsSince(earlier: ForceSample): UInt = deviceMicros - earlier.deviceMicros
}

/// Everything the device can tell us. The codec decodes the FULL tag surface even
/// though the timed-hang runner only consumes `.sample`, `.battery` and
/// `.appVersion` — decoding the rest costs nothing today and is what makes a future
/// RFD or max-strength mode an addition rather than a refactor.
///
/// TRANSLATION NOTE: Swift's enum-with-associated-values becomes a sealed interface;
/// the case names are capitalised because they are Kotlin TYPES. The two cases
/// carrying raw bytes hand-write `equals`/`hashCode`, since a generated data-class
/// `equals` compares a `ByteArray` by reference where Swift's `Data` compares by value.
sealed interface ProgressorEvent {
    data class Sample(val sample: ForceSample) : ProgressorEvent

    data class Battery(val millivolts: UInt) : ProgressorEvent

    /// Battery as a ready-made 0…1 fraction, from gauges that report the standard
    /// Battery Service percentage. The Progressor keeps `.battery` — its raw
    /// millivolts go through its own discharge curve, and forcing one shape on the
    /// other would bake a Tindeq-specific curve into every ported device.
    data class BatteryFraction(val fraction: Double) : ProgressorEvent

    data class AppVersion(val text: String) : ProgressorEvent

    data class ErrorInformation(val text: String) : ProgressorEvent

    /// Peak rate of force development. Layout INFERRED from the weight format
    /// (float + µs pair) and unverified on hardware — do not ship an RFD feature
    /// without confirming it.
    data class RfdPeak(val kg: Double, val micros: UInt) : ProgressorEvent

    data class RfdPeakSeries(val samples: List<ForceSample>) : ProgressorEvent

    data object LowPowerWarning : ProgressorEvent

    /// A tag-0 reply we can't attribute (no pending command, or an empty ack).
    class CommandResponse(val payload: ByteArray) : ProgressorEvent {
        override fun equals(other: Any?): Boolean =
            other is CommandResponse && payload.contentEquals(other.payload)

        override fun hashCode(): Int = payload.contentHashCode()

        override fun toString(): String = "CommandResponse(payload=${payload.toHexString()})"
    }

    class Unknown(val tag: Int, val payload: ByteArray) : ProgressorEvent {
        override fun equals(other: Any?): Boolean =
            other is Unknown && tag == other.tag && payload.contentEquals(other.payload)

        override fun hashCode(): Int = 31 * tag + payload.contentHashCode()

        override fun toString(): String = "Unknown(tag=$tag, payload=${payload.toHexString()})"
    }
}

/// Uppercase, unspaced hex — the same shape the shared `Fixtures/codec/*.json` files
/// use for every byte vector, so a failure message can be pasted straight into one.
internal fun ByteArray.toHexString(): String =
    joinToString("") { "%02X".format(it.toInt() and 0xFF) }

// MARK: - Decoder

object ProgressorCodec {

    /// Response tags carried in byte 0 of every notification.
    enum class ResponseTag(val rawValue: Int) {
        commandResponse(0),
        weightMeasurement(1),
        rfdPeak(2),
        rfdPeakSeries(3),
        lowPowerWarning(4);

        companion object {
            fun fromRaw(raw: Int): ResponseTag? = entries.firstOrNull { it.rawValue == raw }
        }
    }

    /// Battery in millivolts, roughly 3.0 V empty → 4.2 V full on the Progressor's
    /// LiPo. Only used for the rough level shown on the device chip.
    const val batteryEmptyMV: Double = 3300.0
    const val batteryFullMV: Double = 4200.0

    fun batteryFraction(millivolts: UInt): Double {
        val span = batteryFullMV - batteryEmptyMV
        return minOf(1.0, maxOf(0.0, (millivolts.toDouble() - batteryEmptyMV) / span))
    }

    /// Decode one notification payload.
    ///
    /// `answering` is the command we are still waiting on, which is the only way to
    /// tell a tag-0 battery reply from a tag-0 version reply — the device does not
    /// echo which command it is answering. Pass nil and tag-0 payloads come back as
    /// raw `.commandResponse`.
    ///
    /// Never throws, never traps: a truncated or malformed packet stops the walk and
    /// returns whatever was fully parsed. Real BLE hardware delivers short reads.
    ///
    /// TRANSLATION NOTE: Swift has two entry points, `decode(_ data: Data, …)` and
    /// `decode(bytes: [UInt8], …)`, because `Data` may be a slice whose indices do not
    /// start at zero. Kotlin's `ByteArray` is always zero-based, so the two collapse
    /// into this one function and the "index from zero regardless of how the Data was
    /// sliced" hazard cannot arise.
    fun decode(data: ByteArray, answering: ProgressorCommand? = null): List<ProgressorEvent> {
        val events = mutableListOf<ProgressorEvent>()
        var i = 0
        // A notification normally carries one TLV block, but the walk is a loop so a
        // packed packet decodes correctly too.
        while (i + 2 <= data.size) {
            val tag = data[i].toInt() and 0xFF
            val length = data[i + 1].toInt() and 0xFF
            val start = i + 2
            val end = start + length
            // Declared length runs past the packet: stop cleanly rather than
            // fabricating values from whatever bytes happen to follow.
            if (end > data.size) break
            val payload = data.copyOfRange(start, end)
            events += decodeBlock(tag, payload, answering)
            i = end
        }
        return validateWeightTimeline(events)
    }

    private fun decodeBlock(
        tag: Int,
        payload: ByteArray,
        answering: ProgressorCommand?,
    ): List<ProgressorEvent> = when (ResponseTag.fromRaw(tag)) {
        ResponseTag.weightMeasurement -> {
            if (payload.isEmpty() || payload.size % 8 != 0) {
                listOf(ProgressorEvent.Unknown(tag, payload))
            } else {
                samples(payload).map { ProgressorEvent.Sample(it) }
            }
        }

        ResponseTag.commandResponse -> when (answering) {
            ProgressorCommand.getBatteryVoltage -> {
                val mv = uint32(payload, 0)
                if (mv == null) {
                    listOf(ProgressorEvent.CommandResponse(payload))
                } else {
                    listOf(ProgressorEvent.Battery(mv))
                }
            }
            ProgressorCommand.getAppVersion -> listOf(ProgressorEvent.AppVersion(ascii(payload)))
            ProgressorCommand.getErrorInformation ->
                listOf(ProgressorEvent.ErrorInformation(ascii(payload)))
            else -> listOf(ProgressorEvent.CommandResponse(payload))
        }

        ResponseTag.rfdPeak -> {
            val s = if (payload.size == 8) samples(payload).firstOrNull() else null
            if (s == null) {
                listOf(ProgressorEvent.Unknown(tag, payload))
            } else {
                listOf(ProgressorEvent.RfdPeak(kg = s.kg, micros = s.deviceMicros))
            }
        }

        ResponseTag.rfdPeakSeries -> {
            if (payload.isEmpty() || payload.size % 8 != 0) {
                listOf(ProgressorEvent.Unknown(tag, payload))
            } else {
                val parsed = samples(payload)
                if (parsed.isEmpty()) {
                    listOf(ProgressorEvent.Unknown(tag, payload))
                } else {
                    listOf(ProgressorEvent.RfdPeakSeries(parsed))
                }
            }
        }

        ResponseTag.lowPowerWarning -> listOf(ProgressorEvent.LowPowerWarning)

        null -> listOf(ProgressorEvent.Unknown(tag, payload))
    }

    /// Validate all emitted weight samples across the WHOLE notification, including
    /// packed tag-1 blocks. A Progressor sample normally arrives every ~12.5 ms. One
    /// interval over 200 ms, or an aggregate span averaging over 25 ms per emitted
    /// sample, means the device timeline is corrupt; suppress the notification as a unit
    /// so none of it can arm, accrue, peak, or release a rep.
    ///
    /// The batch marker is assigned here, after force filtering and notification-wide
    /// validation. Assigning it inside `samples(in:)` would lose the boundary whenever a
    /// rejected leading pair preceded the first usable sample.
    private fun validateWeightTimeline(events: List<ProgressorEvent>): List<ProgressorEvent> {
        val weightSamples = events.mapNotNull { (it as? ProgressorEvent.Sample)?.sample }
        if (weightSamples.isEmpty()) return events

        for (index in 1 until weightSamples.size) {
            val delta = weightSamples[index].microsSince(weightSamples[index - 1])
            if (delta > 200_000u) {
                return events.filter { it !is ProgressorEvent.Sample }
            }
        }

        val first = weightSamples.first()
        val last = weightSamples.last()
        val totalSpan = last.microsSince(first).toULong()
        val spanBudget = weightSamples.size.toULong() * 25_000uL
        if (totalSpan > spanBudget) {
            return events.filter { it !is ProgressorEvent.Sample }
        }

        var markedStart = false
        return events.map { event ->
            if (event !is ProgressorEvent.Sample) {
                event
            } else {
                val marked = event.sample.copy(isBatchStart = !markedStart)
                markedStart = true
                ProgressorEvent.Sample(marked)
            }
        }
    }

    /// Walk a structurally valid payload of repeated 8-byte `(float32 kg, uint32 µs)`
    /// pairs, dropping readings a 150 kg load cell cannot physically produce. The
    /// −10…165 kg window allows negative zero drift and 10% calibration tolerance while
    /// rejecting finite garbage at the codec choke point, before it can reach the runner,
    /// trace, store, or recorded maxes.
    private fun samples(payload: ByteArray): List<ForceSample> {
        val out = ArrayList<ForceSample>(payload.size / 8)
        var i = 0
        while (i + 8 <= payload.size) {
            val raw = uint32(payload, i) ?: break
            val micros = uint32(payload, i + 4) ?: break
            // TRANSLATION NOTE: `Double(Float(bitPattern: raw))` is a widening of the
            // FLOAT value, so the double carries float precision exactly. Kotlin's
            // `Float.fromBits(...).toDouble()` is the same widening; re-parsing the
            // decimal text would not be.
            val kg = Float.fromBits(raw.toInt()).toDouble()
            if (kg.isFinite() && kg >= -10 && kg <= 165) {
                out.add(ForceSample(kg = kg, deviceMicros = micros))
            }
            i += 8
        }
        return out
    }

    private fun uint32(bytes: ByteArray, offset: Int): UInt? {
        if (offset < 0 || offset + 4 > bytes.size) return null
        return (bytes[offset].toInt() and 0xFF).toUInt() or
            ((bytes[offset + 1].toInt() and 0xFF).toUInt() shl 8) or
            ((bytes[offset + 2].toInt() and 0xFF).toUInt() shl 16) or
            ((bytes[offset + 3].toInt() and 0xFF).toUInt() shl 24)
    }

    /// Firmware strings are NUL-padded ASCII.
    private fun ascii(bytes: ByteArray): String =
        String(bytes.takeWhile { it.toInt() != 0 }.toByteArray(), Charsets.UTF_8)
}
