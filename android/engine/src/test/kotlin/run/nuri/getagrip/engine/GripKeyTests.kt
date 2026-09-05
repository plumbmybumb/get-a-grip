// SPDX-License-Identifier: MPL-2.0
// Original contributions Copyright 2026 Nuri Bruner.

package run.nuri.getagrip.engine

import java.text.NumberFormat
import java.util.Locale
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/// The canonical grip key is the only thing joining a rep pulled in March to one
/// pulled in December and to the MaxRecord that says what 25 % means for it. There is
/// no grip library to register in, so the key IS the registry — which makes silently
/// merging two different grips into one trend series, or forking one grip into two,
/// the worst corruption this app can inflict on history.
///
/// Every expectation below is a hand-written literal. A test that rebuilt the key with
/// the same string interpolation the type uses would happily accept a reformat.
/// Translated from Tests/GripKeyTests.swift.
class GripKeyTests {

    /// The display copy below is English (`L10n` unset) and `L10n.tr` formats through
    /// the DEFAULT locale, so the locale is pinned rather than inherited from whatever
    /// machine runs the suite.
    @BeforeTest
    fun pinLocale() {
        Locale.setDefault(Locale.US)
    }

    // MARK: - The key itself

    @Test
    fun canonicalKeyIsExactlyThreePipeJoinedFields() {
        val spec = GripSpec(edgeMM = 20, fingers = FingerSet.frontTwo, position = GripPosition.fullCrimp)
        assertEquals("20|IM|fullCrimp", spec.key)
        assertEquals(
            "20|IM|halfCrimp",
            GripSpec(edgeMM = 20, fingers = FingerSet.frontTwo, position = GripPosition.halfCrimp).key,
        )
        assertEquals(3, spec.key.split("|").size)
    }

    /// The prefill is a product spec, so its keys get pinned like one: these six
    /// strings are what every future log, trend and max for Nuri's protocol will join on.
    @Test
    fun allSixPrefillKeysArePinned() {
        assertEquals(
            listOf(
                "20|IMRL|halfCrimp",
                "20|IMR|halfCrimp",
                "20|IM|openHand",
                "20|MR|openHand",
                "20|IM|fullCrimp",
                "20|MR|fullCrimp",
            ),
            RoutineDraft.starter.plan.sets.map { it.grip.key },
        )
    }

    /// `edgeMM` is an Int and that is load-bearing, not a style choice: the key is a
    /// serialization, and Double formatting is locale-dependent, so a French phone and
    /// an American one would fork one trend series into three.
    @Test
    fun edgeIsFormattedAsAPlainIntegerUnderAnyLocale() {
        val spec = GripSpec(edgeMM = 20, fingers = FingerSet.four, position = GripPosition.halfCrimp)

        // What the same edge would look like if it were ever "improved" to a Double.
        val french = NumberFormat.getNumberInstance(Locale.FRANCE).apply {
            minimumFractionDigits = 1
            maximumFractionDigits = 1
        }.format(20.0)
        assertEquals("20,0", french)

        assertEquals("20|IMRL|halfCrimp", spec.key)
        assertFalse(spec.key.contains(","), "a decimal comma in the key forks the series")
        assertFalse(spec.key.contains("."), "a decimal point in the key forks the series")
    }

    /// The schema-level expression of "do not add a grip library": two sets built at
    /// unrelated call sites are the same series with nothing to register.
    @Test
    fun independentlyBuiltIdenticalSpecsShareKeyAndHash() {
        val fromBuilder = GripSpec(
            edgeMM = 20,
            fingers = FingerSet.of(listOf(FingerSet.index, FingerSet.middle, FingerSet.ring)),
            position = GripPosition.halfCrimp,
        )
        val fromPrefill = RoutineDraft.starter.plan.sets[1].grip

        assertEquals(fromBuilder, fromPrefill)
        assertEquals(fromBuilder.key, fromPrefill.key)
        assertEquals(fromBuilder.hashCode(), fromPrefill.hashCode())
        assertEquals(1, setOf(fromBuilder, fromPrefill).size)
    }

    @Test
    fun differingInAnySingleFieldProducesADifferentKey() {
        val keys = listOf(
            GripSpec(20, FingerSet.frontTwo, GripPosition.halfCrimp).key,
            GripSpec(20, FingerSet.frontTwo, GripPosition.fullCrimp).key,
            GripSpec(18, FingerSet.frontTwo, GripPosition.halfCrimp).key,
            GripSpec(20, FingerSet.middleTwo, GripPosition.halfCrimp).key,
        )
        assertEquals(
            listOf("20|IM|halfCrimp", "20|IM|fullCrimp", "18|IM|halfCrimp", "20|MR|halfCrimp"),
            keys,
        )
        assertEquals(4, keys.toSet().size, "two different grips must never share a trend series")
    }

