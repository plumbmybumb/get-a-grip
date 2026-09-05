// SPDX-License-Identifier: MPL-2.0
// Original contributions Copyright 2026 Nuri Bruner.

package run.nuri.getagrip.engine

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.UUID

/// The whole training history as ONE self-describing Markdown document, written to be
/// pasted into a language model.
///
/// **It is deliberately English, whatever the app's language is.** A French export and an
/// English one would be two different schemas describing the same rows, and the reader on
/// the other end has to learn the vocabulary from the document itself — so the document
/// says so in its own first legend line and then keeps one set of words forever. Every
/// string in this file is a bare literal for exactly that reason: nothing here goes
/// through `L10n`, and nothing here may borrow a display name from a type that does
/// (`GripSpec.shortName`, `SessionKind.displayName`, `RPE.displayName` are all translated).
///
/// **PURE, and deterministic.** No clock is read here — the caller passes `generatedOn`
/// — and every ordering is total, so two calls on the same input produce byte-identical
/// strings. That is what makes the whole thing testable and what stops a diff of two
/// exports being noise.
///
/// It consumes VALUES only (`engine/` may not see the store); `AnalysisExportAssembler`
/// on the app side is the one place models become these.
///
/// TRANSLATION NOTE (from Shared/Engine/AnalysisExport.swift):
///   - `Date` → `java.time.Instant`; the fixture wire form is ISO-8601 UTC to the second.
///   - Swift's `sorted(by:)` predicates become `Comparator`s. Every one of them is
///     TOTAL, which is what makes the document byte-stable on both platforms; the one
///     that is not obviously so — the ascending sweep in `tablesAtSessionTime` — is the
///     reversed argument order of `newestFirst`, so its id tiebreak runs DESCENDING.
///     That looks like a typo and is not: it is what `sorted { newestFirst($1, $0) }`
///     means, and reproducing it is how the two engines agree on a tie.
///   - `String(format: "%.1f", …)` is C printf (ties to even) → `Fmt.fixed`, never
///     `String.format`. `Double.rounded()` is ties-away-from-zero, which is neither
///     `Math.rint` nor Kotlin's `round`, so it is spelled out in `roundedAwayFromZero`.
object AnalysisExport {

    // MARK: - What the document is made of

    /// How a session's hold time was measured. `WorkoutLog` carries no flag for this, so
    /// the assembler infers it — see `AnalysisExportAssembler`. It matters because a
    /// timer-only session's seconds come off the wall clock rather than off the gauge,
    /// and its kilogram columns are empty rather than zero.
    enum class Timing(val rawValue: String) {
        /// A gauge measured the pull: hold time accrued from device timestamps.
        gauge("gauge"),

        /// The runner ran the plan with no gauge attached: hold time is wall clock.
        timerOnly("timerOnly"),

        /// Nothing was timed — a session logged after the fact.
        logged("logged");

        companion object {
            fun fromRaw(raw: String): Timing? = entries.firstOrNull { it.rawValue == raw }
        }
    }

    /// One session, already flattened out of its model and its blobs.
    data class Session(
        /// Sort tiebreak, never printed — two sessions can share a start instant after a
        /// CloudKit merge and the order still has to be total.
        val id: UUID = UUID.randomUUID(),
        val day: DayStamp = DayStamp(0),
        val startedAt: Instant = Instant.EPOCH,
        /// The routine's name as it reads TODAY where the routine survives, else the
        /// name frozen into the log — the same resolution History's own list uses.
        val routineName: String = "",
        val kind: SessionKind = SessionKind.hang,
        val minutes: Int? = null,
        val rpe: RPE? = null,
        val fingerStrain: FingerStrain? = null,
        val peakKg: Double = 0.0,
        val avgKg: Double = 0.0,
        val totalHeldSeconds: Double = 0.0,
        val plannedReps: Int = 0,
        val completedReps: Int = 0,
        val timing: Timing = Timing.gauge,
        val reps: List<RepSummary> = emptyList(),
        val notes: String = "",
        val sessionsPerDayTarget: Int? = null,
        val finishedAt: Instant? = null,
        val plan: SessionPlan? = null,
    ) : JsonEncodable {

        /// Swift's `UUID.uuidString` is uppercase; the tiebreak compares that text.
        val idText: String get() = id.toString().uppercase(Locale.ROOT)

        override fun toJson(): JsonElement {
            val fields = linkedMapOf<String, JsonElement>(
                "id" to JsonPrimitive(idText),
                "day" to JsonPrimitive(day.raw),
                "startedAt" to JsonPrimitive(instantText(startedAt)),
                "routineName" to JsonPrimitive(routineName),
                "kind" to JsonPrimitive(kind.rawValue),
            )
            minutes?.let { fields["minutes"] = JsonPrimitive(it) }
            rpe?.let { fields["rpe"] = JsonPrimitive(it.rawValue) }
            fingerStrain?.let { fields["fingerStrain"] = JsonPrimitive(it.rawValue) }
            fields["peakKg"] = JsonPrimitive(peakKg)
            fields["avgKg"] = JsonPrimitive(avgKg)
            fields["totalHeldSeconds"] = JsonPrimitive(totalHeldSeconds)
            fields["plannedReps"] = JsonPrimitive(plannedReps)
            fields["completedReps"] = JsonPrimitive(completedReps)
            fields["timing"] = JsonPrimitive(timing.rawValue)
            fields["reps"] = JsonArray(reps.map { it.toJson() })
            fields["notes"] = JsonPrimitive(notes)
            sessionsPerDayTarget?.let { fields["sessionsPerDayTarget"] = JsonPrimitive(it) }
            finishedAt?.let { fields["finishedAt"] = JsonPrimitive(instantText(it)) }
            plan?.let { fields["plan"] = it.toJson() }
            return JsonObject(fields)
        }

        companion object {
            fun fromJson(element: JsonElement?): Session? =
                JsonRead.obj(element)?.let { fromJson(it) }

            fun fromJson(o: JsonObject): Session = Session(
                id = SetPlan.uuidFromJson(o["id"]) ?: UUID.randomUUID(),
                day = DayStamp(o.intOr("day", 0)),
                startedAt = instantFromJson(o["startedAt"]) ?: Instant.EPOCH,
                routineName = o.stringOr("routineName", ""),
                kind = SessionKind.fallback(o.stringOr("kind", SessionKind.hang.rawValue)),
                minutes = o.optionalInt("minutes"),
                rpe = o.optionalInt("rpe")?.let { RPE.fromRaw(it) },
                fingerStrain = o.optionalInt("fingerStrain")?.let { FingerStrain.fromRaw(it) },
                peakKg = o.doubleOr("peakKg", 0.0),
                avgKg = o.doubleOr("avgKg", 0.0),
                totalHeldSeconds = o.doubleOr("totalHeldSeconds", 0.0),
                plannedReps = o.intOr("plannedReps", 0),
                completedReps = o.intOr("completedReps", 0),
                timing = Timing.fromRaw(o.stringOr("timing", Timing.gauge.rawValue)) ?: Timing.gauge,
                reps = o.optionalArray("reps")?.mapNotNull { RepSummary.fromJson(it) } ?: emptyList(),
                notes = o.stringOr("notes", ""),
                sessionsPerDayTarget = o.optionalInt("sessionsPerDayTarget"),
                finishedAt = instantFromJson(o["finishedAt"]),
                plan = SessionPlan.fromJson(o["plan"]),
            )
        }
    }

