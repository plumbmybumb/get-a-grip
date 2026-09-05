// SPDX-License-Identifier: MPL-2.0
// Original contributions Copyright 2026 Nuri Bruner.

package run.nuri.getagrip.ui

import run.nuri.getagrip.engine.DayStamp
import run.nuri.getagrip.engine.FingerStrain
import run.nuri.getagrip.engine.RPE
import run.nuri.getagrip.engine.SessionKind
import run.nuri.getagrip.ui.history.SessionLogDraft
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/// The log sheet's whole decision, with no sheet around it.
///
/// **Save stays enabled after two taps.** Kind + day alone is a complete log, and the two
/// strain axes may stay unanswered — the fast path has to cost exactly what it costs today,
/// because the realistic moment to log Tuesday's gym session is Wednesday morning and
/// anything heavier than two taps is a session that never gets recorded.
class SessionLogDraftTests {

    private val today = DayStamp(20_000)

    // MARK: - What Save needs

    /// The one rule. Nothing else on the sheet is required.
    @Test
    fun kindAloneIsACompleteLog() {
        val draft = SessionLogDraft(kind = SessionKind.climbVolume)
        assertTrue(draft.canSave)
        assertNull(draft.rpe)
        assertNull(draft.fingerStrain)
    }

    /// No default kind. Volume and limit are genuinely different days and the app cannot
    /// guess which you had — pre-selecting one would get it wrong half the time and silently
    /// mis-describe the week, which is what this feature exists to fix.
    @Test
    fun anUntouchedDraftCannotBeSaved() {
        assertFalse(SessionLogDraft().canSave)
        assertNull(SessionLogDraft().kind)
    }

    /// **`hang` belongs to the runner and `benchmark` to `recordMax`.** Neither may be
    /// created by hand, and the gate is the engine's own `isLoggedByHand` rather than a
    /// second list here — the store refuses them too, so a sheet that offered one would show
    /// an enabled Save that silently did nothing.
    @Test
    fun theKindsOnlyTheAppMayWriteAreNotSaveable() {
        assertFalse(SessionLogDraft(kind = SessionKind.hang).canSave)
        assertFalse(SessionLogDraft(kind = SessionKind.benchmark).canSave)
    }

    /// And the three the sheet offers all are.
    @Test
    fun everyKindOnTheChipRowIsSaveable() {
        SessionLogDraft.kinds.forEach { kind ->
            assertTrue(SessionLogDraft(kind = kind).canSave, "$kind should be loggable by hand")
        }
    }

    /// The strain axes never gate Save; they only ride along.
    @Test
    fun answeringTheStrainAxesChangesNothingAboutSave() {
        val answered = SessionLogDraft(
            kind = SessionKind.climbLimit,
            rpe = RPE.maximal,
            fingerStrain = FingerStrain.wrecked,
        )
        assertTrue(answered.canSave)
        assertEquals(RPE.maximal, answered.rpe)
        assertEquals(FingerStrain.wrecked, answered.fingerStrain)
    }

    // MARK: - The day

    @Test
    fun todayIsTheDayTheClockSays() {
        assertEquals(today, SessionLogDraft(kind = SessionKind.climbVolume).day(today))
    }

    /// Yesterday, because the realistic moment to log a session is the next morning.
    @Test
    fun yesterdayIsOneDayBack() {
        assertEquals(today - 1, SessionLogDraft(kind = SessionKind.climbVolume, daysAgo = 1).day(today))
    }

    /// CLAMPED rather than validated: a negative would file training in the future, which no
    /// screen should be able to produce and no store should have to defend against twice.
    @Test
    fun aNegativeDayIsClampedToToday() {
        assertEquals(today, SessionLogDraft(kind = SessionKind.climbVolume, daysAgo = -3).day(today))
    }

    // MARK: - The consequence copy

    /// The rule is STATED on the screen that invokes it: a climb settles the day, a manual
    /// hang only fills one session share. The copy follows the selected kind, and until one
    /// is picked it says so rather than describing a day nobody chose.
    @Test
    fun theConsequenceFollowsTheKind() {
        assertTrue(SessionLogDraft(kind = SessionKind.climbLimit).consequence.contains("completes today"))
        assertTrue(
            SessionLogDraft(kind = SessionKind.hangManual).consequence
                .contains("does not settle the day")
        )
        assertTrue(SessionLogDraft().consequence.contains("Choose a session kind"))
    }

    @Test
    fun theConsequenceNamesTheDayItWillLandOn() {
        val yesterday = SessionLogDraft(kind = SessionKind.climbVolume, daysAgo = 1)
        assertTrue(yesterday.consequence.contains("yesterday"))
    }

    // MARK: - The duration ladder

    /// Two hours is pre-filled: a close-enough duration is more useful to the load model than
    /// nothing at all, and it is visible and one drag from right.
    @Test
    fun theDurationDefaultsToTwoHoursAndIsOnTheLadder() {
        assertEquals(120, SessionLogDraft().minutes)
        assertTrue(SessionLogDraft.durationStops.contains(120.0))
    }

    /// The ladder is what makes the control quotable — every value it can produce is readable
    /// without touching it, so every stop must have a label of its own.
    @Test
    fun everyDurationStopHasItsOwnLabel() {
        val labels = SessionLogDraft.durationStops.map { SessionLogDraft.durationLabel(it.toInt()) }
        assertEquals(labels.size, labels.toSet().size)
        assertEquals("2h", SessionLogDraft.durationLabel(120))
        assertEquals("1h30", SessionLogDraft.durationLabel(90))
        // An off-ladder minute count still reads as minutes rather than as an empty string.
        assertEquals("75m", SessionLogDraft.durationLabel(75))
    }
}
