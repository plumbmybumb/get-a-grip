// SPDX-License-Identifier: MPL-2.0
// Original contributions Copyright 2026 Nuri Bruner.
//
// Get a Grip — the custom tab and toolbar symbols (the "opus" redesign, 2026-09-30).
//
// POSITION: speak iOS first, house second. Every symbol is a metaphor an iPhone user
// already reads (a calendar, a gear, a dial, a share tray), drawn with SF Regular's own
// line weight, and the house vocabulary appears only where it IS the meaning: fingertips
// on the edge for Today, sessions as a calendar's days, a force peak under its record
// line for Benchmarks.
//
// One pen for all eleven:
//   - ONE line weight (`W`), measured from SF Symbols: Regular-M circle = 8.2 units,
//     plus = 8; this set uses 7.4 so it sits a hair under the system glyphs, never over.
//   - Round caps and round joins everywhere; containers share one corner radius.
//   - ONE knockout gap (`G`) wherever two parts must read as separate objects.
//   - Weight scales stroke only (Ultralight/Regular/Black), scale scales size only
//     (S 0.8, M 1, L 1.29) — the SF rule, so a toolbar asking for Small gets a symbol
//     the size of its system neighbours instead of an oversized Medium.
//   - Every weight is built by the SAME code path, so all three weight groups have
//     identical command structure and Xcode can interpolate the six between them
//     (asserted below).
//
// Fills only: the importer rejects strokes. Lines are filled outlines; holes are
// reversed contours under nonzero winding; overlapping solids share one orientation so
// they union. `oriented(_:solid:)` normalises every contour, so orientation is never
// left to how a primitive happened to be walked.
//
// LAYERS exist for ONE reason: symbol effects. `.bounce.byLayer` and friends walk a
// symbol's annotated layers in order, so the fingers of Today lift one after another
// only because each finger is its own layer. Every path carries the SF annotation
// class (`monochrome-N hierarchical-N:primary`); all layers stay primary, so rendering
// is identical to an unlayered symbol and only the motion differs. The per-symbol
// layer split is the `layer:` closure in the table at the bottom.
//
//   swift scripts/make_symbols.swift Sources/Assets.xcassets [preview-dir]
//
// writes <out-dir>/<name>.symbolset/{<name>.svg, Contents.json} and, if given, one
// standalone Regular-M SVG per symbol into preview-dir for rasterising.

import Foundation

// MARK: - Geometry

struct P { var x: Double; var y: Double }
func + (a: P, b: P) -> P { P(x: a.x + b.x, y: a.y + b.y) }
func - (a: P, b: P) -> P { P(x: a.x - b.x, y: a.y - b.y) }
func * (a: P, k: Double) -> P { P(x: a.x * k, y: a.y * k) }
func len(_ a: P) -> Double { (a.x * a.x + a.y * a.y).squareRoot() }
func unit(_ a: P) -> P { a * (1 / max(1e-12, len(a))) }
func polar(_ r: Double, _ a: Double) -> P { P(x: r * cos(a), y: r * sin(a)) }

enum Cmd { case move(P), line(P), cubic(P, P, P), close }

struct Shape {
    var cmds: [Cmd]

    func mapped(_ f: (P) -> P) -> Shape {
        Shape(cmds: cmds.map {
            switch $0 {
            case .move(let p): return .move(f(p))
            case .line(let p): return .line(f(p))
            case .cubic(let a, let b, let c): return .cubic(f(a), f(b), f(c))
            case .close: return .close
            }
        })
    }

    var points: [P] {
        cmds.flatMap { c -> [P] in
            switch c {
            case .move(let p), .line(let p): return [p]
            case .cubic(let a, let b, let c): return [a, b, c]
            case .close: return []
            }
        }
    }

    /// Shoelace over on-curve and control points — exact enough for the SIGN.
    /// Positive = clockwise on screen (y down).
    var signedArea: Double {
        let p = points
        var a = 0.0
        for i in p.indices {
            let q = p[(i + 1) % p.count]
            a += p[i].x * q.y - q.x * p[i].y
        }
        return a / 2
    }

