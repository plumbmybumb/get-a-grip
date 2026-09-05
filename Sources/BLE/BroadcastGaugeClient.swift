// SPDX-License-Identifier: MPL-2.0 AND BSD-2-Clause
// Original contributions Copyright 2026 Nuri Bruner.

import CoreBluetooth
import Foundation

/// The WH-C06 crane scale, which never connects to anything.
///
/// It is a broadcast-only device: the weight is inside its BLE ADVERTISEMENT, so there is
/// no GATT link, no characteristic to subscribe to and nothing to write. Everything this
/// client does is a scan, and the words "connected" and "streaming" have to be
/// reinterpreted around that:
///
/// - **`connect()` starts scanning; the first matching advertisement IS the connection.**
///   There is no handshake to wait for and nothing to fail halfway.
/// - **`disconnect()` stops scanning**, and silence does the same thing on its own:
///   `WHC06Codec.advertisementSilenceSeconds` without a frame is treated as a dropped
///   link, exactly as the reference does with its own 10-second timer.
/// - **The scan runs with `CBCentralManagerScanOptionAllowDuplicatesKey`**, because every
///   advertisement is a reading. Without it CoreBluetooth reports each device ONCE and
///   the gauge would deliver a single sample and then appear to die. That option is also
///   the reason `sustainsBackgroundStreaming` is false for this kind: iOS coalesces
///   duplicates in the background whatever the app asks for, so a backgrounded session
///   would silently stop measuring — the runner pauses instead.
/// - **No service filter**, because the scale advertises no services at all. The filter
///   is the manufacturer-data check inside `WHC06Codec`.
///
/// Tare is app-side arithmetic (`SoftwareTare`): the scale has no tare command, and its
/// reading includes whatever sling and hardware is hanging from it.
///
/// Protocol knowledge ported from hangtime-grip-connect (BSD-2-Clause, © 2024
/// Stevie-Ray Hartog, https://github.com/Stevie-Ray/hangtime-grip-connect).
@MainActor
final class BroadcastGaugeClient: NSObject, ProgressorClient {
    var onEvent: ((ProgressorEvent) -> Void)?
    var onPacketBoundary: ((PacketBoundary) -> Void)?
    var onStateChange: ((ProgressorConnectionState) -> Void)?
    var onDiagnostic: ((ProgressorClientDiagnostic) -> Void)?

    private(set) var state: ProgressorConnectionState = .idle {
        didSet { if state != oldValue { onStateChange?(state) } }
    }
    private(set) var deviceName: String?

    let kind: GaugeKind = .whc06

    /// How long a scan waits for the FIRST advertisement before giving up. Same 15 s the
    /// other clients allow a scan, so "Searching…" never spins forever.
    private static let firstFrameDeadlineSeconds: UInt64 = 15

    private var central: CBCentralManager?
    /// Set only by an accepted explicit `connect()`, and kept across radio power loss so
    /// switching Bluetooth back on resumes the same intent.
    private var wantsConnection = false

    /// **The scale we are listening to, locked on the first frame.** Two of these in one
    /// gym would otherwise interleave their weights into one trace, and nothing in the
    /// advertisement says which pull belongs to which climber. Released on a link loss, so
    /// the next matching advertiser can take over.
    ///
    /// First-come, and there is nothing better available: the scale's company identifier
    /// (0x0100) is shared with TomTom, and the reference's own name filter is commented out
    /// because these units advertise inconsistently — so the codec's length-and-capacity
    /// check is the whole filter. **Known limitation, not a solved problem:** another
    /// device on that company ID whose payload happens to fit could take the lock, and the
    /// only cure is that it goes quiet for ten seconds or the user disconnects. Reaching
    /// for a name filter needs a real unit in hand to see what it actually advertises.
    private var lockedPeripheral: UUID?

    private var silenceTask: Task<Void, Never>?
    private var firstFrameTask: Task<Void, Never>?
    /// Host MONOTONIC uptime of the newest frame — never `Date`, which steps under NTP and
    /// could make a live scale look ten seconds gone.
    private var lastFrameUptime: TimeInterval?
    private var generation: UInt64 = 0