    // MARK: - FingerSet

    /// The token is the wire format, so it is written in pip order regardless of how
    /// the set was assembled.
    @Test
    fun fingerTokenIsFixedOrderRegardlessOfConstructionOrder() {
        assertEquals("IR", FingerSet.of(listOf(FingerSet.ring, FingerSet.index)).token)
        assertEquals("IR", FingerSet.of(listOf(FingerSet.index, FingerSet.ring)).token)
        assertEquals("IMRL", FingerSet.four.token)
        assertEquals(
            "IMRL",
            FingerSet.of(listOf(FingerSet.little, FingerSet.ring, FingerSet.middle, FingerSet.index)).token,
        )
        assertEquals("IMRLT", FingerSet.letters)
    }

    /// An OptionSet over exactly four bits means every representable value is a real
    /// grip — there is no "unknown value from a newer build" to fall back from, which
    /// is why FingerSet needs none of GripPosition's extensibility machinery.
    @Test
    fun fingerSetTokenRoundTripsForAllFifteenCombinations() {
        val seen = mutableSetOf<String>()
        for (raw in 1..15) {
            val set = FingerSet(raw)
            assertEquals(set, FingerSet.fromToken(set.token), "round-trip failed for raw $raw")
            assertTrue(seen.add(set.token), "token collision on raw $raw")
        }
        assertEquals(15, seen.size)
    }

    @Test
    fun rawValueMasksToTheFiveRealDigits() {
        // Unknown bits cannot exist, so nothing downstream ever has to defend against
        // one. Bit four is the THUMB now, so it is real and survives the mask.
        assertEquals(
            FingerSet.of(listOf(FingerSet.index, FingerSet.middle, FingerSet.thumb)),
            FingerSet(0b1_0000 or 0b0011),
        )
        assertEquals(
            FingerSet.of(
                listOf(FingerSet.index, FingerSet.middle, FingerSet.ring, FingerSet.little, FingerSet.thumb)
            ),
            FingerSet(Int.MAX_VALUE),
        )
        assertEquals(0, FingerSet(0b10_0000).rawValue, "bit five dies at the mask")
    }

    @Test
    fun unknownFingerLettersAreIgnoredAndEmptyFallsBackToFour() {
        assertEquals(FingerSet.of(listOf(FingerSet.index, FingerSet.middle)), FingerSet.fromToken("IXM"))
        assertEquals(FingerSet.four, FingerSet.fromToken(""))
        // All-unknown reduces to empty, which is not a grip anybody can pull.
        assertEquals(FingerSet.four, FingerSet.fromToken("ZZZ"))
    }

    /// Naming a set of two "Front 2" when it is index + ring would be a lie printed on
    /// a set row, so composites name themselves instead.
    @Test
    fun compositeFingerSetNamesItselfRatherThanLying() {
        val indexAndRing = FingerSet.of(listOf(FingerSet.index, FingerSet.ring))
        assertEquals("Index + ring", indexAndRing.name)
        assertEquals("Front 2", FingerSet.frontTwo.name)
        assertEquals("4 fingers", FingerSet.four.name)
        assertEquals("Back 3", FingerSet.backThree.name)
        assertEquals("Middle", FingerSet.middle.name)
        assertEquals("IR", indexAndRing.shortName)
        assertEquals("F2", FingerSet.frontTwo.shortName)
    }

    @Test
    fun occupiedFlagsRunIndexToLittle() {
        // FingerGlyph and FingerPips draw straight off this, so the order is contractual.
        assertEquals(listOf(true, true, false, false), FingerSet.frontTwo.occupied)
        assertEquals(listOf(false, false, true, true), FingerSet.backTwo.occupied)
        assertEquals(4, FingerSet.four.count)
        assertEquals(2, FingerSet.middleTwo.count)
    }

    // MARK: - GripPosition (extensible on purpose)

