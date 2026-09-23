// SPDX-License-Identifier: MPL-2.0 AND BSD-2-Clause
// Original contributions Copyright 2026 Nuri Bruner.

import Foundation

// The Griptonite Motherboard wire protocol — pure value types, no CoreBluetooth.
//
// Protocol knowledge ported from hangtime-grip-connect (BSD-2-Clause, © 2024
// Stevie-Ray Hartog, https://github.com/Stevie-Ray/hangtime-grip-connect).
//
// UNVERIFIED ON HARDWARE BY THIS PROJECT.
//
// This is the only ported device whose stream is TEXT. Nordic UART carries
// LF-terminated ASCII lines, and a line is one of three things: a 32-character hex
// packet (three sensor samples), a comma-separated calibration row answering the
// "C" command, or something we do not handle. So the decoder does two jobs the
// others do not — it reassembles lines across notifications, and it holds the
// calibration table that turns raw ADC counts into kilograms.
//
// **A packet is THREE SENSORS, not three moments.** Left, centre and right are
// summed into ONE reading per packet. The reference's `samplesNumber = 3` counts
// load cells; anything that reads it as a sample rate is off by 3×.

enum MotherboardCodec {

    // MARK: - GATT wiring

    /// Nordic UART. As everywhere in the reference, `rx` is the characteristic the
    /// HOST subscribes to and `tx` the one it writes.
    ///
    /// Start is "C" then "S30": raw counts mean nothing without the calibration table
    /// "C" requests. The reference waits up to 2.5 s between them while rows arrive, and
    /// `startPayloadDelaySeconds` is that wait, so the start cannot race the table home.
    /// A packet that arrives early yields NO readings rather than uncalibrated counts
    /// wrong by orders of magnitude.
    ///
    /// STOP is "#", the serial query. The reference stops with an EMPTY write ("all
    /// commands will stop the data stream"), but a zero-length GATT write is a no-op on
    /// some stacks, so the most harmless real command does it. Its reply is neither hex
    /// nor a calibration row, so the decoder drops it.
    static let profile = GaugeGattProfile(
        serviceUUID: "6E400001-B5A3-F393-E0A9-E50E24DCCA9E",
        notifyCharacteristicUUID: "6E400003-B5A3-F393-E0A9-E50E24DCCA9E",
        writeCharacteristicUUID: "6E400002-B5A3-F393-E0A9-E50E24DCCA9E",
        streamStartPayloads: [Data("C".utf8), Data("S30".utf8)],
        streamStopPayload: Data("#".utf8),
        startPayloadDelaySeconds: 2.5,
        tareCharacteristicUUID: nil,
        tarePayload: nil
    )

    // MARK: - Protocol constants

    /// A streaming packet is exactly this many hex CHARACTERS (16 bytes).
    static let hexPacketLength = 32
    /// Sensors decoded per packet: left, centre, right.
    static let sensorCount = 3
    /// Calibration slots the device answers with. Four, though only three are ever
    /// consumed — ported as four so a row addressed to sensor 3 is stored rather
    /// than dropped, exactly as the reference stores it.
    static let calibrationSlotCount = 4

    /// A line that never ends is not a Motherboard line. The reference buffers without
    /// bound; here a twenty-minute session with no LF would grow the buffer for all of
    /// it. 1 KB is thirty lines of slack.
    static let maxBufferedBytes = 1024

    // MARK: - Calibration

