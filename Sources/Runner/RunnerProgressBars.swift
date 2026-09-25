// SPDX-License-Identifier: MPL-2.0
// Original contributions Copyright 2026 Nuri Bruner.

import SwiftUI

// MARK: - DESIGN EXPLORATION: where the session's position lives on the runner

// Ways to show "which set, which pull, how much is left" on the runner. On the
// `design/set-rep-bars` TEST branch they are chosen in Settings › "Progress style
// (test)" so a TestFlight build can compare them on real hardware; in DEBUG the
// `-progressStyle segments|timeline|rails|nested|baseline` launch argument overrides
// the setting. This branch never merges as-is.
//
// Rules every variant keeps:
// - **Secondary, never a hero.** Ink and steel; the only bleu is the pull whose clock
//   is running, because bleu IS the live-force signal. Nothing here chooses red: the
//   phase tint is passed in, so the live cell escalates with the ring and the trace.
// - **The glass never sits over the moving canvas.** The trace's `Canvas` redraws at
//   display rate, and a glass backdrop re-samples every frame the layer under it
//   changes. The graph region is SPLIT so the canvas stops short of each instrument
//   (see `RunnerView.graphRegion`): the glass samples the static background and wash.
// - **Cells are square-ended inside a capsule track.** A capsule is a FINGER in this
//   app (22 x 38, `IslandHand`), and a circle is a SESSION. A rail of round-ended
//   cells would draw a column of fingers; one clipped track divided by hairline gaps
//   reads as a progress bar.
// - **High-frequency state stays in a leaf.** The structure reads the coarse snapshot;
//   only `LiveCellFill` / `TimelinePlayhead` observe `session.repProgress`.
//
// Strings are verbatim: this is a prototype, and localized keys would land in the
// shipping string catalog.

enum RunnerProgressStyle: String {
    case baseline, segments, timeline, rails, nested, zoom, underline, stacked

    /// The four the Settings picker offers. Rails was tried and rejected; it stays
    /// reachable through the DEBUG launch argument for comparison only.
    static let selectable: [RunnerProgressStyle] = [.stacked, .zoom, .underline, .baseline, .nested, .segments, .timeline]

    var settingsName: String {
        switch self {
        case .baseline: "Today"
        case .nested: "Nested"
        case .segments: "Segments"
        case .timeline: "Timeline"
        case .rails: "Rails"
        case .zoom: "Zoom"
        case .underline: "Underline"
        case .stacked: "Stacked"
        }
    }

    /// DEBUG: `-progressStyle <style>` beats the stored setting, so fixtures stay
    /// comparable whatever the simulator's Settings say.
    static let launchOverride: RunnerProgressStyle? = {
        #if DEBUG
        let args = ProcessInfo.processInfo.arguments
        guard let flag = args.firstIndex(of: "-progressStyle"), args.indices.contains(flag + 1) else { return nil }
        return RunnerProgressStyle(rawValue: args[flag + 1])
        #else
        return nil
        #endif
    }()

    static func resolved(stored: RunnerProgressStyle) -> RunnerProgressStyle {
        launchOverride ?? stored
    }
}

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
    /// The current pull's clock has run (working, or paused mid-pull): it carries a fill.
    var isLive: Bool
    /// Scheduled seconds still ahead — hold + rest + lead-in of every unfinished pull.
    var remainingSeconds: Int

    init(slots: [RepSlot], results: [RepSummary], phase: RunnerPhase) {
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
        let inner: RunnerPhase
        if case .paused(let wrapped) = phase { inner = wrapped } else { inner = phase }
        if case .working = inner { isLive = true } else { isLive = false }
        remainingSeconds = slots[min(next, slots.count)...].reduce(0) { $0 + $1.totalSeconds }
    }

    var total: Int { setSizes.reduce(0, +) }
    var done: Int { finished.count }
    var pullsLeft: Int { total - done }

    /// First global pull index of each set.
    var setStarts: [Int] {
        var start = 0
        return setSizes.map { size in
            defer { start += size }
            return start
        }
    }

    /// The set holding `current` (the NEXT set during a set break — the same forward
    /// tense as "Set 2 of 4" everywhere else on the rest screen).
    var currentSet: Int {
        guard let current else { return max(0, setSizes.count - 1) }
        var start = 0
        for (index, size) in setSizes.enumerated() {
            if current < start + size { return index }
            start += size
        }
        return max(0, setSizes.count - 1)
    }
    var pullsInSet: Int { setSizes.isEmpty ? 0 : setSizes[currentSet] }
    /// Pulls of the current set already finished.
    var doneInSet: Int {
        guard !setSizes.isEmpty else { return 0 }
        return max(0, min(done - setStarts[currentSet], pullsInSet))
    }
    /// 1-based, for "pull 3 of 6".
    var pullPositionInSet: Int { min(doneInSet + 1, pullsInSet) }
    /// Sets not yet finished, counting the one you are in.
    var setsLeft: Int { setSizes.count - currentSet }
    var pullsLeftInSet: Int { pullsInSet - doneInSet }
    /// Continuous: whole sets done plus the fraction of the current one.
    var setsFilled: Double {
        guard pullsInSet > 0 else { return Double(currentSet) }
        return Double(currentSet) + Double(doneInSet) / Double(pullsInSet)
    }
    /// A track whose total is 1 is a constant dressed as a counter ("Set 1 of 1").
    var showsSets: Bool { setSizes.count > 1 }
    var showsPulls: Bool { pullsInSet > 1 }
    var currentSetStart: Int { setSizes.isEmpty ? 0 : setStarts[currentSet] }

    var completedIndices: Set<Int> { Set(finished.indices.filter { finished[$0] }) }
    var skippedIndices: Set<Int> { Set(finished.indices.filter { !finished[$0] }) }

    /// "Set 2 of 4, pull 3 of 6, 12 pulls left" — the ONE sentence all three variants
    /// give VoiceOver, with the constant halves dropped the way the screen drops them.
    var spoken: String {
        var parts: [String] = []
        if showsSets { parts.append("Set \(currentSet + 1) of \(setSizes.count)") }
        if showsPulls { parts.append("pull \(pullPositionInSet) of \(pullsInSet)") }
        parts.append(pullsLeft == 1 ? "1 pull left" : "\(pullsLeft) pulls left")
        let sentence = parts.joined(separator: ", ")
        return sentence.prefix(1).uppercased() + sentence.dropFirst()
    }

    var remainingMinutesText: String {
        remainingSeconds < 60 ? "<1 MIN" : "≈\(Int((Double(remainingSeconds) / 60).rounded(.up))) MIN"
    }
}

