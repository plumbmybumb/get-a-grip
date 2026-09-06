// SPDX-License-Identifier: MPL-2.0
// Original contributions Copyright 2026 Nuri Bruner.

import CoreGraphics
import Foundation
import ImageIO
import XCTest
@testable import Doigt

@MainActor
final class InputResponsivenessTests: XCTestCase {
    func testDialAccessibilityKeepsTheNextStopWhenStartingBetweenDetents() {
        let values: [Double] = [10, 15, 20, 25, 30]
        XCTAssertEqual(DialTrack.adjustedIndex(value: 23, values: values, isUnset: false, increasing: true), 3)
        XCTAssertEqual(DialTrack.adjustedIndex(value: 22, values: values, isUnset: false, increasing: false), 2)
        XCTAssertEqual(DialTrack.adjustedIndex(value: 25, values: values, isUnset: false, increasing: true), 4)
        XCTAssertNil(DialTrack.adjustedIndex(value: 30, values: values, isUnset: false, increasing: true))
        XCTAssertNil(DialTrack.adjustedIndex(value: 10, values: values, isUnset: false, increasing: false))
        XCTAssertEqual(DialTrack.adjustedIndex(value: 23, values: values, isUnset: true, increasing: true), 0)
    }

    func testStepperScrollCannotRestartOrCommitWhenTheFingerReturns() {
        var press = RepeatingStepPressState()
        XCTAssertTrue(press.update(translation: .zero, enabled: true))
        XCTAssertFalse(press.update(translation: CGSize(width: 0, height: 12), enabled: true))
        XCTAssertFalse(press.update(translation: .zero, enabled: true))
        XCTAssertFalse(press.finish(translation: .zero, enabled: true))
        // The next touch remains a normal, responsive tap.
        XCTAssertTrue(press.update(translation: .zero, enabled: true))
        XCTAssertTrue(press.finish(translation: .zero, enabled: true))
    }

    func testRepeatingStepperDoesNotAddAnotherStepOnRelease() {
        var press = RepeatingStepPressState()
        XCTAssertTrue(press.update(translation: .zero, enabled: true))
        press.didRepeat = true
        XCTAssertFalse(press.finish(translation: .zero, enabled: true))
    }

    func testStepperCannotRestartAfterBecomingDisabledDuringATouch() {
        var press = RepeatingStepPressState()
        XCTAssertTrue(press.update(translation: .zero, enabled: true))
        XCTAssertFalse(press.update(translation: .zero, enabled: false))
        XCTAssertFalse(press.update(translation: .zero, enabled: true))
        XCTAssertFalse(press.finish(translation: .zero, enabled: true))
    }

    func testBackgroundPNGEncodingPreservesSizeAndTransparency() async throws {
        let context = try XCTUnwrap(CGContext(data: nil, width: 8, height: 6,
            bitsPerComponent: 8, bytesPerRow: 8 * 4,
            space: CGColorSpaceCreateDeviceRGB(),
            bitmapInfo: CGImageAlphaInfo.premultipliedLast.rawValue))
        context.clear(CGRect(x: 0, y: 0, width: 8, height: 6))
        context.setFillColor(CGColor(red: 1, green: 0, blue: 0, alpha: 0.5))
        context.fill(CGRect(x: 0, y: 0, width: 4, height: 6))
        let original = try XCTUnwrap(context.makeImage())
        let encoded = await ShareImageEncoder.shared.pngData(for: original)
        let data = try XCTUnwrap(encoded)
        let source = try XCTUnwrap(CGImageSourceCreateWithData(data as CFData, nil))
        let decoded = try XCTUnwrap(CGImageSourceCreateImageAtIndex(source, 0, nil))
        XCTAssertEqual(decoded.width, original.width)
        XCTAssertEqual(decoded.height, original.height)
        context.clear(CGRect(x: 0, y: 0, width: 8, height: 6))
        context.draw(decoded, in: CGRect(x: 0, y: 0, width: 8, height: 6))
        let pixels = try XCTUnwrap(context.data).assumingMemoryBound(to: UInt8.self)
        XCTAssertLessThanOrEqual(abs(Int(pixels[3]) - 128), 1)
        XCTAssertEqual(pixels[7 * 4 + 3], 0)
    }
}
