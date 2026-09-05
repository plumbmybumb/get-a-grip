// SPDX-License-Identifier: MPL-2.0
// Original contributions Copyright 2026 Nuri Bruner.

package run.nuri.getagrip.engine

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

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
///
/// Translated from Tests/SimpleGaugeCodecTests.swift.
class SimpleGaugeCodecTests {

    private val noReadings = emptyList<GaugeReading>()

    // MARK: - WH-C06 (broadcast advertisement)

    /// A full-shape advertisement, company-ID prefix included, exactly as
    /// CoreBluetooth hands it over.
    private fun whc06Advertisement(
        weightRaw: Int,
        status: Int = 0x00,
        companyID: Int = 0x0100,
        extraTrailingBytes: Int = 0,
    ): ByteArray {
        val raw = IntArray(17 + extraTrailingBytes)
        raw[0] = companyID and 0xFF          // little-endian company ID
        raw[1] = (companyID shr 8) and 0xFF
        raw[12] = (weightRaw shr 8) and 0xFF // big-endian weight
        raw[13] = weightRaw and 0xFF
        raw[16] = status
        return ByteArray(raw.size) { raw[it].toByte() }
    }

    /// The reference indexes the payload AFTER the 2-byte company ID (weight at 10),
    /// so on iOS the same field sits at 12. 0x0A28 = 2600 → 26.00 kg.
    @Test
    fun whc06WeightIsBigEndianHundredthsAtTheIOSPrefixedOffset() {
        val frame = bytes(
            0x00, 0x01,                                     // company 0x0100, LE
            0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00,
            0x0A, 0x28,                                     // weight 2600
            0x00, 0x00,
            0x00,                                           // status
        )
        assertEquals(26.00, WHC06Codec.kilogramsFromManufacturerData(frame))
    }

    /// The same two bytes the other way round: 0x280A = 10250 → 102.50 kg. Both
    /// readings are loads a 300 kg crane scale could plausibly report, which is why
    /// nothing but a fixture catches this.
    @Test
    fun whc06ReversedWeightBytesAreADifferentPlausibleLoad() {
        val reversed = whc06Advertisement(weightRaw = 0x280A)
        assertEquals(102.50, WHC06Codec.kilogramsFromManufacturerData(reversed))
    }

    /// 0x0100 is shared with TomTom, so the ID alone is not a filter — but a frame
    /// carrying somebody else's ID is not this scale's frame at all.
    @Test
    fun whc06RejectsAnotherMakersCompanyID() {
        val apple = whc06Advertisement(weightRaw = 2600, companyID = 0x004C)
        assertNull(WHC06Codec.kilogramsFromManufacturerData(apple))
        assertNull(WHC06Codec.statusFromManufacturerData(apple))
    }

    /// A frame is required to reach through the WEIGHT field and no further. Anything
    /// shorter cannot be read at all; anything longer is padding.
    ///
    /// The old floor was 17 — the whole documented shape, through a status byte the
    /// reference itself leaves unread — and a real unit stopping one byte short of it
    /// would have been refused, reaching the user as a scale that never connects. That is
    /// the undiagnosable silence the codec's own rule forbids.
    @Test
    fun whc06RequiresOnlyTheBytesItActuallyReads() {
        val whole = whc06Advertisement(weightRaw = 2600, status = 0xA3)
        for (length in 0 until WHC06Codec.minimumFrameLength) {
            assertNull(
                WHC06Codec.kilogramsFromManufacturerData(whole.copyOfRange(0, length)),
                "a $length-byte advertisement stops short of the weight field",
            )
        }
        assertEquals(
            WHC06Codec.weightOffset + 2,
            WHC06Codec.minimumFrameLength,
            "the floor is the weight field, not the documented tail",
        )
        assertEquals(26.00, WHC06Codec.kilogramsFromManufacturerData(whole.copyOfRange(0, 14)))
        assertEquals(26.00, WHC06Codec.kilogramsFromManufacturerData(whole))
    }

    /// The status byte is read opportunistically, so a frame that ends before it yields a
    /// weight and no status — rather than nothing at all.
    @Test
    fun whc06StatusIsAbsentRatherThanFatalOnAShortFrame() {
        val short = whc06Advertisement(weightRaw = 2600, status = 0xA3).copyOfRange(0, 14)
        assertEquals(26.00, WHC06Codec.kilogramsFromManufacturerData(short))
        assertNull(WHC06Codec.statusFromManufacturerData(short))
    }

