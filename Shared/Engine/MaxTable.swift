// SPDX-License-Identifier: MPL-2.0
// Original contributions Copyright 2026 Nuri Bruner.

import Foundation

/// Every max you have on file, addressed by GRIP **and HAND**.
///
/// Hands are not equal (Nuri's right is ~10 % down on his left, 2026-08-04), so one max
/// for both prescribes too much for one hand and too little for the other. A max per
/// hand fixes that for free — 25 % of each is different kilograms — so **the routine
/// needs no per-hand controls at all.**
///
/// **Resolution: the specific beats the general.**
/// - a `.left` or `.right` rep uses that hand's max, falling back to the both-hands max,
///   so a single recorded max works as it always did;
/// - a `.both` rep uses ONLY a both-hands max. Summing the hands would be a silent,
///   doubled guess pointed at someone's fingers: *no max means no target, never a guess.*
///
/// **A max has a LENGTH too** (2026-10-01): the peak (`seconds` nil or 0, what every
/// caller meant before) or a timed max — the average you held over N seconds. They are
/// separate numbers in separate slots, and a timed lookup NEVER falls back to the peak:
/// 90 % of a 10 s max and 90 % of a peak are very different loads, and silently swapping
/// one for the other is a guess pointed at someone's fingers.
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
    /// A timed max appends `|10s`, so a PEAK key is byte-identical to every key written
    /// before timed maxes existed.
    static func key(grip: String, side: Side, seconds: Int? = nil) -> String {
        guard let seconds, seconds > 0 else { return "\(grip)|\(side.rawValue)" }
        return "\(grip)|\(side.rawValue)|\(seconds)s"
    }

    /// Zero and negative are dropped, so `PlanMath` never divides by them.
    mutating func record(_ kg: Double, grip: String, side: Side, seconds: Int? = nil) {
        guard kg.isFinite, kg > 0 else { return }
        byKey[Self.key(grip: grip, side: side, seconds: seconds)] = kg
    }

    /// The max to use for a rep on `side`. See the type's note for the fallback rule —
    /// the hand falls back to both hands, the LENGTH never falls back to the peak.
    func max(grip: String, side: Side, seconds: Int? = nil) -> Double? {
        if let own = byKey[Self.key(grip: grip, side: side, seconds: seconds)] { return own }
        guard side != .both else { return nil }
        return byKey[Self.key(grip: grip, side: .both, seconds: seconds)]
    }

    /// What is on file for exactly this hand, with NO fallback. The builder and
    /// max-change receipts must distinguish an actual hand record from a shared
    /// fallback before explaining targets or offering a proportional rescale.
    func exact(grip: String, side: Side, seconds: Int? = nil) -> Double? {
        byKey[Self.key(grip: grip, side: side, seconds: seconds)]
    }

    /// Every timed length on file for a grip, shortest first — what the builder offers
    /// beside "Peak". Derived from what you have measured, never a fixed menu.
    func timedLengths(grip: String) -> [Int] {
        let prefix = grip + "|"
        let lengths = byKey.keys.compactMap { key -> Int? in
            guard key.hasPrefix(prefix), key.hasSuffix("s"),
                  let last = key.split(separator: "|").last else { return nil }
            return Int(last.dropLast())
        }
        return Set(lengths).sorted()
    }

    /// Every timed length on file for ANY grip, shortest first.
    var timedLengths: [Int] {
        let lengths = byKey.keys.compactMap { key -> Int? in
            // Only a timed key ends in "<n>s"; a peak key ends in the hand's raw name.
            guard let last = key.split(separator: "|").last, last.hasSuffix("s") else { return nil }
            return Int(last.dropLast())
        }
        return Set(lengths).sorted()
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
