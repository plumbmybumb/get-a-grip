// SPDX-License-Identifier: MPL-2.0
// Original contributions Copyright 2026 Nuri Bruner.

import CoreBluetooth
import Foundation

enum BroadcastRadioState: Equatable {
    case unknown, poweredOn, poweredOff, unauthorized, unsupported
}

struct BroadcastAdvertisement {
    var peripheralID: UUID
    var name: String?
    /// Includes the two-byte company ID, exactly as CoreBluetooth supplies it.
    var manufacturerData: Data
}

@MainActor
protocol BroadcastScanTransport: AnyObject {
    var radioState: BroadcastRadioState { get }
    var onRadioStateChange: ((BroadcastRadioState) -> Void)? { get set }
    func activate()
    /// True means the scan API was called; it is not a radio acknowledgement.
    func startScan(onAdvertisement: @escaping (BroadcastAdvertisement) -> Void) -> Bool
    func stopScan()
}

/// Owns a single lazy central, on the same main queue as all existing iOS clients.
/// A new delegate carries each registration's callback. This lets the state machine
/// reject work delivered to an old registration after cancellation or retry.
///
/// CoreBluetooth exposes no public advertisement observation time or scan ID. An
/// advertisement delivered by the OS to the NEW delegate cannot be distinguished
/// from a fresh radio observation. Registration guards do not claim to solve that
/// platform limitation; sustained delivery still needs physical-device checks.
@MainActor
final class CoreBluetoothBroadcastScanTransport: BroadcastScanTransport {
    var onRadioStateChange: ((BroadcastRadioState) -> Void)?
    private var central: CBCentralManager?
    private var delegate: ScanDelegate?

    var radioState: BroadcastRadioState { Self.radioState(central?.state ?? .unknown) }

    func activate() {
        guard central == nil else { return }
        let delegate = ScanDelegate(owner: self, onAdvertisement: nil)
        self.delegate = delegate
        central = CBCentralManager(delegate: delegate, queue: .main)
    }

    func startScan(onAdvertisement: @escaping (BroadcastAdvertisement) -> Void) -> Bool {
        guard let central, central.state == .poweredOn else { return false }
        delegate?.onAdvertisement = nil
        let delegate = ScanDelegate(owner: self, onAdvertisement: onAdvertisement)
        self.delegate = delegate
        central.delegate = delegate
        // WH-C06 advertises no services. Its manufacturer-data frame is the
        // filter; duplicates are readings, so coalescing them would kill the stream.
        central.scanForPeripherals(withServices: nil,
                                   options: [CBCentralManagerScanOptionAllowDuplicatesKey: true])
        return true
    }

    func stopScan() {
        delegate?.onAdvertisement = nil
        central?.stopScan()
    }

    private static func radioState(_ state: CBManagerState) -> BroadcastRadioState {
        switch state {
        case .poweredOn: .poweredOn
        case .poweredOff: .poweredOff
        case .unauthorized: .unauthorized
        case .unsupported: .unsupported
        default: .unknown
        }
    }

    @MainActor
    private final class ScanDelegate: NSObject, @preconcurrency CBCentralManagerDelegate {
        weak var owner: CoreBluetoothBroadcastScanTransport?
        var onAdvertisement: ((BroadcastAdvertisement) -> Void)?

        init(owner: CoreBluetoothBroadcastScanTransport,
             onAdvertisement: ((BroadcastAdvertisement) -> Void)?) {
            self.owner = owner
            self.onAdvertisement = onAdvertisement
        }

        func centralManagerDidUpdateState(_ central: CBCentralManager) {
            owner?.onRadioStateChange?(CoreBluetoothBroadcastScanTransport.radioState(central.state))
        }

        func centralManager(_ central: CBCentralManager,
                            didDiscover peripheral: CBPeripheral,
                            advertisementData: [String: Any], rssi RSSI: NSNumber) {
            guard let manufacturerData = advertisementData[CBAdvertisementDataManufacturerDataKey] as? Data else { return }
            onAdvertisement?(BroadcastAdvertisement(
                peripheralID: peripheral.identifier,
                name: advertisementData[CBAdvertisementDataLocalNameKey] as? String ?? peripheral.name,
                manufacturerData: manufacturerData))
        }
    }
}
