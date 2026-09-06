// SPDX-License-Identifier: MPL-2.0
// Original contributions Copyright 2026 Nuri Bruner.

import Foundation

// The routine, as value types. Everything here is what the runner executes, what the
// builder edits and what a log freezes — one vocabulary, no DTO layer, no separate
// "template model" that has to be kept in step.
//
// Vocabulary note that runs through the whole app: the MODEL says rep, the UI says
// pull. One rep IS one pull; nothing counts halves.

// MARK: - Sides

enum Side: String, Codable, Hashable, Sendable, CaseIterable {
    case left, right, both

    var other: Side {
        switch self {
        case .left:  .right
        case .right: .left
        case .both:  .both
        }
    }

    var name: String {
        switch self {
        case .left:  String(localized: "Left")
        case .right: String(localized: "Right")
        case .both:  String(localized: "Both")
        }
    }

    /// Set in caps because it is read at arm's length, mid-set, by someone whose eyes
    /// are on a fingerboard rather than on the phone.
    var prompt: String {
        switch self {
        case .left:  String(localized: "LEFT")
        case .right: String(localized: "RIGHT")
        case .both:  String(localized: "BOTH")
        }
    }
}

/// How a set is shared between hands.
enum HandMode: String, Codable, Hashable, Sendable, CaseIterable {
    case alternateEachRep      // L R L R … one pull at a time
    case alternateEachSet      // all of one side, then all of the other, WITHIN a set
    case bothHands             // one pull, both hands

    /// How many sides a set covers. `repsPerSide × this` is its real rep count, and
    /// `PlanMath.repCount` is the only place that multiplication is allowed to happen.
    var sideCount: Int {
        switch self {
        case .alternateEachRep, .alternateEachSet: 2
        case .bothHands: 1
        }
    }

    /// Every set starts here — alternation RESETS at each set boundary, so no set ever
    /// begins on the "wrong" hand because the set before it had an odd rep count.
    var startSide: Side {
        switch self {
        case .alternateEachRep, .alternateEachSet: .left
        case .bothHands: .both
        }
    }

    var name: String {
        switch self {
        case .alternateEachRep: String(localized: "Alternate each pull")
        case .alternateEachSet: String(localized: "One hand at a time")
        case .bothHands:        String(localized: "Both hands")
        }
    }

    var explainer: String {
        switch self {
        case .alternateEachRep: String(localized: "Left, right, left, right — swapping hands every pull.")
        case .alternateEachSet: String(localized: "All six on the left, then all six on the right, inside one set.")
        case .bothHands:        String(localized: "One pull with both hands on the edge. Reps per side is just the number of pulls.")
        }
    }

    /// The decode door. A mode written by a newer build lands here as an unknown raw
    /// and becomes the default rather than throwing — the routine survives, one field
    /// is wrong, and `SessionTemplate.handModeRaw` still holds the original verbatim.
    init(fallback raw: String) {
        self = HandMode(rawValue: raw) ?? .alternateEachRep
    }
}

// MARK: - One row of the routine

struct SetPlan: Identifiable, Hashable, Sendable, Codable {
    /// Editor identity, not content identity. Two sets can be byte-identical and still
    /// be distinct rows — Nuri's protocol repeats front-2 at a different position.
    var id: UUID = UUID()
    var grip: GripSpec = GripSpec()
    /// Reps PER SIDE — the unit Nuri actually speaks ("6 reps each side"). The real
    /// count is this × `HandMode.sideCount`; the property NAME is the whole defence
    /// against the silent factor of two.
    var repsPerSide: Int = 6
    /// nil == follow the routine's rhythm. The prefill leaves every one of these nil,
    /// which is what makes "change every rest to 25 s" a single edit.
    var holdSeconds: Int? = nil
    var restSeconds: Int? = nil
    var targetLoKg: Double? = nil
    var targetHiKg: Double? = nil
    /// This set's own percentage-of-max band. nil == follow the routine's, which is what
    /// makes "everything at 17–22 %" a single edit. Only consulted when the set carries
    /// no explicit kg band — see `PlanMath.targetBand(_:in:maxKg:)` for the precedence.
    var targetLoPercent: Double? = nil
    var targetHiPercent: Double? = nil
    var note: String = ""

