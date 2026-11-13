// SPDX-License-Identifier: MPL-2.0
// Original contributions Copyright 2026 Nuri Bruner.

package run.nuri.getagrip.engine

import java.io.ByteArrayOutputStream
import java.util.Base64
import java.util.UUID
import java.util.zip.Deflater
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/// A shared routine arrives from a CAMERA — a stranger's phone, a printed sheet on a
/// gym wall, a screenshot of a screenshot. So the codec is held to two things at once:
/// a routine that survives the trip must come back exactly as it left, and everything
/// else must fail closed with words a person can read.
///
/// The fixtures below are hand-built JSON pushed through deflate and base64url by this
/// file's own helper, never through `RoutineShare.url(for:)` — an encoder tested against
/// its own decoder agrees with itself about the wrong shape.
class RoutineShareCodecTests {

    /// The smallest legal routine: one set with one pull in it. Every malformed fixture
    /// below varies exactly one thing around this.
    private val oneSetJSON =
        """{"grip":{"edgeMM":20,"fingers":"IMRL","position":"halfCrimp"},"repsPerSide":6}"""

    // MARK: - Round trip

    @Test fun typedBuilderLimitsAndExactBandsSurviveSavingAndSharing() {
        val base = RoutineDraft.starter
        val source = base.copy(plan = base.plan.copy(setBreakSeconds = 900, thresholdKg = 30.0,
            sets = base.plan.sets.mapIndexed { index, set ->
                if (index == 0) set.copy(targetLoPercent = 0.17, targetHiPercent = 0.19) else set
            }))
        val saved = assertNotNull(BlobCodec.decode(assertNotNull(BlobCodec.encode(source))) { RoutineDraft.fromJson(it) })
        val imported = RoutineShare.draft(assertNotNull(RoutineShare.url(saved)))
        for (draft in listOf(saved, imported)) {
            assertEquals(900, draft.plan.setBreakSeconds)
            assertEquals(30.0, draft.plan.thresholdKg)
            assertEquals(0.17, draft.plan.sets[0].targetLoPercent)
            assertEquals(0.19, draft.plan.sets[0].targetHiPercent)
        }
    }

    @Test
    fun shortHoldsSurviveSavingAndSharing() {
        for (seconds in listOf(1, 2)) {
            val base = RoutineDraft.starter
            val source = base.copy(plan = base.plan.copy(
                holdSeconds = seconds,
                sets = base.plan.sets.mapIndexed { index, set ->
                    if (index == 0) set.copy(holdSeconds = seconds) else set
                },
            ))
            val saved = assertNotNull(BlobCodec.decode(assertNotNull(BlobCodec.encode(source))) { RoutineDraft.fromJson(it) })
            assertEquals(seconds, saved.plan.holdSeconds)
            assertEquals(seconds, saved.plan.sets[0].holdSeconds)
            val imported = RoutineShare.draft(assertNotNull(RoutineShare.url(saved)))
            assertEquals(seconds, imported.plan.holdSeconds)
            for (set in imported.plan.sets) {
                assertEquals(seconds, PlanMath.hold(set, imported.plan))
            }
        }
    }

    /// Both shipped prefills, because they exercise opposite corners: `.starter` is six
    /// sets with no targets at all, `.maxDay` is a WHENEVER routine carrying percentage
    /// bands on three of its four sets.
    @Test
    fun theShippedRoutinesRoundTripThroughTheWireUnchanged() {
        for (source in listOf(RoutineDraft.starter, RoutineDraft.maxDay)) {
            val url = assertNotNull(RoutineShare.url(source))
            val imported = RoutineShare.draft(url)

            assertEquals(withoutSetIDs(source.plan), withoutSetIDs(imported.plan),
                "the plan is the payload — every field of it must survive")
            assertEquals(source.sessionsPerDay, imported.sessionsPerDay)
            assertEquals(source.isOnDemand, imported.isOnDemand)
            assertNull(imported.templateID, "an imported routine does not exist yet")
            assertFalse(imported.remindersEnabled,
                "importing must never ambush the recipient with a permission prompt")
            assertEquals(source.sessionsPerDay, imported.reminders.size,
                "the RECIPIENT's default ladder, one time per session a day")
        }
    }

