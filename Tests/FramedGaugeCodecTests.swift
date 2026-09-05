// SPDX-License-Identifier: MPL-2.0
// Original contributions Copyright 2026 Nuri Bruner.

import XCTest
@testable import Doigt

/// Byte-level fixtures for the four FRAMED ported protocols: Climbro, Griptonite
/// Motherboard, PitchSix Force Board and Jlyscales CTS500.
///
/// Every fixture is hand-computed from hangtime-grip-connect's device models (the
/// arithmetic is spelled out in the comments), and the primary fixture for each
/// device is the literal byte string that reference's OWN tests use — a decoder
/// checked only against a matching encoder agrees with itself about the wrong byte
/// order, and none of these devices is verified on hardware here, so the reference
/// is the only witness we have.
///
/// The dangerous class is the same as the Progressor's: an endianness or scaling
/// mistake produces a plausible wrong number (a 20 kg pull reading as 5 kg or
/// 2.7 million) rather than a crash.
final class FramedGaugeCodecTests: XCTestCase {

    // MARK: - Climbro

    /// The reference's own fixture: `[0xF0, 112, 0xF5, 10, 0xF6, 20]` — battery
    /// marker, an empty-battery byte, sensor marker, 10 kg, the 36 kg reserved word,
    /// 20 kg. Its test asserts battery "0" and currents 10, 36, 20.
    func testClimbroDecodesTheReferenceMarkerFixture() {
        var decoder = ClimbroCodec.Decoder()
        let readings = decoder.ingest(Data([0xF0, 112, 0xF5, 10, 0xF6, 20]))

        XCTAssertEqual(readings, [
            GaugeReading(kg: 10),
            GaugeReading(kg: 36),
            GaugeReading(kg: 20),
        ])
        // 112 is `minBatteryDisc`, so exactly empty.
        XCTAssertEqual(decoder.batteryFraction, 0)
    }

    /// The battery coefficient is `100 / (230 - 112)`. Halfway is byte 171:
    /// (171 − 112) / 118 = 0.5.
    func testClimbroBatteryUsesTheReferenceDischargeSpan() throws {
        var decoder = ClimbroCodec.Decoder()
        _ = decoder.ingest(Data([0xF0, 171]))
        XCTAssertEqual(try XCTUnwrap(decoder.batteryFraction), 0.5, accuracy: 0.0001)

        _ = decoder.ingest(Data([230]))
        XCTAssertEqual(try XCTUnwrap(decoder.batteryFraction), 1.0, accuracy: 0.0001)
    }

    /// The reference reports −94 % for a battery byte of 0 (it never clamps). A
    /// fraction is drawn as a ring, so ours saturates at both ends instead.
    func testClimbroBatteryFractionClampsWhereTheReferenceGoesNegative() {
        var decoder = ClimbroCodec.Decoder()
        _ = decoder.ingest(Data([0xF0, 0]))
        XCTAssertEqual(decoder.batteryFraction, 0)

        _ = decoder.ingest(Data([255]))
        XCTAssertEqual(decoder.batteryFraction, 1)
    }

    /// There is no framing on Microchip Transparent UART, so the channel a byte
    /// belongs to is state that MUST survive the notification boundary — the radio
    /// splits the stream wherever it likes.
    func testClimbroChannelSurvivesTheNotificationBoundary() {
        var decoder = ClimbroCodec.Decoder()
        XCTAssertEqual(decoder.ingest(Data([0xF5])), [])
        XCTAssertEqual(decoder.ingest(Data([12, 13])), [
            GaugeReading(kg: 12),
            GaugeReading(kg: 13),
        ])
    }

    /// Bytes arriving before any marker are dropped, not guessed at: the reference
    /// initialises its flag to 0, which is neither channel.
    func testClimbroDropsBytesArrivingBeforeAnyMarker() {
        var decoder = ClimbroCodec.Decoder()
        XCTAssertEqual(decoder.ingest(Data([5, 6, 0xF5, 7])), [GaugeReading(kg: 7)])
        XCTAssertNil(decoder.batteryFraction)
    }

    func testClimbroBatteryBytesAreNeverForceReadings() throws {
        var decoder = ClimbroCodec.Decoder()
        XCTAssertEqual(decoder.ingest(Data([0xF0, 200, 150])), [])
        // (150 − 112) / 118 — the LAST battery byte wins.
        XCTAssertEqual(try XCTUnwrap(decoder.batteryFraction), 38.0 / 118.0, accuracy: 0.0001)
    }

    func testClimbroEmptyNotificationDecodesToNothing() {
        var decoder = ClimbroCodec.Decoder()
        XCTAssertEqual(decoder.ingest(Data()), [])
    }

    /// Subscribing is the whole start sequence — the reference defines no commands
    /// for this device at all. A payload appearing here later would be a change in
    /// what we believe about the protocol, not a tidy-up.
    func testClimbroProfileStartsBySubscribingAlone() {
        XCTAssertTrue(ClimbroCodec.profile.streamStartPayloads.isEmpty)
        XCTAssertNil(ClimbroCodec.profile.streamStopPayload)
        XCTAssertNil(ClimbroCodec.profile.tareCharacteristicUUID)
        XCTAssertEqual(ClimbroCodec.profile.notifyCharacteristicUUID,
                       "49535343-1E4D-4BD9-BA61-23C647249616")
        XCTAssertEqual(ClimbroCodec.profile.writeCharacteristicUUID,
                       "49535343-8841-43F4-A8D4-ECBE34729BB3")
    }

