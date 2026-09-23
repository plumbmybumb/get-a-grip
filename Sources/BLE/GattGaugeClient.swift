// SPDX-License-Identifier: MPL-2.0 AND BSD-2-Clause
// Original contributions Copyright 2026 Nuri Bruner.

import CoreBluetooth
import Foundation

/// Every connected gauge that is NOT a Tindeq Progressor.
///
/// One client for six devices, because after the codec there is nothing device-specific
/// left in the wire handling: discover the profile's service, subscribe to its notify
/// characteristic (plus any `alternateNotifyCharacteristicUUIDs`), write
/// `oneTimeSetupPayloads` once, write `streamStartPayloads` in order — paced by
/// `startPayloadDelaySeconds` where a device needs it — and hand every notification to
/// `kind.makeFrameDecoder()`. What differs between devices lives entirely in
/// `GaugeGattProfile` and the codec.
///
/// **Deliberately simpler than `LiveProgressorClient`.** Its serialized queries,
/// tare-integrity latch and peripheral quarantine do not apply: a GATT read's reply
/// names its own characteristic, so nothing can cross-pair; a tare is one plain write or
/// app-side arithmetic; and every device here is a PORT of hangtime-grip-connect's
/// documented protocol that this project has never held, so the honest shape is the
/// small one.
///
/// **Isolation.** Same shape as `LiveProgressorClient` — `@MainActor`, `queue: .main`,
/// `@preconcurrency` delegate conformances (SE-0423). See that type for the two
/// alternatives that do not compile.
///
/// Protocol knowledge ported from hangtime-grip-connect (BSD-2-Clause, © 2024
/// Stevie-Ray Hartog, https://github.com/Stevie-Ray/hangtime-grip-connect).
@MainActor
final class GattGaugeClient: NSObject, ProgressorClient {
    var onEvent: ((ProgressorEvent) -> Void)?
    var onPacketBoundary: ((PacketBoundary) -> Void)?
    var onStateChange: ((ProgressorConnectionState) -> Void)?
    var onDiagnostic: ((ProgressorClientDiagnostic) -> Void)?

    private(set) var state: ProgressorConnectionState = .idle {
        didSet { if state != oldValue { onStateChange?(state) } }
    }
    private(set) var deviceName: String?

    let kind: GaugeKind

    private let profile: GaugeGattProfile
    private let capabilities: GaugeCapabilities

    private let serviceUUID: CBUUID
    private let notifyUUID: CBUUID
    /// Extra characteristics to subscribe ALONGSIDE `notifyUUID` — the Entralpi's second
    /// "rx", which the reference also subscribes to because its source cannot say which of
    /// the two actually streams.
    private let alternateNotifyUUIDs: [CBUUID]
    private let writeUUID: CBUUID?
    private let tareUUID: CBUUID?

    /// The standard Battery Service. Read once at connect for the kinds whose
    /// capabilities claim it; absent, the row stays blank.
    private static let batteryServiceUUID = CBUUID(string: "180F")
    private static let batteryLevelUUID = CBUUID(string: "2A19")
    /// Firmware Revision String, in Device Information (0x180A). Every ported device's
    /// service table lists it; nothing depends on it arriving.
    private static let firmwareRevisionUUID = CBUUID(string: "2A26")
    /// Software Revision String, the other Device Information slot a version can live in.
    /// Read only when a device has no Firmware Revision to offer — Frez publishes the
    /// Dyno's firmware/API version here.
    private static let softwareRevisionUUID = CBUUID(string: "2A28")
    /// Serial Number String. Wanted by exactly one kind of gauge, the one whose counts need
    /// a per-device coefficient that the serial is the key to; never read otherwise.
    private static let serialNumberUUID = CBUUID(string: "2A25")
    private static let attemptLimit = 5

    /// **A `.withResponse` write that is never acknowledged must not wedge the queue for the
    /// life of the link.** One lost ATT response otherwise left tare, stop and every later
    /// re-kick undeliverable while notifications kept arriving — invisible to both
    /// watchdogs. Two seconds, the deadline `LiveProgressorClient` gives a query reply.
    private static let writeResponseDeadlineSeconds: Double = 2

    /// **Service UUIDs that are evidence of a SERIAL MODULE, not of a device**: the stock
    /// 16-bit HM-10/JDY services and the Nordic and Microchip UART profiles ship on
    /// countless unrelated products (`.entralpi` and `.pb700bt` declare the same `FFF0`
    /// and `FFF4`). Matching on one alone would decode a stranger's module as kilograms,
    /// so for these the advertised NAME must agree too; a long-form vendor-unique service
    /// (the Force Board's) remains proof on its own.
    private static let wellKnownServiceUUIDs: Set<String> = [
        "0000FFF0-0000-1000-8000-00805F9B34FB",   // HM-10 / JDY BLE-serial
        "0000FFE0-0000-1000-8000-00805F9B34FB",   // the same family's other service
        "6E400001-B5A3-F393-E0A9-E50E24DCCA9E",   // Nordic UART
        "49535343-FE7D-4AE5-8FA9-9FAFD205E455",   // Microchip Transparent UART
    ]

    /// **CoreBluetooth cannot filter a scan by NAME, and the reference filters every
    /// ported device by name** (Web Bluetooth `{ name }` / `{ namePrefix }`). Whether any
    /// of them advertises its service UUID is UNVERIFIED, so the scan is unfiltered and
    /// each hit is matched on advertised service or on name.
    ///
    /// Unfiltered scanning only yields results in the foreground, which is where every
    /// connect in this app happens.
    private static func nameHints(for kind: GaugeKind) -> [String] {
        switch kind {
        case .entralpi: ["ENTRALPI"]
        case .forceboard: ["Force Board"]
        case .climbro: ["Climbro"]
        case .motherboard: ["Motherboard"]
        // The reference accepts both, and the two model lines share this protocol.
        case .cts500: ["CTS500", "CTS-300"]
        case .pb700bt: ["NSD Workout"]
        // Frez's own rule: "a device whose advertised name starts with FrezDyno-".
        case .frezdyno: [FrezDynoCodec.advertisedNamePrefix]
        // Not driven by this client: the Progressor has its own, and the WH-C06 is
        // matched on manufacturer data by `BroadcastGaugeClient`.
        case .progressor, .whc06: []
        }
    }