    func reversed() -> Shape {
        var segs: [(from: P, cmd: Cmd)] = []
        var start = P(x: 0, y: 0), current = P(x: 0, y: 0)
        for c in cmds {
            switch c {
            case .move(let p): start = p; current = p
            case .line(let p): segs.append((current, .line(p))); current = p
            case .cubic(let a, let b, let p): segs.append((current, .cubic(a, b, p))); current = p
            case .close:
                if len(current - start) > 1e-6 {
                    segs.append((current, .line(start))); current = start
                }
            }
        }
        var out: [Cmd] = [.move(current)]
        for s in segs.reversed() {
            switch s.cmd {
            case .line: out.append(.line(s.from))
            case .cubic(let a, let b, _): out.append(.cubic(b, a, s.from))
            default: break
            }
        }
        out.append(.close)
        return Shape(cmds: out)
    }

    /// Command letters only — the interpolation-compatibility fingerprint.
    var structure: String {
        cmds.map { c -> String in
            switch c { case .move: return "M"; case .line: return "L"; case .cubic: return "C"; case .close: return "Z" }
        }.joined()
    }

    func svg() -> String {
        func n(_ v: Double) -> String { String(format: "%.2f", v) }
        return cmds.map {
            switch $0 {
            case .move(let p): return "M\(n(p.x)),\(n(p.y))"
            case .line(let p): return "L\(n(p.x)),\(n(p.y))"
            case .cubic(let a, let b, let c): return "C\(n(a.x)),\(n(a.y)) \(n(b.x)),\(n(b.y)) \(n(c.x)),\(n(c.y))"
            case .close: return "Z"
            }
        }.joined(separator: " ")
    }
}

/// Solid contours run clockwise (positive), holes counter-clockwise.
func oriented(_ s: Shape, solid: Bool) -> Shape {
    (s.signedArea > 0) == solid ? s : s.reversed()
}
func solid(_ s: Shape) -> Shape { oriented(s, solid: true) }
func hole(_ s: Shape) -> Shape { oriented(s, solid: false) }

/// Circular arc a0 → a1 as cubics. The segment count depends on the SWEEP only, and the
/// callers keep sweeps weight-independent, so structure never varies with weight.
func arcCmds(_ c: P, _ r: Double, from a0: Double, to a1: Double) -> [Cmd] {
    let total = a1 - a0
    let steps = max(1, Int(ceil(abs(total) / (Double.pi / 2) - 1e-9)))
    let step = total / Double(steps)
    var out: [Cmd] = []
    for i in 0..<steps {
        let s = a0 + step * Double(i), e = s + step
        let k = 4.0 / 3.0 * tan((e - s) / 4)
        let p1 = P(x: c.x + r * (cos(s) - k * sin(s)), y: c.y + r * (sin(s) + k * cos(s)))
        let p2 = P(x: c.x + r * (cos(e) + k * sin(e)), y: c.y + r * (sin(e) - k * cos(e)))
        out.append(.cubic(p1, p2, c + polar(r, e)))
    }
    return out
}

func circle(_ c: P, _ r: Double) -> Shape {
    var cmds: [Cmd] = [.move(c + polar(r, 0))]
    cmds += arcCmds(c, r, from: 0, to: 2 * Double.pi)
    cmds.append(.close)
    return Shape(cmds: cmds)
}

/// A polygon with every corner rounded by its own radius (one cubic per corner, emitted
/// even at radius ~0 so the command structure is fixed).
func roundedPolygon(_ v: [P], _ radii: [Double]) -> Shape {
    let n = v.count
    var cmds: [Cmd] = []
    for i in 0..<n {
        let V = v[i], A = v[(i + n - 1) % n], B = v[(i + 1) % n]
        let u1 = unit(A - V), u2 = unit(B - V)
        let cosT = max(-1, min(1, u1.x * u2.x + u1.y * u2.y))
        let theta = acos(cosT)                       // interior angle
        let maxD = min(len(A - V), len(B - V)) / 2
        let r = max(0.01, radii[i])
        let d = min(r / tan(theta / 2), maxD)
        let rr = d * tan(theta / 2)
        let T1 = V + u1 * d, T2 = V + u2 * d
        let phi = Double.pi - theta                  // turning angle
        let h = 4.0 / 3.0 * tan(phi / 4) * rr
        let c1 = T1 + (V - T1).unitOr(u1 * -1) * h
        let c2 = T2 + (V - T2).unitOr(u2 * -1) * h
        cmds.append(i == 0 ? .move(T1) : .line(T1))
        cmds.append(.cubic(c1, c2, T2))
    }
    cmds.append(.close)
    return Shape(cmds: cmds)
}
extension P { func unitOr(_ f: P) -> P { len(self) < 1e-9 ? f : unit(self) } }

