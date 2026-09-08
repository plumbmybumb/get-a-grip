// SPDX-License-Identifier: MPL-2.0 AND BSD-2-Clause
// Original contributions Copyright 2026 Nuri Bruner.

import Foundation

/// WH-C06 measurements are advertisements, not a connected GATT stream. A healthy
/// scan remains running; only a lost signal starts bounded acquisition attempts.
/// Protocol knowledge ported from hangtime-grip-connect (BSD-2-Clause, © 2024
/// Stevie-Ray Hartog, https://github.com/Stevie-Ray/hangtime-grip-connect).
@MainActor
final class BroadcastGaugeClient: ProgressorClient {
    var onEvent: ((ProgressorEvent) -> Void)?
    var onPacketBoundary: ((PacketBoundary) -> Void)?
    var onStateChange: ((ProgressorConnectionState) -> Void)?
    var onDiagnostic: ((ProgressorClientDiagnostic) -> Void)?

    private(set) var state: ProgressorConnectionState = .idle {
        didSet { if state != oldValue { onStateChange?(state) } }
    }
    private(set) var deviceName: String?
    let kind: GaugeKind = .whc06

    private enum Acquisition: String {
        case initial
        case recovery
        var maximumAttempts: Int { self == .initial ? 1 : 3 }
    }

    private static let firstFrameDeadlineSeconds: TimeInterval = 15
    private static let retryDelays: [TimeInterval] = [2, 5]

    private let transport: any BroadcastScanTransport
    private let scheduler: any BroadcastScanScheduler
    private var wantsConnection = false
    private var isScanning = false
    private var scanGeneration: UInt64 = 0
    private var acquisition = Acquisition.initial
    private var attempt = 0

    /// First valid advertiser owns this logical link. Another scale must never
    /// interleave its weights or inherit a software zero captured against this one.
    private var lockedPeripheral: UUID?
    private var softwareTare = SoftwareTare()
    private var lastFrameUptime: TimeInterval?
    private var firstFrameTimer: (any BroadcastScanCancellation)?
    private var silenceTimer: (any BroadcastScanCancellation)?
    private var retryTimer: (any BroadcastScanCancellation)?

    convenience init() {
        self.init(transport: CoreBluetoothBroadcastScanTransport(),
                  scheduler: MonotonicBroadcastScanScheduler())
    }

    /// The real lifecycle is testable without creating CoreBluetooth (which does
    /// not exist in the Simulator) or waiting through radio timeouts in tests.
    init(transport: any BroadcastScanTransport, scheduler: any BroadcastScanScheduler) {
        self.transport = transport
        self.scheduler = scheduler
        transport.onRadioStateChange = { [weak self] state in
            self?.radioStateChanged(state)
        }
    }

    // MARK: - ProgressorClient

    func connect() {
        guard !wantsConnection, !state.isBusy, !state.isConnected else { return }
        wantsConnection = true
        acquisition = .initial
        attempt = 0
        // Constructing the central raises the Bluetooth permission prompt. Keep
        // that construction behind an explicit Connect, including with this seam.
        transport.activate()
        if transport.radioState == .poweredOn {
            beginScan()
        } else {
            state = Self.connectionState(for: transport.radioState)
        }
    }

    func disconnect() {
        wantsConnection = false
        cancelRetry()
        retireScan(reason: "disconnect")
        clearLink()
        state = .disconnected(reason: nil)
    }

    func sleepDevice() { disconnect() }

    func send(_ command: ProgressorCommand) {
        switch command {
        case .tare:
            softwareTare.capture()
        case .enterSleep:
            disconnect()
        default:
            // There is no command characteristic. Stop only changes recording
            // in DeviceStore; the scale keeps broadcasting and the scan is its link.
            break
        }
    }

    func startStreaming(cause: StreamStartCause) {
        guard wantsConnection, transport.radioState == .poweredOn else {
            diagnostic("scan unavailable (\(cause.label))")
            return
        }
        if isScanning {
            // Ordinary advertisement gaps and tare must not bounce a healthy scan.
            diagnostic("scan already active (\(cause.label))")
        } else if retryTimer != nil {
            diagnostic("scan recovery pending (\(cause.label))")
        } else {
            beginScan()
        }
    }

    // MARK: - Acquisition

    private func beginScan() {
        guard wantsConnection, transport.radioState == .poweredOn,
              !isScanning, retryTimer == nil else { return }
        attempt += 1
        scanGeneration &+= 1
        let generation = scanGeneration
        isScanning = true
        state = .scanning
        // A state observer may cancel synchronously; never resurrect that intent.
        guard ownsScan(generation) else { return }
        diagnostic("scan requested (\(acquisition.rawValue), attempt \(attempt))")
        firstFrameTimer = scheduler.schedule(after: Self.firstFrameDeadlineSeconds) { [weak self] in
            guard let self, self.ownsScan(generation), self.state == .scanning else { return }
            self.diagnostic("scan timed out")
            self.acquisitionFailed()
        }
        let started = transport.startScan { [weak self] advertisement in
            self?.received(advertisement, generation: generation)
        }
        if !started, ownsScan(generation) {
            diagnostic("scan could not start")
            acquisitionFailed()
        }
    }