    /// Decode clamps. `repsRange` starts at 0 because a zero-rep set is representable
    /// (and dropped by `SessionPlan.executable`); the UI floor is 1.
    static let repsRange = 0...20
    // Match the hold dial: positive whole seconds, including short 1–2 s pulls.
    static let holdRange = 1...120
    static let restRange = 0...600
    /// 1 %…100 %. The ceiling is 100 rather than something "sensible" like 60 because a
    /// max-effort routine is a legitimate thing to author, and the floor is above zero
    /// because a 0 % target is a target of nothing.
    static let percentRange = 0.01...1.0

    var overridesTiming: Bool { holdSeconds != nil || restSeconds != nil }

    /// An explicit band ON THIS SET, of either kind — NOT "this set will show a target",
    /// which also depends on the routine's band and on a max existing for the grip.
    var hasTarget: Bool { targetLoKg != nil || targetHiKg != nil }
    var hasPercentTarget: Bool { targetLoPercent != nil || targetHiPercent != nil }

    /// Normalized on the way out: an upside-down band is never STORED, and is never
    /// returned either. One endpoint alone gives a degenerate range — a target line
    /// rather than a band, which is exactly what one endpoint means.
    var targetBand: ClosedRange<Double>? { Self.band(lo: targetLoKg, hi: targetHiKg) }

    /// This set's own percentage band, ignoring the routine's.
    var targetPercentBand: ClosedRange<Double>? {
        Self.band(lo: targetLoPercent, hi: targetHiPercent)
    }

    /// The one place two optional endpoints become a range, shared with `SessionPlan` so
    /// kg bands and percentage bands cannot normalize differently.
    static func band(lo: Double?, hi: Double?) -> ClosedRange<Double>? {
        switch (lo, hi) {
        case (.some(let lo), .some(let hi)): return Swift.min(lo, hi)...Swift.max(lo, hi)
        case (.some(let lo), .none):         return lo...lo
        case (.none, .some(let hi)):         return hi...hi
        case (.none, .none):                 return nil
        }
    }

    /// FROZEN — see `SessionPlan.CodingKeys`. Additive only.
    enum CodingKeys: String, CodingKey {
        case id, grip, repsPerSide, holdSeconds, restSeconds, targetLoKg, targetHiKg, note
        case targetLoPercent, targetHiPercent
    }
}

// In an extension so the memberwise `SetPlan(grip:repsPerSide:)` survives — declaring
// `init(from:)` in the body would delete it, and the prefill is memberwise.
extension SetPlan {
    init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        self.id = c.value(.id, or: UUID())
        self.grip = c.value(.grip, or: GripSpec())
        self.repsPerSide = Self.repsRange.clamping(c.value(.repsPerSide, or: 6))
        let hold: Int? = c.optional(.holdSeconds)
        let rest: Int? = c.optional(.restSeconds)
        self.holdSeconds = hold.map { Self.holdRange.clamping($0) }
        self.restSeconds = rest.map { Self.restRange.clamping($0) }
        self.targetLoKg = c.optional(.targetLoKg)
        self.targetHiKg = c.optional(.targetHiKg)
        self.targetLoPercent = c.optional(.targetLoPercent).map { Self.percentRange.clamping($0) }
        self.targetHiPercent = c.optional(.targetHiPercent).map { Self.percentRange.clamping($0) }
        self.note = c.value(.note, or: "")
    }
}

// MARK: - The routine

/// What the runner executes and what a log freezes. Deliberately carries NO
/// scheduling: reminders and sessions-a-day are things a person arranges, not things a
/// session does, and mixing them in is how a "session" grows into Frez's three layers.
struct SessionPlan: Hashable, Sendable, Codable {
    var name: String = String(localized: "Daily no-hangs")
    var sets: [SetPlan] = []
    var handMode: HandMode = .alternateEachRep
    /// RHYTHM — the routine-level defaults every set inherits unless it overrides.
    var holdSeconds: Int = 10
    var restSeconds: Int = 20
    var setBreakSeconds: Int = 60
    /// "Get ready" before the FIRST rep of each set, not before every rep.
    var leadInSeconds: Int = 5
    /// Engagement DETECTOR, not intensity: "you have taken the load". One value for the
    /// whole routine because ~2 kg sits below every set's working load. Intensity lives
    /// in each set's target band.
    var thresholdKg: Double = 2.0
    /// Hold the rest countdown until you are actually OFF the edge.
    ///
    /// Default ON, which is a deliberate behaviour change for routines written before
    /// this existed: starting the clock the instant the hold completes charges your rest
    /// for the two or three seconds it takes to stand down, every rep, so a 20 s rest was
    /// never 20 s of rest. Off is still honest — a fixed cadence you pace yourself to.
    var waitForReleaseBeforeRest: Bool = true

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
    var pausesOutsideTargetBand: Bool = true