func roundedRect(_ x: Double, _ y: Double, _ w: Double, _ h: Double, _ r: Double) -> Shape {
    roundedPolygon([P(x: x, y: y), P(x: x + w, y: y), P(x: x + w, y: y + h), P(x: x, y: y + h)],
                   [r, r, r, r])
}

/// A straight stroke with round caps: a stadium from a to b.
func segment(_ a: P, _ b: P, _ w: Double) -> Shape {
    let hw = w / 2
    let d = unit(b - a)
    let n = P(x: -d.y, y: d.x)
    let an = atan2(n.y, n.x)
    var c: [Cmd] = [.move(a + n * hw), .line(b + n * hw)]
    // Cap at b sweeps from +n to -n THROUGH +d.
    let sweepB = cross(n, d) > 0 ? Double.pi : -Double.pi
    c += arcCmds(b, hw, from: an, to: an + sweepB)
    c.append(.line(a - n * hw))
    c += arcCmds(a, hw, from: an + Double.pi, to: an + Double.pi + sweepB)
    c.append(.close)
    return solid(Shape(cmds: c))
}
func cross(_ a: P, _ b: P) -> Double { a.x * b.y - a.y * b.x }

/// A circular-arc stroke with round caps (angles in screen radians, y down).
func arcStroke(_ c: P, _ r: Double, from a0: Double, to a1: Double, _ w: Double) -> Shape {
    let hw = w / 2
    let dir = a1 > a0 ? 1.0 : -1.0
    var cmds: [Cmd] = [.move(c + polar(r + hw, a0))]
    cmds += arcCmds(c, r + hw, from: a0, to: a1)
    // End cap: around the end point, from outer to inner, bulging along the travel.
    let e = c + polar(r, a1)
    cmds += arcCmds(e, hw, from: a1, to: a1 + dir * Double.pi)
    cmds += arcCmds(c, r - hw, from: a1, to: a0)
    let s = c + polar(r, a0)
    cmds += arcCmds(s, hw, from: a0 + Double.pi, to: a0 + Double.pi + dir * Double.pi)
    cmds.append(.close)
    return solid(Shape(cmds: cmds))
}

/// A stroke along a densely sampled open curve: offset sides, round caps.
func curveStroke(_ pts: [P], _ w: Double) -> Shape {
    let hw = w / 2
    func nrm(_ a: P, _ b: P) -> P { let d = unit(b - a); return P(x: -d.y, y: d.x) }
    var normals: [P] = []
    for i in pts.indices {
        let a = i > 0 ? nrm(pts[i - 1], pts[i]) : nil
        let b = i + 1 < pts.count ? nrm(pts[i], pts[i + 1]) : nil
        switch (a, b) {
        case (let a?, let b?):
            let m = unit(a + b)
            let cosHalf = max(0.35, m.x * a.x + m.y * a.y)
            normals.append(m * (1 / cosHalf))
        case (let a?, nil): normals.append(a)
        case (nil, let b?): normals.append(b)
        default: normals.append(P(x: 0, y: 1))
        }
    }
    var c: [Cmd] = []
    for i in pts.indices { let p = pts[i] + normals[i] * hw; c.append(i == 0 ? .move(p) : .line(p)) }
    let last = pts[pts.count - 1], nl = normals[normals.count - 1]
    let dl = unit(pts[pts.count - 1] - pts[pts.count - 2])
    let aL = atan2(nl.y, nl.x)
    c += arcCmds(last, hw, from: aL, to: aL + (cross(nl, dl) > 0 ? Double.pi : -Double.pi))
    for i in pts.indices.reversed() { c.append(.line(pts[i] - normals[i] * hw)) }
    let first = pts[0], nf = normals[0] * -1
    let df = unit(pts[0] - pts[1])
    let aF = atan2(nf.y, nf.x)
    c += arcCmds(first, hw, from: aF, to: aF + (cross(nf, df) > 0 ? Double.pi : -Double.pi))
    c.append(.close)
    return solid(Shape(cmds: c))
}

func bezier(_ p0: P, _ p1: P, _ p2: P, _ p3: P, samples: Int) -> [P] {
    (0...samples).map { i in
        let t = Double(i) / Double(samples), u = 1 - t
        return p0 * (u * u * u) + p1 * (3 * u * u * t) + p2 * (3 * u * t * t) + p3 * (t * t * t)
    }
}

// MARK: - The pen

