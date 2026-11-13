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

    /// A routine is a few hundred characters; the reader copies its input several times on
    /// the main thread. Past the cap the paste is not read at all — however good the
    /// routine sitting at the front of it.
    @Test
    fun aPasteLongerThanTheCapIsNotRead() {
        assertEquals(131_072, AgentRoutine.maxInputUnits)
        val routine = """{"sets": [{"edgeMm": 20, "pulls": 6}]}"""

        val onTheLine = routine + " ".repeat(AgentRoutine.maxInputUnits - routine.length)
        assertEquals(AgentRoutine.maxInputUnits, onTheLine.length)
        assertEquals(1, read(onTheLine).draft.plan.sets.size)

        assertEquals(AgentRoutine.Failure.unreadable, failure("$onTheLine "))
        assertEquals(AgentRoutine.Failure.unreadable, failure("x".repeat(4_000_000)))
        // UTF-16 units, the one length both platforms have: an astral emoji is two.
        val grin = String(Character.toChars(0x1F600))
        assertEquals(
            AgentRoutine.Failure.unreadable,
            failure(routine + grin.repeat((AgentRoutine.maxInputUnits - routine.length) / 2 + 1)),
        )
    }

    /// A pasted name reaches the same card, notification and CSV row as a scanned one, so
    /// it takes the share link's own cleaning rather than a second, weaker one.
    @Test
    fun aPastedNameIsCleanedLikeAScannedOne() {
        fun named(name: String) =
            read("""{"name": "$name", "sets": [{"edgeMm": 20, "pulls": 6}]}""").draft.plan.name

        assertEquals("Recovery 7:3", named("  Recovery 7:3  "))
        assertEquals("Maxday", named("Max\u202Eday"))
        assertEquals("Line one Line two", named("Line one\\nLine two"))
        assertEquals("a".repeat(60), named("a".repeat(200)))
        // One grapheme cluster, 5,001 scalars: the old cap counted it as one character.
        val stacked = named("e" + "\u0301".repeat(5_000))
        assertEquals(60, stacked.codePointCount(0, stacked.length))
        // Controls are dropped before the count, as on iOS, so they cost none of the sixty.
        assertEquals("a".repeat(60), named("\u202E".repeat(10) + "a".repeat(70)))
        assertEquals(RoutineShare.maxNameScalars, AgentRoutine.maxNameCharacters)
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
        // Foundation's whitespace, measured: a ZERO WIDTH SPACE and a NEXT LINE are trimmed,
        // a unit separator (which `trim()` would take) is not.
        assertEquals("x", Lenient.trimmed("\u200B\u0085 x\u3000\u2029"))
        assertEquals("\u001Fx", Lenient.trimmed("\u001Fx"))
    }
}
