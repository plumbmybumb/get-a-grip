// SPDX-License-Identifier: MPL-2.0
// Original contributions Copyright 2026 Nuri Bruner.

import Foundation

// `oracle target-basis generate|verify <fixtures-dir>` — see main.swift and
// docs/TARGET_BASIS.md.
//
// Target loads as a percentage of CRITICAL FORCE. The REAL iOS engine (`PlanMath`,
// `CriticalForceTable`, `SessionPlan`'s decoder) computes every expectation; nothing here
// re-implements a rule. Four files:
//
//   target-basis/plan.json      (a) one plan using both bases at routine AND set level
//   target-basis/tables.json    (b) the max table and the critical force table it runs on
//   target-basis/expected.json  (c) the resolved band of every rep (kg lo/hi or null)
//   target-basis/cases.json     everything else: the CF fold, the decode/encode rules,
//                               and more resolution cases (hands, intensity, missing)

func targetBasisCommand(_ args: [String]) throws {
    guard args.count >= 2 else {
        FileHandle.standardError.write(Data("usage: oracle target-basis generate|verify <fixtures-dir>\n".utf8))
        exit(2)
    }
    let directory = URL(fileURLWithPath: args[1], isDirectory: true).appendingPathComponent("target-basis")
    switch args[0] {
    case "generate": try targetBasisRun(directory, writing: true)
    case "verify": try targetBasisRun(directory, writing: false)
    default:
        FileHandle.standardError.write(Data("oracle target-basis: unknown mode \(args[0])\n".utf8))
        exit(2)
    }
}

// MARK: - Shapes

private struct TBMax: Codable { var grip: String; var side: String; var kg: Double; var seconds: Int? }
private struct TBCF: Codable { var grip: String; var side: String; var kg: Double }

private struct TBSlot: Codable {
    var setIndex: Int
    var repIndex: Int
    var side: String
    var grip: String
    var basis: String
    var targetLo: Double?
    var targetHi: Double?

    enum CodingKeys: String, CodingKey { case setIndex, repIndex, side, grip, basis, targetLo, targetHi }
    // Explicit nulls: "no target" is an asserted value, not an absent one.
    func encode(to encoder: Encoder) throws {
        var c = encoder.container(keyedBy: CodingKeys.self)
        try c.encode(setIndex, forKey: .setIndex)
        try c.encode(repIndex, forKey: .repIndex)
        try c.encode(side, forKey: .side)
        try c.encode(grip, forKey: .grip)
        try c.encode(basis, forKey: .basis)
        try c.encode(targetLo, forKey: .targetLo)
        try c.encode(targetHi, forKey: .targetHi)
    }
}

private struct TBMissing: Codable {
    var grip: String
    var side: String
    var basis: String
    var maxSeconds: Int?

    enum CodingKeys: String, CodingKey { case grip, side, basis, maxSeconds }
    func encode(to encoder: Encoder) throws {
        var c = encoder.container(keyedBy: CodingKeys.self)
        try c.encode(grip, forKey: .grip)
        try c.encode(side, forKey: .side)
        try c.encode(basis, forKey: .basis)
        try c.encode(maxSeconds, forKey: .maxSeconds)
    }
}

private struct TBResolution: Codable {
    var name: String
    var plan: String
    var maxes: [TBMax]
    var criticalForces: [TBCF]
    var slots: [TBSlot]
    var untargetedGripCount: Int
    var missing: [TBMissing]
    var peakIntensity: Double?

    enum CodingKeys: String, CodingKey {
        case name, plan, maxes, criticalForces, slots, untargetedGripCount, missing, peakIntensity
    }
    func encode(to encoder: Encoder) throws {
        var c = encoder.container(keyedBy: CodingKeys.self)
        try c.encode(name, forKey: .name)
        try c.encode(plan, forKey: .plan)
        try c.encode(maxes, forKey: .maxes)
        try c.encode(criticalForces, forKey: .criticalForces)
        try c.encode(slots, forKey: .slots)
        try c.encode(untargetedGripCount, forKey: .untargetedGripCount)
        try c.encode(missing, forKey: .missing)
        try c.encode(peakIntensity, forKey: .peakIntensity)
    }
}

private struct TBFoldEntry: Codable {
    var grip: String
    var side: String
    var protocolKey: String
    var criticalForceKg: Double
    var recordedAt: String
}

