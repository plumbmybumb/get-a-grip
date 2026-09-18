// SPDX-License-Identifier: MPL-2.0
// Original contributions Copyright 2026 Nuri Bruner.

import Foundation

// The Frez Dyno wire protocol, v1 — pure value types, no CoreBluetooth, no network.
//
// Written from Frez's own published Dyno API, not from anybody's reverse engineering,
// which is a better witness than the ported gauges have — and still UNVERIFIED ON
// HARDWARE BY THIS PROJECT until a Dyno has pulled on this app.
//
// The device separates transport from calibration on purpose. It streams SIGNED RAW
// ADC counts with its own clock, nine to a notification at 250 Hz, and turning them
// into kilograms is the client's job:
//
//     tare_adc  = average(first 100 unloaded samples)
//     weight_kg = a × (raw_adc − tare_adc)
//
// where `a` is the per-device slope Frez's coefficient API returns for the serial.
// Both halves of that arithmetic live HERE, so the Bluetooth client is only bytes in,
// readings out, and both platforms assert the same fixtures against it.

enum FrezDynoCodec {

    // MARK: - Protocol constants

    static let serviceUUID = "DA8A6C41-154B-4B9A-9B00-2F84DFCEBFE9"
    static let notifyCharacteristicUUID = "DA8A6C42-154B-4B9A-9B00-2F84DFCEBFE9"
    static let writeCharacteristicUUID = "DA8A6C43-154B-4B9A-9B00-2F84DFCEBFE9"
    /// Device Information (0x180A) › Serial Number String, `FrezDyno-######`. The one
    /// read this gauge needs that no other does: the serial is the key to the coefficient.
    static let serialNumberCharacteristicUUID = "2A25"
    /// Device Information › Software Revision String. Frez documents the firmware/API
    /// version here (0x2A28), not in Firmware Revision (0x2A26) like the ported boards.
    static let softwareRevisionCharacteristicUUID = "2A28"
    static let advertisedNamePrefix = "FrezDyno-"
    /// Frez asks for MTU 85 where the platform allows it; a v1 notification is 74 bytes.
    static let preferredMTU = 85

    /// Two-byte command frames: the opcode, then a reserved zero.
    enum Command {
        /// Starts a new session and resets the device's elapsed time to zero.
        static let start = Data([0x01, 0x00])
        static let stop = Data([0x02, 0x00])
        /// Deliberately never sent: it powers the device OFF and costs a physical
        /// button press to wake, the same wrong price the Tindeq's sleep opcode carries.
        static let powerOff = Data([0xFF, 0x00])
    }

    static let bulkResponseCode: UInt8 = 0x01
    static let reservedByte: UInt8 = 0x00
    static let headerLength = 2
    static let recordLength = 8
    static let recordsPerFrame = 9
    /// 2 + 9 × 8. Frez: "treat a truncated frame as an error instead of parsing partial
    /// samples", so anything else is rejected whole.
    static let frameLength = headerLength + recordLength * recordsPerFrame
    static let nominalSampleRate: Double = 250
    static let standardGravity = 9.80665

    /// Frez's own number: keep the Dyno unloaded while the first 100 samples (0.4 s)
    /// establish the zero.
    static let defaultTareSampleCount = 100

    /// The device clock restarting on a Start command — which the app re-sends routinely
    /// (watchdog, foreground, tare recovery) — looks like a jump BACK of at least this
    /// much that lands under it: the previous record was a quarter second or more into
    /// the old session, the next is within a quarter second of zero. A restart's first
    /// record arrives within one notification (36 ms) of the Start, so the margin is
    /// generous; a small backwards step mid-stream satisfies neither half and is dropped,
    /// as Frez's defensive-client notes ask. The one case this refuses is a Start re-sent
    /// within a quarter second of the previous one, which then drops the handful of
    /// records until the new clock passes the old one — bounded by the threshold itself.
    static let restartThresholdMs: UInt32 = 250

    /// Nothing a hand does; a count the coefficient turns into more than this is a
    /// corrupt record. The same fail-closed headroom the other codecs use.
    static let maxPlausibleKilograms: Double = 1000

    // MARK: - GATT wiring

    /// Subscribe, then write Start; Stop on the way out. No one-time setup and no
    /// hardware tare — the zero is arithmetic, see `Decoder`.
    static let profile = GaugeGattProfile(
        serviceUUID: serviceUUID,
        notifyCharacteristicUUID: notifyCharacteristicUUID,
        writeCharacteristicUUID: writeCharacteristicUUID,
        streamStartPayloads: [Command.start],
        streamStopPayload: Command.stop
    )

    // MARK: - Frame parsing

