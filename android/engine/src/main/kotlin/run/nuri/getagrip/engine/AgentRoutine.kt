// SPDX-License-Identifier: MPL-2.0
// Original contributions Copyright 2026 Nuri Bruner.

package run.nuri.getagrip.engine

import java.math.BigDecimal
import java.math.RoundingMode
import java.text.BreakIterator
import java.util.Locale
import java.util.UUID
import kotlin.math.abs
import kotlin.math.floor

// A routine written by somebody's AI chat (Nuri, 2026-10-02): the app hands out
// INSTRUCTIONS to paste into any assistant, the assistant interviews the climber and
// replies with one block of JSON, and that reply is pasted back here.
//
// The format is NOT `SessionPlan`'s JSON. That one carries row ids, fractions written
// as 0.7, and fields nobody would describe in a chat, and an assistant would get them
// wrong. This is a small vocabulary of whole numbers and listed words, read FORGIVINGLY:
// the reply arrives wrapped in prose and code fences, keys in whatever case the model
// chose, a percentage as 70 or as 0.7, a hold of "7 s". Anything out of range is brought
// into range and REPORTED (`Note`), never silently changed and never refused, because the
// preview that follows is where the climber checks what the assistant meant.
//
// The JSON reader is our own (`Lenient`), not kotlinx's: its lenient mode and
// Foundation's JSON5 mode accept different things, and this file must read every reply
// exactly as `AgentRoutine.swift` does. Both are pinned by `Fixtures/agent/`.
//
// TRANSLATION NOTE (from Shared/Engine/AgentRoutine.swift): Swift walks text as
// `Character`s — extended grapheme clusters, so "\r\n" is ONE character and a `//`
// comment in CRLF text runs to the next bare "\n". The Kotlin reader walks the same
// clusters (`Lenient.characters`, `BreakIterator`) and reads each cluster's properties
// off its first scalar, which is how Swift's `isWhitespace`, `isNewline` and `isLetter`
// work. Swift's `Result<Reading, Failure>` is `Outcome` here, and `Field.name` is
// `Field.routineName` because `name` is final on Kotlin's `Enum`.

object AgentRoutine {
    /// The `format` an assistant is told to write. A reply naming a HIGHER version reads as
    /// "update the app"; one naming none is read as version 1.
    const val formatVersion = 1
    const val formatName = "get-a-grip-routine"

    // MARK: - What the assistant is told

    /// Copied to the clipboard verbatim. ENGLISH whatever the UI language (one schema for
    /// every reader, the analysis export's rule); the assistant still talks to the climber
    /// in theirs. Every range here is the app's own clamp — change one, change both.
    /// Byte-identical to iOS: `Fixtures/agent/instructions.txt` pins both.
    val instructions: String = """
        You are helping me build a hangboard routine for Get a Grip, a finger-training app for climbers. The app reads routines in one exact format, described below.

        HOW TO WORK
        1. Ask me how I want to train: the goal (for example strength, recovery, endurance or max hangs), the edges and grips, how long to hold and rest, how many pulls and sets, whether my hands alternate or pull together, and any target load. Ask only what you still need, a few short questions at a time, and suggest sensible values when I'm unsure.
        2. Once you have enough, write one short line telling me to copy the code block and paste it into Get a Grip. Then write ONE code block of JSON in exactly the format below, and nothing after it.
        3. Use only the keys shown. Write the listed values in English exactly as shown, even if we talk in another language. Leave out anything optional you don't know; the app fills in defaults.

        FORMAT
        {
          "format": "get-a-grip-routine/1",
          "name": "a name, up to 60 characters",
          "hands": "alternate" (left, right, left... one pull at a time) | "one-hand-at-a-time" (all of a set's pulls on one hand, then the other) | "both" (both hands together),
          "startingHand": "left" | "right",
          "holdSeconds": whole number 1-120, the hold for every pull,
          "restSeconds": whole number 0-600, the rest between pulls,
          "setBreakSeconds": whole number 0-900, the rest between sets,
          "leadInSeconds": whole number 0-60, a countdown before each set's first pull,
          "sessionsPerDay": 1-4, how many times a day I do this routine,
          "onDemand": true for a routine with no daily goal, such as a rest day or max day (leave sessionsPerDay out then),
          "target": optional, the load every set aims for (see TARGETS),
          "fineTuning": {
            "pauseTheClock": "out-of-range" (the clock stops whenever my load leaves the target range; the default) | "below-range" (it stops only when I'm under the range, so pulling harder still counts) | "never" (the range is shown but never stops the clock),
            "startRestWhenILetGo": true (the rest countdown waits until I let go of the edge; the default) | false (a fixed cadence),
            "pullCountsAboveKg": number 0.5-30, the load that counts as pulling, default 2
          },
          "sets": [ 1-50 sets in order, each one:
            {
              "edgeMm": whole number 1-100, the edge depth in millimetres,
              "fingers": "4" | "front-3" | "back-3" | "front-2" | "middle-2" | "back-2" | "index" | "middle" | "ring" | "little",
              "grip": "half-crimp" | "open-hand" | "full-crimp" | "drag" | "pinch" | "finger-curl",
              "pulls": whole number 1-100, pulls PER HAND (with "hands": "both", just the number of pulls),
              "holdSeconds": optional, this set's own hold instead of the routine's,
              "restSeconds": optional, this set's own rest instead of the routine's,
              "target": optional, this set's own target instead of the routine's,
              "note": optional, a short note shown on the set
            }
          ]
        }

        TARGETS
        A target is a load range to hold. Leave it out when there is none; many routines go by feel.
        - As a percentage of my max (preferred): {"percentOfMax": [low, high], "max": "peak"}. Numbers 1-100. "peak" is my single hardest pull on that grip. For a percentage of a timed max, the most I can hold for N seconds, write "max": N with N between 3 and 60, for example "max": 10.
        - In kilograms: {"kg": [low, high]}.
        - A single value is fine: [70, 70].
        The app works percentages out from my own saved maxes, per grip and per hand, so they stay right as I get stronger.

        WHAT THE APP CAN'T DO
        It has no progressions over weeks, no weekly schedules, no added weight, and no different hold or rest for individual pulls inside a set. Max tests and critical force tests are separate, in the app's Benchmarks tab. If I ask for one of these, tell me briefly and build the closest routine the format allows.

        EXAMPLE
        ```json
        {
          "format": "get-a-grip-routine/1",
          "name": "Recovery 7:3",
          "hands": "alternate",
          "startingHand": "left",
          "holdSeconds": 7,
          "restSeconds": 3,
          "setBreakSeconds": 120,
          "leadInSeconds": 5,
          "sessionsPerDay": 1,
          "target": {"percentOfMax": [20, 30], "max": "peak"},
          "fineTuning": {"pauseTheClock": "below-range", "startRestWhenILetGo": false},
          "sets": [
            {"edgeMm": 20, "fingers": "4", "grip": "half-crimp", "pulls": 6},
            {"edgeMm": 20, "fingers": "front-3", "grip": "drag", "pulls": 6},
            {"edgeMm": 15, "fingers": "4", "grip": "open-hand", "pulls": 4, "holdSeconds": 10, "target": {"percentOfMax": [60, 70], "max": 10}}
          ]
        }
        ```
    """.trimIndent()