// MARK: - The shared primitive

/// Lays cells out along a track: equal cells, a hairline between pulls, a wider gap
/// between sets. Past the density where a hairline would eat the cell, the gaps go
/// and the track becomes a continuous bar — a 100-pull set must still fit, and at
/// that density the count is the label's job, not the drawing's.
enum SegmentLayout {
    struct Result: Equatable {
        var cells: [ClosedRange<CGFloat>] = []
        var groups: [ClosedRange<CGFloat>] = []
    }

    static func layout(groups sizes: [Int], length: CGFloat,
                       cellGap: CGFloat, groupGap: CGFloat) -> Result {
        let total = sizes.reduce(0, +)
        guard total > 0, length > 0 else { return Result() }
        var groupGap = sizes.count > 1 ? groupGap : 0
        // Set gaps go too once the sets themselves are slivers.
        if length / CGFloat(sizes.count) < groupGap * 3 { groupGap = 0 }
        let usable = length - groupGap * CGFloat(sizes.count - 1)
        let tentative = usable / CGFloat(total)
        let gap = tentative >= cellGap * 3 ? cellGap : 0
        let unit = (usable - gap * CGFloat(total - sizes.count)) / CGFloat(total)
        var result = Result()
        var cursor: CGFloat = 0
        for size in sizes {
            let groupStart = cursor
            for index in 0..<size {
                result.cells.append(cursor...(cursor + unit))
                cursor += unit + (index < size - 1 ? gap : 0)
            }
            result.groups.append(groupStart...cursor)
            cursor += groupGap
        }
        return result
    }
}

/// The cells covering `[lower, upper)` in CELL units — so the fill runs continuously
/// through the gaps, and animating `upper` from 3 to 4 fills exactly one cell along the
/// track's own path. `include` limits it to some cells (completed vs skipped layers).
///
/// Each cell is INTERSECTED with its set's capsule rather than the track being clipped
/// afterwards: a clip would cut the outline of a hollow end cell (`ring`) in half.
struct SegmentCellsShape: Shape {
    var cells: [ClosedRange<CGFloat>]
    var groups: [ClosedRange<CGFloat>] = []
    var lower: Double
    var upper: Double
    var include: Set<Int>? = nil
    var axis: Axis = .horizontal
    var cornerRadius: CGFloat = 1.5
    /// A hollow cell's outline width; nil fills.
    var ring: CGFloat? = nil

    var animatableData: AnimatablePair<Double, Double> {
        get { AnimatablePair(lower, upper) }
        set { lower = newValue.first; upper = newValue.second }
    }

    func path(in rect: CGRect) -> Path {
        var path = Path()
        for (index, cell) in cells.enumerated() {
            if let include, !include.contains(index) { continue }
            let from = max(lower - Double(index), 0)
            let to = min(upper - Double(index), 1)
            guard to > from else { continue }
            let length = cell.upperBound - cell.lowerBound
            let start = cell.lowerBound + length * from
            let end = cell.lowerBound + length * to
            let box = axis == .horizontal
                ? CGRect(x: rect.minX + start, y: rect.minY, width: end - start, height: rect.height)
                : CGRect(x: rect.minX, y: rect.minY + start, width: rect.width, height: end - start)
            let group = groups.first { $0.contains((cell.lowerBound + cell.upperBound) / 2) }
            let capsule = group.map { group in
                axis == .horizontal
                    ? CGRect(x: rect.minX + group.lowerBound, y: rect.minY,
                             width: group.upperBound - group.lowerBound, height: rect.height)
                    : CGRect(x: rect.minX, y: rect.minY + group.lowerBound,
                             width: rect.width, height: group.upperBound - group.lowerBound)
            }
            // Cells that TOUCH (a contiguous track) are square where they meet; rounding
            // them would notch the bar at every pull.
            let touches = (index > 0 && cell.lowerBound - cells[index - 1].upperBound < 0.5)
                || (index + 1 < cells.count && cells[index + 1].lowerBound - cell.upperBound < 0.5)
            let cellRadius = touches ? 0 : cornerRadius
            func piece(_ box: CGRect, _ capsule: CGRect?) -> Path {
                let radius = max(0, min(cellRadius, box.width / 2, box.height / 2))
                let cellPath = Path(roundedRect: box, cornerSize: CGSize(width: radius, height: radius),
                                    style: .continuous)
                guard let capsule, capsule.width > 0, capsule.height > 0 else { return cellPath }
                return cellPath.intersection(Capsule(style: .continuous).path(in: capsule))
            }
            let outer = piece(box, capsule)
            if let ring, box.width > ring * 2, box.height > ring * 2 {
                let inner = piece(box.insetBy(dx: ring, dy: ring), capsule?.insetBy(dx: ring, dy: ring))
                path.addPath(outer.subtracting(inner))
            } else {
                path.addPath(outer)
            }
        }
        return path
    }
}

/// A hairline at every boundary between two pulls of the same set — the pull ticks of
/// an outlined set block. Horizontal tracks only (the timeline).
struct SegmentTicks: Shape {
    var cells: [ClosedRange<CGFloat>]
    var groups: [ClosedRange<CGFloat>]

    func path(in rect: CGRect) -> Path {
        var path = Path()
        for (index, cell) in cells.enumerated().dropLast() {
            let next = cells[index + 1]
            // A set gap is wider than a pull gap; only pull boundaries get a tick.
            guard !groups.contains(where: { abs($0.upperBound - cell.upperBound) < 0.01 }) else { continue }
            let x = rect.minX + (cell.upperBound + next.lowerBound) / 2
            path.move(to: CGPoint(x: x, y: rect.minY + 2))
            path.addLine(to: CGPoint(x: x, y: rect.maxY - 2))
        }
        return path
    }
}