private struct TBLookup: Codable {
    var grip: String
    var side: String
    var criticalForce: Double?
    var exact: Double?

    enum CodingKeys: String, CodingKey { case grip, side, criticalForce, exact }
    func encode(to encoder: Encoder) throws {
        var c = encoder.container(keyedBy: CodingKeys.self)
        try c.encode(grip, forKey: .grip)
        try c.encode(side, forKey: .side)
        try c.encode(criticalForce, forKey: .criticalForce)
        try c.encode(exact, forKey: .exact)
    }
}

private struct TBFold: Codable {
    var name: String
    var entries: [TBFoldEntry]
    var asOf: String?
    var lookups: [TBLookup]

    enum CodingKeys: String, CodingKey { case name, entries, asOf, lookups }
    func encode(to encoder: Encoder) throws {
        var c = encoder.container(keyedBy: CodingKeys.self)
        try c.encode(name, forKey: .name)
        try c.encode(entries, forKey: .entries)
        try c.encode(asOf, forKey: .asOf)
        try c.encode(lookups, forKey: .lookups)
    }
}

private struct TBCodable: Codable {
    var name: String
    var type: String
    var input: String
    /// Canonical JSON (`BlobCodec.encoder`) after a lenient decode and re-encode.
    var canonical: String
    /// What the decoded value's basis READS as — unknown raws read as "max".
    var targetBasis: String
}

private struct TBCases: Codable {
    var description: String
    var fold: [TBFold]
    var codable: [TBCodable]
    var resolution: [TBResolution]
}

// MARK: - The inputs

private let tbInstant: ISO8601DateFormatter = {
    let f = ISO8601DateFormatter()
    f.formatOptions = [.withInternetDateTime]
    return f
}()

private let g3 = GripSpec(edgeMM: 20, fingers: .frontThree, position: .halfCrimp)   // 20|IMR|halfCrimp
private let g4 = GripSpec(edgeMM: 20, fingers: .four, position: .halfCrimp)         // 20|IMRL|halfCrimp
private let g2 = GripSpec(edgeMM: 15, fingers: .frontTwo, position: .openHand)      // 15|IM|openHand

private func stable(_ n: Int) -> UUID {
    UUID(uuidString: String(format: "00000000-0000-0000-0000-%012d", n))!
}

private func percentSet(_ n: Int, _ grip: GripSpec, reps: Int, lo: Double?, hi: Double?,
                        basisRaw: String? = nil) -> SetPlan {
    var s = SetPlan(grip: grip, repsPerSide: reps)
    s.id = stable(n)
    s.targetLoPercent = lo
    s.targetHiPercent = hi
    s.targetBasisRaw = basisRaw
    return s
}

/// (a) One plan, both bases, both levels. Executed AS WRITTEN (not normalized), so the
/// routine-level band and its basis are live and inherited.
private func mainPlan() -> SessionPlan {
    var p = SessionPlan()
    p.name = "Target basis"
    p.handMode = .alternateEachRep
    p.holdSeconds = 7
    p.restSeconds = 3
    p.setBreakSeconds = 60
    p.leadInSeconds = 0
    p.targetLoPercent = 0.7
    p.targetHiPercent = 0.8
    p.targetBasisRaw = "criticalForce"
    var kg = SetPlan(grip: g4, repsPerSide: 1, targetLoKg: 8, targetHiKg: 10)
    kg.id = stable(5)
    kg.targetBasisRaw = "criticalForce"
    var timed = percentSet(8, g4, reps: 1, lo: 0.9, hi: 0.9, basisRaw: "max")
    timed.targetMaxSeconds = 10
    p.sets = [
        percentSet(1, g3, reps: 2, lo: nil, hi: nil),                         // inherits 70–80 % of CF
        percentSet(2, g3, reps: 1, lo: 0.2, hi: 0.3, basisRaw: "max"),          // own band, explicit max
        percentSet(3, g4, reps: 1, lo: 0.9, hi: 1.0, basisRaw: "criticalForce"), // own band of CF
        percentSet(4, g4, reps: 1, lo: 0.5, hi: nil),                         // own band, no basis → max
        kg,                                                                    // kg wins; basis ignored
        percentSet(6, g2, reps: 1, lo: nil, hi: nil),                         // inherits CF; none on file
        percentSet(7, g4, reps: 1, lo: 0.6, hi: 0.6, basisRaw: "wPrime"),       // unknown raw → max
        timed,                                                                 // 90 % of a 10 s max
    ]
    return p
}

