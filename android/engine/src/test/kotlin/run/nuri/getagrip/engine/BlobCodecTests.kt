// SPDX-License-Identifier: MPL-2.0
// Original contributions Copyright 2026 Nuri Bruner.

package run.nuri.getagrip.engine

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/// Child collections are blob-encoded `Data`/`String`, not CloudKit relationships, so
/// schema migration becomes forward-compatibility of the decoders. That trade only pays
/// if the decoders are genuinely lenient: a routine that a newer build wrote, or a
/// record a half-finished sync mangled, must degrade to something usable rather than
/// throwing away the whole set list.
///
/// The wire fixtures below are hand-written JSON, not round-trips through our own
/// encoder — an encoder tested against its own decoder agrees with itself about the
/// wrong shape.
///
/// TRANSLATION NOTE (from Tests/BlobCodecTests.swift): three assertions there are about
/// the SwiftData `SessionTemplate` rather than about the codec, and belong to `:app`,
/// not to `:engine` — `testTemplateSetterLeavesThePreviousBlobIntactOnEncodeFailure`,
/// `testSessionTemplateKeepsAnUnknownHandModeRawVerbatim`, and the three
/// `template.setsData = Data()` lines inside
/// `testEmptyDataDecodesToEmptyArrayRatherThanThrowingOrTrapping` (whose codec half is
/// translated below). They are named here rather than faked with a stub model.
class BlobCodecTests {

    // MARK: - Shape of the bytes

