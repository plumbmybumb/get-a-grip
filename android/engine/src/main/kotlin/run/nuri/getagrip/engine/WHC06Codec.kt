// SPDX-License-Identifier: MPL-2.0 AND BSD-2-Clause
// Original contributions Copyright 2026 Nuri Bruner.

package run.nuri.getagrip.engine

// The Weiheng WH-C06 hanging scale (also sold as the MAT Muscle Meter; it
// advertises the local name `IF_B7`). Protocol knowledge ported from
// hangtime-grip-connect (BSD-2-Clause, © 2024 Stevie-Ray Hartog,
// https://github.com/Stevie-Ray/hangtime-grip-connect).
//
// The one gauge with NO CONNECTION AT ALL: the scale shouts its reading in the
// manufacturer data of every advertisement, so there is no GATT profile or frame
// decoder. The client scans with duplicates allowed and hands each frame to
// `kilogramsFromManufacturerData`. "Connected" means "advertisements are arriving", so
// silence ends the link.
//
// The weight field is BIG-ENDIAN, unlike the Progressor and BLE's own headers. Read
// backwards, a 26 kg pull reports 102.5 kg — plausible, hence a test of both orders.

object WHC06Codec {
    /// Bluetooth SIG company identifier carried in the advertisement.
    ///
    /// **Not unique to this scale.** 0x0100 is assigned to TomTom International BV
    /// and Weiheng ships it anyway, so a company-ID match proves nothing on its own.
    /// The frame's SHAPE is the real filter — see `kilogramsFromManufacturerData`.
    ///
    /// TRANSLATION NOTE: Swift's `UInt16` is an `Int` in 0…65535 here, as everywhere
    /// in the ported codecs — the value is compared, never made to overflow.
    const val companyID: Int = 0x0100

    /// Advertisement silence that counts as a disconnect, matching the reference's
    /// own 10-second watchdog. There is no GATT link to lose, so this is the only
    /// signal that the scale has been switched off, walked away, or run flat.
    const val advertisementSilenceSeconds: Double = 10.0

    /// The local name the scale advertises. Some reference ports filter on it, so it is
    /// a possible SECOND check if other 0x0100 traffic ever becomes a problem. Not
    /// required: a name filter would silently exclude a relabelled unit.
    const val advertisedLocalName = "IF_B7"

    /// Byte offsets into CoreBluetooth's manufacturer-data value, which INCLUDES the
    /// 2-byte little-endian company ID. The Web Bluetooth reference indexes after that
    /// prefix, so its offsets 10 and 14 are our 12 and 16 (its React Native port, slicing
    /// the full data at bytes 12…13, confirms the shift).
    ///
    /// (Android's `ScanRecord.getManufacturerSpecificData(id)` strips the company-ID
    /// prefix, so the `:app` scanner must re-prepend it — or read the raw bytes — to
    /// hand this function the shape it documents. Recorded here because getting that
    /// wrong shifts every offset by two and yields a plausible wrong load.)
    const val weightOffset = 12
    const val statusOffset = 16

    /// **Through the WEIGHT bytes and no further** — everything the app reads.
    ///
    /// Demanding 17 (through the status byte) filtered harder, but the reference never
    /// reads that byte: its own required offset is 11 (14 here). Rejection is total — nil
    /// means "not our advertisement" — so a unit one byte short would never connect, and
    /// the silence watchdog would report it switched off: undiagnosable silence. The
    /// company ID, the capacity window and the lock on the first advertiser remain the
    /// filter.
    const val minimumFrameLength = 14

    /// Rated capacity (300 kg). The raw field can express 655.35 kg, which the load cell
    /// cannot produce, so rejecting that range is a free shape filter — as with the
    /// Progressor's −10…165 kg window, garbage never reaches the runner or a max.
    const val capacityKg: Double = 300.0

    /// The stability/unit byte, split into its two nibbles.
    ///
    /// TRANSLATION NOTE: Swift returns a named tuple `(stability: UInt8, unit: UInt8)?`;
    /// Kotlin has no named tuple that survives into a test assertion legibly, so the
    /// pair is a small value type with the same two member names.
    data class Status(val stability: Int, val unit: Int)