/// The ink this family draws in. Completed is full ink (graphite: the app's "done"
/// mark, it inverts with the scheme); skipped is a lighter ink, used up but not done;
/// the empty well is a quiet step off the glass.
enum ProgressInk {
    static let done = Ink.primary
    /// A solid, like done, but a step lighter: used up, not pulled.
    static let skipped = Ink.primary.opacity(0.45)
    /// The OUTLINE of a pull still to come. Thin strokes are mostly antialiased edge,
    /// so this sits a full step above what the arithmetic for 3:1 asks (~0.5).
    static let remaining = Ink.primary.opacity(0.62)
    /// The faint body inside that outline, and the emptied cells of a draining rail.
    static let well = Ink.primary.opacity(0.08)
    static let ringWidth: CGFloat = 1.5
}

/// A horizontal or vertical track: wells, completed and skipped layers, the "here"
/// cell, and the live fill. `drains` inverts the meaning for the rails: the INK is
/// what remains, and the live pull empties from the top.
struct SegmentTrack: View {
    var sizes: [Int]
    /// Global index of this track's first cell, for mapping `finished`.
    var offset: Int = 0
    var model: SessionProgressModel
    /// Continuous fill in this track's own cell units (a sets track passes a fraction).
    var filled: Double
    var here: Int?
    var liveSession: RunnerSession?
    var tint: Color
    var axis: Axis = .horizontal
    var drains = false
    var cellGap: CGFloat = 3
    var groupGap: CGFloat = 7
    var usesOutcomes = true
    /// Outline each remaining CELL (a short track) or each SET with pull ticks inside it
    /// (the whole-session timeline, where two dozen little outlines read as a chain).
    var outlinesSets = false
    /// The heavier outline on the pull you are on. Off where a playhead already says it.
    var marksHere = true
    @Environment(\.accessibilityReduceMotion) private var reduceMotion

    var body: some View {
        GeometryReader { proxy in
            let length = axis == .horizontal ? proxy.size.width : proxy.size.height
            let layout = SegmentLayout.layout(groups: sizes, length: length,
                                              cellGap: cellGap, groupGap: groupGap)
            let count = Double(layout.cells.count)
            let cells = layout.cells
            let groups = layout.groups
            let liveHere = here != nil && liveSession != nil && model.isLive
            ZStack(alignment: .topLeading) {
                SegmentCellsShape(cells: cells, groups: groups, lower: 0, upper: count, axis: axis)
                    .fill(ProgressInk.well)
                if drains {
                    // What REMAINS is ink; the top empties as work is done. The live cell
                    // is left to its leaf, which drains it from the top.
                    SegmentCellsShape(cells: cells, groups: groups, lower: filled + (liveHere ? 1 : 0),
                                      upper: count, axis: axis)
                        .fill(ProgressInk.done)
                } else {
                    // Every cell is outlined underneath; done cells are solid and cover it,
                    // so a half-done set cell is half solid, half outline, with no seam.
                    // The one you are on (or about to be) carries full ink, so "here"
                    // reads before anything fills it.
                    if outlinesSets {
                        SegmentCellsShape(cells: groups, groups: groups, lower: 0,
                                          upper: Double(groups.count), axis: axis,
                                          ring: ProgressInk.ringWidth)
                            .fill(ProgressInk.remaining)
                        SegmentTicks(cells: cells, groups: groups)
                            .stroke(ProgressInk.remaining, lineWidth: 1)
                    } else {
                        SegmentCellsShape(cells: cells, groups: groups, lower: 0, upper: count,
                                          axis: axis, ring: ProgressInk.ringWidth)
                            .fill(ProgressInk.remaining)
                    }
                    if let here, marksHere {
                        SegmentCellsShape(cells: cells, groups: groups, lower: Double(here),
                                          upper: Double(here + 1), axis: axis, ring: 2)
                            .fill(ProgressInk.done)
                    }
                    if usesOutcomes {
                        SegmentCellsShape(cells: cells, groups: groups, lower: 0, upper: filled,
                                          include: shifted(model.completedIndices), axis: axis)
                            .fill(ProgressInk.done)
                        SegmentCellsShape(cells: cells, groups: groups, lower: 0, upper: filled,
                                          include: shifted(model.skippedIndices), axis: axis)
                            .fill(ProgressInk.skipped)
                    } else {
                        SegmentCellsShape(cells: cells, groups: groups, lower: 0, upper: filled, axis: axis)
                            .fill(ProgressInk.done)
                    }
                }
                if let here, let liveSession, model.isLive, cells.indices.contains(here) {
                    LiveCellFill(session: liveSession, cell: cells[here], groups: groups, axis: axis,
                                 drains: drains, tint: tint)
                        // A new pull starts from empty rather than draining the last one.
                        .id(offset + here)
                }
                if outlinesSets {
                    // Over the solid part the same ticks are drawn in the glass's own
                    // tone, so finished pulls stay countable inside a filled set.
                    SegmentTicks(cells: cells, groups: groups)
                        .stroke(Color(uiColor: .systemBackground).opacity(0.75), lineWidth: 1)
                        .mask {
                            SegmentCellsShape(cells: cells, groups: groups, lower: 0,
                                              upper: filled + (liveHere ? 1 : 0), axis: axis)
                        }
                }
            }
            // Only the structure animates here — the live fill has its own linear curve.
            // Reduce Motion: no travel along the track; the new state simply appears.
            .animation(reduceMotion ? nil : Motion.state(false), value: filled)
            .animation(reduceMotion ? nil : Motion.state(false), value: here)
        }
    }

    private func shifted(_ indices: Set<Int>) -> Set<Int> {
        Set(indices.compactMap { $0 >= offset ? $0 - offset : nil })
    }
}