    // MARK: - Force Board

    /// The reference's own fixture, `forceBoardPacket([1000, 1200])`, asserting two
    /// readings of 1000 and 1200 POUNDS.
    ///
    /// Header 00 02 = two samples, big-endian. 1000 = 0·32768 + 3·256 + 232, so the
    /// sample is 00 03 E8; 1200 = 0·32768 + 4·256 + 176 → 00 04 B0. In kilograms:
    /// 1000 × 0.45359237 = 453.59237 and 1200 × 0.45359237 = 544.310844.
    func testForceBoardDecodesTheReferenceTwoSamplePacketInKilograms() {
        var decoder = ForceBoardCodec.Decoder()
        let readings = decoder.ingest(Data([0x00, 0x02,
                                            0x00, 0x03, 0xE8,
                                            0x00, 0x04, 0xB0]))

        XCTAssertEqual(readings.count, 2)
        XCTAssertEqual(readings[0].kg, 453.59237, accuracy: 0.00001)
        XCTAssertEqual(readings[1].kg, 544.310844, accuracy: 0.00001)
        // A synthetic stamp is the client's job; codecs stay pure.
        XCTAssertNil(readings[0].deviceMicros)
    }

    /// The top byte is worth 2^15, NOT 2^16. 40000 lbs packs as 01 1C 40
    /// (1·32768 + 28·256 + 64); a 24-bit read of the same bytes gives 0x011C40 =
    /// 72768, i.e. 80 % too heavy, with no crash to point at it.
    func testForceBoardSampleHighByteIsWorthThirtyTwoThousandSevenHundredSixtyEight() {
        XCTAssertEqual(ForceBoardCodec.pounds(fromSample: (0x01, 0x1C, 0x40)), 40000)
        XCTAssertEqual(ForceBoardCodec.pounds(fromSample: (0x00, 0x03, 0xE8)), 1000)
        XCTAssertEqual(ForceBoardCodec.pounds(fromSample: (0x00, 0x00, 0x2A)), 42)
    }

    /// A count the packet cannot honour must stop the walk, not read past the end.
    func testForceBoardPacketClaimingMoreSamplesThanItCarriesStopsCleanly() {
        var decoder = ForceBoardCodec.Decoder()
        let readings = decoder.ingest(Data([0x00, 0x03,
                                            0x00, 0x00, 0x0A,
                                            0x00, 0x00, 0x14,
                                            0x00, 0x00]))  // third sample cut short

        XCTAssertEqual(readings.count, 2)
        XCTAssertEqual(readings[0].kg, 10 * ForceBoardCodec.poundsToKilograms, accuracy: 0.00001)
        XCTAssertEqual(readings[1].kg, 20 * ForceBoardCodec.poundsToKilograms, accuracy: 0.00001)
    }

    func testForceBoardShortAndEmptyPacketsDecodeToNothing() {
        var decoder = ForceBoardCodec.Decoder()
        XCTAssertEqual(decoder.ingest(Data()), [])
        XCTAssertEqual(decoder.ingest(Data([0x00])), [])
        XCTAssertEqual(decoder.ingest(Data([0x00, 0x01])), [])          // header only
        XCTAssertEqual(decoder.ingest(Data([0x00, 0x01, 0x00, 0x00])), []) // half a sample
        XCTAssertEqual(decoder.ingest(Data([0x00, 0x00, 0x00, 0x00, 0x00])), []) // zero samples
    }

    /// The one device whose write characteristic is NOT in the service it streams from —
    /// wiring the generic client depends on and the registry cannot express.
    func testForceBoardProfileNamesBothServices() {
        XCTAssertEqual(ForceBoardCodec.profile.serviceUUID,
                       "9A88D67F-8DF2-4AFE-9E0D-C2BBBE773DD0")
        XCTAssertEqual(ForceBoardCodec.profile.notifyCharacteristicUUID,
                       "9A88D682-8DF2-4AFE-9E0D-C2BBBE773DD0")
        XCTAssertEqual(ForceBoardCodec.profile.writeCharacteristicUUID,
                       "467A8517-6E39-11EB-9439-0242AC130002")
        XCTAssertEqual(ForceBoardCodec.deviceModeServiceUUID,
                       "467A8516-6E39-11EB-9439-0242AC130002")
        XCTAssertEqual(ForceBoardCodec.profile.streamStartPayloads, [Data([0x04])])
        XCTAssertEqual(ForceBoardCodec.profile.streamStopPayload, Data([0x07]))
    }

