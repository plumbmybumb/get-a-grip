// SPDX-License-Identifier: MPL-2.0
// Original contributions Copyright 2026 Nuri Bruner.

import SwiftUI

/// Keeps the curve off the card's edges. Without the bottom inset a resting gauge draws
/// its zero line on the boundary, where the rounded corners clip it into a glitch.
///
/// File scope, not statics on the View: `Canvas`'s draw closure is not `@MainActor`, so
/// it cannot read statics of a `View`.
private enum TraceInset {
    static let top: CGFloat = 12
    static let bottom: CGFloat = 10
}

/// Axis constants shared with the layout that places a full-bleed plot — file scope,
/// like `TraceInset`, so a plain geometry struct can read them.
enum TraceAxis {
    /// Headroom kept above the highest load seen and above a target band's ceiling: the
    /// peak lands at 1 / this of the plot's height. `RunnerView` places its full-bleed plot
    /// so exactly this headroom lies under the glass panel.
    static let ceilingHeadroom: Double = 1.25
}

#if DEBUG
/// A one-line record of the trace's LAST draw, readable from any actor. The draw runs in
/// a nonisolated `Canvas` closure, so "healthy buffer, no line" can only be explained
/// from inside `draw`. Read by the DEBUG diagnostics dumper.
final class TraceDrawProbe: @unchecked Sendable {
    static let shared = TraceDrawProbe()
    /// `-traceHeadLog`: every draw's head position is written to `Documents/tracehead.csv`
    /// by the diagnostics dumper, so smoothness is judged from the rows (step per frame,
    /// stream due per frame) rather than by eye.
    static let logsHead = ProcessInfo.processInfo.arguments.contains("-traceHeadLog")
    private let lock = NSLock()
    private var _line = "no draw yet"
    private var _count = 0
    private var _rows: [String] = ["now,headX,headY,dueT,newestT,pending"]
    func set(_ s: String) { lock.lock(); _count += 1; _line = "#\(_count) " + s; lock.unlock() }
    var line: String { lock.lock(); defer { lock.unlock() }; return _line }
    /// `dueT` is the last drawn point's time and `newestT` the buffer's: their difference
    /// to `now` says how much stream came due this frame and how deep the buffer sits.
    func logHead(now: TimeInterval, head: CGPoint, dueT: TimeInterval, newestT: TimeInterval, pending: Int) {
        lock.lock(); defer { lock.unlock() }
        guard _rows.count < 40_000 else { return }
        _rows.append(String(format: "%.4f,%.2f,%.2f,%.4f,%.4f,%d", now, head.x, head.y, dueT, newestT, pending))
    }
    var headRows: String { lock.lock(); defer { lock.unlock() }; return _rows.joined(separator: "\n") }
}
#endif

/// The head's two visual softeners — the only smoothing the trace applies, both lag-free.
enum TraceHead {
    /// How long a newly arrived segment takes to reach full strength.
    static let freshSeconds: TimeInterval = 0.12
    /// The head's target: the newest readings, lightly averaged — three at 80 Hz is ~40 ms,
    /// batch noise damped and nothing hidden. A pure function so it is unit-tested.
    static func newestKg(samples: [DeviceStore.TracePoint]) -> Double? {
        guard !samples.isEmpty else { return nil }
        let n = min(3, samples.count)
        return samples.suffix(n).reduce(0) { $0 + $1.kg } / Double(n)
    }
}