    /// One record of the bulk notification: a signed count and the device's elapsed
    /// milliseconds since the latest Start — cumulative, never a delta.
    struct RawSample: Equatable, Sendable {
        var rawADC: Int32
        var elapsedMs: UInt32
    }

    /// `[0x01] [0x00] + 9 × [int32 LE raw_adc] [uint32 LE elapsed_ms]`, or nil.
    ///
    /// All-or-nothing on purpose: a short read is an MTU problem to surface, not a
    /// frame to salvage, and a frame that fails the header is a different protocol.
    static func parse(_ data: Data) -> [RawSample]? {
        guard data.count == frameLength else { return nil }
        let bytes = [UInt8](data)
        guard bytes[0] == bulkResponseCode, bytes[1] == reservedByte else { return nil }

        var samples: [RawSample] = []
        samples.reserveCapacity(recordsPerFrame)
        var offset = headerLength
        for _ in 0..<recordsPerFrame {
            let raw = UInt32(bytes[offset])
                | UInt32(bytes[offset + 1]) << 8
                | UInt32(bytes[offset + 2]) << 16
                | UInt32(bytes[offset + 3]) << 24
            let elapsed = UInt32(bytes[offset + 4])
                | UInt32(bytes[offset + 5]) << 8
                | UInt32(bytes[offset + 6]) << 16
                | UInt32(bytes[offset + 7]) << 24
            samples.append(RawSample(rawADC: Int32(bitPattern: raw), elapsedMs: elapsed))
            offset += recordLength
        }
        return samples
    }

    /// The device clock in the engine's unit. Wrapping multiply on purpose: `elapsed_ms`
    /// wraps at 49 days, the product at the same ~71.6 minutes the Tindeq clock does, and
    /// every consumer already subtracts with `&-`.
    static func deviceMicros(elapsedMs: UInt32) -> UInt32 {
        elapsedMs &* 1000
    }

    // MARK: - Decoder

    /// Raw counts in, kilograms out — once it has a coefficient and a zero.
    ///
    /// **One tare per LINK, never per Start.** The app re-sends Start on every silence,
    /// foreground return and tare recovery, and Start resets the device clock, so a tare
    /// keyed to "the first samples after Start" would re-zero the gauge under whatever
    /// load was on it at the time — a hang, typically. The count offset is a property of
    /// the load cell, not of the session, so the zero taken when the link came up stays
    /// good; the Tare button's app-side capture sits on top of it for anything drifting.
    /// A new connection is a new decoder and a fresh zero, which is also what Frez asks
    /// for after an unexpected disconnect.
    struct Decoder: GaugeFrameDecoder {
        let coefficient: Double
        let tareSampleCount: Int

        private var tareSum: Double = 0
        private var tareCollected = 0
        private(set) var tareADC: Double?
        private var lastElapsedMs: UInt32?

        init(coefficient: Double, tareSampleCount: Int = FrezDynoCodec.defaultTareSampleCount) {
            self.coefficient = coefficient
            self.tareSampleCount = max(1, tareSampleCount)
        }

        /// Whether the zero has been established — the readout shows nothing before it.
        var isTared: Bool { tareADC != nil }

        mutating func ingest(_ data: Data) -> [GaugeReading] {
            guard let samples = FrezDynoCodec.parse(data) else { return [] }
            var readings: [GaugeReading] = []
            for sample in samples {
                guard advanceClock(to: sample.elapsedMs) else { continue }
                if tareADC == nil {
                    tareSum += Double(sample.rawADC)
                    tareCollected += 1
                    if tareCollected == tareSampleCount {
                        tareADC = tareSum / Double(tareCollected)
                    }
                    continue
                }
                guard let zero = tareADC else { continue }
                let kg = coefficient * (Double(sample.rawADC) - zero)
                guard kg.isFinite, abs(kg) <= FrezDynoCodec.maxPlausibleKilograms else { continue }
                readings.append(GaugeReading(kg: kg,
                                             deviceMicros: FrezDynoCodec.deviceMicros(elapsedMs: sample.elapsedMs)))
            }
            return readings
        }

        /// Frez: "Reject duplicate or decreasing elapsed time within a measurement
        /// session." A jump back of at least `restartThresholdMs` that lands under it is
        /// the next session, not a fault; any other backwards step is dropped.
        private mutating func advanceClock(to elapsedMs: UInt32) -> Bool {
            if let last = lastElapsedMs, elapsedMs <= last {
                let threshold = FrezDynoCodec.restartThresholdMs
                let isRestart = elapsedMs < threshold && last - elapsedMs >= threshold
                guard isRestart else { return false }
            }
            lastElapsedMs = elapsedMs
            return true
        }
    }
}
