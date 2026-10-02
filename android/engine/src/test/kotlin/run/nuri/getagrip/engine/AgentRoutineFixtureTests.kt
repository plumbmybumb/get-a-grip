// SPDX-License-Identifier: MPL-2.0
// Original contributions Copyright 2026 Nuri Bruner.

package run.nuri.getagrip.engine

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.util.Locale
import java.util.UUID
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/// "Create with AI" across two engines. `Fixtures/agent/cases.json` is the iOS reader's
/// verdict on every hand-authored paste in `inputs.json` (`oracle agent generate`); this
/// reader must reach the same one — outcome, the routine as canonical JSON, and the note
/// codes in order — and copy the same instructions, byte for byte.
class AgentRoutineFixtureTests {

    /// The default routine name is English only while `L10n` is unset; pinned so a
    /// machine's locale cannot reach a formatted note.
    @BeforeTest
    fun pinLocale() {
        Locale.setDefault(Locale.US)
    }

    /// The oracle's ids: a counter, so a regeneration is byte-identical.
    private fun fixtureIDs(): () -> UUID {
        var next = 0L
        return { UUID.fromString(String.format(Locale.ROOT, "A6E70000-0000-4000-8000-%012d", next++)) }
    }

    /// `{plan, sessionsPerDay, isOnDemand}` through `BlobCodec`'s default escaping — Swift's
    /// `BlobCodec.encoder`, `.sortedKeys` alone.
    private class FixtureRoutine(val draft: RoutineDraft) : JsonEncodable {
        override fun toJson(): JsonElement = JsonObject(
            mapOf(
                "plan" to draft.plan.toJson(),
                "sessionsPerDay" to JsonPrimitive(draft.sessionsPerDay),
                "isOnDemand" to JsonPrimitive(draft.isOnDemand),
            )
        )
    }

    @Test
    fun theInstructionsAreTheOnesTheOracleWrote() {
        assertEquals(Fixtures.text("agent/instructions.txt"), AgentRoutine.instructions)
    }

    @Test
    fun everyPasteReadsToTheOraclesVerdict() {
        val rows = Fixtures.load("agent/cases.json").jsonArray.map { it.jsonObject }
        assertTrue(rows.isNotEmpty(), "agent/cases.json is empty — run `oracle agent generate`")
        for (row in rows) {
            val name = row.getValue("name").jsonPrimitive.content
            val input = row.getValue("input").jsonPrimitive.content
            val wantNotes = row.getValue("notes").jsonArray.map { it.jsonPrimitive.content }
            when (val outcome = AgentRoutine.read(input, fixtureIDs())) {
                is AgentRoutine.Outcome.Success -> {
                    assertEquals(row.getValue("outcome").jsonPrimitive.content, "ok", "$name: outcome")
                    assertEquals(row.getValue("routine").jsonPrimitive.content,
                        BlobCodec.encode(FixtureRoutine(outcome.reading.draft)), "$name: routine")
                    assertEquals(wantNotes, outcome.reading.notes.map { it.code }, "$name: notes")
                }
                is AgentRoutine.Outcome.Failed -> {
                    assertEquals(row.getValue("outcome").jsonPrimitive.content, outcome.failure.code, "$name: outcome")
                    assertEquals(JsonNull, row.getValue("routine"), "$name: routine")
                    assertEquals(wantNotes, emptyList(), "$name: notes")
                }
            }
        }
    }
}