    /// The shipped prefills leave most fields at their defaults, and a comparison of
    /// defaults to defaults cannot catch a field being dropped on the wire. This fixture
    /// sets every optional and every toggle to a NON-default value, so losing any one of
    /// them fails the equality.
    @Test
    fun aRoutineWithEveryFieldOffItsDefaultRoundTrips() {
        val heavy = SetPlan(
            grip = GripSpec(15, FingerSet.frontTwo, GripPosition.fullCrimp),
            repsPerSide = 3,
            holdSeconds = 5,          // per-set overrides, distinct from the plan's own
            restSeconds = 90,
            targetLoKg = 25.0,
            targetHiKg = 30.5,
        )
        val banded = SetPlan(
            grip = GripSpec(45, FingerSet.four, GripPosition.pinch),
            repsPerSide = 4,
            targetLoPercent = 0.55,
            targetHiPercent = 0.72,
        )
        val source = RoutineDraft().let { d ->
            d.copy(
                plan = d.plan.copy(
                    name = "Loaded",
                    sets = listOf(heavy, banded),
                    handMode = HandMode.bothHands,
                    holdSeconds = 7,
                    restSeconds = 33,
                    setBreakSeconds = 45,
                    leadInSeconds = 11,
                    thresholdKg = 3.5,
                    waitForReleaseBeforeRest = false,
                    pausesOutsideTargetBand = false,
                    startingHand = Side.right,
                ),
                sessionsPerDay = 3,
                isOnDemand = true,
            )
        }

        val url = assertNotNull(RoutineShare.url(source))
        val imported = RoutineShare.draft(url)

        assertEquals(withoutSetIDs(source.plan), withoutSetIDs(imported.plan))
        assertEquals(3, imported.sessionsPerDay)
        assertTrue(imported.isOnDemand)
    }

    /// The encoder refuses to mint a code the decoder would refuse to read — a QR that
    /// fails on every phone including the sender's is a failure the sharer can never
    /// learn about, where the null routes into an alert they can.
    @Test
    fun theEncoderRefusesWhatTheDecoderWouldRefuse() {
        val empty = RoutineDraft().let { d -> d.copy(plan = d.plan.copy(sets = emptyList())) }
        assertNull(RoutineShare.url(empty), "no pulls, no code")

        val oversized = RoutineDraft().let { d ->
            d.copy(plan = d.plan.copy(sets = (0 until 51).map { SetPlan(repsPerSide = 1) }))
        }
        assertNull(RoutineShare.url(oversized), "51 sets imports as .tooLarge everywhere")

        // And the caps that TRUNCATE rather than refuse are applied at both ends, so
        // the sharer's screen and the recipient's can never disagree about the name.
        val longNamed = RoutineDraft.starter.let { d -> d.copy(plan = d.plan.copy(name = "B".repeat(200))) }
        val url = assertNotNull(RoutineShare.url(longNamed),
            "a long name is a routine with a long name, not a refusal")
        val imported = RoutineShare.draft(url)
        assertEquals("B".repeat(60), imported.plan.name)
    }

    /// Row identity is the recipient's, not the sender's — the same rule
    /// `RoutineDraft.copying` follows, and for the same reason: two people's routines
    /// sharing a SetPlan id makes one person's reorder move the other's rows.
    @Test
    fun setIDsAreRemintedOnEveryImport() {
        val source = RoutineDraft.starter
        val url = assertNotNull(RoutineShare.url(source))

        val first = RoutineShare.draft(url).plan.sets.map { it.id }.toSet()
        val second = RoutineShare.draft(url).plan.sets.map { it.id }.toSet()

        assertEquals(6, first.size)
        assertEquals(6, second.size)
        assertTrue(first.intersect(second).isEmpty(), "two scans of one code are two routines")
        assertTrue(first.intersect(source.plan.sets.map { it.id }.toSet()).isEmpty(),
            "and neither of them is the sender's")
    }

    /// Sorted keys plus a deterministic compressor means the bytes are a pure function
    /// of the content: a QR printed and stuck on a fingerboard keeps matching the routine
    /// it came from, and this format is testable at all.
    @Test
    fun encodingTheSameRoutineTwiceProducesTheIdenticalURL() {
        val draft = RoutineDraft.starter
        val first = assertNotNull(RoutineShare.url(draft))
        val second = assertNotNull(RoutineShare.url(draft))
        assertEquals(first, second)

        // Set ids ride along, so they are content: a freshly minted `.starter` is a
        // different payload even though it is the same protocol.
        val reminted = assertNotNull(RoutineShare.url(RoutineDraft.starter))
        assertNotEquals(first, reminted)
    }

    /// QR headroom. Nothing enforces this at runtime — it is here so a field added to
    /// `SessionPlan` that bloats the payload shows up as a failing test rather than as a
    /// code nobody's camera can lock onto.
    @Test
    fun theStarterRoutineFitsWellInsideAScannableCode() {
        val url = assertNotNull(RoutineShare.url(RoutineDraft.starter))
        assertTrue(url.length < 900,
            "six sets is the everyday case; a code this size is comfortable at M correction")
    }

    /// The routing predicate and the builder must agree, or every code this build
    /// produces is one link-handling guard away from being ignored.
    @Test
    fun aGeneratedURLIsRecognisedAsARoutineLink() {
        val url = assertNotNull(RoutineShare.url(RoutineDraft.starter))
        assertTrue(RoutineShare.isRoutineLink(url))
        assertTrue(url.startsWith("getagrip://routine#"))
    }

    // MARK: - Forward compatibility

