// SPDX-License-Identifier: MPL-2.0
// Original contributions Copyright 2026 Nuri Bruner.

package run.nuri.getagrip.engine

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

// What a finished session leaves behind. `WorkoutLog`'s blob columns are typed on these,
// so a log written by any build must stay readable by every build after it.
//
// TRANSLATION NOTE (from Shared/Engine/SessionResult.swift): every Swift enum here
// carries a `var name: String` display string. `name` is FINAL on Kotlin's `Enum`, so
// it cannot be shadowed — the display string is `displayName` on each enum below, and
// `rawValue` still carries the wire string. Nothing else about them moves.

/// How a rep ended, including historical outcomes retained for readable old exports.
/// Unrecognized stored outcomes decode conservatively as aborted.
enum class RepOutcome(val rawValue: String) {
    completed("completed"),

    /// Historical outcome only; current builds never end a rep on a dropout timeout.
    earlyRelease("earlyRelease"),

    /// The user skipped it, deliberately.
    skipped("skipped"),

    /// Explicit session abort, or an unreadable stored outcome. A dropout or
    /// background transition alone never auto-aborts a pull.
    aborted("aborted");

    companion object {
        fun fromRaw(raw: String): RepOutcome? = entries.firstOrNull { it.rawValue == raw }
        fun fromJson(element: JsonElement?): RepOutcome? =
            JsonRead.string(element)?.let { fromRaw(it) }
    }
}

/// One pull, as it actually happened.
data class RepSummary(
    val setIndex: Int = 0,
    val repIndex: Int = 0,
    val side: Side = Side.left,

    /// EMBEDDED, not referenced: history stays meaningful forever without resolving a
    /// routine that may have been edited beyond recognition or deleted outright.
    val grip: GripSpec = GripSpec(),

    /// What was AUTHORED — kept beside `heldSeconds` so a short rep reads as a short
    /// rep rather than as a rewritten plan.
    val targetSeconds: Int = 10,

    /// What was ACCRUED over threshold, from device timestamps only — never wall clock,
    /// where BLE jitter or a UI hitch would invent hang time. Double: it is sub-second.
    val heldSeconds: Double = 0.0,
    val peakKg: Double = 0.0,

    /// Mean while engaged; segments below threshold are excluded, so a dropout does not
    /// drag the average toward zero and make a good rep look weak.
    val avgKg: Double = 0.0,

    /// **What this rep was ASKED to pull, in kilograms, for THIS hand** — null when the
    /// routine set no target or the grip had no max on file for that hand. Per REP
    /// because only there is the answer single-valued (the hands have different maxes).
    /// The plan keeps what was AUTHORED; this keeps what was DEMANDED, which must survive
    /// a new max recorded next month.
    val targetLoKg: Double? = null,
    val targetHiKg: Double? = null,
    val outcome: RepOutcome = RepOutcome.completed,
    // Host-monotonic offsets; null for old blobs. These never determine force credit.
    val startedElapsedSeconds: Double? = null,
    val endedElapsedSeconds: Double? = null,
) : JsonEncodable {

    val targetBand: ClosedFloatingPointRange<Double>? get() = SetPlan.band(targetLoKg, targetHiKg)

    /// FROZEN — these keys live in write-once log blobs. ADDITIVE ONLY: later keys such
    /// as `targetLoKg`/`targetHiKg` decode as null on older reps, which is true.
    override fun toJson(): JsonElement {
        val fields = linkedMapOf<String, JsonElement>(
            "setIndex" to JsonPrimitive(setIndex),
            "repIndex" to JsonPrimitive(repIndex),
            "side" to JsonPrimitive(side.rawValue),
            "grip" to grip.toJson(),
            "targetSeconds" to JsonPrimitive(targetSeconds),
            "heldSeconds" to JsonPrimitive(heldSeconds),
            "peakKg" to JsonPrimitive(peakKg),
            "avgKg" to JsonPrimitive(avgKg),
            "outcome" to JsonPrimitive(outcome.rawValue),
        )
        targetLoKg?.let { fields["targetLoKg"] = JsonPrimitive(it) }
        targetHiKg?.let { fields["targetHiKg"] = JsonPrimitive(it) }
        startedElapsedSeconds?.let { fields["startedElapsedSeconds"] = JsonPrimitive(it) }
        endedElapsedSeconds?.let { fields["endedElapsedSeconds"] = JsonPrimitive(it) }
        return JsonObject(fields)
    }

    companion object {
        fun fromJson(element: JsonElement?): RepSummary? =
            JsonRead.obj(element)?.let { fromJson(it) }

        fun fromJson(o: JsonObject): RepSummary = RepSummary(
            setIndex = maxOf(0, o.intOr("setIndex", 0)),
            repIndex = maxOf(0, o.intOr("repIndex", 0)),
            side = o.valueOr("side", Side.left) { Side.fromJson(it) },
            grip = o.valueOr("grip", GripSpec()) { GripSpec.fromJson(it) },
            targetSeconds = maxOf(0, o.intOr("targetSeconds", 10)),
            // Negatives are not "a very short rep", they are a corrupt number that would
            // subtract from a session total.
            heldSeconds = maxOf(0.0, o.doubleOr("heldSeconds", 0.0)),
            peakKg = maxOf(0.0, o.doubleOr("peakKg", 0.0)),
            avgKg = maxOf(0.0, o.doubleOr("avgKg", 0.0)),
            // Absent on older reps: null means "no target was recorded", which is true.
            targetLoKg = o.optionalDouble("targetLoKg"),
            targetHiKg = o.optionalDouble("targetHiKg"),
            outcome = o.valueOr("outcome", RepOutcome.aborted) { RepOutcome.fromJson(it) },
            startedElapsedSeconds = o.optionalDouble("startedElapsedSeconds"),
            endedElapsedSeconds = o.optionalDouble("endedElapsedSeconds"),
        )
    }
}

