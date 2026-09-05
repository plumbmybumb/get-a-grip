// SPDX-License-Identifier: MPL-2.0
// Original contributions Copyright 2026 Nuri Bruner.

import Foundation

// `oracle export scenarios|generate|verify <fixtures-dir>` — see main.swift.
//
//   scenarios  writes export/<name>.json — the INPUTS, built in Swift from the same
//              values the 22 XCTest cases build, so the shared fixture and the iOS
//              suite describe one history rather than two.
//   generate   reads every export/<name>.json and writes export/<name>.md from the REAL
//              `AnalysisExport.document`. That it reads the JSON back is the point: the
//              document is produced from exactly the bytes Kotlin will read.
//   verify     regenerates and diffs against the .md on disk.
//
// The wire shape is `AnalysisExport.Input` flattened: instants as ISO-8601 UTC strings,
// days as epoch-day ints, enum raws as strings, reps as `RepSummary`'s own Codable
// objects. `ExportInputDTO` below and `AnalysisExport.Input.fromJson` in Kotlin are the
// two halves of that contract.

func exportCommand(_ args: [String]) throws {
    guard args.count >= 2 else {
        FileHandle.standardError.write(
            Data("usage: oracle export scenarios|generate|verify <fixtures-dir>\n".utf8))
        exit(2)
    }
    let directory = URL(fileURLWithPath: args[1], isDirectory: true)
        .appendingPathComponent("export", isDirectory: true)
    switch args[0] {
    case "scenarios": try exportScenarios(directory)
    case "generate":  try exportRun(directory, writing: true)
    case "verify":    try exportRun(directory, writing: false)
    default:
        FileHandle.standardError.write(Data("oracle export: unknown mode \(args[0])\n".utf8))
        exit(2)
    }
}

// MARK: - The wire shape

private let isoInstant: ISO8601DateFormatter = {
    let f = ISO8601DateFormatter()
    f.formatOptions = [.withInternetDateTime]
    f.timeZone = TimeZone(secondsFromGMT: 0)
    return f
}()

private struct ExportSessionDTO: Codable {
    var id: String
    var day: Int
    var startedAt: String
    var routineName: String
    var kind: String
    var minutes: Int?
    var rpe: Int?
    var fingerStrain: Int?
    var peakKg: Double
    var avgKg: Double
    var totalHeldSeconds: Double
    var plannedReps: Int
    var completedReps: Int
    var timing: String
    var reps: [RepSummary]
    var notes: String
    var sessionsPerDayTarget: Int? = nil
    var finishedAt: String? = nil
    var plan: SessionPlan? = nil
}

private struct ExportMaxDTO: Codable {
    var grip: GripSpec
    var side: String
    var kg: Double
    var day: Int
    var recordedAt: String
    var source: String
}

private struct ExportInputDTO: Codable {
    var sessions: [ExportSessionDTO]
    var maxes: [ExportMaxDTO]
    var today: Int
    var generatedOn: Int
    var sessionsPerDayTarget: Int
}

private func dto(_ input: AnalysisExport.Input) -> ExportInputDTO {
    ExportInputDTO(
        sessions: input.sessions.map { session in
            ExportSessionDTO(
                id: session.id.uuidString,
                day: session.day.raw,
                startedAt: isoInstant.string(from: session.startedAt),
                routineName: session.routineName,
                kind: session.kind.rawValue,
                minutes: session.minutes,
                rpe: session.rpe?.rawValue,
                fingerStrain: session.fingerStrain?.rawValue,
                peakKg: session.peakKg,
                avgKg: session.avgKg,
                totalHeldSeconds: session.totalHeldSeconds,
                plannedReps: session.plannedReps,
                completedReps: session.completedReps,
                timing: session.timing.rawValue,
                reps: session.reps,
                notes: session.notes, sessionsPerDayTarget: session.sessionsPerDayTarget,
                finishedAt: session.finishedAt.map { isoInstant.string(from: $0) }, plan: session.plan)
        },
        maxes: input.maxes.map { entry in
            ExportMaxDTO(
                grip: entry.grip,
                side: entry.side.rawValue,
                kg: entry.kg,
                day: entry.day.raw,
                recordedAt: isoInstant.string(from: entry.recordedAt),
                source: entry.source.rawValue)
        },
        today: input.today.raw,
        generatedOn: input.generatedOn.raw,
        sessionsPerDayTarget: input.sessionsPerDayTarget)
}

