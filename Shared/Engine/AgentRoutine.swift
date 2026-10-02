// SPDX-License-Identifier: MPL-2.0
// Original contributions Copyright 2026 Nuri Bruner.

import Foundation

// A routine written by somebody's AI chat (Nuri, 2026-10-02): the app hands out
// INSTRUCTIONS to paste into any assistant, the assistant interviews the climber and
// replies with one block of JSON, and that reply is pasted back here.
//
// The format is NOT `SessionPlan`'s Codable. That one carries row ids, fractions written
// as 0.7, and fields nobody would describe in a chat, and an assistant would get them
// wrong. This is a small vocabulary of whole numbers and listed words, read FORGIVINGLY:
// the reply arrives wrapped in prose and code fences, keys in whatever case the model
// chose, a percentage as 70 or as 0.7, a hold of "7 s". Anything out of range is brought
// into range and REPORTED (`Note`), never silently changed and never refused, because the
// preview that follows is where the climber checks what the assistant meant.
//
// The JSON reader is our own (`Lenient`), not Foundation's: its JSON5 mode and
// kotlinx's lenient mode accept different things, and the Kotlin twin
// (`AgentRoutine.kt`) must read every reply exactly as this one does. Both are pinned by
// `Fixtures/agent/`.
//
// Pure Foundation: this file compiles into the widget target too.

enum AgentRoutine {
    /// The `format` an assistant is told to write. A reply naming a HIGHER version reads as
    /// "update the app"; one naming none is read as version 1.
    static let formatVersion = 1
    static let formatName = "get-a-grip-routine"

    // MARK: - What the assistant is told

    /// Copied to the clipboard verbatim. ENGLISH whatever the UI language (one schema for
    /// every reader, the analysis export's rule); the assistant still talks to the climber
    /// in theirs. Every range here is the app's own clamp — change one, change both.
    static let instructions = """
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
    """

    // MARK: - What can go wrong

    enum Failure: Error, Equatable, Sendable {
        /// No JSON object anywhere in the paste: the climber copied the chat, not the block.
        case noRoutine
        /// An object, but not one this reader can make sense of.
        case unreadable
        /// A routine with no usable sets.
        case noSets
        /// The reply names a newer format than this build reads.
        case newerFormat

        /// Stable, for fixtures.
        var code: String {
            switch self {
            case .noRoutine:   "noRoutine"
            case .unreadable:  "unreadable"
            case .noSets:      "noSets"
            case .newerFormat: "newerFormat"
            }
        }

        var message: String {
            switch self {
            case .noRoutine:
                String(localized: "Couldn't find a routine in what you pasted. Copy the AI's code block and try again.")
            case .unreadable:
                String(localized: "Couldn't read this routine. Ask the AI to write it again as one code block.")
            case .noSets:
                String(localized: "This routine has no sets. Ask the AI to add at least one.")
            case .newerFormat:
                String(localized: "This routine needs a newer Get a Grip. Update the app to import it.")
            }
        }
    }

    // MARK: - What was changed on the way in

    /// Which value a `Note` is about. The raw value is the fixture code.
    enum Field: String, Sendable, Hashable {
        case name, hands, startingHand, hold, rest, setBreak, leadIn, sessionsPerDay
        case pauseTheClock, threshold, sets, edge, fingers, grip, pulls, percent, timedMax, kg

        var label: String {
            switch self {
            case .name:           String(localized: "Name")
            case .hands:          String(localized: "Hands")
            case .startingHand:   String(localized: "First hand")
            case .hold:           String(localized: "Hold")
            case .rest:           String(localized: "Rest between pulls")
            case .setBreak:       String(localized: "Break between sets")
            case .leadIn:         String(localized: "Lead-in")
            case .sessionsPerDay: String(localized: "Sessions a day")
            case .pauseTheClock:  String(localized: "Pause the clock")
            case .threshold:      String(localized: "A pull counts above")
            case .sets:           String(localized: "Sets")
            case .edge:           String(localized: "Edge")
            case .fingers:        String(localized: "Fingers")
            case .grip:           String(localized: "Grip")
            case .pulls:          String(localized: "Pulls")
            case .percent:        String(localized: "Target")
            case .timedMax:       String(localized: "Timed max")
            case .kg:             String(localized: "Target")
            }
        }
    }

