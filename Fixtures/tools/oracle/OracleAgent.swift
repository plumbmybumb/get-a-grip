// SPDX-License-Identifier: MPL-2.0
// Original contributions Copyright 2026 Nuri Bruner.

import Foundation

// `oracle agent generate|verify <fixtures-dir>` — the "Create with AI" reader.
//
// `agent/inputs.json` is HAND-AUTHORED (and partly collected from real assistants): what
// people will paste. `generate` runs every input through the real iOS `AgentRoutine.read`
// and writes `agent/cases.json` — outcome, the routine as canonical JSON, the note codes
// — plus `agent/instructions.txt`, the text the app copies. `AgentRoutineFixtureTests.kt`
// holds the Kotlin reader and the Kotlin copy of the instructions to both files.

private struct AgentFixtureRoutine: Encodable {
    let plan: SessionPlan
    let sessionsPerDay: Int
    let isOnDemand: Bool
}

/// Set ids from a counter, so a re-generation is byte-identical.
private func fixtureIDs() -> () -> UUID {
    var next = 0
    return {
        defer { next += 1 }
        return UUID(uuidString: String(format: "A6E70000-0000-4000-8000-%012d", next))!
    }
}

private func agentCase(name: String, input: String) -> [String: Any] {
    var row: [String: Any] = ["name": name, "input": input]
    switch AgentRoutine.read(input, makeID: fixtureIDs()) {
    case .success(let reading):
        let routine = AgentFixtureRoutine(plan: reading.draft.plan,
                                          sessionsPerDay: reading.draft.sessionsPerDay,
                                          isOnDemand: reading.draft.isOnDemand)
        let data = (try? BlobCodec.encoder.encode(routine)) ?? Data()
        row["outcome"] = "ok"
        row["routine"] = String(decoding: data, as: UTF8.self)
        row["notes"] = reading.notes.map(\.code)
    case .failure(let failure):
        row["outcome"] = failure.code
        row["routine"] = NSNull()
        row["notes"] = [String]()
    }
    return row
}

func agentCommand(_ args: [String]) throws {
    guard args.count >= 2 else { usage() }
    let directory = URL(fileURLWithPath: args[1], isDirectory: true)
        .appendingPathComponent("agent", isDirectory: true)
    let inputs = try readJSONArray(directory.appendingPathComponent("inputs.json"))
    let cases = inputs.map { agentCase(name: $0["name"] as? String ?? "", input: $0["text"] as? String ?? "") }
    let instructionsURL = directory.appendingPathComponent("instructions.txt")

    switch args[0] {
    case "generate":
        try Data(AgentRoutine.instructions.utf8).write(to: instructionsURL, options: .atomic)
        try writeJSON(cases, to: directory.appendingPathComponent("cases.json"))
        let ok = cases.filter { $0["outcome"] as? String == "ok" }.count
        print("agent generate: \(cases.count) cases (\(ok) ok), instructions \(AgentRoutine.instructions.count) chars")
    case "verify":
        var failures: [String] = []
        let onDisk = try String(contentsOf: instructionsURL, encoding: .utf8)
        if onDisk != AgentRoutine.instructions { failures.append("instructions.txt differs from AgentRoutine.instructions") }
        let expected = try readJSONArray(directory.appendingPathComponent("cases.json"))
        if expected.count != cases.count { failures.append("case count \(expected.count) on disk, \(cases.count) derived") }
        for (want, got) in zip(expected, cases) {
            let name = got["name"] as? String ?? "?"
            for key in ["outcome", "routine"] where "\(want[key] ?? "nil")" != "\(got[key] ?? "nil")" {
                failures.append("\(name): \(key) differs")
            }
            if (want["notes"] as? [String] ?? []) != (got["notes"] as? [String] ?? []) {
                failures.append("\(name): notes differ")
            }
        }
        guard failures.isEmpty else {
            FileHandle.standardError.write(Data((failures.joined(separator: "\n") + "\n").utf8))
            throw OracleError("agent verify: \(failures.count) failure(s)")
        }
        print("agent verify: \(cases.count) cases OK")
    default:
        usage()
    }
}
