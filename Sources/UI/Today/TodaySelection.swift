// SPDX-License-Identifier: MPL-2.0
// Original contributions Copyright 2026 Nuri Bruner.

import Foundation

/// Which routine Today fronts, and which one wears the up-next border — decided ONCE per
/// body evaluation from plain values.
///
/// As computed properties on `TodayView` they re-derived each other: `ordered` re-sorted
/// per read (ten-plus per render), `upNext` walked every reminder blob per CARD, and the
/// header decoded a summary twice — all on the frame that swiped the deck. Now folded
/// once and handed down, with the rules here where a test can reach them.
enum TodaySelection {
    /// One routine as the selection rules see it.
    struct Candidate: Equatable {
        let id: UUID
        /// Reminder times in minutes from midnight — EMPTY when reminders are off or the day is
        /// done, because a finished routine cannot be "calling".
        let callingMinutes: [Int]
    }

    /// Rungs 2–4: the routine the app would front WITH NO HAND ON IT — the deck's home card,
    /// wearing the up-next border.
    ///
    /// 2. the routine whose reminder CALLED most recently (at or before `now`; latest wins,
    ///    ties to the earlier candidate, biased to the primary). Ordered by TRAINING day
    ///    (`ReminderTime.trainingDayOrder`): at 00:30 the 23:00 reminder called ninety
    ///    minutes ago and 08:00 has not;
    /// 3. `suggestedID`, the routine started today on this device, while it exists;
    /// 4. the first — the primary.
    static func upNextID(_ candidates: [Candidate], suggestedID: UUID?, nowMinutes: Int) -> UUID? {
        let now = ReminderTime.trainingDayOrder(nowMinutes)
        var best: (id: UUID, firedAt: Int)?
        for candidate in candidates {
            guard let fired = candidate.callingMinutes
                .map({ ReminderTime.trainingDayOrder($0) })
                .filter({ $0 <= now })
                .max()
            else { continue }
            // Strictly greater: the tie stays with the earlier routine.
            if best == nil || fired > best!.firedAt { best = (candidate.id, fired) }
        }
        if let best { return best.id }
        if let suggestedID, candidates.contains(where: { $0.id == suggestedID }) {
            return suggestedID
        }
        return candidates.first?.id
    }

    /// Rung 1 over the rest: an explicit choice made TODAY wins while the routine still
    /// exists — a CloudKit merge that deletes it simply falls through to `upNext`.
    static func selectedID(chosenID: UUID?, chosenToday: Bool,
                           among ids: [UUID], upNextID: UUID?) -> UUID? {
        if chosenToday, let chosenID, ids.contains(chosenID) { return chosenID }
        return upNextID
    }

    /// Minutes since midnight on the device clock — what reminder times are written in.
    /// `upNextID` does the training-day conversion, so both sides convert the same way.
    static func minutesNow(_ date: Date = Date(), calendar: Calendar = .current) -> Int {
        let comps = calendar.dateComponents([.hour, .minute], from: date)
        return (comps.hour ?? 0) * 60 + (comps.minute ?? 0)
    }
}
