// SPDX-License-Identifier: MPL-2.0
// Original contributions Copyright 2026 Nuri Bruner.
import XCTest
@testable import Doigt

@MainActor
final class LegalAgreementTests: XCTestCase {
    func testBundledDocumentsAreReadableInBothLanguages() throws {
        let loaded = try XCTUnwrap(LegalBundle.loaded)
        XCTAssertEqual(loaded.fingerprint.count, 64)
        for language in ["en", "fr"] {
            for kind in ["terms", "privacy"] {
                let document = try XCTUnwrap(loaded.document.documents[language]?[kind])
                XCTAssertFalse(document.sections.isEmpty)
                XCTAssertTrue(document.plainText.contains("Oregon"))
            }
            XCTAssertNotEqual(loaded.document.text("checkbox", language), "checkbox")
        }
    }
    func testAcceptanceSurvivesRelaunchAndDoesNotDuplicate() throws {
        let loaded = try XCTUnwrap(LegalBundle.loaded)
        let dir = URL.temporaryDirectory.appending(path: UUID().uuidString)
        defer { try? FileManager.default.removeItem(at: dir) }
        let file = dir.appending(path: "receipt.json")
        let store = LegalAgreementStore(url: file)
        XCTAssertFalse(store.hasAccepted(loaded.document, fingerprint: loaded.fingerprint))
        try store.accept(loaded.document, fingerprint: loaded.fingerprint, language: "fr", now: Date(timeIntervalSince1970: 100))
        try store.accept(loaded.document, fingerprint: loaded.fingerprint, language: "en")
        let reloaded = LegalAgreementStore(url: file)
        XCTAssertTrue(reloaded.hasAccepted(loaded.document, fingerprint: loaded.fingerprint))
        XCTAssertEqual(reloaded.records.count, 1)
        XCTAssertEqual(reloaded.records.first?.language, "fr")
        XCTAssertEqual(reloaded.records.first?.acceptedUTC, "1970-01-01T00:01:40Z")
        XCTAssertFalse(reloaded.hasAccepted(loaded.document, fingerprint: "changed document"))
    }
    func testOldVersionAndChangedScreenNeedNewAcceptance() {
        let record = LegalAcceptance(version: "old", fingerprint: "same", language: "en", acceptedUTC: "date", appVersion: "1", platform: "iOS", screenVersion: 1)
        XCTAssertFalse(record.matches(version: "new", fingerprint: "same", screenVersion: 1))
        XCTAssertFalse(record.matches(version: "old", fingerprint: "same", screenVersion: 2))
    }
    func testCorruptionAndWriteFailureNeverGrantAcceptance() throws {
        let loaded = try XCTUnwrap(LegalBundle.loaded)
        let dir = URL.temporaryDirectory.appending(path: UUID().uuidString)
        try FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        defer { try? FileManager.default.removeItem(at: dir) }
        let file = dir.appending(path: "receipt.json")
        try Data("{broken".utf8).write(to: file)
        XCTAssertFalse(LegalAgreementStore(url: file).hasAccepted(loaded.document, fingerprint: loaded.fingerprint))
        let impossible = LegalAgreementStore(url: file.appending(path: "child.json"))
        XCTAssertThrowsError(try impossible.accept(loaded.document, fingerprint: loaded.fingerprint, language: "en"))
        XCTAssertFalse(impossible.hasAccepted(loaded.document, fingerprint: loaded.fingerprint))
    }
}
