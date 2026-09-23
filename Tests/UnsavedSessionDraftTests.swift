// SPDX-License-Identifier: MPL-2.0
// Original contributions Copyright 2026 Nuri Bruner.

import XCTest
@testable import Doigt

/// A finished session is on disk until it is saved or discarded — see `UnsavedSessionDraft`.
@MainActor
final class UnsavedSessionDraftTests: XCTestCase {
    private var draftDirectory: URL!

    override func setUp() async throws {
        draftDirectory = FileManager.default.temporaryDirectory
            .appending(path: "unsaved-sessions-\(UUID().uuidString)", directoryHint: .isDirectory)
    }

    override func tearDown() async throws {
        try? FileManager.default.removeItem(at: draftDirectory)
    }

    func testAFinishedSessionIsOnDiskUntilItIsAnswered() throws {
        let store = UnsavedSessionDraftStore(directory: draftDirectory)
        let client = RecordingProgressorClient()
        let device = DeviceStore(client: client)
        client.setState(.connected)
        let routine = RunnerFixtures.template()
        let session = RunnerSession(template: routine, device: device,
                                    liveActivity: RunnerActivityRecorder(), cues: RunnerCueRecorder(),
                                    draftStore: store, activityStartDelay: nil)
        session.begin()
        defer { session.end() }
        XCTAssertTrue(store.all().isEmpty, "Nothing is written while the session runs")
        RunnerFixtures.pull(session, kg: 10, samples: 120, from: 1)
        XCTAssertTrue(session.isFinished)

        let draft = try XCTUnwrap(store.load(id: session.sessionID))
        XCTAssertEqual(draft.reps, session.runner.results)
        XCTAssertEqual(draft.plan, session.plan)
        XCTAssertEqual(draft.startedAt, session.startedAt)
        XCTAssertEqual(draft.finishedAt, session.finishedAt)
        XCTAssertEqual(draft.templateID, routine.id)
        XCTAssertEqual(draft.templateName, routine.name)
        XCTAssertEqual(draft.completedCount, 1)
        XCTAssertTrue(LiveSessionDrafts.ids.contains(session.sessionID),
                      "Its summary is open in this process, so recovery must skip it")

        session.clearDraft()
        XCTAssertNil(store.load(id: session.sessionID))
        XCTAssertFalse(LiveSessionDrafts.ids.contains(session.sessionID))
    }

    func testASessionWithNothingToKeepWritesNoDraft() {
        let store = UnsavedSessionDraftStore(directory: draftDirectory)
        let client = RecordingProgressorClient()
        let device = DeviceStore(client: client)
        client.setState(.connected)
        let session = RunnerSession(
            template: RunnerFixtures.template(), device: device,
            liveActivity: RunnerActivityRecorder(), cues: RunnerCueRecorder(),
            draftStore: store, activityStartDelay: nil)
        session.begin()
        defer { session.end() }
        session.send(.abort)
        XCTAssertTrue(session.isFinished)
        XCTAssertTrue(store.all().isEmpty, "The summary offers no Save, so there is nothing to recover")
    }

    func testDraftStoreRoundTripsListsOldestFirstAndDeletes() throws {
        let store = UnsavedSessionDraftStore(directory: draftDirectory)
        var rep = RepSummary()
        rep.heldSeconds = 7.5
        rep.peakKg = 21.25
        rep.targetLoKg = 10
        rep.targetHiKg = 12
        rep.startedElapsedSeconds = 3.2
        var plan = SessionPlan()
        plan.name = "Round trip"
        let newer = UnsavedSessionDraft(id: UUID(), plan: plan, reps: [rep, RepSummary()],
                                        startedAt: Date(timeIntervalSince1970: 2_000),
                                        finishedAt: Date(timeIntervalSince1970: 2_600),
                                        templateID: UUID(), templateName: "Round trip")
        let older = UnsavedSessionDraft(id: UUID(), plan: plan, reps: [rep],
                                        startedAt: Date(timeIntervalSince1970: 1_000),
                                        finishedAt: Date(timeIntervalSince1970: 1_600),
                                        templateID: nil, templateName: "Gone")
        try store.write(newer)
        try store.write(older)
        // A file this build cannot read is skipped, never fatal, and never deleted.
        let garbage = draftDirectory.appending(path: "\(UUID().uuidString).json")
        try Data("{\"id\":".utf8).write(to: garbage)

        XCTAssertEqual(store.load(id: newer.id), newer)
        XCTAssertEqual(store.all(), [older, newer])
        XCTAssertTrue(FileManager.default.fileExists(atPath: garbage.path()))

        store.delete(id: older.id)
        XCTAssertNil(store.load(id: older.id))
        XCTAssertEqual(store.all(), [newer])
        store.delete(id: older.id)   // idempotent
        XCTAssertEqual(store.all(), [newer])
    }

    func testADraftFromANewerBuildStillDecodes() throws {
        let store = UnsavedSessionDraftStore(directory: draftDirectory)
        let draft = UnsavedSessionDraft(id: UUID(), plan: SessionPlan(), reps: [RepSummary()],
                                        startedAt: Date(timeIntervalSince1970: 1_000),
                                        finishedAt: Date(timeIntervalSince1970: 1_100),
                                        templateID: nil, templateName: "Future")
        var object = try XCTUnwrap(JSONSerialization.jsonObject(
            with: UnsavedSessionDraftStore.encoder.encode(draft)) as? [String: Any])
        object["someFieldFromTheFuture"] = ["nested": true]
        try FileManager.default.createDirectory(at: draftDirectory, withIntermediateDirectories: true)
        try JSONSerialization.data(withJSONObject: object)
            .write(to: draftDirectory.appending(path: "\(draft.id.uuidString).json"))
        XCTAssertEqual(store.load(id: draft.id), draft)
    }
}