/// Where a max came from. `measured` means the app watched it happen; `manual` means
/// the user typed it — and the difference matters when a percentage is computed off it.
enum class MaxSource(val rawValue: String) {
    manual("manual"),
    measured("measured");

    companion object {
        fun fromRaw(raw: String): MaxSource? = entries.firstOrNull { it.rawValue == raw }
        fun fromJson(element: JsonElement?): MaxSource? =
            JsonRead.string(element)?.let { fromRaw(it) }
    }
}

/// WHAT KIND of training a logged session was.
///
/// A climbing session is finger training too (Nuri, 2026-08-05: *"It's not really fair
/// to say that I didn't train"*); a day bouldering at your limit is MORE finger load
/// than the routine it displaced, and scoring it as a miss lied about the week.
///
/// **A climb COMPLETES THE DAY** (`TemplateStore.dayIsComplete`): no reminder fires
/// afterwards. An extra hang session is still offered, never asked for. A hang logged by
/// hand instead tallies like a routine session rather than settling the day.
///
/// One enum rather than kind plus style: every question is "was this a climb" or "which
/// sort of session", and both fall out of the case.
enum class SessionKind(val rawValue: String) {
    /// The routine — a hangboard/no-hang session the runner drove.
    hang("hang"),

    /// Mileage: laps, circuits, an easy evening. Real load, sub-maximal.
    climbVolume("climbVolume"),

    /// Hard bouldering or projecting — a maximal finger stimulus.
    climbLimit("climbLimit"),

    /// Weighted or max hangs done away from the gauge.
    hangManual("hangManual"),

    /// A testing day: a gauge-measured max or a critical force test was recorded. Logged
    /// automatically the first time either lands on a day, never by typing a number,
    /// because typing is not training. One per day; see `TemplateStore.benchmarkDayLog`.
    benchmark("benchmark");

    val isClimb: Boolean get() = this == climbVolume || this == climbLimit

    /// Counts toward the day's hang tally — the runner's own sessions and hangs logged
    /// by hand.
    val countsAsHang: Boolean get() = this == hang || this == hangManual

