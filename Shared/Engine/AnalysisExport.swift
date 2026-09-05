// SPDX-License-Identifier: MPL-2.0
// Original contributions Copyright 2026 Nuri Bruner.

import Foundation

/// The whole training history as ONE self-describing Markdown document, written to be
/// pasted into a language model.
///
/// **It is deliberately English, whatever the app's language is.** A French export and an
/// English one would be two different schemas describing the same rows, and the reader on
/// the other end has to learn the vocabulary from the document itself — so the document
/// says so in its own first legend line and then keeps one set of words forever. Every
/// string in this file is a bare Swift literal for exactly that reason: nothing here goes
/// through `String(localized:)`, and nothing here may borrow a display name from a type
/// that does (`GripSpec.shortName`, `SessionKind.name`, `RPE.name` are all translated).
///
/// **PURE, and deterministic.** No clock is read here — the caller passes `generatedOn`
/// — and every ordering is total, so two calls on the same input produce byte-identical
/// strings. That is what makes the whole thing testable and what stops a diff of two
/// exports being noise.
///
/// It consumes VALUES only (`Shared/` may not see SwiftData); `AnalysisExportAssembler`
/// in `Sources/Store` is the one place models become these.
enum AnalysisExport {

    // MARK: - What the document is made of

    /// How a session's hold time was measured. `WorkoutLog` carries no flag for this, so
    /// the assembler infers it — see `AnalysisExportAssembler`. It matters because a
    /// timer-only session's seconds come off the wall clock rather than off the gauge,
    /// and its kilogram columns are empty rather than zero.
    enum Timing: String, Hashable, Sendable {
        /// A gauge measured the pull: hold time accrued from device timestamps.
        case gauge
        /// The runner ran the plan with no gauge attached: hold time is wall clock.
        case timerOnly
        /// Nothing was timed — a session logged after the fact.
        case logged
    }

    /// One session, already flattened out of its model and its blobs.
    struct Session: Hashable, Sendable {
        /// Sort tiebreak, never printed — two sessions can share a start instant after a
        /// CloudKit merge and the order still has to be total.
        var id: UUID = UUID()
        var day: DayStamp = DayStamp(raw: 0)
        var startedAt: Date = Date(timeIntervalSinceReferenceDate: 0)
        /// The routine's name as it reads TODAY where the routine survives, else the
        /// name frozen into the log — the same resolution History's own list uses.
        var routineName: String = ""
        var kind: SessionKind = .hang
        var minutes: Int?
        var rpe: RPE?
        var fingerStrain: FingerStrain?
        var peakKg: Double = 0
        var avgKg: Double = 0
        var totalHeldSeconds: Double = 0
        var plannedReps: Int = 0
        var completedReps: Int = 0
        var timing: Timing = .gauge
        var reps: [RepSummary] = []
        var notes: String = ""
        var sessionsPerDayTarget: Int? = nil
        var finishedAt: Date? = nil
        var plan: SessionPlan? = nil
    }

    /// One recorded max, as a value.
    struct MaxEntry: Hashable, Sendable {
        var grip: GripSpec = GripSpec()
        var side: Side = .both
        var kg: Double = 0
        var day: DayStamp = DayStamp(raw: 0)
        /// Used for ordering and for "which max was current when this session happened".
        /// Never printed — the day is what the document shows.
        var recordedAt: Date = Date(timeIntervalSinceReferenceDate: 0)
        var source: MaxSource = .manual

        var gripKey: String { grip.key }
        var maxKey: String { MaxTable.key(grip: grip.key, side: side) }
    }

    /// Everything the formatter is allowed to know.
    struct Input: Hashable, Sendable {
        var sessions: [Session] = []
        var maxes: [MaxEntry] = []
        /// The day the export was taken — the anchor the 8-week boundary is measured from.
        var today: DayStamp = DayStamp(raw: 0)
        /// Passed in, never read from a clock here.
        var generatedOn: DayStamp = DayStamp(raw: 0)
        /// What "a full day" currently means, frozen into the newest log. Sessions each
        /// carry their own, which is why this is stated rather than assumed.
        var sessionsPerDayTarget: Int = 1

        var isEmpty: Bool { sessions.isEmpty && maxes.isEmpty }
    }

    // MARK: - The boundary

    /// Sessions on or after `today - 55` are written out rep by rep; everything older is
    /// rolled up by week. 56 days INCLUDING today, which is what "the last 8 weeks" means
    /// to somebody looking at a calendar.
    static let detailedDays = 56

    static func detailCutoff(today: DayStamp) -> DayStamp {
        today - (detailedDays - 1)
    }

    // MARK: - The document

    static func document(_ input: Input) -> String {
        var out: [String] = []

        let sessions = input.sessions.sorted(by: newestFirst)
        let maxes = input.maxes.sorted(by: oldestFirst)
        let cutoff = detailCutoff(today: input.today)
        let recent = sessions.filter { $0.day >= cutoff }
        let older = sessions.filter { $0.day < cutoff }

        out += title(input)
        out += legend(input, sessions: sessions)
        out += currentMaxes(maxes)
        out += maxHistory(maxes)
        out += recentSessions(recent, maxes: maxes, cutoff: cutoff)
        out += weeklyRollups(older, cutoff: cutoff)
        out += consistency(sessions, target: input.sessionsPerDayTarget, today: input.today)
        out += questions()

        return out.joined(separator: "\n") + "\n"
    }

