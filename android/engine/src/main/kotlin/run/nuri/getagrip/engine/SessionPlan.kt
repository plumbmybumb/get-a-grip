// SPDX-License-Identifier: MPL-2.0
// Original contributions Copyright 2026 Nuri Bruner.

package run.nuri.getagrip.engine

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale
import java.util.UUID

// The routine, as value types. Everything here is what the runner executes, what the
// builder edits and what a log freezes — one vocabulary, no DTO layer, no separate
// "template model" that has to be kept in step.
//
// Vocabulary note that runs through the whole app: the MODEL says rep, the UI says
// pull. One rep IS one pull; nothing counts halves.
//
// TRANSLATION NOTE (from Shared/Engine/SessionPlan.swift): Swift's `var name: String`
// on an enum cannot survive — `name` is final on Kotlin's `Enum` — so every enum's
// display string is `displayName` here. Swift's `mutating func` on a value type becomes
// a function that RETURNS the new value (`setSessionsPerDay`), and `didSet` clamping
// becomes a property initializer (`ReminderTime`).

// MARK: - Sides

enum class Side(val rawValue: String) {
    left("left"),
    right("right"),
    both("both");

    val other: Side
        get() = when (this) {
            left -> right
            right -> left
            both -> both
        }

    val displayName: String
        get() = when (this) {
            left -> L10n.tr("Left")
            right -> L10n.tr("Right")
            both -> L10n.tr("Both")
        }

    /// Set in caps because it is read at arm's length, mid-set, by someone whose eyes
    /// are on a fingerboard rather than on the phone.
    val prompt: String
        get() = when (this) {
            left -> L10n.tr("LEFT")
            right -> L10n.tr("RIGHT")
            both -> L10n.tr("BOTH")
        }

    companion object {
        fun fromRaw(raw: String): Side? = entries.firstOrNull { it.rawValue == raw }
        fun fromJson(element: JsonElement?): Side? = JsonRead.string(element)?.let { fromRaw(it) }
    }
}

/// How a set is shared between hands.
enum class HandMode(val rawValue: String) {
    alternateEachRep("alternateEachRep"), // L R L R … one pull at a time
    alternateEachSet("alternateEachSet"), // all of one side, then all of the other, WITHIN a set
    bothHands("bothHands");               // one pull, both hands

    /// How many sides a set covers. `repsPerSide × this` is its real rep count, and
    /// `PlanMath.repCount` is the only place that multiplication is allowed to happen.
    val sideCount: Int
        get() = when (this) {
            alternateEachRep, alternateEachSet -> 2
            bothHands -> 1
        }

    /// Every set starts here — alternation RESETS at each set boundary, so no set ever
    /// begins on the "wrong" hand because the set before it had an odd rep count.
    val startSide: Side
        get() = when (this) {
            alternateEachRep, alternateEachSet -> Side.left
            bothHands -> Side.both
        }

    val displayName: String
        get() = when (this) {
            alternateEachRep -> L10n.tr("Alternate each pull")
            alternateEachSet -> L10n.tr("One hand at a time")
            bothHands -> L10n.tr("Both hands")
        }

    val explainer: String
        get() = when (this) {
            alternateEachRep -> L10n.tr("Left, right, left, right — swapping hands every pull.")
            alternateEachSet -> L10n.tr("All the pulls on one hand, then all of them on the other, inside one set.")
            bothHands -> L10n.tr("One pull with both hands on the edge. Reps per side is just the number of pulls.")
        }

    companion object {
        fun fromRaw(raw: String): HandMode? = entries.firstOrNull { it.rawValue == raw }

        /// The decode door. A mode written by a newer build lands here as an unknown raw
        /// and becomes the default rather than throwing — the routine survives, one field
        /// is wrong, and `SessionTemplate.handModeRaw` still holds the original verbatim.
        fun fallback(raw: String): HandMode = fromRaw(raw) ?: alternateEachRep
    }
}

// MARK: - One row of the routine

