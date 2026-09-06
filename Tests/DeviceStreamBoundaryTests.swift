// SPDX-License-Identifier: MPL-2.0
// Original contributions Copyright 2026 Nuri Bruner.
import XCTest
import UIKit
@testable import Doigt

@MainActor
final class DeviceStreamBoundaryTests: XCTestCase {
    func testTrailingNotificationsCannotReviveAStoppedMeasurement() {
        let client = RecordingProgressorClient()
        let device = DeviceStore(client: client)
        client.connect()
        var delivered = 0
        device.onSample = { _ in delivered += 1 }
        device.startStreaming(cause: .manualMeasurement)
        client.emit(.sample(ForceSample(kg: 5, deviceMicros: 0)))
        let trace = device.trace
        device.stopStreaming(cause: .userStopped)
        client.emit(.sample(ForceSample(kg: 50, deviceMicros: 12_500)))
        XCTAssertEqual(device.currentKg, 0)
        XCTAssertEqual(device.peakKg, 5)
        XCTAssertEqual(device.trace, trace)
        XCTAssertEqual(delivered, 1)
        device.startStreaming(cause: .manualMeasurement)
        client.emit(.sample(ForceSample(kg: 3, deviceMicros: 0)))
        XCTAssertEqual(delivered, 2)
        XCTAssertEqual(device.currentKg, 3)
        device.stopStreaming(cause: .userStopped)
    }
    func testDisconnectedTareDoesNotResetMeasurementOrWrite() {
        let client = RecordingProgressorClient()
        let device = DeviceStore(client: client)
        device.tare()
        XCTAssertTrue(client.commands.isEmpty)
    }
    func testFinishingStreamWhileBackgroundedBeginsIdleGrace() {
        let client = RecordingProgressorClient()
        let device = DeviceStore(client: client)
        var began = 0
        device.beginAssertion = { _ in began += 1; return UIBackgroundTaskIdentifier(rawValue: 99) }
        device.endAssertion = { _ in }
        client.connect()
        device.startStreaming(cause: .manualMeasurement)
        device.beginBackgroundGrace()
        XCTAssertEqual(began, 0)
        device.stopStreaming(cause: .userStopped)
        XCTAssertEqual(began, 1)
        XCTAssertTrue(device.state.isConnected)
        device.cancelBackgroundGrace()
    }
    func testReleasingStoreEndsOutstandingBackgroundAssertion() {
        let client = RecordingProgressorClient()
        var ended = 0
        var device: DeviceStore? = DeviceStore(client: client)
        let isReleased = { [weak device] in device == nil }
        device?.beginAssertion = { _ in UIBackgroundTaskIdentifier(rawValue: 99) }
        device?.endAssertion = { _ in ended += 1 }
        client.connect()
        device?.beginBackgroundGrace()
        device = nil
        XCTAssertTrue(isReleased())
        XCTAssertEqual(ended, 1)
    }
}