    /// One recorded max, as a value.
    data class MaxEntry(
        val grip: GripSpec = GripSpec(),
        val side: Side = Side.both,
        val kg: Double = 0.0,
        val day: DayStamp = DayStamp(0),
        /// Used for ordering and for "which max was current when this session happened".
        /// Never printed — the day is what the document shows.
        val recordedAt: Instant = Instant.EPOCH,
        val source: MaxSource = MaxSource.manual,
    ) : JsonEncodable {

        val gripKey: String get() = grip.key
        val maxKey: String get() = MaxTable.key(grip.key, side)

        override fun toJson(): JsonElement = JsonObject(
            linkedMapOf(
                "grip" to grip.toJson(),
                "side" to JsonPrimitive(side.rawValue),
                "kg" to JsonPrimitive(kg),
                "day" to JsonPrimitive(day.raw),
                "recordedAt" to JsonPrimitive(instantText(recordedAt)),
                "source" to JsonPrimitive(source.rawValue),
            )
        )

        companion object {
            fun fromJson(element: JsonElement?): MaxEntry? =
                JsonRead.obj(element)?.let { fromJson(it) }

            fun fromJson(o: JsonObject): MaxEntry = MaxEntry(
                grip = o.valueOr("grip", GripSpec()) { GripSpec.fromJson(it) },
                side = o.valueOr("side", Side.both) { Side.fromJson(it) },
                kg = o.doubleOr("kg", 0.0),
                day = DayStamp(o.intOr("day", 0)),
                recordedAt = instantFromJson(o["recordedAt"]) ?: Instant.EPOCH,
                source = o.valueOr("source", MaxSource.manual) { MaxSource.fromJson(it) },
            )
        }
    }

    /// Everything the formatter is allowed to know.
    data class Input(
        val sessions: List<Session> = emptyList(),
        val maxes: List<MaxEntry> = emptyList(),
        /// The day the export was taken — the anchor the 8-week boundary is measured from.
        val today: DayStamp = DayStamp(0),
        /// Passed in, never read from a clock here.
        val generatedOn: DayStamp = DayStamp(0),
        /// What "a full day" currently means, frozen into the newest log. Sessions each
        /// carry their own, which is why this is stated rather than assumed.
        val sessionsPerDayTarget: Int = 1,
    ) : JsonEncodable {

        val isEmpty: Boolean get() = sessions.isEmpty() && maxes.isEmpty()

        override fun toJson(): JsonElement = JsonObject(
            linkedMapOf(
                "sessions" to JsonArray(sessions.map { it.toJson() }),
                "maxes" to JsonArray(maxes.map { it.toJson() }),
                "today" to JsonPrimitive(today.raw),
                "generatedOn" to JsonPrimitive(generatedOn.raw),
                "sessionsPerDayTarget" to JsonPrimitive(sessionsPerDayTarget),
            )
        )

        companion object {
            fun fromJson(element: JsonElement?): Input? =
                JsonRead.obj(element)?.let { fromJson(it) }

            fun fromJson(o: JsonObject): Input = Input(
                sessions = o.optionalArray("sessions")?.mapNotNull { Session.fromJson(it) } ?: emptyList(),
                maxes = o.optionalArray("maxes")?.mapNotNull { MaxEntry.fromJson(it) } ?: emptyList(),
                today = DayStamp(o.intOr("today", 0)),
                generatedOn = DayStamp(o.intOr("generatedOn", 0)),
                sessionsPerDayTarget = o.intOr("sessionsPerDayTarget", 1),
            )
        }
    }

    /// ISO-8601 UTC to the second — spelled out rather than left to `Instant.toString()`,
    /// whose minimal form and the Swift oracle's `ISO8601DateFormatter` must agree byte
    /// for byte inside a fixture.
    private val instantFormat: DateTimeFormatter =
        DateTimeFormatter.ofPattern("uuuu-MM-dd'T'HH:mm:ss'Z'").withZone(ZoneOffset.UTC)

    fun instantText(instant: Instant): String = instantFormat.format(instant)

    private fun instantFromJson(element: JsonElement?): Instant? {
        val text = JsonRead.string(element) ?: return null
        return try { Instant.parse(text) } catch (_: Exception) { null }
    }

    // MARK: - The boundary

    /// Sessions on or after `today - 55` are written out rep by rep; everything older is
    /// rolled up by week. 56 days INCLUDING today, which is what "the last 8 weeks" means
    /// to somebody looking at a calendar.
    const val detailedDays = 56

    fun detailCutoff(today: DayStamp): DayStamp = today - (detailedDays - 1)

    // MARK: - The document

    fun document(input: Input): String {
        val out = ArrayList<String>()

        val sessions = input.sessions.sortedWith(newestFirst)
        val maxes = input.maxes.sortedWith(oldestFirst)
        val cutoff = detailCutoff(input.today)
        val recent = sessions.filter { it.day >= cutoff }
        val older = sessions.filter { it.day < cutoff }

        out += title(input)
        out += legend(input, sessions)
        out += currentMaxes(maxes)
        out += maxHistory(maxes)
        out += recentSessions(recent, maxes, cutoff)
        out += weeklyRollups(older, cutoff)
        out += consistency(sessions, input.sessionsPerDayTarget, input.today)
        out += questions()

        return out.joinToString("\n") + "\n"
    }

