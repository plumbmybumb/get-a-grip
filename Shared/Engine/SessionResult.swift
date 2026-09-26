// SPDX-License-Identifier: MPL-2.0
// Original contributions Copyright 2026 Nuri Bruner.

import Foundation

// What a finished session leaves behind. `WorkoutLog`'s blob columns are typed on these,
// so a log written by any build must stay readable by every build after it.

/// How a rep ended, including historical outcomes retained for readable old exports.
/// Unrecognized stored outcomes decode conservatively as aborted.
enum RepOutcome: String, Codable, Hashable, Sendable {
    case completed
    /// Historical outcome only; current builds never end a rep on a dropout timeout.
    case earlyRelease
    /// The user skipped it, deliberately.
    case skipped
    /// The session was explicitly aborted, or a stored outcome is unreadable.
    /// A dropout or background transition alone never auto-aborts a pull.
    case aborted
}

/// One pull, as it actually happened.
struct RepSummary: Hashable, Sendable, Codable {
    var setIndex: Int = 0
    var repIndex: Int = 0
    var side: Side = .left
    /// EMBEDDED, not referenced: history stays meaningful forever without resolving a
    /// routine that may have been edited beyond recognition or deleted outright.
    var grip: GripSpec = GripSpec()
    /// What was AUTHORED — kept beside `heldSeconds` so a short rep reads as a short
    /// rep rather than as a rewritten plan.
    var targetSeconds: Int = 10
    /// What was ACCRUED over threshold, from device timestamps only — never wall clock,
    /// where BLE jitter or a UI hitch would invent hang time. Double: it is sub-second.
    var heldSeconds: Double = 0
    var peakKg: Double = 0
    /// Mean while engaged; segments below threshold are excluded, so a dropout does not
    /// drag the average toward zero and make a good rep look weak.
    var avgKg: Double = 0
    /// **What this rep was ASKED to pull, in kilograms, for THIS hand** — nil when the
    /// routine set no target or the grip had no max on file for that hand. Per REP
    /// because only there is the answer single-valued (the hands have different maxes).
    /// The plan keeps what was AUTHORED; this keeps what was DEMANDED, which must survive
    /// a new max recorded next month.
    var targetLoKg: Double? = nil
    var targetHiKg: Double? = nil
    var outcome: RepOutcome = .completed
    /// Host-monotonic offsets from session start, not force-clock credit. End is when
    /// the outcome was recorded (including a skip); a never-started pull has no start.
    /// Nil on historical blobs: never reconstruct actual timing from the prescription.
    var startedElapsedSeconds: Double? = nil
    var endedElapsedSeconds: Double? = nil

    var targetBand: ClosedRange<Double>? { SetPlan.band(lo: targetLoKg, hi: targetHiKg) }

    /// FROZEN — these keys live in write-once log blobs. ADDITIVE ONLY: later keys such
    /// as `targetLoKg`/`targetHiKg` decode as nil on older reps, which is true.
    enum CodingKeys: String, CodingKey {
        case setIndex, repIndex, side, grip, targetSeconds, heldSeconds, peakKg, avgKg, outcome
        case targetLoKg, targetHiKg, startedElapsedSeconds, endedElapsedSeconds
    }
}

// In an extension so the memberwise initializer survives for the runner to use.
extension RepSummary {
    init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        self.setIndex = Swift.max(0, c.value(.setIndex, or: 0))
        self.repIndex = Swift.max(0, c.value(.repIndex, or: 0))
        self.side = c.value(.side, or: .left)
        self.grip = c.value(.grip, or: GripSpec())
        self.targetSeconds = Swift.max(0, c.value(.targetSeconds, or: 10))
        // Negatives are not "a very short rep", they are a corrupt number that would
        // subtract from a session total.
        self.heldSeconds = Swift.max(0, c.value(.heldSeconds, or: 0))
        self.peakKg = Swift.max(0, c.value(.peakKg, or: 0))
        self.avgKg = Swift.max(0, c.value(.avgKg, or: 0))
        // Absent on older reps: nil means "no target was recorded", which is true.
        self.targetLoKg = c.optional(.targetLoKg)
        self.targetHiKg = c.optional(.targetHiKg)
        self.outcome = c.value(.outcome, or: .aborted)
        self.startedElapsedSeconds = c.optional(.startedElapsedSeconds)
        self.endedElapsedSeconds = c.optional(.endedElapsedSeconds)
    }
}