    /// One value the reader had to change: out of range, an unrecognised word, or missing
    /// where the routine cannot do without it. Shown in the preview, so nothing an
    /// assistant wrote is altered behind the climber's back.
    struct Note: Hashable, Sendable {
        /// 1-based set number, nil for the routine.
        var set: Int?
        var field: Field
        /// As written (trimmed), or "—" when it was missing.
        var from: String
        /// What the routine now holds, in the same units.
        var to: String

        var code: String {
            (set.map { "set\($0)." } ?? "") + "\(field.rawValue):\(from)->\(to)"
        }

        /// "Set 2 · Hold: 200 → 120"
        var message: String {
            let label = set.map { String(localized: "Set \($0) · \(field.label)") } ?? field.label
            return String(localized: "\(label): \(from) → \(to)")
        }
    }

    struct Reading: Sendable {
        var draft: RoutineDraft
        var notes: [Note]
    }

    // MARK: - Reading a reply

    static let maxSets = 50
    static let maxNameCharacters = 60
    static let maxNoteCharacters = 500
    static let timedMaxRange = 3...60
    static let sessionsPerDayRange = RoutineDraft.sessionsRange
    static let kgRange = 0.0...250.0
    static let percentRange = 1...100
    static let leadInRange = 0...60

    /// `makeID` is a seam for fixtures, which need the same set ids on every run.
    static func read(_ text: String, makeID: () -> UUID = { UUID() }) -> Result<Reading, Failure> {
        guard let source = Lenient.extractObject(from: text) else { return .failure(.noRoutine) }
        guard case .object(var root)? = Lenient.parse(source) else { return .failure(.unreadable) }
        // An assistant that wraps it — {"routine": {...}} — still wrote a routine.
        if Lenient.field(root, "sets") == nil, case .object(let inner)? = Lenient.field(root, "routine") {
            root = inner
        }
        if case .string(let format)? = Lenient.field(root, "format"),
           let version = Lenient.trailingInteger(format), version > formatVersion {
            return .failure(.newerFormat)
        }
        guard case .array(let rawSets)? = Lenient.field(root, "sets") else {
            return Lenient.field(root, "sets") == nil ? .failure(.noSets) : .failure(.unreadable)
        }
        var notes: [Note] = []
        var plan = SessionPlan()
        // A blank builder's name, not the house default: an unnamed AI routine is not the
        // starter routine.
        plan.name = String(localized: "My routine")

        // Models file settings in either place — a lead-in inside `fineTuning`, a pause
        // rule at the top level — so every routine-wide key is looked up in both, the
        // place the format puts it first.
        let fineFields: [(String, Lenient.Value)] = {
            if case .object(let f)? = Lenient.field(root, "fineTuning") { return f }
            return []
        }()
        let top = root + fineFields
        let fine = fineFields + root

        // Identity
        if case .string(let name)? = Lenient.field(top, "name") {
            let trimmed = name.trimmingCharacters(in: .whitespacesAndNewlines)
            plan.name = String(trimmed.prefix(maxNameCharacters))
        }

        // Hands
        if let raw = Lenient.field(top, "hands") {
            let word = Lenient.word(raw)
            if let mode = handMode(word) {
                plan.handMode = mode
            } else {
                plan.handMode = .alternateEachRep
                notes.append(Note(set: nil, field: .hands, from: Lenient.display(raw), to: "alternate"))
            }
        }
        if let raw = Lenient.field(top, "startingHand") {
            switch Lenient.word(raw) {
            case "left", "l": plan.startingHand = .left
            case "right", "r": plan.startingHand = .right
            default:
                notes.append(Note(set: nil, field: .startingHand, from: Lenient.display(raw), to: "left"))
            }
        }

        // Rhythm
        plan.holdSeconds = integer(top, "holdSeconds", in: SetPlan.holdRange, default: plan.holdSeconds,
                                   field: .hold, set: nil, seconds: true, notes: &notes)
        plan.restSeconds = integer(top, "restSeconds", in: SetPlan.restRange, default: plan.restSeconds,
                                   field: .rest, set: nil, seconds: true, notes: &notes)
        plan.setBreakSeconds = integer(top, "setBreakSeconds", in: SessionPlan.setBreakRange,
                                       default: plan.setBreakSeconds, field: .setBreak, set: nil,
                                       seconds: true, notes: &notes)
        plan.leadInSeconds = integer(top, "leadInSeconds", in: leadInRange, default: plan.leadInSeconds,
                                     field: .leadIn, set: nil, seconds: true, notes: &notes)

        // Fine tuning
        if let raw = Lenient.field(fine, "pauseTheClock") {
            if let gate = bandGate(Lenient.word(raw)) {
                plan.targetBandGate = gate
            } else {
                notes.append(Note(set: nil, field: .pauseTheClock, from: Lenient.display(raw), to: "out-of-range"))
            }
        }
        if let release = Lenient.field(fine, "startRestWhenILetGo").flatMap(Lenient.bool) {
            plan.waitForReleaseBeforeRest = release
        }
        if let raw = Lenient.field(fine, "pullCountsAboveKg"), let kg = Lenient.number(raw) {
            let clamped = SessionPlan.thresholdRange.clamping(kg)
            if clamped != kg {
                notes.append(Note(set: nil, field: .threshold, from: Lenient.format(kg), to: Lenient.format(clamped)))
            }
            plan.thresholdKg = clamped
        }

        // Cadence
        var sessionsPerDay = RoutineDraft().sessionsPerDay
        let onDemand = Lenient.field(top, "onDemand").flatMap(Lenient.bool) ?? false
        if !onDemand {
            sessionsPerDay = integer(top, "sessionsPerDay", in: sessionsPerDayRange, default: sessionsPerDay,
                                     field: .sessionsPerDay, set: nil, notes: &notes)
        }

        // The routine's own target: percentages stay on the plan (normalizing demotes
        // them onto the sets); kilograms have no routine level, so they land on every set
        // that names none of its own.
        let routineTarget = Lenient.field(root, "target").map { target($0, set: nil, notes: &notes) }
        if case .percent(let lo, let hi, let seconds)? = routineTarget {
            plan.targetLoPercent = lo
            plan.targetHiPercent = hi
            plan.targetMaxSeconds = seconds
        }

        // Sets
        if rawSets.count > maxSets {
            notes.append(Note(set: nil, field: .sets, from: String(rawSets.count), to: String(maxSets)))
        }
        for (index, rawSet) in rawSets.prefix(maxSets).enumerated() {
            guard case .object(let object) = rawSet else { continue }
            let number = index + 1
            var set = SetPlan(id: makeID())

            var grip = GripSpec()
            grip.edgeMM = integer(object, "edgeMm", in: GripSpec.edgeRange, default: grip.edgeMM,
                                  field: .edge, set: number, notes: &notes)
            if let raw = Lenient.field(object, "grip") {
                if let position = gripPosition(Lenient.word(raw)) {
                    grip.position = position
                } else {
                    notes.append(Note(set: number, field: .grip, from: Lenient.display(raw), to: "half-crimp"))
                }
            }
            if let raw = Lenient.field(object, "fingers") {
                if let fingers = fingerSet(raw) {
                    grip.fingers = fingers
                } else {
                    notes.append(Note(set: number, field: .fingers, from: Lenient.display(raw), to: "4"))
                }
            }
            // Re-asserted after both fields land: a pinch always carries the thumb, and the
            // order the two were set in must not decide that.
            if grip.position == .pinch { grip.fingers.insert(.thumb) }
            set.grip = grip

            if Lenient.field(object, "pulls") == nil {
                notes.append(Note(set: number, field: .pulls, from: "—", to: String(set.repsPerSide)))
            } else {
                set.repsPerSide = integer(object, "pulls", in: 1...SetPlan.repsRange.upperBound,
                                          default: set.repsPerSide, field: .pulls, set: number, notes: &notes)
            }
            if Lenient.field(object, "holdSeconds") != nil {
                set.holdSeconds = integer(object, "holdSeconds", in: SetPlan.holdRange, default: plan.holdSeconds,
                                          field: .hold, set: number, seconds: true, notes: &notes)
            }
            if Lenient.field(object, "restSeconds") != nil {
                set.restSeconds = integer(object, "restSeconds", in: SetPlan.restRange, default: plan.restSeconds,
                                          field: .rest, set: number, seconds: true, notes: &notes)
            }
            switch Lenient.field(object, "target").map({ target($0, set: number, notes: &notes) }) ?? routineTarget {
            case .percent(let lo, let hi, let seconds)?:
                // A set that inherits the routine's percentage keeps nothing of its own;
                // only an explicit set target is written onto the set.
                if Lenient.field(object, "target") != nil {
                    set.targetLoPercent = lo
                    set.targetHiPercent = hi
                    set.targetMaxSeconds = seconds
                }
            case .kg(let lo, let hi)?:
                set.targetLoKg = lo
                set.targetHiKg = hi
            case .noLoad?, nil:
                break
            }
            if case .string(let note)? = Lenient.field(object, "note") {
                set.note = String(note.trimmingCharacters(in: .whitespacesAndNewlines).prefix(maxNoteCharacters))
            }
            plan.sets.append(set)
        }
        guard !plan.sets.isEmpty else { return .failure(.noSets) }

        // Built like an import, from the defaults: the climber's reminders, nothing
        // switched on behind their back, and nil identity so the store CREATES a routine.
        var draft = RoutineDraft()
        draft.templateID = nil
        draft.plan = plan
        draft.setSessionsPerDay(sessionsPerDay)
        draft.isOnDemand = onDemand
        draft.remindersEnabled = false
        return .success(Reading(draft: draft, notes: notes))
    }

