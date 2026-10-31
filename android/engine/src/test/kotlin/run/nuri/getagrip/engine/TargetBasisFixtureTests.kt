// SPDX-License-Identifier: MPL-2.0
// Original contributions Copyright 2026 Nuri Bruner.

package run.nuri.getagrip.engine

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.time.Instant
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/// `Fixtures/target-basis/` — targets as a percentage of max OR of critical force
/// (`docs/TARGET_BASIS.md`). Written by the Swift oracle (`oracle target-basis generate`);
/// this is the Kotlin reader, so the two engines resolve the same plan to the same kilograms.
class TargetBasisFixtureTests {

    private fun string(o: JsonObject, key: String): String = o.getValue(key).jsonPrimitive.content
    private fun double(e: JsonElement?): Double? =
        if (e == null || e is JsonNull) null else e.jsonPrimitive.content.toDouble()
    private fun int(e: JsonElement?): Int? =
        if (e == null || e is JsonNull) null else e.jsonPrimitive.content.toInt()
    private fun side(raw: String): Side = Side.fromRaw(raw) ?: error("unknown side $raw")

    private fun maxTable(rows: List<JsonObject>): MaxTable {
        val table = MaxTable()
        for (row in rows) {
            table.record(double(row["kg"])!!, string(row, "grip"), side(string(row, "side")), int(row["seconds"]))
        }
        return table
    }

    private fun cfTable(rows: List<JsonObject>): CriticalForceTable {
        val table = CriticalForceTable()
        for (row in rows) table.record(double(row["kg"])!!, string(row, "grip"), side(string(row, "side")))
        return table
    }

    private fun near(a: Double, b: Double) = abs(a - b) < 1e-9

    /// One `plan / tables / expected` triple, checked the way the oracle generated it.
    private fun check(
        name: String,
        planText: String,
        maxes: MaxTable,
        cfs: CriticalForceTable,
        expected: JsonObject,
    ) {
        val plan = SessionPlan.fromJson(Json.parseToJsonElement(planText))!!
        val live = plan.executable
        val slots = PlanMath.sequence(plan, maxes, cfs)
        val want = expected.getValue("slots").jsonArray.map { it.jsonObject }
        assertEquals(want.size, slots.size, "$name: slot count")
        for ((slot, w) in slots.zip(want)) {
            val where = "$name slot ${slot.setIndex}.${slot.repIndex}"
            assertEquals(int(w["setIndex"]), slot.setIndex, where)
            assertEquals(int(w["repIndex"]), slot.repIndex, where)
            assertEquals(string(w, "side"), slot.side.rawValue, where)
            assertEquals(string(w, "grip"), slot.grip.key, where)
            assertEquals(string(w, "basis"),
                PlanMath.targetBasis(live.sets[slot.setIndex], live).rawValue, "$where basis")
            val lo = double(w["targetLo"])
            val hi = double(w["targetHi"])
            val band = slot.targetBand
            if (lo == null || hi == null) {
                assertNull(band, "$where: no target, never a guess")
            } else {
                assertNotNull(band, "$where: expected $lo–$hi")
                assertTrue(near(band.start, lo) && near(band.endInclusive, hi), "$where: expected $lo–$hi, got $band")
            }
        }
        assertEquals(int(expected["untargetedGripCount"]), PlanMath.untargetedGripCount(plan, maxes, cfs),
            "$name: untargetedGripCount")
        val missing = PlanMath.missingReferences(plan, maxes, cfs).map {
            listOf(it.grip.key, it.side.rawValue, it.basis.rawValue, it.maxSeconds?.toString() ?: "null")
        }
        val wantMissing = expected.getValue("missing").jsonArray.map { it.jsonObject }.map {
            listOf(string(it, "grip"), string(it, "side"), string(it, "basis"), int(it["maxSeconds"])?.toString() ?: "null")
        }
        assertEquals(wantMissing, missing, "$name: missingReferences")
        val intensity = PlanMath.peakIntensity(plan, maxes, cfs)
        val wantIntensity = double(expected["peakIntensity"])
        if (wantIntensity == null) {
            assertNull(intensity, "$name: peakIntensity")
        } else {
            assertNotNull(intensity, "$name: peakIntensity expected $wantIntensity")
            assertTrue(near(intensity, wantIntensity), "$name: peakIntensity expected $wantIntensity, got $intensity")
        }
    }

