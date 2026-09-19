// SPDX-License-Identifier: MPL-2.0
// Original contributions Copyright 2026 Nuri Bruner.

import CoreImage
import CoreImage.CIFilterBuiltins
import SwiftUI

/// The slate mesh field — a cool textured-stone backdrop that gives Liquid Glass
/// something with tone to refract.
///
/// STATIC, and it should stay that way. Measured on Schengen Slice: with the drift
/// on, idle CPU sat at ~5% forever, and at 15fps it forced every glass surface to
/// re-blur its backdrop 15× a second — which is what made interaction feel choppy.
/// Glass needs a STABLE thing to sample. That matters even more here than there: a
/// workout screen is on for minutes at a time with a live graph already redrawing.
struct AppBackground: View {
    @Environment(\.colorScheme) private var scheme

    #if DEBUG
    /// DIAGNOSTIC: `-flatBackground` swaps this whole stack for one opaque fill.
    ///
    /// Every `.regularMaterial` card and every `.glassEffect` surface in the app blurs
    /// whatever is behind it, and behind them is FIVE layers — mesh, radial highlight,
    /// mottle bitmap, grain bitmap, vignette. On a Mac GPU that is free, which is why the
    /// simulator has never once shown the cost; on a phone it is paid per frame, per
    /// blurring surface, and it is the first thing to rule in or out when a screen scrolls
    /// badly on device. Add the argument to the scheme, run on the phone, and if the lag
    /// vanishes the answer is compositing rather than SwiftUI.
    private static let isFlat = ProcessInfo.processInfo.arguments.contains("-flatBackground")
    #endif

    var body: some View {
        #if DEBUG
        if Self.isFlat {
            (scheme == .dark ? Color(hex: "24292F") : Color(hex: "DDE1E7")).ignoresSafeArea()
        } else {
            layered
        }
        #else
        layered
        #endif
    }

    private var layered: some View {
        ZStack {
            MeshGradient(width: 3, height: 3, points: Self.meshPoints, colors: Self.meshColors(scheme))
                .overlay(
                    // Whisper of top-left light for depth. Very low opacity so its
                    // fade can't band into visible white rings.
                    RadialGradient(colors: [.white.opacity(0.06), .clear],
                                   center: .init(x: 0.28, y: 0.12), startRadius: 8, endRadius: 520)
                )
            // Broad tonal cloud first, then the crisp tooth on top of it.
            Image(uiImage: SlateTexture.mottle)
                .resizable()
                .scaledToFill()
                .allowsHitTesting(false)
            Image(uiImage: SlateTexture.grain)
                .resizable(resizingMode: .tile)
                .allowsHitTesting(false)
            // A lit surface, not a flat fill: the corners fall off very slightly,
            // which separates "premium material" from "grey rectangle". Also dithers
            // the mesh, killing the banding OLEDs show on big flat washes.
            RadialGradient(colors: [.clear, .black.opacity(scheme == .dark ? 0.20 : 0.085)],
                           center: .center, startRadius: 180, endRadius: 760)
                .allowsHitTesting(false)
        }
        .clipped()
        // ONE full-bleed escape for the whole background stack — per-child
        // ignoresSafeArea inside the ZStack disturbs sibling layout (the screen title
        // creeps under the status bar).
        .ignoresSafeArea()
        .id(scheme)
    }

    /// Corners pinned, edge-mids and centre offset slightly so the colour blobs
    /// aren't a symmetric grid. Fixed values — nothing animates.
    private static let meshPoints: [SIMD2<Float>] = [
        SIMD2(0, 0),    SIMD2(0.53, 0.0),  SIMD2(1, 0),
        SIMD2(0.0, 0.47), SIMD2(0.52, 0.54), SIMD2(1.0, 0.51),
        SIMD2(0, 1),    SIMD2(0.48, 1.0),  SIMD2(1, 1),
    ]

