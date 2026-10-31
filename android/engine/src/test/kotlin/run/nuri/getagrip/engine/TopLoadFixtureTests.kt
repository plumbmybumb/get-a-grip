// SPDX-License-Identifier: MPL-2.0
// Original contributions Copyright 2026 Nuri Bruner.

package run.nuri.getagrip.engine

import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/// History's "Load per grip" line is `TrainingLoad.topLoadKg` — the hardest 5 % of held time.
/// `Fixtures/trend/top-load.json` holds the vectors both engines must reproduce, so the two
/// apps cannot plot two different numbers for the same session.
class TopLoadFixtureTests {

    @Test
    fun topLoadMatchesTheSharedFixture() {
        val cases = Fixtures.load("trend/top-load.json").jsonObject.getValue("cases").jsonArray
        assertTrue(cases.size >= 10, "the fixture lost its cases")
        for (case in cases.map { it.jsonObject }) {
            val name = case.getValue("name").jsonPrimitive.content
            val fraction = case.getValue("fraction").jsonPrimitive.content.toDouble()
            val reps = case.getValue("reps").jsonArray.map { it.jsonObject }.map { rep ->
                RepSummary(
                    avgKg = rep.getValue("avgKg").jsonPrimitive.content.toDouble(),
                    heldSeconds = rep.getValue("heldSeconds").jsonPrimitive.content.toDouble(),
                    outcome = RepOutcome.entries.first { it.rawValue == rep.getValue("outcome").jsonPrimitive.content },
                )
            }
            val actual = TrainingLoad.topLoadKg(reps, fraction)
            val expected = case.getValue("expectKg")
            if (expected is JsonNull) {
                assertNull(actual, "$name: blank, never a number")
            } else {
                val want = expected.jsonPrimitive.content.toDouble()
                assertNotNull(actual, "$name: expected $want")
                assertTrue(abs(actual - want) < 1e-9, "$name: expected $want, got $actual")
            }
        }
    }

    @Test
    fun theDefaultFractionIsFivePercent() {
        // 100 s held: the top 5 s are 2 s at 40 kg and 3 s of the 30 kg rep.
        val reps = listOf(
            RepSummary(avgKg = 30.0, heldSeconds = 10.0),
            RepSummary(avgKg = 40.0, heldSeconds = 2.0),
            RepSummary(avgKg = 10.0, heldSeconds = 88.0),
        )
        val value = assertNotNull(TrainingLoad.topLoadKg(reps))
        assertTrue(abs(value - 34.0) < 1e-9, "got $value")
    }
}
