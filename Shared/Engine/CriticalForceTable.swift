// SPDX-License-Identifier: MPL-2.0
// Original contributions Copyright 2026 Nuri Bruner.

import Foundation

/// Every CRITICAL FORCE on file, addressed by grip and hand — what a target set as "% of
/// critical force" resolves against (`TargetBasis.criticalForce`, `docs/TARGET_BASIS.md`).
///
/// **EXACTLY `MaxTable`'s rules, by construction**: it is a `MaxTable` inside (peak slots
/// only), so the one implementation of the hand rule serves both —
/// - key `gripKey|side` (`MaxTable.key(grip:side:)`, no length suffix);
/// - a `.left`/`.right` rep uses that hand's CF and falls back to the both-hands CF;
/// - a `.both` rep resolves ONLY against a both-hands CF — never the hands summed;
/// - no CF on file means no target, never a guess; a one-handed CF never reaches the other
///   hand;
/// - zero, negative and non-finite values are dropped, so nothing divides by them.
///
/// A wrapper rather than a generic over `MaxTable`: the max table also carries timed
/// slots (`|10s`), and a CF has no length. Wrapping keeps the fallback in ONE place and
/// makes "a CF has no length" a fact of the type rather than a rule a caller remembers.
struct CriticalForceTable: Hashable, Sendable {
    private var table = MaxTable()

    init() {}

    /// Pre-keyed (`gripKey|side`), for callers that already folded.
    init(keyed: [String: Double]) {
        table = MaxTable(keyed: keyed)
    }

    /// Zero, negative and non-finite are dropped.
    mutating func record(_ kg: Double, grip: String, side: Side) {
        table.record(kg, grip: grip, side: side)
    }

    /// The CF a rep on `side` resolves against, with `MaxTable`'s hand fallback.
    func criticalForce(grip: String, side: Side) -> Double? {
        table.max(grip: grip, side: side)
    }

    /// What is on file for exactly this hand, with NO fallback.
    func exact(grip: String, side: Side) -> Double? {
        table.exact(grip: grip, side: side)
    }

    var isEmpty: Bool { table.isEmpty }

    // MARK: - The fold

    /// One test as the fold needs it — the store, the watch and the export all reduce
    /// their own records to this, so every surface folds the same way.
    struct Entry: Hashable, Sendable {
        var gripKey: String
        var side: Side
        /// `CriticalForceProtocol.key`. Only the standard protocol resolves targets.
        var protocolKey: String
        var criticalForceKg: Double
        var recordedAt: Date
    }

    /// The table from a set of tests: per `gripKey|side`, the NEWEST valid test.
    ///
    /// - **Standard protocol only** (`CriticalForceProtocol.standard.key`, `7:3x24`): CF
    ///   is only defined for a duty cycle, so a test on another protocol is a different
    ///   number and never prescribes a load.
    /// - **Valid first, then newest**: a test whose CF is zero, negative or non-finite is
    ///   skipped BEFORE choosing the newest, so it cannot mask an older good one.
    /// - `asOf` keeps only tests recorded at or before that instant (inclusive) — "the CF
    ///   as it stood" for the export. nil keeps everything.
    /// - Ties on `recordedAt` for one key: the LATER entry in the given order wins.
    static func folding(_ entries: [Entry], asOf: Date? = nil) -> CriticalForceTable {
        let standard = CriticalForceProtocol.standard.key
        var newest: [String: Entry] = [:]
        for entry in entries {
            guard entry.protocolKey == standard,
                  entry.criticalForceKg.isFinite, entry.criticalForceKg > 0 else { continue }
            if let asOf, entry.recordedAt > asOf { continue }
            let key = MaxTable.key(grip: entry.gripKey, side: entry.side)
            if let held = newest[key], held.recordedAt > entry.recordedAt { continue }
            newest[key] = entry
        }
        var table = CriticalForceTable()
        for entry in newest.values {
            table.record(entry.criticalForceKg, grip: entry.gripKey, side: entry.side)
        }
        return table
    }
}
