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
import kotlin.test.assertNull

/// The cross-platform blob contract: every engine value type decoded from the SAME
/// hand-written wire text on both platforms, re-encoded, and compared byte for byte.
/// The two engines share no code — `Fixtures/blob/*.json` is what keeps them from
/// drifting, and a rule change in `Shared/Engine` is not done until the fixture and the
/// Kotlin twin change with it. Schemas in `Fixtures/README.md`.
class BlobFixtureTests {

    /// Decode `input`, re-encode, expect `canonical`; `canonical: null` means the decode
    /// must FAIL rather than invent a value.
    private fun runCases(file: String, decode: (String) -> JsonEncodable?) {
        val doc = Fixtures.load(file).jsonObject
        val type = doc.getValue("type").jsonPrimitive.content
        for (case in doc.getValue("cases").jsonArray.map { it.jsonObject }) {
            val name = case.getValue("name").jsonPrimitive.content
            val label = "$type / $name"
            val input = case.getValue("input").jsonPrimitive.content
            val decoded = decode(input)
            val canonical = case.getValue("canonical")
            if (canonical is JsonNull) {
                assertNull(decoded, "$label must not decode")
                continue
            }
            assertNotNull(decoded, "$label must decode")
            assertEquals(canonical.jsonPrimitive.content, BlobCodec.encode(decoded), label)
            case["key"]?.let { key ->
                assertEquals(key.jsonPrimitive.content, (decoded as GripSpec).key, "$label key")
            }
        }
    }

    @Test
    fun gripSpecFixtures() = runCases("blob/GripSpec.json") { text ->
        BlobCodec.decode(text) { GripSpec.fromJson(it) }
    }

    @Test
    fun setPlanFixtures() = runCases("blob/SetPlan.json") { text ->
        BlobCodec.decode(text) { SetPlan.fromJson(it) }
    }

    @Test
    fun sessionPlanFixtures() = runCases("blob/SessionPlan.json") { text ->
        BlobCodec.decode(text) { SessionPlan.fromJson(it) }
    }

    @Test
    fun repSummaryFixtures() = runCases("blob/RepSummary.json") { text ->
        BlobCodec.decode(text) { RepSummary.fromJson(it) }
    }

    @Test
    fun reminderTimeFixtures() = runCases("blob/ReminderTime.json") { text ->
        BlobCodec.decode(text) { ReminderTime.fromJson(it) }
    }

    @Test
    fun routineDraftFixtures() = runCases("blob/RoutineDraft.json") { text ->
        BlobCodec.decode(text) { RoutineDraft.fromJson(it) }
    }

    /// A TOP-LEVEL array is element-wise: one mangled element costs one row, not the
    /// routine. (`SessionPlan.sets`, which is NESTED, is all-or-nothing — pinned by the
    /// "one broken element costs the whole `sets` key" case in SessionPlan.json.)
    @Test
    fun setPlanArrayFixtures() {
        val doc = Fixtures.load("blob/SetPlanArray.json").jsonObject
        for (case in doc.getValue("cases").jsonArray.map { it.jsonObject }) {
            val name = case.getValue("name").jsonPrimitive.content
            val input = case.getValue("input").jsonPrimitive.content
            val decoded = BlobCodec.decodeArray(input) { SetPlan.fromJson(it) }
            assertEquals(
                case.getValue("canonical").jsonPrimitive.content,
                BlobCodec.encodeAll(decoded),
                "SetPlanArray / $name",
            )
        }
    }
}