    private var softwareTare = SoftwareTare()

    deinit {
        silenceTask?.cancel()
        firstFrameTask?.cancel()
    }

    // MARK: - ProgressorClient

    func connect() {
        guard !wantsConnection, !state.isBusy, !state.isConnected else { return }
        wantsConnection = true

        // LAZY on purpose, same as every other client: constructing the central is what
        // raises the system Bluetooth prompt, and the ask belongs to a Connect tap.
        guard let central else {
            central = CBCentralManager(delegate: self, queue: .main)
            return   // continues in centralManagerDidUpdateState
        }
        guard central.state == .poweredOn else {
            state = Self.state(for: central.state)
            return
        }
        beginScan(.userInitiated)
    }

    func disconnect() {
        wantsConnection = false
        generation &+= 1
        central?.stopScan()
        cancelAllTasks()
        lockedPeripheral = nil
        softwareTare.reset()
        deviceName = nil
        state = .disconnected(reason: nil)
    }

    /// Nothing to put to sleep: the scale runs its own power-down timer and has no command
    /// surface to ask. Stopping the scan is the whole of what this app can do, and it is
    /// also all that saves any battery on THIS phone.
    func sleepDevice() {
        disconnect()
    }

    func send(_ command: ProgressorCommand) {
        switch command {
        case .tare:
            // App-side zero. Captures the newest reading; with none observed the offset is
            // left alone — see `SoftwareTare`.
            softwareTare.capture()
        case .enterSleep:
            sleepDevice()
        case .stopWeightMeasurement:
            // **Deliberately a no-op.** The scan IS the link here, so stopping it on a
            // stream stop would read as a disconnect and then need a reconnect to undo.
            // The store's own `isStreaming` flag is what governs recording; the scale goes
            // on broadcasting either way, which is the truth this screen should show.
            return
        case .startWeightMeasurement, .getBatteryVoltage, .getAppVersion,
             .getErrorInformation, .clearErrorInformation,
             .startPeakRFDMeasurement, .startPeakRFDSeries,
             .addCalibrationPoint, .saveCalibration:
            // No command surface at all: there is nothing to write to. The scale reports no
            // battery either, which is why `hasStandardBattery` is false for this kind.
            return
        }
    }

    /// Re-arms the scan. There is no start command to write, but a wedged scan is the one
    /// failure this can actually repair — which makes the store's silence watchdog useful
    /// here for exactly the same reason it is useful on a Tindeq.
    ///
    /// It reports the start as WRITTEN because scanning is the only act that can make data
    /// flow: the alternative is a breadcrumb ring showing a start requested and never
    /// delivered, which reads as a stall in the log that exists to explain stalls.
    func startStreaming(cause: StreamStartCause) {
        guard wantsConnection, let central, central.state == .poweredOn else {
            onDiagnostic?(.streamStartDeferred(cause))
            return
        }
        // **An active scan IS the stream — never bounce it.** Restarting buys nothing
        // (there is no device-side state to re-arm) and costs a dead window while
        // CoreBluetooth spins the radio back up. On real hardware (Nuri, 2026-08-17)
        // that dead window fed the very silence the runner's watchdog re-kicks on:
        // each re-kick bounced the scan, the bounce caused the next gap, the gap the
        // next re-kick — his "blips of signal" loop. Broadcast delivery is
        // best-effort and BURSTY; while the scan is running, a re-kick's job is
        // already being done.
        if central.isScanning {
            onDiagnostic?(.streamStartWritten(cause))
            return
        }
        beginScan(.rekick)
        onDiagnostic?(.streamStartWritten(cause))
    }

    // MARK: - Scanning

