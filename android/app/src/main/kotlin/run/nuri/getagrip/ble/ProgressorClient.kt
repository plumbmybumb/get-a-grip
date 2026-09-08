// SPDX-License-Identifier: MPL-2.0
// Original contributions Copyright 2026 Nuri Bruner.

package run.nuri.getagrip.ble

import run.nuri.getagrip.engine.GaugeKind
import run.nuri.getagrip.engine.L10n
import run.nuri.getagrip.engine.ProgressorCommand
import run.nuri.getagrip.engine.ProgressorEvent

/// What the app knows about the link to the gauge.
///
/// TRANSLATION NOTE: Swift's `enum` with one associated-value case becomes a sealed
/// interface, exactly as `ProgressorEvent` does in `:engine`. `Sendable, Equatable`
/// has no Kotlin counterpart — `data object` / `data class` give structural equality
/// for free, and thread confinement is a contract stated in `ProgressorClient` below
/// rather than something the compiler checks.
sealed interface ProgressorConnectionState {
    /// Nothing attempted yet — no scanner or GATT client exists, so no permission
    /// prompt has been shown.
    data object Idle : ProgressorConnectionState

    data object BluetoothOff : ProgressorConnectionState

    data object Unauthorized : ProgressorConnectionState

    /// No BLE hardware. TRANSLATION NOTE: on iOS this is what the Simulator always
    /// reports, which is why the mock client exists. An Android emulator has no
    /// working BLE either, so the same rule applies and `MockProgressorClient` is
    /// reached the same two ways.
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

/// Why a stream start was requested. The cause stays attached if the write has to wait
/// behind a tare, because a later write without its original reason makes the next
/// hardware trace impossible to interpret.
enum class StreamStartCause(val rawValue: String) {
    initial("initial"),
    reconnect("reconnect"),
    foreground("foreground"),
    watchdog("watchdog"),
    tareRecovery("tareRecovery"),
    manualMeasurement("manualMeasurement"),

    /// The human tapped Wake. Distinct from `watchdog` on purpose: attributing a manual
    /// recovery to the automatic one would make the breadcrumb report lie about who
    /// revived the stream, in the one log that exists to explain exactly that.
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

/// Why a stream stopped. The counterpart to `StreamStartCause`, and the reason the
/// breadcrumb ring can tell a genuine stall from the app doing exactly what it should.
/// **Deliberately has no default anywhere it is taken.** A defaulted cause is a cause
/// that is wrong at the call sites nobody revisited, and a diagnostic that quietly
/// mislabels an automatic stop as "stopped by hand" is worse than one that says nothing:
/// it sends whoever reads the log looking for a user who was never there.
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

/// Client-side lifecycle facts that are not themselves connection-state changes.
/// They let the store's in-memory breadcrumb ring include quarantine boundaries without
/// passing Bluetooth objects out of the client.
sealed interface ProgressorClientDiagnostic {
    /// A scanner lifecycle fact, never a device write or an advertisement payload.
    data class BroadcastScan(val event: String) : ProgressorClientDiagnostic
    data object RetiringPeripheral : ProgressorClientDiagnostic
    data object QuarantineReleased : ProgressorClientDiagnostic
    data class StreamStartDeferred(val cause: StreamStartCause) : ProgressorClientDiagnostic
    data class StreamStartWritten(val cause: StreamStartCause) : ProgressorClientDiagnostic
}

/// The seam between the app and the gauge.
///
/// **Isolation.** On iOS this protocol is `@MainActor`, so isolation propagates to every
/// conformer: the live client hands CoreBluetooth `queue: .main` and its delegate
/// callbacks genuinely arrive on the main thread, with no boundary to cross anywhere in
/// the stack. At ~10 notifications/sec of microsecond-cheap TLV decoding there is nothing
/// to gain from a second isolation domain and plenty to lose.
///
/// TRANSLATION NOTE: Kotlin has no actor isolation to propagate, so the same guarantee is
/// a CONTRACT every implementation keeps by construction — Android's BLE callbacks arrive
/// on a binder thread, so each client re-posts to `Dispatchers.Main.immediate` before it
/// touches its own state or calls any of the three callbacks below. `immediate` and not
/// `Dispatchers.Main`: a callback already on the main thread must run in the same turn,
/// or a `connect()` that answers synchronously (the mock, and a reattach to a live link)
/// would publish its state one frame after the caller looked.
///
/// Callback-style rather than Flow for the same reason as on iOS — the store wires
/// `onEvent` the way the sibling apps wire their session callbacks, and every hop stays
/// visible in one place. A flow per fact would fan the ~80 samples a second out through
/// as many collectors as there are readers.
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

    /// Which physical device family this client drives. Capability gating — tare
    /// behaviour, background streaming, timing source — reads `kind.capabilities`,
    /// never the concrete client type.
    ///
    /// TRANSLATION NOTE: Swift puts the default in a protocol extension; Kotlin's
    /// interface member default does the same job. It covers the two Tindeq clients
    /// (live and mock); multi-device clients override it.
    val kind: GaugeKind get() = GaugeKind.progressor

    /// Scan for, and connect to, the first gauge found. Idempotent.
    fun connect()

    fun disconnect()

    /// **`startWeightMeasurement` is REFUSED here, on every client.** Start writes carry
    /// a cause through the one public start funnel; silently accepting an uncaused start
    /// would make its later hardware breadcrumb a guess. Callers use
    /// `startStreaming(cause:)` instead.
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

    /// Ask the gauge to sleep and end this connection without allowing an automatic
    /// reconnect to race the power-down command. Explicit power-down only; normal
    /// session completion keeps the gauge available for the second daily session.
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
