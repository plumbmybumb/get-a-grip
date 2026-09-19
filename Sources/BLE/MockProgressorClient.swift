// SPDX-License-Identifier: MPL-2.0
// Original contributions Copyright 2026 Nuri Bruner.

import Foundation

/// A synthetic Progressor.
///
/// This is not a convenience — it is the only way to run the app anywhere other
/// than a physical iPhone with the real gauge attached, because **the Simulator has
/// no Bluetooth stack at all**. It is also what a demo mode uses, so someone
/// without hardware (an App Store reviewer, a curious climber) can see a whole
/// session run.
///
/// Same `@MainActor` shape as the live client; the sample pump is a `Task` created
/// in main-actor context, so it inherits that isolation.
@MainActor
final class MockProgressorClient: ProgressorClient {
    var onEvent: ((ProgressorEvent) -> Void)?
    var onPacketBoundary: ((PacketBoundary) -> Void)?
    var onStateChange: ((ProgressorConnectionState) -> Void)?
    var onDiagnostic: ((ProgressorClientDiagnostic) -> Void)?

    private(set) var state: ProgressorConnectionState = .idle {
        didSet { if state != oldValue { onStateChange?(state) } }
    }
    private(set) var deviceName: String? = "Progressor_MOCK"

    var profile: MockForceProfile

    /// The device streams 80 samples/sec, delivered in batches of ~8.
    private static let sampleHz: Double = 80
    private static let batchSize = 8
    private static let microsPerSample: UInt32 = UInt32(1_000_000 / 80)
    /// `-mockClumpMS N` (DEBUG only): hand batches over in CLUMPS N ms apart instead of
    /// one every 100 ms — an iPad-style Bluetooth stack delivering notifications late and
    /// together. The samples' device timestamps are untouched; only their arrival bunches,
    /// which is exactly the shape that hid the trace on Nuri's iPad (2026-09-19).
    private static let clumpMS: Int = {
        #if DEBUG
        let args = ProcessInfo.processInfo.arguments
        if let flag = args.firstIndex(of: "-mockClumpMS"), flag + 1 < args.count,
           let ms = Int(args[flag + 1]) {
            return ms
        }
        #endif
        return 0
    }()

    /// `-mockJitterMS N` (DEBUG only): each delivery is late by a random 0…N ms, with the
    /// batches that fell due meanwhile landing together — the real radio's p95 300 ms /
    /// max 420 ms gaps between ~190 ms packets (Nuri's phone, 2026-09-19). The device
    /// timestamps keep their 80 Hz; only arrival wobbles, which is what the trace has to
    /// draw through.
    private static let jitterMS: Int = {
        #if DEBUG
        let args = ProcessInfo.processInfo.arguments
        if let flag = args.firstIndex(of: "-mockJitterMS"), flag + 1 < args.count,
           let ms = Int(args[flag + 1]) {
            return ms
        }
        #endif
        return 0
    }()

    private var connectTask: Task<Void, Never>?
    private var connectionGeneration: UInt64 = 0
    private var pump: Task<Void, Never>?
    private var elapsedSamples: UInt64 = 0
    private var deviceMicros: UInt32 = 0
    private var tareOffsetKg: Double = 0

    init(profile: MockForceProfile = .clean) {
        self.profile = profile
    }

    deinit {
        connectTask?.cancel()
        pump?.cancel()
    }

    // MARK: - ProgressorClient

    func connect() {
        guard !state.isConnected, !state.isBusy else { return }
        connectionGeneration &+= 1
        let generation = connectionGeneration
        state = .scanning
        connectTask = Task { [weak self] in
            // A beat of latency so the connecting UI is actually exercised rather
            // than skipped past in a single frame.
            do {
                try await Task.sleep(for: .milliseconds(300))
            } catch {
                return
            }
            guard !Task.isCancelled,
                  let self,
                  self.connectionGeneration == generation else { return }
            self.state = .connecting

            do {
                try await Task.sleep(for: .milliseconds(150))
            } catch {
                return
            }
            guard !Task.isCancelled,
                  self.connectionGeneration == generation,
                  self.state == .connecting else { return }
            self.connectTask = nil
            self.state = .connected
            self.onEvent?(.appVersion("mock-1.0"))
            self.onEvent?(.battery(millivolts: 3980))
        }
    }

    func disconnect() {
        connectionGeneration &+= 1
        connectTask?.cancel()
        connectTask = nil
        stopPump()
        state = .disconnected(reason: nil)
    }

    func send(_ command: ProgressorCommand) {
        guard state.isConnected else { return }
        switch command {
        case .tare:
            // Tare zeroes whatever is on the gauge right now — the same trap as the
            // real device: tare under load and every reading after it is wrong.
            tareOffsetKg = rawForceNow()
        case .startWeightMeasurement:
            // Starts need a cause so the diagnostic ring cannot claim a reason that the
            // caller never supplied; `startStreaming(cause:)` is the only start path.
            break
        case .stopWeightMeasurement:
            stopPump()
        case .getBatteryVoltage:
            onEvent?(.battery(millivolts: 3980))
        case .getAppVersion:
            onEvent?(.appVersion("mock-1.0"))
        case .enterSleep:
            stopPump()
            state = .disconnected(reason: String(localized: "Device asleep"))
        default:
            break
        }
    }