    /// **The board's tare bytes are recorded and deliberately NOT in the profile.**
    ///
    /// In the reference that write lives only in `tareByCharacteristic`, which its own tare
    /// path never calls — ForceBoard does not override `tare()`, so its shipped behaviour is
    /// the base class's software tare. Naming the characteristic here would REPLACE the
    /// working app-side tare with an unexercised write (`GattGaugeClient` treats the two as
    /// mutually exclusive), and if those bytes are wrong on real firmware Tare becomes a
    /// silent no-op on a device this project has never held.
    func testForceBoardKeepsItsUnexercisedDeviceTareOutOfTheProfile() {
        XCTAssertNil(ForceBoardCodec.profile.tareCharacteristicUUID)
        XCTAssertNil(ForceBoardCodec.profile.tarePayload)
        XCTAssertFalse(GaugeKind.forceboard.capabilities.hasHardwareTare,
                       "the software tare is what ships until hardware confirms the write")

        // Kept as constants so a hardware session has the bytes to try.
        XCTAssertEqual(ForceBoardCodec.tareCharacteristicUUID,
                       "9A88D683-8DF2-4AFE-9E0D-C2BBBE773DD0")
        XCTAssertEqual(ForceBoardCodec.tarePayload, Data([0x01]))
    }

    /// **A generous ceiling rather than none.** This frame has no checksum, no header magic
    /// and no reassembly buffer, so a continuation packet is decoded as a fresh one and a
    /// 3-byte sample can express 8,421,375 lb — 3.8 million kilograms. One of those sets
    /// the store's peak, rescales the trace for the session, and inside a max attempt
    /// becomes the recorded max that sets every percentage target for that grip.
    ///
    /// 2000 lb is the bound: it survives a 10× scaling surprise (the whole-pounds reading
    /// has no second source) while rejecting everything a bad frame produces. Rejection is
    /// PER SAMPLE, so the good readings either side of one still arrive.
    func testForceBoardDropsSamplesNoBoardCouldProduceAndKeepsTheirNeighbours() {
        var decoder = ForceBoardCodec.Decoder()
        // 42 lb, then the largest a 3-byte sample can express, then 42 lb again.
        let readings = decoder.ingest(Data([0x00, 0x03,
                                            0x00, 0x00, 0x2A,
                                            0xFF, 0xFF, 0xFF,
                                            0x00, 0x00, 0x2A]))

        XCTAssertEqual(readings.count, 2, "the absurd sample is dropped, the pulls are not")
        for reading in readings {
            XCTAssertEqual(reading.kg, 42 * ForceBoardCodec.poundsToKilograms, accuracy: 0.00001)
        }

        // The boundary itself: 1999 lb = 906.73 kg is inside, 2000 lb = 907.18 is not.
        XCTAssertEqual(ForceBoardCodec.maxPlausibleKilograms, 907)
        var edge = ForceBoardCodec.Decoder()
        // 1999 = 0·32768 + 7·256 + 207 → 00 07 CF; 2000 → 00 07 D0.
        XCTAssertEqual(edge.ingest(Data([0x00, 0x01, 0x00, 0x07, 0xCF])).count, 1)
        XCTAssertEqual(edge.ingest(Data([0x00, 0x01, 0x00, 0x07, 0xD0])), [])
    }

    // MARK: - Motherboard

    /// Calibration rows arrive as `slot,index,force,raw`. These are the rows the
    /// reference's own Motherboard tests install — an identity map from 0…100 raw
    /// counts to 0…100 kg — expressed as the lines a device would actually send.
    private func motherboardCalibration(
        rows: [(force: Int, raw: Int)] = [(force: 0, raw: 0), (force: 100, raw: 100)],
        slots: Int = MotherboardCodec.calibrationSlotCount
    ) -> Data {
        var text = ""
        for slot in 0..<slots {
            for (index, row) in rows.enumerated() {
                text += "\(slot),\(index),\(row.force),\(row.raw)\n"
            }
        }
        return Data(text.utf8)
    }

    /// One hex packet line, built the way the reference's `motherboardPacket` helper
    /// builds it: uint16 LE index, uint16 LE battery, three 24-bit LE samples,
    /// three unused bytes, hex-encoded, LF-terminated.
    private func motherboardPacket(sampleIndex: UInt16 = 1,
                                   batteryRaw: UInt16 = 0x012C,
                                   samples: [Int],
                                   lineEnding: String = "\n") -> Data {
        var bytes = [UInt8](repeating: 0, count: 16)
        bytes[0] = UInt8(sampleIndex & 0xFF)
        bytes[1] = UInt8(sampleIndex >> 8)
        bytes[2] = UInt8(batteryRaw & 0xFF)
        bytes[3] = UInt8(batteryRaw >> 8)
        for (index, sample) in samples.enumerated() {
            var raw = sample
            if raw < 0 { raw += 0x1000000 }
            let start = 4 + 3 * index
            bytes[start] = UInt8(raw & 0xFF)
            bytes[start + 1] = UInt8((raw >> 8) & 0xFF)
            bytes[start + 2] = UInt8((raw >> 16) & 0xFF)
        }
        let hex = bytes.map { String(format: "%02X", Int($0)) }.joined()
        return Data((hex + lineEnding).utf8)
    }

