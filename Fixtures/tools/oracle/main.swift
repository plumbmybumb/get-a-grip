// SPDX-License-Identifier: MPL-2.0
// Original contributions Copyright 2026 Nuri Bruner.

import Foundation

// Dispatcher only. Each subcommand lives in its own file so the generators can be
// written and reviewed independently:
//   OracleShare.swift   share   — RoutineShare URLs and envelopes (Fixtures/share)
//   OracleExport.swift  export  — AnalysisExport documents (Fixtures/export)
//   OracleRunner.swift  runner  — record / verify SessionRunner traces (Fixtures/runner)
//
// Every command takes the repository's Fixtures directory as its first argument, so
// the binary never guesses where it is running from.

func usage() -> Never {
    let text = """
    usage: oracle <command> <fixtures-dir> [options]

      smoke                       prove the engine is linked and print a few facts
      share  generate <dir>       write share/urls.json and share/roundtrip.json
      share  verify   <dir>       decode every URL in share/urls.json and compare
      export generate <dir>       write export/<scenario>.md for every export/<scenario>.json
      export verify   <dir>       regenerate and diff against the .md on disk
      runner record   <dir>       record the canonical scenarios into runner/*.json
      runner verify   <dir>       replay every runner/*.json through SessionRunner

    """
    FileHandle.standardError.write(Data(text.utf8))
    exit(2)
}

func smoke() {
    let starter = RoutineDraft.starter
    print("starter keys:", starter.plan.sets.map(\.grip.key))
    if let url = RoutineShare.url(for: starter) {
        print("share url:", url.absoluteString.count, "chars")
    }
    var runner = SessionRunner(plan: starter.plan)
    print("first cues:", runner.handle(.start, at: 0))
    print("export title:", AnalysisExport.document(AnalysisExport.Input()).split(separator: "\n").first ?? "")
}

let arguments = Array(CommandLine.arguments.dropFirst())
guard let command = arguments.first else { usage() }
let rest = Array(arguments.dropFirst())

do {
    switch command {
    case "smoke": smoke()
    case "share": try shareCommand(rest)
    case "export": try exportCommand(rest)
    case "runner": try runnerCommand(rest)
    default: usage()
    }
} catch {
    FileHandle.standardError.write(Data("oracle: \(error)\n".utf8))
    exit(1)
}
