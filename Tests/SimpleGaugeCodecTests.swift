// SPDX-License-Identifier: MPL-2.0
// Original contributions Copyright 2026 Nuri Bruner.

import XCTest
@testable import Doigt

/// Byte-level fixtures for the three simplest ported gauges: the WH-C06 broadcast
/// scale, the Entralpi force plate and the NSD PB-700BT.
///
/// Every fixture is hand-computed from the hangtime-grip-connect source, with the
/// arithmetic written out in the comment — a decoder tested only against its own
/// encoder agrees with itself about the wrong byte order. All three of these
/// protocols are BIG-ENDIAN, which is the opposite of the Progressor's, and that is
/// the dangerous class of bug here: a reversed 26.00 kg reads as 102.50 kg, a
/// plausible load rather than an obvious crash. So the endianness tests assert the
/// value BOTH ways round.
final class SimpleGaugeCodecTests: XCTestCase {

    // MARK: - WH-C06 (broadcast advertisement)

    /// A full-shape advertisement, company-ID prefix included, exactly as
    /// CoreBluetooth hands it over.
    private func whc06Advertisement(weightRaw: UInt16,
                                    status: UInt8 = 0x00,
                                    companyID: UInt16 = 0x0100,
                                    extraTrailingBytes: Int = 0) -> Data {
        var bytes = [UInt8](repeating: 0, count: 17 + extraTrailingBytes)
        bytes[0] = UInt8(truncatingIfNeeded: companyID)          // little-endian company ID
        bytes[1] = UInt8(truncatingIfNeeded: companyID >> 8)
        bytes[12] = UInt8(truncatingIfNeeded: weightRaw >> 8)    // big-endian weight
        bytes[13] = UInt8(truncatingIfNeeded: weightRaw)
        bytes[16] = status
        return Data(bytes)
    }

    /// The reference indexes the payload AFTER the 2-byte company ID (weight at 10),
    /// so on iOS the same field sits at 12. 0x0A28 = 2600 → 26.00 kg.
    func testWHC06WeightIsBigEndianHundredthsAtTheIOSPrefixedOffset() {
        let frame = Data([0x00, 0x01,                                     // company 0x0100, LE
                          0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00,
                          0x0A, 0x28,                                     // weight 2600
                          0x00, 0x00,
                          0x00])                                          // status
        XCTAssertEqual(WHC06Codec.kilograms(fromManufacturerData: frame), 26.00)
    }

    /// The same two bytes the other way round: 0x280A = 10250 → 102.50 kg. Both
    /// readings are loads a 300 kg crane scale could plausibly report, which is why
    /// nothing but a fixture catches this.
    func testWHC06ReversedWeightBytesAreADifferentPlausibleLoad() {
        let reversed = whc06Advertisement(weightRaw: 0x280A)
        XCTAssertEqual(WHC06Codec.kilograms(fromManufacturerData: reversed), 102.50)
    }

    /// 0x0100 is shared with TomTom, so the ID alone is not a filter — but a frame
    /// carrying somebody else's ID is not this scale's frame at all.
    func testWHC06RejectsAnotherMakersCompanyID() {
        let apple = whc06Advertisement(weightRaw: 2600, companyID: 0x004C)
        XCTAssertNil(WHC06Codec.kilograms(fromManufacturerData: apple))
        XCTAssertNil(WHC06Codec.status(fromManufacturerData: apple))
    }

    /// A frame is required to reach through the WEIGHT field and no further. Anything
    /// shorter cannot be read at all; anything longer is padding.
    ///
    /// The old floor was 17 — the whole documented shape, through a status byte the
    /// reference itself leaves unread — and a real unit stopping one byte short of it
    /// would have been refused, reaching the user as a scale that never connects. That is
    /// the undiagnosable silence the codec's own rule forbids.
    func testWHC06RequiresOnlyTheBytesItActuallyReads() {
        let whole = whc06Advertisement(weightRaw: 2600, status: 0xA3)
        for length in 0..<WHC06Codec.minimumFrameLength {
            XCTAssertNil(WHC06Codec.kilograms(fromManufacturerData: whole.prefix(length)),
                         "a \(length)-byte advertisement stops short of the weight field")
        }
        XCTAssertEqual(WHC06Codec.minimumFrameLength, WHC06Codec.weightOffset + 2,
                       "the floor is the weight field, not the documented tail")
        XCTAssertEqual(WHC06Codec.kilograms(fromManufacturerData: whole.prefix(14)), 26.00)
        XCTAssertEqual(WHC06Codec.kilograms(fromManufacturerData: whole), 26.00)
    }