    // MARK: - Vocabulary

    private static func handMode(_ word: String) -> HandMode? {
        switch word {
        case "alternate", "alternating", "alternateeachpull", "alternateeachrep", "alternateeachhang",
             "alternatehands", "lr", "lrlr", "eachpull":
            .alternateEachRep
        case "onehandatatime", "oneatatime", "onehand", "alternateeachset", "singlehand", "singlearm",
             "onearm", "eachhand", "perhand", "onehanded":
            .alternateEachSet
        case "both", "bothhands", "twohands", "together", "twohanded", "twoarms", "botharms":
            .bothHands
        default: nil
        }
    }

    private static func bandGate(_ word: String) -> TargetBandGate? {
        switch word {
        case "outofrange", "outside", "outsiderange", "outsidetherange", "always", "both", "default":
            .outside
        case "belowrange", "below", "belowtherange", "under", "underrange", "onlybelow", "onlybelowrange":
            .below
        case "never", "off", "none", "no", "false":
            .off
        default: nil
        }
    }

    private static func gripPosition(_ word: String) -> GripPosition? {
        switch word {
        case "halfcrimp", "half", "hc", "crimp", "halfcrimped": .halfCrimp
        case "openhand", "open", "oh", "openhanded", "extended": .openHand
        case "fullcrimp", "full", "fc", "closedcrimp", "closed", "fullcrimped": .fullCrimp
        case "drag", "threefingerdrag", "3fingerdrag", "3fdrag", "frontthreedrag": .drag
        case "pinch", "pinchgrip": .pinch
        case "fingercurl", "curl", "curls", "fingercurls": .fingerCurl
        default: nil
        }
    }

