// SPDX-License-Identifier: MPL-2.0
// Original contributions Copyright 2026 Nuri Bruner.

import Foundation
import Observation
#if !os(watchOS)
import UIKit
#endif

#if os(watchOS)
/// watchOS has no background-task assertion, so `beginBackgroundGrace` always takes
/// its DENIED branch — disconnect at once. Right for a watch: outside a workout session
/// nothing keeps the app alive for a grace, and inside one the stream is left alone.
struct BackgroundAssertionID: Equatable, Sendable {
    static let invalid = BackgroundAssertionID()
}
#else
typealias BackgroundAssertionID = UIBackgroundTaskIdentifier
#endif

/// Everything the app knows about the gauge right now: link state, the live force
/// reading, the rolling trace the graph draws, and battery/firmware.
///
/// It owns the client and is the ONLY thing that talks to it, so there is exactly one
/// place where wire events become app state.
@Observable
@MainActor
final class DeviceStore {
    private(set) var state: ProgressorConnectionState = .idle
    /// Increments when a new connected link is published. A tare confirmation carries
    /// this epoch so an alert from an old link cannot authorize a write on a new one.
    private(set) var connectionEpoch: UInt64 = 0
    private(set) var deviceName: String?
    private(set) var firmwareVersion: String?
    private(set) var batteryFraction: Double?
    /// Where a remotely calibrated gauge (Frez Dyno) stands between "connected" and
    /// "produces force". `.notRequired` for every other gauge. The screens read this to
    /// say WHY a connected Dyno shows no force instead of showing a silent zero.
    private(set) var calibrationStatus: GaugeCalibrationStatus = .notRequired
    private(set) var isStreaming = false {
        didSet {
            guard isStreaming != oldValue else { return }
            if isStreaming {
                startFreshnessWatchdog()
            } else {
                stopFreshnessWatchdog()
                if isInBackground { beginBackgroundGrace() }
            }
        }
    }
    /// Whether the live reading has received a sample in the last second. This is
    /// separate from the BLE link state: a connected gauge can stop delivering data.
    private(set) var isSignalFresh = false

    /// In-memory only, bounded evidence for distinguishing a real link drop from a
    /// connected-but-stale trace after a session. It is surfaced in Settings › About.
    private(set) var diagnosticEntries: [DiagnosticBreadcrumbEntry] = []

    /// Latest reading, tare-relative, in kilograms.
    @ObservationIgnored private var latestKg: Double = 0
    private(set) var currentKg: Double {
        get { _ = sampleRevision; return latestKg }
        set {
            guard latestKg != newValue else { return }
            latestKg = newValue
            sampleStateChanged()
            // Coarse and change-guarded: `TareButton` reading the raw figure re-evaluated
            // its whole body at ~80 Hz for a value that only matters at ONE threshold.
            // The tap's action closure still reads `currentKg` directly — a read inside a
            // closure creates no observation dependency.
            let loaded = abs(currentKg) >= TarePolicy.confirmationThresholdKg
            if loaded != isLoadedForTare { isLoadedForTare = loaded }
        }
    }
    /// Whether the load is heavy enough that a tare needs confirming — see `currentKg`.
    private(set) var isLoadedForTare = false
    /// Highest reading since the last `resetPeak()`.
    @ObservationIgnored private var latestPeak: Double = 0
    private(set) var peakKg: Double {
        get { _ = sampleRevision; return latestPeak }
        set { guard latestPeak != newValue else { return }; latestPeak = newValue; sampleStateChanged() }
    }
    /// The rolling window the force trace draws, on a PLAYBACK timeline built here at
    /// ingestion — not on raw device timestamps.
    ///
    /// The device's µs counter restarts on its own schedule — tare, a re-sent start, a
    /// reconnect — and a view-held anchor to it was poisoned with no path back (the
    /// graph died while the kg readout lived). So the store, which SEES those events,
    /// builds a monotone clock: each `t` advances by the wrap-safe device delta (one
    /// sample period when the delta is nonsense), slewed toward wall time and snapped
    /// after a genuine gap. The view just draws (now − t); nothing in it can be poisoned.
    struct TracePoint: Equatable {
        var kg: Double
        /// Seconds, `timeIntervalSinceReferenceDate` epoch, strictly monotone.
        var t: TimeInterval
        /// Wall time the reading reached the app — a whole packet shares one. The trace
        /// fades a packet's segment in over its first frames instead of popping it.
        var arrival: TimeInterval = 0
    }
    /// How far the playback clock may run from wall time, either way, before the
    /// timeline is declared broken: contiguous data further BEHIND than this snaps
    /// forward, and a buffer further AHEAD is dropped as a backlog. Tolerates delivery
    /// clumps of roughly twice this — past any radio stack's, far below a suspended
    /// app's backlog.
    static let lateDeliveryLimitSeconds: TimeInterval = 3.0
    /// **NOTHING IS STAMPED IN THE FUTURE: a packet's newest reading lands at its arrival.**
    ///
    /// Slewing each reading toward wall time itself centres a packet on its arrival, so
    /// its newest half is stamped ahead of now and the line lurches at each packet. Two
    /// buffered designs were rejected on the phone (one felt behind the pull, one looked
    /// wrong), so the clock targets wall time LESS half a packet: the newest reading is
    /// stamped at arrival, older ones sit behind it by their device deltas, and the trace
    /// draws all of it at once. Remaining smoothing is visual — see `ForceTraceView`.
    /// The running span of a packet, learned at packet starts, is what the alignment uses.
    @ObservationIgnored private var lastPacketArrival: TimeInterval?
    @ObservationIgnored private var lastPacketFirstT: TimeInterval?
    @ObservationIgnored private var packetSpanEstimate: TimeInterval = 0.1
    @ObservationIgnored private var packetsSeen = 0
    /// One line for the DEBUG diagnostics: how the clock is aligned.
    var playbackReport: String {
        String(format: "Trace clock: packet span %.0f ms · packets %d · newest reading stamped at arrival",
               packetSpanEstimate * 1000, packetsSeen)
    }
    @ObservationIgnored private var traceStorage: [TracePoint] = []
    var trace: [TracePoint] { _ = sampleRevision; return traceStorage }
    @ObservationIgnored private var lastTraceMicros: UInt32?
    /// The most recent sample with its device timestamp — the runner will accrue
    /// work time from deltas of this, never from arrival time.
    @ObservationIgnored private var latestSample: ForceSample?
    private(set) var lastSample: ForceSample? {
        get { _ = sampleRevision; return latestSample }
        set { latestSample = newValue; sampleStateChanged() }
    }

