// SPDX-License-Identifier: MPL-2.0
// Original contributions Copyright 2026 Nuri Bruner.

import Foundation

// `oracle share generate|verify <fixtures-dir>` — see main.swift.
//
// The REAL iOS `RoutineShare` mints every `ok` URL in `share/urls.json`, and the real
// decoder is what stamps every outcome. Nothing here re-implements the format: the
// hand-built rejection cases are strings, and their verdicts come back out of the
// shipping decoder rather than being asserted by this file.
//
// Cross-decode is the contract, NOT byte-identical URLs — Apple's raw-deflate output and
// Java's legitimately differ. So `verify` reads BOTH `urls.json` (this side's URLs) and
// `urls-android.json` (written by `ShareFixtureTests.kt` from the Kotlin encoder) and
// puts every one of them through the iOS decoder.
//
// SET IDS ARE ZEROED in every recorded `envelope`: import remints them by design
// (`RoutineShare` mints a fresh `SetPlan.id` per row so two people's routines can never
// share row identity), so they are the one part of the plan the two engines must NOT
// agree on. Everything else in the envelope is compared byte for byte.

func shareCommand(_ args: [String]) throws {
    guard args.count >= 2 else {
        FileHandle.standardError.write(Data("usage: oracle share generate|verify <fixtures-dir>\n".utf8))
        exit(2)
    }
    let directory = URL(fileURLWithPath: args[1], isDirectory: true)
    switch args[0] {
    case "generate": try shareGenerate(directory)
    case "verify": try shareVerify(directory)
    default:
        FileHandle.standardError.write(Data("oracle share: unknown mode \(args[0])\n".utf8))
        exit(2)
    }
}

// MARK: - The envelope, as the fixture records it

/// The same four keys `RoutineShare.Envelope` writes, declared here because that type is
/// private to the codec. Synthesized `Codable` gives the identical shape, and
/// `BlobCodec.encoder` (sorted keys, slashes escaped as Foundation's default does) is the
/// canonical form every fixture in this repo uses.
private struct OracleEnvelope: Codable {
    var v: Int
    var plan: SessionPlan
    var sessionsPerDay: Int
    var isOnDemand: Bool
}

private let zeroUUID = UUID(uuidString: "00000000-0000-0000-0000-000000000000")!

private func withoutSetIDs(_ plan: SessionPlan) -> SessionPlan {
    var out = plan
    out.sets = out.sets.map { set in
        var s = set
        s.id = zeroUUID
        return s
    }
    return out
}

private func envelopeText(_ draft: RoutineDraft) -> String? {
    let envelope = OracleEnvelope(v: RoutineShare.currentVersion,
                                  plan: withoutSetIDs(draft.plan),
                                  sessionsPerDay: draft.sessionsPerDay,
                                  isOnDemand: draft.isOnDemand)
    guard let data = try? BlobCodec.encoder.encode(envelope) else { return nil }
    return String(decoding: data, as: UTF8.self)
}

private func planText(_ plan: SessionPlan) -> String? {
    guard let data = try? BlobCodec.encoder.encode(plan) else { return nil }
    return String(decoding: data, as: UTF8.self)
}

/// The shipping decoder's verdict, as the fixture's `outcome` string.
private func decodeOutcome(_ text: String) -> (outcome: String, envelope: String?) {
    guard let url = URL(string: text) else { return ("notARoutineLink", nil) }
    do {
        let draft = try RoutineShare.draft(from: url)
        return ("ok", envelopeText(draft))
    } catch let error as RoutineShareError {
        switch error {
        case .notARoutineLink: return ("notARoutineLink", nil)
        case .unreadable:      return ("unreadable", nil)
        case .newerVersion:    return ("newerVersion", nil)
        case .emptyRoutine:    return ("emptyRoutine", nil)
        case .tooLarge:        return ("tooLarge", nil)
        }
    } catch {
        return ("unreadable", nil)
    }
}

// MARK: - Hand-built wire

/// JSON → raw deflate → base64url, deliberately NOT borrowed from the codec: a hand-built
/// fixture that went through the encoder could only ever agree with the encoder.
private func compressedPayload(_ json: String) -> Data {
    (try! (Data(json.utf8) as NSData).compressed(using: .zlib)) as Data
}

private func base64url(_ data: Data) -> String {
    var s = data.base64EncodedString()
        .replacingOccurrences(of: "+", with: "-")
        .replacingOccurrences(of: "/", with: "_")
    while s.hasSuffix("=") { s.removeLast() }
    return s
}