private func input(_ dto: ExportInputDTO) -> AnalysisExport.Input {
    AnalysisExport.Input(
        sessions: dto.sessions.map { row in
            AnalysisExport.Session(
                id: UUID(uuidString: row.id) ?? UUID(),
                day: DayStamp(raw: row.day),
                startedAt: isoInstant.date(from: row.startedAt) ?? Date(timeIntervalSince1970: 0),
                routineName: row.routineName,
                kind: SessionKind(rawValue: row.kind) ?? .hang,
                minutes: row.minutes,
                rpe: row.rpe.flatMap { RPE(rawValue: $0) },
                fingerStrain: row.fingerStrain.flatMap { FingerStrain(rawValue: $0) },
                peakKg: row.peakKg,
                avgKg: row.avgKg,
                totalHeldSeconds: row.totalHeldSeconds,
                plannedReps: row.plannedReps,
                completedReps: row.completedReps,
                timing: AnalysisExport.Timing(rawValue: row.timing) ?? .gauge,
                reps: row.reps,
                notes: row.notes, sessionsPerDayTarget: row.sessionsPerDayTarget,
                finishedAt: row.finishedAt.flatMap { isoInstant.date(from: $0) }, plan: row.plan)
        },
        maxes: dto.maxes.map { row in
            AnalysisExport.MaxEntry(
                grip: row.grip,
                side: Side(rawValue: row.side) ?? .both,
                kg: row.kg,
                day: DayStamp(raw: row.day),
                recordedAt: isoInstant.date(from: row.recordedAt) ?? Date(timeIntervalSince1970: 0),
                source: MaxSource(rawValue: row.source) ?? .manual)
        },
        today: DayStamp(raw: dto.today),
        generatedOn: DayStamp(raw: dto.generatedOn),
        sessionsPerDayTarget: dto.sessionsPerDayTarget)
}

// MARK: - Building the scenarios

private let exportToday = DayStamp(year: 2026, month: 8, day: 28)

private func at(_ day: DayStamp, hour: Int = 9) -> Date {
    Date(timeIntervalSince1970: Double(day.raw) * 86_400 + Double(hour) * 3600)
}

/// Deterministic ids: the id is the session sort's last tiebreak, so a random one would
/// reshuffle the document between two regenerations of the same history.
private func stableID(_ n: Int) -> UUID {
    UUID(uuidString: String(format: "00000000-0000-0000-0000-%012d", n))!
}

private func grip(_ edge: Int = 20, _ fingers: FingerSet = .four,
                  _ position: GripPosition = .halfCrimp) -> GripSpec {
    GripSpec(edgeMM: edge, fingers: fingers, position: position)
}

private func rep(_ index: Int, side: Side, grip: GripSpec, peak: Double,
                 held: Double = 10, planned: Int = 10,
                 outcome: RepOutcome = .completed,
                 targetLo: Double? = nil, targetHi: Double? = nil) -> RepSummary {
    var summary = RepSummary()
    summary.setIndex = 0
    summary.repIndex = index
    summary.side = side
    summary.grip = grip
    summary.targetSeconds = planned
    summary.heldSeconds = held
    summary.peakKg = peak
    summary.avgKg = peak * 0.9
    summary.targetLoKg = targetLo
    summary.targetHiKg = targetHi
    summary.outcome = outcome
    return summary
}

private var sessionCounter = 0