    private struct WriteEntry {
        enum Target { case stream, tare }
        let payload: Data
        let target: Target
        /// Set on the LAST payload of a start sequence, so "start written" means the
        /// whole sequence reached the device.
        let startCause: StreamStartCause?
    }

    private var central: CBCentralManager?
    private var peripheral: CBPeripheral?

    private var notifyCharacteristic: CBCharacteristic?
    private var writeCharacteristic: CBCharacteristic?
    private var tareCharacteristic: CBCharacteristic?
    private var batteryCharacteristic: CBCharacteristic?
    private var firmwareCharacteristic: CBCharacteristic?
    private var softwareRevisionCharacteristic: CBCharacteristic?
    private var serialCharacteristic: CBCharacteristic?

    /// Answers the coefficient question for a gauge that `requiresRemoteCalibration`;
    /// nil for every other kind, which never asks. See `FrezCalibration.swift` for the
    /// rules that keep this the app's only non-Apple network call.
    private let calibration: (any GaugeCalibrationResolver)?
    /// The lookup in flight for THIS link. Cancelled with the link: a coefficient that
    /// arrives for a connection that has since gone must not mint a decoder for the next.
    private var calibrationTask: Task<Void, Never>?

    /// Alternate notify characteristics found anywhere in the table, and the subscription
    /// bookkeeping for the whole set.
    ///
    /// **The link is established when the primary OR any alternate is notifying**; the
    /// attempt fails only when every candidate has answered and none is notifying.
    private var alternateNotifyCharacteristics: [CBCharacteristic] = []
    private var streamCharacteristics: [CBCharacteristic] = []
    private var notifyingCharacteristics: [CBCharacteristic] = []
    private var pendingSubscriptions = 0
    /// Written once per LINK, after subscribing and before any start payload — see
    /// `writeOneTimeSetupPayloadsIfNeeded`.
    private var oneTimeSetupWritten = false

    /// Matches for the notify and write UUIDs found OUTSIDE the profile's own service,
    /// used only when that service does not contain them.
    ///
    /// **The PitchSix Force Board is why this exists**: its Device Mode characteristic
    /// (every start and stop write) lives in a different service from the weight stream,
    /// and `GaugeGattProfile` names one service. The profile's own service is still
    /// preferred, because a 16-bit UUID like `fff4` is not unique across a device's table.
    private var notifyElsewhere: CBCharacteristic?
    private var writeElsewhere: CBCharacteristic?

    /// Discovery walks EVERY service: the ForceBoard's write and tare characteristics
    /// live in other services, the Battery Service is separate by definition, and
    /// `GaugeGattProfile` names characteristics, not the services that hold them.
    private var pendingServiceDiscoveries = 0
    /// One subscription per link: a second characteristics callback would otherwise
    /// re-subscribe and mint a FRESH decoder mid-stream, losing a half-reassembled frame.
    private var discoveryFinished = false

    /// Link-local, exactly as the protocol requires: a decoder holding half a
    /// reassembled frame must never meet the next connection's bytes.
    private var decoder: (any GaugeFrameDecoder)?
    private var publishedBatteryFraction: Double?

    /// Used only when the profile names no hardware tare. Reset with the link, because an
    /// offset captured against one connection's zero is meaningless on the next.
    private var softwareTare = SoftwareTare()

    private var generation: UInt64 = 0
    private var activeGeneration: UInt64?
    private var scanGeneration: UInt64?

    private var attemptsRemaining = 0
    /// Set only by an accepted explicit `connect()`, and kept across radio power loss so
    /// switching Bluetooth back on resumes the same user intent — parity with
    /// `LiveProgressorClient.wantsConnection`.
    private var wantsConnection = false
    private var refreshBudgetWhenPoweredOn = false

    private var scanDeadlineTask: Task<Void, Never>?
    private var connectDeadlineTask: Task<Void, Never>?
    private var backoffTask: Task<Void, Never>?

    private var writeQueue: [WriteEntry] = []
    private var inFlightWrite: WriteEntry?
    private var inFlightWriteDeadlineTask: Task<Void, Never>?
    /// Identifies the write a deadline belongs to, so a late deadline cannot clear the
    /// NEXT write's slot.
    private var inFlightWriteToken: UInt64 = 0
    /// A paced start sequence in flight. See `beginStartSequence`.
    private var startSequenceTask: Task<Void, Never>?

    init(kind: GaugeKind, profile: GaugeGattProfile,
         calibration: (any GaugeCalibrationResolver)? = nil) {
        self.kind = kind
        self.profile = profile
        self.capabilities = kind.capabilities
        self.calibration = calibration
        self.serviceUUID = CBUUID(string: profile.serviceUUID)
        self.notifyUUID = CBUUID(string: profile.notifyCharacteristicUUID)
        self.alternateNotifyUUIDs = profile.alternateNotifyCharacteristicUUIDs
            .map { CBUUID(string: $0) }
        self.writeUUID = profile.writeCharacteristicUUID.map { CBUUID(string: $0) }
        self.tareUUID = profile.tareCharacteristicUUID.map { CBUUID(string: $0) }
        super.init()
    }

