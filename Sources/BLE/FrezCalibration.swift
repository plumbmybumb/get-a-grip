// SPDX-License-Identifier: MPL-2.0
// Original contributions Copyright 2026 Nuri Bruner.

import Foundation

// Remote calibration for a gauge whose stream is raw counts (`requiresRemoteCalibration`).
//
// The Frez Dyno sends signed ADC values and leaves the conversion to the client, with
// one per-device slope that only Frez's coefficient API knows. This file is the ONLY
// place the app talks to a server that is not Apple's, so its rules are spelled out:
//
// - Nothing here runs unless the selected gauge needs it AND a unit is connected. Every
//   other gauge never constructs any of it.
// - One request per serial, EVER. The slope is a property of the load cell; once it is
//   in hand it is cached on the device and the network is never asked again for that
//   Dyno. That is also what keeps the account's device and rate limits honest.
// - The session is one-shot: built for the request, invalidated after it. No networking
//   stack lives in the process between requests.
// - The access key is a build setting that lives only in ignored files (see project.yml
//   and BUILDING.md). A build without one still connects and says why there is no force.
// - Failure is fail-closed, as Frez asks: no coefficient, no calibrated force, and the
//   reason is shown rather than a guess at a number.

/// The per-device slope, and where it came from.
struct GaugeCalibration: Sendable, Equatable {
    var coefficient: Double
    /// Answered from the on-device cache, with no request made.
    var cached: Bool
}

/// Why calibrated force is, or is not, available. Published by the store so the gauge
/// screen and the runner can say so instead of showing a silent 0.0 kg.
enum GaugeCalibrationStatus: Sendable, Equatable {
    /// Every gauge that reports kilograms itself.
    case notRequired
    /// Connected; the serial read is in flight.
    case waitingForSerial
    case resolving(serial: String)
    case ready(serial: String, calibration: GaugeCalibration)
    case failed(serial: String?, failure: GaugeCalibrationFailure)

    var isReady: Bool {
        if case .ready = self { return true }
        return false
    }

    /// True while a calibrated gauge cannot produce force — the state the UI must explain.
    var blocksForce: Bool {
        switch self {
        case .notRequired, .ready: false
        case .waitingForSerial, .resolving, .failed: true
        }
    }
}

/// Frez's documented error table, plus the two failures that happen before a request.
enum GaugeCalibrationFailure: Error, Sendable, Equatable {
    /// The Serial Number characteristic was absent, unreadable or empty.
    case missingSerial
    /// This build carries no access key (forks, CI, a local build without the ignored file).
    case noAccessKey
    /// 400 invalid_request
    case invalidRequest
    /// 401 invalid_access_key
    case invalidAccessKey
    /// 403 device_limit_reached / developer_device_not_allowlisted
    case deviceLimitReached
    /// 404 device_not_found
    case deviceNotFound
    /// 409 device_ownership_review_required
    case ownershipReview
    /// 422 coefficient_request_failed
    case calibrationUnavailable
    /// 429 service_unavailable
    case rateLimited
    /// A 200 whose body carried no usable `a`, or an unexpected status.
    case badResponse
    /// The request never completed: offline, timed out, TLS.
    case network(String)

    /// One line for the gauge screen. Every case names what to DO where there is
    /// something to do; the rest name whose problem it is.
    var label: String {
        switch self {
        case .missingSerial: String(localized: "This Dyno did not report a serial number, so its calibration cannot be looked up.")
        case .noAccessKey: String(localized: "This build has no Frez access key, so the Dyno's calibration cannot be fetched.")
        case .invalidRequest: String(localized: "Frez rejected the calibration request.")
        case .invalidAccessKey: String(localized: "Frez did not accept this app's access key.")
        case .deviceLimitReached: String(localized: "Frez has reached the number of Dynos this app may register.")
        case .deviceNotFound: String(localized: "Frez has no calibration on file for this Dyno.")
        case .ownershipReview: String(localized: "Frez is reviewing this Dyno's ownership before it can be registered.")
        case .calibrationUnavailable: String(localized: "Frez could not produce a calibration for this Dyno.")
        case .rateLimited: String(localized: "Frez is busy. Try again in a minute.")
        case .badResponse: String(localized: "Frez sent an answer this app could not read.")
        case .network: String(localized: "The calibration lookup needs an internet connection the first time a Dyno is used.")
        }
    }
}

/// The seam between the client and whatever answers the coefficient question — Frez's
/// API in the app, a scripted answer in tests.
@MainActor
protocol GaugeCalibrationResolver: AnyObject {
    func calibration(forSerial serial: String) async -> Result<GaugeCalibration, GaugeCalibrationFailure>
}

/// `GET https://api.frez.app/functions/v1/dyno-coefficient?serial=…` with the access key in a
/// header, exactly one query parameter (Frez rejects both or neither), answered by
/// `{"a": α}`.
@MainActor
final class FrezCoefficientResolver: GaugeCalibrationResolver {
    typealias Transport = @Sendable (URLRequest) async throws -> (Data, HTTPURLResponse)

