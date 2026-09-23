// SPDX-License-Identifier: MPL-2.0
// Original contributions Copyright 2026 Nuri Bruner.

import Charts
import SwiftData
import SwiftUI

/// History's "Load per grip" block: one trend card per routine with measured pulls.
///
/// Its own view, for two reasons that each cost the whole screen:
///
/// - **The grip selection lives HERE.** Held by `HistoryView`, every chip tap re-ran the
///   whole screen (day ledger, routine index, odometer) to move one chip's fill.
/// - **The model is built off the main actor** (`TrendModel.build`) in a `.task` keyed on
///   the history's generation. The first open used to decode every rep blob on the main
///   thread before the tab could draw. A same-height placeholder stands in, and a stale
///   model keeps drawing while its replacement builds, so nothing jumps.
///
/// VALUE-COMPARED (`.equatable()` at the call site) on `generation`, the live routine
/// names and the layout — never on `logs`, which is only snapshotted when the generation
/// moves.
struct TrendDeck: View, Equatable {
    /// Bumped by `HistoryView` whenever its query hands back a different set of rows.
    let generation: Int
    /// Read only inside the snapshot task — see the type's note.
    let logs: [WorkoutLog]
    let routineNames: [UUID: String]
    /// The wide pane: every routine's card stacked, nothing peeking or paging.
    let wide: Bool

    nonisolated static func == (a: Self, b: Self) -> Bool {
        a.generation == b.generation && a.routineNames == b.routineNames && a.wide == b.wide
    }

    @Environment(\.weightUnit) private var weightUnit
    /// Which grip's trend is on screen; nil means the most-trained one. ONE selection shared
    /// across the deck, so a grip picked on one routine stays picked as you swipe — how you
    /// compare a grip across routines. Cards without it fall back to their own and remember
    /// nothing.
    @State private var selectedGripKey: String?
    @State private var model: TrendModel?

    private struct BuildKey: Equatable {
        let generation: Int
        let routineNames: [UUID: String]
    }

    var body: some View {
        Group {
            if let model {
                cards(model.routines)
            } else {
                placeholder.padding(.horizontal, Metrics.hPadding)
            }
        }
        .task(id: BuildKey(generation: generation, routineNames: routineNames)) {
            // Columns only on main: the blob is copied, never decoded, here.
            let rows = logs.compactMap { log -> TrendModel.Row? in
                guard log.kind == .hang else { return nil }
                return TrendModel.Row(id: log.id, templateID: log.templateID,
                                      templateName: log.templateName,
                                      startedAt: log.startedAt, resultsData: log.resultsData)
            }
            let names = routineNames
            let built = await Task.detached(priority: .userInitiated) {
                TrendModel.build(rows: rows, routineNames: names)
            }.value
            guard !Task.isCancelled else { return }
            model = built
        }
    }

    /// One card per routine with measured pulls; a deck only once a second routine has data
    /// — as on Today, a permanent sliver of "more" with one routine would be a lie.
    @ViewBuilder
    private func cards(_ routines: [TrendModel.Routine]) -> some View {
        if routines.count > 1, wide {
            // The wide pane is tall and scrolls: cards stack, nothing peeks or pages.
            VStack(spacing: 12) {
                ForEach(routines) { trendCard($0) }
            }
            .padding(.horizontal, Metrics.hPadding)
        } else if routines.count > 1 {
            deck(routines)
        } else if let only = routines.first {
            trendCard(only).padding(.horizontal, Metrics.hPadding)
        } else {
            emptyTrendCard.padding(.horizontal, Metrics.hPadding)
        }
    }

    /// Average load per session for ONE grip, WITHIN ONE ROUTINE — one CARD per routine,
    /// swiped like Today's deck (Nuri, 2026-08-10). Per routine because the same grip at two
    /// intensities is two training lines: a 12 kg repeater and a 30 kg max pull averaged
    /// into a zigzag that tracked which routine ran. Averaged, not peak: this is volume
    /// training, and the session mean is what the fingers absorbed.
    ///
    /// Three horizontal gestures share this screen (the deck, the grip chips, row
    /// swipe-to-delete), so the deck moves ONE card per swipe and the chip row hands the
    /// drag back whenever its chips fit (`.basedOnSize`).
    private func deck(_ routines: [TrendModel.Routine]) -> some View {
        ScrollView(.horizontal) {
            HStack(alignment: .top, spacing: 8) {
                ForEach(routines) { routine in
                    trendCard(routine)
                        .containerRelativeFrame(.horizontal)
                }
            }
            .scrollTargetLayout()
        }
        .scrollTargetBehavior(.viewAligned(limitBehavior: .always))
        .scrollIndicators(.hidden)
        // Today's deck geometry, verbatim: cards on the house grid, the neighbour
        // peeking 20 pt.
        .contentMargins(.leading, Metrics.hPadding, for: .scrollContent)
        .contentMargins(.trailing, Metrics.hPadding + 8, for: .scrollContent)
    }

