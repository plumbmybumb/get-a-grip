// SPDX-License-Identifier: MPL-2.0
// Original contributions Copyright 2026 Nuri Bruner.

import XCTest
@testable import Doigt

/// A shared routine arrives from a CAMERA — a stranger's phone, a printed sheet on a
/// gym wall, a screenshot of a screenshot. So the codec is held to two things at once:
/// a routine that survives the trip must come back exactly as it left, and everything
/// else must fail closed with words a person can read.
///
/// The fixtures below are hand-built JSON pushed through zlib and base64url by this
/// file's own helper, never through `RoutineShare.url(for:)` — an encoder tested against
/// its own decoder agrees with itself about the wrong shape.
final class RoutineShareCodecTests: XCTestCase {

    /// The smallest legal routine: one set with one pull in it. Every malformed fixture
    /// below varies exactly one thing around this.
    private let oneSetJSON =
        #"{"grip":{"edgeMM":20,"fingers":"IMRL","position":"halfCrimp"},"repsPerSide":6}"#

    // MARK: - Round trip

    func testTypedBuilderLimitsAndExactBandsSurviveSavingAndSharing() throws {
        var source = RoutineDraft.starter
        source.plan.setBreakSeconds = 900
        source.plan.thresholdKg = 30
        source.plan.sets[0].targetLoPercent = 0.17
        source.plan.sets[0].targetHiPercent = 0.19
        let saved = try JSONDecoder().decode(RoutineDraft.self, from: JSONEncoder().encode(source))
        let imported = try RoutineShare.draft(from: XCTUnwrap(RoutineShare.url(for: saved)))
        for draft in [saved, imported] {
            XCTAssertEqual(draft.plan.setBreakSeconds, 900)
            XCTAssertEqual(draft.plan.thresholdKg, 30)
            XCTAssertEqual(draft.plan.sets[0].targetLoPercent, 0.17)
            XCTAssertEqual(draft.plan.sets[0].targetHiPercent, 0.19)
        }
    }

    func testShortHoldsSurviveSavingAndSharing() throws {
        for seconds in [1, 2] {
            var source = RoutineDraft.starter
            source.plan.holdSeconds = seconds
            source.plan.sets[0].holdSeconds = seconds
            let saved = try JSONDecoder().decode(RoutineDraft.self,
                                                 from: JSONEncoder().encode(source))
            XCTAssertEqual(saved.plan.holdSeconds, seconds)
            XCTAssertEqual(saved.plan.sets[0].holdSeconds, seconds)
            let imported = try RoutineShare.draft(from: XCTUnwrap(RoutineShare.url(for: saved)))
            XCTAssertEqual(imported.plan.holdSeconds, seconds)
            for set in imported.plan.sets {
                XCTAssertEqual(PlanMath.hold(set, in: imported.plan), seconds)
            }
        }
    }

    /// Both shipped prefills, because they exercise opposite corners: `.starter` is six
    /// sets with no targets at all, `.maxDay` is a WHENEVER routine carrying percentage
    /// bands on three of its four sets.
    func testTheShippedRoutinesRoundTripThroughTheWireUnchanged() throws {
        for source in [RoutineDraft.starter, RoutineDraft.maxDay] {
            let url = try XCTUnwrap(RoutineShare.url(for: source))
            let imported = try RoutineShare.draft(from: url)

            XCTAssertEqual(withoutSetIDs(imported.plan), withoutSetIDs(source.plan),
                           "the plan is the payload — every field of it must survive")
            XCTAssertEqual(imported.sessionsPerDay, source.sessionsPerDay)
            XCTAssertEqual(imported.isOnDemand, source.isOnDemand)
            XCTAssertNil(imported.templateID, "an imported routine does not exist yet")
            XCTAssertFalse(imported.remindersEnabled,
                           "importing must never ambush the recipient with a permission prompt")
            XCTAssertEqual(imported.reminders.count, source.sessionsPerDay,
                           "the RECIPIENT's default ladder, one time per session a day")
        }
    }