    /// Why a scan is starting, which is the only thing that differs between the three.
    private enum ScanReason {
        /// A Connect tap. Somebody is watching "Searching…", so this one gives up after
        /// `firstFrameDeadlineSeconds` rather than spinning forever.
        case userInitiated
        /// An established link went quiet. Waits INDEFINITELY on purpose — the same rule a
        /// dropped Tindeq gets: a session waits for the gauge, it never decides the workout
        /// is over. These scales also power themselves down between uses, so "gone for
        /// twenty seconds" is an ordinary event here, not a failure.
        case reacquire
        /// A stream re-kick while frames are still arriving. Repairs a wedged scan without
        /// touching the published state or the deadlines.
        case rekick
    }

    /// **Does not bump `generation`** — that counter tracks the LINK, not the scan, and a
    /// re-kick while connected must leave the silence deadline armed. Bumping it here made
    /// every stream re-kick disarm the one watchdog that can notice a scale going quiet, so
    /// a wedged scan would have sat "connected" with no data forever.
    private func beginScan(_ reason: ScanReason) {
        guard wantsConnection, let central, central.state == .poweredOn else { return }
        if reason != .rekick { state = .scanning }
        // No service filter — the scale advertises none — and duplicates ALLOWED, because
        // each duplicate is the next reading.
        central.scanForPeripherals(withServices: nil,
                                   options: [CBCentralManagerScanOptionAllowDuplicatesKey: true])
        if reason == .userInitiated { startFirstFrameDeadline(generation: generation) }
    }

    private func startFirstFrameDeadline(generation: UInt64) {
        firstFrameTask?.cancel()
        firstFrameTask = Task { [weak self] in
            do {
                try await Task.sleep(for: .seconds(Self.firstFrameDeadlineSeconds))
            } catch {
                return
            }
            guard !Task.isCancelled,
                  let self,
                  self.generation == generation,
                  self.state == .scanning else { return }
            // No retry budget, unlike the connected clients: with no handshake to
            // establish, a second attempt is byte-for-byte the same scan. Five identical
            // scans would only spend five times the radio.
            self.wantsConnection = false
            self.central?.stopScan()
            self.cancelAllTasks()
            self.state = .disconnected(reason: String(localized: "No scale found"))
        }
    }

    /// Ten seconds without a frame is a dropped link. It is the reference's own rule, and
    /// it is the ONLY disconnect signal a broadcast device can give: switched off, out of
    /// range and battery-flat are indistinguishable from here.
    ///
    /// **One task for the whole link, polling a stored timestamp** — deliberately not a
    /// fresh ten-second task per advertisement. Frames arrive around eight times a second,
    /// so cancel-and-restart would allocate eight tasks a second and throw each away a
    /// frame later; the same churn the routine draft's debounce was rewritten to avoid. The
    /// cost is that the disconnect lands within a second of the deadline instead of on it,
    /// which nothing here can tell apart.
    private func startSilenceWatchdog() {
        guard silenceTask == nil else { return }
        let watchGeneration = generation
        silenceTask = Task { [weak self] in
            while !Task.isCancelled {
                do {
                    try await Task.sleep(for: .seconds(1))
                } catch {
                    return
                }
                guard !Task.isCancelled,
                      let self,
                      self.generation == watchGeneration,
                      self.state.isConnected else { return }
                guard let last = self.lastFrameUptime else { continue }
                guard ProcessInfo.processInfo.systemUptime - last
                        >= WHC06Codec.advertisementSilenceSeconds else { continue }
                self.handleSilence()
                return
            }
        }
    }

    private func handleSilence() {
        silenceTask?.cancel()
        silenceTask = nil
        lastFrameUptime = nil
        // A new link generation: nothing owed by the old one may fire against the next.
        generation &+= 1
        // The zero was captured against a link that is gone; the sling may not even be on
        // the next one.
        softwareTare.reset()
        lockedPeripheral = nil
        deviceName = nil
        state = .disconnected(reason: String(localized: "Scale stopped broadcasting"))
        // And straight back to scanning, because the intent has not changed and the scale
        // coming back is the likely case. A fresh scan rather than the old one: this is
        // also the repair if the scan itself wedged.
        central?.stopScan()
        beginScan(.reacquire)
    }

