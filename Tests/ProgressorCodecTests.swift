// SPDX-License-Identifier: MPL-2.0
// Original contributions Copyright 2026 Nuri Bruner.

import XCTest
@testable import Doigt

/// Byte-level fixtures for the Tindeq wire protocol.
///
/// These are hand-computed, not round-tripped through our own encoder: a decoder
/// tested only against its own encoder agrees with itself about the wrong byte
/// order. Endianness bugs are the dangerous class here because they produce
/// plausible-looking wrong numbers (a 20 kg pull reading as 4.6e-41 or 1.4e14)
/// rather than an obvious crash.
final class ProgressorCodecTests: XCTestCase {

    private func littleEndianBytes(_ value: UInt32) -> [UInt8] {
        [UInt8(truncatingIfNeeded: value),
         UInt8(truncatingIfNeeded: value >> 8),
         UInt8(truncatingIfNeeded: value >> 16),
         UInt8(truncatingIfNeeded: value >> 24)]
    }

    private func sampleBytes(kg: Float, micros: UInt32) -> [UInt8] {
        littleEndianBytes(kg.bitPattern) + littleEndianBytes(micros)
    }

    private func weightPacket(_ samples: [(Float, UInt32)]) -> Data {
        let payload = samples.flatMap { sampleBytes(kg: $0.0, micros: $0.1) }
        return Data([0x01, UInt8(payload.count)] + payload)
    }

    // MARK: - Little-endian float fixtures

    /// 20.0f is IEEE-754 0x41A00000, so on the wire (LE) it is 00 00 A0 41.
    func testSingleWeightSampleDecodesLittleEndianFloat() {
        // tag 1 (weight), length 8, float32 20.0, uint32 12500 µs
        let packet = Data([0x01, 0x08,
                           0x00, 0x00, 0xA0, 0x41,
                           0xD4, 0x30, 0x00, 0x00])
        let events = ProgressorCodec.decode(packet)
        XCTAssertEqual(events, [.sample(ForceSample(kg: 20.0, deviceMicros: 12_500))])
    }

    /// The device batches ~8 samples per notification at 80 Hz — every one of them
    /// must come out, in order.
    func testBatchedWeightPacketDecodesEverySampleInOrder() {
        var payload = Data()
        payload.append(contentsOf: [0x00, 0x00, 0x80, 0x3F, 0x00, 0x00, 0x00, 0x00])  // 1.0 kg @ 0 µs
        payload.append(contentsOf: [0x00, 0x00, 0xD0, 0x40, 0xD4, 0x30, 0x00, 0x00])  // 6.5 kg @ 12500 µs
        payload.append(contentsOf: [0x00, 0x00, 0xA0, 0x41, 0xA8, 0x61, 0x00, 0x00])  // 20.0 kg @ 25000 µs
        let packet = Data([0x01, UInt8(payload.count)]) + payload

        let events = ProgressorCodec.decode(packet)
        XCTAssertEqual(events, [
            .sample(ForceSample(kg: 1.0, deviceMicros: 0)),
            .sample(ForceSample(kg: 6.5, deviceMicros: 12_500, isBatchStart: false)),
            .sample(ForceSample(kg: 20.0, deviceMicros: 25_000, isBatchStart: false)),
        ])
    }

    func testWeightWindowAcceptsItsExactBoundaries() {
        XCTAssertEqual(ProgressorCodec.decode(weightPacket([(-10.0, 0)])),
                       [.sample(ForceSample(kg: -10.0, deviceMicros: 0))])
        XCTAssertEqual(ProgressorCodec.decode(weightPacket([(165.0, 12_500)])),
                       [.sample(ForceSample(kg: 165.0, deviceMicros: 12_500))])
    }

    func testWeightWindowRejectsTheNextFloatBeyondEachBoundary() {
        XCTAssertEqual(ProgressorCodec.decode(weightPacket([(Float(-10).nextDown, 0)])), [])
        XCTAssertEqual(ProgressorCodec.decode(weightPacket([(Float(165).nextUp, 12_500)])), [])
    }

