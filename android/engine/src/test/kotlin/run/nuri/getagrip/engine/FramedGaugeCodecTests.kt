// SPDX-License-Identifier: MPL-2.0
// Original contributions Copyright 2026 Nuri Bruner.

package run.nuri.getagrip.engine

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

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
///
/// Translated from Tests/FramedGaugeCodecTests.swift.
class FramedGaugeCodecTests {

    private val noReadings = emptyList<GaugeReading>()

    // MARK: - Climbro

    /// The reference's own fixture: `[0xF0, 112, 0xF5, 10, 0xF6, 20]` — battery
    /// marker, an empty-battery byte, sensor marker, 10 kg, the 36 kg reserved word,
    /// 20 kg. Its test asserts battery "0" and currents 10, 36, 20.
    @Test
    fun climbroDecodesTheReferenceMarkerFixture() {
        val decoder = ClimbroCodec.Decoder()
        val readings = decoder.ingest(bytes(0xF0, 112, 0xF5, 10, 0xF6, 20))

        assertEquals(
            listOf(GaugeReading(kg = 10.0), GaugeReading(kg = 36.0), GaugeReading(kg = 20.0)),
            readings,
        )
        // 112 is `minBatteryDisc`, so exactly empty.
        assertEquals(0.0, decoder.batteryFraction)
    }

    /// The battery coefficient is `100 / (230 - 112)`. Halfway is byte 171:
    /// (171 − 112) / 118 = 0.5.
    @Test
    fun climbroBatteryUsesTheReferenceDischargeSpan() {
        val decoder = ClimbroCodec.Decoder()
        decoder.ingest(bytes(0xF0, 171))
        assertEquals(0.5, assertNotNull(decoder.batteryFraction), 0.0001)

        decoder.ingest(bytes(230))
        assertEquals(1.0, assertNotNull(decoder.batteryFraction), 0.0001)
    }

    /// The reference reports −94 % for a battery byte of 0 (it never clamps). A
    /// fraction is drawn as a ring, so ours saturates at both ends instead.
    @Test
    fun climbroBatteryFractionClampsWhereTheReferenceGoesNegative() {
        val decoder = ClimbroCodec.Decoder()
        decoder.ingest(bytes(0xF0, 0))
        assertEquals(0.0, decoder.batteryFraction)

        decoder.ingest(bytes(255))
        assertEquals(1.0, decoder.batteryFraction)
    }

    /// There is no framing on Microchip Transparent UART, so the channel a byte
    /// belongs to is state that MUST survive the notification boundary — the radio
    /// splits the stream wherever it likes.
    @Test
    fun climbroChannelSurvivesTheNotificationBoundary() {
        val decoder = ClimbroCodec.Decoder()
        assertEquals(noReadings, decoder.ingest(bytes(0xF5)))
        assertEquals(
            listOf(GaugeReading(kg = 12.0), GaugeReading(kg = 13.0)),
            decoder.ingest(bytes(12, 13)),
        )
    }

    /// Bytes arriving before any marker are dropped, not guessed at: the reference
    /// initialises its flag to 0, which is neither channel.
    @Test
    fun climbroDropsBytesArrivingBeforeAnyMarker() {
        val decoder = ClimbroCodec.Decoder()
        assertEquals(listOf(GaugeReading(kg = 7.0)), decoder.ingest(bytes(5, 6, 0xF5, 7)))
        assertNull(decoder.batteryFraction)
    }

    @Test
    fun climbroBatteryBytesAreNeverForceReadings() {
        val decoder = ClimbroCodec.Decoder()
        assertEquals(noReadings, decoder.ingest(bytes(0xF0, 200, 150)))
        // (150 − 112) / 118 — the LAST battery byte wins.
        assertEquals(38.0 / 118.0, assertNotNull(decoder.batteryFraction), 0.0001)
    }

    @Test
    fun climbroEmptyNotificationDecodesToNothing() {
        val decoder = ClimbroCodec.Decoder()
        assertEquals(noReadings, decoder.ingest(ByteArray(0)))
    }

    /// Subscribing is the whole start sequence — the reference defines no commands
    /// for this device at all. A payload appearing here later would be a change in
    /// what we believe about the protocol, not a tidy-up.
    @Test
    fun climbroProfileStartsBySubscribingAlone() {
        assertTrue(ClimbroCodec.profile.streamStartPayloads.isEmpty())
        assertNull(ClimbroCodec.profile.streamStopPayload)
        assertNull(ClimbroCodec.profile.tareCharacteristicUUID)
        assertEquals(
            "49535343-1E4D-4BD9-BA61-23C647249616",
            ClimbroCodec.profile.notifyCharacteristicUUID,
        )
        assertEquals(
            "49535343-8841-43F4-A8D4-ECBE34729BB3",
            ClimbroCodec.profile.writeCharacteristicUUID,
        )
    }

    // MARK: - Force Board