    deinit {
        scanDeadlineTask?.cancel()
        connectDeadlineTask?.cancel()
        backoffTask?.cancel()
        inFlightWriteDeadlineTask?.cancel()
        startSequenceTask?.cancel()
        calibrationTask?.cancel()
    }

    // MARK: - ProgressorClient

    func connect() {
        // `wantsConnection` covers the backoff and radio-off gaps, where the state is not
        // busy but the intent is live. A second tap must not replenish the retry budget.
        guard !wantsConnection, !state.isBusy, !state.isConnected else { return }

        wantsConnection = true
        attemptsRemaining = Self.attemptLimit
        refreshBudgetWhenPoweredOn = false

        // LAZY: constructing the central is what raises the system Bluetooth prompt, so
        // it happens on a Connect tap and never at launch.
        guard let central else {
            central = CBCentralManager(delegate: self, queue: .main)
            return   // continues in centralManagerDidUpdateState
        }
        guard central.state == .poweredOn else {
            state = Self.state(for: central.state)
            return
        }
        beginAttemptIfPossible()
    }

    func disconnect() {
        wantsConnection = false
        refreshBudgetWhenPoweredOn = false
        attemptsRemaining = 0
        generation &+= 1

        central?.stopScan()
        cancelAllTasks()
        scanGeneration = nil
        clearLinkState()

        if let peripheral {
            central?.cancelPeripheralConnection(peripheral)
            peripheral.delegate = nil
        }
        peripheral = nil
        activeGeneration = nil
        deviceName = nil
        state = .disconnected(reason: nil)
    }

    /// **A plain disconnect.** No ported device documents a sleep opcode, and inventing
    /// one would be guessing at bytes on somebody else's hardware.
    func sleepDevice() {
        disconnect()
    }

    func send(_ command: ProgressorCommand) {
        switch command {
        case .tare:
            tareNow()
        case .startWeightMeasurement:
            // `startStreaming(cause:)` is the only start path, so every breadcrumb names
            // who asked.
            return
        case .stopWeightMeasurement:
            guard let payload = profile.streamStopPayload else { return }
            enqueue(payload, target: .stream)
        case .enterSleep:
            sleepDevice()
        case .getBatteryVoltage:
            readBatteryLevel()
        case .getAppVersion, .getErrorInformation, .clearErrorInformation,
             .startPeakRFDMeasurement, .startPeakRFDSeries,
             .addCalibrationPoint, .saveCalibration:
            // Tindeq commands with no counterpart on any ported device. Ignored rather than
            // mapped onto a plausible-looking write to untested hardware.
            return
        }
    }

    /// **Never gated on an "is streaming" flag**, which can only skip the one command a
    /// session depends on; re-sending a start is harmless.
    ///
    /// No engine timeline break belongs to a re-kick of THIS client: synthetic stamps
    /// have no device epoch — see `RunnerSession.restartStreamArmingStaleBatchHeal`.
    func startStreaming(cause: StreamStartCause) {
        guard state.isConnected, !notifyingCharacteristics.isEmpty else {
            onDiagnostic?(.streamStartDeferred(cause))
            return
        }
        guard !profile.streamStartPayloads.isEmpty else {
            // Subscribing IS the start on these devices (the Entralpi streams the moment
            // notifications are on), so the ring records the start as written.
            onDiagnostic?(.streamStartWritten(cause))
            return
        }

        guard profile.startPayloadDelaySeconds <= 0 else {
            // **A SEQUENCE in flight absorbs further starts** — not the "never gate on
            // isStreaming" mistake: that is a flag that can go stale forever, this is a
            // time-bounded window that always completes. Otherwise the 500 ms watchdog
            // re-asks the Motherboard for its calibration table inside the 2.5 s it takes.
            guard startSequenceTask == nil else {
                onDiagnostic?(.streamStartWritten(cause))
                return
            }
            beginStartSequence(cause: cause)
            return
        }

        for (index, payload) in profile.streamStartPayloads.enumerated() {
            let isLast = index == profile.streamStartPayloads.count - 1
            enqueueStart(payload, cause: isLast ? cause : nil)
        }
    }

    /// Writes the start payloads with the profile's own wait between them.
    ///
    /// The Motherboard is why: its reference writes "C", waits up to 2500 ms for the
    /// calibration dump, and only then writes "S30" — back to back, the start could land
    /// in the middle of the device's reply.
    private func beginStartSequence(cause: StreamStartCause) {
        let payloads = profile.streamStartPayloads
        let delay = profile.startPayloadDelaySeconds
        // CAPTURED: a sleep that outlives its link must neither write into the next one
        // nor clear the slot the next one's sequence holds.
        let generation = self.generation
        startSequenceTask = Task { [weak self] in
            for (index, payload) in payloads.enumerated() {
                if index > 0 {
                    do {
                        try await Task.sleep(for: .seconds(delay))
                    } catch {
                        return   // cancelled with the link; `clearLinkState` clears the slot
                    }
                }
                guard !Task.isCancelled, let self,
                      self.generation == generation, self.state.isConnected else { return }
                self.enqueueStart(payload, cause: index == payloads.count - 1 ? cause : nil)
            }
            guard let self, self.generation == generation else { return }
            self.startSequenceTask = nil
        }
    }

    /// Enqueue one start payload, COALESCED against the queue.
    ///
    /// A byte-identical start still queued is the write this cause wants; a second copy
    /// only spends the radio twice (the 500 ms watchdog re-asked the Motherboard for its
    /// calibration table before the first ask left). The breadcrumb is still recorded.
    private func enqueueStart(_ payload: Data, cause: StreamStartCause?) {
        guard !writeQueue.contains(where: { $0.target == .stream && $0.payload == payload })
        else {
            if let cause { onDiagnostic?(.streamStartWritten(cause)) }
            return
        }
        enqueue(payload, target: .stream, startCause: cause)
    }

