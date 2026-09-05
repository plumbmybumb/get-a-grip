// SPDX-License-Identifier: MPL-2.0
// Original contributions Copyright 2026 Nuri Bruner.

import Foundation

// MARK: - Readings

/// One decoded force reading from any supported gauge, before it becomes a
/// `ForceSample`.
///
/// `deviceMicros` is nil for every device except the Progressor: the ported scales
/// and boards carry no sample clock of their own, so the BLE client stamps each
/// reading with `SyntheticSampleClock` at ingestion instead. Downstream nothing
/// changes — the synthetic stamp wraps at the same 2^32 µs the Tindeq's does, and
/// every consumer already subtracts with `&-`.
struct GaugeReading: Sendable, Equatable {
    var kg: Double
    var deviceMicros: UInt32?

    init(kg: Double, deviceMicros: UInt32? = nil) {
        self.kg = kg
        self.deviceMicros = deviceMicros
    }
}

// MARK: - Frame decoding

/// A stateful per-connection frame decoder: raw notification payloads in,
/// kilogram readings out.
///
/// Stateful because some protocols (Motherboard) split one frame across several
/// notifications and need a reassembly buffer. A decoder lives and dies with one
/// connection — link cleanup drops it with the rest of the link state, so no
/// half-frame can ever pair with the next connection's bytes. Truncated or
/// malformed input must stop the walk cleanly and return whatever decoded whole;
/// real BLE delivers short reads.
protocol GaugeFrameDecoder {
    mutating func ingest(_ data: Data) -> [GaugeReading]
    /// Battery level (0…1) for protocols that carry it inside the data stream
    /// (Climbro) rather than in the standard Battery Service. Nil until seen.
    var batteryFraction: Double? { get }
}

extension GaugeFrameDecoder {
    var batteryFraction: Double? { nil }
}

// MARK: - Synthetic sample clock

/// µs stamps for devices that have no clock of their own.
///
/// Built from host MONOTONIC uptime, never `Date` — wall time steps under NTP and
/// timezone changes, and a stamp that jumps backwards would read as a stale batch
/// and trip the engine's fail-closed high-water check. Truncating to `UInt32`
/// reproduces the Tindeq clock's ~71.6-minute wrap on purpose: it keeps one wrap
/// rule, already handled everywhere with `&-`, instead of adding a second timing
/// regime with its own edge cases.
enum SyntheticSampleClock {
    static func micros(uptime: TimeInterval) -> UInt32 {
        UInt32(truncatingIfNeeded: Int64(uptime * 1_000_000))
    }
}

// MARK: - GATT wiring facts

/// The connection facts for a GATT-connected gauge, as STRINGS — this file is in
/// `Shared/`, which must never import CoreBluetooth. `CBUUID` conversion happens
/// in `Sources/BLE`.
struct GaugeGattProfile: Sendable, Equatable {
    var serviceUUID: String
    var notifyCharacteristicUUID: String
    var writeCharacteristicUUID: String?
    /// Written in order to the write characteristic to start the stream.
    /// Empty means subscribing to notifications is itself the start.
    var streamStartPayloads: [Data] = []
    var streamStopPayload: Data?
    /// Written once per LINK, right after subscribing — configuration that must not
    /// ride the repeated start path. Start payloads are re-sent on every re-kick BY
    /// DESIGN (the house rule: never gate the start), so a config write folded into
    /// them would be re-issued ~1500 times across a silent 20-minute session. The
    /// CTS500's sampling-rate command lives here for exactly that reason.
    var oneTimeSetupPayloads: [Data] = []
    /// Seconds to wait between successive `streamStartPayloads` writes. The
    /// Motherboard answers "C" with its calibration table and the reference waits
    /// 2.5 s before sending "S30"; written back-to-back, the start can race the
    /// table home and the device streams packets the decoder must drop.
    var startPayloadDelaySeconds: Double = 0
    /// Extra characteristics to subscribe ALONGSIDE `notifyCharacteristicUUID`,
    /// resolved across every discovered service. For devices where the reference
    /// subscribes everything it marks "rx" and never had to pick one (Entralpi
    /// declares two candidates); subscribing to both is what the reference does,
    /// costs nothing, and the same decoder parses whichever speaks.
    var alternateNotifyCharacteristicUUIDs: [String] = []
    /// Hardware tare, for the devices whose DEVICE tare the reference itself
    /// exercises (CTS500). Everything else gets the client's software tare.
    var tareCharacteristicUUID: String?
    var tarePayload: Data?
}

