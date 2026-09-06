// SPDX-License-Identifier: MPL-2.0
// Original contributions Copyright 2026 Nuri Bruner.

import CoreTransferable
import UniformTypeIdentifiers
import XCTest
@testable import Doigt

@MainActor
final class SharePNGTests: XCTestCase {
    func testDataAndFileReceiversGetOriginalPNGAndSuggestedName() async throws {
        let bytes = Data(base64Encoded: "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mNk+A8AAQUBAScY42YAAAAASUVORK5CYII=")!
        let image = SharePNG(data: bytes, filename: "get-a-grip-5-weeks.png")
        XCTAssertEqual(image.exportedContentTypes(), [.png])
        XCTAssertEqual(image.suggestedFilename, "get-a-grip-5-weeks.png")
        let data = try await image.exported(as: .png)
        XCTAssertEqual(data, bytes)

        let destination = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        try FileManager.default.createDirectory(at: destination, withIntermediateDirectories: true)
        defer { try? FileManager.default.removeItem(at: destination) }
        let exported = try await image.export(to: destination, contentType: .png)
        XCTAssertEqual(exported.lastPathComponent, "get-a-grip-5-weeks.png")
        XCTAssertEqual(try Data(contentsOf: exported), bytes)
    }

    func testAnalysisTransferKeepsCSVFileNameAndTextFallback() async throws {
        let csv = "workout,peak_kg\nsynthetic-workout,12.5\n"
        let item = AnalysisExportFile(text: csv, filename: "get-a-grip-training-all.csv")
        XCTAssertEqual(item.suggestedFilename, "get-a-grip-training-all.csv")
        XCTAssertTrue(item.exportedContentTypes().contains(.commaSeparatedText))
        let data = try await item.exported(as: .commaSeparatedText)
        XCTAssertEqual(String(data: data, encoding: .utf8), csv)
        let text = try await item.exported(as: .utf8PlainText)
        XCTAssertEqual(String(data: text, encoding: .utf8), csv)
    }
}