    // MARK: - Title

    private fun title(input: Input): List<String> = listOf(
        "# Get a Grip — training export",
        "",
        "Generated ${isoDay(input.generatedOn)}. Loads are KILOGRAMS (kg); durations are SECONDS (s) unless the column says otherwise. Dates are YYYY-MM-DD.",
        "",
    )

    // MARK: - Legend

    private fun legend(input: Input, sessions: List<Session>): List<String> {
        val out = arrayListOf("## Legend", "")
        out.add("This document is written in English whatever language the app is set to, so that one fixed schema is being read every time. `—` in any cell means the value was never recorded; it is never a zero and never a guess.")
        out.add("")

        out.add("- **Pull** — one rep: a single hold on one grip with one hand (or with both). **Set** — a run of pulls sharing one grip. A session is a sequence of sets.")
        out.add("- **Plan s** is the hold the routine asked for. **Held s** is what was actually accrued while the load was over the rep's own threshold — so a pull that came off the edge halfway reads short rather than being rewritten.")
        out.add("- **How held time was measured** is stated on every session header. `gauge` means the seconds came from the force gauge's own sample timestamps. `timer-only` means the session ran with no gauge attached and the seconds came off the WALL CLOCK; those sessions carry no kilograms at all. `logged` means the session was written down after the fact — no reps, only a duration.")
        out.add("- **Peak kg / Avg kg are stored PER REP** — the numbers in the rep tables are that pull's own peak and its mean while engaged, not the session's. The session header's peak and average are the session-level figures kept beside them; for a session whose rep blob is missing or unreadable, the header figures are all that survives.")
        out.add("- **% max** is that rep's own PEAK divided by the max on file for that grip AND hand **as it stood on the day of the session** — a max recorded later never rewrites what an older session was pulling at. Resolution is specific-beats-general: a left or right pull uses that hand's max and falls back to a both-hands max; a BOTH-hands pull resolves only against a both-hands max, never against the two hands added together. No max on file means a blank, never a number.")
        out.add("- **Target kg** is what the rep was ASKED to pull for that hand, frozen at the time. Blank where the routine set no target or the grip had no max to take a percentage of.")
        out.add("- **Outcome** is one of `completed`, `earlyRelease` (came off the edge), `skipped` (deliberately passed over — skipped pulls ARE recorded, and they count toward the planned total but never toward the completed one; their kilogram cells are blank because the pull never happened, not zero), `aborted` (the session or the link ended mid-rep).")
        out.add("- **Hands**: `L` left, `R` right, `B` both.")
        out.add("- **A climbing day counts as training.** A day at the gym is more finger load than the hangboard session it displaced, so `climbVolume` and `climbLimit` sessions settle a day the same way a routine session does. `benchmark` is a max-testing day, logged automatically the first time a gauge-measured max lands. `hangManual` is a weighted or max hang done away from the gauge.")
        out.add("")

        out.add("**Grip notation** — `20mm 4F HC` is a 20 mm edge, four fingers, half crimp.")
        out.add("")
        out.add("Edge is the depth in millimetres. Then the digits on the hold:")
        out.add("")
        for (line in fingerCodeLegend(sessions, input.maxes)) {
            out.add("- $line")
        }
        out.add("")
        out.add("Then how the hand is set on it:")
        out.add("")
        for (line in positionCodeLegend(sessions, input.maxes)) {
            out.add("- $line")
        }
        out.add("")

        out.add("**The two effort axes** are graded by hand after a session and are optional — either or both may be blank. They are stored as 1–5 integers, not as a Borg CR-10 score:")
        out.add("")
        out.add("- **Effort** (systemic — how hard the session felt overall): 1 easy · 2 comfortable · 3 solid · 4 hard · 5 all I had.")
        out.add("- **Fingers** (local — how much it asked of the fingers specifically, the stronger signal in the climbing session-RPE literature): 1 nothing · 2 light · 3 worked · 4 taxed · 5 wrecked.")
        out.add("")
        return out
    }

    /// Only the codes the data actually uses, so the legend is a key to THIS document
    /// rather than a tour of a vocabulary nobody in it trained.
    private fun fingerCodeLegend(sessions: List<Session>, maxes: List<MaxEntry>): List<String> {
        val seen = LinkedHashSet<String>()
        val used = ArrayList<FingerSet>()
        for (grip in allGrips(sessions, maxes)) {
            val code = fingerCode(grip.fingers)
            if (seen.add(code)) used.add(grip.fingers)
        }
        if (used.isEmpty()) return listOf("`4F` — all four fingers. (No grips in this export yet.)")
        return used
            .map { fingerCode(it) to fingerEnglish(it) }
            .sortedBy { it.first }
            .map { "`${it.first}` — ${it.second}" }
    }

    private fun positionCodeLegend(sessions: List<Session>, maxes: List<MaxEntry>): List<String> {
        val seen = LinkedHashSet<String>()
        val used = ArrayList<GripPosition>()
        for (grip in allGrips(sessions, maxes)) {
            val code = positionCode(grip.position)
            if (seen.add(code)) used.add(grip.position)
        }
        if (used.isEmpty()) return listOf("`HC` — half crimp. (No grips in this export yet.)")
        return used
            .map { positionCode(it) to positionEnglish(it) }
            .sortedBy { it.first }
            .map { "`${it.first}` — ${it.second}" }
    }

    private fun allGrips(sessions: List<Session>, maxes: List<MaxEntry>): List<GripSpec> {
        // Deterministic: sessions in their given order, then reps in theirs, then maxes.
        val grips = ArrayList<GripSpec>()
        for (session in sessions.sortedWith(newestFirst)) {
            grips.addAll(session.reps.map { it.grip })
        }
        grips.addAll(maxes.sortedWith(oldestFirst).map { it.grip })
        return grips
    }

    // MARK: - Current maxes

    private fun currentMaxes(maxes: List<MaxEntry>): List<String> {
        val out = arrayListOf("## Current maxes", "")
        val current = newestPerKey(maxes)
        if (current.isEmpty()) {
            out.add("No maxes recorded. Every percentage target in this app is a fraction of a max, so a routine that prescribes percentages resolves to nothing until one exists.")
            out.add("")
            return out
        }
        out.add("The newest record for each grip and hand. `measured` means the gauge watched it happen; `typed` means it was entered by hand.")
        out.add("")
        out.add("| Grip | Hand | kg | Date | Source |")
        out.add("| --- | --- | ---: | --- | --- |")
        for (entry in current) {
            out.add("| ${gripCode(entry.grip)} | ${handCode(entry.side)} | ${kgText(entry.kg)} | ${isoDay(entry.day)} | ${sourceText(entry.source)} |")
        }
        out.add("")
        return out
    }

