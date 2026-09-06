// SPDX-License-Identifier: MPL-2.0
// Original contributions Copyright 2026 Nuri Bruner.

package run.nuri.getagrip.ui

import run.nuri.getagrip.ui.components.ValueFieldParser
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/// The typed-number contract, lifted out of the composable when the field became its own
/// leaf so a keypress stops rebuilding the dial above it.
///
/// Every rule here is one the iOS app has already paid for once, and the reason they are
/// pinned is that they MOVED: parsing used to live inside the row's commit, where nothing
/// could reach it.
class ValueFieldTests {
    @Test fun nonFiniteOrOverflowingInputLeavesTheValueUnchanged() {
        for (input in listOf("NaN", "Infinity", "-Infinity", "1e309", "1e308")) {
            assertNull(ValueFieldParser.parse(input, decimals = 1), input)
        }
    }


    /// An UNTOUCHED field must report nothing at all. Tapping a number to change it and then
    /// changing your mind has to leave the value alone — a commit that read an empty string
    /// as zero would silently wipe the row.
    @Test
    fun anUntouchedFieldParsesToNothing() {
        assertNull(ValueFieldParser.parse("", decimals = 0))
        assertNull(ValueFieldParser.parse("   ", decimals = 0))
    }

    /// Not a number is not an edit — the same rule, for a field somebody pasted into.
    @Test
    fun nonsenseParsesToNothingRatherThanZero() {
        assertNull(ValueFieldParser.parse("abc", decimals = 1))
        assertNull(ValueFieldParser.parse("-", decimals = 1))
    }

    /// The same phone reads "2,5" in French and "2.5" in English, and a keypad does not care
    /// which one you were taught.
    @Test
    fun aCommaReadsAsADecimalPoint() {
        assertEquals(2.5, ValueFieldParser.parse("2,5", decimals = 1))
        assertEquals(2.5, ValueFieldParser.parse("2.5", decimals = 1))
    }

    @Test
    fun surroundingWhitespaceIsIgnored() {
        assertEquals(22.0, ValueFieldParser.parse("  22 ", decimals = 0))
    }

    /// **Typing is NOT snapped to the control's step.** The dial lands on the ladder so a
    /// round number is easy to reach by dragging; the field is the escape hatch for
    /// everything else, and one that turns a typed 7 into 5 is not an escape hatch. 22 mm is
    /// the standing example — an edge no ladder in the app offers.
    @Test
    fun aTypedValueOffTheLadderSurvivesIntact() {
        assertEquals(22.0, ValueFieldParser.parse("22", decimals = 0))
        assertEquals(7.0, ValueFieldParser.parse("7", decimals = 0))
    }

    /// Only the DISPLAY precision is enforced: whole numbers for seconds and millimetres,
    /// one decimal for kilograms.
    @Test
    fun onlyDisplayPrecisionIsEnforced() {
        assertEquals(13.0, ValueFieldParser.parse("12.6", decimals = 0))
        assertEquals(12.6, ValueFieldParser.parse("12.64", decimals = 1))
        assertEquals(12.6, ValueFieldParser.rounded(12.649, decimals = 1))
        assertEquals(12.7, ValueFieldParser.rounded(12.65, decimals = 1))
    }
}
