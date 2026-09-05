// SPDX-License-Identifier: MPL-2.0
// Original contributions Copyright 2026 Nuri Bruner.

import Foundation

// `oracle runner record|verify <fixtures-dir>` — see main.swift.
//
// **VERIFY is the gate.** `Fixtures/runner/*.json` is recorded by the Kotlin engine
// (`android/.../RunnerTrace.kt`) and replayed HERE through the real iOS `SessionRunner`:
// same plan, same maxes, same events, same caller-supplied `t`. Every step's cue list is
// compared, then the phase, the rep count, and each `RepSummary` as canonical JSON. Two
// independent implementations therefore have to agree on every millisecond of a workout,
// which is the only way a rule written down in `Shared/Engine` can be known to have
// survived translation.
//
// RECORD is deliberately a stub. A fixture wants ONE author, and the Kotlin side already
// is it — a Swift recorder would let each engine assert its own output and call that a
// contract. If the iOS engine ever needs to originate a trace, add the scenarios here and
// have Kotlin's `RunnerFixtureTests` be the check; do not add a second author for the
// same file.

func runnerCommand(_ args: [String]) throws {
    guard args.count >= 2 else { usage() }
    let directory = URL(fileURLWithPath: args[1], isDirectory: true)
        .appendingPathComponent("runner", isDirectory: true)

    switch args[0] {
    case "verify":
        try runnerVerify(directory)
    case "record":
        let note = "oracle runner record: not implemented — Fixtures/runner is recorded by "
            + "the Kotlin engine and VERIFIED here. See the note at the top of "
            + "OracleRunner.swift.\n"
        FileHandle.standardError.write(Data(note.utf8))
        exit(3)
    default:
        usage()
    }
}

// MARK: - Verify

private func runnerVerify(_ directory: URL) throws {
    let files = try FileManager.default
        .contentsOfDirectory(at: directory, includingPropertiesForKeys: nil)
        .filter { $0.pathExtension == "json" }
        .sorted { $0.lastPathComponent < $1.lastPathComponent }

    guard !files.isEmpty else {
        fail("no runner fixtures found in \(directory.path)")
    }

    var steps = 0
    for file in files {
        steps += try replay(file)
    }
    print("runner verify: \(files.count) scenarios, \(steps) steps — OK")
}

private func replay(_ file: URL) throws -> Int {
    let raw = try Data(contentsOf: file)
    guard let scenario = try JSONSerialization.jsonObject(with: raw) as? [String: Any] else {
        fail("\(file.lastPathComponent): not a JSON object")
    }
    let name = scenario["name"] as? String ?? file.lastPathComponent

    guard let planText = scenario["plan"] as? String,
          let plan = BlobCodec.decode(SessionPlan.self, from: Data(planText.utf8)) else {
        fail("\(name): the plan must decode")
    }
    // The plan is CANONICAL, so a decode/re-encode round trip is a free byte check on
    // every field the scenario exercises — and it is the check that catches a decoder
    // clamp differing between the two engines.
    guard let reencoded = BlobCodec.encode(plan),
          String(decoding: reencoded, as: UTF8.self) == planText else {
        fail("""
        \(name): the plan is not canonical after a round trip
          fixture: \(planText)
          swift:   \(BlobCodec.encode(plan).map { String(decoding: $0, as: UTF8.self) } ?? "<nil>")
        """)
    }

    var maxes = MaxTable()
    for entry in scenario["maxes"] as? [[String: Any]] ?? [] {
        guard let grip = entry["grip"] as? String,
              let sideRaw = entry["side"] as? String, let side = Side(rawValue: sideRaw),
              let kg = (entry["kg"] as? NSNumber)?.doubleValue else {
            fail("\(name): malformed maxes entry \(entry)")
        }
        maxes.record(kg, grip: grip, side: side)
    }

    var runner = SessionRunner(
        plan: plan,
        maxes: maxes,
        timerOnly: scenario["timerOnly"] as? Bool ?? false,
        maxCreditedSampleGapSeconds:
            (scenario["maxCreditedSampleGapSeconds"] as? NSNumber)?.doubleValue)

    let steps = scenario["steps"] as? [[String: Any]] ?? []
    for (index, step) in steps.enumerated() {
        guard let t = (step["t"] as? NSNumber)?.doubleValue,
              let eventJSON = step["event"] as? [String: Any] else {
            fail("\(name): step \(index) is malformed")
        }
        let event = decodeEvent(eventJSON, name: name, index: index)
        let produced = runner.handle(event, at: t).map(encode)
        let expected = step["cues"] as? [[String: Any]] ?? []
        if !cuesMatch(expected, produced) {
            fail("""
            \(name): step \(index) — cues differ
              event:    \(compact(eventJSON))
              t:        \(t)
              fixture:  \(expected.map(compact).joined(separator: " "))
              swift:    \(produced.map(compact).joined(separator: " "))
            """)
        }
    }

    guard let final = scenario["final"] as? [String: Any] else {
        fail("\(name): no final state")
    }
    let expectedPhase = final["phase"] as? String ?? ""
    let actualPhase = phaseText(runner.phase)
    if expectedPhase != actualPhase {
        fail("\(name): final phase — fixture '\(expectedPhase)', swift '\(actualPhase)'")
    }
    let expectedCount = (final["completedRepCount"] as? NSNumber)?.intValue ?? -1
    if expectedCount != runner.completedRepCount {
        fail("""
        \(name): completedRepCount — fixture \(expectedCount), swift \(runner.completedRepCount)
        """)
    }
    let expectedResults = final["results"] as? [String] ?? []
    let actualResults = runner.results.map {
        BlobCodec.encode($0).map { String(decoding: $0, as: UTF8.self) } ?? "<nil>"
    }
    if expectedResults != actualResults {
        for (i, pair) in zip(expectedResults, actualResults).enumerated() where pair.0 != pair.1 {
            fail("""
            \(name): rep \(i) differs
              fixture: \(pair.0)
              swift:   \(pair.1)
            """)
        }
        fail("""
        \(name): \(expectedResults.count) reps in the fixture, \(actualResults.count) from swift
        """)
    }
    return steps.count
}