    /// Configuration written ONCE per link, right after subscribing and before any start
    /// payload can be enqueued.
    ///
    /// `streamStartPayloads` is re-sent on every re-kick, so configuration folded into it
    /// would be re-issued ~1500 times in a silent session. The CTS500's sampling-rate
    /// command is EEPROM-class and plausibly resets the ADC, which would make each
    /// re-kick prevent the stream it is reviving.
    private func writeOneTimeSetupPayloadsIfNeeded() {
        guard !oneTimeSetupWritten, !profile.oneTimeSetupPayloads.isEmpty else { return }
        oneTimeSetupWritten = true
        for payload in profile.oneTimeSetupPayloads {
            enqueue(payload, target: .stream)
        }
    }

    // MARK: - Tare

    /// Hardware tare when the profile names one, app-side arithmetic otherwise. **The two
    /// are mutually exclusive on purpose:** a device that zeroes itself must not also have
    /// an app-side offset subtracted, or the next reading is short by the load that was on
    /// it — the same double-adjust the reference guards with `clearTareOffset()`.
    private func tareNow() {
        if tareCharacteristic != nil, let payload = profile.tarePayload {
            softwareTare.reset()
            enqueue(payload, target: .tare)
            return
        }
        // Captures the newest reading as the offset, not the reference's five-second
        // average: Tare must take effect in the frame it is tapped, and `TarePolicy`
        // already refuses a reading that is not live. See `SoftwareTare.capture()`.
        softwareTare.capture()
    }

    // MARK: - Writes

    private func enqueue(_ payload: Data, target: WriteEntry.Target,
                         startCause: StreamStartCause? = nil) {
        writeQueue.append(WriteEntry(payload: payload, target: target, startCause: startCause))
        drainWriteQueue()
    }

    /// Queued and PACED, never fired back to back. CoreBluetooth silently discards a
    /// `.withoutResponse` write when its buffer is not ready and accepts one
    /// `.withResponse` write at a time.
    private func drainWriteQueue() {
        guard state.isConnected, let peripheral, isCurrent(peripheral) else { return }

        while let next = writeQueue.first {
            guard let characteristic = self.characteristic(for: next.target) else {
                // The link lacks this write's characteristic: drop it rather than hold the
                // queue.
                writeQueue.removeFirst()
                continue
            }

            let type: CBCharacteristicWriteType =
                characteristic.properties.contains(.write) ? .withResponse : .withoutResponse

            if type == .withResponse {
                guard inFlightWrite == nil else { return }
            } else {
                guard peripheral.canSendWriteWithoutResponse else { return }
            }

            let entry = writeQueue.removeFirst()
            if type == .withResponse {
                inFlightWrite = entry
                armInFlightWriteDeadline()
            }
            peripheral.writeValue(entry.payload, for: characteristic, type: type)
            if let cause = entry.startCause { onDiagnostic?(.streamStartWritten(cause)) }
            if type == .withResponse { return }
        }
    }

    /// The one recovery from a write response that never comes — see
    /// `writeResponseDeadlineSeconds`. Expiry treats the write as lost and drains; a lost
    /// start is re-sent by the silence watchdog anyway.
    private func armInFlightWriteDeadline() {
        inFlightWriteToken &+= 1
        let token = inFlightWriteToken
        inFlightWriteDeadlineTask?.cancel()
        inFlightWriteDeadlineTask = Task { [weak self] in
            do {
                try await Task.sleep(for: .seconds(Self.writeResponseDeadlineSeconds))
            } catch {
                return
            }
            guard !Task.isCancelled,
                  let self,
                  self.inFlightWriteToken == token,
                  self.inFlightWrite != nil else { return }
            self.inFlightWrite = nil
            self.inFlightWriteDeadlineTask = nil
            self.drainWriteQueue()
        }
    }

    private func clearInFlightWrite() {
        inFlightWrite = nil
        inFlightWriteDeadlineTask?.cancel()
        inFlightWriteDeadlineTask = nil
    }

    private func characteristic(for target: WriteEntry.Target) -> CBCharacteristic? {
        switch target {
        case .stream: writeCharacteristic
        case .tare: tareCharacteristic
        }
    }

    private func readBatteryLevel() {
        guard let peripheral, isCurrent(peripheral), let batteryCharacteristic else { return }
        peripheral.readValue(for: batteryCharacteristic)
    }

    // MARK: - Connection flow

    private func beginAttemptIfPossible() {
        guard wantsConnection,
              let central,
              central.state == .poweredOn,
              !state.isConnected,
              !state.isBusy else { return }

        guard attemptsRemaining > 0 else {
            wantsConnection = false
            state = .disconnected(reason: String(localized: "Connection attempts exhausted"))
            return
        }

        backoffTask?.cancel()
        backoffTask = nil
        attemptsRemaining -= 1
        generation &+= 1
        scanGeneration = generation

        // BLE links belong to the system daemon, so after a relaunch the gauge may already
        // be connected; `retrieveConnectedPeripherals` matches on what it exposes.
        if let known = central.retrieveConnectedPeripherals(withServices: [serviceUUID]).first {
            attach(known, via: central, generation: generation)
            return
        }

        state = .scanning
        // Unfiltered — see `nameHints(for:)`. Duplicates are not needed: one hit is
        // enough to start connecting.
        central.scanForPeripherals(withServices: nil)
        startScanDeadline(generation: generation)
    }

    private func attach(_ found: CBPeripheral, via central: CBCentralManager,
                        generation: UInt64) {
        guard wantsConnection,
              central.state == .poweredOn,
              self.generation == generation else { return }

        central.stopScan()
        scanDeadlineTask?.cancel()
        scanDeadlineTask = nil
        scanGeneration = nil
        peripheral = found
        activeGeneration = generation
        // Never overwrite an advertised local name with a nil `peripheral.name`: it is
        // often the only name until the link is up.
        deviceName = found.name ?? deviceName
        found.delegate = self
        state = .connecting
        central.connect(found)
        startConnectDeadline(for: found, generation: generation)
    }

