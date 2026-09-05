// SPDX-License-Identifier: MPL-2.0
// Original contributions Copyright 2026 Nuri Bruner.

package run.nuri.getagrip.store

import android.content.Context
import android.content.Intent
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import run.nuri.getagrip.ble.BroadcastGaugeClient
import run.nuri.getagrip.ble.GattGaugeClient
import run.nuri.getagrip.ble.HostClock
import run.nuri.getagrip.ble.LiveProgressorClient
import run.nuri.getagrip.ble.MockForceProfile
import run.nuri.getagrip.ble.MockProgressorClient
import run.nuri.getagrip.ble.ProgressorClient
import run.nuri.getagrip.ble.PacketBoundary
import run.nuri.getagrip.ble.ProgressorClientDiagnostic
import run.nuri.getagrip.ble.ProgressorConnectionState
import run.nuri.getagrip.ble.StreamStartCause
import run.nuri.getagrip.ble.StreamStopCause
import run.nuri.getagrip.ble.SystemHostClock
import run.nuri.getagrip.engine.ForceSample
import run.nuri.getagrip.engine.GaugeCapabilities
import run.nuri.getagrip.engine.GaugeKind
import run.nuri.getagrip.engine.ProgressorCodec
import run.nuri.getagrip.engine.ProgressorEvent
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/// Which client shape a kind needs. Split out from `makeClient` so the ROUTING is a pure
/// fact that a JVM test can assert.
///
/// TRANSLATION NOTE: on iOS the test constructs the real clients and checks their types,
/// which is safe there because creating the central is deferred to `connect()`. On Android
/// every real client needs a `Context` and a `BluetoothManager`, so the routing decision
/// is named here and `makeClient` below is the one place it becomes an object.
enum class GaugeClientShape {
    /// Its own battle-tested client: serialized queries, the tare-integrity latch and the
    /// peripheral quarantine are Tindeq-specific and stay there.
    progressor,

    /// The generic connected client, driven entirely by `GaugeGattProfile`.
    gatt,

    /// A broadcast-only scale: there is nothing to connect to, so the scan is the link.
    broadcast,
}

/// **The one place a kind becomes a client shape**, and it dispatches on CAPABILITIES
/// rather than on the case name: anything with a GATT profile gets the generic connected
/// client, a broadcast-only scale gets the scanner, and the Progressor keeps its own.
/// Adding a device is a codec plus a registry row — never a new branch here.
object GaugeClientRouting {
    fun shape(kind: GaugeKind): GaugeClientShape = when {
        kind.gatt != null -> GaugeClientShape.gatt
        kind.capabilities.isBroadcast -> GaugeClientShape.broadcast
        else -> GaugeClientShape.progressor
    }
}

/// Builds a client for a kind. The app hands `DeviceStore` an Android-backed one; a test
/// hands it a fake, which is what keeps the whole store JVM-testable.
fun interface GaugeClientFactory {
    fun make(kind: GaugeKind): ProgressorClient
}

class AndroidGaugeClientFactory(
    private val context: Context,
    private val scope: CoroutineScope,
    private val clock: HostClock = SystemHostClock,
) : GaugeClientFactory {
    override fun make(kind: GaugeKind): ProgressorClient =
        when (GaugeClientRouting.shape(kind)) {
            GaugeClientShape.gatt ->
                GattGaugeClient(context, scope, kind, kind.gatt!!, clock)
            GaugeClientShape.broadcast -> BroadcastGaugeClient(context, scope, clock)
            GaugeClientShape.progressor -> LiveProgressorClient(context, scope)
        }
}

/// **Asking for BLUETOOTH_SCAN / BLUETOOTH_CONNECT belongs to a Connect tap, never to
/// launch.** The Activity fulfils this; the store asks it on `connect()`, which is the
/// exact place iOS constructs its `CBCentralManager` for exactly the same reason — that
/// construction is what raises the system prompt there.
fun interface PermissionGate {
    /// Grants or refuses, called back on the main thread. Already-granted must answer
    /// synchronously, so a Connect tap on a permitted app costs no frame.
    fun ensureBluetoothPermissions(onResult: (Boolean) -> Unit)
}

