// SPDX-License-Identifier: MPL-2.0
// Original contributions Copyright 2026 Nuri Bruner.

package run.nuri.getagrip.engine

import kotlin.math.nextDown
import kotlin.math.nextUp
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/// Byte-level fixtures for the Tindeq wire protocol.
///
/// These are hand-computed, not round-tripped through our own encoder: a decoder
/// tested only against its own encoder agrees with itself about the wrong byte
/// order. Endianness bugs are the dangerous class here because they produce
/// plausible-looking wrong numbers (a 20 kg pull reading as 4.6e-41 or 1.4e14)
/// rather than an obvious crash.
///
/// Translated from Tests/ProgressorCodecTests.swift.
class ProgressorCodecTests {

    private fun littleEndianBytes(value: UInt): ByteArray = bytes(
        (value and 0xFFu).toInt(),
        ((value shr 8) and 0xFFu).toInt(),
        ((value shr 16) and 0xFFu).toInt(),
        ((value shr 24) and 0xFFu).toInt(),
    )

    private fun sampleBytes(kg: Float, micros: UInt): ByteArray =
        littleEndianBytes(kg.toRawBits().toUInt()) + littleEndianBytes(micros)

    private fun weightPacket(samples: List<Pair<Float, UInt>>): ByteArray {
        var payload = ByteArray(0)
        for (sample in samples) payload += sampleBytes(kg = sample.first, micros = sample.second)
        return bytes(0x01, payload.size) + payload
    }

    private fun sample(kg: Double, micros: UInt, batchStart: Boolean = true) =
        ProgressorEvent.Sample(ForceSample(kg = kg, deviceMicros = micros, isBatchStart = batchStart))

    // MARK: - Little-endian float fixtures

    /// 20.0f is IEEE-754 0x41A00000, so on the wire (LE) it is 00 00 A0 41.
    @Test
    fun singleWeightSampleDecodesLittleEndianFloat() {
        // tag 1 (weight), length 8, float32 20.0, uint32 12500 µs
        val packet = bytes(
            0x01, 0x08,
            0x00, 0x00, 0xA0, 0x41,
            0xD4, 0x30, 0x00, 0x00,
        )
        val events = ProgressorCodec.decode(packet)
        assertEquals(listOf(sample(20.0, 12_500u)), events)
    }

    /// The device batches ~8 samples per notification at 80 Hz — every one of them
    /// must come out, in order.
    @Test
    fun batchedWeightPacketDecodesEverySampleInOrder() {
        val payload =
            bytes(0x00, 0x00, 0x80, 0x3F, 0x00, 0x00, 0x00, 0x00) +   // 1.0 kg @ 0 µs
                bytes(0x00, 0x00, 0xD0, 0x40, 0xD4, 0x30, 0x00, 0x00) +   // 6.5 kg @ 12500 µs
                bytes(0x00, 0x00, 0xA0, 0x41, 0xA8, 0x61, 0x00, 0x00)     // 20.0 kg @ 25000 µs
        val packet = bytes(0x01, payload.size) + payload

        val events = ProgressorCodec.decode(packet)
        assertEquals(
            listOf(
                sample(1.0, 0u),
                sample(6.5, 12_500u, batchStart = false),
                sample(20.0, 25_000u, batchStart = false),
            ),
            events,
        )
    }

    @Test
    fun weightWindowAcceptsItsExactBoundaries() {
        assertEquals(
            listOf(sample(-10.0, 0u)),
            ProgressorCodec.decode(weightPacket(listOf(-10.0f to 0u))),
        )
        assertEquals(
            listOf(sample(165.0, 12_500u)),
            ProgressorCodec.decode(weightPacket(listOf(165.0f to 12_500u))),
        )
    }

    @Test
    fun weightWindowRejectsTheNextFloatBeyondEachBoundary() {
        assertEquals(
            emptyList(),
            ProgressorCodec.decode(weightPacket(listOf((-10.0f).nextDown() to 0u))),
        )
        assertEquals(
            emptyList(),
            ProgressorCodec.decode(weightPacket(listOf(165.0f.nextUp() to 12_500u))),
        )
    }

    @Test
    fun nonFiniteAndWireImpossibleWeightsAreDroppedPairByPair() {
        val packet = weightPacket(
            listOf(
                Float.NaN to 0u,
                Float.POSITIVE_INFINITY to 12_500u,
                Float.NEGATIVE_INFINITY to 25_000u,
                (-10.0f).nextDown() to 37_500u,
                5_000.0f to 50_000u,
                20.0f to 62_500u,
            ),
        )

        assertEquals(
            listOf(sample(20.0, 62_500u)),
            ProgressorCodec.decode(packet),
            "the first emitted sample keeps the notification boundary",
        )
    }

