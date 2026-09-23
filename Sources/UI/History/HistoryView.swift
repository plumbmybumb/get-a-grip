// SPDX-License-Identifier: MPL-2.0
// Original contributions Copyright 2026 Nuri Bruner.

import SwiftData
import SwiftUI

/// What you have actually done, and whether it is going anywhere.
///
/// Two different questions, and the screen answers them in that order: **did I show up**
/// (the month grid, which is about the ritual) and **is it getting stronger** (the per-grip
/// trend, which is about the training). Consistency comes first deliberately — for a
/// twice-a-day habit, turning up is the whole game, and a load chart that creeps up while
/// the grid is full of holes is telling you a comforting lie.
///
/// Every number here comes from `WorkoutLog`, which freezes its plan and each rep's grip
/// at save time. Editing or deleting a routine can therefore never rewrite what history
/// says you DID. Its NAME is the one deliberate exception — see `displayName(of:)`.
struct HistoryView: View {
    @Environment(\.weightUnit) private var weightUnit
    /// Newest first — the session you are most likely looking for is the one you just did.
    @Query(sort: [SortDescriptor(\WorkoutLog.startedAt, order: .reverse)])
    private var logs: [WorkoutLog]

    /// The export's worker fetches from this container on a context of its own — see
    /// `AnalysisExportAssembler.Source`.
    @Environment(\.modelContext) private var modelContext

    @Environment(DayClock.self) private var clock
    @Environment(TemplateStore.self) private var templates
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    /// Half of the wide-layout gate; the view's own aspect is the other half. See `body`.
    @Environment(\.horizontalSizeClass) private var sizeClass
    /// The other half — wider than tall — and the width the wide layout divides. Written
    /// by `onGeometryChange`, each only when ITS answer changes, where a screen-level
    /// `GeometryReader` used to rebuild everything inside it on any geometry change at
    /// all: the undo bar's inset arriving was one.
    @State private var isLandscape = false
    @State private var viewWidth: CGFloat = 0

    @State private var undoTick = 0
    /// Expanded stays expanded while the tab lives; collapsing back is just scrolling
    /// up, so there is no "show fewer".
    @State private var showAllSessions = false
    @State private var calendarShare: ShareCalendarRequest?
    @State private var analysisExport: AnalysisExportRequest?

    /// Decoded rep blobs, once per log EVER.
    ///
    /// `WorkoutLog.resultsData` is write-once, so this cache never invalidates. Only the
    /// session rows read it now — one leading grip per VISIBLE row — since the trend deck
    /// decodes its own copy off the main actor (`TrendModel`); without the cache every
    /// body evaluation re-decoded each visible row's JSON on the main thread. A reference type mutated during body, deliberately outside
    /// observation — the same trick as ForceTraceView's AxisMemory.
    ///
    /// A deleted log leaves its entry behind, which is deliberate rather than a leak:
    /// undo re-inserts the SAME id carrying byte-identical `resultsData`, so the stale
    /// entry is still the right answer and the restored row draws without re-decoding.
    private final class RepsCache {
        var byID: [UUID: [RepSummary]] = [:]
    }
    @State private var repsCache = RepsCache()

    private func reps(for log: WorkoutLog) -> [RepSummary] {
        if let hit = repsCache.byID[log.id] { return hit }
        let decoded = log.reps
        repsCache.byID[log.id] = decoded
        return decoded
    }

    /// The column-only folds over the whole history — the month grid's ledger and the
    /// odometer — kept for as long as the query hands back the SAME rows.
    ///
    /// They were rebuilt on every body evaluation, and this body re-runs for things that
    /// change none of their inputs: "Show earlier", the undo bar, a sheet. The key is the
    /// rows' IDENTITY (a pointer compare per row, touching no attribute — nothing is
    /// faulted to answer it) plus the day and the tracking start. A new, deleted or
    /// restored session is a different object, so the key cannot miss a change the
    /// folds read; the one field edited in place on a saved log is its grade, which
    /// neither fold reads. `generation` counts the rebuilds, which is what `TrendDeck`
    /// compares itself on.
    private final class DerivedCache {
        private var rows: [WorkoutLog] = []
        private var today: DayStamp?
        private var trackingSince: DayStamp?
        private(set) var generation = 0
        private(set) var ledger: DayLedger?
        private(set) var lifetime = LifetimeStats()

        func refresh(logs: [WorkoutLog], today: DayStamp, trackingSince: DayStamp?) {
            let sameRows = rows.count == logs.count
                && zip(rows, logs).allSatisfy { $0 === $1 }
            if !sameRows {
                rows = logs
                generation += 1
                lifetime = logs.lifetime
                ledger = nil
            }
            if ledger == nil || self.today != today || self.trackingSince != trackingSince {
                self.today = today
                self.trackingSince = trackingSince
                ledger = DayLedger(logs: logs, today: today, trackingSince: trackingSince)
            }
        }
    }
    @State private var derived = DerivedCache()