/// Size and spacing constants shared by every symbol (Regular-M units; cap height 70).
enum Pen {
    /// Regular line weight. SF Regular-M measures 8.0–8.2; 7.4 sits a hair lighter.
    static let regular = 7.4
    static let ultralight = 2.4
    static let black = 15.0
    /// The one knockout: wherever two parts must read as two objects.
    static func gap(_ w: Double) -> Double { 4.2 + 0.12 * w }
    /// The one container corner radius.
    static let corner = 15.0
}

// MARK: - The symbols (origin = symbol centre, y down, Regular-M units)

/// TODAY — the app icon's own mark: four fingertips resting on the edge. The rung sits
/// IN FRONT; the knockout above it is the occlusion, so the bars read as fingers over an
/// edge and not as a bar chart on an axis. Bars are fingers; the rung is the edge.
func today(_ w: Double) -> [Shape] {
    let G = Pen.gap(w)
    let lengths = [0.86, 1.0, 0.94, 0.80]                  // HandGeometry.lengthFactor
    let b = 19.0 + 0.35 * (w - Pen.regular)                // finger width, fuller when bolder
    let g = 6.6 - 0.15 * (w - Pen.regular)
    let tallest = 48.0
    let handW = 4 * b + 3 * g
    let rungW = 112.0
    let rungH = 6.0 + 0.55 * w                             // the edge is a slab, not a rule
    let rungTop = 22.0
    var s: [Shape] = []
    for (i, f) in lengths.enumerated() {
        let x = -handW / 2 + Double(i) * (b + g)
        let bottom = rungTop - G
        let top = bottom - tallest * f
        s.append(solid(roundedPolygon(
            [P(x: x, y: bottom), P(x: x, y: top), P(x: x + b, y: top), P(x: x + b, y: bottom)],
            [1.2, b / 2, b / 2, 1.2])))
    }
    s.append(solid(roundedRect(-rungW / 2, rungTop, rungW, rungH, rungH / 2)))
    return s
}

/// HISTORY — a calendar page whose days are session circles (circles are sessions).
struct Page { let x = -46.0, y = -42.0, w = 92.0, h = 84.0; let header = 22.0 }

func history(_ w: Double, filled: Bool) -> [Shape] {
    let pg = Page()
    let G = Pen.gap(w)
    var s: [Shape] = []
    let dotR = 6.0 + 0.12 * (w - Pen.regular)
    let cols = [-23.0, 0.0, 23.0]
    if filled {
        // Two solid slabs — header and body — split by the knockout, days punched out.
        let hb = pg.y + pg.header
        s.append(solid(roundedPolygon([P(x: pg.x, y: pg.y), P(x: pg.x + pg.w, y: pg.y),
                                       P(x: pg.x + pg.w, y: hb), P(x: pg.x, y: hb)],
                                      [Pen.corner, Pen.corner, 1.2, 1.2])))
        let bt = hb + G
        s.append(solid(roundedPolygon([P(x: pg.x, y: bt), P(x: pg.x + pg.w, y: bt),
                                       P(x: pg.x + pg.w, y: pg.y + pg.h), P(x: pg.x, y: pg.y + pg.h)],
                                      [1.2, 1.2, Pen.corner, Pen.corner])))
        let mid = (bt + pg.y + pg.h) / 2
        for y in [mid - 10.5, mid + 10.5] { for x in cols { s.append(hole(circle(P(x: x, y: y), dotR))) } }
    } else {
        s += pageFrame(w)
        let bt = pg.y + pg.header + w / 2
        let mid = (bt + pg.y + pg.h - w) / 2 + w / 4
        for y in [mid - 10.5, mid + 10.5] { for x in cols { s.append(solid(circle(P(x: x, y: y), dotR - 0.5))) } }
    }
    return s
}

/// The calendar outline shared by History (hollow) and Log: frame ring + header rule.
func pageFrame(_ w: Double) -> [Shape] {
    let pg = Page()
    var s: [Shape] = []
    s.append(solid(roundedRect(pg.x, pg.y, pg.w, pg.h, Pen.corner)))
    s.append(hole(roundedRect(pg.x + w, pg.y + w, pg.w - 2 * w, pg.h - 2 * w, max(0.5, Pen.corner - w))))
    // Header: the band above the rule is solid, the SF calendar convention.
    let hb = pg.y + pg.header
    s.append(solid(roundedPolygon([P(x: pg.x + w * 0.5, y: pg.y + w * 0.5), P(x: pg.x + pg.w - w * 0.5, y: pg.y + w * 0.5),
                                   P(x: pg.x + pg.w - w * 0.5, y: hb), P(x: pg.x + w * 0.5, y: hb)],
                                  [Pen.corner - w * 0.5, Pen.corner - w * 0.5, 0.5, 0.5])))
    return s
}