    /// The reference's literal fixture line, with its own calibration table.
    ///
    /// `01002C010A0000ECFFFFE2FFFF000000` is index 1, battery 0x012C = 300, then
    /// samples 0x00000A = 10, 0xFFFFEC → −20 and 0xFFFFE2 → −30. Through the
    /// identity table that is 10, −20, −30; centre and right inverted give 10, 20,
    /// 30; the total is 60 — exactly what the reference asserts, distribution and
    /// all.
    func testMotherboardDecodesTheReferencePacketToItsSummedTotal() throws {
        var decoder = MotherboardCodec.Decoder()
        XCTAssertEqual(decoder.ingest(motherboardCalibration()), [])
        XCTAssertTrue(decoder.hasCalibration)

        let readings = decoder.ingest(Data("01002C010A0000ECFFFFE2FFFF000000\n".utf8))

        XCTAssertEqual(readings.count, 1)
        XCTAssertEqual(readings[0].kg, 60, accuracy: 0.0001)
        XCTAssertEqual(decoder.latestSampleIndex, 1)
        XCTAssertEqual(decoder.latestBatteryRaw, 300)
        // The battery field is raw and undocumented; this device's percentage comes
        // from the standard 0x180F service the client reads instead.
        XCTAssertNil(decoder.batteryFraction)
    }

    /// LEFT IS NOT INVERTED. A loop that negated all three zones would pass every
    /// symmetric fixture and get the sign of a real pull backwards.
    func testMotherboardInvertsCentreAndRightOnly() {
        var decoder = MotherboardCodec.Decoder()
        _ = decoder.ingest(motherboardCalibration())

        let readings = decoder.ingest(motherboardPacket(samples: [10, 0, 0]))
        XCTAssertEqual(readings.count, 1)
        XCTAssertEqual(readings[0].kg, 10, accuracy: 0.0001)

        let inverted = decoder.ingest(motherboardPacket(samples: [0, -10, -10]))
        XCTAssertEqual(inverted.count, 1)
        XCTAssertEqual(inverted[0].kg, 20, accuracy: 0.0001)
    }

    /// A line arrives in as many notifications as the radio feels like using, and
    /// nothing may be emitted before its LF.
    func testMotherboardReassemblesOneLineAcrossThreeNotifications() {
        var decoder = MotherboardCodec.Decoder()
        _ = decoder.ingest(motherboardCalibration())

        let line = [UInt8](motherboardPacket(samples: [10, -20, -30]))
        XCTAssertEqual(decoder.ingest(Data(line[0..<7])), [])
        XCTAssertEqual(decoder.ingest(Data(line[7..<20])), [])

        let readings = decoder.ingest(Data(line[20...]))
        XCTAssertEqual(readings.count, 1)
        XCTAssertEqual(readings[0].kg, 60, accuracy: 0.0001)
    }

    /// CRLF must not reach the hex test — a trailing CR fails it on every packet.
    func testMotherboardStripsCarriageReturns() {
        var decoder = MotherboardCodec.Decoder()
        _ = decoder.ingest(motherboardCalibration())

        let readings = decoder.ingest(motherboardPacket(samples: [10, -20, -30],
                                                        lineEnding: "\r\n"))
        XCTAssertEqual(readings.count, 1)
        XCTAssertEqual(readings[0].kg, 60, accuracy: 0.0001)
    }

    /// No calibration, no reading. The start sequence writes "C" before "S30" and the
    /// client waits `startPayloadDelaySeconds` between them, but a packet can still beat
    /// the table home — it is dropped rather than reported as a raw ADC count.
    func testMotherboardEmitsNothingUntilCalibrationArrives() {
        var decoder = MotherboardCodec.Decoder()
        XCTAssertFalse(decoder.hasCalibration)
        XCTAssertEqual(decoder.ingest(motherboardPacket(samples: [10, -20, -30])), [])

        _ = decoder.ingest(motherboardCalibration())
        XCTAssertEqual(decoder.ingest(motherboardPacket(samples: [10, -20, -30])).count, 1)
    }

    /// A sample above the table's last raw count has no mapping. The reference
    /// returns 0 for it; 0 kg mid-pull would tell the runner the climber let go at
    /// the exact moment they pulled hardest, so the whole packet is dropped instead.
    func testMotherboardDropsAPacketWithASampleOffTheTopOfTheTable() {
        var decoder = MotherboardCodec.Decoder()
        _ = decoder.ingest(motherboardCalibration())

        XCTAssertEqual(decoder.ingest(motherboardPacket(samples: [150, 0, 0])), [])
    }

    /// A zero-width calibration segment divides by zero. NaN kilograms would poison
    /// every peak and average downstream, so no reading is emitted at all.
    func testMotherboardDegenerateCalibrationSegmentYieldsNoReadingRatherThanNaN() {
        var decoder = MotherboardCodec.Decoder()
        // Both rows report raw count 0, so there is no interval to interpolate over.
        _ = decoder.ingest(motherboardCalibration(rows: [(force: 0, raw: 0),
                                                        (force: 50, raw: 0)]))

        XCTAssertEqual(decoder.ingest(motherboardPacket(samples: [10, 0, 0])), [])
    }

