// SPDX-License-Identifier: MPL-2.0
// Original contributions Copyright 2026 Nuri Bruner.

import SwiftData
import XCTest
@testable import Doigt

/// Child collections are blob-encoded Codable `Data`, not CloudKit relationships, so
/// schema migration becomes Codable forward-compatibility. That trade only pays if the
/// decoders are genuinely lenient: a routine that a newer build wrote, or a record a
/// half-finished sync mangled, must degrade to something usable rather than throwing
/// away the whole set list.
///
/// The wire fixtures below are hand-written JSON, not round-trips through our own
/// encoder — an encoder tested against its own decoder agrees with itself about the
/// wrong shape.
@MainActor
final class BlobCodecTests: XCTestCase {

    private func makeTemplate() -> SessionTemplate {
        SessionTemplate(draft: .starter, sortIndex: 0)
    }

    // MARK: - Shape of the bytes

    /// Mirrors Schengen's testDayStampsEncodeFlat: the single-value Codable conformances
    /// are what keep a blob readable by eye and cheap to hand-write a fixture for.
    func testGripSpecEncodesFlat() throws {
        let data = try XCTUnwrap(BlobCodec.encode(
            GripSpec(edgeMM: 20, fingers: .frontTwo, position: .halfCrimp)))
        let json = String(decoding: data, as: UTF8.self)

        XCTAssertEqual(json, #"{"edgeMM":20,"fingers":"IM","position":"halfCrimp"}"#)
        XCTAssertTrue(json.contains(#""fingers":"IM""#))
        XCTAssertFalse(json.contains("rawValue"), "a nested rawValue box would leak the type into the wire format")
    }

    func testReminderTimeEncodesAsABareInt() throws {
        let data = try XCTUnwrap(BlobCodec.encode([ReminderTime(hour: 8, minute: 0),
                                                   ReminderTime(hour: 19, minute: 0)]))
        XCTAssertEqual(String(decoding: data, as: UTF8.self), "[480,1140]")
    }

    // MARK: - Forward and backward compatibility

    /// The blob an older build wrote: only the fields that existed then. Nothing may
    /// throw, and the absent optionals must read as "follow the routine", not as zero.
    func testOldBlobMissingNewOptionalFieldsStillDecodes() throws {
        let wire = Data("""
        {"id":"8B2C4E8A-0000-4000-8000-000000000001",\
        "grip":{"edgeMM":20,"fingers":"IMRL","position":"halfCrimp"},\
        "repsPerSide":6}
        """.utf8)

        let set = try XCTUnwrap(BlobCodec.decode(SetPlan.self, from: wire))
        XCTAssertEqual(set.repsPerSide, 6)
        XCTAssertEqual(set.grip.key, "20|IMRL|halfCrimp")
        XCTAssertNil(set.holdSeconds)
        XCTAssertNil(set.restSeconds)
        XCTAssertNil(set.targetLoKg)
        XCTAssertNil(set.targetHiKg)
        XCTAssertEqual(set.note, "")
        XCTAssertFalse(set.overridesTiming)
        XCTAssertFalse(set.hasTarget)
        XCTAssertNil(set.targetLoPercent)
        XCTAssertNil(set.targetHiPercent)
        XCTAssertFalse(set.hasPercentTarget, "absent means follow the routine, not 0 %")
    }

    /// A routine blob written before the release gate and the percentage band existed.
    /// The absent flag must read `true` — the routine GAINS the behaviour rather than
    /// keeping the old one, which is the deliberate default and the whole reason a plain
    /// `Bool` (not an optional) is the right shape for it.
    func testOldPlanBlobGainsTheReleaseGateAndNoTargetBand() throws {
        let wire = Data("""
        {"name":"Daily no-hangs","sets":[],"handMode":"alternateEachRep",\
        "holdSeconds":10,"restSeconds":20,"setBreakSeconds":60,\
        "leadInSeconds":5,"thresholdKg":2}
        """.utf8)

        let plan = try XCTUnwrap(BlobCodec.decode(SessionPlan.self, from: wire))
        XCTAssertTrue(plan.waitForReleaseBeforeRest)
        XCTAssertNil(plan.targetPercentBand, "and no target appears out of nowhere")
        XCTAssertEqual(plan.restSeconds, 20, "the fields that were there are untouched")
    }

    /// Both new fields survive a full round trip, and an out-of-range percentage is
    /// clamped on the way in rather than resolving to a nonsense load.
    func testTargetPercentagesRoundTripAndClamp() throws {
        var plan = SessionPlan()
        plan.waitForReleaseBeforeRest = false
        plan.targetLoPercent = 0.17
        plan.targetHiPercent = 0.22
        var set = SetPlan(grip: GripSpec(), repsPerSide: 3)
        set.targetLoPercent = 0.40
        set.targetHiPercent = 0.45
        plan.sets = [set]

        let data = try XCTUnwrap(BlobCodec.encode(plan))
        let back = try XCTUnwrap(BlobCodec.decode(SessionPlan.self, from: data))
        XCTAssertFalse(back.waitForReleaseBeforeRest)
        XCTAssertEqual(back.targetPercentBand, 0.17...0.22)
        XCTAssertEqual(back.sets.first?.targetPercentBand, 0.40...0.45)

        let absurd = Data("""
        {"name":"x","sets":[],"targetLoPercent":-2,"targetHiPercent":400}
        """.utf8)
        let clamped = try XCTUnwrap(BlobCodec.decode(SessionPlan.self, from: absurd))
        XCTAssertEqual(clamped.targetPercentBand, SetPlan.percentRange)
    }

    /// `try?` in `KeyedDecodingContainer.value(_:or:)`, not `try`: a field a newer build
    /// retyped costs that one field, never the whole routine.
    func testFieldWithAWrongTypeFallsBackInsteadOfKillingTheSet() throws {
        let wire = Data("""
        {"id":"8B2C4E8A-0000-4000-8000-000000000002",\
        "grip":{"edgeMM":20,"fingers":"IM","position":"openHand"},\
        "repsPerSide":"six","note":"middle two"}
        """.utf8)

        let set = try XCTUnwrap(BlobCodec.decode(SetPlan.self, from: wire))
        XCTAssertEqual(set.repsPerSide, 6, "the retyped field falls back to its default")
        XCTAssertEqual(set.grip.key, "20|IM|openHand", "and every other field survives intact")
        XCTAssertEqual(set.note, "middle two")
    }

    /// `decodeArray`'s whole reason to exist. A single mangled element must cost one
    /// set row, not six.
    func testOneStructurallyBrokenElementDoesNotCostTheWholeRoutine() {
        let wire = Data("""
        [{"id":"8B2C4E8A-0000-4000-8000-000000000003",\
        "grip":{"edgeMM":20,"fingers":"IMRL","position":"halfCrimp"},"repsPerSide":6},\
        "nope",\
        {"id":"8B2C4E8A-0000-4000-8000-000000000004",\
        "grip":{"edgeMM":20,"fingers":"IM","position":"fullCrimp"},"repsPerSide":1}]
        """.utf8)

        let sets = BlobCodec.decodeArray(SetPlan.self, from: wire)
        XCTAssertEqual(sets.count, 2)
        XCTAssertEqual(sets.map(\.grip.key), ["20|IMRL|halfCrimp", "20|IM|fullCrimp"])
    }

    /// A freshly defaulted SwiftData record has `Data()` in every blob column — the
    /// state every routine passes through between `init` and its first `apply`.
    func testEmptyDataDecodesToEmptyArrayRatherThanThrowingOrTrapping() {
        let template = makeTemplate()
        template.setsData = Data()
        template.remindersData = Data()
        template.parkedRemindersData = Data()

        XCTAssertEqual(template.sets, [])
        XCTAssertEqual(template.reminders, [])
        XCTAssertEqual(template.parkedReminders, [])
        XCTAssertEqual(BlobCodec.decodeArray(SetPlan.self, from: Data()), [])
        XCTAssertNil(BlobCodec.decode(SetPlan.self, from: Data()))
    }

    /// A corrupt blob must degrade to a routine somebody can still run, never to a
    /// billion-rep set that hangs the runner or a zero-second hold.
    func testOutOfRangeNumbersAreClampedOnDecode() throws {
        let wire = Data("""
        {"id":"8B2C4E8A-0000-4000-8000-000000000005",\
        "grip":{"edgeMM":0,"fingers":"IMRL","position":"halfCrimp"},\
        "repsPerSide":1000000000,"holdSeconds":0,"restSeconds":100000}
        """.utf8)

        let set = try XCTUnwrap(BlobCodec.decode(SetPlan.self, from: wire))
        XCTAssertEqual(set.repsPerSide, 20, "SetPlan.repsRange upper bound")
        XCTAssertEqual(set.holdSeconds, 1, "SetPlan.holdRange lower bound")
        XCTAssertEqual(set.restSeconds, 600, "SetPlan.restRange upper bound")
        XCTAssertEqual(set.grip.edgeMM, 1, "GripSpec clamps the edge to 1...100")
    }

    /// `.sortedKeys` makes the bytes a pure function of the content. Without it an
    /// unchanged routine re-encodes differently, SwiftData marks the column dirty and
    /// CloudKit syncs a no-op on every save.
    func testEncodingIsByteStable() throws {
        let sets = RoutineDraft.starter.plan.sets
        let first = try XCTUnwrap(BlobCodec.encode(sets))
        let second = try XCTUnwrap(BlobCodec.encode(sets))
        XCTAssertEqual(first, second)

        // Equal values built separately, not the same instance encoded twice.
        let copy = sets.map { $0 }
        XCTAssertEqual(try XCTUnwrap(BlobCodec.encode(copy)), first)

        let grips = try XCTUnwrap(BlobCodec.encode(GripSpec()))
        XCTAssertEqual(String(decoding: grips, as: UTF8.self),
                       #"{"edgeMM":20,"fingers":"IMRL","position":"halfCrimp"}"#)
    }

    /// Blanking the column on an encode failure would turn one un-encodable set into a
    /// routine with no sets at all — a silent total loss where the honest outcome is
    /// "the edit did not take".
    func testTemplateSetterLeavesThePreviousBlobIntactOnEncodeFailure() {
        let template = makeTemplate()
        template.sets = RoutineDraft.starter.plan.sets
        let good = template.setsData
        XCTAssertFalse(good.isEmpty)

        // A non-finite Double is the one value JSONEncoder refuses outright, which
        // makes it the only externally-drivable encode failure.
        var poisoned = SetPlan(grip: GripSpec(edgeMM: 20, fingers: .four, position: .halfCrimp),
                               repsPerSide: 6)
        poisoned.targetLoKg = Double.infinity
        template.sets = [poisoned]

        XCTAssertEqual(template.setsData, good, "a failed encode must not blank the column")
        XCTAssertEqual(template.sets.count, 6)
    }

    // MARK: - Hand mode: lossy in snapshots, verbatim in the live routine

    /// A WorkoutLog is write-once and display-only, so falling back is safe there:
    /// the alternative is a history row that refuses to open.
    func testUnknownHandModeInAFrozenSnapshotDecodesToDefaultWithoutThrowing() throws {
        let wire = Data("""
        {"handMode":"alternateEveryOtherTuesday","holdSeconds":10,"leadInSeconds":5,\
        "name":"Daily no-hangs","restSeconds":20,"setBreakSeconds":60,"sets":[],\
        "thresholdKg":2}
        """.utf8)

        let plan = try XCTUnwrap(BlobCodec.decode(SessionPlan.self, from: wire))
        XCTAssertEqual(plan.handMode, .alternateEachRep)
        XCTAssertEqual(plan.name, "Daily no-hangs")
        XCTAssertEqual(plan.holdSeconds, 10)
        XCTAssertEqual(HandMode(fallback: "alternateEveryOtherTuesday"), .alternateEachRep)
    }

    /// The LIVE routine is the opposite case: `handModeRaw` is a verbatim String column
    /// exactly so a mode written by a newer build survives a round trip through this
    /// one. Reading a template — for the plan, the draft, a summary — must never
    /// normalize the column on the way past.
    func testSessionTemplateKeepsAnUnknownHandModeRawVerbatim() {
        let template = makeTemplate()
        template.handModeRaw = "leftOnly"

        XCTAssertNil(template.handMode, "nil is how this build says 'a mode I do not know'")
        XCTAssertEqual(template.plan.handMode, .alternateEachRep, "engine-facing value falls back")

        // Every read path, then an edit that has nothing to do with the hand mode.
        _ = template.draft
        _ = template.summaryLine
        _ = template.estimatedSeconds
        template.name = "Rest day"

        XCTAssertEqual(template.handModeRaw, "leftOnly")
    }

    // MARK: - Leniency primitives

    func testClampingIsInclusiveOnBothEnds() {
        XCTAssertEqual((3...120).clamping(0), 3)
        XCTAssertEqual((3...120).clamping(1000), 120)
        XCTAssertEqual((3...120).clamping(10), 10)
        XCTAssertEqual((0...20).clamping(-4), 0)
    }
}