data class SetPlan(
    /// Editor identity, not content identity. Two sets can be byte-identical and still
    /// be distinct rows — Nuri's protocol repeats front-2 at a different position.
    val id: UUID = UUID.randomUUID(),
    val grip: GripSpec = GripSpec(),

    /// Reps PER SIDE — the unit Nuri actually speaks ("6 reps each side"). The real
    /// count is this × `HandMode.sideCount`; the property NAME is the whole defence
    /// against the silent factor of two.
    val repsPerSide: Int = 6,

    /// null == follow the routine's rhythm. The prefill leaves every one of these null,
    /// which is what makes "change every rest to 25 s" a single edit.
    val holdSeconds: Int? = null,
    val restSeconds: Int? = null,
    val targetLoKg: Double? = null,
    val targetHiKg: Double? = null,

    /// This set's own percentage-of-max band. null == follow the routine's, which is what
    /// makes "everything at 17–22 %" a single edit. Only consulted when the set carries
    /// no explicit kg band — see `PlanMath.targetBand` for the precedence.
    val targetLoPercent: Double? = null,
    val targetHiPercent: Double? = null,
    val note: String = "",
) : JsonEncodable {

    val overridesTiming: Boolean get() = holdSeconds != null || restSeconds != null

    /// An explicit band ON THIS SET, of either kind — NOT "this set will show a target",
    /// which also depends on the routine's band and on a max existing for the grip.
    val hasTarget: Boolean get() = targetLoKg != null || targetHiKg != null
    val hasPercentTarget: Boolean get() = targetLoPercent != null || targetHiPercent != null

    /// Normalized on the way out: an upside-down band is never STORED, and is never
    /// returned either. One endpoint alone gives a degenerate range — a target line
    /// rather than a band, which is exactly what one endpoint means.
    val targetBand: ClosedFloatingPointRange<Double>? get() = band(targetLoKg, targetHiKg)

    /// This set's own percentage band, ignoring the routine's.
    val targetPercentBand: ClosedFloatingPointRange<Double>? get() = band(targetLoPercent, targetHiPercent)

    /// FROZEN — see `SessionPlan`'s coding keys. Additive only.
    override fun toJson(): JsonElement {
        val fields = linkedMapOf<String, JsonElement>(
            "id" to JsonPrimitive(id.toString().uppercase(Locale.ROOT)),
            "grip" to grip.toJson(),
            "repsPerSide" to JsonPrimitive(repsPerSide),
        )
        holdSeconds?.let { fields["holdSeconds"] = JsonPrimitive(it) }
        restSeconds?.let { fields["restSeconds"] = JsonPrimitive(it) }
        targetLoKg?.let { fields["targetLoKg"] = JsonPrimitive(it) }
        targetHiKg?.let { fields["targetHiKg"] = JsonPrimitive(it) }
        targetLoPercent?.let { fields["targetLoPercent"] = JsonPrimitive(it) }
        targetHiPercent?.let { fields["targetHiPercent"] = JsonPrimitive(it) }
        fields["note"] = JsonPrimitive(note)
        return JsonObject(fields)
    }

    companion object {
        /// Decode clamps. `repsRange` starts at 0 because a zero-rep set is representable
        /// (and dropped by `SessionPlan.executable`); the UI floor is 1.
        val repsRange = 0..20
        // Match the hold dial: positive whole seconds, including short 1–2 s pulls.
        val holdRange = 1..120
        val restRange = 0..600

        /// 1 %…100 %. The ceiling is 100 rather than something "sensible" like 60 because a
        /// max-effort routine is a legitimate thing to author, and the floor is above zero
        /// because a 0 % target is a target of nothing.
        val percentRange = 0.01..1.0

        /// The one place two optional endpoints become a range, shared with `SessionPlan` so
        /// kg bands and percentage bands cannot normalize differently.
        fun band(lo: Double?, hi: Double?): ClosedFloatingPointRange<Double>? = when {
            lo != null && hi != null -> minOf(lo, hi)..maxOf(lo, hi)
            lo != null -> lo..lo
            hi != null -> hi..hi
            else -> null
        }

        /// TRANSLATION NOTE: Foundation decodes a UUID from its canonical 8-4-4-4-12 hex
        /// text and throws on anything else, where `java.util.UUID.fromString` also
        /// accepts short groups ("1-1-1-1-1"). The shape is checked here so a malformed id
        /// falls back to a fresh UUID on both platforms rather than on only one.
        private val uuidText = Regex("[0-9a-fA-F]{8}(-[0-9a-fA-F]{4}){3}-[0-9a-fA-F]{12}")

        fun uuidFromJson(element: JsonElement?): UUID? {
            val text = JsonRead.string(element) ?: return null
            if (!uuidText.matches(text)) return null
            return try {
                UUID.fromString(text)
            } catch (_: IllegalArgumentException) {
                null
            }
        }

        fun fromJson(element: JsonElement?): SetPlan? = JsonRead.obj(element)?.let { fromJson(it) }

        fun fromJson(o: JsonObject): SetPlan = SetPlan(
            id = o.valueOr("id", UUID.randomUUID()) { uuidFromJson(it) },
            grip = o.valueOr("grip", GripSpec()) { GripSpec.fromJson(it) },
            repsPerSide = repsRange.clamping(o.intOr("repsPerSide", 6)),
            holdSeconds = o.optionalInt("holdSeconds")?.let { holdRange.clamping(it) },
            restSeconds = o.optionalInt("restSeconds")?.let { restRange.clamping(it) },
            targetLoKg = o.optionalDouble("targetLoKg"),
            targetHiKg = o.optionalDouble("targetHiKg"),
            targetLoPercent = o.optionalDouble("targetLoPercent")?.let { percentRange.clamping(it) },
            targetHiPercent = o.optionalDouble("targetHiPercent")?.let { percentRange.clamping(it) },
            note = o.stringOr("note", ""),
        )
    }
}

