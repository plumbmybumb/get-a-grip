// SPDX-License-Identifier: MPL-2.0
// Original contributions Copyright 2026 Nuri Bruner.

package run.nuri.getagrip.engine

/// A published protocol offered as a STARTING POINT from "New routine" (Nuri, 2026-09-25).
///
/// This reverses the 2026-08-10/-19 "nothing is offered" rule on purpose, and in the
/// narrowest form that works: the chooser opens with "Build from scratch", a protocol is
/// previewed in full before it is yours, and once added it is an ordinary routine you edit
/// like any other. It is NOT a library — nothing here is referenced after the add, so
/// changing a protocol below never rewrites a routine somebody already owns.
///
/// Each one transcribes the source's numbers as Nuri captured them from Frez. The C4
/// sources also name a contraction (active curl / passive pull); a grip cannot carry that
/// and the app does not show it (Nuri's call), so it is not recorded anywhere.
///
/// TRANSLATION NOTE (from Shared/Engine/RoutineProtocol.swift): the same four, the same
/// numbers; `RoutineProtocolTests` pins both engines to them.
enum class RoutineProtocol(val rawValue: String) {
    dailyNoHangs("dailyNoHangs"),
    c4WarmUp("c4WarmUp"),
    c4Max("c4Max"),
    fingerRehab("fingerRehab");

    val id: String get() = rawValue

    val title: String
        get() = when (this) {
            dailyNoHangs -> L10n.tr("Daily no-hangs")
            c4WarmUp -> L10n.tr("C4 warm-up")
            c4Max -> L10n.tr("C4 max")
            fingerRehab -> L10n.tr("Finger rehab")
        }

    /// Attribution, never endorsement: the authors published these; they did not approve
    /// this app.
    val source: String
        get() = when (this) {
            dailyNoHangs -> L10n.tr("After Emil Abrahamsson")
            c4WarmUp, c4Max -> L10n.tr("After Camp4 Human Performance")
            fingerRehab -> L10n.tr("After Hooper's Beta")
        }

    val blurb: String
        get() = when (this) {
            dailyNoHangs -> L10n.tr("Light no-hangs across six grips, twice a day, every day.")
            c4WarmUp -> L10n.tr("Two pulls a hand per rung, ramping from 45 to 95 % of max.")
            c4Max -> L10n.tr("Three sets of three at 80–100 % of max, when you're fresh.")
            fingerRehab -> L10n.tr("Light 10-second pulls at 15–25 % of max to reload an injured finger.")
        }

    /// Shown under the preview only where it is true.
    val caution: String?
        get() = when (this) {
            fingerRehab ->
                L10n.tr("A rehab plan is not a diagnosis. Load an injured finger only as far as your physio or doctor advises.")
            dailyNoHangs, c4WarmUp, c4Max -> null
        }

    /// Computed, never stored: every call mints fresh `SetPlan` ids, so adding the same
    /// protocol twice gives two routines that share no row identity (see `starter`).
    val draft: RoutineDraft
        get() = when (this) {
            dailyNoHangs -> dailyNoHangsDraft()
            c4WarmUp -> c4WarmUpDraft()
            c4Max -> c4MaxDraft()
            fingerRehab -> fingerRehabDraft()
        }