    // Raw state is current during every engine callback. Views observe one revision
    // when the notification finishes, never a delayed/conflated measurement stream.
    private(set) var sampleRevision = 0
    @ObservationIgnored private var packetOpen = false
    @ObservationIgnored private var packetDirty = false
    @ObservationIgnored let pipelineDiagnostics = PipelineDiagnostics()

    private func sampleStateChanged() {
        if packetOpen { packetDirty = true } else { sampleRevision &+= 1 }
    }

    private func packetBoundary(_ boundary: PacketBoundary) {
        switch boundary {
        case .began(let arrival):
            packetOpen = true
            packetDirty = false
            pipelineDiagnostics.begin(arrival: arrival, now: ProcessInfo.processInfo.systemUptime)
        case .ended:
            packetOpen = false
            if packetDirty { sampleRevision &+= 1 }
            packetDirty = false
            pipelineDiagnostics.end(now: ProcessInfo.processInfo.systemUptime)
        }
    }


    /// True when driven by `MockProgressorClient` rather than real hardware. The UI
    /// must say so.
    private(set) var isMock: Bool

    /// Which gauge the app is driving.
    ///
    /// Persisted in the App Group container rather than `SettingsStore`, because `init`
    /// chooses the client from it and the settings store is a sibling, not built first.
    private(set) var gaugeKind: GaugeKind

    /// **Gate behaviour on THESE flags, never on `gaugeKind` itself.** A rule keyed to a
    /// capability survives the next device; one keyed to a device name does not.
    var gaugeCapabilities: GaugeCapabilities { gaugeKind.capabilities }

    /// How long the live reading survives without a sample before the UI calls it
    /// stale and zeroes the number. One second suits a CONNECTED stream; broadcast
    /// delivery is BURSTY — a real WH-C06's advertisements arrive in clumps with
    /// multi-second holes, and one second made the readout flap with the radio
    /// (hardware, 2026-08-17). 3.5 s sits well under the client's 10 s disconnect.
    var signalSilenceTolerance: TimeInterval {
        gaugeCapabilities.isBroadcast ? 3.5 : 1.0
    }

    /// How old the newest reading may be and still count as "live" for the Tare button —
    /// its displayed MODE (`isReadingLive`) and the tap's safety re-check
    /// (`TarePolicy.isSafeToTareNow` / `.confirmationDecision`) must read this same
    /// number, or the two can disagree.
    ///
    /// `TarePolicy.liveReadingMaxAgeSeconds` (0.3 s) is a Tindeq number for an 80 Hz
    /// stream. On a broadcast gauge's clumpy advertisements it flipped the button to
    /// Wake and back with the radio (hardware, 2026-08-17), so broadcast uses 3.5 s,
    /// matching `signalSilenceTolerance`: on a scale that refreshes every few seconds
    /// that is the freshest truth on offer. The ≥1 kg confirmation still guards a
    /// loaded tare either way.
    var tareReadingMaxAge: TimeInterval {
        gaugeCapabilities.isBroadcast ? 3.5 : TarePolicy.liveReadingMaxAgeSeconds
    }

    /// Every sample, in order, for whoever is running a session.
    ///
    /// A callback, not observable state: SwiftUI coalesces observable changes, so a
    /// view watching `lastSample` would see a handful of the ~80 samples a second and
    /// under-count hang time by an order of magnitude.
    @ObservationIgnored var onSample: ((ForceSample) -> Void)?