    // MARK: - What can go wrong

    enum class Failure(
        /// Stable, for fixtures.
        val code: String,
    ) {
        /// No JSON object anywhere in the paste: the climber copied the chat, not the block.
        noRoutine("noRoutine"),
        /// An object, but not one this reader can make sense of.
        unreadable("unreadable"),
        /// A routine with no usable sets.
        noSets("noSets"),
        /// The reply names a newer format than this build reads.
        newerFormat("newerFormat");

        val message: String
            get() = when (this) {
                noRoutine -> L10n.tr("Couldn't find a routine in what you pasted. Copy the AI's code block and try again.")
                unreadable -> L10n.tr("Couldn't read this routine. Ask the AI to write it again as one code block.")
                noSets -> L10n.tr("This routine has no sets. Ask the AI to add at least one.")
                newerFormat -> L10n.tr("This routine needs a newer Get a Grip. Update the app to import it.")
            }
    }

    // MARK: - What was changed on the way in

    /// Which value a `Note` is about. `rawValue` is the fixture code.
    enum class Field(val rawValue: String) {
        routineName("name"), hands("hands"), startingHand("startingHand"), hold("hold"),
        rest("rest"), setBreak("setBreak"), leadIn("leadIn"), sessionsPerDay("sessionsPerDay"),
        pauseTheClock("pauseTheClock"), threshold("threshold"), sets("sets"), edge("edge"),
        fingers("fingers"), grip("grip"), pulls("pulls"), percent("percent"),
        timedMax("timedMax"), kg("kg");

        val label: String
            get() = when (this) {
                routineName -> L10n.tr("Name")
                hands -> L10n.tr("Hands")
                startingHand -> L10n.tr("First hand")
                hold -> L10n.tr("Hold")
                rest -> L10n.tr("Rest between pulls")
                setBreak -> L10n.tr("Break between sets")
                leadIn -> L10n.tr("Lead-in")
                sessionsPerDay -> L10n.tr("Sessions a day")
                pauseTheClock -> L10n.tr("Pause the clock")
                threshold -> L10n.tr("A pull counts above")
                sets -> L10n.tr("Sets")
                edge -> L10n.tr("Edge")
                fingers -> L10n.tr("Fingers")
                grip -> L10n.tr("Grip")
                pulls -> L10n.tr("Pulls")
                percent -> L10n.tr("Target")
                timedMax -> L10n.tr("Timed max")
                kg -> L10n.tr("Target")
            }

        companion object {
            fun fromRaw(raw: String): Field? = entries.firstOrNull { it.rawValue == raw }
        }
    }

    /// One value the reader had to change: out of range, an unrecognised word, or missing
    /// where the routine cannot do without it. Shown in the preview, so nothing an
    /// assistant wrote is altered behind the climber's back.
    data class Note(
        /// 1-based set number, null for the routine.
        val set: Int?,
        val field: Field,
        /// As written (trimmed), or "—" when it was missing.
        val from: String,
        /// What the routine now holds, in the same units.
        val to: String,
    ) {
        // `this.field`: a bare `field` inside an accessor is Kotlin's backing field.
        val code: String
            get() = (set?.let { "set$it." } ?: "") + "${this.field.rawValue}:$from->$to"

        /// "Set 2 · Hold: 200 → 120"
        val message: String
            get() {
                val label = set?.let { L10n.tr("Set %d · %s", it, this.field.label) } ?: this.field.label
                return L10n.tr("%s: %s → %s", label, from, to)
            }
    }

    data class Reading(val draft: RoutineDraft, val notes: List<Note>)

    sealed interface Outcome {
        data class Success(val reading: Reading) : Outcome
        data class Failed(val failure: Failure) : Outcome
    }

    // MARK: - Reading a reply

    const val maxSets = 50
    const val maxNameCharacters = 60
    const val maxNoteCharacters = 500
    val timedMaxRange = 3..60
    val sessionsPerDayRange = RoutineDraft.sessionsRange
    val kgRange = 0.0..250.0
    val percentRange = 1..100
    val leadInRange = 0..60