    /// The CloudKit-skew data-loss test. Two devices sync the same routine; the older
    /// build must hand back exactly what the newer one wrote. This test fails the
    /// moment GripPosition becomes an enum.
    @Test
    fun unknownGripPositionRawSurvivesDecodeAndReEncode() {
        // Written in sorted-key order with no whitespace so the bytes are directly
        // comparable to what BlobCodec produces.
        //
        // "sloper", not "pinch". This test's subject is a position raw this build has
        // never heard of, and `pinch` stopped being one the moment it gained a model
        // invariant — a pinch now pulls the thumb in on decode, which is a DELIBERATE
        // rewrite and would have quietly turned this into a test of that instead.
        val wire = """{"edgeMM":20,"fingers":"IM","position":"sloper"}"""

        val decoded = BlobCodec.decode(wire) { GripSpec.fromJson(it) }
        assertNotNull(decoded)
        assertEquals("sloper", decoded.position.rawValue)
        assertEquals("20|IM|sloper", decoded.key)

        val reEncoded = BlobCodec.encode(decoded)
        assertEquals(wire, reEncoded, "a round trip through this build must not rewrite the record")
    }

    // MARK: - A pinch is thumb opposition

    /// THE RULE (Nuri, 2026-08-04): "there's no world where you can pinch without the
    /// thumb". So the app must not be able to REPRESENT one — enforced in the model, not
    /// in the three screens that edit a grip, because a rule that lives in a view is a
    /// rule the next view forgets.
    @Test
    fun aPinchAlwaysCarriesTheThumb() {
        // Through the constructor, which is the one door — see the translation note on
        // `GripSpec`, where Swift instead needs an init shadowing the memberwise one.
        val built = GripSpec(edgeMM = 20, fingers = FingerSet.frontTwo, position = GripPosition.pinch)
        assertTrue(built.fingers.hasThumb)
        assertEquals("20|IMT|pinch", built.key)

        // And through mutation, in both orders.
        val open = GripSpec(edgeMM = 20, fingers = FingerSet.frontTwo, position = GripPosition.openHand)
        assertFalse(open.fingers.hasThumb)
        val switched = open.withPosition(GripPosition.pinch)
        assertTrue(switched.fingers.hasThumb, "choosing pinch pulls the thumb in")

        val stripped = GripSpec(edgeMM = 20, fingers = FingerSet.frontTwo, position = GripPosition.pinch)
            .withFingers(FingerSet.frontTwo) // an attempt to take the thumb back out
        assertTrue(stripped.fingers.hasThumb, "and it cannot be taken back out")
    }

    /// Every other position leaves the thumb alone — the rule is one-directional. A
    /// thumb-assisted open hand is a real grip and must stay representable.
    @Test
    fun onlyPinchForcesTheThumb() {
        for (position in listOf(
            GripPosition.halfCrimp, GripPosition.openHand, GripPosition.fullCrimp, GripPosition.drag
        )) {
            val spec = GripSpec(edgeMM = 20, fingers = FingerSet.frontTwo, position = position)
            assertFalse(spec.fingers.hasThumb, "${position.rawValue} must not add one")
        }
        val thumbed = GripSpec(edgeMM = 20, fingers = FingerSet.frontTwo, position = GripPosition.openHand)
            .let { it.withFingers(it.fingers.union(FingerSet.thumb)) }
        assertTrue(thumbed.fingers.hasThumb, "and the thumb stays where it was asked for")
    }

    /// A blob written before the rule existed self-heals on read. This DOES change its
    /// key, so a max recorded against the thumbless form stops joining — acceptable only
    /// because `pinch` is days old and unshipped, and stated here so the next person to
    /// add an invariant knows what it costs on data that HAS shipped.
    @Test
    fun aThumblessPinchBlobSelfHealsOnDecode() {
        val wire = """{"edgeMM":20,"fingers":"IM","position":"pinch"}"""
        val decoded = BlobCodec.decode(wire) { GripSpec.fromJson(it) }
        assertNotNull(decoded)
        assertTrue(decoded.fingers.hasThumb)
        assertEquals("20|IMT|pinch", decoded.key, "the key moves — deliberately")
    }

    @Test
    fun unknownGripPositionDisplaysItselfAndClosureIsNeutral() {
        val sloper = GripPosition("sloper")
        assertEquals("sloper", sloper.name)
        assertEquals("SLOPER", sloper.shortName)
        // Neutral, so the glyph draws a half-closed hand rather than guessing straight
        // fingers or a full crimp for something it has never heard of.
        assertEquals(0.5, sloper.closure, 0.0001)
    }

