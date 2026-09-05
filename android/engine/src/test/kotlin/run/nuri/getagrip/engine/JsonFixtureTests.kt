// SPDX-License-Identifier: MPL-2.0
// Original contributions Copyright 2026 Nuri Bruner.

package run.nuri.getagrip.engine

import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

/// The first fixture-backed tests: the JSON writer and the lenient scalar reads,
/// asserted from `Fixtures/blob/*.json` — the same files the Swift suite reads. See
/// `Fixtures/README.md` for the schemas and `Fixtures.kt` for the loader.
class JsonFixtureTests {

    @Test
    fun theWriterMatchesFoundationByteForByte() {
        val doc = Fixtures.load("blob/json-writer.json").jsonObject
        for (case in doc.getValue("cases").jsonArray.map { it.jsonObject }) {
            val name = case.getValue("name").jsonPrimitive.content
            val escape = case["escapeSlashes"]?.jsonPrimitive?.content != "false"
            val input = BlobCodec.parse(case.getValue("input").jsonPrimitive.content)
            assertNotNull(input, "fixture '$name' input must parse")
            assertEquals(case.getValue("output").jsonPrimitive.content, BlobCodec.write(input, escape), "case '$name'")
        }
    }

    @Test
    fun lenientScalarReadsMatchFoundation() {
        val doc = Fixtures.load("blob/lenient-scalars.json").jsonObject
        for (case in doc.getValue("cases").jsonArray.map { it.jsonObject }) {
            val type = case.getValue("type").jsonPrimitive.content
            val json = case.getValue("json").jsonPrimitive.content
            val element = BlobCodec.parse(json)
            val expected = case["result"]
            val actual: Any? = when (type) {
                "Int" -> JsonRead.int(element)
                "Double" -> JsonRead.double(element)
                "Bool" -> JsonRead.bool(element)
                "String" -> JsonRead.string(element)
                else -> error("unknown type $type")
            }
            val want: Any? = when {
                expected == null || expected is JsonNull -> null
                type == "Int" -> expected.jsonPrimitive.content.toInt()
                type == "Double" -> expected.jsonPrimitive.content.toDouble()
                type == "Bool" -> expected.jsonPrimitive.content.toBoolean()
                else -> expected.jsonPrimitive.content
            }
            assertEquals(want, actual, "$type from $json")
        }
    }
}
