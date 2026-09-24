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
    case baseline, segments, timeline, rails, nested

    /// The four the Settings picker offers. Rails was tried and rejected; it stays
    /// reachable through the DEBUG launch argument for comparison only.
    static let selectable: [RunnerProgressStyle] = [.baseline, .nested, .segments, .timeline]

    var settingsName: String {
        switch self {
        case .baseline: "Today"
        case .nested: "Nested"
        case .segments: "Segments"
        case .timeline: "Timeline"
        case .rails: "Rails"
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
            case .baseline, .nested: EmptyView()
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