/// Where a max came from. `measured` means the app watched it happen; `manual` means
/// the user typed it — and the difference matters when a percentage is computed off it.
enum MaxSource: String, Codable, Hashable, Sendable {
    case manual, measured
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
enum SessionKind: String, Codable, Hashable, Sendable, CaseIterable {
    /// The routine — a hangboard/no-hang session the runner drove.
    case hang
    /// Mileage: laps, circuits, an easy evening. Real load, sub-maximal.
    case climbVolume
    /// Hard bouldering or projecting — a maximal finger stimulus.
    case climbLimit
    /// Weighted or max hangs done away from the gauge.
    case hangManual
    /// A testing day: a gauge-measured max or a critical force test was recorded. Logged
    /// automatically the first time either lands on a day, never by typing a number,
    /// because typing is not training. One per day; see `TemplateStore.stampBenchmarkDay`.
    case benchmark

    var isClimb: Bool { self == .climbVolume || self == .climbLimit }

    /// Counts toward the day's hang tally — the runner's own sessions and hangs logged
    /// by hand.
    var countsAsHang: Bool { self == .hang || self == .hangManual }

    /// The kinds the log sheet may write. `.hang` is the runner's to write and
    /// `.benchmark` is `recordMax`'s; neither may be created by hand.
    var isLoggedByHand: Bool { isClimb || self == .hangManual }

    /// SETTLES THE DAY: no reminder fires after it. Climbs because the training happened
    /// elsewhere; a benchmark because maximal testing IS a maximal finger stimulus.
    var settlesDay: Bool { isClimb || self == .benchmark }

    /// Title case, for a row that names it.
    var name: String {
        switch self {
        case .hang:        String(localized: "Hangboard")
        case .climbVolume: String(localized: "Volume climbing")
        case .climbLimit:  String(localized: "Limit climbing")
        case .hangManual:  String(localized: "Weighted hangs")
        case .benchmark:   String(localized: "Benchmark")
        }
    }

    /// The word alone, for a chip where "climbing" is already the context.
    var shortName: String {
        switch self {
        case .hang:        String(localized: "Hangboard")
        case .climbVolume: String(localized: "Volume")
        case .climbLimit:  String(localized: "Limit")
        case .hangManual:  String(localized: "Hangs")
        case .benchmark:   String(localized: "Benchmark")
        }
    }

    /// What each style is, in a climber's words, under the picker: "volume" and "limit"
    /// are jargon, and a mis-picked one mis-describes the week.
    var explainer: String {
        switch self {
        case .hang:        String(localized: "A session on the board.")
        case .climbVolume: String(localized: "Laps, circuits or an easy session, well below your limit.")
        case .climbLimit:  String(localized: "Hard bouldering or projecting, at your limit.")
        case .hangManual:  String(localized: "Weighted or max hangs done without the gauge.")
        case .benchmark:   String(localized: "A max or critical force test on the gauge.")
        }
    }

    /// An unknown raw from a newer build reads as `.hang`, as every row before this column
    /// did. CONSERVATIVE: a misread future kind under-counts the day and asks for more
    /// training, rather than silently excusing a day nobody trained.
    init(fallback raw: String) {
        self = SessionKind(rawValue: raw) ?? .hang
    }
}

/// How hard it felt, which is the one thing no force gauge can read.
enum RPE: Int, Codable, Hashable, Sendable, CaseIterable {
    case easy = 1, comfortable, solid, hard, maximal

    var name: String {
        switch self {
        case .easy:        String(localized: "Easy")
        case .comfortable: String(localized: "Comfortable")
        case .solid:       String(localized: "Solid")
        case .hard:        String(localized: "Hard")
        case .maximal:     String(localized: "All I had")
        }
    }
}

/// The LOCAL axis — how much the session asked of the fingers specifically, which the
/// climbing session-RPE literature found to be the stronger signal for training work.
/// Deliberately not called tendon load: a post-session slider cannot observe the share of
/// pulley versus muscle-belly load, and the clinical read on tendon load belongs later.
enum FingerStrain: Int, Codable, Hashable, Sendable, CaseIterable {
    case nothing = 1, light, worked, taxed, wrecked

    var name: String {
        switch self {
        case .nothing: String(localized: "Nothing")
        case .light: String(localized: "Light")
        case .worked: String(localized: "Worked")
        case .taxed: String(localized: "Taxed")
        case .wrecked: String(localized: "Wrecked")
        }
    }
}