    private func startScanDeadline(generation: UInt64) {
        scanDeadlineTask?.cancel()
        scanDeadlineTask = Task { [weak self] in
            do {
                try await Task.sleep(for: .seconds(15))
            } catch {
                return
            }
            guard !Task.isCancelled,
                  let self,
                  self.generation == generation,
                  self.scanGeneration == generation,
                  self.state == .scanning else { return }
            self.central?.stopScan()
            self.failAttempt(reason: String(localized: "No \(self.kind.displayName) found"))
        }
    }

    private func startConnectDeadline(for found: CBPeripheral, generation: UInt64) {
        let identity = ObjectIdentifier(found)
        connectDeadlineTask?.cancel()
        connectDeadlineTask = Task { [weak self] in
            do {
                try await Task.sleep(for: .seconds(8))
            } catch {
                return
            }
            guard !Task.isCancelled,
                  let self,
                  self.generation == generation,
                  self.activeGeneration == generation,
                  let active = self.peripheral,
                  ObjectIdentifier(active) == identity else { return }
            self.failAttempt(reason: String(localized: "Connection timed out"), cancelling: active)
        }
    }

    private func scheduleRetry() {
        guard wantsConnection, attemptsRemaining > 0 else {
            wantsConnection = false
            return
        }
        let retryGeneration = generation
        backoffTask?.cancel()
        // One second — what lets this client skip the Tindeq's quarantine: a cancelled
        // peripheral's callback belongs to a superseded generation, and a rescan never
        // races the cancellation it just issued.
        backoffTask = Task { [weak self] in
            do {
                try await Task.sleep(for: .seconds(1))
            } catch {
                return
            }
            guard !Task.isCancelled,
                  let self,
                  self.generation == retryGeneration,
                  self.wantsConnection,
                  self.central?.state == .poweredOn else { return }
            self.beginAttemptIfPossible()
        }
    }

    private func failAttempt(reason: String) {
        invalidateAttempt()
        state = .disconnected(reason: reason)
        guard wantsConnection, attemptsRemaining > 0 else {
            wantsConnection = false
            return
        }
        scheduleRetry()
    }

    private func failAttempt(reason: String, cancelling failed: CBPeripheral) {
        guard isCurrent(failed) else { return }
        invalidateAttempt()
        central?.cancelPeripheralConnection(failed)
        failed.delegate = nil
        state = .disconnected(reason: reason)
        guard wantsConnection, attemptsRemaining > 0 else {
            wantsConnection = false
            return
        }
        scheduleRetry()
    }

    private func invalidateAttempt() {
        generation &+= 1
        central?.stopScan()
        cancelAllTasks()
        scanGeneration = nil
        activeGeneration = nil
        peripheral = nil
        deviceName = nil
        clearLinkState()
    }

    private func handlePowerUnavailable(_ central: CBCentralManager) {
        refreshBudgetWhenPoweredOn = wantsConnection
        attemptsRemaining = 0
        let active = peripheral
        invalidateAttempt()
        if let active {
            central.cancelPeripheralConnection(active)
            active.delegate = nil
        }
        state = Self.state(for: central.state)
    }

    private func isCurrent(_ candidate: CBPeripheral) -> Bool {
        guard let peripheral, peripheral === candidate else { return false }
        return activeGeneration == generation
    }

    private func clearLinkState() {
        notifyCharacteristic = nil
        writeCharacteristic = nil
        notifyElsewhere = nil
        writeElsewhere = nil
        alternateNotifyCharacteristics.removeAll()
        streamCharacteristics.removeAll()
        notifyingCharacteristics.removeAll()
        pendingSubscriptions = 0
        oneTimeSetupWritten = false
        tareCharacteristic = nil
        batteryCharacteristic = nil
        firmwareCharacteristic = nil
        softwareRevisionCharacteristic = nil
        serialCharacteristic = nil
        // A coefficient still in flight belongs to the link that asked for it.
        calibrationTask?.cancel()
        calibrationTask = nil
        pendingServiceDiscoveries = 0
        discoveryFinished = false
        writeQueue.removeAll()
        // The paced sequence belongs to this link; left in the slot it would fold every
        // future start into a sequence that has already returned.
        startSequenceTask?.cancel()
        startSequenceTask = nil
        clearInFlightWrite()
        // Both die with the link, and for the same reason: a half-reassembled frame and a
        // captured zero are facts about one connection only.
        decoder = nil
        publishedBatteryFraction = nil
        softwareTare.reset()
    }