private func link(_ json: String, host: String = "getagrip://routine") -> String {
    host + "#" + base64url(compressedPayload(json))
}

// MARK: - The drafts the encoder is asked to mint

/// Deterministic row identity so `share generate` is idempotent: `RoutineDraft.starter`
/// mints fresh `SetPlan` UUIDs on every access, and those ids ARE payload — without this
/// every regeneration would rewrite every URL in the fixture for no change in meaning.
private func stableIDs(_ draft: RoutineDraft) -> RoutineDraft {
    var out = draft
    out.plan.sets = out.plan.sets.enumerated().map { index, set in
        var s = set
        s.id = UUID(uuidString: String(format: "00000000-0000-0000-0000-%012d", index + 1))!
        return s
    }
    return out
}

private func everyFieldOffDefault() -> RoutineDraft {
    var source = RoutineDraft()
    source.plan.name = "Loaded"
    source.plan.handMode = .bothHands
    source.plan.holdSeconds = 7
    source.plan.restSeconds = 33
    source.plan.setBreakSeconds = 45
    source.plan.leadInSeconds = 11
    source.plan.thresholdKg = 3.5
    source.plan.waitForReleaseBeforeRest = false
    source.plan.pausesOutsideTargetBand = false
    var heavy = SetPlan(grip: GripSpec(edgeMM: 15, fingers: .frontTwo, position: .fullCrimp),
                        repsPerSide: 3)
    heavy.holdSeconds = 5
    heavy.restSeconds = 90
    heavy.targetLoKg = 25
    heavy.targetHiKg = 30.5
    heavy.note = "top set — chalk up"
    var banded = SetPlan(grip: GripSpec(edgeMM: 45, fingers: .four, position: .pinch),
                         repsPerSide: 4)
    banded.targetLoPercent = 0.55
    banded.targetHiPercent = 0.72
    source.plan.sets = [heavy, banded]
    source.sessionsPerDay = 3
    source.isOnDemand = true
    return stableIDs(source)
}

private func longNameAndNote() -> RoutineDraft {
    var source = RoutineDraft.starter
    source.plan.name = String(repeating: "B", count: 200)
    source.plan.sets[0].note = String(repeating: "n", count: 900)
    return stableIDs(source)
}

private func unknownGripPositions() -> RoutineDraft {
    var source = RoutineDraft()
    source.plan.name = "From a newer build"
    source.plan.sets = [
        SetPlan(grip: GripSpec(edgeMM: 18, fingers: .backThree, position: GripPosition("sloper")),
                repsPerSide: 5),
        SetPlan(grip: GripSpec(edgeMM: 35, fingers: .four, position: GripPosition("gaston")),
                repsPerSide: 3),
    ]
    return stableIDs(source)
}

private func oneSetDraft() -> RoutineDraft {
    var source = RoutineDraft()
    source.plan.name = "One set"
    source.plan.sets = [SetPlan(grip: GripSpec(edgeMM: 20, fingers: .four, position: .halfCrimp),
                                repsPerSide: 6)]
    source.sessionsPerDay = 1
    return stableIDs(source)
}

private func fingerCurlDraft() -> RoutineDraft {
    var source = oneSetDraft()
    source.plan.name = "Finger curls"
    source.plan.sets[0].grip.position = GripPosition("fingerCurl")
    source.plan.sets[0].targetLoPercent = 0.20
    source.plan.sets[0].targetHiPercent = 0.30
    return source
}

private func encoderDrafts() -> [(name: String, draft: RoutineDraft)] {
    [
        ("starter", stableIDs(.starter)),
        ("maxDay", stableIDs(.maxDay)),
        ("everyFieldOffDefault", everyFieldOffDefault()),
        ("longNameAndNote", longNameAndNote()),
        ("unknownGripPositions", unknownGripPositions()),
        ("oneSet", oneSetDraft()),
        ("fingerCurl", fingerCurlDraft()),
    ]
}

// MARK: - Generate

private let oneSetJSON =
    #"{"grip":{"edgeMM":20,"fingers":"IMRL","position":"halfCrimp"},"repsPerSide":6}"#

