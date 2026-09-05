// SPDX-License-Identifier: MPL-2.0
// Original contributions Copyright 2026 Nuri Bruner.

package run.nuri.getagrip.engine

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime

/// A calendar day as an integer — days since 1970-01-01 (proleptic Gregorian).
///
/// A training day is the day the user lived through, which has no timezone: a session
/// finished at 00:30 belongs to the evening it was part of, and "2 of 2 today" must
/// flip at local midnight without a relaunch. Storing days as integers makes all of
/// that pure integer math and immune to the DST/timezone off-by-one bugs a timestamp
/// invites, and it makes `WorkoutLog.dayKey` a cheap Int predicate.
///
/// The ONLY place a time zone appears is this file's conversion boundary.
///
/// TRANSLATION NOTE (from Shared/Engine/DayStamp.swift): Swift takes the ZONE of the
/// user's `Calendar` and never its calendar system, because a Thai or Japanese device
/// calendar would shift every stamp by centuries. `java.time.LocalDate` is ISO-8601
/// (proleptic Gregorian) by construction and takes only a `ZoneId`, so the same rule
/// holds here without a special case — `LocalDate.toEpochDay()` IS `raw`.
data class DayStamp(val raw: Int) : Comparable<DayStamp>, JsonEncodable {

    override fun compareTo(other: DayStamp): Int = raw.compareTo(other.raw)

    // Strideable — the 14-day consistency strip is literally `(today - 13)..today`.
    fun advanced(by: Int): DayStamp = DayStamp(raw + by)
    fun distance(to: DayStamp): Int = to.raw - raw

    operator fun plus(days: Int): DayStamp = DayStamp(raw + days)
    operator fun minus(days: Int): DayStamp = DayStamp(raw - days)
    /// Signed distance in days (NOT a count of inclusive days — that is this + 1).
    operator fun minus(other: DayStamp): Int = raw - other.raw

    /// A bare Int on the wire — `20669`, never `{"raw":20669}`.
    override fun toJson(): JsonElement = JsonPrimitive(raw)

    fun localDate(): LocalDate = LocalDate.ofEpochDay(raw.toLong())

    /// Local start-of-day for this day — for date pickers and chart axes. If local
    /// midnight does not exist on a DST edge, `java.time` picks the first valid instant,
    /// exactly as Foundation's `Calendar` does.
    fun startOfDay(zone: ZoneId = ZoneId.systemDefault()): ZonedDateTime =
        localDate().atStartOfDay(zone)

    companion object {
        fun fromJson(e: JsonElement?): DayStamp? = JsonRead.int(e)?.let { DayStamp(it) }

        /// A specific Y/M/D (proleptic Gregorian) — mainly for tests and fixed dates.
        fun of(year: Int, month: Int, day: Int): DayStamp =
            DayStamp(LocalDate.of(year, month, day).toEpochDay().toInt())

        /// The calendar day `instant` falls on in `zone` (default: the user's).
        fun of(instant: Instant, zone: ZoneId = ZoneId.systemDefault()): DayStamp =
            DayStamp(instant.atZone(zone).toLocalDate().toEpochDay().toInt())

        fun today(zone: ZoneId = ZoneId.systemDefault()): DayStamp = of(Instant.now(), zone)

        /// The range of days `from..to`, inclusive, in order.
        fun span(from: DayStamp, to: DayStamp): List<DayStamp> =
            if (to.raw < from.raw) emptyList() else (from.raw..to.raw).map(::DayStamp)
    }
}