// MARK: - The routine

/// What the runner executes and what a log freezes. Deliberately carries NO
/// scheduling: reminders and sessions-a-day are things a person arranges, not things a
/// session does, and mixing them in is how a "session" grows into Frez's three layers.
data class SessionPlan(
    val name: String = L10n.tr("Daily no-hangs"),
    val sets: List<SetPlan> = emptyList(),
    val handMode: HandMode = HandMode.alternateEachRep,

    /// RHYTHM — the routine-level defaults every set inherits unless it overrides.
    val holdSeconds: Int = 10,
    val restSeconds: Int = 20,
    val setBreakSeconds: Int = 60,

    /// "Get ready" before the FIRST rep of each set, not before every rep.
    val leadInSeconds: Int = 5,

    /// Engagement DETECTOR, not intensity: "you have taken the load". One value for the
    /// whole routine because ~2 kg sits below every set's working load. Intensity lives
    /// in each set's target band.
    val thresholdKg: Double = 2.0,

    /// Hold the rest countdown until you are actually OFF the edge.
    ///
    /// Default ON, which is a deliberate behaviour change for routines written before
    /// this existed: starting the clock the instant the hold completes charges your rest
    /// for the two or three seconds it takes to stand down, every rep, so a 20 s rest was
    /// never 20 s of rest. Off is still honest — a fixed cadence you pace yourself to.
    val waitForReleaseBeforeRest: Boolean = true,

    /// Whether leaving the TARGET BAND stops the rep clock.
    ///
    /// Default ON, which is the rule the band exists to enforce: a rep prescribed at
    /// 22.5–34 kg should not be bankable at 12. But the band is a prescription, not a
    /// referee, and there are honest reasons to want it drawn without it judging —
    /// training by feel on a day your fingers disagree with last month's numbers, or a
    /// grip whose max is stale. Off, the band still draws as a lane on the trace and the
    /// clock runs whenever you are ENGAGED, whatever the load.
    ///
    /// It never loosens the engagement threshold: let go of the edge and the rep still
    /// stops, because that is not a question about range, it is a question about whether
    /// you are pulling at all.
    val pausesOutsideTargetBand: Boolean = true,

    /// TARGET LOAD as a fraction of your max on whichever grip a set uses — the routine
    /// default every set inherits, exactly like `holdSeconds`.
    ///
    /// A percentage rather than kilograms because the prescription IS a fraction ("20 %
    /// of max"), and because ONE band then means the right load on all six grips at once:
    /// a four-finger half crimp and a middle-2 have very different maxes and the same
    /// intensity. Kilograms would freeze one day's arithmetic and go stale the next time
    /// a max is recorded — which is the whole reason this is not just a seeded number.
    val targetLoPercent: Double? = null,
    val targetHiPercent: Double? = null,
) : JsonEncodable {

    /// null when no band is set. Normalized the same way `SetPlan.targetBand` is — one
    /// endpoint alone is a LINE, which is what one endpoint means.
    val targetPercentBand: ClosedFloatingPointRange<Double>?
        get() = SetPlan.band(targetLoPercent, targetHiPercent)

    /// Sets that will actually run. Everything in `PlanMath` operates on this, and the
    /// runner freezes THIS, so `RepSummary.setIndex` is unambiguous forever after.
    val executable: SessionPlan get() = copy(sets = sets.filter { it.repsPerSide > 0 })

    /// FROZEN — names already in every routine blob ever written. ADDITIVE only: a new
    /// key is fine (older blobs simply lack it and take the decoder's default), a
    /// renamed or removed one silently drops a field on every routine on disk.
    override fun toJson(): JsonElement {
        val fields = linkedMapOf<String, JsonElement>(
            "name" to JsonPrimitive(name),
            "sets" to JsonArray(sets.map { it.toJson() }),
            "handMode" to JsonPrimitive(handMode.rawValue),
            "holdSeconds" to JsonPrimitive(holdSeconds),
            "restSeconds" to JsonPrimitive(restSeconds),
            "setBreakSeconds" to JsonPrimitive(setBreakSeconds),
            "leadInSeconds" to JsonPrimitive(leadInSeconds),
            "thresholdKg" to JsonPrimitive(thresholdKg),
            "waitForReleaseBeforeRest" to JsonPrimitive(waitForReleaseBeforeRest),
            "pausesOutsideTargetBand" to JsonPrimitive(pausesOutsideTargetBand),
        )
        targetLoPercent?.let { fields["targetLoPercent"] = JsonPrimitive(it) }
        targetHiPercent?.let { fields["targetHiPercent"] = JsonPrimitive(it) }
        return JsonObject(fields)
    }

    companion object {
        // Shared with the editor so typed values survive persistence and sharing.
        val setBreakRange = 0..900
        // Preserve legacy sub-0.5 kg thresholds while accepting the editor's full upper limit.
        val thresholdRange = 0.1..30.0

        /// TRANSLATION NOTE: a NESTED array is all-or-nothing on both platforms, unlike
        /// `BlobCodec.decodeArray`'s element-wise read of a TOP-LEVEL array. Swift's
        /// `c.value(.sets, or: [])` wraps one `[SetPlan]` decode in `try?`, so one element
        /// that is not an object throws and the whole key falls back — returning null here
        /// is what reproduces that.
        private fun setsFromJson(element: JsonElement): List<SetPlan>? {
            val array = JsonRead.array(element) ?: return null
            val out = ArrayList<SetPlan>(array.size)
            for (child in array) out.add(SetPlan.fromJson(child) ?: return null)
            return out
        }

        fun fromJson(element: JsonElement?): SessionPlan? =
            JsonRead.obj(element)?.let { fromJson(it) }

        fun fromJson(o: JsonObject): SessionPlan = SessionPlan(
            name = o.stringOr("name", L10n.tr("Daily no-hangs")),
            sets = o.valueOr("sets", emptyList()) { setsFromJson(it) },
            // Falls back rather than throwing, and that is SAFE here only because a
            // SessionPlan blob lives inside a write-once WorkoutLog. The LIVE routine keeps
            // its mode in SessionTemplate.handModeRaw verbatim, so a mode from a newer
            // build is never rewritten by this build reading it.
            handMode = HandMode.fallback(
                o.stringOr("handMode", HandMode.alternateEachRep.rawValue)
            ),
            holdSeconds = SetPlan.holdRange.clamping(o.intOr("holdSeconds", 10)),
            restSeconds = SetPlan.restRange.clamping(o.intOr("restSeconds", 20)),
            setBreakSeconds = setBreakRange.clamping(o.intOr("setBreakSeconds", 60)),
            leadInSeconds = (0..60).clamping(o.intOr("leadInSeconds", 5)),
            // A zero threshold would read as "engaged" against sensor noise and start the
            // clock before the user touched the edge.
            thresholdKg = thresholdRange.clamping(o.doubleOr("thresholdKg", 2.0)),
            // Absent key → true, so an existing routine GAINS the behaviour. See the
            // property for why that is the right default rather than the safe-looking one.
            waitForReleaseBeforeRest = o.boolOr("waitForReleaseBeforeRest", true),
            // Absent key → true, so every routine written before this existed keeps the
            // behaviour it was authored under.
            pausesOutsideTargetBand = o.boolOr("pausesOutsideTargetBand", true),
            targetLoPercent = o.optionalDouble("targetLoPercent")
                ?.let { SetPlan.percentRange.clamping(it) },
            targetHiPercent = o.optionalDouble("targetHiPercent")
                ?.let { SetPlan.percentRange.clamping(it) },
        )
    }
}

