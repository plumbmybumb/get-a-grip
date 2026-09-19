// SPDX-License-Identifier: MPL-2.0
// Original contributions Copyright 2026 Nuri Bruner.

import SwiftUI

/// Keeps the curve off the card's edges. Without the bottom inset a resting gauge
/// draws its zero line exactly on the boundary, where the rounded corners clip it and
/// it reads as a rendering glitch rather than as "no load".
///
/// File-scope rather than static members of the View: `View` is `@MainActor`, and
/// `Canvas`'s draw closure is not — statics on the struct can't be read from inside it.
private enum TraceInset {
    static let top: CGFloat = 12
    static let bottom: CGFloat = 10
}

/// Axis constants shared with the layout that places a full-bleed plot — file scope,
/// like `TraceInset`, so a plain geometry struct can read them.
enum TraceAxis {
    /// How much headroom the axis keeps above the highest load it has seen, and above
    /// a target band's ceiling: the peak lands at 1 / this of the plot's height.
    /// `RunnerView` places its full-bleed plot so that exactly this headroom — and
    /// nothing the curve normally reaches — lies under the glass panel.
    static let ceilingHeadroom: Double = 1.25
}

#if DEBUG
/// A one-line record of the trace's LAST draw, readable from any actor. The draw runs in
/// a nonisolated `Canvas` closure, so a healthy buffer that still shows no line can only
/// be explained from inside `draw` — this carries out what it decided. Read by the DEBUG
/// diagnostics dumper.
final class TraceDrawProbe: @unchecked Sendable {
    static let shared = TraceDrawProbe()
    /// `-traceHeadLog`: every draw's head position is kept and written to
    /// `Documents/tracehead.csv` by the diagnostics dumper. Smoothness is judged from
    /// those rows — the head's step per frame, and how many points came due at once —
    /// instead of from watching a screen; that is how the jitter buffer was sized.
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
/// whose time has not come yet — the tail of a late, bunched delivery — waits beyond the
/// right edge and slides in when it is due, so bunched arrival never bends the line.
///
/// **This view is deliberately STATELESS about time.** Its first version kept anchor
/// state (`@State` device-µs ↔ wall-clock pairs) and died on real hardware: a tare
/// cleared the buffer, the anchors survived with pre-tare values, and there was no path
/// back — a blank graph beside a live kg readout until the screen was re-entered. State
/// that models another clock can be poisoned; geometry from (now − t) cannot.
struct ForceTraceView: View {
    var samples: [DeviceStore.TracePoint]
    /// Drawn as a dashed rule: the load a rep has to beat for its clock to run.
    var thresholdKg: Double?
    /// **The range this rep is asking for, drawn as a lane to land the curve in**
    /// (Nuri, 2026-08-09). When there is one it REPLACES the threshold rule rather than
    /// joining it: the band's floor is what the clock now runs off, so a third horizontal
    /// line would be a second answer to the same question.
    var targetBand: ClosedRange<Double>?
    /// Colour of the trace — the caller passes the phase tint so the graph and the
    /// rest of the screen escalate together.
    var tint: Color = StatusTint.engaged
    /// The active gauge's `nominalSampleRate`, which is the only thing that says how far
    /// apart two ordinary points are — see `streamGapSeconds`. Passed as a VALUE, like
    /// `samples`, so this view still touches no store.
    var nominalSampleRate: Double = 80

    /// Broadcast gauges bridge every point inside the window instead of breaking runs
    /// at gaps — see `streamGapSeconds`. A value, for the same previewability reason.
    var bridgesSparseDelivery: Bool = false
    var diagnostics: PipelineDiagnostics? = nil
    /// A completed effort keeps its measured picture instead of scrolling off screen.
    var frozenAt: TimeInterval? = nil
    /// **Where the plot sits inside the canvas.** In a card it is the card less the
    /// small edge clearances; when the trace is the SCREEN's background
    /// (`RunnerView`'s stacked layout) the canvas is the whole display and the caller
    /// places the plot by the glass it runs beneath — ceiling under the information
    /// panel, floor under the controls. A value, like everything else here.
    var plot: PlotInsets = .card
    /// **Drawn as a LIT object** — the runner's full-bleed trace. The stroke brightens
    /// toward now, the live point glows, and the lane's edges are solid hairlines: on
    /// an open screen a flat 2.5 pt line and two dashed rules read as chart furniture,
    /// and a curve you look at for twenty minutes should look like the thing being
    /// measured. A card keeps the plain drawing, so no other screen changes.
    var lit: Bool = false