    var body: some View {
        NavigationStack {
            // TWO PANES when the window is regular-width AND wider than tall: an iPad in
            // landscape, or a foldable opened sideways. Size class and aspect, never the
            // idiom — an iPad in portrait keeps the single column, and a Slide Over column
            // is a phone. Apple's own guidance for the foldable says the same, and
            // `RunnerView.live(_:)` gates its wide layout on exactly this expression.
            content(wide: sizeClass == .regular && isLandscape, width: viewWidth)
                .frame(maxWidth: .infinity, maxHeight: .infinity)
                .onGeometryChange(for: Bool.self, of: { $0.size.width > $0.size.height }) {
                    isLandscape = $0
                }
                .onGeometryChange(for: CGFloat.self, of: { $0.size.width }) { viewWidth = $0 }
                .navigationTitle("History")
            .navigationSubtitle(subtitle)
            // The screen-level action belongs on the screen's own bar: the month card's
            // share button exports ONE five-week calendar as a picture, and this exports
            // the whole ledger as a document. Two different scopes, so two different
            // places — a second button on the card would read as a variant of the first.
            .toolbar {
                ToolbarItem(placement: .primaryAction) { exportButton }
            }
        }
        // The stack, the title, the undo bar, the sheets and the feedback are declared
        // ONCE and shared by both layouts. A pane that carried its own would give the
        // wide window two undo bars and two copies of every sheet.
        .safeAreaInset(edge: .bottom) { undoBar }
        .sensoryFeedback(.success, trigger: undoTick)
        .sheet(item: $calendarShare) { request in
            ShareCalendarSheet(request: request) {
                calendarShare = nil
            }
        }
        .sheet(item: $analysisExport) { request in
            AnalysisExportSheet(request: request) {
                analysisExport = nil
            }
        }
    }

    /// One column or two, built from the same blocks.
    ///
    /// The empty state stays single-column in both: with nothing logged there is no log
    /// to stand beside the summaries, and two half-empty panes state the emptiness twice.
    @ViewBuilder
    private func content(wide: Bool, width: CGFloat) -> some View {
        if logs.isEmpty {
            List {
                emptyCard.houseListRow(top: 12, bottom: 10)
            }
            .historyList()
            // ALWAYS via `.background {}`, never as a ZStack sibling — as a sibling it
            // disturbs the safe-area layout and the title creeps under the status bar.
            .background { AppBackground() }
        } else {
            // Folded once per CHANGE to the rows, not per body evaluation, and handed
            // down rather than each of the ~200 questions a card asks re-scanning the
            // whole log array. See `DerivedCache` and `DayLedger`.
            let _ = derived.refresh(logs: logs, today: clock.today,
                                    trackingSince: templates.trackingSince)
            let ledger = derived.ledger ?? DayLedger(logs: logs, today: clock.today,
                                                     trackingSince: templates.trackingSince)
            if wide {
                twoPane(ledger: ledger, lifetime: derived.lifetime, width: width)
            } else {
                singleColumn(ledger: ledger, lifetime: derived.lifetime)
            }
        }
    }

    /// The phone's History, unchanged: one `List`, the two summary blocks as rows above
    /// the log.
    ///
    /// A real `List` rather than `ScreenScaffold`'s ScrollView, for exactly the reason
    /// Maxes is one: swipe-to-delete, the row-slide physics and the full-swipe commit all
    /// come from UIKit, and a hand-rolled drag gesture never matches them. The summary
    /// cards are just rows; nothing here needs `scrollTo`.
    private func singleColumn(ledger: DayLedger, lifetime: LifetimeStats) -> some View {
        List {
            monthBlock(ledger).summaryListRow(top: 12, bottom: 6)
            trendBlock().summaryListRow(top: 6, bottom: 6)
            // THIRD, above the log and below the two pictures (Nuri, 2026-09-20): the
            // month says how often, the trend says how hard, the odometer says how much,
            // all of it — and the sessions it adds up sit right under it.
            lifetimeBlock(lifetime).summaryListRow(top: 6, bottom: 6)
            sessionRows
        }
        .historyList()
        // ALWAYS via `.background {}`, never as a ZStack sibling — as a sibling it
        // disturbs the safe-area layout and the title creeps under the status bar.
        .background { AppBackground() }
    }

    /// An iPad in landscape: the summaries left, the log right.
    ///
    /// Full-bleed, the one `List` ran the month grid's cells out to the size of coasters
    /// and made every session row a metre wide. Splitting it is not a new screen — it is
    /// the same two questions in the same order, turned ninety degrees, and the right
    /// half stays a real `List` because the swipes are the whole reason it is one. The
    /// summaries move to a plain ScrollView because nothing in them swipes, and because a
    /// column that scrolls on its own is the point: the grid and the trend stay put while
    /// years of sessions go past them.
    private func twoPane(ledger: DayLedger,
                         lifetime: LifetimeStats,
                         width: CGFloat) -> some View {
        HStack(spacing: 0) {
            ScrollView {
                // 12 between the blocks and 12 above the first, so the column keeps the
                // rhythm the `List` rows have today.
                VStack(spacing: 12) {
                    summaryBlocks(ledger: ledger, lifetime: lifetime, wide: true)
                }
                .padding(.top, 12)
                .padding(.bottom, 24)
            }
            // Two cards usually fit the height; nothing should rubber-band when they do.
            .scrollBounceBehavior(.basedOnSize)
            .scrollEdgeEffectStyle(.soft, for: .bottom)
            .frame(width: summaryWidth(in: width))

            List { sessionRows }
                .historyList()
        }
        // ONE field behind both panes rather than one each: the mesh, its highlight and
        // the vignette are all relative to their own bounds, so two would seam down the
        // divider. Still `.background {}` and never a ZStack sibling — as a sibling it
        // disturbs the safe-area layout and the title creeps under the status bar.
        .background { AppBackground() }
    }

    /// How wide the summaries stand.
    ///
    /// A little under half the window, stopping at the house's regular-width column: past
    /// 560 the five-week grid spaces its cells until a week stops reading as a row, and
    /// the log is the longer thing, so it keeps the larger share. 420 is the floor the
    /// cards were measured at — below it the trend chips wrap — and never more than half
    /// the window, so a narrow regular-width landscape window cannot hand the sessions a
    /// gutter to live in.
    private func summaryWidth(in total: CGFloat) -> CGFloat {
        min(max(total * 0.44, 420), Metrics.maxContentWidthRegular, total * 0.5)
    }