    func testMotherboardIgnoresLinesThatAreNotPacketsOrCalibrationRows() {
        var decoder = MotherboardCodec.Decoder()
        _ = decoder.ingest(motherboardCalibration())

        // The serial reply, a short hex line, an odd-length hex line, a row for a
        // slot that does not exist: all ignored, none of them fatal.
        XCTAssertEqual(decoder.ingest(Data("1234-5678-ABCD\n".utf8)), [])
        XCTAssertEqual(decoder.ingest(Data("01002C010A00\n".utf8)), [])
        XCTAssertEqual(decoder.ingest(Data("01002C010A0000ECFFFFE2FFFF00000\n".utf8)), [])
        XCTAssertEqual(decoder.ingest(Data("9,1,100,100\n".utf8)), [])
        XCTAssertEqual(decoder.ingest(Data("not,four,numbers,here\n".utf8)), [])

        // …and the next real packet still decodes.
        XCTAssertEqual(decoder.ingest(motherboardPacket(samples: [10, -20, -30])).count, 1)
    }

    /// The slot is an INDEX parsed out of untrusted text. `Double("nan")`, `Double("inf")`
    /// and `Double("1e30")` all parse, and `Int(_:)` on any of them TRAPS — so a device
    /// this project has never held could have crashed the app with one malformed line.
    /// Such a row is dropped, exactly as the Kotlin twin drops it, whether the table is
    /// still empty or already in force: it neither lands in a slot nor clears one.
    func testMotherboardDropsACalibrationRowWhoseSlotIsNotAnExactInRangeInteger() {
        var decoder = MotherboardCodec.Decoder()
        let badRows = ["nan,0,0,0\n", "1e30,0,0,0\n", "inf,0,0,0\n", "-inf,0,0,0\n",
                       "-1,0,0,0\n", "0.5,0,0,0\n", "4,0,0,0\n"]
        let empty = Array(repeating: 0, count: MotherboardCodec.calibrationSlotCount)

        for line in badRows {
            XCTAssertEqual(decoder.ingest(Data(line.utf8)), [], line)
            XCTAssertEqual(decoder.calibrationRowCounts, empty, "\(line) must not land in any slot")
        }
        XCTAssertFalse(decoder.hasCalibration)

        // With a table in force, a bad row's index 0 must not clear a slot either.
        _ = decoder.ingest(motherboardCalibration())
        let full = decoder.calibrationRowCounts
        for line in badRows {
            XCTAssertEqual(decoder.ingest(Data(line.utf8)), [], line)
            XCTAssertEqual(decoder.calibrationRowCounts, full, "\(line) must not clear or extend a slot")
        }
        XCTAssertTrue(decoder.hasCalibration)
        XCTAssertEqual(decoder.ingest(motherboardPacket(samples: [10, -20, -30])).first?.kg ?? 0,
                       60, accuracy: 0.0001)
    }

    /// A peer that never sends an LF would grow the buffer for the whole session.
    /// Past the cap the buffer is dropped, and the next whole line decodes.
    func testMotherboardDiscardsAnUnterminatedFloodAndKeepsDecodingAfterwards() {
        var decoder = MotherboardCodec.Decoder()
        _ = decoder.ingest(motherboardCalibration())

        let flood = Data(repeating: 0x41, count: MotherboardCodec.maxBufferedBytes + 64)
        XCTAssertEqual(decoder.ingest(flood), [])

        let readings = decoder.ingest(motherboardPacket(samples: [10, -20, -30]))
        XCTAssertEqual(readings.count, 1)
        XCTAssertEqual(readings[0].kg, 60, accuracy: 0.0001)
    }

    /// **A dump REPLACES its slot.** "C" rides the start payloads, which the app re-sends
    /// on every re-kick — the silence watchdog, a tare recovery, coming back to the
    /// foreground — while the decoder lives for the whole link. Appending grew the table
    /// without bound and turned it into a non-monotonic concatenation that
    /// `applyCalibration` walks linearly three times per packet, so a session got steadily
    /// slower the longer it ran.
    func testMotherboardASecondCalibrationDumpReplacesTheTableRatherThanStackingOnIt() {
        var decoder = MotherboardCodec.Decoder()
        _ = decoder.ingest(motherboardCalibration())
        let afterFirstDump = decoder.calibrationRowCounts
        let firstReading = decoder.ingest(motherboardPacket(samples: [10, -20, -30]))

        _ = decoder.ingest(motherboardCalibration())

        XCTAssertEqual(decoder.calibrationRowCounts, afterFirstDump,
                       "the same dump twice is the same table, not two copies of it")
        XCTAssertEqual(decoder.calibrationRowCounts, Array(repeating: 2,
                       count: MotherboardCodec.calibrationSlotCount))
        XCTAssertTrue(decoder.hasCalibration)

        let secondReading = decoder.ingest(motherboardPacket(samples: [10, -20, -30]))
        XCTAssertEqual(secondReading.count, firstReading.count)
        XCTAssertEqual(secondReading.first?.kg ?? 0, firstReading.first?.kg ?? -1,
                       accuracy: 0.0001)
    }

    /// A dump that has genuinely changed still lands: the slot is cleared by the arrival of
    /// its row 0, so the newest table is the one in force.
    func testMotherboardANewDumpSupersedesTheOldCalibration() {
        var decoder = MotherboardCodec.Decoder()
        _ = decoder.ingest(motherboardCalibration())
        XCTAssertEqual(decoder.ingest(motherboardPacket(samples: [10, 0, 0])).first?.kg ?? 0,
                       10, accuracy: 0.0001)

        // Half the force for the same raw count: 0…100 counts now map to 0…50 kg.
        _ = decoder.ingest(motherboardCalibration(rows: [(force: 0, raw: 0),
                                                         (force: 50, raw: 100)]))

        XCTAssertEqual(decoder.calibrationRowCounts.first, 2)
        XCTAssertEqual(decoder.ingest(motherboardPacket(samples: [10, 0, 0])).first?.kg ?? 0,
                       5, accuracy: 0.0001)
    }

