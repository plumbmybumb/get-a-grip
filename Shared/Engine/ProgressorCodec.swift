// SPDX-License-Identifier: MPL-2.0
// Original contributions Copyright 2026 Nuri Bruner.

import Foundation

// The Tindeq Progressor wire protocol — pure value types and pure functions.
//
// NOTHING in this file may import CoreBluetooth: the codec has to be testable
// byte-for-byte on any machine, and the Simulator has no Bluetooth stack at all.
// The GATT identifiers live here as strings; the BLE layer turns them into CBUUIDs.
//
// Source: Tindeq's published Progressor API (tindeq.com/progressor_api), whose
// opcode/response tables are images; the constants below are cross-checked against
// the open-source clients that transcribe them (blims/Tindeq-Progressor-API,
// Stevie-Ray/hangtime-grip-connect).
//
// Everything on the wire is LITTLE-ENDIAN.

// MARK: - GATT identifiers

enum ProgressorGATT {
    /// The custom "Progressor" service. Scan by THIS rather than by name: the
    /// advertised name is `Progressor_<serial>`, which varies per unit.
    static let serviceUUID = "7E4E1701-1EA6-40C9-9DCC-13D34FFEAD57"
    /// Notifications land here. The client must subscribe before any measurement.
    static let dataCharacteristicUUID = "7E4E1702-1EA6-40C9-9DCC-13D34FFEAD57"
    /// Commands are written here.
    static let controlPointCharacteristicUUID = "7E4E1703-1EA6-40C9-9DCC-13D34FFEAD57"
}

// MARK: - Commands

enum ProgressorCommand: UInt8, Sendable, CaseIterable {
    case tare = 100
    case startWeightMeasurement = 101
    case stopWeightMeasurement = 102
    case startPeakRFDMeasurement = 103
    case startPeakRFDSeries = 104
    case addCalibrationPoint = 105
    case saveCalibration = 106
    case getAppVersion = 107
    case getErrorInformation = 108
    case clearErrorInformation = 109
    case enterSleep = 110
    case getBatteryVoltage = 111

    /// Commands whose reply arrives as a `.commandResponse` (tag 0) and therefore
    /// needs the pending command to be interpreted.
    var expectsResponse: Bool {
        switch self {
        case .getAppVersion, .getErrorInformation, .getBatteryVoltage: true
        default: false
        }
    }

    /// The bytes to write to the control point.
    ///
    /// Zero-payload commands go out as a BARE OPCODE, not as a 2-byte `[opcode, 0]`
    /// TLV — that is what every field-proven open-source client sends and what the
    /// firmware answers. Only `addCalibrationPoint` carries a payload.
    var encoded: Data { Data([rawValue]) }

    /// Calibration is the one command with a payload: `[opcode, length, float32]`.
    ///
    /// UNVERIFIED against hardware — v1 never calibrates, and a wrong write here
    /// could corrupt a device's calibration table. Confirm the exact layout against
    /// Tindeq's own tables before exposing any calibration UI.
    static func addCalibrationPoint(knownWeightKg: Float) -> Data {
        var payload = Data([ProgressorCommand.addCalibrationPoint.rawValue, 4])
        withUnsafeBytes(of: knownWeightKg.bitPattern.littleEndian) { payload.append(contentsOf: $0) }
        return payload
    }
}

// MARK: - Samples and events

/// One force reading straight off the device.
///
/// `deviceMicros` is the device's own microsecond clock since the measurement
/// started — NOT a host timestamp. All work-phase timing uses deltas of this,
/// because BLE delivery jitter (samples arrive ~80/s batched into ~10 packets/s)
/// makes host arrival time useless for measuring how long someone actually pulled.
///
/// It is a `UInt32`, so it WRAPS every 2^32 µs ≈ 71.6 minutes. Always subtract with
/// `&-` (see `microsSince`), never compare absolute values across a long span.
struct ForceSample: Sendable, Equatable {
    var kg: Double
    var deviceMicros: UInt32
    /// True on the first force sample emitted by one BLE notification. The runner uses
    /// this boundary to reject a retransmitted batch as a unit rather than accepting its
    /// later, apparently-forward samples one by one.
    var isBatchStart: Bool

    init(kg: Double, deviceMicros: UInt32, isBatchStart: Bool = true) {
        self.kg = kg
        self.deviceMicros = deviceMicros
        self.isBatchStart = isBatchStart
    }