    /// TARGET LOAD as a fraction of your max on whichever grip a set uses — the routine
    /// default every set inherits, exactly like `holdSeconds`.
    ///
    /// A percentage rather than kilograms because the prescription IS a fraction ("20 %
    /// of max"), and because ONE band then means the right load on all six grips at once:
    /// a four-finger half crimp and a middle-2 have very different maxes and the same
    /// intensity. Kilograms would freeze one day's arithmetic and go stale the next time
    /// a max is recorded — which is the whole reason this is not just a seeded number.
    var targetLoPercent: Double? = nil
    var targetHiPercent: Double? = nil

    /// nil when no band is set. Normalized the same way `SetPlan.targetBand` is — one
    /// endpoint alone is a LINE, which is what one endpoint means.
    var targetPercentBand: ClosedRange<Double>? {
        SetPlan.band(lo: targetLoPercent, hi: targetHiPercent)
    }

    /// Shared with the editor so typed values survive persistence and sharing.
    static let setBreakRange = 0...900
    // Preserve legacy sub-0.5 kg thresholds while accepting the editor's full upper limit.
    static let thresholdRange = 0.1...30.0

    /// Sets that will actually run. Everything in `PlanMath` operates on this, and the
    /// runner freezes THIS, so `RepSummary.setIndex` is unambiguous forever after.
    var executable: SessionPlan {
        var out = self
        out.sets = sets.filter { $0.repsPerSide > 0 }
        return out
    }

    /// FROZEN — names already in every routine blob ever written. ADDITIVE only: a new
    /// case is fine (older blobs simply lack the key and take the decoder's default), a
    /// renamed or removed one silently drops a field on every routine on disk.
    enum CodingKeys: String, CodingKey {
        case name, sets, handMode, holdSeconds, restSeconds, setBreakSeconds, leadInSeconds, thresholdKg
        case waitForReleaseBeforeRest
        case targetLoPercent, targetHiPercent
        case pausesOutsideTargetBand
    }
}

extension SessionPlan {
    init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        self.name = c.value(.name, or: String(localized: "Daily no-hangs"))
        self.sets = c.value(.sets, or: [])
        // Falls back rather than throwing, and that is SAFE here only because a
        // SessionPlan blob lives inside a write-once WorkoutLog. The LIVE routine keeps
        // its mode in SessionTemplate.handModeRaw verbatim, so a mode from a newer
        // build is never rewritten by this build reading it.
        self.handMode = HandMode(fallback: c.value(.handMode, or: HandMode.alternateEachRep.rawValue))
        self.holdSeconds = SetPlan.holdRange.clamping(c.value(.holdSeconds, or: 10))
        self.restSeconds = SetPlan.restRange.clamping(c.value(.restSeconds, or: 20))
        self.setBreakSeconds = Self.setBreakRange.clamping(c.value(.setBreakSeconds, or: 60))
        self.leadInSeconds = (0...60).clamping(c.value(.leadInSeconds, or: 5))
        // A zero threshold would read as "engaged" against sensor noise and start the
        // clock before the user touched the edge.
        self.thresholdKg = Self.thresholdRange.clamping(c.value(.thresholdKg, or: 2.0))
        // Absent key → true, so an existing routine GAINS the behaviour. See the
        // property for why that is the right default rather than the safe-looking one.
        self.waitForReleaseBeforeRest = c.value(.waitForReleaseBeforeRest, or: true)
        // Absent key → true, so every routine written before this existed keeps the
        // behaviour it was authored under.
        self.pausesOutsideTargetBand = c.value(.pausesOutsideTargetBand, or: true)
        self.targetLoPercent = c.optional(.targetLoPercent).map { SetPlan.percentRange.clamping($0) }
        self.targetHiPercent = c.optional(.targetHiPercent).map { SetPlan.percentRange.clamping($0) }
    }
}

