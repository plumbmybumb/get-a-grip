// SPDX-License-Identifier: MPL-2.0
// Original contributions Copyright 2026 Nuri Bruner.

import CoreGraphics
import Foundation
import ImageIO
import UniformTypeIdentifiers

/// SwiftUI draws an export on the main actor; lossless compression does not need it.
/// Serial encoding also bounds memory while someone quickly changes preview options.
actor ShareImageEncoder {
    static let shared = ShareImageEncoder()

    func pngData(for image: CGImage) -> Data? {
        guard !Task.isCancelled else { return nil }
        let data = NSMutableData()
        guard let destination = CGImageDestinationCreateWithData(
            data, UTType.png.identifier as CFString, 1, nil) else { return nil }
        CGImageDestinationAddImage(destination, image, nil)
        guard CGImageDestinationFinalize(destination), !Task.isCancelled else { return nil }
        return data as Data
    }
}

/// WHEN a share card is baked, which matters as much as where the PNG is encoded.
///
/// `ImageRenderer` has to run on the main actor, and a 1080 px card at 3× is tens of
/// milliseconds of layout and rasterising. Run from `.task` at presentation, that landed
/// inside the sheet's own slide-up and hitched it; run on every style tap, it hitched the
/// chip. Nothing needs the PNG in the first few hundred milliseconds — the preview above
/// the buttons is the live view, not the image — so the first bake waits for the sheet to
/// settle and option changes are debounced, letting a run of taps cost one render.
enum ShareRenderTiming {
    /// Long enough for the sheet's presentation to finish before the main actor stalls.
    static let afterPresentation: Duration = .milliseconds(350)
    /// A second tap inside this window replaces the first one's render rather than
    /// queueing behind it.
    static let afterOptionChange: Duration = .milliseconds(180)

    /// Sleeps for `delay`; false when the task was cancelled meanwhile, which is how a
    /// newer selection (or the sheet closing) takes the render away.
    static func wait(_ delay: Duration) async -> Bool {
        try? await Task.sleep(for: delay)
        return !Task.isCancelled
    }
}