    /// Elapsed microseconds from `earlier` to this sample, correct across the
    /// UInt32 wrap. Consecutive samples are ~12.5 ms apart against a 71.6-minute
    /// period, so at most one wrap can ever separate two of them.
    func microsSince(_ earlier: ForceSample) -> UInt32 {
        deviceMicros &- earlier.deviceMicros
    }
}

/// Everything the device can tell us. The codec decodes the FULL tag surface even
/// though the timed-hang runner only consumes `.sample`, `.battery` and
/// `.appVersion` — decoding the rest costs nothing today and is what makes a future
/// RFD or max-strength mode an addition rather than a refactor.
enum ProgressorEvent: Sendable, Equatable {
    case sample(ForceSample)
    case battery(millivolts: UInt32)
    /// Battery as a ready-made 0…1 fraction, from gauges that report the standard
    /// Battery Service percentage. The Progressor keeps `.battery` — its raw
    /// millivolts go through its own discharge curve, and forcing one shape on the
    /// other would bake a Tindeq-specific curve into every ported device.
    case batteryFraction(Double)
    case appVersion(String)
    case errorInformation(String)
    /// Peak rate of force development. Layout INFERRED from the weight format
    /// (float + µs pair) and unverified on hardware — do not ship an RFD feature
    /// without confirming it.
    case rfdPeak(kg: Double, micros: UInt32)
    case rfdPeakSeries([ForceSample])
    case lowPowerWarning
    /// A tag-0 reply we can't attribute (no pending command, or an empty ack).
    case commandResponse(Data)
    case unknown(tag: UInt8, payload: Data)
}

// MARK: - Decoder

enum ProgressorCodec {
    /// Response tags carried in byte 0 of every notification.
    enum ResponseTag: UInt8 {
        case commandResponse = 0
        case weightMeasurement = 1
        case rfdPeak = 2
        case rfdPeakSeries = 3
        case lowPowerWarning = 4
    }

    /// Battery in millivolts, roughly 3.0 V empty → 4.2 V full on the Progressor's
    /// LiPo. Only used for the rough level shown on the device chip.
    static let batteryEmptyMV: Double = 3300
    static let batteryFullMV: Double = 4200

    static func batteryFraction(millivolts: UInt32) -> Double {
        let span = batteryFullMV - batteryEmptyMV
        return min(1, max(0, (Double(millivolts) - batteryEmptyMV) / span))
    }

    /// Decode one notification payload.
    ///
    /// `answering` is the command we are still waiting on, which is the only way to
    /// tell a tag-0 battery reply from a tag-0 version reply — the device does not
    /// echo which command it is answering. Pass nil and tag-0 payloads come back as
    /// raw `.commandResponse`.
    ///
    /// Never throws, never traps: a truncated or malformed packet stops the walk and
    /// returns whatever was fully parsed. Real BLE hardware delivers short reads.
    static func decode(_ data: Data, answering pending: ProgressorCommand? = nil) -> [ProgressorEvent] {
        // Index from zero regardless of how the Data was sliced upstream.
        decode(bytes: [UInt8](data), answering: pending)
    }

    static func decode(bytes: [UInt8], answering pending: ProgressorCommand? = nil) -> [ProgressorEvent] {
        var events: [ProgressorEvent] = []
        var i = 0
        // A notification normally carries one TLV block, but the walk is a loop so a
        // packed packet decodes correctly too.
        while i + 2 <= bytes.count {
            let tag = bytes[i]
            let length = Int(bytes[i + 1])
            let start = i + 2
            let end = start + length
            // Declared length runs past the packet: stop cleanly rather than
            // fabricating values from whatever bytes happen to follow.
            guard end <= bytes.count else { break }
            let payload = Array(bytes[start..<end])
            events.append(contentsOf: decodeBlock(tag: tag, payload: payload, answering: pending))
            i = end
        }
        return validateWeightTimeline(in: events)
    }