/// The live force trace: a rolling window of the gauge's readings, scrolling smoothly.
///
/// Time-based, not index-based: the Progressor delivers ~80 Hz samples in batches of
/// about eight, so index spacing plus redraw-on-arrival stutters ten times a second.
/// Points carry a PLAYBACK time (`DeviceStore.TracePoint.t` — monotone, built at
/// ingestion, immune to the device's counter restarting on tare/reconnect/re-start),
/// and the window's right edge advances with the wall clock in a `TimelineView`. A point
/// whose time has not come yet waits beyond the right edge and slides in when due, so
/// bunched arrival never bends the line.
///
/// **This view is STATELESS about time.** A first version kept `@State` device-µs ↔
/// wall-clock anchors, and on hardware a tare left them poisoned with no path back (a
/// blank graph beside a live kg readout until the screen was re-entered). State that
/// models another clock can be poisoned; geometry from (now − t) cannot.
struct ForceTraceView: View {
    var samples: [DeviceStore.TracePoint]
    /// Drawn as a dashed rule: the load a rep has to beat for its clock to run.
    var thresholdKg: Double?
    /// **The range this rep asks for, drawn as a lane to land the curve in** (Nuri,
    /// 2026-08-09). It REPLACES the threshold rule: the band's floor is what the clock runs
    /// off, so a third horizontal line would be a second answer to the same question.
    var targetBand: ClosedRange<Double>?
    /// Colour of the trace — the caller passes the phase tint so the graph and the
    /// rest of the screen escalate together.
    var tint: Color = StatusTint.engaged
    /// The active gauge's `nominalSampleRate`, the only thing that says how far apart two
    /// ordinary points are — see `streamGapSeconds`. A VALUE, so this view touches no store.
    var nominalSampleRate: Double = 80

    /// Broadcast gauges bridge every point inside the window instead of breaking runs
    /// at gaps — see `streamGapSeconds`. A value, for the same previewability reason.
    var bridgesSparseDelivery: Bool = false
    var diagnostics: PipelineDiagnostics? = nil
    /// A completed effort keeps its measured picture instead of scrolling off screen.
    var frozenAt: TimeInterval? = nil
    /// **Where the plot sits inside the canvas.** In a card: the card less small edge
    /// clearances. When the trace is the SCREEN's background (`RunnerView`'s stacked
    /// layout) the caller places the plot by the glass above and below it.
    var plot: PlotInsets = .card
    /// **Drawn as a LIT object** — the runner's full-bleed trace. The stroke brightens
    /// toward now, the live point glows, the lane's edges are solid hairlines: on an open
    /// screen a flat line and dashed rules read as chart furniture. Cards keep the plain
    /// drawing.
    var lit: Bool = false

    /// The band of the canvas that 0 kg → ceiling maps onto, as insets from the
    /// canvas's own edges.
    struct PlotInsets: Equatable {
        var top: CGFloat = TraceInset.top
        var bottom: CGFloat = TraceInset.bottom
        /// Clearance between the trace's head and the right edge. Zero in a card (its inset
        /// keeps the head dot whole); a full-bleed canvas steps in from the screen edge.
        var trailing: CGFloat = 0
        /// The card's own clearances.
        static let card = PlotInsets()
    }

    @Environment(\.accessibilityReduceMotion) private var reduceMotion


    /// How much history is on screen. Six seconds shows a whole 10 s hold's shape
    /// without squeezing the detail out of it.
    private static let windowSeconds: Double = 6

    /// Longer than this between two samples and the stream stopped: above a BLE batch
    /// (~100 ms) and a dropped one, below anything a human would call an interruption.
    ///
    /// **Three of this gauge's own sample periods, floored at the Progressor's 0.35 s.**
    /// A flat 0.35 (3.5 Tindeq batches) is only ~2.8 periods of an 8 Hz crane scale, whose
    /// advertisements are best-effort: two missed frames (375 ms) declared a gap and threw
    /// away the drawn history. Keyed to the rate, an ordinary miss stays ordinary.
    ///
    /// A BROADCAST gauge has no gap threshold at all: delivery is bursty by nature, so
    /// every hole started a new run and the history vanished at each one (Nuri's first
    /// WH-C06 session, 2026-08-17). Points older than the window still fall off the left
    /// edge on their own; a scale that genuinely left is the silence watchdog's job.
    private var streamGapSeconds: Double {
        bridgesSparseDelivery ? .greatestFiniteMagnitude : max(0.35, 3.0 / max(1, nominalSampleRate))
    }

