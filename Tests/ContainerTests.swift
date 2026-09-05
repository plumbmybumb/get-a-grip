// SPDX-License-Identifier: MPL-2.0
// Original contributions Copyright 2026 Nuri Bruner.

import SwiftData
import XCTest
@testable import Doigt

/// CloudKit's rules for a SwiftData schema are not advisory: break one and the app
/// does not crash, it silently declines to sync — a failure a user discovers months
/// later when their second phone shows an empty History. These tests turn every one of
/// those rules into a compile-and-run gate.
@MainActor
final class ContainerTests: XCTestCase {

    /// The literal every rung of `makeContainer` must pass, and the on-disk filename.
    /// Changing it does not migrate anything — it opens a brand new empty store, which
    /// looks exactly like total data loss.
    private static let storeName = "Doigt"
    private static let cloudKitContainerID = "iCloud.run.nuri.doigt"

    private var schema: Schema {
        Schema([SessionTemplate.self, WorkoutLog.self, MaxRecord.self])
    }

    // MARK: - Schema shape

    /// The rule that costs the most to get wrong: CloudKit refuses a non-optional
    /// attribute with no default, because a record arriving from another device may
    /// simply not carry that field.
    func testEverySwiftDataAttributeIsOptionalOrDefaulted() {
        for entity in schema.entities {
            XCTAssertFalse(entity.attributes.isEmpty, "\(entity.name) has no attributes")
            for attribute in entity.attributes {
                XCTAssertTrue(attribute.isOptional || attribute.defaultValue != nil,
                              "\(entity.name).\(attribute.name) is neither optional nor defaulted")
                XCTAssertFalse(attribute.isUnique,
                               "\(entity.name).\(attribute.name) is unique — CloudKit forbids it")
            }
            XCTAssertTrue(entity.uniquenessConstraints.isEmpty,
                          "\(entity.name) declares #Unique, which CloudKit forbids")
        }
    }

    /// Child collections are blob-encoded `Data` precisely so this stays true: CloudKit
    /// syncs to-many relationships as UNORDERED reference sets, and set order IS the
    /// routine. Zero relationships also sidesteps the delete-rule limitations.
    func testSchemaHasNoRelationships() {
        for entity in schema.entities {
            XCTAssertTrue(entity.relationships.isEmpty,
                          "\(entity.name) declares a relationship: \(entity.relationships.map(\.name))")
        }
        XCTAssertEqual(Set(schema.entities.map(\.name)),
                       ["SessionTemplate", "WorkoutLog", "MaxRecord"])
    }

    // MARK: - Building the real thing

    /// The definitive check that the schema is CloudKit-legal. It is deliberately NOT a
    /// substitute for the on-device one: ad-hoc simulator signing strips the iCloud
    /// entitlement, so the store can fail to load here for reasons that have nothing to
    /// do with the model — those are skipped rather than reported as a schema fault.
    /// Real sync verification still happens on a team-signed device build.
    func testCloudKitConfiguredContainerBuilds() throws {
        let schema = self.schema
        let config = ModelConfiguration(Self.storeName,
                                        schema: schema,
                                        isStoredInMemoryOnly: true,
                                        cloudKitDatabase: .private(Self.cloudKitContainerID))
        do {
            _ = try ModelContainer(for: schema, configurations: [config])
        } catch {
            let description = String(describing: error).lowercased()
            let environmental = ["entitlement", "container identifier", "icloud",
                                 "in-memory", "in memory", "not supported"]
            if environmental.contains(where: { description.contains($0) }) {
                throw XCTSkip("CloudKit store unavailable in this build: \(error)")
            }
            XCTFail("CloudKit-configured container failed to build: \(error)")
        }
    }