    @Test
    fun knownPositionsCarryTheFrozenChipOrderAndClosures() {
        assertEquals(
            listOf(
                GripPosition.halfCrimp, GripPosition.openHand, GripPosition.fullCrimp,
                GripPosition.drag, GripPosition.pinch, GripPosition.fingerCurl,
            ),
            GripPosition.known,
        )
        assertEquals(0.0, GripPosition.drag.closure, 0.0001)
        assertEquals(0.2, GripPosition.openHand.closure, 0.0001)
        assertEquals(0.65, GripPosition.halfCrimp.closure, 0.0001)
        assertEquals(1.0, GripPosition.fullCrimp.closure, 0.0001)
        assertEquals("HC", GripPosition.halfCrimp.shortName)
        assertEquals("Open", GripPosition.openHand.name)
    }

    // MARK: - Frozen display copy

    @Test
    fun frozenGripCopyStrings() {
        val fourFinger = GripSpec(edgeMM = 20, fingers = FingerSet.four, position = GripPosition.halfCrimp)
        assertEquals("20 mm · 4 fingers · half crimp", fourFinger.line)
        assertEquals("20 mm · 4 fingers · Half crimp", fourFinger.displayName)
        assertEquals("20 mm edge, 4 fingers, half crimp", fourFinger.spoken)
        assertEquals(
            "20mm F2 FC",
            GripSpec(edgeMM = 20, fingers = FingerSet.frontTwo, position = GripPosition.fullCrimp).shortName,
        )
    }

    // MARK: - The thumb (added 2026-08-04)

    /// THE regression that must never happen: adding the thumb must not move a single
    /// byte of any key written before it existed. Its letter is LAST for this reason.
    @Test
    fun thumblessKeysAreByteIdenticalToThePreThumbFormat() {
        assertEquals("IMRL", FingerSet.four.token)
        assertEquals("IM", FingerSet.frontTwo.token)
        assertEquals(
            "20|IMRL|halfCrimp",
            GripSpec(edgeMM = 20, fingers = FingerSet.four, position = GripPosition.halfCrimp).key,
        )
        assertEquals(
            "20|MR|fullCrimp",
            GripSpec(edgeMM = 20, fingers = FingerSet.middleTwo, position = GripPosition.fullCrimp).key,
        )
    }

    @Test
    fun thumbTokensRoundTripAndProduceStableKeys() {
        val pinchGrip = FingerSet.of(listOf(FingerSet.index, FingerSet.middle, FingerSet.thumb))
        assertEquals("IMT", pinchGrip.token)
        assertEquals(pinchGrip, FingerSet.fromToken("IMT"))
        assertEquals(pinchGrip, FingerSet.fromToken("TIM"), "parse order is free; emit order is not")
        assertEquals(
            "25|IMT|pinch",
            GripSpec(edgeMM = 25, fingers = pinchGrip, position = GripPosition.pinch).key,
        )
    }

    @Test
    fun thumbNamesReadAsAClimberWouldSayThem() {
        val frontTwoThumb = FingerSet.of(listOf(FingerSet.index, FingerSet.middle, FingerSet.thumb))
        assertEquals("Front 2 + thumb", frontTwoThumb.name)
        assertEquals("F2+T", frontTwoThumb.shortName)
        assertEquals("Thumb", FingerSet.thumb.name)
        assertEquals("T", FingerSet.thumb.shortName)
        assertEquals(
            "4 fingers + thumb",
            FingerSet.of(
                listOf(FingerSet.index, FingerSet.middle, FingerSet.ring, FingerSet.little, FingerSet.thumb)
            ).name,
        )
    }

    @Test
    fun theMaskAdmitsTheThumbAndNothingAbove() {
        assertEquals(FingerSet.thumb, FingerSet(1 shl 4))
        assertEquals(0, FingerSet(1 shl 5).rawValue, "bit five does not exist")
        assertFalse(FingerSet.four.hasThumb)
        assertEquals(4, FingerSet.four.occupied.size, "occupied stays the four FINGERS")
    }

    @Test
    fun pinchIsAKnownPositionWithItsOwnClosure() {
        assertTrue(GripPosition.known.contains(GripPosition.pinch))
        assertEquals("Pinch", GripPosition.pinch.name)
        assertEquals("PN", GripPosition.pinch.shortName)
        // Between open (0.2) and half crimp (0.65): squeezed, not curled.
        assertEquals(0.35, GripPosition.pinch.closure, 0.0001)
        assertEquals(GripPosition.pinch, GripPosition("pinch"), "round-trips through the raw")
    }
}