    /// The band of the canvas that 0 kg → ceiling maps onto, as insets from the
    /// canvas's own edges.
    struct PlotInsets: Equatable {
        var top: CGFloat = TraceInset.top
        var bottom: CGFloat = TraceInset.bottom
        /// Clearance between the head of the trace and the right edge. Zero in a card,
        /// whose own inset keeps the head dot whole; a full-bleed canvas ends at the
        /// physical screen edge under the screen border, so the head steps in from it.
        var trailing: CGFloat = 0
        /// The card's own clearances.
        static let card = PlotInsets()
    }

    @Environment(\.accessibilityReduceMotion) private var reduceMotion


    /// How much history is on screen. Six seconds shows a whole 10 s hold's shape
    /// without squeezing the detail out of it.
    private static let windowSeconds: Double = 6

    /// Longer than this between two samples and the stream stopped. Comfortably above a
    /// BLE batch (~100 ms) and a dropped one, comfortably below anything a human would
    /// call an interruption.
    ///
    /// **Three of this gauge's own sample periods, floored at the Progressor's 0.35 s.**
    /// The flat 0.35 was a Tindeq number — 3.5 batches at ten notifications a second — and
    /// only ~2.8 periods of an 8 Hz crane scale, whose duplicate advertisements are
    /// best-effort even in the foreground. Two missed frames there is 375 ms, which
    /// declared a gap and threw away the entire drawn history for a graph showing one
    /// point. Keyed to the rate, an ordinary miss stays ordinary.
    ///
    /// And for a BROADCAST gauge there is no gap threshold at all: delivery is bursty
    /// by nature — clumps of advertisements with multi-second holes between them — so
    /// every hole "started a new run" and the whole drawn history vanished at each one,
    /// which is exactly the disappearing graph Nuri's first WH-C06 session showed
    /// (2026-08-17, his ask verbatim: "string together all of those random data points
    /// into a graph instead of dropping it"). Points older than the window still fall
    /// off the left edge on their own; a scale that genuinely left is the silence
    /// watchdog's job, not the renderer's.
    private var streamGapSeconds: Double {
        bridgesSparseDelivery ? .greatestFiniteMagnitude : max(0.35, 3.0 / max(1, nominalSampleRate))
    }

    /// Cross-frame axis state. A reference type on purpose: it is written in the
    /// TimelineView builder every frame, and routing that through `@State` would
    /// re-enter SwiftUI's update machinery 120×/s for a value only the next frame
    /// reads. Only ever touched from the view body (MainActor).
    private final class AxisMemory {
        var maxSeen: Double = 0
        var displayed: Double = 0
        var lastFrame: TimeInterval = 0
        /// The latest sample's playback time, refreshed every body evaluation. A plain
        /// field rather than `@State` so the watcher task below can read "what's newest
        /// right now" without a Task teardown/rebuild every time a sample arrives — see
        /// `watchForExpiry()`.
        var newestTime: TimeInterval?
        /// The head dot's eased value — a per-frame value, not a clock.
        var headKg: Double?
    }
    @State private var axis = AxisMemory()
    /// A TimelineView cannot notice on its own that a stopped trace has finally slid
    /// beyond the window: no sample changes at that instant. The single watcher task
    /// flips this state only when nothing remains drawable. A FRESH sample needs no
    /// help from the task at all: `deadlineReached` (below) compares this against the
    /// window's freshly-computed `newestTime` every render, so a stale expiry for an
    /// old sample simply stops matching the moment a new one lands.
    @State private var expiredNewestTime: TimeInterval?
    /// The registered observer can differ from the incoming value until SwiftUI
    /// delivers its lifecycle update. Keep the exact object we opened so a frozen
    /// trace cannot close another visible graph's registration, and a resumed trace
    /// registers even though its view identity never changed.
    @State private var registeredDiagnostics: PipelineDiagnostics?
    @State private var isDiagnosticsVisible = false

