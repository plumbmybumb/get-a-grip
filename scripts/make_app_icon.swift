// SPDX-License-Identifier: MPL-2.0
// Original contributions Copyright 2026 Nuri Bruner.

// Generates the 1024×1024 iOS app icon: FOUR FINGERTIPS ON AN EDGE.
//
// The concept is the app's own iconography scaled up. `FingerGlyph` — four bars whose
// corners close as the grip closes — already carries meaning on every set row, every
// history chip and the summary screen, so the icon is not a separate piece of art to
// keep in sync: it is the same mark, and a climber reads it as fingers on a rung before
// reading any word.
//
// The single accent is the EDGE, in Bleu de France, because that is where the force the
// app measures actually passes. The fingers are graphite, the app's interactive ink.
// One accent element, as the house palette requires.
//
//   swift scripts/make_app_icon.swift <out.png> [any|dark|tinted]
//
// iOS icons must be 1024×1024 and FULLY OPAQUE (no alpha); iOS applies its own corner
// mask and, on iOS 26, its own Liquid Glass treatment — which is why the drawing stays
// FLAT: the system supplies the depth, and baked-in shadows fight it.
import AppKit
import CoreGraphics
import ImageIO

let args = CommandLine.arguments
guard (2...3).contains(args.count) else {
    fputs("usage: make_app_icon.swift <out.png> [any|dark|tinted]\n", stderr)
    exit(64)
}
let outPath = args[1]
let style = args.count >= 3 ? args[2] : "any"
guard ["any", "dark", "tinted"].contains(style) else {
    fputs("Unknown icon style: \(style). Use any, dark or tinted.\n", stderr)
    exit(64)
}
let S = 1024
let side = CGFloat(S)

let cs = CGColorSpaceCreateDeviceRGB()
func col(_ r: CGFloat, _ g: CGFloat, _ b: CGFloat, _ a: CGFloat = 1) -> CGColor {
    CGColor(colorSpace: cs, components: [r / 255, g / 255, b / 255, a])!
}

guard let ctx = CGContext(data: nil, width: S, height: S, bitsPerComponent: 8,
                          bytesPerRow: 0, space: cs,
                          bitmapInfo: CGImageAlphaInfo.noneSkipLast.rawValue) else {
    fatalError("could not create context")
}

/// "tinted" must read as pure grayscale luminance — the system multiplies the user's
/// own accent colour over it, so any hue baked in here fights whatever they picked.
struct Palette {
    let bgTop: CGColor, bgBot: CGColor, finger: CGColor, edge: CGColor
}
let palette: Palette
switch style {
case "dark":
    palette = Palette(bgTop: col(44, 50, 59), bgBot: col(25, 29, 36),
                      finger: col(231, 235, 241),      // Accent.graphite, dark variant
                      edge: col(90, 169, 240))         // Accent.bleu, dark variant
case "tinted":
    palette = Palette(bgTop: col(29, 29, 29), bgBot: col(16, 16, 16),
                      finger: col(239, 239, 239),
                      edge: col(159, 159, 159))
default:
    palette = Palette(bgTop: col(227, 230, 235), bgBot: col(195, 201, 210),
                      finger: col(43, 48, 56),         // Accent.graphite
                      edge: col(49, 140, 231))         // Accent.bleu (Bleu de France)
}

// The slate field, flat and vertical — the same tonal move as `AppBackground`, with none
// of its texture. Grain that reads as "pressed paper" at screen size turns to noise at
// 60pt and to mud at 29.
let bg = CGGradient(colorsSpace: cs, colors: [palette.bgTop, palette.bgBot] as CFArray,
                    locations: [0, 1])!
ctx.drawLinearGradient(bg, start: CGPoint(x: 0, y: side), end: CGPoint(x: 0, y: 0),
                       options: [])

func roundedBar(_ rect: CGRect, radius: CGFloat, _ color: CGColor) {
    let path = CGPath(roundedRect: rect, cornerWidth: radius, cornerHeight: radius,
                      transform: nil)
    ctx.addPath(path)
    ctx.setFillColor(color)
    ctx.fillPath()
}

// THE EDGE. Wider than the hand that sits on it, so it reads as a rung the fingers are
// on rather than a floor they stand on.
let edgeHeight: CGFloat = 96
let edgeWidth: CGFloat = 858
let edgeY: CGFloat = 302
roundedBar(CGRect(x: (side - edgeWidth) / 2, y: edgeY, width: edgeWidth, height: edgeHeight),
           radius: edgeHeight / 2, palette.edge)

// THE FINGERS. Heights follow a real hand — index shorter than middle, ring between,
// little shortest — because four equal bars read as a barcode, and the uneven silhouette
// is what makes it a HAND at a glance.
let fingerWidth: CGFloat = 148
// Gap chosen against the SMALL sizes, not the 1024 render: at 60pt on the home screen
// this is about 2.7px of daylight, which is the difference between four fingers and one
// striped block. Narrower looks tidier at full size and disappears where it matters.
let gap: CGFloat = 46
let heights: [CGFloat] = [286, 352, 322, 244]   // index, middle, ring, little
let handWidth = fingerWidth * 4 + gap * 3
var x = (side - handWidth) / 2
let fingerBottom = edgeY + edgeHeight - 20      // tucked BEHIND the edge, not resting on it

for height in heights {
    roundedBar(CGRect(x: x, y: fingerBottom, width: fingerWidth, height: height),
               radius: fingerWidth / 2, palette.finger)
    x += fingerWidth + gap
}

// Redraw the edge over the finger roots: the overlap is what makes the fingers read as
// gripping the rung rather than balancing on top of it.
roundedBar(CGRect(x: (side - edgeWidth) / 2, y: edgeY, width: edgeWidth, height: edgeHeight),
           radius: edgeHeight / 2, palette.edge)

guard let image = ctx.makeImage() else { fatalError("could not render") }
let url = URL(fileURLWithPath: outPath)
guard let dest = CGImageDestinationCreateWithURL(url as CFURL, "public.png" as CFString, 1, nil) else {
    fatalError("could not open \(outPath)")
}
CGImageDestinationAddImage(dest, image, nil)
guard CGImageDestinationFinalize(dest) else { fatalError("could not write \(outPath)") }
print("wrote \(outPath) [\(style)]")