    /// `makeID` is a seam for fixtures, which need the same set ids on every run.
    fun read(text: String, makeID: () -> UUID = { UUID.randomUUID() }): Outcome {
        val source = Lenient.extractObject(text) ?: return Outcome.Failed(Failure.noRoutine)
        var root = (Lenient.parse(source) as? Lenient.Value.Obj)?.fields
            ?: return Outcome.Failed(Failure.unreadable)
        // An assistant that wraps it — {"routine": {...}} — still wrote a routine.
        if (Lenient.field(root, "sets") == null) {
            (Lenient.field(root, "routine") as? Lenient.Value.Obj)?.let { root = it.fields }
        }
        val format = Lenient.field(root, "format")
        if (format is Lenient.Value.Str) {
            val version = Lenient.trailingInteger(format.value)
            if (version != null && version > formatVersion) return Outcome.Failed(Failure.newerFormat)
        }
        val setsValue = Lenient.field(root, "sets")
        val rawSets = (setsValue as? Lenient.Value.Arr)?.items
            ?: return Outcome.Failed(if (setsValue == null) Failure.noSets else Failure.unreadable)

        val notes = mutableListOf<Note>()
        // A blank builder's name, not the house default: an unnamed AI routine is not the
        // starter routine.
        var plan = SessionPlan(name = L10n.tr("My routine"))

        // Models file settings in either place — a lead-in inside `fineTuning`, a pause
        // rule at the top level — so every routine-wide key is looked up in both, the
        // place the format puts it first.
        val fineFields = (Lenient.field(root, "fineTuning") as? Lenient.Value.Obj)?.fields ?: emptyList()
        val top = root + fineFields
        val fine = fineFields + root

        // Identity
        (Lenient.field(top, "name") as? Lenient.Value.Str)?.let { name ->
            plan = plan.copy(name = Lenient.prefix(Lenient.trimmed(name.value), maxNameCharacters))
        }

        // Hands
        Lenient.field(top, "hands")?.let { raw ->
            val mode = handMode(Lenient.word(raw))
            if (mode != null) {
                plan = plan.copy(handMode = mode)
            } else {
                plan = plan.copy(handMode = HandMode.alternateEachRep)
                notes.add(Note(null, Field.hands, Lenient.display(raw), "alternate"))
            }
        }
        Lenient.field(top, "startingHand")?.let { raw ->
            when (Lenient.word(raw)) {
                "left", "l" -> plan = plan.copy(startingHand = Side.left)
                "right", "r" -> plan = plan.copy(startingHand = Side.right)
                else -> notes.add(Note(null, Field.startingHand, Lenient.display(raw), "left"))
            }
        }

        // Rhythm
        plan = plan.copy(holdSeconds = integer(top, "holdSeconds", SetPlan.holdRange, plan.holdSeconds,
            Field.hold, null, seconds = true, notes = notes))
        plan = plan.copy(restSeconds = integer(top, "restSeconds", SetPlan.restRange, plan.restSeconds,
            Field.rest, null, seconds = true, notes = notes))
        plan = plan.copy(setBreakSeconds = integer(top, "setBreakSeconds", SessionPlan.setBreakRange,
            plan.setBreakSeconds, Field.setBreak, null, seconds = true, notes = notes))
        plan = plan.copy(leadInSeconds = integer(top, "leadInSeconds", leadInRange, plan.leadInSeconds,
            Field.leadIn, null, seconds = true, notes = notes))

        // Fine tuning
        Lenient.field(fine, "pauseTheClock")?.let { raw ->
            val gate = bandGate(Lenient.word(raw))
            if (gate != null) {
                plan = plan.withTargetBandGate(gate)
            } else {
                notes.add(Note(null, Field.pauseTheClock, Lenient.display(raw), "out-of-range"))
            }
        }
        Lenient.field(fine, "startRestWhenILetGo")?.let(Lenient::bool)?.let { release ->
            plan = plan.copy(waitForReleaseBeforeRest = release)
        }
        Lenient.field(fine, "pullCountsAboveKg")?.let(Lenient::number)?.let { kg ->
            val clamped = SessionPlan.thresholdRange.clamping(kg)
            if (clamped != kg) {
                notes.add(Note(null, Field.threshold, Lenient.format(kg), Lenient.format(clamped)))
            }
            plan = plan.copy(thresholdKg = clamped)
        }

        // Cadence
        var sessionsPerDay = RoutineDraft().sessionsPerDay
        val onDemand = Lenient.field(top, "onDemand")?.let(Lenient::bool) ?: false
        if (!onDemand) {
            sessionsPerDay = integer(top, "sessionsPerDay", sessionsPerDayRange, sessionsPerDay,
                Field.sessionsPerDay, null, notes = notes)
        }

        // The routine's own target: percentages stay on the plan (normalizing demotes
        // them onto the sets); kilograms have no routine level, so they land on every set
        // that names none of its own.
        val routineTarget = Lenient.field(root, "target")?.let { target(it, null, notes) }
        if (routineTarget is Target.Percent) {
            plan = plan.copy(
                targetLoPercent = routineTarget.lo,
                targetHiPercent = routineTarget.hi,
                targetMaxSeconds = routineTarget.seconds,
            )
        }

        // Sets
        if (rawSets.size > maxSets) {
            notes.add(Note(null, Field.sets, rawSets.size.toString(), maxSets.toString()))
        }
        val sets = mutableListOf<SetPlan>()
        rawSets.take(maxSets).forEachIndexed { index, rawSet ->
            val obj = (rawSet as? Lenient.Value.Obj)?.fields ?: return@forEachIndexed
            val number = index + 1
            var set = SetPlan(id = makeID())

            val defaultGrip = GripSpec()
            val edge = integer(obj, "edgeMm", GripSpec.edgeRange, defaultGrip.edgeMM, Field.edge, number, notes = notes)
            var position = defaultGrip.position
            Lenient.field(obj, "grip")?.let { raw ->
                val read = gripPosition(Lenient.word(raw))
                if (read != null) position = read
                else notes.add(Note(number, Field.grip, Lenient.display(raw), "half-crimp"))
            }
            var fingers = defaultGrip.fingers
            Lenient.field(obj, "fingers")?.let { raw ->
                val read = fingerSet(raw)
                if (read != null) fingers = read
                else notes.add(Note(number, Field.fingers, Lenient.display(raw), "4"))
            }
            // A pinch always carries the thumb: the constructor re-asserts it, so the order
            // the two fields were read in cannot decide it.
            set = set.copy(grip = GripSpec(edge, fingers, position))

            if (Lenient.field(obj, "pulls") == null) {
                notes.add(Note(number, Field.pulls, "—", set.repsPerSide.toString()))
            } else {
                set = set.copy(repsPerSide = integer(obj, "pulls", 1..SetPlan.repsRange.last, set.repsPerSide,
                    Field.pulls, number, notes = notes))
            }
            if (Lenient.field(obj, "holdSeconds") != null) {
                set = set.copy(holdSeconds = integer(obj, "holdSeconds", SetPlan.holdRange, plan.holdSeconds,
                    Field.hold, number, seconds = true, notes = notes))
            }
            if (Lenient.field(obj, "restSeconds") != null) {
                set = set.copy(restSeconds = integer(obj, "restSeconds", SetPlan.restRange, plan.restSeconds,
                    Field.rest, number, seconds = true, notes = notes))
            }
            val ownTarget = Lenient.field(obj, "target")
            when (val resolved = ownTarget?.let { target(it, number, notes) } ?: routineTarget) {
                is Target.Percent -> {
                    // A set that inherits the routine's percentage keeps nothing of its own;
                    // only an explicit set target is written onto the set.
                    if (ownTarget != null) {
                        set = set.copy(
                            targetLoPercent = resolved.lo,
                            targetHiPercent = resolved.hi,
                            targetMaxSeconds = resolved.seconds,
                        )
                    }
                }
                is Target.Kg -> set = set.copy(targetLoKg = resolved.lo, targetHiKg = resolved.hi)
                Target.NoLoad, null -> Unit
            }
            (Lenient.field(obj, "note") as? Lenient.Value.Str)?.let { note ->
                set = set.copy(note = Lenient.prefix(Lenient.trimmed(note.value), maxNoteCharacters))
            }
            sets.add(set)
        }
        if (sets.isEmpty()) return Outcome.Failed(Failure.noSets)
        plan = plan.copy(sets = sets)

        // Built like an import, from the defaults: the climber's reminders, nothing
        // switched on behind their back, and null identity so the store CREATES a routine.
        val draft = RoutineDraft(templateID = null, plan = plan)
            .setSessionsPerDay(sessionsPerDay)
            .copy(isOnDemand = onDemand, remindersEnabled = false)
        return Outcome.Success(Reading(draft, notes))
    }