/// (b) The tables (a) runs on.
private func mainMaxes() -> [TBMax] {
    [TBMax(grip: g3.key, side: "both", kg: 40, seconds: nil),
     TBMax(grip: g4.key, side: "left", kg: 50, seconds: nil),
     TBMax(grip: g4.key, side: "right", kg: 45, seconds: nil),
     TBMax(grip: g4.key, side: "left", kg: 42, seconds: 10),
     TBMax(grip: g2.key, side: "both", kg: 20, seconds: nil)]
}

private func mainCriticalForces() -> [TBCF] {
    [TBCF(grip: g3.key, side: "left", kg: 15),
     TBCF(grip: g3.key, side: "both", kg: 16),
     TBCF(grip: g4.key, side: "right", kg: 19)]
}

private func table(_ maxes: [TBMax]) -> MaxTable {
    var t = MaxTable()
    for m in maxes { t.record(m.kg, grip: m.grip, side: Side(rawValue: m.side)!, seconds: m.seconds) }
    return t
}

private func table(_ cfs: [TBCF]) -> CriticalForceTable {
    var t = CriticalForceTable()
    for c in cfs { t.record(c.kg, grip: c.grip, side: Side(rawValue: c.side)!) }
    return t
}

private func planText(_ plan: SessionPlan) -> String {
    String(decoding: BlobCodec.encode(plan)!, as: UTF8.self)
}

private func resolve(_ name: String, plan: SessionPlan, maxes: [TBMax], cfs: [TBCF]) -> TBResolution {
    let maxTable = table(maxes)
    let cfTable = table(cfs)
    let live = plan.executable
    let slots = PlanMath.sequence(for: plan, maxes: maxTable, criticalForces: cfTable).map { slot in
        TBSlot(setIndex: slot.setIndex, repIndex: slot.repIndex, side: slot.side.rawValue,
               grip: slot.grip.key,
               basis: PlanMath.targetBasis(live.sets[slot.setIndex], in: live).rawValue,
               targetLo: slot.targetBand?.lowerBound, targetHi: slot.targetBand?.upperBound)
    }
    let missing = PlanMath.missingReferences(plan, maxes: maxTable, criticalForces: cfTable).map {
        TBMissing(grip: $0.grip.key, side: $0.side.rawValue, basis: $0.basis.rawValue, maxSeconds: $0.maxSeconds)
    }
    return TBResolution(name: name, plan: planText(plan), maxes: maxes, criticalForces: cfs, slots: slots,
                        untargetedGripCount: PlanMath.untargetedGripCount(plan, maxes: maxTable,
                                                                          criticalForces: cfTable),
                        missing: missing,
                        peakIntensity: PlanMath.peakIntensity(of: plan, maxes: maxTable,
                                                              criticalForces: cfTable))
}

private func extraResolutions() -> [TBResolution] {
    var both = SessionPlan()
    both.name = "Both hands"
    both.handMode = .bothHands
    both.sets = [percentSet(11, g3, reps: 2, lo: 0.6, hi: 0.8, basisRaw: "criticalForce")]
    var oneHand = both
    oneHand.name = "One hand at a time"
    oneHand.handMode = .alternateEachSet
    oneHand.startingHand = .right
    return [
        // A both-hands rep resolves ONLY against a both-hands CF: two hand CFs are never summed.
        resolve("bothHandsNeedABothHandsCF", plan: both, maxes: [],
                cfs: [TBCF(grip: g3.key, side: "left", kg: 15), TBCF(grip: g3.key, side: "right", kg: 17)]),
        resolve("bothHandsWithABothHandsCF", plan: both,
                maxes: [TBMax(grip: g3.key, side: "both", kg: 40, seconds: nil)],
                cfs: [TBCF(grip: g3.key, side: "both", kg: 16)]),
        // A one-handed CF never leaks to the other hand; a max on file never stands in.
        resolve("oneHandedCFNeverLeaks", plan: oneHand,
                maxes: [TBMax(grip: g3.key, side: "both", kg: 40, seconds: nil)],
                cfs: [TBCF(grip: g3.key, side: "left", kg: 15)]),
        // Nothing on file at all: no target anywhere, intensity unknown.
        resolve("nothingOnFile", plan: mainPlan(), maxes: [], cfs: []),
    ]
}