    /// The 9-colour grid for the 3×3 mesh — slate tones, light at the top settling
    /// darker toward the bottom, with a faint cool-blue undertone so the gray never
    /// reads as dead concrete.
    private static func meshColors(_ scheme: ColorScheme) -> [Color] {
        if scheme == .dark {
            let topEdge = Color(hex: "2C323B")
            let topMid  = Color(hex: "343A45")
            let midEdge = Color(hex: "242932")
            let midMid  = Color(hex: "2A303A")
            let botEdge = Color(hex: "191D24")
            let botMid  = Color(hex: "1F242C")
            return [topEdge, topMid, topEdge, midEdge, midMid, midEdge, botEdge, botMid, botEdge]
        }
        let topEdge = Color(hex: "E3E6EB")
        let topMid  = Color(hex: "EBEDF0")
        let midEdge = Color(hex: "D2D7DE")
        let midMid  = Color(hex: "DADEE4")
        let botEdge = Color(hex: "C3C9D2")
        let botMid  = Color(hex: "CBD1D9")
        return [topEdge, topMid, topEdge, midEdge, midMid, midEdge, botEdge, botMid, botEdge]
    }
}

/// Paper-slate texture: a FINE tooth layer over a soft blurred mottle — reads as
/// pressed paper/stone rather than the dusty speckle a single white-noise pass gives.
/// Generated once with CoreImage; the grain TILES (sharp at any size, ~256KB), the
/// mottle is a stretched sheet (blur can't tile without seaming).
///
/// **Nothing here force-unwraps, and the fallback is an EMPTY image.** These are lazy
/// `static let`s, so the render runs inside whichever screen happens to touch the
/// background first — and CoreImage can legitimately return nothing under memory
/// pressure. A trap there would take the app down while drawing a texture; an empty
/// `UIImage` simply omits the layer, leaving the mesh, the highlight and the vignette,
/// which is a background nobody would notice was one short.
@MainActor
private enum SlateTexture {
    /// 1 texel = 1 DEVICE PIXEL, so fine grain actually reads as fine rather than as
    /// a slightly blurred picture of a texture.
    private static var pixelScale: CGFloat { max(2, UITraitCollection.current.displayScale) }

    private static func gray(_ image: CIImage, alpha: CGFloat) -> CIImage? {
        let mono = CIFilter.colorControls()
        mono.inputImage = image
        mono.saturation = 0
        let fade = CIFilter.colorMatrix()
        fade.inputImage = mono.outputImage
        fade.aVector = CIVector(x: 0, y: 0, z: 0, w: alpha)
        return fade.outputImage
    }

    static let grain: UIImage = {
        let side: CGFloat = 256
        let rect = CGRect(x: 0, y: 0, width: side, height: side)
        guard let noise = CIFilter.randomGenerator().outputImage?.cropped(to: rect) else {
            return UIImage()
        }
        // Two octaves: a sharp pass plus a half-pixel-blurred one. Pure white noise
        // alone reads as digital speckle; the softer companion gives it the slight
        // irregularity of a pressed surface.
        let fine = gray(noise, alpha: 0.055)
        let soft = gray(noise.applyingGaussianBlur(sigma: 0.8).cropped(to: rect), alpha: 0.05)
        let composite = CIFilter.sourceOverCompositing()
        composite.inputImage = fine
        composite.backgroundImage = soft
        guard let blended = composite.outputImage?.cropped(to: rect),
              let cg = CIContext().createCGImage(blended, from: rect) else { return UIImage() }
        return UIImage(cgImage: cg, scale: pixelScale, orientation: .up)
    }()

    static let mottle: UIImage = {
        let rect = CGRect(x: 0, y: 0, width: 320, height: 660)
        guard let noise = CIFilter.randomGenerator().outputImage else { return UIImage() }
        let clouds = gray(
            noise
                .transformed(by: CGAffineTransform(scaleX: 14, y: 14))
                .cropped(to: rect.insetBy(dx: -70, dy: -70))
                .applyingGaussianBlur(sigma: 18)
                .cropped(to: rect),
            alpha: 0.075
        )
        guard let clouds, let cg = CIContext().createCGImage(clouds, from: rect) else {
            return UIImage()
        }
        return UIImage(cgImage: cg, scale: 1, orientation: .up)
    }()
}