    /// The y-axis ceiling, LATCHED and EASED — never a per-frame function of the
    /// rolling buffer.
    ///
    /// Deriving it from `samples.max()` was the rubber-banding: the buffer only
    /// remembers six seconds, so every pull grew the axis on the way up, and six
    /// seconds later — when that pull's peak slid out of the buffer — the whole
    /// graph stretched back, dragging the threshold line with it. An axis keyed to
    /// what happened six seconds ago reads as random motion.
    ///
    /// So the max is latched for the life of the view (a training screen wants one
    /// stable scale per session, exactly like peakKg), and the displayed ceiling
    /// eases toward its target over ~100 ms so the one legitimate rescale — a new
    /// personal peak mid-session — is a glide, not a snap.
    /// Seconds since the last frame, clamped: the one number every per-frame ease uses.
    private func frameDelta(now: TimeInterval) -> TimeInterval {
        let dt = min(max(now - axis.lastFrame, 0), 0.1)
        axis.lastFrame = now
        return dt
    }

    /// The head dot settles toward the newest reading over ~60 ms — no overshoot, because an
    /// overshooting head would draw a load nobody pulled, and short enough that it is a
    /// softening of the packet step rather than a delay of it. Snapped on a frozen trace.
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
        // Recording through a helper because a bare assignment expression is not a
        // View and does not compile inside a ViewBuilder body.
        let newestTime = recordNewest(samples.last?.t)
        let alreadyExpired = newestTime.map {
            Date().timeIntervalSinceReferenceDate - $0 >= Self.windowSeconds
        } ?? true
        // Key the deadline state to the sample that armed it. A fresh sample therefore
        // resumes immediately, before the replacement task gets its first turn to run.
        let deadlineReached = newestTime == nil || expiredNewestTime == newestTime
        let paused = frozenAt != nil || alreadyExpired || deadlineReached
        // NOT paused under Reduce Motion. It used to be: the Canvas then redrew only when
        // data arrived, "the old stepping behaviour", on the theory that someone who
        // asked for less motion should not get a continuously sliding graph. On a small
        // card each step was two points. On the full-screen canvas — an iPad's 1180 pt
        // window — every packet became a 37 pt lurch of the whole picture five times a
        // second, the head and the fill jumping with it: far MORE motion, and the abrupt
        // kind Reduce Motion exists to remove (Nuri's iPad, 2026-09-19: "so laggy").
        // A slow, steady slide is the gentlest way a graph of time can move; what the
        // setting drops here is the decoration (the 0.85 opacity below), not the tick.
        TimelineView(.animation(paused: paused)) { timeline in
            let _ = diagnostics?.drawing(now: ProcessInfo.processInfo.systemUptime)
            // **`now` is the WALL clock, not `timeline.date`.** The schedule's date rides
            // the animation clock, which stops while the device is asleep or the app is
            // suspended; the sample timestamps in `t` are built from `Date()`, which does
            // not. After a lock or a background spell the two diverge by the whole gap —
            // an iPad 25 s behind — and since the draw positions every point by (now − t),
            // a mismatched `now` put the entire buffer in the "future" and the due-only
            // renderer drew nothing at all, while the phone (never suspended mid-session)
            // was fine (Nuri's iPad, 2026-09-19; found by a draw-time probe reading
            // `newestAhead=25166ms` against a buffer the store showed at −6 ms). Reading
            // `Date()` here — the SAME clock the store stamps `t` with — is what keeps the
            // renderer's clock and the data's clock identical. `timeline.date` now serves
            // only its real purpose: waking the body every frame.
            let _ = timeline.date
            let now = frozenAt ?? Date().timeIntervalSinceReferenceDate
            // Computed HERE, not in the Canvas closure: the axis memory is
            // MainActor-bound view state, and the draw closure only needs the number.
            let dt = frameDelta(now: now)
            let ceiling = axisCeiling(dt: dt)
            let head = headValue(dt: dt)
            Canvas { context, size in
                draw(in: context, size: size, now: now, ceiling: ceiling, head: head)
            }
        }
        .opacity(reduceMotion ? 0.85 : 1)
        // The off-screen anchor point (above) can land well left of x = 0 on a
        // sparse-delivery gauge; without this the overhanging segment would draw
        // outside the card rather than being invisible, as intended, past the edge.
        .clipped()
        .accessibilityHidden(true)
        // ONE long-lived watcher for the view's whole life, not one per sample. The
        // previous `.task(id: newestTime)` tore down and reallocated a ~6 s sleep Task
        // on every rendered sample — i.e. at display rate for a 21-minute session — the
        // exact anti-pattern the builder's draft stash already avoids by COALESCING
        // rather than cancelling and restarting. A fresh sample needs none of that: it just
        // has to stop matching `expiredNewestTime`, which the render-time comparison
        // above already does for free. The watcher's only job is the opposite
        // direction — noticing when nothing is left to draw.
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

