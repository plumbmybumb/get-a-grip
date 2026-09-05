// SPDX-License-Identifier: MPL-2.0
// Original contributions Copyright 2026 Nuri Bruner.

package run.nuri.getagrip.engine

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/// **The replay half of the cross-engine gate.** Every `Fixtures/runner/*.json` is fed
/// back through a fresh `SessionRunner`, step by step, asserting the exact cue list of
/// every step and the state the session ended in. `tools/oracle runner verify` does the
/// same against the real iOS engine, so a trace that passes both was produced by two
/// independent implementations agreeing on ~every millisecond of a workout.
///
/// A recorded step is the whole contract: `t`, the event, and what came back. Asserting
/// only the final state would let a cue schedule drift silently — and the cues ARE the
/// session as far as the climber is concerned.
class RunnerFixtureTests {

    @Test
    fun everyRecordedSessionReplaysIdentically() {
        val files = Fixtures.files("runner")
        assertTrue(files.isNotEmpty(), "no runner fixtures found")

        for (file in files) {
            val scenario = Fixtures.load("runner/${file.name}").jsonObject
            val name = scenario.getValue("name").jsonPrimitive.content

            val planText = scenario.getValue("plan").jsonPrimitive.content
            val plan = BlobCodec.decode(planText) { SessionPlan.fromJson(it) }
            assertNotNull(plan, "$name: the plan must decode")
            // `plan` is CANONICAL, so a decode/re-encode round trip is a free byte check.
            assertEquals(planText, BlobCodec.encode(plan), "$name: the plan is canonical")

            val maxes = MaxTable()
            for (entry in scenario.getValue("maxes").jsonArray.map { it.jsonObject }) {
                maxes.record(
                    entry.getValue("kg").jsonPrimitive.content.toDouble(),
                    entry.getValue("grip").jsonPrimitive.content,
                    Side.fromRaw(entry.getValue("side").jsonPrimitive.content)!!,
                )
            }

            val runner = SessionRunner(
                plan = plan,
                maxes = maxes,
                timerOnly = scenario.getValue("timerOnly").jsonPrimitive.boolean,
                maxCreditedSampleGapSeconds = scenario["maxCreditedSampleGapSeconds"]
                    ?.jsonPrimitive?.content?.toDouble(),
            )

            for ((index, step) in scenario.getValue("steps").jsonArray.withIndex()) {
                val row = step.jsonObject
                val t = row.getValue("t").jsonPrimitive.content.toDouble()
                val event = decodeEvent(row.getValue("event").jsonObject)
                val cues = runner.handle(event, at = t)
                assertEquals(
                    row.getValue("cues").jsonArray.map { it.jsonObject },
                    cues.map { RunnerTrace.encode(it) },
                    "$name: step $index ($event at $t)",
                )
            }

            val final = scenario.getValue("final").jsonObject
            assertEquals(
                final.getValue("phase").jsonPrimitive.content,
                RunnerTrace.phaseText(runner.phase),
                "$name: final phase",
            )
            assertEquals(
                final.getValue("completedRepCount").jsonPrimitive.content.toInt(),
                runner.completedRepCount,
                "$name: completedRepCount",
            )
            assertEquals(
                final.getValue("results").jsonArray.map { it.jsonPrimitive.content },
                runner.results.map { BlobCodec.encode(it) },
                "$name: results",
            )
        }
    }

    /// The inverse of `RunnerTrace.encode(RunnerEvent)`.
    private fun decodeEvent(o: JsonObject): RunnerEvent =
        when (val type = o.getValue("type").jsonPrimitive.content) {
            "start" -> RunnerEvent.Start
            "sample" -> RunnerEvent.Sample(
                ForceSample(
                    kg = o.getValue("kg").jsonPrimitive.content.toDouble(),
                    deviceMicros = o.getValue("micros").jsonPrimitive.content.toUInt(),
                    isBatchStart = o.getValue("batchStart").jsonPrimitive.boolean,
                ),
            )
            "tick" -> RunnerEvent.Tick
            "connectionLost" -> RunnerEvent.ConnectionLost
            "connectionRestored" -> RunnerEvent.ConnectionRestored
            "tareCommitted" -> RunnerEvent.TareCommitted
            "streamRestarted" -> RunnerEvent.StreamRestarted
            "pause" -> RunnerEvent.Pause
            "resume" -> RunnerEvent.Resume
            "skipRep" -> RunnerEvent.SkipRep
            "skipSet" -> RunnerEvent.SkipSet
            "abort" -> RunnerEvent.Abort
            else -> error("unknown runner event type '$type'")
        }
}
