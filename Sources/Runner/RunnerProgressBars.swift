// SPDX-License-Identifier: MPL-2.0
// Original contributions Copyright 2026 Nuri Bruner.

import SwiftUI

// MARK: - Where the session's position lives on the runner

// Two rows in the panel, between the hero numbers and the SET / PULL labels:
//
// 1. `RunnerTimeBar` — ONE full-width bar that is always there, so the panel never
//    changes shape between pull and rest: the hold filling in bleu while you pull, the
//    rest's own countdown draining in steel while you rest.
// 2. `RoutinePills` — the whole routine as quiet pills, one per pull, a wider gap
//    between sets: what is done, what was skipped, and which pull is next.
//
// Rules both keep:
// - **Secondary to the hero numbers.** Ink and steel; the only bleu is the hold whose
//   clock is running (bleu IS the live-force signal) and, at rest, the pill the rest
//   is waiting for — the single accent pointing forward.
// - **A pill is never a dot.** A circle is a SESSION in this app and a capsule 22 × 38
//   is a FINGER (`IslandHand`); the pills stay at least 1.5× as long as they are tall,
//   and a dense plan drops its gaps rather than shrinking into dots.
// - **High-frequency state stays in a leaf.** The pills read the coarse snapshot's
//   results; only `RunnerTimeBar` observes `repProgress` and the rest countdown.

/// Where the session is, folded from the runner's resolved slots and results. Built in
/// the runner's body, which the coarse snapshot already invalidates ~1–2×/s; `results`
/// only changes when `snapshot.completedRepCount` does, so reading it there is safe.
struct SessionProgressModel: Equatable {
    /// Pull counts per set, in order.
    var setSizes: [Int]
    /// One per FINISHED pull: true = completed, false = skipped or aborted. A skipped
    /// pull is used up but did not happen, so it draws lighter than a completed one.
    var finished: [Bool]
    /// The pull being pulled, or the next one while resting; nil once finished.
    var current: Int?

    init(slots: [RepSlot], results: [RepSummary]) {
        var sizes: [Int] = []
        var lastSet: Int?
        for slot in slots {
            if slot.setIndex == lastSet { sizes[sizes.count - 1] += 1 } else { sizes.append(1) }
            lastSet = slot.setIndex
        }
        setSizes = sizes
        finished = results.map { $0.outcome == .completed }
        let next = results.count
        current = next < slots.count ? next : nil
    }
}

// MARK: - The routine as pills

/// Pull slots along `width`: ~2 pt between pulls, ~6 pt between sets. Dense plans
/// shrink the pull gap to 1 pt, then drop it, keeping only the set gaps (which
/// themselves narrow before a set becomes a sliver).
enum RoutinePillLayout {
    static func cells(sizes: [Int], width: CGFloat,
                      pullGap preferredPullGap: CGFloat = 2,
                      setGap preferredSetGap: CGFloat = 6) -> [ClosedRange<CGFloat>] {
        let total = sizes.reduce(0, +)
        guard total > 0, width > 0 else { return [] }
        let sets = sizes.count
        var setGap = sets > 1 ? preferredSetGap : 0
        var pullGap = preferredPullGap
        func unit() -> CGFloat {
            (width - setGap * CGFloat(sets - 1) - pullGap * CGFloat(total - sets)) / CGFloat(total)
        }
        // A pill must stay at least 1.5× as long as it is tall: at 60 pulls a ~4 pt
        // slot drew a row of DOTS, and a circle is a session in this app. So the pull
        // gap narrows under an 8 pt slot and goes under 6 pt, leaving set gaps only.
        if unit() < 8, pullGap > 1 { pullGap = 1 }
        if unit() < 6 { pullGap = 0 }
        while unit() < 1, setGap > 1 { setGap -= 1 }
        let slot = max(0.25, unit())
        var cells: [ClosedRange<CGFloat>] = []
        var cursor: CGFloat = 0
        for size in sizes {
            for index in 0..<size {
                cells.append(cursor...(cursor + slot))
                cursor += slot + (index < size - 1 ? pullGap : 0)
            }
            cursor += setGap
        }
        return cells
    }
}

/// The pills in `include`. Round-ended at the line's own height, so a pill is a short
/// piece of the same capsule, never an outlined box.
struct RoutinePillsShape: Shape {
    var cells: [ClosedRange<CGFloat>]
    var include: [Int]

    func path(in rect: CGRect) -> Path {
        var path = Path()
        let radius = rect.height / 2
        for index in include where cells.indices.contains(index) {
            let start = cells[index].lowerBound
            let end = cells[index].upperBound
            guard end > start + 0.01 else { continue }
            let box = CGRect(x: rect.minX + start, y: rect.minY, width: end - start, height: rect.height)
            // Pills that TOUCH (a dense plan with no pull gap) meet square: rounding them
            // would notch a continuous line at every pull.
            let touches = (index > 0 && cells[index].lowerBound - cells[index - 1].upperBound < 0.5)
                || (index + 1 < cells.count && cells[index + 1].lowerBound - cells[index].upperBound < 0.5)
            let r = touches ? 0 : min(radius, box.width / 2)
            path.addRoundedRect(in: box, cornerSize: CGSize(width: r, height: r), style: .continuous)
        }
        return path
    }
}

/// The whole routine under the time bar, ALWAYS — pulling and resting alike.
struct RoutinePills: View {
    var model: SessionProgressModel
    /// Working, releasing, or paused inside either.
    var isLive: Bool