    /// The reference's own fixture, `forceBoardPacket([1000, 1200])`, asserting two
    /// readings of 1000 and 1200 POUNDS.
    ///
    /// Header 00 02 = two samples, big-endian. 1000 = 0·32768 + 3·256 + 232, so the
    /// sample is 00 03 E8; 1200 = 0·32768 + 4·256 + 176 → 00 04 B0. In kilograms:
    /// 1000 × 0.45359237 = 453.59237 and 1200 × 0.45359237 = 544.310844.
    @Test
    fun forceBoardDecodesTheReferenceTwoSamplePacketInKilograms() {
        val decoder = ForceBoardCodec.Decoder()
        val readings = decoder.ingest(
            bytes(
                0x00, 0x02,
                0x00, 0x03, 0xE8,
                0x00, 0x04, 0xB0,
            ),
        )

        assertEquals(2, readings.size)
        assertEquals(453.59237, readings[0].kg, 0.00001)
        assertEquals(544.310844, readings[1].kg, 0.00001)
        // A synthetic stamp is the client's job; codecs stay pure.
        assertNull(readings[0].deviceMicros)
    }

    /// The top byte is worth 2^15, NOT 2^16. 40000 lbs packs as 01 1C 40
    /// (1·32768 + 28·256 + 64); a 24-bit read of the same bytes gives 0x011C40 =
    /// 72768, i.e. 80 % too heavy, with no crash to point at it.
    @Test
    fun forceBoardSampleHighByteIsWorthThirtyTwoThousandSevenHundredSixtyEight() {
        assertEquals(40000.0, ForceBoardCodec.pounds(0x01, 0x1C, 0x40))
        assertEquals(1000.0, ForceBoardCodec.pounds(0x00, 0x03, 0xE8))
        assertEquals(42.0, ForceBoardCodec.pounds(0x00, 0x00, 0x2A))
    }

    /// A count the packet cannot honour must stop the walk, not read past the end.
    @Test
    fun forceBoardPacketClaimingMoreSamplesThanItCarriesStopsCleanly() {
        val decoder = ForceBoardCodec.Decoder()
        val readings = decoder.ingest(
            bytes(
                0x00, 0x03,
                0x00, 0x00, 0x0A,
                0x00, 0x00, 0x14,
                0x00, 0x00,
            ),
        ) // third sample cut short

        assertEquals(2, readings.size)
        assertEquals(10 * ForceBoardCodec.poundsToKilograms, readings[0].kg, 0.00001)
        assertEquals(20 * ForceBoardCodec.poundsToKilograms, readings[1].kg, 0.00001)
    }

    @Test
    fun forceBoardShortAndEmptyPacketsDecodeToNothing() {
        val decoder = ForceBoardCodec.Decoder()
        assertEquals(noReadings, decoder.ingest(ByteArray(0)))
        assertEquals(noReadings, decoder.ingest(bytes(0x00)))
        assertEquals(noReadings, decoder.ingest(bytes(0x00, 0x01)))                    // header only
        assertEquals(noReadings, decoder.ingest(bytes(0x00, 0x01, 0x00, 0x00)))        // half a sample
        assertEquals(noReadings, decoder.ingest(bytes(0x00, 0x00, 0x00, 0x00, 0x00)))  // zero samples
    }

    /// The one device whose write characteristic is NOT in the service it streams from —
    /// wiring the generic client depends on and the registry cannot express.
    @Test
    fun forceBoardProfileNamesBothServices() {
        assertEquals("9A88D67F-8DF2-4AFE-9E0D-C2BBBE773DD0", ForceBoardCodec.profile.serviceUUID)
        assertEquals(
            "9A88D682-8DF2-4AFE-9E0D-C2BBBE773DD0",
            ForceBoardCodec.profile.notifyCharacteristicUUID,
        )
        assertEquals(
            "467A8517-6E39-11EB-9439-0242AC130002",
            ForceBoardCodec.profile.writeCharacteristicUUID,
        )
        assertEquals("467A8516-6E39-11EB-9439-0242AC130002", ForceBoardCodec.deviceModeServiceUUID)
        assertEquals(1, ForceBoardCodec.profile.streamStartPayloads.size)
        assertBytesEqual(bytes(0x04), ForceBoardCodec.profile.streamStartPayloads[0])
        assertBytesEqual(bytes(0x07), ForceBoardCodec.profile.streamStopPayload)
    }

