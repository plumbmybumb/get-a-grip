// SPDX-License-Identifier: MPL-2.0
// Original contributions Copyright 2026 Nuri Bruner.

package run.nuri.getagrip.engine

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MaxEditDraftTests {
    @Test fun openingAnEditorDoesNotCreateRecordsForEitherHand() {
        val draft = MaxEditDraft(30.0, 40.0)
        assertFalse(draft.canSave)
        assertTrue(draft.changes.isEmpty())
        assertEquals(30.0, draft.leftKg)
        assertEquals(40.0, draft.rightKg)
    }

    @Test fun missingHandDoesNotInheritAnotherHandsMax() {
        val draft = MaxEditDraft(leftKg = 30.0)
        assertEquals(0.0, draft.rightKg)
        assertNull(draft.originalKg(Side.right))
        draft.rightKg = 35.0
        assertEquals(listOf(MaxEditDraft.Change(Side.right, 35.0)), draft.changes)
    }

    @Test fun unchangedRetestOnlyIncludesTheExplicitlySelectedHand() {
        val draft = MaxEditDraft(30.0, 40.0)
        draft.setRecordsAnotherTest(true, Side.left)
        assertTrue(draft.canSave)
        assertEquals(listOf(MaxEditDraft.Change(Side.left, 30.0)), draft.changes)
        draft.setRecordsAnotherTest(false, Side.left)
        assertFalse(draft.canSave)
    }

    @Test fun aRetestCannotInventAnUnsetOrSharedHand() {
        val draft = MaxEditDraft(leftKg = 30.0)
        draft.setRecordsAnotherTest(true, Side.right)
        draft.setRecordsAnotherTest(true, Side.both)
        assertTrue(draft.repeatedTests.isEmpty())
        assertFalse(draft.canSave)
    }

    @Test fun anInvalidChangedHandRejectsTheWholeEdit() {
        for (invalid in listOf(0.0, -1.0, Double.NaN, Double.POSITIVE_INFINITY)) {
            val draft = MaxEditDraft(30.0, 40.0)
            draft.leftKg = invalid
            draft.rightKg = 45.0
            assertTrue(draft.hasInvalidChanges)
            assertFalse(draft.canSave)
        }
    }

    @Test fun rebaseFollowsUntouchedValuesAndKeepsExplicitEdits() {
        val draft = MaxEditDraft(30.0, 40.0)
        draft.leftKg = 33.0
        draft.rebase(31.0, 41.0)
        assertEquals(33.0, draft.leftKg)
        assertEquals(41.0, draft.rightKg)
        assertEquals(listOf(MaxEditDraft.Change(Side.left, 33.0)), draft.changes)
    }

    @Test fun rebaseDoesNotReplaceAnExplicitUnchangedRetest() {
        val draft = MaxEditDraft(30.0, 40.0)
        draft.setRecordsAnotherTest(true, Side.left)
        draft.rebase(35.0, 45.0)
        assertEquals(30.0, draft.leftKg)
        assertEquals(45.0, draft.rightKg)
        assertTrue(draft.recordsAnotherTest(Side.left))
    }

    @Test fun aCopyCanBeEditedWithoutMutatingTheDisplayedOriginal() {
        val original = MaxEditDraft(30.0, 40.0)
        val copy = original.copy()
        copy.leftKg = 32.0
        copy.setRecordsAnotherTest(true, Side.right)
        assertEquals(30.0, original.leftKg)
        assertTrue(original.repeatedTests.isEmpty())
        assertFalse(original.canSave)
        assertEquals(2, copy.changes.size)
    }
}