    /// The same samples, carrying the store's own PLAYBACK time instead of the device's
    /// raw counter — see `playbackTime`, which is built to be monotone across tares,
    /// counter resets and reconnects.
    ///
    /// A second callback rather than a wider `onSample`: the runner accrues hang time
    /// from device deltas and must not get a slewed clock, while anything measuring over
    /// a WINDOW OF SECONDS (the max test) needs a timeline that cannot jump backwards.
    @ObservationIgnored var onTracePoint: ((TracePoint) -> Void)?

    @ObservationIgnored private var freshnessTask: Task<Void, Never>?
    @ObservationIgnored private var freshnessGeneration: UInt64 = 0
    @ObservationIgnored private var freshnessStartedAt: Date?
    @ObservationIgnored private var lastSignalAt: Date?

    /// How old the newest sample is, or nil when none has arrived on this stream.
    ///
    /// The EXACT answer, read at action time — the last check before an irreversible
    /// tare. `@ObservationIgnored` because publishing it would invalidate a view 80 times
    /// a second; what the UI renders from is `isReadingLive` below.
    func secondsSinceLastSample(now: Date = .now) -> TimeInterval? {
        lastSignalAt.map { now.timeIntervalSince($0) }
    }

    /// Whether the reading is live enough to zero the gauge against — **observable, so
    /// the Tare button actually changes mode when it flips.**
    ///
    /// Separate from `isSignalFresh` (diagnostic, tolerates a second of silence): this
    /// tolerates `tareReadingMaxAge`, because a tare cannot be taken back.
    ///
    /// Republished by the 500 ms watchdog, so it can lag by one tick — only in the SAFE
    /// direction: the tap re-checks `secondsSinceLastSample()` exactly and downgrades to
    /// a wake, never zeroing an unknown load.
    private(set) var isReadingLive = false

    private func refreshReadingLiveness(now: Date = .now) {
        let live = isStreaming
            && (secondsSinceLastSample(now: now).map { $0 <= tareReadingMaxAge } ?? false)
        if live != isReadingLive { isReadingLive = live }
    }
    @ObservationIgnored private var diagnosticRing = DiagnosticBreadcrumbRing()

    /// Sized in SECONDS from the gauge's own rate — a fixed 480 points is six seconds at
    /// 80 Hz but under two at the Dyno's 250 Hz. Two seconds more than the ~6 s window
    /// shows: the buffer's lead (up to ~1.6 s) is pending past the right edge, and the
    /// oldest point must lie OFF the left edge or the fill's start jitters as points age out.
    private static let traceSeconds: Double = 8
    private static let minimumTraceCapacity = 480
    private var traceCapacity: Int {
        max(Self.minimumTraceCapacity, Int(Self.traceSeconds * gaugeCapabilities.nominalSampleRate))
    }
    /// Half a second of the gauge's samples, floored at 40 — the overshoot the buffer is
    /// allowed before it is trimmed back.
    private var traceTrimSlack: Int {
        max(40, Int(0.5 * gaugeCapabilities.nominalSampleRate))
    }

    /// **Trimmed in CHUNKS, not a point at a time.** `removeFirst` shifts every element,
    /// so exact trimming moved the whole buffer 80–250 times a second. Overshooting by
    /// `slack` does that shift once per half-second; the overshoot is all OLD points
    /// past the window's left edge, so nothing drawn changes.
    nonisolated static func trimTrace<Point>(_ buffer: inout [Point], capacity: Int, slack: Int) {
        guard buffer.count > capacity + max(0, slack) else { return }
        buffer.removeFirst(buffer.count - capacity)
    }

    private var client: any ProgressorClient

    init(useMock: Bool = DeviceStore.mockRequestedAtLaunch) {
        isMock = useMock
        // **Demo mode reports the Progressor whatever is stored**: the mock scripts a
        // Tindeq, and the stored kind would claim capabilities it lacks. The stored
        // choice comes back when demo mode ends.
        let kind: GaugeKind = useMock ? .progressor : DeviceStore.persistedGaugeKind()
        gaugeKind = kind
        client = useMock ? MockProgressorClient(profile: DeviceStore.mockProfileRequestedAtLaunch)
                         : DeviceStore.makeClient(for: kind)
        wire()
    }

    /// Injection seam for lifecycle policy tests; the app path above still chooses the
    /// real or scripted client from the launch mode.
    ///
    /// The kind comes from the CLIENT rather than storage, so one test's persisted
    /// choice cannot change another's capabilities.
    init(client: any ProgressorClient, isMock: Bool = false) {
        self.isMock = isMock
        self.gaugeKind = client.kind
        self.client = client
        wire()
    }

    isolated deinit {
        freshnessTask?.cancel()
        backgroundGraceTask?.cancel()
        endBackgroundAssertion()
    }

    /// `-mockDevice` is passed by `./build.sh run`. DEBUG only: a shipped build must not
    /// be switchable to a scripted gauge by a launch argument.
    ///
    /// This gates the ARGUMENT, never the mock: `MockProgressorClient` stays compiled
    /// into every configuration for "Try demo mode" — see `useMockDevice(_:)`.
    static var mockRequestedAtLaunch: Bool {
        #if DEBUG
        return ProcessInfo.processInfo.arguments.contains("-mockDevice")
        #else
        return false
        #endif
    }