    private func cancelAllTasks() {
        scanDeadlineTask?.cancel()
        scanDeadlineTask = nil
        connectDeadlineTask?.cancel()
        connectDeadlineTask = nil
        backoffTask?.cancel()
        backoffTask = nil
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

    // MARK: - Sample ingestion

    private func ingest(_ data: Data) {
        onPacketBoundary?(.began(receivedAt: ProcessInfo.processInfo.systemUptime))
        defer { onPacketBoundary?(.ended) }
        // Copy out, mutate, store back: the decoders are structs, and losing the copy
        // would lose the reassembly buffer the Motherboard's split frames need.
        guard var working = decoder else { return }
        let readings = working.ingest(data)
        decoder = working

        if let fraction = working.batteryFraction, fraction != publishedBatteryFraction {
            // Climbro carries battery inside the data stream rather than in 0x180F.
            publishedBatteryFraction = fraction
            onEvent?(.batteryFraction(min(1, max(0, fraction))))
        }

        guard !readings.isEmpty else { return }

        // **One stamp per NOTIFICATION, shared by every reading it carried.** Spreading
        // them at the nominal rate would invent timing the engine credits as hang time.
        // Interior deltas are zero and the next notification carries the whole interval,
        // so the SUM the engine accrues is exactly the time that passed. `isBatchStart`
        // marks the first reading: one notification, one batch, as on the Tindeq.
        let arrival = SyntheticSampleClock.micros(uptime: ProcessInfo.processInfo.systemUptime)
        for (index, reading) in readings.enumerated() {
            let kg = softwareTare.value(for: reading.kg)
            onEvent?(.sample(ForceSample(kg: kg,
                                        deviceMicros: reading.deviceMicros ?? arrival,
                                        isBatchStart: index == 0)))
        }
    }
}

// MARK: - Software tare

/// The app-side zero, for every gauge that has no tare of its own.
///
/// Shared with `BroadcastGaugeClient`. A value type with no clock or Bluetooth, so the
/// double-subtract and empty-capture mistakes are testable on their own.
struct SoftwareTare {
    private var offsetKg: Double = 0
    private var latestRawKg: Double?

    /// Observe and convert in ONE call, so the "remember the raw reading" half can never
    /// be forgotten at a call site and leave `capture()` zeroing against a stale value.
    mutating func value(for raw: Double) -> Double {
        latestRawKg = raw
        return raw - offsetKg
    }

    /// Zero against the newest reading. A capture with nothing observed leaves the offset
    /// where it is: on a fresh link that is already zero, and throwing away a good offset
    /// because the stream went quiet would shift every later reading.
    mutating func capture() {
        guard let latestRawKg else { return }
        offsetKg = latestRawKg
    }

    /// The link went away. An offset captured against one connection's zero says nothing
    /// about the next one's.
    mutating func reset() {
        offsetKg = 0
        latestRawKg = nil
    }

    var offset: Double { offsetKg }
    var hasReading: Bool { latestRawKg != nil }
}

// MARK: - CBCentralManagerDelegate

// `@preconcurrency`: sound because the central uses `queue: .main`. See
// `LiveProgressorClient` for the full account.
extension GattGaugeClient: @preconcurrency CBCentralManagerDelegate {
    func centralManagerDidUpdateState(_ central: CBCentralManager) {
        guard central.state == .poweredOn else {
            handlePowerUnavailable(central)
            return
        }
        if refreshBudgetWhenPoweredOn {
            attemptsRemaining = Self.attemptLimit
            refreshBudgetWhenPoweredOn = false
        }
        if wantsConnection { beginAttemptIfPossible() }
    }

    func centralManager(_ central: CBCentralManager,
                        didDiscover peripheral: CBPeripheral,
                        advertisementData: [String: Any],
                        rssi RSSI: NSNumber) {
        guard central.state == .poweredOn,
              wantsConnection,
              state == .scanning,
              self.peripheral == nil,
              scanGeneration == generation,
              matches(peripheral, advertisement: advertisementData) else { return }
        if let advertised = advertisementData[CBAdvertisementDataLocalNameKey] as? String {
            deviceName = advertised
        }
        attach(peripheral, via: central, generation: generation)
    }

    /// The scan is unfiltered, so THIS is the filter: the profile's service if the device
    /// advertises it, otherwise the reference's own name filters.
    ///
    /// **A service-UUID match alone is only proof for a LONG-FORM vendor-unique service**
    /// — see `wellKnownServiceUUIDs`. Otherwise a stranger's HM-10 in range would be
    /// decoded as kilograms that arm reps and set targets from a fabricated max.
    private func matches(_ peripheral: CBPeripheral, advertisement: [String: Any]) -> Bool {
        let name = (advertisement[CBAdvertisementDataLocalNameKey] as? String) ?? peripheral.name
        let hints = Self.nameHints(for: kind)
        // Prefix, not equality: an exact name is its own prefix, and a unit that appends
        // a serial ("Force Board 214") still matches.
        let nameMatches = name.map { advertised in
            let folded = advertised.lowercased()
            return hints.contains { folded.hasPrefix($0.lowercased()) }
        } ?? false

        if let advertised = advertisement[CBAdvertisementDataServiceUUIDsKey] as? [CBUUID],
           advertised.contains(serviceUUID) {
            // With no name filter to lean on there is nothing better than the service, so
            // it stands; that is only reachable by a kind this client does not drive.
            guard !hints.isEmpty,
                  Self.wellKnownServiceUUIDs.contains(profile.serviceUUID.uppercased())
            else { return true }
            return nameMatches
        }
        return nameMatches
    }

    func centralManager(_ central: CBCentralManager, didConnect peripheral: CBPeripheral) {
        guard central.state == .poweredOn, isCurrent(peripheral) else { return }
        deviceName = peripheral.name ?? deviceName
        // nil, not a filtered list: the tare and battery characteristics live in services
        // the profile does not name. See `pendingServiceDiscoveries`.
        peripheral.discoverServices(nil)
    }

    func centralManager(_ central: CBCentralManager,
                        didFailToConnect peripheral: CBPeripheral,
                        error: Error?) {
        guard central.state == .poweredOn, isCurrent(peripheral) else { return }
        invalidateAttempt()
        state = .disconnected(reason: error?.localizedDescription ?? String(localized: "Couldn't connect"))
        guard wantsConnection, attemptsRemaining > 0 else {
            wantsConnection = false
            return
        }
        scheduleRetry()
    }

