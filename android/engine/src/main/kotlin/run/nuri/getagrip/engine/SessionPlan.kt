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

// The routine, as value types: what the runner executes, the builder edits and a log
// freezes — one vocabulary, no DTO layer. The MODEL says rep, the UI says pull; one rep
// IS one pull, and nothing counts halves.
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

    /// Caps: read at arm's length, mid-set, by eyes on a fingerboard.
    val prompt: String
        get() = when (this) {
            left -> L10n.tr("LEFT")
            right -> L10n.tr("RIGHT")
            both -> L10n.tr("BOTH")
        }

    companion object {
        fun fromRaw(raw: String): Side? = entries.firstOrNull { it.rawValue == raw }
        fun fromJson(element: JsonElement?): Side? = JsonRead.string(element)?.let { fromRaw(it) }

        /// The hand a routine STARTS on, read from a raw. `both` is not a hand to start
        /// on and an unknown raw is a newer build's idea, so both read as null — "no
        /// readable choice" — and `startingHandFallback` turns that into the default, left.
        fun startingHandOrNull(raw: String): Side? = when (fromRaw(raw)) {
            left -> left
            right -> right
            else -> null
        }

        fun startingHandFallback(raw: String): Side = startingHandOrNull(raw) ?: left
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

    /// Every set starts here — alternation RESETS at each set boundary, so an odd rep
    /// count never starts the next set on the wrong hand. WHICH hand is the routine's
    /// (`SessionPlan.startingHand`); a `both` passed in reads as the default, left.
    fun startSide(startingHand: Side): Side = when (this) {
        alternateEachRep, alternateEachSet -> if (startingHand == Side.right) Side.right else Side.left
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

        /// The decode door: a newer build's unknown mode becomes the default rather than
        /// throwing, while `SessionTemplate.handModeRaw` keeps the original verbatim.
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

    /// Normalized on the way out, so an upside-down band is never returned. One endpoint
    /// alone is a degenerate range — a target line, which is what one endpoint means.
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
        // A practical storage bound while the runner records individual pulls, including
        // skipped pulls. The editor shares this range so saving never reduces a count
        // the user was allowed to enter.
        val repsRange = 0..100
        // Match the hold dial: positive whole seconds, including short 1–2 s pulls.
        val holdRange = 1..120
        val restRange = 0..600

        /// 1 %…100 %: a max-effort routine is legitimate to author, and a 0 % target is a
        /// target of nothing.
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

/// What the runner executes and a log freezes. Carries NO scheduling: reminders and
/// sessions-a-day are arranged by a person, not done by a session.
data class SessionPlan(
    val name: String = L10n.tr("Daily no-hangs"),
    val sets: List<SetPlan> = emptyList(),
    val handMode: HandMode = HandMode.alternateEachRep,

    /// Which hand the first pull of every set is on under either alternating mode
    /// (Nuri, 2026-09-18). Left by default, as every older routine was. Never `both`:
    /// the decoder reads that, and any unknown raw, as left.
    val startingHand: Side = Side.left,

    /// RHYTHM — the routine-level defaults every set inherits unless it overrides.
    val holdSeconds: Int = 10,
    val restSeconds: Int = 20,
    val setBreakSeconds: Int = 60,

    /// "Get ready" before the FIRST rep of each set, not before every rep.
    val leadInSeconds: Int = 5,

    /// Engagement DETECTOR, not intensity: "you have taken the load". One value because
    /// ~2 kg sits below every set's working load; intensity lives in the target band.
    val thresholdKg: Double = 2.0,

    /// Hold the rest countdown until you are actually OFF the edge. Default ON, even for
    /// older routines: starting the clock when the hold completes charges the rest for
    /// the seconds it takes to stand down, so a 20 s rest was never 20 s. Off is still
    /// honest — a fixed cadence you pace yourself to.
    val waitForReleaseBeforeRest: Boolean = true,

    /// Whether leaving the TARGET BAND stops the rep clock. Default ON, the rule the band
    /// exists to enforce. Off is for training by feel or against a stale max: the band
    /// still draws as a lane and the clock runs whenever you are ENGAGED. It never
    /// loosens the engagement threshold — letting go still stops the rep.
    val pausesOutsideTargetBand: Boolean = true,

    /// TARGET LOAD as a fraction of your max on whichever grip a set uses, inherited like
    /// `holdSeconds`. A percentage because the prescription IS a fraction, so ONE band is
    /// the right load on every grip at once; kilograms would freeze one day's arithmetic
    /// and go stale the next time a max is recorded.
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
            "startingHand" to JsonPrimitive(startingHand.rawValue),
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
            // Falling back is SAFE only because this blob is a write-once WorkoutLog's;
            // the LIVE routine keeps its raw mode in SessionTemplate.handModeRaw.
            handMode = HandMode.fallback(
                o.stringOr("handMode", HandMode.alternateEachRep.rawValue)
            ),
            // Left for every blob written before the field existed, and for a raw this
            // build does not know — `both` included, which is not a hand to start on.
            startingHand = Side.startingHandFallback(
                o.stringOr("startingHand", Side.left.rawValue)
            ),
            holdSeconds = SetPlan.holdRange.clamping(o.intOr("holdSeconds", 10)),
            restSeconds = SetPlan.restRange.clamping(o.intOr("restSeconds", 20)),
            setBreakSeconds = setBreakRange.clamping(o.intOr("setBreakSeconds", 60)),
            leadInSeconds = (0..60).clamping(o.intOr("leadInSeconds", 5)),
            // A zero threshold would read as "engaged" against sensor noise and start the
            // clock before the user touched the edge.
            thresholdKg = thresholdRange.clamping(o.doubleOr("thresholdKg", 2.0)),
            // Absent key → true, so an existing routine GAINS the behaviour (see the property).
            waitForReleaseBeforeRest = o.boolOr("waitForReleaseBeforeRest", true),
            // Absent key → true: older routines keep the behaviour they were authored under.
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

/// The editor's working copy — the ONE type that new, prefilled and edited routines
/// all produce, so no create path drifts from an edit path.
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

    /// A WHENEVER routine (Nuri, 2026-08-10): no daily target, no reminders, never owed —
    /// a max day is done when you're fresh, not a ritual you break.
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

    /// The shape that is safe to persist (trimmed name, tidy reminders, bands the right
    /// way up, dead sets dropped). Applied INTO the store, never on read — that would
    /// rewrite a blob nobody edited and sync a no-op.
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
            // The routine's own band too: an inverted one would invert every inheriting set.
            val planLo = outPlan.targetLoPercent
            val planHi = outPlan.targetHiPercent
            if (planLo != null && planHi != null && planLo > planHi) {
                outPlan = outPlan.copy(targetLoPercent = planHi, targetHiPercent = planLo)
            }
            outPlan = consolidatingInheritance(outPlan)
            // DEMOTE a legacy routine-level band onto the sets and clear it, so "load lives
            // per set" holds for older routines. Resolution-preserving: a set with its own
            // target already outranked it; one without now shows the numbers it inherited.
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

    /// The ONE mutator that parks and restores reminder times; setting `sessionsPerDay`
    /// directly loses the user's times on the first 2 → 1 → 2.
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
        /// The setup deck writes hold/rest overrides onto every grip card (and `addGrip`
        /// copies them), so deck-built routines arrived with every set overriding and
        /// `plan.holdSeconds` at a phantom 10: the RHYTHM card quoted a number no set used
        /// and "change it here once" did nothing. Two passes, both RESOLUTION-PRESERVING:
        ///
        /// 1. **Promote** a value every executable set overrides identically — none of them
        ///    is inheriting, so moving it onto the plan changes nothing.
        /// 2. **Clear** an override that equals the routine's value; one that merely agrees
        ///    silently skips that set the next time the rhythm changes.
        ///
        /// `PlanMath.hold`/`rest`/`targetBand` answer identically before and after — the
        /// property the tests pin, and what makes this safe to run on every save.
        private fun consolidatingInheritance(plan: SessionPlan): SessionPlan {
            val live = plan.sets
            if (live.isEmpty()) return plan

            var out = plan
            uniform(live) { it.holdSeconds }?.let { out = out.copy(holdSeconds = it) }
            uniform(live) { it.restSeconds }?.let { out = out.copy(restSeconds = it) }
            // Targets are never promoted: load lives per set (Nuri, 2026-08-10), and with no
            // routine-level load editor a promoted band would be active but invisible.
            // `normalized` DEMOTES any legacy routine-level band right after this pass.

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
        /// Every set inherits the routine's 10 s / 20 s — ZERO timing overrides, so changing
        /// one rest interval is one edit. The 3-finger and plain 2-finger positions are a
        /// GUESS (the protocol names only the 4-finger half crimp and the crimped pair); they
        /// ship as the low-intensity counterparts of the crimped sets that follow.
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

        /// TRULY EMPTY (Nuri, 2026-08-11: "there shouldn't be anything in here"). A seeded
        /// set was a GUESS presented as your routine and usually the first thing deleted;
        /// an empty list costs two taps, not a form. `validationIssue` keeps Save disabled,
        /// and says why, until a real set exists.
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

        /// The C4 max-testing ladder (camp4humanperformance.com/blog/progressor): 3 s pulls,
        /// 5 s rests, one hand at a time, one grip ramped over three sets to a two-rep max.
        /// The final set carries NO band: a band gates the clock, and pausing a max attempt
        /// as it fades below 95 % is exactly wrong. A WHENEVER routine: nobody maxes daily.
        /// Computed for the same reason as `starter`.
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