// MARK: - Reminders

/// A daily reminder slot. NO UUID, on purpose: identity IS the time, so two reminders
/// at 08:00 are unrepresentable and the notification identifier can be content-keyed —
/// editing 08:00 → 09:00 replaces the pending request in place instead of leaking one.
struct ReminderTime: Hashable, Comparable, Sendable, Identifiable, Codable {
    var minutesFromMidnight: Int {
        didSet { minutesFromMidnight = Self.range.clamping(minutesFromMidnight) }
    }

    static let range = 0...1439

    init(minutesFromMidnight: Int) {
        // didSet does not run during init, so the clamp is repeated here rather than
        // assumed.
        self.minutesFromMidnight = Self.range.clamping(minutesFromMidnight)
    }

    init(hour: Int, minute: Int) {
        self.init(minutesFromMidnight: hour * 60 + minute)
    }

    var hour: Int { minutesFromMidnight / 60 }
    var minute: Int { minutesFromMidnight % 60 }
    var id: Int { minutesFromMidnight }

    /// "r0480" — readable in a log AND content-keyed, so the identifier for 08:00 is
    /// the same on both of the user's devices without anything being synced.
    var slot: String { "r" + String(format: "%04d", minutesFromMidnight) }

    /// Locale-correct — 08:00 or 8:00 AM, never hand-assembled. Built on a fixed
    /// mid-January date so a DST transition can never shift the hour being displayed.
    func displayText(calendar: Calendar = .current) -> String {
        var comps = DateComponents()
        comps.year = 2001
        comps.month = 1
        comps.day = 15
        comps.hour = hour
        comps.minute = minute
        let date = calendar.date(from: comps) ?? Date(timeIntervalSince1970: 0)
        return date.formatted(date: .omitted, time: .shortened)
    }

    /// Hour and minute only — a repeating daily trigger, never a dated one.
    var dateComponents: DateComponents { DateComponents(hour: hour, minute: minute) }

    /// The ladder new slots are filled from, in order: morning, evening, then the two
    /// in-between times someone training four times a day actually uses.
    static let defaults: [ReminderTime] = [
        ReminderTime(hour: 8, minute: 0),
        ReminderTime(hour: 19, minute: 0),
        ReminderTime(hour: 12, minute: 30),
        ReminderTime(hour: 21, minute: 30),
    ]

    static func < (lhs: ReminderTime, rhs: ReminderTime) -> Bool {
        lhs.minutesFromMidnight < rhs.minutesFromMidnight
    }
}

// Codable is SINGLE-VALUE over `minutesFromMidnight`, and decodes through the clamping
// initializer so a bad number cannot become a notification at hour 47.
extension ReminderTime {
    init(from decoder: Decoder) throws {
        self.init(minutesFromMidnight: try decoder.singleValueContainer().decode(Int.self))
    }

    func encode(to encoder: Encoder) throws {
        var c = encoder.singleValueContainer()
        try c.encode(minutesFromMidnight)
    }
}

// MARK: - The draft

/// The wizard's working copy — and, because the wizard IS the editor, the ONE type
/// that "new routine", "prefill" and "edit what I have" all produce. There is no
/// separate create path to keep in step with a separate edit path.
struct RoutineDraft: Hashable, Sendable, Codable {
    /// nil = this routine does not exist yet.
    var templateID: UUID? = nil
    var plan: SessionPlan = SessionPlan()
    var sessionsPerDay: Int = 2
    var reminders: [ReminderTime] = Array(ReminderTime.defaults.prefix(2))
    /// Times removed by lowering `sessionsPerDay`, kept so raising it again restores
    /// the user's own 19:00 rather than a default.
    var parkedReminders: [ReminderTime] = []
    var remindersEnabled: Bool = true
    /// A WHENEVER routine (Nuri, 2026-08-10): no daily target, no reminders, never
    /// owed — a max day is something you do when you're fresh, not a ritual you break.
    /// Sessions still log honestly; the routine just never asks for one.
    var isOnDemand: Bool = false

