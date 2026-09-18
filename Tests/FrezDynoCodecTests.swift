// SPDX-License-Identifier: MPL-2.0
// Original contributions Copyright 2026 Nuri Bruner.

import XCTest
@testable import Doigt

/// The Frez Dyno codec, case for case the same vectors as `Fixtures/codec/frezdyno.json`
/// (which the Kotlin suite asserts), built here from the record arithmetic rather than
/// pasted as hex so a reader can see what each byte means.
///
/// Frez's protocol is written down by Frez, so unlike the ported gauges these are not
/// guesses about somebody else's firmware — but the device is still unverified on
/// hardware by this project, and the dangerous class is the same as everywhere else: a
/// sign, endianness or scaling slip yields a plausible wrong number, never a crash.
final class FrezDynoCodecTests: XCTestCase {

    /// A plausible per-device slope, and an unloaded count that is deliberately nowhere
    /// near zero: the whole point of the tare is that raw counts carry a large offset.
    private let a = 0.000012345678
    private let zero: Int32 = 8_123_456

    private func frame(_ records: [(raw: Int32, ms: UInt32)],
                       code: UInt8 = FrezDynoCodec.bulkResponseCode,
                       reserved: UInt8 = FrezDynoCodec.reservedByte) -> Data {
        var data = Data([code, reserved])
        for record in records {
            withUnsafeBytes(of: record.raw.littleEndian) { data.append(contentsOf: $0) }
            withUnsafeBytes(of: record.ms.littleEndian) { data.append(contentsOf: $0) }
        }
        return data
    }

    private func records(from startMs: UInt32, _ raws: [Int32], step: UInt32 = 4) -> [(raw: Int32, ms: UInt32)] {
        raws.enumerated().map { (raw: $0.element, ms: startMs + UInt32($0.offset) * step) }
    }

    private var tareFrame: Data { frame(records(from: 0, Array(repeating: zero, count: 9))) }

    private func decoder() -> FrezDynoCodec.Decoder {
        FrezDynoCodec.Decoder(coefficient: a, tareSampleCount: 9)
    }

    // MARK: - Framing

    func testAFrameIsExactlyNineRecordsBehindTheHeader() {
        XCTAssertEqual(FrezDynoCodec.frameLength, 74)
        let samples = FrezDynoCodec.parse(frame(records(from: 36, [1, -1, 2, -2, 3, -3, 4, -4, 5])))
        XCTAssertEqual(samples?.count, 9)
        XCTAssertEqual(samples?.first, FrezDynoCodec.RawSample(rawADC: 1, elapsedMs: 36))
        XCTAssertEqual(samples?[1], FrezDynoCodec.RawSample(rawADC: -1, elapsedMs: 40))
        XCTAssertEqual(samples?.last, FrezDynoCodec.RawSample(rawADC: 5, elapsedMs: 68))
    }

    /// Frez: "treat a truncated frame as an error instead of parsing partial samples."
    func testATruncatedFrameIsRejectedWholeAndDoesNotFeedTheTare() {
        let short = frame(records(from: 0, Array(repeating: zero, count: 8)))   // 66 bytes
        XCTAssertNil(FrezDynoCodec.parse(short))

        var decoder = decoder()
        XCTAssertEqual(decoder.ingest(short), [])
        XCTAssertFalse(decoder.isTared, "eight counts must not have been banked toward the tare")
        XCTAssertEqual(decoder.ingest(tareFrame), [])
        XCTAssertTrue(decoder.isTared)
    }

    func testAWrongResponseCodeOrReservedByteIsNotABulkFrame() {
        XCTAssertNil(FrezDynoCodec.parse(frame(records(from: 0, Array(repeating: zero, count: 9)), code: 0x02)))
        XCTAssertNil(FrezDynoCodec.parse(frame(records(from: 0, Array(repeating: zero, count: 9)), reserved: 0x01)))
    }

    func testCountsAreSignedLittleEndian() {
        // 0xFFFFFFFF is −1 as a signed count; read unsigned it would be four billion.
        let bytes = Data([0x01, 0x00]) + Data(repeating: 0xFF, count: 4) + Data([0x24, 0x00, 0x00, 0x00])
            + Data(repeating: 0, count: 8 * 8)
        XCTAssertEqual(FrezDynoCodec.parse(bytes)?.first, FrezDynoCodec.RawSample(rawADC: -1, elapsedMs: 36))
    }