    /// Newest per grip AND hand — folding on the grip alone would let a right-hand max
    /// recorded second become the grip's current number and drop the left out of the
    /// table entirely, which is the same bug `MaxRecord.maxKey` exists to prevent.
    private fun newestPerKey(maxes: List<MaxEntry>): List<MaxEntry> {
        val newest = LinkedHashMap<String, MaxEntry>()
        for (entry in maxes.sortedWith(oldestFirst)) {
            newest[entry.maxKey] = entry
        }
        return newest.values.sortedWith(byGripThenHand)
    }

    // MARK: - Max history

    private fun maxHistory(maxes: List<MaxEntry>): List<String> {
        val out = arrayListOf("## Max history", "")
        if (maxes.isEmpty()) {
            out.add("Nothing recorded yet.")
            out.add("")
            return out
        }
        out.add("Every record ever written, oldest first within each grip and hand. Records are append-only, so this is the progression itself.")
        out.add("")

        val groups = LinkedHashMap<String, MutableList<MaxEntry>>()
        for (entry in maxes) groups.getOrPut(entry.maxKey) { ArrayList() }.add(entry)
        val order = groups.values.mapNotNull { it.firstOrNull() }.sortedWith(byGripThenHand)

        for (head in order) {
            val entries = (groups[head.maxKey] ?: emptyList<MaxEntry>()).sortedWith(oldestFirst)
            out.add("### ${gripCode(head.grip)} · ${handWord(head.side)}")
            out.add("")
            out.add("| Date | kg | Source |")
            out.add("| --- | ---: | --- |")
            for (entry in entries) {
                out.add("| ${isoDay(entry.day)} | ${kgText(entry.kg)} | ${sourceText(entry.source)} |")
            }
            out.add("")
        }
        return out
    }

    // MARK: - Sessions, last 8 weeks

    private fun recentSessions(sessions: List<Session>, maxes: List<MaxEntry>,
                               cutoff: DayStamp): List<String> {
        val out = arrayListOf("## Sessions, last 8 weeks", "")
        // When the whole history fits inside the window, saying "on or after <cutoff>"
        // implies eight weeks of behaviour that do not exist — the history's own start
        // is the honest bound (flagged by the feature's first real reader, 2026-08-28).
        val oldest = sessions.map { it.day.raw }.minOrNull()
        if (oldest != null && oldest >= cutoff.raw) {
            out.add("Every session on record — the history begins ${isoDay(DayStamp(oldest))}. Newest first.")
        } else {
            out.add("Every session on or after ${isoDay(cutoff)}, newest first.")
        }
        out.add("")
        if (sessions.isEmpty()) {
            out.add("No sessions in this window.")
            out.add("")
            return out
        }

        // ONE sweep for the whole window: the sessions are walked oldest-first while a
        // pointer advances over the maxes, so "the max as it stood that day" costs a
        // dictionary write per record rather than a filter per session.
        val tables = tablesAtSessionTime(sessions, maxes)

        for (session in sessions) {
            out.add("### ${sessionHeader(session)}")
            out.add("")
            out += sessionBody(session, tables[session.id] ?: MaxTable())
        }
        return out
    }

    private fun sessionHeader(session: Session): String {
        val parts = arrayListOf(isoDay(session.day), session.routineName, session.kind.rawValue)
        session.minutes?.let { parts.add("$it min") }
        // "held 0s" on a session that was never timed is noise dressed as a measurement.
        if (session.timing != Timing.logged) {
            parts.add("held ${durationText(session.totalHeldSeconds)}")
        }
        parts.add(timingWord(session.timing))
        return parts.filter { it.isNotEmpty() }.joinToString(" · ")
    }

    private fun sessionBody(session: Session, table: MaxTable): List<String> {
        val out = ArrayList<String>()
        val facts = ArrayList<String>()
        facts.add("Pulls: ${session.completedReps} completed of ${session.plannedReps} planned.")
        if (session.timing == Timing.gauge) {
            facts.add("Session peak ${kgText(session.peakKg)} kg, session average ${kgText(session.avgKg)} kg.")
        }
        facts.add("Effort ${axisText(session.rpe?.rawValue)}, fingers ${axisText(session.fingerStrain?.rawValue)}.")
        out.add(facts.joinToString(" "))
        out.add("")

        if (session.notes.isNotEmpty()) {
            out.add("Note: ${singleLine(session.notes)}")
            out.add("")
        }

        if (session.reps.isEmpty()) {
            if (session.kind == SessionKind.hang) {
                out.add("No rep detail survives for this session.")
                out.add("")
            }
            return out
        }

        out.add("| # | Hand | Grip | Plan s | Held s | Peak kg | Avg kg | Target kg | % max | Outcome |")
        out.add("| ---: | --- | --- | ---: | ---: | ---: | ---: | --- | ---: | --- |")
        for ((index, rep) in session.reps.withIndex()) {
            // A SKIPPED pull registers no load because it never happened, which is not
            // the same fact as "it registered zero" — printing 0.0 would put a pull that
            // was passed over into any average taken down this column.
            val measured = session.timing == Timing.gauge && rep.outcome != RepOutcome.skipped
            val peak = if (measured) kgText(rep.peakKg) else blank
            val avg = if (measured) kgText(rep.avgKg) else blank
            val bandRange = rep.targetBand
            val band = if (bandRange != null) {
                "${kgText(bandRange.start)}–${kgText(bandRange.endInclusive)}"
            } else {
                blank
            }
            val intensity = if (measured) {
                percentText(rep.peakKg, table.max(rep.grip.key, rep.side))
            } else {
                blank
            }
            out.add("| ${index + 1} | ${handCode(rep.side)} | ${gripCode(rep.grip)} | ${rep.targetSeconds} | ${secondsText(rep.heldSeconds)} | $peak | $avg | $band | $intensity | ${rep.outcome.rawValue} |")
        }
        out.add("")
        return out
    }