    static let sessionsRange = 1...4

    var isNew: Bool { templateID == nil }

    /// Why Save is refused, in the exact words shown under it. nil = ready.
    var validationIssue: String? {
        if plan.executable.sets.isEmpty { return String(localized: "Add at least one set with a pull in it.") }
        if remindersEnabled && reminders.isEmpty { return String(localized: "Add a reminder time, or turn reminders off.") }
        return nil
    }

    /// The shape that is safe to persist: name trimmed (empty → the house default),
    /// reminders sorted and deduped, target bands the right way up, dead sets dropped,
    /// sessions clamped. Called on the way INTO the store, never on the way out — a
    /// normalize-on-read would rewrite a blob nobody edited and sync a no-op.
    var normalized: RoutineDraft {
        var out = self
        let trimmed = out.plan.name.trimmingCharacters(in: .whitespacesAndNewlines)
        out.plan.name = trimmed.isEmpty ? String(localized: "Daily no-hangs") : trimmed
        out.plan.sets = out.plan.sets
            .filter { $0.repsPerSide > 0 }
            .map { set in
                var s = set
                if let lo = s.targetLoKg, let hi = s.targetHiKg, lo > hi {
                    s.targetLoKg = hi
                    s.targetHiKg = lo
                }
                if let lo = s.targetLoPercent, let hi = s.targetHiPercent, lo > hi {
                    s.targetLoPercent = hi
                    s.targetHiPercent = lo
                }
                s.note = s.note.trimmingCharacters(in: .whitespacesAndNewlines)
                return s
            }
        // The routine's own band gets the same treatment: dragging "From" past "To" is
        // one gesture in the builder, and an inverted band would resolve to an inverted
        // kilogram range on every set that inherits it.
        if let lo = out.plan.targetLoPercent, let hi = out.plan.targetHiPercent, lo > hi {
            out.plan.targetLoPercent = hi
            out.plan.targetHiPercent = lo
        }
        out.plan = Self.consolidatingInheritance(out.plan)
        // DEMOTE a routine-level band onto the sets, then clear it — the migration
        // that makes "load lives per set" true for routines authored before it was.
        // Resolution-preserving: a set with any target of its own already outranked
        // the routine's, and a set without one resolves to the same numbers it
        // inherited, now written where the editor can see them.
        if let band = out.plan.targetPercentBand {
            out.plan.sets = out.plan.sets.map { set in
                guard !set.hasTarget, !set.hasPercentTarget else { return set }
                var s = set
                s.targetLoPercent = band.lowerBound
                s.targetHiPercent = band.upperBound
                return s
            }
            out.plan.targetLoPercent = nil
            out.plan.targetHiPercent = nil
        }
        out.sessionsPerDay = Self.sessionsRange.clamping(out.sessionsPerDay)
        out.reminders = Self.tidy(out.reminders)
        out.parkedReminders = Self.tidy(out.parkedReminders)
        // A whenever routine cannot remind — the times are KEPT so flipping back to a
        // ritual restores the user's own schedule, but the switch is forced off.
        if out.isOnDemand { out.remindersEnabled = false }
        return out
    }

    /// Fold per-set values that are really ROUTINE values back where they belong.
    ///
    /// The setup deck edits hold, rest and the target band ON EACH GRIP CARD, so it
    /// writes a per-set override every time one is touched — and `addGrip` copies the
    /// previous grip, overrides included. A routine built that way arrived with every set
    /// overriding and `plan.holdSeconds` still at its factory 10, which broke inheritance
    /// in three visible ways: the document's RHYTHM card quoted a number no set used,
    /// changing it did nothing, and a set added later inherited that phantom while its
    /// siblings ran something else. The coach card's own promise — "change it here once
    /// and it changes everywhere" — was false for every deck-authored routine.
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
    private static func consolidatingInheritance(_ plan: SessionPlan) -> SessionPlan {
        var out = plan
        let live = out.sets
        guard !live.isEmpty else { return out }

        if let hold = uniform(live, { $0.holdSeconds }) { out.holdSeconds = hold }
        if let rest = uniform(live, { $0.restSeconds }) { out.restSeconds = rest }
        // TARGETS ARE NEVER PROMOTED any more — load lives per set (Nuri, 2026-08-10:
        // "target load needs to only be in each set"), and with no routine-level load
        // editor left in the builder, a promoted band would be active but invisible.
        // The inverse — DEMOTION of a legacy routine-level band — happens in
        // `normalized` right after this pass.

        out.sets = out.sets.map { set in
            var s = set
            if s.holdSeconds == out.holdSeconds { s.holdSeconds = nil }
            if s.restSeconds == out.restSeconds { s.restSeconds = nil }
            return s
        }
        return out
    }

