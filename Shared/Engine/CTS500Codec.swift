// SPDX-License-Identifier: MPL-2.0 AND BSD-2-Clause
// Original contributions Copyright 2026 Nuri Bruner.

import Foundation

// The Jlyscales CTS500 wire protocol — pure value types, no CoreBluetooth.
//
// Protocol knowledge ported from hangtime-grip-connect (BSD-2-Clause, © 2024
// Stevie-Ray Hartog, https://github.com/Stevie-Ray/hangtime-grip-connect).
//
// UNVERIFIED ON HARDWARE BY THIS PROJECT.
//
// A generic MY-BT102 UART bridge in front of a crane-scale board: fixed-length frames
// behind a 0x05 header with an additive checksum, and ONE channel carrying commands,
// echoes and weight uploads. Replies do not name their command (like the Progressor's
// tag 0), but a frame's SHAPE identifies it, so no pending-command state is needed.
//
// Frames, all of them checksummed:
//   6 bytes  `05 <opcode> p0 p1 p2 ck`   command, and the device's echo of it
//   7 bytes  `05 80 <opcode> .. .. .. ck` typed reply to a query (battery, temperature)
//   7 bytes  `05 <b1> w3 w2 w1 w0 ck`     weight upload, big-endian centi-kilograms

enum CTS500Codec {

    // MARK: - Protocol constants

    static let header: UInt8 = 0x05
    /// Byte 1 of a TYPED reply. Byte 2 then carries the opcode being answered, which
    /// is the one place in this protocol where a reply names its question.
    static let responseFlag: UInt8 = 0x80
    static let ackFrameLength = 6
    static let dataFrameLength = 7

    /// Every opcode the reference knows. The set matters far more than the
    /// individual values: `isWeightFrame` identifies a weight upload partly by its
    /// byte 1 NOT being an opcode, so an opcode missing from this list would make
    /// that device's command echo decode as a load of several thousand kilograms.
    enum Opcode {
        static let setRange: UInt8 = 0x81
        static let setDivision: UInt8 = 0x82
        static let setFirstCalibrationWeight: UInt8 = 0x83
        static let setSecondCalibrationWeight: UInt8 = 0x84
        static let powerOnReset: UInt8 = 0x85
        static let zeroScale: UInt8 = 0x86
        static let runFirstCalibration: UInt8 = 0xA1
        static let runSecondCalibration: UInt8 = 0xA2
        static let getFirmwareVersion: UInt8 = 0xA4
        static let tareScale: UInt8 = 0xA6
        static let noLoadCalibration: UInt8 = 0xA7
        static let getWeight: UInt8 = 0xA9
        static let startWeightMeasurement: UInt8 = 0xAA
        static let stopWeightMeasurement: UInt8 = 0xAB
        static let setBaudRate: UInt8 = 0xC0
        static let setSamplingRate: UInt8 = 0xC1
        static let setShutdownTime: UInt8 = 0xC3
        static let getBatteryVoltage: UInt8 = 0xC4
        static let getTemperature: UInt8 = 0xC5
        static let setUpperTemperatureLimit: UInt8 = 0xC6
        static let setLowerTemperatureLimit: UInt8 = 0xC7
        static let peakMode: UInt8 = 0xCA
        static let setMaxWeightLimit: UInt8 = 0xD1
        static let setMinWeightLimit: UInt8 = 0xD2
        static let setWeightAlarmMode: UInt8 = 0xD3
        static let setAlarmOutput: UInt8 = 0xD4
    }

    static let commandOpcodes: Set<UInt8> = [
        Opcode.setRange, Opcode.setDivision, Opcode.setFirstCalibrationWeight,
        Opcode.setSecondCalibrationWeight, Opcode.powerOnReset, Opcode.zeroScale,
        Opcode.runFirstCalibration, Opcode.runSecondCalibration, Opcode.getFirmwareVersion,
        Opcode.tareScale, Opcode.noLoadCalibration, Opcode.getWeight,
        Opcode.startWeightMeasurement, Opcode.stopWeightMeasurement, Opcode.setBaudRate,
        Opcode.setSamplingRate, Opcode.setShutdownTime, Opcode.getBatteryVoltage,
        Opcode.getTemperature, Opcode.setUpperTemperatureLimit, Opcode.setLowerTemperatureLimit,
        Opcode.peakMode, Opcode.setMaxWeightLimit, Opcode.setMinWeightLimit,
        Opcode.setWeightAlarmMode, Opcode.setAlarmOutput,
    ]