    /// The max table as it stood at each session's own start instant, keyed by session id.
    private fun tablesAtSessionTime(sessions: List<Session>,
                                    maxes: List<MaxEntry>): Map<UUID, MaxTable> {
        val ascending = sessions.sortedWith(oldestSessionFirst)
        val records = maxes.sortedWith(oldestFirst)
        val table = MaxTable()
        var cursor = 0
        val result = LinkedHashMap<UUID, MaxTable>()
        for (session in ascending) {
            while (cursor < records.size && !records[cursor].recordedAt.isAfter(session.startedAt)) {
                val entry = records[cursor]
                table.record(entry.kg, entry.gripKey, entry.side)
                cursor += 1
            }
            // Swift stores a VALUE here; `MaxTable` is a class on this side, so the
            // snapshot has to be taken explicitly or every session would share the last
            // sweep's table.
            result[session.id] = table.copy()
        }
        return result
    }

    // MARK: - Weekly rollups

    private fun weeklyRollups(sessions: List<Session>, cutoff: DayStamp): List<String> {
        val out = arrayListOf("## Older than 8 weeks, by week", "")
        if (sessions.isEmpty()) {
            out.add("Nothing older than ${isoDay(cutoff)}.")
            out.add("")
            return out
        }
        out.add("Everything before ${isoDay(cutoff)}, one row per week (weeks start on Monday). Median effort axes are over the sessions in that week that were graded.")
        out.add("")
        out.add("| Week of | Sessions | Climb days | Pulls done/planned | Time under tension | Median effort | Median fingers |")
        out.add("| --- | ---: | ---: | --- | ---: | ---: | ---: |")

        val weeks = LinkedHashMap<Int, MutableList<Session>>()
        for (session in sessions) weeks.getOrPut(weekStart(session.day).raw) { ArrayList() }.add(session)

        for (raw in weeks.keys.sortedDescending()) {
            val week = weeks[raw] ?: emptyList<Session>()
            val climbDays = week.filter { it.kind.isClimb }.map { it.day.raw }.toSet().size
            val done = week.sumOf { it.completedReps }
            val planned = week.sumOf { it.plannedReps }
            val tension = week.sumOf { it.totalHeldSeconds }
            val effort = medianText(week.mapNotNull { it.rpe?.rawValue })
            val fingers = medianText(week.mapNotNull { it.fingerStrain?.rawValue })
            out.add("| ${isoDay(DayStamp(raw))} | ${week.size} | $climbDays | $done/$planned | ${durationText(tension)} | $effort | $fingers |")
        }
        out.add("")
        return out
    }

    // MARK: - Consistency

    private fun consistency(sessions: List<Session>, target: Int, today: DayStamp): List<String> {
        val out = arrayListOf("## Consistency", "")
        val perDay = maxOf(1, target)
        out.add("Target: $perDay session${if (perDay == 1) "" else "s"} a day. (Each session also carries the target that was in force when it was logged, so a change to the target never re-scores days already lived.)")
        out.add("")
        if (sessions.isEmpty()) {
            out.add("No sessions on record.")
            out.add("")
            return out
        }
        out.add("Days trained per week across the whole history — a day counts if ANY session landed on it, climbing days included. Newest week first. A week's denominator counts only its days inside the recorded history, so the first and the current week are usually partial — a day before the history began, or still in the future, is not scored as a miss.")
        out.add("")
        out.add("| Week of | Days trained | Sessions |")
        out.add("| --- | ---: | ---: |")

        val weeks = LinkedHashMap<Int, MutableList<Session>>()
        for (session in sessions) weeks.getOrPut(weekStart(session.day).raw) { ArrayList() }.add(session)
        // The first recorded day bounds every denominator from below; today bounds it
        // from above. Scoring a day outside [firstDay … today] as a miss is how a
        // 19-for-19 streak printed as "6 of 7" on its opening week (found by the
        // feature's own first real reader, 2026-08-28).
        val firstDay = sessions.map { it.day.raw }.minOrNull() ?: today.raw
        for (raw in weeks.keys.sortedDescending()) {
            val week = weeks[raw] ?: emptyList<Session>()
            val days = week.map { it.day.raw }.toSet().size
            val span = minOf(raw + 6, today.raw) - maxOf(raw, firstDay) + 1
            out.add("| ${isoDay(DayStamp(raw))} | $days of ${maxOf(days, span)} | ${week.size} |")
        }
        out.add("")
        return out
    }

    // MARK: - Questions

    private fun questions(): List<String> = listOf(
        "## Questions worth asking",
        "",
        "1. Is one hand falling behind the other — in maxes, in held seconds, or in % max at the same prescription?",
        "2. Are the two effort axes diverging? Fingers climbing while overall effort stays flat is the early warning this schema exists to expose.",
        "3. How fast are the maxes actually moving per grip, and is the training load moving with them or ahead of them?",
        "4. What does the adherence pattern look like — which days and which weeks get missed, and does a missed day follow a hard one?",
        "",
    )

    // MARK: - Ordering (total, so the document is byte-stable)

    private val newestFirst = Comparator<Session> { a, b ->
        when {
            a.startedAt != b.startedAt -> b.startedAt.compareTo(a.startedAt)
            a.day != b.day -> b.day.compareTo(a.day)
            else -> a.idText.compareTo(b.idText)
        }
    }

    /// Swift's `sessions.sorted { newestFirst($1, $0) }` — the ARGUMENTS are swapped, not
    /// the comparator negated, so the id tiebreak flips with everything else and runs
    /// descending while the instants run ascending.
    private val oldestSessionFirst = Comparator<Session> { a, b ->
        when {
            a.startedAt != b.startedAt -> a.startedAt.compareTo(b.startedAt)
            a.day != b.day -> a.day.compareTo(b.day)
            else -> b.idText.compareTo(a.idText)
        }
    }

    private val oldestFirst = Comparator<MaxEntry> { a, b ->
        when {
            a.recordedAt != b.recordedAt -> a.recordedAt.compareTo(b.recordedAt)
            a.day != b.day -> a.day.compareTo(b.day)
            a.maxKey != b.maxKey -> a.maxKey.compareTo(b.maxKey)
            else -> a.kg.compareTo(b.kg)
        }
    }

    private val byGripThenHand = Comparator<MaxEntry> { a, b ->
        if (a.gripKey != b.gripKey) a.gripKey.compareTo(b.gripKey)
        else sideRank(a.side).compareTo(sideRank(b.side))
    }