    /// The one value every set carries, or nil if they disagree or any is inheriting.
    private static func uniform<T: Equatable>(_ sets: [SetPlan],
                                              _ value: (SetPlan) -> T?) -> T? {
        guard let first = value(sets[0]) else { return nil }
        return sets.allSatisfy { value($0) == first } ? first : nil
    }

    /// The ONE mutator that parks and restores reminder times. Anything that sets
    /// `sessionsPerDay` directly loses the user's times the first time they try two a
    /// day, change their mind, and change it back.
    mutating func setSessionsPerDay(_ n: Int) {
        let target = Self.sessionsRange.clamping(n)
        if target < reminders.count {
            parkedReminders = Self.tidy(parkedReminders + reminders.suffix(reminders.count - target))
            reminders = Array(reminders.prefix(target))
        } else if target > reminders.count {
            var filled = reminders
            var parked = parkedReminders
            while filled.count < target, !parked.isEmpty {
                filled.append(parked.removeFirst())
            }
            // Then the default ladder, skipping anything already on the list.
            for candidate in ReminderTime.defaults where filled.count < target {
                if !filled.contains(candidate) { filled.append(candidate) }
            }
            reminders = Self.tidy(filled)
            parkedReminders = Self.tidy(parked)
        }
        sessionsPerDay = target
    }

    /// Sorted and deduped by the time itself — identity IS the time.
    private static func tidy(_ times: [ReminderTime]) -> [ReminderTime] {
        Array(Set(times)).sorted()
    }

    /// FROZEN — this is what the debounced draft rescue writes to UserDefaults.
    /// `isOnDemand` arrived later and decodes as false on every draft stashed before it
    /// existed, which is the honest reading — those drafts were all rituals.
    enum CodingKeys: String, CodingKey {
        case templateID, plan, sessionsPerDay, reminders, parkedReminders, remindersEnabled
        case isOnDemand
    }
}

extension RoutineDraft {
    init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        self.templateID = c.optional(.templateID)
        self.plan = c.value(.plan, or: SessionPlan())
        self.sessionsPerDay = Self.sessionsRange.clamping(c.value(.sessionsPerDay, or: 2))
        self.reminders = c.value(.reminders, or: Array(ReminderTime.defaults.prefix(2)))
        self.parkedReminders = c.value(.parkedReminders, or: [])
        self.remindersEnabled = c.value(.remindersEnabled, or: true)
        self.isOnDemand = c.value(.isOnDemand, or: false)
    }
}

// MARK: - Seeds

