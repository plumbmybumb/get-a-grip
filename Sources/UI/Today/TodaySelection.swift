// SPDX-License-Identifier: MPL-2.0
// Original contributions Copyright 2026 Nuri Bruner.

import Foundation

/// Which routine Today fronts, and which one wears the up-next border — decided ONCE per
/// body evaluation from plain values.
///
/// These used to be computed properties on `TodayView`, each re-deriving the ones below
/// it: `ordered` re-sorted the query on every read (ten and more per render), `upNext`
/// was asked once per CARD and walked every routine's reminder blob each time, and the
/// header decoded the selected routine's summary a second time beside the card's own.
/// Cheap one at a time, and all of it on the frame that swiped the deck. The view now
/// folds the routines into these values once and hands them down; the rules themselves
/// live here, where a test can reach them.
enum TodaySelection {
    /// One routine as the selection rules see it.
    struct Candidate: Equatable {
        let id: UUID
        /// The routine's reminder times in minutes from midnight — EMPTY when reminders
        /// are off or the day is already done, because a routine you finished has been
        /// answered and cannot be "calling".
        let callingMinutes: [Int]
    }

    /// Rungs 2–4: the routine the app would front WITH NO HAND ON IT — the deck's home
    /// card, and the one wearing the up-next border.
    ///
    /// 2. the routine whose reminder CALLED most recently — fired at or before `now`,
    ///    the latest such time wins, a tie going to the earlier routine in `candidates`
    ///    (stable, and biased toward the primary). "Before" and "latest" are in
    ///    TRAINING-day order (`ReminderTime.trainingDayOrder`), the planner's order: at
    ///    00:30 the 23:00 reminder called ninety minutes ago, and the 08:00 one has
    ///    not called yet;
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