    /// The unit the scale is SET TO (low nibble of the status byte); the weight field is
    /// hundredths of whatever the display shows, not of a kilogram.
    ///
    /// The maker's `ScaleWatcher.java` (Weiheng's SDK, via sebws/Crane) declares
    /// "重量单位 1：kg, 2：LB, 3：ST, 4：斤"; TheLastKiwi/Dyna, written against a US unit,
    /// saw **0 in pounds**. So 1 kg, 0 and 2 lb, 3 stone, 4 jin (half a kilogram); nothing
    /// else is known. The hangtime reference leaves the nibble unread, so pounds arrived
    /// 2.2× too large until a field report (2026-09-18).
    enum class Unit(val kilogramsPerUnit: Double) {
        kilograms(1.0),
        pounds(0.45359237),
        stone(6.35029318),
        jin(0.5);

        companion object {
            fun fromCode(code: Int): Unit? = when (code) {
                1 -> kilograms
                0, 2 -> pounds
                3 -> stone
                4 -> jin
                else -> null
            }
        }
    }

    /// Kilograms from one advertisement, or nil when this is not a WH-C06 frame.
    ///
    /// `data` is CoreBluetooth's `CBAdvertisementDataManufacturerDataKey` value,
    /// company-ID prefix included. nil unless the company ID matches, the frame reaches
    /// through the weight field and the reading is inside capacity. Zero IS a reading:
    /// treating an unloaded scale's 0.00 as "no frame" would disconnect an idle scale.
    ///
    /// Converted by the scale's own `Unit`. **An unknown unit (nibble absent, or an
    /// unlisted code) reads as kilograms**; the client's diagnostics name the code and
    /// raw count so it can be added rather than guessed at.
    ///
    /// No tare here: the client subtracts a captured baseline, so this would double-count.
    ///
    /// TRANSLATION NOTE: Swift's argument label folds into the name —
    /// `kilograms(fromManufacturerData:)` becomes `kilogramsFromManufacturerData`.
    fun kilogramsFromManufacturerData(data: ByteArray): Double? {
        if (data.size < minimumFrameLength) return null
        if ((data[0].toInt() and 0xFF) or ((data[1].toInt() and 0xFF) shl 8) != companyID) return null

        val raw = rawCountFromManufacturerData(data) ?: return null
        val factor = unitFromManufacturerData(data)?.kilogramsPerUnit ?: 1.0
        val kg = raw.toDouble() / 100 * factor
        if (kg > capacityKg) return null
        return kg
    }

    /// The raw 16-bit count the weight is converted from — hundredths of the display unit
    /// — or null when this is not a WH-C06 frame. For diagnostics: the one number that says
    /// what the scale sent, beside the unit it said it was in.
    fun rawCountFromManufacturerData(data: ByteArray): Int? {
        if (data.size < minimumFrameLength) return null
        if ((data[0].toInt() and 0xFF) or ((data[1].toInt() and 0xFF) shl 8) != companyID) return null
        return ((data[weightOffset].toInt() and 0xFF) shl 8) or (data[weightOffset + 1].toInt() and 0xFF)
    }

    /// The scale's display unit, or null when the frame stops short of the status byte or
    /// carries a code outside the maker's table. Null means "read as kilograms" — see
    /// `kilogramsFromManufacturerData`.
    fun unitFromManufacturerData(data: ByteArray): Unit? =
        statusFromManufacturerData(data)?.let { Unit.fromCode(it.unit) }

    /// The stability/unit byte: high nibble a stability code (no known table, nothing
    /// gates on it), low nibble a `Unit` code. **Read OPPORTUNISTICALLY: nil when the
    /// frame stops short of it**, which is no reason to refuse the weight.
    fun statusFromManufacturerData(data: ByteArray): Status? {
        if (data.size <= statusOffset) return null
        if ((data[0].toInt() and 0xFF) or ((data[1].toInt() and 0xFF) shl 8) != companyID) return null

        val byte = data[statusOffset].toInt() and 0xFF
        return Status(stability = byte shr 4, unit = byte and 0x0F)
    }
}
