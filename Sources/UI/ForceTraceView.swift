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

/// The live force trace: a rolling window of the gauge's readings, scrolling smoothly.
///
/// Time-based, not index-based: the Progressor delivers ~80 Hz samples in batches of
/// about eight, so index spacing plus redraw-on-arrival stutters ten times a second.
/// Points carry a PLAYBACK time (`DeviceStore.TracePoint.t` — monotone, built at
/// ingestion, immune to the device's counter restarting on tare/reconnect/re-start),
/// and the window's right edge advances with the wall clock in a `TimelineView`.
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
    }
    @State private var axis = AxisMemory()
    /// A TimelineView cannot notice on its own that a stopped trace has finally slid
    /// beyond the window: no sample changes at that instant. The single watcher task
    /// flips this state only when nothing remains drawable. A FRESH sample needs no
    /// help from the task at all: `deadlineReached` (below) compares this against the
    /// window's freshly-computed `newestTime` every render, so a stale expiry for an
    /// old sample simply stops matching the moment a new one lands.
    @State private var expiredNewestTime: TimeInterval?

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
    private func axisCeiling(now: TimeInterval) -> Double {
        // Walked without allocating a mapped array — this runs every frame.
        for sample in samples where sample.kg > axis.maxSeen { axis.maxSeen = sample.kg }
        // The band's ceiling has to be ON the axis with headroom above it, or the lane
        // you are aiming for sits jammed against the top edge of the card.
        let target = max(10, axis.maxSeen * 1.25, (thresholdKg ?? 0) * 1.6,
                         (targetBand?.upperBound ?? 0) * 1.25)
        let dt = min(max(now - axis.lastFrame, 0), 0.1)
        axis.lastFrame = now
        if axis.displayed == 0 || reduceMotion {
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
        let paused = reduceMotion || alreadyExpired || deadlineReached
        // Paused under Reduce Motion: the Canvas then redraws only when data changes,
        // which is the old stepping behaviour — correct here, because someone who asked
        // for less motion should not be given a continuously sliding graph.
        TimelineView(.animation(paused: paused)) { timeline in
            let _ = diagnostics?.drawing(now: ProcessInfo.processInfo.systemUptime)
            let now = timeline.date.timeIntervalSinceReferenceDate
            // Computed HERE, not in the Canvas closure: the axis memory is
            // MainActor-bound view state, and the draw closure only needs the number.
            let ceiling = axisCeiling(now: now)
            Canvas { context, size in
                draw(in: context, size: size, now: now, ceiling: ceiling)
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
        // exact anti-pattern CLAUDE.md already names for the draft stash ("COALESCES;
        // it does not cancel-and-restart"). A fresh sample needs none of that: it just
        // has to stop matching `expiredNewestTime`, which the render-time comparison
        // above already does for free. The watcher's only job is the opposite
        // direction — noticing when nothing is left to draw.
        .task { await watchForExpiry() }
        .onAppear { diagnostics?.graphOpened() }
        .onDisappear { diagnostics?.graphClosed() }
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
                      ceiling: Double) {
        let plotHeight = max(1, size.height - TraceInset.top - TraceInset.bottom)

        func y(_ kg: Double) -> CGFloat {
            let fraction = min(max(kg, 0), ceiling) / ceiling
            return TraceInset.top + plotHeight - CGFloat(fraction) * plotHeight
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
            context.fill(Path(lane), with: .color(Ink.tertiary.opacity(0.13)))
            for edge in [top, bottom] {
                var rule = Path()
                rule.move(to: CGPoint(x: 0, y: edge))
                rule.addLine(to: CGPoint(x: size.width, y: edge))
                context.stroke(rule, with: .color(Ink.tertiary.opacity(0.5)),
                               style: StrokeStyle(lineWidth: 1, dash: [4, 4]))
            }
        } else if let thresholdKg, thresholdKg > 0, thresholdKg < ceiling {
            var rule = Path()
            rule.move(to: CGPoint(x: 0, y: y(thresholdKg)))
            rule.addLine(to: CGPoint(x: size.width, y: y(thresholdKg)))
            context.stroke(rule, with: .color(Ink.tertiary.opacity(0.55)),
                           style: StrokeStyle(lineWidth: 1, dash: [4, 4]))
        }

        guard samples.count > 1, let newest = samples.last else { return }

        // How far the window's right edge has slid past the newest point. Wall-clock,
        // so it grows every frame; `t` is the store's slewed playback time, so radio
        // jitter doesn't move the trace.
        let drift = max(0, now - newest.t)

        /// Smoothed inline rather than via a precomputed array. At 120 Hz the two
        /// 480-element arrays this replaces were ~1.4 MB/s of pure allocation churn for
        /// arithmetic that measures 0.03 % of a frame — the cost was never the maths,
        /// it was the garbage.
        func smoothed(_ i: Int) -> Double {
            guard i > 0, i < samples.count - 1 else { return samples[i].kg }
            return (samples[i - 1].kg + samples[i].kg + samples[i + 1].kg) / 3
        }

        func x(_ index: Int) -> CGFloat {
            let age = (newest.t - samples[index].t) + drift
            return size.width - CGFloat(age / Self.windowSeconds) * size.width
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
        context.opacity = min(1, (now - samples[runStart].t) / 0.5)

        var line = Path()
        var firstDrawn: CGPoint?
        for index in anchorIndex..<samples.count {
            let point = CGPoint(x: x(index), y: y(smoothed(index)))
            if firstDrawn == nil {
                line.move(to: point)
                firstDrawn = point
            } else {
                line.addLine(to: point)
            }
        }
        guard let firstDrawn else { return }

        // WHILE DATA IS FLOWING, the head rides the right edge. The newest sample is
        // always one BLE batch (~100 ms) old, so placing the head at its timestamp left
        // a sliver of card between fill and edge that breathed at 10 Hz — the firmware's
        // delivery cadence made visible, which is exactly what this view exists to hide.
        // The edge value is a 250 ms running average (batch noise damped, a real pull
        // still tracked); the historical points stay placed by their own timestamps, so
        // the anti-stutter geometry is untouched. The 0.5 s staleness cap keeps the old
        // behaviour after stop/disconnect: no fresh data, no synthetic head, and the
        // trace slides away instead of pinning a stale flat line to the edge forever.
        let head: CGPoint
        if drift < 0.5 {
            // 120 ms — roughly one BLE batch. Long enough to damp batch noise, short
            // enough that a fast pull's onset doesn't drag a laggy hook at the tip
            // (at 250 ms the averaged head visibly trailed the line during hard pulls).
            var sum = 0.0, count = 0.0
            var i = samples.count - 1
            while i >= 0, newest.t - samples[i].t <= 0.12 {
                sum += samples[i].kg
                count += 1
                i -= 1
            }
            let edgeKg = count > 0 ? sum / count : smoothed(samples.count - 1)
            head = CGPoint(x: size.width, y: y(edgeKg))
            line.addLine(to: head)
        } else {
            head = CGPoint(x: x(samples.count - 1), y: y(smoothed(samples.count - 1)))
        }

        // Soft fill under the curve reads as "load", the stroke reads as "now".
        //
        // **Closed at the line's OWN first x, never at 0.** With a full buffer the curve
        // already starts off the left edge and the two are the same point, which is why
        // this went unnoticed — but any short buffer (a fresh session, a tare, coming back
        // from the home screen) starts the line mid-canvas, and closing at 0 drew a
        // diagonal from the bottom-left corner up to it: the "weird shadow under the
        // graph" in Nuri's screenshots. The fill now drops straight down from where the
        // data actually begins.
        var fill = line
        fill.addLine(to: CGPoint(x: head.x, y: size.height))
        fill.addLine(to: CGPoint(x: firstDrawn.x, y: size.height))
        fill.closeSubpath()

        // FADED IN FROM THE LEFT. Closing the fill under its first point is correct, but
        // on a short buffer — the first seconds after a tare, a reconnect, or coming back
        // from the home screen — it drops a full-strength vertical cliff in the middle of
        // the card, which reads as a wall of load that was never pulled (Nuri, 2026-08-09).
        // The mask ramps the fill up over its first 40 pt, so history begins rather than
        // starts. Only the fill: the STROKE is real data and stays crisp to its first point.
        context.drawLayer { layer in
            // The ramp exists for a run that BEGINS on-screen only. A run already
            // extending past the left edge has no beginning to soften — and ramping
            // it anyway re-anchored the fade to whichever sample happened to be the
            // off-screen anchor, so the shading's left edge JUMPED each time a point
            // aged out while the stroke above it slid smoothly (Nuri, 2026-08-18:
            // "the shading under it is not smoothly disappearing"). Off-screen runs
            // fill solid; the view's clip is the boundary, and a clipped edge cannot
            // jump. The handover is seamless: the moment a run's start crosses x = 0
            // the ramp's visible remainder is already nil.
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
                startPoint: .zero, endPoint: CGPoint(x: 0, y: size.height)))
        }

        context.stroke(line, with: .color(tint),
                       style: StrokeStyle(lineWidth: 2.5, lineCap: .round, lineJoin: .round))

        context.fill(Path(ellipseIn: CGRect(x: head.x - 4, y: head.y - 4, width: 8, height: 8)),
                     with: .color(tint))
    }

}