    /// The status byte is read opportunistically, so a frame that ends before it yields a
    /// weight and no status — rather than nothing at all.
    func testWHC06StatusIsAbsentRatherThanFatalOnAShortFrame() {
        let short = whc06Advertisement(weightRaw: 2600, status: 0xA3).prefix(14)
        XCTAssertEqual(WHC06Codec.kilograms(fromManufacturerData: short), 26.00)
        XCTAssertNil(WHC06Codec.status(fromManufacturerData: short))
    }

    /// Advertisements are padded and vendors append; extra trailing bytes are not a
    /// reason to refuse a frame whose documented fields are all present.
    func testWHC06AcceptsAFrameLongerThanTheDocumentedShape() {
        let padded = whc06Advertisement(weightRaw: 2600, extraTrailingBytes: 6)
        XCTAssertEqual(WHC06Codec.kilograms(fromManufacturerData: padded), 26.00)
    }

    /// The raw field can express 655.35 kg on a 300 kg load cell, so the impossible
    /// range is rejected at the codec choke point rather than reaching the runner.
    func testWHC06RejectsLoadsBeyondTheScalesCapacity() {
        // 30000 → 300.00 kg (the rating itself), 30001 → 300.01 kg (one hundredth past).
        XCTAssertEqual(WHC06Codec.kilograms(fromManufacturerData:
            whc06Advertisement(weightRaw: 30_000)), 300.00)
        XCTAssertNil(WHC06Codec.kilograms(fromManufacturerData:
            whc06Advertisement(weightRaw: 30_001)))
        XCTAssertNil(WHC06Codec.kilograms(fromManufacturerData:
            whc06Advertisement(weightRaw: .max)))
    }

    /// An idle scale reports 0.00 kg every advertisement. Reading that as "no frame"
    /// would make a hanging, switched-on scale look disconnected after ten seconds.
    func testWHC06ZeroIsAReadingNotAnAbsentFrame() {
        XCTAssertEqual(WHC06Codec.kilograms(fromManufacturerData:
            whc06Advertisement(weightRaw: 0)), 0.0)
    }

    /// High nibble stability, low nibble unit — parsed, unused, and mapped to nothing
    /// because the reference leaves this read commented out with no table for either.
    func testWHC06StatusByteSplitsIntoStabilityAndUnitNibbles() {
        let frame = whc06Advertisement(weightRaw: 2600, status: 0xA3)
        let status = WHC06Codec.status(fromManufacturerData: frame)
        XCTAssertEqual(status?.stability, 0x0A)
        XCTAssertEqual(status?.unit, 0x03)
    }

    func testWHC06ConstantsMatchTheReference() {
        XCTAssertEqual(WHC06Codec.companyID, 0x0100)
        XCTAssertEqual(WHC06Codec.advertisementSilenceSeconds, 10)
        XCTAssertEqual(WHC06Codec.weightOffset, 12, "reference offset 10 plus the company-ID prefix")
        XCTAssertEqual(WHC06Codec.statusOffset, 16, "reference offset 14 plus the company-ID prefix")
        XCTAssertEqual(WHC06Codec.minimumFrameLength, 14,
                       "the reference's own required offset is 11, i.e. 13 with the prefix")
    }

    // MARK: - Entralpi (one reading per notification)

    /// 0x09C4 = 2500 → 25.00 kg.
    func testEntralpiWeightIsBigEndianHundredthsOfAKilogram() {
        var decoder = EntralpiCodec.Decoder()
        XCTAssertEqual(decoder.ingest(Data([0x09, 0xC4])), [GaugeReading(kg: 25.0)])
    }

    /// The same bytes reversed: 0xC409 = 50185 → 501.85 kg.
    func testEntralpiReversedBytesAreADifferentLoad() {
        var decoder = EntralpiCodec.Decoder()
        let readings = decoder.ingest(Data([0xC4, 0x09]))
        XCTAssertEqual(readings.count, 1)
        XCTAssertEqual(readings.first?.kg ?? 0, 501.85, accuracy: 0.0001)
    }

