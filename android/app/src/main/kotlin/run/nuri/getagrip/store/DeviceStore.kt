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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import run.nuri.getagrip.ble.BroadcastGaugeClient
import run.nuri.getagrip.ble.FrezCoefficientResolver
import run.nuri.getagrip.ble.GattGaugeClient
import run.nuri.getagrip.ble.GaugeCalibrationFailure
import run.nuri.getagrip.ble.GaugeCalibrationResolver
import run.nuri.getagrip.ble.GaugeCalibrationStatus
import run.nuri.getagrip.ble.HostClock
import run.nuri.getagrip.ble.LiveProgressorClient
import run.nuri.getagrip.ble.MockForceProfile
import run.nuri.getagrip.ble.MockProgressorClient
import run.nuri.getagrip.ble.ProgressorClient
import run.nuri.getagrip.ble.PacketBoundary
import run.nuri.getagrip.ble.ProgressorClientDiagnostic
import run.nuri.getagrip.ble.ProgressorConnectionState
import run.nuri.getagrip.ble.ScanStartBudget
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
/// fact a JVM test can assert.
///
/// TRANSLATION NOTE: iOS tests construct the real clients (the central is deferred to
/// `connect()`). Android clients need a `Context` and `BluetoothManager`, so the decision
/// is named here and `makeClient` is the one place it becomes an object.
enum class GaugeClientShape {
    /// Its own battle-tested client: serialized queries, the tare-integrity latch and the
    /// peripheral quarantine are Tindeq-specific and stay there.
    progressor,

    /// The generic connected client, driven entirely by `GaugeGattProfile`.
    gatt,

    /// A broadcast-only scale: there is nothing to connect to, so the scan is the link.
    broadcast,
}

/// **The one place a kind becomes a client shape**, dispatching on CAPABILITIES rather than
/// the case name: a GATT profile gets the generic client, a broadcast-only scale the
/// scanner, the Progressor its own. Adding a device is a codec plus a registry row, never a
/// new branch here.
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
    /// ONE count of scan starts for every client this app builds: Android's quota is per
    /// app, so switching gauges must not forget what the phone has already counted.
    private val scanBudget = ScanStartBudget()

    override fun make(kind: GaugeKind): ProgressorClient =
        when (GaugeClientRouting.shape(kind)) {
            GaugeClientShape.gatt ->
                GattGaugeClient(context, scope, kind, kind.gatt!!, clock, calibration(kind), scanBudget)
            GaugeClientShape.broadcast -> BroadcastGaugeClient(context, scope, clock, scanBudget)
            GaugeClientShape.progressor -> LiveProgressorClient(context, scope, clock, scanBudget)
        }

    /// Only a gauge that needs a resolver gets one; every other kind constructs no
    /// networking — see `FrezCalibration.kt`.
    private fun calibration(kind: GaugeKind): GaugeCalibrationResolver? {
        if (!kind.capabilities.requiresRemoteCalibration) return null
        return FrezCoefficientResolver(
            preferences = context.applicationContext
                .getSharedPreferences(FrezCoefficientResolver.preferencesName, Context.MODE_PRIVATE),
        )
    }
}

/// **Asking for BLUETOOTH_SCAN / BLUETOOTH_CONNECT belongs to a Connect tap, never to
/// launch.** The store asks on `connect()`, where iOS constructs its `CBCentralManager`
/// (which raises the prompt there) for the same reason.
fun interface PermissionGate {
    /// Grants or refuses, called back on the main thread. Already-granted answers
    /// synchronously, so a Connect tap costs no frame.
    fun ensureBluetoothPermissions(onResult: (Boolean) -> Unit)
}

