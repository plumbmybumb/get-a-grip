// SPDX-License-Identifier: MPL-2.0
// Original contributions Copyright 2026 Nuri Bruner.

package run.nuri.getagrip.ui

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import run.nuri.getagrip.engine.FingerSet
import run.nuri.getagrip.ui.components.FingerSelection

class FingerSelectionTests {
    @Test
    fun selectingDigitsNeverLeavesOnlyTheThumbOrAnEmptyGrip() {
        for (rawValue in 1..31) {
            val fingers = FingerSet(rawValue)
            if (fingers.subtracting(FingerSet.thumb).isEmpty) continue
            for (finger in FingerSet.allDigits) {
                val result = FingerSelection.toggling(finger, fingers)
                assertFalse(result.subtracting(FingerSet.thumb).isEmpty)
                val candidate = FingerSet(rawValue xor finger.rawValue)
                assertEquals(if (candidate.subtracting(FingerSet.thumb).isEmpty) fingers else candidate, result)
            }
        }
    }

    @Test
    fun thumbCanBeRemovedWithoutChangingOtherFingers() {
        val pinch = FingerSet.index.union(FingerSet.thumb)
        assertEquals(pinch, FingerSelection.toggling(FingerSet.index, pinch))
        assertEquals(FingerSet.index, FingerSelection.toggling(FingerSet.thumb, pinch))
        assertEquals(pinch.union(FingerSet.middle), FingerSelection.toggling(FingerSet.middle, pinch))
    }
}
