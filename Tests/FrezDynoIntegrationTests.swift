// SPDX-License-Identifier: MPL-2.0
// Original contributions Copyright 2026 Nuri Bruner.

import XCTest
@testable import Doigt

/// Everything around the Dyno codec: the registry row, the coefficient lookup, and how
/// its progress reaches the store. No CoreBluetooth and no network anywhere — the
/// resolver takes a transport closure, and the store takes a scripted client.
@MainActor
final class FrezDynoIntegrationTests: XCTestCase {

    // MARK: - Registry

    func testTheDynoIsAConnectedGaugeWithAClockThatNeedsACoefficient() {
        let caps = GaugeKind.frezdyno.capabilities
        XCTAssertTrue(caps.hasDeviceClock, "every record carries device elapsed time")
        XCTAssertFalse(caps.hasHardwareTare, "the zero is arithmetic in the codec")
        XCTAssertFalse(caps.isBroadcast)
        XCTAssertTrue(caps.hasStandardBattery)
        XCTAssertTrue(caps.sustainsBackgroundStreaming)
        XCTAssertEqual(caps.nominalSampleRate, 250)
        XCTAssertFalse(caps.hardwareVerified, "unverified until a Dyno has pulled on this app")
        XCTAssertTrue(caps.requiresRemoteCalibration)
        XCTAssertEqual(caps.protocolSource, .vendorDocumented)

        XCTAssertEqual(GaugeKind.frezdyno.gatt, FrezDynoCodec.profile)
        XCTAssertNil(GaugeKind.frezdyno.makeFrameDecoder(), "no coefficient, no decoder")
        XCTAssertNotNil(GaugeKind.frezdyno.makeCalibratedFrameDecoder(coefficient: 1e-5))
        XCTAssertTrue(GaugeKind.selectable.contains(.frezdyno))
        XCTAssertTrue(DeviceStore.makeClient(for: .frezdyno) is GattGaugeClient)
    }

    /// The coefficient path is the Dyno's alone: handing a slope to a gauge that
    /// reports kilograms itself must be impossible, not merely unused.
    func testNoOtherGaugeNeedsOrAcceptsACoefficient() {
        for kind in GaugeKind.allCases where kind != .frezdyno {
            XCTAssertFalse(kind.capabilities.requiresRemoteCalibration, "\(kind)")
            XCTAssertNil(kind.makeCalibratedFrameDecoder(coefficient: 1e-5), "\(kind)")
        }
    }

    func testProtocolProvenanceIsStatedPerGauge() {
        XCTAssertEqual(GaugeKind.progressor.capabilities.protocolSource, .vendorDocumented)
        XCTAssertEqual(GaugeKind.frezdyno.capabilities.protocolSource, .vendorDocumented)
        for kind in [GaugeKind.whc06, .entralpi, .forceboard, .climbro, .motherboard, .cts500, .pb700bt] {
            XCTAssertEqual(kind.capabilities.protocolSource, .ported, "\(kind)")
        }
    }

    // MARK: - The coefficient lookup

    /// Records what was asked and answers what it was told to. `@unchecked` because the
    /// transport closure must be `@Sendable` and this is a test double touched from one
    /// actor.
    private final class TransportSpy: @unchecked Sendable {
        var requests: [URLRequest] = []
        var status = 200
        var body = Data(#"{"a": 0.000012345678}"#.utf8)
        var error: Error?

        var transport: FrezCoefficientResolver.Transport {
            { [self] request in
                requests.append(request)
                if let error { throw error }
                let response = HTTPURLResponse(url: request.url!, statusCode: status,
                                               httpVersion: nil, headerFields: nil)!
                return (body, response)
            }
        }
    }

    private func freshDefaults() -> UserDefaults {
        let name = "frez-dyno-tests-\(UUID().uuidString)"
        let defaults = UserDefaults(suiteName: name)!
        defaults.removePersistentDomain(forName: name)
        return defaults
    }

