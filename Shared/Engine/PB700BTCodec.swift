// SPDX-License-Identifier: MPL-2.0 AND BSD-2-Clause
// Original contributions Copyright 2026 Nuri Bruner.

import Foundation

// The NSD PB-700BT. Protocol knowledge ported from hangtime-grip-connect
// (BSD-2-Clause, © 2024 Stevie-Ray Hartog,
// https://github.com/Stevie-Ray/hangtime-grip-connect).
//
// **THIS DEVICE MEASURES ROTATION SPEED, NOT FORCE.** The PB-700BT (NSD Spinner
// Bluetooth) is a gyroscopic hand exerciser — a powerball. Its notifications carry the
// PERIOD of one rotor revolution; the reference converts that to RPM (plausible range
// 800…15000) and pushes it down its single "mass" channel.
//
// So `Decoder.ingest` decodes every frame and returns NO readings. Four-figure RPM in a
// kilogram channel would arm every rep instantly and write a five-figure "max" that sets
// the grip's percentage targets — data corruption, not a unit bug. No number beats a
// confident wrong one. It is also why `GaugeKind.selectable` omits this device.
//
// The parse is kept and tested (`revolutionsPerMinute(from:)`), like the Progressor's
// unconsumed RFD tags, so a future rotation mode is an addition, not a refactor.
//
// Everything in the frame is BIG-ENDIAN.

enum PB700BTCodec {
    /// The only notify characteristic the reference subscribes to (its one `rx`):
    /// `0000FFF4` under the ISSC transparent UART service `0000FFF0`. The device also
    /// declares an unknown custom service (`0000FEBA` with `FA10`/`FA11`/`FA13`); if it
    /// has a force or torque channel at all it would live there, and nothing establishes it.
    static let profile = GaugeGattProfile(
        serviceUUID: "0000FFF0-0000-1000-8000-00805F9B34FB",
        notifyCharacteristicUUID: "0000FFF4-0000-1000-8000-00805F9B34FB",
        writeCharacteristicUUID: nil,
        // Empty: the reference writes nothing to this device — no enable opcode, no
        // handshake. Subscribing is all there is.
        streamStartPayloads: [],
        streamStopPayload: nil,
        tareCharacteristicUUID: nil,
        tarePayload: nil)

    /// Ticks per second of the period counter: the reference's undocumented constant
    /// (`60 * (666666 / period)`, a 1.5 µs tick) — a magic number, not a known clock rate.
    static let timerTicksPerSecond: Double = 666_666

    /// The reference discards anything outside this band, so it doubles as a frame
    /// sanity check: a byte-order mistake lands orders of magnitude outside it (a
    /// reversed 4000 RPM frame reads 0.15 RPM).
    static let plausibleRPM: ClosedRange<Double> = 800...15_000

    /// Revolutions per minute from one notification, or nil when the frame is not a
    /// usable rotation sample.
    ///
    /// Layout, big-endian throughout:
    ///   bytes 0…3  uint32  rotor period, in `timerTicksPerSecond` ticks
    ///   bytes 4…7  uint32  the reference's `sampleIndex` (see `sampleIndex(from:)`)
    ///
    /// Eight bytes are required though the period needs four: the reference reads
    /// offset 4 unconditionally, so it never accepted a shorter frame either. A zero
    /// period is refused before the division, not left to the plausibility band.
    ///
    /// Rounded to whole RPM, as the reference does.
    static func revolutionsPerMinute(from data: Data) -> Double? {
        // Index from zero regardless of how the Data was sliced upstream.
        let bytes = [UInt8](data)
        guard bytes.count >= 8 else { return nil }
        guard let period = bigEndianUInt32(bytes, at: 0), period != 0 else { return nil }

        let rpm = 60 * (timerTicksPerSecond / Double(period))
        guard rpm.isFinite, plausibleRPM.contains(rpm) else { return nil }
        return rpm.rounded()
    }

    /// The second word, stored by the reference as `sampleIndex` — a use, not a meaning
    /// (revolution counter, device tick or sample number; the source does not say). **If
    /// hardware shows it is a device clock, this family gains `hasDeviceClock`.**
    static func sampleIndex(from data: Data) -> UInt32? {
        let bytes = [UInt8](data)
        guard bytes.count >= 8 else { return nil }
        return bigEndianUInt32(bytes, at: 4)
    }

    struct Decoder: GaugeFrameDecoder {
        /// The most recent rotation speed, in RPM, kept inspectable for a future rotation
        /// feature. Nothing in the force path consumes it.
        private(set) var lastRPM: Double?

        /// Always returns no readings — see this file's header. The frame is still
        /// decoded: silence is the fail-closed answer, not the unexamined one.
        mutating func ingest(_ data: Data) -> [GaugeReading] {
            if let rpm = PB700BTCodec.revolutionsPerMinute(from: data) { lastRPM = rpm }
            return []
        }
    }

    private static func bigEndianUInt32(_ bytes: [UInt8], at offset: Int) -> UInt32? {
        guard offset >= 0, offset + 4 <= bytes.count else { return nil }
        return UInt32(bytes[offset]) << 24
            | UInt32(bytes[offset + 1]) << 16
            | UInt32(bytes[offset + 2]) << 8
            | UInt32(bytes[offset + 3])
    }
}