    private fun sideRank(side: Side): Int = when (side) {
        Side.both -> 0
        Side.left -> 1
        Side.right -> 2
    }

    // MARK: - English vocabulary (never localized — see the type's note)

    const val blank = "—"

    fun gripCode(grip: GripSpec): String =
        "${grip.edgeMM}mm ${fingerCode(grip.fingers)} ${positionCode(grip.position)}"

    /// The same shapes `FingerSet.shortName` produces, spelled out here so a French
    /// device cannot export a different code for the same hand.
    fun fingerCode(fingers: FingerSet): String {
        if (fingers.contains(FingerSet.thumb)) {
            val rest = fingers.subtracting(FingerSet.thumb)
            return if (rest.isEmpty) "T" else "${fingerCode(rest)}+T"
        }
        return when (fingers) {
            FingerSet.four -> "4F"
            FingerSet.frontThree -> "F3"
            FingerSet.backThree -> "B3"
            FingerSet.frontTwo -> "F2"
            FingerSet.middleTwo -> "M2"
            FingerSet.backTwo -> "B2"
            FingerSet.index -> "I"
            FingerSet.middle -> "M"
            FingerSet.ring -> "R"
            FingerSet.little -> "L"
            else -> fingers.token
        }
    }

    fun fingerEnglish(fingers: FingerSet): String {
        if (fingers.contains(FingerSet.thumb)) {
            val rest = fingers.subtracting(FingerSet.thumb)
            return if (rest.isEmpty) "thumb only" else "${fingerEnglish(rest)}, thumb also on the hold"
        }
        return when (fingers) {
            FingerSet.four -> "all four fingers"
            FingerSet.frontThree -> "index, middle and ring"
            FingerSet.backThree -> "middle, ring and little"
            FingerSet.frontTwo -> "index and middle"
            FingerSet.middleTwo -> "middle and ring"
            FingerSet.backTwo -> "ring and little"
            FingerSet.index -> "index alone"
            FingerSet.middle -> "middle alone"
            FingerSet.ring -> "ring alone"
            FingerSet.little -> "little alone"
            else -> {
                val names = listOf("index", "middle", "ring", "little")
                val occupied = fingers.occupied
                val parts = names.filterIndexed { i, _ -> occupied.getOrElse(i) { false } }
                if (parts.isEmpty()) "no fingers recorded" else parts.joinToString(" + ")
            }
        }
    }

    /// Mirrors `GripPosition.shortName`'s English forms. An unknown raw from a newer
    /// build passes through uppercased, exactly as it does on screen.
    fun positionCode(position: GripPosition): String = when (position) {
        GripPosition.halfCrimp -> "HC"
        GripPosition.openHand -> "OH"
        GripPosition.fullCrimp -> "FC"
        GripPosition.drag -> "DR"
        GripPosition.pinch -> "PN"
        GripPosition.fingerCurl -> "CURL"
        else -> position.rawValue.uppercase(Locale.ROOT)
    }

    fun positionEnglish(position: GripPosition): String = when (position) {
        GripPosition.halfCrimp -> "half crimp"
        GripPosition.openHand -> "open hand"
        GripPosition.fullCrimp -> "full crimp"
        GripPosition.drag -> "drag"
        GripPosition.pinch -> "pinch (the thumb is always on a pinch)"
        GripPosition.fingerCurl -> "finger curl (isometric, starting in half crimp)"
        else -> "${position.rawValue} — a position this build does not name"
    }

    fun handCode(side: Side): String = when (side) {
        Side.left -> "L"
        Side.right -> "R"
        Side.both -> "B"
    }

    fun handWord(side: Side): String = when (side) {
        Side.left -> "left"
        Side.right -> "right"
        Side.both -> "both hands"
    }

    private fun sourceText(source: MaxSource): String =
        if (source == MaxSource.measured) "measured" else "typed"

    private fun timingWord(timing: Timing): String = when (timing) {
        Timing.gauge -> "gauge"
        Timing.timerOnly -> "timer-only (wall clock)"
        Timing.logged -> "logged"
    }

    // MARK: - Formatting (locale-free by construction)

    /// Swift's `String(format:)` with no locale argument is non-localized, so the
    /// separator is a dot on a French phone as well — which is the whole point of a fixed
    /// schema. `Fmt.fixed` is the printf-faithful twin; `String.format` is not.
    fun kgText(kg: Double): String {
        if (!kg.isFinite()) return blank
        return Fmt.fixed(kg, 1)
    }

    fun secondsText(seconds: Double): String {
        if (!seconds.isFinite()) return blank
        return Fmt.fixed(seconds, 1)
    }

    fun percentText(kg: Double, maxKg: Double?): String {
        if (maxKg == null || maxKg <= 0 || !kg.isFinite() || kg <= 0) return blank
        return "${roundedAwayFromZero(kg / maxKg * 100).toLong()}%"
    }

    fun axisText(raw: Int?): String = if (raw == null) blank else "$raw/5"

    fun medianText(values: List<Int>): String {
        if (values.isEmpty()) return blank
        val sorted = values.sorted()
        val middle = sorted.size / 2
        if (sorted.size % 2 == 0) {
            val mean = (sorted[middle - 1] + sorted[middle]).toDouble() / 2
            return if (mean == roundedAwayFromZero(mean)) "${mean.toLong()}" else Fmt.fixed(mean, 1)
        }
        return "${sorted[middle]}"
    }

    fun durationText(seconds: Double): String {
        val total = roundedAwayFromZero(seconds).toLong()
        if (total <= 0) return "0s"
        val hours = total / 3600
        val minutes = (total % 3600) / 60
        val secs = total % 60
        if (hours > 0) return "${hours}h ${minutes}m"
        if (minutes > 0) return "${minutes}m ${secs}s"
        return "${secs}s"
    }

    /// Swift's `Double.rounded()` is `.toNearestOrAwayFromZero` — neither `Math.rint`
    /// (ties to even) nor Kotlin's `round` is that, so it is written out.
    private fun roundedAwayFromZero(value: Double): Double =
        if (value < 0) -kotlin.math.floor(-value + 0.5) else kotlin.math.floor(value + 0.5)

