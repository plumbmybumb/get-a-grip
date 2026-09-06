// SPDX-License-Identifier: MPL-2.0
// Original contributions Copyright 2026 Nuri Bruner.
import CryptoKit
import Foundation
import Observation

struct LegalDocument: Decodable {
    struct Section: Decodable { let title: String; let paragraphs: [String] }
    let title: String
    let summary: String
    let sections: [Section]
    var plainText: String {
        ([title, summary] + sections.flatMap { [$0.title] + $0.paragraphs }).joined(separator: "\n\n")
    }
}

struct LegalBundle: Decodable {
    let version: String
    let screenVersion: Int
    let documents: [String: [String: LegalDocument]]
    let ui: [String: [String: String]]

    static let loaded: (document: LegalBundle, fingerprint: String)? = {
        guard let url = Bundle.main.url(forResource: "agreement-2026-09-06", withExtension: "json"),
              let data = try? Data(contentsOf: url),
              let document = try? JSONDecoder().decode(LegalBundle.self, from: data) else { return nil }
        return (document, SHA256.hash(data: data).map { String(format: "%02x", $0) }.joined())
    }()
    static func language(_ locale: Locale) -> String { locale.language.languageCode?.identifier == "fr" ? "fr" : "en" }
    func text(_ key: String, _ language: String) -> String { ui[language]?[key] ?? ui["en"]?[key] ?? key }
}

struct LegalAcceptance: Codable, Equatable {
    let version: String
    let fingerprint: String
    let language: String
    let acceptedUTC: String
    let appVersion: String
    let platform: String
    let screenVersion: Int

    func matches(version: String, fingerprint: String, screenVersion: Int) -> Bool {
        self.version == version && self.fingerprint == fingerprint && self.screenVersion == screenVersion
    }
}

/// Local evidence of Terms acceptance, not an identity check or injury release.
/// Atomic file writes are awaited before entering training; a failed write never accepts.
@Observable @MainActor
final class LegalAgreementStore {
    private let url: URL
    private(set) var records: [LegalAcceptance]
    init(url: URL? = nil) {
        self.url = url ?? URL.applicationSupportDirectory.appending(path: "legal-acceptances.json")
        records = (try? JSONDecoder().decode([LegalAcceptance].self, from: Data(contentsOf: self.url))) ?? []
    }
    func hasAccepted(_ bundle: LegalBundle, fingerprint: String) -> Bool {
        records.contains { $0.matches(version: bundle.version, fingerprint: fingerprint, screenVersion: bundle.screenVersion) }
    }
    func accept(_ bundle: LegalBundle, fingerprint: String, language: String, now: Date = .now) throws {
        guard ["en", "fr"].contains(language) else { throw CocoaError(.coderInvalidValue) }
        guard !hasAccepted(bundle, fingerprint: fingerprint) else { return }
        let info = Bundle.main.infoDictionary ?? [:]
        let record = LegalAcceptance(version: bundle.version, fingerprint: fingerprint, language: language,
            acceptedUTC: ISO8601DateFormatter().string(from: now),
            appVersion: "\(info["CFBundleShortVersionString"] as? String ?? "?") (\(info["CFBundleVersion"] as? String ?? "?"))",
            platform: "iOS", screenVersion: bundle.screenVersion)
        let updated = records + [record]
        let encoder = JSONEncoder(); encoder.outputFormatting = [.prettyPrinted, .sortedKeys]
        let data = try encoder.encode(updated)
        try FileManager.default.createDirectory(at: url.deletingLastPathComponent(), withIntermediateDirectories: true)
        try data.write(to: url, options: .atomic)
        records = updated
    }
    var receipt: String? {
        guard !records.isEmpty else { return nil }
        let encoder = JSONEncoder(); encoder.outputFormatting = [.prettyPrinted, .sortedKeys]
        return (try? encoder.encode(records)).flatMap { String(data: $0, encoding: .utf8) }
    }
}