private func foldCases() -> [TBFold] {
    let t0 = Date(timeIntervalSince1970: 1_780_000_000)
    func at(_ dt: TimeInterval) -> String { tbInstant.string(from: t0.addingTimeInterval(dt)) }
    let entries = [
        TBFoldEntry(grip: g3.key, side: "left", protocolKey: "7:3x24", criticalForceKg: 14, recordedAt: at(0)),
        TBFoldEntry(grip: g3.key, side: "left", protocolKey: "7:3x24", criticalForceKg: 16, recordedAt: at(100)),
        TBFoldEntry(grip: g3.key, side: "left", protocolKey: "7:3x24", criticalForceKg: 0, recordedAt: at(200)),
        TBFoldEntry(grip: g3.key, side: "left", protocolKey: "5:5x20", criticalForceKg: 30, recordedAt: at(300)),
        TBFoldEntry(grip: g3.key, side: "both", protocolKey: "7:3x24", criticalForceKg: 17, recordedAt: at(50)),
        TBFoldEntry(grip: g4.key, side: "right", protocolKey: "7:3x24", criticalForceKg: 19, recordedAt: at(10)),
        TBFoldEntry(grip: g4.key, side: "right", protocolKey: "7:3x24", criticalForceKg: 18, recordedAt: at(10)),
    ]
    let lookups: [(GripSpec, Side)] = [(g3, .left), (g3, .right), (g3, .both),
                                        (g4, .left), (g4, .right), (g4, .both)]
    func fold(_ name: String, asOf: String?) -> TBFold {
        let values = entries.map {
            CriticalForceTable.Entry(gripKey: $0.grip, side: Side(rawValue: $0.side)!, protocolKey: $0.protocolKey,
                                     criticalForceKg: $0.criticalForceKg,
                                     recordedAt: tbInstant.date(from: $0.recordedAt)!)
        }
        let t = CriticalForceTable.folding(values, asOf: asOf.flatMap { tbInstant.date(from: $0) })
        return TBFold(name: name, entries: entries, asOf: asOf, lookups: lookups.map { grip, side in
            TBLookup(grip: grip.key, side: side.rawValue,
                     criticalForce: t.criticalForce(grip: grip.key, side: side),
                     exact: t.exact(grip: grip.key, side: side))
        })
    }
    return [fold("newestValidStandardPerGripAndHand", asOf: nil),
            fold("asOfBeforeTheNewest", asOf: at(99)),
            fold("asOfIsInclusive", asOf: at(100)),
            fold("asOfBeforeEverything", asOf: at(-1))]
}