private func session(_ day: DayStamp,
                     name: String = "Daily no-hangs",
                     kind: SessionKind = .hang,
                     timing: AnalysisExport.Timing = .gauge,
                     reps: [RepSummary] = [],
                     held: Double? = nil,
                     planned: Int? = nil,
                     completed: Int? = nil,
                     minutes: Int? = 20,
                     rpe: RPE? = nil,
                     strain: FingerStrain? = nil,
                     notes: String = "") -> AnalysisExport.Session {
    sessionCounter += 1
    return AnalysisExport.Session(
        id: stableID(sessionCounter),
        day: day,
        startedAt: at(day),
        routineName: name,
        kind: kind,
        minutes: minutes,
        rpe: rpe,
        fingerStrain: strain,
        peakKg: reps.map(\.peakKg).max() ?? 0,
        avgKg: reps.map(\.avgKg).max() ?? 0,
        totalHeldSeconds: held ?? reps.reduce(0) { $0 + $1.heldSeconds },
        plannedReps: planned ?? reps.count,
        completedReps: completed ?? reps.filter { $0.outcome == .completed }.count,
        timing: timing,
        reps: reps,
        notes: notes)
}

private func maxEntry(_ kg: Double, grip: GripSpec, side: Side, on day: DayStamp,
                      source: MaxSource = .measured) -> AnalysisExport.MaxEntry {
    AnalysisExport.MaxEntry(grip: grip, side: side, kg: kg, day: day,
                            recordedAt: at(day, hour: 8), source: source)
}

private func makeInput(sessions: [AnalysisExport.Session] = [],
                       maxes: [AnalysisExport.MaxEntry] = []) -> AnalysisExport.Input {
    AnalysisExport.Input(sessions: sessions, maxes: maxes, today: exportToday,
                         generatedOn: exportToday, sessionsPerDayTarget: 2)
}

/// One deterministic pseudo-random walk, so the rich scenario is varied and repeatable.
private struct Roll {
    private var state: UInt64 = 0x5EED_1234_ABCD_0001
    mutating func next(_ bound: Int) -> Int {
        state = state &* 6_364_136_223_846_793_005 &+ 1_442_695_040_888_963_407
        return Int((state >> 33) % UInt64(bound))
    }
}