    // MARK: - The blocks both layouts are made of

    /// One card per 5-WEEK WINDOW, swiped like every other deck (Nuri, 2026-08-10:
    /// "swipable cards of previous windows") — and only a deck once a second window
    /// exists to swipe to, the same honesty rule as Today's.
    ///
    /// The block pads ITSELF rather than leaning on a row inset, because it has to sit in
    /// two containers: the deck manages its own margins so the neighbour peeks at the
    /// container's edge — the screen in one column, the left pane in two — and the lone
    /// card sits on the house grid either way.
    @ViewBuilder
    private func monthBlock(_ ledger: DayLedger, wide: Bool = false) -> some View {
        Group {
            if monthPageCount(ledger) > 1 {
                // In the wide pane the neighbour must not peek: cut off by the pane's
                // edge rather than the screen's, a sliver of card reads as a glitch
                // (Nuri's scribble, 2026-09-19). The deck still pages; the indicator
                // says so instead.
                monthDeck(ledger, peeks: !wide)
            } else {
                monthCard(0, ledger).padding(.horizontal, Metrics.hPadding)
            }
        }
        .tourAnchor(.historyMonth)
        // Stagger stops at the cards, which are the only rows guaranteed to be on screen
        // at load. A `List` is lazy, so a staggered session row would fade and rise as it
        // scrolled under your thumb — an entrance animation replayed mid-scroll reads as
        // the screen glitching.
        .staggerIn(0)
    }

    /// One trend card per routine with measured pulls — see `TrendDeck`, which owns the
    /// grip selection and builds its model off the main actor, so a chip tap re-runs
    /// that block alone and never this screen.
    private func trendBlock(wide: Bool = false) -> some View {
        TrendDeck(generation: derived.generation, logs: logs,
                  routineNames: templates.routineNames, wide: wide)
            .equatable()
            .staggerIn(1)
    }

    /// Both summaries, in order, for the wide layout's left column. The single column
    /// places the same two blocks as `List` rows — same views, same tour anchor, same
    /// entrance — so the two layouts cannot drift apart.
    @ViewBuilder
    private func summaryBlocks(ledger: DayLedger,
                               lifetime: LifetimeStats,
                               wide: Bool = false) -> some View {
        monthBlock(ledger, wide: wide)
        trendBlock(wide: wide)
        lifetimeBlock(lifetime)
    }

    /// The all-time card — see `LifetimeCard`. Same margins as the two blocks above it.
    private func lifetimeBlock(_ lifetime: LifetimeStats) -> some View {
        LifetimeCard(stats: lifetime)
            .padding(.horizontal, Metrics.hPadding)
    }

    /// The log itself: the label, the sessions, the door to the rest, the footnote.
    /// `List` rows in BOTH layouts — that is what keeps the swipes, and it is why the
    /// right-hand pane is a `List` rather than another ScrollView.
    @ViewBuilder
    private var sessionRows: some View {
        // A plain row, never a `Section` header: plain-style headers PIN, and the
        // content then scrolls illegibly behind a clear background.
        CapsLabel(String(localized: "Sessions")).houseListRow(top: 10, bottom: 2)

        // TEN, then a door (Nuri, 2026-08-10): the log grows forever, and a habit app's
        // history would soon be a hundred rows of scroll under two cards. The recent ones
        // are the ones you check; the rest are one tap away, not gone.
        ForEach(visibleLogs) { log in
            SessionRow(log: log,
                       name: displayName(of: log),
                       leadingGrip: reps(for: log).first?.grip)
                .sessionListRow()
                .swipeActions(edge: .leading, allowsFullSwipe: false) {
                    Button {
                        analysisExport = makeExportRequest(workout: log)
                    } label: {
                        Label("Share", systemImage: "square.and.arrow.up")
                    }
                    .tint(Accent.graphite)
                    .accessibilityLabel("Export workout")
                }
                .swipeActions(edge: .trailing, allowsFullSwipe: true) {
                    deleteButton(log)
                }
        }

        if !showAllSessions, logs.count > Self.recentSessionLimit {
            showAllRow
        }

        footnote.houseListRow(top: 14, bottom: 24)
    }

    /// Offered even with nothing logged — the sheet then says plainly that there is
    /// nothing to export, which is a better answer than a toolbar item that is present
    /// on some launches and missing on others.
    private var exportButton: some View {
        Button {
            analysisExport = makeExportRequest()
        } label: {
            Image(systemName: "doc.text")
                .font(.system(.body, weight: .semibold))
                .foregroundStyle(Accent.graphite)
                // A toolbar item sizes its own hit area, but the shape has to be declared
                // or only the glyph's opaque pixels take the tap.
                .contentShape(.rect)
        }
        .accessibilityLabel("Export for analysis")
    }

    /// Freeze an address at the tap — nothing here walks the history. The sheet opens
    /// at once and its worker fetches the rows and their blobs off the main actor; this
    /// used to copy both blob columns of every log before the sheet could appear.
    private func makeExportRequest(workout: WorkoutLog? = nil) -> AnalysisExportRequest {
        let source = AnalysisExportAssembler.Source(
            container: modelContext.container,
            workoutID: workout?.id,
            routineNames: templates.routineNames,
            today: clock.today)
        return AnalysisExportRequest(
            source: source,
            workout: workout.map { .init(name: displayName(of: $0), day: $0.day) })
    }

    private static let recentSessionLimit = 10

    private var visibleLogs: [WorkoutLog] {
        showAllSessions ? logs : Array(logs.prefix(Self.recentSessionLimit))
    }

