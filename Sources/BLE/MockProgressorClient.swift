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
            tareOffsetKg += rawForceNow()
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
        startPump()
    }

    // MARK: - Sample pump

    private func startPump() {
        guard pump == nil else { return }
        elapsedSamples = 0
        deviceMicros = 0
        pump = Task { [weak self] in
            while !Task.isCancelled {
                guard let self else { return }
                self.emitBatch()
                do {
                    try await Task.sleep(
                        for: .milliseconds(Int(1000 * Double(Self.batchSize) / Self.sampleHz))
                    )
                } catch {
                    return
                }
            }
        }
    }

    private func stopPump() {
        pump?.cancel()
        pump = nil
    }

    /// One notification's worth of samples, exactly as the device batches them —
    /// which is what makes the runner's "accrue from device timestamps, not arrival
    /// time" rule testable against something realistic.
    private func emitBatch() {
        onPacketBoundary?(.began(receivedAt: ProcessInfo.processInfo.systemUptime))
        defer { onPacketBoundary?(.ended) }
        for _ in 0..<Self.batchSize {
            let seconds = Double(elapsedSamples) / Self.sampleHz
            let kg = MockForceProfile.force(at: seconds, profile: profile) - tareOffsetKg
            onEvent?(.sample(ForceSample(kg: kg, deviceMicros: deviceMicros)))
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