    // MARK: - Title

    private static func title(_ input: Input) -> [String] {
        [
            "# Get a Grip — training export",
            "",
            "Generated \(isoDay(input.generatedOn)). Loads are KILOGRAMS (kg); durations are SECONDS (s) unless the column says otherwise. Dates are YYYY-MM-DD.",
            "",
        ]
    }

    // MARK: - Legend

    private static func legend(_ input: Input, sessions: [Session]) -> [String] {
        var out = ["## Legend", ""]
        out.append("This document is written in English whatever language the app is set to, so that one fixed schema is being read every time. `—` in any cell means the value was never recorded; it is never a zero and never a guess.")
        out.append("")

        out.append("- **Pull** — one rep: a single hold on one grip with one hand (or with both). **Set** — a run of pulls sharing one grip. A session is a sequence of sets.")
        out.append("- **Plan s** is the hold the routine asked for. **Held s** is what was actually accrued while the load was over the rep's own threshold — so a pull that came off the edge halfway reads short rather than being rewritten.")
        out.append("- **How held time was measured** is stated on every session header. `gauge` means the seconds came from the force gauge's own sample timestamps. `timer-only` means the session ran with no gauge attached and the seconds came off the WALL CLOCK; those sessions carry no kilograms at all. `logged` means the session was written down after the fact — no reps, only a duration.")
        out.append("- **Peak kg / Avg kg are stored PER REP** — the numbers in the rep tables are that pull's own peak and its mean while engaged, not the session's. The session header's peak and average are the session-level figures kept beside them; for a session whose rep blob is missing or unreadable, the header figures are all that survives.")
        out.append("- **% max** is that rep's own PEAK divided by the max on file for that grip AND hand **as it stood on the day of the session** — a max recorded later never rewrites what an older session was pulling at. Resolution is specific-beats-general: a left or right pull uses that hand's max and falls back to a both-hands max; a BOTH-hands pull resolves only against a both-hands max, never against the two hands added together. No max on file means a blank, never a number.")
        out.append("- **Target kg** is what the rep was ASKED to pull for that hand, frozen at the time. Blank where the routine set no target or the grip had no max to take a percentage of.")
        out.append("- **Outcome** is one of `completed`, `earlyRelease` (came off the edge), `skipped` (deliberately passed over — skipped pulls ARE recorded, and they count toward the planned total but never toward the completed one; their kilogram cells are blank because the pull never happened, not zero), `aborted` (the session or the link ended mid-rep).")
        out.append("- **Hands**: `L` left, `R` right, `B` both.")
        out.append("- **A climbing day counts as training.** A day at the gym is more finger load than the hangboard session it displaced, so `climbVolume` and `climbLimit` sessions settle a day the same way a routine session does. `benchmark` is a max-testing day, logged automatically the first time a gauge-measured max lands. `hangManual` is a weighted or max hang done away from the gauge.")
        out.append("")

        out.append("**Grip notation** — `20mm 4F HC` is a 20 mm edge, four fingers, half crimp.")
        out.append("")
        out.append("Edge is the depth in millimetres. Then the digits on the hold:")
        out.append("")
        for line in fingerCodeLegend(sessions: sessions, maxes: input.maxes) {
            out.append("- \(line)")
        }
        out.append("")
        out.append("Then how the hand is set on it:")
        out.append("")
        for line in positionCodeLegend(sessions: sessions, maxes: input.maxes) {
            out.append("- \(line)")
        }
        out.append("")

        out.append("**The two effort axes** are graded by hand after a session and are optional — either or both may be blank. They are stored as 1–5 integers, not as a Borg CR-10 score:")
        out.append("")
        out.append("- **Effort** (systemic — how hard the session felt overall): 1 easy · 2 comfortable · 3 solid · 4 hard · 5 all I had.")
        out.append("- **Fingers** (local — how much it asked of the fingers specifically, the stronger signal in the climbing session-RPE literature): 1 nothing · 2 light · 3 worked · 4 taxed · 5 wrecked.")
        out.append("")
        return out
    }

    /// Only the codes the data actually uses, so the legend is a key to THIS document
    /// rather than a tour of a vocabulary nobody in it trained.
    private static func fingerCodeLegend(sessions: [Session], maxes: [MaxEntry]) -> [String] {
        var seen: Set<String> = []
        var used: [FingerSet] = []
        for grip in allGrips(sessions: sessions, maxes: maxes) {
            let code = fingerCode(grip.fingers)
            if seen.insert(code).inserted { used.append(grip.fingers) }
        }
        guard !used.isEmpty else { return ["`4F` — all four fingers. (No grips in this export yet.)"] }
        return used
            .map { (code: fingerCode($0), text: fingerEnglish($0)) }
            .sorted { $0.code < $1.code }
            .map { "`\($0.code)` — \($0.text)" }
    }

    private static func positionCodeLegend(sessions: [Session], maxes: [MaxEntry]) -> [String] {
        var seen: Set<String> = []
        var used: [GripPosition] = []
        for grip in allGrips(sessions: sessions, maxes: maxes) {
            let code = positionCode(grip.position)
            if seen.insert(code).inserted { used.append(grip.position) }
        }
        guard !used.isEmpty else { return ["`HC` — half crimp. (No grips in this export yet.)"] }
        return used
            .map { (code: positionCode($0), text: positionEnglish($0)) }
            .sorted { $0.code < $1.code }
            .map { "`\($0.code)` — \($0.text)" }
    }

