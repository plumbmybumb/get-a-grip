// SPDX-License-Identifier: MPL-2.0
// Original contributions Copyright 2026 Nuri Bruner.

package run.nuri.getagrip.ui

import run.nuri.getagrip.ui.components.BodyWeightEntry
import run.nuri.getagrip.ui.units.WeightUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/// Body weight is typed, never slid: what a typed entry becomes.
class BodyWeightEntryTests {

    @Test
    fun eitherDecimalSeparatorReads() {
        assertEquals(68.5, BodyWeightEntry.parse("68,5", WeightUnit.kg))
        assertEquals(68.5, BodyWeightEntry.parse(" 68.5 ", WeightUnit.kg))
    }

    @Test
    fun poundsAreStoredAsKilograms() {
        assertEquals(150 * WeightUnit.KG_PER_LB, BodyWeightEntry.parse("150", WeightUnit.lb)!!, 1e-9)
    }

    /// A typo is not a weight: stored values are clamped to what a person can weigh.
    @Test
    fun entriesClampToAPlausibleBody() {
        assertEquals(25.0, BodyWeightEntry.parse("7", WeightUnit.kg))
        assertEquals(250.0, BodyWeightEntry.parse("7000", WeightUnit.kg))
    }

    /// Empty or unreadable keeps what was stored: the parse says "nothing", never 0.
    @Test
    fun emptyOrUnreadableIsNothing() {
        assertNull(BodyWeightEntry.parse("", WeightUnit.kg))
        assertNull(BodyWeightEntry.parse("abc", WeightUnit.kg))
        assertNull(BodyWeightEntry.parse("0", WeightUnit.kg))
        assertNull(BodyWeightEntry.parse("-70", WeightUnit.kg))
    }

    /// No invented default: nothing entered shows as an empty field (the "—" placeholder).
    @Test
    fun nothingEnteredDisplaysEmpty() {
        assertEquals("", BodyWeightEntry.display(null, WeightUnit.kg))
        assertEquals("70", BodyWeightEntry.display(70.0, WeightUnit.kg))
    }
}