    // MARK: - Tare and conversion

    func testNothingIsReportedUntilTheTareIsEstablishedThenCountsBecomeKilograms() {
        var decoder = decoder()
        XCTAssertEqual(decoder.ingest(tareFrame), [], "the first nine counts are the zero, not readings")
        XCTAssertEqual(decoder.tareADC, Double(zero))

        let loaded: [Int32] = [zero + 810_000, zero + 1_620_000, zero + 2_430_000, zero + 3_240_000,
                               zero + 4_050_000, zero + 3_240_000, zero + 2_430_000, zero + 1_620_000,
                               zero - 810_000]
        let readings = decoder.ingest(frame(records(from: 36, loaded)))
        XCTAssertEqual(readings.count, 9)
        for (index, reading) in readings.enumerated() {
            XCTAssertEqual(reading.kg, a * Double(loaded[index] - zero), accuracy: 1e-9)
            XCTAssertEqual(reading.deviceMicros, (36 + UInt32(index) * 4) * 1000)
        }
        XCTAssertEqual(readings[0].kg, 10, accuracy: 1e-5)
        XCTAssertEqual(readings[8].kg, -10, accuracy: 1e-5, "a negative load is a fact, not a corrupt frame")
    }

    func testTheTareAveragesEveryCollectedCountNotTheLastOne() {
        var decoder = decoder()
        _ = decoder.ingest(frame(records(from: 0, [zero - 40, zero + 40, zero - 20, zero + 20, zero,
                                                    zero + 60, zero - 60, zero + 10, zero - 10])))
        XCTAssertEqual(decoder.tareADC, Double(zero))
        let readings = decoder.ingest(frame(records(from: 36, Array(repeating: zero, count: 9))))
        XCTAssertEqual(readings.count, 9)
        for reading in readings { XCTAssertEqual(reading.kg, 0, accuracy: 1e-12) }
    }

    /// The default is Frez's own 100 unloaded samples — 0.4 s at 250 Hz.
    func testTheDefaultTareIsOneHundredSamples() {
        var decoder = FrezDynoCodec.Decoder(coefficient: a)
        XCTAssertEqual(decoder.tareSampleCount, 100)
        for index in 0..<11 {
            let readings = decoder.ingest(frame(records(from: UInt32(index) * 36, Array(repeating: zero, count: 9))))
            XCTAssertEqual(readings, [], "frame \(index) still belongs to the tare")
        }
        XCTAssertFalse(decoder.isTared, "99 samples are not yet a zero")
        let twelfth = decoder.ingest(frame(records(from: 11 * 36, Array(repeating: zero + 810_000, count: 9))))
        XCTAssertTrue(decoder.isTared)
        XCTAssertEqual(twelfth.count, 8, "the hundredth sample completes the tare; the eight after it are readings")
    }

    func testACountTheCoefficientTurnsIntoOverAThousandKilogramsIsACorruptRecord() {
        var decoder = decoder()
        _ = decoder.ingest(tareFrame)
        var raws = Array(repeating: zero + 810_000, count: 9)
        raws[4] = 2_000_000_000
        let readings = decoder.ingest(frame(records(from: 36, raws)))
        XCTAssertEqual(readings.count, 8)
        XCTAssertFalse(readings.contains { abs($0.kg) > FrezDynoCodec.maxPlausibleKilograms })
    }

    // MARK: - The device clock

    /// Frez: "Reject duplicate or decreasing elapsed time within a measurement session."
    /// But Start resets the device clock, and the app re-sends Start on purpose, so a
    /// jump back of at least a quarter second that lands near zero is the next session —
    /// dropping it would drop the stream. 68 → 64 is neither (an eight-millisecond
    /// stumble), 300 → 296 is neither (it does not land near zero); 300 → 4 is both.
    func testDuplicateAndBackwardsTimestampsAreDroppedButARestartNearZeroIsANewSession() {
        var decoder = decoder()
        _ = decoder.ingest(tareFrame)
        let loaded = zero + 810_000
        let readings = decoder.ingest(frame([(loaded, 68), (loaded, 68), (loaded, 64), (loaded, 72),
                                             (loaded, 300), (loaded, 296), (loaded, 4), (loaded, 8), (loaded, 12)]))
        XCTAssertEqual(readings.map(\.deviceMicros), [68_000, 72_000, 300_000, 4_000, 8_000, 12_000])
    }