    /// Records the render pass's one message to the watcher task — a plain field
    /// write, not `@State`, so it never itself triggers a re-render.
    private func recordNewest(_ t: TimeInterval?) -> TimeInterval? {
        axis.newestTime = t
        return t
    }

    /// Sleeps until the newest known sample would age out of the window, then checks
    /// whether a fresher one arrived while asleep. Marks that run expired only when
    /// nothing did, then loops — this single task is the replacement for the
    /// one-Task-per-sample churn described above.
    private func watchForExpiry() async {
        while !Task.isCancelled {
            guard let newest = axis.newestTime else {
                try? await Task.sleep(for: .seconds(1))
                continue
            }
            // Once this run is marked expired nothing changes until a FRESH sample
            // lands — and a fresh sample un-expires the render on its own (the
            // `deadlineReached` comparison stops matching), so the quiet wait can be
            // long. Without this the loop woke every second for the rest of a
            // 21-minute session to re-confirm a stopped trace was still stopped.
            if expiredNewestTime == newest {
                try? await Task.sleep(for: .seconds(10))
                continue
            }
            let age = max(0, Date().timeIntervalSinceReferenceDate - newest)
            let remaining = max(0, Self.windowSeconds - age)
            // Floored at 1 s even once "expired": re-checking a stalled stream costs
            // nothing here, but sleeping for `remaining == 0` every iteration would
            // spin the loop as fast as the scheduler allows.
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
            // A LANE, not two lines. The pair of dashed rules alone left the eye to work
            // out which side of each one it was on; a filled band is a place to be, and
            // the curve is either in it or it isn't. Neutral ink deliberately — the TRACE
            // carries the phase colour, and a tinted lane behind a tinted curve would put
            // two competing signals in the same square inch.
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

        /// Smoothed inline rather than via a precomputed array. At 120 Hz the two
        /// 480-element arrays this replaces were ~1.4 MB/s of pure allocation churn for
        /// arithmetic that measures 0.03 % of a frame — the cost was never the maths,
        /// it was the garbage.

        // TRUE age, so every point sits where its own time puts it — including PAST the
        // right edge when the wall clock has not reached it. Bunched delivery (an iPad's
        // radio stack hands the stream over in clumps of a second or more) puts the last
        // points of each clump ahead of wall time; pinning the newest point to the edge
        // instead, as this used to, lurched the whole line left by a clump each time one
        // landed, and the store then dropped the buffer to keep its timeline sane — which
        // is why Nuri's iPad drew no line at all (2026-09-19). Those points now wait beyond
        // the edge and slide in on time, a jitter buffer, and the line stays continuous
        // whatever the delivery looks like. `.clipped()` hides the waiting segment.
        func x(_ index: Int) -> CGFloat {
            let age = now - samples[index].t
            return plotRight - CGFloat(age / Self.windowSeconds) * size.width
        }

        // **START AT THE NEWEST UNBROKEN RUN.** A gap in the buffer means the stream
        // stopped — backgrounded, disconnected, tared — and the points either side of it
        // are minutes apart in the hand even though they are adjacent in the array.
        // Drawing across it put a horizontal bridge through the middle of the graph and
        // an impossible cliff at each end (Nuri, 2026-08-09: "you go to your home screen
        // and come back, the graph is all messed up"). A gap is a boundary, not data.
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
        // points already on screen made the line's visible start SNAP forward the
        // instant the oldest on-screen point aged past the edge — at 80 Hz the next
        // point is a fraction of a pixel further right and the snap is invisible, but
        // a broadcast gauge's readings land seconds apart, so the same snap is a
        // visible chunk of trace vanishing at once (Nuri, 2026-08-17: "it should just
        // continue off screen uninterrupted. There's no real value in having it
        // disappear"). Walking forward from the run's own start to the last point
        // whose x is still left of 0 gives ONE off-screen anchor per run — the
        // segment that crosses x = 0 is drawn from it every frame, so the trace
        // slides continuously off the left edge instead of jumping. The anchor can
        // land well off screen; `.clipped()` below is what makes that harmless.
        var anchorIndex = runStart
        while anchorIndex < samples.count - 1, x(anchorIndex + 1) < 0 {
            anchorIndex += 1
        }

        // **THE TRACE FADES BACK IN.** Coming back from the home screen the buffer starts
        // again from nothing, and a graph that simply appears — two seconds wide, mid-card
        // — reads as a glitch rather than as a recording resuming (Nuri, 2026-08-09).
        //
        // Derived from the DATA, with no state at all: how old the oldest drawn sample is
        // IS how long this run has been going, so the run reveals itself over its first
        // half second and is fully opaque from then on. Nothing to reset, nothing to
        // poison, and it covers every case the buffer restarts in — resume, tare,
        // reconnect, the first pull of a session — without any of them being special.
        // Same reasoning as this view's refusal to keep clock anchors.
        var context = context
        context.opacity = frozenAt == nil ? min(1, (now - samples[runStart].t) / 0.5) : 1

        // Everything known is drawn, at its true place in time. The clock stamps a packet's
        // newest reading at its arrival, so "due" is simply "arrived" — the filter only
        // catches the few milliseconds a slightly early packet's tail sits past now.
        var lastDue = samples.count - 1
        while lastDue >= anchorIndex, samples[lastDue].t > now { lastDue -= 1 }
        guard lastDue >= anchorIndex else {
            #if DEBUG
            TraceDrawProbe.shared.set("early: nothingDue anchor=\(anchorIndex) count=\(samples.count)")
            #endif
            return
        }

        // **THE FRESH SEGMENT MATERIALIZES; THE HEAD SETTLES.** Raw data arrives in packets
        // of ~190 ms, so a whole segment of line becomes known at once, and drawn at full
        // strength that is a pop five times a second — the jitter Nuri saw on a plot this
        // tall (2026-09-19). Positions are never touched: a buffered glide and a live pen
        // with a connector were both tried the same day and rejected (one felt behind the
        // hand, the other looked wrong — "just take the raw data and feed it in"). Instead
        // the readings that arrived within the last `TraceHead.freshSeconds` fade in over
        // those frames, and the head dot eases toward the newest reading over ~60 ms.
        // Visual only: nothing is delayed, nothing is invented.
        let freshSince = now - TraceHead.freshSeconds
        var settledEnd = lastDue
        if frozenAt == nil {
            while settledEnd >= anchorIndex, samples[settledEnd].arrival > freshSince { settledEnd -= 1 }
        }

        func point(_ i: Int) -> CGPoint { CGPoint(x: x(i), y: y(smoothed(i))) }
        let firstDrawn = point(anchorIndex)
        let lastPoint = point(lastDue)

        // WHILE DATA IS FLOWING the head rides the right edge — the newest reading, eased —
        // and a short segment joins the last drawn point to it: a packet's width at most,
        // as the newest point slides left until the next packet lands at the edge. After
        // half a second of silence there is no synthetic head, and the trace slides away
        // rather than pinning a stale value to the edge.
        let streaming = frozenAt == nil && now - samples[samples.count - 1].t < 0.5
        let headPoint = (streaming && head != nil) ? CGPoint(x: plotRight, y: y(head!)) : lastPoint

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

        // Soft fill under the curve reads as "load", the stroke reads as "now". ONE fill,
        // at full strength, under everything known — the fade belongs to the stroke alone.
        // Fading the fill piece by piece made the shading pulse in and out at the head with
        // every packet (Nuri, 2026-09-19), which is the opposite of what a fill is for.
        //
        // **Closed at the line's OWN first x, never at 0.** With a full buffer the curve
        // already starts off the left edge and the two are the same point — but a short
        // buffer (a fresh session, a tare, coming back from the home screen) starts the
        // line mid-canvas, and closing at 0 drew a diagonal from the bottom-left corner up
        // to it: the "weird shadow under the graph" in Nuri's screenshots (2026-08-09).
        var fill = Path()
        fill.move(to: firstDrawn)
        if lastDue > anchorIndex {
            for index in (anchorIndex + 1)...lastDue { fill.addLine(to: point(index)) }
        }
        if headPoint != lastPoint { fill.addLine(to: headPoint) }
        fill.addLine(to: CGPoint(x: headPoint.x, y: size.height))
        fill.addLine(to: CGPoint(x: firstDrawn.x, y: size.height))
        fill.closeSubpath()

        // FADED IN FROM THE LEFT when the run begins on screen: a full-strength vertical
        // cliff mid-canvas reads as a wall of load that was never pulled. Off-screen runs
        // fill solid — the clip is the boundary, and a clipped edge cannot jump (the store
        // keeps two seconds more than the window shows so a full buffer's start IS off
        // screen; the shading used to jitter as points aged out when it was not).
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
                // History dims toward the left and NOW is full strength, so the eye lands
                // on the end of the line that matters. Plain fills — no blur filter,
                // nothing per frame that a Canvas does not already do.
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