    /// YYYY-MM-DD, built out of `DayStamp`'s own fixed proleptic-Gregorian calendar — no
    /// formatter, no locale, no time zone anywhere in the answer.
    fun isoDay(day: DayStamp): String {
        val date = day.localDate()
        val year = date.year.toString().padStart(4, '0')
        val month = date.monthValue.toString().padStart(2, '0')
        val dayOfMonth = date.dayOfMonth.toString().padStart(2, '0')
        return "$year-$month-$dayOfMonth"
    }

    /// Monday-start week containing `day`. Day 0 is a Thursday, hence the +3.
    fun weekStart(day: DayStamp): DayStamp {
        val offset = ((day.raw + 3) % 7 + 7) % 7
        return DayStamp(day.raw - offset)
    }

    /// A note pasted into a table or a header cannot carry newlines or pipes.
    private fun singleLine(text: String): String =
        text.replace("\n", " ").replace("|", "/").trim()

    enum class CSVScope { recent, all, workout }
    enum class CSVDetail { summary, pulls }
    data class CSVDocument(val text: String, val filename: String, val sessionCount: Int,
                           val pullCount: Int, val maxCount: Int) {
        val byteCount: Int = text.toByteArray(Charsets.UTF_8).size
        val isEmpty: Boolean get() = sessionCount == 0 && maxCount == 0
    }

    fun csv(input: Input, scope: CSVScope = CSVScope.recent, detail: CSVDetail = CSVDetail.summary): CSVDocument {
        val cutoff = detailCutoff(input.today)
        val sessions = input.sessions.filter { scope != CSVScope.recent || it.day >= cutoff }.sortedWith(newestFirst)
        val maxes = if (scope == CSVScope.workout) emptyList() else input.maxes.filter {
            scope != CSVScope.recent || it.day >= cutoff
        }.sortedWith(oldestFirst)
        val tables = tablesAtSessionTime(sessions, input.maxes)
        val headers = "record,workout,date,started_utc,routine,kind,timing,minutes,effort_1_5,finger_strain_1_5,daily_target,completed,planned,set,pull,edge_mm,fingers,position,hand,planned_s,held_s,peak_kg,avg_kg,target_low_kg,target_high_kg,max_at_start_kg,outcome,source,notes,ended_utc,time_source,max_reference,started_elapsed_s,ended_elapsed_s,gap_before_s,planned_rest_s,planned_lead_in_s,recorded".split(",")
        val rows = arrayListOf(headers.joinToString(","))
        fun row(fields: Map<String, String>) { rows += headers.joinToString(",") { csvCell(fields[it] ?: "") } }
        val span = if (scope == CSVScope.recent) "since ${isoDay(cutoff)}" else scope.name
        row(mapOf("record" to "guide", "date" to isoDay(input.generatedOn), "notes" to "Get a Grip CSV v2. workout is a permanent UUID; (workout,pull) identifies a recorded pull. record=workout contains totals; set or pull rows describe the same work: do not add them to workout totals. Summary groups recorded pulls by set, grip, hand and identical prescription. recorded counts outcomes, including skips; completed counts completed outcomes only. Summary planned_s and held_s are sums, peak_kg is the maximum, avg_kg is weighted by credited held_s; outcome lists counts. Workbook planned is the full planned pull count, including unattempted pulls. Dates are local training days; UTC timestamps are absolute. Blank is unavailable, never zero. ended_utc is the stored finish: runner_completion for newly timed sessions; legacy_save_or_end may include time on an old save screen. Manual logs have no known end instant. started_elapsed_s and ended_elapsed_s are host-monotonic observations from session start, including pauses and waiting; ended marks outcome recording, not necessarily physical release. Old timing is not_recorded; not_started means no engagement before an outcome. Summary offsets span the group only when all performed pulls have timing. gap_before_s includes pauses and waiting, not just rest. planned_rest_s and planned_lead_in_s are saved prescriptions (sums on set rows); rest includes set breaks. kg is force in kilograms; held_s is credited time above threshold. timing is inferred: gauge if any pull has positive force, timerOnly otherwise; logged means manual or no surviving detail. Skips and timer-only pulls have no force values. Target bounds are frozen prescriptions. max_at_start_kg uses only records at or before start; max_reference distinguishes hand_specific, both_hands, both_hands_fallback and no_recorded_max_at_start. Missing does not prove a grip was never benchmarked. Never add hand maxes. Effort and strain are 1-5, not Borg CR10. Fingers: I=index M=middle R=ring L=little T=thumb. fingerCurl starts in half crimp. Formula-like text is apostrophe-protected. No raw force trace or gauge model is stored. Scope: $span; detail: ${detail.name}."))
        var pullCount = 0
        sessions.forEach { session ->
            val key = session.id.toString()
            row(mapOf("record" to "workout", "workout" to key, "date" to isoDay(session.day),
                "started_utc" to instantText(session.startedAt),
                "ended_utc" to if (session.timing == Timing.logged) "" else session.finishedAt?.let(::instantText).orEmpty(),
                "time_source" to if (session.timing == Timing.logged) "manual" else if (session.reps.any { it.endedElapsedSeconds != null }) "runner_completion" else "legacy_save_or_end", "routine" to session.routineName,
                "kind" to session.kind.rawValue, "timing" to session.timing.rawValue,
                "minutes" to (session.minutes?.toString() ?: ""),
                "effort_1_5" to (session.rpe?.rawValue?.toString() ?: ""),
                "finger_strain_1_5" to (session.fingerStrain?.rawValue?.toString() ?: ""),
                "daily_target" to (session.sessionsPerDayTarget?.toString() ?: ""),
                "completed" to session.completedReps.toString(), "planned" to session.plannedReps.toString(),
                "held_s" to if (session.timing != Timing.logged) csvNumber(session.totalHeldSeconds) else "",
                "peak_kg" to if (session.timing == Timing.gauge) csvNumber(session.peakKg) else "",
                "avg_kg" to if (session.timing == Timing.gauge) csvNumber(session.avgKg) else "",
                "notes" to session.notes))
            val table = tables[session.id] ?: MaxTable()
            val slots = session.plan?.let { PlanMath.sequence(it) }.orEmpty()
            var previousEnd: Double? = null
            val pullRows = mutableListOf<Map<String, String>>()
            session.reps.forEachIndexed { pull, rep ->
                val measured = session.timing == Timing.gauge && rep.outcome != RepOutcome.skipped
                val fields = (csvGrip(rep.grip, rep.side) + mapOf("record" to "pull", "workout" to key,
                    "date" to isoDay(session.day), "routine" to session.routineName, "timing" to session.timing.rawValue,
                    "set" to (rep.setIndex + 1).toString(), "pull" to (pull + 1).toString(),
                    "planned_s" to rep.targetSeconds.toString(), "held_s" to csvNumber(rep.heldSeconds),
                    "peak_kg" to if (measured) csvNumber(rep.peakKg) else "",
                    "avg_kg" to if (measured) csvNumber(rep.avgKg) else "",
                    "target_low_kg" to (rep.targetBand?.let { csvNumber(it.start) } ?: ""),
                    "target_high_kg" to (rep.targetBand?.let { csvNumber(it.endInclusive) } ?: ""),
                    "max_at_start_kg" to (table.max(rep.grip.key, rep.side)?.let(::csvNumber) ?: ""),
                    "outcome" to rep.outcome.rawValue)).toMutableMap()
                fields["max_reference"] = if (table.exact(rep.grip.key, rep.side) != null) {
                    if (rep.side == Side.both) "both_hands" else "hand_specific"
                } else if (table.max(rep.grip.key, rep.side) != null) "both_hands_fallback" else "no_recorded_max_at_start"
                val end = validElapsed(rep.endedElapsedSeconds)
                val start = validElapsed(rep.startedElapsedSeconds)?.takeIf { end != null && it <= end }
                fields["started_elapsed_s"] = start?.let(::csvNumber).orEmpty()
                fields["ended_elapsed_s"] = end?.let(::csvNumber).orEmpty()
                fields["time_source"] = if (end == null) "not_recorded" else if (start == null) "not_started" else "host_monotonic"
                previousEnd?.let { previous -> if (start != null && start >= previous) fields["gap_before_s"] = csvNumber(start - previous) }
                if (start != null) previousEnd = end else if (end == null) previousEnd = null
                slots.firstOrNull { it.setIndex == rep.setIndex && it.repIndex == rep.repIndex && it.side == rep.side && it.grip == rep.grip }?.let {
                    fields["planned_rest_s"] = it.restAfter.toString()
                    fields["planned_lead_in_s"] = it.leadInBefore.toString()
                }
                pullRows += fields
                pullCount++
            }
            if (detail == CSVDetail.pulls) pullRows.forEach(::row)
            else csvSummaries(pullRows, session.reps, session.timing == Timing.gauge).forEach(::row)
        }
        maxes.forEach { entry ->
            row(csvGrip(entry.grip, entry.side) + mapOf("record" to "max", "date" to isoDay(entry.day),
                "started_utc" to instantText(entry.recordedAt), "peak_kg" to csvNumber(entry.kg), "source" to entry.source.rawValue))
        }
        val suffix = if (scope == CSVScope.workout) "workout-${isoDay(sessions.firstOrNull()?.day ?: input.today)}" else "training-${scope.name}"
        return CSVDocument(rows.joinToString("\r\n") + "\r\n", "get-a-grip-$suffix-${detail.name}.csv", sessions.size, pullCount, maxes.size)
    }