    /// Advertisements are padded and vendors append; extra trailing bytes are not a
    /// reason to refuse a frame whose documented fields are all present.
    @Test
    fun whc06AcceptsAFrameLongerThanTheDocumentedShape() {
        val padded = whc06Advertisement(weightRaw = 2600, extraTrailingBytes = 6)
        assertEquals(26.00, WHC06Codec.kilogramsFromManufacturerData(padded))
    }

    /// The raw field can express 655.35 kg on a 300 kg load cell, so the impossible
    /// range is rejected at the codec choke point rather than reaching the runner.
    @Test
    fun whc06RejectsLoadsBeyondTheScalesCapacity() {
        // 30000 → 300.00 kg (the rating itself), 30001 → 300.01 kg (one hundredth past).
        assertEquals(
            300.00,
            WHC06Codec.kilogramsFromManufacturerData(whc06Advertisement(weightRaw = 30_000)),
        )
        assertNull(WHC06Codec.kilogramsFromManufacturerData(whc06Advertisement(weightRaw = 30_001)))
        assertNull(WHC06Codec.kilogramsFromManufacturerData(whc06Advertisement(weightRaw = 0xFFFF)))
    }

    /// An idle scale reports 0.00 kg every advertisement. Reading that as "no frame"
    /// would make a hanging, switched-on scale look disconnected after ten seconds.
    @Test
    fun whc06ZeroIsAReadingNotAnAbsentFrame() {
        assertEquals(
            0.0,
            WHC06Codec.kilogramsFromManufacturerData(whc06Advertisement(weightRaw = 0)),
        )
    }

    /// High nibble stability, low nibble unit — parsed, unused, and mapped to nothing
    /// because the reference leaves this read commented out with no table for either.
    @Test
    fun whc06StatusByteSplitsIntoStabilityAndUnitNibbles() {
        val frame = whc06Advertisement(weightRaw = 2600, status = 0xA3)
        val status = WHC06Codec.statusFromManufacturerData(frame)
        assertEquals(0x0A, status?.stability)
        assertEquals(0x03, status?.unit)
    }

    @Test
    fun whc06ConstantsMatchTheReference() {
        assertEquals(0x0100, WHC06Codec.companyID)
        assertEquals(10.0, WHC06Codec.advertisementSilenceSeconds)
        assertEquals(12, WHC06Codec.weightOffset, "reference offset 10 plus the company-ID prefix")
        assertEquals(16, WHC06Codec.statusOffset, "reference offset 14 plus the company-ID prefix")
        assertEquals(
            14,
            WHC06Codec.minimumFrameLength,
            "the reference's own required offset is 11, i.e. 13 with the prefix",
        )
    }

    // MARK: - Entralpi (one reading per notification)

    /// 0x09C4 = 2500 → 25.00 kg.
    @Test
    fun entralpiWeightIsBigEndianHundredthsOfAKilogram() {
        val decoder = EntralpiCodec.Decoder()
        assertEquals(listOf(GaugeReading(kg = 25.0)), decoder.ingest(bytes(0x09, 0xC4)))
    }

    /// The same bytes reversed: 0xC409 = 50185 → 501.85 kg.
    @Test
    fun entralpiReversedBytesAreADifferentLoad() {
        val decoder = EntralpiCodec.Decoder()
        val readings = decoder.ingest(bytes(0xC4, 0x09))
        assertEquals(1, readings.size)
        assertEquals(501.85, readings.firstOrNull()?.kg ?: 0.0, 0.0001)
    }

    /// The reference runs the value through `toFixed(1)` — a string round-trip that
    /// quantises the device's own 0.01 kg resolution to 0.1 kg before the release
    /// band, the target-band gate or a recorded max ever sees it. We keep the
    /// resolution the device sent; 0x09C5 = 2501 → 25.01 kg, not 25.0.
    @Test
    fun entralpiKeepsHundredthResolutionRatherThanTheReferencesDisplayRounding() {
        val decoder = EntralpiCodec.Decoder()
        val readings = decoder.ingest(bytes(0x09, 0xC5))
        assertEquals(25.01, readings.firstOrNull()?.kg ?: 0.0, 0.0001)
        assertNotEquals(25.0, readings.firstOrNull()?.kg)
    }