extension RoutineDraft {
    /// Nuri's actual protocol. A computed VAR, never a `static let`: a lazy `let` mints
    /// the six SetPlan UUIDs once per process and would hand identical ids to two
    /// routines built in one sitting.
    ///
    /// Note what is NOT here — every SetPlan leaves `holdSeconds` and `restSeconds`
    /// nil, so all six inherit the routine's 10 s / 20 s. That is the point of the
    /// RHYTHM block: the prefill contains ZERO timing overrides, so changing one rest
    /// interval is one edit.
    ///
    /// Three of the positions are a considered GUESS, not dictation: the protocol
    /// states half crimp for the 4-finger set and crimp for the last two, and says
    /// nothing for the 3-finger and the plain 2-finger sets. They ship as half crimp
    /// and open hand so the plain pairs read as the low-intensity counterparts of the
    /// crimped ones that follow, and step 3 of the builder is where that gets corrected
    /// in four taps.
    static var starter: RoutineDraft {
        RoutineDraft(
            templateID: nil,
            plan: SessionPlan(
                name: String(localized: "Daily no-hangs"),
                sets: [
                    // 20 mm, 4 fingers half-crimp — 6 each side       key "20|IMRL|halfCrimp"
                    SetPlan(grip: GripSpec(edgeMM: 20, fingers: .four,       position: .halfCrimp),
                            repsPerSide: 6),
                    // 3 fingers — 6 each side                          key "20|IMR|halfCrimp"
                    SetPlan(grip: GripSpec(edgeMM: 20, fingers: .frontThree, position: .halfCrimp),
                            repsPerSide: 6),
                    // front 2 — 2 each side                            key "20|IM|openHand"
                    SetPlan(grip: GripSpec(edgeMM: 20, fingers: .frontTwo,   position: .openHand),
                            repsPerSide: 2),
                    // middle 2 — 2 each side                           key "20|MR|openHand"
                    SetPlan(grip: GripSpec(edgeMM: 20, fingers: .middleTwo,  position: .openHand),
                            repsPerSide: 2),
                    // front 2 crimped — 1 each side                    key "20|IM|fullCrimp"
                    SetPlan(grip: GripSpec(edgeMM: 20, fingers: .frontTwo,   position: .fullCrimp),
                            repsPerSide: 1),
                    // middle 2 crimped — 1 each side                   key "20|MR|fullCrimp"
                    SetPlan(grip: GripSpec(edgeMM: 20, fingers: .middleTwo,  position: .fullCrimp),
                            repsPerSide: 1),
                ],
                handMode: .alternateEachRep,   // "swapping hands every pull"
                holdSeconds: 10,               // "~10s pulls"
                restSeconds: 20,               // "20s rest"
                setBreakSeconds: 60,
                leadInSeconds: 5,
                thresholdKg: 2.0),             // engagement detector, not intensity
            sessionsPerDay: 2,                 // "twice a day, every day"
            reminders: [ReminderTime(hour: 8, minute: 0), ReminderTime(hour: 19, minute: 0)],
            parkedReminders: [],
            // TRUE. The permission ask happens on SAVE, not on tapping a prefill — by
            // then the user has read step 5 of 5 and seen both times on screen.
            remindersEnabled: true)
    }

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
    static func blank(named name: String = String(localized: "My routine")) -> RoutineDraft {
        var d = RoutineDraft()
        d.plan.name = name
        d.plan.sets = []
        return d
    }

    /// Fresh SetPlan ids and no templateID: a duplicate must never share row identity
    /// with its source, or reordering one reorders the other under `.onMove`.
    static func copying(_ source: RoutineDraft) -> RoutineDraft {
        var d = source
        d.templateID = nil
        d.plan.name = String(localized: "Copy of \(source.plan.name)")
        d.plan.sets = d.plan.sets.map { set in
            var s = set
            s.id = UUID()
            return s
        }
        return d
    }

    /// The C4 max-testing ladder (camp4humanperformance.com/blog/progressor), as a
    /// one-tap prefill: 3 s pulls, 5 s rests, one hand at a time, one grip ramped over
    /// three sets to a final two-rep max effort. The ramp sets carry percent bands; the
    /// final set deliberately carries NONE — a band gates the rep clock, and pausing a
    /// max attempt the instant it fades below 95 % is exactly wrong. A WHENEVER routine
    /// by construction: nobody maxes daily.
    ///
    /// A computed var for the same reason `.starter` is — a `static let` would mint the
    /// SetPlan UUIDs once per process.
    static var maxDay: RoutineDraft {
        let grip = GripSpec(edgeMM: 20, fingers: .four, position: .halfCrimp)
        var d = RoutineDraft()
        d.plan.name = String(localized: "Max day")
        d.plan.holdSeconds = 3
        d.plan.restSeconds = 5
        d.plan.setBreakSeconds = 120
        d.plan.handMode = .alternateEachSet
        d.plan.sets = [
            SetPlan(grip: grip, repsPerSide: 4, targetLoPercent: 0.50, targetHiPercent: 0.60),
            SetPlan(grip: grip, repsPerSide: 4, targetLoPercent: 0.65, targetHiPercent: 0.75),
            SetPlan(grip: grip, repsPerSide: 4, targetLoPercent: 0.80, targetHiPercent: 0.90),
            SetPlan(grip: grip, repsPerSide: 2),
        ]
        d.sessionsPerDay = 1
        d.isOnDemand = true
        return d
    }
}
