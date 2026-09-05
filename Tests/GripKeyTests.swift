// SPDX-License-Identifier: MPL-2.0
// Original contributions Copyright 2026 Nuri Bruner.

import XCTest
@testable import Doigt

/// The canonical grip key is the only thing joining a rep pulled in March to one
/// pulled in December and to the MaxRecord that says what 25 % means for it. There is
/// no grip library to register in, so the key IS the registry — which makes silently
/// merging two different grips into one trend series, or forking one grip into two,
/// the worst corruption this app can inflict on history.
///
/// Every expectation below is a hand-written literal. A test that rebuilt the key with
/// the same string interpolation the type uses would happily accept a reformat.
final class GripKeyTests: XCTestCase {

    // MARK: - The key itself

    func testCanonicalKeyIsExactlyThreePipeJoinedFields() {
        let spec = GripSpec(edgeMM: 20, fingers: .frontTwo, position: .fullCrimp)
        XCTAssertEqual(spec.key, "20|IM|fullCrimp")
        XCTAssertEqual(GripSpec(edgeMM: 20, fingers: .frontTwo, position: .halfCrimp).key,
                       "20|IM|halfCrimp")
        XCTAssertEqual(spec.key.split(separator: "|", omittingEmptySubsequences: false).count, 3)
    }

    /// The prefill is a product spec, so its keys get pinned like one: these six
    /// strings are what every future log, trend and max for Nuri's protocol will join on.
    func testAllSixPrefillKeysArePinned() {
        XCTAssertEqual(RoutineDraft.starter.plan.sets.map(\.grip.key), [
            "20|IMRL|halfCrimp",
            "20|IMR|halfCrimp",
            "20|IM|openHand",
            "20|MR|openHand",
            "20|IM|fullCrimp",
            "20|MR|fullCrimp",
        ])
    }

    /// `edgeMM` is an Int and that is load-bearing, not a style choice: the key is a
    /// serialization, and Double formatting is locale-dependent, so a French phone and
    /// an American one would fork one trend series into three.
    func testEdgeIsFormattedAsAPlainIntegerUnderAnyLocale() {
        let spec = GripSpec(edgeMM: 20, fingers: .four, position: .halfCrimp)

        // What the same edge would look like if it were ever "improved" to a Double.
        let french = 20.0.formatted(
            .number.precision(.fractionLength(1)).locale(Locale(identifier: "fr_FR")))
        XCTAssertEqual(french, "20,0")

        XCTAssertEqual(spec.key, "20|IMRL|halfCrimp")
        XCTAssertFalse(spec.key.contains(","), "a decimal comma in the key forks the series")
        XCTAssertFalse(spec.key.contains("."), "a decimal point in the key forks the series")
    }

    /// The schema-level expression of "do not add a grip library": two sets built at
    /// unrelated call sites are the same series with nothing to register.
    func testIndependentlyBuiltIdenticalSpecsShareKeyAndHash() {
        let fromBuilder = GripSpec(edgeMM: 20, fingers: [.index, .middle, .ring], position: .halfCrimp)
        let fromPrefill = RoutineDraft.starter.plan.sets[1].grip

        XCTAssertEqual(fromBuilder, fromPrefill)
        XCTAssertEqual(fromBuilder.key, fromPrefill.key)
        XCTAssertEqual(fromBuilder.hashValue, fromPrefill.hashValue)
        XCTAssertEqual(Set([fromBuilder, fromPrefill]).count, 1)
    }

    func testDifferingInAnySingleFieldProducesADifferentKey() {
        let keys = [
            GripSpec(edgeMM: 20, fingers: .frontTwo, position: .halfCrimp).key,
            GripSpec(edgeMM: 20, fingers: .frontTwo, position: .fullCrimp).key,
            GripSpec(edgeMM: 18, fingers: .frontTwo, position: .halfCrimp).key,
            GripSpec(edgeMM: 20, fingers: .middleTwo, position: .halfCrimp).key,
        ]
        XCTAssertEqual(keys, ["20|IM|halfCrimp", "20|IM|fullCrimp", "18|IM|halfCrimp", "20|MR|halfCrimp"])
        XCTAssertEqual(Set(keys).count, 4, "two different grips must never share a trend series")
    }