    /// "C" then "S30": the calibration request has to precede the stream, or the
    /// packets that arrive first mean nothing — and the reference's own 2500 ms wait
    /// between the two is carried by the profile rather than lost in a two-write burst.
    func testMotherboardProfileAsksForCalibrationBeforeStarting() {
        XCTAssertEqual(MotherboardCodec.profile.streamStartPayloads,
                       [Data("C".utf8), Data("S30".utf8)])
        XCTAssertEqual(MotherboardCodec.profile.startPayloadDelaySeconds, 2.5,
                       "the reference's own wait between the two writes")
        XCTAssertEqual(MotherboardCodec.profile.streamStopPayload, Data("#".utf8))
        XCTAssertEqual(MotherboardCodec.profile.serviceUUID,
                       "6E400001-B5A3-F393-E0A9-E50E24DCCA9E")
        XCTAssertEqual(MotherboardCodec.profile.notifyCharacteristicUUID,
                       "6E400003-B5A3-F393-E0A9-E50E24DCCA9E")
        XCTAssertEqual(MotherboardCodec.profile.writeCharacteristicUUID,
                       "6E400002-B5A3-F393-E0A9-E50E24DCCA9E")
    }

    // MARK: - CTS500

    /// `buildCommand(opcode)` is `05 <opcode> 00 00 00 <sum>`, the sum truncated to
    /// a byte. Start 0xAA: 0x05 + 0xAA = 0xAF. Stop 0xAB: 0xB0. Tare 0xA6: 0xAB.
    /// Sampling rate 0xC1 with payload 00 00 02: 0x05 + 0xC1 + 0x02 = 0xC8.
    func testCTS500CommandFramesMatchTheReferenceChecksums() {
        XCTAssertEqual(CTS500Codec.command(0xAA), Data([0x05, 0xAA, 0x00, 0x00, 0x00, 0xAF]))
        XCTAssertEqual(CTS500Codec.command(0xAB), Data([0x05, 0xAB, 0x00, 0x00, 0x00, 0xB0]))
        XCTAssertEqual(CTS500Codec.command(0xA6), Data([0x05, 0xA6, 0x00, 0x00, 0x00, 0xAB]))
        XCTAssertEqual(CTS500Codec.command(0xC1, payload: (0x00, 0x00, 0x02)),
                       Data([0x05, 0xC1, 0x00, 0x00, 0x02, 0xC8]))
    }

    /// The checksum wraps rather than traps — it is `& 0xFF` in the reference and
    /// `&+` here. 0xFF + 0xFF = 0x1FE → 0xFE.
    func testCTS500ChecksumWrapsInsteadOfOverflowing() {
        XCTAssertEqual(CTS500Codec.checksum([0xFF, 0xFF]), 0xFE)
        XCTAssertEqual(CTS500Codec.checksum([0x05, 0xAA, 0x00, 0x00, 0x00]), 0xAF)
    }

    /// The reference's own fragmented-frame test: 12.34 kg is 1234 = 0x000004D2 in
    /// big-endian centi-kilograms, so the frame is 05 01 00 00 04 D2 with checksum
    /// 0x05 + 0x01 + 0x04 + 0xD2 = 0xDC. Split 3 + 4, nothing may be emitted from
    /// the first half.
    func testCTS500ReassemblesAFragmentedWeightFrame() {
        var decoder = CTS500Codec.Decoder()
        let frame = Data([0x05, 0x01, 0x00, 0x00, 0x04, 0xD2, 0xDC])

        XCTAssertEqual(decoder.ingest(frame.prefix(3)), [])

        let readings = decoder.ingest(frame.suffix(4))
        XCTAssertEqual(readings.count, 1)
        XCTAssertEqual(readings[0].kg, 12.34, accuracy: 0.0001)
    }

    /// Bytes 2…5 are BIG-endian. 100.00 kg is 10000 = 0x00002710; read
    /// little-endian the same bytes are 0x10270000 = 2,712,207.36 kg — a number a
    /// glance at a graph would never explain.
    func testCTS500WeightIsBigEndianCentiKilograms() {
        var decoder = CTS500Codec.Decoder()
        // checksum: 0x05 + 0x01 + 0x27 + 0x10 = 0x3D
        let readings = decoder.ingest(Data([0x05, 0x01, 0x00, 0x00, 0x27, 0x10, 0x3D]))

        XCTAssertEqual(readings.count, 1)
        XCTAssertEqual(readings[0].kg, 100, accuracy: 0.0001)
    }

