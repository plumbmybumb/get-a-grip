// SPDX-License-Identifier: MPL-2.0
// Original contributions Copyright 2026 Nuri Bruner.
package run.nuri.getagrip.ui.units

import run.nuri.getagrip.engine.*
import run.nuri.getagrip.runner.LiveUpdateContent
import run.nuri.getagrip.runner.SessionActivityPhase
import run.nuri.getagrip.runner.SessionActivityState
import run.nuri.getagrip.ui.components.ValueFieldParser
import run.nuri.getagrip.ui.history.ShareCalendarBestPull
import run.nuri.getagrip.ui.maxes.MaxEntryDraft
import java.time.Instant
import java.util.Locale
import kotlin.test.*

class WeightUnitTests {
    private val previousLocale = Locale.getDefault()
    private val previousLookup = L10n.lookup
    @BeforeTest fun before() { Locale.setDefault(Locale.US); L10n.lookup = null; WeightUnits.current = WeightUnit.kg }
    @AfterTest fun after() { Locale.setDefault(previousLocale); L10n.lookup = previousLookup; WeightUnits.current = WeightUnit.kg }

    @Test fun missingOrUnknownPreferencePreservesExistingKilogramDefault() {
        assertEquals(WeightUnit.kg, WeightUnit.fromRaw(null))
        assertEquals(WeightUnit.kg, WeightUnit.fromRaw("unsupported"))
        assertEquals(WeightUnit.lb, WeightUnit.fromRaw("lb"))
    }

    @Test fun usesExactInternationalPoundAndPreservesSignedTareReadings() {
        assertEquals(0.45359237, WeightUnit.lb.toKg(1.0), 0.0)
        assertEquals(1.0, WeightUnit.lb.fromKg(0.45359237), 0.0)
        assertEquals("-1.1 lb", WeightUnit.lb.text(-0.5))
        for (kg in listOf(-0.5, 0.0, 0.5, 12.345678, 250.0)) {
            assertEquals(kg, WeightUnit.lb.toKg(WeightUnit.lb.fromKg(kg)), 1e-12)
        }
    }

    @Test fun togglingDisplaysDoesNotRequantizeAMeasuredMaxOrChangeItsProvenance() {
        val max = MaxEntryDraft().apply { receiveMeasured(12.3456789, Side.left) }
        repeat(100) {
            WeightUnits.current = if (it % 2 == 0) WeightUnit.lb else WeightUnit.kg
            WeightUnits.number(max.kg)
            assertEquals(12.3456789, max.kg, 0.0)
            assertEquals(MaxSource.measured, max.source)
            assertEquals(Side.left, max.side)
        }
    }

    @Test fun typedDecimalPoundsBecomeCanonicalKgAndRemainExactInRoutineShares() {
        val display = assertNotNull(ValueFieldParser.parse("22,7", 1))
        val kg = WeightUnit.lb.toKg(display)
        val draft = RoutineDraft(plan = SessionPlan(sets = listOf(SetPlan(grip = GripSpec(), targetLoKg = kg, targetHiKg = kg))))
        val original = assertNotNull(BlobCodec.encode(draft))
        WeightUnits.current = WeightUnit.lb
        assertEquals("22.7", WeightUnits.number(draft.plan.sets.first().targetBand!!.start))
        val link = assertNotNull(RoutineShare.url(draft))
        WeightUnits.current = WeightUnit.kg
        val received = RoutineShare.draft(link)
        assertEquals(kg, received.plan.sets.first().targetBand!!.start, 1e-12)
        assertEquals("10.3", WeightUnits.number(received.plan.sets.first().targetBand!!.start))
        assertEquals(original, BlobCodec.encode(draft))
        assertTrue(original.contains("targetLoKg"))
        assertFalse(original.contains("targetLoLb"))
    }

    @Test fun targetBandAndNotificationConvertBothEndpointsButNotTheirDeadline() {
        WeightUnits.current = WeightUnit.lb
        assertEquals("22.0–33.1 lb", WeightUnits.band(10.0..15.0))
        val state = SessionActivityState(GripSpec(), Side.left, SessionActivityPhase.resting, 1, 2,
            targetLoKg = 10.0, targetHiKg = 15.0, endsAtEpochMillis = 50_000, pendingSeconds = null)
        val content = LiveUpdateContent.of(state, 6, 1, 40_000)
        assertTrue(content.text.endsWith("22.0–33.1 lb"))
        assertEquals(50_000L, content.chronometerEndsAtMillis)
        assertEquals(10.0, state.targetLoKg)
    }

    @Test fun localizedUnitTemplatesDoNotRewriteUserSuppliedNames() {
        WeightUnits.current = WeightUnit.lb
        assertEquals("routine kg · 22.0–33.1 lb", WeightUnits.tr("%s · %s–%s kg", "routine kg", "22.0", "33.1"))
        assertEquals("target 22.0 to 33.1 pounds", WeightUnits.tr("target %s to %s kilograms", "22.0", "33.1"))
    }

    @Test fun frenchFormattingAndSpokenUnitsFollowTheLocale() {
        Locale.setDefault(Locale.FRANCE)
        L10n.lookup = { key -> when (key) { "pounds" -> "livres"; "target %s to %s kilograms" -> "cible de %s à %s kilogrammes"; else -> null } }
        WeightUnits.current = WeightUnit.lb
        assertEquals("22,0 lb", WeightUnits.text(10.0))
        assertEquals("cible de 22,0 à 33,1 livres", WeightUnits.tr("target %s to %s kilograms", WeightUnits.number(10.0), WeightUnits.number(15.0)))
    }

    @Test fun shareImageBestPullFollowsTheChosenUnit() {
        val best = ShareCalendarBestPull(10.0, GripSpec(), Side.right, Instant.EPOCH)
        assertTrue(best.line.contains("10.0 KG"))
        WeightUnits.current = WeightUnit.lb
        assertTrue(best.line.contains("22.0 LB"))
        assertEquals(10.0, best.kg)
    }

    @Test fun poundSlidersUseWholeSelectedUnitStepsInsideCanonicalBounds() {
        val maxRange = WeightUnit.lb.sliderRange(0.0..100.0, 0.5)
        assertEquals(0.0..220.0, maxRange)
        val threshold = WeightUnit.lb.sliderRange(0.5..10.0, 0.1)
        assertTrue(WeightUnit.lb.toKg(threshold.start) >= 0.5)
        assertTrue(WeightUnit.lb.toKg(threshold.endInclusive) <= 10.0)
    }
}
