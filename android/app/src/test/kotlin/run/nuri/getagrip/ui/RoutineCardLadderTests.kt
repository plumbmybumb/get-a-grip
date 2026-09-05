// SPDX-License-Identifier: MPL-2.0
// Original contributions Copyright 2026 Nuri Bruner.

package run.nuri.getagrip.ui

import run.nuri.getagrip.engine.FingerSet
import run.nuri.getagrip.engine.GripSpec
import run.nuri.getagrip.engine.LadderRung
import run.nuri.getagrip.engine.RoutineSummary
import run.nuri.getagrip.ui.today.PlanRowFit
import run.nuri.getagrip.ui.today.PlanRowStats
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/// The routine card's plan row picks one of three forms — the full glyph row, the COMPACT
/// glyph row, or the plain sentence — and iOS spells that as `ViewThatFits(in: .horizontal)`
/// with the three candidates in order. Compose has no measuring container that is free, so
/// the choice is arithmetic, and being arithmetic is exactly what lets it be pinned here
/// rather than from a screenshot (the same move `DialLadder` makes for the dial's drawing).
///
/// The labels come from `PlanRowStats.of`, not from literals, so this is a test of the real
/// rendering path: change the wording of a stat and the fit is re-measured with it.
class RoutineCardLadderTests {

    /// A single-edge routine — Nuri's own daily protocol. Every set agrees on 20 mm, which is
    /// the case the FULL row exists to serve.
    private val singleEdge = summary(edges = List(6) { 20 })

    /// A 20-and-10 taper. `edgeLine` states the span in ladder order ("20–10 mm"), and those
    /// three extra characters are what overflowed the row on hardware and truncated its own
    /// stat ("20–10…").
    private val mixedEdge = summary(edges = listOf(20, 20, 15, 15, 10, 10))

    private fun full(summary: RoutineSummary) = PlanRowStats.of(summary).map { it.full }
    private fun compact(summary: RoutineSummary) = PlanRowStats.of(summary).map { it.compact }

    // MARK: - The three forms

    /// A card interior on a 380 dp phone: 380 − 2×20 screen padding − 2×16 card padding.
    private val cardInterior = 308f

    @Test
    fun aSingleEdgeRoutineKeepsTheFullGlyphRow() {
        assertEquals(
            PlanRowFit.Form.Full,
            PlanRowFit.form(cardInterior, 1f, full(singleEdge), compact(singleEdge)),
        )
    }

    /// **The failure this ladder exists for.** The span plus four symbols does not fit, and
    /// the answer is the COMPACT row — not the sentence. The first fix on iOS fell straight
    /// past compact and threw the icons away on exactly the routine the span exists for
    /// (Nuri: "the little icons being gone is sad").
    @Test
    fun anEdgeSpanFallsToCompactRatherThanToTheSentence() {
        assertEquals(
            PlanRowFit.Form.Compact,
            PlanRowFit.form(cardInterior, 1f, full(mixedEdge), compact(mixedEdge)),
        )
    }

    /// Only when even the compact row cannot fit does the sentence take over — it is the
    /// last rung, never the second.
    @Test
    fun aRowTooNarrowForEitherGlyphRowFallsToTheSentence() {
        assertEquals(
            PlanRowFit.Form.Sentence,
            PlanRowFit.form(200f, 1f, full(mixedEdge), compact(mixedEdge)),
        )
    }

    // MARK: - The accessibility fork

    /// A glyph row cannot wrap, and someone who asked for big text is served by words. The
    /// fork is on the FONT SCALE alone: however wide the card is, at accessibility sizes the
    /// row is a sentence.
    @Test
    fun anAccessibilityFontScaleAlwaysGetsTheSentence() {
        assertEquals(
            PlanRowFit.Form.Sentence,
            PlanRowFit.form(
                availableDp = 2000f,
                fontScale = PlanRowFit.SENTENCE_FONT_SCALE,
                full = full(singleEdge),
                compact = compact(singleEdge),
            ),
        )
    }

    /// And a hair under the rung is still the glyph row — the threshold is a boundary, not a
    /// slope.
    @Test
    fun justUnderTheAccessibilityRungTheGlyphRowSurvives() {
        assertEquals(
            PlanRowFit.Form.Full,
            PlanRowFit.form(
                availableDp = 400f,
                fontScale = PlanRowFit.SENTENCE_FONT_SCALE - 0.01f,
                full = full(singleEdge),
                compact = compact(singleEdge),
            ),
        )
    }

    // MARK: - The estimate itself

    /// Compact keeps every symbol and sheds only the count words the symbol already labels,
    /// so it must always be the narrower of the two. If this ever inverted, the ladder would
    /// have a rung that makes things worse.
    @Test
    fun theCompactRowIsAlwaysNarrowerThanTheFullOne() {
        listOf(singleEdge, mixedEdge).forEach { summary ->
            assertTrue(
                PlanRowFit.width(compact(summary), 1f, spacingDp = 10f) <
                    PlanRowFit.width(full(summary), 1f, spacingDp = 13f),
                "compact must be narrower for ${summary.metaLine}",
            )
        }
    }

    /// Bigger text is a wider row. Trivial, and it is the property the whole fork rests on:
    /// a scale that did not widen the estimate would keep the glyph row past the point where
    /// it truncates.
    @Test
    fun aLargerFontScaleWidensTheEstimate() {
        val small = PlanRowFit.width(full(singleEdge), 1f, spacingDp = 13f)
        val large = PlanRowFit.width(full(singleEdge), 1.2f, spacingDp = 13f)
        assertTrue(large > small)
    }

    /// A routine with no sets still draws a row — the chevron alone — rather than dividing by
    /// an empty list.
    @Test
    fun anEmptyStatListCostsOnlyTheChevron() {
        assertTrue(PlanRowFit.width(emptyList(), 1f, spacingDp = 13f) > 0f)
        assertEquals(
            PlanRowFit.Form.Full,
            PlanRowFit.form(cardInterior, 1f, emptyList(), emptyList()),
        )
    }

    // MARK: -

    private fun summary(edges: List<Int>): RoutineSummary {
        val ladder = edges.mapIndexed { index, mm ->
            LadderRung(index, GripSpec(edgeMM = mm, fingers = FingerSet.four), repsPerSide = 3)
        }
        return RoutineSummary(
            id = UUID.randomUUID(),
            name = "Daily no-hangs",
            ladder = ladder,
            setCount = edges.size,
            totalReps = 36,
            sharedEdgeMM = edges.distinct().singleOrNull(),
            estimatedSeconds = 21 * 60,
            sessionsPerDay = 2,
            completedToday = 0,
            nextReminder = null,
        )
    }
}
