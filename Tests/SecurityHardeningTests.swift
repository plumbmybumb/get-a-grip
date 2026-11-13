// SPDX-License-Identifier: MPL-2.0
// Original contributions Copyright 2026 Nuri Bruner.

import XCTest
@testable import Doigt

/// What arrives from OUTSIDE — a stranger's routine code, a pasted reply, a Bluetooth
/// peer that only claims to be a gauge, a calibration server — pinned at the boundary
/// where each one enters.
///
/// Every test here is the regression for something that got through: a kilogram target
/// no `Int` could hold took the app down at launch on every synced device; a 687-character
/// link carried a quarter-megabyte "position" into the saved routine; a damaged
/// calibration table made a hangboard report 1e33 kg.
final class SecurityHardeningTests: XCTestCase {

    private let oneSetJSON =
        #"{"grip":{"edgeMM":20,"fingers":"IMRL","position":"halfCrimp"},"repsPerSide":6}"#

    // MARK: - Numbers on their way to the screen

    func testADoubleBecomesAnIntWithoutEverTrapping() {
        XCTAssertEqual(Int(saturating: 41.9), 41)
        XCTAssertEqual(Int(saturating: -41.9), -41)
        XCTAssertEqual(Int(saturating: 2e30), .max)
        XCTAssertEqual(Int(saturating: -2e30), .min)
        XCTAssertEqual(Int(saturating: .infinity), .max)
        XCTAssertEqual(Int(saturating: -.infinity), .min)
        XCTAssertEqual(Int(saturating: .nan), 0)
        XCTAssertEqual(Int(saturating: Double(Int.max)), .max)
    }

