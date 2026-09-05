// SPDX-License-Identifier: MPL-2.0
// Original contributions Copyright 2026 Nuri Bruner.

import Foundation

/// What the app knows about the link to the gauge.
enum ProgressorConnectionState: Sendable, Equatable {
    /// Nothing attempted yet — no CBCentralManager exists, so no permission prompt
    /// has been shown.
    case idle
    case bluetoothOff
    case unauthorized
    /// No BLE hardware. This is what the Simulator always reports, which is why the
    /// mock client exists.
    case unsupported
    case scanning
    case connecting
    case connected
    case disconnected(reason: String?)

    var isConnected: Bool { self == .connected }

    var isBusy: Bool {
        switch self {
        case .scanning, .connecting: true
        default: false
        }
    }

    /// User-facing one-liner for the device chip.
    var label: String {
        switch self {
        case .idle: String(localized: "Not connected")
        case .bluetoothOff: String(localized: "Bluetooth off")
        case .unauthorized: String(localized: "Bluetooth denied")
        case .unsupported: String(localized: "No Bluetooth")
        case .scanning: String(localized: "Searching…")
        case .connecting: String(localized: "Connecting…")
        case .connected: String(localized: "Connected")
        case .disconnected(let reason): reason ?? String(localized: "Disconnected")
        }
    }
}

/// Why a stream start was requested. The cause stays attached if the write has to wait
/// behind a tare, because a later write without its original reason makes the next
/// hardware trace impossible to interpret.
enum StreamStartCause: String, Sendable, CaseIterable {
    case initial
    case reconnect
    case foreground
    case watchdog
    case tareRecovery
    case manualMeasurement
    /// The human tapped Wake. Distinct from `.watchdog` on purpose: attributing a manual
    /// recovery to the automatic one would make the breadcrumb report lie about who
    /// revived the stream, in the one log that exists to explain exactly that.
    case manualWake

    var label: String {
        switch self {
        case .initial: "initial"
        case .reconnect: "reconnect"
        case .foreground: "foreground"
        case .watchdog: "watchdog"
        case .tareRecovery: "tare recovery"
        case .manualMeasurement: "manual measurement"
        case .manualWake: "manual wake"
        }
    }
}

/// Why a stream stopped. The counterpart to `StreamStartCause`, and the reason the
/// breadcrumb ring can tell a genuine stall from the app doing exactly what it should.
/// **Deliberately has no default anywhere it is taken.** A defaulted cause is a cause
/// that is wrong at the call sites nobody revisited, and a diagnostic that quietly
/// mislabels an automatic stop as "stopped by hand" is worse than one that says nothing:
/// it sends whoever reads the log looking for a user who was never there.
enum StreamStopCause: String, Sendable, CaseIterable {
    case sessionEnded
    case userStopped
    case measurementComplete
    case screenClosed
    case timedOut
    case disconnecting
    case sleeping

    var label: String {
        switch self {
        case .sessionEnded: "session ended"
        case .userStopped: "stopped by hand"
        case .measurementComplete: "measurement finished"
        case .screenClosed: "screen closed"
        case .timedOut: "timed out"
        case .disconnecting: "disconnecting"
        case .sleeping: "gauge sleeping"
        }
    }
}

/// Client-side lifecycle facts that are not themselves connection-state changes.
/// They let the store's in-memory breadcrumb ring include quarantine boundaries without
/// passing CoreBluetooth objects out of the client.
enum ProgressorClientDiagnostic: Sendable {
    case retiringPeripheral
    case quarantineReleased
    case streamStartDeferred(StreamStartCause)
    case streamStartWritten(StreamStartCause)
}

/// The seam between the app and the gauge.
///
/// Marked `@MainActor` on the PROTOCOL so isolation propagates to every conformer:
/// the live client passes `queue: .main` to CoreBluetooth, so its delegate callbacks
/// genuinely arrive on the main thread and there is no isolation boundary to cross
/// anywhere in the stack. At ~10 notifications/sec of microsecond-cheap TLV decoding
/// there is nothing to gain from a second isolation domain, and plenty to lose.
///
/// Callback-style rather than `AsyncStream` for the same reason — the store wires
/// `onEvent` the way the sibling apps wire their session callbacks, and every hop
/// stays visible in one place.
@MainActor
protocol ProgressorClient: AnyObject {
    var onEvent: ((ProgressorEvent) -> Void)? { get set }
    var onPacketBoundary: ((PacketBoundary) -> Void)? { get set }
    var onStateChange: ((ProgressorConnectionState) -> Void)? { get set }
    var onDiagnostic: ((ProgressorClientDiagnostic) -> Void)? { get set }

    var state: ProgressorConnectionState { get }
    /// Advertised name of the connected unit, e.g. `Progressor_1234`.
    var deviceName: String? { get }
    /// Which physical device family this client drives. Capability gating — tare
    /// behaviour, background streaming, timing source — reads `kind.capabilities`,
    /// never the concrete client type.
    var kind: GaugeKind { get }

    /// Scan for, and connect to, the first Progressor found. Idempotent.
    func connect()
    func disconnect()
    func send(_ command: ProgressorCommand)
    func startStreaming(cause: StreamStartCause)
    /// Ask the gauge to sleep and end this connection without allowing an automatic
    /// reconnect to race the power-down command.
    func sleepDevice()
}

enum PacketBoundary {
    case began(receivedAt: TimeInterval)
    case ended
}

extension ProgressorClient {
    // Existing test/third-party adapters can continue to emit individual events.
    var onPacketBoundary: ((PacketBoundary) -> Void)? {
        get { nil }
        set {}
    }

    /// The default covers the two existing clients (live Tindeq and the mock,
    /// which scripts a Tindeq); multi-device clients override it.
    var kind: GaugeKind { .progressor }

    func tare() { send(.tare) }
    func stopStreaming() { send(.stopWeightMeasurement) }
    func readBattery() { send(.getBatteryVoltage) }
    /// Explicit power-down only; normal session completion keeps the gauge available
    /// for the second daily session.
    func sleepDevice() { send(.enterSleep) }
}
