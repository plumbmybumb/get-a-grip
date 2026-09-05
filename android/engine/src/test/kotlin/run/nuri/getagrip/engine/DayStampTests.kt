// SPDX-License-Identifier: MPL-2.0
// Original contributions Copyright 2026 Nuri Bruner.

package run.nuri.getagrip.engine

import java.time.LocalDateTime
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/// `DayStamp` is the join for everything day-shaped: "2 of 2 today", the 14-day
/// consistency strip, and `WorkoutLog.dayKey`. It is an epoch-day Int so all of that
/// is integer math, with a time zone touched only where a human date enters or leaves.
/// Translated from Tests/DayStampTests.swift.
class DayStampTests {

    private fun zone(id: String): ZoneId = ZoneId.of(id)
    private fun at(zone: ZoneId, y: Int, m: Int, d: Int, h: Int = 0, min: Int = 0) =
        LocalDateTime.of(y, m, d, h, min).atZone(zone).toInstant()

    @Test
    fun epochAnchorAndIntegerArithmetic() {
        assertEquals(0, DayStamp.of(1970, 1, 1).raw)
        assertEquals(1, DayStamp.of(1970, 1, 2).raw)
        assertEquals(10, DayStamp.of(2026, 8, 3) - DayStamp.of(2026, 7, 24))
        assertEquals(DayStamp.of(2026, 8, 3), DayStamp.of(2026, 7, 24) + 10)
        // 2028 is a leap year, 2027 is not — the arithmetic must not be calendar-blind
        // in the direction that loses a day.
        assertEquals(2, DayStamp.of(2028, 3, 1) - DayStamp.of(2028, 2, 28))
        assertEquals(1, DayStamp.of(2027, 3, 1) - DayStamp.of(2027, 2, 28))
    }

    /// A stamp names the day you actually experienced, which is a function of where you
    /// were standing. Once frozen into `WorkoutLog.dayKey` it is a bare Int, so flying
    /// to Los Angeles can never retro-move a session done in Paris to another day.
    @Test
    fun dayKeyIsStableAcrossATimeZoneChange() {
        val paris = zone("Europe/Paris")
        val losAngeles = zone("America/Los_Angeles")

        // 12:00 in Paris is 03:00 the same date in Los Angeles — one instant, one day.
        val midday = at(paris, 2026, 7, 24, 12)
        assertEquals(DayStamp.of(2026, 7, 24), DayStamp.of(midday, paris))
        assertEquals(DayStamp.of(2026, 7, 24), DayStamp.of(midday, losAngeles))

        // Freeze it, then move the phone. The Int does not care where it is read.
        val frozen = DayStamp.of(midday, paris).raw
        assertEquals(DayStamp.of(2026, 7, 24), DayStamp(frozen))
        assertEquals(frozen, DayStamp(frozen).raw)
    }

    /// The case that makes freezing `dayKey` at save the right call rather than deriving
    /// it later: a 00:30 session belongs to the day the user lived through, and a UTC
    /// read of the same instant would file it under the previous day.
    @Test
    fun midnightThirtySessionBelongsToTheLocalDayItStartedOn() {
        val paris = zone("Europe/Paris")
        val utc = zone("UTC")
        val lateNight = at(paris, 2026, 7, 24, 0, 30)

        assertEquals(DayStamp.of(2026, 7, 24), DayStamp.of(lateNight, paris))
        assertEquals(DayStamp.of(2026, 7, 23), DayStamp.of(lateNight, utc),
            "reading the same instant in UTC files it under the wrong day")
    }

    /// A Thai-region phone defaults to the Buddhist calendar (2026 CE = 2569 BE) and a
    /// Japanese one can use the era calendar. On iOS `gregorian(zoneOf:)` takes the ZONE
    /// and never the calendar system; here `java.time` is ISO by construction and only a
    /// zone is ever passed, so a device calendar cannot leak in at all. The leap-day and
    /// round-trip checks are kept because they are what the rule protects.
    @Test
    fun nonGregorianDeviceCalendarStillProducesGregorianEpochDays() {
        val bangkok = zone("Asia/Bangkok")
        val tokyo = zone("Asia/Tokyo")

        val noon = at(bangkok, 2026, 7, 24, 12)
        assertEquals(DayStamp.of(2026, 7, 24), DayStamp.of(noon, bangkok))

        // Leap-day integrity.
        val feb29 = at(bangkok, 2028, 2, 29, 12)
        val mar1 = at(bangkok, 2028, 3, 1, 12)
        assertEquals(1, DayStamp.of(mar1, bangkok) - DayStamp.of(feb29, bangkok))

        for (stamp in listOf(DayStamp.of(2026, 7, 24), DayStamp.of(2028, 2, 29))) {
            assertEquals(stamp, DayStamp.of(stamp.startOfDay(tokyo).toInstant(), tokyo))
            assertEquals(stamp, DayStamp.of(stamp.startOfDay(bangkok).toInstant(), bangkok))
        }
    }

    @Test
    fun dateRoundTripAcrossZonesIncludingDSTTransitions() {
        val stamps = listOf(
            DayStamp.of(2026, 8, 3),
            DayStamp.of(2026, 12, 31),
            DayStamp.of(2027, 3, 28),   // EU DST spring forward
            DayStamp.of(2027, 10, 31),  // EU DST fall back
        )
        for (id in listOf("Europe/Paris", "Asia/Tokyo", "Pacific/Honolulu", "America/Santiago")) {
            val z = zone(id)
            for (stamp in stamps) {
                assertEquals(stamp, DayStamp.of(stamp.startOfDay(z).toInstant(), z), "round-trip failed in $id")
            }
        }
    }

    @Test
    fun strideableAndComparableBehaveLikeIntegers() {
        val day = DayStamp.of(2026, 8, 3)
        assertEquals(day + 13, day.advanced(13))
        assertEquals(13, day.distance(day + 13))
        assertTrue(day - 1 < day)
        assertEquals(14, DayStamp.span(day - 13, day).size, "the consistency strip is exactly this stride")
    }

    @Test
    fun dayStampEncodesAsABareInt() {
        assertEquals("20669", BlobCodec.encode(DayStamp(20_669)))
        assertEquals(DayStamp(20_669), BlobCodec.decode("20669", DayStamp::fromJson))
    }
}