    /// The door to the rest of the log. Says HOW MANY it is holding back — "show more"
    /// hiding an unknown quantity reads as a trick.
    private var showAllRow: some View {
        Button {
            withAnimation(Motion.state(reduceMotion)) { showAllSessions = true }
        } label: {
            HStack(spacing: 6) {
                Text("Show \(logs.count - Self.recentSessionLimit) earlier sessions")
                Image(systemName: "chevron.down")
                    .font(.system(.caption, weight: .semibold))
            }
            .font(.system(.footnote, weight: .semibold))
            .foregroundStyle(Accent.graphite)
            .frame(maxWidth: .infinity, minHeight: 44)
            // MANDATORY: a centred label in a full-width row — padding and clear
            // background contribute nothing to SwiftUI's default hit area.
            .contentShape(.rect)
        }
        .buttonStyle(PressFeedbackButtonStyle())
        .houseListRow(top: 2, bottom: 2)
    }

    /// Full swipe is ON here, and that is the one place this screen departs from Maxes.
    /// Maxes disables it because `recordMax` always stamps `recordedAt` as NOW, so
    /// restoring a max would quietly move it to today. A `WorkoutLog` is frozen — undo
    /// puts back the same id, the same dates and the same raw blobs — so the house
    /// gesture applies: a short drag reveals the pill, carrying it through commits.
    private func deleteButton(_ log: WorkoutLog) -> some View {
        Button(role: .destructive) {
            _ = templates.deleteSession(log)
        } label: {
            Label("Delete", systemImage: "trash")
        }
        .tint(Accent.alarm)
        .accessibilityLabel(String(localized: "Delete this session, \(displayName(of: log)) on \(log.historyDate().formatted(.dateTime.weekday(.wide).day().month(.wide)))"))
    }

    /// Delete carries no confirmation dialog — the same bargain as a deleted routine. A
    /// dialog in front of every swipe is a tax on the taps that meant it, and people
    /// learn to dismiss it blindly; ten seconds of Undo costs the confident nothing.
    @ViewBuilder
    private var undoBar: some View {
        VStack(spacing: 0) {
            if templates.lastDeletedSession != nil {
                UndoBar(message: String(localized: "Session deleted")) {
                    templates.undoDeleteSession()
                    undoTick += 1
                }
                .transition(.move(edge: .bottom).combined(with: .opacity))
            }
        }
        // 8, not 4: the bar sat close enough to the tab bar that a reach for Undo
        // could land on the tab strip instead (rank 3 audit finding).
        .padding(.bottom, 8)
        .animation(Motion.state(reduceMotion),
                   value: templates.lastDeletedSession)
    }

    /// What a delete here does and does not touch, said once at the foot of the list.
    /// Deleting a session moves the month grid and the trend under it, and that is a
    /// surprising amount of consequence for a swipe — so the screen states it rather
    /// than letting the grid quietly change shape.
    private var footnote: some View {
        Text("Deleting a session removes it from your streak and your trends too. Your routines are untouched.")
            .font(.system(.footnote))
            .foregroundStyle(Ink.tertiary)
            .fixedSize(horizontal: false, vertical: true)
    }

    private var subtitle: String {
        logs.isEmpty ? String(localized: "Sessions and trends") : String(localized: "\(logs.count) session\(logs.count == 1 ? "" : "s")")
    }

    // MARK: - Empty

    private var emptyCard: some View {
        MaterialCard(surface: .flat) {
            VStack(alignment: .leading, spacing: 12) {
                CapsLabel(String(localized: "Nothing here yet"))
                Image(systemName: "chart.xyaxis.line")
                    .font(.system(.largeTitle, weight: .light))
                    .foregroundStyle(Ink.tertiary.opacity(0.55))
                    .accessibilityHidden(true)
                Text("Finish a session and it lands here — what you did, how hard it felt, and how your load is moving per grip.")
                    .font(.system(.subheadline))
                    .foregroundStyle(Ink.secondary)
            }
            .fixedSize(horizontal: false, vertical: true)
        }
    }

    // MARK: - The month

    /// The month grid's whole input, folded ONCE.
    ///
    /// Every cell used to answer its own questions by scanning the entire log array:
    /// `fraction`, `climb` and `benchmark` each filtered all of it, per day, and
    /// `trackingSince` was a computed `min()` over every log READ FROM INSIDE two
    /// per-day loops. One 35-day card cost on the order of two hundred whole-array
    /// passes, rebuilt on every body evaluation — which, on a horizontal deck, means
    /// during the swipe. Invisible at ninety sessions; not invisible at two years of
    /// twice-daily training, which is exactly the person this screen is for.
    ///
    /// Now: one pass over the logs, and every cell is a dictionary lookup. It is a
    /// value built in `body` rather than a cache, deliberately — `logs` is a `@Query`
    /// that gains and loses rows, and a cache over it needs an invalidation key that
    /// `count` cannot honestly provide (delete one, add one).
    /// Internal rather than private ONLY so `HistoryLedgerTests` can reach it: it now
    /// owns the day-fill rule the grid draws, and rewriting that rule without a test
    /// pinning it is how a calendar starts quietly lying.
    struct DayLedger {
        /// What happened on one day, in the four terms the grid asks about.
        private struct Day {
            var hangs = 0
            /// The target frozen INTO the logs, not today's setting: a day trained under
            /// a once-a-day routine was a full day, even if the routine now asks for two.
            var target = 1
            var settled = false
            var climbed = false
            var benchmarked = false
        }