/// Eight weeks of detail plus four months of rollup: every branch of the document at
/// once — climbs, benchmarks, hand-logged hangs, timer-only and logged sessions, skipped
/// and aborted pulls, target bands, notes with a pipe and a newline in them, both hands'
/// maxes moving over time, and a hang session whose rep blob did not survive.
private func richHistory() -> AnalysisExport.Input {
    var roll = Roll()
    let g4 = grip(20, .four, .halfCrimp)
    let g3 = grip(20, .frontThree, .halfCrimp)
    let g2 = grip(20, .frontTwo, .openHand)
    let pinch = grip(35, FingerSet([.index, .middle, .thumb]), .pinch)
    let sloper = grip(45, .four, GripPosition("sloper"))
    let grips = [g4, g3, g2, pinch, sloper]

    var sessions: [AnalysisExport.Session] = []
    // 120 days back to today, training most days, twice on some.
    for back in stride(from: 120, through: 0, by: -1) {
        let day = exportToday - back
        let dice = roll.next(10)
        if dice == 0 { continue }                     // a rest day
        if dice == 1 {
            sessions.append(session(day, name: "Gym", kind: roll.next(2) == 0 ? .climbVolume : .climbLimit,
                                    timing: .logged, held: 0, planned: 0, completed: 0,
                                    minutes: 60 + roll.next(60),
                                    rpe: RPE(rawValue: roll.next(5) + 1),
                                    strain: FingerStrain(rawValue: roll.next(5) + 1)))
            continue
        }
        if dice == 2 {
            sessions.append(session(day, name: "Weighted hangs", kind: .hangManual,
                                    timing: .logged, held: 0, planned: 0, completed: 0,
                                    minutes: 25, rpe: RPE(rawValue: roll.next(5) + 1)))
            continue
        }
        if dice == 3 && back > 60 {
            sessions.append(session(day, name: "Benchmark", kind: .benchmark,
                                    timing: .logged, held: 0, planned: 0, completed: 0,
                                    minutes: 40))
            continue
        }
        let g = grips[roll.next(grips.count)]
        var reps: [RepSummary] = []
        let count = 4 + roll.next(4)
        for index in 0..<count {
            let side: Side = index % 2 == 0 ? .left : .right
            let outcome: RepOutcome
            switch roll.next(12) {
            case 0: outcome = .skipped
            case 1: outcome = .earlyRelease
            case 2: outcome = .aborted
            default: outcome = .completed
            }
            let peak = outcome == .skipped ? 0 : 8 + Double(roll.next(140)) / 10
            let band = roll.next(3) == 0
            reps.append(rep(index, side: side, grip: g, peak: peak,
                            held: outcome == .skipped ? 0 : Double(6 + roll.next(60)) / 10,
                            planned: 10,
                            outcome: outcome,
                            targetLo: band ? 9.5 : nil,
                            targetHi: band ? 13 : nil))
        }
        let timing: AnalysisExport.Timing = roll.next(8) == 0 ? .timerOnly : .gauge
        let notes = roll.next(6) == 0
            ? "Felt strong on the \(AnalysisExport.gripCode(g)) | second set\nkept the shoulders down."
            : ""
        sessions.append(session(day, name: back > 56 ? "Old protocol" : "Daily no-hangs",
                                timing: timing, reps: reps,
                                planned: count, minutes: 18 + roll.next(10),
                                rpe: roll.next(3) == 0 ? nil : RPE(rawValue: roll.next(5) + 1),
                                strain: roll.next(3) == 0 ? nil : FingerStrain(rawValue: roll.next(5) + 1),
                                notes: notes))
        if roll.next(4) == 0 {
            sessions.append(session(day, name: "Daily no-hangs", timing: .gauge,
                                    reps: reps, planned: count, minutes: 17))
        }
    }
    // One hang session whose rep blob did not survive — the header figures are all there is.
    sessions.append(session(exportToday - 12, name: "Daily no-hangs", reps: [],
                            held: 84, planned: 12, completed: 11, minutes: 21,
                            rpe: .solid, strain: .worked))

    var maxes: [AnalysisExport.MaxEntry] = []
    for (index, g) in grips.enumerated() {
        let base = 18.0 + Double(index) * 4
        maxes.append(maxEntry(base, grip: g, side: .both, on: exportToday - 150))
        maxes.append(maxEntry(base + 2, grip: g, side: .left, on: exportToday - 90,
                              source: index % 2 == 0 ? .measured : .manual))
        maxes.append(maxEntry(base + 1.4, grip: g, side: .right, on: exportToday - 90))
        maxes.append(maxEntry(base + 3.6, grip: g, side: .left, on: exportToday - 20))
    }

    return AnalysisExport.Input(sessions: sessions, maxes: maxes, today: exportToday,
                                generatedOn: exportToday, sessionsPerDayTarget: 2)
}

