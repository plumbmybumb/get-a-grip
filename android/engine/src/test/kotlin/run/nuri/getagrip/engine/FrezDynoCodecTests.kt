// SPDX-License-Identifier: MPL-2.0
// Original contributions Copyright 2026 Nuri Bruner.

package run.nuri.getagrip.engine

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/// The Frez Dyno codec, case for case the same vectors as `Fixtures/codec/frezdyno.json`
/// (which `CodecFixtureTests` asserts), built here from the record arithmetic rather than
/// pasted as hex so a reader can see what each byte means.
///
/// Frez's protocol is written down by Frez, so unlike the ported gauges these are not
/// guesses about somebody else's firmware — but the device is still unverified on
/// hardware by this project, and the dangerous class is the same as everywhere else: a
/// sign, endianness or scaling slip yields a plausible wrong number, never a crash.
///
/// Translated from Tests/FrezDynoCodecTests.swift.
class FrezDynoCodecTests {

    /// A plausible per-device slope, and an unloaded count that is deliberately nowhere
    /// near zero: the whole point of the tare is that raw counts carry a large offset.
    private val a = 0.000012345678
    private val zero: Int = 8_123_456

    /// TRANSLATION NOTE: Swift builds each record with `withUnsafeBytes(of:littleEndian)`;
    /// Kotlin has no such thing, so the four bytes are shifted out by hand. Same order,
    /// and the shift is the readable half of what `littleEndian` means anyway.
    private fun frame(
        records: List<Pair<Int, UInt>>,
        code: Int = FrezDynoCodec.bulkResponseCode,
        reserved: Int = FrezDynoCodec.reservedByte,
    ): ByteArray {
        val out = ArrayList<Int>(2 + records.size * FrezDynoCodec.recordLength)
        out.add(code)
        out.add(reserved)
        for ((raw, ms) in records) {
            for (shift in 0..3) out.add((raw shr (shift * 8)) and 0xFF)
            for (shift in 0..3) out.add(((ms shr (shift * 8)) and 0xFFu).toInt())
        }
        return ByteArray(out.size) { out[it].toByte() }
    }

    private fun records(from: UInt, raws: List<Int>, step: UInt = 4u): List<Pair<Int, UInt>> =
        raws.mapIndexed { index, raw -> raw to (from + index.toUInt() * step) }

    private fun repeated(raw: Int, count: Int = 9): List<Int> = List(count) { raw }

    private val tareFrame: ByteArray get() = frame(records(0u, repeated(zero)))

    private fun decoder() = FrezDynoCodec.Decoder(coefficient = a, tareSampleCount = 9)

    // MARK: - Framing

    @Test
    fun aFrameIsExactlyNineRecordsBehindTheHeader() {
        assertEquals(74, FrezDynoCodec.frameLength)
        val samples = FrezDynoCodec.parse(
            frame(records(36u, listOf(1, -1, 2, -2, 3, -3, 4, -4, 5))),
        )
        assertEquals(9, samples?.size)
        assertEquals(FrezDynoCodec.RawSample(rawADC = 1, elapsedMs = 36u), samples?.first())
        assertEquals(FrezDynoCodec.RawSample(rawADC = -1, elapsedMs = 40u), samples?.get(1))
        assertEquals(FrezDynoCodec.RawSample(rawADC = 5, elapsedMs = 68u), samples?.last())
    }

    /// Frez: "treat a truncated frame as an error instead of parsing partial samples."
    @Test
    fun aTruncatedFrameIsRejectedWholeAndDoesNotFeedTheTare() {
        val short = frame(records(0u, repeated(zero, count = 8))) // 66 bytes
        assertNull(FrezDynoCodec.parse(short))

        val decoder = decoder()
        assertEquals(emptyList(), decoder.ingest(short))
        assertFalse(decoder.isTared, "eight counts must not have been banked toward the tare")
        assertEquals(emptyList(), decoder.ingest(tareFrame))
        assertTrue(decoder.isTared)
    }

    @Test
    fun aWrongResponseCodeOrReservedByteIsNotABulkFrame() {
        assertNull(FrezDynoCodec.parse(frame(records(0u, repeated(zero)), code = 0x02)))
        assertNull(FrezDynoCodec.parse(frame(records(0u, repeated(zero)), reserved = 0x01)))
    }