/// THE LEAF: the one pull whose clock is running, filled by `repProgress`. The only
/// view in this family that observes a per-sample value.
struct LiveCellFill: View {
    var session: RunnerSession
    var cell: ClosedRange<CGFloat>
    var groups: [ClosedRange<CGFloat>]
    var axis: Axis
    var drains: Bool
    var tint: Color
    @Environment(\.accessibilityReduceMotion) private var reduceMotion

    var body: some View {
        #if DEBUG
        let _ = RunnerProgressProbe.count("LiveCellFill")
        #endif
        let progress = session.repProgress
        SegmentCellsShape(cells: [cell], groups: groups,
                          lower: drains ? progress : 0,
                          upper: drains ? 1 : progress, axis: axis)
            .fill(tint)
            .animation(reduceMotion ? nil : Motion.measuredProgress, value: progress)
    }
}

// MARK: - 1. SEGMENTS

/// Two tracks on one small glass surface at the top of the graph: sets, then the pulls
/// of this set. Done is ink, the live pull fills in the phase tint, what is left is an
/// empty well. The count that matters from the wall — pulls left — is a numeral.
struct SegmentsProgressView: View {
    var model: SessionProgressModel
    var session: RunnerSession
    var tint: Color

    var body: some View {
        HStack(spacing: 14) {
            Grid(alignment: .leading, horizontalSpacing: 10, verticalSpacing: 9) {
                if model.showsSets {
                    GridRow {
                        label("SET \(model.currentSet + 1) OF \(model.setSizes.count)")
                        SegmentTrack(sizes: [model.setSizes.count], model: model, filled: model.setsFilled,
                                     here: model.current.map { _ in model.currentSet },
                                     liveSession: nil, tint: tint, usesOutcomes: false)
                            .frame(height: 9)
                    }
                }
                if model.showsPulls {
                    GridRow {
                        label("PULL \(model.pullPositionInSet) OF \(model.pullsInSet)")
                        SegmentTrack(sizes: [model.pullsInSet], offset: model.currentSetStart,
                                     model: model, filled: Double(model.doneInSet),
                                     here: model.current.map { _ in model.doneInSet },
                                     liveSession: session, tint: tint)
                            .frame(height: 9)
                    }
                }
            }
            RemainingCount(value: model.pullsLeft)
        }
        .padding(.horizontal, 16)
        .padding(.vertical, 11)
        .accessibleGlass(nil, in: RoundedRectangle(cornerRadius: 22, style: .continuous))
        .shadow(color: .black.opacity(0.08), radius: 10, y: 4)
    }

    private func label(_ text: String) -> some View {
        CapsLabel(text, size: 12, tint: Ink.secondary)
            .monospacedDigit()
            .lineLimit(1)
            .fixedSize()
    }
}

/// "12 LEFT" — the remaining count, as a numeral you can read from the wall.
struct RemainingCount: View {
    var value: Int

    var body: some View {
        HStack(alignment: .firstTextBaseline, spacing: 4) {
            Text("\(value)")
                .font(.title2.weight(.semibold))
                .monospacedDigit()
                .contentTransition(.numericText(countsDown: true))
                .foregroundStyle(Ink.primary)
            CapsLabel("LEFT", size: 12, tint: Ink.secondary)
        }
        .fixedSize()
    }
}

// MARK: - 2. TIMELINE

/// The whole plan as one slim track above the dock, where a player's scrubber sits
/// above its transport controls: a block per set, a hairline per pull, a playhead at
/// "you are here". The only variant that shows what is AHEAD at the session's scale.
struct TimelineProgressView: View {
    var model: SessionProgressModel
    var session: RunnerSession
    var tint: Color

    var body: some View {
        VStack(spacing: 8) {
            // One line when it fits; at large text the two facts stack rather than
            // truncating "PULL 3 OF 6" to "PUL…".
            ViewThatFits(in: .horizontal) {
                HStack(alignment: .firstTextBaseline) {
                    positionLabel.fixedSize()
                    Spacer(minLength: 8)
                    remainingLabel
                }
                VStack(alignment: .leading, spacing: 4) {
                    positionLabel
                    remainingLabel
                }
                .frame(maxWidth: .infinity, alignment: .leading)
            }
            ZStack {
                SegmentTrack(sizes: model.setSizes, model: model, filled: Double(model.done),
                             here: model.current, liveSession: session, tint: tint,
                             cellGap: 0, groupGap: 6, outlinesSets: true, marksHere: false)
                    .frame(height: 8)
                TimelinePlayhead(model: model, session: session)
            }
            .frame(height: 20)
        }
        .padding(.horizontal, 18)
        .padding(.vertical, 11)
        .accessibleGlass(nil, in: RoundedRectangle(cornerRadius: 22, style: .continuous))
        .shadow(color: .black.opacity(0.08), radius: 10, y: 4)
    }

    private var positionLabel: some View {
        CapsLabel(positionText, size: 12, tint: Ink.secondary)
            .monospacedDigit()
            .lineLimit(1)
    }

    private var remainingLabel: some View {
        CapsLabel("\(model.pullsLeft) LEFT · \(model.remainingMinutesText)", size: 12, tint: Ink.secondary)
            .monospacedDigit()
            .lineLimit(1)
            .fixedSize()
    }

    private var positionText: String {
        var parts: [String] = []
        if model.showsSets { parts.append("SET \(model.currentSet + 1) OF \(model.setSizes.count)") }
        if model.showsPulls { parts.append("PULL \(model.pullPositionInSet) OF \(model.pullsInSet)") }
        return parts.joined(separator: " · ")
    }
}

/// The playhead: a thin solid mark across the track, NOT a bead — a circle means a
/// session in this app, and a glass bead on the glass track would be glass on glass.
/// A leaf, because while a pull runs it rides the live fill's leading edge.
struct TimelinePlayhead: View {
    var model: SessionProgressModel
    var session: RunnerSession
    @Environment(\.accessibilityReduceMotion) private var reduceMotion