    private static func allGrips(sessions: [Session], maxes: [MaxEntry]) -> [GripSpec] {
        // Deterministic: sessions in their given order, then reps in theirs, then maxes.
        var grips: [GripSpec] = []
        for session in sessions.sorted(by: newestFirst) {
            grips.append(contentsOf: session.reps.map(\.grip))
        }
        grips.append(contentsOf: maxes.sorted(by: oldestFirst).map(\.grip))
        return grips
    }

    // MARK: - Current maxes

    private static func currentMaxes(_ maxes: [MaxEntry]) -> [String] {
        var out = ["## Current maxes", ""]
        let current = newestPerKey(maxes)
        guard !current.isEmpty else {
            out += ["No maxes recorded. Every percentage target in this app is a fraction of a max, so a routine that prescribes percentages resolves to nothing until one exists.", ""]
            return out
        }
        out.append("The newest record for each grip and hand. `measured` means the gauge watched it happen; `typed` means it was entered by hand.")
        out.append("")
        out.append("| Grip | Hand | kg | Date | Source |")
        out.append("| --- | --- | ---: | --- | --- |")
        for entry in current {
            out.append("| \(gripCode(entry.grip)) | \(handCode(entry.side)) | \(kgText(entry.kg)) | \(isoDay(entry.day)) | \(sourceText(entry.source)) |")
        }
        out.append("")
        return out
    }

    /// Newest per grip AND hand — folding on the grip alone would let a right-hand max
    /// recorded second become the grip's current number and drop the left out of the
    /// table entirely, which is the same bug `MaxRecord.maxKey` exists to prevent.
    private static func newestPerKey(_ maxes: [MaxEntry]) -> [MaxEntry] {
        var newest: [String: MaxEntry] = [:]
        for entry in maxes.sorted(by: oldestFirst) {
            newest[entry.maxKey] = entry
        }
        return newest.values.sorted(by: byGripThenHand)
    }

    // MARK: - Max history

    private static func maxHistory(_ maxes: [MaxEntry]) -> [String] {
        var out = ["## Max history", ""]
        guard !maxes.isEmpty else {
            out += ["Nothing recorded yet.", ""]
            return out
        }
        out.append("Every record ever written, oldest first within each grip and hand. Records are append-only, so this is the progression itself.")
        out.append("")

        var groups: [String: [MaxEntry]] = [:]
        for entry in maxes { groups[entry.maxKey, default: []].append(entry) }
        let order = groups.values.compactMap(\.first).sorted(by: byGripThenHand)

        for head in order {
            let entries = (groups[head.maxKey] ?? []).sorted(by: oldestFirst)
            out.append("### \(gripCode(head.grip)) · \(handWord(head.side))")
            out.append("")
            out.append("| Date | kg | Source |")
            out.append("| --- | ---: | --- |")
            for entry in entries {
                out.append("| \(isoDay(entry.day)) | \(kgText(entry.kg)) | \(sourceText(entry.source)) |")
            }
            out.append("")
        }
        return out
    }

    // MARK: - Sessions, last 8 weeks

    private static func recentSessions(_ sessions: [Session], maxes: [MaxEntry],
                                       cutoff: DayStamp) -> [String] {
        var out = ["## Sessions, last 8 weeks", ""]
        // When the whole history fits inside the window, saying "on or after <cutoff>"
        // implies eight weeks of behaviour that do not exist — the history's own start
        // is the honest bound (flagged by the feature's first real reader, 2026-08-28).
        let oldest = sessions.map(\.day.raw).min()
        if let oldest, oldest >= cutoff.raw {
            out.append("Every session on record — the history begins \(isoDay(DayStamp(raw: oldest))). Newest first.")
        } else {
            out.append("Every session on or after \(isoDay(cutoff)), newest first.")
        }
        out.append("")
        guard !sessions.isEmpty else {
            out += ["No sessions in this window.", ""]
            return out
        }

        // ONE sweep for the whole window: the sessions are walked oldest-first while a
        // pointer advances over the maxes, so "the max as it stood that day" costs a
        // dictionary write per record rather than a filter per session.
        let tables = tablesAtSessionTime(sessions, maxes: maxes)

        for session in sessions {
            out.append("### \(sessionHeader(session))")
            out.append("")
            out += sessionBody(session, table: tables[session.id] ?? MaxTable())
        }
        return out
    }

    private static func sessionHeader(_ session: Session) -> String {
        var parts = [isoDay(session.day), session.routineName, session.kind.rawValue]
        if let minutes = session.minutes { parts.append("\(minutes) min") }
        // "held 0s" on a session that was never timed is noise dressed as a measurement.
        if session.timing != .logged {
            parts.append("held \(durationText(session.totalHeldSeconds))")
        }
        parts.append(timingWord(session.timing))
        return parts.filter { !$0.isEmpty }.joined(separator: " · ")
    }