/// Everything the app knows about the gauge right now: link state, the live force
/// reading, the rolling trace the graph draws, and battery/firmware.
///
/// It owns the client and is the ONLY thing that talks to it, so there is exactly one
/// place where wire events become app state.
///
/// TRANSLATION NOTE: iOS's `@Observable @MainActor final class` injected via
/// `.environment()` becomes a `@Stable` class holding Compose snapshot state, handed down
/// through `LocalDeviceStore`. `@ObservationIgnored` has a direct twin — a plain field
/// rather than a `mutableStateOf` — and the distinction matters for exactly the same
/// reason: a field that changes 80 times a second must not invalidate anything.
@Stable
class DeviceStore(
    client: ProgressorClient,
    isMock: Boolean = false,
    private val scope: CoroutineScope,
    private val clock: HostClock = SystemHostClock,
    private val kindStore: GaugeKindStore = InMemoryGaugeKindStore(),
    private val clientFactory: GaugeClientFactory = GaugeClientFactory { client },
    private val mockFactory: (MockForceProfile) -> ProgressorClient = { profile ->
        MockProgressorClient(scope, profile)
    },
) {

    /// The rolling window the force trace draws, on a PLAYBACK timeline built here at
    /// ingestion — not on raw device timestamps.
    ///
    /// The first hardware session killed the graph twice (connect-inside-the-runner, and
    /// every tare) while the kg readout stayed alive, because the view anchored itself to
    /// the device's µs counter and kept that anchor in view state. The counter restarts on
    /// the device's own schedule — tare, a re-sent start command, a reconnect — and a
    /// poisoned anchor had no path back. So the store, which SEES those events, rebuilds a
    /// clean monotone clock instead. The view just draws (now − t); there is nothing left
    /// in it to poison. **Never reintroduce view-held clock state.**
    data class TracePoint(
        val kg: Double,
        /// Seconds on `HostClock.wallSeconds`, strictly monotone.
        val t: Double,
    )

    // MARK: - Published state

    var state: ProgressorConnectionState by mutableStateOf(ProgressorConnectionState.Idle)
        private set

    /// Increments when a new connected link is published. A tare confirmation carries this
    /// epoch so an alert from an old link cannot authorize a write on a new one.
    var connectionEpoch: ULong by mutableStateOf(0uL)
        private set

    var deviceName: String? by mutableStateOf(null)
        private set

    var firmwareVersion: String? by mutableStateOf(null)
        private set

    var batteryFraction: Double? by mutableStateOf(null)
        private set

    var isStreaming: Boolean by mutableStateOf(false)
        private set

    /// Whether the live reading has received a sample in the last second. This is separate
    /// from the BLE link state: a connected gauge can stop delivering data.
    var isSignalFresh: Boolean by mutableStateOf(false)
        private set

    /// In-memory only, bounded evidence for distinguishing a real link drop from a
    /// connected-but-stale trace after a session. Surfaced in Settings › About.
    var diagnosticEntries: List<DiagnosticBreadcrumbEntry> by mutableStateOf(emptyList())
        private set

    /// Latest reading, tare-relative, in kilograms.
    private var latestKg = 0.0
    var currentKg: Double
        get() { sampleRevision; return latestKg }
        private set(value) { if (latestKg != value) { latestKg = value; sampleStateChanged() } }

    /// Whether the load is heavy enough that a tare needs confirming.
    ///
    /// Coarse and change-guarded, the same shape as `isReadingLive`. The Tare button used
    /// to read the raw kilogram figure directly, which re-evaluated its whole body — icon,
    /// label, enabled state, the accessibility hint, the alert — at sample rate (~80 Hz)
    /// for the entire session, to track a value that only ever matters at the ONE
    /// threshold `TarePolicy.shouldConfirm` cares about.
    var isLoadedForTare: Boolean by mutableStateOf(false)
        private set

    /// Whether the reading is live enough to zero the gauge against — **observable, so the
    /// Tare button actually changes mode when it flips.**
    ///
    /// Separate from `isSignalFresh` because they answer different questions on different
    /// clocks: that one drives an overlay and tolerates a full second of silence, this one
    /// tolerates `tareReadingMaxAge`, because a tare cannot be taken back for the rest of
    /// the session. Republished by the same 500 ms watchdog, so it lags the true boundary
    /// by up to one tick — deliberately in the SAFE direction only.
    var isReadingLive: Boolean by mutableStateOf(false)
        private set

    /// Highest reading since the last `resetPeak()`.
    private var latestPeak = 0.0
    var peakKg: Double
        get() { sampleRevision; return latestPeak }
        private set(value) { if (latestPeak != value) { latestPeak = value; sampleStateChanged() } }

    // Append to ordinary storage for the entire packet; observers subscribe to its
    // revision, not to each list operation. Raw callbacks still see every sample.
    private val traceStorage = ArrayList<TracePoint>()
    val trace: List<TracePoint> get() { sampleRevision; return traceStorage }

    /// The most recent sample with its device timestamp — the runner accrues work time
    /// from deltas of this, never from arrival time.
    private var latestSample: ForceSample? = null
    var lastSample: ForceSample?
        get() { sampleRevision; return latestSample }
        private set(value) { latestSample = value; sampleStateChanged() }

    var sampleRevision by mutableIntStateOf(0)
        private set
    private var packetOpen = false
    private var packetDirty = false
    val pipelineDiagnostics = PipelineDiagnostics()

    private fun sampleStateChanged() {
        if (packetOpen) packetDirty = true else sampleRevision++
    }

    private fun packetBoundary(boundary: PacketBoundary) {
        when (boundary) {
            is PacketBoundary.Began -> {
                packetOpen = true
                packetDirty = false
                pipelineDiagnostics.begin(boundary.receivedAt, clock.uptimeSeconds())
            }
            PacketBoundary.Ended -> {
                packetOpen = false
                if (packetDirty) sampleRevision++
                packetDirty = false
                pipelineDiagnostics.end(clock.uptimeSeconds())
            }
        }
    }


    /// True when driven by `MockProgressorClient` rather than real hardware. The UI must
    /// say so: a number that looks like a measurement but isn't is worse than no number.
    var isMock: Boolean by mutableStateOf(isMock)
        private set

    /// Which gauge the app is driving.
    var gaugeKind: GaugeKind by mutableStateOf(client.kind)
        private set

    /// **Gate behaviour on THESE flags, never on `gaugeKind` itself.** A rule keyed to a
    /// capability survives the next device; a rule keyed to a device name is a bug waiting
    /// in the one after.
    val gaugeCapabilities: GaugeCapabilities
        get() = if (isMock) demoCapabilities else gaugeKind.capabilities

    // The demo has no physical connection and requests no Bluetooth permission. Android
    // cannot run a connectedDevice foreground service for it; pause it on background just
    // like a timer session. All consumers use the same capability (service and lifecycle).
    private val demoCapabilities = GaugeKind.progressor.capabilities.copy(sustainsBackgroundStreaming = false)

    /// Fulfilled by the Activity. Nil in tests and until the Activity attaches, where the
    /// store connects without asking — a fake client needs no permission.
    var permissionGate: PermissionGate? = null

    // MARK: - Non-observed streams

    /// Every sample, in order, for whoever is running a session.
    ///
    /// Deliberately a callback rather than something a view observes: `lastSample` is a
    /// snapshot for rendering, and Compose coalesces snapshot changes per frame, so a view
    /// watching it would see a handful of the ~80 samples that arrive each second and
    /// under-count hang time by an order of magnitude. The runner needs all of them.
    var onSample: ((ForceSample) -> Unit)? = null

    /// The same samples, carrying the store's own PLAYBACK time instead of the device's
    /// raw counter — see `playbackTime`, which is built to be monotone across tares,
    /// counter resets and reconnects.
    ///
    /// A second callback rather than a wider `onSample`, because the two have genuinely
    /// different needs: the runner accrues hang time from device deltas and must not be
    /// handed a slewed clock, while anything measuring over a WINDOW OF SECONDS (the max
    /// test) needs a timeline that cannot jump backwards mid-measurement.
    var onTracePoint: ((TracePoint) -> Unit)? = null

    // MARK: - Ignored state

    private var lastTraceMicros: UInt? = null
    private var freshnessJob: Job? = null
    private var freshnessGeneration: ULong = 0uL
    private var freshnessStartedAt: Double? = null
    private var lastSignalAt: Double? = null
    private val diagnosticRing = DiagnosticBreadcrumbRing()
    private var client: ProgressorClient = client

    init {
        wire()
    }

    /// The app path: choose the client from the launch mode and the stored kind.
    ///
    /// **Demo mode reports the Progressor whatever is stored.** The mock scripts a Tindeq —
    /// device µs clock and hardware tare, but foreground-only on Android — so reporting the stored kind
    /// would hand the runner capabilities the client running does not have. The stored
    /// choice is untouched and comes back the moment demo mode ends.
    constructor(
        useMock: Boolean,
        scope: CoroutineScope,
        kindStore: GaugeKindStore,
        clientFactory: GaugeClientFactory,
        clock: HostClock = SystemHostClock,
        mockFactory: (MockForceProfile) -> ProgressorClient = { MockProgressorClient(scope, it) },
    ) : this(
        client = if (useMock) {
            mockFactory(MockForceProfile.clean)
        } else {
            clientFactory.make(kindStore.load())
        },
        isMock = useMock,
        scope = scope,
        clock = clock,
        kindStore = kindStore,
        clientFactory = clientFactory,
        mockFactory = mockFactory,
    ) {
        gaugeKind = if (useMock) GaugeKind.progressor else kindStore.load()
    }

    // MARK: - Which gauge

    /// How long the live reading survives without a sample before the UI calls it stale
    /// and zeroes the number. One second is right for a CONNECTED stream, where a missing
    /// second means dozens of missing samples; broadcast delivery is best-effort and
    /// BURSTY — a real WH-C06's advertisements arrive in clumps with multi-second holes
    /// (the reference library tolerates TEN seconds), so the one-second rule made the kg
    /// readout and the waiting overlay flap in time with the radio (2026-08-17). 3.5 s
    /// sits well under the client's own 10 s disconnect, so a scale that genuinely left
    /// still reads as gone.
    val signalSilenceTolerance: Double
        get() = if (gaugeCapabilities.isBroadcast) 3.5 else 1.0

    /// How old the newest reading may be and still count as "live" for the Tare button —
    /// its displayed MODE (`isReadingLive`) and the tap's own safety re-check
    /// (`TarePolicy.isSafeToTareNow` / `confirmationDecision`) must both read this same
    /// number, or the two can disagree, which IS the bug this exists to fix.
    ///
    /// `TarePolicy.liveReadingMaxAgeSeconds` (0.3 s) is a Tindeq number — right for an
    /// 80 Hz connected stream — and stays untouched. A broadcast gauge's advertisements
    /// arrive in clumps, so 0.3 s flipped the button to Wake and back "oscillating back
    /// and forth" in time with the radio (Nuri, 2026-08-17), and Wake is a no-op there
    /// anyway since the scan never stops.
    val tareReadingMaxAge: Double
        get() = if (gaugeCapabilities.isBroadcast) 3.5 else TarePolicy.liveReadingMaxAgeSeconds

    /// Switch gauges. Disconnects first, swaps the client, persists the choice — and
    /// deliberately does NOT connect: asking for the Bluetooth permissions is what a
    /// Connect tap is for, and the house rule is that the ask arrives with a tap behind it.
    fun selectGaugeKind(kind: GaugeKind) {
        // `isMock` is in the guard because choosing a gauge while the demo device is
        // running has to do something even when the kind already matches.
        if (kind == gaugeKind && !isMock) return
        kindStore.save(kind)
        gaugeKind = kind
        // Choosing a real gauge leaves demo mode: the mock scripts a Tindeq and cannot
        // stand in for the device just chosen.
        isMock = false
        adopt(clientFactory.make(kind))
    }

    /// Swap in the synthetic device (demo mode, or anything running on an emulator).
    /// Always compiled in — a debug-only mock leaves anyone without hardware, reviewers
    /// included, stuck on a screen that never connects.
    ///
    /// Leaving demo mode returns to whatever gauge is SELECTED, not always the Progressor:
    /// someone who chose a crane scale and then looked at the demo must land back on it.
    fun useMockDevice(mock: Boolean, profile: MockForceProfile = MockForceProfile.clean) {
        isMock = mock
        gaugeKind = if (mock) GaugeKind.progressor else kindStore.load()
        adopt(if (mock) mockFactory(profile) else clientFactory.make(gaugeKind))
        connect()
    }

    // MARK: - Commands

    fun connect() {
        val gate = permissionGate
        // Demo mode touches no radio, so it must never raise a permission prompt.
        if (gate == null || isMock) {
            client.connect()
            return
        }
        gate.ensureBluetoothPermissions { granted ->
            if (granted) {
                client.connect()
            } else {
                // The twin of CoreBluetooth publishing `.unauthorized`: the client has no
                // way to know, because it was never allowed to start.
                record(DiagnosticBreadcrumb.Connection(ProgressorConnectionState.Unauthorized))
                state = ProgressorConnectionState.Unauthorized
            }
        }
    }

    fun disconnect() {
        // An explicit stop is a decision; it must not resurrect itself on foreground.
        resumeScanOnForeground = false
        // Recorded here too: `disconnect` sets `isStreaming` directly rather than going
        // through `stopStreaming`, so without this the ring would show a link going away
        // with the stream apparently still running.
        if (isStreaming) record(DiagnosticBreadcrumb.StreamStopped(StreamStopCause.disconnecting))
        cancelBackgroundGrace()
        client.disconnect()
        publishStreaming(false)
    }

    fun tare() {
        client.tare()
        // Re-issue the start command whenever a stream should be running. On the first
        // hardware session, taring mid-stream killed the graph for good — whether the
        // firmware stops the measurement or restarts its clock, re-sending start is
        // harmless in every case and restores it in the bad one.
        //
        // **Not for a broadcast gauge.** There "restart the stream" is a scan bounce, and
        // the failure it repairs cannot be caused by a tare: nothing was written to the
        // scale, its advertisements never stopped, and the zero is app-side arithmetic.
        // All it would buy is a visible gap in the readings at the moment the user asked
        // for a clean zero.
        if (isStreaming && !gaugeCapabilities.isBroadcast) {
            startStreaming(StreamStartCause.tareRecovery)
        }
        resetPeak()
    }

    fun startStreaming(cause: StreamStartCause) {
        if (!state.isConnected) return
        record(DiagnosticBreadcrumb.StreamStartRequested(cause))
        client.startStreaming(cause)
        publishStreaming(true)
    }

    fun stopStreaming(cause: StreamStopCause) {
        record(DiagnosticBreadcrumb.StreamStopped(cause))
        client.stopStreaming()
        publishStreaming(false)
        publishCurrentKg(0.0)
    }

    fun readBattery() {
        client.readBattery()
    }

    /// Explicitly power down the gauge. Normal session completion leaves it awake for the
    /// second daily session; sleeping it requires a physical button press to wake.
    fun sleepDevice() {
        if (isStreaming) record(DiagnosticBreadcrumb.StreamStopped(StreamStopCause.sleeping))
        cancelBackgroundGrace()
        client.sleepDevice()
        publishStreaming(false)
        publishCurrentKg(0.0)
    }

    fun recordScenePhase(phase: String) {
        record(DiagnosticBreadcrumb.ScenePhase(phase))
    }

    fun resetPeak() {
        peakKg = 0.0
        traceStorage.clear()
        sampleStateChanged()
        lastTraceMicros = null
    }

    /// **Throw the graph away when the app comes back to the foreground.**
    ///
    /// While suspended the app receives nothing, and the Bluetooth stack hands over
    /// whatever it buffered the moment it wakes. `playbackTime` snaps the first of those
    /// to wall time and then walks forward by their device deltas — so a burst
    /// representing half a minute of real hanging gets replayed into a six-second window
    /// as one squashed, flat line pinned to the right edge. None of it is drawable.
    ///
    /// The peak is deliberately KEPT — it is a fact about the session, not about the
    /// graph, and the axis is scaled from it.
    fun dropStaleTrace() {
        traceStorage.clear()
        sampleStateChanged()
        lastTraceMicros = null
    }

    // MARK: - The background grace period

    /// **On leaving the app, unless a session is streaming, the link is dropped — after a
    /// 45 s GRACE, never at once.**
    ///
    /// Firing immediately could not tell a two-second voice-assistant call from a phone put
    /// in a bag, and charged both a 5–6 s reconnect; that churn is what Nuri reported as
    /// "weird Bluetooth drops". The rule itself is `BackgroundGracePolicy`; this is its
    /// realisation, and `GetAGripApplication`'s `ProcessLifecycleOwner` observer is what
    /// calls it — the PROCESS leaving the foreground, not one Activity pausing.
    ///
    /// TRANSLATION NOTE (from iOS): there the window is held open by a
    /// `beginBackgroundTask` assertion whose EXPIRATION HANDLER does the disconnect,
    /// because a suspended iOS process keeps its CoreBluetooth link alive and would
    /// otherwise leave the gauge awake until flat — and a DENIED assertion disconnects at
    /// once for exactly the same reason. **Android has no assertion to be denied, and it
    /// fails the other way: a process the OS reclaims takes its GATT link with it.** So the
    /// worst case here is a grace that is CUT SHORT, never a gauge left burning — which is
    /// why this is a plain coroutine on the store's scope with no assertion machinery
    /// around it. (A session that must genuinely survive backgrounding runs a
    /// `connectedDevice` foreground service instead — see `SessionForegroundService` — and
    /// that session is streaming, so it takes the `none` branch below.)
    fun beginBackgroundGrace() {
        when (
            BackgroundGracePolicy.onLeavingForeground(
                isConnected = state.isConnected,
                isBusy = state.isBusy,
                isStreaming = isStreaming,
                sustainsBackgroundStreaming = gaugeCapabilities.sustainsBackgroundStreaming,
            )
        ) {
            BackgroundGraceAction.none -> Unit

            BackgroundGraceAction.disconnectNow -> {
                disconnect()
                // Consumed by `cancelBackgroundGrace` on the way back. "Connecting" a
                // broadcast gauge is only scanning — no dialog, no write, no pairing — so
                // resuming it automatically is safe, and NOT resuming would charge every app
                // switch a manual Connect tap. Set AFTER `disconnect()`, which clears it as
                // an explicit stop.
                resumeScanOnForeground = true
            }

            BackgroundGraceAction.scheduleDisconnect -> {
                // Re-entrant by contract: two ON_STOPs in a row must not stack two timers,
                // and the second would extend a window the first already opened.
                if (backgroundGraceJob != null) return
                record(DiagnosticBreadcrumb.BackgroundDisconnectScheduled)
                backgroundGraceJob = scope.launch {
                    delay(BackgroundGracePolicy.graceSeconds * 1_000L)
                    disconnectAfterGrace()
                }
            }
        }
    }

    /// Came back inside the window: the link was never touched, so there is nothing to
    /// restore — only the pending disconnect to call off, and the broadcast scan to stand
    /// back up.
    fun cancelBackgroundGrace() {
        if (resumeScanOnForeground) {
            resumeScanOnForeground = false
            connect()
        }
        val job = backgroundGraceJob ?: return
        backgroundGraceJob = null
        job.cancel()
        record(DiagnosticBreadcrumb.BackgroundDisconnectCancelled)
    }

    /// The window ran out. Internal rather than private so a test can drive it exactly as
    /// the timer does — without that seam the grace tests could pass while this never
    /// disconnected at all.
    ///
    /// **Re-checked, not assumed.** Forty-five seconds is long enough for a session to have
    /// started (a Live Update tapped from the lock screen) or for the user to have
    /// disconnected by hand, and disconnecting a streaming gauge would end a workout the
    /// grace was never about.
    internal fun disconnectAfterGrace() {
        backgroundGraceJob?.cancel()
        backgroundGraceJob = null
        if (state.isConnected && !isStreaming) disconnect()
    }

    private var backgroundGraceJob: Job? = null

    /// Set when the background rule tears down a broadcast scan; consumed by the next
    /// foreground return. Lives only across a background→foreground span, where no UI is
    /// reachable — an explicit `disconnect()` clears it.
    private var resumeScanOnForeground = false

    // MARK: - Wiring

    /// Retire the current client and adopt another.
    ///
    /// Detaching the old client's callbacks is load-bearing: `disconnect()` cancels its
    /// own work, but a Bluetooth callback already in flight would otherwise land on this
    /// store after the swap and publish a dead client's state over the new one's.
    private fun adopt(next: ProgressorClient) {
        client.disconnect()
        client.onEvent = null
        client.onPacketBoundary = null
        packetOpen = false
        packetDirty = false
        pipelineDiagnostics.reset()
        client.onStateChange = null
        client.onDiagnostic = null
        client = next
        resetState()
        wire()
    }

    private fun wire() {
        client.onStateChange = { next ->
            if (next.isConnected && !state.isConnected) {
                connectionEpoch += 1uL
                pipelineDiagnostics.reset()
            }
            record(DiagnosticBreadcrumb.Connection(next))
            state = next
            deviceName = client.deviceName
            if (!next.isConnected) {
                publishStreaming(false)
                publishCurrentKg(0.0)
                publishSignalFresh(false)
                freshnessStartedAt = null
                lastSignalAt = null
            }
        }
        client.onDiagnostic = { diagnostic ->
            when (diagnostic) {
                ProgressorClientDiagnostic.RetiringPeripheral ->
                    record(DiagnosticBreadcrumb.RetiringPeripheral)
                ProgressorClientDiagnostic.QuarantineReleased ->
                    record(DiagnosticBreadcrumb.QuarantineReleased)
                is ProgressorClientDiagnostic.StreamStartDeferred ->
                    record(DiagnosticBreadcrumb.StreamStartDeferred(diagnostic.cause))
                is ProgressorClientDiagnostic.StreamStartWritten ->
                    record(DiagnosticBreadcrumb.StreamStartWritten(diagnostic.cause))
            }
        }
        client.onEvent = { event -> handle(event) }
        client.onPacketBoundary = { boundary -> packetBoundary(boundary) }
    }

    private fun resetState() {
        state = ProgressorConnectionState.Idle
        deviceName = null
        firmwareVersion = null
        batteryFraction = null
        publishStreaming(false)
        publishSignalFresh(false)
        isReadingLive = false
        freshnessStartedAt = null
        lastSignalAt = null
        publishCurrentKg(0.0)
        lastSample = null
        resetPeak()
    }

    private fun handle(event: ProgressorEvent) {
        when (event) {
            is ProgressorEvent.Sample -> ingest(event.sample)

            is ProgressorEvent.Battery ->
                batteryFraction = ProgressorCodec.batteryFraction(event.millivolts)

            is ProgressorEvent.BatteryFraction -> batteryFraction = event.fraction

            is ProgressorEvent.AppVersion -> firmwareVersion = event.text

            ProgressorEvent.LowPowerWarning ->
                batteryFraction = min(batteryFraction ?: 0.1, 0.1)

            // Decoded for completeness; nothing in the timed-hang flow consumes them yet.
            // The RFD tags are what a future max/RFD mode will read.
            else -> Unit
        }
    }

    private fun ingest(sample: ForceSample) {
        pipelineDiagnostics.sample()
        publishCurrentKg(sample.kg)
        if (isStreaming) {
            lastSignalAt = clock.wallSeconds()
            publishSignalFresh(true)
            // Immediately, not on the next watchdog tick: a stream coming back must return
            // the Tare button to taring in the same frame the numbers move.
            refreshReadingLiveness()
        }
        lastSample = sample
        peakKg = max(peakKg, sample.kg)
        onSample?.invoke(sample)

        // **A BACKLOG DELIVERED IN ONE BURST RESTARTS THE GRAPH.**
        //
        // The Bluetooth stack queues notifications while the app is suspended and hands
        // the lot over on wake. Each carries a device timestamp 12.5 ms after the last, so
        // ingesting a few hundred of them in one frame walks the playback clock seconds
        // into the FUTURE — and a clock ahead of real time is the one thing this timeline
        // cannot represent, because the view draws (now − t).
        //
        // Being ahead is therefore not drift to converge; it is proof that what just
        // arrived did not happen now. So the buffer is dropped and the next sample starts
        // a fresh run at wall time. During a long flush this simply keeps firing, which is
        // correct: nothing is drawn until samples are arriving at real-time pace again.
        //
        // Self-healing, and it needs no lifecycle hook: a stalled main thread or a radio
        // that buffers for its own reasons is the same fault and gets the same repair.
        // 0.5 s, not 0.25: a normal BLE batch is ~8 samples (0.1 s of device time) and two
        // arriving together is ordinary jitter. A real backlog is seconds.
        val lastT = traceStorage.lastOrNull()?.t
        if (lastT != null && lastT > clock.wallSeconds() + 0.5) {
            record(DiagnosticBreadcrumb.TraceFlush(1))
            traceStorage.clear()
            sampleStateChanged()
            lastTraceMicros = null
        }

        val point = TracePoint(kg = sample.kg, t = playbackTime(sample))
        onTracePoint?.invoke(point)
        traceStorage.add(point)
        sampleStateChanged()
        while (traceStorage.size > traceCapacity) traceStorage.removeAt(0)
    }

    /// The playback clock: device-time deltas on a wall-time footing.
    ///
    /// Delta comes from the device (wrap-safe), so batching never bunches points; a delta
    /// outside (0, trust window] means the counter restarted or the timeline broke, and
    /// one sample period is the honest guess. The result is slewed toward wall time by at
    /// most 0.5 ms per sample — enough to track clock drift, too little to see — and snaps
    /// after a 250 ms error (a real stall, where slewing would take seconds to converge).
    ///
    /// **"One sample period" is THIS gauge's**, not the Tindeq's 12.5 ms. Every reading in
    /// one notification carries the same stamp (see `GattGaugeClient.ingest`), so the
    /// interior ones arrive here with a zero delta and take the fallback — and a packet of
    /// N readings would then advance the clock by N × 12.5 ms whatever the real interval
    /// was. On a device that declares its own sample count that can run the playback clock
    /// AHEAD of wall time, which this timeline cannot represent: the trace is dropped every
    /// time it happens, so the graph would clear itself every few seconds for the session's
    /// whole life.
    private fun playbackTime(sample: ForceSample): Double {
        val wallNow = clock.wallSeconds()
        val previous = lastTraceMicros
        val lastT = traceStorage.lastOrNull()?.t
        lastTraceMicros = sample.deviceMicros
        if (previous == null || lastT == null) return wallNow

        val deltaMicros = sample.deviceMicros - previous // wrap-safe: Kotlin UInt wraps
        // The (0, 1 s] trust window is a DEVICE-clock rule: past it the counter restarted
        // and one period is the honest guess. A SYNTHETIC stamp is host-monotonic elapsed
        // time and cannot be nonsense — and a broadcast scale's multi-second advertisement
        // holes are ordinary delivery, not a reset — so it is trusted up to the 10 s the
        // silence watchdog calls a disconnect. Compressing those real gaps to one period
        // was part of why the sparse WH-C06 trace kept collapsing on hardware (2026-08-17).
        val maxTrustedMicros: UInt =
            if (gaugeCapabilities.hasDeviceClock) 1_000_000u else 10_000_000u
        val delta = if (deltaMicros > 0u && deltaMicros <= maxTrustedMicros) {
            deltaMicros.toDouble() / 1_000_000
        } else {
            1.0 / max(1.0, gaugeCapabilities.nominalSampleRate)
        }
        val candidate = lastT + delta
        val error = wallNow - candidate
        // BEHIND wall time by a lot: we stalled, so jump forward and carry on.
        if (error > 0.25) return wallNow
        // Running AHEAD of wall time is handled by the caller, which drops the buffer and
        // starts again — see `ingest`. Crawling toward wall time instead was tried and was
        // worse: it converges over tens of seconds, and the whole trace sits squashed into
        // a few pixels the entire time.
        return candidate + min(max(error, -0.0005), 0.0005)
    }

    // MARK: - Signal freshness

    /// How old the newest sample is, or nil when none has arrived on this stream.
    ///
    /// The EXACT answer, read at action time — the last check before an irreversible tare.
    /// Not observable, because publishing it would invalidate a view 80 times a second;
    /// what the UI renders from is `isReadingLive`.
    fun secondsSinceLastSample(now: Double = clock.wallSeconds()): Double? =
        lastSignalAt?.let { now - it }

    private fun refreshReadingLiveness(now: Double = clock.wallSeconds()) {
        val age = secondsSinceLastSample(now)
        val live = isStreaming && age != null && age <= tareReadingMaxAge
        if (live != isReadingLive) isReadingLive = live
    }

    private fun publishStreaming(streaming: Boolean) {
        if (isStreaming == streaming) return
        isStreaming = streaming
        if (streaming) startFreshnessWatchdog() else stopFreshnessWatchdog()
    }

    private fun publishCurrentKg(kg: Double) {
        currentKg = kg
        val loaded = abs(kg) >= TarePolicy.confirmationThresholdKg
        if (loaded != isLoadedForTare) isLoadedForTare = loaded
    }

    private fun startFreshnessWatchdog() {
        freshnessGeneration += 1uL
        val generation = freshnessGeneration
        freshnessJob?.cancel()
        freshnessStartedAt = clock.wallSeconds()
        lastSignalAt = null
        publishSignalFresh(false)
        freshnessJob = scope.launch {
            while (isActive) {
                delay(500)
                if (freshnessGeneration != generation || !isStreaming) return@launch

                val now = clock.wallSeconds()
                refreshReadingLiveness(now)
                val signalAt = lastSignalAt
                if (signalAt != null) {
                    if (now - signalAt <= signalSilenceTolerance) {
                        publishSignalFresh(true)
                    } else {
                        publishCurrentKg(0.0)
                        publishSignalFresh(false)
                    }
                } else {
                    val startedAt = freshnessStartedAt
                    if (startedAt != null && now - startedAt > signalSilenceTolerance) {
                        publishCurrentKg(0.0)
                        publishSignalFresh(false)
                    }
                }
            }
        }
    }

    private fun stopFreshnessWatchdog() {
        freshnessGeneration += 1uL
        freshnessJob?.cancel()
        freshnessJob = null
        freshnessStartedAt = null
        lastSignalAt = null
        publishSignalFresh(false)
        isReadingLive = false
    }

    private fun publishSignalFresh(fresh: Boolean) {
        if (isSignalFresh == fresh) return
        isSignalFresh = fresh
        record(DiagnosticBreadcrumb.SignalFreshness(fresh))
    }

    private fun record(event: DiagnosticBreadcrumb) {
        diagnosticRing.append(event, clock.wallSeconds())
        diagnosticEntries = diagnosticRing.entries
    }

    companion object {
        /// ~6 seconds of history at 80 Hz — enough to see the shape of a pull without the
        /// trace becoming an unreadable smear.
        private const val traceCapacity = 480

        /// The twin of iOS's `-mockDevice` launch argument, which `./build.sh run` passes
        /// because a Simulator build can never reach real hardware. Here it is
        /// `adb shell am start … --ez mockDevice true`, read once from the launch Intent.
        fun mockRequestedAtLaunch(intent: Intent?): Boolean =
            intent?.getBooleanExtra("mockDevice", false) == true
    }
}
