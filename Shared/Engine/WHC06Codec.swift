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

    /// Rated capacity of the scale (300 kg). The raw field is an unsigned 16-bit count
    /// of hundredths of the display unit, so in kilograms it can express 655.35 kg — a
    /// value the load cell cannot produce. Rejecting the impossible range is a free extra shape filter at
    /// the choke point, the same reasoning as the Progressor codec's −10…165 kg
    /// window: garbage must never reach the runner, the trace, or a recorded max.
    static let capacityKg: Double = 300

    /// The unit the scale is SET TO, from the low nibble of the status byte. The weight
    /// field is hundredths of whatever the display shows, not of a kilogram.
    ///
    /// Two firmwares are known, and they agree on kilograms. The maker's own reference
    /// (Weiheng's `ScaleWatcher.java`, shipped with the scale's SDK and carried in
    /// sebws/Crane) declares "重量单位 1：kg, 2：LB, 3：ST, 4：斤" — kilograms, pounds,
    /// stone, jin (the Chinese catty, half a kilogram). TheLastKiwi/Dyna, written against
    /// a US unit, recorded the byte as 1 in kilograms and **0 in pounds**. So 1 is
    /// kilograms, 0 and 2 are pounds, 3 stone, 4 jin, and nothing else is known. The
    /// hangtime reference this codec was ported from names the nibble and leaves it
    /// unread, which is why every reading was kilograms until a field report (2026-09-18,
    /// a Pixel 8 and a scale switched to pounds) saw every number arrive 2.2× too large.
    enum Unit: CaseIterable, Sendable {
        case kilograms, pounds, stone, jin

        init?(code: UInt8) {
            switch code {
            case 1:    self = .kilograms
            case 0, 2: self = .pounds
            case 3:    self = .stone
            case 4:    self = .jin
            default:   return nil
            }
        }

        var kilogramsPerUnit: Double {
            switch self {
            case .kilograms: 1
            case .pounds:    0.45359237
            case .stone:     6.35029318
            case .jin:       0.5
            }
        }
    }

    /// Kilograms from one advertisement, or nil when this is not a WH-C06 frame.
    ///
    /// `data` is CoreBluetooth's `CBAdvertisementDataManufacturerDataKey` value,
    /// company-ID prefix included. Returns nil unless the company ID matches, the
    /// frame reaches through the weight field, and the reading is inside the
    /// scale's capacity. Zero IS a reading — an unloaded scale reports 0.00 kg, and
    /// conflating that with "no frame" would make the client treat a hanging idle
    /// scale as disconnected.
    ///
    /// The raw field is converted by the scale's own unit (`Unit`). **A unit this codec
    /// does not know — the nibble absent on a short frame, or a code neither firmware
    /// uses — reads as kilograms**, which is what every frame read as before the nibble
    /// was decoded, and the client's diagnostics name the code and the raw count so a
    /// new one can be added rather than guessed at.
    ///
    /// No tare is applied here. The scale has no tare command of its own, so the
    /// client subtracts a captured baseline app-side; a codec that also subtracted
    /// would double-count it.
    static func kilograms(fromManufacturerData data: Data) -> Double? {
        // Index from zero regardless of how the Data was sliced upstream.
        let bytes = [UInt8](data)
        guard bytes.count >= minimumFrameLength else { return nil }
        guard UInt16(bytes[0]) | UInt16(bytes[1]) << 8 == companyID else { return nil }

        guard let raw = rawCount(fromManufacturerData: data) else { return nil }
        let factor = unit(fromManufacturerData: data)?.kilogramsPerUnit ?? 1
        let kg = Double(raw) / 100 * factor
        guard kg <= capacityKg else { return nil }
        return kg
    }

    /// The raw 16-bit count the weight is converted from — hundredths of the display unit
    /// — or nil when this is not a WH-C06 frame. For diagnostics: the one number that says
    /// what the scale sent, beside the unit it said it was in.
    static func rawCount(fromManufacturerData data: Data) -> UInt16? {
        let bytes = [UInt8](data)
        guard bytes.count >= minimumFrameLength else { return nil }
        guard UInt16(bytes[0]) | UInt16(bytes[1]) << 8 == companyID else { return nil }
        return UInt16(bytes[weightOffset]) << 8 | UInt16(bytes[weightOffset + 1])
    }

    /// The scale's display unit, or nil when the frame stops short of the status byte or
    /// carries a code outside the maker's table. Nil means "read as kilograms" — see
    /// `kilograms(fromManufacturerData:)`.
    static func unit(fromManufacturerData data: Data) -> Unit? {
        status(fromManufacturerData: data).flatMap { Unit(code: $0.unit) }
    }

    /// The stability/unit byte, split into its two nibbles: high = a stability code,
    /// low = a unit code (`Unit`). **Read OPPORTUNISTICALLY: nil when the frame stops
    /// short of it**, which is not a reason to refuse the weight — see
    /// `minimumFrameLength`. The stability nibble has no table anywhere and nothing in
    /// the app gates on it.
    static func status(fromManufacturerData data: Data) -> (stability: UInt8, unit: UInt8)? {
        let bytes = [UInt8](data)
        guard bytes.count > statusOffset else { return nil }
        guard UInt16(bytes[0]) | UInt16(bytes[1]) << 8 == companyID else { return nil }

        let byte = bytes[statusOffset]
        return (stability: byte >> 4, unit: byte & 0x0F)
    }
}
