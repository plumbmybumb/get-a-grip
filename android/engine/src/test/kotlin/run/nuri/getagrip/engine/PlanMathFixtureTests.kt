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

/// `PlanMath.sequence` is the ONE list everything the app says about a routine folds
/// over — the estimate, the per-side totals, the row clocks, and the order the runner
/// executes. `Fixtures/planmath/sequences.json` replays whole routines through both
/// engines so a parallel closed form cannot appear on one platform and not the other.
class PlanMathFixtureTests {

    @Test
    fun sequencesMatchTheSharedFixture() {
        for (scenario in Fixtures.load("planmath/sequences.json").jsonArray.map { it.jsonObject }) {
            val name = scenario.getValue("name").jsonPrimitive.content
            val planText = scenario.getValue("plan").jsonPrimitive.content

            val plan = BlobCodec.decode(planText) { SessionPlan.fromJson(it) }
            assertNotNull(plan, "$name: the plan must decode")
            // `plan` is CANONICAL, so a decode/re-encode round trip is a free byte check
            // on every field the scenario exercises.
            assertEquals(planText, BlobCodec.encode(plan), "$name: the plan is canonical")

            val maxes = MaxTable()
            for (entry in scenario.getValue("maxes").jsonArray.map { it.jsonObject }) {
                maxes.record(
                    entry.getValue("kg").jsonPrimitive.content.toDouble(),
                    entry.getValue("grip").jsonPrimitive.content,
                    Side.fromRaw(entry.getValue("side").jsonPrimitive.content)!!,
                )
            }

            val slots = PlanMath.sequence(plan, maxes)
            val expected = scenario.getValue("slots").jsonArray.map { it.jsonObject }
            assertEquals(expected.size, slots.size, "$name: slot count")

            for ((index, slot) in slots.withIndex()) {
                val want = expected[index]
                fun int(key: String) = want.getValue(key).jsonPrimitive.content.toInt()
                fun text(key: String) = want.getValue(key).jsonPrimitive.content
                fun flag(key: String) = want.getValue(key).jsonPrimitive.content.toBoolean()
                val at = "$name: slot $index"

                assertEquals(int("setIndex"), slot.setIndex, "$at setIndex")
                assertEquals(int("repIndex"), slot.repIndex, "$at repIndex")
                assertEquals(text("side"), slot.side.rawValue, "$at side")
                assertEquals(text("grip"), slot.grip.key, "$at grip")
                assertEquals(int("holdSeconds"), slot.holdSeconds, "$at holdSeconds")
                assertEquals(int("leadInBefore"), slot.leadInBefore, "$at leadInBefore")
                assertEquals(int("restAfter"), slot.restAfter, "$at restAfter")
                assertEquals(flag("isFirstOfSet"), slot.isFirstOfSet, "$at isFirstOfSet")
                assertEquals(flag("isLastOfSet"), slot.isLastOfSet, "$at isLastOfSet")

                val lo = want.getValue("targetLo")
                val hi = want.getValue("targetHi")
                if (lo is JsonNull) {
                    assertNull(slot.targetBand, "$at must have no target")
                } else {
                    val band = slot.targetBand
                    assertNotNull(band, "$at must have a target")
                    assertEquals(lo.jsonPrimitive.content.toDouble(), band.start, 0.0001, "$at targetLo")
                    assertEquals(
                        hi.jsonPrimitive.content.toDouble(), band.endInclusive, 0.0001,
                        "$at targetHi",
                    )
                }
            }

            fun total(key: String) = scenario.getValue(key).jsonPrimitive.content.toInt()
            assertEquals(total("totalSeconds"), PlanMath.totalSeconds(plan), "$name: totalSeconds")
            assertEquals(total("tensionSeconds"), PlanMath.tensionSeconds(plan), "$name: tensionSeconds")
            assertEquals(total("totalReps"), PlanMath.totalReps(plan), "$name: totalReps")
            assertEquals(total("setCount"), PlanMath.setCount(plan), "$name: setCount")

            val live = plan.executable
            assertEquals(
                scenario.getValue("handSequence").jsonArray.map { sides ->
                    sides.jsonArray.map { it.jsonPrimitive.content }
                },
                live.sets.map { set -> PlanMath.handSequence(set, live).map { it.rawValue } },
                "$name: handSequence",
            )
        }
    }
}