    /// The same trade the blob columns make: a routine from a newer build imports with
    /// the fields this build understands rather than refusing outright.
    @Test
    fun unknownKeysInTheEnvelopeAndInThePlanAreIgnored() {
        val json = """
            {"v":1,"sessionsPerDay":2,"isOnDemand":false,"gauge":"progressor",
            "plan":{"name":"From the future","mood":"crisp","sets":[
            {"grip":{"edgeMM":18,"fingers":"IMR","position":"halfCrimp","texture":"wood"},
            "repsPerSide":4,"tempo":"slow"}]}}
        """.trimIndent().replace("\n", "")

        val imported = RoutineShare.draft(link(json))
        assertEquals("From the future", imported.plan.name)
        assertEquals(1, imported.plan.sets.size)
        assertEquals("18|IMR|halfCrimp", imported.plan.sets[0].grip.key)
        assertEquals(4, imported.plan.sets[0].repsPerSide)
    }

    /// The version gates the ENVELOPE, not the plan — which is why a bump is rare and a
    /// missing version is a payload this format never wrote.
    @Test
    fun theEnvelopeVersionGatesTheWholePayload() {
        assertImportFails(RoutineShareError.newerVersion,
            link("""{"v":999,"plan":{"sets":[$oneSetJSON]}}"""))
        assertImportFails(RoutineShareError.unreadable,
            link("""{"plan":{"sets":[$oneSetJSON]}}"""))
        assertImportFails(RoutineShareError.unreadable,
            link("""{"v":0,"plan":{"sets":[$oneSetJSON]}}"""))
        RoutineShare.draft(link("""{"v":1,"plan":{"sets":[$oneSetJSON]}}"""))
    }

    // MARK: - Damage and foreign links

    /// Everything a camera can hand over that is not a routine. The split matters: a
    /// link that is not ours is routed away silently, while a link that IS ours and
    /// arrived damaged owes the user a sentence.
    @Test
    fun foreignLinksAreNotOursAndDamagedOnesSayWhy() {
        for (foreign in listOf(
            "mailto:nuri@example.com",
            "https://example.com/blog/hangboarding",
            // "routine" as a SUBSTRING of a path is somebody's blog, not our
            // link — the claim is a whole path component or nothing.
            "https://x.com/my-routines/7#abc",
            "https://example.com/routines-of-2027#abc",
            "getagrip://max#YWJj",
        )) {
            assertFalse(RoutineShare.isRoutineLink(foreign), "$foreign is not ours to answer for")
        }
        // While a nested path whose component IS "routine" stays ours — the AASA layout
        // is undecided, so the component may land anywhere in the path.
        assertTrue(RoutineShare.isRoutineLink("https://getagrip.example/app/routine#YWJj"))

        // Ours, with nothing attached at all: somebody typed the scheme.
        assertImportFails(RoutineShareError.notARoutineLink, "getagrip://routine")
        // Ours, with an empty payload: a code that scanned badly.
        assertImportFails(RoutineShareError.unreadable, "getagrip://routine#")
        // Not base64url at all.
        assertImportFails(RoutineShareError.unreadable, "getagrip://routine#not-a-payload!!")
        // Valid base64url of bytes that were never compressed.
        assertImportFails(RoutineShareError.unreadable,
            "getagrip://routine#" + base64url(
                byteArrayOf(0xDE.toByte(), 0xAD.toByte(), 0xBE.toByte(), 0xEF.toByte(), 0x00, 0x11, 0x22, 0x33)))
        // A real payload with its tail missing — the commonest real damage, a QR read
        // from half a screenshot.
        val whole = compressed("""{"v":1,"plan":{"sets":[$oneSetJSON]}}""")
        assertImportFails(RoutineShareError.unreadable,
            "getagrip://routine#" + base64url(whole.copyOfRange(0, whole.size - 10)))
    }

    // MARK: - Untrusted numbers and invariants

    /// Nothing in this file clamps anything: the plan's own decoders already do, and
    /// that is the point — the share format adds no second description of a routine to
    /// keep in step with the first.
    @Test
    fun wildNumbersAreClampedByThePlansOwnDecoders() {
        val json = """
            {"v":1,"plan":{"holdSeconds":9999,"restSeconds":-40,"thresholdKg":0,"sets":[
            {"grip":{"edgeMM":0,"fingers":"IMRL","position":"halfCrimp"},"repsPerSide":99}]}}
        """.trimIndent().replace("\n", "")

        val imported = RoutineShare.draft(link(json))
        assertEquals(120, imported.plan.holdSeconds)
        assertEquals(0, imported.plan.restSeconds)
        assertEquals(0.1, imported.plan.thresholdKg, 0.0001,
            "a zero threshold would read as engaged against sensor noise")
        assertEquals(1, imported.plan.sets[0].grip.edgeMM)
        assertEquals(99, imported.plan.sets[0].repsPerSide, "Valid larger pull counts are not reduced during import")
    }