    /// `-mockProfile shaky` (or `weak`, `idle`) scripts the demo gauge for a headless
    /// run — the way to put RE-GRIP on a screenshot, since `clean` never drops. DEBUG
    /// only; a release build's demo mode is always the textbook pull.
    static var mockProfileRequestedAtLaunch: MockForceProfile {
        #if DEBUG
        let arguments = ProcessInfo.processInfo.arguments
        if let flag = arguments.firstIndex(of: "-mockProfile"), flag + 1 < arguments.count,
           let profile = MockForceProfile(rawValue: arguments[flag + 1]) {
            return profile
        }
        #endif
        return .clean
    }

    // MARK: - Which gauge

    private static let gaugeKindKey = "gauge.kind"

    private static var gaugeDefaults: UserDefaults { AppGroup.defaults ?? .standard }

    /// Internal so the persistence round-trip is testable.
    ///
    /// An unrecognised raw value reads as `.progressor` — the CONSERVATIVE direction,
    /// as `SessionKind` does; refusing to build a client would strand a downgrade on a
    /// screen that never connects.
    static func persistedGaugeKind() -> GaugeKind {
        guard let raw = gaugeDefaults.string(forKey: gaugeKindKey),
              let kind = GaugeKind(rawValue: raw) else { return .progressor }
        return kind
    }

    static func persistGaugeKind(_ kind: GaugeKind) {
        gaugeDefaults.set(kind.rawValue, forKey: gaugeKindKey)
    }

    /// **The one place a kind becomes a client**, dispatching on CAPABILITIES: a GATT
    /// profile gets the generic connected client, a broadcast-only scale the scanner,
    /// and the Progressor its own client. Adding a device is a codec plus a registry row.
    ///
    /// The Tindeq client is NOT the generic one on purpose: its serialized queries,
    /// tare-integrity latch and peripheral quarantine were each earned by a hardware
    /// failure of that protocol, and copying them to untested devices would be
    /// borrowed confidence.
    static func makeClient(for kind: GaugeKind) -> any ProgressorClient {
        if let profile = kind.gatt {
            // The resolver exists only for a gauge that needs one. Every other kind gets
            // nil and never constructs a byte of networking — see FrezCalibration.swift.
            let calibration: (any GaugeCalibrationResolver)? =
                kind.capabilities.requiresRemoteCalibration ? FrezCoefficientResolver() : nil
            return GattGaugeClient(kind: kind, profile: profile, calibration: calibration)
        }
        if kind.capabilities.isBroadcast { return BroadcastGaugeClient() }
        return LiveProgressorClient()
    }

    /// Switch gauges. Disconnects first, swaps the client, persists the choice — and
    /// does NOT connect: the system Bluetooth prompt must arrive with a Connect tap
    /// behind it.
    func selectGaugeKind(_ kind: GaugeKind) {
        // `isMock` is in the guard because choosing a gauge while the demo device is
        // running has to do something even when the kind already matches.
        guard kind != gaugeKind || isMock else { return }
        Self.persistGaugeKind(kind)
        gaugeKind = kind
        // Choosing a real gauge leaves demo mode: the mock scripts a Tindeq and cannot
        // stand in for the device just chosen.
        isMock = false
        adopt(Self.makeClient(for: kind))
    }

    // MARK: - Commands

    func connect() { client.connect() }

    func disconnect() {
        // An explicit stop is a decision; it must not resurrect itself on foreground.
        resumeScanOnForeground = false
        // Recorded here too: this bypasses `stopStreaming`, and the ring would otherwise
        // show the link going away with the stream still running.
        if isStreaming { record(.streamStopped(.disconnecting)) }
        cancelBackgroundGrace(leavingBackground: false)
        client.disconnect()
        isStreaming = false
    }

    func tare() {
        guard state.isConnected else { return }
        client.tare()
        // Re-issue start whenever a stream should be running: on hardware, taring
        // mid-stream killed the graph for good, and re-sending start is harmless.
        //
        // **Not for a broadcast gauge**, where a restart is a scan bounce and a tare is
        // app-side arithmetic that cannot stop the advertisements — it would only buy a
        // gap in the readings at the moment of the zero.
        if isStreaming, !gaugeCapabilities.isBroadcast { startStreaming(cause: .tareRecovery) }
        resetPeak(preservingTrace: true)
    }

    /// Search cancellation is an explicit disconnect; automatic recovery must not
    /// revive it afterward. Shared by Today, the gauge, max test and session controls.
    var canCancelBroadcastSearch: Bool {
        gaugeCapabilities.isBroadcast && state == .scanning
    }

    func startStreaming(cause: StreamStartCause) {
        guard state.isConnected else { return }
        // A broadcast watchdog usually finds the scan already running; don't fill the
        // ring with requests that changed nothing on the radio.
        if !gaugeCapabilities.isBroadcast || cause != .watchdog {
            record(.streamStartRequested(cause))
        }
        isStreaming = true
        client.startStreaming(cause: cause)
    }