    private func acquisitionFailed() {
        retireScan(reason: "failed attempt")
        guard wantsConnection, transport.radioState == .poweredOn else { return }
        guard attempt < acquisition.maximumAttempts else {
            wantsConnection = false
            clearLink()
            if acquisition == .recovery { diagnostic("scan recovery exhausted") }
            state = .disconnected(reason: String(localized: "No scale found"))
            return
        }

        let delay = Self.retryDelays[attempt - 1]
        let generation = scanGeneration
        diagnostic("scan retry in \(Int(delay)) s")
        retryTimer = scheduler.schedule(after: delay) { [weak self] in
            guard let self, self.wantsConnection, self.scanGeneration == generation,
                  self.transport.radioState == .poweredOn else { return }
            self.retryTimer = nil
            self.beginScan()
        }
    }

    private func received(_ advertisement: BroadcastAdvertisement, generation: UInt64) {
        guard ownsScan(generation),
              let rawKg = WHC06Codec.kilograms(fromManufacturerData: advertisement.manufacturerData),
              lockedPeripheral == nil || lockedPeripheral == advertisement.peripheralID else { return }

        lockedPeripheral = advertisement.peripheralID
        if let name = advertisement.name { deviceName = name }
        let uptime = scheduler.now
        lastFrameUptime = uptime
        if !state.isConnected {
            firstFrameTimer?.cancel()
            firstFrameTimer = nil
            diagnostic("scale advertisements received")
            state = .connected
        }
        guard ownsScan(generation) else { return }
        armSilenceTimer(generation: generation)

        // iOS supplies no public native observation timestamp. This is the
        // monotonic CALLBACK time, matching the synthetic-clock contract; it does
        // not claim that a radio-delayed packet was measured at that instant.
        let sample = ForceSample(kg: softwareTare.value(for: rawKg),
                                 deviceMicros: SyntheticSampleClock.micros(uptime: uptime),
                                 isBatchStart: true)
        onPacketBoundary?(.began(receivedAt: uptime))
        defer { onPacketBoundary?(.ended) }
        onEvent?(.sample(sample))
    }

    /// One timer follows the last reading; accepting a packet never allocates or
    /// cancels a task. At expiry it either waits out the remaining interval or
    /// retires the silent scan. There is no periodic healthy-scan renewal here.
    private func armSilenceTimer(generation: UInt64) {
        guard silenceTimer == nil, let lastFrameUptime else { return }
        let remaining = max(0, WHC06Codec.advertisementSilenceSeconds - (scheduler.now - lastFrameUptime))
        silenceTimer = scheduler.schedule(after: remaining) { [weak self] in
            guard let self, self.ownsScan(generation), self.state.isConnected,
                  let last = self.lastFrameUptime else { return }
            self.silenceTimer = nil
            if self.scheduler.now - last >= WHC06Codec.advertisementSilenceSeconds {
                self.handleSilence()
            } else {
                self.armSilenceTimer(generation: generation)
            }
        }
    }

    private func handleSilence() {
        retireScan(reason: "advertisement silence")
        let generation = scanGeneration
        clearLink()
        state = .disconnected(reason: String(localized: "Scale stopped broadcasting"))
        guard wantsConnection, scanGeneration == generation else { return }
        acquisition = .recovery
        attempt = 0
        beginScan()
    }

    // MARK: - Cleanup and radio lifecycle

    private func ownsScan(_ generation: UInt64) -> Bool {
        wantsConnection && isScanning && scanGeneration == generation && transport.radioState == .poweredOn
    }

    private func retireScan(reason: String) {
        // Invalidate callbacks BEFORE stopping the transport. A callback already
        // queued against a retired registration must not reconnect or emit a sample.
        scanGeneration &+= 1
        firstFrameTimer?.cancel()
        firstFrameTimer = nil
        silenceTimer?.cancel()
        silenceTimer = nil
        if isScanning {
            isScanning = false
            transport.stopScan()
            diagnostic("scan stopped (\(reason))")
        }
    }

    private func clearLink() {
        lockedPeripheral = nil
        softwareTare.reset()
        deviceName = nil
        lastFrameUptime = nil
    }

    private func cancelRetry() {
        retryTimer?.cancel()
        retryTimer = nil
    }

    private func radioStateChanged(_ radioState: BroadcastRadioState) {
        guard radioState != .poweredOn else {
            if wantsConnection, !isScanning, retryTimer == nil {
                acquisition = .initial
                attempt = 0
                beginScan()
            }
            return
        }
        cancelRetry()
        retireScan(reason: "Bluetooth unavailable")
        clearLink()
        state = Self.connectionState(for: radioState)
        // Keep the user's connection intent across radio loss. Cancel clears it,
        // so turning Bluetooth on later cannot undo an explicit disconnect.
    }

    private static func connectionState(for radioState: BroadcastRadioState) -> ProgressorConnectionState {
        switch radioState {
        case .poweredOn, .unknown: .idle
        case .poweredOff: .bluetoothOff
        case .unauthorized: .unauthorized
        case .unsupported: .unsupported
        }
    }

    private func diagnostic(_ event: String) { onDiagnostic?(.broadcastScan(event)) }
}