    /// A pinch IS thumb opposition, so a thumbless one is not a grip anybody can perform
    /// — and the invariant has to hold over a wire somebody else wrote, not just in the
    /// three screens that edit a grip.
    @Test
    fun aPinchArrivesWithItsThumbHoweverItWasSent() {
        val json = """
            {"v":1,"plan":{"sets":[
            {"grip":{"edgeMM":20,"fingers":"IM","position":"pinch"},"repsPerSide":4}]}}
        """.trimIndent().replace("\n", "")

        val imported = RoutineShare.draft(link(json))
        assertTrue(imported.plan.sets[0].grip.fingers.hasThumb)
        assertEquals("20|IMT|pinch", imported.plan.sets[0].grip.key)
    }

    @Test
    fun anOversizedOrEmptyRoutineIsRefused() {
        val fiftyOne = List(51) { oneSetJSON }.joinToString(",")
        assertImportFails(RoutineShareError.tooLarge, link("""{"v":1,"plan":{"sets":[$fiftyOne]}}"""))

        // Fifty is the ceiling, not the wall — a routine sitting on it still imports.
        val fifty = List(50) { oneSetJSON }.joinToString(",")
        RoutineShare.draft(link("""{"v":1,"plan":{"sets":[$fifty]}}"""))

        // A set with no pulls in it is dropped by `executable`, so a routine of nothing
        // but those has nothing to run and says so rather than importing a blank card.
        // This is the ONE shape `.emptyRoutine` still names: the sets arrived intact and
        // every one is zero-rep.
        val dead = """{"grip":{"edgeMM":20,"fingers":"IMRL","position":"halfCrimp"},"repsPerSide":0}"""
        assertImportFails(RoutineShareError.emptyRoutine, link("""{"v":1,"plan":{"sets":[$dead]}}"""))
        // NO sets at all is damage, not a routine — the encoder refuses to build an
        // empty code, so one arriving must have been mangled, and blaming the sharer's
        // routine for it sends the recipient back to a person whose routine is fine.
        assertImportFails(RoutineShareError.unreadable, link("""{"v":1,"plan":{"sets":[]}}"""))
    }

    /// The misclassification a lenient envelope used to make: JSON-layer damage fell
    /// through `SessionPlan()`'s empty defaults and was reported as "a routine with no
    /// pulls in it" — a statement about the SHARER — instead of as a damaged code.
    @Test
    fun jsonLayerDamageReadsAsUnreadableNeverAsAnEmptyRoutine() {
        // The plan key present but mangled into a scalar by a bad scan.
        assertImportFails(RoutineShareError.unreadable, link("""{"v":1,"plan":"nope"}"""))
        // The plan key missing entirely.
        assertImportFails(RoutineShareError.unreadable, link("""{"v":1}"""))
        // And the ordering that keeps `.newerVersion` honest: a future format may
        // reshape the plan itself, so the version verdict must come before the strict
        // plan decode gets a chance to call it damage.
        assertImportFails(RoutineShareError.newerVersion, link("""{"v":999}"""))
    }

    /// An intermediary — a third-party scanner, a link shortener — is allowed to
    /// percent-encode unreserved characters on the way through. The alphabet contains
    /// no character that NEEDS encoding, so decoding is pure tolerance: nothing it
    /// produces could ever have been in a payload the encoder wrote.
    @Test
    fun aPercentEncodedFragmentStillDecodes() {
        val source = RoutineDraft.starter
        val native = assertNotNull(RoutineShare.url(source))
        val fragment = native.substringAfter('#')
        val encoded = "%%%02X".format(fragment[0].code) + fragment.drop(1)
        val mangled = "getagrip://routine#$encoded"

        val imported = RoutineShare.draft(mangled)
        assertEquals(withoutSetIDs(source.plan), withoutSetIDs(imported.plan))
    }

    /// The length gate fires before any string surgery: a link is untrusted input from
    /// a camera or a message, and refusing an absurd one must not first allocate
    /// several copies of it.
    @Test
    fun anAbsurdlyLongFragmentIsRefused() {
        val huge = "A".repeat(1_000_000)
        assertImportFails(RoutineShareError.unreadable, "getagrip://routine#$huge")
    }

    /// Free text from a stranger, capped rather than rejected: a long name is somebody's
    /// routine with a long name.
    @Test
    fun strangersTextIsTrimmedAndCapped() {
        val longName = "A".repeat(200)
        val longNote = "n".repeat(900)
        val json = """
            {"v":1,"plan":{"name":"  $longName  ","sets":[
            {"grip":{"edgeMM":20,"fingers":"IMRL","position":"halfCrimp"},
            "repsPerSide":6,"note":"$longNote"}]}}
        """.trimIndent().replace("\n", "")

        val imported = RoutineShare.draft(link(json))
        assertEquals("A".repeat(60), imported.plan.name)
        assertEquals(1, imported.plan.sets.size, "an old sharer's note key costs nothing")

        // A name that was only whitespace comes back empty — the trim happened — and the
        // store's own `normalized` is what names it on save.
        val blank = RoutineShare.draft(link("""{"v":1,"plan":{"name":"   ","sets":[$oneSetJSON]}}"""))
        assertEquals("", blank.plan.name)
        assertEquals("Daily no-hangs", blank.normalized.plan.name)
    }

