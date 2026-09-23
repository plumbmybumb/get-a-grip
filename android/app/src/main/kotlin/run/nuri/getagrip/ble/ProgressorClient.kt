// SPDX-License-Identifier: MPL-2.0
// Original contributions Copyright 2026 Nuri Bruner.

package run.nuri.getagrip.ble

import run.nuri.getagrip.engine.GaugeKind
import run.nuri.getagrip.engine.L10n
import run.nuri.getagrip.engine.ProgressorCommand
import run.nuri.getagrip.engine.ProgressorEvent

/// What the app knows about the link to the gauge.
///
/// TRANSLATION NOTE: Swift's `enum` becomes a sealed interface (as `ProgressorEvent` in
/// `:engine`). `data object`/`data class` give structural equality; thread confinement is a
/// contract stated on `ProgressorClient`, not compiler-checked.
sealed interface ProgressorConnectionState {
    /// Nothing attempted yet — no scanner or GATT client, so no permission prompt shown.
    data object Idle : ProgressorConnectionState

    data object BluetoothOff : ProgressorConnectionState

    data object Unauthorized : ProgressorConnectionState

    /// No BLE hardware. TRANSLATION NOTE: what the iOS Simulator always reports; an Android
    /// emulator has no working BLE either, so `MockProgressorClient` is reached the same
    /// two ways.
    data object Unsupported : ProgressorConnectionState

    data object Scanning : ProgressorConnectionState

    data object Connecting : ProgressorConnectionState

    data object Connected : ProgressorConnectionState

    data class Disconnected(val reason: String? = null) : ProgressorConnectionState

    val isConnected: Boolean get() = this is Connected

    val isBusy: Boolean get() = this is Scanning || this is Connecting

    /// User-facing one-liner for the device chip.
    val label: String
        get() = when (this) {
            Idle -> L10n.tr("Not connected")
            BluetoothOff -> L10n.tr("Bluetooth off")
            Unauthorized -> L10n.tr("Bluetooth denied")
            Unsupported -> L10n.tr("No Bluetooth")
            Scanning -> L10n.tr("Searching…")
            Connecting -> L10n.tr("Connecting…")
            Connected -> L10n.tr("Connected")
            is Disconnected -> reason ?: L10n.tr("Disconnected")
        }
}

/// Why a stream start was requested. The cause stays attached if the write waits behind a
/// tare; a start without its reason makes the next hardware trace uninterpretable.
enum class StreamStartCause(val rawValue: String) {
    initial("initial"),
    reconnect("reconnect"),
    foreground("foreground"),
    watchdog("watchdog"),
    tareRecovery("tareRecovery"),
    manualMeasurement("manualMeasurement"),

    /// The human tapped Wake. Distinct from `watchdog`: crediting a manual recovery to the
    /// automatic one would lie in the one log that explains who revived the stream.
    manualWake("manualWake");

    val label: String
        get() = when (this) {
            initial -> "initial"
            reconnect -> "reconnect"
            foreground -> "foreground"
            watchdog -> "watchdog"
            tareRecovery -> "tare recovery"
            manualMeasurement -> "manual measurement"
            manualWake -> "manual wake"
        }
}

/// Why a stream stopped — lets the breadcrumb ring tell a genuine stall from the app doing
/// what it should. **No default anywhere it is taken**: a defaulted cause is wrong at every
/// call site nobody revisited, and mislabelling an automatic stop "stopped by hand" sends
/// the reader looking for a user who was never there.
enum class StreamStopCause(val rawValue: String) {
    sessionEnded("sessionEnded"),
    userStopped("userStopped"),
    measurementComplete("measurementComplete"),
    screenClosed("screenClosed"),
    timedOut("timedOut"),
    disconnecting("disconnecting"),
    sleeping("sleeping");

    val label: String
        get() = when (this) {
            sessionEnded -> "session ended"
            userStopped -> "stopped by hand"
            measurementComplete -> "measurement finished"
            screenClosed -> "screen closed"
            timedOut -> "timed out"
            disconnecting -> "disconnecting"
            sleeping -> "gauge sleeping"
        }
}

