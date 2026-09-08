// SPDX-License-Identifier: MPL-2.0
// Original contributions Copyright 2026 Nuri Bruner.

/// A completed pull offered as a working max for the grip and hand that produced it.
/// Choosing one remains optional; computing candidates never writes a max.
struct SessionMaxCandidate: Identifiable, Hashable, Sendable {
    let grip: GripSpec
    let side: Side
    let kg: Double
    /// The record this candidate would replace, excluding target-load fallbacks.
    let previous: Double?

    var id: String { MaxTable.key(grip: grip.key, side: side) }

    static func from(reps: [RepSummary], maxes: MaxTable) -> [SessionMaxCandidate] {
        var best: [String: SessionMaxCandidate] = [:]
        for rep in reps where rep.outcome == .completed && rep.peakKg.isFinite && rep.peakKg > 1 {
            let key = MaxTable.key(grip: rep.grip.key, side: rep.side)
            if let held = best[key], held.kg >= rep.peakKg { continue }
            best[key] = SessionMaxCandidate(
                grip: rep.grip, side: rep.side, kg: rep.peakKg,
                // A shared max can supply a target, but it is not a measured record
                // for this hand and must not hide its first max or claim to be replaced.
                previous: maxes.exact(grip: rep.grip.key, side: rep.side))
        }
        return best.values
            .filter { candidate in candidate.previous.map { candidate.kg > $0 } ?? true }
            .sorted { $0.kg == $1.kg ? $0.id < $1.id : $0.kg > $1.kg }
    }
}
