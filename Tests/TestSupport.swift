// SPDX-License-Identifier: MPL-2.0
// Original contributions Copyright 2026 Nuri Bruner.

import Foundation
import SwiftData
import XCTest
@testable import Doigt

/// The fixtures every unit-test file shares.
///
/// Each of these existed three or four times over, one copy per file, and the copies had
/// already drifted: the settings reset that listed only the fields ONE file cared about
/// leaked a draft stash from `TemplateStoreTests` into `DataAuditStoreTests`, and three
/// near-identical gauge doubles meant a behaviour fixed in one of them stayed broken in
/// the other two. A fixture with one home cannot drift.

// MARK: - The gauge double

/// The `ProgressorClient` the unit suite drives: it records what was written and lets a
/// test push state changes and events by hand, which is the only way to exercise the BLE
/// layer at all — **CoreBluetooth does not exist in the Simulator**.
///
/// It replaced `FakeGaugeClient` (MultiGaugeStoreTests) and `ScriptedClient`
/// (FrezDynoIntegrationTests) outright. Both were this class with a field or two missing;
/// neither relied on its own drift (`ScriptedClient` dropped commands and disconnected on
/// `sleepDevice`, and no test ever called either), so recording them is strictly more
/// than those tests asked for. The one thing that WAS load-bearing is `deviceName` — the
/// Frez test proves the diagnostic ring never carries a Dyno's serial, which is vacuous
/// unless the client is actually named after one — so it is a parameter, not a constant.
@MainActor
final class RecordingProgressorClient: ProgressorClient {
    var onEvent: ((ProgressorEvent) -> Void)?
    var onPacketBoundary: ((PacketBoundary) -> Void)?
    var onStateChange: ((ProgressorConnectionState) -> Void)?
    var onDiagnostic: ((ProgressorClientDiagnostic) -> Void)?

    private(set) var state: ProgressorConnectionState = .idle
    private(set) var deviceName: String?
    private(set) var commands: [ProgressorCommand] = []
    /// Which device this stands in for. The timing rules a session picks up at
    /// construction — the credited-gap cap, the silence threshold, whether a re-kick may
    /// break the timeline — all read `kind.capabilities`, so a test about them has to be
    /// able to say what it is driving.
    let kind: GaugeKind

    init(kind: GaugeKind = .progressor, deviceName: String? = "Test gauge") {
        self.kind = kind
        self.deviceName = deviceName
    }

    func connect() { setState(.connected) }

    func disconnect() { setState(.disconnected(reason: nil)) }

    func send(_ command: ProgressorCommand) {
        commands.append(command)
    }

    func startStreaming(cause: StreamStartCause) {
        commands.append(.startWeightMeasurement)
        onDiagnostic?(.streamStartWritten(cause))
    }

    func sleepDevice() { send(.enterSleep) }

    func setState(_ next: ProgressorConnectionState) {
        state = next
        onStateChange?(next)
    }

    func emit(_ event: ProgressorEvent) { onEvent?(event) }
}

// MARK: - Model fixtures

enum TestFixtures {

    /// The app's CloudKit-shaped schema. Four files built this list by hand, so a model
    /// added to the app reached some of them and not others.
    static var schema: Schema {
        Schema([SessionTemplate.self, WorkoutLog.self, MaxRecord.self])
    }

    /// A four-finger half crimp on a 20 mm edge: the routine's own default grip, and what
    /// `GripSpec()` builds. For a test that just needs *a* grip.
    ///
    /// The files whose subject IS this literal — `GripKeyTests` deriving
    /// `"20|IMRL|halfCrimp"`, and the blob round-trips — keep spelling it out on purpose:
    /// there the parameters are the thing under test, not scaffolding.
    static var halfCrimp20: GripSpec {
        GripSpec(edgeMM: 20, fingers: .four, position: .halfCrimp)
    }
}

// MARK: - Shared test-case helpers

extension XCTestCase {

    /// Put every `SettingsStore` value the suite touches back to a known state.
    ///
    /// `SettingsStore` is backed by the App Group's `UserDefaults`, so it is ONE mutable
    /// world shared by the whole bundle and by every previous run on this simulator:
    /// what a test writes is what the next test's `SettingsStore()` reads. Resetting only
    /// the fields one file knew about is exactly how `TemplateStoreTests`' stashed draft
    /// and "last started routine" leaked into `DataAuditStoreTests`. Reset all of them or
    /// none of them.
    ///
    /// `didAskNotificationPermission` is deliberately NOT its shipping default: no test
    /// wants the contextual permission ask, and both original copies set it to true.
    @MainActor
    @discardableResult
    func resetSettings(_ settings: SettingsStore) -> SettingsStore {
        settings.builderGuideDone = false
        settings.didAskNotificationPermission = true
        settings.lastStartedRoutineID = nil
        settings.lastStartedDayRaw = 0
        settings.draftStash = nil
        settings.weightUnit = .kg
        settings.remindsOnThisDevice = true
        settings.frezIntroSeen = false
        settings.reviewRequested = false
        return settings
    }

    /// Poll until `condition` holds, then assert that it did.
    ///
    /// For the genuinely asynchronous doubles: the mock gauge delivers on a timer, so
    /// there is no callback to await and a fixed sleep is either flaky or slow.
    @MainActor
    func waitUntil(_ message: @autoclosure () -> String = "The condition never became true",
                   timeout: Duration = .seconds(3),
                   file: StaticString = #filePath, line: UInt = #line,
                   _ condition: () -> Bool) async throws {
        let deadline = ContinuousClock.now + timeout
        while !condition(), ContinuousClock.now < deadline {
            try await Task.sleep(for: .milliseconds(10))
        }
        XCTAssertTrue(condition(), message(), file: file, line: line)
    }
}