/// LOG — the same page with a plus where the days go: one more entry in History.
func log(_ w: Double) -> [Shape] {
    let pg = Page()
    var s = pageFrame(w)
    let bt = pg.y + pg.header
    let cy = (bt + pg.y + pg.h - w) / 2 + w / 4
    let arm = 15.0
    s.append(segment(P(x: -arm, y: cy), P(x: arm, y: cy), w))
    s.append(segment(P(x: 0, y: cy - arm), P(x: 0, y: cy + arm), w))
    return s
}

/// BENCHMARKS — one max pull: force rising steeply, peaking, holding, falling away,
/// with the dashed record line of the max screen hovering just above its summit.
func peakCurve() -> [P] {
    var p: [P] = []
    p += bezier(P(x: -44, y: 38), P(x: -35, y: 22), P(x: -29, y: -22), P(x: -14, y: -22), samples: 36)
    p += bezier(P(x: -14, y: -22), P(x: -2, y: -22), P(x: 4, y: 2), P(x: 18, y: 14), samples: 28).dropFirst()
    p += bezier(P(x: 18, y: 14), P(x: 29, y: 23), P(x: 36, y: 33), P(x: 44, y: 38), samples: 20).dropFirst()
    return p
}

func benchmarks(_ w: Double, filled: Bool) -> [Shape] {
    var s: [Shape] = []
    let curve = peakCurve()
    let base = 38.0
    if filled {
        var interior: [Cmd] = [.move(curve[0])]
        for q in curve.dropFirst() { interior.append(.line(q)) }
        interior.append(.close)
        s.append(solid(Shape(cmds: interior)))
    }
    s.append(curveStroke(curve, w))
    s.append(segment(P(x: -44, y: base), P(x: 44, y: base), w))
    // The record line: four dashes, fixed count at every weight.
    let lineY = -22.0 - w / 2 - Pen.gap(w) - 0.5 - w / 2
    let span = 92.0, n = 4.0, dashGap = 8.0
    let dashL = (span - (n - 1) * dashGap) / n            // visible length incl. caps
    for i in 0..<4 {
        let x0 = -span / 2 + Double(i) * (dashL + dashGap)
        s.append(segment(P(x: x0 + w / 2, y: lineY), P(x: x0 + dashL - w / 2, y: lineY), w))
    }
    return s
}

/// SETTINGS — a gear. Eight teeth; filled has a round hole, hollow is a toothed ring
/// around a hub ring (the SF gearshape anatomy, in this set's weight).
func gearPolygon(tip rT: Double, root rR: Double, tipHalf wt: Double, rootHalf wr: Double) -> [P] {
    var v: [P] = []
    for k in 0..<8 {
        let a = -Double.pi / 2 + Double(k) * Double.pi / 4
        let u = polar(1, a), n = P(x: -u.y, y: u.x)
        let rootD = (rR * rR - wr * wr).squareRoot(), tipD = (rT * rT - wt * wt).squareRoot()
        v.append(u * rootD - n * wr)
        v.append(u * tipD - n * wt)
        v.append(u * tipD + n * wt)
        v.append(u * rootD + n * wr)
    }
    return v
}

func settings(_ w: Double, filled: Bool) -> [Shape] {
    let rT = 49.0, rR = 38.0
    let wt = 8.0 + 0.2 * (w - Pen.regular), wr = 10.5 + 0.2 * (w - Pen.regular)
    let v = gearPolygon(tip: rT, root: rR, tipHalf: wt, rootHalf: wr)
    let radii = (0..<8).flatMap { _ in [3.0, 3.2, 3.2, 3.0] }
    var s: [Shape] = [solid(roundedPolygon(v, radii))]
    if filled {
        s.append(hole(circle(P(x: 0, y: 0), 15.5 - 0.35 * (w - Pen.regular))))
    } else {
        s.append(hole(circle(P(x: 0, y: 0), rR - w)))
        s.append(solid(circle(P(x: 0, y: 0), 14.5)))
        s.append(hole(circle(P(x: 0, y: 0), 14.5 - w)))
    }
    return s
}