/// Everything the app knows about the gauge right now: link state, live force, the rolling
/// trace, battery/firmware. It owns the client and is the ONLY thing that talks to it — the
/// one place wire events become app state.
///
/// TRANSLATION NOTE: iOS's `@Observable @MainActor` class becomes a `@Stable` class holding
/// Compose snapshot state, provided through `LocalDeviceStore`. `@ObservationIgnored`
/// becomes a plain field, for the same reason: a field that changes 80 times a second must
/// not invalidate anything.
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
    /// The grace's frozen-process backstop — see `BackgroundGraceBackstop`.
    private val graceBackstop: BackgroundGraceBackstop = NoGraceBackstop,
) {

    /// The rolling window the force trace draws, on a PLAYBACK timeline built here at
    /// ingestion — not on raw device timestamps.
    ///
    /// The device's µs counter restarts on its own schedule (tare, re-sent start,
    /// reconnect). A view that anchored to it killed the graph on the first hardware
    /// session while the kg readout lived on, and the poisoned anchor had no way back. The
    /// store SEES those events, so it builds a clean monotone clock and the view just draws
    /// (now − t). **Never reintroduce view-held clock state.**
    data class TracePoint(
        val kg: Double,
        /// Seconds on `HostClock.wallSeconds`, strictly monotone.
        val t: Double,
    )

    // MARK: - Published state

    var state: ProgressorConnectionState
        get() = stateValue
        private set(value) {
            stateValue = value
            linkFlow.value = Link(value.isConnected, connectionEpoch)
        }
    private var stateValue: ProgressorConnectionState by mutableStateOf(ProgressorConnectionState.Idle)

    /// The link as a stream a SESSION can follow on its own scope — see
    /// `RunnerSession.watchConnection`.
    ///
    /// **A flow, not Compose state:** the runner used to learn of drops from a
    /// `LaunchedEffect`, and a stopped Activity runs no effects, so a session kept alive by
    /// the foreground service behind a locked screen never heard
    /// `ConnectionLost`/`ConnectionRestored` — the one job the service exists for. A
    /// `StateFlow` is written synchronously from the client callback and collected on the
    /// session's scope, which no screen can pause.
    ///
    /// The epoch rides along because a flow CONFLATES: a drop and reconnect between two
    /// collections would otherwise look like no change, and the engine would never break
    /// its timeline.
    data class Link(val isConnected: Boolean, val epoch: ULong)

    private val linkFlow = MutableStateFlow(Link(isConnected = false, epoch = 0uL))
    val link: StateFlow<Link> get() = linkFlow

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

    /// Where a remotely calibrated gauge (Frez Dyno) stands between "connected" and
    /// "produces force"; `NotRequired` for every other gauge. Lets screens say WHY a
    /// connected Dyno shows no force.
    var calibrationStatus: GaugeCalibrationStatus by mutableStateOf(GaugeCalibrationStatus.NotRequired)
        private set

    var isStreaming: Boolean by mutableStateOf(false)
        private set

    /// Whether the live reading has received a sample in the last second. This is separate
    /// from the BLE link state: a connected gauge can stop delivering data.
    var isSignalFresh: Boolean by mutableStateOf(false)
        private set

    /// In-memory, bounded evidence for telling a real link drop from a connected-but-stale
    /// trace. Surfaced in Settings › About.
    ///
    /// **Read through a revision, not republished per event.** A Bluetooth backlog records
    /// a `TraceFlush` per dropped sample (merged into one entry by the ring), and
    /// republishing per event recomposed Settings at sample rate. The revision moves only
    /// when an entry is ADDED; a read still returns the ring as it stands, merged count
    /// included.
    val diagnosticEntries: List<DiagnosticBreadcrumbEntry>
        get() { diagnosticRevision; return diagnosticRing.entries }

    /// Bumped when the ring gains an entry. Internal so a test can pin the change-guard.
    internal var diagnosticRevision by mutableIntStateOf(0)
        private set

    /// Latest reading, tare-relative, in kilograms.
    private var latestKg = 0.0
    var currentKg: Double
        get() { sampleRevision; return latestKg }
        private set(value) { if (latestKg != value) { latestKg = value; sampleStateChanged() } }

    /// Whether the load is heavy enough that a tare needs confirming. Coarse and
    /// change-guarded like `isReadingLive`: reading raw kilograms re-evaluated the whole
    /// Tare button at ~80 Hz for a value that matters only at `TarePolicy.shouldConfirm`'s
    /// one threshold.
    var isLoadedForTare: Boolean by mutableStateOf(false)
        private set

    /// Whether the reading is live enough to zero the gauge against — **observable, so the
    /// Tare button changes mode when it flips.**
    ///
    /// Separate from `isSignalFresh`: that one feeds diagnostics and tolerates a full
    /// second; this one tolerates `tareReadingMaxAge`, because a tare cannot be taken back.
    /// Republished by the 500 ms watchdog, so it lags by up to one tick, in the SAFE
    /// direction only.
    var isReadingLive: Boolean by mutableStateOf(false)
        private set

    /// Highest reading since the last `resetPeak()`.
    private var latestPeak = 0.0
    var peakKg: Double
        get() { sampleRevision; return latestPeak }
        private set(value) { if (latestPeak != value) { latestPeak = value; sampleStateChanged() } }

    // Appended per packet into plain storage; observers subscribe to the revision. Raw
    // callbacks still see every sample.
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

    /// **Gate behaviour on THESE flags, never on `gaugeKind`.** A rule keyed to a
    /// capability survives the next device; one keyed to a device name does not.
    val gaugeCapabilities: GaugeCapabilities
        get() = if (isMock) demoCapabilities else gaugeKind.capabilities

    // The demo has no physical connection and requests no Bluetooth permission, so it
    // cannot run a connectedDevice foreground service; it pauses on background like a timer
    // session. Service and lifecycle read this same capability.
    private val demoCapabilities = GaugeKind.progressor.capabilities.copy(sustainsBackgroundStreaming = false)

    /// Fulfilled by the Activity. Null in tests and until attached, where the store
    /// connects without asking.
    var permissionGate: PermissionGate? = null

    // MARK: - Non-observed streams

    /// Every sample, in order, for whoever is running a session.
    ///
    /// A callback, not observable state: Compose coalesces snapshot changes per frame, so a
    /// view watching `lastSample` would see a handful of the ~80 samples a second and
    /// under-count hang time by an order of magnitude.
    var onSample: ((ForceSample) -> Unit)? = null

    /// The same samples on the store's PLAYBACK time (see `playbackTime`, monotone across
    /// tares, counter resets and reconnects).
    ///
    /// A second callback because the needs differ: the runner accrues hang time from device
    /// deltas and must not get a slewed clock, while anything measuring over a WINDOW OF
    /// SECONDS (the max test) needs a timeline that cannot jump backwards.
    var onTracePoint: ((TracePoint) -> Unit)? = null

    // MARK: - Ignored state

    private var lastTraceMicros: UInt? = null
    private var freshnessJob: Job? = null
    private var freshnessGeneration: ULong = 0uL
    private var freshnessStartedAt: Double? = null
    private var lastSignalAt: Double? = null
    private val diagnosticRing = DiagnosticBreadcrumbRing()

    private val traceCapacity: Int
        get() = max(
            minimumTraceCapacity,
            (traceSeconds * gaugeCapabilities.nominalSampleRate).toInt(),
        )

    private var client: ProgressorClient = client

    init {
        wire()
    }

    /// The app path: choose the client from the launch mode and the stored kind.
    ///
    /// **Demo mode reports the Progressor whatever is stored.** The mock scripts a Tindeq
    /// (device µs clock, hardware tare, foreground-only on Android), so reporting the
    /// stored kind would promise capabilities the running client lacks. The stored choice
    /// returns when demo mode ends.
    constructor(
        useMock: Boolean,
        scope: CoroutineScope,
        kindStore: GaugeKindStore,
        clientFactory: GaugeClientFactory,
        clock: HostClock = SystemHostClock,
        mockFactory: (MockForceProfile) -> ProgressorClient = { MockProgressorClient(scope, it) },
        graceBackstop: BackgroundGraceBackstop = NoGraceBackstop,
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
        graceBackstop = graceBackstop,
    ) {
        gaugeKind = if (useMock) GaugeKind.progressor else kindStore.load()
    }

    // MARK: - Which gauge

    /// How long the live reading survives without a sample before the UI calls it stale.
    /// One second suits a CONNECTED stream (dozens of missing samples). Broadcast delivery
    /// is BURSTY — a real WH-C06 advertises in clumps with multi-second holes (the
    /// reference library tolerates 10 s) — and the one-second rule made the readout flap
    /// with the radio (2026-08-17). 3.5 s stays well under the client's 10 s disconnect.
    val signalSilenceTolerance: Double
        get() = if (gaugeCapabilities.isBroadcast) 3.5 else 1.0

    /// How old the newest reading may be and still count as "live" for the Tare button. Its
    /// displayed MODE (`isReadingLive`) and the tap's safety re-check
    /// (`TarePolicy.isSafeToTareNow` / `confirmationDecision`) must read this same number,
    /// or they disagree.
    ///
    /// `TarePolicy.liveReadingMaxAgeSeconds` (0.3 s) is a Tindeq number for an 80 Hz
    /// stream. On a broadcast gauge's clumped advertisements it flipped the button to Wake
    /// and back with the radio (Nuri, 2026-08-17), and Wake is a no-op there anyway.
    val tareReadingMaxAge: Double
        get() = if (gaugeCapabilities.isBroadcast) 3.5 else TarePolicy.liveReadingMaxAgeSeconds

    /// Switch gauges: disconnect, swap the client, persist the choice — and NOT connect.
    /// The permission ask belongs to a Connect tap.
    fun selectGaugeKind(kind: GaugeKind) {
        // `isMock` in the guard: choosing a gauge during demo mode must act even when the
        // kind matches.
        if (kind == gaugeKind && !isMock) return
        kindStore.save(kind)
        gaugeKind = kind
        // Choosing a real gauge leaves demo mode: the mock scripts a Tindeq and cannot
        // stand in.
        isMock = false
        adopt(clientFactory.make(kind))
    }

    /// Swap in the synthetic device (demo mode, or an emulator). Always compiled in — a
    /// debug-only mock leaves anyone without hardware, reviewers included, on a screen that
    /// never connects.
    ///
    /// Leaving demo mode returns to the SELECTED gauge, not always the Progressor.
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
                // The twin of CoreBluetooth's `.unauthorized`: the client never started, so
                // it cannot know.
                record(DiagnosticBreadcrumb.Connection(ProgressorConnectionState.Unauthorized))
                state = ProgressorConnectionState.Unauthorized
            }
        }
    }

    fun disconnect() {
        // An explicit stop is a decision; it must not resurrect itself on foreground.
        resumeScanOnForeground = false
        // Recorded here because `disconnect` sets `isStreaming` directly, bypassing
        // `stopStreaming`; otherwise the ring shows the link gone with the stream still
        // running.
        if (isStreaming) record(DiagnosticBreadcrumb.StreamStopped(StreamStopCause.disconnecting))
        cancelBackgroundGrace(leavingBackground = false)
        client.disconnect()
        publishStreaming(false)
    }

    fun tare() {
        if (!state.isConnected) return
        client.tare()
        // Re-send start whenever a stream should be running: on the first hardware session,
        // taring mid-stream killed the graph for good. Whether the firmware stops the
        // measurement or restarts its clock, re-sending is harmless and repairs the bad
        // case.
        //
        // **Not for a broadcast gauge.** There a restart is a scan bounce, and a tare
        // cannot cause the failure (nothing is written; the zero is app-side), so it would
        // only cut a gap into the readings at the moment of the zero.
        if (isStreaming && !gaugeCapabilities.isBroadcast) {
            startStreaming(StreamStartCause.tareRecovery)
        }
        resetPeak(preservingTrace = true)
    }

    fun startStreaming(cause: StreamStartCause) {
        if (!state.isConnected) return
        // The broadcast client reports whether it restarted or kept its scan; recording
        // every 500 ms no-op would drown those facts.
        if (!gaugeCapabilities.isBroadcast || cause != StreamStartCause.watchdog) {
            record(DiagnosticBreadcrumb.StreamStartRequested(cause))
        }
        publishStreaming(true)
        client.startStreaming(cause)
    }

    fun stopStreaming(cause: StreamStopCause) {
        record(DiagnosticBreadcrumb.StreamStopped(cause))
        publishStreaming(false)
        publishCurrentKg(0.0)
        client.stopStreaming()
    }

    fun readBattery() {
        client.readBattery()
    }

    /// Explicitly power down the gauge. Normal session completion leaves it awake for the
    /// second daily session; sleeping it requires a physical button press to wake.
    fun sleepDevice() {
        if (isStreaming) record(DiagnosticBreadcrumb.StreamStopped(StreamStopCause.sleeping))
        cancelBackgroundGrace(leavingBackground = false)
        client.sleepDevice()
        publishStreaming(false)
        publishCurrentKg(0.0)
    }

    fun recordScenePhase(phase: String) {
        record(DiagnosticBreadcrumb.ScenePhase(phase))
    }

    /// Tare keeps the trace and its restart-aware playback anchor; new
    /// measurements/sessions start with an empty graph by default.
    fun resetPeak(preservingTrace: Boolean = false) {
        peakKg = 0.0
        if (!preservingTrace) {
            traceStorage.clear()
            lastTraceMicros = null
        }
        sampleStateChanged()
    }

    /// **Throw the graph away when the app comes back to the foreground.**
    ///
    /// The Bluetooth stack hands over what it buffered during suspension on wake, and
    /// `playbackTime` replays it into the six-second window as one squashed flat line. None
    /// of it is drawable.
    ///
    /// The peak is KEPT: it is a fact about the session, and the axis is scaled from it.
    fun dropStaleTrace() {
        traceStorage.clear()
        sampleStateChanged()
        lastTraceMicros = null
    }

    // MARK: - The background grace period

    /// **On leaving the app, unless a session is streaming, the link is dropped — after a
    /// 45 s GRACE, never at once.** The rule and its reasons are `BackgroundGracePolicy`;
    /// this realises it. Called by `GetAGripApplication`'s `ProcessLifecycleOwner` observer
    /// when the PROCESS leaves the foreground, not when one Activity pauses.
    ///
    /// TRANSLATION NOTE: iOS holds the window with a `beginBackgroundTask` assertion whose
    /// expiration handler disconnects. Android's twin hazard is a FROZEN process, which
    /// keeps its link up while this timer stands still, so the window is armed twice: this
    /// coroutine and a `BackgroundGraceBackstop` alarm; whichever fires first disconnects.
    /// A streaming session (kept alive by `SessionForegroundService`) takes the `none`
    /// branch.
    fun beginBackgroundGrace() {
        isInBackground = true
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
                // Consumed by `cancelBackgroundGrace`. "Connecting" a broadcast gauge is
                // only scanning (no dialog, write or pairing), so resuming automatically is
                // safe and spares every app switch a Connect tap. Set AFTER `disconnect()`,
                // which clears it.
                resumeScanOnForeground = true
            }

            BackgroundGraceAction.scheduleDisconnect -> {
                // Re-entrant: two ON_STOPs must not stack timers or extend the open window.
                if (backgroundGraceJob != null) return
                record(DiagnosticBreadcrumb.BackgroundDisconnectScheduled)
                backgroundGraceJob = scope.launch {
                    delay(BackgroundGracePolicy.graceSeconds * 1_000L)
                    disconnectAfterGrace()
                }
                graceBackstop.arm(BackgroundGracePolicy.graceSeconds * 1_000L)
            }
        }
    }

    /// Came back inside the window: the link was never touched. Call off the pending
    /// disconnect and stand the broadcast scan back up.
    fun cancelBackgroundGrace(leavingBackground: Boolean = true) {
        if (leavingBackground) isInBackground = false
        if (resumeScanOnForeground) {
            resumeScanOnForeground = false
            connect()
        }
        val job = backgroundGraceJob ?: return
        backgroundGraceJob = null
        job.cancel()
        graceBackstop.cancel()
        record(DiagnosticBreadcrumb.BackgroundDisconnectCancelled)
    }

    /// The window ran out. Internal so a test drives it exactly as the timer does.
    ///
    /// **Re-checked, not assumed.** In 45 s a session may have started (a Live Update
    /// tapped from the lock screen) or the user disconnected by hand; dropping a streaming
    /// gauge would end a workout the grace was never about.
    internal fun disconnectAfterGrace() {
        backgroundGraceJob?.cancel()
        backgroundGraceJob = null
        graceBackstop.cancel()
        if (state.isConnected && !isStreaming) disconnect()
    }

    /// The backstop alarm arrived — possibly to a process frozen through the whole window,
    /// possibly racing a foreground return. On top of the grace's re-checks it asks: is the
    /// app STILL in the background? A late alarm must never drop the link under a screen in
    /// use.
    fun backgroundGraceBackstopFired() {
        if (!isInBackground) return
        disconnectAfterGrace()
    }

    private var isInBackground = false
    private var backgroundGraceJob: Job? = null

    /// Set when the background rule tears down a broadcast scan; consumed by the next
    /// foreground return. An explicit `disconnect()` clears it.
    private var resumeScanOnForeground = false

    // MARK: - Wiring

    /// Retire the current client and adopt another. Detaching the old client's callbacks is
    /// load-bearing: a Bluetooth callback already in flight would otherwise publish a dead
    /// client's state over the new one's.
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
            val arrived = next.isConnected && !state.isConnected
            if (arrived) {
                connectionEpoch += 1uL
                pipelineDiagnostics.reset()
            }
            record(DiagnosticBreadcrumb.Connection(next))
            state = next
            deviceName = client.deviceName
            // A link that comes up while the app is AWAY (a reconnect behind a locked
            // screen) is an idle gauge held open, and gets the same grace. A session
            // re-kicks its stream within moments, and the grace re-checks for that.
            if (arrived && isInBackground) beginBackgroundGrace()
            if (!next.isConnected) {
                publishStreaming(false)
                publishCurrentKg(0.0)
                publishSignalFresh(false)
                freshnessStartedAt = null
                lastSignalAt = null
                // A coefficient belongs to a link; the client re-resolves on the next one.
                calibrationStatus = GaugeCalibrationStatus.NotRequired
            }
        }
        client.onDiagnostic = { diagnostic ->
            when (diagnostic) {
                is ProgressorClientDiagnostic.BroadcastScan ->
                    record(DiagnosticBreadcrumb.BroadcastScan(diagnostic.event))
                ProgressorClientDiagnostic.RetiringPeripheral ->
                    record(DiagnosticBreadcrumb.RetiringPeripheral)
                ProgressorClientDiagnostic.QuarantineReleased ->
                    record(DiagnosticBreadcrumb.QuarantineReleased)
                is ProgressorClientDiagnostic.StreamStartDeferred ->
                    record(DiagnosticBreadcrumb.StreamStartDeferred(diagnostic.cause))
                is ProgressorClientDiagnostic.StreamStartWritten ->
                    record(DiagnosticBreadcrumb.StreamStartWritten(diagnostic.cause))
                is ProgressorClientDiagnostic.Calibration -> {
                    calibrationStatus = diagnostic.status
                    record(DiagnosticBreadcrumb.Calibration(calibrationPhase(diagnostic.status)))
                }
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
        calibrationStatus = GaugeCalibrationStatus.NotRequired
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

            // Decoded for completeness; the RFD tags are for a future max/RFD mode.
            else -> Unit
        }
    }

    private fun ingest(sample: ForceSample) {
        if (!isStreaming) return
        pipelineDiagnostics.sample()
        publishCurrentKg(sample.kg)
        if (isStreaming) {
            lastSignalAt = clock.wallSeconds()
            publishSignalFresh(true)
            // Immediately, not on the next watchdog tick: the Tare button must return to
            // taring as the numbers move.
            refreshReadingLiveness()
        }
        lastSample = sample
        peakKg = max(peakKg, sample.kg)
        onSample?.invoke(sample)

        // **A BACKLOG DELIVERED IN ONE BURST RESTARTS THE GRAPH.**
        //
        // Notifications queued during suspension arrive together on wake, each stamped 12.5
        // ms after the last, so a few hundred walk the playback clock seconds into the
        // FUTURE — which a timeline drawn as (now − t) cannot represent.
        //
        // Being ahead is proof the data did not happen now, not drift to converge: drop the
        // buffer and start a fresh run at wall time. During a long flush this keeps firing,
        // correctly, until samples arrive at real-time pace. It needs no lifecycle hook: a
        // stalled main thread or a buffering radio gets the same repair.
        // 0.5 s, not 0.25: a normal BLE batch is ~0.1 s of device time and two arriving
        // together is ordinary jitter; a real backlog is seconds.
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
    /// Deltas come from the device (wrap-safe), so batching never bunches points; a delta
    /// outside (0, trust window] means the counter restarted and one sample period is the
    /// honest guess. Slewed toward wall time by ≤0.5 ms per sample (tracks drift
    /// invisibly), snapping after a 250 ms error (a real stall).
    ///
    /// **"One sample period" is THIS gauge's**, not the Tindeq's 12.5 ms. Readings in one
    /// notification share a stamp (see `GattGaugeClient.ingest`), so interior ones arrive
    /// with a zero delta and take the fallback; with the wrong period, N readings could
    /// push the clock AHEAD of wall time, and the trace would be dropped every few seconds.
    private fun playbackTime(sample: ForceSample): Double {
        val wallNow = clock.wallSeconds()
        val previous = lastTraceMicros
        val lastT = traceStorage.lastOrNull()?.t
        lastTraceMicros = sample.deviceMicros
        if (previous == null || lastT == null) return wallNow

        val deltaMicros = sample.deviceMicros - previous // wrap-safe: Kotlin UInt wraps
        // The (0, 1 s] trust window is a DEVICE-clock rule. A SYNTHETIC stamp is
        // host-monotonic and cannot be nonsense, and a broadcast scale's multi-second holes
        // are ordinary delivery, so it is trusted up to the 10 s the silence watchdog calls
        // a disconnect. Compressing those gaps to one period helped collapse the sparse
        // WH-C06 trace on hardware (2026-08-17).
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
        // Running AHEAD is handled by the caller, which drops the buffer (see `ingest`).
        // Crawling toward wall time instead was worse: it converged over tens of seconds
        // with the trace squashed into a few pixels.
        return candidate + min(max(error, -0.0005), 0.0005)
    }

    // MARK: - Signal freshness

    /// How old the newest sample is, or null when none has arrived on this stream. The
    /// EXACT answer, read at action time before an irreversible tare. Not observable (it
    /// would invalidate at 80 Hz); the UI renders `isReadingLive`.
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
        if (!streaming && isInBackground) beginBackgroundGrace()
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

    /// The cue player's door into the ring, so one export covers link, stream AND sound.
    /// Main thread only.
    fun recordAudio(event: String) = record(DiagnosticBreadcrumb.Audio(event))

    private fun record(event: DiagnosticBreadcrumb) {
        if (diagnosticRing.append(event, clock.wallSeconds())) diagnosticRevision++
    }

    companion object {
        /// ~6 s of history: the shape of a pull without an unreadable smear. Sized in
        /// seconds, not points: 480 points was six seconds at the Progressor's 80 Hz but
        /// under two at the Dyno's 250 Hz.
        private const val traceSeconds: Double = 6.0
        private const val minimumTraceCapacity = 480

        /// Fixed English for the breadcrumb ring — a phase, never the serial, because the
        /// ring travels in support mail.
        private fun calibrationPhase(status: GaugeCalibrationStatus): String = when (status) {
            GaugeCalibrationStatus.NotRequired -> "not required"
            GaugeCalibrationStatus.WaitingForSerial -> "waiting for serial"
            is GaugeCalibrationStatus.Resolving -> "looking up coefficient"
            is GaugeCalibrationStatus.Ready ->
                if (status.calibration.cached) "ready (cached)" else "ready (fetched)"
            is GaugeCalibrationStatus.Failed -> when (status.failure) {
                GaugeCalibrationFailure.MissingSerial -> "failed (no serial)"
                GaugeCalibrationFailure.NoAccessKey -> "failed (no access key)"
                GaugeCalibrationFailure.InvalidRequest -> "failed (400)"
                GaugeCalibrationFailure.InvalidAccessKey -> "failed (401)"
                GaugeCalibrationFailure.DeviceLimitReached -> "failed (403)"
                GaugeCalibrationFailure.DeviceNotFound -> "failed (404)"
                GaugeCalibrationFailure.OwnershipReview -> "failed (409)"
                GaugeCalibrationFailure.CalibrationUnavailable -> "failed (422)"
                GaugeCalibrationFailure.RateLimited -> "failed (429)"
                GaugeCalibrationFailure.BadResponse -> "failed (bad response)"
                is GaugeCalibrationFailure.Network -> "failed (network)"
            }
        }

        /// The twin of iOS's `-mockDevice` launch argument:
        /// `adb shell am start … --ez mockDevice true`, read once from the launch Intent.
        fun mockRequestedAtLaunch(intent: Intent?): Boolean =
            intent?.getBooleanExtra("mockDevice", false) == true
    }
}
