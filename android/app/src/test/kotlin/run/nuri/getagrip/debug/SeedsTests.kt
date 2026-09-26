// SPDX-License-Identifier: MPL-2.0
// Original contributions Copyright 2026 Nuri Bruner.

package run.nuri.getagrip.debug

import run.nuri.getagrip.ble.MockForceProfile
import run.nuri.getagrip.engine.RoutineDraft
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/// The store-screenshot seeds: a demo run must land INSIDE the typed band, and today must
/// read as a day half done.
class SeedsTests {

    @Test
    fun theDemoKgTargetIsTypedOnEverySetAndClearsEveryPercentage() {
        val draft = RoutineDraft.starter.let {
            it.copy(plan = it.plan.copy(targetLoPercent = 0.18, targetHiPercent = 0.22))
        }
        val seeded = Seeds.withDemoKgTarget(draft)

        assertNull(seeded.plan.targetLoPercent)
        assertNull(seeded.plan.targetHiPercent)
        assertEquals(draft.plan.sets.size, seeded.plan.sets.size)
        for (set in seeded.plan.sets) {
            assertEquals(20.0..24.0, set.targetBand)
            assertNull(set.targetPercentBand)
        }
        // The grips themselves are untouched: the band is the only thing the seed changes.
        assertEquals(draft.plan.sets.map { it.grip }, seeded.plan.sets.map { it.grip })
    }

    @Test
    fun theDemoGaugePlateauSitsInsideTheDemoBand() {
        // Sampled from the real demo curve, past the ramp-in and before the ramp-out.
        var t = 0.5
        while (t < MockForceProfile.workSeconds - 0.35) {
            val kg = MockForceProfile.force(t, MockForceProfile.clean)
            assertTrue(kg > Seeds.demoTargetLoKg && kg < Seeds.demoTargetHiKg, "$kg kg at $t s")
            t += 0.0125
        }
    }

    @Test
    fun oneTodayLeavesOnlyTodayAtASingleSession() {
        assertEquals(1, Seeds.sessionsOn(daysAgo = 0, oneToday = true))
        assertEquals(2, Seeds.sessionsOn(daysAgo = 0, oneToday = false))
        // Every other day is exactly what it was.
        for (daysAgo in 1..55) {
            assertEquals(
                Seeds.sessionsOn(daysAgo, oneToday = false),
                Seeds.sessionsOn(daysAgo, oneToday = true),
            )
        }
        assertEquals(1, Seeds.sessionsOn(daysAgo = 1, oneToday = false))
        assertEquals(2, Seeds.sessionsOn(daysAgo = 2, oneToday = false))
    }
}