/// GAUGE — a closed dial, needle swung high, pivot at its centre.
///
/// CLOSED on purpose (Nuri, 2026-09-30: "looks off-center"). The first draft left the
/// dial open at the bottom like a speedometer. A toolbar centres a symbol by its bounding
/// box, the eye centres a dial on its pivot, and an open dial's pivot sits below its box's
/// centre — measured about 2 pt low in the 44 pt button. A full ring is symmetric about
/// the pivot by construction, so both centres coincide. (Also SF's own gauge.with.needle.)
func gauge(_ w: Double) -> [Shape] {
    let c = P(x: 0, y: 0)
    let R = 41.0                                            // a ring reads larger than an arc
    var s: [Shape] = [solid(circle(c, R + w / 2)), hole(circle(c, R - w / 2))]
    let needleA = -Double.pi / 4 + 0.08                    // up and to the right: a strong pull
    s.append(segment(c, c + polar(27, needleA), w))
    s.append(solid(circle(c, 5.5 + 0.55 * w)))
    return s
}

/// EXPORT — the share tray with an arrow leaving it: the idiom the share sheet it opens
/// already speaks, drawn in this set's single weight with round joins.
func export(_ w: Double) -> [Shape] {
    var s: [Shape] = []
    let L = -38.0, R = 38.0, T = -8.0, B = 48.0, r = 14.0
    // Tray: short returns at the top, rounded corners, open over the arrow.
    // Returns stop a weight-scaled distance from the shaft, so Black never fuses them.
    let inner = 9.0 + 0.9 * w
    s.append(segment(P(x: L + r, y: T), P(x: -inner, y: T), w))
    s.append(segment(P(x: inner, y: T), P(x: R - r, y: T), w))
    s.append(arcStroke(P(x: L + r, y: T + r), r, from: -Double.pi / 2, to: -Double.pi, w))
    s.append(segment(P(x: L, y: T + r), P(x: L, y: B - r), w))
    s.append(arcStroke(P(x: L + r, y: B - r), r, from: Double.pi, to: Double.pi / 2, w))
    s.append(segment(P(x: L + r, y: B), P(x: R - r, y: B), w))
    s.append(arcStroke(P(x: R - r, y: B - r), r, from: Double.pi / 2, to: 0, w))
    s.append(segment(P(x: R, y: B - r), P(x: R, y: T + r), w))
    s.append(arcStroke(P(x: R - r, y: T + r), r, from: 0, to: -Double.pi / 2, w))
    // Arrow.
    let tip = P(x: 0, y: -54)
    s.append(segment(P(x: 0, y: 20), tip, w))
    s.append(segment(P(x: -16, y: -38), tip, w))
    s.append(segment(P(x: 16, y: -38), tip, w))
    return s
}

// MARK: - Template emission

let capMidY = 311.0
let regularCentreX = 465.0

func bbox(_ shapes: [Shape]) -> (minX: Double, maxX: Double, minY: Double, maxY: Double) {
    let pts = shapes.flatMap(\.points)
    return (pts.map(\.x).min()!, pts.map(\.x).max()!, pts.map(\.y).min()!, pts.map(\.y).max()!)
}

/// Build one group: `k` is the symbol scale, `stroke` the TARGET line weight. The drawing
/// is built at M size with stroke/k, then scaled by k, so size and weight stay independent.
func placed(_ make: (Double) -> [Shape], k: Double, stroke: Double, cx: Double, cy: Double) -> [Shape] {
    let shapes = make(stroke / k)
    let b = bbox(shapes)
    let mx = (b.minX + b.maxX) / 2, my = (b.minY + b.maxY) / 2
    return shapes.map { $0.mapped { p in P(x: cx + (p.x - mx) * k, y: cy + (p.y - my) * k) } }
}

/// THE MOTION GROUPS — the piece every layered effect actually reads. Annotating layers
/// with `monochrome-N` classes is enough for rendering modes, and NOT enough for motion:
/// without `-sfsymbols-motion-group`, every layer lands in one group and `.byLayer` moves
/// the symbol as a single piece (measured frame by frame 2026-09-30: four fingers scaling
/// in lockstep). Apple's own exports carry this `<style>` block; group N animates Nth,
/// so layer order IS the wave order.
func motionStyle(_ layers: Set<Int>) -> String {
    let rules = layers.sorted().flatMap { L -> [String] in
        let g = "{-sfsymbols-motion-group:\(L)}"
        return [".monochrome-\(L) \(g)", ".multicolor-\(L):tintColor \(g)", ".hierarchical-\(L):primary \(g)"]
    }
    return "<style>" + rules.joined(separator: "\n") + "\n.SFSymbolsPreviewWireframe {fill:none;opacity:1.0;stroke:black;stroke-width:0.5}\n</style>"
}