/// Translated one for one from the inputs the 22 `AnalysisExportTests` cases build, plus
/// the rich history above. The four notation/formatting tests take no `Input` and so have
/// no scenario — they are pure functions, pinned by the translated unit tests on both
/// sides.
private func scenarios() -> [(name: String, input: AnalysisExport.Input)] {
    sessionCounter = 0
    var out: [(String, AnalysisExport.Input)] = []

    out.append(("legend-and-schema", makeInput(
        sessions: [session(exportToday, reps: [rep(0, side: .left, grip: grip(), peak: 12)])],
        maxes: [maxEntry(40, grip: grip(), side: .both, on: exportToday - 10)])))

    out.append(("no-max-blank-intensity", makeInput(
        sessions: [session(exportToday, reps: [rep(0, side: .left, grip: grip(), peak: 18)])])))

    let g = grip()
    out.append(("hand-specific-max-wins", makeInput(
        sessions: [session(exportToday, reps: [
            rep(0, side: .left, grip: g, peak: 20),
            rep(1, side: .right, grip: g, peak: 18),
        ])],
        maxes: [maxEntry(40, grip: g, side: .both, on: exportToday - 20),
                maxEntry(36, grip: g, side: .right, on: exportToday - 10)])))

    out.append(("both-hands-never-summed", makeInput(
        sessions: [session(exportToday, reps: [rep(0, side: .both, grip: g, peak: 40)])],
        maxes: [maxEntry(30, grip: g, side: .left, on: exportToday - 10),
                maxEntry(30, grip: g, side: .right, on: exportToday - 10)])))

    let old = exportToday - 30
    out.append(("max-as-it-stood-that-day", makeInput(
        sessions: [session(old, reps: [rep(0, side: .left, grip: g, peak: 20)])],
        maxes: [maxEntry(40, grip: g, side: .both, on: old - 5),
                maxEntry(50, grip: g, side: .both, on: exportToday)])))

    out.append(("timer-only", makeInput(
        sessions: [session(exportToday, timing: .timerOnly,
                           reps: [rep(0, side: .left, grip: grip(), peak: 0)])],
        maxes: [maxEntry(40, grip: grip(), side: .both, on: exportToday - 10)])))

    out.append(("skipped-pull", makeInput(
        sessions: [session(exportToday, reps: [
            rep(0, side: .left, grip: g, peak: 20),
            rep(1, side: .right, grip: g, peak: 0, held: 0, outcome: .skipped),
        ], planned: 2, completed: 1)],
        maxes: [maxEntry(40, grip: g, side: .both, on: exportToday - 10)])))

    let inside = exportToday - (AnalysisExport.detailedDays - 1)
    out.append(("eight-week-boundary", makeInput(sessions: [
        session(inside, name: "Inside", reps: [rep(0, side: .left, grip: grip(), peak: 12)]),
        session(inside - 1, name: "Outside", reps: [rep(0, side: .left, grip: grip(), peak: 12)]),
    ])))

    out.append(("empty-detail-window", makeInput(sessions: [
        session(exportToday - 100, reps: [rep(0, side: .left, grip: grip(), peak: 12)]),
    ])))

    let monday = AnalysisExport.weekStart(exportToday - 100)
    out.append(("weekly-rollup", makeInput(sessions: [
        session(monday + 1, name: "A",
                reps: [rep(0, side: .left, grip: grip(), peak: 12, held: 10),
                       rep(1, side: .right, grip: grip(), peak: 12, held: 6, outcome: .earlyRelease)],
                planned: 2, completed: 1, rpe: .solid, strain: .worked),
        session(monday + 3, name: "B",
                reps: [rep(0, side: .left, grip: grip(), peak: 12, held: 20)],
                planned: 4, completed: 1, rpe: .hard, strain: .taxed),
        session(monday + 3, name: "Limit climbing", kind: .climbLimit, timing: .logged,
                held: 0, planned: 0, completed: 0),
    ])))

    let thisMonday = AnalysisExport.weekStart(exportToday)
    out.append(("consistency-with-climbs", makeInput(sessions: [
        session(thisMonday, name: "A", reps: [rep(0, side: .left, grip: grip(), peak: 12)]),
        session(thisMonday, name: "B", reps: [rep(0, side: .left, grip: grip(), peak: 12)]),
        session(thisMonday + 2, name: "Gym", kind: .climbVolume, timing: .logged),
    ])))

    let thursday = AnalysisExport.weekStart(exportToday) - 18
    out.append(("partial-opening-week", makeInput(sessions: (0..<4).map {
        session(thursday + $0, name: "A", reps: [rep(0, side: .left, grip: grip(), peak: 12)])
    })))

    out.append(("current-maxes-per-hand", makeInput(maxes: [
        maxEntry(38, grip: g, side: .left, on: exportToday - 20),
        maxEntry(34, grip: g, side: .right, on: exportToday - 10, source: .manual),
    ])))

    out.append(("max-history", makeInput(maxes: [
        maxEntry(30, grip: g, side: .both, on: exportToday - 60),
        maxEntry(34, grip: g, side: .both, on: exportToday - 30),
        maxEntry(36, grip: g, side: .both, on: exportToday - 1),
    ])))

    let g1 = grip(20, .four, .halfCrimp)
    let g2 = grip(14, .frontThree, .openHand)
    let g3 = grip(20, .frontTwo, .fullCrimp)
    out.append(("twelve-sessions-determinism", makeInput(
        sessions: (0..<12).map { index in
            session(exportToday - index * 9,
                    name: "Routine \(index % 3)",
                    reps: [rep(0, side: .left, grip: g1, peak: 12 + Double(index)),
                           rep(1, side: .right, grip: g2, peak: 10 + Double(index)),
                           rep(2, side: .both, grip: g3, peak: 20 + Double(index))],
                    rpe: RPE(rawValue: index % 5 + 1),
                    strain: FingerStrain(rawValue: index % 5 + 1))
        },
        maxes: [maxEntry(40, grip: g1, side: .both, on: exportToday - 80),
                maxEntry(36, grip: g1, side: .right, on: exportToday - 40),
                maxEntry(22, grip: g2, side: .left, on: exportToday - 30),
                maxEntry(24, grip: g3, side: .both, on: exportToday - 5)])))

    out.append(("empty-history", makeInput()))

    out.append(("rich-eight-weeks-and-older", richHistory()))

    return out.map { (name: $0.0, input: $0.1) }
}

