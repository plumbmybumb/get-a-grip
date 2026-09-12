// SPDX-License-Identifier: MPL-2.0
// Original contributions Copyright 2026 Nuri Bruner.

package run.nuri.getagrip.ui

import java.util.Locale
import kotlin.test.assertEquals
import kotlin.test.assertNull
import org.junit.After
import org.junit.Before
import org.junit.Test
import run.nuri.getagrip.engine.HandMode
import run.nuri.getagrip.engine.L10n
import run.nuri.getagrip.engine.SessionPlan
import run.nuri.getagrip.engine.SetPlan
import run.nuri.getagrip.ui.today.overviewPullCount
import run.nuri.getagrip.ui.today.overviewTarget
import run.nuri.getagrip.ui.units.WeightUnit
import run.nuri.getagrip.ui.units.WeightUnits

class RoutineOverviewContentTests {
    private val oldLocale = Locale.getDefault()
    private val oldLookup = L10n.lookup
    private val oldUnit = WeightUnits.current

    @Before fun setup() {
        Locale.setDefault(Locale.US)
        L10n.lookup = null
        WeightUnits.current = WeightUnit.kg
    }

    @After fun restore() {
        Locale.setDefault(oldLocale)
        L10n.lookup = oldLookup
        WeightUnits.current = oldUnit
    }

    @Test fun alternatingAndBothHandsCountsAreUnambiguousForLongSets() {
        val set = SetPlan(repsPerSide = 36)
        assertEquals("36 per side · 72 pulls total", overviewPullCount(set, SessionPlan(handMode = HandMode.alternateEachRep)))
        assertEquals("36 per side · 72 pulls total", overviewPullCount(set, SessionPlan(handMode = HandMode.alternateEachSet)))
        assertEquals("36 pulls total", overviewPullCount(set, SessionPlan(handMode = HandMode.bothHands)))
        assertEquals("1 pull total", overviewPullCount(set.copy(repsPerSide = 1), SessionPlan(handMode = HandMode.bothHands)))
    }

    @Test fun inheritedFractionalPercentStaysThePrescriptionWithoutAMaxLookup() {
        val plan = SessionPlan(targetLoPercent = 0.225, targetHiPercent = 0.372)
        assertEquals("Target: 22.5–37.2 % of max", overviewTarget(SetPlan(), plan))
        val set = SetPlan(targetLoPercent = 0.18, targetHiPercent = 0.22)
        assertEquals("Target: 18–22 % of max", overviewTarget(set, plan))
        assertEquals("Target: 22.5 % of max", overviewTarget(SetPlan(targetLoPercent = 0.225), plan))
        assertNull(overviewTarget(SetPlan(), SessionPlan()))
    }

    @Test fun fixedWeightWinsOverBothPercentageSourcesAndUsesPreferredUnit() {
        val plan = SessionPlan(targetLoPercent = 0.2, targetHiPercent = 0.3)
        val set = SetPlan(targetLoKg = 4.0, targetHiKg = 8.0, targetLoPercent = 0.5, targetHiPercent = 0.8)
        assertEquals("4.0–8.0 kg target", overviewTarget(set, plan))
        WeightUnits.current = WeightUnit.lb
        assertEquals("8.8–17.6 lb target", overviewTarget(set, plan))
        assertEquals(4.0, set.targetLoKg)
        assertEquals(8.0, set.targetHiKg)
    }

    @Test fun fractionalPercentageUsesTheReadersDecimalSeparator() {
        Locale.setDefault(Locale.FRANCE)
        assertEquals("Target: 22,5–37,2 % of max", overviewTarget(
            SetPlan(), SessionPlan(targetLoPercent = 0.225, targetHiPercent = 0.372),
        ))
    }
}