    var body: some View {
        #if DEBUG
        let _ = RunnerProgressProbe.count("TimelinePlayhead")
        #endif
        GeometryReader { proxy in
            let layout = SegmentLayout.layout(groups: model.setSizes, length: proxy.size.width,
                                              cellGap: 0, groupGap: 6)
            if let current = model.current, layout.cells.indices.contains(current) {
                let cell = layout.cells[current]
                let progress = model.isLive ? session.repProgress : 0
                let x = cell.lowerBound + (cell.upperBound - cell.lowerBound) * progress
                Capsule(style: .continuous)
                    .fill(Ink.primary)
                    .frame(width: 3, height: proxy.size.height)
                    .offset(x: x - 1.5)
                    .animation(reduceMotion ? nil
                               : (model.isLive ? Motion.measuredProgress : Motion.state(false)),
                               value: x)
            }
        }
    }
}

// MARK: - 3. RAILS

/// Two thin vertical rails on the graph's LEFT edge — never the right, where the
/// trace's newest point lives. The ink is what REMAINS: each rail drains top-down as
/// the work is done, and the live pull empties from the top in the phase tint.
struct RailsProgressView: View {
    var model: SessionProgressModel
    var session: RunnerSession
    var tint: Color

    var body: some View {
        HStack(alignment: .top, spacing: 8) {
            if model.showsSets {
                column(value: model.setsLeft, caption: model.setsLeft == 1 ? "SET" : "SETS") {
                    SegmentTrack(sizes: [model.setSizes.count], model: model, filled: model.setsFilled,
                                 here: nil, liveSession: nil, tint: tint, axis: .vertical,
                                 drains: true, cellGap: 3)
                }
            }
            if model.showsPulls || !model.showsSets {
                column(value: model.pullsLeftInSet, caption: model.pullsLeftInSet == 1 ? "PULL" : "PULLS") {
                    SegmentTrack(sizes: [model.pullsInSet], offset: model.currentSetStart,
                                 model: model, filled: Double(model.doneInSet),
                                 here: model.current.map { _ in model.doneInSet },
                                 liveSession: session, tint: tint, axis: .vertical,
                                 drains: true, cellGap: 3)
                }
            }
        }
        .padding(.horizontal, 9)
        .padding(.top, 10)
        .padding(.bottom, 14)
        .accessibleGlass(nil, in: RoundedRectangle(cornerRadius: 20, style: .continuous))
        .shadow(color: .black.opacity(0.08), radius: 10, y: 4)
    }

    private func column(value: Int, caption: String,
                        @ViewBuilder track: () -> some View) -> some View {
        VStack(spacing: 1) {
            Text("\(value)")
                .font(.title3.weight(.semibold))
                .monospacedDigit()
                .contentTransition(.numericText(countsDown: true))
                .foregroundStyle(Ink.primary)
            CapsLabel(caption, size: 11, tint: Ink.secondary)
                .fixedSize()
            track()
                .frame(width: 10)
                .frame(maxHeight: .infinity)
                .padding(.top, 8)
        }
        .frame(minWidth: 36)
    }
}

// MARK: - 4. NESTED

/// The tracks INSIDE the existing top panel, in the room today's thin hold bar and the
/// "SET 2 OF 4 · PULL 9 OF 24" row take (owner feedback: the floating card covered too
/// much graph). No glass of its own — flat fills on the panel's glass, so no second
/// blur and no second surface. One row: sets as a compact prefix, this set's pulls
/// filling the rest, the live pull carrying the hold fill — the only live progress on
/// the screen, so the pulls track is the wider one.
///
/// The label row mirrors the old counters row exactly — leading label, the reserved
/// rest word in the middle, trailing label, with the same two-row fallback — so the
/// panel's height is the old row's plus (track − bar) − (the spacing the pair saves).
struct NestedProgressRow<Middle: View>: View {
    var model: SessionProgressModel
    var session: RunnerSession
    var tint: Color
    @ViewBuilder var middle: () -> Middle
    @Environment(\.accessibilityReduceMotion) private var reduceMotion

    /// Sets take a fixed share of the row: the prefix is read as "which block", the
    /// pulls as "how far into it", and the latter carries the live fill.
    static var setsShare: CGFloat { 0.30 }
    static var trackHeight: CGFloat { 10 }
    /// Track + this = the old bar (4) + the stack spacing it sat in (12).
    static var labelSpacing: CGFloat { 6 }

    var body: some View {
        VStack(spacing: Self.labelSpacing) {
            GeometryReader { proxy in
                HStack(spacing: 10) {
                    if model.showsSets {
                        SegmentTrack(sizes: [model.setSizes.count], model: model, filled: model.setsFilled,
                                     here: model.current.map { _ in model.currentSet },
                                     liveSession: nil, tint: tint, usesOutcomes: false)
                            .frame(width: max(44, (proxy.size.width - 10) * Self.setsShare))
                    }
                    SegmentTrack(sizes: [model.pullsInSet], offset: model.currentSetStart,
                                 model: model, filled: Double(model.doneInSet),
                                 here: model.current.map { _ in model.doneInSet },
                                 liveSession: session, tint: tint)
                }
            }
            .frame(height: Self.trackHeight)
            ViewThatFits(in: .horizontal) {
                HStack(spacing: 8) {
                    label(leading).fixedSize()
                    Spacer(minLength: 0)
                    middle()
                    Spacer(minLength: 0)
                    label(trailing).fixedSize()
                }
                VStack(spacing: 4) {
                    middle()
                    HStack(alignment: .top, spacing: 8) {
                        label(leading)
                        Spacer(minLength: 0)
                        // Two short lines, never a phrase broken in the middle ("PULL 3 OF /
                        // 6 · 16 LEFT") — and never three, which would make the panel taller
                        // than the counters row it replaces. "3/6" is the rest summary's own
                        // compact form.
                        VStack(alignment: .trailing, spacing: 2) {
                            ForEach(compactTrailing, id: \.self) { part in
                                label(part).multilineTextAlignment(.trailing)
                            }
                        }
                    }
                }
            }
            .monospacedDigit()
        }
        .animation(Motion.state(reduceMotion), value: model)
        .accessibilityElement(children: .ignore)
        .accessibilityLabel(model.spoken)
        .accessibilityIdentifier("runner.progressInstrument")
    }