    /// A bad checksum discards the frame — and must leave the buffer able to decode
    /// the next good one, or one corrupted packet ends the session's data.
    func testCTS500RejectsABadChecksumAndRecoversOnTheNextFrame() {
        var decoder = CTS500Codec.Decoder()
        // The reference's own test corrupts the checksum with ^ 0xFF: 0xDC → 0x23.
        XCTAssertEqual(decoder.ingest(Data([0x05, 0x01, 0x00, 0x00, 0x04, 0xD2, 0x23])), [])

        let readings = decoder.ingest(Data([0x05, 0x01, 0x00, 0x00, 0x04, 0xD2, 0xDC]))
        XCTAssertEqual(readings.count, 1)
        XCTAssertEqual(readings[0].kg, 12.34, accuracy: 0.0001)
    }

    /// Commands, echoes and weight share one channel, so the session's own start,
    /// rate and tare writes come back as 6-byte echoes. Reading one as a weight
    /// would report tens of thousands of kilograms.
    func testCTS500CommandEchoesAreNotWeightFrames() {
        var decoder = CTS500Codec.Decoder()
        XCTAssertEqual(decoder.ingest(Data([0x05, 0xAA, 0x00, 0x00, 0x00, 0xAF])), [])
        XCTAssertEqual(decoder.ingest(Data([0x05, 0xC1, 0x00, 0x00, 0x02, 0xC8])), [])
        XCTAssertEqual(decoder.ingest(Data([0x05, 0xA6, 0x00, 0x00, 0x00, 0xAB])), [])
        XCTAssertEqual(decoder.ingest(Data([0x05, 0xAB, 0x00, 0x00, 0x00, 0xB0])), [])
    }

    /// A typed reply is `05 80 <opcode> …`. The reference's battery fixture is
    /// 05 80 C4 00 01 63 → (0x0163) / 100 = 3.55 V, checksum
    /// 0x05 + 0x80 + 0xC4 + 0x01 + 0x63 = 0x1AD → 0xAD.
    ///
    /// Volts are NOT published as a battery fraction: that needs this cell's
    /// discharge curve, and the Progressor's curve is the Progressor's.
    func testCTS500TypedBatteryReplyIsParsedButNeverBecomesAFraction() throws {
        var decoder = CTS500Codec.Decoder()
        XCTAssertEqual(decoder.ingest(Data([0x05, 0x80, 0xC4, 0x00, 0x01, 0x63, 0xAD])), [])
        XCTAssertEqual(try XCTUnwrap(decoder.latestBatteryVolts), 3.55, accuracy: 0.0001)
        XCTAssertNil(decoder.batteryFraction)
    }

    /// A 0x05 inside another frame's payload is a false header. Resynchronising
    /// CONTINUES the walk here, where the reference abandons the notification after
    /// dropping one byte — at 40 Hz that would stall the stream for a noticeable
    /// fraction of a second on a single bad byte.
    func testCTS500ResynchronisesPastAFalseHeaderWithinOneNotification() {
        var decoder = CTS500Codec.Decoder()
        // Seven bytes that validate as neither frame length (0x99 is not an opcode,
        // and the checksum does not match), followed by a good 12.34 kg frame.
        let junk: [UInt8] = [0x05, 0x99, 0x99, 0x99, 0x99, 0x99, 0x99]
        let frame: [UInt8] = [0x05, 0x01, 0x00, 0x00, 0x04, 0xD2, 0xDC]

        let readings = decoder.ingest(Data(junk + frame))
        XCTAssertEqual(readings.count, 1)
        XCTAssertEqual(readings[0].kg, 12.34, accuracy: 0.0001)
    }

    /// Two frames in one notification both decode: the device batches at whatever
    /// rate the UART bridge flushes.
    func testCTS500DecodesTwoWeightFramesFromOneNotification() {
        var decoder = CTS500Codec.Decoder()
        let first: [UInt8] = [0x05, 0x01, 0x00, 0x00, 0x04, 0xD2, 0xDC]   // 12.34 kg
        let second: [UInt8] = [0x05, 0x01, 0x00, 0x00, 0x27, 0x10, 0x3D]  // 100.00 kg

        let readings = decoder.ingest(Data(first + second))
        XCTAssertEqual(readings.count, 2)
        XCTAssertEqual(readings[0].kg, 12.34, accuracy: 0.0001)
        XCTAssertEqual(readings[1].kg, 100, accuracy: 0.0001)
    }

    /// The weight field is read UNSIGNED, exactly as the reference reads it, so a
    /// firmware sending small negative drift in two's complement would produce
    /// 42,949,672.91 kg. Dropped: a missing sample costs a tenth of a second of
    /// trace, an absurd one sets the session peak and rescales the graph.
    func testCTS500DropsAWeightNoHandCouldProduce() {
        var decoder = CTS500Codec.Decoder()
        // checksum: 0x05 + 0x01 + 0xFF × 4 = 0x402 → 0x02
        XCTAssertEqual(decoder.ingest(Data([0x05, 0x01, 0xFF, 0xFF, 0xFF, 0xFF, 0x02])), [])
    }

    func testCTS500BelowFrameLengthFragmentsWaitForMoreBytes() {
        var decoder = CTS500Codec.Decoder()
        XCTAssertEqual(decoder.ingest(Data()), [])
        XCTAssertEqual(decoder.ingest(Data([0x05, 0x01, 0x00, 0x00, 0x04])), [])

        let readings = decoder.ingest(Data([0xD2, 0xDC]))
        XCTAssertEqual(readings.count, 1)
        XCTAssertEqual(readings[0].kg, 12.34, accuracy: 0.0001)
    }