    /// One routine's trend. `maxHeight: .infinity` makes every card stand the deck's full
    /// height; a half-mast card beside a full chart reads as a rendering fault.
    private func trendCard(_ routine: TrendModel.Routine) -> some View {
        let selected = routine.selectedGrip(selectedGripKey)
        let series = selected.flatMap { routine.series[$0] } ?? []
        return MaterialCard(surface: .flat) {
            VStack(alignment: .leading, spacing: 12) {
                CapsLabel(String(localized: "Load per grip"))
                // The page's identity, at card weight now that swiping reaches the others.
                Text(routine.name)
                    .font(.system(.title3, weight: .semibold))
                    .foregroundStyle(Ink.primary)
                    .lineLimit(1)
                    .minimumScaleFactor(0.8)

                gripPicker(routine.grips, selected: selected)

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

    /// The card's own anatomy, empty, while the first model builds — at a charted card's
    /// height, so nothing below moves when the real one arrives.
    private var placeholder: some View {
        MaterialCard(surface: .flat) {
            VStack(alignment: .leading, spacing: 12) {
                CapsLabel(String(localized: "Load per grip"))
                Text(verbatim: " ")
                    .font(.system(.title3, weight: .semibold))
                Chip(title: " ", isSelected: false) {}
                    .fixedSize()
                    .hidden()
                    .padding(.vertical, 2)
                ProgressView()
                    .frame(maxWidth: .infinity)
                    .frame(height: 170)
                Text(verbatim: " ")
                    .font(.system(.footnote))
            }
        }
        .accessibilityElement(children: .ignore)
        .accessibilityLabel(String(localized: "Load per grip"))
    }

    /// Sessions exist but none carried kilograms — every pull so far was gauge-free.
    private var emptyTrendCard: some View {
        MaterialCard(surface: .flat) {
            VStack(alignment: .leading, spacing: 12) {
                CapsLabel(String(localized: "Load per grip"))
                Text("No measured pulls to chart yet.")
                    .font(.system(.subheadline))
                    .foregroundStyle(Ink.secondary)
            }
        }
    }

    private func gripPicker(_ options: [TrendModel.GripOption], selected: String?) -> some View {
        ScrollView(.horizontal) {
            HStack(spacing: 8) {
                ForEach(options, id: \.key) { option in
                    Chip(title: option.grip.shortName,
                         isSelected: option.key == selected) {
                        selectedGripKey = option.key
                    }
                    // Chips fill their container by default: right in a grid, wrong in a scroller.
                    .fixedSize()
                    .accessibilityLabel(option.grip.spoken)
                }
            }
            .padding(.vertical, 2)
        }
        .scrollIndicators(.hidden)
        // MANDATORY in the deck: otherwise a chip row that FITS still claims every
        // horizontal drag on it — a dead stripe where paging stops.
        .scrollBounceBehavior(.basedOnSize)
        // Every other Chip selection ticks on the choice; this one must too.
        .sensoryFeedback(.selection, trigger: selectedGripKey)
    }

    private func chart(_ series: [TrendModel.Point]) -> some View {
        Chart(series) { point in
            // The runner's brush: bleu 0.28 → 0.02 under the curve (ForceTraceView),
            // since this is the same measured kilograms. A naked hairline read as a
            // second, thinner instrument.
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

    private func trendSummary(_ series: [TrendModel.Point]) -> String {
        guard let first = series.first, let last = series.last else { return "" }
        let delta = last.avgKg - first.avgKg
        let magnitude = weightUnit.number(abs(delta))
        // Under half a kilo across a series is grip noise; calling it progress
        // would be flattery.
        guard abs(delta) >= 0.5 else {
            return String(localized: "Holding steady around \(weightUnit.number(last.avgKg)) \(weightUnit.symbol).")
        }
        return delta > 0
            ? String(localized: "Up \(magnitude) \(weightUnit.symbol) across \(series.count) sessions.")
            : String(localized: "Down \(magnitude) \(weightUnit.symbol) across \(series.count) sessions.")
    }
}
