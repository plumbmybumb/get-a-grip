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
    /// The start sequence is "C" then "S30", in that order, because the raw counts
    /// mean nothing without the calibration table and "C" is what asks for it. The
    /// reference waits up to 2500 ms between the two writes while the rows arrive, and
    /// `startPayloadDelaySeconds` is that wait — the client paces the sequence rather
    /// than firing both writes into the same runloop turn, where the start could race
    /// the table home or land while the device is still dumping rows. Any packet that
    /// does arrive early produces NO readings (see `applyCalibration`) rather than
    /// uncalibrated counts: a fraction of a second of silence at the start of a stream,
    /// against numbers that would be wrong by orders of magnitude.
    ///
    /// STOP is "#", the serial query. The reference's own stop is an EMPTY write
    /// with the comment "all commands will stop the data stream"; a zero-length
    /// GATT write is a no-op on some stacks, so we send the most harmless real
    /// command instead and let its documented side effect do the work. Its ASCII
    /// reply is neither hex nor a calibration row, so the decoder drops it.
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

    /// A line that never ends is not a Motherboard line.
    ///
    /// The reference buffers without a bound, which is fine for a page you close;
    /// a session here runs for twenty minutes with the radio live, and a stream
    /// that produces no LF would grow this buffer for all of it. 1 KB is thirty
    /// lines' worth of slack before we decide the peer is not speaking our
    /// protocol.
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
    /// - A sample ABOVE the table's last raw count returns nil here, where the
    ///   reference returns 0. Zero is not a smaller error than a missing sample: it
    ///   is the app telling the runner the climber let go, mid-pull, at exactly the
    ///   moment they pulled hardest. Fail closed instead — same rule as "no max
    ///   means no target, never a guess".
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
            // A zero-width segment would divide by zero and hand the engine a NaN
            // kilogram, which poisons every average and peak it touches. Skip it and
            // let a later segment answer, or fail closed if none can.
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
        /// Each sample is signed by RANGE, not by a sign bit the way a two's
        /// complement 24-bit value would be read in Swift: `>= 0x7FFFFF` means
        /// subtract 0x1000000. That boundary is the reference's, off by one from the
        /// textbook 0x800000, and it is kept because a sample sitting exactly on it
        /// is indistinguishable garbage either way.
        ///
        /// Centre and right are INVERTED after calibration — the outer cells read
        /// the opposite direction from the middle one — and the three then sum to
        /// the total load. Left is not inverted; a loop that negated all three would
        /// look tidier and be wrong.
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
        /// **A NEW DUMP REPLACES ITS SLOT.** "C" is asked again on every reconnect-free
        /// re-kick the app makes — the silence watchdog, a tare recovery, the foreground
        /// return — while the decoder lives for the whole LINK, so appending unconditionally
        /// grew the table without bound and left it a non-monotonic concatenation of copies
        /// that `applyCalibration` walks linearly three times per packet. Row INDEX 0 is
        /// what marks the start of a dump, which is the same signal the reference uses when
        /// it refuses to re-request a table it already has.
        private mutating func ingestCalibration(line: [UInt8]) {
            let text = String(decoding: line, as: UTF8.self)
            let parts = text.split(separator: ",", omittingEmptySubsequences: false)
            guard parts.count == 4 else { return }

            let numbers = parts.compactMap { Double($0.trimmingCharacters(in: .whitespaces)) }
            guard numbers.count == 4 else { return }

            // The slot is an INDEX parsed out of untrusted text, and `Int(_:)` on a Double
            // TRAPS for NaN, ±inf and anything outside Int's range — while `Double("nan")`,
            // `Double("inf")` and `Double("1e30")` all parse, so one malformed line from a
            // device this project has never held could take the app down. `Int(exactly:)`
            // is nil for every one of those and for a fractional slot; the Kotlin twin's
            // saturate-then-compare (`toInt()`, then `== slot.toDouble()`) rejects the same
            // set, so the two engines drop exactly the same rows.
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