    private static func decodeBlock(tag: UInt8, payload: [UInt8],
                                    answering pending: ProgressorCommand?) -> [ProgressorEvent] {
        switch ResponseTag(rawValue: tag) {
        case .weightMeasurement:
            guard !payload.isEmpty, payload.count.isMultiple(of: 8) else {
                return [.unknown(tag: tag, payload: Data(payload))]
            }
            return samples(in: payload).map { .sample($0) }

        case .commandResponse:
            switch pending {
            case .getBatteryVoltage:
                guard let mv = uint32(payload, at: 0) else { return [.commandResponse(Data(payload))] }
                return [.battery(millivolts: mv)]
            case .getAppVersion:
                return [.appVersion(ascii(payload))]
            case .getErrorInformation:
                return [.errorInformation(ascii(payload))]
            default:
                return [.commandResponse(Data(payload))]
            }

        case .rfdPeak:
            guard payload.count == 8, let s = samples(in: payload).first else {
                return [.unknown(tag: tag, payload: Data(payload))]
            }
            return [.rfdPeak(kg: s.kg, micros: s.deviceMicros)]

        case .rfdPeakSeries:
            guard !payload.isEmpty, payload.count.isMultiple(of: 8) else {
                return [.unknown(tag: tag, payload: Data(payload))]
            }
            let parsed = samples(in: payload)
            guard !parsed.isEmpty else { return [.unknown(tag: tag, payload: Data(payload))] }
            return [.rfdPeakSeries(parsed)]

        case .lowPowerWarning:
            return [.lowPowerWarning]

        case nil:
            return [.unknown(tag: tag, payload: Data(payload))]
        }
    }

    /// Validate all emitted weight samples across the WHOLE notification, including
    /// packed tag-1 blocks. A Progressor sample normally arrives every ~12.5 ms. One
    /// interval over 200 ms, or an aggregate span averaging over 25 ms per emitted
    /// sample, means the device timeline is corrupt; suppress the notification as a unit
    /// so none of it can arm, accrue, peak, or release a rep.
    ///
    /// The batch marker is assigned here, after force filtering and notification-wide
    /// validation. Assigning it inside `samples(in:)` would lose the boundary whenever a
    /// rejected leading pair preceded the first usable sample.
    private static func validateWeightTimeline(in events: [ProgressorEvent]) -> [ProgressorEvent] {
        let weightSamples = events.compactMap { event -> ForceSample? in
            guard case .sample(let sample) = event else { return nil }
            return sample
        }
        guard !weightSamples.isEmpty else { return events }

        for index in weightSamples.indices.dropFirst() {
            let delta = weightSamples[index].microsSince(weightSamples[index - 1])
            guard delta <= 200_000 else {
                return events.filter { if case .sample = $0 { false } else { true } }
            }
        }

        guard let first = weightSamples.first, let last = weightSamples.last else { return events }
        let totalSpan = UInt64(last.microsSince(first))
        let spanBudget = UInt64(weightSamples.count) * 25_000
        guard totalSpan <= spanBudget else {
            return events.filter { if case .sample = $0 { false } else { true } }
        }

        var markedStart = false
        return events.map { event in
            guard case .sample(let sample) = event else { return event }
            var marked = sample
            marked.isBatchStart = !markedStart
            markedStart = true
            return .sample(marked)
        }
    }

    /// Walk a structurally valid payload of repeated 8-byte `(float32 kg, uint32 µs)`
    /// pairs, dropping readings a 150 kg load cell cannot physically produce. The
    /// −10…165 kg window allows negative zero drift and 10% calibration tolerance while
    /// rejecting finite garbage at the codec choke point, before it can reach the runner,
    /// trace, store, or recorded maxes.
    private static func samples(in payload: [UInt8]) -> [ForceSample] {
        var out: [ForceSample] = []
        out.reserveCapacity(payload.count / 8)
        var i = 0
        while i + 8 <= payload.count {
            guard let raw = uint32(payload, at: i), let micros = uint32(payload, at: i + 4) else { break }
            let kg = Double(Float(bitPattern: raw))
            if kg.isFinite, kg >= -10, kg <= 165 {
                out.append(ForceSample(kg: kg, deviceMicros: micros))
            }
            i += 8
        }
        return out
    }

    private static func uint32(_ bytes: [UInt8], at offset: Int) -> UInt32? {
        guard offset >= 0, offset + 4 <= bytes.count else { return nil }
        return UInt32(bytes[offset])
            | UInt32(bytes[offset + 1]) << 8
            | UInt32(bytes[offset + 2]) << 16
            | UInt32(bytes[offset + 3]) << 24
    }

    /// Firmware strings are NUL-padded ASCII.
    private static func ascii(_ bytes: [UInt8]) -> String {
        String(decoding: bytes.prefix { $0 != 0 }, as: UTF8.self)
    }
}
