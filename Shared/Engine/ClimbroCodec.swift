// SPDX-License-Identifier: MPL-2.0 AND BSD-2-Clause
// Original contributions Copyright 2026 Nuri Bruner.

import Foundation

// The Climbro wire protocol — pure value types, no CoreBluetooth (see the rule in
// ProgressorCodec.swift; everything in Shared/ has to decode on any machine).
//
// Protocol knowledge ported from hangtime-grip-connect (BSD-2-Clause, © 2024
// Stevie-Ray Hartog, https://github.com/Stevie-Ray/hangtime-grip-connect).
//
// UNVERIFIED ON HARDWARE BY THIS PROJECT. Everything below is a transcription of
// that client's `climbro.model.ts`, not something we have watched a Climbro send.
//
// The transport is Microchip's Transparent UART (an RN487x module): no framing, length
// or checksum. Each byte means whatever the last MARKER byte said, so the decoder is
// stateful — a notification can end mid-channel and the next one continues it.

enum ClimbroCodec {

    // MARK: - GATT wiring

    /// Microchip Transparent UART. The datasheet's "UART Transmit" is the one the HOST
    /// subscribes to (device → phone), hence the reference's `rx`. Do not swap them:
    /// writing to the notify characteristic silently does nothing.
    ///
    /// No start payload: the reference defines no commands, and a Climbro pushes sensor
    /// bytes as soon as you subscribe. The write characteristic is recorded as a device
    /// fact; nothing writes to it today.
    static let profile = GaugeGattProfile(
        serviceUUID: "49535343-FE7D-4AE5-8FA9-9FAFD205E455",
        notifyCharacteristicUUID: "49535343-1E4D-4BD9-BA61-23C647249616",
        writeCharacteristicUUID: "49535343-8841-43F4-A8D4-ECBE34729BB3",
        streamStartPayloads: [],
        streamStopPayload: nil,
        tareCharacteristicUUID: nil,
        tarePayload: nil
    )

    // MARK: - Protocol constants

    /// Every byte after this one is battery, until the next marker.
    static let batteryMarker: UInt8 = 0xF0
    /// Every byte after this one is force, until the next marker.
    static let sensorMarker: UInt8 = 0xF5
    /// 0xF6 is a RESERVED WORD meaning 36 kg, not the number 246.
    ///
    /// The stream cannot send the marker values as data, so it needs an escape near
    /// them — which also shows the sensor byte is KILOGRAMS with a 1 kg LSB, since an
    /// escape would be meaningless for raw ADC counts.
    static let reserved36kgMarker: UInt8 = 0xF6
    static let reserved36kgValue: UInt8 = 36

    /// Battery: a raw discharge byte between the reference's `minBatteryDisc` and
    /// `maxBatteryDisc` (its `batLevelCoef` is `100 / (230 - 112)`, as a percentage).
    static let batteryEmptyByte: Double = 112
    static let batteryFullByte: Double = 230

    /// 0…1 from one battery byte. CLAMPED, unlike the reference (−94 % for a byte of 0):
    /// a reading outside the known span saturates rather than lies.
    static func batteryFraction(byte: UInt8) -> Double {
        let span = batteryFullByte - batteryEmptyByte
        return min(1, max(0, (Double(byte) - batteryEmptyByte) / span))
    }

    // MARK: - Decoder

    struct Decoder: GaugeFrameDecoder {

        /// Which channel the bytes belong to (the reference's `flagSynchro`, starting
        /// at neither marker), so bytes before the first marker are DROPPED, not guessed.
        private enum Channel {
            case unknown
            case battery
            case sensor
        }

        private var channel: Channel = .unknown

        /// Battery arrives INSIDE the stream here (there is no 0x180F service on
        /// this device), which is why `GaugeFrameDecoder` has this member at all.
        private(set) var batteryFraction: Double?

        init() {}

        /// Walk the bytes. A short read simply decodes fewer, and the channel survives
        /// into the next notification because the radio splits the stream anywhere.
        mutating func ingest(_ data: Data) -> [GaugeReading] {
            var readings: [GaugeReading] = []
            readings.reserveCapacity(data.count)

            // Iterate the SEQUENCE, never subscripts: a `Data` sliced upstream keeps
            // its parent's indices, and `data[0]` on such a slice traps.
            for byte in data {
                if byte == ClimbroCodec.batteryMarker {
                    channel = .battery
                    continue
                }
                if byte == ClimbroCodec.sensorMarker {
                    channel = .sensor
                    continue
                }

                // Substituted BEFORE the channel switch, as in the reference, so a 0xF6
                // in the battery channel reads as 36. Indistinguishable on the wire;
                // one harmless wrong battery reading beats inventing a rule.
                let value = byte == ClimbroCodec.reserved36kgMarker
                    ? ClimbroCodec.reserved36kgValue
                    : byte

                switch channel {
                case .battery:
                    batteryFraction = ClimbroCodec.batteryFraction(byte: value)
                case .sensor:
                    // The byte IS kilograms. No scaling, no calibration table, and a
                    // 1 kg quantisation the device cannot express more finely.
                    readings.append(GaugeReading(kg: Double(value)))
                case .unknown:
                    continue
                }
            }

            return readings
        }
    }
}
