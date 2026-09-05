// SPDX-License-Identifier: MPL-2.0 AND BSD-2-Clause
// Original contributions Copyright 2026 Nuri Bruner.

import Foundation

// The Weiheng WH-C06 hanging scale (also sold as the MAT Muscle Meter; it
// advertises the local name `IF_B7`). Protocol knowledge ported from
// hangtime-grip-connect (BSD-2-Clause, © 2024 Stevie-Ray Hartog,
// https://github.com/Stevie-Ray/hangtime-grip-connect).
//
// This is the one supported gauge with NO CONNECTION AT ALL. The scale exposes no
// service worth talking to; it shouts its current reading in the manufacturer-data
// field of every advertisement, so there is no `GaugeGattProfile` and no
// `GaugeFrameDecoder` here — the client scans with duplicate advertisements allowed
// and hands each frame to `kilograms(fromManufacturerData:)`. "Connected" for this
// device means "advertisements are arriving", which is why silence, not a GATT
// event, is what ends the link.
//
// The weight field is BIG-ENDIAN — the opposite of the Progressor and of BLE's own
// headers. Read it the wrong way round and a 26 kg pull reports as 102.5 kg: a
// plausible number, not a crash, which is exactly why the fixture test asserts both
// orders.

enum WHC06Codec {
    /// Bluetooth SIG company identifier carried in the advertisement.
    ///
    /// **Not unique to this scale.** 0x0100 is assigned to TomTom International BV
    /// and Weiheng ships it anyway, so a company-ID match proves nothing on its own.
    /// The frame's SHAPE is the real filter — see `kilograms(fromManufacturerData:)`.
    static let companyID: UInt16 = 0x0100

    /// Advertisement silence that counts as a disconnect, matching the reference's
    /// own 10-second watchdog. There is no GATT link to lose, so this is the only
    /// signal that the scale has been switched off, walked away, or run flat.
    static let advertisementSilenceSeconds: TimeInterval = 10

    /// The local name the scale advertises. Recorded because the reference's React
    /// Native and Capacitor ports filter on it (the Web Bluetooth port filters on
    /// the company ID alone), so it is a usable SECOND check if 0x0100 traffic from
    /// other makers ever turns out to be a problem in the wild. Not required: iOS
    /// hands us the manufacturer data either way, and a name filter would silently
    /// exclude a relabelled unit.
    static let advertisedLocalName = "IF_B7"

    /// Byte offsets into CoreBluetooth's manufacturer-data value, which INCLUDES the
    /// 2-byte little-endian company ID. The Web Bluetooth reference indexes the
    /// payload *after* that prefix, so every reference offset shifts by +2 here: its
    /// weight offset 10 is our 12, its stability offset 14 is our 16. The reference's
    /// React Native port confirms the shift independently — it slices the full
    /// manufacturer data at hex characters 24…27, i.e. bytes 12…13.
    static let weightOffset = 12
    static let statusOffset = 16

    /// **Through the WEIGHT bytes and no further.** A frame this long carries everything
    /// the app reads, so it is everything the app may require.
    ///
    /// It used to demand 17 — through the status byte — on the reasoning that the whole
    /// documented shape is a stronger filter against another 0x0100 advertiser. But that
    /// byte is one the reference NAMES and never reads: its own read is commented out, the
    /// offset it actually requires is 11 (14 bytes here, with the company-ID prefix), and
    /// its React Native port needs only through byte 13. Rejecting is total —
    /// `BroadcastGaugeClient` reads nil as "not our advertisement" — so a real unit whose
    /// advertisement stops one byte short of a byte nobody parses would never reach
    /// `.connected`, and the ten-second silence watchdog would report it as a scale that is
    /// switched off. Undiagnosable silence is exactly what the "a connected-but-silent
    /// gauge must SAY so" rule exists to prevent, and no invented constant is worth it.
    ///
    /// The company ID, the weight field's own capacity window and the scale's lock on the
    /// first advertiser remain the filter.
    static let minimumFrameLength = 14

    /// Rated capacity of the scale (300 kg). The raw field is an unsigned 16-bit
    /// hundredth-kilogram count, so it can express 655.35 kg — a value the load cell
    /// cannot produce. Rejecting the impossible range is a free extra shape filter at
    /// the choke point, the same reasoning as the Progressor codec's −10…165 kg
    /// window: garbage must never reach the runner, the trace, or a recorded max.
    static let capacityKg: Double = 300

    /// Kilograms from one advertisement, or nil when this is not a WH-C06 frame.
    ///
    /// `data` is CoreBluetooth's `CBAdvertisementDataManufacturerDataKey` value,
    /// company-ID prefix included. Returns nil unless the company ID matches, the
    /// frame reaches through the weight field, and the reading is inside the
    /// scale's capacity. Zero IS a reading — an unloaded scale reports 0.00 kg, and
    /// conflating that with "no frame" would make the client treat a hanging idle
    /// scale as disconnected.
    ///
    /// No tare is applied here. The scale has no tare command of its own, so the
    /// client subtracts a captured baseline app-side; a codec that also subtracted
    /// would double-count it.
    static func kilograms(fromManufacturerData data: Data) -> Double? {
        // Index from zero regardless of how the Data was sliced upstream.
        let bytes = [UInt8](data)
        guard bytes.count >= minimumFrameLength else { return nil }
        guard UInt16(bytes[0]) | UInt16(bytes[1]) << 8 == companyID else { return nil }

        let raw = UInt16(bytes[weightOffset]) << 8 | UInt16(bytes[weightOffset + 1])
        let kg = Double(raw) / 100
        guard kg <= capacityKg else { return nil }
        return kg
    }

    /// The stability/unit byte, split into its two nibbles: high = a stability code,
    /// low = a unit code. **Read OPPORTUNISTICALLY: nil when the frame stops short of it**,
    /// which is not a reason to refuse the weight — see `minimumFrameLength`.
    ///
    /// The reference identifies this byte and its offset but leaves the read
    /// COMMENTED OUT, with no table for either nibble's values — so neither meaning
    /// is established, and nothing in Doigt gates on them. It is parsed here because
    /// the unit nibble is the open question that matters: `kilograms(...)` divides by
    /// 100 unconditionally and treats the result as kilograms, which is right only
    /// while the scale is set to kg. **OPEN hardware check:** switch the scale to
    /// pounds and log this nibble, then either convert in `kilograms(...)` or refuse
    /// the frame. Guessing the code now would ship arithmetic pointed at somebody's
    /// fingers on the strength of a commented-out line.
    static func status(fromManufacturerData data: Data) -> (stability: UInt8, unit: UInt8)? {
        let bytes = [UInt8](data)
        guard bytes.count > statusOffset else { return nil }
        guard UInt16(bytes[0]) | UInt16(bytes[1]) << 8 == companyID else { return nil }

        let byte = bytes[statusOffset]
        return (stability: byte >> 4, unit: byte & 0x0F)
    }
}