    // MARK: - FingerSet

    /// The token is the wire format, so it is written in pip order regardless of how
    /// the set was assembled.
    func testFingerTokenIsFixedOrderRegardlessOfConstructionOrder() {
        XCTAssertEqual(FingerSet([.ring, .index]).token, "IR")
        XCTAssertEqual(FingerSet([.index, .ring]).token, "IR")
        XCTAssertEqual(FingerSet.four.token, "IMRL")
        XCTAssertEqual(FingerSet([.little, .ring, .middle, .index]).token, "IMRL")
        XCTAssertEqual(FingerSet.letters, "IMRLT")
    }

    /// An OptionSet over exactly four bits means every representable value is a real
    /// grip — there is no "unknown value from a newer build" to fall back from, which
    /// is why FingerSet needs none of GripPosition's extensibility machinery.
    func testFingerSetTokenRoundTripsForAllFifteenCombinations() {
        var seen = Set<String>()
        for raw in 1...15 {
            let set = FingerSet(rawValue: raw)
            XCTAssertEqual(FingerSet(token: set.token), set, "round-trip failed for raw \(raw)")
            XCTAssertTrue(seen.insert(set.token).inserted, "token collision on raw \(raw)")
        }
        XCTAssertEqual(seen.count, 15)
    }

    func testRawValueMasksToTheFiveRealDigits() {
        // Unknown bits cannot exist, so nothing downstream ever has to defend against
        // one. Bit four is the THUMB now, so it is real and survives the mask.
        XCTAssertEqual(FingerSet(rawValue: 0b1_0000 | 0b0011), FingerSet([.index, .middle, .thumb]))
        XCTAssertEqual(FingerSet(rawValue: Int.max), [.index, .middle, .ring, .little, .thumb])
        XCTAssertEqual(FingerSet(rawValue: 0b10_0000).rawValue, 0, "bit five dies at the mask")
    }

    func testUnknownFingerLettersAreIgnoredAndEmptyFallsBackToFour() {
        XCTAssertEqual(FingerSet(token: "IXM"), FingerSet([.index, .middle]))
        XCTAssertEqual(FingerSet(token: ""), FingerSet.four)
        // All-unknown reduces to empty, which is not a grip anybody can pull.
        XCTAssertEqual(FingerSet(token: "ZZZ"), FingerSet.four)
    }

    /// Naming a set of two "Front 2" when it is index + ring would be a lie printed on
    /// a set row, so composites name themselves instead.
    func testCompositeFingerSetNamesItselfRatherThanLying() {
        XCTAssertEqual(FingerSet([.index, .ring]).name, "Index + ring")
        XCTAssertEqual(FingerSet.frontTwo.name, "Front 2")
        XCTAssertEqual(FingerSet.four.name, "4 fingers")
        XCTAssertEqual(FingerSet.backThree.name, "Back 3")
        XCTAssertEqual(FingerSet([.middle]).name, "Middle")
        XCTAssertEqual(FingerSet([.index, .ring]).shortName, "IR")
        XCTAssertEqual(FingerSet.frontTwo.shortName, "F2")
    }

    func testOccupiedFlagsRunIndexToLittle() {
        // FingerGlyph and FingerPips draw straight off this, so the order is contractual.
        XCTAssertEqual(FingerSet.frontTwo.occupied, [true, true, false, false])
        XCTAssertEqual(FingerSet.backTwo.occupied, [false, false, true, true])
        XCTAssertEqual(FingerSet.four.count, 4)
        XCTAssertEqual(FingerSet.middleTwo.count, 2)
    }

    // MARK: - GripPosition (extensible on purpose)