// MARK: - Reminders

/// A daily reminder slot. NO UUID, on purpose: identity IS the time, so two reminders
/// at 08:00 are unrepresentable and the notification identifier can be content-keyed —
/// editing 08:00 → 09:00 replaces the pending request in place instead of leaking one.
///
/// TRANSLATION NOTE: Swift clamps in a `didSet` (repeated by hand in `init`, because
/// observers do not run during initialisation). Kotlin has no observers, so the clamp
/// lives in the property initializer — which is the ONE door, and so cannot be skipped
/// by a `copy`. That also rules out a `data class`, hence the hand-written
/// `equals`/`hashCode`/`compareTo`.
class ReminderTime(minutesFromMidnight: Int) : Comparable<ReminderTime>, JsonEncodable {

    val minutesFromMidnight: Int = range.clamping(minutesFromMidnight)

    constructor(hour: Int, minute: Int) : this(hour * 60 + minute)

    val hour: Int get() = minutesFromMidnight / 60
    val minute: Int get() = minutesFromMidnight % 60
    val id: Int get() = minutesFromMidnight

    /// "r0480" — readable in a log AND content-keyed, so the identifier for 08:00 is
    /// the same on both of the user's devices without anything being synced.
    val slot: String get() = "r" + minutesFromMidnight.toString().padStart(4, '0')

