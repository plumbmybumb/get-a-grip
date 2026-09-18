// SPDX-License-Identifier: MPL-2.0
// Original contributions Copyright 2026 Nuri Bruner.

import CoreGraphics
import CoreImage
import CoreImage.CIFilterBuiltins
import SwiftUI

/// A QR code drawn as a SCANNING SURFACE, not as chrome.
///
/// Two things follow from that and neither is negotiable. **The platter is white and the
/// modules are dark in BOTH colour schemes** — a code is read by a camera, and contrast
/// is the whole feature; an adaptive fill that inverted in dark mode would hand someone
/// a picture that no longer scans. And the image is drawn with `.interpolation(.none)`,
/// because a QR is a bitmap of hard squares: any smoothing rounds module edges into grey
/// and costs a decoder the very contrast it is looking for.
struct QRCodeView: View {
    let string: String
    /// The drawn code itself; the quiet zone is padding on top of this.
    var size: CGFloat = 200

    /// The white margin every QR needs to be found at all. Generous rather than minimal:
    /// the generator's own border is one module, and this platter sits on the app's slate
    /// field where a code with no margin reads as an edge-to-edge texture.
    private let quietZone: CGFloat = 16

    private var platter: RoundedRectangle {
        RoundedRectangle(cornerRadius: Metrics.radiusInner, style: .continuous)
    }

    var body: some View {
        code
            .frame(width: size, height: size)
            .padding(quietZone)
            .background(Color.white, in: platter)
            // A fixed dark hairline, not `Ink`: the platter is white in both schemes, so
            // an adaptive ink would resolve near-white on white and the edge would vanish
            // exactly where the surface needs one.
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
            // Generation failing is not expected, and a blank white square would read as
            // a code that simply did not scan. Fixed ink for the same reason as the
            // hairline above.
            Text("This code couldn't be drawn.")
                .font(.system(.footnote))
                .foregroundStyle(Color.black.opacity(0.55))
                .multilineTextAlignment(.center)
        }
    }
}

/// The CoreImage generation, memoised on its INPUT — one slot, because a screen only ever
/// shows one code and the export card bakes that same string a second time.
///
/// It is a memo rather than `@State` loaded in `.task`, deliberately: `ImageRenderer`
/// renders a view tree SYNCHRONOUSLY, so an image that arrives a runloop turn later has
/// not arrived when the PNG is baked and the exported card would carry an empty platter.
/// Reading it from `body` costs one string comparison per evaluation; the filter itself
/// runs once per payload.
@MainActor
private final class QRMemo {
    static let shared = QRMemo()

    private var source: String?
    private var cached: CGImage?

    /// One `CIContext` for the memo's life, not one per render — the context is the
    /// expensive object (a Metal device and its caches), the filter is cheap. Same
    /// lesson as `SlateTexture`.
    private static let context = CIContext()

    func image(for string: String) -> CGImage? {
        if source == string, cached != nil { return cached }
        // A failed render is NOT memoised: `source` stays unset, so the next ask tries
        // again instead of a transient CoreImage failure being remembered forever.
        guard let image = Self.render(string) else { return nil }
        source = string
        cached = image
        return image
    }

    private static func render(_ string: String) -> CGImage? {
        let filter = CIFilter.qrCodeGenerator()
        filter.message = Data(string.utf8)
        // "M" — 15 % recovery. "L" would fit a longer payload into fewer modules, but a
        // code that gets shared as a screenshot, printed, or read across a room at an
        // angle needs the redundancy more than it needs the density.
        filter.correctionLevel = "M"
        guard let output = filter.outputImage else { return nil }
        // The generator emits one PIXEL per module; a 10× blow-up gives the downstream
        // nearest-neighbour resample ten source pixels per module to choose from, so no
        // module can land more than a tenth off its true width. (The drawn sizes are
        // smaller than this bitmap, so the resample is a downscale — the cost is one
        // ~1200 px square held by the memo, cheap next to redrawing wrong.)
        let scaled = output.transformed(by: CGAffineTransform(scaleX: 10, y: 10))
        return context.createCGImage(scaled, from: scaled.extent)
    }
}

extension QRCodeView {
    /// Whether a code can actually be drawn for this payload — the share sheet asks
    /// BEFORE baking the export PNG, because a failed generation renders the fallback
    /// sentence and a card whose only content is an error message must never be what
    /// `ShareLink` hands a friend.
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