    func startStreaming(cause: StreamStartCause) {
        guard state.isConnected else { return }
        onDiagnostic?(.streamStartWritten(cause))
        deviceMicros = 0
        startPump()
    }

    // MARK: - Sample pump

    private func startPump() {
        guard pump == nil else { return }
        elapsedSamples = 0
        deviceMicros = 0
        pump = Task { [weak self] in
            // Batches fall due on the device's own 80 Hz schedule whatever the pump does;
            // the pump only decides WHEN it hands them over. One per period by default;
            // under `-mockClumpMS` it wakes once per clump, under `-mockJitterMS` each wake
            // is late by a random amount — and every batch that fell due while it slept
            // lands together, exactly as a radio stack delivers late notifications.
            let batchMS = Int(1000 * Double(Self.batchSize) / Self.sampleHz)
            let batch = Duration.milliseconds(batchMS)
            let clump = Duration.milliseconds(max(Self.clumpMS, batchMS))
            var due = ContinuousClock.now + batch
            while !Task.isCancelled {
                let jitter = Duration.milliseconds(Self.jitterMS > 0 ? Int.random(in: 0...Self.jitterMS) : 0)
                do {
                    try await Task.sleep(until: due + (clump - batch) + jitter, clock: .continuous)
                } catch {
                    return
                }
                guard let self else { return }
                let now = ContinuousClock.now
                // Everything handed over in one wake is ONE packet, marked once: the real
                // radio's late notification carries all its samples under a single mark,
                // and the store learns the packet span from packet starts.
                var packetStart = true
                while due <= now {
                    self.emitBatch(packetStart: packetStart)
                    packetStart = false
                    due += batch
                }
            }
        }
    }

    private func stopPump() {
        pump?.cancel()
        pump = nil
        // Each demo stream replays the profile from zero. Release its synthetic load
        // here too, so the next session's pre-start tare cannot capture the last pull.
        elapsedSamples = 0
        tareOffsetKg = 0
    }

    /// One notification's worth of samples, exactly as the device batches them —
    /// which is what makes the runner's "accrue from device timestamps, not arrival
    /// time" rule testable against something realistic.
    private func emitBatch(packetStart: Bool = true) {
        onPacketBoundary?(.began(receivedAt: ProcessInfo.processInfo.systemUptime))
        defer { onPacketBoundary?(.ended) }
        for index in 0..<Self.batchSize {
            let seconds = Double(elapsedSamples) / Self.sampleHz
            let kg = MockForceProfile.force(at: seconds, profile: profile) - tareOffsetKg
            onEvent?(.sample(ForceSample(kg: kg, deviceMicros: deviceMicros,
                                         isBatchStart: packetStart && index == 0)))
            elapsedSamples += 1
            // Wrapping on purpose: over a long session the real device's UInt32 µs
            // clock rolls over at ~71.6 minutes, and the app must survive it.
            deviceMicros = deviceMicros &+ Self.microsPerSample
        }
    }

    private func rawForceNow() -> Double {
        MockForceProfile.force(at: Double(elapsedSamples) / Self.sampleHz, profile: profile)
    }
}

// MARK: - Force profiles

/// Scripted force traces. Pure functions of elapsed time, so a test can sample the
/// same curve the UI sees without running any timers.
enum MockForceProfile: String, CaseIterable, Sendable {
    /// Textbook: sharp ramp, steady plateau, clean release.
    case clean
    /// Wobbles across the threshold and briefly drops — the case that decides
    /// whether hysteresis and dropout grace are tuned right.
    case shaky
    /// Fades through the hold, the way a real set's last rep does.
    case weak
    /// Nothing on the gauge. For checking idle/zero-drift behaviour.
    case idle

    /// Work + rest cycle, chosen to match the default no-hang shape (10 s on,
    /// 20 s off) so a mock run lines up with a real routine.
    static let workSeconds: Double = 10
    static let restSeconds: Double = 20

    static func force(at seconds: Double, profile: MockForceProfile) -> Double {
        let jitter = sin(seconds * 37.7) * 0.18 + sin(seconds * 13.1) * 0.1
        guard profile != .idle else { return max(0, 0.15 + jitter * 0.3) }

        let cycle = workSeconds + restSeconds
        let phase = seconds.truncatingRemainder(dividingBy: cycle)
        guard phase < workSeconds else { return max(0, 0.2 + jitter * 0.3) }

        // Ramp on and off so the trace has real edges to detect.
        let rampIn = min(1, phase / 0.45)
        let rampOut = min(1, (workSeconds - phase) / 0.35)
        let envelope = min(rampIn, rampOut)

        let plateau: Double
        switch profile {
        case .clean:
            plateau = 22 + jitter
        case .shaky:
            // Rides around the threshold with a genuine dip near the middle.
            let wobble = sin(phase * 2.9) * 3.5
            let dip = (phase > 5.2 && phase < 5.7) ? -14.0 : 0
            plateau = 20 + wobble + dip + jitter
        case .weak:
            plateau = 24 - (phase / workSeconds) * 12 + jitter
        case .idle:
            plateau = 0
        }
        return max(0, plateau * envelope)
    }
}