    /// Locale-correct — 08:00 or 8:00 AM, never hand-assembled.
    ///
    /// TRANSLATION NOTE: Swift builds a fixed mid-January date so a DST transition can
    /// never shift the hour being displayed. `java.time.LocalTime` has no date at all,
    /// so the hazard does not exist here and there is nothing to defend against.
    fun displayText(locale: Locale = Locale.getDefault()): String =
        DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT).withLocale(locale)
            .format(LocalTime.of(hour, minute))

    /// Hour and minute only — a repeating daily trigger, never a dated one. (Swift's
    /// `dateComponents`; scheduling itself is an `:app` concern on both platforms.)
    fun localTime(): LocalTime = LocalTime.of(hour, minute)

    // Codable is SINGLE-VALUE over `minutesFromMidnight`, and decodes through the
    // clamping initializer so a bad number cannot become a notification at hour 47.
    override fun toJson(): JsonElement = JsonPrimitive(minutesFromMidnight)

    override fun compareTo(other: ReminderTime): Int =
        minutesFromMidnight.compareTo(other.minutesFromMidnight)

    override fun equals(other: Any?): Boolean =
        other is ReminderTime && minutesFromMidnight == other.minutesFromMidnight

    override fun hashCode(): Int = minutesFromMidnight

    override fun toString(): String = slot

    companion object {
        val range = 0..1439

        /// The ladder new slots are filled from, in order: morning, evening, then the two
        /// in-between times someone training four times a day actually uses.
        val defaults: List<ReminderTime> = listOf(
            ReminderTime(hour = 8, minute = 0),
            ReminderTime(hour = 19, minute = 0),
            ReminderTime(hour = 12, minute = 30),
            ReminderTime(hour = 21, minute = 30),
        )

        fun fromJson(element: JsonElement?): ReminderTime? =
            JsonRead.int(element)?.let { ReminderTime(it) }
    }
}

// MARK: - The draft