// MARK: - Commands

private func exportScenarios(_ directory: URL) throws {
    try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
    let encoder = JSONEncoder()
    encoder.outputFormatting = [.sortedKeys, .prettyPrinted]
    var written = 0
    for entry in scenarios() {
        let data = try encoder.encode(dto(entry.input))
        try (data + Data("\n".utf8))
            .write(to: directory.appendingPathComponent("\(entry.name).json"), options: .atomic)
        written += 1
    }
    print("export scenarios: \(written) inputs written")
}

private func exportRun(_ directory: URL, writing: Bool) throws {
    let names = try FileManager.default
        .contentsOfDirectory(atPath: directory.path)
        .filter { $0.hasSuffix(".json") }
        .map { String($0.dropLast(5)) }
        .sorted()
    guard !names.isEmpty else {
        throw OracleError("no export/*.json scenarios — run `oracle export scenarios` first")
    }
    var failures: [String] = []
    let decoder = JSONDecoder()
    for name in names {
        let jsonURL = directory.appendingPathComponent("\(name).json")
        let markdownURL = directory.appendingPathComponent("\(name).md")
        let parsed = try decoder.decode(ExportInputDTO.self, from: Data(contentsOf: jsonURL))
        let values = input(parsed)
        for scope in AnalysisExport.CSVScope.allCases {
            var selected = values
            if scope == .workout { selected.sessions = Array(values.sessions.prefix(1)) }
            for detail in AnalysisExport.CSVDetail.allCases {
            let csv = AnalysisExport.csv(selected, scope: scope, detail: detail).text
            let url = directory.appendingPathComponent("\(name).\(scope.rawValue).\(detail.rawValue).csv")
            if writing { try Data(csv.utf8).write(to: url, options: .atomic) }
            else if (try? String(contentsOf: url, encoding: .utf8)) != csv {
                failures.append("export/\(name).\(scope.rawValue).\(detail.rawValue).csv differs")
            }
            }
        }
        let document = AnalysisExport.document(values)
        if writing {
            try Data(document.utf8).write(to: markdownURL, options: .atomic)
        } else {
            let existing = (try? String(contentsOf: markdownURL, encoding: .utf8)) ?? ""
            if existing != document {
                failures.append("export/\(name).md differs from what the engine produces "
                                + "(\(existing.count) bytes on disk, \(document.count) generated)")
            }
        }
    }
    guard failures.isEmpty else {
        FileHandle.standardError.write(Data((failures.joined(separator: "\n") + "\n").utf8))
        throw OracleError("export verify: \(failures.count) of \(names.count) scenarios differ")
    }
    print("export \(writing ? "generate" : "verify"): \(names.count) scenarios OK")
}