    func testNonFiniteAndWireImpossibleWeightsAreDroppedPairByPair() {
        let packet = weightPacket([
            (.nan, 0),
            (.infinity, 12_500),
            (-.infinity, 25_000),
            (Float(-10).nextDown, 37_500),
            (5_000, 50_000),
            (20, 62_500),
        ])

        XCTAssertEqual(ProgressorCodec.decode(packet),
                       [.sample(ForceSample(kg: 20, deviceMicros: 62_500))],
                       "the first emitted sample keeps the notification boundary")
    }

    // MARK: - Command responses (tag 0 needs the pending command to disambiguate)

    func testBatteryResponseDecodesAsMillivolts() {
        // 4200 mV = 0x1068 → LE 68 10 00 00
        let packet = Data([0x00, 0x04, 0x68, 0x10, 0x00, 0x00])
        XCTAssertEqual(ProgressorCodec.decode(packet, answering: .getBatteryVoltage),
                       [.battery(millivolts: 4200)])
    }

    func testAppVersionResponseDecodesAsTrimmedASCII() {
        let packet = Data([0x00, 0x06]) + Data("1.2.3".utf8) + Data([0x00])
        XCTAssertEqual(ProgressorCodec.decode(packet, answering: .getAppVersion),
                       [.appVersion("1.2.3")])
    }

    /// The device never echoes which command it is answering, so with nothing
    /// pending the only honest decode is the raw payload — NOT a guess that four
    /// bytes must mean battery.
    func testTagZeroWithoutPendingCommandStaysRaw() {
        let packet = Data([0x00, 0x04, 0x68, 0x10, 0x00, 0x00])
        XCTAssertEqual(ProgressorCodec.decode(packet),
                       [.commandResponse(Data([0x68, 0x10, 0x00, 0x00]))])
    }

    func testLowPowerWarningDecodes() {
        XCTAssertEqual(ProgressorCodec.decode(Data([0x04, 0x00])), [.lowPowerWarning])
    }

    func testUnknownTagIsSurfacedRatherThanDropped() {
        let packet = Data([0x63, 0x02, 0xAB, 0xCD])
        XCTAssertEqual(ProgressorCodec.decode(packet),
                       [.unknown(tag: 0x63, payload: Data([0xAB, 0xCD]))])
    }

    // MARK: - Robustness

    /// Real BLE delivers short reads. A declared length running past the buffer must
    /// stop the walk, never read past the end and never trap.
    func testTruncatedPacketStopsCleanly() {
        let packet = Data([0x01, 0x10, 0x00, 0x00, 0xA0, 0x41])   // claims 16 bytes, has 4
        XCTAssertEqual(ProgressorCodec.decode(packet), [])
    }

    func testNonMultipleOfEightWeightBlockIsRejectedWhole() {
        // Length 12 = one whole pair plus half of another.
        let packet = Data([0x01, 0x0C,
                           0x00, 0x00, 0xA0, 0x41, 0xD4, 0x30, 0x00, 0x00,
                           0x00, 0x00, 0x80, 0x3F])
        XCTAssertEqual(ProgressorCodec.decode(packet),
                       [.unknown(tag: 1, payload: Data(packet.dropFirst(2)))])
    }

    func testEmptyWeightBlockIsRejected() {
        XCTAssertEqual(ProgressorCodec.decode(Data([0x01, 0x00])),
                       [.unknown(tag: 1, payload: Data())])
    }

    func testMalformedRFDPeakNeverMasqueradesAsACommandResponse() {
        for payload in [Data(), Data(repeating: 0xAA, count: 7), Data(repeating: 0xAA, count: 9)] {
            let packet = Data([0x02, UInt8(payload.count)]) + payload
            XCTAssertEqual(ProgressorCodec.decode(packet, answering: .getBatteryVoltage),
                           [.unknown(tag: 2, payload: payload)])
        }
    }

    func testRFDSeriesRequiresANonemptyWholeNumberOfPairs() {
        for payload in [Data(), Data(repeating: 0xAA, count: 7), Data(repeating: 0xAA, count: 9)] {
            let packet = Data([0x03, UInt8(payload.count)]) + payload
            XCTAssertEqual(ProgressorCodec.decode(packet),
                           [.unknown(tag: 3, payload: payload)])
        }

        let invalidForce = Data(sampleBytes(kg: .nan, micros: 12_500))
        XCTAssertEqual(ProgressorCodec.decode(Data([0x03, 0x08]) + invalidForce),
                       [.unknown(tag: 3, payload: invalidForce)])
    }