    private static func sessionBody(_ session: Session, table: MaxTable) -> [String] {
        var out: [String] = []
        var facts: [String] = []
        facts.append("Pulls: \(session.completedReps) completed of \(session.plannedReps) planned.")
        if session.timing == .gauge {
            facts.append("Session peak \(kgText(session.peakKg)) kg, session average \(kgText(session.avgKg)) kg.")
        }
        facts.append("Effort \(axisText(session.rpe?.rawValue)), fingers \(axisText(session.fingerStrain?.rawValue)).")
        out.append(facts.joined(separator: " "))
        out.append("")

        if !session.notes.isEmpty {
            out.append("Note: \(singleLine(session.notes))")
            out.append("")
        }

        guard !session.reps.isEmpty else {
            if session.kind == .hang {
                out.append("No rep detail survives for this session.")
                out.append("")
            }
            return out
        }

        out.append("| # | Hand | Grip | Plan s | Held s | Peak kg | Avg kg | Target kg | % max | Outcome |")
        out.append("| ---: | --- | --- | ---: | ---: | ---: | ---: | --- | ---: | --- |")
        for (index, rep) in session.reps.enumerated() {
            // A SKIPPED pull registers no load because it never happened, which is not
            // the same fact as "it registered zero" — printing 0.0 would put a pull that
            // was passed over into any average taken down this column.
            let measured = session.timing == .gauge && rep.outcome != .skipped
            let peak = measured ? kgText(rep.peakKg) : blank
            let avg = measured ? kgText(rep.avgKg) : blank
            let band = rep.targetBand.map { "\(kgText($0.lowerBound))–\(kgText($0.upperBound))" } ?? blank
            let intensity = measured
                ? percentText(rep.peakKg, maxKg: table.max(grip: rep.grip.key, side: rep.side))
                : blank
            out.append("| \(index + 1) | \(handCode(rep.side)) | \(gripCode(rep.grip)) | \(rep.targetSeconds) | \(secondsText(rep.heldSeconds)) | \(peak) | \(avg) | \(band) | \(intensity) | \(rep.outcome.rawValue) |")
        }
        out.append("")
        return out
    }

    /// The max table as it stood at each session's own start instant, keyed by session id.
    private static func tablesAtSessionTime(_ sessions: [Session],
                                            maxes: [MaxEntry]) -> [UUID: MaxTable] {
        let ascending = sessions.sorted { newestFirst($1, $0) }
        let records = maxes.sorted(by: oldestFirst)
        var table = MaxTable()
        var cursor = 0
        var result: [UUID: MaxTable] = [:]
        for session in ascending {
            while cursor < records.count, records[cursor].recordedAt <= session.startedAt {
                let entry = records[cursor]
                table.record(entry.kg, grip: entry.gripKey, side: entry.side)
                cursor += 1
            }
            result[session.id] = table
        }
        return result
    }

    // MARK: - Weekly rollups

    private static func weeklyRollups(_ sessions: [Session], cutoff: DayStamp) -> [String] {
        var out = ["## Older than 8 weeks, by week", ""]
        guard !sessions.isEmpty else {
            out += ["Nothing older than \(isoDay(cutoff)).", ""]
            return out
        }
        out.append("Everything before \(isoDay(cutoff)), one row per week (weeks start on Monday). Median effort axes are over the sessions in that week that were graded.")
        out.append("")
        out.append("| Week of | Sessions | Climb days | Pulls done/planned | Time under tension | Median effort | Median fingers |")
        out.append("| --- | ---: | ---: | --- | ---: | ---: | ---: |")

        var weeks: [Int: [Session]] = [:]
        for session in sessions { weeks[weekStart(session.day).raw, default: []].append(session) }

        for raw in weeks.keys.sorted(by: >) {
            let week = weeks[raw] ?? []
            let climbDays = Set(week.filter { $0.kind.isClimb }.map(\.day.raw)).count
            let done = week.reduce(0) { $0 + $1.completedReps }
            let planned = week.reduce(0) { $0 + $1.plannedReps }
            let tension = week.reduce(0.0) { $0 + $1.totalHeldSeconds }
            let effort = medianText(week.compactMap { $0.rpe?.rawValue })
            let fingers = medianText(week.compactMap { $0.fingerStrain?.rawValue })
            out.append("| \(isoDay(DayStamp(raw: raw))) | \(week.count) | \(climbDays) | \(done)/\(planned) | \(durationText(tension)) | \(effort) | \(fingers) |")
        }
        out.append("")
        return out
    }

    // MARK: - Consistency

    private static func consistency(_ sessions: [Session], target: Int,
                                    today: DayStamp) -> [String] {
        var out = ["## Consistency", ""]
        out.append("Target: \(max(1, target)) session\(max(1, target) == 1 ? "" : "s") a day. (Each session also carries the target that was in force when it was logged, so a change to the target never re-scores days already lived.)")
        out.append("")
        guard !sessions.isEmpty else {
            out += ["No sessions on record.", ""]
            return out
        }
        out.append("Days trained per week across the whole history — a day counts if ANY session landed on it, climbing days included. Newest week first. A week's denominator counts only its days inside the recorded history, so the first and the current week are usually partial — a day before the history began, or still in the future, is not scored as a miss.")
        out.append("")
        out.append("| Week of | Days trained | Sessions |")
        out.append("| --- | ---: | ---: |")

        var weeks: [Int: [Session]] = [:]
        for session in sessions { weeks[weekStart(session.day).raw, default: []].append(session) }
        // The first recorded day bounds every denominator from below; today bounds it
        // from above. Scoring a day outside [firstDay … today] as a miss is how a
        // 19-for-19 streak printed as "6 of 7" on its opening week (found by the
        // feature's own first real reader, 2026-08-28).
        let firstDay = sessions.map(\.day.raw).min() ?? today.raw
        for raw in weeks.keys.sorted(by: >) {
            let week = weeks[raw] ?? []
            let days = Set(week.map(\.day.raw)).count
            let span = min(raw + 6, today.raw) - max(raw, firstDay) + 1
            out.append("| \(isoDay(DayStamp(raw: raw))) | \(days) of \(max(days, span)) | \(week.count) |")
        }
        out.append("")
        return out
    }