    private static func fingerSet(_ raw: Lenient.Value) -> FingerSet? {
        let word: String
        if let n = Lenient.number(raw), case .number = raw {
            word = String(Lenient.whole(n.rounded(.towardZero)))
        } else {
            word = Lenient.word(raw)
        }
        switch word {
        case "4", "four", "all", "4fingers", "fourfingers", "allfour", "full": return .four
        case "3", "front3", "frontthree", "three", "3fingers", "threefingers", "front3fingers": return .frontThree
        case "back3", "backthree": return .backThree
        case "2", "front2", "fronttwo", "two", "2fingers", "twofingers", "front2fingers": return .frontTwo
        case "middle2", "middletwo": return .middleTwo
        case "back2", "backtwo": return .backTwo
        case "index", "indexfinger": return .index
        // A lone finger on a hangboard is almost always the middle one (a "mono").
        case "1", "middle", "middlefinger", "mono": return .middle
        case "ring", "ringfinger": return .ring
        case "little", "pinky", "pinkie", "littlefinger": return .little
        default: break
        }
        // The app's own tokens ("IM", "MRL", "IMRLT"), which a model reading a shared
        // routine might echo back.
        let letters = word.uppercased()
        guard !letters.isEmpty, letters.allSatisfy({ FingerSet.letters.contains($0) }) else { return nil }
        let set = FingerSet(token: letters)
        return set.isEmpty ? nil : set
    }