    /// One notification is one sample: the reference reads offset 0 and nothing else,
    /// so a longer notification is not evidence of samples we are dropping.
    @Test
    fun entralpiIgnoresEverythingPastTheFirstTwoBytes() {
        val decoder = EntralpiCodec.Decoder()
        val padded = bytes(0x09, 0xC4, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF)
        assertEquals(listOf(GaugeReading(kg = 25.0)), decoder.ingest(padded))
    }

    @Test
    fun entralpiShortNotificationDecodesToNothing() {
        val decoder = EntralpiCodec.Decoder()
        assertEquals(noReadings, decoder.ingest(ByteArray(0)))
        assertEquals(noReadings, decoder.ingest(bytes(0x09)))
    }

    @Test
    fun entralpiZeroIsAReading() {
        val decoder = EntralpiCodec.Decoder()
        assertEquals(listOf(GaugeReading(kg = 0.0)), decoder.ingest(bytes(0x00, 0x00)))
    }

    /// The codec must never invent a timestamp: this device stamps nothing, so the
    /// client applies `SyntheticSampleClock` and the runner clamps per-sample credit.
    @Test
    fun entralpiReadingsCarryNoDeviceClock() {
        val decoder = EntralpiCodec.Decoder()
        assertNull(decoder.ingest(bytes(0x09, 0xC4)).firstOrNull()?.deviceMicros)
    }

    /// Battery comes from the standard Battery Service the client reads at connect,
    /// not from the stream — only Climbro carries it in-band.
    @Test
    fun entralpiDecoderPublishesNoInStreamBattery() {
        val decoder = EntralpiCodec.Decoder()
        decoder.ingest(bytes(0x09, 0xC4))
        assertNull(decoder.batteryFraction)
    }

    /// Subscribing IS the start: the reference writes nothing to this device
    /// anywhere, and it has no hardware tare.
    @Test
    fun entralpiProfileNeedsNoWritesAtAll() {
        assertEquals("0000FFF0-0000-1000-8000-00805F9B34FB", EntralpiCodec.profile.serviceUUID)
        assertEquals(
            "0000FFF4-0000-1000-8000-00805F9B34FB",
            EntralpiCodec.profile.notifyCharacteristicUUID,
        )
        assertNull(EntralpiCodec.profile.writeCharacteristicUUID)
        assertTrue(EntralpiCodec.profile.streamStartPayloads.isEmpty())
        assertNull(EntralpiCodec.profile.streamStopPayload)
        assertNull(EntralpiCodec.profile.tareCharacteristicUUID)
        assertNull(EntralpiCodec.profile.tarePayload)
    }

    /// **The reference subscribes to every characteristic it marks "rx" and never had to
    /// choose**: 0000FFF4 under 0000FFF0 and 0000FFF1 under 0000181D. Picking one would be
    /// a coin flip the source cannot settle, and picking wrong is a device that connects
    /// and never delivers a byte — so the profile carries both and the client subscribes
    /// to both. The same decoder parses whichever one speaks.
    @Test
    fun entralpiProfileCarriesBothCharacteristicsTheReferenceSubscribesTo() {
        assertEquals(
            listOf("0000FFF1-0000-1000-8000-00805F9B34FB"),
            EntralpiCodec.profile.alternateNotifyCharacteristicUUIDs,
        )
        assertNotEquals(
            EntralpiCodec.profile.notifyCharacteristicUUID,
            EntralpiCodec.profile.alternateNotifyCharacteristicUUIDs.firstOrNull(),
            "an alternate that repeats the primary subscribes twice to one handle",
        )
    }

    /// The plausibility window the CTS500 and the Force Board both carry, for the reason
    /// this protocol makes plainest: no checksum, no framing, and two arbitrary bytes are
    /// enough to set a session peak and a recorded max.
    ///
    /// **Nothing the documented field can express reaches it** — 0xFFFF is 655.35 kg — so
    /// the highest possible reading still decodes. That is deliberate: the ceiling is the
    /// backstop for a scaling surprise (a widened read, a different divisor), not a filter
    /// that fires on today's bytes.
    @Test
    fun entralpiCarriesACeilingAboveEverythingTheFieldCanExpress() {
        val decoder = EntralpiCodec.Decoder()
        val readings = decoder.ingest(bytes(0xFF, 0xFF))
        assertEquals(655.35, readings.firstOrNull()?.kg ?: 0.0, 0.0001)

        assertEquals(907.0, EntralpiCodec.maxPlausibleKilograms, "2000 lb, as the Force Board")
        assertTrue(EntralpiCodec.maxPlausibleKilograms > 655.35)
    }