    func testTheKeyGoesInTheHeaderAndTheSerialInTheQuery() async throws {
        let spy = TransportSpy()
        let resolver = FrezCoefficientResolver(accessKey: "k3y", defaults: freshDefaults(),
                                               transport: spy.transport)
        let result = await resolver.calibration(forSerial: "FrezDyno-000123")

        XCTAssertEqual(result, .success(GaugeCalibration(coefficient: 0.000012345678, cached: false)))
        let request = try XCTUnwrap(spy.requests.first)
        XCTAssertEqual(request.url?.absoluteString,
                       "https://api.frez.app/functions/v1/dyno-coefficient?serial=FrezDyno-000123")
        XCTAssertEqual(request.httpMethod, "GET")
        XCTAssertEqual(request.value(forHTTPHeaderField: "X-Frez-Access-Key"), "k3y")
        XCTAssertEqual(request.timeoutInterval, 5)
    }

    /// Once per Dyno, ever: the second ask is answered from the device, and only a
    /// different serial spends another request.
    func testACoefficientIsFetchedOnceAndThenServedFromTheCache() async {
        let spy = TransportSpy()
        let defaults = freshDefaults()
        let resolver = FrezCoefficientResolver(accessKey: "k3y", defaults: defaults, transport: spy.transport)

        let first = await resolver.calibration(forSerial: "FrezDyno-000123")
        let second = await resolver.calibration(forSerial: "FrezDyno-000123")
        XCTAssertEqual(first, .success(GaugeCalibration(coefficient: 0.000012345678, cached: false)))
        XCTAssertEqual(second, .success(GaugeCalibration(coefficient: 0.000012345678, cached: true)))
        XCTAssertEqual(spy.requests.count, 1)

        // A fresh resolver over the same defaults is the next launch.
        let relaunched = FrezCoefficientResolver(accessKey: "k3y", defaults: defaults, transport: spy.transport)
        let third = await relaunched.calibration(forSerial: "FrezDyno-000123")
        XCTAssertEqual(third, .success(GaugeCalibration(coefficient: 0.000012345678, cached: true)))
        XCTAssertEqual(spy.requests.count, 1)

        _ = await relaunched.calibration(forSerial: "FrezDyno-000124")
        XCTAssertEqual(spy.requests.count, 2, "a different Dyno is a different question")
    }

    func testABuildWithoutAKeyNeverTouchesTheNetwork() async {
        let spy = TransportSpy()
        let resolver = FrezCoefficientResolver(accessKey: nil, defaults: freshDefaults(), transport: spy.transport)
        let result = await resolver.calibration(forSerial: "FrezDyno-000123")
        XCTAssertEqual(result, .failure(.noAccessKey))
        XCTAssertTrue(spy.requests.isEmpty)
    }

    /// The cache is consulted BEFORE the key: a Dyno calibrated on a build that had one
    /// keeps working on a build that does not.
    func testACachedCoefficientOutlivesTheKey() async {
        let spy = TransportSpy()
        let defaults = freshDefaults()
        defaults.set(0.00002, forKey: FrezCoefficientResolver.cacheKey(serial: "FrezDyno-000123"))
        let resolver = FrezCoefficientResolver(accessKey: nil, defaults: defaults, transport: spy.transport)
        let result = await resolver.calibration(forSerial: "FrezDyno-000123")
        XCTAssertEqual(result, .success(GaugeCalibration(coefficient: 0.00002, cached: true)))
        XCTAssertTrue(spy.requests.isEmpty)
    }

    func testFrezStatusesMapToNamedFailuresAndCacheNothing() async {
        let table: [(Int, GaugeCalibrationFailure)] = [
            (400, .invalidRequest), (401, .invalidAccessKey), (403, .deviceLimitReached),
            (404, .deviceNotFound), (409, .ownershipReview), (422, .calibrationUnavailable),
            (429, .rateLimited), (500, .badResponse),
        ]
        for (status, failure) in table {
            let spy = TransportSpy()
            spy.status = status
            let defaults = freshDefaults()
            let resolver = FrezCoefficientResolver(accessKey: "k3y", defaults: defaults, transport: spy.transport)
            let result = await resolver.calibration(forSerial: "FrezDyno-000123")
            XCTAssertEqual(result, .failure(failure), "HTTP \(status)")
            XCTAssertNil(defaults.object(forKey: FrezCoefficientResolver.cacheKey(serial: "FrezDyno-000123")),
                         "HTTP \(status) must not be remembered as a coefficient")
        }
    }

