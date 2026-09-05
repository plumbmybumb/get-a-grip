// SPDX-License-Identifier: MPL-2.0
// Original contributions Copyright 2026 Nuri Bruner.

package run.nuri.getagrip.ui

import run.nuri.getagrip.engine.DayStamp
import run.nuri.getagrip.ui.history.HistoryWindows
import run.nuri.getagrip.ui.history.ShareCalendarGrid
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/// The share card's geometry, pinned.
///
/// It is arithmetic rather than taste, and every number here is load-bearing: the content
/// column's width is DERIVED from the grid so the title, the summary, the best-pull line and
/// the wordmark all share the grid's edges. Text aligned to the card's own padding instead
/// sat left of the first column and read as pushed into the corner (Nuri, 2026-08-12) —
/// which is exactly the class of bug a screenshot makes you squint at and a test does not.
class ShareCalendarGridTests {

    private val today = DayStamp(20_000)

    /// 360 × 360 — the square every story format crops from without thinking.
    @Test
    fun theCardIsASquareThreeHundredAndSixty() {
        assertEquals(360f, ShareCalendarGrid.EXPORT_SIDE)
    }

    /// **7 × 36 with a 7 pt gutter**, which is what the whole layout is measured from.
    @Test
    fun theGridIsSevenThirtySixPointCellsOnASevenPointGap() {
        assertEquals(7, ShareCalendarGrid.COLUMNS)
        assertEquals(5, ShareCalendarGrid.ROWS)
        assertEquals(36f, ShareCalendarGrid.CELL)
        assertEquals(7f, ShareCalendarGrid.GAP)
    }

    /// The grid's exact span — 7 fixed cells plus 6 gaps — and NOT a rounded guess. This is
    /// the width the whole content column is pinned to.
    @Test
    fun theContentColumnIsExactlyTheGridsSpan() {
        assertEquals(7 * 36f + 6 * 7f, ShareCalendarGrid.GRID_WIDTH)
        assertEquals(294f, ShareCalendarGrid.GRID_WIDTH)
    }

    /// It has to FIT: the grid plus its own margins must not exceed the card, or the export
    /// clips the week it was made to show.
    @Test
    fun theGridFitsInsideTheCard() {
        assertTrue(ShareCalendarGrid.GRID_WIDTH < ShareCalendarGrid.EXPORT_SIDE)
        val gridHeight = ShareCalendarGrid.ROWS * ShareCalendarGrid.CELL +
            (ShareCalendarGrid.ROWS - 1) * ShareCalendarGrid.GAP
        assertEquals(208f, gridHeight)
        // Room left over for the title, the summary, the best-pull line and the wordmark.
        assertTrue(gridHeight < ShareCalendarGrid.EXPORT_SIDE * 0.62f)
    }

    /// **The card can never be handed a day count its grid cannot hold.** The window and the
    /// grid are two expressions of the same five weeks, and they are asserted against each
    /// other rather than both being trusted to say 35.
    @Test
    fun theGridHoldsExactlyOneHistoryWindow() {
        assertEquals(35, ShareCalendarGrid.CELL_COUNT)
        assertEquals(HistoryWindows.WINDOW_DAYS, ShareCalendarGrid.CELL_COUNT)
        assertEquals(ShareCalendarGrid.CELL_COUNT, HistoryWindows.days(0, today).size)
        assertEquals(
            ShareCalendarGrid.ROWS,
            HistoryWindows.days(0, today).chunked(ShareCalendarGrid.COLUMNS).size,
        )
    }

    // MARK: - The one line of copy

    /// The card's summary is the SHORT one — no "since you started" clause, because the card
    /// is a picture rather than a screen and the tracked count already says it.
    @Test
    fun theSummaryCountsTrainedAgainstTrackedDays() {
        val days = HistoryWindows.days(0, today)
        val since = today - 400
        assertEquals(
            "20 of 35 days trained",
            ShareCalendarGrid.summary(days, since) { it.raw % 35 < 20 },
        )
    }

    /// A first week with the app reads as 5 of 5, never 5 of 35: the days before you owned it
    /// are not days you missed.
    @Test
    fun untrackedDaysAreNotCountedAgainstYou() {
        val days = HistoryWindows.days(0, today)
        val since = today - 4
        assertEquals(
            "5 of 5 days trained",
            ShareCalendarGrid.summary(days, since) { it >= since },
        )
    }

    /// Nothing tracked at all still produces a sentence rather than a division by zero.
    @Test
    fun anEntirelyUntrackedWindowStillHasALine() {
        val days = HistoryWindows.days(3, today)
        assertEquals("0 of 0 days trained", ShareCalendarGrid.summary(days, today) { true })
    }
}