    // MARK: - A stranger's link, held to its size and its vocabulary

    /// The two numbers in a routine the decoder used to take on trust. A band of 1e30 kg
    /// read as a 2e28-fold intensity against any max on file; now it is a band at the
    /// ceiling, which every screen that divides by, multiplies by or prints it survives.
    @Test
    fun kilogramTargetsAreClampedAtDecode() {
        fun set(lo: String, hi: String) = """{"v":1,"plan":{"sets":[
            {"grip":{"edgeMM":20,"fingers":"IMRL","position":"halfCrimp"},"repsPerSide":6,
            "targetLoKg":$lo,"targetHiKg":$hi}]}}""".trimIndent().replace("\n", "")

        val absurd = RoutineShare.draft(link(set("1e30", "1e30"))).plan.sets[0]
        assertEquals(1000.0, absurd.targetLoKg)
        assertEquals(1000.0, absurd.targetHiKg)

        val negative = RoutineShare.draft(link(set("-5", "12.5"))).plan.sets[0]
        assertEquals(0.0, negative.targetLoKg)
        assertEquals(12.5, negative.targetHiKg, "a real band is left exactly as it was written")

        // Past what a double holds is not a big number, it is no number: the endpoint is
        // absent, exactly as if the key had never been written.
        val overflowing = RoutineShare.draft(link(set("1e999", "20"))).plan.sets[0]
        assertNull(overflowing.targetLoKg)
        assertEquals(20.0, overflowing.targetHiKg)

        // EVERY decode, not only a link's: a routine stored before the bound existed
        // heals the next time its blob is read.
        val stored = BlobCodec.decodeArray(
            """[{"grip":{"edgeMM":20,"fingers":"IMRL","position":"halfCrimp"},""" +
                """"repsPerSide":6,"targetLoKg":1e30,"targetHiKg":1e30}]""",
        ) { SetPlan.fromJson(it) }
        assertEquals(1000.0, stored.single().targetLoKg)
        assertEquals(1000.0, stored.single().targetHiKg)
        assertEquals(0.0..1000.0, SetPlan.kgRange)
    }

    /// The plan decoder keeps a position VERBATIM so a newer build's vocabulary survives —
    /// which is right for a blob this app wrote and wrong for a stranger's. A position
    /// from a link has to be a WORD.
    @Test
    fun aGripPositionFromALinkMustBeAVocabularyWord() {
        fun withPosition(position: String) =
            link("""{"v":1,"plan":{"sets":[{"grip":{"edgeMM":20,"fingers":"IMRL",""" +
                """"position":"$position"},"repsPerSide":6}]}}""")

        assertImportFails(RoutineShareError.unreadable, withPosition("a".repeat(5_000)))
        // A separator would forge a `GripSpec.key` (`20|IMRL|half|crimp`) or a CSV column.
        assertImportFails(RoutineShareError.unreadable, withPosition("half/crimp"))
        assertImportFails(RoutineShareError.unreadable, withPosition("half|crimp"))
        assertImportFails(RoutineShareError.unreadable, withPosition("half crimp"))
        assertImportFails(RoutineShareError.unreadable, withPosition("half,crimp"))
        assertImportFails(RoutineShareError.unreadable, withPosition(""))
        assertImportFails(RoutineShareError.unreadable, withPosition("2finger"))
        assertImportFails(RoutineShareError.unreadable, withPosition("crimpé"))
        // A regex `$` would let this one through: it matches before a final line break.
        assertImportFails(RoutineShareError.unreadable, withPosition("halfCrimp\\n"))
        assertImportFails(RoutineShareError.unreadable, withPosition("a".repeat(33)))

        // The cap is 32, and a position sitting on it still imports.
        assertEquals("a".repeat(32), RoutineShare.draft(withPosition("a".repeat(32))).plan.sets[0].grip.position.rawValue)

        // An unknown-but-well-formed position is a newer build's word, not an attack: it
        // imports, keys, and goes back out on the wire unchanged.
        val sloper = RoutineShare.draft(withPosition("sloper"))
        assertEquals("20|IMRL|sloper", sloper.plan.sets[0].grip.key)
        val again = RoutineShare.draft(assertNotNull(RoutineShare.url(sloper)))
        assertEquals(GripPosition("sloper"), again.plan.sets[0].grip.position)
    }