// MARK: - The wire shapes, per Fixtures/README.md

private func decodeEvent(_ o: [String: Any], name: String, index: Int) -> RunnerEvent {
    switch o["type"] as? String {
    case "start": return .start
    case "sample":
        guard let kg = (o["kg"] as? NSNumber)?.doubleValue,
              let micros = (o["micros"] as? NSNumber)?.uint32Value else {
            fail("\(name): step \(index) — malformed sample \(compact(o))")
        }
        return .sample(ForceSample(kg: kg, deviceMicros: micros,
                                   isBatchStart: o["batchStart"] as? Bool ?? true))
    case "tick": return .tick
    case "connectionLost": return .connectionLost
    case "connectionRestored": return .connectionRestored
    case "tareCommitted": return .tareCommitted
    case "streamRestarted": return .streamRestarted
    case "pause": return .pause
    case "resume": return .resume
    case "skipRep": return .skipRep
    case "skipSet": return .skipSet
    case "abort": return .abort
    default:
        fail("\(name): step \(index) — unknown event \(compact(o))")
    }
}

private func encode(_ cue: RunnerCue) -> [String: Any] {
    switch cue {
    case .leadInTick(let seconds): return ["type": "leadInTick", "seconds": seconds]
    case .armed(let side): return ["type": "armed", "side": side.rawValue]
    case .repStarted: return ["type": "repStarted"]
    case .repHalfway: return ["type": "repHalfway"]
    case .repEnded(let completed): return ["type": "repEnded", "completed": completed]
    case .restTick(let seconds): return ["type": "restTick", "seconds": seconds]
    case .setCompleted(let setIndex): return ["type": "setCompleted", "setIndex": setIndex]
    case .sessionCompleted: return ["type": "sessionCompleted"]
    case .dropoutWarning: return ["type": "dropoutWarning"]
    case .connectionLost: return ["type": "connectionLost"]
    }
}

/// `idle | leadIn:<slot> | armed:<slot> | working:<slot> | releasing:<slot> |
/// resting:<slot> | paused(<inner>) | finished`.
private func phaseText(_ phase: RunnerPhase) -> String {
    switch phase {
    case .idle: return "idle"
    case .leadIn(let slot): return "leadIn:\(slot)"
    case .armed(let slot): return "armed:\(slot)"
    case .working(let slot): return "working:\(slot)"
    case .releasing(let slot): return "releasing:\(slot)"
    case .resting(let slot): return "resting:\(slot)"
    case .paused(let inner): return "paused(\(phaseText(inner)))"
    case .finished: return "finished"
    }
}

// MARK: - Comparison and reporting

/// `[String: Any]` is not `Equatable`, and `NSDictionary` equality would compare a JSON
/// `true` against a `1` by number. Comparing the CANONICAL text of each cue is exact and
/// is the same reading both engines write.
private func cuesMatch(_ expected: [[String: Any]], _ produced: [[String: Any]]) -> Bool {
    expected.count == produced.count
        && zip(expected, produced).allSatisfy { compact($0) == compact($1) }
}

private func compact(_ o: [String: Any]) -> String {
    let data = (try? JSONSerialization.data(withJSONObject: o, options: [.sortedKeys])) ?? Data()
    return String(decoding: data, as: UTF8.self)
}

private func fail(_ message: String) -> Never {
    FileHandle.standardError.write(Data("oracle runner: \(message)\n".utf8))
    exit(1)
}
