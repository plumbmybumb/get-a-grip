// SPDX-License-Identifier: MPL-2.0
// Original contributions Copyright 2026 Nuri Bruner.

package run.nuri.getagrip.ui

import run.nuri.getagrip.engine.DayStamp
import run.nuri.getagrip.ui.history.HistoryWindows
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/// The five-week deck's arithmetic: how many cards there are, which days each holds, what
/// each is called, and what its one line of copy counts.
///
/// Pure functions with no Compose in them, for the same reason `DayLedger` is a plain class:
/// a calendar that silently changes what a window MEANS is the worst kind of bug on this
/// screen, because it still looks like a calendar.
class HistoryWindowTests {

    private val today = DayStamp(20_000)

    // MARK: - How many cards

    /// A brand-new app still gets its current five weeks. Never zero, never a deck.
    @Test
    fun aFreshInstallHasExactlyOneWindow() {
        assertEquals(1, HistoryWindows.pageCount(trackingSince = today, today = today))
    }

    /// The window is INCLUSIVE of both ends, so 35 days of history is still one card and the
    /// 36th is what mints the second.
    @Test
    fun theSecondWindowAppearsOnTheThirtySixthTrackedDay() {
        assertEquals(1, HistoryWindows.pageCount(today - 34, today))
        assertEquals(2, HistoryWindows.pageCount(today - 35, today))
        assertEquals(2, HistoryWindows.pageCount(today - 69, today))
        assertEquals(3, HistoryWindows.pageCount(today - 70, today))
    }

    /// Two years of twice-daily training is a deck of twenty-one cards, which is why the deck
    /// is lazy: it must not build seventy hidden grids at once.
    @Test
    fun aLongHistoryIsManyWindows() {
        assertEquals(21, HistoryWindows.pageCount(today - 730, today))
    }

    /// `trackingSince` in the future — nothing logged yet, so the ledger reports today — still
    /// produces one window rather than a negative count.
    @Test
    fun aTrackingDayAfterTodayStillYieldsOneWindow() {
        assertEquals(1, HistoryWindows.pageCount(today + 5, today))
    }

    // MARK: - Which days

    /// Window 0 is HOME: the five weeks ending today, oldest first so it fills the grid left
    /// to right, top to bottom.
    @Test
    fun windowZeroEndsToday() {
        val days = HistoryWindows.days(0, today)
        assertEquals(35, days.size)
        assertEquals(today - 34, days.first())
        assertEquals(today, days.last())
    }

    /// Windows tile exactly, with no gap and no overlap: window 1 ends the day before window
    /// 0 begins. A day that fell between two cards would be a training day the calendar never
    /// drew.
    @Test
    fun windowsTileWithNoGapAndNoOverlap() {
        val home = HistoryWindows.days(0, today)
        val previous = HistoryWindows.days(1, today)
        assertEquals(home.first() - 1, previous.last())
        assertEquals(35, previous.size)
        assertTrue(home.zipWithNext().all { (a, b) -> b.raw == a.raw + 1 })
        assertTrue(previous.zipWithNext().all { (a, b) -> b.raw == a.raw + 1 })
    }

    /// The grid is 7 × 5 and the window is 35 days. Stating it as a test because the card's
    /// layout chunks by seven and a mismatch would silently drop a row.
    @Test
    fun aWindowIsExactlyFiveWeeksOfSeven() {
        assertEquals(35, HistoryWindows.WINDOW_DAYS)
        assertEquals(5, HistoryWindows.days(0, today).chunked(7).size)
    }

    // MARK: - What it is called

    /// **Home says what it IS; the past says WHEN it was** — the window's identity once "last
    /// 5 weeks" stops being true of it.
    @Test
    fun homeIsNamedAndThePastIsDated() {
        assertEquals("Last 5 weeks", HistoryWindows.title(0, HistoryWindows.days(0, today)))
        val older = HistoryWindows.title(1, HistoryWindows.days(1, today))
        assertTrue(older != "Last 5 weeks")
        // A RANGE, both ends named: one date would leave the reader to work out which end.
        assertTrue(older.contains("–"), "expected an en dash range, got: $older")
    }

    /// An empty day list cannot happen from `days(_)`, but the title must not throw if one
    /// ever reaches it — a card with no name is better than a crash on the History tab.
    @Test
    fun anEmptyWindowFallsBackToTheHomeTitle() {
        assertEquals("Last 5 weeks", HistoryWindows.title(3, emptyList()))
    }

    // MARK: - The summary line

    /// Counted against the days actually TRACKED, so a first week with the app doesn't read as
    /// 6 of 35 — nobody can fail on a day they did not own the app.
    @Test
    fun anUntrackedStartCountsOnlyTheDaysYouHad() {
        val days = HistoryWindows.days(0, today)
        val since = today - 6
        val line = HistoryWindows.summary(0, days, since) { it >= since }
        assertEquals("7 of 7 days trained since you started", line)
    }

    /// A fully-tracked HOME window says "the last 35 days"; a fully-tracked older one just
    /// says 35, because "the last" is only true of the card you are standing on.
    @Test
    fun aFullyTrackedWindowNamesItselfByPosition() {
        val days = HistoryWindows.days(0, today)
        val since = today - 400
        assertEquals(
            "20 of the last 35 days trained",
            HistoryWindows.summary(0, days, since) { it.raw % 35 < 20 },
        )
        assertEquals(
            "0 of 35 days trained",
            HistoryWindows.summary(2, HistoryWindows.days(2, today), since) { false },
        )
    }
}