    /// The other string kept verbatim: what a percentage is OF, on a set and on the plan.
    @Test
    fun aTargetBasisFromALinkMustBeAVocabularyWord() {
        fun onSet(basis: String) = link(
            """{"v":1,"plan":{"sets":[{"grip":{"edgeMM":20,"fingers":"IMRL","position":"halfCrimp"},""" +
                """"repsPerSide":6,"targetLoPercent":0.7,"targetHiPercent":0.8,"targetBasis":"$basis"}]}}""",
        )
        fun onPlan(basis: String) = link(
            """{"v":1,"plan":{"targetLoPercent":0.7,"targetHiPercent":0.8,"targetBasis":"$basis","sets":[$oneSetJSON]}}""",
        )

        for (bad in listOf("critical force", "critical/force", "x".repeat(5_000), "9lives")) {
            assertImportFails(RoutineShareError.unreadable, onSet(bad))
            assertImportFails(RoutineShareError.unreadable, onPlan(bad))
        }

        assertEquals("criticalForce", RoutineShare.draft(onSet("criticalForce")).plan.sets[0].targetBasisRaw)
        assertEquals("criticalForce", RoutineShare.draft(onPlan("criticalForce")).plan.targetBasisRaw)
        // A basis this build has never heard of still survives verbatim, as it always did.
        assertEquals("anaerobicCapacity", RoutineShare.draft(onSet("anaerobicCapacity")).plan.sets[0].targetBasisRaw)
        // An empty basis carries nothing and reads as absent, so there is nothing to refuse.
        assertNull(RoutineShare.draft(onSet("")).plan.sets[0].targetBasisRaw)
    }

    /// The encoder half of the same rule: a draft the decoder would refuse makes no code.
    @Test
    fun theEncoderRefusesAPositionOrBasisTheDecoderWouldRefuse() {
        fun draft(set: SetPlan, basis: String? = null) = RoutineDraft().let { d ->
            d.copy(plan = d.plan.copy(sets = listOf(set), targetBasisRaw = basis))
        }

        assertNull(RoutineShare.url(draft(SetPlan(grip = GripSpec(position = GripPosition("half/crimp"))))))
        assertNull(RoutineShare.url(draft(SetPlan(grip = GripSpec(position = GripPosition("a".repeat(33)))))))
        assertNull(RoutineShare.url(draft(SetPlan(grip = GripSpec(position = GripPosition(""))))))
        assertNull(RoutineShare.url(draft(SetPlan(targetBasisRaw = "not a word"))))
        assertNull(RoutineShare.url(draft(SetPlan(), basis = "not a word")))
        // A ZERO-rep set travels too, so its strings are held to the same rule.
        assertNull(RoutineShare.url(draft(SetPlan()).let { d ->
            d.copy(plan = d.plan.copy(sets = d.plan.sets +
                SetPlan(grip = GripSpec(position = GripPosition("half crimp")), repsPerSide = 0)))
        }))

        assertNotNull(RoutineShare.url(draft(SetPlan(grip = GripSpec(position = GripPosition("sloper"))))))
        assertNotNull(RoutineShare.url(draft(SetPlan().withTargetBasis(TargetBasis.criticalForce))))
    }

    /// Nesting is refused by COUNTING, before a parser recurses into it: a 59-character
    /// link of nothing but brackets once overflowed the stack. Depth is how many `[` and
    /// `{` are open at once, the envelope's own brace included.
    @Test
    fun nestingDeeperThanSixtyFourIsUnreadableWithoutBeingParsed() {
        fun nested(arrays: Int) = link(
            """{"v":1,"plan":{"sets":[$oneSetJSON]},"future":""" +
                "[".repeat(arrays) + "]".repeat(arrays) + "}",
        )

        // 1 (the envelope) + 63 = 64: the ceiling, not the wall.
        assertEquals(1, RoutineShare.draft(nested(63)).plan.sets.size)
        // 1 + 64 = 65.
        assertImportFails(RoutineShareError.unreadable, nested(64))
        // The attack itself: tens of kilobytes of brackets in a few dozen characters.
        assertImportFails(RoutineShareError.unreadable, nested(15_000))
        assertImportFails(
            RoutineShareError.unreadable,
            "getagrip://routine#" + base64url(compressed("[".repeat(30_000))),
        )

        // Brackets inside a STRING are text, however many there are — including after an
        // escaped quote, which does not end the string.
        val quoted = link(
            """{"v":1,"plan":{"name":"\"${"[{".repeat(100)}","sets":[$oneSetJSON]}}""",
        )
        assertEquals(
            ("\"" + "[{".repeat(100)).take(RoutineShare.maxNameScalars),
            RoutineShare.draft(quoted).plan.name,
            "read as a name (and capped like one), never counted as structure",
        )
    }

    /// The decompressed cap is a backstop sized to the payload: the largest legal routine
    /// inflates to about 6 KB.
    @Test
    fun aPayloadThatInflatesPastThirtyTwoKilobytesIsUnreadable() {
        fun padded(bytes: Int): String {
            val frame = """{"v":1,"plan":{"sets":[$oneSetJSON]},"pad":""}"""
            return link(frame.replace("\"pad\":\"\"", "\"pad\":\"${"a".repeat(bytes - frame.length)}\""))
        }

        assertEquals(32 * 1024, RoutineShare.maxDecompressedBytes)
        // Exactly on the cap still reads; one byte over, and a clean 33 KB, do not.
        assertEquals(1, RoutineShare.draft(padded(32 * 1024)).plan.sets.size)
        assertImportFails(RoutineShareError.unreadable, padded(32 * 1024 + 1))
        assertImportFails(RoutineShareError.unreadable, padded(33 * 1024))
    }

