// SPDX-License-Identifier: MPL-2.0
// Original contributions Copyright 2026 Nuri Bruner.

package run.nuri.getagrip.engine

import java.time.Instant

/// Every CRITICAL FORCE on file, addressed by grip and hand — what a target set as "% of
/// critical force" resolves against (`TargetBasis.criticalForce`, `docs/TARGET_BASIS.md`).
///
/// **EXACTLY `MaxTable`'s rules, by construction**: it is a `MaxTable` inside (peak slots
/// only), so the one implementation of the hand rule serves both —
/// - key `gripKey|side` (`MaxTable.key(grip, side)`, no length suffix);
/// - a `left`/`right` rep uses that hand's CF and falls back to the both-hands CF;
/// - a `both` rep resolves ONLY against a both-hands CF — never the hands summed;
/// - no CF on file means no target, never a guess; a one-handed CF never reaches the other
///   hand;
/// - zero, negative and non-finite values are dropped, so nothing divides by them.
///
/// TRANSLATION NOTE (from Shared/Engine/CriticalForceTable.swift): Swift's value-type
/// struct is a class here, like `MaxTable`; `copy()` is the value-semantics escape hatch.
class CriticalForceTable private constructor(private val table: MaxTable) {

    constructor() : this(MaxTable())

    /// Pre-keyed (`gripKey|side`), for callers that already folded.
    constructor(keyed: Map<String, Double>) : this(MaxTable(keyed))

    /// Zero, negative and non-finite are dropped.
    fun record(kg: Double, grip: String, side: Side) {
        table.record(kg, grip, side)
    }

    /// The CF a rep on `side` resolves against, with `MaxTable`'s hand fallback.
    fun criticalForce(grip: String, side: Side): Double? = table.max(grip, side)

    /// What is on file for exactly this hand, with NO fallback.
    fun exact(grip: String, side: Side): Double? = table.exact(grip, side)

    val isEmpty: Boolean get() = table.isEmpty

    fun copy(): CriticalForceTable = CriticalForceTable(table.copy())

    override fun equals(other: Any?): Boolean = other is CriticalForceTable && table == other.table
    override fun hashCode(): Int = table.hashCode()
    override fun toString(): String = "CriticalForceTable($table)"

    /// One test as the fold needs it — the store and the export both reduce their own
    /// records to this, so every surface folds the same way.
    data class Entry(
        val gripKey: String,
        val side: Side,
        /// `CriticalForceProtocol.key`. Only the standard protocol resolves targets.
        val protocolKey: String,
        val criticalForceKg: Double,
        val recordedAt: Instant,
    )

    companion object {
        /// The table from a set of tests: per `gripKey|side`, the NEWEST valid test.
        ///
        /// - **Standard protocol only** (`CriticalForceProtocol.standard.key`, `7:3x24`): CF
        ///   is only defined for a duty cycle, so a test on another protocol is a different
        ///   number and never prescribes a load.
        /// - **Valid first, then newest**: a test whose CF is zero, negative or non-finite
        ///   is skipped BEFORE choosing the newest, so it cannot mask an older good one.
        /// - `asOf` keeps only tests recorded at or before that instant (inclusive) — "the
        ///   CF as it stood" for the export. null keeps everything.
        /// - Ties on `recordedAt` for one key: the LATER entry in the given order wins.
        fun folding(entries: List<Entry>, asOf: Instant? = null): CriticalForceTable {
            val standard = CriticalForceProtocol.standard.key
            val newest = LinkedHashMap<String, Entry>()
            for (entry in entries) {
                if (entry.protocolKey != standard) continue
                if (!entry.criticalForceKg.isFinite() || entry.criticalForceKg <= 0) continue
                if (asOf != null && entry.recordedAt.isAfter(asOf)) continue
                val key = MaxTable.key(entry.gripKey, entry.side)
                val held = newest[key]
                if (held != null && held.recordedAt.isAfter(entry.recordedAt)) continue
                newest[key] = entry
            }
            val table = CriticalForceTable()
            for (entry in newest.values) table.record(entry.criticalForceKg, entry.gripKey, entry.side)
            return table
        }
    }
}