/// Client-side lifecycle facts that are not connection-state changes, so the breadcrumb
/// ring can show quarantine boundaries without Bluetooth objects leaving the client.
sealed interface ProgressorClientDiagnostic {
    /// A scanner lifecycle fact, never a device write or an advertisement payload.
    data class BroadcastScan(val event: String) : ProgressorClientDiagnostic
    data object RetiringPeripheral : ProgressorClientDiagnostic
    data object QuarantineReleased : ProgressorClientDiagnostic
    data class StreamStartDeferred(val cause: StreamStartCause) : ProgressorClientDiagnostic
    data class StreamStartWritten(val cause: StreamStartCause) : ProgressorClientDiagnostic

    /// A remotely calibrated gauge's progress to producing force; only from a kind that
    /// `requiresRemoteCalibration`.
    data class Calibration(val status: GaugeCalibrationStatus) : ProgressorClientDiagnostic
}

/// The seam between the app and the gauge.
///
/// **Isolation.** On iOS this protocol is `@MainActor`: CoreBluetooth runs on
/// `queue: .main`, so callbacks arrive on the main thread with no boundary to cross. At ~10
/// notifications/sec of cheap TLV decoding a second isolation domain gains nothing.
///
/// TRANSLATION NOTE: Kotlin has no isolation to propagate, so it is a CONTRACT: Android BLE
/// callbacks arrive on a binder thread, and each client re-posts to
/// `Dispatchers.Main.immediate` before touching state or calling back. `immediate` so a
/// `connect()` that answers synchronously (the mock, a reattach) publishes in the same
/// turn.
///
/// Callbacks rather than Flow, as on iOS: every hop stays visible in one place, and a flow
/// per fact would fan ~80 samples a second out through every collector.
interface ProgressorClient {
    var onEvent: ((ProgressorEvent) -> Unit)?
    var onPacketBoundary: ((PacketBoundary) -> Unit)?
        get() = null
        set(_) {}
    var onStateChange: ((ProgressorConnectionState) -> Unit)?
    var onDiagnostic: ((ProgressorClientDiagnostic) -> Unit)?

    val state: ProgressorConnectionState

    /// Advertised name of the connected unit, e.g. `Progressor_1234`.
    val deviceName: String?

    /// Which device family this client drives. Capability gating (tare, background
    /// streaming, timing source) reads `kind.capabilities`, never the concrete client type.
    ///
    /// TRANSLATION NOTE: Swift's protocol-extension default becomes an interface default,
    /// covering the two Tindeq clients (live and mock); multi-device clients override it.
    val kind: GaugeKind get() = GaugeKind.progressor

    /// Scan for, and connect to, the first gauge found. Idempotent.
    fun connect()

    fun disconnect()

    /// **`startWeightMeasurement` is REFUSED here, on every client**: starts carry a cause
    /// through `startStreaming(cause:)`, so a hardware breadcrumb is never a guess.
    fun send(command: ProgressorCommand)

    fun startStreaming(cause: StreamStartCause)

    fun stopStreaming() {
        send(ProgressorCommand.stopWeightMeasurement)
    }

    fun tare() {
        send(ProgressorCommand.tare)
    }

    fun readBattery() {
        send(ProgressorCommand.getBatteryVoltage)
    }

    /// Sleep the gauge and end the connection without letting an automatic reconnect race
    /// the power-down. Explicit power-down only; normal completion keeps the gauge
    /// available for the second daily session.
    fun sleepDevice() {
        send(ProgressorCommand.enterSleep)
    }
}

/** Notification boundaries are presentation/diagnostic metadata, never engine timing. */
sealed interface PacketBoundary {
    data class Began(val receivedAt: Double) : PacketBoundary
    data object Ended : PacketBoundary
}

internal inline fun ProgressorClient.withPacket(receivedAt: Double, action: () -> Unit) {
    onPacketBoundary?.invoke(PacketBoundary.Began(receivedAt))
    try { action() } finally { onPacketBoundary?.invoke(PacketBoundary.Ended) }
}