    func testATransportErrorIsReportedAsNetworkAndCachesNothing() async {
        let spy = TransportSpy()
        spy.error = URLError(.notConnectedToInternet)
        let defaults = freshDefaults()
        let resolver = FrezCoefficientResolver(accessKey: "k3y", defaults: defaults, transport: spy.transport)
        let result = await resolver.calibration(forSerial: "FrezDyno-000123")
        guard case .failure(.network) = result else {
            return XCTFail("expected a network failure, got \(result)")
        }
        XCTAssertNil(defaults.object(forKey: FrezCoefficientResolver.cacheKey(serial: "FrezDyno-000123")))
    }

    /// A zero or negative slope would turn every pull into nothing or its opposite;
    /// that is a corrupt answer, not a calibration.
    func testAnUnusableCoefficientIsABadResponseNotACalibration() async {
        XCTAssertEqual(FrezCoefficientResolver.coefficient(in: Data(#"{"a": 0.000012345678}"#.utf8)), 0.000012345678)
        XCTAssertEqual(FrezCoefficientResolver.coefficient(in: Data(#"{"a": "0.5"}"#.utf8)), 0.5)
        XCTAssertNil(FrezCoefficientResolver.coefficient(in: Data(#"{"a": 0}"#.utf8)))
        XCTAssertNil(FrezCoefficientResolver.coefficient(in: Data(#"{"a": -1e-5}"#.utf8)))
        XCTAssertNil(FrezCoefficientResolver.coefficient(in: Data(#"{"a": "nan"}"#.utf8)))
        XCTAssertNil(FrezCoefficientResolver.coefficient(in: Data(#"{"b": 1}"#.utf8)))
        XCTAssertNil(FrezCoefficientResolver.coefficient(in: Data("not json".utf8)))

        let spy = TransportSpy()
        spy.body = Data(#"{"a": 0}"#.utf8)
        let resolver = FrezCoefficientResolver(accessKey: "k3y", defaults: freshDefaults(), transport: spy.transport)
        let result = await resolver.calibration(forSerial: "FrezDyno-000123")
        XCTAssertEqual(result, .failure(.badResponse))
    }

    // MARK: - The store

    /// The client's calibration diagnostics become store state the screens read, and a
    /// breadcrumb that names the phase but never the serial.
    func testCalibrationDiagnosticsBecomeStoreStateAndASerialFreeBreadcrumb() {
        // The serial in the device name is the point: the breadcrumb assertion below is
        // vacuous against a client that was never named after a Dyno.
        let client = RecordingProgressorClient(kind: .frezdyno, deviceName: "FrezDyno-000123")
        let store = DeviceStore(client: client)
        client.connect()
        XCTAssertTrue(store.state.isConnected)

        client.onDiagnostic?(.calibration(.waitingForSerial))
        XCTAssertEqual(store.calibrationStatus, .waitingForSerial)
        XCTAssertTrue(store.calibrationStatus.blocksForce)

        let calibration = GaugeCalibration(coefficient: 1e-5, cached: true)
        client.onDiagnostic?(.calibration(.ready(serial: "FrezDyno-000123", calibration: calibration)))
        XCTAssertEqual(store.calibrationStatus, .ready(serial: "FrezDyno-000123", calibration: calibration))
        XCTAssertTrue(store.calibrationStatus.isReady)
        XCTAssertFalse(store.calibrationStatus.blocksForce)

        XCTAssertTrue(store.diagnosticEntries.contains { $0.text == "Calibration: ready (cached)" })
        XCTAssertFalse(store.diagnosticEntries.contains { $0.text.contains("000123") },
                       "the ring travels in support mail and must not carry the serial")

        client.onDiagnostic?(.calibration(.failed(serial: "FrezDyno-000123", failure: .invalidAccessKey)))
        XCTAssertEqual(store.calibrationStatus, .failed(serial: "FrezDyno-000123", failure: .invalidAccessKey))
        XCTAssertTrue(store.diagnosticEntries.contains { $0.text == "Calibration: failed (401)" })

        client.disconnect()
        XCTAssertEqual(store.calibrationStatus, .notRequired, "a coefficient belongs to a link")
    }

    func testEveryOtherGaugeReportsCalibrationNotRequired() {
        let store = DeviceStore(client: RecordingProgressorClient(kind: .cts500))
        XCTAssertEqual(store.calibrationStatus, .notRequired)
        XCTAssertFalse(store.calibrationStatus.blocksForce)
    }
}