    func centralManager(_ central: CBCentralManager,
                        didDisconnectPeripheral peripheral: CBPeripheral,
                        error: Error?) {
        guard isCurrent(peripheral) else { return }

        let wasEstablished = state.isConnected
        invalidateAttempt()

        // Radio state is authoritative: its own callback already published the off or
        // unauthorized state, and a late disconnect must not overwrite it or rescan.
        guard central.state == .poweredOn else { return }

        state = .disconnected(reason: error?.localizedDescription)
        guard wantsConnection else { return }

        if wasEstablished {
            // Subscribing successfully reset the budget, so a dropped established link
            // begins a fresh cycle immediately.
            attemptsRemaining = Self.attemptLimit
            beginAttemptIfPossible()
        } else if attemptsRemaining > 0 {
            scheduleRetry()
        } else {
            wantsConnection = false
        }
    }
}

// MARK: - CBPeripheralDelegate

extension GattGaugeClient: @preconcurrency CBPeripheralDelegate {
    func peripheral(_ peripheral: CBPeripheral, didDiscoverServices error: Error?) {
        guard isCurrent(peripheral) else { return }
        if let error {
            failAttempt(reason: String(localized: "Service discovery failed: \(error.localizedDescription)"),
                        cancelling: peripheral)
            return
        }
        let services = peripheral.services ?? []
        guard services.contains(where: { $0.uuid == serviceUUID }) else {
            failAttempt(reason: String(localized: "Not a \(kind.displayName)"), cancelling: peripheral)
            return
        }
        pendingServiceDiscoveries = services.count
        for service in services {
            peripheral.discoverCharacteristics(nil, for: service)
        }
    }

    func peripheral(_ peripheral: CBPeripheral,
                    didDiscoverCharacteristicsFor service: CBService,
                    error: Error?) {
        guard isCurrent(peripheral) else { return }

        // A service that refuses discovery is not fatal — the one that matters is checked
        // when the walk finishes. Notify and write PREFER the profile's own service (see
        // `notifyElsewhere`); alternates, tare, battery and firmware match anywhere.
        let isProfileService = service.uuid == serviceUUID
        for characteristic in service.characteristics ?? [] {
            let uuid = characteristic.uuid
            if uuid == notifyUUID {
                if isProfileService {
                    notifyCharacteristic = characteristic
                } else if notifyElsewhere == nil {
                    notifyElsewhere = characteristic
                }
            }
            if let writeUUID, uuid == writeUUID {
                if isProfileService {
                    writeCharacteristic = characteristic
                } else if writeElsewhere == nil {
                    writeElsewhere = characteristic
                }
            }
            // The Entralpi's second candidate is declared under the Weight Scale service,
            // not the UART one.
            if alternateNotifyUUIDs.contains(uuid),
               !alternateNotifyCharacteristics.contains(where: { $0 === characteristic }) {
                alternateNotifyCharacteristics.append(characteristic)
            }
            if let tareUUID, uuid == tareUUID, tareCharacteristic == nil {
                tareCharacteristic = characteristic
            }
            if capabilities.hasStandardBattery, uuid == Self.batteryLevelUUID,
               batteryCharacteristic == nil {
                batteryCharacteristic = characteristic
            }
            if uuid == Self.firmwareRevisionUUID, firmwareCharacteristic == nil {
                firmwareCharacteristic = characteristic
            }
            if uuid == Self.softwareRevisionUUID, softwareRevisionCharacteristic == nil {
                softwareRevisionCharacteristic = characteristic
            }
            if capabilities.requiresRemoteCalibration, uuid == Self.serialNumberUUID,
               serialCharacteristic == nil {
                serialCharacteristic = characteristic
            }
        }

        pendingServiceDiscoveries = max(0, pendingServiceDiscoveries - 1)
        guard pendingServiceDiscoveries == 0 else { return }
        finishDiscovery(on: peripheral)
    }

    private func finishDiscovery(on peripheral: CBPeripheral) {
        guard !discoveryFinished else { return }
        discoveryFinished = true

        // Fall back to a match found in another service only now that the whole table has
        // been walked, so the profile's own service always wins if it had one.
        if notifyCharacteristic == nil { notifyCharacteristic = notifyElsewhere }
        if writeCharacteristic == nil { writeCharacteristic = writeElsewhere }

        // The profile's own notify characteristic FIRST, then the alternates, keeping only
        // the ones that can actually push.
        var candidates: [CBCharacteristic] = []
        for candidate in [notifyCharacteristic].compactMap({ $0 }) + alternateNotifyCharacteristics
        where candidate.properties.contains(.notify) || candidate.properties.contains(.indicate) {
            guard !candidates.contains(where: { $0 === candidate }) else { continue }
            candidates.append(candidate)
        }
        guard !candidates.isEmpty else {
            let found = notifyCharacteristic != nil || !alternateNotifyCharacteristics.isEmpty
            failAttempt(reason: found ? String(localized: "Data characteristic does not notify")
                                      : String(localized: "Missing data characteristic"),
                        cancelling: peripheral)
            return
        }
        if !profile.streamStartPayloads.isEmpty, writeCharacteristic == nil {
            failAttempt(reason: String(localized: "Missing control characteristic"), cancelling: peripheral)
            return
        }
        // The decoder is minted HERE, one per link — except for a gauge whose counts need
        // a coefficient, which gets none until `resolveCalibration` has one: Frez's rule is
        // no calibrated force until the lookup has succeeded.
        decoder = capabilities.requiresRemoteCalibration ? nil : kind.makeFrameDecoder()
        streamCharacteristics = candidates
        pendingSubscriptions = candidates.count
        for candidate in candidates { peripheral.setNotifyValue(true, for: candidate) }
    }