    // MARK: - Questions

    private static func questions() -> [String] {
        [
            "## Questions worth asking",
            "",
            "1. Is one hand falling behind the other — in maxes, in held seconds, or in % max at the same prescription?",
            "2. Are the two effort axes diverging? Fingers climbing while overall effort stays flat is the early warning this schema exists to expose.",
            "3. How fast are the maxes actually moving per grip, and is the training load moving with them or ahead of them?",
            "4. What does the adherence pattern look like — which days and which weeks get missed, and does a missed day follow a hard one?",
            "",
        ]
    }

    // MARK: - Ordering (total, so the document is byte-stable)

    private static func newestFirst(_ a: Session, _ b: Session) -> Bool {
        if a.startedAt != b.startedAt { return a.startedAt > b.startedAt }
        if a.day != b.day { return a.day > b.day }
        return a.id.uuidString < b.id.uuidString
    }

    private static func oldestFirst(_ a: MaxEntry, _ b: MaxEntry) -> Bool {
        if a.recordedAt != b.recordedAt { return a.recordedAt < b.recordedAt }
        if a.day != b.day { return a.day < b.day }
        if a.maxKey != b.maxKey { return a.maxKey < b.maxKey }
        return a.kg < b.kg
    }

    private static func byGripThenHand(_ a: MaxEntry, _ b: MaxEntry) -> Bool {
        if a.gripKey != b.gripKey { return a.gripKey < b.gripKey }
        return sideRank(a.side) < sideRank(b.side)
    }

    private static func sideRank(_ side: Side) -> Int {
        switch side {
        case .both:  0
        case .left:  1
        case .right: 2
        }
    }

    // MARK: - English vocabulary (never localized — see the type's note)

    static let blank = "—"

    static func gripCode(_ grip: GripSpec) -> String {
        "\(grip.edgeMM)mm \(fingerCode(grip.fingers)) \(positionCode(grip.position))"
    }

    /// The same shapes `FingerSet.shortName` produces, spelled out here so a French
    /// device cannot export a different code for the same hand.
    static func fingerCode(_ fingers: FingerSet) -> String {
        if fingers.contains(.thumb) {
            let rest = fingers.subtracting(.thumb)
            return rest.isEmpty ? "T" : "\(fingerCode(rest))+T"
        }
        switch fingers {
        case .four:       return "4F"
        case .frontThree: return "F3"
        case .backThree:  return "B3"
        case .frontTwo:   return "F2"
        case .middleTwo:  return "M2"
        case .backTwo:    return "B2"
        case .index:      return "I"
        case .middle:     return "M"
        case .ring:       return "R"
        case .little:     return "L"
        default:          return fingers.token
        }
    }

    static func fingerEnglish(_ fingers: FingerSet) -> String {
        if fingers.contains(.thumb) {
            let rest = fingers.subtracting(.thumb)
            return rest.isEmpty ? "thumb only" : "\(fingerEnglish(rest)), thumb also on the hold"
        }
        switch fingers {
        case .four:       return "all four fingers"
        case .frontThree: return "index, middle and ring"
        case .backThree:  return "middle, ring and little"
        case .frontTwo:   return "index and middle"
        case .middleTwo:  return "middle and ring"
        case .backTwo:    return "ring and little"
        case .index:      return "index alone"
        case .middle:     return "middle alone"
        case .ring:       return "ring alone"
        case .little:     return "little alone"
        default:
            let names = ["index", "middle", "ring", "little"]
            let parts = zip(names, fingers.occupied).filter(\.1).map(\.0)
            return parts.isEmpty ? "no fingers recorded" : parts.joined(separator: " + ")
        }
    }

    /// Mirrors `GripPosition.shortName`'s English forms. An unknown raw from a newer
    /// build passes through uppercased, exactly as it does on screen.
    static func positionCode(_ position: GripPosition) -> String {
        switch position {
        case .halfCrimp: return "HC"
        case .openHand:  return "OH"
        case .fullCrimp: return "FC"
        case .drag:      return "DR"
        case .pinch:     return "PN"
        case .fingerCurl: return "CURL"
        default:         return position.rawValue.uppercased()
        }
    }

    static func positionEnglish(_ position: GripPosition) -> String {
        switch position {
        case .halfCrimp: return "half crimp"
        case .openHand:  return "open hand"
        case .fullCrimp: return "full crimp"
        case .drag:      return "drag"
        case .pinch:     return "pinch (the thumb is always on a pinch)"
        case .fingerCurl: return "finger curl (isometric, starting in half crimp)"
        default:         return "\(position.rawValue) — a position this build does not name"
        }
    }

    static func handCode(_ side: Side) -> String {
        switch side {
        case .left:  "L"
        case .right: "R"
        case .both:  "B"
        }
    }

    static func handWord(_ side: Side) -> String {
        switch side {
        case .left:  "left"
        case .right: "right"
        case .both:  "both hands"
        }
    }

    private static func sourceText(_ source: MaxSource) -> String {
        source == .measured ? "measured" : "typed"
    }

    private static func timingWord(_ timing: Timing) -> String {
        switch timing {
        case .gauge:     "gauge"
        case .timerOnly: "timer-only (wall clock)"
        case .logged:    "logged"
        }
    }

