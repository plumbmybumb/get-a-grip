// SPDX-License-Identifier: MPL-2.0
// Original contributions Copyright 2026 Nuri Bruner.

package run.nuri.getagrip.engine

import java.util.Locale
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.fail

/// "Create with AI": the reader is pinned case by case in `Fixtures/agent/`
/// (`AgentRoutineFixtureTests`); these are the rules worth stating in words.
/// Translated from Tests/AgentRoutineTests.swift.
class AgentRoutineTests {

    @BeforeTest
    fun pinLocale() {
        Locale.setDefault(Locale.US)
    }

    /// Fails the test with the reader's own failure.
    private fun read(text: String): AgentRoutine.Reading =
        when (val outcome = AgentRoutine.read(text)) {
            is AgentRoutine.Outcome.Success -> outcome.reading
            is AgentRoutine.Outcome.Failed -> fail("read failed: ${outcome.failure.code}")
        }

    private fun failure(text: String): AgentRoutine.Failure? =
        (AgentRoutine.read(text) as? AgentRoutine.Outcome.Failed)?.failure

    /// The example the instructions hand every assistant must itself read cleanly: an
    /// example the app adjusts teaches every model to write something the app adjusts.
    @Test
    fun theInstructionsOwnExampleReadsWithNoChanges() {
        val reading = read(AgentRoutine.instructions.split("EXAMPLE").last())
        assertEquals(emptyList(), reading.notes)
        val plan = reading.draft.plan
        assertEquals("Recovery 7:3", plan.name)
        assertEquals(7, plan.holdSeconds)
        assertEquals(3, plan.restSeconds)
        assertEquals(TargetBandGate.below, plan.targetBandGate)
        assertFalse(plan.waitForReleaseBeforeRest)
        assertEquals(3, plan.sets.size)
        assertEquals(GripSpec(edgeMM = 20, fingers = FingerSet.frontThree, position = GripPosition.drag), plan.sets[1].grip)
        assertEquals(10, plan.sets[2].holdSeconds)
        assertEquals(10, plan.sets[2].targetMaxSeconds)
        assertEquals(0.6, plan.sets[2].targetLoPercent ?: 0.0, 1e-9)
        assertEquals(0.2, plan.targetLoPercent ?: 0.0, 1e-9)
    }

    /// Imported like a stranger's code: no identity, no reminders switched on.
    @Test
    fun aReadRoutineIsANewRoutineWithRemindersOff() {
        val reading = read("""{"sets": [{"edgeMm": 20, "pulls": 6}]}""")
        assertNull(reading.draft.templateID)
        assertFalse(reading.draft.remindersEnabled)
    }

    /// A routine percentage keeps its basis through `normalized`, which moves it onto the
    /// sets — what the preview shows and the store saves.
    @Test
    fun aRoutineTimedMaxTargetSurvivesNormalizing() {
        val reading = read("""{"target": {"percentOfMax": [80, 90], "max": 7}, "sets": [{"edgeMm": 20, "pulls": 3}]}""")
        val saved = reading.draft.normalized.plan
        assertEquals(7, saved.sets[0].targetMaxSeconds)
        assertEquals(0.9, saved.sets[0].targetHiPercent ?: 0.0, 1e-9)
    }

    /// Out of range is changed AND reported — never refused, never silent.
    @Test
    fun aValueOutOfRangeIsClampedAndReported() {
        val reading = read("""{"holdSeconds": 200, "sets": [{"edgeMm": 140, "pulls": 6}]}""")
        assertEquals(SetPlan.holdRange.last, reading.draft.plan.holdSeconds)
        assertEquals(GripSpec.edgeRange.last, reading.draft.plan.sets[0].grip.edgeMM)
        assertEquals(listOf("hold:200->120", "set1.edge:140->100"), reading.notes.map { it.code })
        assertEquals("Set 1 · Edge: 140 → 100", reading.notes[1].message)
    }

    @Test
    fun thePasteThatIsNotARoutineSaysWhy() {
        assertEquals(AgentRoutine.Failure.noRoutine, failure("Sounds good! Want me to write it?"))
        assertEquals(AgentRoutine.Failure.unreadable, failure("""{"name": "x", "sets": [{"edgeMm": 20"""))
        assertEquals(AgentRoutine.Failure.noSets, failure("""{"sets": []}"""))
        assertEquals(AgentRoutine.Failure.newerFormat, failure("""{"format": "get-a-grip-routine/2", "sets": [{}]}"""))
    }

    /// Kotlin-only: the places Swift's `Character` and number formatting differ from the
    /// JVM's defaults, each of which would make the two readers disagree.
    @Test
    fun theReaderSeesTextAsSwiftDoes() {
        // CRLF is one Character in Swift, so a line comment runs to the next bare "\n".
        assertEquals(listOf("a", "\r\n", "b"), Lenient.characters("a\r\nb"))
        // `rounded()` ties away from zero; `%.2f` rounds the exact binary value.
        assertEquals(-3.0, Lenient.rounded(-2.5))
        assertEquals("0.12", Lenient.format(0.125))
        assertEquals("-0", Lenient.format(-0.001))
        assertEquals("7.5", Lenient.format(7.5))
        // Java parses "1f"; Swift does not, so it stays a word and the number inside is read.
        assertEquals(AgentRoutine.Failure.noSets, failure("{hold: 1f}"))
        assertEquals(10.0, Lenient.seconds(Lenient.Value.Str("10s")))
        assertEquals(120.0, Lenient.seconds(Lenient.Value.Str("2 min")))
    }
}
