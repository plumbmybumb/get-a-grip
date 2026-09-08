// SPDX-License-Identifier: MPL-2.0
// Original contributions Copyright 2026 Nuri Bruner.

import SwiftUI
import XCTest
@testable import Doigt

@MainActor
final class ActionLayoutTests: XCTestCase {
    /// Inspect rendered glyphs, not just a container's reported bounds: the original
    /// capsules had perfectly valid frames while their words touched both edges.
    func testFrenchActionInkKeepsItsInsetsAtStandardAndLargeSizes() throws {
        for size: DynamicTypeSize in [.large, .xxxLarge, .accessibility3] {
            for title in ["Passer le tirage", "Passer la série", "Tenir pour finir", "Continuer à tenir…"] {
                for width: CGFloat in [112, 172, 335] {
                    let image = try render(
                        Text(title)
                            .font(.system(.subheadline, weight: .semibold))
                            .foregroundStyle(.black)
                            .actionLabelLayout(fullWidth: true)
                            .background(.white)
                            .frame(width: width)
                            .environment(\.dynamicTypeSize, size))
                    let ink = try darkInkBounds(image)
                    let context = "\(title), \(size), \(width) pt"
                    XCTAssertGreaterThanOrEqual(ink.minX, Metrics.buttonHorizontalPadding - 1, context)
                    XCTAssertLessThanOrEqual(ink.maxX, width - Metrics.buttonHorizontalPadding + 1, context)
                    XCTAssertGreaterThanOrEqual(ink.minY, Metrics.buttonVerticalPadding - 1, context)
                    XCTAssertLessThanOrEqual(ink.maxY, CGFloat(image.height) - Metrics.buttonVerticalPadding + 1, context)
                    XCTAssertGreaterThanOrEqual(image.height, Int(Metrics.compactButtonHeight), context)
                }
            }
        }
    }

    func testEnglishActionsRemainOneRowOnANarrowPhone() throws {
        let image = try render(actionRow(titles: ["Skip pull", "Skip set", "Keep holding…"], size: .large))
        XCTAssertLessThanOrEqual(image.height, 72, "Preserve graph space when padded labels fit in two lines")
    }

    func testFrenchAccessibilityActionsReflowInsteadOfCrushingTheirLabels() throws {
        let standard = try render(actionRow(titles: ["Passer le tirage", "Passer la série", "Continuer à tenir…"], size: .large))
        let enlarged = try render(actionRow(titles: ["Passer le tirage", "Passer la série", "Continuer à tenir…"], size: .accessibility3))
        XCTAssertGreaterThan(enlarged.height, standard.height)
        XCTAssertLessThanOrEqual(enlarged.width, 335)
    }

    func testWrappedAndSingleLineActionsPaintEqualHeightCapsulesInTheirRow() throws {
        let image = try render(
            AdaptiveActionRow(maximumRowHeight: 200) {
                ForEach(["Passer le tirage", "Pause"], id: \.self) { title in
                    Text(title)
                        .font(.system(.subheadline, weight: .semibold))
                        .foregroundStyle(.black)
                        .actionLabelLayout(fullWidth: true, fillsRowHeight: true)
                        .background(.red, in: .capsule)
                }
            }
            .frame(width: 335)
            .environment(\.dynamicTypeSize, .xxxLarge))
        let left = try redSurfaceBounds(image, xRange: 0..<(image.width / 2))
        let right = try redSurfaceBounds(image, xRange: (image.width / 2)..<image.width)
        XCTAssertEqual(left.minY, right.minY)
        XCTAssertEqual(left.maxY, right.maxY, "The background must fill the row, not just an invisible outer frame")
        XCTAssertGreaterThan(image.height, 48, "Exercise a genuinely wrapped label")
        XCTAssertLessThanOrEqual(image.height, 100, "Both differently wrapped labels must share one row")
    }

    func testAccessibilityGripInkStaysAboveTheNameRowIncludingThumbAndEmphasis() throws {
        for fingers: FingerSet in [.four, [.four, .thumb]] {
            for emphasized in [false, true] {
                let image = try render(
                    VStack(spacing: 6) {
                        RunnerGripGlyph(grip: GripSpec(fingers: fingers), emphasized: emphasized)
                            .colorMultiply(.black)
                        Rectangle().fill(.red).frame(height: 4)
                    }
                    .frame(width: 335)
                    .background(.white)
                    .environment(\.dynamicTypeSize, .accessibility3))
                let glyph = try darkInkBounds(image)
                let nextRow = try redSurfaceBounds(image, xRange: 0..<image.width)
                // Core Graphics bitmap rows may be bottom-up. Compare the two
                // non-overlapping ink ranges rather than assuming raster direction.
                let separation = max(nextRow.minY - glyph.maxY, glyph.minY - nextRow.maxY)
                XCTAssertGreaterThanOrEqual(separation, 5, "Scaled glyph ink must not reach the next row")
            }
        }
    }

    private func actionRow(titles: [String], size: DynamicTypeSize) -> some View {
        AdaptiveActionRow {
            ForEach(titles, id: \.self) { title in
                Text(title)
                    .font(.system(.subheadline, weight: .semibold))
                    .actionLabelLayout(fullWidth: true, fillsRowHeight: true)
                    .background(.white)
            }
        }
        .frame(width: 335)
        .environment(\.dynamicTypeSize, size)
    }

    private func render<V: View>(_ view: V) throws -> CGImage {
        let renderer = ImageRenderer(content: view)
        renderer.scale = 1
        return try XCTUnwrap(renderer.cgImage)
    }

    private func darkInkBounds(_ image: CGImage) throws -> CGRect {
        try pixelBounds(image, xRange: 0..<image.width) { red, green, blue, alpha in
            red < 96 && green < 96 && blue < 96 && alpha > 128
        }
    }

    private func redSurfaceBounds(_ image: CGImage, xRange: Range<Int>) throws -> CGRect {
        try pixelBounds(image, xRange: xRange) { red, green, blue, alpha in
            red > 200 && green < 80 && blue < 80 && alpha > 128
        }
    }

    private func pixelBounds(_ image: CGImage, xRange: Range<Int>,
                             matches: (UInt8, UInt8, UInt8, UInt8) -> Bool) throws -> CGRect {
        let width = image.width
        let height = image.height
        var pixels = [UInt8](repeating: 255, count: width * height * 4)
        let context = try XCTUnwrap(CGContext(data: &pixels, width: width, height: height,
            bitsPerComponent: 8, bytesPerRow: width * 4, space: CGColorSpaceCreateDeviceRGB(),
            bitmapInfo: CGImageAlphaInfo.premultipliedLast.rawValue))
        context.draw(image, in: CGRect(x: 0, y: 0, width: width, height: height))
        var minX = width, minY = height, maxX = -1, maxY = -1
        for y in 0..<height {
            for x in xRange {
                let offset = (y * width + x) * 4
                if matches(pixels[offset], pixels[offset + 1], pixels[offset + 2], pixels[offset + 3]) {
                    minX = min(minX, x); maxX = max(maxX, x)
                    minY = min(minY, y); maxY = max(maxY, y)
                }
            }
        }
        XCTAssertGreaterThanOrEqual(maxX, 0, "The expected pixels must render")
        return CGRect(x: minX, y: minY, width: maxX - minX + 1, height: maxY - minY + 1)
    }
}