// MARK: - The registry

/// Every gauge Doigt can drive. One case per physical device family; the codec,
/// capabilities and wiring facts all key off this, so adding a device is one codec
/// file plus one row in each switch below — never a refactor.
enum GaugeKind: String, CaseIterable, Codable, Sendable {
    case progressor
    case whc06
    case entralpi
    case forceboard
    case climbro
    case motherboard
    case cts500
    case pb700bt
}

/// What a given gauge can and cannot do. UI and runner behaviour gate on THESE
/// flags, never on the kind itself — a rule keyed to a capability survives the
/// next device; a rule keyed to a device name is a bug waiting in the one after.
struct GaugeCapabilities: Sendable, Equatable {
    /// Device µs timestamps in the stream (Tindeq only). Without them the client
    /// synthesizes stamps and the runner clamps per-sample credit, because host
    /// arrival gaps are radio facts, not measurements of the hand.
    var hasDeviceClock: Bool
    /// A tare the DEVICE executes. False means the client subtracts a captured
    /// baseline app-side — the Tare button works either way.
    var hasHardwareTare: Bool
    /// Broadcast-only: weight arrives in advertisements, there is no connection
    /// at all (WH-C06). "Connected" means "advertisements are arriving".
    var isBroadcast: Bool
    /// Standard Battery Service 0x180F / 0x2A19 percentage read at connect.
    var hasStandardBattery: Bool
    /// Whether a session may keep running while backgrounded. `bluetooth-central`
    /// keeps a CONNECTED stream alive; broadcast scanning coalesces duplicates in
    /// the background and effectively goes silent, so those sessions must pause.
    var sustainsBackgroundStreaming: Bool
    /// Approximate samples per second — for UI copy and debounce sanity checks,
    /// never for timing.
    var nominalSampleRate: Double
    /// Verified against real hardware by THIS project. Everything false here is a
    /// port of hangtime-grip-connect's documented protocol and Settings says so —
    /// the same honesty rule as the codec's inferred RFD layout.
    var hardwareVerified: Bool
}

extension GaugeKind {
    var displayName: String {
        switch self {
        case .progressor: String(localized: "Tindeq Progressor")
        case .whc06: String(localized: "WH-C06 crane scale")
        case .entralpi: String(localized: "Entralpi force plate")
        case .forceboard: String(localized: "PitchSix Force Board")
        case .climbro: String(localized: "Climbro")
        case .motherboard: String(localized: "Griptonite Motherboard")
        case .cts500: String(localized: "Jlyscales CTS500")
        case .pb700bt: String(localized: "NSD PB-700BT")
        }
    }

    var maker: String {
        switch self {
        case .progressor: String(localized: "Tindeq")
        case .whc06: String(localized: "Weiheng")
        case .entralpi: String(localized: "Entralpi")
        case .forceboard: String(localized: "PitchSix")
        case .climbro: String(localized: "Climbro")
        case .motherboard: String(localized: "Griptonite")
        case .cts500: String(localized: "Jlyscales")
        case .pb700bt: String(localized: "NSD")
        }
    }