    /// The whole link is measured before anything is derived from it. The https form lets
    /// the test pad a WORKING link to either side of the line.
    @Test
    fun aLinkLongerThanTwentyKilobytesIsRefusedBeforeItIsParsed() {
        val payload = assertNotNull(RoutineShare.url(RoutineDraft.starter)).substringAfter('#')
        fun ofLength(length: Int): String {
            val head = "https://example.com/"
            val tail = "/routine#$payload"
            return head + "a".repeat(length - head.length - tail.length) + tail
        }

        assertEquals(20 * 1024, RoutineShare.maxLinkLength)
        val onTheLine = ofLength(RoutineShare.maxLinkLength)
        assertEquals(RoutineShare.maxLinkLength, onTheLine.length)
        assertEquals(6, RoutineShare.draft(onTheLine).plan.sets.size)
        assertImportFails(RoutineShareError.unreadable, ofLength(RoutineShare.maxLinkLength + 1))
        // The iOS suite's own case: a fragment a scanner percent-encoded three for one.
        assertImportFails(RoutineShareError.unreadable, "getagrip://routine#" + "%41".repeat(7_000))

        // Before ANY other work: an over-long string is refused as unreadable without
        // first being parsed to find out whether it was a routine link at all.
        assertImportFails(RoutineShareError.unreadable, "x".repeat(RoutineShare.maxLinkLength + 1))
        assertImportFails(RoutineShareError.notARoutineLink, "x".repeat(RoutineShare.maxLinkLength))
    }

    // MARK: - The name is text

    /// A routine's name is its title on the card, a notification and a CSV row. Bidi
    /// controls reorder what surrounds them — how a name dresses itself as something
    /// else — so they are dropped, every one.
    @Test
    fun bidiControlsAreDroppedFromAName() {
        assertEquals("Dailygnp.exe", RoutineShare.sanitizedName("Daily\u202Egnp.exe"))
        val everyControl = listOf(
            0x061C, 0x200E, 0x200F, 0x202A, 0x202B, 0x202C, 0x202D, 0x202E,
            0x2066, 0x2067, 0x2068, 0x2069,
        )
        for (control in everyControl) {
            val name = "Max" + String(Character.toChars(control)) + "day"
            assertEquals("Maxday", RoutineShare.sanitizedName(name), "U+%04X".format(control))
        }
        // Its neighbours on either side are ordinary characters and stay.
        assertEquals("a\u2065b", RoutineShare.sanitizedName("a\u2065b"))
        assertEquals("a\u202Fb", RoutineShare.sanitizedName("a\u202Fb"))
    }

    /// A title is one line everywhere it is drawn.
    @Test
    fun controlCharactersAndLineBreaksInANameBecomeASpace() {
        assertEquals("Line one Line two", RoutineShare.sanitizedName("Line one\nLine two"))
        assertEquals("a  b", RoutineShare.sanitizedName("a\r\nb"), "two controls, two spaces")
        for (control in listOf(0x00, 0x09, 0x1B, 0x1F, 0x7F, 0x85, 0x9F, 0x2028, 0x2029)) {
            val name = "a" + String(Character.toChars(control)) + "b"
            assertEquals("a b", RoutineShare.sanitizedName(name), "U+%04X".format(control))
        }
        // …and one at either end is then trimmed away with the rest of the whitespace.
        assertEquals("Daily", RoutineShare.sanitizedName("\n\tDaily\u0000\u2028"))
        // The iOS suite's own three, so the two engines are pinned to the same strings.
        assertEquals("Max day for two", RoutineShare.sanitizedName("Max\u202E day\nfor\u2028two\u200F"))
        assertEquals("Daily  no-hangs", RoutineShare.sanitizedName("  \u0007Daily\u0000 no-hangs\r\n "))
        assertEquals("R\u00E9glette 20 mm \u00B7 \u4E0A", RoutineShare.sanitizedName("R\u00E9glette 20 mm \u00B7 \u4E0A"))
        // Either side of the two ranges is left alone.
        assertEquals("a~b", RoutineShare.sanitizedName("a~b"))
        assertEquals("a\u00A1b", RoutineShare.sanitizedName("a\u00A1b"))
    }