    /// Cross-frame axis state. A reference type on purpose: written in the TimelineView
    /// builder every frame, and `@State` would re-enter SwiftUI's update machinery 120×/s
    /// for a value only the next frame reads. Only touched from the view body (MainActor).
    private final class AxisMemory {
        var maxSeen: Double = 0
        var displayed: Double = 0
        var lastFrame: TimeInterval = 0
        /// The latest sample's playback time, refreshed every body evaluation. A plain field,
        /// not `@State`, so the watcher task can read it without a Task rebuild per sample —
        /// see `watchForExpiry()`.
        var newestTime: TimeInterval?
        /// The head dot's eased value — a per-frame value, not a clock.
        var headKg: Double?
    }
    @State private var axis = AxisMemory()
    /// A TimelineView cannot notice on its own that a stopped trace has slid out of the
    /// window — no sample changes at that instant — so the watcher task flips this when
    /// nothing remains drawable. A FRESH sample needs no help: `deadlineReached` compares
    /// this against the current `newestTime` every render, so a stale expiry stops matching
    /// the moment a new sample lands.
    @State private var expiredNewestTime: TimeInterval?
    /// Can differ from the incoming value until SwiftUI delivers its lifecycle update. Keep
    /// the exact object we opened, so a frozen trace cannot close another graph's
    /// registration and a resumed trace registers although its identity never changed.
    @State private var registeredDiagnostics: PipelineDiagnostics?
    @State private var isDiagnosticsVisible = false

    /// The y-axis ceiling, LATCHED and EASED — never a per-frame function of the
    /// rolling buffer.
    ///
    /// Deriving it from `samples.max()` rubber-banded: the buffer holds six seconds, so the
    /// axis grew on every pull and stretched back six seconds later when that peak slid
    /// out, dragging the threshold line with it. So the max is latched for the view's life
    /// (one stable scale per session, like peakKg), and the displayed ceiling eases toward
    /// its target over ~100 ms so a new peak mid-session is a glide, not a snap.
    /// Seconds since the last frame, clamped: the one number every per-frame ease uses.
    private func frameDelta(now: TimeInterval) -> TimeInterval {
        let dt = min(max(now - axis.lastFrame, 0), 0.1)
        axis.lastFrame = now
        return dt
    }

    /// The head dot settles toward the newest reading over ~60 ms with no overshoot (an
    /// overshooting head would draw a load nobody pulled). Snapped on a frozen trace.
    private func headValue(dt: TimeInterval) -> Double? {
        guard let target = TraceHead.newestKg(samples: samples) else {
            axis.headKg = nil
            return nil
        }
        if let current = axis.headKg, frozenAt == nil {
            axis.headKg = current + (target - current) * min(1, dt * 25)
        } else {
            axis.headKg = target
        }
        return axis.headKg
    }

    private func axisCeiling(dt: TimeInterval) -> Double {
        // Walked without allocating a mapped array — this runs every frame.
        for sample in samples where sample.kg > axis.maxSeen { axis.maxSeen = sample.kg }
        // The band's ceiling has to be ON the axis with headroom above it, or the lane
        // you are aiming for sits jammed against the top edge of the card.
        let target = max(10, axis.maxSeen * TraceAxis.ceilingHeadroom, (thresholdKg ?? 0) * 1.6,
                         (targetBand?.upperBound ?? 0) * TraceAxis.ceilingHeadroom)
        if axis.displayed == 0 || reduceMotion || frozenAt != nil {
            axis.displayed = target
        } else {
            axis.displayed += (target - axis.displayed) * min(1, dt * 10)
        }
        return axis.displayed
    }