    // MARK: - PB-700BT (rotation, not force)

    private fun pb700Notification(period: Long, sampleIndex: Long = 0): ByteArray {
        fun bigEndian(value: Long): ByteArray = bytes(
            ((value shr 24) and 0xFF).toInt(),
            ((value shr 16) and 0xFF).toInt(),
            ((value shr 8) and 0xFF).toInt(),
            (value and 0xFF).toInt(),
        )
        return bigEndian(period) + bigEndian(sampleIndex)
    }

    /// period 10000 ticks → 60 × 666666 / 10000 = 3999.996 → 4000 RPM.
    @Test
    fun pb700BTPeriodDecodesAsBigEndianRPM() {
        val frame = bytes(
            0x00, 0x00, 0x27, 0x10,   // period 10000, big-endian
            0x00, 0x00, 0x00, 0x00,
        )
        assertEquals(4000.0, PB700BTCodec.revolutionsPerMinute(frame))
    }

    /// The same four bytes reversed are 0x10270000 = 271,581,184 ticks → 0.15 RPM,
    /// which the plausibility band refuses. Byte order is load-bearing even for a
    /// value nothing consumes.
    @Test
    fun pb700BTReversedPeriodBytesFallOutsideThePlausibleBand() {
        val reversed = bytes(
            0x10, 0x27, 0x00, 0x00,
            0x00, 0x00, 0x00, 0x00,
        )
        assertNull(PB700BTCodec.revolutionsPerMinute(reversed))
    }

    /// Refused before the division, not after: `60 * (x / 0)` is `.infinity`, and
    /// leaning on the band to catch that leaves a divide-by-zero one edit away from
    /// being the answer.
    @Test
    fun pb700BTRejectsAZeroPeriod() {
        assertNull(PB700BTCodec.revolutionsPerMinute(pb700Notification(period = 0)))
    }

    /// The reference's own 800…15000 window, asserted at both edges.
    ///   period 49999 → 39,999,960 / 49,999 =   800.02 RPM  (inside)
    ///   period 50000 → 39,999,960 / 50,000 =   799.99 RPM  (outside)
    ///   period  2667 → 39,999,960 /  2,667 = 14998.11 RPM  (inside)
    ///   period  2666 → 39,999,960 /  2,666 = 15003.74 RPM  (outside)
    @Test
    fun pb700BTRejectsRotationSpeedsOutsideThePlausibleBand() {
        assertEquals(800.0, PB700BTCodec.revolutionsPerMinute(pb700Notification(period = 49_999)))
        assertNull(PB700BTCodec.revolutionsPerMinute(pb700Notification(period = 50_000)))
        assertEquals(
            14_998.0,
            PB700BTCodec.revolutionsPerMinute(pb700Notification(period = 2_667)),
        )
        assertNull(PB700BTCodec.revolutionsPerMinute(pb700Notification(period = 2_666)))
    }

    /// Eight bytes are required even though the period occupies four: the reference
    /// reads offset 4 unconditionally, so in a browser a shorter read throws out of
    /// the handler and yields nothing. A four-byte frame carrying a perfectly good
    /// period is still refused, deliberately.
    @Test
    fun pb700BTTruncatedNotificationDecodesToNothing() {
        val whole = pb700Notification(period = 10_000, sampleIndex = 7)
        for (length in 0 until 8) {
            assertNull(
                PB700BTCodec.revolutionsPerMinute(whole.copyOfRange(0, length)),
                "a $length-byte notification is not a whole frame",
            )
            assertNull(PB700BTCodec.sampleIndex(whole.copyOfRange(0, length)))
        }
    }

    /// 0x01020304 = 16,909,060, big-endian at offset 4.
    @Test
    fun pb700BTSampleIndexIsBigEndianAtOffsetFour() {
        val frame = bytes(
            0x00, 0x00, 0x27, 0x10,
            0x01, 0x02, 0x03, 0x04,
        )
        assertEquals(16_909_060u, PB700BTCodec.sampleIndex(frame))
    }