    /// The whole chain that crash-looped the app: the link, the plan decoder, the fold
    /// against a real max, and the percentage the routine card speaks.
    func testAnAbsurdKilogramTargetIsClampedAndItsPercentageCanBeSpoken() throws {
        let json = #"""
        {"v":1,"plan":{"name":"Max day","sets":[{"grip":{"edgeMM":20,"fingers":"IMRL",\#
        "position":"halfCrimp"},"repsPerSide":3,"targetLoKg":1e30,"targetHiKg":1e30}]}}
        """#
        let imported = try RoutineShare.draft(from: link(json))
        let set = imported.plan.sets[0]
        XCTAssertEqual(set.targetLoKg, 1000)
        XCTAssertEqual(set.targetHiKg, 1000)

        var maxes = MaxTable()
        maxes.record(50, grip: set.grip.key, side: .both)
        let peak = try XCTUnwrap(PlanMath.peakIntensity(of: imported.plan, maxes: maxes))
        XCTAssertEqual(peak, 20, accuracy: 0.0001)
        XCTAssertEqual(Int(saturating: (peak * 100).rounded()), 2000)
    }

    /// The clamp lives in the plan decoder, not the share codec, so a routine that was
    /// saved before the bound existed heals the next time its blob is read.
    func testAStoredSetWithAnAbsurdTargetHealsOnDecode() throws {
        func decode(_ lo: String, _ hi: String) throws -> SetPlan {
            let json = #"{"grip":{"edgeMM":20,"fingers":"IMRL","position":"halfCrimp"},"repsPerSide":3,"targetLoKg":\#(lo),"targetHiKg":\#(hi)}"#
            return try JSONDecoder().decode(SetPlan.self, from: Data(json.utf8))
        }
        XCTAssertEqual(try decode("1e30", "1e308").targetBand, 1000...1000)
        XCTAssertEqual(try decode("-5", "12.5").targetBand, 0...12.5)
        XCTAssertEqual(try decode("10", "15").targetBand, 10...15, "an ordinary band is untouched")
        XCTAssertNil(SetPlan.kilograms(.nan))
        XCTAssertNil(SetPlan.kilograms(.infinity))
        XCTAssertNil(SetPlan.kilograms(nil))
    }

    // MARK: - Strings in a routine code

    func testAGripPositionFromAStrangerIsAVocabularyWord() throws {
        func code(_ position: String) throws -> URL {
            try link(#"{"v":1,"plan":{"sets":[{"grip":{"edgeMM":20,"fingers":"IMRL","position":"\#(position)"},"repsPerSide":3}]}}"#)
        }
        // A position this build has never heard of is still a word, and still round-trips.
        XCTAssertEqual(try RoutineShare.draft(from: code("sloper")).plan.sets[0].grip.position.rawValue, "sloper")
        XCTAssertNoThrow(try RoutineShare.draft(from: code("a" + String(repeating: "B", count: 31))))

        for hostile in ["a" + String(repeating: "B", count: 32),
                        String(repeating: "x", count: 5000),
                        "half|Crimp", "half/crimp", "half crimp", "2finger", "", "=cmd", #"réglette"#] {
            assertImportFails(.unreadable, from: try code(hostile))
        }
    }

    func testATargetBasisFromAStrangerIsAVocabularyWord() throws {
        func code(set: String = "", plan: String = "") throws -> URL {
            try link(#"{"v":1,"plan":{"sets":[{"grip":{"edgeMM":20,"fingers":"IMRL","position":"halfCrimp"},"repsPerSide":3\#(set)}]\#(plan)}}"#)
        }
        XCTAssertEqual(try RoutineShare.draft(from: code(set: #","targetBasis":"criticalForce""#))
            .plan.sets[0].targetBasis, .criticalForce)
        XCTAssertEqual(try RoutineShare.draft(from: code(set: #","targetBasis":"futureBasis""#))
            .plan.sets[0].targetBasisRaw, "futureBasis", "a newer build's word survives")
        assertImportFails(.unreadable, from: try code(set: #","targetBasis":"critical force""#))
        assertImportFails(.unreadable, from: try code(plan: #","targetBasis":"max;x""#))
        assertImportFails(.unreadable,
                          from: try code(set: #","targetBasis":"\#(String(repeating: "b", count: 250_000))""#))
    }

    /// A code the sender's own phone would refuse must never be minted.
    func testTheEncoderRefusesStringsTheDecoderWouldRefuse() {
        var draft = RoutineDraft.blank()
        draft.plan.sets = [SetPlan(grip: GripSpec(edgeMM: 20, fingers: .four, position: GripPosition("half crimp")),
                                   repsPerSide: 3)]
        XCTAssertNil(RoutineShare.url(for: draft))
        draft.plan.sets[0].grip.position = .halfCrimp
        XCTAssertNotNil(RoutineShare.url(for: draft))
        draft.plan.sets[0].targetBasisRaw = "not a word"
        XCTAssertNil(RoutineShare.url(for: draft))
    }

    // MARK: - Shape of the payload

    func testNestingIsBoundedAndBracketsInsideStringsDoNotCount() throws {
        func code(depth: Int) throws -> URL {
            let nest = String(repeating: "[", count: depth) + String(repeating: "]", count: depth)
            return try link(#"{"v":1,"x":\#(nest),"plan":{"sets":[\#(oneSetJSON)]}}"#)
        }
        // The envelope itself is one level.
        XCTAssertNoThrow(try RoutineShare.draft(from: code(depth: 63)))
        assertImportFails(.unreadable, from: try code(depth: 64))
        assertImportFails(.unreadable, from: try code(depth: 120_000))

        let brackets = String(repeating: "[", count: 200) + #"\"["#
        XCTAssertNoThrow(try RoutineShare.draft(
            from: link(#"{"v":1,"plan":{"name":"\#(brackets)","sets":[\#(oneSetJSON)]}}"#)))
    }

    func testTheInflatedPayloadIsCappedAtThirtyTwoKilobytes() throws {
        func code(pad: Int) -> String {
            #"{"v":1,"pad":"\#(String(repeating: "A", count: pad))","plan":{"sets":[\#(oneSetJSON)]}}"#
        }
        let room = 32 * 1024 - code(pad: 0).utf8.count
        XCTAssertNoThrow(try RoutineShare.draft(from: link(code(pad: room))))
        assertImportFails(.unreadable, from: try link(code(pad: room + 1)))
        assertImportFails(.unreadable, from: try link(code(pad: 250_000)))
    }

    func testALinkOfTwentyKilobytesIsRefusedBeforeItsFragmentIsRead() throws {
        let long = try XCTUnwrap(URL(string: "getagrip://routine#" + String(repeating: "%41", count: 7_000)))
        assertImportFails(.unreadable, from: long)
    }

    // MARK: - A name is one line of text

    func testANameLosesBidiControlsAndLineBreaks() {
        XCTAssertEqual(RoutineShare.sanitizedName("Max\u{202E} day\nfor\u{2028}two\u{200F}"), "Max day for two")
        XCTAssertEqual(RoutineShare.sanitizedName("  \u{0007}Daily\u{0000} no-hangs\r\n "), "Daily  no-hangs")
        XCTAssertEqual(RoutineShare.sanitizedName("Réglette 20 mm · 上"), "Réglette 20 mm · 上", "ordinary text is untouched")
    }

    /// Characters are the wrong unit: one base letter can carry any number of marks.
    func testANameIsCappedInScalarsNotCharacters() {
        let zalgo = "a" + String(repeating: "\u{0301}", count: 5_000)
        XCTAssertEqual(zalgo.count, 1, "one Character, which is how it used to pass a 60-Character cap")
        XCTAssertEqual(RoutineShare.sanitizedName(zalgo).unicodeScalars.count, 60)

        let astral = String(repeating: "A", count: 59) + "😀😀"
        let capped = RoutineShare.sanitizedName(astral)
        XCTAssertEqual(capped.unicodeScalars.count, 60)
        XCTAssertTrue(capped.hasSuffix("😀"), "the cut never lands inside a scalar")
        XCTAssertEqual(RoutineShare.sanitizedName(String(repeating: "A", count: 200)).count, 60)
    }

    // MARK: - Pasted from an AI chat

    func testAnOversizedPasteIsRefusedBeforeItIsRead() {
        let routine = #"{"format":"get-a-grip-routine/1","name":"x","sets":[{"edgeMm":20,"fingers":"4","grip":"half-crimp","pulls":6}]}"#
        let padding = String(repeating: " ", count: AgentRoutine.maxInputUnits - routine.utf16.count)
        if case .failure = AgentRoutine.read(padding + routine) {
            XCTFail("a reply exactly at the cap is still read")
        }
        guard case .failure(.unreadable) = AgentRoutine.read(padding + " " + routine) else {
            return XCTFail("one unit past the cap is refused")
        }
    }

    func testAPastedNameIsCleanedLikeASharedOne() {
        let text = #"{"format":"get-a-grip-routine/1","name":"Max\u202e day\nplan","sets":[{"edgeMm":20,"fingers":"4","grip":"half-crimp","pulls":6}]}"#
        guard case .success(let reading) = AgentRoutine.read(text) else { return XCTFail("the routine reads") }
        XCTAssertEqual(reading.draft.plan.name, "Max day plan")
    }

    // MARK: - A Bluetooth peer is not a gauge until it behaves like one

    private func ascii(_ text: String) -> Data { Data(text.utf8) }

    /// 16 bytes as 32 hex characters: index, battery, three signed 24-bit samples.
    private func packet(_ left: Int, _ centre: Int, _ right: Int) -> Data {
        func cell(_ v: Int) -> String {
            let u = v & 0xFFFFFF
            return String(format: "%02X%02X%02X", u & 0xFF, (u >> 8) & 0xFF, (u >> 16) & 0xFF)
        }
        return ascii("01002C01" + cell(left) + cell(centre) + cell(right) + "000000\n")
    }

    func testAMotherboardTableCannotSetAnAbsurdScale() {
        var decoder = MotherboardCodec.Decoder()
        _ = decoder.ingest(ascii((0..<4).map { "\($0),0,0,0\n\($0),1,1e300,100\n" }.joined()))
        XCTAssertEqual(decoder.ingest(packet(10, -20, -30)), [], "6e299 kg is not a reading")

        var steep = MotherboardCodec.Decoder()
        _ = steep.ingest(ascii((0..<4).map { "\($0),0,0,0\n\($0),1,2000,100\n" }.joined()))
        XCTAssertEqual(steep.ingest(packet(60, 0, 0)), [], "1200 kg is past the ceiling")
        XCTAssertEqual(steep.ingest(packet(-60, 0, 0)), [], "and so is its mirror image")
        XCTAssertEqual(steep.ingest(packet(5, 0, 0)), [GaugeReading(kg: 100)], "the next plausible packet still reads")
    }

    func testAMotherboardTableCannotGrowWithTheLink() {
        var decoder = MotherboardCodec.Decoder()
        _ = decoder.ingest(ascii("0,0,0,0\n"))
        for index in 1...5_000 {
            _ = decoder.ingest(ascii("0,\(index),\(index),\(index)\n"))
        }
        XCTAssertEqual(decoder.calibrationRowCounts[0], MotherboardCodec.maxCalibrationRows)
        // A fresh dump still replaces the slot: index 0 is the start-of-dump signal.
        _ = decoder.ingest(ascii("0,0,0,0\n0,1,100,100\n"))
        XCTAssertEqual(decoder.calibrationRowCounts[0], 2)
    }

    func testANonFiniteCalibrationRowIsRefused() {
        var decoder = MotherboardCodec.Decoder()
        _ = decoder.ingest(ascii((0..<4).map { "\($0),0,0,0\n\($0),1,inf,50\n\($0),1,nan,60\n\($0),1,100,100\n" }.joined()))
        XCTAssertEqual(decoder.calibrationRowCounts, [2, 2, 2, 2])
        XCTAssertEqual(decoder.ingest(packet(10, -20, -30)), [GaugeReading(kg: 60)])
    }

    func testTheStoreAcceptsOnlyPlausibleReadings() {
        XCTAssertTrue(DeviceStore.isPlausible(0))
        XCTAssertTrue(DeviceStore.isPlausible(-12.5))
        XCTAssertTrue(DeviceStore.isPlausible(1500))
        XCTAssertFalse(DeviceStore.isPlausible(1500.1))
        XCTAssertFalse(DeviceStore.isPlausible(-1e33))
        XCTAssertFalse(DeviceStore.isPlausible(.infinity))
        XCTAssertFalse(DeviceStore.isPlausible(.nan))
    }

    func testASoftwareTareNeverCapturesANonFiniteReading() {
        var tare = SoftwareTare()
        XCTAssertEqual(tare.value(for: 4), 4)
        _ = tare.value(for: .infinity)
        _ = tare.value(for: .nan)
        _ = tare.value(for: 1e9)
        tare.capture()
        XCTAssertEqual(tare.offset, 4, "the last PLAUSIBLE reading is the zero")
        XCTAssertEqual(tare.value(for: 10), 6)
    }

    // MARK: - The calibration request

    func testASerialIsShapedLikeASerial() {
        XCTAssertTrue(FrezCoefficientResolver.isWellFormed(serial: "FrezDyno-000123"))
        XCTAssertTrue(FrezCoefficientResolver.isWellFormed(serial: "AB_12.cd:9"))
        XCTAssertTrue(FrezCoefficientResolver.isWellFormed(serial: String(repeating: "7", count: 64)))
        for hostile in ["", String(repeating: "7", count: 65), "Frez Dyno", "a&serial=b", "a/b", "a\nb",
                        "é", "x?y", "a#b", "a%20b", "a+b"] {
            XCTAssertFalse(FrezCoefficientResolver.isWellFormed(serial: hostile), hostile)
        }
    }

    @MainActor
    func testAMalformedSerialNeverReachesTheNetworkOrTheCache() async {
        let name = "hardening-\(UUID().uuidString)"
        let defaults = UserDefaults(suiteName: name)!
        defer { defaults.removePersistentDomain(forName: name) }
        let calls = CallCounter()
        let resolver = FrezCoefficientResolver(accessKey: "k3y", defaults: defaults) { _ in
            calls.count += 1
            throw URLError(.badURL)
        }
        let result = await resolver.calibration(forSerial: "evil&serial=FrezDyno-000001")
        XCTAssertEqual(result, .failure(.invalidRequest))
        XCTAssertEqual(calls.count, 0, "no request is made")
        XCTAssertTrue(defaults.dictionaryRepresentation().keys.allSatisfy { !$0.hasPrefix("frez.coefficient.") })
    }

    /// A body that never ends is a bad answer, not a missing connection.
    @MainActor
    func testAnOverlongAnswerReadsAsABadResponse() async {
        let name = "hardening-\(UUID().uuidString)"
        let defaults = UserDefaults(suiteName: name)!
        defer { defaults.removePersistentDomain(forName: name) }
        let resolver = FrezCoefficientResolver(accessKey: "k3y", defaults: defaults) { _ in
            throw URLError(.dataLengthExceedsMaximum)
        }
        let result = await resolver.calibration(forSerial: "FrezDyno-000123")
        XCTAssertEqual(result, .failure(.badResponse))
        XCTAssertTrue(defaults.dictionaryRepresentation().keys.allSatisfy { !$0.hasPrefix("frez.coefficient.") })
    }

    // MARK: - The CSV a spreadsheet opens

    func testAFormulaIsNeutralisedByItsFirstScalar() {
        XCTAssertEqual(AnalysisExport.csvCell("=1+1"), "'=1+1")
        XCTAssertEqual(AnalysisExport.csvCell("  @SUM(A1)"), "'  @SUM(A1)")
        XCTAssertEqual(AnalysisExport.csvCell("+cmd"), "'+cmd")
        XCTAssertEqual(AnalysisExport.csvCell("-cmd"), "'-cmd")
        // `=` under a combining mark is ONE Character that is not "=".
        XCTAssertEqual(AnalysisExport.csvCell("=\u{0301}HYPERLINK(1)"), "'=\u{0301}HYPERLINK(1)")
        XCTAssertEqual(AnalysisExport.csvCell("=\u{FE0F}1"), "'=\u{FE0F}1")
    }

    func testASemicolonOrTabCannotSplitACell() {
        XCTAssertEqual(AnalysisExport.csvCell("x;=HYPERLINK(1);y"), "\"x;=HYPERLINK(1);y\"")
        XCTAssertEqual(AnalysisExport.csvCell("a\tb"), "\"a\tb\"")
        XCTAssertEqual(AnalysisExport.csvCell("\tx"), "\"'\tx\"")
        XCTAssertEqual(AnalysisExport.csvCell("say \"hi\", ok"), "\"say \"\"hi\"\", ok\"")
        XCTAssertEqual(AnalysisExport.csvCell("line\r\nbreak"), "\"line\r\nbreak\"")
        XCTAssertEqual(AnalysisExport.csvCell("Daily no-hangs"), "Daily no-hangs")
    }

    func testANumberStaysANumber() {
        XCTAssertEqual(AnalysisExport.csvCell("-0.3"), "-0.3")
        XCTAssertEqual(AnalysisExport.csvCell("-12"), "-12")
        XCTAssertEqual(AnalysisExport.csvCell("42.0"), "42.0")
        // Anything that only LOOKS numeric at the front is text, and guarded.
        XCTAssertEqual(AnalysisExport.csvCell("-1+1"), "'-1+1")
        XCTAssertEqual(AnalysisExport.csvCell("-"), "'-")
        XCTAssertEqual(AnalysisExport.csvCell("-.5"), "'-.5")
        XCTAssertEqual(AnalysisExport.csvCell("-1."), "'-1.")
    }

    // MARK: - Helpers

    /// A transport closure is `@Sendable`; the test only ever touches this from one place.
    private final class CallCounter: @unchecked Sendable { var count = 0 }

    private func link(_ json: String) throws -> URL {
        let deflated = try (Data(json.utf8) as NSData).compressed(using: .zlib) as Data
        var payload = deflated.base64EncodedString()
            .replacingOccurrences(of: "+", with: "-")
            .replacingOccurrences(of: "/", with: "_")
        while payload.hasSuffix("=") { payload.removeLast() }
        return try XCTUnwrap(URL(string: "getagrip://routine#" + payload))
    }

    private func assertImportFails(_ expected: RoutineShareError, from url: URL,
                                   file: StaticString = #filePath, line: UInt = #line) {
        XCTAssertThrowsError(try RoutineShare.draft(from: url), file: file, line: line) { error in
            XCTAssertEqual(error as? RoutineShareError, expected, file: file, line: line)
        }
    }
}