    companion object {
        /// The four, in the chooser's order.
        val allCases: List<RoutineProtocol> = entries

        private fun halfCrimp20() = GripSpec(20, FingerSet.four, GripPosition.halfCrimp)

        /// A 20 mm ladder of six grips, alternating every pull. Same shape as `starter`,
        /// with the positions and load the written protocol states.
        ///
        /// **Rest is the gap between pulls, and the other hand's pull counts toward it.**
        /// Alternating L R L R, a 10 s gap plus the other hand's 10 s pull is the
        /// protocol's 20 s per hand; a 20 s gap gave each hand 40 s and the session ran
        /// twice as long (Nuri, 2026-09-25). Sets change on the same beat: no extra break.
        private fun dailyNoHangsDraft(): RoutineDraft {
            fun set(fingers: FingerSet, position: GripPosition, reps: Int) = SetPlan(
                grip = GripSpec(20, fingers, position), repsPerSide = reps,
                targetLoPercent = 0.35, targetHiPercent = 0.45,
            )
            return RoutineDraft(
                plan = SessionPlan(
                    name = L10n.tr("Daily no-hangs"),
                    handMode = HandMode.alternateEachRep,
                    holdSeconds = 10, restSeconds = 10, setBreakSeconds = 10,
                    sets = listOf(
                        set(FingerSet.four, GripPosition.halfCrimp, 6),
                        set(FingerSet.frontThree, GripPosition.drag, 6),
                        set(FingerSet.frontTwo, GripPosition.drag, 2),
                        set(FingerSet.middleTwo, GripPosition.drag, 2),
                        set(FingerSet.frontTwo, GripPosition.halfCrimp, 2),
                        set(FingerSet.middleTwo, GripPosition.halfCrimp, 2),
                    ),
                ),
                sessionsPerDay = 2,
                reminders = listOf(ReminderTime(8, 0), ReminderTime(19, 0)),
            )
        }

        /// Six rungs of two pulls a hand, one hand at a time as C4 tests. One hand at a
        /// time runs a hand's pulls back to back, so its rest is all its own.
        private fun c4WarmUpDraft(): RoutineDraft {
            val bands = listOf(0.45 to 0.55, 0.65 to 0.75, 0.75 to 0.85, 0.85 to 0.95, 0.55 to 0.65, 0.75 to 0.85)
            return RoutineDraft(
                plan = SessionPlan(
                    name = L10n.tr("C4 warm-up"),
                    handMode = HandMode.alternateEachSet,
                    holdSeconds = 5, restSeconds = 10, setBreakSeconds = 30,
                    sets = bands.map { (lo, hi) ->
                        SetPlan(grip = halfCrimp20(), repsPerSide = 2, targetLoPercent = lo, targetHiPercent = hi)
                    },
                ),
                sessionsPerDay = 1,
                isOnDemand = true,
            )
        }

        /// Three by three at 80–100 %. The band is a LANE here, not a gate: gating would
        /// pause the clock on a pull that beats the max on file — the one pull this routine
        /// is for.
        private fun c4MaxDraft(): RoutineDraft = RoutineDraft(
            plan = SessionPlan(
                name = L10n.tr("C4 max"),
                handMode = HandMode.alternateEachSet,
                holdSeconds = 4, restSeconds = 10, setBreakSeconds = 60,
                pausesOutsideTargetBand = false,
                sets = List(3) {
                    SetPlan(grip = halfCrimp20(), repsPerSide = 3, targetLoPercent = 0.80, targetHiPercent = 1.0)
                },
            ),
            sessionsPerDay = 1,
            isOnDemand = true,
        )

        /// Five by five a hand, alternating every pull (Nuri, 2026-09-25), 10 s on and a
        /// full minute off per hand: a 50 s gap plus the other hand's 10 s pull, on the same
        /// beat between sets (see `dailyNoHangsDraft` for the rule). Capped at 25 %. The band
        /// GATES here — the ceiling is the prescription, and EASE OFF is the right thing to
        /// hear over it.
        private fun fingerRehabDraft(): RoutineDraft = RoutineDraft(
            plan = SessionPlan(
                name = L10n.tr("Finger rehab"),
                handMode = HandMode.alternateEachRep,
                holdSeconds = 10, restSeconds = 50, setBreakSeconds = 50,
                sets = List(5) {
                    SetPlan(grip = halfCrimp20(), repsPerSide = 5, targetLoPercent = 0.15, targetHiPercent = 0.25)
                },
            ),
            sessionsPerDay = 1,
            reminders = listOf(ReminderTime(8, 0)),
        )
    }
}