    // MARK: - Numbers and targets

    private enum Target {
        case percent(lo: Double, hi: Double, seconds: Int?)
        case kg(lo: Double, hi: Double)
        case noLoad
    }

    private static func target(_ raw: Lenient.Value, set: Int?, notes: inout [Note]) -> Target {
        guard case .object(let object) = raw else { return .noLoad }
        if let percent = Lenient.field(object, "percentOfMax") ?? Lenient.field(object, "percent"),
           let range = Lenient.range(percent) {
            // Fractions are accepted as written by anyone who thinks of 70 % as 0.7.
            let scale = range.hi <= 1 ? 100.0 : 1.0
            let lo = range.lo * scale, hi = range.hi * scale
            let bounds = Double(percentRange.lowerBound)...Double(percentRange.upperBound)
            let cLo = bounds.clamping(lo.rounded()), cHi = bounds.clamping(hi.rounded())
            if cLo != lo.rounded() || cHi != hi.rounded() {
                notes.append(Note(set: set, field: .percent,
                                  from: "\(Lenient.format(lo))–\(Lenient.format(hi))",
                                  to: "\(Lenient.format(cLo))–\(Lenient.format(cHi))"))
            }
            var seconds: Int?
            if let max = Lenient.field(object, "max") {
                if let n = Lenient.number(max) {
                    let whole = Lenient.whole(n.rounded())
                    let clamped = timedMaxRange.clamping(whole)
                    if clamped != whole {
                        notes.append(Note(set: set, field: .timedMax, from: Lenient.format(n), to: String(clamped)))
                    }
                    seconds = clamped
                } else if Lenient.word(max) != "peak" {
                    notes.append(Note(set: set, field: .timedMax, from: Lenient.display(max), to: "peak"))
                }
            }
            return .percent(lo: min(cLo, cHi) / 100, hi: max(cLo, cHi) / 100, seconds: seconds)
        }
        if let kg = Lenient.field(object, "kg"), let range = Lenient.range(kg) {
            let cLo = kgRange.clamping(range.lo), cHi = kgRange.clamping(range.hi)
            if cLo != range.lo || cHi != range.hi {
                notes.append(Note(set: set, field: .kg,
                                  from: "\(Lenient.format(range.lo))–\(Lenient.format(range.hi))",
                                  to: "\(Lenient.format(cLo))–\(Lenient.format(cHi))"))
            }
            return .kg(lo: min(cLo, cHi), hi: max(cLo, cHi))
        }
        return .noLoad
    }

    /// A whole number from `key`, clamped into `range`, with a note when it moved.
    /// Missing or unreadable returns `fallback` without a note: the app filling a default
    /// is what the instructions promise. `seconds` reads "2 min" as 120.
    private static func integer(_ object: [(String, Lenient.Value)], _ key: String, in range: ClosedRange<Int>,
                                default fallback: Int, field: Field, set: Int?, seconds: Bool = false,
                                notes: inout [Note]) -> Int {
        guard let raw = Lenient.field(object, key),
              let n = seconds ? Lenient.seconds(raw) : Lenient.number(raw) else { return fallback }
        let whole = Lenient.whole(n.rounded())
        let clamped = range.clamping(whole)
        if clamped != whole || n != n.rounded() {
            notes.append(Note(set: set, field: field, from: Lenient.format(n), to: String(clamped)))
        }
        return clamped
    }
}

// MARK: - A forgiving JSON reader