    /// Map one raw sample through one sensor's calibration table.
    ///
    /// A table row is `[index, force, raw]` — the reference reads `[1]` as the
    /// calibrated force and `[2]` as the raw count it corresponds to. The mapping is
    /// piecewise linear between consecutive rows.
    ///
    /// Two ported quirks and one deliberate departure:
    ///
    /// - Reflection below the zero point is `-sample`, NOT `2 * zero - sample`
    ///   (which is present in the reference, commented out). The two agree only when
    ///   the zero row's raw count is 0. Kept as the reference computes it, because
    ///   that is the arithmetic its own tests pin.
    /// - The sign is applied at the END, so a negative sample is mapped through the
    ///   positive side of the table and then negated.
    /// - A sample ABOVE the table's last raw count returns nil, where the reference
    ///   returns 0 — which would tell the runner the climber let go at the moment they
    ///   pulled hardest. Fail closed instead.
    static func applyCalibration(sample: Double, table: [[Double]]) -> Double? {
        guard let zeroRow = table.first, zeroRow.count >= 3, table.count >= 2 else { return nil }

        var value = sample
        var sign: Double = 1
        if value < zeroRow[2] {
            sign = -1
            value = -value
        }

        for index in 1..<table.count {
            let lower = table[index - 1]
            let upper = table[index]
            guard lower.count >= 3, upper.count >= 3 else { return nil }

            let start = lower[2]
            let end = upper[2]
            guard value < end else { continue }
            // A zero-width segment would divide by zero into a NaN kilogram that poisons
            // every average and peak. Skip it; a later segment answers, or none does.
            guard end != start else { continue }

            let fraction = (value - start) / (end - start)
            let force = lower[1] + fraction * (upper[1] - lower[1])
            guard force.isFinite else { return nil }
            return sign * force
        }

        return nil
    }

    // MARK: - Decoder

    struct Decoder: GaugeFrameDecoder {

        /// Bytes waiting for their LF. A notification splits wherever the radio
        /// decides to split it, so half a packet is normal, not an error.
        private var buffer: [UInt8] = []

        /// One table per sensor slot, filled from "C" replies. Empty until the
        /// device answers, and no reading is emitted while it is.
        private var calibration: [[[Double]]] = Array(
            repeating: [], count: MotherboardCodec.calibrationSlotCount
        )

        /// The device's own packet counter (uint16 LE, byte 0). Not used for timing —
        /// the client stamps arrival with `SyntheticSampleClock` — but it is the only
        /// way to notice dropped packets, so it is kept for diagnostics.
        private(set) var latestSampleIndex: UInt16?

        /// Raw battery field (uint16 LE, byte 2). Deliberately NOT published as
        /// `batteryFraction`: 0x012C in the reference's fixture is plainly not a
        /// percentage, its scaling is undocumented, and this device carries the
        /// standard 0x180F Battery Service that the client reads properly.
        private(set) var latestBatteryRaw: UInt16?

        /// Whether every sensor we decode has a usable table yet.
        var hasCalibration: Bool {
            (0..<MotherboardCodec.sensorCount).allSatisfy { calibration[$0].count >= 2 }
        }

        /// Rows held per slot. Exposed for the one property that is otherwise invisible:
        /// a second dump must REPLACE a slot, not stack on it — see `ingestCalibration`.
        var calibrationRowCounts: [Int] { calibration.map(\.count) }

        init() {}

        mutating func ingest(_ data: Data) -> [GaugeReading] {
            buffer.append(contentsOf: data)

            var readings: [GaugeReading] = []
            while let lineFeed = buffer.firstIndex(of: 0x0A) {
                var line = Array(buffer[0..<lineFeed])
                buffer.removeFirst(lineFeed + 1)
                // CRLF is stripped to CR-then-nothing; the reference does the same,
                // and a stray CR would fail the all-hex test on every packet.
                if line.last == 0x0D { line.removeLast() }
                if let reading = handle(line: line) { readings.append(reading) }
            }

            if buffer.count > MotherboardCodec.maxBufferedBytes { buffer.removeAll() }
            return readings
        }

        private mutating func handle(line: [UInt8]) -> GaugeReading? {
            if line.count == MotherboardCodec.hexPacketLength,
               let bytes = MotherboardCodec.hexDecode(line) {
                return decodePacket(bytes)
            }
            ingestCalibration(line: line)
            return nil
        }