    /// The single-value encodings are what keep a blob readable by eye and cheap to
    /// hand-write a fixture for.
    @Test
    fun gripSpecEncodesFlat() {
        val json = BlobCodec.encode(
            GripSpec(edgeMM = 20, fingers = FingerSet.frontTwo, position = GripPosition.halfCrimp)
        )
        assertNotNull(json)

        assertEquals("""{"edgeMM":20,"fingers":"IM","position":"halfCrimp"}""", json)
        assertTrue(json.contains(""""fingers":"IM""""))
        assertFalse(
            json.contains("rawValue"),
            "a nested rawValue box would leak the type into the wire format",
        )
    }

    @Test
    fun reminderTimeEncodesAsABareInt() {
        val json = BlobCodec.encodeAll(
            listOf(ReminderTime(hour = 8, minute = 0), ReminderTime(hour = 19, minute = 0))
        )
        assertEquals("[480,1140]", json)
    }

    // MARK: - Forward and backward compatibility

    /// The blob an older build wrote: only the fields that existed then. Nothing may
    /// throw, and the absent optionals must read as "follow the routine", not as zero.
    @Test
    fun oldBlobMissingNewOptionalFieldsStillDecodes() {
        val wire = """{"id":"8B2C4E8A-0000-4000-8000-000000000001",""" +
            """"grip":{"edgeMM":20,"fingers":"IMRL","position":"halfCrimp"},""" +
            """"repsPerSide":6}"""

        val set = BlobCodec.decode(wire) { SetPlan.fromJson(it) }
        assertNotNull(set)
        assertEquals(6, set.repsPerSide)
        assertEquals("20|IMRL|halfCrimp", set.grip.key)
        assertNull(set.holdSeconds)
        assertNull(set.restSeconds)
        assertNull(set.targetLoKg)
        assertNull(set.targetHiKg)
        assertEquals("", set.note)
        assertFalse(set.overridesTiming)
        assertFalse(set.hasTarget)
        assertNull(set.targetLoPercent)
        assertNull(set.targetHiPercent)
        assertFalse(set.hasPercentTarget, "absent means follow the routine, not 0 %")
    }

    /// A routine blob written before the release gate and the percentage band existed.
    /// The absent flag must read `true` — the routine GAINS the behaviour rather than
    /// keeping the old one, which is the deliberate default and the whole reason a plain
    /// `Boolean` (not a nullable one) is the right shape for it.
    @Test
    fun oldPlanBlobGainsTheReleaseGateAndNoTargetBand() {
        val wire = """{"name":"Daily no-hangs","sets":[],"handMode":"alternateEachRep",""" +
            """"holdSeconds":10,"restSeconds":20,"setBreakSeconds":60,""" +
            """"leadInSeconds":5,"thresholdKg":2}"""

        val plan = BlobCodec.decode(wire) { SessionPlan.fromJson(it) }
        assertNotNull(plan)
        assertTrue(plan.waitForReleaseBeforeRest)
        assertNull(plan.targetPercentBand, "and no target appears out of nowhere")
        assertEquals(20, plan.restSeconds, "the fields that were there are untouched")
    }

    /// Both new fields survive a full round trip, and an out-of-range percentage is
    /// clamped on the way in rather than resolving to a nonsense load.
    @Test
    fun targetPercentagesRoundTripAndClamp() {
        val set = SetPlan(grip = GripSpec(), repsPerSide = 3, targetLoPercent = 0.40, targetHiPercent = 0.45)
        val plan = SessionPlan(
            sets = listOf(set),
            waitForReleaseBeforeRest = false,
            targetLoPercent = 0.17,
            targetHiPercent = 0.22,
        )

        val text = BlobCodec.encode(plan)
        assertNotNull(text)
        val back = BlobCodec.decode(text) { SessionPlan.fromJson(it) }
        assertNotNull(back)
        assertFalse(back.waitForReleaseBeforeRest)
        assertEquals(0.17..0.22, back.targetPercentBand)
        assertEquals(0.40..0.45, back.sets.first().targetPercentBand)

        val absurd = """{"name":"x","sets":[],"targetLoPercent":-2,"targetHiPercent":400}"""
        val clamped = BlobCodec.decode(absurd) { SessionPlan.fromJson(it) }
        assertNotNull(clamped)
        assertEquals(SetPlan.percentRange, clamped.targetPercentBand)
    }

    /// A lenient read, not a typed one: a field a newer build retyped costs that one
    /// field, never the whole routine.
    @Test
    fun fieldWithAWrongTypeFallsBackInsteadOfKillingTheSet() {
        val wire = """{"id":"8B2C4E8A-0000-4000-8000-000000000002",""" +
            """"grip":{"edgeMM":20,"fingers":"IM","position":"openHand"},""" +
            """"repsPerSide":"six","note":"middle two"}"""

        val set = BlobCodec.decode(wire) { SetPlan.fromJson(it) }
        assertNotNull(set)
        assertEquals(6, set.repsPerSide, "the retyped field falls back to its default")
        assertEquals("20|IM|openHand", set.grip.key, "and every other field survives intact")
        assertEquals("middle two", set.note)
    }

    /// `decodeArray`'s whole reason to exist. A single mangled element must cost one
    /// set row, not six.
    @Test
    fun oneStructurallyBrokenElementDoesNotCostTheWholeRoutine() {
        val wire = """[{"id":"8B2C4E8A-0000-4000-8000-000000000003",""" +
            """"grip":{"edgeMM":20,"fingers":"IMRL","position":"halfCrimp"},"repsPerSide":6},""" +
            """"nope",""" +
            """{"id":"8B2C4E8A-0000-4000-8000-000000000004",""" +
            """"grip":{"edgeMM":20,"fingers":"IM","position":"fullCrimp"},"repsPerSide":1}]"""

        val sets = BlobCodec.decodeArray(wire) { SetPlan.fromJson(it) }
        assertEquals(2, sets.size)
        assertEquals(listOf("20|IMRL|halfCrimp", "20|IM|fullCrimp"), sets.map { it.grip.key })
    }

    /// A freshly defaulted record has an empty blob column — the state every routine
    /// passes through between `init` and its first `apply`. (The `SessionTemplate` half
    /// of the Swift test is an `:app` concern; see the note on this class.)
    @Test
    fun emptyDataDecodesToEmptyArrayRatherThanThrowingOrTrapping() {
        assertEquals(emptyList(), BlobCodec.decodeArray("") { SetPlan.fromJson(it) })
        assertNull(BlobCodec.decode("") { SetPlan.fromJson(it) })
    }

    /// A corrupt blob must degrade to a routine somebody can still run, never to a
    /// billion-rep set that hangs the runner or a zero-second hold.
    @Test
    fun outOfRangeNumbersAreClampedOnDecode() {
        val wire = """{"id":"8B2C4E8A-0000-4000-8000-000000000005",""" +
            """"grip":{"edgeMM":0,"fingers":"IMRL","position":"halfCrimp"},""" +
            """"repsPerSide":1000000000,"holdSeconds":0,"restSeconds":100000}"""

        val set = BlobCodec.decode(wire) { SetPlan.fromJson(it) }
        assertNotNull(set)
        assertEquals(100, set.repsPerSide, "SetPlan.repsRange upper bound")
        assertEquals(1, set.holdSeconds, "SetPlan.holdRange lower bound")
        assertEquals(600, set.restSeconds, "SetPlan.restRange upper bound")
        assertEquals(1, set.grip.edgeMM, "GripSpec clamps the edge to 1..100")
    }

    /// Sorted keys make the bytes a pure function of the content. Without that an
    /// unchanged routine re-encodes differently, the store marks the column dirty and
    /// CloudKit syncs a no-op on every save.
    @Test
    fun encodingIsByteStable() {
        val sets = RoutineDraft.starter.plan.sets
        val first = BlobCodec.encodeAll(sets)
        val second = BlobCodec.encodeAll(sets)
        assertNotNull(first)
        assertEquals(first, second)

        // Equal values built separately, not the same instance encoded twice.
        val copy = sets.map { it.copy() }
        assertEquals(first, BlobCodec.encodeAll(copy))

        val grips = BlobCodec.encode(GripSpec())
        assertEquals("""{"edgeMM":20,"fingers":"IMRL","position":"halfCrimp"}""", grips)
    }

    // MARK: - Hand mode: lossy in snapshots, verbatim in the live routine

    /// A WorkoutLog is write-once and display-only, so falling back is safe there:
    /// the alternative is a history row that refuses to open.
    @Test
    fun unknownHandModeInAFrozenSnapshotDecodesToDefaultWithoutThrowing() {
        val wire = """{"handMode":"alternateEveryOtherTuesday","holdSeconds":10,"leadInSeconds":5,""" +
            """"name":"Daily no-hangs","restSeconds":20,"setBreakSeconds":60,"sets":[],""" +
            """"thresholdKg":2}"""

        val plan = BlobCodec.decode(wire) { SessionPlan.fromJson(it) }
        assertNotNull(plan)
        assertEquals(HandMode.alternateEachRep, plan.handMode)
        assertEquals("Daily no-hangs", plan.name)
        assertEquals(10, plan.holdSeconds)
        assertEquals(HandMode.alternateEachRep, HandMode.fallback("alternateEveryOtherTuesday"))
    }

    // MARK: - Leniency primitives

    @Test
    fun clampingIsInclusiveOnBothEnds() {
        assertEquals(3, (3..120).clamping(0))
        assertEquals(120, (3..120).clamping(1000))
        assertEquals(10, (3..120).clamping(10))
        assertEquals(0, (0..20).clamping(-4))
    }
}
