// SPDX-License-Identifier: MPL-2.0
// Original contributions Copyright 2026 Nuri Bruner.

import Foundation

/// Everything History's "Load per grip" deck draws, built OFF the main actor from plain
/// values.
///
/// It used to be built lazily inside body: the first open of History each launch decoded
/// every session's rep blob on the main thread to find which routines had chartable pulls,
/// then again per card for its grips and series — a freeze that grew with every week of
/// training, on the tap that opened the tab. `TrendDeck` now copies each hang session's
/// columns and its still-encoded `resultsData` into `Row`s on main (no decoding), and
/// `build` does the rest detached. The rules are unchanged; they are simply here, pure,
/// where a test can reach them.
struct TrendModel: Equatable, Sendable {
    /// One hang session, as copied off its model on the main actor.
    struct Row: Sendable {
        let id: UUID
        let templateID: UUID?
        let templateName: String
        let startedAt: Date
        /// Still encoded — decoding is the expensive part and happens in `build`.
        let resultsData: Data
    }

    struct GripOption: Hashable, Sendable {
        let key: String
        let grip: GripSpec
        let count: Int
    }

    struct Point: Identifiable, Hashable, Sendable {
        let id: UUID
        let date: Date
        let avgKg: Double
    }

    /// One card: one routine's grips, most-trained first, and each grip's series.
    struct Routine: Identifiable, Equatable, Sendable {
        var id: String { key }
        let key: String
        /// The routine's live name where it still exists, else the newest frozen one in
        /// the group — the closest thing to "what it's called now" that survives the
        /// routine being deleted.
        let name: String
        let grips: [GripOption]
        /// Oldest first, keyed by grip key.
        let series: [String: [Point]]

        /// A shared selection when this routine trained it, else its own most-trained
        /// grip — so the card that DID train the chosen grip keeps it as you swipe back.
        func selectedGrip(_ shared: String?) -> String? {
            if let shared, grips.contains(where: { $0.key == shared }) { return shared }
            return grips.first?.key
        }
    }

    /// Most recently trained first — the deck's FRONT card is the routine you are in
    /// the middle of caring about.
    let routines: [Routine]

    /// A rep the LOAD chart may count: it finished, and a gauge was actually watching.
    /// Gauge-free sessions log their reps at 0 kg — nothing was measured, which is the
    /// truth — and charting those zeros dragged a grip's line to the floor every time
    /// the Progressor stayed in the drawer. The month grid still counts those sessions
    /// in full; only the KILOGRAM chart ignores them, because they carry no kilograms.
    static func chartable(_ rep: RepSummary) -> Bool {
        rep.outcome == .completed && rep.avgKg > 0
    }

    /// The grouping key: the frozen `templateID` when the session has one, else the
    /// frozen name. ID first so a renamed routine keeps ONE line (both spellings share
    /// the ID); the name catches sessions from before IDs were recorded.
    static func routineKey(templateID: UUID?, templateName: String) -> String {
        templateID?.uuidString ?? templateName
    }

    /// `rows` newest first, as History's query sorts them; hang sessions only.
    /// `routineNames` is the store's live-name map: a live routine is named by it, a
    /// session whose routine no longer exists gets no card at all (Nuri, 2026-09-20:
    /// "Daily no-hangs" was long deleted and still had one). Deleting a routine now takes
    /// its sessions with it, so that only catches rows written before the rule and rows a
    /// CloudKit import delivered ahead of their routine; both still list as sessions.
    static func build(rows: [Row], routineNames: [UUID: String]) -> TrendModel {
        struct Group {
            var name: String?
            var gripCounts: [String: (grip: GripSpec, count: Int)] = [:]
            /// Newest first while folding; reversed at the end.
            var series: [String: [Point]] = [:]
        }
        var groups: [String: Group] = [:]
        var order: [String] = []

        for row in rows {
            if Task.isCancelled { return TrendModel(routines: []) }
            let key = routineKey(templateID: row.templateID, templateName: row.templateName)
            var group = groups[key] ?? Group()
            // One pass per session: the grip counts, and each grip's time-weighted mean
            // for this session — a rep that dropped off after a second must not weigh
            // as much as a full hang, the same rule as `WorkoutLog.avgKg`.
            var held: [String: Double] = [:]
            var weighted: [String: Double] = [:]
            var chartedAny = false
            for rep in BlobCodec.decodeArray(RepSummary.self, from: row.resultsData)
            where chartable(rep) {
                chartedAny = true
                let grip = rep.grip.key
                group.gripCounts[grip] = (rep.grip, (group.gripCounts[grip]?.count ?? 0) + 1)
                held[grip, default: 0] += rep.heldSeconds
                weighted[grip, default: 0] += rep.avgKg * rep.heldSeconds
            }
            for (grip, seconds) in held where seconds > 0 {
                group.series[grip, default: []].append(
                    Point(id: row.id, date: row.startedAt, avgKg: weighted[grip, default: 0] / seconds))
            }
            // A card exists from the newest session that charted anything, and is
            // named from that session; a routine deleted from the store gets none.
            let live = row.templateID.map { routineNames[$0] != nil } ?? true
            if chartedAny, live, group.name == nil {
                group.name = WorkoutLog.displayName(templateID: row.templateID,
                                                    templateName: row.templateName,
                                                    in: routineNames)
                order.append(key)
            }
            groups[key] = group
        }

        let routines = order.compactMap { key -> Routine? in
            guard let group = groups[key], let name = group.name else { return nil }
            // The annotation is load-bearing: un-anchored, this map-plus-ternary-sort
            // chain sends the type checker past its time budget.
            let grips: [GripOption] = group.gripCounts
                .map { GripOption(key: $0.key, grip: $0.value.grip, count: $0.value.count) }
                // Count descending, then key ascending, so two grips that tie do not
                // shuffle between launches.
                .sorted { $0.count == $1.count ? $0.key < $1.key : $0.count > $1.count }
            return Routine(key: key, name: name, grips: grips,
                           series: group.series.mapValues { Array($0.reversed()) })
        }
        return TrendModel(routines: routines)
    }
}