    func recordScenePhase(_ phase: String) {
        record(.scenePhase(phase))
    }

    // MARK: - The background grace period

    /// How long the link survives after you leave the app.
    ///
    /// Disconnecting the instant you backgrounded could not tell a two-second "hey Siri"
    /// from a phone put in a bag, and charged both a 5–6 s reconnect — the "weird
    /// Bluetooth drops" the breadcrumb logs traced to `Scene: background` (2026-08-16).
    ///
    /// 45 s is Nuri's call: long enough for Siri or an app switch, short enough that a
    /// phone genuinely put down still frees the gauge.
    private static let backgroundGraceSeconds: UInt64 = 45

    @ObservationIgnored private var isInBackground = false
    @ObservationIgnored private var backgroundGraceTask: Task<Void, Never>?
    @ObservationIgnored private var backgroundAssertion: BackgroundAssertionID = .invalid
    /// Set when the background rule tears down a broadcast scan; consumed by the next
    /// foreground return. Lives only across a background→foreground span, where no UI
    /// is reachable — an explicit `disconnect()` clears it.
    @ObservationIgnored private var resumeScanOnForeground = false

    /// **The battery guarantee is preserved.** A suspended process gets no further
    /// callback, so a plain `Task.sleep` would never run and the gauge would stay awake
    /// until flat. So the grace holds a background assertion, and its EXPIRATION HANDLER
    /// disconnects if iOS suspends us early: the window is `min(45 s, whatever iOS
    /// grants)`, and the failure mode is a shorter grace — never a gauge left burning.
    func beginBackgroundGrace() {
        isInBackground = true
        // **A gauge that cannot stream in the background gets no grace at all.** A
        // broadcast "link" is an allow-duplicates scan — the most power-hungry BLE mode —
        // that iOS silences on background anyway. Its 10 s silence watchdog would flip the
        // state to `.scanning` inside the window, where `disconnectAfterGrace` (connected
        // links only) never fires, and the scan would burn on. `state.isBusy` is in the
        // guard to catch exactly scanning and connecting.
        if !gaugeCapabilities.sustainsBackgroundStreaming, state.isConnected || state.isBusy {
            disconnect()
            // Consumed by `cancelBackgroundGrace` on the way back: resuming a scan is
            // safe (no dialog, no write), and not resuming would charge every app switch
            // a Connect tap. Set AFTER `disconnect()`, which clears it as an explicit stop.
            resumeScanOnForeground = true
            return
        }
        guard state.isConnected, !isStreaming, backgroundGraceTask == nil else { return }

        backgroundAssertion = beginAssertion { [weak self] in
            // iOS is about to suspend us. Disconnect NOW or never.
            self?.disconnectAfterGrace()
        }

        // **DENIED means disconnect immediately.** With no assertion (`.invalid`) the
        // sleeping task below never runs once suspended, leaving the gauge awake until flat.
        guard backgroundAssertion != .invalid else {
            disconnect()
            return
        }

        record(.backgroundDisconnectScheduled)
        backgroundGraceTask = Task { [weak self] in
            try? await Task.sleep(for: .seconds(Self.backgroundGraceSeconds))
            guard !Task.isCancelled else { return }
            self?.disconnectAfterGrace()
        }
    }

    /// Injection seam: the Simulator cannot refuse a background assertion on demand, and
    /// the denied branch is what protects the gauge's battery.
    @ObservationIgnored
    var beginAssertion: (@escaping @MainActor () -> Void) -> BackgroundAssertionID = { handler in
        #if os(watchOS)
        _ = handler
        return .invalid
        #else
        return UIApplication.shared.beginBackgroundTask(withName: "gauge-disconnect-grace") {
            MainActor.assumeIsolated { handler() }
        }
        #endif
    }

    /// Came back inside the window: the link was never touched, so there is nothing to
    /// restore — only the pending disconnect to call off.
    func cancelBackgroundGrace(leavingBackground: Bool = true) {
        if leavingBackground { isInBackground = false }
        // The broadcast counterpart: the background rule tore the scan down, so the
        // foreground return stands it back up. Before the guard — no grace was armed.
        if resumeScanOnForeground {
            resumeScanOnForeground = false
            connect()
        }
        guard backgroundGraceTask != nil else { return }
        backgroundGraceTask?.cancel()
        backgroundGraceTask = nil
        endBackgroundAssertion()
        record(.backgroundDisconnectCancelled)
    }

    /// Internal so a test can drive it exactly as the expiration handler does — see
    /// `beginAssertion`.
    func disconnectAfterGrace() {
        backgroundGraceTask?.cancel()
        backgroundGraceTask = nil
        if state.isConnected, !isStreaming { disconnect() }
        endBackgroundAssertion()
    }

    private func endBackgroundAssertion() {
        guard backgroundAssertion != .invalid else { return }
        endAssertion(backgroundAssertion)
        backgroundAssertion = .invalid
    }