    var body: some View {
        // Through a helper: a bare assignment is not a View and does not compile here.
        let newestTime = recordNewest(samples.last?.t)
        let alreadyExpired = newestTime.map {
            Date().timeIntervalSinceReferenceDate - $0 >= Self.windowSeconds
        } ?? true
        // Keyed to the sample that armed it, so a fresh sample resumes immediately,
        // before the replacement task gets its first turn.
        let deadlineReached = newestTime == nil || expiredNewestTime == newestTime
        let paused = frozenAt != nil || alreadyExpired || deadlineReached
        // NOT paused under Reduce Motion. It used to be, so the Canvas redrew only on
        // data; on a full-screen iPad canvas every packet became a 37 pt lurch of the
        // whole picture five times a second (Nuri's iPad, 2026-09-19) — more motion,
        // and the abrupt kind Reduce Motion exists to remove. A slow steady slide is
        // the gentlest way a graph of time can move; the setting drops decoration
        // (the 0.85 opacity below), not the tick.
        TimelineView(.animation(paused: paused)) { timeline in
            let _ = diagnostics?.drawing(now: ProcessInfo.processInfo.systemUptime)
            // **`now` is the WALL clock, not `timeline.date`.** The schedule's date rides
            // the animation clock, which stops while the device sleeps or the app is
            // suspended; the sample times in `t` come from `Date()`, which does not. After
            // a lock the two diverged by the whole gap (an iPad 25 s behind), every point
            // landed in the "future", and the due-only renderer drew nothing (Nuri's iPad,
            // 2026-09-19). Reading `Date()` — the clock the store stamps `t` with — keeps
            // both identical. `timeline.date` only wakes the body every frame.
            let _ = timeline.date
            let now = frozenAt ?? Date().timeIntervalSinceReferenceDate
            // Here, not in the Canvas closure: axis memory is MainActor view state.
            let dt = frameDelta(now: now)
            let ceiling = axisCeiling(dt: dt)
            let head = headValue(dt: dt)
            Canvas { context, size in
                draw(in: context, size: size, now: now, ceiling: ceiling, head: head)
            }
        }
        .opacity(reduceMotion ? 0.85 : 1)
        // The off-screen anchor can land well left of x = 0 on a sparse-delivery
        // gauge; without this the overhang would draw outside the card.
        .clipped()
        .accessibilityHidden(true)
        // ONE long-lived watcher for the view's life, not one per sample:
        // `.task(id: newestTime)` reallocated a ~6 s sleep Task at display rate for
        // a whole session. A fresh sample only has to stop matching
        // `expiredNewestTime`, which the render-time comparison does for free; the
        // watcher's only job is noticing when nothing is left to draw.
        .task(id: frozenAt != nil) { if frozenAt == nil { await watchForExpiry() } }
        .onAppear {
            isDiagnosticsVisible = true
            updateDiagnosticsRegistration()
        }
        .onChange(of: diagnostics.map(ObjectIdentifier.init)) { _, _ in
            updateDiagnosticsRegistration()
        }
        .onDisappear {
            isDiagnosticsVisible = false
            updateDiagnosticsRegistration()
        }
    }

    private func updateDiagnosticsRegistration() {
        let visibleDiagnostics = isDiagnosticsVisible ? diagnostics : nil
        guard registeredDiagnostics !== visibleDiagnostics else { return }
        registeredDiagnostics?.graphClosed()
        registeredDiagnostics = visibleDiagnostics
        registeredDiagnostics?.graphOpened()
    }

    /// The render pass's one message to the watcher task — a plain field write, not
    /// `@State`, so it never itself triggers a re-render.
    private func recordNewest(_ t: TimeInterval?) -> TimeInterval? {
        axis.newestTime = t
        return t
    }

    /// Sleeps until the newest known sample would age out of the window, then checks
    /// whether a fresher one arrived. Marks that run expired only when nothing did, then
    /// loops.
    private func watchForExpiry() async {
        while !Task.isCancelled {
            guard let newest = axis.newestTime else {
                try? await Task.sleep(for: .seconds(1))
                continue
            }
            // Once expired nothing changes until a FRESH sample lands, and that
            // un-expires the render by itself, so the wait can be long rather than
            // waking every second to re-confirm a stopped trace.
            if expiredNewestTime == newest {
                try? await Task.sleep(for: .seconds(10))
                continue
            }
            let age = max(0, Date().timeIntervalSinceReferenceDate - newest)
            let remaining = max(0, Self.windowSeconds - age)
            // Floored at 1 s: sleeping for `remaining == 0` would spin the loop.
            try? await Task.sleep(for: .seconds(max(remaining, 1)))
            guard !Task.isCancelled else { return }
            if axis.newestTime == newest, expiredNewestTime != newest {
                expiredNewestTime = newest
            }
        }
    }