    /// The CloudKit-skew data-loss test. Two devices sync the same routine; the older
    /// build must hand back exactly what the newer one wrote. This test fails the
    /// moment GripPosition becomes an enum.
    func testUnknownGripPositionRawSurvivesDecodeAndReEncode() throws {
        // Written in .sortedKeys order with no whitespace so the bytes are directly
        // comparable to what BlobCodec.encoder produces.
        //
        // "sloper", not "pinch". This test's subject is a position raw this build has
        // never heard of, and `.pinch` stopped being one the moment it gained a model
        // invariant — a pinch now pulls the thumb in on decode, which is a DELIBERATE
        // rewrite and would have quietly turned this into a test of that instead.
        let wire = Data(#"{"edgeMM":20,"fingers":"IM","position":"sloper"}"#.utf8)

        let decoded = try XCTUnwrap(BlobCodec.decode(GripSpec.self, from: wire))
        XCTAssertEqual(decoded.position.rawValue, "sloper")
        XCTAssertEqual(decoded.key, "20|IM|sloper")

        let reEncoded = try XCTUnwrap(BlobCodec.encode(decoded))
        XCTAssertEqual(reEncoded, wire, "a round trip through this build must not rewrite the record")
    }

    // MARK: - A pinch is thumb opposition

    /// THE RULE (Nuri, 2026-08-04): "there's no world where you can pinch without the
    /// thumb". So the app must not be able to REPRESENT one — enforced in the model, not
    /// in the three screens that edit a grip, because a rule that lives in a view is a
    /// rule the next view forgets.
    func testAPinchAlwaysCarriesTheThumb() {
        // Through the initialiser, which shadows the memberwise one precisely because
        // property observers do not fire during init.
        let built = GripSpec(edgeMM: 20, fingers: .frontTwo, position: .pinch)
        XCTAssertTrue(built.fingers.hasThumb)
        XCTAssertEqual(built.key, "20|IMT|pinch")

        // And through mutation, in both orders.
        var switched = GripSpec(edgeMM: 20, fingers: .frontTwo, position: .openHand)
        XCTAssertFalse(switched.fingers.hasThumb)
        switched.position = .pinch
        XCTAssertTrue(switched.fingers.hasThumb, "choosing pinch pulls the thumb in")

        var stripped = GripSpec(edgeMM: 20, fingers: .frontTwo, position: .pinch)
        stripped.fingers = .frontTwo          // an attempt to take the thumb back out
        XCTAssertTrue(stripped.fingers.hasThumb, "and it cannot be taken back out")
    }

    /// Every other position leaves the thumb alone — the rule is one-directional. A
    /// thumb-assisted open hand is a real grip and must stay representable.
    func testOnlyPinchForcesTheThumb() {
        for position in [GripPosition.halfCrimp, .openHand, .fullCrimp, .drag] {
            let spec = GripSpec(edgeMM: 20, fingers: .frontTwo, position: position)
            XCTAssertFalse(spec.fingers.hasThumb, "\(position.rawValue) must not add one")
        }
        var thumbed = GripSpec(edgeMM: 20, fingers: .frontTwo, position: .openHand)
        thumbed.fingers.formUnion(.thumb)
        XCTAssertTrue(thumbed.fingers.hasThumb, "and the thumb stays where it was asked for")
    }

    /// A blob written before the rule existed self-heals on read. This DOES change its
    /// key, so a max recorded against the thumbless form stops joining — acceptable only
    /// because `.pinch` is days old and unshipped, and stated here so the next person to
    /// add an invariant knows what it costs on data that HAS shipped.
    func testAThumblessPinchBlobSelfHealsOnDecode() throws {
        let wire = Data(#"{"edgeMM":20,"fingers":"IM","position":"pinch"}"#.utf8)
        let decoded = try XCTUnwrap(BlobCodec.decode(GripSpec.self, from: wire))
        XCTAssertTrue(decoded.fingers.hasThumb)
        XCTAssertEqual(decoded.key, "20|IMT|pinch", "the key moves — deliberately")
    }

    func testUnknownGripPositionDisplaysItselfAndClosureIsNeutral() {
        let sloper = GripPosition("sloper")
        XCTAssertEqual(sloper.name, "sloper")
        XCTAssertEqual(sloper.shortName, "SLOPER")
        // Neutral, so the glyph draws a half-closed hand rather than guessing straight
        // fingers or a full crimp for something it has never heard of.
        XCTAssertEqual(sloper.closure, 0.5, accuracy: 0.0001)
    }

    func testKnownPositionsCarryTheFrozenChipOrderAndClosures() {
        XCTAssertEqual(GripPosition.known, [.halfCrimp, .openHand, .fullCrimp, .drag, .pinch, .fingerCurl])
        XCTAssertEqual(GripPosition.drag.closure, 0.0, accuracy: 0.0001)
        XCTAssertEqual(GripPosition.openHand.closure, 0.2, accuracy: 0.0001)
        XCTAssertEqual(GripPosition.halfCrimp.closure, 0.65, accuracy: 0.0001)
        XCTAssertEqual(GripPosition.fullCrimp.closure, 1.0, accuracy: 0.0001)
        XCTAssertEqual(GripPosition.halfCrimp.shortName, "HC")
        XCTAssertEqual(GripPosition.openHand.name, "Open")
    }

    // MARK: - Frozen display copy

    func testFrozenGripCopyStrings() {
        let fourFinger = GripSpec(edgeMM: 20, fingers: .four, position: .halfCrimp)
        XCTAssertEqual(fourFinger.line, "20 mm · 4 fingers · half crimp")
        XCTAssertEqual(fourFinger.displayName, "20 mm · 4 fingers · Half crimp")
        XCTAssertEqual(fourFinger.spoken, "20 mm edge, 4 fingers, half crimp")
        XCTAssertEqual(GripSpec(edgeMM: 20, fingers: .frontTwo, position: .fullCrimp).shortName,
                       "20mm F2 FC")
    }
}


// MARK: - The thumb (added 2026-08-04)

extension GripKeyTests {
    /// THE regression that must never happen: adding the thumb must not move a single
    /// byte of any key written before it existed. Its letter is LAST for this reason.
    func testThumblessKeysAreByteIdenticalToThePreThumbFormat() {
        XCTAssertEqual(FingerSet.four.token, "IMRL")
        XCTAssertEqual(FingerSet.frontTwo.token, "IM")
        XCTAssertEqual(GripSpec(edgeMM: 20, fingers: .four, position: .halfCrimp).key,
                       "20|IMRL|halfCrimp")
        XCTAssertEqual(GripSpec(edgeMM: 20, fingers: .middleTwo, position: .fullCrimp).key,
                       "20|MR|fullCrimp")
    }

    func testThumbTokensRoundTripAndProduceStableKeys() {
        let pinchGrip: FingerSet = [.index, .middle, .thumb]
        XCTAssertEqual(pinchGrip.token, "IMT")
        XCTAssertEqual(FingerSet(token: "IMT"), pinchGrip)
        XCTAssertEqual(FingerSet(token: "TIM"), pinchGrip, "parse order is free; emit order is not")
        XCTAssertEqual(GripSpec(edgeMM: 25, fingers: pinchGrip, position: .pinch).key,
                       "25|IMT|pinch")
    }

    func testThumbNamesReadAsAClimberWouldSayThem() {
        XCTAssertEqual(FingerSet([.index, .middle, .thumb]).name, "Front 2 + thumb")
        XCTAssertEqual(FingerSet([.index, .middle, .thumb]).shortName, "F2+T")
        XCTAssertEqual(FingerSet.thumb.name, "Thumb")
        XCTAssertEqual(FingerSet.thumb.shortName, "T")
        XCTAssertEqual(FingerSet([.index, .middle, .ring, .little, .thumb]).name,
                       "4 fingers + thumb")
    }

    func testTheMaskAdmitsTheThumbAndNothingAbove() {
        XCTAssertEqual(FingerSet(rawValue: 1 << 4), .thumb)
        XCTAssertEqual(FingerSet(rawValue: 1 << 5).rawValue, 0, "bit five does not exist")
        XCTAssertFalse(FingerSet.four.hasThumb)
        XCTAssertEqual(FingerSet.four.occupied.count, 4, "occupied stays the four FINGERS")
    }

    func testPinchIsAKnownPositionWithItsOwnClosure() {
        XCTAssertTrue(GripPosition.known.contains(.pinch))
        XCTAssertEqual(GripPosition.pinch.name, "Pinch")
        XCTAssertEqual(GripPosition.pinch.shortName, "PN")
        // Between open (0.2) and half crimp (0.65): squeezed, not curled.
        XCTAssertEqual(GripPosition.pinch.closure, 0.35, accuracy: 0.0001)
        XCTAssertEqual(GripPosition("pinch"), .pinch, "round-trips through the raw")
    }
}