    // MARK: - Formatting (locale-free by construction)

    /// `String(format:)` with no locale argument is non-localized, so the separator is a
    /// dot on a French phone as well — which is the whole point of a fixed schema.
    static func kgText(_ kg: Double) -> String {
        guard kg.isFinite else { return blank }
        return String(format: "%.1f", kg)
    }

    static func secondsText(_ seconds: Double) -> String {
        guard seconds.isFinite else { return blank }
        return String(format: "%.1f", seconds)
    }

    static func percentText(_ kg: Double, maxKg: Double?) -> String {
        guard let maxKg, maxKg > 0, kg.isFinite, kg > 0 else { return blank }
        return "\(Int((kg / maxKg * 100).rounded()))%"
    }

    static func axisText(_ raw: Int?) -> String {
        guard let raw else { return blank }
        return "\(raw)/5"
    }

    static func medianText(_ values: [Int]) -> String {
        guard !values.isEmpty else { return blank }
        let sorted = values.sorted()
        let middle = sorted.count / 2
        if sorted.count.isMultiple(of: 2) {
            let mean = Double(sorted[middle - 1] + sorted[middle]) / 2
            return mean == mean.rounded() ? "\(Int(mean))" : String(format: "%.1f", mean)
        }
        return "\(sorted[middle])"
    }

    static func durationText(_ seconds: Double) -> String {
        let total = Int(seconds.rounded())
        guard total > 0 else { return "0s" }
        let hours = total / 3600
        let minutes = (total % 3600) / 60
        let secs = total % 60
        if hours > 0 { return "\(hours)h \(minutes)m" }
        if minutes > 0 { return "\(minutes)m \(secs)s" }
        return "\(secs)s"
    }

    /// YYYY-MM-DD, built out of `DayStamp`'s own fixed UTC calendar — no `DateFormatter`,
    /// no locale, no time zone anywhere in the answer.
    static func isoDay(_ day: DayStamp) -> String {
        let date = Date(timeIntervalSince1970: Double(day.raw) * 86_400)
        let c = DayStamp.utcCalendar.dateComponents([.year, .month, .day], from: date)
        return String(format: "%04ld-%02ld-%02ld", c.year ?? 0, c.month ?? 0, c.day ?? 0)
    }

    /// Monday-start week containing `day`. Day 0 is a Thursday, hence the +3.
    static func weekStart(_ day: DayStamp) -> DayStamp {
        let offset = ((day.raw + 3) % 7 + 7) % 7
        return DayStamp(raw: day.raw - offset)
    }

    /// A note pasted into a table or a header cannot carry newlines or pipes.
    private static func singleLine(_ text: String) -> String {
        text.replacingOccurrences(of: "\n", with: " ")
            .replacingOccurrences(of: "|", with: "/")
            .trimmingCharacters(in: .whitespacesAndNewlines)
    }
}

// MARK: - Compact, self-contained CSV
extension AnalysisExport {
    enum CSVScope: String, CaseIterable, Sendable { case recent, all, workout }

    enum CSVDetail: String, CaseIterable, Sendable { case summary, pulls }

    struct CSVDocument: Sendable {
        let text: String
        let filename: String
        let byteCount: Int
        let sessionCount: Int
        let pullCount: Int
        let maxCount: Int
        var isEmpty: Bool { sessionCount == 0 && maxCount == 0 }
    }