    /// The reference runs the value through `toFixed(1)` — a string round-trip that
    /// quantises the device's own 0.01 kg resolution to 0.1 kg before the release
    /// band, the target-band gate or a recorded max ever sees it. We keep the
    /// resolution the device sent; 0x09C5 = 2501 → 25.01 kg, not 25.0.
    func testEntralpiKeepsHundredthResolutionRatherThanTheReferencesDisplayRounding() {
        var decoder = EntralpiCodec.Decoder()
        let readings = decoder.ingest(Data([0x09, 0xC5]))
        XCTAssertEqual(readings.first?.kg ?? 0, 25.01, accuracy: 0.0001)
        XCTAssertNotEqual(readings.first?.kg, 25.0)
    }

    /// One notification is one sample: the reference reads offset 0 and nothing else,
    /// so a longer notification is not evidence of samples we are dropping.
    func testEntralpiIgnoresEverythingPastTheFirstTwoBytes() {
        var decoder = EntralpiCodec.Decoder()
        let padded = Data([0x09, 0xC4, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF])
        XCTAssertEqual(decoder.ingest(padded), [GaugeReading(kg: 25.0)])
    }

    func testEntralpiShortNotificationDecodesToNothing() {
        var decoder = EntralpiCodec.Decoder()
        XCTAssertEqual(decoder.ingest(Data()), [])
        XCTAssertEqual(decoder.ingest(Data([0x09])), [])
    }

    func testEntralpiZeroIsAReading() {
        var decoder = EntralpiCodec.Decoder()
        XCTAssertEqual(decoder.ingest(Data([0x00, 0x00])), [GaugeReading(kg: 0.0)])
    }

    /// The codec must never invent a timestamp: this device stamps nothing, so the
    /// client applies `SyntheticSampleClock` and the runner clamps per-sample credit.
    func testEntralpiReadingsCarryNoDeviceClock() {
        var decoder = EntralpiCodec.Decoder()
        XCTAssertNil(decoder.ingest(Data([0x09, 0xC4])).first?.deviceMicros)
    }

    /// Battery comes from the standard Battery Service the client reads at connect,
    /// not from the stream — only Climbro carries it in-band.
    func testEntralpiDecoderPublishesNoInStreamBattery() {
        var decoder = EntralpiCodec.Decoder()
        _ = decoder.ingest(Data([0x09, 0xC4]))
        XCTAssertNil(decoder.batteryFraction)
    }

    /// Subscribing IS the start: the reference writes nothing to this device
    /// anywhere, and it has no hardware tare.
    func testEntralpiProfileNeedsNoWritesAtAll() {
        XCTAssertEqual(EntralpiCodec.profile.serviceUUID, "0000FFF0-0000-1000-8000-00805F9B34FB")
        XCTAssertEqual(EntralpiCodec.profile.notifyCharacteristicUUID,
                       "0000FFF4-0000-1000-8000-00805F9B34FB")
        XCTAssertNil(EntralpiCodec.profile.writeCharacteristicUUID)
        XCTAssertEqual(EntralpiCodec.profile.streamStartPayloads, [])
        XCTAssertNil(EntralpiCodec.profile.streamStopPayload)
        XCTAssertNil(EntralpiCodec.profile.tareCharacteristicUUID)
        XCTAssertNil(EntralpiCodec.profile.tarePayload)
    }

    /// **The reference subscribes to every characteristic it marks "rx" and never had to
    /// choose**: 0000FFF4 under 0000FFF0 and 0000FFF1 under 0000181D. Picking one would be
    /// a coin flip the source cannot settle, and picking wrong is a device that connects
    /// and never delivers a byte — so the profile carries both and the client subscribes
    /// to both. The same decoder parses whichever one speaks.
    func testEntralpiProfileCarriesBothCharacteristicsTheReferenceSubscribesTo() {
        XCTAssertEqual(EntralpiCodec.profile.alternateNotifyCharacteristicUUIDs,
                       ["0000FFF1-0000-1000-8000-00805F9B34FB"])
        XCTAssertNotEqual(EntralpiCodec.profile.alternateNotifyCharacteristicUUIDs.first,
                          EntralpiCodec.profile.notifyCharacteristicUUID,
                          "an alternate that repeats the primary subscribes twice to one handle")
    }

