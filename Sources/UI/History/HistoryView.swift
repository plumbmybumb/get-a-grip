// SPDX-License-Identifier: MPL-2.0
// Original contributions Copyright 2026 Nuri Bruner.

import Charts
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

    /// Every max ever written, oldest first — the export's MAX HISTORY section is the
    /// progression itself, so it needs the whole append-only ledger rather than the
    /// store's newest-per-grip fold. Nothing else on this screen reads it; a `@Query`
    /// costs nothing until something does.
    @Query(sort: [SortDescriptor(\MaxRecord.recordedAt)])
    private var maxRecords: [MaxRecord]

    @Environment(DayClock.self) private var clock
    @Environment(TemplateStore.self) private var templates
    @Environment(\.accessibilityReduceMotion) private var reduceMotion

    /// Which grip's trend is on screen. nil means "the most-trained one". ONE selection
    /// shared across every card in the trend deck, deliberately: picking "20 mm 4F HC"
    /// on one routine's card keeps it picked as you swipe to the next, which is exactly
    /// how you compare one grip across routines. Cards where the grip does not exist
    /// fall back to their own most-trained one — and remember nothing.
    @State private var selectedGripKey: String?
    @State private var undoTick = 0
    /// Expanded stays expanded while the tab lives; collapsing back is just scrolling
    /// up, so there is no "show fewer".
    @State private var showAllSessions = false
    @State private var calendarShare: ShareCalendarRequest?
    @State private var analysisExport: AnalysisExportRequest?

    /// Decoded rep blobs, once per log EVER.
    ///
    /// `WorkoutLog.resultsData` is write-once, so this cache never invalidates — and
    /// without it every body evaluation re-decoded the whole history: tapping a trend
    /// chip walked every log's JSON on the main thread, a button that gets slower every
    /// week you train. A reference type mutated during body, deliberately outside
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

    /// One generation of chart results. Preparing it once per body avoids hashing the
    /// entire history for every card and every grip lookup. Dropping the old generation
    /// also keeps repeated delete/undo/save cycles from accumulating stale series.
    private final class TrendCache {
        var logIDs: [UUID] = []
        var gripOptions: [String: [GripOption]] = [:]
        var series: [String: [TrendPoint]] = [:]

        func prepare(logIDs: [UUID]) {
            guard self.logIDs != logIDs else { return }
            self.logIDs = logIDs
            gripOptions.removeAll(keepingCapacity: true)
            series.removeAll(keepingCapacity: true)
        }
    }
    @State private var trendCache = TrendCache()

    var body: some View {
        trendCache.prepare(logIDs: logs.map(\.id))
        // A real `List` rather than `ScreenScaffold`'s ScrollView, for exactly the
        // reason Maxes is one: swipe-to-delete, the row-slide physics and the full-swipe
        // commit all come from UIKit, and a hand-rolled drag gesture never matches them.
        // The two summary cards are just rows; nothing here needs `scrollTo`.
        return NavigationStack {
            List {
                if logs.isEmpty {
                    emptyCard.houseListRow(top: 12, bottom: 10)
                } else {
                    // Stagger stops at the cards, which are the only rows guaranteed to
                    // be on screen at load. A `List` is lazy, so a staggered session row
                    // would fade and rise as it scrolled under your thumb — an entrance
                    // animation replayed mid-scroll reads as the screen glitching.
                    // One card per 5-WEEK WINDOW, swiped like every other deck (Nuri,
                    // 2026-08-10: "swipable cards of previous windows") — and only a
                    // deck once a second window exists to swipe to, the same honesty
                    // rule as Today's.
                    // Folded ONCE here and handed down, rather than each of the ~200
                    // questions a card asks re-scanning the whole log array. See
                    // `DayLedger`.
                    let ledger = DayLedger(logs: logs, today: clock.today, trackingSince: templates.trackingSince)
                    Group {
                        if monthPageCount(ledger) > 1 {
                            monthDeck(ledger)
                                .listRowBackground(Color.clear)
                                .listRowSeparator(.hidden)
                                .listRowInsets(EdgeInsets(top: 12, leading: 0,
                                                          bottom: 6, trailing: 0))
                        } else {
                            monthCard(0, ledger).houseListRow(top: 12, bottom: 6)
                        }
                    }
                    .tourAnchor(.historyMonth)
                    .staggerIn(0)

                    // One trend card per routine with measured pulls, and the deck only
                    // exists once a second routine has data to swipe to — the same rule
                    // as Today's: a permanent sliver of "more" on a screen with one
                    // routine would say there is more when there isn't.
                    // Bound once: it walks every log, and reading it three times in one
                    // body evaluation walked them three times.
                    let routines = routineOptions
                    // Indexed once for the deck. Each card then scans only its own
                    // sessions for grip options and series instead of filtering the
                    // entire history once per routine, per chip tap.
                    let logsByRoutine = routineLogIndex
                    Group {
                        if routines.count > 1 {
                            trendDeck(routines, logsByRoutine: logsByRoutine)
                                .listRowBackground(Color.clear)
                                .listRowSeparator(.hidden)
                                // Zero horizontal insets: the deck manages its own
                                // margins so the neighbour peeks at the SCREEN edge,
                                // not at the row's inset.
                                .listRowInsets(EdgeInsets(top: 6, leading: 0,
                                                          bottom: 6, trailing: 0))
                        } else if let only = routines.first {
                            trendCard(only, logs: logsByRoutine[only.key, default: []])
                                .houseListRow(top: 6, bottom: 6)
                        } else {
                            emptyTrendCard.houseListRow(top: 6, bottom: 6)
                        }
                    }
                    .staggerIn(1)

                    // A plain row, never a `Section` header: plain-style headers PIN, and
                    // the content then scrolls illegibly behind a clear background.
                    CapsLabel(String(localized: "Sessions")).houseListRow(top: 10, bottom: 2)

                    // TEN, then a door (Nuri, 2026-08-10): the log grows forever, and a
                    // habit app's history would soon be a hundred rows of scroll under
                    // two cards. The recent ones are the ones you check; the rest are
                    // one tap away, not gone.
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
            }
            .listStyle(.plain)
            // The list draws its own cards on the slate field; the system's grouped fill
            // would sit between them and the background they are meant to float on.
            .scrollContentBackground(.hidden)
            // ALWAYS via `.background {}`, never as a ZStack sibling — as a sibling it
            // disturbs the safe-area layout and the title creeps under the status bar.
            .background { AppBackground() }
            .scrollEdgeEffectStyle(.soft, for: .bottom)
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

    /// Freeze cheap model fields at the tap; the sheet decodes blobs and formats
    /// them on a worker, keeping long-history exports off the interaction path.
    private func makeExportRequest(workout: WorkoutLog? = nil) -> AnalysisExportRequest {
        let snapshot = AnalysisExportAssembler.snapshot(
            logs: workout.map { [$0] } ?? logs,
            maxRecords: maxRecords,
            displayName: { displayName(of: $0) },
            today: clock.today)
        return AnalysisExportRequest(snapshot: snapshot, isWorkout: workout != nil)
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
        MaterialCard {
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
    private func monthDeck(_ ledger: DayLedger) -> some View {
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
        .scrollIndicators(.hidden)
        .contentMargins(.leading, Metrics.hPadding, for: .scrollContent)
        .contentMargins(.trailing, Metrics.hPadding + 8, for: .scrollContent)
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
        return MaterialCard {
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

    // MARK: - Trend

    /// Average load per session for ONE grip, WITHIN ONE ROUTINE — one CARD per
    /// routine, swiped exactly like Today's deck (Nuri, 2026-08-10: cards, "so you can
    /// swipe to see your history on each routine"; it replaced a scope menu in the
    /// header). Per routine because the same grip at two intensities is two different
    /// training lines: a 12 kg repeater and a 30 kg max pull share a chip and nothing
    /// else, and averaging them drew a zigzag that tracked which routine ran, not how
    /// strong the fingers were getting. Averaged rather than peak because this is
    /// volume training: a peak is one moment and mostly noise, while the mean across
    /// the session is what the fingers actually absorbed.
    ///
    /// Three horizontal gestures share this screen — the deck, each card's grip chips,
    /// and swipe-to-delete on the rows below — which is why the deck moves ONE card
    /// per swipe (each page is a destination, not a distance) and the chip row hands
    /// the drag back to the deck whenever its chips fit (`.basedOnSize`).
    private func trendDeck(_ options: [RoutineOption],
                           logsByRoutine: [String: [WorkoutLog]]) -> some View {
        ScrollView(.horizontal) {
            HStack(alignment: .top, spacing: 8) {
                ForEach(options, id: \.key) { option in
                    trendCard(option, logs: logsByRoutine[option.key, default: []])
                        .containerRelativeFrame(.horizontal)
                }
            }
            .scrollTargetLayout()
        }
        .scrollTargetBehavior(.viewAligned(limitBehavior: .always))
        .scrollIndicators(.hidden)
        // Today's deck geometry, verbatim: settled cards on the house grid, the
        // neighbour peeking 20 pt at the screen edge.
        .contentMargins(.leading, Metrics.hPadding, for: .scrollContent)
        .contentMargins(.trailing, Metrics.hPadding + 8, for: .scrollContent)
    }

    /// One routine's trend. `maxHeight: .infinity` makes every card in the deck stand
    /// the deck's full height — a half-mast "one session so far" card beside a full
    /// chart would read as a rendering fault, not as less data.
    private func trendCard(_ option: RoutineOption, logs: [WorkoutLog]) -> some View {
        // Computed ONCE per card and handed down. `gripOptions` scans this routine's
        // indexed slice and decodes its reps, and it used to be called afresh by the picker,
        // twice more by `currentGripKey` — itself called per CHIP — and again by
        // `trendSeries`: a five-chip card walked the whole history a dozen times over to
        // draw one chart.
        let grips = gripOptions(in: logs, routineKey: option.key)
        let selected = currentGrip(among: grips)
        return MaterialCard {
            VStack(alignment: .leading, spacing: 12) {
                CapsLabel(String(localized: "Load per grip"))
                // The page's identity — what the old menu stated in the corner, said at
                // card weight now that swiping is how the other routines are reached.
                Text(option.name)
                    .font(.system(.title3, weight: .semibold))
                    .foregroundStyle(Ink.primary)
                    .lineLimit(1)
                    .minimumScaleFactor(0.8)

                gripPicker(grips, selected: selected)

                let series = trendSeries(in: logs, grip: selected, routineKey: option.key)
                if series.count < 2 {
                    Text("One session so far. A second gives this a direction.")
                        .font(.system(.subheadline))
                        .foregroundStyle(Ink.secondary)
                        .fixedSize(horizontal: false, vertical: true)
                } else {
                    chart(series)
                    Text(trendSummary(series))
                        .font(.system(.footnote))
                        .monospacedDigit()
                        .foregroundStyle(Ink.tertiary)
                }
            }
            .frame(maxHeight: .infinity, alignment: .top)
        }
    }

    /// Sessions exist but none carried kilograms — every pull so far was gauge-free.
    private var emptyTrendCard: some View {
        MaterialCard {
            VStack(alignment: .leading, spacing: 12) {
                CapsLabel(String(localized: "Load per grip"))
                Text("No measured pulls to chart yet.")
                    .font(.system(.subheadline))
                    .foregroundStyle(Ink.secondary)
            }
        }
    }

    private func gripPicker(_ options: [GripOption], selected: String?) -> some View {
        ScrollView(.horizontal) {
            HStack(spacing: 8) {
                ForEach(options, id: \.key) { option in
                    Chip(title: option.grip.shortName,
                         isSelected: option.key == selected) {
                        selectedGripKey = option.key
                    }
                    // Chips fill their container by default, which is right in a grid and
                    // wrong in a horizontal scroller.
                    .fixedSize()
                    .accessibilityLabel(option.grip.spoken)
                }
            }
            .padding(.vertical, 2)
        }
        .scrollIndicators(.hidden)
        // MANDATORY inside the deck, not polish: without it a chip row that FITS still
        // claims every horizontal drag that starts on it, and on a one-chip card that
        // is a dead stripe where the page gesture silently stops working.
        .scrollBounceBehavior(.basedOnSize)
        // Every other Chip-driven selection in the codebase ticks on the choice
        // (`IntChipRow`, `PositionChipRow`, `HandModeChipRow`, `MaxSideChipRow`) — this
        // was the one selection tap on the screen with no tactile confirmation.
        .sensoryFeedback(.selection, trigger: selectedGripKey)
    }

    private func chart(_ series: [TrendPoint]) -> some View {
        Chart(series) { point in
            // The runner's own brush: the trace fills under its curve with bleu
            // 0.28 → 0.02 (ForceTraceView), and these are the same species of data —
            // measured kilograms — so they get the same ink. A naked hairline here
            // made History read as a second, thinner instrument.
            AreaMark(x: .value("Date", point.date), y: .value("Load", weightUnit.fromKg(point.avgKg)))
                .interpolationMethod(.monotone)
                .foregroundStyle(LinearGradient(
                    colors: [Accent.bleu.opacity(0.28), Accent.bleu.opacity(0.02)],
                    startPoint: .top, endPoint: .bottom))
            LineMark(x: .value("Date", point.date), y: .value("Load", weightUnit.fromKg(point.avgKg)))
                .interpolationMethod(.monotone)
                .foregroundStyle(Accent.bleu)
            PointMark(x: .value("Date", point.date), y: .value("Load", weightUnit.fromKg(point.avgKg)))
                .foregroundStyle(Accent.bleu)
                .symbolSize(28)
        }
        .chartXAxis {
            AxisMarks(values: .automatic(desiredCount: 4)) { _ in
                AxisGridLine().foregroundStyle(Ink.tertiary.opacity(0.2))
                AxisValueLabel(format: .dateTime.day().month(.abbreviated))
            }
        }
        .chartYAxisLabel(weightUnit.symbol)
        .chartYAxis {
            AxisMarks { _ in
                AxisGridLine().foregroundStyle(Ink.tertiary.opacity(0.2))
                AxisValueLabel()
            }
        }
        .frame(height: 170)
        .accessibilityElement(children: .ignore)
        .accessibilityLabel(trendSummary(series))
    }

    private func trendSummary(_ series: [TrendPoint]) -> String {
        guard let first = series.first, let last = series.last else { return "" }
        let delta = last.avgKg - first.avgKg
        let magnitude = weightUnit.number(abs(delta))
        // Under half a kilo across a whole series is inside the noise of how you happened
        // to grip it that morning, and calling that progress would be flattery.
        guard abs(delta) >= 0.5 else {
            return String(localized: "Holding steady around \(weightUnit.number(last.avgKg)) \(weightUnit.symbol).")
        }
        return delta > 0
            ? String(localized: "Up \(magnitude) \(weightUnit.symbol) across \(series.count) sessions.")
            : String(localized: "Down \(magnitude) \(weightUnit.symbol) across \(series.count) sessions.")
    }

    // MARK: - Derived

    /// A rep the LOAD chart may count: it finished, and a gauge was actually watching.
    /// The second half exists because gauge-free sessions log their reps at 0 kg —
    /// nothing was measured, which is the truth — and charting those zeros dragged a
    /// grip's line to the floor every time the Progressor stayed in the drawer. The
    /// month grid still counts those sessions in full; only the KILOGRAM chart ignores
    /// them, because they carry no kilograms.
    private func chartable(_ rep: RepSummary) -> Bool {
        rep.outcome == .completed && rep.avgKg > 0
    }

    /// The grouping key: the frozen `templateID` when the log has one, else the frozen
    /// name. ID first so a renamed routine keeps ONE line (both spellings share the ID);
    /// the name catches logs from before IDs were recorded. A deleted routine keeps its
    /// group either way — history answers for itself.
    private func routineKey(of log: WorkoutLog) -> String {
        log.templateID?.uuidString ?? log.templateName
    }

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

    private struct RoutineOption: Hashable {
        let key: String
        /// The routine's live name where it still exists, else the newest frozen one in
        /// the group — the closest thing to "what it's called now" that survives the
        /// routine being deleted.
        let name: String
    }

    /// Every routine with at least one chartable pull, most recently trained first —
    /// so the deck's FRONT card is the routine you are in the middle of caring about.
    private var routineOptions: [RoutineOption] {
        var names: [String: String] = [:]
        var order: [String] = []
        // `logs` is newest-first, so first sighting fixes both the order and the name.
        for log in logs where log.kind == .hang {
            let key = routineKey(of: log)
            guard names[key] == nil else { continue }
            guard reps(for: log).contains(where: chartable) else { continue }
            names[key] = displayName(of: log)
            order.append(key)
        }
        return order.map { RoutineOption(key: $0, name: names[$0] ?? "") }
    }

    /// Newest-first slices, matching `logs`. Built once beside the trend deck and then
    /// shared by every card, so total indexing cost stays linear in history size.
    private var routineLogIndex: [String: [WorkoutLog]] {
        var result: [String: [WorkoutLog]] = [:]
        for log in logs where log.kind == .hang {
            result[routineKey(of: log), default: []].append(log)
        }
        return result
    }

    private struct GripOption: Hashable {
        let key: String
        let grip: GripSpec
        let count: Int
    }

    /// Every grip in ONE routine with at least one chartable rep, most-trained first —
    /// so the chip you want is usually already the selected one.
    ///
    /// Memoised per routine in the current history generation: without it, every tap in
    /// the deck re-walked and re-decoded this routine's whole chartable history again.
    private func gripOptions(in logs: [WorkoutLog], routineKey: String) -> [GripOption] {
        let cacheKey = routineKey
        if let cached = trendCache.gripOptions[cacheKey] { return cached }
        var counts: [String: (grip: GripSpec, count: Int)] = [:]
        for log in logs {
            for rep in reps(for: log) where chartable(rep) {
                counts[rep.grip.key] = (rep.grip, (counts[rep.grip.key]?.count ?? 0) + 1)
            }
        }
        // The annotation is load-bearing: un-anchored, this map-plus-ternary-sort chain
        // sends the type checker past its time budget and the file stops compiling.
        let result: [GripOption] = counts
            .map { GripOption(key: $0.key, grip: $0.value.grip, count: $0.value.count) }
            // Count descending, then key ascending, so the order is stable rather than
            // shuffling between launches when two grips tie.
            .sorted { $0.count == $1.count ? $0.key < $1.key : $0.count > $1.count }
        trendCache.gripOptions[cacheKey] = result
        return result
    }

    /// Membership-checked per card, so a card whose routine never trained the shared
    /// selection falls back to its own most-trained grip — and the card that DID train
    /// it keeps your chip chosen as you swipe back.
    private func currentGrip(among options: [GripOption]) -> String? {
        if let selected = selectedGripKey, options.contains(where: { $0.key == selected }) {
            return selected
        }
        return options.first?.key
    }

    struct TrendPoint: Identifiable, Hashable {
        let id: UUID
        let date: Date
        let avgKg: Double
    }

    /// One point per session of one routine, oldest first, time-weighted within the
    /// session for the same reason `WorkoutLog.avgKg` is: a rep that dropped off after
    /// a second must not weigh as much as a full hang.
    ///
    /// Memoised per routine and grip in the current generation; the held/weighted sums
    /// fold in ONE pass per log rather than a `.filter` followed by two `.reduce`s over
    /// the filtered result — the same "walked a dozen times to draw one chart" cost the
    /// cache above exists to spare, now closed at both ends.
    private func trendSeries(in logs: [WorkoutLog], grip: String?, routineKey: String) -> [TrendPoint] {
        guard let grip else { return [] }
        let cacheKey = "\(routineKey)|\(grip)"
        if let cached = trendCache.series[cacheKey] { return cached }
        let result = logs.reversed().compactMap { log -> TrendPoint? in
            var held = 0.0
            var weighted = 0.0
            for rep in reps(for: log) where rep.grip.key == grip && chartable(rep) {
                held += rep.heldSeconds
                weighted += rep.avgKg * rep.heldSeconds
            }
            guard held > 0 else { return nil }
            return TrendPoint(id: log.id, date: log.startedAt, avgKg: weighted / held)
        }
        trendCache.series[cacheKey] = result
        return result
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
