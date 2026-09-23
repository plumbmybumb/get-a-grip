// SPDX-License-Identifier: MPL-2.0 AND BSD-2-Clause
// Original contributions Copyright 2026 Nuri Bruner.

import Foundation

// The PitchSix Force Board wire protocol — pure value types, no CoreBluetooth.
//
// Protocol knowledge ported from hangtime-grip-connect (BSD-2-Clause, © 2024
// Stevie-Ray Hartog, https://github.com/Stevie-Ray/hangtime-grip-connect).
//
// UNVERIFIED ON HARDWARE BY THIS PROJECT.
//
// Two facts here matter more than the rest of the file:
//
// 1. **The board streams POUNDS** (the reference's `streamUnit = "lbs"`). The app is
//    kilograms everywhere, so the conversion happens HERE, never downstream where a
//    display would have to remember which device it was drawing.
// 2. **The notify and write characteristics live in DIFFERENT SERVICES.** Data comes
//    out of the Force Board service; the mode command goes into the Weight service,
//    named by `deviceModeServiceUUID` — see the note on `profile`.

enum ForceBoardCodec {

    // MARK: - GATT wiring

    /// The service that STREAMS.
    ///
    /// `writeCharacteristicUUID` (Device Mode) belongs to `deviceModeServiceUUID`, not
    /// `serviceUUID`. A client must discover ALL services and look characteristics up
    /// across them; discovering only `serviceUUID` silently fails to start the stream.
    ///
    /// **No tare characteristic, deliberately — see `tareCharacteristicUUID` below.**
    ///
    /// Modes, from the reference's command table: 0x04 streaming (what we want),
    /// 0x05 tare-by-mode, 0x06 quick-start (streams only above a threshold —
    /// deliberately unused: a session must see the load fall to zero to know the
    /// climber came off the edge), 0x07 idle.
    static let profile = GaugeGattProfile(
        serviceUUID: "9A88D67F-8DF2-4AFE-9E0D-C2BBBE773DD0",
        notifyCharacteristicUUID: "9A88D682-8DF2-4AFE-9E0D-C2BBBE773DD0",
        writeCharacteristicUUID: "467A8517-6E39-11EB-9439-0242AC130002",
        streamStartPayloads: [Data([0x04])],
        streamStopPayload: Data([0x07]),
        tareCharacteristicUUID: nil,
        tarePayload: nil
    )

    /// The service holding the Device Mode characteristic that `profile` writes to.
    /// Exposed separately because the profile shape has room for exactly one
    /// service and this device needs two.
    static let deviceModeServiceUUID = "467A8516-6E39-11EB-9439-0242AC130002"

    /// **OPEN hardware check: the board's own tare, kept OUT of the profile.**
    ///
    /// Writing 0x01 should zero the board, but the reference only exposes it as
    /// `tareByCharacteristic`, which its own tare path never calls — its shipped
    /// behaviour is SOFTWARE tare, and `hasHardwareTare` says so.
    ///
    /// The two mechanisms are exclusive in `GattGaugeClient`, so naming this in the
    /// profile would REPLACE the working tare with an unexercised one. If the write is
    /// wrong on real firmware, Tare silently does nothing and every reading and band gate
    /// carries the board's standing offset. Promote these only once a real unit confirms.
    static let tareCharacteristicUUID = "9A88D683-8DF2-4AFE-9E0D-C2BBBE773DD0"
    static let tarePayload = Data([0x01])

    // MARK: - Units

    /// One pound-force in kilograms-force. The reference goes through newtons
    /// (4.4482216152605 / 9.80665), which is exactly the avoirdupois pound — one
    /// multiply, less rounding.
    ///
    /// That a raw count is WHOLE pounds has no second source: the reference asserts it
    /// only against its own fixture builder. If the board streams tenths, every reading
    /// is off by 10× — confirm against a known hanging weight before trusting it.
    static let poundsToKilograms: Double = 0.45359237

    /// Anything above this is a misframed packet, not a pull.
    ///
    /// **A GENEROUS ceiling rather than none.** 2000 lb (907 kg) survives a 10× scaling
    /// surprise — a tight bound would silently discard EVERY reading if the scaling is
    /// finer — and still rejects what a bad frame can express (8,421,375 lb).
    ///
    /// None was not an option: there is no checksum, header magic or sequence field, so
    /// a packet over the ATT MTU has its continuation decoded as a fresh packet. ONE such
    /// reading would set the session peak and, inside a max attempt, the recorded max
    /// behind every percentage target. As with the Progressor's −10…165 kg window,
    /// garbage is rejected AT THE CODEC.
    static let maxPlausibleKilograms: Double = 907

    /// One 3-byte sample in pounds.
    ///
    ///     value = b0 * 32768 + b1 * 256 + b2
    ///
    /// The high byte is worth 2^15, not 2^16, so the middle byte carries 7 bits. The
    /// reference's fixture builder packs it that way — the only evidence of the layout —
    /// so it is ported to the digit rather than "corrected": a naive `b0 << 16` decode
    /// agrees below 32768 and then quietly doubles.
    ///
    /// Values are UNSIGNED: drift below the board's own zero reads as zero.
    static func pounds(fromSample bytes: (UInt8, UInt8, UInt8)) -> Double {
        Double(bytes.0) * 32768 + Double(bytes.1) * 256 + Double(bytes.2)
    }

    // MARK: - Decoder

    struct Decoder: GaugeFrameDecoder {

        init() {}

        /// Frame: `[count_hi, count_lo]` then `count` × 3-byte samples, all
        /// big-endian. The sample count is the device's promise about the rest of the
        /// packet; the sample arithmetic itself lives in
        /// `ForceBoardCodec.pounds(fromSample:)`, where its oddity is documented.
        ///
        /// Every packet is self-describing, so there is no buffer: a short read costs
        /// only the samples that were cut off.
        mutating func ingest(_ data: Data) -> [GaugeReading] {
            let bytes = [UInt8](data)
            // Two header bytes and at least one whole sample, or there is nothing to
            // say. A count-only packet is legal and means zero samples.
            guard bytes.count >= 5 else { return [] }

            let declared = Int(bytes[0]) << 8 | Int(bytes[1])
            var readings: [GaugeReading] = []
            readings.reserveCapacity(min(declared, (bytes.count - 2) / 3))

            for index in 0..<declared {
                let offset = 2 + index * 3
                // A packet claiming more samples than it carries stops here rather
                // than reading past the end. Real BLE delivers short reads.
                guard offset + 2 < bytes.count else { break }

                let pounds = ForceBoardCodec.pounds(
                    fromSample: (bytes[offset], bytes[offset + 1], bytes[offset + 2])
                )
                let kg = pounds * ForceBoardCodec.poundsToKilograms
                // Per SAMPLE, not per packet: an absurd one costs a fortieth of a second
                // while its neighbours stand. See `maxPlausibleKilograms`.
                guard kg <= ForceBoardCodec.maxPlausibleKilograms else { continue }
                readings.append(GaugeReading(kg: kg))
            }

            return readings
        }
    }
}