    nonisolated static let endpoint = URL(string: "https://api.frez.app/functions/v1/dyno-coefficient")!
    nonisolated static let accessKeyHeader = "X-Frez-Access-Key"
    /// The Info.plist key `project.yml` fills from the `FREZ_ACCESS_KEY` build setting.
    nonisolated static let infoPlistKey = "FrezAccessKey"
    /// Frez's own example uses five seconds; a first-time lookup on a slow link is worth
    /// waiting that long for, and a stuck one must not hold the gauge screen hostage.
    /// `nonisolated` because the one-shot transport reads it off the main actor.
    nonisolated static let timeoutSeconds: TimeInterval = 5

    nonisolated static func cacheKey(serial: String) -> String { "frez.coefficient.\(serial)" }

    /// The bundled key, or nil when the build carries none. An empty string is "none":
    /// `project.yml` defaults the setting to "" so forks and CI build without a key.
    static var bundledAccessKey: String? {
        guard let raw = Bundle.main.object(forInfoDictionaryKey: infoPlistKey) as? String else { return nil }
        let trimmed = raw.trimmingCharacters(in: .whitespacesAndNewlines)
        return trimmed.isEmpty ? nil : trimmed
    }

    private let accessKey: String?
    private let defaults: UserDefaults
    private let transport: Transport

    init(accessKey: String? = FrezCoefficientResolver.bundledAccessKey,
         defaults: UserDefaults = AppGroup.defaults ?? .standard,
         transport: @escaping Transport = FrezCoefficientResolver.oneShotSession) {
        self.accessKey = accessKey
        self.defaults = defaults
        self.transport = transport
    }

    func calibration(forSerial serial: String) async -> Result<GaugeCalibration, GaugeCalibrationFailure> {
        // The cache first, and before the key check on purpose: a Dyno calibrated on a
        // build that had a key keeps working on one that does not.
        let key = Self.cacheKey(serial: serial)
        if let cached = defaults.object(forKey: key) as? Double, Self.isUsable(cached) {
            return .success(GaugeCalibration(coefficient: cached, cached: true))
        }
        guard let accessKey else { return .failure(.noAccessKey) }

        guard var components = URLComponents(url: Self.endpoint, resolvingAgainstBaseURL: false) else {
            return .failure(.invalidRequest)
        }
        components.queryItems = [URLQueryItem(name: "serial", value: serial)]
        guard let url = components.url else { return .failure(.invalidRequest) }
        var request = URLRequest(url: url, timeoutInterval: Self.timeoutSeconds)
        request.httpMethod = "GET"
        request.setValue(accessKey, forHTTPHeaderField: Self.accessKeyHeader)
        request.setValue("application/json", forHTTPHeaderField: "Accept")

        let data: Data
        let response: HTTPURLResponse
        do {
            (data, response) = try await transport(request)
        } catch {
            return .failure(.network(error.localizedDescription))
        }
        guard response.statusCode == 200 else {
            return .failure(Self.failure(forStatus: response.statusCode))
        }
        guard let coefficient = Self.coefficient(in: data) else { return .failure(.badResponse) }
        defaults.set(coefficient, forKey: key)
        return .success(GaugeCalibration(coefficient: coefficient, cached: false))
    }

    /// `{"a": 0.000012345678}`. A number, or a numeric string — and only a finite,
    /// POSITIVE one: a zero or negative slope would turn every pull into nothing or into
    /// its opposite, which is a corrupt answer, not a calibration.
    static func coefficient(in data: Data) -> Double? {
        guard let object = try? JSONSerialization.jsonObject(with: data) as? [String: Any] else { return nil }
        let value: Double?
        switch object["a"] {
        case let number as NSNumber: value = number.doubleValue
        case let text as String: value = Double(text)
        default: value = nil
        }
        guard let value, isUsable(value) else { return nil }
        return value
    }

    static func isUsable(_ coefficient: Double) -> Bool {
        coefficient.isFinite && coefficient > 0
    }

    /// Frez's documented status table.
    static func failure(forStatus status: Int) -> GaugeCalibrationFailure {
        switch status {
        case 400: .invalidRequest
        case 401: .invalidAccessKey
        case 403: .deviceLimitReached
        case 404: .deviceNotFound
        case 409: .ownershipReview
        case 422: .calibrationUnavailable
        case 429: .rateLimited
        default: .badResponse
        }
    }

    /// One request, one ephemeral session, invalidated on the way out. No cookies, no
    /// cache, no background configuration, nothing that outlives the answer.
    static let oneShotSession: Transport = { request in
        let configuration = URLSessionConfiguration.ephemeral
        configuration.timeoutIntervalForRequest = timeoutSeconds
        configuration.timeoutIntervalForResource = timeoutSeconds
        configuration.waitsForConnectivity = false
        let session = URLSession(configuration: configuration)
        defer { session.finishTasksAndInvalidate() }
        let (data, response) = try await session.data(for: request)
        guard let http = response as? HTTPURLResponse else { throw URLError(.badServerResponse) }
        return (data, http)
    }
}