    @Test
    fun theCanonicalPlanResolvesLikeTheOracle() {
        val plan = string(Fixtures.load("target-basis/plan.json").jsonObject, "plan")
        val tables = Fixtures.load("target-basis/tables.json").jsonObject
        val expected = Fixtures.load("target-basis/expected.json").jsonObject
        check(
            "plan.json",
            plan,
            maxTable(tables.getValue("maxes").jsonArray.map { it.jsonObject }),
            cfTable(tables.getValue("criticalForces").jsonArray.map { it.jsonObject }),
            expected,
        )
    }

    @Test
    fun theCanonicalPlanReEncodesByteForByte() {
        // Execute-as-written: the plan carries a routine-level CF band, a stray CF basis on
        // a kg set and an unknown raw — every one of them must survive a decode/encode.
        val text = string(Fixtures.load("target-basis/plan.json").jsonObject, "plan")
        val plan = SessionPlan.fromJson(Json.parseToJsonElement(text))!!
        assertEquals(text, BlobCodec.encode(plan, escapeSlashes = false))
    }

    @Test
    fun everyResolutionCaseMatches() {
        val cases = Fixtures.load("target-basis/cases.json").jsonObject.getValue("resolution").jsonArray
        assertTrue(cases.size >= 4, "the fixture lost its resolution cases")
        for (case in cases.map { it.jsonObject }) {
            check(
                string(case, "name"),
                string(case, "plan"),
                maxTable(case.getValue("maxes").jsonArray.map { it.jsonObject }),
                cfTable(case.getValue("criticalForces").jsonArray.map { it.jsonObject }),
                case,
            )
        }
    }

    @Test
    fun theCriticalForceFoldMatches() {
        val cases = Fixtures.load("target-basis/cases.json").jsonObject.getValue("fold").jsonArray
        assertTrue(cases.size >= 4, "the fixture lost its fold cases")
        for (case in cases.map { it.jsonObject }) {
            val name = string(case, "name")
            val asOf = case["asOf"]?.takeIf { it !is JsonNull }?.let { Instant.parse(it.jsonPrimitive.content) }
            val entries = case.getValue("entries").jsonArray.map { it.jsonObject }.map {
                CriticalForceTable.Entry(
                    gripKey = string(it, "grip"),
                    side = side(string(it, "side")),
                    protocolKey = string(it, "protocolKey"),
                    criticalForceKg = double(it["criticalForceKg"])!!,
                    recordedAt = Instant.parse(string(it, "recordedAt")),
                )
            }
            val table = CriticalForceTable.folding(entries, asOf)
            for (lookup in case.getValue("lookups").jsonArray.map { it.jsonObject }) {
                val grip = string(lookup, "grip")
                val hand = side(string(lookup, "side"))
                assertEquals(double(lookup["criticalForce"]), table.criticalForce(grip, hand), "$name $grip $hand")
                assertEquals(double(lookup["exact"]), table.exact(grip, hand), "$name $grip $hand exact")
            }
        }
    }

    @Test
    fun theCodableCasesDecodeLenientlyAndReEncodeCanonically() {
        val cases = Fixtures.load("target-basis/cases.json").jsonObject.getValue("codable").jsonArray
        assertTrue(cases.size >= 8, "the fixture lost its codable cases")
        for (case in cases.map { it.jsonObject }) {
            val name = string(case, "name")
            val input = Json.parseToJsonElement(string(case, "input"))
            val (encoded, reads) = when (val type = string(case, "type")) {
                "SetPlan" -> SetPlan.fromJson(input)!!.let { BlobCodec.encode(it, escapeSlashes = false) to it.targetBasis }
                "SessionPlan" -> SessionPlan.fromJson(input)!!.let { BlobCodec.encode(it, escapeSlashes = false) to it.targetBasis }
                else -> error("$name: unknown type $type")
            }
            assertEquals(string(case, "canonical"), encoded, "$name: canonical re-encode")
            assertEquals(string(case, "targetBasis"), reads.rawValue, "$name: reads as")
        }
    }
}
