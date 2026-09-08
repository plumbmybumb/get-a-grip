// SPDX-License-Identifier: MPL-2.0
// Original contributions Copyright 2026 Nuri Bruner.

import XCTest
@testable import Doigt

@MainActor
final class BroadcastDiagnosticsTests: XCTestCase {
    func testRepeatedScanFactsPreserveFirstTimeAndConnectionHistory() {
        var ring = DiagnosticBreadcrumbRing()
        let first = Date(timeIntervalSince1970: 1)
        ring.append(.connection(.connected), at: first)
        ring.append(.broadcastScan("scan already active (watchdog)"), at: first)
        for index in 2...500 {
            ring.append(.broadcastScan("scan already active (watchdog)"),
                        at: Date(timeIntervalSince1970: Double(index)))
        }
        XCTAssertEqual(ring.entries.count, 2)
        XCTAssertEqual(ring.entries.first?.event, .connection(.connected))
        XCTAssertEqual(ring.entries.last?.date, first)
    }

    func testScanCoalescingDoesNotHideInterveningSignalLoss() {
        var ring = DiagnosticBreadcrumbRing()
        ring.append(.broadcastScan("scan already active (watchdog)"))
        ring.append(.signalFreshness(false))
        ring.append(.broadcastScan("scan already active (watchdog)"))
        XCTAssertEqual(ring.entries.count, 3)
        XCTAssertEqual(ring.entries[1].event, .signalFreshness(false))
    }

    func testStoreExportsActualScanFactsThroughExistingReport() {
        let client = RecordingProgressorClient(kind: .whc06)
        let device = DeviceStore(client: client)
        client.onDiagnostic?(.broadcastScan("scan timed out"))
        XCTAssertEqual(device.diagnosticEntries.last?.event, .broadcastScan("scan timed out"))
        let report = DiagnosticReport.text(from: device.diagnosticEntries)
        XCTAssertTrue(report.contains("Bluetooth scan: scan timed out"))
        XCTAssertFalse(report.contains("Stream start written"))
    }

    func testOnlyBroadcastWatchdogRequestsAreQuietWhileStillReachingClient() {
        for kind in [GaugeKind.whc06, .progressor, .entralpi] {
            let client = RecordingProgressorClient(kind: kind)
            let device = DeviceStore(client: client)
            client.setState(.connected)
            device.startStreaming(cause: .watchdog)
            XCTAssertEqual(client.commands, [.startWeightMeasurement])
            XCTAssertEqual(device.diagnosticEntries.contains {
                $0.event == .streamStartRequested(.watchdog)
            }, !kind.capabilities.isBroadcast)

            device.startStreaming(cause: .manualWake)
            XCTAssertTrue(device.diagnosticEntries.contains {
                $0.event == .streamStartRequested(.manualWake)
            })
        }
    }
}