    @Test
    fun countsAreSignedLittleEndian() {
        // 0xFFFFFFFF is −1 as a signed count; read unsigned it would be four billion.
        val bytes = ByteArray(FrezDynoCodec.frameLength)
        bytes[0] = 0x01
        bytes[1] = 0x00
        for (index in 2..5) bytes[index] = 0xFF.toByte()
        bytes[6] = 0x24
        assertEquals(
            FrezDynoCodec.RawSample(rawADC = -1, elapsedMs = 36u),
            FrezDynoCodec.parse(bytes)?.first(),
        )
    }

    // MARK: - Tare and conversion

    @Test
    fun nothingIsReportedUntilTheTareIsEstablishedThenCountsBecomeKilograms() {
        val decoder = decoder()
        assertEquals(
            emptyList(), decoder.ingest(tareFrame),
            "the first nine counts are the zero, not readings",
        )
        assertEquals(zero.toDouble(), decoder.tareADC)

        val loaded = listOf(
            zero + 810_000, zero + 1_620_000, zero + 2_430_000, zero + 3_240_000,
            zero + 4_050_000, zero + 3_240_000, zero + 2_430_000, zero + 1_620_000,
            zero - 810_000,
        )
        val readings = decoder.ingest(frame(records(36u, loaded)))
        assertEquals(9, readings.size)
        for ((index, reading) in readings.withIndex()) {
            assertEquals(a * (loaded[index] - zero).toDouble(), reading.kg, 1e-9)
            assertEquals((36u + index.toUInt() * 4u) * 1000u, reading.deviceMicros)
        }
        assertEquals(10.0, readings[0].kg, 1e-5)
        assertEquals(-10.0, readings[8].kg, 1e-5, "a negative load is a fact, not a corrupt frame")
    }

    @Test
    fun theTareAveragesEveryCollectedCountNotTheLastOne() {
        val decoder = decoder()
        decoder.ingest(
            frame(
                records(
                    0u,
                    listOf(
                        zero - 40, zero + 40, zero - 20, zero + 20, zero,
                        zero + 60, zero - 60, zero + 10, zero - 10,
                    ),
                ),
            ),
        )
        assertEquals(zero.toDouble(), decoder.tareADC)
        val readings = decoder.ingest(frame(records(36u, repeated(zero))))
        assertEquals(9, readings.size)
        for (reading in readings) assertEquals(0.0, reading.kg, 1e-12)
    }

    /// The default is Frez's own 100 unloaded samples — 0.4 s at 250 Hz.
    @Test
    fun theDefaultTareIsOneHundredSamples() {
        val decoder = FrezDynoCodec.Decoder(coefficient = a)
        assertEquals(100, decoder.tareSampleCount)
        for (index in 0 until 11) {
            val readings = decoder.ingest(frame(records(index.toUInt() * 36u, repeated(zero))))
            assertEquals(emptyList(), readings, "frame $index still belongs to the tare")
        }
        assertFalse(decoder.isTared, "99 samples are not yet a zero")
        val twelfth = decoder.ingest(frame(records(11u * 36u, repeated(zero + 810_000))))
        assertTrue(decoder.isTared)
        assertEquals(
            8, twelfth.size,
            "the hundredth sample completes the tare; the eight after it are readings",
        )
    }

    @Test
    fun aCountTheCoefficientTurnsIntoOverAThousandKilogramsIsACorruptRecord() {
        val decoder = decoder()
        decoder.ingest(tareFrame)
        val raws = repeated(zero + 810_000).toMutableList()
        raws[4] = 2_000_000_000
        val readings = decoder.ingest(frame(records(36u, raws)))
        assertEquals(8, readings.size)
        assertFalse(readings.any { kotlin.math.abs(it.kg) > FrezDynoCodec.maxPlausibleKilograms })
    }

    // MARK: - The device clock

    /// Frez: "Reject duplicate or decreasing elapsed time within a measurement session."
    /// But Start resets the device clock, and the app re-sends Start on purpose, so a
    /// jump back of at least a quarter second that lands near zero is the next session —
    /// dropping it would drop the stream. 68 → 64 is neither (an eight-millisecond
    /// stumble), 300 → 296 is neither (it does not land near zero); 300 → 4 is both.
    @Test
    fun duplicateAndBackwardsTimestampsAreDroppedButARestartNearZeroIsANewSession() {
        val decoder = decoder()
        decoder.ingest(tareFrame)
        val loaded = zero + 810_000
        val readings = decoder.ingest(
            frame(
                listOf(
                    loaded to 68u, loaded to 68u, loaded to 64u, loaded to 72u,
                    loaded to 300u, loaded to 296u, loaded to 4u, loaded to 8u, loaded to 12u,
                ),
            ),
        )
        assertEquals(
            listOf(68_000u, 72_000u, 300_000u, 4_000u, 8_000u, 12_000u),
            readings.map { it.deviceMicros },
        )
    }