    @ObservationIgnored
    var endAssertion: (BackgroundAssertionID) -> Void = { id in
        #if !os(watchOS)
        UIApplication.shared.endBackgroundTask(id)
        #endif
    }

    func stopStreaming(cause: StreamStopCause) {
        record(.streamStopped(cause))
        isStreaming = false
        currentKg = 0
        client.stopStreaming()
    }

    func readBattery() { client.readBattery() }

    /// Explicitly power down the gauge. Normal session completion leaves it awake for
    /// the second daily session; sleeping it requires a physical button press to wake.
    func sleepDevice() {
        if isStreaming { record(.streamStopped(.sleeping)) }
        cancelBackgroundGrace(leavingBackground: false)
        client.sleepDevice()
        isStreaming = false
        currentKg = 0
    }

    /// Tare resets the live peak, but its existing trace remains historical data.
    /// Keep the playback anchor too: it already handles a restarted device counter.
    /// A new measurement/session still starts with an empty graph by default.
    func resetPeak(preservingTrace: Bool = false) {
        peakKg = 0
        if !preservingTrace {
            traceStorage.removeAll(keepingCapacity: true)
            lastTraceMicros = nil
        }
        sampleStateChanged()
    }

    /// **Throw the graph away when the app comes back to the foreground.**
    ///
    /// CoreBluetooth hands over everything it buffered while suspended, and
    /// `playbackTime` replays it into the six-second window as one squashed flat line.
    /// None of it happened in the window, so none of it is drawable.
    ///
    /// The peak is KEPT — it is a fact about the session, not the graph.
    func dropStaleTrace() {
        traceStorage.removeAll(keepingCapacity: true)
        sampleStateChanged()
        lastTraceMicros = nil
        // The gap to the first packet after a resume is the suspension, not the radio.
        lastPacketArrival = nil
        lastPacketFirstT = nil
    }

    /// The playback clock: device-time deltas on a wall-time footing, aligned so that a
    /// packet's newest reading lands at its arrival.
    ///
    /// Delta comes from the device (wrap-safe), so batching never bunches points; a
    /// delta outside (0, 1 s] means the counter restarted or the timeline broke, and
    /// one sample period is the honest guess. The result is slewed toward the target
    /// (wall time less half a packet) by at most 0.5 ms per sample — enough to track
    /// clock drift, too little to see — and snaps after a genuine stall.
    ///
    /// **"One sample period" is THIS gauge's**, not the Tindeq's 12.5 ms. Readings in one
    /// notification share a stamp (see `GattGaugeClient.ingest`) and take the fallback,
    /// so at 12.5 ms a slower gauge's packets would run the clock AHEAD of wall time and
    /// the trace would be dropped every few seconds.
    private func playbackTime(for sample: ForceSample, wallNow: TimeInterval) -> TimeInterval {
        // Half a packet BEHIND wall time: the slew settles a packet centred on its target,
        // which puts the packet's newest reading at the moment it arrived and nothing in
        // the future. Everything below that compares against "wall time" uses this.
        let target = wallNow - packetSpanEstimate / 2
        defer { lastTraceMicros = sample.deviceMicros }
        guard let previous = lastTraceMicros, let lastT = traceStorage.last?.t else { return target }
        let deltaMicros = sample.deviceMicros &- previous   // wrap-safe
        // The (0, 1 s] trust window is a DEVICE-clock rule: past it the counter
        // restarted and one period is the honest guess. A SYNTHETIC stamp is
        // host-monotonic and cannot be nonsense, and a broadcast scale's multi-second
        // holes are ordinary delivery, so it is trusted up to the 10 s silence
        // disconnect — compressing those gaps collapsed the WH-C06 trace on hardware.
        let maxTrustedMicros: UInt32 = gaugeCapabilities.hasDeviceClock ? 1_000_000 : 10_000_000
        let trusted = deltaMicros > 0 && deltaMicros <= maxTrustedMicros
        let delta = trusted
            ? Double(deltaMicros) / 1_000_000
            : 1.0 / max(1, gaugeCapabilities.nominalSampleRate)
        let candidate = lastT + delta
        let error = target - candidate
        // BEHIND the target. Two different things look alike here and get different limits:
        //
        // - An UNTRUSTED delta (a counter reset, a gap over a second) means the timeline
        //   itself broke and one period was substituted. More than a second behind after
        //   that is a real stall — jump forward and carry on, which the graph draws as a
        //   break.
        // - A TRUSTED delta means the data is contiguous and merely arrived late — an
        //   iPad's Bluetooth stack delivers in CLUMPS of a second or more. Snapping there
        //   restarted the trace at every clump and the iPad drew no line at all
        //   (2026-09-19; reproduce with `-mockClumpMS 1200`). Contiguous data may lag
        //   `lateDeliveryLimitSeconds` instead and the line stays whole.
        if error > (trusted ? Self.lateDeliveryLimitSeconds : 1.0) { return target }
        // Running AHEAD by a clump is harmless: `ForceTraceView` draws only what is due.
        // A real backlog is dropped by the caller — see `handle(_:)`. Crawling toward the
        // target from far away was worse: it converges over tens of seconds with the
        // trace squashed into a few pixels. The ±0.5 ms slew is a 4 % time-stretch.
        return candidate + min(max(error, -0.0005), 0.0005)
    }

