// SPDX-License-Identifier: MPL-2.0
// Original contributions Copyright 2026 Nuri Bruner.

package run.nuri.getagrip.engine

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/// The share format across two engines. **Cross-decode is the contract, not byte-identical
/// URLs** — Apple's raw-deflate output and Java's legitimately differ for the same bytes,
/// and nothing in the app ever compares two encoders' output. So each side decodes the
/// OTHER side's codes: `Fixtures/share/urls.json` holds the iOS oracle's, and the last
/// test here writes `urls-android.json` for `oracle share verify` to read back.
///
/// Set ids are ZEROED in every recorded envelope: import remints them by design, so they
/// are the one part of the plan the two engines must not agree on.
class ShareFixtureTests {

    private val zero = UUID.fromString("00000000-0000-0000-0000-000000000000")

    private fun withoutSetIDs(plan: SessionPlan): SessionPlan =
        plan.copy(sets = plan.sets.map { it.copy(id = zero) })

    /// The canonical `{v, plan, sessionsPerDay, isOnDemand}` the fixture pins — encoded
    /// through `BlobCodec`'s DEFAULT escaping, matching Swift's `BlobCodec.encoder`
    /// (`.sortedKeys` alone). The share URL itself is the only place slashes stay bare.
    private fun envelopeText(draft: RoutineDraft): String? =
        BlobCodec.encode(
            RoutineShare.Envelope(
                v = RoutineShare.currentVersion,
                plan = withoutSetIDs(draft.plan),
                sessionsPerDay = draft.sessionsPerDay,
                isOnDemand = draft.isOnDemand,
            )
        )

    private data class Verdict(val outcome: String, val envelope: String?)

    private fun decodeOutcome(url: String): Verdict =
        try {
            Verdict("ok", envelopeText(RoutineShare.draft(url)))
        } catch (error: RoutineShareError) {
            val name = when (error) {
                RoutineShareError.notARoutineLink -> "notARoutineLink"
                RoutineShareError.unreadable -> "unreadable"
                RoutineShareError.newerVersion -> "newerVersion"
                RoutineShareError.emptyRoutine -> "emptyRoutine"
                RoutineShareError.tooLarge -> "tooLarge"
            }
            Verdict(name, null)
        }

    private fun urlRows(relative: String): List<JsonObject> =
        Fixtures.load(relative).jsonArray.map { it.jsonObject }

    private fun JsonObject.text(key: String): String = getValue(key).jsonPrimitive.content

    // MARK: - The iOS oracle's codes, decoded here

    @Test
    fun everyOracleURLDecodesToItsRecordedOutcome() {
        val rows = urlRows("share/urls.json")
        assertTrue(rows.isNotEmpty(), "share/urls.json is empty — run `oracle share generate`")
        for (row in rows) {
            val name = row.text("name")
            val verdict = decodeOutcome(row.text("url"))
            assertEquals(row.text("outcome"), verdict.outcome, "$name: outcome")
            if (row.text("outcome") != "ok") continue
            assertEquals(row.text("envelope"), verdict.envelope,
                "$name: the envelope the URL decodes to")
        }
    }

    // MARK: - This engine's own round trip

    @Test
    fun theRoundtripDraftsSurviveThisEnginesEncoder() {
        for (row in urlRows("share/roundtrip.json")) {
            val name = row.text("name")
            val draft = draftFrom(row.getValue("draft").jsonObject, name)

            val url = assertNotNull(RoutineShare.url(draft), "$name: the encoder refused it")
            assertTrue(url.startsWith("getagrip://routine#"), "$name: the wire form")

            // The documented caps, on the code this engine just minted.
            val payload = assertNotNull(RoutineShare.dataFromBase64url(url.substringAfter('#')),
                "$name: the fragment is base64url")
            assertTrue(payload.size <= RoutineShare.maxCompressedBytes,
                "$name: ${payload.size} compressed bytes is over the cap")
            assertTrue(draft.plan.sets.size <= RoutineShare.maxSets, "$name: set count")

            val imported = RoutineShare.draft(url)
            assertEquals(RoutineShare.sanitizedName(draft.plan.name), imported.plan.name,
                "$name: the name cap is applied at BOTH ends")
            assertTrue(imported.plan.sets.all { it.note.length <= RoutineShare.maxNoteCharacters },
                "$name: the note cap is applied at BOTH ends")

            // The envelope is the payload: what went in, capped, is what comes back.
            val capped = draft.copy(
                plan = draft.plan.copy(
                    name = RoutineShare.sanitizedName(draft.plan.name),
                    sets = draft.plan.sets.map { it.copy(note = it.note.take(RoutineShare.maxNoteCharacters)) },
                ),
                sessionsPerDay = draft.setSessionsPerDay(draft.sessionsPerDay).sessionsPerDay,
            )
            assertEquals(envelopeText(capped), envelopeText(imported), "$name: the envelope")
        }
    }

    /// Writes `share/urls-android.json` — this engine's URLs for the same drafts, plus
    /// its own verdict on every hand-built case. `oracle share verify` reads it back, so
    /// the two engines are checked in BOTH directions rather than each against itself.
    @Test
    fun writeTheAndroidURLsForTheOracleToVerify() {
        val encoderNames = urlRows("share/roundtrip.json").map { it.text("name") }.toSet()
        val rows = ArrayList<JsonElement>()

        for (row in urlRows("share/roundtrip.json")) {
            val name = row.text("name")
            val draft = draftFrom(row.getValue("draft").jsonObject, name)
            val url = assertNotNull(RoutineShare.url(draft), "$name: the encoder refused it")
            rows.add(entry(name, url, decodeOutcome(url)))
        }
        // The hand-built cases are strings, so they are the same on both sides; what this
        // side contributes is its own VERDICT on each, which the oracle then re-checks.
        for (row in urlRows("share/urls.json")) {
            val name = row.text("name")
            if (name in encoderNames) continue
            val url = row.text("url")
            rows.add(entry(name, url, decodeOutcome(url)))
        }

        val text = Json { prettyPrint = true }.encodeToString(JsonArray.serializer(), JsonArray(rows))
        File(Fixtures.root, "share/urls-android.json").writeText(text + "\n")
        assertTrue(rows.size >= encoderNames.size, "every draft and every hand-built case")
    }

    private fun entry(name: String, url: String, verdict: Verdict): JsonObject = JsonObject(
        linkedMapOf(
            "envelope" to (verdict.envelope?.let { JsonPrimitive(it) } ?: JsonNull),
            "name" to JsonPrimitive(name),
            "outcome" to JsonPrimitive(verdict.outcome),
            "url" to JsonPrimitive(url),
        )
    )

    private fun draftFrom(source: JsonObject, name: String): RoutineDraft {
        val planText = source.getValue("plan").jsonPrimitive.content
        val plan = assertNotNull(BlobCodec.decode(planText) { SessionPlan.fromJson(it) },
            "$name: the plan must decode")
        // `plan` is CANONICAL, so a decode/re-encode round trip is a free byte check on
        // every field the draft exercises.
        assertEquals(planText, BlobCodec.encode(plan), "$name: the plan is canonical")
        return RoutineDraft(
            plan = plan,
            sessionsPerDay = source.getValue("sessionsPerDay").jsonPrimitive.content.toInt(),
            isOnDemand = source.getValue("isOnDemand").jsonPrimitive.content.toBoolean(),
        )
    }
}