    /// Snapshot values can be formatted off the main actor. A single-workout input
    /// contains just that session, but keeps all maxes for historical lookup only.
    static func csv(_ input: Input, scope: CSVScope = .recent, detail: CSVDetail = .summary) -> CSVDocument {
        let cutoff = detailCutoff(today: input.today)
        let sessions = input.sessions.filter { scope != .recent || $0.day >= cutoff }
            .sorted(by: newestFirst)
        let maxes = scope == .workout ? [] : input.maxes.filter {
            scope != .recent || $0.day >= cutoff
        }.sorted(by: oldestFirst)
        let tables = tablesAtSessionTime(sessions, maxes: input.maxes)
        let headers = "record,workout,date,started_utc,routine,kind,timing,minutes,effort_1_5,finger_strain_1_5,daily_target,completed,planned,set,pull,edge_mm,fingers,position,hand,planned_s,held_s,peak_kg,avg_kg,target_low_kg,target_high_kg,max_at_start_kg,outcome,source,notes,ended_utc,time_source,max_reference,started_elapsed_s,ended_elapsed_s,gap_before_s,planned_rest_s,planned_lead_in_s,recorded".components(separatedBy: ",")
        var rows = [headers.joined(separator: ",")]
        func row(_ fields: [String: String]) {
            rows.append(headers.map { csvCell(fields[$0] ?? "") }.joined(separator: ","))
        }
        let span = scope == .recent ? "since \(isoDay(cutoff))" : scope.rawValue
        row(["record": "guide", "date": isoDay(input.generatedOn),
             "notes": "Get a Grip CSV v2. workout is a permanent UUID; (workout,pull) identifies a recorded pull. record=workout contains totals; set or pull rows describe the same work: do not add them to workout totals. Summary groups recorded pulls by set, grip, hand and identical prescription. recorded counts outcomes, including skips; completed counts completed outcomes only. Summary planned_s and held_s are sums, peak_kg is the maximum, avg_kg is weighted by credited held_s; outcome lists counts. Workbook planned is the full planned pull count, including unattempted pulls. Dates are local training days; UTC timestamps are absolute. Blank is unavailable, never zero. ended_utc is the stored finish: runner_completion for newly timed sessions; legacy_save_or_end may include time on an old save screen. Manual logs have no known end instant. started_elapsed_s and ended_elapsed_s are host-monotonic observations from session start, including pauses and waiting; ended marks outcome recording, not necessarily physical release. Old timing is not_recorded; not_started means no engagement before an outcome. Summary offsets span the group only when all performed pulls have timing. gap_before_s includes pauses and waiting, not just rest. planned_rest_s and planned_lead_in_s are saved prescriptions (sums on set rows); rest includes set breaks. kg is force in kilograms; held_s is credited time above threshold. timing is inferred: gauge if any pull has positive force, timerOnly otherwise; logged means manual or no surviving detail. Skips and timer-only pulls have no force values. Target bounds are frozen prescriptions. max_at_start_kg uses only records at or before start; max_reference distinguishes hand_specific, both_hands, both_hands_fallback and no_recorded_max_at_start. Missing does not prove a grip was never benchmarked. Never add hand maxes. Effort and strain are 1-5, not Borg CR10. Fingers: I=index M=middle R=ring L=little T=thumb. fingerCurl starts in half crimp. Formula-like text is apostrophe-protected. No raw force trace or gauge model is stored. Scope: \(span); detail: \(detail.rawValue)."])
        let instant = ISO8601DateFormatter()
        instant.formatOptions = [.withInternetDateTime]
        var pullCount = 0
        for session in sessions {
            let key = session.id.uuidString.lowercased()
            row(["record": "workout", "workout": key, "date": isoDay(session.day),
                 "started_utc": instant.string(from: session.startedAt),
                 "ended_utc": session.timing == .logged ? "" : session.finishedAt.map { instant.string(from: $0) } ?? "",
                 "time_source": session.timing == .logged ? "manual" : (session.reps.contains { $0.endedElapsedSeconds != nil } ? "runner_completion" : "legacy_save_or_end"),
                 "routine": session.routineName, "kind": session.kind.rawValue,
                 "timing": session.timing.rawValue,
                 "minutes": session.minutes.map(String.init) ?? "",
                 "effort_1_5": session.rpe.map { String($0.rawValue) } ?? "",
                 "finger_strain_1_5": session.fingerStrain.map { String($0.rawValue) } ?? "",
                 "daily_target": session.sessionsPerDayTarget.map(String.init) ?? "",
                 "completed": String(session.completedReps), "planned": String(session.plannedReps),
                 "held_s": session.timing != .logged ? csvNumber(session.totalHeldSeconds) : "",
                 "peak_kg": session.timing == .gauge ? csvNumber(session.peakKg) : "",
                 "avg_kg": session.timing == .gauge ? csvNumber(session.avgKg) : "",
                 "notes": session.notes])
            let table = tables[session.id] ?? MaxTable()
            let slots = session.plan.map { PlanMath.sequence(for: $0) } ?? []
            var previousEnd: Double?
            var pullRows: [[String: String]] = []
            for (pull, rep) in session.reps.enumerated() {
                let measured = session.timing == .gauge && rep.outcome != .skipped
                var fields = csvGrip(rep.grip, side: rep.side)
                fields.merge(["record": "pull", "workout": key, "date": isoDay(session.day),
                    "routine": session.routineName, "timing": session.timing.rawValue,
                    "set": String(rep.setIndex + 1), "pull": String(pull + 1),
                    "planned_s": String(rep.targetSeconds), "held_s": csvNumber(rep.heldSeconds),
                    "peak_kg": measured ? csvNumber(rep.peakKg) : "",
                    "avg_kg": measured ? csvNumber(rep.avgKg) : "",
                    "target_low_kg": rep.targetBand.map { csvNumber($0.lowerBound) } ?? "",
                    "target_high_kg": rep.targetBand.map { csvNumber($0.upperBound) } ?? "",
                    "max_at_start_kg": table.max(grip: rep.grip.key, side: rep.side).map(csvNumber) ?? "",
                    "outcome": rep.outcome.rawValue]) { _, new in new }
                fields["max_reference"] = table.exact(grip: rep.grip.key, side: rep.side) != nil
                    ? (rep.side == .both ? "both_hands" : "hand_specific")
                    : (table.max(grip: rep.grip.key, side: rep.side) != nil ? "both_hands_fallback" : "no_recorded_max_at_start")
                let end = validElapsed(rep.endedElapsedSeconds)
                let start = validElapsed(rep.startedElapsedSeconds).flatMap { value in
                    end.map { value <= $0 ? value : nil } ?? nil
                }
                fields["started_elapsed_s"] = start.map(csvNumber) ?? ""
                fields["ended_elapsed_s"] = end.map(csvNumber) ?? ""
                fields["time_source"] = end == nil ? "not_recorded" : (start == nil ? "not_started" : "host_monotonic")
                if let start, let previousEnd, start >= previousEnd {
                    fields["gap_before_s"] = csvNumber(start - previousEnd)
                }
                if start != nil { previousEnd = end }
                else if end == nil { previousEnd = nil }
                if let slot = slots.first(where: { $0.setIndex == rep.setIndex && $0.repIndex == rep.repIndex && $0.side == rep.side && $0.grip == rep.grip }) {
                    fields["planned_rest_s"] = String(slot.restAfter)
                    fields["planned_lead_in_s"] = String(slot.leadInBefore)
                }
                pullRows.append(fields)
                pullCount += 1
            }
            if detail == .pulls { pullRows.forEach(row) }
            else { csvSummaries(pullRows, reps: session.reps, measured: session.timing == .gauge).forEach(row) }
        }
        for entry in maxes {
            var fields = csvGrip(entry.grip, side: entry.side)
            fields.merge(["record": "max", "date": isoDay(entry.day),
                          "started_utc": instant.string(from: entry.recordedAt),
                          "peak_kg": csvNumber(entry.kg), "source": entry.source.rawValue]) { _, new in new }
            row(fields)
        }
        let suffix = scope == .workout ? "workout-\(sessions.first.map { isoDay($0.day) } ?? isoDay(input.today))" : "training-\(scope.rawValue)"
        let text = rows.joined(separator: "\r\n") + "\r\n"
        return CSVDocument(text: text,
                           filename: "get-a-grip-\(suffix)-\(detail.rawValue).csv", byteCount: text.utf8.count, sessionCount: sessions.count,
                           pullCount: pullCount, maxCount: maxes.count)
    }