    /// Every caller of this is a link ending, so the watchdog's timestamp goes with the
    /// watchdog: a stale "last heard" would let the next link inherit a ten-second-old
    /// deadline and disconnect itself on its first tick.
    private func cancelAllTasks() {
        silenceTask?.cancel()
        silenceTask = nil
        lastFrameUptime = nil
        firstFrameTask?.cancel()
        firstFrameTask = nil
    }

    private func handlePowerUnavailable(_ central: CBCentralManager) {
        central.stopScan()
        cancelAllTasks()
        generation &+= 1
        lockedPeripheral = nil
        softwareTare.reset()
        deviceName = nil
        state = Self.state(for: central.state)
    }

    private static func state(for cb: CBManagerState) -> ProgressorConnectionState {
        switch cb {
        case .poweredOn: .idle
        case .poweredOff: .bluetoothOff
        case .unauthorized: .unauthorized
        case .unsupported: .unsupported
        default: .idle
        }
    }
}

// MARK: - CBCentralManagerDelegate

// `@preconcurrency` plus `queue: .main`, exactly as in `LiveProgressorClient` — the two
// alternatives do not compile. See CLAUDE.md.
extension BroadcastGaugeClient: @preconcurrency CBCentralManagerDelegate {
    func centralManagerDidUpdateState(_ central: CBCentralManager) {
        guard central.state == .poweredOn else {
            handlePowerUnavailable(central)
            return
        }
        // The radio came back with the user's original intent still standing, so this is
        // that same Connect tap resuming — deadline included.
        if wantsConnection { beginScan(.userInitiated) }
    }

    func centralManager(_ central: CBCentralManager,
                        didDiscover peripheral: CBPeripheral,
                        advertisementData: [String: Any],
                        rssi RSSI: NSNumber) {
        guard central.state == .poweredOn, wantsConnection else { return }
        // The manufacturer-data blob CoreBluetooth hands over still carries the 2-byte
        // little-endian company ID prefix; the codec expects it that way and does the
        // company-ID and length checks itself. A frame that is not a weight frame — or is
        // some other maker's device sharing the company ID — returns nil and is ignored,
        // which is the whole filter an unfiltered scan gets.
        guard let manufacturerData =
                advertisementData[CBAdvertisementDataManufacturerDataKey] as? Data,
              let rawKg = WHC06Codec.kilograms(fromManufacturerData: manufacturerData) else { return }

        // Stay with one scale for the life of the link.
        if let lockedPeripheral, lockedPeripheral != peripheral.identifier { return }
        lockedPeripheral = peripheral.identifier

        if let advertised = advertisementData[CBAdvertisementDataLocalNameKey] as? String {
            deviceName = advertised
        } else if deviceName == nil {
            // The scale is documented to advertise as `IF_B7` on some units, so the name
            // is never a filter — but it is worth showing whatever it gives.
            deviceName = peripheral.name
        }

        if !state.isConnected {
            firstFrameTask?.cancel()
            firstFrameTask = nil
            state = .connected
        }

        // One uptime read, used for both jobs: the sample's stamp and the silence
        // watchdog's "when did we last hear anything".
        let uptime = ProcessInfo.processInfo.systemUptime
        lastFrameUptime = uptime
        startSilenceWatchdog()

        // One advertisement is one sample and therefore one batch. The stamp is synthetic:
        // the scale sends no clock, and the runner clamps per-sample credit for exactly this
        // reason — an RF gap between advertisements is a radio fact, not a measurement of
        // somebody's fingers.
        let kg = softwareTare.value(for: rawKg)
        onPacketBoundary?(.began(receivedAt: ProcessInfo.processInfo.systemUptime))
        defer { onPacketBoundary?(.ended) }
        onEvent?(.sample(ForceSample(kg: kg,
                                    deviceMicros: SyntheticSampleClock.micros(uptime: uptime),
                                    isBatchStart: true)))
    }
}
