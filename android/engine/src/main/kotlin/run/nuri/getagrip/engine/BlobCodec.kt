// SPDX-License-Identifier: MPL-2.0
// Original contributions Copyright 2026 Nuri Bruner.

package run.nuri.getagrip.engine

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.math.BigDecimal

/// A value that knows its own wire shape. The engine value types ARE the wire format —
/// there is no parallel DTO layer, exactly as on iOS — with frozen key names and
/// lenient decoders (`companion fun fromJson`).
interface JsonEncodable {
    fun toJson(): JsonElement
}

/// The one JSON door. Every blob column in the store and the debounced draft rescue go
/// through here, so there is exactly one encoder configuration to reason about when
/// two builds disagree — and, on Android, so that a blob is BYTE-IDENTICAL to what the
/// iOS app writes for the same value (`Fixtures/blob/*`).
///
/// TRANSLATION NOTE (from Shared/BlobCodec.swift): iOS uses Foundation's `JSONEncoder`
/// with `.sortedKeys` and its default escaping. This writer reproduces that output,
/// measured on macOS 26 Foundation (2026-09-04, `scratchpad/probe.swift`):
///   - compact, no whitespace; object keys in byte order (uppercase before lowercase);
///   - a whole double below 1e16 prints as an integer (`25`, not `25.0`), `-0.0` as `-0`;
///   - other doubles print with the shortest round-trip digits, plain between 1e-4 and
///     1e16 (`0.0001`, `123456789012.5`), exponential outside (`1e-05`, `2.5e-07`, `1e+20`);
///   - `/` is escaped as `\/` (RoutineShare passes `escapeSlashes = false`, the twin of
///     `.withoutEscapingSlashes`); control characters as `\u00XX`; non-ASCII raw;
///   - an absent optional is OMITTED, never written as `null`.
object BlobCodec {
    class NotEncodable(message: String) : RuntimeException(message)

    fun write(element: JsonElement, escapeSlashes: Boolean = true): String =
        StringBuilder().also { writeElement(element, it, escapeSlashes) }.toString()

    /// null on failure rather than a throw: on iOS every caller is a property setter on
    /// a model, and the correct response to "this did not encode" is to leave the good
    /// blob already on disk exactly where it is. The only failure a value type can
    /// produce is a non-finite double.
    fun encode(value: JsonEncodable, escapeSlashes: Boolean = true): String? =
        try { write(value.toJson(), escapeSlashes) } catch (_: NotEncodable) { null }

    fun encodeAll(values: List<JsonEncodable>, escapeSlashes: Boolean = true): String? =
        try { write(JsonArray(values.map { it.toJson() }), escapeSlashes) } catch (_: NotEncodable) { null }

    private val parser = Json { ignoreUnknownKeys = true }

    /// Empty text → null, not a decode error: a freshly inserted record has empty blob
    /// columns by definition, and that is not a failure worth surfacing.
    fun parse(text: String): JsonElement? {
        if (text.isEmpty()) return null
        return try { parser.parseToJsonElement(text) } catch (_: Exception) { null }
    }

    fun <T> decode(text: String, read: (JsonElement) -> T?): T? {
        val element = parse(text) ?: return null
        return try { read(element) } catch (_: Exception) { null }
    }

    /// Element-wise: one structurally broken element is dropped instead of taking the
    /// whole routine with it — the difference between a routine missing a row and a
    /// routine that vanished. `read` returns null (or throws) for an element that is
    /// not the expected shape, which is what Swift's `Lenient<T>` wrapper swallows.
    fun <T> decodeArray(text: String, read: (JsonElement) -> T?): List<T> {
        val array = parse(text) as? JsonArray ?: return emptyList()
        return array.mapNotNull { element -> try { read(element) } catch (_: Exception) { null } }
    }

    // MARK: - Numbers

    /// Foundation's number text for a double, see the header. Throws on NaN/inf, which
    /// is how Swift's encoder fails on them.
    fun number(value: Double): String {
        if (!value.isFinite()) throw NotEncodable("non-finite double")
        if (value == 0.0) return if (1.0 / value < 0) "-0" else "0"
        val magnitude = kotlin.math.abs(value)
        if (value == Math.rint(value) && magnitude < 1e16) return value.toLong().toString()
        // `Double.toString` is the shortest round-trip form on JDK 19+ (Ryu-style); the
        // digits are exact, only the layout differs from Swift's, so lay them out again.
        val decimal = BigDecimal(value.toString()).stripTrailingZeros()
        val digits = decimal.unscaledValue().abs().toString()
        val exponent = digits.length - 1 - decimal.scale()
        val sign = if (value < 0) "-" else ""
        return if (exponent < -4 || exponent >= 16) {
            val mantissa = if (digits.length == 1) digits else digits[0] + "." + digits.substring(1)
            sign + mantissa + "e" + (if (exponent < 0) "-" else "+") +
                kotlin.math.abs(exponent).toString().padStart(2, '0')
        } else {
            sign + decimal.abs().toPlainString()
        }
    }

    private val integerLiteral = Regex("-?\\d+")

    private fun writeNumberOrLiteral(content: String, out: StringBuilder) {
        when {
            content == "true" || content == "false" -> out.append(content)
            integerLiteral.matches(content) -> out.append(content)
            else -> out.append(number(content.toDoubleOrNull() ?: throw NotEncodable("not a number: $content")))
        }
    }

    // MARK: - Walk

    private fun writeElement(e: JsonElement, out: StringBuilder, escapeSlashes: Boolean) {
        when (e) {
            is JsonNull -> out.append("null")
            is JsonPrimitive -> if (e.isString) writeString(e.content, out, escapeSlashes)
                                else writeNumberOrLiteral(e.content, out)
            is JsonArray -> {
                out.append('[')
                e.forEachIndexed { i, child ->
                    if (i > 0) out.append(',')
                    writeElement(child, out, escapeSlashes)
                }
                out.append(']')
            }
            is JsonObject -> {
                out.append('{')
                // UTF-16 code-unit order, which is byte order for every key this app
                // writes (ASCII identifiers) — the same order Swift's `.sortedKeys` gave.
                e.entries.sortedBy { it.key }.forEachIndexed { i, (key, child) ->
                    if (i > 0) out.append(',')
                    writeString(key, out, escapeSlashes)
                    out.append(':')
                    writeElement(child, out, escapeSlashes)
                }
                out.append('}')
            }
        }
    }

    private const val FORM_FEED: Char = '\u000C'

    private fun writeString(s: String, out: StringBuilder, escapeSlashes: Boolean) {
        out.append('"')
        for (ch in s) {
            when (ch) {
                '"' -> out.append("\\\"")
                '\\' -> out.append("\\\\")
                '/' -> if (escapeSlashes) out.append("\\/") else out.append('/')
                '\n' -> out.append("\\n")
                '\r' -> out.append("\\r")
                '\t' -> out.append("\\t")
                '\b' -> out.append("\\b")
                FORM_FEED -> out.append("\\f")
                else -> if (ch < ' ') out.append("\\u").append(ch.code.toString(16).padStart(4, '0'))
                        else out.append(ch)
            }
        }
        out.append('"')
    }
}