    /// SECONDARY to the time bar above: slimmer, and quieter.
    static let height: CGFloat = 3

    /// Quieter through a lighter TRACK and a slim pill, not a lighter ink: at 3 pt, done
    /// ink at 0.46 measured 2.5:1 against the track in light mode (thin shapes are
    /// mostly antialiased edge), so done stays at 0.6 to clear 3:1.
    private static let track = Color(uiColor: .tertiarySystemFill)
    private static let done = Ink.primary.opacity(0.6)
    /// A skipped pull is used up but was not pulled.
    private static let skipped = Ink.primary.opacity(0.2)

    /// While a pull runs, the current pill is marked in INK — the time bar is the one
    /// bleu thing on the panel. At rest the time bar turns steel, and the next pill takes
    /// the bleu as the single accent pointing forward. 0.55 measured 2.5:1 on the light
    /// panel, under the 3:1 floor.
    private var next: Color {
        isLive ? Ink.primary.opacity(0.85) : StatusTint.engaged.opacity(0.72)
    }

    /// Whether the pull under way carries the live mark: armed has not started it.
    static func isLive(_ phase: RunnerPhase) -> Bool {
        let inner: RunnerPhase
        if case .paused(let wrapped) = phase { inner = wrapped } else { inner = phase }
        switch inner {
        case .working, .releasing: return true
        default: return false
        }
    }

    var body: some View {
        GeometryReader { proxy in
            let cells = RoutinePillLayout.cells(sizes: model.setSizes, width: proxy.size.width)
            let completed = model.finished.indices.filter { model.finished[$0] }
            let skipped = model.finished.indices.filter { !model.finished[$0] }
            ZStack(alignment: .leading) {
                RoutinePillsShape(cells: cells, include: Array(cells.indices))
                    .fill(Self.track)
                RoutinePillsShape(cells: cells, include: completed)
                    .fill(Self.done)
                RoutinePillsShape(cells: cells, include: skipped)
                    .fill(Self.skipped)
                if let current = model.current {
                    RoutinePillsShape(cells: cells, include: [current])
                        .fill(next)
                }
            }
            .clipShape(Capsule(style: .continuous))
        }
        .frame(height: Self.height)
    }
}

// MARK: - The time bar

/// ONE bar that is always there — so the panel never changes shape between pull and
/// rest (owner, 2026-09-25).
///
/// - Pulling: the hold bar (systemFill track, bleu fill growing with held time).
///   Released but still on the edge: full. Armed: empty, waiting.
/// - Rest, set break, count-in: the rest's own countdown, DRAINING from full to empty
///   in the calm steel. Drain, not fill, because it is time REMAINING: it shrinks as
///   the big numeral counts down, and it makes both seams continuous — a finished hold
///   is a FULL bleu bar and a new rest is a FULL steel bar (only the colour changes),
///   and a rest that has run out is an EMPTY bar exactly where the next hold starts.
/// - Paused: frozen — the engine reads a paused countdown against `pausedAt`.
///
/// A LEAF: it alone reads `repProgress` and `phaseRemainingFraction`. The fraction is
/// the engine's own countdown, the same one behind the rest numeral, so they cannot
/// disagree.
struct RunnerTimeBar: View {
    enum Mode: Equatable {
        case hold, armed, released, countdown, none

        /// What the one bar measures in this phase.
        init(_ phase: RunnerPhase) {
            let inner: RunnerPhase
            if case .paused(let wrapped) = phase { inner = wrapped } else { inner = phase }
            switch inner {
            case .working: self = .hold
            case .releasing: self = .released
            case .armed: self = .armed
            case .resting, .leadIn: self = .countdown
            case .idle, .finished, .paused: self = .none
            }
        }
    }

    /// PRIMARY: a touch heavier than the pills below it.
    static let height: CGFloat = 6

    var session: RunnerSession
    var mode: Mode
    /// Changes per pull / per rest, so each gets a fresh layer: the hold that ends
    /// fades out FULL, the rest that ends fades out EMPTY.
    var identity: Int
    @Environment(\.accessibilityReduceMotion) private var reduceMotion

    var body: some View {
        let fraction: Double = switch mode {
        case .hold: session.repProgress
        case .released: 1
        case .countdown: session.phaseRemainingFraction ?? 0
        case .armed, .none: 0
        }
        let isRest = mode == .countdown
        ZStack(alignment: .leading) {
            // What the system `ProgressView` draws as its track (measured).
            Capsule(style: .continuous).fill(Color(uiColor: .systemFill))
            GeometryReader { proxy in
                // A rectangle clipped by the track, as the system bar draws it: the last
                // second of a rest is a sliver on the rounded end, not a round DOT (a
                // circle is a session in this app).
                Rectangle()
                    .fill(isRest ? StatusTint.calm : StatusTint.engaged)
                    .frame(width: proxy.size.width * max(0, min(1, fraction)))
                    .animation(reduceMotion ? nil : Motion.measuredProgress, value: fraction)
            }
            .id(TimeBarKey(isRest: isRest, identity: identity))
            .transition(.opacity)
        }
        .clipShape(Capsule(style: .continuous))
        .frame(height: Self.height)
        // The pull ↔ rest seam: a colour change on a bar that is full (or empty) on both
        // sides of it. Reduce Motion keeps it — it is already a cross-fade.
        .animation(Motion.state(reduceMotion), value: TimeBarKey(isRest: isRest, identity: identity))
        .accessibilityHidden(true)
    }

    private struct TimeBarKey: Hashable {
        var isRest: Bool
        var identity: Int
    }
}