    /// Bytes with no header at all cannot begin a frame, so they are dropped rather
    /// than accumulated.
    func testCTS500DiscardsBytesWithNoHeaderInThem() {
        var decoder = CTS500Codec.Decoder()
        XCTAssertEqual(decoder.ingest(Data([0x11, 0x22, 0x33, 0x44, 0x55, 0x66])), [])

        let readings = decoder.ingest(Data([0x05, 0x01, 0x00, 0x00, 0x04, 0xD2, 0xDC]))
        XCTAssertEqual(readings.count, 1)
        XCTAssertEqual(readings[0].kg, 12.34, accuracy: 0.0001)
    }

    /// **40 Hz is a ONE-TIME setup write, and START is the whole start sequence.**
    ///
    /// The rate request has to be asked (the reference never sends it, and the device's
    /// factory default is unknown — its lowest code is 10 Hz, which would leave barely one
    /// sample inside the runner's 100 ms engage debounce), but it must not ride the path
    /// that is re-sent on every re-kick: the watchdog fires every 500 ms of silence, a tare
    /// recovers, the foreground returns, and 0xC1 is an EEPROM-class configuration command
    /// that plausibly resets the ADC. Re-sending START is harmless by house rule;
    /// re-writing a config ~1500 times across a silent session is not the same thing.
    ///
    /// The tare payload is the DEVICE's own `TARE_SCALE`, written to the same
    /// characteristic as everything else, and the registry row agrees.
    func testCTS500ProfileRequestsFortyHertzOnceThenStartsOnEveryKick() {
        XCTAssertEqual(CTS500Codec.profile.oneTimeSetupPayloads,
                       [Data([0x05, 0xC1, 0x00, 0x00, 0x02, 0xC8])])
        XCTAssertEqual(CTS500Codec.profile.streamStartPayloads,
                       [Data([0x05, 0xAA, 0x00, 0x00, 0x00, 0xAF])])
        XCTAssertEqual(CTS500Codec.profile.streamStopPayload,
                       Data([0x05, 0xAB, 0x00, 0x00, 0x00, 0xB0]))
        XCTAssertEqual(CTS500Codec.profile.tarePayload,
                       Data([0x05, 0xA6, 0x00, 0x00, 0x00, 0xAB]))
        XCTAssertEqual(CTS500Codec.profile.tareCharacteristicUUID,
                       CTS500Codec.profile.writeCharacteristicUUID)
        XCTAssertTrue(GaugeKind.cts500.capabilities.hasHardwareTare)
        XCTAssertEqual(CTS500Codec.profile.serviceUUID,
                       "0000FFE0-0000-1000-8000-00805F9B34FB")
        XCTAssertEqual(CTS500Codec.profile.notifyCharacteristicUUID,
                       "0000FFE1-0000-1000-8000-00805F9B34FB")
    }

    /// Every opcode the reference knows has to be in the set, because a weight frame
    /// is identified partly by its byte 1 NOT being one. A missing opcode turns that
    /// device's echo into a load reading.
    func testCTS500OpcodeSetCoversEveryCommandTheReferenceDefines() {
        XCTAssertEqual(CTS500Codec.commandOpcodes.count, 26)
        for opcode in [0x81, 0x82, 0x83, 0x84, 0x85, 0x86,
                       0xA1, 0xA2, 0xA4, 0xA6, 0xA7, 0xA9, 0xAA, 0xAB,
                       0xC0, 0xC1, 0xC3, 0xC4, 0xC5, 0xC6, 0xC7, 0xCA,
                       0xD1, 0xD2, 0xD3, 0xD4] {
            XCTAssertTrue(CTS500Codec.commandOpcodes.contains(UInt8(opcode)),
                          "opcode \(String(opcode, radix: 16)) missing")
        }
    }

    // MARK: - Registry wiring

    /// The registry's job is one row per device; these four are the framed ones.
    /// A decoder must be a FRESH instance every time — reassembly buffers and the
    /// Motherboard's calibration table die with the link they were filled over.
    func testFramedKindsResolveToTheirOwnProfilesAndFreshDecoders() {
        XCTAssertEqual(GaugeKind.climbro.gatt, ClimbroCodec.profile)
        XCTAssertEqual(GaugeKind.forceboard.gatt, ForceBoardCodec.profile)
        XCTAssertEqual(GaugeKind.motherboard.gatt, MotherboardCodec.profile)
        XCTAssertEqual(GaugeKind.cts500.gatt, CTS500Codec.profile)

        for kind in [GaugeKind.climbro, .forceboard, .motherboard, .cts500] {
            XCTAssertNotNil(kind.makeFrameDecoder(), "\(kind) has no decoder")
            XCTAssertFalse(kind.capabilities.hasDeviceClock,
                           "\(kind) stamps nothing; the client supplies synthetic µs")
        }

        var first = MotherboardCodec.Decoder()
        _ = first.ingest(motherboardCalibration())
        XCTAssertTrue(first.hasCalibration)
        XCTAssertFalse(MotherboardCodec.Decoder().hasCalibration)
    }
}
