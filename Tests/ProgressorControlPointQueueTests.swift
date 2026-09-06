// SPDX-License-Identifier: MPL-2.0
// Original contributions Copyright 2026 Nuri Bruner.
import XCTest
@testable import Doigt

@MainActor
final class ProgressorControlPointQueueTests: XCTestCase {
    private final class Transport: ProgressorControlPointTransport {
        var canWrite = true
        var writesWithResponse = true
        var readyWithoutResponse = true
        var writes: [ProgressorCommand] = []
        var replyDeadline: UInt64?
        var writeDeadline: UInt64?
        var failure: String?
        var slept = false
        func write(_ command: ProgressorCommand, withResponse: Bool) { writes.append(command) }
        func failPermanently(reason: String) { failure = reason; canWrite = false }
        func completeSleep() { slept = true }
        func armReplyDeadline(id: UInt64) { replyDeadline = id }
        func cancelReplyDeadline() { replyDeadline = nil }
        func armWriteDeadline(id: UInt64) { writeDeadline = id }
        func cancelWriteDeadline() { writeDeadline = nil }
        func armSleepFallback(id: UInt64) {}
        func diagnostic(_ event: ProgressorClientDiagnostic) {}
    }
    func testQueriesSerializeButControlsBypassTheReplyWait() {
        let transport = Transport(), queue: ProgressorControlPointQueue
        queue = ProgressorControlPointQueue(transport: transport)
        queue.enqueue(.getAppVersion)
        queue.enqueue(.getBatteryVoltage)
        XCTAssertEqual(transport.writes, [.getAppVersion])
        queue.writeCompleted(error: nil)
        queue.enqueue(.stopWeightMeasurement)
        XCTAssertEqual(transport.writes, [.getAppVersion, .stopWeightMeasurement])
        queue.writeCompleted(error: nil)
        XCTAssertEqual(queue.pendingReplyCommand, .getAppVersion)
        queue.commandReplyReceived()
        XCTAssertEqual(transport.writes.last, .getBatteryVoltage)
    }
    func testReplyTimeoutPoisonsQueriesUntilANewPhysicalLink() throws {
        let transport = Transport(), queue: ProgressorControlPointQueue
        queue = ProgressorControlPointQueue(transport: transport)
        queue.enqueue(.getAppVersion)
        let deadline = try XCTUnwrap(transport.replyDeadline)
        queue.writeCompleted(error: nil)
        queue.replyDeadlineFired(id: deadline)
        XCTAssertTrue(queue.isQueryChannelPoisoned)
        queue.enqueue(.getBatteryVoltage)
        queue.commandReplyReceived() // late old reply owns nothing
        XCTAssertNil(queue.pendingReplyCommand)
        queue.enqueue(.stopWeightMeasurement)
        XCTAssertEqual(transport.writes, [.getAppVersion, .stopWeightMeasurement])
        queue.clearLinkState(clearDeferredStart: true)
        queue.linkEstablished()
        queue.enqueue(.getBatteryVoltage)
        XCTAssertEqual(queue.pendingReplyCommand, .getBatteryVoltage)
    }
    func testDisconnectedTareCannotReachANewLink() {
        let transport = Transport(), queue: ProgressorControlPointQueue
        queue = ProgressorControlPointQueue(transport: transport)
        transport.canWrite = false
        queue.enqueue(.tare)
        queue.enqueue(.startWeightMeasurement, startCause: .initial)
        transport.canWrite = true
        queue.linkEstablished()
        queue.enqueue(.startWeightMeasurement, startCause: .initial)
        XCTAssertEqual(transport.writes, [.startWeightMeasurement])
        XCTAssertFalse(queue.hasDeferredStart)
    }
    func testUnknownWriteCompletionEndsTheLinkAndLateACKCannotReleaseStart() throws {
        let transport = Transport(), queue: ProgressorControlPointQueue
        queue = ProgressorControlPointQueue(transport: transport)
        queue.enqueue(.tare)
        let deadline = try XCTUnwrap(transport.writeDeadline)
        queue.enqueue(.startWeightMeasurement, startCause: .initial)
        queue.writeDeadlineFired(id: deadline)
        XCTAssertNotNil(transport.failure)
        XCTAssertNil(transport.writeDeadline)
        queue.writeCompleted(error: nil)
        XCTAssertEqual(transport.writes, [.tare])
        XCTAssertFalse(queue.hasDeferredStart)
        transport.canWrite = true
        queue.linkEstablished()
        queue.enqueue(.startWeightMeasurement, startCause: .reconnect)
        XCTAssertEqual(transport.writes.last, .startWeightMeasurement)
        queue.writeDeadlineFired(id: deadline) // stale deadline cannot kill the next write
        XCTAssertTrue(transport.canWrite)
    }
    func testStopCancelsTheStartWaitingForTareAcknowledgement() {
        let transport = Transport(), queue: ProgressorControlPointQueue
        queue = ProgressorControlPointQueue(transport: transport)
        queue.enqueue(.tare)
        queue.enqueue(.startWeightMeasurement, startCause: .initial)
        queue.enqueue(.stopWeightMeasurement)
        queue.writeCompleted(error: nil)
        XCTAssertEqual(transport.writes, [.tare, .stopWeightMeasurement])
        XCTAssertFalse(queue.hasDeferredStart)
    }
    func testFailedSupersededStreamIntentIsNotRetried() {
        for (old, replacement) in [(ProgressorCommand.startWeightMeasurement, ProgressorCommand.stopWeightMeasurement),
                                    (.startWeightMeasurement, .enterSleep), (.stopWeightMeasurement, .startWeightMeasurement)] {
            let transport = Transport()
            let queue = ProgressorControlPointQueue(transport: transport)
            queue.enqueue(old)
            queue.enqueue(replacement)
            queue.writeCompleted(error: "ATT failure")
            XCTAssertEqual(transport.writes, [old, replacement])
            XCTAssertNil(transport.failure)
        }
    }
    func testKnownWriteFailureRetriesOnceThenFails() {
        let transport = Transport(), queue: ProgressorControlPointQueue
        queue = ProgressorControlPointQueue(transport: transport)
        queue.enqueue(.tare)
        queue.writeCompleted(error: "ATT failure")
        XCTAssertEqual(transport.writes, [.tare, .tare])
        queue.writeCompleted(error: "ATT failure")
        XCTAssertNotNil(transport.failure)
    }
    func testWithoutResponseBackpressureAndUnacknowledgeableTareFailClosed() {
        let transport = Transport(), queue: ProgressorControlPointQueue
        queue = ProgressorControlPointQueue(transport: transport)
        transport.writesWithResponse = false
        transport.readyWithoutResponse = false
        queue.enqueue(.getAppVersion)
        XCTAssertTrue(transport.writes.isEmpty)
        transport.readyWithoutResponse = true
        queue.drain()
        XCTAssertEqual(transport.writes, [.getAppVersion])
        queue.enqueue(.tare)
        XCTAssertNotNil(transport.failure)
        XCTAssertEqual(transport.writes, [.getAppVersion])
    }
    func testIncompleteCalibrationCommandHasNoWirePayloadAndCannotBeQueued() {
        let transport = Transport(), queue: ProgressorControlPointQueue
        queue = ProgressorControlPointQueue(transport: transport)
        XCTAssertNil(ProgressorCommand.addCalibrationPoint.encoded)
        queue.enqueue(.addCalibrationPoint)
        XCTAssertTrue(transport.writes.isEmpty)
        XCTAssertTrue(queue.queuedCommands.isEmpty)
    }
}