/// Reads what chat assistants actually emit: JSON with comments, trailing commas, single
/// quotes, bare keys and typographic quotes. Deliberately small and deliberately OURS —
/// see the file header. Keys are matched loosely (`field`): case, `_`, `-` and spaces
/// are ignored, so `edge_mm`, `EdgeMM` and `edge-mm` are one key.
enum Lenient {
    indirect enum Value: Equatable, Sendable {
        case object([(String, Value)])
        case array([Value])
        case string(String)
        case number(Double)
        case bool(Bool)
        case null

        static func == (a: Value, b: Value) -> Bool {
            switch (a, b) {
            case (.object(let x), .object(let y)):
                x.count == y.count && zip(x, y).allSatisfy { $0.0 == $1.0 && $0.1 == $1.1 }
            case (.array(let x), .array(let y)): x == y
            case (.string(let x), .string(let y)): x == y
            case (.number(let x), .number(let y)): x == y
            case (.bool(let x), .bool(let y)): x == y
            case (.null, .null): true
            default: false
            }
        }
    }

    /// Key aliases an assistant plausibly writes for each key the format defines.
    private static let aliases: [String: [String]] = [
        "edgemm": ["edge", "edgesize", "edgedepth", "depthmm", "mm"],
        "holdseconds": ["hold", "holdtime", "hangseconds", "hang", "work", "workseconds", "on"],
        "restseconds": ["rest", "resttime", "off", "offseconds", "restbetweenpulls"],
        "setbreakseconds": ["setbreak", "setrest", "restbetweensets", "breakbetweensets", "setrestseconds"],
        "leadinseconds": ["leadin", "countdown", "countdownseconds", "getready"],
        "pulls": ["reps", "repetitions", "pullsperhand", "repsperhand", "repsperside", "hangs"],
        "sessionsperday": ["sessions", "timesperday", "perday"],
        "percentofmax": ["percentmax", "percentage", "pct"],
        "startinghand": ["firsthand", "startwith", "start"],
        "pausetheclock": ["pauseclock", "pause", "pausewhen"],
        "pullcountsabovekg": ["threshold", "thresholdkg", "pullthreshold", "pullthresholdkg"],
        "startrestwheniletgo": ["waitforrelease", "waitforreleasebeforerest", "restwheniletgo"],
    ]

    static func key(_ raw: String) -> String {
        String(raw.lowercased().unicodeScalars.filter { CharacterSet.alphanumerics.contains($0) })
    }

    /// The value under `name` or one of its aliases; first match wins.
    static func field(_ object: [(String, Value)], _ name: String) -> Value? {
        let wanted = key(name)
        if let hit = object.first(where: { key($0.0) == wanted }) { return hit.1 }
        for alias in aliases[wanted] ?? [] {
            if let hit = object.first(where: { key($0.0) == alias }) { return hit.1 }
        }
        return nil
    }

    /// A word for vocabulary matching: lowercased, letters and digits only.
    static func word(_ value: Value) -> String {
        switch value {
        case .string(let s): key(s)
        case .number(let n): format(n)
        case .bool(let b): b ? "true" : "false"
        default: ""
        }
    }

    /// The value as the assistant wrote it, for a note.
    static func display(_ value: Value) -> String {
        switch value {
        case .string(let s): String(s.trimmingCharacters(in: .whitespacesAndNewlines).prefix(40))
        case .number(let n): format(n)
        case .bool(let b): b ? "true" : "false"
        case .null: "null"
        case .array, .object: "…"
        }
    }

    /// A number, from a number or from the first number in a string ("7 s", "20mm").
    static func number(_ value: Value) -> Double? {
        switch value {
        case .number(let n): return n.isFinite ? n : nil
        case .string(let s):
            var digits = ""
            var seenDigit = false
            for character in s {
                if character.isASCII, character.isNumber {
                    digits.append(character); seenDigit = true
                } else if (character == "." || character == ",") && seenDigit && !digits.contains(".") {
                    digits.append(".")
                } else if character == "-" && !seenDigit && digits.isEmpty {
                    digits.append(character)
                } else if seenDigit {
                    break
                }
            }
            if digits.hasSuffix(".") { digits.removeLast() }
            guard seenDigit, let n = Double(digits), n.isFinite else { return nil }
            return n
        default: return nil
        }
    }