    // MARK: - Vocabulary

    private fun handMode(word: String): HandMode? = when (word) {
        "alternate", "alternating", "alternateeachpull", "alternateeachrep", "alternateeachhang",
        "alternatehands", "lr", "lrlr", "eachpull" -> HandMode.alternateEachRep
        "onehandatatime", "oneatatime", "onehand", "alternateeachset", "singlehand", "singlearm",
        "onearm", "eachhand", "perhand", "onehanded" -> HandMode.alternateEachSet
        "both", "bothhands", "twohands", "together", "twohanded", "twoarms", "botharms" -> HandMode.bothHands
        else -> null
    }

    private fun bandGate(word: String): TargetBandGate? = when (word) {
        "outofrange", "outside", "outsiderange", "outsidetherange", "always", "both", "default" ->
            TargetBandGate.outside
        "belowrange", "below", "belowtherange", "under", "underrange", "onlybelow", "onlybelowrange" ->
            TargetBandGate.below
        "never", "off", "none", "no", "false" -> TargetBandGate.off
        else -> null
    }

    private fun gripPosition(word: String): GripPosition? = when (word) {
        "halfcrimp", "half", "hc", "crimp", "halfcrimped" -> GripPosition.halfCrimp
        "openhand", "open", "oh", "openhanded", "extended" -> GripPosition.openHand
        "fullcrimp", "full", "fc", "closedcrimp", "closed", "fullcrimped" -> GripPosition.fullCrimp
        "drag", "threefingerdrag", "3fingerdrag", "3fdrag", "frontthreedrag" -> GripPosition.drag
        "pinch", "pinchgrip" -> GripPosition.pinch
        "fingercurl", "curl", "curls", "fingercurls" -> GripPosition.fingerCurl
        else -> null
    }

    private fun fingerSet(raw: Lenient.Value): FingerSet? {
        // Swift's `Int(n)` truncates toward zero; `toLong` does too (and saturates where
        // Swift would trap, which no finite count of fingers reaches).
        val word = if (raw is Lenient.Value.Num) raw.value.toLong().toString() else Lenient.word(raw)
        when (word) {
            "4", "four", "all", "4fingers", "fourfingers", "allfour", "full" -> return FingerSet.four
            "3", "front3", "frontthree", "three", "3fingers", "threefingers", "front3fingers" -> return FingerSet.frontThree
            "back3", "backthree" -> return FingerSet.backThree
            "2", "front2", "fronttwo", "two", "2fingers", "twofingers", "front2fingers" -> return FingerSet.frontTwo
            "middle2", "middletwo" -> return FingerSet.middleTwo
            "back2", "backtwo" -> return FingerSet.backTwo
            "index", "indexfinger" -> return FingerSet.index
            // A lone finger on a hangboard is almost always the middle one (a "mono").
            "1", "middle", "middlefinger", "mono" -> return FingerSet.middle
            "ring", "ringfinger" -> return FingerSet.ring
            "little", "pinky", "pinkie", "littlefinger" -> return FingerSet.little
        }
        // The app's own tokens ("IM", "MRL", "IMRLT"), which a model reading a shared
        // routine might echo back.
        val letters = word.uppercase(Locale.ROOT)
        if (letters.isEmpty() || !letters.all { FingerSet.letters.contains(it) }) return null
        return FingerSet.fromToken(letters)
    }