    /// A/D sampling-rate payload codes: 0x00 = 10 Hz, 0x01 = 20, 0x02 = 40,
    /// 0x03 = 80, 0x04 = 160, 0x05 = 320.
    static let samplingRate40HzCode: UInt8 = 0x02

    /// Anything above this is a corrupt frame, not a pull. The weight is read UNSIGNED,
    /// as the reference reads it, so two's-complement negative drift would read as tens
    /// of millions of kilograms. Dropping it is fail-closed: a missing sample costs a
    /// tenth of a second, one absurd sample sets the session peak. (A genuinely negative
    /// reading is DISCARDED — the sign convention is unverified.)
    ///
    /// **DOUBLE the family's rated 500 kg, on purpose.** The centi-kilogram scaling is
    /// unverified here, and a ceiling at rated capacity would silently discard EVERY
    /// reading if the real scaling is finer — silence is far harder to diagnose than a
    /// number out by 10×. Same reasoning as the Force Board's and Entralpi's 907 kg.
    static let maxPlausibleKilograms: Double = 1000

    // MARK: - Command building

    /// Additive checksum over every byte before the checksum slot, truncated to a
    /// byte. Weak — it cannot see a transposition — which is why the plausibility
    /// ceiling above exists on top of it.
    static func checksum(_ bytes: [UInt8]) -> UInt8 {
        bytes.reduce(UInt8(0)) { $0 &+ $1 }
    }

    /// `05 <opcode> p0 p1 p2 ck`. The reference's `buildCommand`, port for port.
    static func command(_ opcode: UInt8,
                        payload: (UInt8, UInt8, UInt8) = (0x00, 0x00, 0x00)) -> Data {
        let body: [UInt8] = [header, opcode, payload.0, payload.1, payload.2]
        return Data(body + [checksum(body)])
    }

    // MARK: - GATT wiring

    /// The MY-BT102's transparent UART: notify on 0xFFE1, write on 0xFFE2.
    ///
    /// **40 Hz is asked for ONCE PER LINK.** The reference never sends
    /// `SET_SAMPLING_RATE`, leaving the rate unknown, and at the lowest code (10 Hz)
    /// barely one sample fits inside the runner's 100 ms engage debounce. Nothing waits
    /// for an echo, since some firmwares apply the change without one.
    ///
    /// It lives in `oneTimeSetupPayloads` because start payloads are re-sent on EVERY
    /// re-kick, and 0xC1 configures an EEPROM-backed board. Re-sending START is harmless;
    /// rewriting a config that plausibly resets the ADC ~1500 times across a silent
    /// session could kill the very stream it was trying to revive.
    ///
    /// The tare is the DEVICE's own (`TARE_SCALE`), on the same characteristic; the
    /// reference's `tare()` sends it too, while clearing the app-side offset.
    static let profile = GaugeGattProfile(
        serviceUUID: "0000FFE0-0000-1000-8000-00805F9B34FB",
        notifyCharacteristicUUID: "0000FFE1-0000-1000-8000-00805F9B34FB",
        writeCharacteristicUUID: "0000FFE2-0000-1000-8000-00805F9B34FB",
        streamStartPayloads: [CTS500Codec.command(Opcode.startWeightMeasurement)],
        streamStopPayload: CTS500Codec.command(Opcode.stopWeightMeasurement),
        oneTimeSetupPayloads: [
            CTS500Codec.command(Opcode.setSamplingRate,
                                payload: (0x00, 0x00, CTS500Codec.samplingRate40HzCode)),
        ],
        tareCharacteristicUUID: "0000FFE2-0000-1000-8000-00805F9B34FB",
        tarePayload: CTS500Codec.command(Opcode.tareScale)
    )

    // MARK: - Frame classification

    static func isValidFrame(_ frame: [UInt8]) -> Bool {
        guard frame.count >= ackFrameLength, frame[0] == header else { return false }
        return checksum(Array(frame.dropLast())) == frame[frame.count - 1]
    }

    /// A weight upload is a 7-byte frame whose byte 1 is neither the reply flag nor
    /// any command opcode. Byte 1 is 0x01 in the reference's own fixture and its
    /// meaning is NOT established — a status or stability flag is the obvious guess,
    /// and it is deliberately not treated as one here.
    static func isWeightFrame(_ frame: [UInt8]) -> Bool {
        frame.count == dataFrameLength
            && frame[0] == header
            && frame[1] != responseFlag
            && !commandOpcodes.contains(frame[1])
            && isValidFrame(frame)
    }