    /// A duration in seconds: a number, "7 s", "7 sec", or "2 min" (minutes × 60).
    static func seconds(_ value: Value) -> Double? {
        guard let n = number(value) else { return nil }
        guard case .string(let s) = value else { return n }
        let unit = s.lowercased().drop(while: { !$0.isLetter }).prefix(while: { $0.isLetter })
        return unit == "m" || unit.hasPrefix("min") ? n * 60 : n
    }

    static func bool(_ value: Value) -> Bool? {
        switch value {
        case .bool(let b): b
        case .number(let n): n != 0
        case .string(let s):
            switch key(s) {
            case "true", "yes", "on", "y": true
            case "false", "no", "off", "n": false
            default: nil
            }
        default: nil
        }
    }

    /// [lo, hi], a single number, or a string like "70-80".
    static func range(_ value: Value) -> (lo: Double, hi: Double)? {
        switch value {
        case .array(let items):
            let numbers = items.compactMap(number)
            guard let first = numbers.first else { return nil }
            let last = numbers.count > 1 ? numbers[1] : first
            return (min(first, last), max(first, last))
        case .number(let n):
            return (n, n)
        case .string(let s):
            let parts = s.split(whereSeparator: { "-–—".contains($0) })
                .compactMap { number(.string(String($0))) }
            if parts.count >= 2 { return (min(parts[0], parts[1]), max(parts[0], parts[1])) }
            if let only = parts.first { return (only, only) }
            return nil
        default:
            return nil
        }
    }

    /// The integer at the end of a format string: "get-a-grip-routine/1" → 1.
    static func trailingInteger(_ s: String) -> Int? {
        let digits = s.reversed().prefix(while: { $0.isASCII && $0.isNumber })
        return digits.isEmpty ? nil : Int(String(digits.reversed()))
    }

    /// An integral `Double` as an `Int`, SATURATING: `Int(_:)` traps on a finite value no
    /// `Int` holds, and a pasted `1e300` must be clamped and reported, not crash the app.
    /// Kotlin's `toLong()` saturates the same way, so both readers report the same note.
    static func whole(_ n: Double) -> Int {
        if n >= Double(Int.max) { return Int.max }
        if n <= Double(Int.min) { return Int.min }
        return Int(n)
    }

    /// Plain, locale-free: 7, 7.5, 0.25.
    static func format(_ n: Double) -> String {
        // An absurd value is reported as absurd, not as the 300 digits of its binary value.
        if abs(n) >= 1e9 { return (n < 0 ? "-" : "") + "999999999+" }
        if n == n.rounded(), abs(n) < 1e15 { return String(Int(n)) }
        var text = String(format: "%.2f", n)
        while text.hasSuffix("0") { text.removeLast() }
        if text.hasSuffix(".") { text.removeLast() }
        return text
    }

    // MARK: Finding the object

    /// The first balanced `{…}` in a fenced code block if there is one, otherwise in the
    /// whole text. Typographic quotes are straightened first: notes apps and some chat
    /// clients "smarten" a copied block.
    static func extractObject(from text: String) -> String? {
        let straightened = text
            .replacingOccurrences(of: "\u{201C}", with: "\"").replacingOccurrences(of: "\u{201D}", with: "\"")
            .replacingOccurrences(of: "\u{201E}", with: "\"").replacingOccurrences(of: "\u{2018}", with: "'")
            .replacingOccurrences(of: "\u{2019}", with: "'")
        let fenced = straightened.components(separatedBy: "```").enumerated()
            .filter { $0.offset % 2 == 1 }.map(\.element)
        for block in fenced {
            if let object = balancedObject(in: block) { return object }
        }
        return balancedObject(in: straightened)
    }

    private static func balancedObject(in text: String) -> String? {
        let chars = Array(text)
        guard let start = chars.firstIndex(of: "{") else { return nil }
        var depth = 0
        var quote: Character?
        var escaped = false
        var i = start
        while i < chars.count {
            let c = chars[i]
            if let q = quote {
                if escaped { escaped = false } else if c == "\\" { escaped = true } else if c == q { quote = nil }
            } else if c == "\"" || c == "'" {
                quote = c
            } else if c == "{" {
                depth += 1
            } else if c == "}" {
                depth -= 1
                if depth == 0 { return String(chars[start...i]) }
            }
            i += 1
        }
        // Unbalanced: a reply cut off mid-block. Hand over what there is; the parser fails
        // it as unreadable rather than calling it no routine at all.
        return String(chars[start...])
    }