    var capabilities: GaugeCapabilities {
        switch self {
        case .progressor:
            GaugeCapabilities(hasDeviceClock: true, hasHardwareTare: true,
                              isBroadcast: false, hasStandardBattery: false,
                              sustainsBackgroundStreaming: true,
                              nominalSampleRate: 80, hardwareVerified: true)
        case .whc06:
            GaugeCapabilities(hasDeviceClock: false, hasHardwareTare: false,
                              isBroadcast: true, hasStandardBattery: false,
                              sustainsBackgroundStreaming: false,
                              nominalSampleRate: 8, hardwareVerified: false)
        case .entralpi:
            GaugeCapabilities(hasDeviceClock: false, hasHardwareTare: false,
                              isBroadcast: false, hasStandardBattery: true,
                              sustainsBackgroundStreaming: true,
                              nominalSampleRate: 10, hardwareVerified: false)
        case .forceboard:
            // The board HAS a tare characteristic, but the reference never calls it
            // from its own tare path — its shipped behaviour is the software tare.
            // Promoting an unexercised API to the only mechanism would make Tare a
            // silent no-op if the write is wrong on real firmware, so software tare
            // ships until hardware confirms the write (OPEN check, see the codec).
            GaugeCapabilities(hasDeviceClock: false, hasHardwareTare: false,
                              isBroadcast: false, hasStandardBattery: true,
                              sustainsBackgroundStreaming: true,
                              nominalSampleRate: 10, hardwareVerified: false)
        case .climbro:
            GaugeCapabilities(hasDeviceClock: false, hasHardwareTare: false,
                              isBroadcast: false, hasStandardBattery: false,
                              sustainsBackgroundStreaming: true,
                              nominalSampleRate: 10, hardwareVerified: false)
        case .motherboard:
            // One packet sums three LOAD CELLS into one reading — the reference's
            // "3 samples" counts sensors, not moments. Its start command is literally
            // "S30", plausibly 30 Hz; inferred, not established.
            GaugeCapabilities(hasDeviceClock: false, hasHardwareTare: false,
                              isBroadcast: false, hasStandardBattery: true,
                              sustainsBackgroundStreaming: true,
                              nominalSampleRate: 30, hardwareVerified: false)
        case .cts500:
            // Hardware tare confirmed in the reference source: TARE_SCALE (0xA6) is a
            // device command and its own `tare()` override sends it.
            GaugeCapabilities(hasDeviceClock: false, hasHardwareTare: true,
                              isBroadcast: false, hasStandardBattery: false,
                              sustainsBackgroundStreaming: true,
                              nominalSampleRate: 40, hardwareVerified: false)
        case .pb700bt:
            GaugeCapabilities(hasDeviceClock: false, hasHardwareTare: false,
                              isBroadcast: false, hasStandardBattery: true,
                              sustainsBackgroundStreaming: true,
                              nominalSampleRate: 10, hardwareVerified: false)
        }
    }

    /// GATT wiring, nil for the two kinds that don't use the generic connected
    /// client: the Progressor (its own battle-tested client) and the WH-C06
    /// (broadcast, nothing to connect to).
    var gatt: GaugeGattProfile? {
        switch self {
        case .progressor, .whc06: nil
        case .entralpi: EntralpiCodec.profile
        case .forceboard: ForceBoardCodec.profile
        case .climbro: ClimbroCodec.profile
        case .motherboard: MotherboardCodec.profile
        case .cts500: CTS500Codec.profile
        case .pb700bt: PB700BTCodec.profile
        }
    }

    /// Nil for the same two kinds, for the same reasons.
    func makeFrameDecoder() -> (any GaugeFrameDecoder)? {
        switch self {
        case .progressor, .whc06: nil
        case .entralpi: EntralpiCodec.Decoder()
        case .forceboard: ForceBoardCodec.Decoder()
        case .climbro: ClimbroCodec.Decoder()
        case .motherboard: MotherboardCodec.Decoder()
        case .cts500: CTS500Codec.Decoder()
        case .pb700bt: PB700BTCodec.Decoder()
        }
    }

    /// Picker order: the two devices this project has in hand first, then the
    /// ports alphabetically by maker.
    ///
    /// **`.pb700bt` is deliberately absent.** The NSD PB-700BT turned out to be a
    /// gyroscopic hand exerciser whose stream is REVOLUTIONS PER MINUTE — the
    /// reference library funnels rpm into its single "mass" channel, and passing
    /// that through as kilograms would arm every rep instantly and record a
    /// five-figure "max" that then sets the grip's percentage targets. Its codec
    /// decodes the frames (fail-closed: no force readings) so a future spin-training
    /// mode is an addition, not a refactor — the same reason the Tindeq codec
    /// decodes RFD tags nothing consumes yet.
    static var selectable: [GaugeKind] {
        [.progressor, .whc06, .climbro, .entralpi, .motherboard, .cts500, .forceboard]
    }
}
