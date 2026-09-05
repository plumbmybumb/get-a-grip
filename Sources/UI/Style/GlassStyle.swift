// SPDX-License-Identifier: MPL-2.0
// Original contributions Copyright 2026 Nuri Bruner.

import CoreImage
import CoreImage.CIFilterBuiltins
import SwiftUI

// MARK: - Glass styling (app-only)
//
// Ported from Schengen Slice (itself ported from Number Bubble) so the apps read as
// siblings. Chrome (buttons, fields, pills) uses Apple's real Liquid Glass
// (`.glassEffect`, `.glass` / `.glassProminent`) on iOS 26 — subtle and refractive,
// not a custom "shiny" gradient. The background stays a custom content layer so the
// glass has soft, shifting colour to refract. Widget-safe tokens (Ink, StatusTint,
// Metrics, Color helpers) live in Shared/DesignTokens.swift — never redefined here.

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
@MainActor
private enum SlateTexture {
    /// 1 texel = 1 DEVICE PIXEL, so fine grain actually reads as fine rather than as
    /// a slightly blurred picture of a texture.
    private static var pixelScale: CGFloat { max(2, UITraitCollection.current.displayScale) }

    private static func gray(_ image: CIImage, alpha: CGFloat) -> CIImage {
        let mono = CIFilter.colorControls()
        mono.inputImage = image
        mono.saturation = 0
        let fade = CIFilter.colorMatrix()
        fade.inputImage = mono.outputImage
        fade.aVector = CIVector(x: 0, y: 0, z: 0, w: alpha)
        return fade.outputImage!
    }

    static let grain: UIImage = {
        let side: CGFloat = 256
        let rect = CGRect(x: 0, y: 0, width: side, height: side)
        let noise = CIFilter.randomGenerator().outputImage!.cropped(to: rect)
        // Two octaves: a sharp pass plus a half-pixel-blurred one. Pure white noise
        // alone reads as digital speckle; the softer companion gives it the slight
        // irregularity of a pressed surface.
        let fine = gray(noise, alpha: 0.055)
        let soft = gray(noise.applyingGaussianBlur(sigma: 0.8).cropped(to: rect), alpha: 0.05)
        let composite = CIFilter.sourceOverCompositing()
        composite.inputImage = fine
        composite.backgroundImage = soft
        let cg = CIContext().createCGImage(composite.outputImage!.cropped(to: rect), from: rect)!
        return UIImage(cgImage: cg, scale: pixelScale, orientation: .up)
    }()

    static let mottle: UIImage = {
        let rect = CGRect(x: 0, y: 0, width: 320, height: 660)
        let noise = CIFilter.randomGenerator().outputImage!
        let clouds = gray(
            noise
                .transformed(by: CGAffineTransform(scaleX: 14, y: 14))
                .cropped(to: rect.insetBy(dx: -70, dy: -70))
                .applyingGaussianBlur(sigma: 18)
                .cropped(to: rect),
            alpha: 0.075
        )
        let cg = CIContext().createCGImage(clouds, from: rect)!
        return UIImage(cgImage: cg, scale: 1, orientation: .up)
    }()
}

// MARK: - Glass tint tokens

/// Semantic colour for a glass control: the Liquid Glass tint hue (nil = clear) and
/// a foreground colour that stays legible over translucent glass.
struct GlassTint {
    var glass: Color?
    var text: Color

    static let neutral = GlassTint(glass: nil, text: Ink.primary)
    /// The default for chrome: ink, not a colour.
    static let graphite = GlassTint(glass: Accent.graphiteFlat,
                                    text: .adaptive(Color(hex: "23272E"), Color(hex: "EDF1F6")))
    /// Live force / connected device.
    static let bleu = GlassTint(glass: Accent.bleuFlat,
                                text: .adaptive(Color(hex: "10508F"), Color(hex: "9FCEFA")))
    /// Attention: dropout, disconnect, destructive.
    static let alarm = GlassTint(glass: Accent.alarmFlat,
                                 text: .adaptive(Color(hex: "8E1B1B"), Color(hex: "FFA79E")))
    /// Armed / waiting.
    static let armed = GlassTint(glass: Color(hex: "FF9800"),
                                 text: .adaptive(Color(hex: "BF360C"), Color(hex: "FFB37A")))