    // MARK: - Numbers and targets

    private sealed interface Target {
        data class Percent(val lo: Double, val hi: Double, val seconds: Int?) : Target
        data class Kg(val lo: Double, val hi: Double) : Target
        data object NoLoad : Target
    }

    private fun target(raw: Lenient.Value, set: Int?, notes: MutableList<Note>): Target {
        val obj = (raw as? Lenient.Value.Obj)?.fields ?: return Target.NoLoad
        val percent = Lenient.field(obj, "percentOfMax") ?: Lenient.field(obj, "percent")
        val band = percent?.let(Lenient::range)
        if (band != null) {
            // Fractions are accepted as written by anyone who thinks of 70 % as 0.7.
            val scale = if (band.second <= 1) 100.0 else 1.0
            val lo = band.first * scale
            val hi = band.second * scale
            val bounds = percentRange.first.toDouble()..percentRange.last.toDouble()
            val cLo = bounds.clamping(Lenient.rounded(lo))
            val cHi = bounds.clamping(Lenient.rounded(hi))
            if (cLo != Lenient.rounded(lo) || cHi != Lenient.rounded(hi)) {
                notes.add(Note(set, Field.percent,
                    "${Lenient.format(lo)}–${Lenient.format(hi)}",
                    "${Lenient.format(cLo)}–${Lenient.format(cHi)}"))
            }
            var seconds: Int? = null
            Lenient.field(obj, "max")?.let { max ->
                val n = Lenient.number(max)
                if (n != null) {
                    val whole = Lenient.whole(n)
                    val clamped = Lenient.clamp(whole, timedMaxRange)
                    if (clamped.toLong() != whole) {
                        notes.add(Note(set, Field.timedMax, Lenient.format(n), clamped.toString()))
                    }
                    seconds = clamped
                } else if (Lenient.word(max) != "peak") {
                    notes.add(Note(set, Field.timedMax, Lenient.display(max), "peak"))
                }
            }
            return Target.Percent(minOf(cLo, cHi) / 100, maxOf(cLo, cHi) / 100, seconds)
        }
        val kg = Lenient.field(obj, "kg")?.let(Lenient::range)
        if (kg != null) {
            val cLo = kgRange.clamping(kg.first)
            val cHi = kgRange.clamping(kg.second)
            if (cLo != kg.first || cHi != kg.second) {
                notes.add(Note(set, Field.kg,
                    "${Lenient.format(kg.first)}–${Lenient.format(kg.second)}",
                    "${Lenient.format(cLo)}–${Lenient.format(cHi)}"))
            }
            return Target.Kg(minOf(cLo, cHi), maxOf(cLo, cHi))
        }
        return Target.NoLoad
    }

    /// A whole number from `key`, clamped into `range`, with a note when it moved.
    /// Missing or unreadable returns `fallback` without a note: the app filling a default
    /// is what the instructions promise. `seconds` reads "2 min" as 120.
    private fun integer(
        obj: List<Pair<String, Lenient.Value>>,
        key: String,
        range: IntRange,
        fallback: Int,
        field: Field,
        set: Int?,
        seconds: Boolean = false,
        notes: MutableList<Note>,
    ): Int {
        val raw = Lenient.field(obj, key) ?: return fallback
        val n = (if (seconds) Lenient.seconds(raw) else Lenient.number(raw)) ?: return fallback
        val whole = Lenient.whole(n)
        val clamped = Lenient.clamp(whole, range)
        if (clamped.toLong() != whole || n != Lenient.rounded(n)) {
            notes.add(Note(set, field, Lenient.format(n), clamped.toString()))
        }
        return clamped
    }
}

// MARK: - A forgiving JSON reader

/// Reads what chat assistants actually emit: JSON with comments, trailing commas, single
/// quotes, bare keys and typographic quotes. Deliberately small and deliberately OURS —
/// see the file header. Keys are matched loosely (`field`): case, `_`, `-` and spaces
/// are ignored, so `edge_mm`, `EdgeMM` and `edge-mm` are one key.
object Lenient {
    sealed interface Value {
        data class Obj(val fields: List<Pair<String, Value>>) : Value
        data class Arr(val items: List<Value>) : Value
        data class Str(val value: String) : Value
        data class Num(val value: Double) : Value
        data class Bool(val value: Boolean) : Value
        data object Null : Value
    }

    /// Key aliases an assistant plausibly writes for each key the format defines.
    private val aliases: Map<String, List<String>> = mapOf(
        "edgemm" to listOf("edge", "edgesize", "edgedepth", "depthmm", "mm"),
        "holdseconds" to listOf("hold", "holdtime", "hangseconds", "hang", "work", "workseconds", "on"),
        "restseconds" to listOf("rest", "resttime", "off", "offseconds", "restbetweenpulls"),
        "setbreakseconds" to listOf("setbreak", "setrest", "restbetweensets", "breakbetweensets", "setrestseconds"),
        "leadinseconds" to listOf("leadin", "countdown", "countdownseconds", "getready"),
        "pulls" to listOf("reps", "repetitions", "pullsperhand", "repsperhand", "repsperside", "hangs"),
        "sessionsperday" to listOf("sessions", "timesperday", "perday"),
        "percentofmax" to listOf("percentmax", "percentage", "pct"),
        "startinghand" to listOf("firsthand", "startwith", "start"),
        "pausetheclock" to listOf("pauseclock", "pause", "pausewhen"),
        "pullcountsabovekg" to listOf("threshold", "thresholdkg", "pullthreshold", "pullthresholdkg"),
        "startrestwheniletgo" to listOf("waitforrelease", "waitforreleasebeforerest", "restwheniletgo"),
    )