    static func isTypedResponse(_ frame: [UInt8], opcode: UInt8) -> Bool {
        frame.count == dataFrameLength
            && frame[0] == header
            && frame[1] == responseFlag
            && frame[2] == opcode
            && isValidFrame(frame)
    }

    /// Bytes 2…5 are BIG-endian hundredths of a kilogram — among little-endian
    /// neighbours, the kind of detail that yields a plausible wrong number, hence a test
    /// whose two byte orders differ by six orders of magnitude.
    static func kilograms(fromWeightFrame frame: [UInt8]) -> Double? {
        guard isWeightFrame(frame) else { return nil }
        let centi = UInt32(frame[2]) << 24
            | UInt32(frame[3]) << 16
            | UInt32(frame[4]) << 8
            | UInt32(frame[5])
        let kg = Double(centi) / 100
        guard kg <= maxPlausibleKilograms else { return nil }
        return kg
    }

    // MARK: - Decoder

    struct Decoder: GaugeFrameDecoder {

        private enum Scan {
            case frame([UInt8])
            /// Not enough bytes yet — wait for the next notification.
            case incomplete
            /// A byte was dropped to hunt for the real frame boundary.
            case resynchronised
        }

        private var buffer: [UInt8] = []

        /// Battery arrives only when asked (0xC4, which the app does not do today); parsed
        /// anyway. NOT a `batteryFraction`: volts → percent needs this cell's own curve.
        private(set) var latestBatteryVolts: Double?

        init() {}

        mutating func ingest(_ data: Data) -> [GaugeReading] {
            buffer.append(contentsOf: data)

            var readings: [GaugeReading] = []
            while buffer.count >= CTS500Codec.ackFrameLength {
                guard let headerIndex = buffer.firstIndex(of: CTS500Codec.header) else {
                    // No header anywhere: none of these bytes can begin a frame.
                    buffer.removeAll()
                    break
                }
                if headerIndex > 0 {
                    buffer.removeFirst(headerIndex)
                    continue
                }

                switch takeFrame() {
                case .frame(let frame):
                    if let reading = interpret(frame) { readings.append(reading) }
                case .incomplete:
                    return readings
                case .resynchronised:
                    continue
                }
            }
            return readings
        }

        /// Pull the next frame off the front of the buffer, which begins with a
        /// header byte.
        ///
        /// A 6-byte echo and a 7-byte data frame share that header, so byte 1 breaks
        /// the tie: a known opcode means an echo. Both candidates must pass their
        /// checksum, and if neither does, the header was a coincidence inside some
        /// other frame's payload — drop one byte and hunt again.
        ///
        /// **Resynchronising continues the walk**, where the reference abandons the
        /// notification — spending one notification per bad byte, a visible stall at
        /// 40 Hz. Every pass consumes a frame or drops a byte, so the walk terminates.
        private mutating func takeFrame() -> Scan {
            if CTS500Codec.commandOpcodes.contains(buffer[1]) {
                let candidate = Array(buffer[0..<CTS500Codec.ackFrameLength])
                if CTS500Codec.isValidFrame(candidate) {
                    buffer.removeFirst(CTS500Codec.ackFrameLength)
                    return .frame(candidate)
                }
            }

            guard buffer.count >= CTS500Codec.dataFrameLength else { return .incomplete }

            let candidate = Array(buffer[0..<CTS500Codec.dataFrameLength])
            if CTS500Codec.isValidFrame(candidate) {
                buffer.removeFirst(CTS500Codec.dataFrameLength)
                return .frame(candidate)
            }

            buffer.removeFirst(1)
            return .resynchronised
        }

        private mutating func interpret(_ frame: [UInt8]) -> GaugeReading? {
            if let kg = CTS500Codec.kilograms(fromWeightFrame: frame) {
                return GaugeReading(kg: kg)
            }
            if CTS500Codec.isTypedResponse(frame, opcode: CTS500Codec.Opcode.getBatteryVoltage) {
                latestBatteryVolts = Double(UInt16(frame[4]) << 8 | UInt16(frame[5])) / 100
            }
            // Everything else is a command echo or a reply we do not consume. It is
            // not an error and must not stop the walk: a session's own start and
            // tare writes come back as echoes.
            return nil
        }
    }
}