    /// Reshape the demo gauge's pulls. A no-op on real hardware. The critical force test
    /// asks for `.allOut` while it runs and hands back the launch profile afterwards.
    func setMockProfile(_ profile: MockForceProfile) {
        (client as? MockProgressorClient)?.profile = profile
    }

    /// Swap in the synthetic device (demo mode, or anything running in the
    /// Simulator). Always compiled in — a DEBUG-only mock leaves anyone without
    /// hardware, reviewers included, stuck on a screen that never connects.
    ///
    /// Leaving demo mode returns to whatever gauge is SELECTED, not always the Progressor.
    func useMockDevice(_ mock: Bool, profile: MockForceProfile = .clean) {
        isMock = mock
        gaugeKind = mock ? .progressor : Self.persistedGaugeKind()
        adopt(mock ? MockProgressorClient(profile: profile) : Self.makeClient(for: gaugeKind))
        connect()
    }

    /// Retire the current client and adopt another.
    ///
    /// Detaching the old client's callbacks is load-bearing: `disconnect()` cancels its own
    /// tasks, but a CoreBluetooth callback already in flight would otherwise land on this
    /// store after the swap and publish a dead client's state over the new one's.
    private func adopt(_ next: any ProgressorClient) {
        client.disconnect()
        client.onEvent = nil
        client.onPacketBoundary = nil
        packetOpen = false
        packetDirty = false
        pipelineDiagnostics.reset()
        client.onStateChange = nil
        client.onDiagnostic = nil
        client = next
        resetState()
        wire()
    }

    // MARK: - Wiring

    private func wire() {
        client.onStateChange = { [weak self] state in
            guard let self else { return }
            if state.isConnected, !self.state.isConnected {
                self.connectionEpoch &+= 1
                self.pipelineDiagnostics.reset()
            }
            self.record(.connection(state))
            self.state = state
            self.deviceName = self.client.deviceName
            if !state.isConnected {
                self.isStreaming = false
                self.currentKg = 0
                self.setSignalFresh(false)
                self.freshnessStartedAt = nil
                self.lastSignalAt = nil
                // A coefficient belongs to a link; the client re-resolves on the next one.
                self.calibrationStatus = .notRequired
            }
        }
        client.onDiagnostic = { [weak self] diagnostic in
            guard let self else { return }
            switch diagnostic {
            case .broadcastScan(let event):
                self.record(.broadcastScan(event))
            case .retiringPeripheral:
                self.record(.retiringPeripheral)
            case .quarantineReleased:
                self.record(.quarantineReleased)
            case .quarantineAbandoned(let reason):
                self.record(.quarantineAbandoned(reason))
            case .streamStartDeferred(let cause):
                self.record(.streamStartDeferred(cause))
            case .streamStartWritten(let cause):
                self.record(.streamStartWritten(cause))
            case .calibration(let status):
                self.calibrationStatus = status
                self.record(.calibration(Self.calibrationPhase(status)))
            }
        }
        client.onEvent = { [weak self] event in self?.handle(event) }
        client.onPacketBoundary = { [weak self] boundary in self?.packetBoundary(boundary) }
    }

    private func resetState() {
        state = .idle
        deviceName = nil
        firmwareVersion = nil
        batteryFraction = nil
        calibrationStatus = .notRequired
        isStreaming = false
        setSignalFresh(false)
        isReadingLive = false
        freshnessStartedAt = nil
        lastSignalAt = nil
        currentKg = 0
        lastSample = nil
        resetPeak()
        // The delivery pattern belongs to the link; the next one is measured afresh.
        lastPacketArrival = nil
        lastPacketFirstT = nil
        packetSpanEstimate = 0.1
        packetsSeen = 0
    }

    /// Fixed English for the breadcrumb ring — a phase, never the serial the status
    /// carries, because the ring travels in support mail.
    private static func calibrationPhase(_ status: GaugeCalibrationStatus) -> String {
        switch status {
        case .notRequired: "not required"
        case .waitingForSerial: "waiting for serial"
        case .resolving: "looking up coefficient"
        case .ready(_, let calibration): calibration.cached ? "ready (cached)" : "ready (fetched)"
        case .failed(_, let failure):
            switch failure {
            case .missingSerial: "failed (no serial)"
            case .noAccessKey: "failed (no access key)"
            case .invalidRequest: "failed (400)"
            case .invalidAccessKey: "failed (401)"
            case .deviceLimitReached: "failed (403)"
            case .deviceNotFound: "failed (404)"
            case .ownershipReview: "failed (409)"
            case .calibrationUnavailable: "failed (422)"
            case .rateLimited: "failed (429)"
            case .badResponse: "failed (bad response)"
            case .network: "failed (network)"
            }
        }
    }