    private fun validElapsed(value: Double?): Double? = value?.takeIf { it.isFinite() && it >= 0 }

    private fun csvSummaries(rows: List<Map<String, String>>, reps: List<RepSummary>, measured: Boolean): List<Map<String, String>> {
        val groups = rows.indices.groupBy { index ->
            val rep = reps[index]
            // Group original values before rounding the displayed target bounds.
            listOf(rep.setIndex.toString(), rep.grip.key, rep.side.rawValue, rep.targetSeconds.toString(),
                rep.targetBand?.start?.toString().orEmpty(), rep.targetBand?.endInclusive?.toString().orEmpty()).joinToString("|")
        }
        return groups.values.map { indices ->
            val values = indices.map { reps[it] }
            val result = rows[indices.first()].toMutableMap()
            result["record"] = "set"
            result["pull"] = ""
            result["gap_before_s"] = ""
            result["recorded"] = values.size.toString()
            result["completed"] = values.count { it.outcome == RepOutcome.completed }.toString()
            result["planned_s"] = values.sumOf { it.targetSeconds }.toString()
            result["held_s"] = csvNumber(values.sumOf { it.heldSeconds })
            val forces = if (measured) values.filter { it.outcome != RepOutcome.skipped } else emptyList()
            result["peak_kg"] = forces.maxOfOrNull { it.peakKg }?.let(::csvNumber).orEmpty()
            val weighted = forces.filter { it.heldSeconds > 0 && it.heldSeconds.isFinite() && it.avgKg.isFinite() }
            val seconds = weighted.sumOf { it.heldSeconds }
            result["avg_kg"] = if (seconds > 0) csvNumber(weighted.sumOf { it.avgKg * it.heldSeconds } / seconds) else ""
            result["outcome"] = values.groupingBy { it.outcome.rawValue }.eachCount().toSortedMap().entries.joinToString("|") { "${it.key}:${it.value}" }
            listOf("planned_rest_s", "planned_lead_in_s").forEach { field ->
                val numbers = indices.mapNotNull { rows[it][field]?.toIntOrNull() }
                result[field] = if (numbers.size == indices.size) numbers.sum().toString() else ""
            }
            val performed = indices.filter { rows[it]["time_source"] != "not_started" }
            val timed = performed.isNotEmpty() && performed.all { rows[it]["time_source"] == "host_monotonic" }
            result["started_elapsed_s"] = if (timed) rows[performed.first()]["started_elapsed_s"].orEmpty() else ""
            result["ended_elapsed_s"] = if (timed) rows[performed.last()]["ended_elapsed_s"].orEmpty() else ""
            result["time_source"] = if (timed) "host_monotonic" else if (performed.isEmpty()) "not_started" else "not_recorded"
            result
        }
    }

    private fun csvGrip(grip: GripSpec, side: Side) = mapOf("edge_mm" to grip.edgeMM.toString(),
        "fingers" to grip.fingers.token, "position" to grip.position.rawValue, "hand" to side.rawValue)
    private fun csvNumber(value: Double): String = if (value.isFinite()) kgText(value) else ""
    private fun csvCell(value: String): String {
        var text = value
        if (text.trim().firstOrNull()?.let { it in "=+-@" } == true) text = "'$text"
        if (text.firstOrNull() == '\t' || text.firstOrNull() == '\r') text = "'$text"
        return if (text.any { it in ",\"\r\n" }) "\"" + text.replace("\"", "\"\"") + "\"" else text
    }
}