    private func draw(in context: GraphicsContext, size: CGSize, now: TimeInterval,
                      ceiling: Double, head: Double?) {
        let plotTop = plot.top
        let plotHeight = max(1, size.height - plotTop - plot.bottom)
        let plotRight = size.width - plot.trailing
        #if DEBUG
        TraceDrawProbe.shared.set(String(format: "size=%.0fx%.0f count=%d ceiling=%.1f tintOpacity=%.2f — running",
                                         size.width, size.height, samples.count, ceiling,
                                         reduceMotion ? 0.85 : 1.0))
        #endif

        func y(_ kg: Double) -> CGFloat {
            let fraction = min(max(kg, 0), ceiling) / ceiling
            return plotTop + plotHeight - CGFloat(fraction) * plotHeight
        }

        if let targetBand {
            // A LANE, not two lines: a filled band is a place to be, and the curve is
            // in it or not. Neutral ink — the TRACE carries the phase colour, and a
            // tinted lane behind it would be two competing signals.
            let top = y(targetBand.upperBound)
            let bottom = y(targetBand.lowerBound)
            let lane = CGRect(x: 0, y: top, width: size.width, height: max(1, bottom - top))
            context.fill(Path(lane), with: .color(Ink.tertiary.opacity(lit ? 0.11 : 0.13)))
            for edge in [top, bottom] {
                var rule = Path()
                rule.move(to: CGPoint(x: 0, y: edge))
                rule.addLine(to: CGPoint(x: size.width, y: edge))
                context.stroke(rule, with: .color(Ink.tertiary.opacity(lit ? 0.38 : 0.5)),
                               style: lit ? StrokeStyle(lineWidth: 1)
                                          : StrokeStyle(lineWidth: 1, dash: [4, 4]))
            }
        } else if let thresholdKg, thresholdKg > 0, thresholdKg < ceiling {
            var rule = Path()
            rule.move(to: CGPoint(x: 0, y: y(thresholdKg)))
            rule.addLine(to: CGPoint(x: size.width, y: y(thresholdKg)))
            context.stroke(rule, with: .color(Ink.tertiary.opacity(lit ? 0.35 : 0.55)),
                           style: StrokeStyle(lineWidth: 1, dash: [4, 4]))
        }

        guard samples.count > 1 else {
            #if DEBUG
            TraceDrawProbe.shared.set("early: count<=1 (count=\(samples.count))")
            #endif
            return
        }

        /// Smoothed inline, not via a precomputed array: two 480-element arrays per frame
        /// were ~1.4 MB/s of allocation churn at 120 Hz. The cost was the garbage, never the
        /// maths (0.03 % of a frame).

        // TRUE age, so every point sits where its own time puts it. The store stamps a
        // packet's newest reading at arrival (`DeviceStore.playbackTime`), so nothing
        // normally sits past the right edge; an early packet's tail is not drawn
        // until due.
        func x(_ index: Int) -> CGFloat {
            let age = now - samples[index].t
            return plotRight - CGFloat(age / Self.windowSeconds) * size.width
        }

        // **START AT THE NEWEST UNBROKEN RUN.** A gap means the stream stopped
        // (backgrounded, disconnected, tared): the points either side are adjacent in
        // the array and minutes apart in the hand. Drawing across it put a bridge
        // through the graph (Nuri, 2026-08-09). A gap is a boundary, not data.
        var runStart = 0
        var scan = samples.count - 1
        while scan > 0 {
            if samples[scan].t - samples[scan - 1].t > streamGapSeconds {
                runStart = scan
                break
            }
            scan -= 1
        }

        func smoothed(_ i: Int) -> Double {
            guard i > runStart, i < samples.count - 1 else { return samples[i].kg }
            return (samples[i - 1].kg + samples[i].kg + samples[i + 1].kg) / 3
        }

        // **THE ANCHOR: the newest point still older than the window.** Drawing only
        // on-screen points made the line's start SNAP forward as the oldest one aged
        // out — invisible at 80 Hz, a visible chunk of trace vanishing on a broadcast
        // gauge whose readings land seconds apart (Nuri, 2026-08-17). One off-screen
        // anchor per run means the segment crossing x = 0 is drawn every frame and the
        // trace slides off the left edge continuously. `.clipped()` hides the overhang.
        var anchorIndex = runStart
        while anchorIndex < samples.count - 1, x(anchorIndex + 1) < 0 {
            anchorIndex += 1
        }

        // **THE TRACE FADES BACK IN.** After the home screen the buffer restarts from
        // nothing, and a graph that simply appears mid-card reads as a glitch (Nuri,
        // 2026-08-09). Derived from the DATA, with no state: the oldest drawn sample's
        // age IS how long this run has gone, so the run reveals itself over its first
        // half second. Nothing to reset or poison, and it covers resume, tare,
        // reconnect and a session's first pull alike.
        var context = context
        context.opacity = frozenAt == nil ? min(1, (now - samples[runStart].t) / 0.5) : 1

        // Everything known is drawn at its true time. The filter only catches the few
        // milliseconds a slightly early packet's tail sits past now.
        var lastDue = samples.count - 1
        while lastDue >= anchorIndex, samples[lastDue].t > now { lastDue -= 1 }
        guard lastDue >= anchorIndex else {
            #if DEBUG
            TraceDrawProbe.shared.set("early: nothingDue anchor=\(anchorIndex) count=\(samples.count)")
            #endif
            return
        }

        // **THE FRESH SEGMENT MATERIALIZES; THE HEAD SETTLES.** Packets of ~190 ms make
        // a whole segment known at once, and at full strength that pops five times a
        // second (Nuri, 2026-09-19). Positions are never touched — a buffered glide and
        // a live pen were both tried and rejected ("just take the raw data and feed it
        // in"). Readings from the last `TraceHead.freshSeconds` fade in, and the head
        // eases toward the newest reading over ~60 ms. Nothing delayed or invented.
        let freshSince = now - TraceHead.freshSeconds
        var settledEnd = lastDue
        if frozenAt == nil {
            while settledEnd >= anchorIndex, samples[settledEnd].arrival > freshSince { settledEnd -= 1 }
        }

        func point(_ i: Int) -> CGPoint { CGPoint(x: x(i), y: y(smoothed(i))) }
        let firstDrawn = point(anchorIndex)
        let lastPoint = point(lastDue)

        // WHILE DATA IS FLOWING the head rides the right edge (the newest reading,
        // eased), joined to the last drawn point by at most a packet's width. After
        // half a second of silence there is no synthetic head, so a stale value is
        // never pinned to the edge.
        let streaming = frozenAt == nil && now - samples[samples.count - 1].t < 0.5
        let headPoint = streaming ? head.map { CGPoint(x: plotRight, y: y($0)) } ?? lastPoint : lastPoint

        // The settled body, then each freshly arrived packet as its own piece with its own
        // strength; the tail to the head takes the newest piece's strength.
        struct Piece { var path: Path; var opacity: Double }
        var pieces: [Piece] = []
        if settledEnd >= anchorIndex {
            var path = Path()
            path.move(to: firstDrawn)
            if settledEnd > anchorIndex {
                for index in (anchorIndex + 1)...settledEnd { path.addLine(to: point(index)) }
            }
            pieces.append(Piece(path: path, opacity: 1))
        }
        var index = max(settledEnd + 1, anchorIndex)
        while index <= lastDue {
            let arrival = samples[index].arrival
            let from = max(index - 1, anchorIndex)
            var path = Path()
            path.move(to: point(from))
            while index <= lastDue, samples[index].arrival == arrival {
                path.addLine(to: point(index))
                index += 1
            }
            pieces.append(Piece(path: path,
                                opacity: min(1, max(0, (now - arrival) / TraceHead.freshSeconds))))
        }
        if headPoint != lastPoint {
            var path = Path()
            path.move(to: lastPoint)
            path.addLine(to: headPoint)
            pieces.append(Piece(path: path, opacity: pieces.last?.opacity ?? 1))
        }

        // Soft fill under the curve reads as "load", the stroke as "now". ONE fill, at
        // full strength, under everything known: fading it piece by piece made the
        // shading pulse at the head with every packet (Nuri, 2026-09-19).
        //
        // **Closed at the line's OWN first x, never at 0.** A short buffer (fresh
        // session, tare, return from the home screen) starts the line mid-canvas, and
        // closing at 0 drew a diagonal "weird shadow" from the bottom-left corner
        // (Nuri, 2026-08-09).
        var fill = Path()
        fill.move(to: firstDrawn)
        if lastDue > anchorIndex {
            for index in (anchorIndex + 1)...lastDue { fill.addLine(to: point(index)) }
        }
        if headPoint != lastPoint { fill.addLine(to: headPoint) }
        fill.addLine(to: CGPoint(x: headPoint.x, y: size.height))
        fill.addLine(to: CGPoint(x: firstDrawn.x, y: size.height))
        fill.closeSubpath()

        // FADED IN FROM THE LEFT when the run begins on screen: a full-strength cliff
        // mid-canvas reads as load never pulled. Off-screen runs fill solid — the clip
        // is the boundary (the store keeps two seconds beyond the window so a full
        // buffer's start IS off screen; otherwise the shading jittered as points aged).
        context.drawLayer { layer in
            if firstDrawn.x > 0 {
                layer.clipToLayer { mask in
                    let ramp = CGRect(x: firstDrawn.x, y: 0, width: 40, height: size.height)
                    mask.fill(Path(ramp), with: .linearGradient(
                        Gradient(colors: [.black.opacity(0), .black]),
                        startPoint: CGPoint(x: ramp.minX, y: 0),
                        endPoint: CGPoint(x: ramp.maxX, y: 0)))
                    mask.fill(Path(CGRect(x: ramp.maxX, y: 0,
                                          width: max(0, size.width - ramp.maxX),
                                          height: size.height)),
                              with: .color(.black))
                }
            }
            layer.fill(fill, with: .linearGradient(
                Gradient(colors: [tint.opacity(0.28), tint.opacity(0.02)]),
                startPoint: CGPoint(x: 0, y: plotTop), endPoint: CGPoint(x: 0, y: size.height)))
        }

        for piece in pieces {
            var strokeContext = context
            strokeContext.opacity *= piece.opacity
            if lit {
                // History dims toward the left and NOW is full strength, so the eye lands on
                // the end that matters. Plain fills, no blur filter.
                strokeContext.stroke(piece.path, with: .linearGradient(
                    Gradient(colors: [tint.opacity(0.45), tint]),
                    startPoint: .zero, endPoint: CGPoint(x: size.width, y: 0)),
                    style: StrokeStyle(lineWidth: 2.5, lineCap: .round, lineJoin: .round))
            } else {
                strokeContext.stroke(piece.path, with: .color(tint),
                                     style: StrokeStyle(lineWidth: 2.5, lineCap: .round, lineJoin: .round))
            }
        }
        if lit {
            // The glow marks the live point from across a room.
            context.fill(Path(ellipseIn: CGRect(x: headPoint.x - 16, y: headPoint.y - 16, width: 32, height: 32)),
                         with: .radialGradient(Gradient(colors: [tint.opacity(0.55), tint.opacity(0)]),
                                               center: headPoint, startRadius: 0, endRadius: 16))
        }
        context.fill(Path(ellipseIn: CGRect(x: headPoint.x - 4, y: headPoint.y - 4, width: 8, height: 8)),
                     with: .color(tint))
        #if DEBUG
        TraceDrawProbe.shared.set(String(format:
            "STROKED size=%.0fx%.0f anchor=%d lastDue=%d/%d firstX=%.0f lastX=%.0f headY=%.0f pieces=%d opacity=%.2f",
            size.width, size.height, anchorIndex, lastDue, samples.count - 1,
            firstDrawn.x, lastPoint.x, headPoint.y, pieces.count, context.opacity))
        if TraceDrawProbe.logsHead {
            TraceDrawProbe.shared.logHead(now: now, head: headPoint, dueT: samples[lastDue].t,
                                          newestT: samples[samples.count - 1].t,
                                          pending: samples.count - 1 - lastDue)
        }
        #endif
    }

}