    /// Lowercased, Unicode letters, marks and digits only — Foundation's
    /// `CharacterSet.alphanumerics` (categories L*, M*, N*).
    fun key(raw: String): String {
        val out = StringBuilder()
        raw.lowercase(Locale.ROOT).codePoints().forEach { cp ->
            if (isAlphanumeric(cp)) out.appendCodePoint(cp)
        }
        return out.toString()
    }

    /// The value under `name` or one of its aliases; first match wins.
    fun field(obj: List<Pair<String, Value>>, name: String): Value? {
        val wanted = key(name)
        obj.firstOrNull { key(it.first) == wanted }?.let { return it.second }
        for (alias in aliases[wanted].orEmpty()) {
            obj.firstOrNull { key(it.first) == alias }?.let { return it.second }
        }
        return null
    }

    /// A word for vocabulary matching: lowercased, letters and digits only.
    fun word(value: Value): String = when (value) {
        is Value.Str -> key(value.value)
        is Value.Num -> format(value.value)
        is Value.Bool -> if (value.value) "true" else "false"
        else -> ""
    }

    /// The value as the assistant wrote it, for a note.
    fun display(value: Value): String = when (value) {
        is Value.Str -> prefix(trimmed(value.value), 40)
        is Value.Num -> format(value.value)
        is Value.Bool -> if (value.value) "true" else "false"
        Value.Null -> "null"
        is Value.Arr, is Value.Obj -> "…"
    }

    /// A number, from a number or from the first number in a string ("7 s", "20mm").
    fun number(value: Value): Double? {
        when (value) {
            is Value.Num -> return value.value.takeIf { it.isFinite() }
            is Value.Str -> {
                val digits = StringBuilder()
                var seenDigit = false
                for (character in characters(value.value)) {
                    if (isAsciiDigit(character)) {
                        digits.append(character); seenDigit = true
                    } else if ((character == "." || character == ",") && seenDigit && !digits.contains('.')) {
                        digits.append('.')
                    } else if (character == "-" && !seenDigit && digits.isEmpty()) {
                        digits.append(character)
                    } else if (seenDigit) {
                        break
                    }
                }
                if (digits.endsWith(".")) digits.setLength(digits.length - 1)
                if (!seenDigit) return null
                return digits.toString().toDoubleOrNull()?.takeIf { it.isFinite() }
            }
            else -> return null
        }
    }

    /// A duration in seconds: a number, "7 s", "7 sec", or "2 min" (minutes × 60).
    fun seconds(value: Value): Double? {
        val n = number(value) ?: return null
        if (value !is Value.Str) return n
        val unit = characters(value.value.lowercase(Locale.ROOT))
            .dropWhile { !isLetter(it) }
            .takeWhile { isLetter(it) }
            .joinToString("")
        return if (unit == "m" || unit.startsWith("min")) n * 60 else n
    }

    fun bool(value: Value): Boolean? = when (value) {
        is Value.Bool -> value.value
        is Value.Num -> value.value != 0.0
        is Value.Str -> when (key(value.value)) {
            "true", "yes", "on", "y" -> true
            "false", "no", "off", "n" -> false
            else -> null
        }
        else -> null
    }

    /// [lo, hi], a single number, or a string like "70-80".
    fun range(value: Value): Pair<Double, Double>? = when (value) {
        is Value.Arr -> {
            val numbers = value.items.mapNotNull(::number)
            numbers.firstOrNull()?.let { first ->
                val last = if (numbers.size > 1) numbers[1] else first
                minOf(first, last) to maxOf(first, last)
            }
        }
        is Value.Num -> value.value to value.value
        is Value.Str -> {
            // Swift's `split(whereSeparator:)`: empty pieces are omitted.
            val pieces = mutableListOf<StringBuilder>(StringBuilder())
            for (character in characters(value.value)) {
                if (character == "-" || character == "–" || character == "—") pieces.add(StringBuilder())
                else pieces.last().append(character)
            }
            val parts = pieces.filter { it.isNotEmpty() }.mapNotNull { number(Value.Str(it.toString())) }
            when {
                parts.size >= 2 -> minOf(parts[0], parts[1]) to maxOf(parts[0], parts[1])
                parts.size == 1 -> parts[0] to parts[0]
                else -> null
            }
        }
        else -> null
    }

    /// The integer at the end of a format string: "get-a-grip-routine/1" → 1. Long, as
    /// Swift's 64-bit `Int`: a longer run of digits is no version at all.
    fun trailingInteger(s: String): Long? {
        val digits = characters(s).reversed().takeWhile(::isAsciiDigit).reversed().joinToString("")
        return if (digits.isEmpty()) null else digits.toLongOrNull()
    }

    /// Plain, locale-free: 7, 7.5, 0.25. C's `%.2f` rounds the EXACT binary value, ties to
    /// even, and keeps the sign of a negative that rounds to zero — `BigDecimal(Double)` is
    /// that exact value.
    fun format(n: Double): String {
        // An absurd value is reported as absurd, not as the 300 digits of its binary value.
        if (abs(n) >= 1e9) return (if (n < 0) "-" else "") + "999999999+"
        if (n == rounded(n) && abs(n) < 1e15) return n.toLong().toString()
        val sign = if (n < 0) "-" else ""
        var text = sign + BigDecimal(abs(n)).setScale(2, RoundingMode.HALF_EVEN).toPlainString()
        text = text.trimEnd('0')
        return text.removeSuffix(".")
    }

