// SPDX-License-Identifier: MPL-2.0
// Original contributions Copyright 2026 Nuri Bruner.

import CoreGraphics
import CoreImage
import CoreImage.CIFilterBuiltins
import SwiftUI

/// A QR code drawn as a SCANNING SURFACE, not as chrome.
///
/// Two consequences, non-negotiable. **White platter, dark modules in BOTH schemes**: a
/// camera reads it, and an adaptive fill inverted in dark mode would not scan. And
/// `.interpolation(.none)`: smoothing rounds module edges into grey and costs the
/// decoder its contrast.
struct QRCodeView: View {
    let string: String
    /// The drawn code itself; the quiet zone is padding on top of this.
    var size: CGFloat = 200

    /// The quiet zone a QR needs to be found. Generous: the generator's own border is one
    /// module, and on the slate field a code with no margin reads as texture.
    private let quietZone: CGFloat = 16

    private var platter: RoundedRectangle {
        RoundedRectangle(cornerRadius: Metrics.radiusInner, style: .continuous)
    }

    var body: some View {
        code
            .frame(width: size, height: size)
            .padding(quietZone)
            .background(Color.white, in: platter)
            // A fixed dark hairline, not `Ink`: on a white platter an adaptive ink could
            // resolve near-white and the edge would vanish.
            .overlay { platter.strokeBorder(Color.black.opacity(0.10), lineWidth: 1) }
            .accessibilityElement()
            .accessibilityLabel(String(localized: "QR code for this routine"))
    }

    @ViewBuilder
    private var code: some View {
        if let image = QRMemo.shared.image(for: string) {
            Image(decorative: image, scale: 1)
                .resizable()
                .interpolation(.none)
                .aspectRatio(1, contentMode: .fit)
        } else {
            // Unexpected; a blank white square would read as a code that failed to
            // scan. Fixed ink, as above.
            Text("This code couldn't be drawn.")
                .font(.system(.footnote))
                .foregroundStyle(Color.black.opacity(0.55))
                .multilineTextAlignment(.center)
        }
    }
}

/// The CoreImage generation, memoised on its INPUT in one slot: a screen shows one code,
/// and the export card bakes the same string again.
///
/// A memo, not `@State` loaded in `.task`: `ImageRenderer` renders SYNCHRONOUSLY, so an
/// image arriving a runloop later would leave the exported platter empty. Reading it in
/// `body` costs one string comparison; the filter runs once per payload.
@MainActor
private final class QRMemo {
    static let shared = QRMemo()

    private var source: String?
    private var cached: CGImage?

    /// One `CIContext` for the memo's life: the context (a Metal device and caches) is the
    /// expensive object. Same lesson as `SlateTexture`.
    private static let context = CIContext()

    func image(for string: String) -> CGImage? {
        if source == string, cached != nil { return cached }
        // Failures are NOT memoised, so a transient CoreImage failure is retried.
        guard let image = Self.render(string) else { return nil }
        source = string
        cached = image
        return image
    }

    private static func render(_ string: String) -> CGImage? {
        let filter = CIFilter.qrCodeGenerator()
        filter.message = Data(string.utf8)
        // "M" — 15 % recovery. "L" is denser, but a code shared as a screenshot,
        // printed, or read at an angle needs the redundancy more.
        filter.correctionLevel = "M"
        guard let output = filter.outputImage else { return nil }
        // One PIXEL per module from the generator; 10× gives the nearest-neighbour
        // resample ten source pixels per module, so none lands more than a tenth off
        // its width. (The draw is a downscale; the ~1200 px memo is cheap.)
        let scaled = output.transformed(by: CGAffineTransform(scaleX: 10, y: 10))
        return context.createCGImage(scaled, from: scaled.extent)
    }
}

extension QRCodeView {
    /// Whether a code can be drawn for this payload — asked BEFORE baking the export PNG, so
    /// a card showing only the fallback error is never what `ShareLink` hands a friend.
    @MainActor
    static func canRender(_ string: String) -> Bool {
        QRMemo.shared.image(for: string) != nil
    }
}

#Preview {
    VStack(spacing: 24) {
        QRCodeView(string: "getagrip://routine#preview-payload")
        QRCodeView(string: "getagrip://routine#preview-payload", size: 120)
    }
    .padding()
    .background { AppBackground() }
}