    /// "SET 2 OF 4" under the prefix; with one set, the pull position moves there.
    private var leading: String {
        model.showsSets ? "SET \(model.currentSet + 1) OF \(model.setSizes.count)"
                        : "PULL \(model.pullPositionInSet) OF \(model.pullsInSet)"
    }

    private var trailing: String {
        model.showsSets ? "PULL \(model.pullPositionInSet) OF \(model.pullsInSet) · \(model.pullsLeft) LEFT"
                        : "\(model.pullsLeft) LEFT"
    }

    private var compactTrailing: [String] {
        model.showsSets ? ["PULL \(model.pullPositionInSet)/\(model.pullsInSet)", "\(model.pullsLeft) LEFT"]
                        : ["\(model.pullsLeft) LEFT"]
    }

    private func label(_ text: String) -> some View {
        CapsLabel(text, size: 12, tint: Ink.secondary)
    }
}

// MARK: - 5. ZOOM and 6. UNDERLINE — today's bar, and the routine on the same line

/// Owner verdict on build 14: keep the FULL-LENGTH hold bar, lose the heavy outlined
/// cells, and show the whole routine on one line without spending space on it.
///
/// ZOOM is today's bar exactly (4 pt capsule, `systemFill` track — measured, it is what
/// the system `ProgressView` draws — bleu fill) with every pull of the routine laid out
/// along it. `zoom` = 1 maps the focused pull's slot onto the whole width, which IS
/// today's hold bar; `zoom` = 0 is the whole routine. One affine map, interpolated, so
/// pull → rest and rest → pull are the same path run in opposite directions.
enum ZoomLayout {
    /// Pull slots along `width`: ~2 pt between pulls, ~6 pt between sets. Dense plans
    /// shrink the pull gap to 1 pt, then drop it (see below), keeping only the set gaps
    /// (which themselves narrow before a set becomes a sliver).
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

/// The slots in `include`, mapped through the zoom. `fraction` fills each included slot
/// from its leading edge (the live hold). Round-ended at the line's own height, so a
/// slot is a short piece of the same capsule, never an outlined box.
struct ZoomSlotsShape: Shape {
    var cells: [ClosedRange<CGFloat>]
    var include: [Int]
    var focus: Int?
    var zoom: Double
    var fraction: Double = 1

    /// Only the fill animates here. `zoom` arrives already interpolated from
    /// `ZoomAnimator`, so every layer of the bar draws the SAME zoom on every frame;
    /// with each shape interpolating its own, the live fill and its slot drifted apart
    /// mid-morph (seen in the recording).
    var animatableData: Double {
        get { fraction }
        set { fraction = newValue }
    }

    func path(in rect: CGRect) -> Path {
        var path = Path()
        let width = rect.width
        let focusCell = focus.flatMap { cells.indices.contains($0) ? cells[$0] : nil }
        // A real ZOOM: scale grows geometrically (k^z) while the focused slot's centre
        // travels linearly to the middle. Interpolating positions linearly instead
        // front-loads the zoom-in and back-loads the zoom-out (measured in the
        // recording: 0.15 s one way, 0.5 s the other) — the same path must feel the
        // same in both directions.
        func map(_ x: CGFloat) -> CGFloat {
            guard let focusCell, zoom > 0 else { return x }
            let middle = (focusCell.lowerBound + focusCell.upperBound) / 2
            let k = width / max(0.25, focusCell.upperBound - focusCell.lowerBound)
            let scale = CGFloat(pow(Double(k), zoom))
            let centre = middle + (width / 2 - middle) * zoom
            return centre + (x - middle) * scale
        }
        let radius = rect.height / 2
        for index in include where cells.indices.contains(index) {
            let start = map(cells[index].lowerBound)
            let end = map(cells[index].upperBound)
            let filledEnd = start + (end - start) * min(max(fraction, 0), 1)
            // Off-screen slots cost nothing; the bar clips at its own ends anyway.
            guard filledEnd > start + 0.01, filledEnd > -1, start < width + 1 else { continue }
            let box = CGRect(x: rect.minX + start, y: rect.minY, width: filledEnd - start, height: rect.height)
            // Slots that TOUCH (the underline's pulls) meet square: rounding them would
            // notch a continuous line at every pull.
            let touches = (index > 0 && cells[index].lowerBound - cells[index - 1].upperBound < 0.5)
                || (index + 1 < cells.count && cells[index + 1].lowerBound - cells[index].upperBound < 0.5)
            let r = touches ? 0 : min(radius, box.width / 2)
            path.addRoundedRect(in: box, cornerSize: CGSize(width: r, height: r), style: .continuous)
        }
        return path
    }
}

/// The ONE animated zoom value for a bar, handed to every layer through the
/// environment — the `BlendedTint` pattern: SwiftUI interpolates this modifier's
/// number, and the shapes below simply read it.
struct ZoomAnimator: ViewModifier, @preconcurrency Animatable {
    var zoom: Double
    var animatableData: Double {
        get { zoom }
        set { zoom = newValue }
    }
    func body(content: Content) -> some View {
        content.environment(\.routineZoom, zoom)
    }
}

private struct RoutineZoomKey: EnvironmentKey {
    static let defaultValue: Double = 0
}

extension EnvironmentValues {
    var routineZoom: Double {
        get { self[RoutineZoomKey.self] }
        set { self[RoutineZoomKey.self] = newValue }
    }
}

/// The colours one routine line is drawn in — ZOOM's by default, STACKED's quieter set.
struct RoutinePalette {
    var track: Color = RoutineLineInk.track
    var done: Color = RoutineLineInk.done
    var skipped: Color = RoutineLineInk.skipped
    var next: Color = RoutineLineInk.next
}

/// The restrained palette both variants share. Track = what the system bar draws.
enum RoutineLineInk {
    static let track = Color(uiColor: .systemFill)
    /// Done pulls: ink, held back so the line stays secondary to the hero numbers.
    static let done = Ink.primary.opacity(0.55)
    /// A skipped pull is used up but was not pulled.
    static let skipped = Ink.primary.opacity(0.26)
    /// The pull a rest is waiting for — rest looks forward. Subtle, but a meaningful
    /// mark: 0.55 measured 2.5:1 on the light panel, under the 3:1 floor.
    static let next = StatusTint.engaged.opacity(0.72)
}

/// ZOOM. `focus` is the phase's own slot (the pull being pulled, or the one whose rest
/// is running, or the one a count-in leads to), so it changes only while the line is
/// zoomed OUT, where the focus has no effect — the map never jumps.
struct ZoomRoutineBar: View {
    var model: SessionProgressModel
    var session: RunnerSession
    var focus: Int?
    /// Armed, working, releasing (and paused inside those): the bar is the hold bar.
    var zoomed: Bool
    /// The focused slot carries the live fill (working, releasing, paused mid-pull).
    var showsLiveFill: Bool
    var tint: Color
    @Environment(\.accessibilityReduceMotion) private var reduceMotion