    /// The plausibility window the CTS500 and the Force Board both carry, for the reason
    /// this protocol makes plainest: no checksum, no framing, and two arbitrary bytes are
    /// enough to set a session peak and a recorded max.
    ///
    /// **Nothing the documented field can express reaches it** — 0xFFFF is 655.35 kg — so
    /// the highest possible reading still decodes. That is deliberate: the ceiling is the
    /// backstop for a scaling surprise (a widened read, a different divisor), not a filter
    /// that fires on today's bytes.
    func testEntralpiCarriesACeilingAboveEverythingTheFieldCanExpress() {
        var decoder = EntralpiCodec.Decoder()
        let readings = decoder.ingest(Data([0xFF, 0xFF]))
        XCTAssertEqual(readings.first?.kg ?? 0, 655.35, accuracy: 0.0001)

        XCTAssertEqual(EntralpiCodec.maxPlausibleKilograms, 907, "2000 lb, as the Force Board")
        XCTAssertGreaterThan(EntralpiCodec.maxPlausibleKilograms, 655.35)
    }

    // MARK: - PB-700BT (rotation, not force)

    private func pb700Notification(period: UInt32, sampleIndex: UInt32 = 0) -> Data {
        func bigEndian(_ value: UInt32) -> [UInt8] {
            [UInt8(truncatingIfNeeded: value >> 24),
             UInt8(truncatingIfNeeded: value >> 16),
             UInt8(truncatingIfNeeded: value >> 8),
             UInt8(truncatingIfNeeded: value)]
        }
        return Data(bigEndian(period) + bigEndian(sampleIndex))
    }

    /// period 10000 ticks → 60 × 666666 / 10000 = 3999.996 → 4000 RPM.
    func testPB700BTPeriodDecodesAsBigEndianRPM() {
        let frame = Data([0x00, 0x00, 0x27, 0x10,   // period 10000, big-endian
                          0x00, 0x00, 0x00, 0x00])
        XCTAssertEqual(PB700BTCodec.revolutionsPerMinute(from: frame), 4000)
    }

    /// The same four bytes reversed are 0x10270000 = 271,581,184 ticks → 0.15 RPM,
    /// which the plausibility band refuses. Byte order is load-bearing even for a
    /// value nothing consumes.
    func testPB700BTReversedPeriodBytesFallOutsideThePlausibleBand() {
        let reversed = Data([0x10, 0x27, 0x00, 0x00,
                             0x00, 0x00, 0x00, 0x00])
        XCTAssertNil(PB700BTCodec.revolutionsPerMinute(from: reversed))
    }

    /// Refused before the division, not after: `60 * (x / 0)` is `.infinity`, and
    /// leaning on the band to catch that leaves a divide-by-zero one edit away from
    /// being the answer.
    func testPB700BTRejectsAZeroPeriod() {
        XCTAssertNil(PB700BTCodec.revolutionsPerMinute(from: pb700Notification(period: 0)))
    }

    /// The reference's own 800…15000 window, asserted at both edges.
    ///   period 49999 → 39,999,960 / 49,999 =   800.02 RPM  (inside)
    ///   period 50000 → 39,999,960 / 50,000 =   799.99 RPM  (outside)
    ///   period  2667 → 39,999,960 /  2,667 = 14998.11 RPM  (inside)
    ///   period  2666 → 39,999,960 /  2,666 = 15003.74 RPM  (outside)
    func testPB700BTRejectsRotationSpeedsOutsideThePlausibleBand() {
        XCTAssertEqual(PB700BTCodec.revolutionsPerMinute(from: pb700Notification(period: 49_999)), 800)
        XCTAssertNil(PB700BTCodec.revolutionsPerMinute(from: pb700Notification(period: 50_000)))
        XCTAssertEqual(PB700BTCodec.revolutionsPerMinute(from: pb700Notification(period: 2_667)), 14_998)
        XCTAssertNil(PB700BTCodec.revolutionsPerMinute(from: pb700Notification(period: 2_666)))
    }

    /// Eight bytes are required even though the period occupies four: the reference
    /// reads offset 4 unconditionally, so in a browser a shorter read throws out of
    /// the handler and yields nothing. A four-byte frame carrying a perfectly good
    /// period is still refused, deliberately.
    func testPB700BTTruncatedNotificationDecodesToNothing() {
        let whole = pb700Notification(period: 10_000, sampleIndex: 7)
        for length in 0..<8 {
            XCTAssertNil(PB700BTCodec.revolutionsPerMinute(from: whole.prefix(length)),
                         "a \(length)-byte notification is not a whole frame")
            XCTAssertNil(PB700BTCodec.sampleIndex(from: whole.prefix(length)))
        }
    }