        /// A packet is 16 bytes: `sampleIndex` (uint16 LE), `battRaw` (uint16 LE),
        /// then three 24-bit LITTLE-endian samples at bytes 4, 7 and 10. Bytes 13…15
        /// are unused by the reference and by us.
        ///
        /// Each sample is signed by RANGE: `>= 0x7FFFFF` means subtract 0x1000000. The
        /// boundary is the reference's, one off the textbook 0x800000; a sample exactly
        /// on it is garbage either way.
        ///
        /// Centre and right are INVERTED after calibration, then all three sum to the
        /// total load. Left is not inverted; negating all three would look tidier and
        /// be wrong.
        private mutating func decodePacket(_ bytes: [UInt8]) -> GaugeReading? {
            guard bytes.count >= 4 + 3 * MotherboardCodec.sensorCount else { return nil }

            latestSampleIndex = UInt16(bytes[0]) | UInt16(bytes[1]) << 8
            latestBatteryRaw = UInt16(bytes[2]) | UInt16(bytes[3]) << 8

            var total: Double = 0
            for sensor in 0..<MotherboardCodec.sensorCount {
                let start = 4 + 3 * sensor
                var raw = Int(bytes[start])
                    | Int(bytes[start + 1]) << 8
                    | Int(bytes[start + 2]) << 16
                if raw >= 0x7FFFFF { raw -= 0x1000000 }

                guard let force = MotherboardCodec.applyCalibration(
                    sample: Double(raw), table: calibration[sensor]
                ) else { return nil }

                total += (sensor == 0 ? force : -force)
            }

            guard total.isFinite else { return nil }
            return GaugeReading(kg: total)
        }

        /// Calibration rows arrive as `sensor,index,force,raw`. Anything with a
        /// different shape is ignored: a hex packet has no commas, so the two line
        /// kinds cannot be confused, and unlike the reference we do not need to
        /// remember which command was written last to tell them apart.
        ///
        /// **A NEW DUMP REPLACES ITS SLOT.** "C" is re-sent on every re-kick (watchdog,
        /// tare recovery, foreground) while the decoder lives for the whole LINK, so
        /// appending grew the table without bound into a non-monotonic concatenation.
        /// Row INDEX 0 marks the start of a dump, the same signal the reference uses.
        private mutating func ingestCalibration(line: [UInt8]) {
            let text = String(decoding: line, as: UTF8.self)
            let parts = text.split(separator: ",", omittingEmptySubsequences: false)
            guard parts.count == 4 else { return }

            let numbers = parts.compactMap { Double($0.trimmingCharacters(in: .whitespaces)) }
            guard numbers.count == 4 else { return }

            // The slot is parsed from untrusted text, and `Int(_:)` TRAPS on the NaN, ±inf
            // and out-of-range values `Double(_:)` happily parses. `Int(exactly:)` is nil
            // for those and for fractions; the Kotlin twin's saturate-then-compare rejects
            // the same set, so both engines drop the same rows.
            guard let slot = Int(exactly: numbers[0]), slot >= 0,
                  slot < MotherboardCodec.calibrationSlotCount else { return }

            if numbers[1] == 0 { calibration[slot].removeAll(keepingCapacity: true) }
            calibration[slot].append(Array(numbers[1...]))
        }
    }

    // MARK: - Hex

    /// ASCII hex → bytes, or nil the moment a character is not hex. The reference
    /// tests the same thing with a regex before decoding; folding the two together
    /// means a line is parsed once.
    static func hexDecode(_ ascii: [UInt8]) -> [UInt8]? {
        guard ascii.count.isMultiple(of: 2), !ascii.isEmpty else { return nil }

        var bytes: [UInt8] = []
        bytes.reserveCapacity(ascii.count / 2)
        var index = 0
        while index < ascii.count {
            guard let high = nibble(ascii[index]), let low = nibble(ascii[index + 1]) else { return nil }
            bytes.append(high << 4 | low)
            index += 2
        }
        return bytes
    }

    private static func nibble(_ character: UInt8) -> UInt8? {
        switch character {
        case 0x30...0x39: character - 0x30           // 0-9
        case 0x41...0x46: character - 0x41 + 10      // A-F
        case 0x61...0x66: character - 0x61 + 10      // a-f
        default: nil
        }
    }
}