    static let height: CGFloat = 4

    var body: some View {
        GeometryReader { proxy in
            let cells = ZoomLayout.cells(sizes: model.setSizes, width: proxy.size.width)
            if reduceMotion {
                // Reduce Motion: no travel. The two states cross-fade in place.
                ZStack {
                    ZoomLayers(model: model, session: session, cells: cells, focus: focus,
                               showsLiveFill: showsLiveFill, tint: tint)
                        .modifier(ZoomAnimator(zoom: zoomed ? 1 : 0))
                        .id(zoomed)
                        .transition(.opacity)
                }
                .animation(Motion.state(true), value: zoomed)
            } else {
                ZoomLayers(model: model, session: session, cells: cells, focus: focus,
                           showsLiveFill: showsLiveFill, tint: tint)
                    .modifier(ZoomAnimator(zoom: zoomed ? 1 : 0))
                    .animation(Motion.state(false), value: zoomed)
            }
        }
        .frame(height: Self.height)
    }

}

/// The bar's layers, drawn at the environment's (animated) zoom.
struct ZoomLayers: View {
    var model: SessionProgressModel
    var session: RunnerSession
    var cells: [ClosedRange<CGFloat>]
    var focus: Int?
    var showsLiveFill: Bool
    var tint: Color
    /// The next/current pill's bleu mark. ZOOM fades it as it zooms into that pill;
    /// STACKED hides it while the pill carries the live fill instead.
    var marksNext = true
    var palette = RoutinePalette()
    @Environment(\.routineZoom) private var zoom
    @Environment(\.accessibilityReduceMotion) private var reduceMotion

    var body: some View {
        let all = Array(cells.indices)
        let completed = model.finished.indices.filter { model.finished[$0] }
        let skipped = model.finished.indices.filter { !model.finished[$0] }
        ZStack(alignment: .leading) {
            ZoomSlotsShape(cells: cells, include: all, focus: focus, zoom: zoom)
                .fill(palette.track)
            ZoomSlotsShape(cells: cells, include: completed, focus: focus, zoom: zoom)
                .fill(palette.done)
            ZoomSlotsShape(cells: cells, include: skipped, focus: focus, zoom: zoom)
                .fill(palette.skipped)
            if let next = model.current {
                ZoomSlotsShape(cells: cells, include: [next], focus: focus, zoom: zoom)
                    .fill(palette.next)
                    // Fades as the line zooms into that very slot: the marker was the
                    // promise, the empty hold bar is the thing itself.
                    .opacity(marksNext ? 1 - zoom : 0)
            }
            if let focus {
                LiveZoomFill(session: session, cells: cells, focus: focus,
                             focusFinished: focus < model.done && model.finished[focus],
                             zoom: zoom, tint: tint)
                    // Fades on the same curve as the zoom, and shrinks WITH its slot.
                    .opacity(showsLiveFill ? 1 : 0)
                    .animation(Motion.state(reduceMotion), value: showsLiveFill)
            }
        }
        // The bar's own extent: slots zoomed past the ends are clipped, like any bar.
        .clipShape(Capsule(style: .continuous))
    }
}

/// THE LEAF: the live hold on the focused slot. Present in every phase (only its
/// opacity changes) so on pull → rest it shrinks WITH its slot while it fades, instead
/// of freezing full-width as a removed view would.
struct LiveZoomFill: View {
    var session: RunnerSession
    var cells: [ClosedRange<CGFloat>]
    var focus: Int
    /// The focused pull is already recorded (LET GO, or the rest after it): the engine
    /// has reset its progress, but the hold it shows was COMPLETE — a fill draining back
    /// to nothing would say the opposite.
    var focusFinished: Bool
    var zoom: Double
    var tint: Color
    @Environment(\.accessibilityReduceMotion) private var reduceMotion

    var body: some View {
        #if DEBUG
        let _ = RunnerProgressProbe.count("LiveZoomFill")
        #endif
        let progress = focusFinished ? 1 : session.repProgress
        ZoomSlotsShape(cells: cells, include: [focus], focus: focus, zoom: zoom, fraction: progress)
            .fill(tint)
            .animation(reduceMotion ? nil : Motion.measuredProgress, value: progress)
    }
}

/// STACKED (owner, after v2): today's hold bar untouched, and directly under it the
/// whole routine as ZOOM's rest-state pills — ALWAYS, pulling and resting alike. The
/// pills are `ZoomLayers` at zoom 0, so height, radius, colours and gaps cannot drift
/// from ZOOM's. Two treatments of the pill being pulled are wired for comparison:
/// the bleu mark (default) or a proportional hold fill (`liveFillsPill`).
struct StackedRoutinePills: View {
    var model: SessionProgressModel
    var session: RunnerSession
    var tint: Color
    /// Working (or releasing / paused mid-pull).
    var isLive: Bool
    var liveFillsPill: Bool

    /// SECONDARY to the time bar above (owner, v4): slimmer, and quieter.
    static let height: CGFloat = 3

