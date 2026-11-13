// SPDX-License-Identifier: MPL-2.0
// Original contributions Copyright 2026 Nuri Bruner.

package run.nuri.getagrip.engine

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.math.roundToInt

// Decoding leniency, in one place.
//
// Every blob this app stores is read back by a build that may be older or newer than
// the one that wrote it, and on iOS CloudKit syncs it between two phones without saying
// which device won. So decoding is written to be TOTAL — a routine that loses one field
// is still a routine, while a routine that throws is a routine the user watches vanish.
//
// TRANSLATION NOTE (from Shared/Engine/Leniency.swift): Swift wraps a typed decode in
// `try?`, so "missing key", "wrong type" and "JSON null" all collapse into the fallback.
// Kotlin has no typed JSON decode to wrap, so each reader below states the exact rule
// Foundation's JSONDecoder applies, MEASURED on macOS 26 Foundation (2026-09-04,
// `scratchpad/probe.swift`, pinned by `Fixtures/blob/lenient-scalars.json`):
//   Int     accepts `25.0` and `1e2` (exact integers written as doubles);
//           rejects `25.5`, `"6"` (a string), `true`.
//   Double  accepts any JSON number; rejects a string.
//   Bool    accepts only the literals `true` / `false`.
//   String  rejects a number.
//   null    reads as ABSENT for every type.

/// Clamp rather than reject. A decoded value outside its range is a message from
/// another build, not a corrupt file — the nearest legal value keeps the routine
/// usable and keeps every downstream `0 until n` loop finite.
fun <T : Comparable<T>> ClosedRange<T>.clamping(v: T): T = when {
    v < start -> start
    v > endInclusive -> endInclusive
    else -> v
}

/// A `Double` rounded to an `Int`, SATURATING — the only rounding a number from outside may
/// take on its way to the screen.
///
/// A kilogram figure that arrived in a share link, a stored blob or a gauge frame is not
/// ours to vouch for, and on iOS a percentage computed from one took the app down at
/// launch. NaN reads as 0; everything else lands on the nearest representable value.
///
/// TRANSLATION NOTE: the twin of Swift's `Int(saturating:)` (Shared/Engine/Leniency.swift),
/// which exists because Swift's `Int(_:)` TRAPS. On this side `toInt()` already saturates
/// and reads NaN as 0, so the one conversion that can throw is `roundToInt()`, on NaN —
/// and that is the call this replaces. Its rounding is otherwise `roundToInt()`'s own.
fun Double.roundToIntSaturating(): Int = if (isNaN()) 0 else roundToInt()

/// The `Float` twin, for a slider's own value.
fun Float.roundToIntSaturating(): Int = if (isNaN()) 0 else roundToInt()

/// Foundation's two whitespace sets, scalar for scalar — what Swift's
/// `trimmingCharacters(in:)` removes, so a trim on this side cuts exactly where iOS cuts.
///
/// MEASURED on macOS 26 Foundation (2026-11-12) by trimming every Unicode scalar in turn:
///   `.whitespacesAndNewlines`  U+0009–000D, 0020, 0085, 00A0, 1680, 2000–200B, 2028, 2029,
///                              202F, 205F, 3000
///   `.whitespaces`             the same without U+000A–000D, 0085, 2028 and 2029
///
/// Kotlin's `trim()` is NOT this set: it also strips U+001C–001F and keeps U+0085 and
/// U+200B (ZERO WIDTH SPACE, which Foundation still counts as a space). Every member is in
/// the BMP, so testing UTF-16 units is testing scalars.
object FoundationWhitespace {
    /// `.whitespaces`: the horizontal ones.
    fun isWhitespace(code: Int): Boolean = when (code) {
        0x09, 0x20, 0xA0, 0x1680, in 0x2000..0x200B, 0x202F, 0x205F, 0x3000 -> true
        else -> false
    }

    /// `.whitespacesAndNewlines`.
    fun isWhitespaceOrNewline(code: Int): Boolean = when (code) {
        in 0x0A..0x0D, 0x85, 0x2028, 0x2029 -> true
        else -> isWhitespace(code)
    }

    /// `text.trimmingCharacters(in: .whitespacesAndNewlines)`.
    fun trimmed(text: String): String = text.trim { isWhitespaceOrNewline(it.code) }
}

/// The typed reads Foundation would perform, each returning null exactly where Swift
/// would throw (and therefore where `value(_:or:)` would fall back).
object JsonRead {
    private fun numberText(e: JsonElement?): String? {
        val p = e as? JsonPrimitive ?: return null
        if (p is JsonNull || p.isString) return null
        return p.content
    }

    fun long(e: JsonElement?): Long? {
        val text = numberText(e) ?: return null
        text.toLongOrNull()?.let { return it }
        val decimal = text.toBigDecimalOrNull() ?: return null
        return try { decimal.longValueExact() } catch (_: ArithmeticException) { null }
    }

    fun int(e: JsonElement?): Int? {
        val l = long(e) ?: return null
        return if (l in Int.MIN_VALUE.toLong()..Int.MAX_VALUE.toLong()) l.toInt() else null
    }

    fun double(e: JsonElement?): Double? =
        numberText(e)?.toDoubleOrNull()?.takeIf { it.isFinite() }

    fun bool(e: JsonElement?): Boolean? = when (numberText(e)) {
        "true" -> true
        "false" -> false
        else -> null
    }

    fun string(e: JsonElement?): String? {
        val p = e as? JsonPrimitive ?: return null
        return if (p !is JsonNull && p.isString) p.content else null
    }

    fun obj(e: JsonElement?): JsonObject? = e as? JsonObject
    fun array(e: JsonElement?): JsonArray? = e as? JsonArray
}

// The keyed-container mirrors: `c.value(.key, or: fallback)` and `c.optional(.key)`.
// A MISSING key (an old blob written before the field existed) and a RETYPED key (a
// newer build that changed Int to Double) both fall back. A throw here would cost the
// whole routine, not one field.

fun JsonObject.intOr(key: String, fallback: Int): Int = JsonRead.int(this[key]) ?: fallback
fun JsonObject.optionalInt(key: String): Int? = JsonRead.int(this[key])
fun JsonObject.doubleOr(key: String, fallback: Double): Double = JsonRead.double(this[key]) ?: fallback
fun JsonObject.optionalDouble(key: String): Double? = JsonRead.double(this[key])
fun JsonObject.boolOr(key: String, fallback: Boolean): Boolean = JsonRead.bool(this[key]) ?: fallback
fun JsonObject.stringOr(key: String, fallback: String): String = JsonRead.string(this[key]) ?: fallback
fun JsonObject.optionalArray(key: String): JsonArray? = JsonRead.array(this[key])

/// For nested values with their own (total) decoder: the reader returns null, or throws,
/// where Swift's `T(from:)` would throw — and the key falls back either way.
inline fun <T> JsonObject.valueOr(key: String, fallback: T, read: (JsonElement) -> T?): T {
    val element = this[key] ?: return fallback
    if (element is JsonNull) return fallback
    return try { read(element) ?: fallback } catch (_: Exception) { fallback }
}

/// The optional twin for nested values.
inline fun <T> JsonObject.optionalValue(key: String, read: (JsonElement) -> T?): T? {
    val element = this[key] ?: return null
    if (element is JsonNull) return null
    return try { read(element) } catch (_: Exception) { null }
}