    /// Swift's `rounded()`: to nearest, ties AWAY from zero (`Math.round` ties toward
    /// positive infinity, `kotlin.math.round` to even).
    fun rounded(n: Double): Double {
        if (!n.isFinite()) return n
        val magnitude = abs(n)
        val down = floor(magnitude)
        val up = if (magnitude - down >= 0.5) down + 1 else down
        return if (n < 0) -up else up
    }

    /// `Int(n.rounded())`, saturating where Swift would trap on a value no `Int` holds.
    fun whole(n: Double): Long = rounded(n).toLong()

    fun clamp(value: Long, range: IntRange): Int =
        value.coerceIn(range.first.toLong(), range.last.toLong()).toInt()

    // MARK: Text, as Swift sees it

    /// The text as Swift `Character`s: extended grapheme clusters.
    fun characters(text: String): List<String> {
        if (text.isEmpty()) return emptyList()
        val iterator = BreakIterator.getCharacterInstance(Locale.ROOT)
        iterator.setText(text)
        val out = ArrayList<String>(text.length)
        var start = iterator.first()
        var end = iterator.next()
        while (end != BreakIterator.DONE) {
            out.add(text.substring(start, end))
            start = end
            end = iterator.next()
        }
        return out
    }

    /// The first `count` characters, as Swift's `prefix(_:)` on a `String`.
    fun prefix(text: String, count: Int): String {
        val characters = characters(text)
        return if (characters.size <= count) text else characters.take(count).joinToString("")
    }

    /// Foundation's `.whitespacesAndNewlines` trim: Z*, U+0009–U+000D and U+0085. Kotlin's
    /// `trim()` also strips U+001C–U+001F, which Foundation keeps.
    fun trimmed(text: String): String = text.trim { isWhitespaceOrNewline(it.code) }

    private fun isWhitespaceOrNewline(code: Int): Boolean =
        code in 0x09..0x0D || code == 0x85 || isSeparator(code)

    /// Foundation's `.whitespaces`: Zs and the tab.
    private fun isHorizontalWhitespace(code: Int): Boolean =
        code == 0x09 || Character.getType(code) == Character.SPACE_SEPARATOR.toInt()

    private fun isSeparator(code: Int): Boolean = when (Character.getType(code)) {
        Character.SPACE_SEPARATOR.toInt(), Character.LINE_SEPARATOR.toInt(),
        Character.PARAGRAPH_SEPARATOR.toInt() -> true
        else -> false
    }

    private fun isAlphanumeric(cp: Int): Boolean = when (Character.getType(cp)) {
        Character.UPPERCASE_LETTER.toInt(), Character.LOWERCASE_LETTER.toInt(),
        Character.TITLECASE_LETTER.toInt(), Character.MODIFIER_LETTER.toInt(),
        Character.OTHER_LETTER.toInt(), Character.NON_SPACING_MARK.toInt(),
        Character.ENCLOSING_MARK.toInt(), Character.COMBINING_SPACING_MARK.toInt(),
        Character.DECIMAL_DIGIT_NUMBER.toInt(), Character.LETTER_NUMBER.toInt(),
        Character.OTHER_NUMBER.toInt() -> true
        else -> false
    }

    private fun firstScalar(character: String): Int = character.codePointAt(0)

    /// `Character.isWhitespace`: the Unicode White_Space property of the first scalar.
    private fun isWhitespace(character: String): Boolean = when (firstScalar(character)) {
        in 0x09..0x0D, 0x20, 0x85, 0xA0, 0x1680, in 0x2000..0x200A, 0x2028, 0x2029, 0x202F, 0x205F, 0x3000 -> true
        else -> false
    }

    /// `Character.isNewline`.
    private fun isNewline(character: String): Boolean = when (firstScalar(character)) {
        in 0x0A..0x0D, 0x85, 0x2028, 0x2029 -> true
        else -> false
    }

    /// `Character.isLetter`: the Alphabetic property of the first scalar.
    private fun isLetter(character: String): Boolean = Character.isAlphabetic(firstScalar(character))

    /// `isASCII && isNumber`: one scalar, 0–9 ("\r\n" is ASCII but not a number).
    private fun isAsciiDigit(character: String): Boolean =
        character.length == 1 && character[0] in '0'..'9'

    // MARK: Finding the object

    /// The first balanced `{…}` in a fenced code block if there is one, otherwise in the
    /// whole text. Typographic quotes are straightened first: notes apps and some chat
    /// clients "smarten" a copied block.
    fun extractObject(text: String): String? {
        val straightened = text
            .replace("“", "\"").replace("”", "\"")
            .replace("„", "\"").replace("‘", "'")
            .replace("’", "'")
        val fenced = straightened.split("```").filterIndexed { index, _ -> index % 2 == 1 }
        for (block in fenced) {
            balancedObject(block)?.let { return it }
        }
        return balancedObject(straightened)
    }

    private fun balancedObject(text: String): String? {
        val chars = characters(text)
        val start = chars.indexOf("{")
        if (start < 0) return null
        var depth = 0
        var quote: String? = null
        var escaped = false
        var i = start
        while (i < chars.size) {
            val c = chars[i]
            val q = quote
            if (q != null) {
                if (escaped) escaped = false else if (c == "\\") escaped = true else if (c == q) quote = null
            } else if (c == "\"" || c == "'") {
                quote = c
            } else if (c == "{") {
                depth += 1
            } else if (c == "}") {
                depth -= 1
                if (depth == 0) return chars.subList(start, i + 1).joinToString("")
            }
            i += 1
        }
        // Unbalanced: a reply cut off mid-block. Hand over what there is; the parser fails
        // it as unreadable rather than calling it no routine at all.
        return chars.subList(start, chars.size).joinToString("")
    }