    // MARK: - Command responses (tag 0 needs the pending command to disambiguate)

    @Test
    fun batteryResponseDecodesAsMillivolts() {
        // 4200 mV = 0x1068 → LE 68 10 00 00
        val packet = bytes(0x00, 0x04, 0x68, 0x10, 0x00, 0x00)
        assertEquals(
            listOf(ProgressorEvent.Battery(millivolts = 4200u)),
            ProgressorCodec.decode(packet, answering = ProgressorCommand.getBatteryVoltage),
        )
    }

    @Test
    fun appVersionResponseDecodesAsTrimmedASCII() {
        val packet = bytes(0x00, 0x06) + "1.2.3".toByteArray(Charsets.UTF_8) + bytes(0x00)
        assertEquals(
            listOf(ProgressorEvent.AppVersion("1.2.3")),
            ProgressorCodec.decode(packet, answering = ProgressorCommand.getAppVersion),
        )
    }

    /// The device never echoes which command it is answering, so with nothing
    /// pending the only honest decode is the raw payload — NOT a guess that four
    /// bytes must mean battery.
    @Test
    fun tagZeroWithoutPendingCommandStaysRaw() {
        val packet = bytes(0x00, 0x04, 0x68, 0x10, 0x00, 0x00)
        assertEquals(
            listOf(ProgressorEvent.CommandResponse(bytes(0x68, 0x10, 0x00, 0x00))),
            ProgressorCodec.decode(packet),
        )
    }

    @Test
    fun lowPowerWarningDecodes() {
        assertEquals(
            listOf(ProgressorEvent.LowPowerWarning),
            ProgressorCodec.decode(bytes(0x04, 0x00)),
        )
    }

    @Test
    fun unknownTagIsSurfacedRatherThanDropped() {
        val packet = bytes(0x63, 0x02, 0xAB, 0xCD)
        assertEquals(
            listOf(ProgressorEvent.Unknown(tag = 0x63, payload = bytes(0xAB, 0xCD))),
            ProgressorCodec.decode(packet),
        )
    }

    // MARK: - Robustness

    /// Real BLE delivers short reads. A declared length running past the buffer must
    /// stop the walk, never read past the end and never trap.
    @Test
    fun truncatedPacketStopsCleanly() {
        val packet = bytes(0x01, 0x10, 0x00, 0x00, 0xA0, 0x41)   // claims 16 bytes, has 4
        assertEquals(emptyList(), ProgressorCodec.decode(packet))
    }

    @Test
    fun nonMultipleOfEightWeightBlockIsRejectedWhole() {
        // Length 12 = one whole pair plus half of another.
        val packet = bytes(
            0x01, 0x0C,
            0x00, 0x00, 0xA0, 0x41, 0xD4, 0x30, 0x00, 0x00,
            0x00, 0x00, 0x80, 0x3F,
        )
        assertEquals(
            listOf(ProgressorEvent.Unknown(tag = 1, payload = packet.copyOfRange(2, packet.size))),
            ProgressorCodec.decode(packet),
        )
    }

    @Test
    fun emptyWeightBlockIsRejected() {
        assertEquals(
            listOf(ProgressorEvent.Unknown(tag = 1, payload = ByteArray(0))),
            ProgressorCodec.decode(bytes(0x01, 0x00)),
        )
    }

    @Test
    fun malformedRFDPeakNeverMasqueradesAsACommandResponse() {
        for (payload in listOf(ByteArray(0), ByteArray(7) { 0xAA.toByte() }, ByteArray(9) { 0xAA.toByte() })) {
            val packet = bytes(0x02, payload.size) + payload
            assertEquals(
                listOf(ProgressorEvent.Unknown(tag = 2, payload = payload)),
                ProgressorCodec.decode(packet, answering = ProgressorCommand.getBatteryVoltage),
            )
        }
    }