    /// The headline fact about this device: it is a gyroscopic hand exerciser, so its
    /// stream carries revolutions per minute and there is no force channel to port.
    /// The decoder understands every frame and yields no readings — four-figure RPM
    /// arriving in a kilogram channel would arm every rep instantly and write a
    /// five-figure "max" that then sets the percentage targets for that grip.
    @Test
    fun pb700BTDecodesEveryFrameAndYieldsNoForceReadings() {
        val decoder = PB700BTCodec.Decoder()
        assertNull(decoder.lastRPM)

        assertEquals(noReadings, decoder.ingest(pb700Notification(period = 10_000)))
        assertEquals(4000.0, decoder.lastRPM, "the frame is decoded, just not reported as load")

        // A refused frame leaves the last good reading alone rather than blanking it.
        assertEquals(noReadings, decoder.ingest(pb700Notification(period = 0)))
        assertEquals(4000.0, decoder.lastRPM)
    }

    @Test
    fun pb700BTProfileUsesTheOnlyCharacteristicTheReferenceSubscribesTo() {
        assertEquals("0000FFF0-0000-1000-8000-00805F9B34FB", PB700BTCodec.profile.serviceUUID)
        assertEquals(
            "0000FFF4-0000-1000-8000-00805F9B34FB",
            PB700BTCodec.profile.notifyCharacteristicUUID,
        )
        assertTrue(
            PB700BTCodec.profile.streamStartPayloads.isEmpty(),
            "the reference writes nothing to this device",
        )
        assertNull(PB700BTCodec.profile.tareCharacteristicUUID)
    }

    // MARK: - Registry wiring

    /// One codec file plus one row in each switch — this is the test that says the
    /// rows point at the right codec, so a copy-paste in the registry cannot hand the
    /// Entralpi's bytes to another device's decoder.
    @Test
    fun registryWiresEachOfTheseKindsToItsOwnCodec() {
        assertEquals(EntralpiCodec.profile, GaugeKind.entralpi.gatt)
        assertEquals(PB700BTCodec.profile, GaugeKind.pb700bt.gatt)

        val entralpi = assertNotNull(
            GaugeKind.entralpi.makeFrameDecoder(),
            "both kinds are GATT-connected and must build a decoder",
        )
        val pb700bt = assertNotNull(
            GaugeKind.pb700bt.makeFrameDecoder(),
            "both kinds are GATT-connected and must build a decoder",
        )
        assertTrue(entralpi is EntralpiCodec.Decoder)
        assertTrue(pb700bt is PB700BTCodec.Decoder)
    }

    /// The WH-C06 has nothing to connect to and nothing to reassemble: weight arrives
    /// in advertisements, which is a different client, not a different profile.
    @Test
    fun theBroadcastScaleHasNeitherProfileNorDecoder() {
        assertNull(GaugeKind.whc06.gatt)
        assertNull(GaugeKind.whc06.makeFrameDecoder())
        assertTrue(GaugeKind.whc06.capabilities.isBroadcast)
        assertFalse(
            GaugeKind.whc06.capabilities.sustainsBackgroundStreaming,
            "duplicate advertisements coalesce in the background",
        )
    }

    /// Both connected devices here declare the standard Battery Service (0x180F /
    /// 0x2A19) in the reference, which is what `hasStandardBattery` promises the
    /// client it can read at connect.
    @Test
    fun bothConnectedKindsClaimTheStandardBatteryServiceTheReferenceDeclares() {
        assertTrue(GaugeKind.entralpi.capabilities.hasStandardBattery)
        assertTrue(GaugeKind.pb700bt.capabilities.hasStandardBattery)
    }

    /// Neither has a tare of its own — the reference sends no tare command to either,
    /// so the Tare button has to mean a captured app-side baseline.
    @Test
    fun neitherSimpleGaugeClaimsAHardwareTare() {
        assertFalse(GaugeKind.entralpi.capabilities.hasHardwareTare)
        assertFalse(GaugeKind.pb700bt.capabilities.hasHardwareTare)
        assertFalse(GaugeKind.whc06.capabilities.hasHardwareTare)
    }
}