    private static func validElapsed(_ value: Double?) -> Double? {
        value.flatMap { $0.isFinite && $0 >= 0 ? $0 : nil }
    }

    /// Keep different prescriptions separate; insertion order follows the saved pulls.
    private static func csvSummaries(_ rows: [[String: String]], reps: [RepSummary], measured: Bool) -> [[String: String]] {
        var keys: [String] = []
        var groups: [String: [Int]] = [:]
        for index in rows.indices {
            let rep = reps[index]
            // Group the original values, before display rounding of target bounds.
            let key = [String(rep.setIndex), rep.grip.key, rep.side.rawValue,
                       String(rep.targetSeconds), rep.targetBand.map { String($0.lowerBound) } ?? "",
                       rep.targetBand.map { String($0.upperBound) } ?? ""].joined(separator: "|")
            if groups[key] == nil { keys.append(key) }
            groups[key, default: []].append(index)
        }
        return keys.map { key in
            let indices = groups[key]!
            let values = indices.map { reps[$0] }
            var result = rows[indices[0]]
            result["record"] = "set"
            result["pull"] = ""
            result["gap_before_s"] = ""
            result["recorded"] = String(values.count)
            result["completed"] = String(values.filter { $0.outcome == .completed }.count)
            result["planned_s"] = String(values.reduce(0) { $0 + $1.targetSeconds })
            result["held_s"] = csvNumber(values.reduce(0) { $0 + $1.heldSeconds })
            let forces = measured ? values.filter { $0.outcome != .skipped } : []
            result["peak_kg"] = forces.map(\.peakKg).max().map(csvNumber) ?? ""
            let weighted = forces.filter { $0.heldSeconds > 0 && $0.heldSeconds.isFinite && $0.avgKg.isFinite }
            let seconds = weighted.reduce(0) { $0 + $1.heldSeconds }
            result["avg_kg"] = seconds > 0 ? csvNumber(weighted.reduce(0) { $0 + $1.avgKg * $1.heldSeconds } / seconds) : ""
            let outcomes = Dictionary(grouping: values, by: { $0.outcome.rawValue })
            result["outcome"] = outcomes.keys.sorted().map { "\($0):\(outcomes[$0]!.count)" }.joined(separator: "|")
            for field in ["planned_rest_s", "planned_lead_in_s"] {
                let numbers = indices.compactMap { rows[$0][field].flatMap(Int.init) }
                result[field] = numbers.count == indices.count ? String(numbers.reduce(0, +)) : ""
            }
            let performed = indices.filter { rows[$0]["time_source"] != "not_started" }
            let timed = !performed.isEmpty && performed.allSatisfy { rows[$0]["time_source"] == "host_monotonic" }
            result["started_elapsed_s"] = timed ? rows[performed.first!]["started_elapsed_s"] : ""
            result["ended_elapsed_s"] = timed ? rows[performed.last!]["ended_elapsed_s"] : ""
            result["time_source"] = timed ? "host_monotonic" : (performed.isEmpty ? "not_started" : "not_recorded")
            return result
        }
    }

    private static func csvGrip(_ grip: GripSpec, side: Side) -> [String: String] {
        ["edge_mm": String(grip.edgeMM), "fingers": grip.fingers.token,
         "position": grip.position.rawValue, "hand": side.rawValue]
    }

    private static func csvNumber(_ value: Double) -> String {
        value.isFinite ? String(format: "%.1f", value) : ""
    }

    /// RFC 4180 quoting, with formula protection for user-authored names and notes.
    /// Spaces before a formula prefix are ignored by some spreadsheet importers.
    private static func csvCell(_ value: String) -> String {
        var text = value
        if let first = text.trimmingCharacters(in: .whitespacesAndNewlines).first,
           "=+-@".contains(first) { text = "'" + text }
        if text.first == "\t" || text.first == "\r" { text = "'" + text }
        // Scalars matter: Swift treats CRLF as one Character. Match both isolated
        // line endings and CRLF without relying on grapheme comparisons.
        if text.unicodeScalars.contains(where: { [34, 44, 13, 10].contains($0.value) }) {
            return "\"" + text.replacingOccurrences(of: "\"", with: "\"\"") + "\""
        }
        return text
    }
}