    // MARK: Parsing

    fun parse(text: String): Value? = Parser(characters(text)).value()

    private class Parser(val chars: List<String>) {
        var i = 0
        var depth = 0

        fun skip() {
            while (i < chars.size) {
                val c = chars[i]
                if (isWhitespace(c) || c == "﻿") { i += 1; continue }
                if (c == "/" && i + 1 < chars.size && chars[i + 1] == "/") {
                    while (i < chars.size && chars[i] != "\n") i += 1
                    continue
                }
                if (c == "/" && i + 1 < chars.size && chars[i + 1] == "*") {
                    i += 2
                    while (i + 1 < chars.size && !(chars[i] == "*" && chars[i + 1] == "/")) i += 1
                    i = minOf(chars.size, i + 2)
                    continue
                }
                break
            }
        }

        fun value(): Value? {
            skip()
            if (i >= chars.size || depth >= 32) return null
            return when (chars[i]) {
                "{" -> obj()
                "[" -> array()
                "\"", "'" -> string()?.let { Value.Str(it) }
                else -> bare()
            }
        }

        fun obj(): Value? {
            i += 1; depth += 1
            try {
                val fields = mutableListOf<Pair<String, Value>>()
                while (true) {
                    skip()
                    if (i >= chars.size) return null
                    if (chars[i] == "}") { i += 1; return Value.Obj(fields) }
                    if (chars[i] == ",") { i += 1; continue }
                    val name: String
                    if (chars[i] == "\"" || chars[i] == "'") {
                        name = string() ?: return null
                    } else {
                        val s = StringBuilder()
                        while (i < chars.size && chars[i] != ":" && !isWhitespace(chars[i])) { s.append(chars[i]); i += 1 }
                        if (s.isEmpty()) return null
                        name = s.toString()
                    }
                    skip()
                    if (i >= chars.size || chars[i] != ":") return null
                    i += 1
                    val v = value() ?: return null
                    fields.add(name to v)
                }
            } finally {
                depth -= 1
            }
        }

        fun array(): Value? {
            i += 1; depth += 1
            try {
                val items = mutableListOf<Value>()
                while (true) {
                    skip()
                    if (i >= chars.size) return null
                    if (chars[i] == "]") { i += 1; return Value.Arr(items) }
                    if (chars[i] == ",") { i += 1; continue }
                    items.add(value() ?: return null)
                }
            } finally {
                depth -= 1
            }
        }

        fun string(): String? {
            val quote = chars[i]
            i += 1
            val out = StringBuilder()
            while (i < chars.size) {
                val c = chars[i]
                i += 1
                if (c == quote) return out.toString()
                if (c == "\\" && i < chars.size) {
                    val e = chars[i]
                    i += 1
                    when (e) {
                        "n" -> out.append('\n')
                        "t" -> out.append('\t')
                        "r" -> out.append('\r')
                        "u" -> {
                            val hex = chars.subList(i, minOf(chars.size, i + 4)).joinToString("")
                            i = minOf(chars.size, i + 4)
                            // `Unicode.Scalar(code)` refuses a surrogate, so an escaped
                            // surrogate pair is dropped, exactly as on iOS.
                            val code = hexScalar(hex)
                            if (code != null && (code < 0xD800 || code > 0xDFFF)) out.appendCodePoint(code)
                        }
                        // Swift appends the escaped character itself: \b reads as "b".
                        else -> out.append(e)
                    }
                } else {
                    out.append(c)
                }
            }
            return null
        }

        /// A number, `true`, `false`, `null`, or an unquoted word (read as a string).
        fun bare(): Value? {
            val s = StringBuilder()
            while (i < chars.size && chars[i] != "," && chars[i] != "}" && chars[i] != "]" &&
                chars[i] != ":" && !isNewline(chars[i])) {
                s.append(chars[i]); i += 1
            }
            val token = s.toString().trim { isHorizontalWhitespace(it.code) }
            if (token.isEmpty()) return null
            when (token.lowercase(Locale.ROOT)) {
                "true" -> return Value.Bool(true)
                "false" -> return Value.Bool(false)
                "null" -> return Value.Null
            }
            val n = swiftDouble(token)
            if (n != null && n.isFinite()) return Value.Num(n)
            return Value.Str(token)
        }
    }

    /// Swift's `UInt32(hex, radix: 16)`: an optional sign, then hex digits only.
    private fun hexScalar(text: String): Int? {
        var digits = text
        var negative = false
        if (digits.startsWith("+")) digits = digits.drop(1)
        else if (digits.startsWith("-")) { digits = digits.drop(1); negative = true }
        if (digits.isEmpty() || !digits.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }) return null
        val value = digits.toInt(16)
        return if (negative && value != 0) null else value
    }

    private val decimalLiteral = Regex("[+-]?(\\d+(\\.\\d*)?|\\.\\d+)([eE][+-]?\\d+)?")
    private val hexLiteral = Regex("([+-]?)0[xX]([0-9a-fA-F]+(\\.[0-9a-fA-F]*)?|\\.[0-9a-fA-F]+)([pP][+-]?\\d+)?")

    /// Swift's `Double(String)` (strtod, whole token): decimal or hex-float, nothing
    /// trailing. Java's own parser also takes "1f" and "1d", which Swift does not.
    /// Infinity and NaN are left out because the caller keeps finite values only.
    private fun swiftDouble(token: String): Double? {
        if (decimalLiteral.matches(token)) return token.toDoubleOrNull()
        val hex = hexLiteral.matchEntire(token) ?: return null
        val (sign, mantissa, _, exponent) = hex.destructured
        return "${sign}0x$mantissa${exponent.ifEmpty { "p0" }}".toDoubleOrNull()
    }
}