        private var days: [Int: Day] = [:]
        /// The first day there is any evidence of. Days before it are not missed days —
        /// nobody can fail on a day they did not own the app, and drawing them as empty
        /// boxes indistinguishable from a skipped session is the screen quietly lying
        /// about a habit it never observed.
        let trackingSince: DayStamp

        init(logs: [WorkoutLog], today: DayStamp, trackingSince: DayStamp? = nil) {
            var earliest: Int?
            for log in logs {
                earliest = Swift.min(earliest ?? log.dayKey, log.dayKey)
                var day = days[log.dayKey] ?? Day()
                day.target = Swift.max(day.target, log.sessionsPerDayTarget)
                if log.kind.countsAsHang { day.hangs += 1 }
                if log.kind.settlesDay { day.settled = true }
                // `isClimb` is the same predicate `Collection.climb(on:)` filters on;
                // only its hardest-first PRECEDENCE is dropped, and no caller here asks
                // which climb it was — the cell and the legend both want a yes or no.
                if log.kind.isClimb { day.climbed = true }
                if log.kind == .benchmark { day.benchmarked = true }
                days[log.dayKey] = day
            }
            self.trackingSince = trackingSince ?? earliest.map { DayStamp(raw: $0) } ?? today
        }

        func fraction(on day: DayStamp) -> Double {
            guard let d = days[day.raw] else { return 0 }
            // A climb — or a benchmark — FILLS the day: both complete it outright, so a
            // half-height bar would contradict the sentence on Today. The notch drawn
            // over the fill stays climb-only.
            guard !d.settled else { return 1 }
            return min(1, Double(d.hangs) / Double(d.target))
        }

        func climbed(on day: DayStamp) -> Bool { days[day.raw]?.climbed ?? false }
        func benchmarked(on day: DayStamp) -> Bool { days[day.raw]?.benchmarked ?? false }
    }

    /// Every 5-week window since tracking began, one card each, newest in front —
    /// the same deck geometry and one-page-per-swipe physics as Today and the trend.
    /// LAZY, unlike the other decks: the page count grows with the training history
    /// and a two-year habit must not build seventy hidden grids at once.
    /// `peeks` is the phone's 20 pt of neighbour at the screen edge; off in the wide
    /// pane, where the edge is a seam rather than the screen and a page indicator
    /// carries the "there is more" instead.
    private func monthDeck(_ ledger: DayLedger, peeks: Bool = true) -> some View {
        ScrollView(.horizontal) {
            LazyHStack(alignment: .top, spacing: 8) {
                ForEach(0..<monthPageCount(ledger), id: \.self) { window in
                    monthCard(window, ledger)
                        .containerRelativeFrame(.horizontal)
                }
            }
            .scrollTargetLayout()
        }
        .scrollTargetBehavior(.viewAligned(limitBehavior: .always))
        .scrollIndicators(peeks ? .hidden : .automatic)
        .contentMargins(.leading, Metrics.hPadding, for: .scrollContent)
        .contentMargins(.trailing, Metrics.hPadding + (peeks ? 8 : 0), for: .scrollContent)
    }

    /// How many windows have anything to show: from today back to the first day on
    /// record, in 35-day pages.
    private func monthPageCount(_ ledger: DayLedger) -> Int {
        let span = clock.today.raw - ledger.trackingSince.raw + 1
        return max(1, Int((Double(span) / 35).rounded(.up)))
    }

    /// Five weeks ending 35×`window` days ago. Window 0 is home — the last five weeks.
    private func monthCard(_ window: Int, _ ledger: DayLedger) -> some View {
        let days = monthDays(window)
        return MaterialCard(surface: .flat) {
            VStack(alignment: .leading, spacing: 12) {
                HStack(spacing: 8) {
                    CapsLabel(windowTitle(window, days: days))
                    Spacer(minLength: 8)
                    Button {
                        calendarShare = ShareCalendarRequest(
                            days: days,
                            ledger: ledger,
                            title: windowTitle(window, days: days),
                            today: clock.today,
                            bestPull: heaviestCurrentMax)
                    } label: {
                        Image(systemName: "square.and.arrow.up")
                            .font(.system(.body, weight: .semibold))
                            .foregroundStyle(Accent.graphite)
                            .frame(width: 44, height: 44)
                            .contentShape(.rect)
                    }
                    .buttonStyle(PressFeedbackButtonStyle())
                    .accessibilityLabel("Share this five-week calendar")
                }
                LazyVGrid(columns: Array(repeating: GridItem(.flexible(), spacing: 6), count: 7),
                          spacing: 6) {
                    ForEach(days, id: \.self) { day in
                        DayCell(fraction: ledger.fraction(on: day),
                                isToday: day == clock.today,
                                isTracked: day >= ledger.trackingSince,
                                climbed: ledger.climbed(on: day),
                                benchmarked: ledger.benchmarked(on: day))
                    }
                }
                .accessibilityElement(children: .ignore)
                .accessibilityLabel(monthSpokenSummary(days, ledger))

                Text(monthSummary(window, days: days, ledger))
                    .font(.system(.footnote))
                    .monospacedDigit()
                    .foregroundStyle(Ink.tertiary)

                // Shown only once there is a marked cell to explain. A legend for a
                // glyph nobody has produced yet is clutter teaching nothing.
                HStack(spacing: 14) {
                    if days.contains(where: { ledger.climbed(on: $0) }) {
                        HStack(spacing: 6) {
                            RoundedRectangle(cornerRadius: 3, style: .continuous)
                                .fill(Accent.graphite)
                                .frame(width: 14, height: 14)
                                .climbNotch(true)
                            Text("Climbing gym")
                                .font(.system(.footnote))
                                .foregroundStyle(Ink.tertiary)
                        }
                    }
                    if days.contains(where: { ledger.benchmarked(on: $0) }) {
                        HStack(spacing: 6) {
                            RoundedRectangle(cornerRadius: 3, style: .continuous)
                                .fill(Accent.bleu)
                                .frame(width: 14, height: 14)
                                .benchmarkBore(true, size: 5)
                            Text("Max testing")
                                .font(.system(.footnote))
                                .foregroundStyle(Ink.tertiary)
                        }
                    }
                }
                .accessibilityHidden(true)
            }
        }
    }

