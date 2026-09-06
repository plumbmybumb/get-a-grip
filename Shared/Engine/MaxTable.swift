// SPDX-License-Identifier: MPL-2.0
// Original contributions Copyright 2026 Nuri Bruner.

import Foundation

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
/// - a `.left` or `.right` rep uses that hand's max, and falls back to the both-hands
///   max when that hand has none — so a single recorded max still works exactly as it
///   did before anyone had heard of this type;
/// - a `.both` rep uses ONLY a both-hands max.
///
/// That last rule is a refusal to guess, and it is deliberate. It would be easy to
/// synthesise a two-handed max by adding the two hands together, and the error would be
/// silent, doubled, and pointed at someone's fingers. The codebase already has the rule
/// this follows — *no max means no target, never a guess* — and the cost of obeying it
/// is one extra record for the rare person who trains two-handed but measured one hand
/// at a time.
struct MaxTable: Hashable, Sendable {
    /// Flat, keyed by `key(grip:side:)`. A dictionary of dictionaries would make the
    /// fallback read as two lookups nested in an optional dance; this way it is two
    /// lookups in a row.
    private var byKey: [String: Double] = [:]

    init() {}

    /// Pre-keyed, for the store — which builds this straight out of its record fold.
    init(keyed: [String: Double]) {
        byKey = keyed.filter { $0.value.isFinite && $0.value > 0 }
    }

    /// The wire format for a (grip, hand) pair. `Side.rawValue`, never a localized name.
    static func key(grip: String, side: Side) -> String {
        "\(grip)|\(side.rawValue)"
    }

    /// Zero and negative are dropped rather than stored: `PlanMath` would have to defend
    /// against dividing by them at every call site otherwise.
    mutating func record(_ kg: Double, grip: String, side: Side) {
        guard kg.isFinite, kg > 0 else { return }
        byKey[Self.key(grip: grip, side: side)] = kg
    }

    /// The max to use for a rep on `side`. See the type's note for the fallback rule.
    func max(grip: String, side: Side) -> Double? {
        if let own = byKey[Self.key(grip: grip, side: side)] { return own }
        guard side != .both else { return nil }
        return byKey[Self.key(grip: grip, side: .both)]
    }

    /// What is on file for exactly this hand, with NO fallback. The builder and
    /// max-change receipts must distinguish an actual hand record from a shared
    /// fallback before explaining targets or offering a proportional rescale.
    func exact(grip: String, side: Side) -> Double? {
        byKey[Self.key(grip: grip, side: side)]
    }

    /// True when a grip resolves to DIFFERENT loads for the two hands — the one question
    /// every per-hand display asks before deciding whether to draw one figure or two.
    func differsByHand(grip: String) -> Bool {
        let left = max(grip: grip, side: .left)
        let right = max(grip: grip, side: .right)
        guard let left, let right else { return left != nil || right != nil }
        return left != right
    }

    var isEmpty: Bool { byKey.isEmpty }
}
