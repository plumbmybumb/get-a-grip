// SPDX-License-Identifier: MPL-2.0
// Original contributions Copyright 2026 Nuri Bruner.

package run.nuri.getagrip.engine

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

// What a finished session leaves behind. M3's runner produces these; they are declared
// now because `WorkoutLog`'s blob columns are typed on them, and a log written in M3
// must be readable by every build after it.
//
// TRANSLATION NOTE (from Shared/Engine/SessionResult.swift): every Swift enum here
// carries a `var name: String` display string. `name` is FINAL on Kotlin's `Enum`, so
// it cannot be shadowed — the display string is `displayName` on each enum below, and
// `rawValue` still carries the wire string. Nothing else about them moves.

/// How a rep ended. A closed domain — every one of these is a thing the runner can
/// decide, and there is no "unknown" because nothing else writes a rep.
enum class RepOutcome(val rawValue: String) {
    completed("completed"),

    /// Force dropped below threshold for longer than the dropout window. The rep ends
    /// promptly rather than freezing on screen waiting for a hand that has let go.
    earlyRelease("earlyRelease"),

    /// The user skipped it, deliberately.
    skipped("skipped"),

    /// The session or the rep was cut short — BLE dropout past the auto-abort, or the
    /// app leaving the foreground.
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

    /// What was ACCRUED over threshold, from device timestamps only. Never wall clock:
    /// BLE jitter and a UI hitch would otherwise invent hang time the device never
    /// measured. Double because the accrual is sub-second.
    val heldSeconds: Double = 0.0,
    val peakKg: Double = 0.0,

    /// Mean while engaged; segments below threshold are excluded, so a dropout does not
    /// drag the average toward zero and make a good rep look weak.
    val avgKg: Double = 0.0,

    /// **What this rep was ASKED to pull, in kilograms, for THIS hand** — null when the
    /// routine set no target or the grip had no max on file for that hand.
    ///
    /// It lives per REP rather than on the frozen plan because that is the only place
    /// the answer is single-valued: a set covers both hands, and a left and a right max
    /// resolve the same percentage to different kilograms. The plan blob keeps what was
    /// AUTHORED (the percentage); these keep what was DEMANDED, which is the number that
    /// has to survive somebody recording a new max next month.
    val targetLoKg: Double? = null,
    val targetHiKg: Double? = null,
    val outcome: RepOutcome = RepOutcome.completed,
    // Host-monotonic offsets; null for old blobs. These never determine force credit.
    val startedElapsedSeconds: Double? = null,
    val endedElapsedSeconds: Double? = null,
) : JsonEncodable {

    val targetBand: ClosedFloatingPointRange<Double>? get() = SetPlan.band(targetLoKg, targetHiKg)

    /// FROZEN — these keys are inside write-once log blobs the moment M3 ships.
    /// ADDITIVE ONLY: `targetLoKg`/`targetHiKg` arrived later and decode as null on every
    /// rep logged before they existed, which is the honest reading — that session's
    /// prescribed load genuinely was not recorded.
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
            // Absent on every rep logged before the column existed, and `optional` is the
            // right door for that: null means "no target was recorded", which is true.
            targetLoKg = o.optionalDouble("targetLoKg"),
            targetHiKg = o.optionalDouble("targetHiKg"),
            outcome = o.valueOr("outcome", RepOutcome.completed) { RepOutcome.fromJson(it) },
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
/// The app was built around one ritual — twice-a-day no-hangs — and then ran into the
/// obvious fact that a climbing session is finger training too (Nuri, 2026-08-05:
/// *"there are days I go climb at the gym and it interrupts my twice-a-day
/// hangboarding. It's not really fair to say that I didn't train"*). A day spent
/// bouldering at your limit is MORE finger load than the routine it displaced, and an
/// app that scored it as a miss was lying about the week.
///
/// **A climb COMPLETES THE DAY** — see `TemplateStore.dayIsComplete`. It does not merely
/// fill a slot: no reminder fires afterwards and the day reads as done. An extra hang
/// session stays available and still logs, because after an easy volume evening one is
/// perfectly reasonable; it is offered, never asked for. A hang logged by hand is
/// different: it tallies like a routine session, because it is still one of the day's
/// sessions rather than a reason to settle the day outright.
///
/// One enum rather than a kind plus a separate style column: every question the app asks
/// is either "was this a climb" or "which sort of session", and both fall straight out
/// of the case.
enum class SessionKind(val rawValue: String) {
    /// The routine — a hangboard/no-hang session the runner drove.
    hang("hang"),

    /// Mileage: laps, circuits, an easy evening. Real load, sub-maximal.
    climbVolume("climbVolume"),

    /// Hard bouldering or projecting — a maximal finger stimulus.
    climbLimit("climbLimit"),

    /// Weighted or max hangs done away from the gauge.
    hangManual("hangManual"),

    /// A max-testing day: gauge-measured maxes were recorded. Logged automatically the
    /// first time a MEASURED max lands on a day — never by typing a number, because
    /// typing is not training. One per day; see `TemplateStore.recordMax`.
    benchmark("benchmark");

    val isClimb: Boolean get() = this == climbVolume || this == climbLimit

    /// Counts toward the day's hang tally — the runner's own sessions and hangs logged
    /// by hand.
    val countsAsHang: Boolean get() = this == hang || this == hangManual

    /// The kinds the log sheet may write. `hang` is the runner's to write and
    /// `benchmark` is `recordMax`'s; neither may be created by hand.
    val isLoggedByHand: Boolean get() = isClimb || this == hangManual

    /// SETTLES THE DAY: no reminder fires after it and the day reads as done. Climbs
    /// because the training happened elsewhere; a benchmark because maximal testing IS
    /// a maximal finger stimulus — being nagged to hangboard after pulling limit maxes
    /// is the same failure as being nagged after the gym.
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

    /// What each style actually is, in the words a climber would use. Shown under the
    /// picker, because "volume" and "limit" are jargon someone may only half-know and a
    /// mis-picked one quietly mis-describes the week.
    val explainer: String
        get() = when (this) {
            hang -> L10n.tr("A session on the board.")
            climbVolume -> L10n.tr("Laps, circuits, an easy evening — real load, well short of maximal.")
            climbLimit -> L10n.tr("Hard bouldering or projecting — a maximal pull on your fingers.")
            hangManual -> L10n.tr("Weighted or max hangs you did on your own — no gauge behind it.")
            benchmark -> L10n.tr("A max-testing day — new ceilings on the gauge.")
        }

    companion object {
        fun fromRaw(raw: String): SessionKind? = entries.firstOrNull { it.rawValue == raw }

        /// An unknown raw from a newer build reads as `hang`, which is what every row
        /// written before this column existed means. It is also the CONSERVATIVE
        /// direction: a future kind misread as a hang session under-counts the day and
        /// asks for more training, where the opposite would silently excuse a day nobody
        /// trained.
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