let weights: [(String, Double, Double)] = [("Ultralight", -200, Pen.ultralight), ("Regular", 0, Pen.regular), ("Black", 200, Pen.black)]
let scales: [(String, Double, Double)] = [("S", -200, 0.8), ("M", 0, 1.0), ("L", 200, 1.29)]

func sheet(name: String, make: (Double) -> [Shape], layer: (Int) -> Int) -> (svg: String, regularM: [Shape]) {
    var groups: [String] = [], margins: [String] = []
    var layersUsed = Set<Int>()
    var regularM: [Shape] = []
    var fingerprint: String? = nil
    for sc in scales {
        for wt in weights {
            let id = "\(wt.0)-\(sc.0)"
            let cx = regularCentreX + wt.1, cy = capMidY + sc.1
            let shapes = placed(make, k: sc.2, stroke: wt.2, cx: cx, cy: cy)
            let fp = shapes.map(\.structure).joined(separator: "|")
            if let f = fingerprint, f != fp {
                FileHandle.standardError.write("\(name): \(id) is not interpolation-compatible\n\(f)\n\(fp)\n".data(using: .utf8)!)
                exit(1)
            }
            fingerprint = fp
            if id == "Regular-M" { regularM = shapes }
            // One <path> per layer, in layer order; shapes keep their order inside it.
            var byLayer: [Int: [Shape]] = [:]
            for (i, sh) in shapes.enumerated() { byLayer[layer(i), default: []].append(sh) }
            layersUsed.formUnion(byLayer.keys)
            let paths = byLayer.keys.sorted().map { L -> String in
                let d = byLayer[L]!.map { $0.svg() }.joined(separator: " ")
                let cls = "monochrome-\(L) multicolor-\(L):tintColor hierarchical-\(L):primary SFSymbolsPreviewWireframe"
                return "            <path class=\"\(cls)\" d=\"\(d)\"/>"
            }.joined(separator: "\n")
            groups.append("        <g id=\"\(id)\">\n\(paths)\n        </g>")
            let b = bbox(shapes)
            let top = 256 + sc.1
            margins.append("        <path id=\"left-margin-\(id)\" d=\"M\(String(format: "%.2f", b.minX)),\(String(format: "%.0f", top)) l0,110\"/>")
            margins.append("        <path id=\"right-margin-\(id)\" d=\"M\(String(format: "%.2f", b.maxX)),\(String(format: "%.0f", top)) l0,110\"/>")
        }
    }
    let svg = """
    <?xml version="1.0" encoding="UTF-8"?>
    <svg height="600" width="800" xmlns="http://www.w3.org/2000/svg" xmlns:xlink="http://www.w3.org/1999/xlink">
    \(motionStyle(layersUsed))
        <g id="Notes" font-family="LucidaGrande, 'Lucida Grande', sans-serif" font-size="13">
            <rect fill="white" height="600.0" width="800.0" x="0.0" y="0.0"/>
            <g font-size="13">
                <text x="18.0" y="176.0">Small</text>
                <text x="18.0" y="376.0">Medium</text>
                <text x="18.0" y="576.0">Large</text>
            </g>
            <g font-size="9">
                <text x="250.0" y="30.0">Ultralight</text>
                <text x="450.0" y="30.0">Regular</text>
                <text x="650.0" y="30.0">Black</text>
                <text id="template-version" fill="#505050" text-anchor="end" x="785.0" y="575.0">Template v.6.0</text>
                <text id="descriptive-name" fill="#505050" text-anchor="end" x="785.0" y="590.0">\(name)</text>
            </g>
        </g>
        <g id="Guides" stroke="rgb(39, 170, 225)" stroke-width="0.5">
            <path id="Capline-S" d="M18,76 l800,0"/>
            <path id="H-reference" d="M85,145.755 L87.685,145.755 L113.369,79.287 L114.052,79.287 L114.052,76 L112.148,76 L85,145.755 Z M95.693,121.536 L130.996,121.536 L130.263,119.313 L96.474,119.313 L95.693,121.536 Z M139.15,145.755 L141.787,145.755 L114.638,76 L113.466,76 L113.466,79.287 L139.15,145.755 Z" stroke="none" transform="translate(0,200)"/>
            <path id="Baseline-S" d="M18,146 l800,0"/>
    \(margins.joined(separator: "\n"))
            <path id="Capline-M" d="M18,276 l800,0"/>
            <path id="Baseline-M" d="M18,346 l800,0"/>
            <path id="Capline-L" d="M18,476 l800,0"/>
            <path id="Baseline-L" d="M18,546 l800,0"/>
        </g>
        <g id="Symbols">
    \(groups.joined(separator: "\n"))
        </g>
    </svg>

    """
    return (svg, regularM)
}