    private func handle(_ event: ProgressorEvent) {
        switch event {
        case .sample(let sample):
            // A queued notification can outlive Stop. It is historical wire traffic,
            // not a new live reading, peak, or runner sample after measurement ended.
            guard isStreaming else { return }
            pipelineDiagnostics.sample()
            currentKg = sample.kg
            if isStreaming {
                lastSignalAt = Date()
                setSignalFresh(true)
                // Immediately, not on the next watchdog tick: a stream coming back must
                // return the Tare button to taring in the same frame the numbers move.
                refreshReadingLiveness()
            }
            lastSample = sample
            peakKg = max(peakKg, sample.kg)
            onSample?(sample)

            // **A BACKLOG DELIVERED IN ONE BURST RESTARTS THE GRAPH.** A few hundred
            // queued notifications ingested in one frame walk the playback clock seconds
            // into the FUTURE. Half a clump ahead is ordinary; past
            // `lateDeliveryLimitSeconds` it proves the data did not happen now, so the
            // buffer is dropped and the next sample starts fresh at wall time. During a
            // long flush this keeps firing, which is correct.
            //
            // Self-healing with no `scenePhase` hook — a stalled main thread is the same
            // fault; `dropStaleTrace()` on foreground stays as the fast path. (0.5 s,
            // sized for an iPhone's batches, dropped every iPad clump.)
            let wallNow = Date().timeIntervalSinceReferenceDate
            if let lastT = traceStorage.last?.t,
               lastT > wallNow + Self.lateDeliveryLimitSeconds {
                record(.traceFlush(count: 1))
                traceStorage.removeAll(keepingCapacity: true)
                sampleStateChanged()
                lastTraceMicros = nil
            }

            // The packet span, learned at PACKET starts: it aligns the clock so a
            // packet's newest reading lands at its arrival.
            if sample.isBatchStart {
                if lastPacketArrival != nil, let firstT = lastPacketFirstT,
                   let lastT = traceStorage.last?.t, lastT >= firstT {
                    let span = lastT - firstT + 1.0 / max(1, gaugeCapabilities.nominalSampleRate)
                    packetSpanEstimate += (span - packetSpanEstimate) * 0.3
                }
                lastPacketArrival = wallNow
            }

            let point = TracePoint(kg: sample.kg,
                                   t: playbackTime(for: sample, wallNow: wallNow),
                                   arrival: wallNow)
            if sample.isBatchStart {
                lastPacketFirstT = point.t
                packetsSeen += 1
            }
            onTracePoint?(point)
            traceStorage.append(point)
            sampleStateChanged()
            Self.trimTrace(&traceStorage, capacity: traceCapacity, slack: traceTrimSlack)
        case .battery(let mv):
            batteryFraction = ProgressorCodec.batteryFraction(millivolts: mv)
        case .batteryFraction(let fraction):
            batteryFraction = fraction
        case .appVersion(let version):
            firmwareVersion = version
        case .lowPowerWarning:
            batteryFraction = min(batteryFraction ?? 0.1, 0.1)
        case .errorInformation, .rfdPeak, .rfdPeakSeries, .commandResponse, .unknown:
            // Decoded for completeness; nothing in the timed-hang flow consumes them
            // yet. The RFD tags are what a future max/RFD mode will read.
            break
        }
    }

    // MARK: - Signal freshness

    private func startFreshnessWatchdog() {
        freshnessGeneration &+= 1
        let generation = freshnessGeneration
        freshnessTask?.cancel()
        freshnessStartedAt = Date()
        lastSignalAt = nil
        setSignalFresh(false)
        freshnessTask = Task { [weak self] in
            while !Task.isCancelled {
                do {
                    try await Task.sleep(for: .milliseconds(500))
                } catch {
                    return
                }
                guard !Task.isCancelled,
                      let self,
                      self.freshnessGeneration == generation,
                      self.isStreaming else { return }

                let now = Date()
                self.refreshReadingLiveness(now: now)
                if let lastSignalAt = self.lastSignalAt {
                    if now.timeIntervalSince(lastSignalAt) <= self.signalSilenceTolerance {
                        self.setSignalFresh(true)
                    } else {
                        self.currentKg = 0
                        self.setSignalFresh(false)
                    }
                } else if let startedAt = self.freshnessStartedAt,
                          now.timeIntervalSince(startedAt) > self.signalSilenceTolerance {
                    self.currentKg = 0
                    self.setSignalFresh(false)
                }
            }
        }
    }

    private func stopFreshnessWatchdog() {
        freshnessGeneration &+= 1
        freshnessTask?.cancel()
        freshnessTask = nil
        freshnessStartedAt = nil
        lastSignalAt = nil
        setSignalFresh(false)
        isReadingLive = false
    }

    private func setSignalFresh(_ fresh: Bool) {
        guard isSignalFresh != fresh else { return }
        isSignalFresh = fresh
        record(.signalFreshness(fresh))
    }

    /// The cue player's door into the ring, so one export tells the whole story of a
    /// session — link, stream AND sound.
    func recordAudio(_ event: String) { record(.audio(event)) }

    private func record(_ event: DiagnosticBreadcrumb) {
        diagnosticRing.append(event)
        diagnosticEntries = diagnosticRing.entries
    }
}