    /// The kinds the log sheet may write. `hang` is the runner's to write and
    /// `benchmark` is `recordMax`'s; neither may be created by hand.
    val isLoggedByHand: Boolean get() = isClimb || this == hangManual

    /// SETTLES THE DAY: no reminder fires after it. Climbs because the training happened
    /// elsewhere; a benchmark because maximal testing IS a maximal finger stimulus.
    val settlesDay: Boolean get() = isClimb || this == benchmark

    /// Title case, for a row that names it.
    val displayName: String
        get() = when (this) {
            hang -> L10n.tr("Hangboard")
            climbVolume -> L10n.tr("Volume climbing")
            climbLimit -> L10n.tr("Limit climbing")
            hangManual -> L10n.tr("Weighted hangs")
            benchmark -> L10n.tr("Benchmark")
        }

    /// The word alone, for a chip where "climbing" is already the context.
    val shortName: String
        get() = when (this) {
            hang -> L10n.tr("Hangboard")
            climbVolume -> L10n.tr("Volume")
            climbLimit -> L10n.tr("Limit")
            hangManual -> L10n.tr("Hangs")
            benchmark -> L10n.tr("Benchmark")
        }

    /// What each style is, in a climber's words, under the picker: "volume" and "limit"
    /// are jargon, and a mis-picked one mis-describes the week.
    val explainer: String
        get() = when (this) {
            hang -> L10n.tr("A session on the board.")
            climbVolume -> L10n.tr("Laps, circuits, an easy evening — real load, well short of maximal.")
            climbLimit -> L10n.tr("Hard bouldering or projecting — a maximal pull on your fingers.")
            hangManual -> L10n.tr("Weighted or max hangs you did on your own — no gauge behind it.")
            benchmark -> L10n.tr("A testing day — a max or a critical force test on the gauge.")
        }

    companion object {
        fun fromRaw(raw: String): SessionKind? = entries.firstOrNull { it.rawValue == raw }

        /// An unknown raw from a newer build reads as `hang`, as every row before this
        /// column did. CONSERVATIVE: a misread future kind under-counts the day and asks
        /// for more training, rather than silently excusing a day nobody trained.
        fun fallback(raw: String): SessionKind = fromRaw(raw) ?: hang
    }
}

/// How hard it felt, which is the one thing no force gauge can read.
enum class RPE(val rawValue: Int) {
    easy(1),
    comfortable(2),
    solid(3),
    hard(4),
    maximal(5);

    val displayName: String
        get() = when (this) {
            easy -> L10n.tr("Easy")
            comfortable -> L10n.tr("Comfortable")
            solid -> L10n.tr("Solid")
            hard -> L10n.tr("Hard")
            maximal -> L10n.tr("All I had")
        }

    companion object {
        fun fromRaw(raw: Int): RPE? = entries.firstOrNull { it.rawValue == raw }
        fun fromJson(element: JsonElement?): RPE? = JsonRead.int(element)?.let { fromRaw(it) }
    }
}

/// The LOCAL axis — how much the session asked of the fingers specifically, which the
/// climbing session-RPE literature found to be the stronger signal for training work.
/// Deliberately not called tendon load: a post-session slider cannot observe the share of
/// pulley versus muscle-belly load, and the clinical read on tendon load belongs later.
enum class FingerStrain(val rawValue: Int) {
    nothing(1),
    light(2),
    worked(3),
    taxed(4),
    wrecked(5);

    val displayName: String
        get() = when (this) {
            nothing -> L10n.tr("Nothing")
            light -> L10n.tr("Light")
            worked -> L10n.tr("Worked")
            taxed -> L10n.tr("Taxed")
            wrecked -> L10n.tr("Wrecked")
        }

    companion object {
        fun fromRaw(raw: Int): FingerStrain? = entries.firstOrNull { it.rawValue == raw }
        fun fromJson(element: JsonElement?): FingerStrain? =
            JsonRead.int(element)?.let { fromRaw(it) }
    }
}