    // MARK: Parsing

    static func parse(_ text: String) -> Value? {
        var parser = Parser(chars: Array(text))
        guard let value = parser.value() else { return nil }
        return value
    }

    private struct Parser {
        let chars: [Character]
        var i = 0
        var depth = 0

        mutating func skip() {
            while i < chars.count {
                let c = chars[i]
                if c.isWhitespace || c == "\u{FEFF}" { i += 1; continue }
                if c == "/", i + 1 < chars.count, chars[i + 1] == "/" {
                    while i < chars.count, chars[i] != "\n" { i += 1 }
                    continue
                }
                if c == "/", i + 1 < chars.count, chars[i + 1] == "*" {
                    i += 2
                    while i + 1 < chars.count, !(chars[i] == "*" && chars[i + 1] == "/") { i += 1 }
                    i = min(chars.count, i + 2)
                    continue
                }
                break
            }
        }

        mutating func value() -> Value? {
            skip()
            guard i < chars.count, depth < 32 else { return nil }
            switch chars[i] {
            case "{": return object()
            case "[": return array()
            case "\"", "'": return string().map(Value.string)
            default: return bare()
            }
        }

        mutating func object() -> Value? {
            i += 1; depth += 1
            defer { depth -= 1 }
            var fields: [(String, Value)] = []
            while true {
                skip()
                guard i < chars.count else { return nil }
                if chars[i] == "}" { i += 1; return .object(fields) }
                if chars[i] == "," { i += 1; continue }
                let name: String
                if chars[i] == "\"" || chars[i] == "'" {
                    guard let s = string() else { return nil }
                    name = s
                } else {
                    var s = ""
                    while i < chars.count, chars[i] != ":", !chars[i].isWhitespace { s.append(chars[i]); i += 1 }
                    guard !s.isEmpty else { return nil }
                    name = s
                }
                skip()
                guard i < chars.count, chars[i] == ":" else { return nil }
                i += 1
                guard let v = value() else { return nil }
                fields.append((name, v))
            }
        }

        mutating func array() -> Value? {
            i += 1; depth += 1
            defer { depth -= 1 }
            var items: [Value] = []
            while true {
                skip()
                guard i < chars.count else { return nil }
                if chars[i] == "]" { i += 1; return .array(items) }
                if chars[i] == "," { i += 1; continue }
                guard let v = value() else { return nil }
                items.append(v)
            }
        }

        mutating func string() -> String? {
            let quote = chars[i]
            i += 1
            var out = ""
            while i < chars.count {
                let c = chars[i]
                i += 1
                if c == quote { return out }
                if c == "\\", i < chars.count {
                    let e = chars[i]
                    i += 1
                    switch e {
                    case "n": out.append("\n")
                    case "t": out.append("\t")
                    case "r": out.append("\r")
                    case "u":
                        let hex = String(chars[i..<min(chars.count, i + 4)])
                        i = min(chars.count, i + 4)
                        if let code = UInt32(hex, radix: 16), let scalar = Unicode.Scalar(code) {
                            out.append(Character(scalar))
                        }
                    default: out.append(e)
                    }
                } else {
                    out.append(c)
                }
            }
            return nil
        }

        /// A number, `true`, `false`, `null`, or an unquoted word (read as a string).
        mutating func bare() -> Value? {
            var s = ""
            while i < chars.count, !",}]:".contains(chars[i]), !chars[i].isNewline { s.append(chars[i]); i += 1 }
            let token = s.trimmingCharacters(in: .whitespaces)
            guard !token.isEmpty else { return nil }
            switch token.lowercased() {
            case "true": return .bool(true)
            case "false": return .bool(false)
            case "null": return .null
            default: break
            }
            if let n = Double(token), n.isFinite { return .number(n) }
            return .string(token)
        }
    }
}