/// The wizard's working copy — and, because the wizard IS the editor, the ONE type
/// that "new routine", "prefill" and "edit what I have" all produce. There is no
/// separate create path to keep in step with a separate edit path.
data class RoutineDraft(
    /// null = this routine does not exist yet.
    val templateID: UUID? = null,
    val plan: SessionPlan = SessionPlan(),
    val sessionsPerDay: Int = 2,
    val reminders: List<ReminderTime> = ReminderTime.defaults.take(2),

    /// Times removed by lowering `sessionsPerDay`, kept so raising it again restores
    /// the user's own 19:00 rather than a default.
    val parkedReminders: List<ReminderTime> = emptyList(),
    val remindersEnabled: Boolean = true,

    /// A WHENEVER routine (Nuri, 2026-08-10): no daily target, no reminders, never
    /// owed — a max day is something you do when you're fresh, not a ritual you break.
    /// Sessions still log honestly; the routine just never asks for one.
    val isOnDemand: Boolean = false,
) : JsonEncodable {

    val isNew: Boolean get() = templateID == null

    /// Why Save is refused, in the exact words shown under it. null = ready.
    val validationIssue: String?
        get() {
            if (plan.executable.sets.isEmpty()) return L10n.tr("Add at least one set with a pull in it.")
            if (remindersEnabled && reminders.isEmpty()) {
                return L10n.tr("Add a reminder time, or turn reminders off.")
            }
            if (remindersEnabled && reminders.toSet().size != reminders.size) {
                return L10n.tr("Choose a different time for each daily reminder.")
            }
            return null
        }

    /// The shape that is safe to persist: name trimmed (empty → the house default),
    /// reminders sorted and deduped, target bands the right way up, dead sets dropped,
    /// sessions clamped. Called on the way INTO the store, never on the way out — a
    /// normalize-on-read would rewrite a blob nobody edited and sync a no-op.
    val normalized: RoutineDraft
        get() {
            val trimmed = plan.name.trim()
            var outPlan = plan.copy(
                name = if (trimmed.isEmpty()) L10n.tr("Daily no-hangs") else trimmed,
                sets = plan.sets
                    .filter { it.repsPerSide > 0 }
                    .map { set ->
                        var s = set
                        val lo = s.targetLoKg
                        val hi = s.targetHiKg
                        if (lo != null && hi != null && lo > hi) {
                            s = s.copy(targetLoKg = hi, targetHiKg = lo)
                        }
                        val loPercent = s.targetLoPercent
                        val hiPercent = s.targetHiPercent
                        if (loPercent != null && hiPercent != null && loPercent > hiPercent) {
                            s = s.copy(targetLoPercent = hiPercent, targetHiPercent = loPercent)
                        }
                        s.copy(note = s.note.trim())
                    }
            )
            // The routine's own band gets the same treatment: dragging "From" past "To" is
            // one gesture in the builder, and an inverted band would resolve to an inverted
            // kilogram range on every set that inherits it.
            val planLo = outPlan.targetLoPercent
            val planHi = outPlan.targetHiPercent
            if (planLo != null && planHi != null && planLo > planHi) {
                outPlan = outPlan.copy(targetLoPercent = planHi, targetHiPercent = planLo)
            }
            outPlan = consolidatingInheritance(outPlan)
            // DEMOTE a routine-level band onto the sets, then clear it — the migration
            // that makes "load lives per set" true for routines authored before it was.
            // Resolution-preserving: a set with any target of its own already outranked
            // the routine's, and a set without one resolves to the same numbers it
            // inherited, now written where the editor can see them.
            val band = outPlan.targetPercentBand
            if (band != null) {
                outPlan = outPlan.copy(
                    sets = outPlan.sets.map { set ->
                        if (set.hasTarget || set.hasPercentTarget) set
                        else set.copy(
                            targetLoPercent = band.start,
                            targetHiPercent = band.endInclusive,
                        )
                    },
                    targetLoPercent = null,
                    targetHiPercent = null,
                )
            }
            var out = copy(
                plan = outPlan,
                sessionsPerDay = sessionsRange.clamping(sessionsPerDay),
                reminders = tidy(reminders),
                parkedReminders = tidy(parkedReminders),
            )
            if (out.remindersEnabled && !out.isOnDemand && out.reminders.isNotEmpty() &&
                out.reminders.size < out.sessionsPerDay) {
                out = out.setSessionsPerDay(out.sessionsPerDay)
            }
            // A whenever routine cannot remind — the times are KEPT so flipping back to a
            // ritual restores the user's own schedule, but the switch is forced off.
            return if (out.isOnDemand) out.copy(remindersEnabled = false) else out
        }

    /// The ONE mutator that parks and restores reminder times. Anything that sets
    /// `sessionsPerDay` directly loses the user's times the first time they try two a
    /// day, change their mind, and change it back.
    ///
    /// TRANSLATION NOTE: Swift's `mutating func setSessionsPerDay(_:)`. A Kotlin value
    /// cannot mutate itself, so this RETURNS the updated draft; the name is kept so the
    /// two files still read the same.
    fun setSessionsPerDay(n: Int): RoutineDraft {
        val target = sessionsRange.clamping(n)
        var nextReminders = tidy(reminders)
        var nextParked = parkedReminders
        if (target < nextReminders.count()) {
            nextParked = tidy(parkedReminders + nextReminders.drop(target))
            nextReminders = nextReminders.take(target)
        } else if (target > nextReminders.count()) {
            val filled = nextReminders.toMutableList()
            val parked = parkedReminders.toMutableList()
            while (filled.count() < target && parked.isNotEmpty()) {
                val candidate = parked.removeAt(0)
                if (!filled.contains(candidate)) filled.add(candidate)
            }
            // Then the default ladder, skipping anything already on the list.
            for (candidate in ReminderTime.defaults) {
                if (filled.count() >= target) break
                if (!filled.contains(candidate)) filled.add(candidate)
            }
            nextReminders = tidy(filled)
            nextParked = tidy(parked)
        }
        return copy(
            reminders = nextReminders,
            parkedReminders = nextParked,
            sessionsPerDay = target,
        )
    }

    /// FROZEN — this is what the debounced draft rescue writes to the key-value store.
    /// `isOnDemand` arrived later and decodes as false on every draft stashed before it
    /// existed, which is the honest reading — those drafts were all rituals.
    override fun toJson(): JsonElement {
        val fields = linkedMapOf<String, JsonElement>()
        templateID?.let { fields["templateID"] = JsonPrimitive(it.toString().uppercase(Locale.ROOT)) }
        fields["plan"] = plan.toJson()
        fields["sessionsPerDay"] = JsonPrimitive(sessionsPerDay)
        fields["reminders"] = JsonArray(reminders.map { it.toJson() })
        fields["parkedReminders"] = JsonArray(parkedReminders.map { it.toJson() })
        fields["remindersEnabled"] = JsonPrimitive(remindersEnabled)
        fields["isOnDemand"] = JsonPrimitive(isOnDemand)
        return JsonObject(fields)
    }

    companion object {
        val sessionsRange = 1..4

        /// Sorted and deduped by the time itself — identity IS the time.
        private fun tidy(times: List<ReminderTime>): List<ReminderTime> =
            times.toSet().sorted()

        /// Fold per-set values that are really ROUTINE values back where they belong.
        ///
        /// The setup deck edits hold, rest and the target band ON EACH GRIP CARD, so it
        /// writes a per-set override every time one is touched — and `addGrip` copies the
        /// previous grip, overrides included. A routine built that way arrived with every
        /// set overriding and `plan.holdSeconds` still at its factory 10, which broke
        /// inheritance in three visible ways: the document's RHYTHM card quoted a number no
        /// set used, changing it did nothing, and a set added later inherited that phantom
        /// while its siblings ran something else. The coach card's own promise — "change it
        /// here once and it changes everywhere" — was false for every deck-authored routine.
        ///
        /// Two passes, both RESOLUTION-PRESERVING by construction:
        ///
        /// 1. **Promote** a value every executable set overrides identically. If they all
        ///    carry it then none of them is inheriting, so moving it onto the plan cannot
        ///    change what any set resolves to.
        /// 2. **Clear** an override that now equals the routine's value. The codebase already
        ///    says why one that merely agrees is harmful: it silently skips that set the next
        ///    time the rhythm changes.
        ///
        /// `PlanMath.hold`/`rest`/`targetBand` answer identically before and after — which is
        /// the property the tests pin, because it is the only thing that makes this safe to
        /// run on every save.
        private fun consolidatingInheritance(plan: SessionPlan): SessionPlan {
            val live = plan.sets
            if (live.isEmpty()) return plan

            var out = plan
            uniform(live) { it.holdSeconds }?.let { out = out.copy(holdSeconds = it) }
            uniform(live) { it.restSeconds }?.let { out = out.copy(restSeconds = it) }
            // TARGETS ARE NEVER PROMOTED any more — load lives per set (Nuri, 2026-08-10:
            // "target load needs to only be in each set"), and with no routine-level load
            // editor left in the builder, a promoted band would be active but invisible.
            // The inverse — DEMOTION of a legacy routine-level band — happens in
            // `normalized` right after this pass.

            val holdSeconds = out.holdSeconds
            val restSeconds = out.restSeconds
            return out.copy(
                sets = out.sets.map { set ->
                    set.copy(
                        holdSeconds = if (set.holdSeconds == holdSeconds) null else set.holdSeconds,
                        restSeconds = if (set.restSeconds == restSeconds) null else set.restSeconds,
                    )
                }
            )
        }

        /// The one value every set carries, or null if they disagree or any is inheriting.
        private fun <T> uniform(sets: List<SetPlan>, value: (SetPlan) -> T?): T? {
            val first = value(sets[0]) ?: return null
            return if (sets.all { value(it) == first }) first else null
        }

        private fun remindersFromJson(element: JsonElement): List<ReminderTime>? {
            val array = JsonRead.array(element) ?: return null
            val out = ArrayList<ReminderTime>(array.size)
            for (child in array) out.add(ReminderTime.fromJson(child) ?: return null)
            return out
        }

        fun fromJson(element: JsonElement?): RoutineDraft? =
            JsonRead.obj(element)?.let { fromJson(it) }

        fun fromJson(o: JsonObject): RoutineDraft = RoutineDraft(
            templateID = o.optionalValue("templateID") { SetPlan.uuidFromJson(it) },
            plan = o.valueOr("plan", SessionPlan()) { SessionPlan.fromJson(it) },
            sessionsPerDay = sessionsRange.clamping(o.intOr("sessionsPerDay", 2)),
            // All-or-nothing, exactly like `SessionPlan.sets` — see the note there.
            reminders = o.valueOr("reminders", ReminderTime.defaults.take(2)) { remindersFromJson(it) },
            parkedReminders = o.valueOr("parkedReminders", emptyList()) { remindersFromJson(it) },
            remindersEnabled = o.boolOr("remindersEnabled", true),
            isOnDemand = o.boolOr("isOnDemand", false),
        )

        // MARK: - Seeds

        /// Nuri's actual protocol. A computed GETTER, never a stored `val`: a stored one
        /// mints the six SetPlan UUIDs once per process and would hand identical ids to two
        /// routines built in one sitting.
        ///
        /// Note what is NOT here — every SetPlan leaves `holdSeconds` and `restSeconds`
        /// null, so all six inherit the routine's 10 s / 20 s. That is the point of the
        /// RHYTHM block: the prefill contains ZERO timing overrides, so changing one rest
        /// interval is one edit.
        ///
        /// Three of the positions are a considered GUESS, not dictation: the protocol
        /// states half crimp for the 4-finger set and crimp for the last two, and says
        /// nothing for the 3-finger and the plain 2-finger sets. They ship as half crimp
        /// and open hand so the plain pairs read as the low-intensity counterparts of the
        /// crimped ones that follow, and step 3 of the builder is where that gets corrected
        /// in four taps.
        val starter: RoutineDraft
            get() = RoutineDraft(
                templateID = null,
                plan = SessionPlan(
                    name = L10n.tr("Daily no-hangs"),
                    sets = listOf(
                        // 20 mm, 4 fingers half-crimp — 6 each side       key "20|IMRL|halfCrimp"
                        SetPlan(
                            grip = GripSpec(20, FingerSet.four, GripPosition.halfCrimp),
                            repsPerSide = 6,
                        ),
                        // 3 fingers — 6 each side                          key "20|IMR|halfCrimp"
                        SetPlan(
                            grip = GripSpec(20, FingerSet.frontThree, GripPosition.halfCrimp),
                            repsPerSide = 6,
                        ),
                        // front 2 — 2 each side                            key "20|IM|openHand"
                        SetPlan(
                            grip = GripSpec(20, FingerSet.frontTwo, GripPosition.openHand),
                            repsPerSide = 2,
                        ),
                        // middle 2 — 2 each side                           key "20|MR|openHand"
                        SetPlan(
                            grip = GripSpec(20, FingerSet.middleTwo, GripPosition.openHand),
                            repsPerSide = 2,
                        ),
                        // front 2 crimped — 1 each side                    key "20|IM|fullCrimp"
                        SetPlan(
                            grip = GripSpec(20, FingerSet.frontTwo, GripPosition.fullCrimp),
                            repsPerSide = 1,
                        ),
                        // middle 2 crimped — 1 each side                   key "20|MR|fullCrimp"
                        SetPlan(
                            grip = GripSpec(20, FingerSet.middleTwo, GripPosition.fullCrimp),
                            repsPerSide = 1,
                        ),
                    ),
                    handMode = HandMode.alternateEachRep, // "swapping hands every pull"
                    holdSeconds = 10,                     // "~10s pulls"
                    restSeconds = 20,                     // "20s rest"
                    setBreakSeconds = 60,
                    leadInSeconds = 5,
                    thresholdKg = 2.0,                    // engagement detector, not intensity
                ),
                sessionsPerDay = 2,                       // "twice a day, every day"
                reminders = listOf(
                    ReminderTime(hour = 8, minute = 0),
                    ReminderTime(hour = 19, minute = 0),
                ),
                parkedReminders = emptyList(),
                // TRUE. The permission ask happens on SAVE, not on tapping a prefill — by
                // then the user has read step 5 of 5 and seen both times on screen.
                remindersEnabled = true,
            )

        /// TRULY EMPTY (Nuri, 2026-08-11: "there shouldn't be anything in here").
        ///
        /// It used to seed one 20 mm four-finger half-crimp set, on the reasoning that an
        /// empty list with an "Add" button is a form and a form is what Frez feels like. That
        /// traded one problem for a worse one: the seeded set was a GUESS presented as your
        /// routine, and the commonest first edit was deleting or rewriting a grip nobody
        /// asked for. The reasoning has also expired — adding a set is one tap and choosing
        /// its grip is one more, so the empty state costs two taps rather than a form.
        ///
        /// `validationIssue` already refuses to save a routine with no pulls in it, so Save
        /// stays disabled and says why until there is a real set here.
        fun blank(name: String = L10n.tr("My routine")): RoutineDraft =
            RoutineDraft().let { d -> d.copy(plan = d.plan.copy(name = name, sets = emptyList())) }

        /// Fresh SetPlan ids and no templateID: a duplicate must never share row identity
        /// with its source, or reordering one reorders the other under a drag.
        fun copying(source: RoutineDraft): RoutineDraft = source.copy(
            templateID = null,
            plan = source.plan.copy(
                name = L10n.tr("Copy of %s", source.plan.name),
                sets = source.plan.sets.map { it.copy(id = UUID.randomUUID()) },
            ),
        )

        /// The C4 max-testing ladder (camp4humanperformance.com/blog/progressor), as a
        /// one-tap prefill: 3 s pulls, 5 s rests, one hand at a time, one grip ramped over
        /// three sets to a final two-rep max effort. The ramp sets carry percent bands; the
        /// final set deliberately carries NONE — a band gates the rep clock, and pausing a
        /// max attempt the instant it fades below 95 % is exactly wrong. A WHENEVER routine
        /// by construction: nobody maxes daily.
        ///
        /// A computed getter for the same reason `starter` is — a stored `val` would mint
        /// the SetPlan UUIDs once per process.
        val maxDay: RoutineDraft
            get() {
                val grip = GripSpec(20, FingerSet.four, GripPosition.halfCrimp)
                return RoutineDraft().let { d ->
                    d.copy(
                        plan = d.plan.copy(
                            name = L10n.tr("Max day"),
                            holdSeconds = 3,
                            restSeconds = 5,
                            setBreakSeconds = 120,
                            handMode = HandMode.alternateEachSet,
                            sets = listOf(
                                SetPlan(
                                    grip = grip, repsPerSide = 4,
                                    targetLoPercent = 0.50, targetHiPercent = 0.60,
                                ),
                                SetPlan(
                                    grip = grip, repsPerSide = 4,
                                    targetLoPercent = 0.65, targetHiPercent = 0.75,
                                ),
                                SetPlan(
                                    grip = grip, repsPerSide = 4,
                                    targetLoPercent = 0.80, targetHiPercent = 0.90,
                                ),
                                SetPlan(grip = grip, repsPerSide = 2),
                            ),
                        ),
                        sessionsPerDay = 1,
                        isOnDemand = true,
                    )
                }
            }
    }
}