    /// The Liquid Glass value: tinted + interactive (press illumination/scale).
    var style: Glass {
        if let c = glass { return .regular.tint(c.opacity(0.55)).interactive() }
        return .regular.interactive()
    }
}

// MARK: - Accessible glass

/// Liquid Glass that honours Reduce Transparency.
///
/// With the setting on, glass has to become genuinely opaque, not merely thicker —
/// legibility over a busy backdrop drops below WCAG AA otherwise.
struct AccessibleGlass<S: Shape>: ViewModifier {
    var shape: S
    var tint: Color?
    @Environment(\.accessibilityReduceTransparency) private var reduceTransparency

    func body(content: Content) -> some View {
        if reduceTransparency {
            content
                .background(Color(uiColor: .secondarySystemBackground), in: shape)
                .overlay(shape.stroke(Ink.tertiary.opacity(0.45), lineWidth: 1))
        } else if let tint {
            content.glassEffect(.regular.tint(tint), in: shape)
        } else {
            content.glassEffect(.regular, in: shape)
        }
    }
}

extension View {
    func accessibleGlass(_ tint: Color? = nil, in shape: some Shape) -> some View {
        modifier(AccessibleGlass(shape: shape, tint: tint))
    }
}

// MARK: - Buttons

/// A glass pill button used for floating chrome.
///
/// The glass lives INSIDE the button label so the press feedback scales the whole
/// pill and the full capsule is the hit target. Wrapping `.glassEffect` AROUND a
/// container that holds the Button instead swallows its touches.
struct GlassPillButton<Label: View>: View {
    var tint: GlassTint = .neutral
    let action: () -> Void
    @ViewBuilder var label: () -> Label

    var body: some View {
        Button(action: action) {
            label()
                .foregroundStyle(tint.text)
                .padding(.horizontal, 16).padding(.vertical, 12)
                .glassEffect(tint.style, in: .capsule)
                .contentShape(.capsule)
        }
        .buttonStyle(PressFeedbackButtonStyle())
    }
}

/// Instant touch-down acknowledgment: sheets take a beat to present, and without
/// immediate visual response the tap feels dropped.
struct PressFeedbackButtonStyle: ButtonStyle {
    /// **Off for row-sized cards.** A card that scales on press drags its own material
    /// backdrop out from under it — which is why those rows used `.plain` and so had NO
    /// touch-down feedback at all. Opacity alone solves the backdrop problem without
    /// throwing away the acknowledgement (audit, 2026-08-11).
    var scales: Bool = true

    @Environment(\.accessibilityReduceMotion) private var reduceMotion

    func makeBody(configuration: Configuration) -> some View {
        configuration.label
            .scaleEffect(configuration.isPressed && !reduceMotion && scales ? 0.94 : 1)
            .opacity(configuration.isPressed ? 0.85 : 1)
            // Acknowledge the press in the first rendered frame. Only release settles;
            // an 80 ms ramp spends several frames catching up with a short tap.
            .animation(configuration.isPressed
                        ? nil
                        : Motion.state(reduceMotion),
                       value: configuration.isPressed)
    }
}

extension View {
    /// Glass capsule field chrome: wrap a field's content (icon + TextField, a value
    /// label, …). Content supplies its own font/ink; this owns padding, the house
    /// field height, and the capsule.
    func glassFieldChrome() -> some View {
        self
            .padding(.horizontal, 18)
            .frame(height: Metrics.fieldHeight)
            .glassEffect(.regular, in: .capsule)
    }
}

/// Filled primary action: full-width prominent Liquid Glass, tinted.
struct PrimaryGlassButton: View {
    var title: String
    var systemImage: String? = nil
    var tint: Color = Accent.graphite
    var action: () -> Void

    @ScaledMetric(relativeTo: .headline) private var textSize: CGFloat = 19

    /// EXPLICIT, because the tint inverts with the colour scheme and the system's own
    /// label choice does not follow it. `Accent.graphite` is near-black in light mode
    /// and near-WHITE in dark, so `.glassProminent` rendered a white pill with white
    /// text — the single button the whole ritual hangs off, unreadable in dark mode.
    /// The label is simply the opposite: white on the dark fill, near-black on the light one.
    private var labelColor: Color {
        .adaptive(Color(hex: "FFFFFF"), Color(hex: "1B1F25"))
    }