    /// The shipped prefills leave most fields at their defaults, and a comparison of
    /// defaults to defaults cannot catch a field being dropped on the wire. This fixture
    /// sets every optional and every toggle to a NON-default value, so losing any one of
    /// them fails the equality.
    func testARoutineWithEveryFieldOffItsDefaultRoundTrips() throws {
        var source = RoutineDraft()
        source.plan.name = "Loaded"
        source.plan.handMode = .bothHands
        source.plan.holdSeconds = 7
        source.plan.restSeconds = 33
        source.plan.setBreakSeconds = 45
        source.plan.leadInSeconds = 11
        source.plan.thresholdKg = 3.5
        source.plan.waitForReleaseBeforeRest = false
        source.plan.pausesOutsideTargetBand = false
        var heavy = SetPlan(grip: GripSpec(edgeMM: 15, fingers: .frontTwo, position: .fullCrimp),
                            repsPerSide: 3)
        heavy.holdSeconds = 5          // per-set overrides, distinct from the plan's own
        heavy.restSeconds = 90
        heavy.targetLoKg = 25
        heavy.targetHiKg = 30.5
        heavy.note = "top set — chalk up"
        var banded = SetPlan(grip: GripSpec(edgeMM: 45, fingers: .four, position: .pinch),
                             repsPerSide: 4)
        banded.targetLoPercent = 0.55
        banded.targetHiPercent = 0.72
        source.plan.sets = [heavy, banded]
        source.sessionsPerDay = 3
        source.isOnDemand = true

        let url = try XCTUnwrap(RoutineShare.url(for: source))
        let imported = try RoutineShare.draft(from: url)

        XCTAssertEqual(withoutSetIDs(imported.plan), withoutSetIDs(source.plan))
        XCTAssertEqual(imported.sessionsPerDay, 3)
        XCTAssertTrue(imported.isOnDemand)
    }

    /// The encoder refuses to mint a code the decoder would refuse to read — a QR that
    /// fails on every phone including the sender's is a failure the sharer can never
    /// learn about, where the nil routes into an alert they can.
    func testTheEncoderRefusesWhatTheDecoderWouldRefuse() {
        var empty = RoutineDraft()
        empty.plan.sets = []
        XCTAssertNil(RoutineShare.url(for: empty), "no pulls, no code")

        var oversized = RoutineDraft()
        oversized.plan.sets = (0..<51).map { _ in SetPlan(repsPerSide: 1) }
        XCTAssertNil(RoutineShare.url(for: oversized), "51 sets imports as .tooLarge everywhere")

        // And the caps that TRUNCATE rather than refuse are applied at both ends, so
        // the sharer's screen and the recipient's can never disagree about the name.
        var longNamed = RoutineDraft.starter
        longNamed.plan.name = String(repeating: "B", count: 200)
        guard let url = RoutineShare.url(for: longNamed),
              let imported = try? RoutineShare.draft(from: url) else {
            return XCTFail("a long name is a routine with a long name, not a refusal")
        }
        XCTAssertEqual(imported.plan.name, String(repeating: "B", count: 60))
    }

    /// Row identity is the recipient's, not the sender's — the same rule
    /// `RoutineDraft.copying` follows, and for the same reason: two people's routines
    /// sharing a SetPlan id makes one person's reorder move the other's rows.
    func testSetIDsAreRemintedOnEveryImport() throws {
        let source = RoutineDraft.starter
        let url = try XCTUnwrap(RoutineShare.url(for: source))

        let first = Set(try RoutineShare.draft(from: url).plan.sets.map(\.id))
        let second = Set(try RoutineShare.draft(from: url).plan.sets.map(\.id))

        XCTAssertEqual(first.count, 6)
        XCTAssertEqual(second.count, 6)
        XCTAssertTrue(first.isDisjoint(with: second), "two scans of one code are two routines")
        XCTAssertTrue(first.isDisjoint(with: Set(source.plan.sets.map(\.id))),
                      "and neither of them is the sender's")
    }

    /// `.sortedKeys` plus a deterministic compressor means the bytes are a pure function
    /// of the content: a QR printed and stuck on a fingerboard keeps matching the routine
    /// it came from, and this format is testable at all.
    func testEncodingTheSameRoutineTwiceProducesTheIdenticalURL() throws {
        let draft = RoutineDraft.starter
        let first = try XCTUnwrap(RoutineShare.url(for: draft))
        let second = try XCTUnwrap(RoutineShare.url(for: draft))
        XCTAssertEqual(first, second)

        // Set ids ride along, so they are content: a freshly minted `.starter` is a
        // different payload even though it is the same protocol.
        let reminted = try XCTUnwrap(RoutineShare.url(for: .starter))
        XCTAssertNotEqual(first, reminted)
    }

    /// QR headroom. Nothing enforces this at runtime — it is here so a field added to
    /// `SessionPlan` that bloats the payload shows up as a failing test rather than as a
    /// code nobody's camera can lock onto.
    func testTheStarterRoutineFitsWellInsideAScannableCode() throws {
        let url = try XCTUnwrap(RoutineShare.url(for: .starter))
        XCTAssertLessThan(url.absoluteString.count, 900,
                          "six sets is the everyday case; a code this size is comfortable at M correction")
    }