    /// Quieter than ZOOM's through a lighter TRACK and a slimmer pill, not a lighter
    /// ink: at 3 pt, done ink at 0.46 measured 2.5:1 against the track in light mode
    /// (thin shapes are mostly antialiased edge), so done stays at 0.6 to clear 3:1.
    /// While a pull runs the current pill is marked in INK —
    /// the time bar is the one bleu thing on the panel. At rest the time bar turns
    /// steel, and the next pill takes the bleu as the single accent pointing forward.
    static func palette(isLive: Bool) -> RoutinePalette {
        RoutinePalette(track: Color(uiColor: .tertiarySystemFill),
                       done: Ink.primary.opacity(0.6),
                       skipped: Ink.primary.opacity(0.2),
                       next: isLive ? Ink.primary.opacity(0.85) : RoutineLineInk.next)
    }

    var body: some View {
        GeometryReader { proxy in
            let cells = ZoomLayout.cells(sizes: model.setSizes, width: proxy.size.width)
            let filling = liveFillsPill && isLive
            ZoomLayers(model: model, session: session, cells: cells,
                       focus: filling ? model.current : nil,
                       showsLiveFill: filling, tint: tint, marksNext: !filling,
                       palette: Self.palette(isLive: isLive))
        }
        .frame(height: Self.height)
    }
}

/// STACKED's top row: ONE bar that is always there — so the panel never changes shape
/// between pull and rest (owner, 2026-09-25).
///
/// - Pulling: today's hold bar exactly (systemFill track, bleu fill growing with held
///   time). Released but still on the edge: full. Armed: empty, waiting.
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
struct StackedTimeBar: View {
    enum Mode: Equatable { case hold, armed, released, countdown, none }

    /// PRIMARY (owner, v4): a touch heavier than the pills below it, and today's bar.
    static let height: CGFloat = 6

    var session: RunnerSession
    var mode: Mode
    /// Changes per pull / per rest, so each gets a fresh layer: the hold that ends
    /// fades out FULL, the rest that ends fades out EMPTY.
    var identity: Int
    @Environment(\.accessibilityReduceMotion) private var reduceMotion

    var body: some View {
        #if DEBUG
        let _ = RunnerProgressProbe.count("StackedTimeBar")
        #endif
        let fraction: Double = switch mode {
        case .hold: session.repProgress
        case .released: 1
        case .countdown: session.phaseRemainingFraction ?? 0
        case .armed, .none: 0
        }
        let isRest = mode == .countdown
        ZStack(alignment: .leading) {
            Capsule(style: .continuous).fill(RoutineLineInk.track)
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

/// UNDERLINE: today's bar untouched, and under it the whole routine as a 2 pt line —
/// hairline set breaks, done in ink, the current pull in bleu.
struct RoutineUnderline: View {
    var model: SessionProgressModel
    @Environment(\.accessibilityReduceMotion) private var reduceMotion

    static let height: CGFloat = 2

    var body: some View {
        GeometryReader { proxy in
            let cells = ZoomLayout.cells(sizes: model.setSizes, width: proxy.size.width,
                                         pullGap: 0, setGap: 2)
            let completed = model.finished.indices.filter { model.finished[$0] }
            let skipped = model.finished.indices.filter { !model.finished[$0] }
            ZStack(alignment: .leading) {
                ZoomSlotsShape(cells: cells, include: Array(cells.indices), focus: nil, zoom: 0)
                    .fill(RoutineLineInk.track)
                ZoomSlotsShape(cells: cells, include: completed, focus: nil, zoom: 0)
                    .fill(RoutineLineInk.done)
                ZoomSlotsShape(cells: cells, include: skipped, focus: nil, zoom: 0)
                    .fill(RoutineLineInk.skipped)
                if let current = model.current {
                    // A tick at least 3 pt wide, so a 60-pull routine still shows it.
                    let cell = cells[current]
                    let width = max(3, cell.upperBound - cell.lowerBound)
                    Capsule(style: .continuous)
                        .fill(StatusTint.engaged)
                        .frame(width: width, height: Self.height)
                        .offset(x: min(cell.lowerBound, proxy.size.width - width))
                }
            }
            .animation(reduceMotion ? nil : Motion.state(false), value: model)
        }
        .frame(height: Self.height)
    }
}

// MARK: - The chooser

/// The variant for the current style, carrying the combined VoiceOver sentence and an
/// identifier. Clamped at `.accessibility1`: a strip over the graph has to FIT (the
/// `ConsistencyCard` rule), and the same facts are spoken whole.
struct RunnerProgressInstrument: View {
    var style: RunnerProgressStyle
    var model: SessionProgressModel
    var session: RunnerSession
    var tint: Color
    @Environment(\.accessibilityReduceMotion) private var reduceMotion

    var body: some View {
        #if DEBUG
        let _ = RunnerProgressProbe.count("Instrument")
        #endif
        Group {
            switch style {
            case .segments: SegmentsProgressView(model: model, session: session, tint: tint)
            case .timeline: TimelineProgressView(model: model, session: session, tint: tint)
            case .rails: RailsProgressView(model: model, session: session, tint: tint)
            case .baseline, .nested, .zoom, .underline, .stacked: EmptyView()
            }
        }
        .animation(Motion.state(reduceMotion), value: model)
        .dynamicTypeSize(...DynamicTypeSize.accessibility1)
        .allowsHitTesting(false)
        .accessibilityElement(children: .ignore)
        .accessibilityLabel(model.spoken)
        .accessibilityIdentifier("runner.progressInstrument")
    }
}

#if DEBUG
/// `-probeRunnerBodies`: counts body evaluations per view and prints them every 5 s, the
/// Instruments-free proxy for what a sample invalidates. Off unless asked for.
@MainActor
enum RunnerProgressProbe {
    static let enabled = ProcessInfo.processInfo.arguments.contains("-probeRunnerBodies")
    private static var counts: [String: Int] = [:]
    private static var started = false

    static func count(_ name: String) {
        guard enabled else { return }
        counts[name, default: 0] += 1
        guard !started else { return }
        started = true
        Task { @MainActor in
            while true {
                try? await Task.sleep(for: .seconds(5))
                let line = counts.sorted { $0.key < $1.key }.map { "\($0.key)=\($0.value)" }
                    .joined(separator: " ")
                print("PROBE[5s] \(line)")
                counts = [:]
            }
        }
    }
}
#endif