    /// A restart does NOT re-tare: the zero belongs to the link, and the load on the
    /// gauge at the moment of a watchdog re-kick is exactly the load that must not
    /// become the new zero.
    @Test
    fun aRestartKeepsTheTare() {
        val decoder = decoder()
        decoder.ingest(tareFrame)
        decoder.ingest(frame(records(400u, repeated(zero + 810_000))))
        val afterRestart = decoder.ingest(frame(records(4u, repeated(zero + 810_000))))
        assertEquals(9, afterRestart.size)
        assertEquals(10.0, afterRestart[0].kg, 1e-5)
        assertEquals(zero.toDouble(), decoder.tareADC)
    }

    /// The one case the rule refuses: a Start re-sent within a quarter second of the last
    /// one. The records until the new clock passes the old one are dropped — a loss
    /// bounded by the threshold — and the stream then continues.
    @Test
    fun aStartResentWithinTheThresholdCostsAtMostTheThresholdOfRecords() {
        val decoder = decoder()
        decoder.ingest(tareFrame) // clock at 32
        val loaded = zero + 810_000
        val first = decoder.ingest(frame(records(36u, repeated(loaded)))) // 36…68
        assertEquals(9, first.size)
        // A second Start 70 ms in: the clock restarts, but 68 − 4 is not a quarter second.
        val overlap = decoder.ingest(frame(records(4u, repeated(loaded)))) // 4…36
        assertEquals(0, overlap.size, "records behind the old clock are dropped")
        val straddling = decoder.ingest(frame(records(40u, repeated(loaded)))) // 40…72
        assertEquals(
            listOf(72_000u), straddling.map { it.deviceMicros },
            "only the record past the old clock survives",
        )
        val resumed = decoder.ingest(frame(records(76u, repeated(loaded)))) // 76…108
        assertEquals(9, resumed.size, "the stream continues once the new clock passes the old one")
    }

    /// Milliseconds × 1000 wraps at the same ~71.6 minutes the Tindeq's µs clock does,
    /// and every consumer already subtracts with wrapping subtraction.
    @Test
    fun deviceMillisecondsBecomeWrappingMicros() {
        assertEquals(36_000u, FrezDynoCodec.deviceMicros(36u))
        assertEquals(4_294_960_000u, FrezDynoCodec.deviceMicros(4_294_960u))
        assertEquals(24_704u, FrezDynoCodec.deviceMicros(4_294_992u))
        val before = ForceSample(kg = 0.0, deviceMicros = FrezDynoCodec.deviceMicros(4_294_960u))
        val after = ForceSample(kg = 0.0, deviceMicros = FrezDynoCodec.deviceMicros(4_294_992u))
        assertEquals(32_000u, after.microsSince(before), "a delta across the wrap is still 32 ms")
    }

    // MARK: - Wiring facts

    @Test
    fun theProfileSubscribesThenWritesTheTwoByteStartAndStop() {
        val profile = FrezDynoCodec.profile
        assertEquals("DA8A6C41-154B-4B9A-9B00-2F84DFCEBFE9", profile.serviceUUID)
        assertEquals("DA8A6C42-154B-4B9A-9B00-2F84DFCEBFE9", profile.notifyCharacteristicUUID)
        assertEquals("DA8A6C43-154B-4B9A-9B00-2F84DFCEBFE9", profile.writeCharacteristicUUID)
        assertEquals(1, profile.streamStartPayloads.size)
        assertBytesEqual(bytes(0x01, 0x00), profile.streamStartPayloads.first())
        assertBytesEqual(bytes(0x02, 0x00), assertNotNull(profile.streamStopPayload))
        assertTrue(profile.oneTimeSetupPayloads.isEmpty())
        assertNull(
            profile.tareCharacteristicUUID,
            "the zero is arithmetic, never a device write",
        )
        assertBytesEqual(bytes(0xFF, 0x00), FrezDynoCodec.Command.powerOff)
    }
}