// MARK: - Main

let args = CommandLine.arguments
guard args.count >= 2 else {
    FileHandle.standardError.write("usage: make_symbols.swift <out-dir> [preview-dir]\n".data(using: .utf8)!)
    exit(2)
}
let outDir = URL(fileURLWithPath: args[1])
let previewDir = args.count >= 3 ? URL(fileURLWithPath: args[2]) : nil

/// Shape index → layer, per symbol. Index order is the order the drawing function
/// appends shapes; the counts are fixed at every weight (asserted in `sheet`).
let one: (Int) -> Int = { _ in 0 }
let symbols: [(name: String, make: (Double) -> [Shape], layer: (Int) -> Int)] = [
    // Today has ONE form: a hollow finger is a loop, and a loop is a session dot.
    // Shapes 0–3 are the fingers index → little, 4 the rung. Each finger is a layer so
    // a byLayer bounce walks the hand like a wave; the rung lands last, as the catch.
    ("tab.today", { today($0) }, { $0 }),
    // Hollow: page frame (3 shapes), then the two rows of days (3 each).
    ("tab.history", { history($0, filled: false) }, { $0 < 3 ? 0 : ($0 < 6 ? 1 : 2) }),
    // Filled: header slab, then the body slab with its six punched days (holes must
    // share the body's path to cut it).
    ("tab.history.fill", { history($0, filled: true) }, { $0 == 0 ? 0 : 1 }),
    // Curve (+ fill) and baseline are the graph; the four dashes are the record line.
    ("tab.benchmarks", { benchmarks($0, filled: false) }, { $0 < 2 ? 0 : 1 }),
    ("tab.benchmarks.fill", { benchmarks($0, filled: true) }, { $0 < 3 ? 0 : 1 }),
    // A gear turns as one piece.
    ("tab.settings", { settings($0, filled: false) }, one),
    ("tab.settings.fill", { settings($0, filled: true) }, one),
    // Ring (outer + hole), then needle + pivot.
    ("glyph.gauge", { gauge($0) }, { $0 < 2 ? 0 : 1 }),
    // Tray (nine segments and corners), then the arrow (three).
    ("glyph.export", { export($0) }, { $0 < 9 ? 0 : 1 }),
    // Page frame (3), then the plus (2).
    ("glyph.log", { log($0) }, { $0 < 3 ? 0 : 1 }),
]

let fm = FileManager.default
for (name, make, layer) in symbols {
    let dir = outDir.appendingPathComponent("\(name).symbolset")
    try fm.createDirectory(at: dir, withIntermediateDirectories: true)
    let (svg, regularM) = sheet(name: name, make: make, layer: layer)
    try svg.write(to: dir.appendingPathComponent("\(name).svg"), atomically: true, encoding: .utf8)
    let contents = """
    {
      "info" : {
        "author" : "xcode",
        "version" : 1
      },
      "symbols" : [
        {
          "filename" : "\(name).svg",
          "idiom" : "universal"
        }
      ]
    }

    """
    try contents.write(to: dir.appendingPathComponent("Contents.json"), atomically: true, encoding: .utf8)
    if let pdir = previewDir {
        try fm.createDirectory(at: pdir, withIntermediateDirectories: true)
        // Standalone Regular-M: a 160-unit box centred on the cap midline.
        let d = regularM.map { $0.svg() }.joined(separator: " ")
        let p = """
        <svg xmlns="http://www.w3.org/2000/svg" width="640" height="640" viewBox="385 231 160 160">
        <rect x="385" y="231" width="160" height="160" fill="white"/>
        <path fill="black" d="\(d)"/>
        </svg>

        """
        try p.write(to: pdir.appendingPathComponent("\(name).svg"), atomically: true, encoding: .utf8)
    }
    print("wrote \(name)")
}
