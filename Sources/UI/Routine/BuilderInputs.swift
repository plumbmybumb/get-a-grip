// SPDX-License-Identifier: MPL-2.0
// Original contributions Copyright 2026 Nuri Bruner.

import SwiftUI

// MARK: - What the builder's rows and sections compare themselves on
//
// The builder is one `@State` draft, and every edit — a keystroke in the name, a dial
// detent, each frame of a band drag — re-evaluates the document body. That is fine: it
// is a dozen function calls. What was not fine was every child taking the WHOLE draft
// or plan as a binding, so the same keystroke re-ran all six set rows, the three
// sections and everything inside them: 15 bodies per keystroke and 25 per frame of a
// band drag, counted with `_printChanges()` on the pinned sim (2026-09-18). On a
// tester's iPhone 13 mini that was the stutter they reported whenever an animation —
// a row expanding, the keyboard rising — coincided with an edit.
//
// So each child now takes ONE binding, purely to write through, and VALUES for
// everything it draws, and compares itself on the values alone (`Equatable`, wrapped in
// `.equatable()` by the document). These are the values. They are cut so that the
// high-rate edits cannot reach a child that does not show them, and cut by STRIPPING —
// a new plan-level field is compared by default; only the named ones are opted out,
// each for a reason.

extension SessionPlan {
    /// Everything the routine says about itself rather than about one set: the plan
    /// with `sets` and `name` stripped. Rows and sections draw their inherited numbers
    /// from this, so a keystroke in the name and a drag inside another row leave them
    /// alone.
    var routineLevel: SessionPlan {
        var plan = self
        plan.sets = []
        plan.name = ""
        return plan
    }

    // MARK: Comparison keys
    //
    // Each child compares itself on the plan-level fields it READS, listed per child.
    // Comparing on the whole `routineLevel` was measured first (2026-09-18): one detent
    // of the set-break dial re-ran all six rows and the fine-tuning card, none of which
    // show the break. Listed, not stripped, so the contract is visible: a child that
    // starts reading a field its key is missing draws it stale until another key field
    // moves — add the field to the key the day the child starts reading it.

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
    /// The draft with its plan blanked: sessions a day, reminders and the on-demand
    /// switch — the every-day section's whole input, so an edit inside a set never
    /// re-runs its date pickers.
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
/// Two facts about SwiftUI decide the shape, both measured 2026-09-18:
///
/// - A `Binding` — `@Binding` or a plain stored one — is a dynamic property, and a body
///   whose dynamic property changed value is re-run whatever the view's `==` says. With
///   `@Binding var plan` on the row, a keystroke in the name still re-ran all six rows,
///   `.equatable()` and all (`_printChanges`: "_plan changed" in each).
/// - Reading `@State` from inside a body — a CHILD's body, through any binding's
///   getter — makes that body depend on the whole state. A dial whose binding read the
///   draft re-ran on every keystroke in the name ("_value changed").
///
/// So a child gets closures to write through, and every binding it hands a control
/// reads a value the child already holds (`current:`, seeded from its own inputs) and
/// writes through the closure. `WriteThroughValue` keeps two writes in one gesture
/// consistent with each other — the band trimmer clears the other unit after setting
/// its own — and lets a UIKit-backed control read back what it just wrote in the same
/// frame. The next body run reseeds it from the new value.
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

/// The same for one set, addressed by ID so an edit in flight while the list reorders
/// lands on the set it came from. One cache per access — the row keeps the access it
/// was built with until its set changes, so every binding it makes shares one view of
/// the set.
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