    func peripheral(_ peripheral: CBPeripheral,
                    didUpdateNotificationStateFor characteristic: CBCharacteristic,
                    error: Error?) {
        guard isCurrent(peripheral),
              streamCharacteristics.contains(where: { $0 === characteristic }) else { return }
        pendingSubscriptions = max(0, pendingSubscriptions - 1)

        if error == nil, characteristic.isNotifying {
            if !notifyingCharacteristics.contains(where: { $0 === characteristic }) {
                notifyingCharacteristics.append(characteristic)
            }
        } else if pendingSubscriptions == 0, notifyingCharacteristics.isEmpty {
            // Only once EVERY candidate has answered and none streams; with alternates a
            // single refusal is expected.
            failAttempt(reason: error.map {
                            String(localized: "Notification subscription failed: \($0.localizedDescription)")
                        } ?? String(localized: "Notification subscription failed"),
                        cancelling: peripheral)
            return
        }

        // The first candidate to notify establishes the link; the rest are already home.
        guard !notifyingCharacteristics.isEmpty, !state.isConnected else { return }

        connectDeadlineTask?.cancel()
        connectDeadlineTask = nil
        attemptsRemaining = Self.attemptLimit
        // BEFORE publishing `.connected`, because that publish runs synchronously into
        // `DeviceStore` and can reach `startStreaming` in the same turn — the setup writes
        // have to be in the queue ahead of any start payload.
        writeOneTimeSetupPayloadsIfNeeded()
        state = .connected

        // Fire-and-forget and NOT serialized: a GATT read's reply names its
        // characteristic, so two reads cannot cross-pair as the Tindeq's replies did.
        if batteryCharacteristic != nil { readBatteryLevel() }
        if let firmwareCharacteristic {
            peripheral.readValue(for: firmwareCharacteristic)
        } else if let softwareRevisionCharacteristic {
            peripheral.readValue(for: softwareRevisionCharacteristic)
        }
        beginCalibrationIfNeeded(on: peripheral)
        drainWriteQueue()
    }

    // MARK: - Remote calibration

    /// Frez's connection order: subscribe, read the serial, fetch the coefficient, and
    /// only then let counts become kilograms. The start may already be queued; until the
    /// decoder exists its notifications are dropped at `ingest` — fail-closed.
    private func beginCalibrationIfNeeded(on peripheral: CBPeripheral) {
        guard capabilities.requiresRemoteCalibration else { return }
        guard let serialCharacteristic else {
            onDiagnostic?(.calibration(.failed(serial: nil, failure: .missingSerial)))
            return
        }
        onDiagnostic?(.calibration(.waitingForSerial))
        peripheral.readValue(for: serialCharacteristic)
    }

    /// The serial arrived (or failed to). One lookup per link, tied to the generation that
    /// asked, so an answer for a connection that has since gone mints nothing.
    private func resolveCalibration(serial rawSerial: String?) {
        let serial = rawSerial?.trimmingCharacters(in: .whitespacesAndNewlines.union(["\0"])) ?? ""
        guard !serial.isEmpty else {
            onDiagnostic?(.calibration(.failed(serial: nil, failure: .missingSerial)))
            return
        }
        guard let calibration else {
            onDiagnostic?(.calibration(.failed(serial: serial, failure: .noAccessKey)))
            return
        }
        onDiagnostic?(.calibration(.resolving(serial: serial)))
        let generation = self.generation
        calibrationTask?.cancel()
        calibrationTask = Task { [weak self] in
            let result = await calibration.calibration(forSerial: serial)
            guard !Task.isCancelled, let self,
                  self.generation == generation, self.state.isConnected else { return }
            self.calibrationTask = nil
            switch result {
            case .success(let answer):
                self.decoder = self.kind.makeCalibratedFrameDecoder(coefficient: answer.coefficient)
                self.onDiagnostic?(.calibration(.ready(serial: serial, calibration: answer)))
            case .failure(let failure):
                self.onDiagnostic?(.calibration(.failed(serial: serial, failure: failure)))
            }
        }
    }

    func peripheral(_ peripheral: CBPeripheral, didWriteValueFor characteristic: CBCharacteristic,
                    error: Error?) {
        guard isCurrent(peripheral), inFlightWrite != nil else { return }
        // A failed write is dropped, not retried: a lost start is recovered by the silence
        // watchdog — one recovery path instead of two that can disagree.
        clearInFlightWrite()
        drainWriteQueue()
    }

    func peripheralIsReady(toSendWriteWithoutResponse peripheral: CBPeripheral) {
        guard isCurrent(peripheral) else { return }
        drainWriteQueue()
    }

    func peripheral(_ peripheral: CBPeripheral,
                    didUpdateValueFor characteristic: CBCharacteristic,
                    error: Error?) {
        guard isCurrent(peripheral) else { return }
        // The serial is the one read whose FAILURE has to be reported: a calibrated gauge
        // left waiting on it would sit at "connected" with no force and no explanation.
        if let serialCharacteristic, characteristic === serialCharacteristic {
            let text = error == nil ? characteristic.value.map { String(decoding: $0, as: UTF8.self) } : nil
            resolveCalibration(serial: text)
            return
        }
        guard error == nil, let data = characteristic.value else { return }

        // Identity first: a characteristic we subscribed to, not merely one carrying its
        // UUID. The same decoder parses any of them.
        if streamCharacteristics.contains(where: { $0 === characteristic }) {
            ingest(data)
            return
        }
        if characteristic.uuid == Self.batteryLevelUUID {
            // 0x2A19 is one byte of PERCENT, 0…100. Truncation-safe like every other decode
            // here: an empty read is simply not a battery level.
            guard let percent = data.first else { return }
            let fraction = min(1, max(0, Double(percent) / 100))
            publishedBatteryFraction = fraction
            onEvent?(.batteryFraction(fraction))
            return
        }
        if characteristic.uuid == Self.firmwareRevisionUUID
            || characteristic.uuid == Self.softwareRevisionUUID {
            guard let text = String(data: data, encoding: .utf8), !text.isEmpty else { return }
            onEvent?(.appVersion(text))
        }
    }
}