private func handBuiltCases() -> [(name: String, url: String)] {
    let whole = compressedPayload(#"{"v":1,"plan":{"sets":[\#(oneSetJSON)]}}"#)
    let fiftyOne = Array(repeating: oneSetJSON, count: 51).joined(separator: ",")
    let fifty = Array(repeating: oneSetJSON, count: 50).joined(separator: ",")
    let dead = #"{"grip":{"edgeMM":20,"fingers":"IMRL","position":"halfCrimp"},"repsPerSide":0}"#
    let starterURL = RoutineShare.url(for: stableIDs(.starter))!.absoluteString
    let starterPayload = String(starterURL.split(separator: "#", maxSplits: 1).last ?? "")
    let firstByte = starterPayload.first.flatMap { $0.asciiValue } ?? 0

    return [
        // Not ours to answer for at all.
        ("foreignSchemeMailto", "mailto:nuri@example.com"),
        ("foreignHTTPSPage", "https://example.com/blog/hangboarding"),
        ("routineAsAPathSubstring", "https://x.com/my-routines/7#abc"),
        ("ourSchemeWrongHost", "getagrip://max#YWJj"),
        // Ours, with nothing attached: somebody typed the scheme.
        ("noFragment", "getagrip://routine"),
        // Ours, with an empty payload: a code that scanned badly.
        ("emptyFragment", "getagrip://routine#"),
        // Damage, in the four shapes a camera actually produces.
        ("junkBase64", "getagrip://routine#not-a-payload!!"),
        ("validBase64ThatIsNotDeflate",
         "getagrip://routine#" + base64url(Data([0xDE, 0xAD, 0xBE, 0xEF, 0x00, 0x11, 0x22, 0x33]))),
        ("truncatedDeflate", "getagrip://routine#" + base64url(whole.dropLast(10))),
        ("deflateOfNonEnvelopeJSON", link(#"{"hello":"world"}"#)),
        ("deflateOfAJSONArray", link(#"[1,2,3]"#)),
        ("planMangledIntoAScalar", link(#"{"v":1,"plan":"nope"}"#)),
        ("planKeyMissing", link(#"{"v":1}"#)),
        // The version gate, both directions.
        ("versionZero", link(#"{"v":0,"plan":{"sets":[\#(oneSetJSON)]}}"#)),
        ("versionNinetyNine", link(#"{"v":99,"plan":{"sets":[\#(oneSetJSON)]}}"#)),
        ("versionMissing", link(#"{"plan":{"sets":[\#(oneSetJSON)]}}"#)),
        // The two caps, and the ceiling that still imports.
        ("fiftyOneSets", link(#"{"v":1,"plan":{"sets":[\#(fiftyOne)]}}"#)),
        ("fiftySets", link(#"{"v":1,"plan":{"sets":[\#(fifty)]}}"#)),
        ("everySetZeroRep", link(#"{"v":1,"plan":{"sets":[\#(dead)]}}"#)),
        ("noSetsAtAll", link(#"{"v":1,"plan":{"sets":[]}}"#)),
        // Forward compatibility and the plan's own clamps.
        ("unknownKeysEverywhere", link(#"""
        {"v":1,"sessionsPerDay":2,"isOnDemand":false,"gauge":"progressor",\#
        "plan":{"name":"From the future","mood":"crisp","sets":[\#
        {"grip":{"edgeMM":18,"fingers":"IMR","position":"halfCrimp","texture":"wood"},\#
        "repsPerSide":4,"tempo":"slow"}]}}
        """#)),
        ("wildNumbersAreClamped", link(#"""
        {"v":1,"plan":{"holdSeconds":9999,"restSeconds":-40,"thresholdKg":0,"sets":[\#
        {"grip":{"edgeMM":0,"fingers":"IMRL","position":"halfCrimp"},"repsPerSide":99}]}}
        """#)),
        ("pinchArrivesWithItsThumb", link(#"""
        {"v":1,"plan":{"sets":[\#
        {"grip":{"edgeMM":20,"fingers":"IM","position":"pinch"},"repsPerSide":4}]}}
        """#)),
        ("strangersTextIsTrimmedAndCapped", link(#"""
        {"v":1,"plan":{"name":"  \#(String(repeating: "A", count: 200))  ","sets":[\#
        {"grip":{"edgeMM":20,"fingers":"IMRL","position":"halfCrimp"},\#
        "repsPerSide":6,"note":"\#(String(repeating: "n", count: 900))"}]}}
        """#)),
        ("sessionsPerDayOutOfRange",
         link(#"{"v":1,"sessionsPerDay":9,"plan":{"sets":[\#(oneSetJSON)]}}"#)),
        // The universal-link form today's build must already read, and the percent-
        // encoding an intermediary is allowed to apply on the way through.
        ("httpsUniversalLinkForm", "https://example.com/routine#" + starterPayload),
        ("nestedHTTPSPathComponent", "https://getagrip.example/app/routine#" + starterPayload),
        ("percentEncodedFragment",
         "getagrip://routine#" + String(format: "%%%02X", firstByte) + String(starterPayload.dropFirst())),
    ]
}

private func shareGenerate(_ directory: URL) throws {
    let shareDirectory = directory.appendingPathComponent("share", isDirectory: true)
    try FileManager.default.createDirectory(at: shareDirectory, withIntermediateDirectories: true)

    var cases: [(name: String, url: String)] = []
    for entry in encoderDrafts() {
        guard let url = RoutineShare.url(for: entry.draft) else {
            throw OracleError("the encoder refused \(entry.name), which no fixture draft may be")
        }
        cases.append((entry.name, url.absoluteString))
    }
    cases.append(contentsOf: handBuiltCases())

    var rows: [[String: Any]] = []
    for entry in cases {
        let verdict = decodeOutcome(entry.url)
        var row: [String: Any] = ["name": entry.name, "url": entry.url, "outcome": verdict.outcome]
        row["envelope"] = verdict.envelope ?? NSNull()
        rows.append(row)
    }
    try writeJSON(rows, to: shareDirectory.appendingPathComponent("urls.json"))

    var roundtrip: [[String: Any]] = []
    for entry in encoderDrafts() {
        guard let plan = planText(entry.draft.plan) else {
            throw OracleError("\(entry.name) did not encode")
        }
        roundtrip.append([
            "name": entry.name,
            "draft": [
                "plan": plan,
                "sessionsPerDay": entry.draft.sessionsPerDay,
                "isOnDemand": entry.draft.isOnDemand,
            ],
        ])
    }
    try writeJSON(roundtrip, to: shareDirectory.appendingPathComponent("roundtrip.json"))

    let okCount = rows.filter { ($0["outcome"] as? String) == "ok" }.count
    print("share generate: \(rows.count) urls (\(okCount) ok), \(roundtrip.count) roundtrip drafts")
}

// MARK: - Verify

private func shareVerify(_ directory: URL) throws {
    let shareDirectory = directory.appendingPathComponent("share", isDirectory: true)
    var total = 0
    var failures: [String] = []

    for file in ["urls.json", "urls-android.json"] {
        let url = shareDirectory.appendingPathComponent(file)
        guard FileManager.default.fileExists(atPath: url.path) else {
            if file == "urls-android.json" {
                throw OracleError("\(file) is missing — run ./android/build.sh engine first, "
                                  + "ShareFixtureTests writes it")
            }
            throw OracleError("\(file) is missing — run `oracle share generate` first")
        }
        let rows = try readJSONArray(url)
        var count = 0
        for row in rows {
            guard let name = row["name"] as? String,
                  let text = row["url"] as? String,
                  let expected = row["outcome"] as? String else {
                failures.append("\(file): a row is not {name, url, outcome}")
                continue
            }
            count += 1
            let verdict = decodeOutcome(text)
            if verdict.outcome != expected {
                failures.append("\(file)/\(name): expected \(expected), got \(verdict.outcome)")
                continue
            }
            guard expected == "ok" else { continue }
            guard let envelope = row["envelope"] as? String else {
                failures.append("\(file)/\(name): an ok row carries no envelope")
                continue
            }
            if verdict.envelope != envelope {
                failures.append("""
                \(file)/\(name): the envelope differs
                  expected: \(envelope)
                  actual:   \(verdict.envelope ?? "nil")
                """)
            }
        }
        total += count
        print("share verify: \(file) — \(count) urls")
    }

    guard failures.isEmpty else {
        FileHandle.standardError.write(Data((failures.joined(separator: "\n") + "\n").utf8))
        throw OracleError("share verify: \(failures.count) failure(s) across \(total) urls")
    }
    print("share verify: \(total) urls OK")
}

// MARK: - Small shared helpers

struct OracleError: Error, CustomStringConvertible {
    let description: String
    init(_ description: String) { self.description = description }
}

func writeJSON(_ value: Any, to url: URL) throws {
    let data = try JSONSerialization.data(withJSONObject: value,
                                          options: [.sortedKeys, .prettyPrinted])
    try (data + Data("\n".utf8)).write(to: url, options: .atomic)
}

func readJSONArray(_ url: URL) throws -> [[String: Any]] {
    let data = try Data(contentsOf: url)
    guard let rows = try JSONSerialization.jsonObject(with: data) as? [[String: Any]] else {
        throw OracleError("\(url.lastPathComponent) is not an array of objects")
    }
    return rows
}
