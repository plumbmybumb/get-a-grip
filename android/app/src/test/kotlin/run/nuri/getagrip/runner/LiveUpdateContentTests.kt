// SPDX-License-Identifier: MPL-2.0
// Original contributions Copyright 2026 Nuri Bruner.

package run.nuri.getagrip.runner

import run.nuri.getagrip.engine.FingerSet
import run.nuri.getagrip.engine.GripPosition
import run.nuri.getagrip.engine.GripSpec
import run.nuri.getagrip.engine.Side
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/// The Live Update's content mapping — the Android twin of the iOS lock-screen card.
///
/// There is no Swift test file to translate: on iOS the card is a SwiftUI view inside a
/// widget extension and is only ever verified by looking at it. Making the mapping a value
/// (`LiveUpdateContent`) is what buys these assertions, and the four rules they pin are all
/// ones the iOS card learned the hard way — the phase ladder with `armed` as its own beat,
/// the fixed-hex tints, the `endsAt > now` guard, and dropping "Set 1 of 1".
class LiveUpdateContentTests {

    private val grip = GripSpec(
        edgeMM = 20,
        fingers = FingerSet.four,
        position = GripPosition.halfCrimp,
    )

    private fun state(
        phase: SessionActivityPhase,
        endsAt: Long? = null,
        pending: Int? = null,
        side: Side = Side.both,
        setNumber: Int = 1,
        repPosition: Int = 1,
        lo: Double? = null,
        hi: Double? = null,
    ) = SessionActivityState(
        grip = grip,
        side = side,
        phase = phase,
        setNumber = setNumber,
        repPosition = repPosition,
        targetLoKg = lo,
        targetHiKg = hi,
        endsAtEpochMillis = endsAt,
        pendingSeconds = pending,
    )

    private fun content(
        state: SessionActivityState,
        plannedReps: Int = 12,
        setCount: Int = 1,
        now: Long = 1_000_000L,
    ) = LiveUpdateContent.of(state, plannedReps, setCount, now)

    /// **One ladder, shared with the runner screen and the iOS card.** Steel while resting
    /// or leading in, amber the moment it is on you, bleu while the clock runs. Blue is
    /// "pulling", not yellow — amber means *waiting on you*, which is why `paused` shares
    /// the armed colour and `releasing` was folded into rest rather than given a fifth word.
    @Test
    fun everyPhaseHasItsWordAndItsFixedTint() {
        val expected = listOf(
            Triple(SessionActivityPhase.leadIn, "Get ready", LiveUpdateContent.TINT_CALM),
            Triple(SessionActivityPhase.armed, "Pull now", LiveUpdateContent.TINT_ARMED),
            Triple(SessionActivityPhase.pulling, "Holding", LiveUpdateContent.TINT_PULLING),
            Triple(SessionActivityPhase.releasing, "Let go", LiveUpdateContent.TINT_CALM),
            Triple(SessionActivityPhase.resting, "Rest", LiveUpdateContent.TINT_CALM),
            Triple(SessionActivityPhase.paused, "Paused", LiveUpdateContent.TINT_PAUSED),
        )
        // Every case, so a phase added later cannot quietly render as a blank card.
        assertEquals(SessionActivityPhase.entries.size, expected.size)
        for ((phase, word, tint) in expected) {
            assertEquals(word, LiveUpdateContent.word(phase))
            assertEquals(tint, LiveUpdateContent.cardTint(phase), "$phase")
        }
    }

    /// The tints are FIXED HEX, carried byte for byte from `SessionActivity.Phase.cardTint`,
    /// and opaque. A translucent wash of the accent would be a legibility coin-toss against
    /// whatever wallpaper is behind the lock screen, on the one surface whose whole job is
    /// being read across a room mid-hang.
    @Test
    fun theTintsAreTheFixedHexesTheIosCardUses() {
        assertEquals(0xFF161A20.toInt(), LiveUpdateContent.TINT_CALM)
        assertEquals(0xFF3A2408.toInt(), LiveUpdateContent.TINT_ARMED)
        assertEquals(0xFF0E2740.toInt(), LiveUpdateContent.TINT_PULLING)
        assertEquals(0xFF24282F.toInt(), LiveUpdateContent.TINT_PAUSED)
    }

    /// A live deadline becomes the chronometer, and the app never pushes again for it.
    @Test
    fun aLiveDeadlineDrivesTheChronometer() {
        val card = content(state(SessionActivityPhase.pulling, endsAt = 1_007_000L))
        assertEquals(1_007_000L, card.chronometerEndsAtMillis)
        assertEquals("Holding", card.title, "no seconds in the title: the clock carries them")
        assertTrue(card.isActive)
    }

