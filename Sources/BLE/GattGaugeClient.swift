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
/// `kind.makeFrameDecoder()`. What differs between an Entralpi and a Motherboard lives
/// entirely in `GaugeGattProfile` and the codec — which is the point of freezing those
/// two shapes.
///
/// **Deliberately simpler than `LiveProgressorClient`.** The Tindeq client carries three
/// mechanisms this one must not copy: serialized queries (its tag-0 replies carry no
/// echo of the command they answer, so one outstanding query is the only safe number),
/// the tare-integrity latch, and the peripheral quarantine. None of them applies here. A
/// GATT read's reply names its own characteristic, so nothing can cross-pair; a tare is
/// either one plain write or app-side arithmetic; and every one of these devices is a
/// PORT of hangtime-grip-connect's documented protocol that this project has never held
/// in its hands, so the honest shape is the small one, with the hard-won Tindeq
/// machinery left where it was earned.
///
/// **Isolation.** Same shape as `LiveProgressorClient`, for the same reasons: the class
/// is `@MainActor`, the central is created with `queue: .main`, and the delegate
/// conformances are declared `@preconcurrency` so main-actor-isolated methods may
/// witness CoreBluetooth's nonisolated `@objc` requirements (SE-0423). The two
/// alternatives — plain isolated methods, or `nonisolated` + `MainActor.assumeIsolated`
/// — DO NOT COMPILE; see CLAUDE.md before rediscovering them.
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
    /// capabilities claim it; a device that does not advertise it simply has no battery
    /// characteristic to find and the row stays blank, which is the honest answer.
    private static let batteryServiceUUID = CBUUID(string: "180F")
    private static let batteryLevelUUID = CBUUID(string: "2A19")
    /// Firmware Revision String, in Device Information (0x180A). Every ported device's
    /// service table in the reference lists it, so Settings' Firmware row can be filled
    /// for free — one read, one event, and nothing depends on it arriving.
    private static let firmwareRevisionUUID = CBUUID(string: "2A26")
    private static let attemptLimit = 5

    /// **A `.withResponse` write that is never acknowledged must not wedge the queue for the
    /// life of the link.** `inFlightWrite` is otherwise cleared only by `didWriteValueFor`,
    /// so one lost ATT response left tare, stop and every later re-kick undeliverable while
    /// notifications kept arriving perfectly — the store's freshness watchdog and the
    /// runner's silence watchdog both see a healthy stream and neither can repair this.
    /// Two seconds, the same deadline `LiveProgressorClient` gives a query reply.
    private static let writeResponseDeadlineSeconds: Double = 2

    /// **Service UUIDs that are evidence of a SERIAL MODULE, not of a device.** These are
    /// the stock 16-bit vendor services (HM-10/JDY `FFF0` and `FFE0`) and the two ubiquitous
    /// UART profiles (Nordic, Microchip), all of which ship on countless unrelated
    /// products — this repo proves it: `.entralpi` and `.pb700bt` declare the same `FFF0`
    /// AND the same `FFF4` notify characteristic. Matching a scan hit on one of them alone
    /// would adopt a stranger's serial module and hand its bytes to a codec that turns two
    /// of them into kilograms, which is the harm `GaugeKind.selectable`'s PB-700BT exclusion
    /// exists to prevent. For these, the advertised NAME has to agree as well; a long-form
    /// vendor-unique service (the Force Board's) remains proof on its own.
    private static let wellKnownServiceUUIDs: Set<String> = [
        "0000FFF0-0000-1000-8000-00805F9B34FB",   // HM-10 / JDY BLE-serial
        "0000FFE0-0000-1000-8000-00805F9B34FB",   // the same family's other service
        "6E400001-B5A3-F393-E0A9-E50E24DCCA9E",   // Nordic UART
        "49535343-FE7D-4AE5-8FA9-9FAFD205E455",   // Microchip Transparent UART
    ]

    /// **CoreBluetooth cannot filter a scan by NAME, and every ported device in the
    /// reference is filtered by name rather than by advertised service.** Web Bluetooth's
    /// `requestDevice` takes `{ name }` / `{ namePrefix }` filters, which is what
    /// hangtime-grip-connect uses for all six of these; whether any of them also puts its
    /// primary service UUID in the advertisement packet is UNVERIFIED. A service-filtered
    /// scan would therefore silently find nothing on a device that keeps its service
    /// private, so the scan is unfiltered and each hit is matched two ways: advertised
    /// service UUID, or advertised name against the reference's own filter strings.
    ///
    /// Unfiltered scanning only yields results in the foreground, which is where every
    /// connect in this app happens (the runner never scans; it is handed a live link).
    private static func nameHints(for kind: GaugeKind) -> [String] {
        switch kind {
        case .entralpi: ["ENTRALPI"]
        case .forceboard: ["Force Board"]
        case .climbro: ["Climbro"]
        case .motherboard: ["Motherboard"]
        // The reference accepts both, and the two model lines share this protocol.
        case .cts500: ["CTS500", "CTS-300"]
        case .pb700bt: ["NSD Workout"]
        // Not driven by this client: the Progressor has its own, and the WH-C06 is
        // matched on manufacturer data by `BroadcastGaugeClient`.
        case .progressor, .whc06: []
        }
    }

    private struct WriteEntry {
        enum Target { case stream, tare }
        let payload: Data
        let target: Target
        /// Set on the LAST payload of a start sequence, so the ring's "start written"
        /// breadcrumb means the whole sequence reached the device rather than its first
        /// byte.
        let startCause: StreamStartCause?
    }

    private var central: CBCentralManager?
    private var peripheral: CBPeripheral?

    private var notifyCharacteristic: CBCharacteristic?
    private var writeCharacteristic: CBCharacteristic?
    private var tareCharacteristic: CBCharacteristic?
    private var batteryCharacteristic: CBCharacteristic?
    private var firmwareCharacteristic: CBCharacteristic?

    /// Alternate notify characteristics found anywhere in the table, and the subscription
    /// bookkeeping for the whole set.
    ///
    /// **The link is established when the primary OR any alternate is notifying.** One of
    /// the Entralpi's two candidates may well refuse or stay mute; only ALL of them failing
    /// is a device that cannot stream, so the attempt fails when every candidate has
    /// answered and none of them is notifying.
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
    /// **The PitchSix Force Board is why this exists**: its Device Mode characteristic —
    /// the one every start and stop payload is written to — belongs to a different service
    /// from the one that streams weight, and `GaugeGattProfile` has room for exactly one
    /// service UUID. Preferring the profile's own service still matters, because a 16-bit
    /// characteristic UUID like `fff4` is not unique across a device's service table and
    /// subscribing to a same-numbered characteristic in the wrong service would look like
    /// a device that connects and never speaks.
    private var notifyElsewhere: CBCharacteristic?
    private var writeElsewhere: CBCharacteristic?

    /// Discovery walks EVERY service, not just the profile's one: the ForceBoard's write
    /// and tare characteristics live in other services, and the Battery Service is a
    /// separate service by definition. `GaugeGattProfile` names characteristics, not the
    /// services that hold them, so the only way to honour it is to look everywhere. It
    /// costs one extra round of discovery on a connect that happens twice a day.
    private var pendingServiceDiscoveries = 0
    /// One subscription per link. Without this, a second characteristics callback for a
    /// service already counted would run the finish step again — re-subscribing and, worse,
    /// minting a FRESH decoder mid-stream, which throws away a half-reassembled frame.
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

    init(kind: GaugeKind, profile: GaugeGattProfile) {
        self.kind = kind
        self.profile = profile
        self.capabilities = kind.capabilities
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
    }

    // MARK: - ProgressorClient

    func connect() {
        // Same guard as the Tindeq client: `wantsConnection` covers the backoff and
        // radio-off gaps, where the public state is not busy but the intent is live. A
        // second tap must not replenish the retry budget.
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

    /// **A plain disconnect.** No ported device documents a sleep opcode, and inventing a
    /// write for one would be guessing at bytes on somebody else's hardware. Dropping the
    /// link is also what actually saves the battery on these devices: they idle down on
    /// their own schedule once nobody is subscribed.
    func sleepDevice() {
        disconnect()
    }

    func send(_ command: ProgressorCommand) {
        switch command {
        case .tare:
            tareNow()
        case .startWeightMeasurement:
            // Starts carry a cause through one funnel, so a later breadcrumb can never be
            // a guess about who asked. `startStreaming(cause:)` is the only start path.
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
            // Tindeq control-point commands with no counterpart on any ported device.
            // Silently ignored rather than mapped onto a plausible-looking write: these
            // protocols are ports, and a speculative command is a write to hardware
            // nobody here has tested.
            return
        }
    }

    /// **Never gated on an "is streaming" flag.** That flag can only ever cause the one
    /// command a session depends on to be skipped, and re-sending a start to a device
    /// already streaming is harmless — the rule the first hardware session taught the
    /// Tindeq client, which applies identically here.
    ///
    /// **No engine timeline break belongs to a re-kick of THIS client.** `RunnerSession`
    /// sends `RunnerEvent.streamRestarted` around the call only for a gauge with a clock of
    /// its own; a synthetic stamp is host uptime, which no device restart can rewind, so
    /// there is no epoch here to break — and the break would clear the accrual anchor and
    /// the arming debounce for nothing.
    func startStreaming(cause: StreamStartCause) {
        guard state.isConnected, !notifyingCharacteristics.isEmpty else {
            onDiagnostic?(.streamStartDeferred(cause))
            return
        }
        guard !profile.streamStartPayloads.isEmpty else {
            // Subscribing IS the start on these devices (the Entralpi streams the moment
            // notifications are on). The ring records the start as having reached the
            // device, because it has: the subscription is the act that starts it. Staying
            // silent instead would leave a request with no write in the one log that
            // exists to explain a stalled stream.
            onDiagnostic?(.streamStartWritten(cause))
            return
        }

        guard profile.startPayloadDelaySeconds <= 0 else {
            // **A SEQUENCE in flight absorbs further starts, and that is NOT the
            // "never gate the start on isStreaming" mistake.** That rule is about a STATE
            // FLAG, which can go stale and then skip the one command a session depends on
            // forever. This is a time-bounded window that always runs to completion — every
            // payload is written or the link is gone — so folding delays a redundant write
            // by at most `startPayloadDelaySeconds`, and the Motherboard's whole reason for
            // pacing is that the 500 ms watchdog would otherwise re-ask for the calibration
            // table four times inside the 2.5 s it takes to arrive.
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
    /// calibration dump, and only then writes "S30". Back-to-back through the paced queue
    /// the two land milliseconds apart, which can put the start command in the middle of the
    /// device's own reply.
    private func beginStartSequence(cause: StreamStartCause) {
        let payloads = profile.streamStartPayloads
        let delay = profile.startPayloadDelaySeconds
        startSequenceTask = Task { [weak self] in
            for (index, payload) in payloads.enumerated() {
                if index > 0 {
                    do {
                        try await Task.sleep(for: .seconds(delay))
                    } catch {
                        return   // cancelled with the link; `clearLinkState` clears the slot
                    }
                }
                guard !Task.isCancelled, let self, self.state.isConnected else { return }
                self.enqueueStart(payload, cause: index == payloads.count - 1 ? cause : nil)
            }
            self?.startSequenceTask = nil
        }
    }

    /// Enqueue one start payload, COALESCED against the queue.
    ///
    /// A byte-identical start payload still sitting unwritten is the write this cause is
    /// asking for, so a second copy would only spend the radio twice: the watchdog re-kicks
    /// every 500 ms while a stream is silent, and on the Motherboard that meant asking for
    /// the calibration table again before the first ask had left the queue. The breadcrumb
    /// is still recorded — the ring must not show a start requested with nothing delivering
    /// it, when the queued write is what delivers it.
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
    /// `streamStartPayloads` is re-sent on every re-kick by design, so a device
    /// configuration folded into it gets re-issued roughly 1500 times across a silent
    /// twenty-minute session. The CTS500's sampling-rate command is EEPROM-class and
    /// plausibly resets the ADC, which would make each re-kick prevent the stream it is
    /// trying to revive.
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
        // Captures the newest reading as the offset. The reference averages five seconds
        // of samples instead; that is wrong for this app, where Tare is a button whose
        // effect must be visible in the frame it is tapped, and `TarePolicy` already
        // refuses to tare against a reading that is not live. With no reading yet the
        // offset is LEFT ALONE rather than zeroed: a fresh link has an offset of zero
        // already, and silently discarding a good offset because the stream went quiet
        // would move every later reading by the load that was on the gauge.
        softwareTare.capture()
    }

    // MARK: - Writes

    private func enqueue(_ payload: Data, target: WriteEntry.Target,
                         startCause: StreamStartCause? = nil) {
        writeQueue.append(WriteEntry(payload: payload, target: target, startCause: startCause))
        drainWriteQueue()
    }

    /// Queued and PACED, never fired back to back. CoreBluetooth silently discards a
    /// `.withoutResponse` write when its buffer is not ready — no error, no callback — and
    /// accepts one `.withResponse` write at a time. The Motherboard's start is a text
    /// command and the CTS500's is a checksummed frame; both would lose a payload.
    private func drainWriteQueue() {
        guard state.isConnected, let peripheral, isCurrent(peripheral) else { return }

        while let next = writeQueue.first {
            guard let characteristic = self.characteristic(for: next.target) else {
                // The link does not have what this write needs. Dropping it is better than
                // holding the queue: the tare falls back to arithmetic and a stop payload
                // for a missing characteristic was never going to arrive.
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
    /// `writeResponseDeadlineSeconds`. Expiry treats the write as lost and drains, exactly
    /// as an acknowledgement would: a failed write is dropped rather than retried here, and
    /// a lost start is re-sent by the store's silence watchdog anyway.
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

        // BLE links belong to the system daemon, not to this process, so after a relaunch
        // the gauge may already be connected. Worth a try even though these devices may
        // not advertise the service — `retrieveConnectedPeripherals` matches on what the
        // device actually exposes, not on its advertisement.
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
        // Never overwrite an advertised local name with a nil `peripheral.name`: the
        // advertisement is what the scan matched on, and on these devices it is often the
        // only name there is until the link is up.
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
        // One second, which is also what keeps this client honest without the Tindeq's
        // quarantine slot: a cancelled peripheral's terminal callback belongs to a
        // superseded generation and is ignored, and the backoff means a rescan never
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
        pendingServiceDiscoveries = 0
        discoveryFinished = false
        writeQueue.removeAll()
        // The paced sequence belongs to this link: its remaining payloads mean nothing on
        // the next one, and a task left in the slot would fold every future start into a
        // sequence that has already returned.
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
        // Copy out, mutate, store back: the decoders are structs (the protocol is written
        // for `mutating ingest`), and losing the copy would lose the reassembly buffer
        // that makes the Motherboard's split frames decodable at all.
        guard var working = decoder else { return }
        let readings = working.ingest(data)
        decoder = working

        if let fraction = working.batteryFraction, fraction != publishedBatteryFraction {
            // Climbro carries battery inside the data stream rather than in 0x180F.
            publishedBatteryFraction = fraction
            onEvent?(.batteryFraction(min(1, max(0, fraction))))
        }

        guard !readings.isEmpty else { return }

        // **One stamp per NOTIFICATION, shared by every reading it carried.** These
        // devices tell us nothing about the spacing of samples inside a frame, and
        // spreading them at the nominal rate would be inventing timing the engine then
        // credits as hang time. Sharing the stamp keeps the arithmetic honest: interior
        // deltas are zero and accrue nothing, and the next notification's delta carries
        // the whole elapsed interval, so the SUM — which is what the engine accrues — is
        // exactly the time that passed. `isBatchStart` marks the first reading, matching
        // the Tindeq's meaning of one notification, one batch.
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
/// Shared with `BroadcastGaugeClient` — a crane scale has nothing to write a tare to at
/// all. Kept as a value type with no clock and no Bluetooth in it so the arithmetic is
/// testable on its own, which is where the double-subtract and empty-capture mistakes
/// would otherwise hide.
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

// `@preconcurrency` is what lets main-actor-isolated methods witness CoreBluetooth's
// nonisolated `@objc` requirements — sound precisely because the central is created with
// `queue: .main`. See `LiveProgressorClient` for the full account.
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
    /// **A service-UUID match alone is only proof for a LONG-FORM vendor-unique service.**
    /// The 16-bit serial services and the two UART profiles are shared by half the BLE
    /// modules in existence (see `wellKnownServiceUUIDs`), so for those kinds the advertised
    /// name has to agree as well — otherwise a stranger's HM-10 in range is adopted as an
    /// Entralpi and its arbitrary bytes are decoded as kilograms, which then arm reps, bank
    /// hang time and set a grip's percentage targets from a fabricated max.
    private func matches(_ peripheral: CBPeripheral, advertisement: [String: Any]) -> Bool {
        let name = (advertisement[CBAdvertisementDataLocalNameKey] as? String) ?? peripheral.name
        let hints = Self.nameHints(for: kind)
        // Prefix, not equality: the reference uses `namePrefix` for the Climbro and
        // exact names elsewhere, and an exact name is its own prefix. A unit that appends
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
        // when the whole walk finishes.
        //
        // Notify and write PREFER the profile's own service and fall back to a match
        // anywhere (see `notifyElsewhere`). Alternates, tare, battery and firmware are
        // matched anywhere outright: the two standard ones are separate services by
        // definition, and a tare characteristic is only ever named by a profile that means
        // one specific handle (the CTS500's is its write characteristic).
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
            // Alternates are matched ANYWHERE outright: the Entralpi's second candidate is
            // declared under the Weight Scale service, not under the UART one, which is the
            // whole reason the reference subscribes to both.
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
        // the ones that can actually push. A characteristic present but mute is not a
        // candidate, and on a device with alternates it is not a failure either.
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
        // The decoder is minted HERE, one per link, and dropped by `clearLinkState`.
        decoder = kind.makeFrameDecoder()
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
            // Only once EVERY candidate has answered and none of them streams. With
            // alternates in play a single refusal is expected — the reference subscribes to
            // both of the Entralpi's "rx" characteristics precisely because its source
            // cannot say which one is real.
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

        // Both reads are fire-and-forget and NOT serialized: unlike the Tindeq control
        // point, a GATT read's reply names the characteristic it came from, so two
        // outstanding reads cannot cross-pair the way a version reply once parsed as
        // battery millivolts.
        if batteryCharacteristic != nil { readBatteryLevel() }
        if let firmwareCharacteristic { peripheral.readValue(for: firmwareCharacteristic) }
        drainWriteQueue()
    }

    func peripheral(_ peripheral: CBPeripheral, didWriteValueFor characteristic: CBCharacteristic,
                    error: Error?) {
        guard isCurrent(peripheral), inFlightWrite != nil else { return }
        // A failed write is dropped rather than retried. The Tindeq client retries because
        // its tare must be acknowledged before a stream may start; here a lost start is
        // recovered by the store's silence watchdog, which re-sends it — one recovery path
        // instead of two that can disagree.
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
        guard isCurrent(peripheral), error == nil, let data = characteristic.value else { return }

        // Identity first: the stream is a characteristic we subscribed to, not merely one
        // carrying its UUID. Any of them may be the one that speaks — the same decoder
        // parses either, which is what makes subscribing to both safe.
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
        if characteristic.uuid == Self.firmwareRevisionUUID {
            guard let text = String(data: data, encoding: .utf8), !text.isEmpty else { return }
            onEvent?(.appVersion(text))
        }
    }
}
