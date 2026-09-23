// SPDX-License-Identifier: MPL-2.0
// Original contributions Copyright 2026 Nuri Bruner.

import SwiftUI

// MARK: - What the builder's rows and sections compare themselves on
//
// The builder is one `@State` draft, and every edit (a keystroke, a dial detent, a
// band-drag frame) re-evaluates the document body — a dozen cheap calls. What was not
// fine was every child taking the WHOLE draft or plan as a binding, so one keystroke
// re-ran all six set rows and three sections: 15 bodies per keystroke and 25 per drag
// frame (`_printChanges()`, pinned sim, 2026-09-18) — the stutter a tester saw on an
// iPhone 13 mini when an animation coincided with an edit.
//
// So each child takes ONE write path and VALUES for everything it draws, and compares on
// the values alone (`Equatable`, wrapped in `.equatable()`). These are the values, cut
// so high-rate edits cannot reach a child that does not show them, and cut by
// STRIPPING — a new plan-level field is compared by default; only named ones opt out.

extension SessionPlan {
    /// Everything the routine says about itself rather than one set: the plan with `sets` and
    /// `name` stripped, so a name keystroke or a drag in another row leaves children alone.
    var routineLevel: SessionPlan {
        var plan = self
        plan.sets = []
        plan.name = ""
        return plan
    }

    // MARK: Comparison keys
    //
    // Each child compares on the plan-level fields it READS. Comparing the whole
    // `routineLevel` re-ran all six rows and the fine-tuning card per set-break
    // detent (2026-09-18). Listed, not stripped, so the contract is visible: a
    // child that starts reading a field missing from its key draws it stale — add
    // the field the day the child reads it.

    /// What a set row draws from the routine: the inherited hold, rest and lead-in
    /// (`PlanMath.setSeconds`, the tension line), the hand mode (rep counts, the
    /// per-hand target) and the routine's percentage band.
    var setRowKey: SetRowKey {
        SetRowKey(holdSeconds: holdSeconds, restSeconds: restSeconds,
                  leadInSeconds: leadInSeconds, handMode: handMode,
                  targetLoPercent: targetLoPercent, targetHiPercent: targetHiPercent)
    }

    /// What the REST & HANDS card draws.
    var rhythmKey: RhythmKey {
        RhythmKey(setBreakSeconds: setBreakSeconds,
                  waitForReleaseBeforeRest: waitForReleaseBeforeRest,
                  handMode: handMode, startingHand: startingHand)
    }

    /// What the fine-tuning card draws — the one card that shows the pull threshold.
    var fineTuningKey: FineTuningKey {
        FineTuningKey(thresholdKg: thresholdKg,
                      pausesOutsideTargetBand: pausesOutsideTargetBand,
                      leadInSeconds: leadInSeconds)
    }
}

struct SetRowKey: Equatable {
    let holdSeconds: Int
    let restSeconds: Int
    let leadInSeconds: Int
    let handMode: HandMode
    let targetLoPercent: Double?
    let targetHiPercent: Double?
}

struct RhythmKey: Equatable {
    let setBreakSeconds: Int
    let waitForReleaseBeforeRest: Bool
    let handMode: HandMode
    let startingHand: Side
}

struct FineTuningKey: Equatable {
    let thresholdKg: Double
    let pausesOutsideTargetBand: Bool
    let leadInSeconds: Int
}

extension RoutineDraft {
    /// The draft with its plan blanked — the every-day section's whole input, so an edit
    /// inside a set never re-runs its date pickers.
    var schedule: RoutineDraft {
        var draft = self
        draft.plan = SessionPlan()
        return draft
    }
}

// MARK: - The write path

/// The builder's write path into the draft, handed to a child as plain CLOSURES, with
/// bindings whose reads never touch the document's state.
///
/// Two SwiftUI facts decide the shape, both measured 2026-09-18:
///
/// - A `Binding` (`@Binding` or stored) is a dynamic property, and a body whose dynamic
///   property changed re-runs whatever `==` says: with `@Binding var plan`, a name
///   keystroke still re-ran all six rows ("_plan changed").
/// - Reading `@State` inside a body — a CHILD's, through a binding's getter — makes it
///   depend on the whole state ("_value changed" per keystroke).
///
/// So a child writes through closures, and each binding it hands a control reads a value
/// the child already holds (`current:`). `WriteThroughValue` keeps two writes in one
/// gesture consistent (the trimmer clears the other unit after setting its own) and lets
/// a UIKit-backed control read back its write in the same frame; the next body reseeds.
@MainActor
final class WriteThroughValue<Value> {
    var value: Value
    init(_ value: Value) { self.value = value }
}

@MainActor
struct DraftAccess {
    let mutate: ((inout RoutineDraft) -> Void) -> Void

    /// A binding for one field: reads `current` (and what it has written since), writes
    /// through `mutate`. Built on demand inside the child's body.
    func binding<Value>(_ keyPath: WritableKeyPath<RoutineDraft, Value>,
                        current: Value) -> Binding<Value> {
        let cache = WriteThroughValue(current)
        return Binding(get: { cache.value },
                       set: { new in
                           cache.value = new
                           mutate { $0[keyPath: keyPath] = new }
                       })
    }
}

/// The same for one set, addressed by ID so an in-flight edit lands on its own set during
/// a reorder. One cache per access, so every binding the row makes shares one view.
@MainActor
struct SetAccess {
    private let cache: WriteThroughValue<SetPlan>
    let write: (SetPlan) -> Void

    init(current: SetPlan, write: @escaping (SetPlan) -> Void) {
        cache = WriteThroughValue(current)
        self.write = write
    }

    var binding: Binding<SetPlan> {
        Binding(get: { cache.value },
                set: { new in
                    cache.value = new
                    write(new)
                })
    }
}
