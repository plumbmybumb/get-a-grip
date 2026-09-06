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