    /// The share card always names the heaviest CURRENT record, never an older peak
    /// from the append-only max history. Ties are stable so two equal pulls do not make
    /// the shared grip depend on dictionary iteration order.
    private var heaviestCurrentMax: ShareCalendarBestPull? {
        templates.currentMaxes.values
            .map(ShareCalendarBestPull.init)
            .sorted {
                if $0.kg != $1.kg { return $0.kg > $1.kg }
                if $0.recordedAt != $1.recordedAt { return $0.recordedAt > $1.recordedAt }
                return $0.sortKey < $1.sortKey
            }
            .first
    }

    private func monthDays(_ window: Int) -> [DayStamp] {
        (0..<35).map { clock.today - (34 - $0) - window * 35 }
    }

    /// Home says what it is; the past says WHEN it was — the window's identity once
    /// "last 5 weeks" stops being true of it.
    private func windowTitle(_ window: Int, days: [DayStamp]) -> String {
        guard window > 0, let first = days.first, let last = days.last else {
            return String(localized: "Last 5 weeks")
        }
        let from = first.date().formatted(.dateTime.day().month(.abbreviated))
        let to = last.date().formatted(.dateTime.day().month(.abbreviated))
        return String(localized: "\(from) – \(to)")
    }

    /// Counted against the days that were actually TRACKED, so a first week with the app
    /// doesn't read as 6 of 35.
    private func monthSummary(_ window: Int, days: [DayStamp], _ ledger: DayLedger) -> String {
        let tracked = days.filter { $0 >= ledger.trackingSince }
        let trained = tracked.filter { ledger.fraction(on: $0) > 0 }.count
        guard tracked.count < days.count else {
            return window == 0 ? String(localized: "\(trained) of the last 35 days trained")
                               : String(localized: "\(trained) of 35 days trained")
        }
        return String(localized: "\(trained) of \(tracked.count) days trained since you started")
    }

    /// Folds the climb/benchmark counts in, so the "a climb completes the day"
    /// distinction the grid's shape-coding carries has a spoken equivalent — the legend
    /// that explains those glyphs is itself `.accessibilityHidden(true)`, so without this
    /// the whole distinction had no accessible channel at all.
    private func monthSpokenSummary(_ days: [DayStamp], _ ledger: DayLedger) -> String {
        let tracked = days.filter { $0 >= ledger.trackingSince }
        let trained = tracked.filter { ledger.fraction(on: $0) > 0 }.count
        let full = tracked.filter { ledger.fraction(on: $0) >= 1 }.count
        let climbed = tracked.filter { ledger.climbed(on: $0) }.count
        let benchmarked = tracked.filter { ledger.benchmarked(on: $0) }.count
        var summary = String(localized: "Trained on \(trained) of \(tracked.count) tracked days, \(full) of them fully.")
        if climbed > 0 {
            summary += String(localized: " \(climbed) \(climbed == 1 ? String(localized: "day") : String(localized: "days")) at the climbing gym.")
        }
        if benchmarked > 0 {
            summary += String(localized: " \(benchmarked) \(benchmarked == 1 ? String(localized: "day") : String(localized: "days")) testing maxes.")
        }
        return summary
    }

    // MARK: - Derived

    /// What to CALL this session's routine — the routine's live name while it still
    /// exists, the frozen copy once it doesn't.
    ///
    /// The log freezes `templateName` at save time and must keep doing so: a deleted
    /// routine has to leave its history with something to be called. But that made a
    /// rename invisible here (Nuri, 2026-08-11) — rename "Daily no-hangs" to "Morning
    /// ladder" and every past session stayed filed under a name that appeared nowhere
    /// else in the app. Worse, it was already INCONSISTENT: the trend card titled itself
    /// from the newest log in the group, so one new session made the card say the new
    /// name while every row beneath it said the old one.
    ///
    /// Resolving at DISPLAY time rather than rewriting the logs is the cheaper and more
    /// honest fix: renaming stays a routine edit instead of a write across the whole
    /// history, renaming back needs no second migration, and what the session actually
    /// WAS — its plan, its reps, its grips — is still frozen and still untouchable.
    private func displayName(of log: WorkoutLog) -> String {
        guard let id = log.templateID else { return log.templateName }
        return templates.routineNames[id] ?? log.templateName
    }
}

// MARK: - Rows and lists shared by both layouts

private extension View {
    /// The house `List` treatment. Shared so the single column and the wide layout's
    /// right-hand pane cannot drift. `AppBackground` is deliberately NOT here: one
    /// column puts it on this list, the wide layout puts one field behind both panes.
    func historyList() -> some View {
        self
            .listStyle(.plain)
            // The list draws its own cards on the slate field; the system's grouped fill
            // would sit between them and the background they are meant to float on.
            .scrollContentBackground(.hidden)
            .scrollEdgeEffectStyle(.soft, for: .bottom)
    }