    /// The deterministic half of the check above — this one has no environmental excuse
    /// available to it, so a schema that cannot load fails here unconditionally.
    func testLocalContainerBuildsFromTheSameSchema() throws {
        let schema = self.schema
        let config = ModelConfiguration(Self.storeName, schema: schema,
                                        isStoredInMemoryOnly: true, cloudKitDatabase: .none)
        let container = try ModelContainer(for: schema, configurations: [config])

        // And it is genuinely usable, not merely constructible.
        let context = ModelContext(container)
        context.insert(SessionTemplate(draft: .starter, sortIndex: 0))
        try context.save()
        XCTAssertEqual(try context.fetch(FetchDescriptor<SessionTemplate>()).count, 1)
    }

    func testStoreConfigurationNameIsFrozen() {
        let config = ModelConfiguration(Self.storeName, schema: schema, cloudKitDatabase: .none)
        XCTAssertEqual(config.name, "Doigt")
        XCTAssertEqual(config.url.lastPathComponent, "Doigt.store",
                       "the configuration name IS the on-disk filename")
        // Both identifiers below are as painful to retrofit as the bundle id.
        XCTAssertEqual(Self.cloudKitContainerID, "iCloud.run.nuri.doigt")
        XCTAssertEqual(AppGroup.id, "group.run.nuri.doigt")
    }

    // MARK: - Model defaults that CloudKit will actually see

    /// A record that arrives missing a field falls back to these, so they have to be
    /// values the app can render rather than sentinels it has to special-case.
    func testWorkoutLogFreezesItsSnapshotAtInit() throws {
        let plan = RoutineDraft.starter.plan.executable
        let day = DayStamp(year: 2026, month: 8, day: 3)
        var rep = RepSummary()
        rep.grip = plan.sets[0].grip
        rep.heldSeconds = 10.4
        rep.peakKg = 12.5

        let log = WorkoutLog(plan: plan, templateID: nil, templateName: "Daily no-hangs",
                             sessionsPerDayTarget: 2, reps: [rep],
                             startedAt: day.date(), finishedAt: day.date().addingTimeInterval(1290),
                             day: day)

        XCTAssertEqual(log.dayKey, day.raw, "the join for '2 of 2 today' is a cheap Int predicate")
        XCTAssertEqual(log.day, day)
        XCTAssertEqual(log.templateName, "Daily no-hangs")
        XCTAssertEqual(log.plan?.sets.count, 6)
        XCTAssertEqual(log.reps.count, 1)
        XCTAssertEqual(log.reps.first?.grip.key, "20|IMRL|halfCrimp")
        XCTAssertNil(log.grade, "an ungraded session has no RPE, it is not 'easy'")
    }

    func testAWorkoutLogWrittenBeforeTheNewColumnsGetsTheHonestFallbacks() {
        let day = DayStamp(year: 2026, month: 8, day: 3)
        let log = WorkoutLog(plan: SessionPlan(), templateID: nil, templateName: "Old",
                             sessionsPerDayTarget: 1, reps: [],
                             startedAt: day.date(), finishedAt: day.date().addingTimeInterval(90 * 60),
                             day: day)

        XCTAssertNil(log.fingerStrainRaw)
        XCTAssertNil(log.durationMinutes)
        XCTAssertEqual(log.sessionMinutes, 90)
    }

    func testMaxRecordComputesItsKeyRatherThanStoringOne() {
        let grip = GripSpec(edgeMM: 20, fingers: .frontTwo, position: .fullCrimp)
        let record = MaxRecord(grip: grip, kg: 31.5, source: .measured)

        XCTAssertEqual(record.gripKey, "20|IM|fullCrimp")
        XCTAssertEqual(record.grip, grip)
        XCTAssertEqual(record.source, .measured)

        // Setting the grip moves the key with it — a stored key column could disagree
        // with the fields beside it, and then there would be two sources of truth.
        record.grip = GripSpec(edgeMM: 18, fingers: .four, position: .openHand)
        XCTAssertEqual(record.gripKey, "18|IMRL|openHand")
        XCTAssertEqual(record.edgeMM, 18)
        XCTAssertEqual(record.fingersRaw, "IMRL")
        XCTAssertEqual(record.positionRaw, "openHand")
    }
}