    func testFiveOneSecondTimestampDeltasSuppressTheWholeNotification() {
        let packet = weightPacket((0..<5).map { (20, UInt32($0) * 1_000_000) })
        XCTAssertEqual(ProgressorCodec.decode(packet), [])
    }

    func testNotificationSpanBudgetCoversPackedWeightBlocks() {
        let first = weightPacket([(20, 0)])
        let second = weightPacket([(20, 200_000)])
        XCTAssertEqual(ProgressorCodec.decode(first + second), [],
                       "each block is valid alone, but the notification span is not")
    }

    func testPackedMultiTLVPacketKeepsCompleteBlocksBeforeATruncatedTail() {
        let first = weightPacket([(20, 1_000)])
        let warning = Data([0x04, 0x00])
        let second = weightPacket([(21, 13_500)])
        let truncatedTail = Data([0x01, 0x08, 0x00])

        XCTAssertEqual(ProgressorCodec.decode(first + warning + second + truncatedTail), [
            .sample(ForceSample(kg: 20, deviceMicros: 1_000)),
            .lowPowerWarning,
            .sample(ForceSample(kg: 21, deviceMicros: 13_500, isBatchStart: false)),
        ])
    }

    func testEmptyAndHeaderOnlyPacketsDecodeToNothing() {
        XCTAssertEqual(ProgressorCodec.decode(Data()), [])
        XCTAssertEqual(ProgressorCodec.decode(Data([0x01])), [])
    }

    // MARK: - Timestamp wrap

    /// The device's µs clock is a UInt32, so it wraps every ~71.6 minutes. Naive
    /// subtraction would yield a huge negative interval and blow up a rep's timing;
    /// wrapping subtraction gives the true small delta.
    func testMicrosecondDeltaSurvivesUInt32Wrap() {
        let before = ForceSample(kg: 20, deviceMicros: 4_294_960_000)
        let after = ForceSample(kg: 20, deviceMicros: 50_000)
        XCTAssertEqual(after.microsSince(before), 57_296)
    }

    func testMicrosecondDeltaInTheNormalCase() {
        let a = ForceSample(kg: 20, deviceMicros: 1_000_000)
        let b = ForceSample(kg: 20, deviceMicros: 1_012_500)
        XCTAssertEqual(b.microsSince(a), 12_500)
    }

    // MARK: - Command encoding

    func testZeroPayloadCommandsEncodeAsABareOpcode() {
        XCTAssertEqual(ProgressorCommand.tare.encoded, Data([100]))
        XCTAssertEqual(ProgressorCommand.startWeightMeasurement.encoded, Data([101]))
        XCTAssertEqual(ProgressorCommand.stopWeightMeasurement.encoded, Data([102]))
        XCTAssertEqual(ProgressorCommand.enterSleep.encoded, Data([110]))
        XCTAssertEqual(ProgressorCommand.getBatteryVoltage.encoded, Data([111]))
    }

    func testOnlyQueryCommandsExpectAResponse() {
        XCTAssertTrue(ProgressorCommand.getBatteryVoltage.expectsResponse)
        XCTAssertTrue(ProgressorCommand.getAppVersion.expectsResponse)
        XCTAssertFalse(ProgressorCommand.tare.expectsResponse)
        XCTAssertFalse(ProgressorCommand.startWeightMeasurement.expectsResponse)
    }

    func testBatteryFractionClampsToTheCellRange() {
        XCTAssertEqual(ProgressorCodec.batteryFraction(millivolts: 4200), 1.0, accuracy: 0.001)
        XCTAssertEqual(ProgressorCodec.batteryFraction(millivolts: 3300), 0.0, accuracy: 0.001)
        XCTAssertEqual(ProgressorCodec.batteryFraction(millivolts: 3750), 0.5, accuracy: 0.001)
        XCTAssertEqual(ProgressorCodec.batteryFraction(millivolts: 5000), 1.0, accuracy: 0.001)
        XCTAssertEqual(ProgressorCodec.batteryFraction(millivolts: 1000), 0.0, accuracy: 0.001)
    }
}