    /// The routing predicate and the builder must agree, or every code this build
    /// produces is one `onOpenURL` guard away from being ignored.
    func testAGeneratedURLIsRecognisedAsARoutineLink() throws {
        let url = try XCTUnwrap(RoutineShare.url(for: .starter))
        XCTAssertTrue(RoutineShare.isRoutineLink(url))
        XCTAssertTrue(url.absoluteString.hasPrefix("getagrip://routine#"))
    }

    // MARK: - Forward compatibility

    /// The same trade the blob columns make: a routine from a newer build imports with
    /// the fields this build understands rather than refusing outright.
    func testUnknownKeysInTheEnvelopeAndInThePlanAreIgnored() throws {
        let json = #"""
        {"v":1,"sessionsPerDay":2,"isOnDemand":false,"gauge":"progressor",\#
        "plan":{"name":"From the future","mood":"crisp","sets":[\#
        {"grip":{"edgeMM":18,"fingers":"IMR","position":"halfCrimp","texture":"wood"},\#
        "repsPerSide":4,"tempo":"slow"}]}}
        """#

        let imported = try RoutineShare.draft(from: link(json))
        XCTAssertEqual(imported.plan.name, "From the future")
        XCTAssertEqual(imported.plan.sets.count, 1)
        XCTAssertEqual(imported.plan.sets[0].grip.key, "18|IMR|halfCrimp")
        XCTAssertEqual(imported.plan.sets[0].repsPerSide, 4)
    }

    /// The version gates the ENVELOPE, not the plan — which is why a bump is rare and a
    /// missing version is a payload this format never wrote.
    func testTheEnvelopeVersionGatesTheWholePayload() throws {
        assertImportFails(.newerVersion,
                          from: try link(#"{"v":999,"plan":{"sets":[\#(oneSetJSON)]}}"#))
        assertImportFails(.unreadable,
                          from: try link(#"{"plan":{"sets":[\#(oneSetJSON)]}}"#))
        assertImportFails(.unreadable,
                          from: try link(#"{"v":0,"plan":{"sets":[\#(oneSetJSON)]}}"#))
        XCTAssertNoThrow(try RoutineShare.draft(
            from: link(#"{"v":1,"plan":{"sets":[\#(oneSetJSON)]}}"#)))
    }

    // MARK: - Damage and foreign links

    /// Everything a camera can hand over that is not a routine. The split matters: a
    /// link that is not ours is routed away silently, while a link that IS ours and
    /// arrived damaged owes the user a sentence.
    func testForeignLinksAreNotOursAndDamagedOnesSayWhy() throws {
        for foreign in ["mailto:nuri@example.com",
                        "https://example.com/blog/hangboarding",
                        // "routine" as a SUBSTRING of a path is somebody's blog, not our
                        // link — the claim is a whole path component or nothing.
                        "https://x.com/my-routines/7#abc",
                        "https://example.com/routines-of-2027#abc",
                        "getagrip://max#YWJj"] {
            let url = try XCTUnwrap(URL(string: foreign))
            XCTAssertFalse(RoutineShare.isRoutineLink(url), "\(foreign) is not ours to answer for")
        }
        // While a nested path whose component IS "routine" stays ours — the AASA layout
        // is undecided, so the component may land anywhere in the path.
        let nested = try XCTUnwrap(URL(string: "https://getagrip.example/app/routine#YWJj"))
        XCTAssertTrue(RoutineShare.isRoutineLink(nested))

        // Ours, with nothing attached at all: somebody typed the scheme.
        assertImportFails(.notARoutineLink, from: try XCTUnwrap(URL(string: "getagrip://routine")))
        // Ours, with an empty payload: a code that scanned badly.
        assertImportFails(.unreadable, from: try XCTUnwrap(URL(string: "getagrip://routine#")))
        // Not base64url at all.
        assertImportFails(.unreadable,
                          from: try XCTUnwrap(URL(string: "getagrip://routine#not-a-payload!!")))
        // Valid base64url of bytes that were never compressed.
        assertImportFails(.unreadable,
                          from: try XCTUnwrap(URL(string:
                            "getagrip://routine#" + base64url(Data([0xDE, 0xAD, 0xBE, 0xEF,
                                                                 0x00, 0x11, 0x22, 0x33])))))
        // A real payload with its tail missing — the commonest real damage, a QR read
        // from half a screenshot.
        let whole = try compressed(#"{"v":1,"plan":{"sets":[\#(oneSetJSON)]}}"#)
        assertImportFails(.unreadable,
                          from: try XCTUnwrap(URL(string:
                            "getagrip://routine#" + base64url(whole.dropLast(10)))))
    }

    // MARK: - Untrusted numbers and invariants

    /// Nothing in this file clamps anything: the plan's own decoders already do, and
    /// that is the point — the share format adds no second description of a routine to
    /// keep in step with the first.
    func testWildNumbersAreClampedByThePlansOwnDecoders() throws {
        let json = #"""
        {"v":1,"plan":{"holdSeconds":9999,"restSeconds":-40,"thresholdKg":0,"sets":[\#
        {"grip":{"edgeMM":0,"fingers":"IMRL","position":"halfCrimp"},"repsPerSide":99}]}}
        """#

        let imported = try RoutineShare.draft(from: link(json))
        XCTAssertEqual(imported.plan.holdSeconds, 120)
        XCTAssertEqual(imported.plan.restSeconds, 0)
        XCTAssertEqual(imported.plan.thresholdKg, 0.1, accuracy: 0.0001,
                       "a zero threshold would read as engaged against sensor noise")
        XCTAssertEqual(imported.plan.sets[0].grip.edgeMM, 1)
        XCTAssertEqual(imported.plan.sets[0].repsPerSide, 20)
    }

    /// A pinch IS thumb opposition, so a thumbless one is not a grip anybody can perform
    /// — and the invariant has to hold over a wire somebody else wrote, not just in the
    /// three screens that edit a grip.
    func testAPinchArrivesWithItsThumbHoweverItWasSent() throws {
        let json = #"""
        {"v":1,"plan":{"sets":[\#
        {"grip":{"edgeMM":20,"fingers":"IM","position":"pinch"},"repsPerSide":4}]}}
        """#

        let imported = try RoutineShare.draft(from: link(json))
        XCTAssertTrue(imported.plan.sets[0].grip.fingers.hasThumb)
        XCTAssertEqual(imported.plan.sets[0].grip.key, "20|IMT|pinch")
    }

    func testAnOversizedOrEmptyRoutineIsRefused() throws {
        let fiftyOne = Array(repeating: oneSetJSON, count: 51).joined(separator: ",")
        assertImportFails(.tooLarge, from: try link(#"{"v":1,"plan":{"sets":[\#(fiftyOne)]}}"#))

        // Fifty is the ceiling, not the wall — a routine sitting on it still imports.
        let fifty = Array(repeating: oneSetJSON, count: 50).joined(separator: ",")
        XCTAssertNoThrow(try RoutineShare.draft(
            from: link(#"{"v":1,"plan":{"sets":[\#(fifty)]}}"#)))

        // A set with no pulls in it is dropped by `executable`, so a routine of nothing
        // but those has nothing to run and says so rather than importing a blank card.
        // This is the ONE shape `.emptyRoutine` still names: the sets arrived intact and
        // every one is zero-rep.
        let dead = #"{"grip":{"edgeMM":20,"fingers":"IMRL","position":"halfCrimp"},"repsPerSide":0}"#
        assertImportFails(.emptyRoutine, from: try link(#"{"v":1,"plan":{"sets":[\#(dead)]}}"#))
        // NO sets at all is damage, not a routine — the encoder refuses to build an
        // empty code, so one arriving must have been mangled, and blaming the sharer's
        // routine for it sends the recipient back to a person whose routine is fine.
        assertImportFails(.unreadable, from: try link(#"{"v":1,"plan":{"sets":[]}}"#))
    }

    /// The misclassification a lenient envelope used to make: JSON-layer damage fell
    /// through `SessionPlan()`'s empty defaults and was reported as "a routine with no
    /// pulls in it" — a statement about the SHARER — instead of as a damaged code.
    func testJSONLayerDamageReadsAsUnreadableNeverAsAnEmptyRoutine() throws {
        // The plan key present but mangled into a scalar by a bad scan.
        assertImportFails(.unreadable, from: try link(#"{"v":1,"plan":"nope"}"#))
        // The plan key missing entirely.
        assertImportFails(.unreadable, from: try link(#"{"v":1}"#))
        // And the ordering that keeps `.newerVersion` honest: a future format may
        // reshape the plan itself, so the version verdict must come before the strict
        // plan decode gets a chance to call it damage.
        assertImportFails(.newerVersion, from: try link(#"{"v":999}"#))
    }

    /// An intermediary — a third-party scanner, a link shortener — is allowed to
    /// percent-encode unreserved characters on the way through. The alphabet contains
    /// no character that NEEDS encoding, so decoding is pure tolerance: nothing it
    /// produces could ever have been in a payload the encoder wrote.
    func testAPercentEncodedFragmentStillDecodes() throws {
        let native = try XCTUnwrap(RoutineShare.url(for: .starter))
        let fragment = try XCTUnwrap(native.fragment(percentEncoded: true))
        let first = try XCTUnwrap(fragment.first)
        let encoded = String(format: "%%%02X", first.asciiValue ?? 0) + fragment.dropFirst()
        let mangled = try XCTUnwrap(URL(string: "getagrip://routine#\(encoded)"))

        let imported = try RoutineShare.draft(from: mangled)
        XCTAssertEqual(withoutSetIDs(imported.plan),
                       withoutSetIDs(RoutineDraft.starter.plan))
    }

    /// The length gate fires before any string surgery: a link is untrusted input from
    /// a camera or a message, and refusing an absurd one must not first allocate
    /// several copies of it.
    func testAnAbsurdlyLongFragmentIsRefused() throws {
        let huge = String(repeating: "A", count: 1_000_000)
        assertImportFails(.unreadable,
                          from: try XCTUnwrap(URL(string: "getagrip://routine#" + huge)))
    }

    /// Free text from a stranger, capped rather than rejected: a long name is somebody's
    /// routine with a long name.
    func testStrangersTextIsTrimmedAndCapped() throws {
        let longName = String(repeating: "A", count: 200)
        let longNote = String(repeating: "n", count: 900)
        let json = #"""
        {"v":1,"plan":{"name":"  \#(longName)  ","sets":[\#
        {"grip":{"edgeMM":20,"fingers":"IMRL","position":"halfCrimp"},\#
        "repsPerSide":6,"note":"\#(longNote)"}]}}
        """#

        let imported = try RoutineShare.draft(from: link(json))
        XCTAssertEqual(imported.plan.name, String(repeating: "A", count: 60))
        XCTAssertEqual(imported.plan.sets[0].note.count, 500)

        // A name that was only whitespace comes back empty — the trim happened — and the
        // store's own `normalized` is what names it on save.
        let blank = try RoutineShare.draft(
            from: link(#"{"v":1,"plan":{"name":"   ","sets":[\#(oneSetJSON)]}}"#))
        XCTAssertEqual(blank.plan.name, "")
        XCTAssertEqual(blank.normalized.plan.name, "Daily no-hangs")
    }

    // MARK: - The future universal link

    /// Today's build must read the link tomorrow's build hands out, or every code shared
    /// across the changeover dies at the App Store fallback.
    func testTheHTTPSFormDecodesIdentically() throws {
        let source = RoutineDraft.starter
        let native = try XCTUnwrap(RoutineShare.url(for: source))
        let payload = try XCTUnwrap(native.fragment(percentEncoded: true))
        let web = try XCTUnwrap(URL(string: "https://example.com/routine#\(payload)"))

        XCTAssertTrue(RoutineShare.isRoutineLink(web))
        let imported = try RoutineShare.draft(from: web)
        XCTAssertEqual(withoutSetIDs(imported.plan), withoutSetIDs(source.plan))
    }

    // MARK: - Hand-building the wire

    /// JSON → zlib → base64url → URL, written out here rather than borrowed from the
    /// codec: these tests are the only independent statement of what the format IS.
    private func link(_ json: String,
                      host: String = "getagrip://routine") throws -> URL {
        let url = URL(string: host + "#" + base64url(try compressed(json)))
        return try XCTUnwrap(url)
    }

    private func compressed(_ json: String) throws -> Data {
        let deflated = try (Data(json.utf8) as NSData).compressed(using: .zlib)
        return deflated as Data
    }

    private func base64url(_ data: Data) -> String {
        var s = data.base64EncodedString()
            .replacingOccurrences(of: "+", with: "-")
            .replacingOccurrences(of: "/", with: "_")
        while s.hasSuffix("=") { s.removeLast() }
        return s
    }

    /// Set ids are reminted on purpose, so plan equality is asserted around them.
    private func withoutSetIDs(_ plan: SessionPlan) -> SessionPlan {
        var out = plan
        let zero = UUID(uuidString: "00000000-0000-0000-0000-000000000000")!
        out.sets = out.sets.map { set in
            var s = set
            s.id = zero
            return s
        }
        return out
    }

    private func assertImportFails(_ expected: RoutineShareError,
                                   from url: URL,
                                   file: StaticString = #filePath,
                                   line: UInt = #line) {
        XCTAssertThrowsError(try RoutineShare.draft(from: url), file: file, line: line) { error in
            XCTAssertEqual(error as? RoutineShareError, expected, file: file, line: line)
        }
    }
}