    /// 0x01020304 = 16,909,060, big-endian at offset 4.
    func testPB700BTSampleIndexIsBigEndianAtOffsetFour() {
        let frame = Data([0x00, 0x00, 0x27, 0x10,
                          0x01, 0x02, 0x03, 0x04])
        XCTAssertEqual(PB700BTCodec.sampleIndex(from: frame), 16_909_060)
    }

    /// The headline fact about this device: it is a gyroscopic hand exerciser, so its
    /// stream carries revolutions per minute and there is no force channel to port.
    /// The decoder understands every frame and yields no readings — four-figure RPM
    /// arriving in a kilogram channel would arm every rep instantly and write a
    /// five-figure "max" that then sets the percentage targets for that grip.
    func testPB700BTDecodesEveryFrameAndYieldsNoForceReadings() {
        var decoder = PB700BTCodec.Decoder()
        XCTAssertNil(decoder.lastRPM)

        XCTAssertEqual(decoder.ingest(pb700Notification(period: 10_000)), [])
        XCTAssertEqual(decoder.lastRPM, 4000, "the frame is decoded, just not reported as load")

        // A refused frame leaves the last good reading alone rather than blanking it.
        XCTAssertEqual(decoder.ingest(pb700Notification(period: 0)), [])
        XCTAssertEqual(decoder.lastRPM, 4000)
    }

    func testPB700BTProfileUsesTheOnlyCharacteristicTheReferenceSubscribesTo() {
        XCTAssertEqual(PB700BTCodec.profile.serviceUUID, "0000FFF0-0000-1000-8000-00805F9B34FB")
        XCTAssertEqual(PB700BTCodec.profile.notifyCharacteristicUUID,
                       "0000FFF4-0000-1000-8000-00805F9B34FB")
        XCTAssertEqual(PB700BTCodec.profile.streamStartPayloads, [],
                       "the reference writes nothing to this device")
        XCTAssertNil(PB700BTCodec.profile.tareCharacteristicUUID)
    }

    // MARK: - Registry wiring

    /// One codec file plus one row in each switch — this is the test that says the
    /// rows point at the right codec, so a copy-paste in the registry cannot hand the
    /// Entralpi's bytes to another device's decoder.
    func testRegistryWiresEachOfTheseKindsToItsOwnCodec() {
        XCTAssertEqual(GaugeKind.entralpi.gatt, EntralpiCodec.profile)
        XCTAssertEqual(GaugeKind.pb700bt.gatt, PB700BTCodec.profile)

        guard let entralpi = GaugeKind.entralpi.makeFrameDecoder(),
              let pb700bt = GaugeKind.pb700bt.makeFrameDecoder() else {
            return XCTFail("both kinds are GATT-connected and must build a decoder")
        }
        XCTAssertTrue(entralpi is EntralpiCodec.Decoder)
        XCTAssertTrue(pb700bt is PB700BTCodec.Decoder)
    }

    /// The WH-C06 has nothing to connect to and nothing to reassemble: weight arrives
    /// in advertisements, which is a different client, not a different profile.
    func testTheBroadcastScaleHasNeitherProfileNorDecoder() {
        XCTAssertNil(GaugeKind.whc06.gatt)
        XCTAssertNil(GaugeKind.whc06.makeFrameDecoder())
        XCTAssertTrue(GaugeKind.whc06.capabilities.isBroadcast)
        XCTAssertFalse(GaugeKind.whc06.capabilities.sustainsBackgroundStreaming,
                       "duplicate advertisements coalesce in the background")
    }

    /// Both connected devices here declare the standard Battery Service (0x180F /
    /// 0x2A19) in the reference, which is what `hasStandardBattery` promises the
    /// client it can read at connect.
    func testBothConnectedKindsClaimTheStandardBatteryServiceTheReferenceDeclares() {
        XCTAssertTrue(GaugeKind.entralpi.capabilities.hasStandardBattery)
        XCTAssertTrue(GaugeKind.pb700bt.capabilities.hasStandardBattery)
    }

    /// Neither has a tare of its own — the reference sends no tare command to either,
    /// so the Tare button has to mean a captured app-side baseline.
    func testNeitherSimpleGaugeClaimsAHardwareTare() {
        XCTAssertFalse(GaugeKind.entralpi.capabilities.hasHardwareTare)
        XCTAssertFalse(GaugeKind.pb700bt.capabilities.hasHardwareTare)
        XCTAssertFalse(GaugeKind.whc06.capabilities.hasHardwareTare)
    }
}