    /// A summary block as a `List` row. NO horizontal inset, unlike `houseListRow`: the
    /// blocks pad themselves so they can sit in a plain column too, and a deck has to
    /// reach the container's edge for its neighbour to peek at it.
    func summaryListRow(top: CGFloat, bottom: CGFloat) -> some View {
        self
            .listRowBackground(Color.clear)
            .listRowSeparator(.hidden)
            .listRowInsets(EdgeInsets(top: top, leading: 0, bottom: bottom, trailing: 0))
    }
}

// MARK: - Day cell

/// Shape-encoded, not colour-only: the fill RISES with how much of the day you did, and
/// today carries a ring. It has to survive Reduce Transparency and colourblindness, and it
/// speaks the same language as the strip on Today.
private struct DayCell: View {
    let fraction: Double
    let isToday: Bool
    /// false = before the first session on record. Drawn as a hairline rather than an
    /// empty box, so a day you never had the app cannot be mistaken for one you skipped.
    var isTracked: Bool = true
    /// A day at the climbing gym — full, and notched. See `ClimbNotch`.
    var climbed: Bool = false
    /// A max-testing day — full, BLEU, and bored (Nuri, 2026-08-10: "see your maxes"
    /// across the five weeks at a glance). Bleu because these are the days the gauge
    /// measured your ceiling — the one meaning bleu carries everywhere — and the bore
    /// because colour alone must never carry a calendar state. A climb on the same day
    /// wins the cell: the notch and graphite stay, matching the day-story precedence
    /// everywhere else.
    var benchmarked: Bool = false

    private var showsBenchmark: Bool { benchmarked && !climbed }

    var body: some View {
        ZStack {
            if isTracked {
                RoundedRectangle(cornerRadius: 5, style: .continuous)
                    .fill(Ink.tertiary.opacity(0.14))
            } else {
                Capsule()
                    .fill(Ink.tertiary.opacity(0.22))
                    .frame(height: 2)
            }
            if fraction > 0 {
                GeometryReader { geo in
                    RoundedRectangle(cornerRadius: 5, style: .continuous)
                        .fill(showsBenchmark ? Accent.bleu : Accent.graphite)
                        .frame(height: geo.size.height * fraction)
                        .frame(maxHeight: .infinity, alignment: .bottom)
                }
                .climbNotch(climbed)
                .benchmarkBore(showsBenchmark, size: 8)
            }
            if isToday {
                // Ink on a benchmark day: a bleu ring on the bleu fill is invisible,
                // and today must stay findable on the day you tested.
                RoundedRectangle(cornerRadius: 5, style: .continuous)
                    .strokeBorder(showsBenchmark ? Ink.primary : Accent.bleu, lineWidth: 2)
            }
        }
        .frame(height: 28)
        // The grid carries one spoken summary; 35 individually-labelled cells would be a
        // minute of VoiceOver to learn nothing.
        .accessibilityHidden(true)
    }
}


// MARK: - Session row

/// One past session, drawn as a MUSIC-APP ROW: artwork, two lines of text, a trailing
/// accessory (Nuri, 2026-08-08 — *"make the list of old sessions look just like the
/// Apple Music song list"*).
///
/// The anatomy is the point. Artwork identifies the item before you read anything, the
/// title says what it was, the subtitle carries the detail, and the date sits right
/// where Music puts its accessories. What it replaced — a material card per row, stats
/// on their own line, the date floated top-right — made ten sessions read as ten
/// documents and fitted four on a screen.
private struct SessionRow: View {
    @Environment(\.weightUnit) private var weightUnit
    let log: WorkoutLog
    /// Resolved by `HistoryView.displayName(of:)` — the routine's live name while it
    /// exists, the log's frozen copy once it doesn't. Passed IN rather than read from
    /// the store here so the row stays a dumb leaf with nothing to observe.
    let name: String
    /// The session's first grip, for the artwork tile. Passed in from History's rep
    /// cache rather than decoded here: `resultsData` is write-once and already decoded
    /// once per log ever, and a row that re-parsed JSON would make the list slower every
    /// week Nuri trains.
    var leadingGrip: GripSpec?

    /// Music's artwork is a rounded square you read before the words. Ours is the app's
    /// own iconography — the grip you pulled, or a climber for a gym session.
    ///
    /// **CLAMPED**, the `ConsistencyCard` dot's own fix: uncapped, this tile grows with
    /// every row in the list, and `trailing`'s `.fixedSize(horizontal: true)` plus
    /// `title`'s bare `.lineLimit(1)` (no shrink path, unlike `subtitle` beside it) meant
    /// the routine name was squeezed hardest with nowhere left to give at accessibility
    /// sizes.
    @ScaledMetric(relativeTo: .subheadline) private var rawArtwork: CGFloat = 44
    private var artwork: CGFloat { min(rawArtwork, 56) }

    var body: some View {
        HStack(spacing: 12) {
            artworkTile
            VStack(alignment: .leading, spacing: 2) {
                Text(title)
                    .font(.system(.subheadline, weight: .medium))
                    .foregroundStyle(Ink.primary)
                    .lineLimit(1)
                    // Same shrink floor as `subtitle` below it: with no minimum scale
                    // factor the routine name — the row's own title — was the one line
                    // in this list with no path but truncation as it grows toward the
                    // artwork tile's new ceiling.
                    .minimumScaleFactor(0.85)
                Text(subtitle)
                    .font(.system(.footnote))
                    .monospacedDigit()
                    .foregroundStyle(Ink.secondary)
                    .lineLimit(1)
                    .minimumScaleFactor(0.85)
            }
            Spacer(minLength: 8)
            trailing
        }
        .padding(.vertical, 4)
        // The row is one thing to VoiceOver; four separate stops to read one session is
        // the classic list-accessibility failure.
        .accessibilityElement(children: .ignore)
        .accessibilityLabel(spoken)
    }