    var body: some View {
        Button(action: action) {
            HStack(spacing: 8) {
                if let systemImage { Image(systemName: systemImage) }
                Text(title)
            }
            .font(.system(size: textSize, weight: .semibold))
            .foregroundStyle(labelColor)
            // A HARD height clips the label instead of the button growing: "Start
            // second session" runs past one line at accessibility sizes, and the one
            // control the whole ritual hangs off must never render as a cut-off word.
            // Short titles still measure exactly Metrics.buttonHeight.
            .lineLimit(2)
            .minimumScaleFactor(0.85)
            .multilineTextAlignment(.center)
            .frame(maxWidth: .infinity)
            .frame(minHeight: Metrics.buttonHeight)
        }
        .buttonStyle(.glassProminent)
        .tint(tint)
    }
}

/// **Solid twins of the two glass buttons, for the one subtree the system lifts.**
///
/// Liquid Glass renders in its own compositing pass and does not honor the hide the
/// context-menu lift performs on its source: hold the routine card down and every
/// in-process layer vanished on cue while the glass button stayed floating alone over
/// the transition (Nuri's device, 2026-08-18) — the sloppiest thing an interaction can
/// look like, and unreproducible in the Simulator, which composites glass differently.
/// So a view that carries `.contextMenu` must be built from NOTHING but in-process
/// layers: same label rules, same metrics, plain fills. Over the static slate field
/// the glass buttons read as their tint anyway; side by side the twins are near
/// indistinguishable at rest and differ only in the refraction nobody sees on a card
/// they are long-pressing.
struct SolidPrimaryButton: View {
    var title: String
    var systemImage: String? = nil
    var tint: Color = Accent.graphite
    var action: () -> Void

    @ScaledMetric(relativeTo: .headline) private var textSize: CGFloat = 19

    /// Same rule as `PrimaryGlassButton`: the tint inverts with the scheme, so the
    /// label is explicitly the opposite ink.
    private var labelColor: Color {
        .adaptive(Color(hex: "FFFFFF"), Color(hex: "1B1F25"))
    }

    var body: some View {
        Button(action: action) {
            HStack(spacing: 8) {
                if let systemImage { Image(systemName: systemImage) }
                Text(title)
            }
            .font(.system(size: textSize, weight: .semibold))
            .foregroundStyle(labelColor)
            .lineLimit(2)
            .minimumScaleFactor(0.85)
            .multilineTextAlignment(.center)
            .frame(maxWidth: .infinity)
            .frame(minHeight: Metrics.buttonHeight)
            .background(tint, in: .capsule)
            .contentShape(.capsule)
        }
        .buttonStyle(PressFeedbackButtonStyle())
    }
}

/// The secondary twin: `SecondaryGlassButton`'s geometry on a soft solid fill.
struct SolidSecondaryButton: View {
    var title: String
    var systemImage: String? = nil
    var action: () -> Void

    @ScaledMetric(relativeTo: .headline) private var textSize: CGFloat = 17

    var body: some View {
        Button(action: action) {
            HStack(spacing: 6) {
                if let systemImage { Image(systemName: systemImage) }
                Text(title)
            }
            .font(.system(size: textSize, weight: .semibold))
            .foregroundStyle(Ink.primary)
            .frame(height: Metrics.fieldHeight)
            .padding(.horizontal, 22)
            .background(Color.adaptive(Color(hex: "FFFFFF"), Color(hex: "343A44")),
                        in: .capsule)
            .contentShape(.capsule)
        }
        .buttonStyle(PressFeedbackButtonStyle())
    }
}

/// Clear secondary action: a glass capsule that hugs its label.
struct SecondaryGlassButton: View {
    var title: String
    var systemImage: String? = nil
    var tint: Color? = nil
    var action: () -> Void

    @ScaledMetric(relativeTo: .headline) private var textSize: CGFloat = 17

    var body: some View {
        Button(action: action) {
            HStack(spacing: 6) {
                if let systemImage { Image(systemName: systemImage) }
                Text(title)
            }
            .font(.system(size: textSize, weight: .semibold))
            .frame(height: Metrics.fieldHeight)
            .padding(.horizontal, 22)
        }
        .buttonStyle(.glass)
        .tint(tint)
    }
}
