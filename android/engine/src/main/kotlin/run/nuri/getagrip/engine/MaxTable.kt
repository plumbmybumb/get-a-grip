// SPDX-License-Identifier: MPL-2.0
// Original contributions Copyright 2026 Nuri Bruner.

package run.nuri.getagrip.engine

/// Every max you have on file, addressed by GRIP **and HAND**.
///
/// Hands are not equal — Nuri's right is about 10 % down on his left (2026-08-04) — and
/// because every target load in this app is a percentage of a max, one number for both
/// hands prescribes a load that is too heavy for one of them and too light for the
/// other. Recording a max per hand fixes that for free: 25 % of the left max and 25 % of
/// the right max are simply different kilograms, so **the routine needs no per-hand
/// controls at all.** It says "25 %" once and each hand gets its own weight.
///
/// **Resolution: the specific beats the general.**
/// - a `left` or `right` rep uses that hand's max, and falls back to the both-hands
///   max when that hand has none — so a single recorded max still works exactly as it
///   did before anyone had heard of this type;
/// - a `both` rep uses ONLY a both-hands max.
///
/// That last rule is a refusal to guess, and it is deliberate. It would be easy to
/// synthesise a two-handed max by adding the two hands together, and the error would be
/// silent, doubled, and pointed at someone's fingers. The codebase already has the rule
/// this follows — *no max means no target, never a guess* — and the cost of obeying it
/// is one extra record for the rare person who trains two-handed but measured one hand
/// at a time.
///
/// TRANSLATION NOTE (from Shared/Engine/MaxTable.swift): a Swift `struct` with a
/// `mutating record`, driven by callers that hold it as a value. Kotlin has no value
/// semantics, so this is a class with `copy()` for the places a test (or a caller) means
/// "the same table plus one more record" — `var table2 = table1` in Swift is
/// `val table2 = table1.copy()` here, and forgetting the copy is the one behaviour
/// change the translation can produce.
class MaxTable {

    /// Flat, keyed by `key(grip, side)`. A dictionary of dictionaries would make the
    /// fallback read as two lookups nested in an optional dance; this way it is two
    /// lookups in a row.
    private val byKey: MutableMap<String, Double>

    constructor() {
        byKey = mutableMapOf()
    }

    /// Pre-keyed, for the store — which builds this straight out of its record fold.
    constructor(keyed: Map<String, Double>) {
        byKey = keyed.filterValues { it.isFinite() && it > 0 }.toMutableMap()
    }

    /// Zero and negative are dropped rather than stored: `PlanMath` would have to defend
    /// against dividing by them at every call site otherwise.
    fun record(kg: Double, grip: String, side: Side) {
        if (!kg.isFinite() || kg <= 0) return
        byKey[key(grip, side)] = kg
    }

    /// The max to use for a rep on `side`. See the type's note for the fallback rule.
    fun max(grip: String, side: Side): Double? {
        byKey[key(grip, side)]?.let { return it }
        if (side == Side.both) return null
        return byKey[key(grip, Side.both)]
    }

    /// What is on file for exactly this hand, with NO fallback. The builder and
    /// max-change receipts must distinguish an actual hand record from a shared
    /// fallback before explaining targets or offering a proportional rescale.
    fun exact(grip: String, side: Side): Double? = byKey[key(grip, side)]

    /// True when a grip resolves to DIFFERENT loads for the two hands — the one question
    /// every per-hand display asks before deciding whether to draw one figure or two.
    fun differsByHand(grip: String): Boolean {
        val left = max(grip, Side.left)
        val right = max(grip, Side.right)
        if (left == null || right == null) return left != null || right != null
        return left != right
    }

    val isEmpty: Boolean get() = byKey.isEmpty()

    /// The value-semantics escape hatch — see the translation note on the type.
    fun copy(): MaxTable = MaxTable(byKey)

    override fun equals(other: Any?): Boolean = other is MaxTable && byKey == other.byKey
    override fun hashCode(): Int = byKey.hashCode()
    override fun toString(): String = "MaxTable($byKey)"

    companion object {
        /// The wire format for a (grip, hand) pair. `Side.rawValue`, never a localized name.
        fun key(grip: String, side: Side): String = "$grip|${side.rawValue}"
    }
}