    private var artworkTile: some View {
        RoundedRectangle(cornerRadius: 10, style: .continuous)
            .fill(Ink.tertiary.opacity(0.16))
            .frame(width: artwork, height: artwork)
            .overlay {
                if log.kind.isClimb {
                    Image(systemName: "figure.climbing")
                        .font(.system(.body, weight: .medium))
                        .foregroundStyle(Accent.graphite)
                } else if log.kind == .benchmark {
                    // The Maxes tab's own symbol — kilograms on a scale.
                    Image(systemName: "scalemass.fill")
                        .font(.system(.body, weight: .medium))
                        .foregroundStyle(Accent.graphite)
                } else if log.kind == .hangManual {
                    Image(systemName: "dumbbell.fill")
                        .font(.system(.body, weight: .medium))
                        .foregroundStyle(Accent.graphite)
                } else if let leadingGrip {
                    FingerGlyph(fingers: leadingGrip.fingers,
                                position: leadingGrip.position, dot: 5, gap: 2.5)
                } else {
                    // A session whose reps did not decode still gets a tile rather than
                    // a hole — the row's shape must not depend on a blob surviving.
                    Image(systemName: "hand.raised")
                        .font(.system(.footnote, weight: .medium))
                        .foregroundStyle(Ink.tertiary)
                }
            }
            .accessibilityHidden(true)
    }

    private var title: String {
        log.kind.isLoggedByHand ? log.kind.name : name
    }

    /// Music's second line is the artist; ours is what the session cost. Compact, and
    /// unit-labelled only where a bare number would be ambiguous.
    private var subtitle: String {
        if log.kind.isLoggedByHand {
            let parts = handLoggedDetails
            if !parts.isEmpty { return parts.joined(separator: " · ") }
            return log.kind.isClimb ? String(localized: "At the climbing gym") : String(localized: "Away from the gauge")
        }
        guard log.kind != .benchmark else { return String(localized: "Tested your maxes") }
        let effort = String(localized: "\(log.completedReps)/\(log.plannedReps) pulls · \(PlanMath.clockText(Int(log.totalHeldSeconds.rounded())))")
        guard log.peakKg > 0 else { return effort }
        return effort + " · " + String(localized: "\(weightUnit.number(log.peakKg)) \(weightUnit.symbol)")
    }

    private var handLoggedDetails: [String] {
        var parts: [String] = []
        if let minutes = log.sessionMinutes {
            parts.append(durationText(minutes))
        }
        if let grade = log.grade { parts.append(grade.name) }
        if let strain = log.fingerStrain { parts.append(String(localized: "Fingers \(strain.name.lowercased())")) }
        return parts
    }

    private func durationText(_ minutes: Int) -> String {
        if minutes % 60 == 0 { return String(localized: "\(minutes / 60)h") }
        if minutes > 60 { return String(localized: "\(minutes / 60)h\(minutes % 60)") }
        return String(localized: "\(minutes)m")
    }

    private func spokenDuration(_ minutes: Int) -> String {
        if minutes % 60 == 0 {
            return String(localized: "\(minutes / 60) \(minutes == 60 ? String(localized: "hour") : String(localized: "hours"))")
        }
        if minutes > 60 {
            return String(localized: "\(minutes / 60) hours and \(minutes % 60) minutes")
        }
        return String(localized: "\(minutes) minutes")
    }

    /// The date, with the grade under it when there is one — Music's trailing accessory
    /// slot, carrying the two things you scan a log for.
    private var trailing: some View {
        VStack(alignment: .trailing, spacing: 3) {
            Text(log.historyDate().formatted(.dateTime.weekday(.abbreviated).day().month(.abbreviated)))
                .font(.system(.footnote))
                .monospacedDigit()
                .foregroundStyle(Ink.tertiary)
            if let grade = log.grade {
                Text(grade.name)
                    .font(.system(.caption2, weight: .semibold))
                    .foregroundStyle(Ink.secondary)
                    .padding(.horizontal, 7)
                    .padding(.vertical, 2)
                    .background(Capsule().stroke(Ink.tertiary.opacity(0.35), lineWidth: 1))
            }
        }
        .fixedSize(horizontal: true, vertical: false)
    }

    private var spoken: String {
        let when = log.historyDate().formatted(.dateTime.weekday(.wide).day().month(.wide))
        var parts: [String]
        if log.kind.isLoggedByHand {
            parts = [log.kind.name, when]
            if let minutes = log.sessionMinutes {
                parts.append(String(localized: "for \(spokenDuration(minutes))"))
            }
            if let grade = log.grade { parts.append(String(localized: "felt \(grade.name)")) }
            if let strain = log.fingerStrain {
                parts.append(String(localized: "your fingers felt \(strain.name.lowercased())"))
            }
            if parts.count == 2 {
                parts.append(log.kind.isClimb ? String(localized: "at the climbing gym") : String(localized: "away from the gauge"))
            }
        } else if log.kind == .benchmark {
            parts = [log.kind.name, when, String(localized: "tested your maxes")]
        } else {
            parts = [
                name,
                when,
                String(localized: "\(log.completedReps) of \(log.plannedReps) pulls completed"),
                String(localized: "\(Int(log.totalHeldSeconds.rounded())) seconds under tension"),
            ]
            if log.peakKg > 0 {
                parts.append(String(localized: "peak \(weightUnit.number(log.peakKg)) \(weightUnit.spokenName)"))
            }
        }
        if !log.kind.isLoggedByHand, let grade = log.grade {
            parts.append(String(localized: "felt \(grade.name)"))
        }
        return parts.joined(separator: ", ")
    }
}
