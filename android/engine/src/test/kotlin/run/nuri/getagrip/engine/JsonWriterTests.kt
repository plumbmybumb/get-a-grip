// SPDX-License-Identifier: MPL-2.0
// Original contributions Copyright 2026 Nuri Bruner.

package run.nuri.getagrip.engine

import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/// The writer must produce the BYTES Foundation's `JSONEncoder([.sortedKeys])` produces,
/// or a routine edited on Android and synced back would re-encode differently and read
/// as a phantom edit. Every expectation here was measured on macOS 26 Foundation
/// (2026-09-04, `scratchpad/probe.swift`) and is also asserted from Swift through
/// `Fixtures/blob/json-writer.json` by `JsonFixtureTests`.
class JsonWriterTests {

    private fun rewrite(text: String, escapeSlashes: Boolean = true): String =
        BlobCodec.write(BlobCodec.parse(text)!!, escapeSlashes)

    @Test
    fun wholeDoublesPrintAsIntegersAndTheRestLikeSwift() {
        assertEquals("25", BlobCodec.number(25.0))
        assertEquals("0.5", BlobCodec.number(0.5))
        assertEquals("1e-05", BlobCodec.number(1e-5))
        assertEquals("0.0001", BlobCodec.number(0.0001))
        assertEquals("123456789012.5", BlobCodec.number(123456789012.5))
        assertEquals("1000000000000000", BlobCodec.number(1e15))
        assertEquals("1e+16", BlobCodec.number(1e16))
        assertEquals("1e+20", BlobCodec.number(1e20))
        assertEquals("-0", BlobCodec.number(-0.0))
        assertEquals("10.0125", BlobCodec.number(10.0125))
        assertEquals("2.5e-07", BlobCodec.number(2.5e-7))
        assertEquals("9007199254740992", BlobCodec.number(9007199254740992.0))
        assertEquals("-12.5", BlobCodec.number(-12.5))
    }

    @Test
    fun keysAreSortedInByteOrder() {
        val input = """{"b":1,"B":2,"a":3,"a/b":4,"aB":5,"ab":6,"targetHiKg":7,"targetHiPercent":8,"targetLoKg":9,"sets":10,"setBreakSeconds":11}"""
        assertEquals(
            """{"B":2,"a":3,"a\/b":4,"aB":5,"ab":6,"b":1,"setBreakSeconds":11,"sets":10,"targetHiKg":7,"targetHiPercent":8,"targetLoKg":9}""",
            rewrite(input),
        )
    }

    @Test
    fun stringsEscapeLikeFoundation() {
        assertEquals("\"a\\/b\"", BlobCodec.write(JsonPrimitive("a/b")))
        assertEquals("\"a/b\"", BlobCodec.write(JsonPrimitive("a/b"), escapeSlashes = false))
        assertEquals("\"a\\\"b\"", BlobCodec.write(JsonPrimitive("a\"b")))
        assertEquals("\"a\\nb\"", BlobCodec.write(JsonPrimitive("a\nb")))
        assertEquals("\"a\\tb\"", BlobCodec.write(JsonPrimitive("a\tb")))
        assertEquals("\"a\\\\b\"", BlobCodec.write(JsonPrimitive("a\\b")))
        // A control character takes the six-character escape; DEL (0x7F) stays raw, as
        // Foundation writes it.
        assertEquals("\"a\\u0001b\"", BlobCodec.write(JsonPrimitive("a\u0001b")))
        assertEquals("\"a\u007Fb\"", BlobCodec.write(JsonPrimitive("a\u007Fb")))
        assertEquals("\"é☃\"", BlobCodec.write(JsonPrimitive("é☃")))
    }

    @Test
    fun aNonFiniteDoubleRefusesToEncodeRatherThanWritingGarbage() {
        val bad = object : JsonEncodable {
            override fun toJson() = JsonPrimitive(Double.POSITIVE_INFINITY)
        }
        assertNull(BlobCodec.encode(bad))
    }

    @Test
    fun lenientScalarReadsMatchFoundation() {
        fun p(text: String) = BlobCodec.parse(text)
        assertEquals(25, JsonRead.int(p("25.0")))
        assertEquals(100, JsonRead.int(p("1e2")))
        assertNull(JsonRead.int(p("25.5")))
        assertNull(JsonRead.int(p("\"6\"")))
        assertNull(JsonRead.int(p("true")))
        assertEquals(25.0, JsonRead.double(p("25")))
        assertNull(JsonRead.double(p("\"2.5\"")))
        assertNull(JsonRead.bool(p("1")))
        assertEquals(true, JsonRead.bool(p("true")))
        assertNull(JsonRead.string(p("5")))
        assertEquals("x", JsonRead.string(p("\"x\"")))
        assertNull(JsonRead.int(p("null")))
    }

    @Test
    fun fixedFormattingRoundsTiesToEvenLikePrintf() {
        // 12.25 and 12.75 are exact in binary, so they are true ties: even wins.
        assertEquals("12.2", Fmt.fixed(12.25, 1))
        assertEquals("12.8", Fmt.fixed(12.75, 1))
        assertEquals("2", Fmt.fixed(2.5, 0))
        assertEquals("4", Fmt.fixed(3.5, 0))
        assertEquals("12.3", Fmt.fixed(12.34, 1))
        assertEquals("12.4", Fmt.fixed(12.36, 1))
        assertEquals("0.1", Fmt.fixed(0.1, 1))
        assertEquals("38.0", Fmt.fixed(38.0, 1))
        assertEquals("50", Fmt.fixed(50.4, 0))
    }
}