    @Test
    fun rfdSeriesRequiresANonemptyWholeNumberOfPairs() {
        for (payload in listOf(ByteArray(0), ByteArray(7) { 0xAA.toByte() }, ByteArray(9) { 0xAA.toByte() })) {
            val packet = bytes(0x03, payload.size) + payload
            assertEquals(
                listOf(ProgressorEvent.Unknown(tag = 3, payload = payload)),
                ProgressorCodec.decode(packet),
            )
        }

        val invalidForce = sampleBytes(kg = Float.NaN, micros = 12_500u)
        assertEquals(
            listOf(ProgressorEvent.Unknown(tag = 3, payload = invalidForce)),
            ProgressorCodec.decode(bytes(0x03, 0x08) + invalidForce),
        )
    }

    @Test
    fun fiveOneSecondTimestampDeltasSuppressTheWholeNotification() {
        val packet = weightPacket((0 until 5).map { 20.0f to it.toUInt() * 1_000_000u })
        assertEquals(emptyList(), ProgressorCodec.decode(packet))
    }

    @Test
    fun notificationSpanBudgetCoversPackedWeightBlocks() {
        val first = weightPacket(listOf(20.0f to 0u))
        val second = weightPacket(listOf(20.0f to 200_000u))
        assertEquals(
            emptyList(),
            ProgressorCodec.decode(first + second),
            "each block is valid alone, but the notification span is not",
        )
    }

    @Test
    fun packedMultiTLVPacketKeepsCompleteBlocksBeforeATruncatedTail() {
        val first = weightPacket(listOf(20.0f to 1_000u))
        val warning = bytes(0x04, 0x00)
        val second = weightPacket(listOf(21.0f to 13_500u))
        val truncatedTail = bytes(0x01, 0x08, 0x00)

        assertEquals(
            listOf(
                sample(20.0, 1_000u),
                ProgressorEvent.LowPowerWarning,
                sample(21.0, 13_500u, batchStart = false),
            ),
            ProgressorCodec.decode(first + warning + second + truncatedTail),
        )
    }

    @Test
    fun emptyAndHeaderOnlyPacketsDecodeToNothing() {
        assertEquals(emptyList(), ProgressorCodec.decode(ByteArray(0)))
        assertEquals(emptyList(), ProgressorCodec.decode(bytes(0x01)))
    }

    // MARK: - Timestamp wrap

    /// The device's µs clock is a UInt32, so it wraps every ~71.6 minutes. Naive
    /// subtraction would yield a huge negative interval and blow up a rep's timing;
    /// wrapping subtraction gives the true small delta.
    @Test
    fun microsecondDeltaSurvivesUInt32Wrap() {
        val before = ForceSample(kg = 20.0, deviceMicros = 4_294_960_000u)
        val after = ForceSample(kg = 20.0, deviceMicros = 50_000u)
        assertEquals(57_296u, after.microsSince(before))
    }

    @Test
    fun microsecondDeltaInTheNormalCase() {
        val a = ForceSample(kg = 20.0, deviceMicros = 1_000_000u)
        val b = ForceSample(kg = 20.0, deviceMicros = 1_012_500u)
        assertEquals(12_500u, b.microsSince(a))
    }

    // MARK: - Command encoding

    @Test
    fun zeroPayloadCommandsEncodeAsABareOpcode() {
        assertBytesEqual(bytes(100), ProgressorCommand.tare.encoded)
        assertBytesEqual(bytes(101), ProgressorCommand.startWeightMeasurement.encoded)
        assertBytesEqual(bytes(102), ProgressorCommand.stopWeightMeasurement.encoded)
        assertBytesEqual(bytes(110), ProgressorCommand.enterSleep.encoded)
        assertBytesEqual(bytes(111), ProgressorCommand.getBatteryVoltage.encoded)
    }

    @Test
    fun onlyQueryCommandsExpectAResponse() {
        assertTrue(ProgressorCommand.getBatteryVoltage.expectsResponse)
        assertTrue(ProgressorCommand.getAppVersion.expectsResponse)
        assertFalse(ProgressorCommand.tare.expectsResponse)
        assertFalse(ProgressorCommand.startWeightMeasurement.expectsResponse)
    }

    @Test
    fun batteryFractionClampsToTheCellRange() {
        assertEquals(1.0, ProgressorCodec.batteryFraction(4200u), 0.001)
        assertEquals(0.0, ProgressorCodec.batteryFraction(3300u), 0.001)
        assertEquals(0.5, ProgressorCodec.batteryFraction(3750u), 0.001)
        assertEquals(1.0, ProgressorCodec.batteryFraction(5000u), 0.001)
        assertEquals(0.0, ProgressorCodec.batteryFraction(1000u), 0.001)
    }
}