    /// A restart does NOT re-tare: the zero belongs to the link, and the load on the
    /// gauge at the moment of a watchdog re-kick is exactly the load that must not
    /// become the new zero.
    func testARestartKeepsTheTare() {
        var decoder = decoder()
        _ = decoder.ingest(tareFrame)
        _ = decoder.ingest(frame(records(from: 400, Array(repeating: zero + 810_000, count: 9))))
        let afterRestart = decoder.ingest(frame(records(from: 4, Array(repeating: zero + 810_000, count: 9))))
        XCTAssertEqual(afterRestart.count, 9)
        XCTAssertEqual(afterRestart[0].kg, 10, accuracy: 1e-5)
        XCTAssertEqual(decoder.tareADC, Double(zero))
    }

    /// The one case the rule refuses: a Start re-sent within a quarter second of the last
    /// one. The records until the new clock passes the old one are dropped — a loss
    /// bounded by the threshold — and the stream then continues.
    func testAStartResentWithinTheThresholdCostsAtMostTheThresholdOfRecords() {
        var decoder = decoder()
        _ = decoder.ingest(tareFrame)                                                  // clock at 32
        let loaded = zero + 810_000
        let first = decoder.ingest(frame(records(from: 36, Array(repeating: loaded, count: 9))))   // 36…68
        XCTAssertEqual(first.count, 9)
        // A second Start 70 ms in: the clock restarts, but 68 − 4 is not a quarter second.
        let overlap = decoder.ingest(frame(records(from: 4, Array(repeating: loaded, count: 9))))  // 4…36
        XCTAssertEqual(overlap.count, 0, "records behind the old clock are dropped")
        let straddling = decoder.ingest(frame(records(from: 40, Array(repeating: loaded, count: 9))))  // 40…72
        XCTAssertEqual(straddling.map(\.deviceMicros), [72_000], "only the record past the old clock survives")
        let resumed = decoder.ingest(frame(records(from: 76, Array(repeating: loaded, count: 9))))  // 76…108
        XCTAssertEqual(resumed.count, 9, "the stream continues once the new clock passes the old one")
    }

    /// Milliseconds × 1000 wraps at the same ~71.6 minutes the Tindeq's µs clock does,
    /// and every consumer already subtracts with `&-`.
    func testDeviceMillisecondsBecomeWrappingMicros() {
        XCTAssertEqual(FrezDynoCodec.deviceMicros(elapsedMs: 36), 36_000)
        XCTAssertEqual(FrezDynoCodec.deviceMicros(elapsedMs: 4_294_960), 4_294_960_000)
        XCTAssertEqual(FrezDynoCodec.deviceMicros(elapsedMs: 4_294_992), 24_704)
        let before = ForceSample(kg: 0, deviceMicros: FrezDynoCodec.deviceMicros(elapsedMs: 4_294_960))
        let after = ForceSample(kg: 0, deviceMicros: FrezDynoCodec.deviceMicros(elapsedMs: 4_294_992))
        XCTAssertEqual(after.microsSince(before), 32_000, "a delta across the wrap is still 32 ms")
    }

    // MARK: - Wiring facts

    func testTheProfileSubscribesThenWritesTheTwoByteStartAndStop() {
        let profile = FrezDynoCodec.profile
        XCTAssertEqual(profile.serviceUUID, "DA8A6C41-154B-4B9A-9B00-2F84DFCEBFE9")
        XCTAssertEqual(profile.notifyCharacteristicUUID, "DA8A6C42-154B-4B9A-9B00-2F84DFCEBFE9")
        XCTAssertEqual(profile.writeCharacteristicUUID, "DA8A6C43-154B-4B9A-9B00-2F84DFCEBFE9")
        XCTAssertEqual(profile.streamStartPayloads, [Data([0x01, 0x00])])
        XCTAssertEqual(profile.streamStopPayload, Data([0x02, 0x00]))
        XCTAssertTrue(profile.oneTimeSetupPayloads.isEmpty)
        XCTAssertNil(profile.tareCharacteristicUUID, "the zero is arithmetic, never a device write")
        XCTAssertEqual(FrezDynoCodec.Command.powerOff, Data([0xFF, 0x00]))
    }
}
