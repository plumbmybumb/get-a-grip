// SPDX-License-Identifier: MPL-2.0
// Original contributions Copyright 2026 Nuri Bruner.

package run.nuri.getagrip.engine

import java.util.Locale
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

/// A display line may wrap ONLY after a middle dot: "36 / pulls", "≈21 / min" and
/// "half / crimp" are each a broken fact at large text.
class DisplayTextTests {

    @BeforeTest
    fun pinLocale() {
        Locale.setDefault(Locale.US)
    }

    /// The only breakable spaces left in a string are the ones right after a "·".
    private fun breakableOnlyAfterDots(text: String) {
        text.forEachIndexed { i, c ->
            if (c == ' ') assertEquals('·', text.getOrNull(i - 1), "breakable space at $i in \"$text\"")
        }
    }

    @Test
    fun dottedBindsEveryTokenAndBreaksAfterDots() {
        assertEquals("20 mm · 4 fingers + thumb · half crimp",
            DisplayText.dotted("20 mm · 4 fingers + thumb · half crimp"))
        assertEquals("a · b", DisplayText.dotted(listOf("a", "b")))
        assertEquals("20 mm · 4 fingers", DisplayText.plain(DisplayText.dotted("20 mm · 4 fingers")))
    }

    @Test
    fun everyDisplayLineBreaksOnlyAfterADot() {
        val grip = GripSpec(20, FingerSet.four.union(FingerSet.thumb), GripPosition.halfCrimp)
        breakableOnlyAfterDots(grip.line)
        breakableOnlyAfterDots(grip.displayName)
        val plan = RoutineDraft.starter.plan
        breakableOnlyAfterDots(PlanMath.summaryLine(plan))
        breakableOnlyAfterDots(PlanMath.subtitleLine(plan))
        breakableOnlyAfterDots(PlanMath.totalsLine(plan))
        breakableOnlyAfterDots(RoutineSummary.previewing(RoutineDraft.starter).metaLine)
        assertFalse(PlanMath.approxMinutes(1290).contains(' '))
        assertFalse(PlanMath.durationText(1290).contains(' '))
    }

    /// The key is a serialization, never display copy: it must not pick up a single NBSP.
    @Test
    fun keysStayPlain() {
        val grip = GripSpec(20, FingerSet.four, GripPosition.halfCrimp)
        assertEquals("20|IMRL|halfCrimp", grip.key)
        assertFalse(grip.toJson().toString().contains(DisplayText.NBSP))
    }
}