    /// **`endsAt > now` is a correctness guard, not tidiness.** A chronometer handed a
    /// deadline in the past counts UP from it, so a card nobody has pushed to in a while
    /// would sit there claiming a rep has been running for four minutes. iOS guards the same
    /// comparison because `Date.now...endsAt` traps outright and renders a blank widget.
    @Test
    fun aDeadlineInThePastIsNotAClock() {
        val card = content(state(SessionActivityPhase.pulling, endsAt = 999_000L))
        assertNull(card.chronometerEndsAtMillis)
    }

    /// Paused has a deadline on paper and no clock in fact. Running one would count down
    /// through a rep nobody is doing.
    @Test
    fun aPausedCardRunsNoClock() {
        val card = content(state(SessionActivityPhase.paused, endsAt = 1_007_000L))
        assertNull(card.chronometerEndsAtMillis)
        assertEquals("Paused", card.title)
        assertFalse(card.isActive)
    }

    @Test
    fun releasingCannotShowTheUpcomingRestOrAnArmedHoldCountdown() {
        val card = content(state(SessionActivityPhase.releasing, endsAt = 1_020_000L, pending = 7))
        assertEquals("Let go", card.title)
        assertNull(card.chronometerEndsAtMillis)
        assertFalse(card.isActive)
        val resting = content(state(SessionActivityPhase.resting, endsAt = 1_023_000L))
        assertEquals(1_023_000L, resting.chronometerEndsAtMillis)
    }

    @Test
    fun armedIgnoresAnObsoleteDeadlineAndShowsOnlyTheHoldLength() {
        val card = content(state(SessionActivityPhase.armed, endsAt = 1_020_000L, pending = 7))
        assertEquals("Pull now · 7s", card.title)
        assertNull(card.chronometerEndsAtMillis)
    }

    /// **ARMED waits on YOU, with no timeout, by design** — so there is no deadline to count
    /// down to, and a live countdown would be a lie that ran while you were still chalking
    /// up. The honest answer is the length of the hold ahead, which is a number sitting
    /// still: it goes in the title, where it cannot be mistaken for a stopped clock.
    @Test
    fun armedShowsTheHoldAheadInsteadOfACountdown() {
        val card = content(state(SessionActivityPhase.armed, pending = 7))
        assertNull(card.chronometerEndsAtMillis)
        assertEquals("Pull now · 7s", card.title)
        assertTrue(card.isActive)
    }

    /// **"Set 1 of 1" is dropped** — a constant dressed up as a counter, and it was costing
    /// the pull count the room it needed to stay on one line.
    @Test
    fun oneSetDropsTheSetCounter() {
        val single = content(state(SessionActivityPhase.resting, repPosition = 4), setCount = 1)
        assertEquals("Pull 4 of 12", single.subText)

        val many = content(
            state(SessionActivityPhase.resting, setNumber = 2, repPosition = 7),
            plannedReps = 36,
            setCount = 6,
        )
        assertEquals("Set 2 of 6 · Pull 7 of 36", many.subText)
    }

    /// The hand LEADS the grip line. iOS carries it in the hand mark the card draws; a
    /// notification has no room for one, so losing it would make the line describe the wrong
    /// pull. `both` is left off — it is the default and says nothing.
    @Test
    fun theHandLeadsTheGripLineAndBothIsSilent() {
        assertEquals(
            "20 mm · 4 fingers · half crimp",
            content(state(SessionActivityPhase.pulling, side = Side.both)).text,
        )
        assertEquals(
            "Left · 20 mm · 4 fingers · half crimp",
            content(state(SessionActivityPhase.pulling, side = Side.left)).text,
        )
    }

    /// **The TARGET, never the live reading.** A kilogram figure pushed on a state change is
    /// stale the instant after it is sent and would sit there confidently wrong between
    /// reps; the band is constant for the whole rep, which makes it the only load number
    /// this surface can tell the truth about.
    @Test
    fun theTargetBandRidesWithTheGrip() {
        val card = content(state(SessionActivityPhase.pulling, lo = 8.0, hi = 12.0))
        assertEquals("20 mm · 4 fingers · half crimp · 8.0–12.0 kg", card.text)
    }

    /// No max on file means no band, and a card with no band says nothing about load rather
    /// than inventing a confident figure — the codebase's standing rule.
    @Test
    fun noBandAddsNothing() {
        assertEquals(
            "20 mm · 4 fingers · half crimp",
            content(state(SessionActivityPhase.pulling, lo = 8.0, hi = null)).text,
        )
    }

    /// The status-bar chip gets the phase word and nothing else: it is a few characters
    /// wide, and the phase is the one thing worth reading at that size.
    @Test
    fun theCriticalStripIsThePhaseWord() {
        assertEquals(
            "Rest",
            content(state(SessionActivityPhase.resting, endsAt = 1_020_000L)).shortCriticalText,
        )
    }
}