    /// Sixty SCALARS. Counted in UTF-16 units the cap could cut an emoji in half; counted
    /// in grapheme clusters one base letter carried thousands of combining marks through.
    @Test
    fun aNameIsCappedAtSixtyCodePointsWithoutSplittingOne() {
        assertEquals(60, RoutineShare.maxNameScalars)

        // The 60th scalar is an astral emoji: two UTF-16 units, kept whole.
        val grin = String(Character.toChars(0x1F600))
        val emoji = RoutineShare.sanitizedName("a".repeat(59) + grin + "b".repeat(10))
        assertEquals("a".repeat(59) + grin, emoji)
        assertEquals(60, emoji.codePointCount(0, emoji.length))
        assertEquals(61, emoji.length)
        // Sixty of them: 120 units, and still not one more scalar than sixty.
        val allEmoji = RoutineShare.sanitizedName(grin.repeat(200))
        assertEquals(grin.repeat(60), allEmoji)

        // One base letter under 5,000 combining acute accents is ONE grapheme cluster.
        val stacked = RoutineShare.sanitizedName("e" + "\u0301".repeat(5_000))
        assertEquals(60, stacked.codePointCount(0, stacked.length))
        assertEquals("e" + "\u0301".repeat(59), stacked)

        // The cut can expose a space; the second trim takes it.
        assertEquals("a".repeat(59), RoutineShare.sanitizedName("a".repeat(59) + " " + "b".repeat(10)))
        // Dropped and replaced characters are gone BEFORE the count, so they cost nothing.
        assertEquals("a".repeat(60), RoutineShare.sanitizedName("\u202E".repeat(30) + "a".repeat(70)))
    }

    /// The trim is Foundation's set, not `trim()`'s: iOS removes a ZERO WIDTH SPACE at
    /// either end of a name, and a recipient must not see a different name than the sender.
    @Test
    fun aNameIsTrimmedExactlyWhereIOSTrimsIt() {
        assertEquals("Name", RoutineShare.sanitizedName("\u200BName\u200B"))
        assertEquals("Name", RoutineShare.sanitizedName("\u00A0\u3000 Name \u2003\u205F"))
        // An ordinary name is untouched, and cleaning a clean name changes nothing.
        for (name in listOf("Daily no-hangs", "Séance du matin", "7:3 × 6", "日课", "")) {
            assertEquals(name, RoutineShare.sanitizedName(name))
            assertEquals(name, RoutineShare.sanitizedName(RoutineShare.sanitizedName(name)))
        }
    }

    /// Applied at BOTH ends, so the sharer's screen and the recipient's cannot disagree.
    @Test
    fun aNameIsCleanedOnTheWayOutAndOnTheWayIn() {
        val dressed = RoutineDraft.starter.let { d ->
            d.copy(plan = d.plan.copy(name = "  Max\u202E day\nplan  "))
        }
        val outbound = RoutineShare.draft(assertNotNull(RoutineShare.url(dressed)))
        assertEquals("Max day plan", outbound.plan.name)

        // A code some other encoder wrote: the JSON escapes decode to the raw characters.
        val inbound = RoutineShare.draft(
            link("""{"v":1,"plan":{"name":"Max\u202E day\nplan","sets":[$oneSetJSON]}}"""),
        )
        assertEquals("Max day plan", inbound.plan.name)
    }

    // MARK: - The future universal link

    /// Today's build must read the link tomorrow's build hands out, or every code shared
    /// across the changeover dies at the App Store fallback.
    @Test
    fun theHTTPSFormDecodesIdentically() {
        val source = RoutineDraft.starter
        val native = assertNotNull(RoutineShare.url(source))
        val payload = native.substringAfter('#')
        val web = "https://example.com/routine#$payload"

        assertTrue(RoutineShare.isRoutineLink(web))
        val imported = RoutineShare.draft(web)
        assertEquals(withoutSetIDs(source.plan), withoutSetIDs(imported.plan))
    }

    // MARK: - Hand-building the wire

    /// JSON → deflate → base64url → URL, written out here rather than borrowed from the
    /// codec: these tests are the only independent statement of what the format IS.
    private fun link(json: String, host: String = "getagrip://routine"): String =
        host + "#" + base64url(compressed(json))

    /// RAW deflate (RFC 1951, `nowrap`) — the framing Apple's `.zlib` actually writes.
    private fun compressed(json: String): ByteArray {
        val deflater = Deflater(Deflater.BEST_COMPRESSION, true)
        try {
            deflater.setInput(json.toByteArray(Charsets.UTF_8))
            deflater.finish()
            val out = ByteArrayOutputStream()
            val buffer = ByteArray(4096)
            while (!deflater.finished()) {
                val n = deflater.deflate(buffer)
                if (n == 0 && deflater.needsInput()) break
                out.write(buffer, 0, n)
            }
            return out.toByteArray()
        } finally {
            deflater.end()
        }
    }

    private fun base64url(bytes: ByteArray): String =
        Base64.getEncoder().encodeToString(bytes)
            .replace("+", "-")
            .replace("/", "_")
            .trimEnd('=')

    /// Set ids are reminted on purpose, so plan equality is asserted around them.
    private fun withoutSetIDs(plan: SessionPlan): SessionPlan {
        val zero = UUID.fromString("00000000-0000-0000-0000-000000000000")
        return plan.copy(sets = plan.sets.map { it.copy(id = zero) })
    }

    private fun assertImportFails(expected: RoutineShareError, url: String) {
        val error = assertFailsWith<RoutineShareError> { RoutineShare.draft(url) }
        assertSame(expected, error)
    }
}