    /// **The board's tare bytes are recorded and deliberately NOT in the profile.**
    ///
    /// In the reference that write lives only in `tareByCharacteristic`, which its own tare
    /// path never calls — ForceBoard does not override `tare()`, so its shipped behaviour is
    /// the base class's software tare. Naming the characteristic here would REPLACE the
    /// working app-side tare with an unexercised write (`GattGaugeClient` treats the two as
    /// mutually exclusive), and if those bytes are wrong on real firmware Tare becomes a
    /// silent no-op on a device this project has never held.
    @Test
    fun forceBoardKeepsItsUnexercisedDeviceTareOutOfTheProfile() {
        assertNull(ForceBoardCodec.profile.tareCharacteristicUUID)
        assertNull(ForceBoardCodec.profile.tarePayload)
        assertFalse(
            GaugeKind.forceboard.capabilities.hasHardwareTare,
            "the software tare is what ships until hardware confirms the write",
        )

        // Kept as constants so a hardware session has the bytes to try.
        assertEquals("9A88D683-8DF2-4AFE-9E0D-C2BBBE773DD0", ForceBoardCodec.tareCharacteristicUUID)
        assertBytesEqual(bytes(0x01), ForceBoardCodec.tarePayload)
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
    @Test
    fun forceBoardDropsSamplesNoBoardCouldProduceAndKeepsTheirNeighbours() {
        val decoder = ForceBoardCodec.Decoder()
        // 42 lb, then the largest a 3-byte sample can express, then 42 lb again.
        val readings = decoder.ingest(
            bytes(
                0x00, 0x03,
                0x00, 0x00, 0x2A,
                0xFF, 0xFF, 0xFF,
                0x00, 0x00, 0x2A,
            ),
        )

        assertEquals(2, readings.size, "the absurd sample is dropped, the pulls are not")
        for (reading in readings) {
            assertEquals(42 * ForceBoardCodec.poundsToKilograms, reading.kg, 0.00001)
        }

        // The boundary itself: 1999 lb = 906.73 kg is inside, 2000 lb = 907.18 is not.
        assertEquals(907.0, ForceBoardCodec.maxPlausibleKilograms)
        val edge = ForceBoardCodec.Decoder()
        // 1999 = 0·32768 + 7·256 + 207 → 00 07 CF; 2000 → 00 07 D0.
        assertEquals(1, edge.ingest(bytes(0x00, 0x01, 0x00, 0x07, 0xCF)).size)
        assertEquals(noReadings, edge.ingest(bytes(0x00, 0x01, 0x00, 0x07, 0xD0)))
    }

    // MARK: - Motherboard

    /// Calibration rows arrive as `slot,index,force,raw`. These are the rows the
    /// reference's own Motherboard tests install — an identity map from 0…100 raw
    /// counts to 0…100 kg — expressed as the lines a device would actually send.
    private fun motherboardCalibration(
        rows: List<Pair<Int, Int>> = listOf(0 to 0, 100 to 100),
        slots: Int = MotherboardCodec.calibrationSlotCount,
    ): ByteArray {
        val text = StringBuilder()
        for (slot in 0 until slots) {
            rows.forEachIndexed { index, row ->
                text.append("$slot,$index,${row.first},${row.second}\n")
            }
        }
        return text.toString().toByteArray(Charsets.UTF_8)
    }

    /// One hex packet line, built the way the reference's `motherboardPacket` helper
    /// builds it: uint16 LE index, uint16 LE battery, three 24-bit LE samples,
    /// three unused bytes, hex-encoded, LF-terminated.
    private fun motherboardPacket(
        samples: List<Int>,
        sampleIndex: Int = 1,
        batteryRaw: Int = 0x012C,
        lineEnding: String = "\n",
    ): ByteArray {
        val raw = IntArray(16)
        raw[0] = sampleIndex and 0xFF
        raw[1] = (sampleIndex shr 8) and 0xFF
        raw[2] = batteryRaw and 0xFF
        raw[3] = (batteryRaw shr 8) and 0xFF
        samples.forEachIndexed { index, sample ->
            var value = sample
            if (value < 0) value += 0x1000000
            val start = 4 + 3 * index
            raw[start] = value and 0xFF
            raw[start + 1] = (value shr 8) and 0xFF
            raw[start + 2] = (value shr 16) and 0xFF
        }
        val hex = raw.joinToString("") { "%02X".format(it) }
        return (hex + lineEnding).toByteArray(Charsets.UTF_8)
    }

    /// The reference's literal fixture line, with its own calibration table.
    ///
    /// `01002C010A0000ECFFFFE2FFFF000000` is index 1, battery 0x012C = 300, then
    /// samples 0x00000A = 10, 0xFFFFEC → −20 and 0xFFFFE2 → −30. Through the
    /// identity table that is 10, −20, −30; centre and right inverted give 10, 20,
    /// 30; the total is 60 — exactly what the reference asserts, distribution and
    /// all.
    @Test
    fun motherboardDecodesTheReferencePacketToItsSummedTotal() {
        val decoder = MotherboardCodec.Decoder()
        assertEquals(noReadings, decoder.ingest(motherboardCalibration()))
        assertTrue(decoder.hasCalibration)

        val readings =
            decoder.ingest("01002C010A0000ECFFFFE2FFFF000000\n".toByteArray(Charsets.UTF_8))

        assertEquals(1, readings.size)
        assertEquals(60.0, readings[0].kg, 0.0001)
        assertEquals(1, decoder.latestSampleIndex)
        assertEquals(300, decoder.latestBatteryRaw)
        // The battery field is raw and undocumented; this device's percentage comes
        // from the standard 0x180F service the client reads instead.
        assertNull(decoder.batteryFraction)
    }

    /// LEFT IS NOT INVERTED. A loop that negated all three zones would pass every
    /// symmetric fixture and get the sign of a real pull backwards.
    @Test
    fun motherboardInvertsCentreAndRightOnly() {
        val decoder = MotherboardCodec.Decoder()
        decoder.ingest(motherboardCalibration())

        val readings = decoder.ingest(motherboardPacket(samples = listOf(10, 0, 0)))
        assertEquals(1, readings.size)
        assertEquals(10.0, readings[0].kg, 0.0001)

        val inverted = decoder.ingest(motherboardPacket(samples = listOf(0, -10, -10)))
        assertEquals(1, inverted.size)
        assertEquals(20.0, inverted[0].kg, 0.0001)
    }

    /// A line arrives in as many notifications as the radio feels like using, and
    /// nothing may be emitted before its LF.
    @Test
    fun motherboardReassemblesOneLineAcrossThreeNotifications() {
        val decoder = MotherboardCodec.Decoder()
        decoder.ingest(motherboardCalibration())

        val line = motherboardPacket(samples = listOf(10, -20, -30))
        assertEquals(noReadings, decoder.ingest(line.copyOfRange(0, 7)))
        assertEquals(noReadings, decoder.ingest(line.copyOfRange(7, 20)))

        val readings = decoder.ingest(line.copyOfRange(20, line.size))
        assertEquals(1, readings.size)
        assertEquals(60.0, readings[0].kg, 0.0001)
    }

    /// CRLF must not reach the hex test — a trailing CR fails it on every packet.
    @Test
    fun motherboardStripsCarriageReturns() {
        val decoder = MotherboardCodec.Decoder()
        decoder.ingest(motherboardCalibration())

        val readings = decoder.ingest(
            motherboardPacket(samples = listOf(10, -20, -30), lineEnding = "\r\n"),
        )
        assertEquals(1, readings.size)
        assertEquals(60.0, readings[0].kg, 0.0001)
    }

    /// No calibration, no reading. The start sequence writes "C" before "S30" and the
    /// client waits `startPayloadDelaySeconds` between them, but a packet can still beat
    /// the table home — it is dropped rather than reported as a raw ADC count.
    @Test
    fun motherboardEmitsNothingUntilCalibrationArrives() {
        val decoder = MotherboardCodec.Decoder()
        assertFalse(decoder.hasCalibration)
        assertEquals(noReadings, decoder.ingest(motherboardPacket(samples = listOf(10, -20, -30))))

        decoder.ingest(motherboardCalibration())
        assertEquals(1, decoder.ingest(motherboardPacket(samples = listOf(10, -20, -30))).size)
    }

    /// A sample above the table's last raw count has no mapping. The reference
    /// returns 0 for it; 0 kg mid-pull would tell the runner the climber let go at
    /// the exact moment they pulled hardest, so the whole packet is dropped instead.
    @Test
    fun motherboardDropsAPacketWithASampleOffTheTopOfTheTable() {
        val decoder = MotherboardCodec.Decoder()
        decoder.ingest(motherboardCalibration())

        assertEquals(noReadings, decoder.ingest(motherboardPacket(samples = listOf(150, 0, 0))))
    }

    /// A zero-width calibration segment divides by zero. NaN kilograms would poison
    /// every peak and average downstream, so no reading is emitted at all.
    @Test
    fun motherboardDegenerateCalibrationSegmentYieldsNoReadingRatherThanNaN() {
        val decoder = MotherboardCodec.Decoder()
        // Both rows report raw count 0, so there is no interval to interpolate over.
        decoder.ingest(motherboardCalibration(rows = listOf(0 to 0, 50 to 0)))

        assertEquals(noReadings, decoder.ingest(motherboardPacket(samples = listOf(10, 0, 0))))
    }

    @Test
    fun motherboardIgnoresLinesThatAreNotPacketsOrCalibrationRows() {
        val decoder = MotherboardCodec.Decoder()
        decoder.ingest(motherboardCalibration())

        // The serial reply, a short hex line, an odd-length hex line, a row for a
        // slot that does not exist: all ignored, none of them fatal.
        assertEquals(noReadings, decoder.ingest("1234-5678-ABCD\n".toByteArray(Charsets.UTF_8)))
        assertEquals(noReadings, decoder.ingest("01002C010A00\n".toByteArray(Charsets.UTF_8)))
        assertEquals(
            noReadings,
            decoder.ingest("01002C010A0000ECFFFFE2FFFF00000\n".toByteArray(Charsets.UTF_8)),
        )
        assertEquals(noReadings, decoder.ingest("9,1,100,100\n".toByteArray(Charsets.UTF_8)))
        assertEquals(noReadings, decoder.ingest("not,four,numbers,here\n".toByteArray(Charsets.UTF_8)))

        // …and the next real packet still decodes.
        assertEquals(1, decoder.ingest(motherboardPacket(samples = listOf(10, -20, -30))).size)
    }

    /// The slot is an INDEX parsed out of untrusted text. "NaN", "Infinity" and "1e30" all
    /// parse; `toInt()` saturates on each and the equality guard drops the row — the same
    /// rows Swift's `Int(exactly:)` drops, so the two engines agree. Whether the table is
    /// still empty or already in force, a bad row neither lands in a slot nor clears one.
    @Test
    fun motherboardDropsACalibrationRowWhoseSlotIsNotAnExactInRangeInteger() {
        val decoder = MotherboardCodec.Decoder()
        val badRows = listOf(
            "NaN,0,0,0\n", "nan,0,0,0\n", "1e30,0,0,0\n", "Infinity,0,0,0\n", "-Infinity,0,0,0\n",
            "inf,0,0,0\n", "-1,0,0,0\n", "0.5,0,0,0\n", "4,0,0,0\n",
        )
        val empty = List(MotherboardCodec.calibrationSlotCount) { 0 }

        for (line in badRows) {
            assertEquals(noReadings, decoder.ingest(line.toByteArray(Charsets.UTF_8)), line)
            assertEquals(empty, decoder.calibrationRowCounts, "$line must not land in any slot")
        }
        assertFalse(decoder.hasCalibration)

        // With a table in force, a bad row's index 0 must not clear a slot either.
        decoder.ingest(motherboardCalibration())
        val full = decoder.calibrationRowCounts
        for (line in badRows) {
            assertEquals(noReadings, decoder.ingest(line.toByteArray(Charsets.UTF_8)), line)
            assertEquals(full, decoder.calibrationRowCounts, "$line must not clear or extend a slot")
        }
        assertTrue(decoder.hasCalibration)
        assertEquals(
            60.0,
            decoder.ingest(motherboardPacket(samples = listOf(10, -20, -30))).firstOrNull()?.kg ?: 0.0,
            0.0001,
        )
    }

    /// A peer that never sends an LF would grow the buffer for the whole session.
    /// Past the cap the buffer is dropped, and the next whole line decodes.
    @Test
    fun motherboardDiscardsAnUnterminatedFloodAndKeepsDecodingAfterwards() {
        val decoder = MotherboardCodec.Decoder()
        decoder.ingest(motherboardCalibration())

        val flood = ByteArray(MotherboardCodec.maxBufferedBytes + 64) { 0x41 }
        assertEquals(noReadings, decoder.ingest(flood))

        val readings = decoder.ingest(motherboardPacket(samples = listOf(10, -20, -30)))
        assertEquals(1, readings.size)
        assertEquals(60.0, readings[0].kg, 0.0001)
    }

    /// **A dump REPLACES its slot.** "C" rides the start payloads, which the app re-sends
    /// on every re-kick — the silence watchdog, a tare recovery, coming back to the
    /// foreground — while the decoder lives for the whole link. Appending grew the table
    /// without bound and turned it into a non-monotonic concatenation that
    /// `applyCalibration` walks linearly three times per packet, so a session got steadily
    /// slower the longer it ran.
    @Test
    fun motherboardASecondCalibrationDumpReplacesTheTableRatherThanStackingOnIt() {
        val decoder = MotherboardCodec.Decoder()
        decoder.ingest(motherboardCalibration())
        val afterFirstDump = decoder.calibrationRowCounts
        val firstReading = decoder.ingest(motherboardPacket(samples = listOf(10, -20, -30)))

        decoder.ingest(motherboardCalibration())

        assertEquals(
            afterFirstDump,
            decoder.calibrationRowCounts,
            "the same dump twice is the same table, not two copies of it",
        )
        assertEquals(
            List(MotherboardCodec.calibrationSlotCount) { 2 },
            decoder.calibrationRowCounts,
        )
        assertTrue(decoder.hasCalibration)

        val secondReading = decoder.ingest(motherboardPacket(samples = listOf(10, -20, -30)))
        assertEquals(firstReading.size, secondReading.size)
        assertEquals(
            firstReading.firstOrNull()?.kg ?: -1.0,
            secondReading.firstOrNull()?.kg ?: 0.0,
            0.0001,
        )
    }

    /// A dump that has genuinely changed still lands: the slot is cleared by the arrival of
    /// its row 0, so the newest table is the one in force.
    @Test
    fun motherboardANewDumpSupersedesTheOldCalibration() {
        val decoder = MotherboardCodec.Decoder()
        decoder.ingest(motherboardCalibration())
        assertEquals(
            10.0,
            decoder.ingest(motherboardPacket(samples = listOf(10, 0, 0))).firstOrNull()?.kg ?: 0.0,
            0.0001,
        )

        // Half the force for the same raw count: 0…100 counts now map to 0…50 kg.
        decoder.ingest(motherboardCalibration(rows = listOf(0 to 0, 50 to 100)))

        assertEquals(2, decoder.calibrationRowCounts.first())
        assertEquals(
            5.0,
            decoder.ingest(motherboardPacket(samples = listOf(10, 0, 0))).firstOrNull()?.kg ?: 0.0,
            0.0001,
        )
    }

    /// "C" then "S30": the calibration request has to precede the stream, or the
    /// packets that arrive first mean nothing — and the reference's own 2500 ms wait
    /// between the two is carried by the profile rather than lost in a two-write burst.
    @Test
    fun motherboardProfileAsksForCalibrationBeforeStarting() {
        assertEquals(2, MotherboardCodec.profile.streamStartPayloads.size)
        assertBytesEqual(
            "C".toByteArray(Charsets.UTF_8),
            MotherboardCodec.profile.streamStartPayloads[0],
        )
        assertBytesEqual(
            "S30".toByteArray(Charsets.UTF_8),
            MotherboardCodec.profile.streamStartPayloads[1],
        )
        assertEquals(
            2.5,
            MotherboardCodec.profile.startPayloadDelaySeconds,
            "the reference's own wait between the two writes",
        )
        assertBytesEqual(
            "#".toByteArray(Charsets.UTF_8),
            MotherboardCodec.profile.streamStopPayload,
        )
        assertEquals("6E400001-B5A3-F393-E0A9-E50E24DCCA9E", MotherboardCodec.profile.serviceUUID)
        assertEquals(
            "6E400003-B5A3-F393-E0A9-E50E24DCCA9E",
            MotherboardCodec.profile.notifyCharacteristicUUID,
        )
        assertEquals(
            "6E400002-B5A3-F393-E0A9-E50E24DCCA9E",
            MotherboardCodec.profile.writeCharacteristicUUID,
        )
    }

    // MARK: - CTS500

    /// `buildCommand(opcode)` is `05 <opcode> 00 00 00 <sum>`, the sum truncated to
    /// a byte. Start 0xAA: 0x05 + 0xAA = 0xAF. Stop 0xAB: 0xB0. Tare 0xA6: 0xAB.
    /// Sampling rate 0xC1 with payload 00 00 02: 0x05 + 0xC1 + 0x02 = 0xC8.
    @Test
    fun cts500CommandFramesMatchTheReferenceChecksums() {
        assertBytesEqual(bytes(0x05, 0xAA, 0x00, 0x00, 0x00, 0xAF), CTS500Codec.command(0xAA))
        assertBytesEqual(bytes(0x05, 0xAB, 0x00, 0x00, 0x00, 0xB0), CTS500Codec.command(0xAB))
        assertBytesEqual(bytes(0x05, 0xA6, 0x00, 0x00, 0x00, 0xAB), CTS500Codec.command(0xA6))
        assertBytesEqual(
            bytes(0x05, 0xC1, 0x00, 0x00, 0x02, 0xC8),
            CTS500Codec.command(0xC1, 0x00, 0x00, 0x02),
        )
    }

    /// The checksum wraps rather than traps — it is `& 0xFF` in the reference and
    /// `&+` here. 0xFF + 0xFF = 0x1FE → 0xFE.
    @Test
    fun cts500ChecksumWrapsInsteadOfOverflowing() {
        assertEquals(0xFE, CTS500Codec.checksum(intArrayOf(0xFF, 0xFF)))
        assertEquals(0xAF, CTS500Codec.checksum(intArrayOf(0x05, 0xAA, 0x00, 0x00, 0x00)))
    }

    /// The reference's own fragmented-frame test: 12.34 kg is 1234 = 0x000004D2 in
    /// big-endian centi-kilograms, so the frame is 05 01 00 00 04 D2 with checksum
    /// 0x05 + 0x01 + 0x04 + 0xD2 = 0xDC. Split 3 + 4, nothing may be emitted from
    /// the first half.
    @Test
    fun cts500ReassemblesAFragmentedWeightFrame() {
        val decoder = CTS500Codec.Decoder()
        val frame = bytes(0x05, 0x01, 0x00, 0x00, 0x04, 0xD2, 0xDC)

        assertEquals(noReadings, decoder.ingest(frame.copyOfRange(0, 3)))

        val readings = decoder.ingest(frame.copyOfRange(3, 7))
        assertEquals(1, readings.size)
        assertEquals(12.34, readings[0].kg, 0.0001)
    }

    /// Bytes 2…5 are BIG-endian. 100.00 kg is 10000 = 0x00002710; read
    /// little-endian the same bytes are 0x10270000 = 2,712,207.36 kg — a number a
    /// glance at a graph would never explain.
    @Test
    fun cts500WeightIsBigEndianCentiKilograms() {
        val decoder = CTS500Codec.Decoder()
        // checksum: 0x05 + 0x01 + 0x27 + 0x10 = 0x3D
        val readings = decoder.ingest(bytes(0x05, 0x01, 0x00, 0x00, 0x27, 0x10, 0x3D))

        assertEquals(1, readings.size)
        assertEquals(100.0, readings[0].kg, 0.0001)
    }

    /// A bad checksum discards the frame — and must leave the buffer able to decode
    /// the next good one, or one corrupted packet ends the session's data.
    @Test
    fun cts500RejectsABadChecksumAndRecoversOnTheNextFrame() {
        val decoder = CTS500Codec.Decoder()
        // The reference's own test corrupts the checksum with ^ 0xFF: 0xDC → 0x23.
        assertEquals(noReadings, decoder.ingest(bytes(0x05, 0x01, 0x00, 0x00, 0x04, 0xD2, 0x23)))

        val readings = decoder.ingest(bytes(0x05, 0x01, 0x00, 0x00, 0x04, 0xD2, 0xDC))
        assertEquals(1, readings.size)
        assertEquals(12.34, readings[0].kg, 0.0001)
    }

    /// Commands, echoes and weight share one channel, so the session's own start,
    /// rate and tare writes come back as 6-byte echoes. Reading one as a weight
    /// would report tens of thousands of kilograms.
    @Test
    fun cts500CommandEchoesAreNotWeightFrames() {
        val decoder = CTS500Codec.Decoder()
        assertEquals(noReadings, decoder.ingest(bytes(0x05, 0xAA, 0x00, 0x00, 0x00, 0xAF)))
        assertEquals(noReadings, decoder.ingest(bytes(0x05, 0xC1, 0x00, 0x00, 0x02, 0xC8)))
        assertEquals(noReadings, decoder.ingest(bytes(0x05, 0xA6, 0x00, 0x00, 0x00, 0xAB)))
        assertEquals(noReadings, decoder.ingest(bytes(0x05, 0xAB, 0x00, 0x00, 0x00, 0xB0)))
    }

    /// A typed reply is `05 80 <opcode> …`. The reference's battery fixture is
    /// 05 80 C4 00 01 63 → (0x0163) / 100 = 3.55 V, checksum
    /// 0x05 + 0x80 + 0xC4 + 0x01 + 0x63 = 0x1AD → 0xAD.
    ///
    /// Volts are NOT published as a battery fraction: that needs this cell's
    /// discharge curve, and the Progressor's curve is the Progressor's.
    @Test
    fun cts500TypedBatteryReplyIsParsedButNeverBecomesAFraction() {
        val decoder = CTS500Codec.Decoder()
        assertEquals(noReadings, decoder.ingest(bytes(0x05, 0x80, 0xC4, 0x00, 0x01, 0x63, 0xAD)))
        assertEquals(3.55, assertNotNull(decoder.latestBatteryVolts), 0.0001)
        assertNull(decoder.batteryFraction)
    }

    /// A 0x05 inside another frame's payload is a false header. Resynchronising
    /// CONTINUES the walk here, where the reference abandons the notification after
    /// dropping one byte — at 40 Hz that would stall the stream for a noticeable
    /// fraction of a second on a single bad byte.
    @Test
    fun cts500ResynchronisesPastAFalseHeaderWithinOneNotification() {
        val decoder = CTS500Codec.Decoder()
        // Seven bytes that validate as neither frame length (0x99 is not an opcode,
        // and the checksum does not match), followed by a good 12.34 kg frame.
        val junk = bytes(0x05, 0x99, 0x99, 0x99, 0x99, 0x99, 0x99)
        val frame = bytes(0x05, 0x01, 0x00, 0x00, 0x04, 0xD2, 0xDC)

        val readings = decoder.ingest(junk + frame)
        assertEquals(1, readings.size)
        assertEquals(12.34, readings[0].kg, 0.0001)
    }

    /// Two frames in one notification both decode: the device batches at whatever
    /// rate the UART bridge flushes.
    @Test
    fun cts500DecodesTwoWeightFramesFromOneNotification() {
        val decoder = CTS500Codec.Decoder()
        val first = bytes(0x05, 0x01, 0x00, 0x00, 0x04, 0xD2, 0xDC)   // 12.34 kg
        val second = bytes(0x05, 0x01, 0x00, 0x00, 0x27, 0x10, 0x3D)  // 100.00 kg

        val readings = decoder.ingest(first + second)
        assertEquals(2, readings.size)
        assertEquals(12.34, readings[0].kg, 0.0001)
        assertEquals(100.0, readings[1].kg, 0.0001)
    }

    /// The weight field is read UNSIGNED, exactly as the reference reads it, so a
    /// firmware sending small negative drift in two's complement would produce
    /// 42,949,672.91 kg. Dropped: a missing sample costs a tenth of a second of
    /// trace, an absurd one sets the session peak and rescales the graph.
    @Test
    fun cts500DropsAWeightNoHandCouldProduce() {
        val decoder = CTS500Codec.Decoder()
        // checksum: 0x05 + 0x01 + 0xFF × 4 = 0x402 → 0x02
        assertEquals(noReadings, decoder.ingest(bytes(0x05, 0x01, 0xFF, 0xFF, 0xFF, 0xFF, 0x02)))
    }

    @Test
    fun cts500BelowFrameLengthFragmentsWaitForMoreBytes() {
        val decoder = CTS500Codec.Decoder()
        assertEquals(noReadings, decoder.ingest(ByteArray(0)))
        assertEquals(noReadings, decoder.ingest(bytes(0x05, 0x01, 0x00, 0x00, 0x04)))

        val readings = decoder.ingest(bytes(0xD2, 0xDC))
        assertEquals(1, readings.size)
        assertEquals(12.34, readings[0].kg, 0.0001)
    }

    /// Bytes with no header at all cannot begin a frame, so they are dropped rather
    /// than accumulated.
    @Test
    fun cts500DiscardsBytesWithNoHeaderInThem() {
        val decoder = CTS500Codec.Decoder()
        assertEquals(noReadings, decoder.ingest(bytes(0x11, 0x22, 0x33, 0x44, 0x55, 0x66)))

        val readings = decoder.ingest(bytes(0x05, 0x01, 0x00, 0x00, 0x04, 0xD2, 0xDC))
        assertEquals(1, readings.size)
        assertEquals(12.34, readings[0].kg, 0.0001)
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
    @Test
    fun cts500ProfileRequestsFortyHertzOnceThenStartsOnEveryKick() {
        assertEquals(1, CTS500Codec.profile.oneTimeSetupPayloads.size)
        assertBytesEqual(
            bytes(0x05, 0xC1, 0x00, 0x00, 0x02, 0xC8),
            CTS500Codec.profile.oneTimeSetupPayloads[0],
        )
        assertEquals(1, CTS500Codec.profile.streamStartPayloads.size)
        assertBytesEqual(
            bytes(0x05, 0xAA, 0x00, 0x00, 0x00, 0xAF),
            CTS500Codec.profile.streamStartPayloads[0],
        )
        assertBytesEqual(
            bytes(0x05, 0xAB, 0x00, 0x00, 0x00, 0xB0),
            CTS500Codec.profile.streamStopPayload,
        )
        assertBytesEqual(
            bytes(0x05, 0xA6, 0x00, 0x00, 0x00, 0xAB),
            CTS500Codec.profile.tarePayload,
        )
        assertEquals(
            CTS500Codec.profile.writeCharacteristicUUID,
            CTS500Codec.profile.tareCharacteristicUUID,
        )
        assertTrue(GaugeKind.cts500.capabilities.hasHardwareTare)
        assertEquals("0000FFE0-0000-1000-8000-00805F9B34FB", CTS500Codec.profile.serviceUUID)
        assertEquals(
            "0000FFE1-0000-1000-8000-00805F9B34FB",
            CTS500Codec.profile.notifyCharacteristicUUID,
        )
    }

    /// Every opcode the reference knows has to be in the set, because a weight frame
    /// is identified partly by its byte 1 NOT being one. A missing opcode turns that
    /// device's echo into a load reading.
    @Test
    fun cts500OpcodeSetCoversEveryCommandTheReferenceDefines() {
        assertEquals(26, CTS500Codec.commandOpcodes.size)
        for (opcode in listOf(
            0x81, 0x82, 0x83, 0x84, 0x85, 0x86,
            0xA1, 0xA2, 0xA4, 0xA6, 0xA7, 0xA9, 0xAA, 0xAB,
            0xC0, 0xC1, 0xC3, 0xC4, 0xC5, 0xC6, 0xC7, 0xCA,
            0xD1, 0xD2, 0xD3, 0xD4,
        )) {
            assertTrue(
                CTS500Codec.commandOpcodes.contains(opcode),
                "opcode ${opcode.toString(16)} missing",
            )
        }
    }

    // MARK: - Registry wiring

    /// The registry's job is one row per device; these four are the framed ones.
    /// A decoder must be a FRESH instance every time — reassembly buffers and the
    /// Motherboard's calibration table die with the link they were filled over.
    @Test
    fun framedKindsResolveToTheirOwnProfilesAndFreshDecoders() {
        assertEquals(ClimbroCodec.profile, GaugeKind.climbro.gatt)
        assertEquals(ForceBoardCodec.profile, GaugeKind.forceboard.gatt)
        assertEquals(MotherboardCodec.profile, GaugeKind.motherboard.gatt)
        assertEquals(CTS500Codec.profile, GaugeKind.cts500.gatt)

        for (kind in listOf(
            GaugeKind.climbro, GaugeKind.forceboard, GaugeKind.motherboard, GaugeKind.cts500,
        )) {
            assertNotNull(kind.makeFrameDecoder(), "$kind has no decoder")
            assertFalse(
                kind.capabilities.hasDeviceClock,
                "$kind stamps nothing; the client supplies synthetic µs",
            )
        }

        val first = MotherboardCodec.Decoder()
        first.ingest(motherboardCalibration())
        assertTrue(first.hasCalibration)
        assertFalse(MotherboardCodec.Decoder().hasCalibration)
    }
}