private func codableCases() -> [TBCodable] {
    let inputs: [(String, String, String)] = [
        ("setAbsentKeyIsMax", "SetPlan",
         #"{"id":"00000000-0000-0000-0000-000000000001","repsPerSide":2,"targetLoPercent":0.2,"targetHiPercent":0.3}"#),
        ("setCriticalForce", "SetPlan",
         #"{"id":"00000000-0000-0000-0000-000000000001","repsPerSide":2,"targetLoPercent":0.7,"targetBasis":"criticalForce"}"#),
        ("setExplicitMaxIsKept", "SetPlan",
         #"{"id":"00000000-0000-0000-0000-000000000001","repsPerSide":2,"targetLoPercent":0.7,"targetBasis":"max"}"#),
        ("setUnknownRawSurvivesAndReadsMax", "SetPlan",
         #"{"id":"00000000-0000-0000-0000-000000000001","repsPerSide":2,"targetLoPercent":0.7,"targetBasis":"wPrime"}"#),
        ("setEmptyRawIsAbsent", "SetPlan",
         #"{"id":"00000000-0000-0000-0000-000000000001","repsPerSide":2,"targetBasis":""}"#),
        ("setNonStringRawIsAbsent", "SetPlan",
         #"{"id":"00000000-0000-0000-0000-000000000001","repsPerSide":2,"targetBasis":3}"#),
        ("planAbsentKeyIsMax", "SessionPlan", #"{"name":"Old","targetLoPercent":0.2,"targetHiPercent":0.3}"#),
        ("planCriticalForce", "SessionPlan",
         #"{"name":"New","targetLoPercent":0.7,"targetHiPercent":0.8,"targetBasis":"criticalForce"}"#),
    ]
    return inputs.map { name, type, input in
        let data = Data(input.utf8)
        if type == "SetPlan" {
            let value = BlobCodec.decode(SetPlan.self, from: data)!
            return TBCodable(name: name, type: type, input: input,
                             canonical: String(decoding: BlobCodec.encode(value)!, as: UTF8.self),
                             targetBasis: value.targetBasis.rawValue)
        }
        let value = BlobCodec.decode(SessionPlan.self, from: data)!
        return TBCodable(name: name, type: type, input: input,
                         canonical: String(decoding: BlobCodec.encode(value)!, as: UTF8.self),
                         targetBasis: value.targetBasis.rawValue)
    }
}

// MARK: - Run

private struct TBPlanFile: Codable { var description: String; var plan: String }
private struct TBTablesFile: Codable { var description: String; var maxes: [TBMax]; var criticalForces: [TBCF] }
private struct TBExpectedFile: Codable {
    var description: String
    var slots: [TBSlot]
    var untargetedGripCount: Int
    var missing: [TBMissing]
    var peakIntensity: Double?

    enum CodingKeys: String, CodingKey { case description, slots, untargetedGripCount, missing, peakIntensity }
    func encode(to encoder: Encoder) throws {
        var c = encoder.container(keyedBy: CodingKeys.self)
        try c.encode(description, forKey: .description)
        try c.encode(slots, forKey: .slots)
        try c.encode(untargetedGripCount, forKey: .untargetedGripCount)
        try c.encode(missing, forKey: .missing)
        try c.encode(peakIntensity, forKey: .peakIntensity)
    }
}

private func targetBasisRun(_ directory: URL, writing: Bool) throws {
    let main = resolve("main", plan: mainPlan(), maxes: mainMaxes(), cfs: mainCriticalForces())
    let files: [(String, any Encodable)] = [
        ("plan.json", TBPlanFile(
            description: "(a) One SessionPlan, canonical JSON, using BOTH bases at routine AND set level. Execute it as written (PlanMath.sequence on the plan, not on RoutineDraft.normalized), so the routine-level 70–80 % of critical force is live and inherited by sets with no band of their own. Set ids are stable.",
            plan: main.plan)),
        ("tables.json", TBTablesFile(
            description: "(b) The tables plan.json resolves against. maxes: MaxTable rows (seconds absent = peak, else a timed max of that length). criticalForces: CriticalForceTable rows, already folded (newest valid standard test per grip|side).",
            maxes: main.maxes, criticalForces: main.criticalForces)),
        ("expected.json", TBExpectedFile(
            description: "(c) Every rep of plan.json resolved against tables.json: PlanMath.sequence(for:maxes:criticalForces:). basis is PlanMath.targetBasis of the rep's set (\"max\" for kg bands, untargeted sets and unknown raws). targetLo/targetHi are kilograms rounded to 0.5 (round half away from zero) or null for no target. untargetedGripCount, missing (PlanMath.missingReferences) and peakIntensity (PlanMath.peakIntensity, a fraction of the PEAK max, null when nothing contributes) are the builder's and Today's folds over the same inputs.",
            slots: main.slots, untargetedGripCount: main.untargetedGripCount,
            missing: main.missing, peakIntensity: main.peakIntensity)),
        ("cases.json", TBCases(
            description: "fold: CriticalForceTable.folding over raw tests (standard protocol 7:3x24 only; invalid kg skipped BEFORE choosing the newest; asOf inclusive; a recordedAt tie goes to the later entry) and the lookups with hand fallback (criticalForce) and without (exact). codable: lenient decode of input, canonical re-encode, and what targetBasis reads as. resolution: more plans in the plan.json/tables.json/expected.json shape.",
            fold: foldCases(), codable: codableCases(), resolution: extraResolutions())),
    ]
    let encoder = JSONEncoder()
    encoder.outputFormatting = [.sortedKeys, .prettyPrinted]
    if writing { try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true) }
    var failures: [String] = []
    for (name, value) in files {
        let data = try encoder.encode(value) + Data("\n".utf8)
        let url = directory.appendingPathComponent(name)
        if writing {
            try data.write(to: url, options: .atomic)
        } else if (try? Data(contentsOf: url)) != data {
            failures.append("target-basis/\(name) differs from what the engine produces")
        }
    }
    guard failures.isEmpty else {
        FileHandle.standardError.write(Data((failures.joined(separator: "\n") + "\n").utf8))
        throw OracleError("target-basis verify: \(failures.count) of \(files.count) files differ")
    }
    print("target-basis \(writing ? "generate" : "verify"): \(files.count) files OK")
}
