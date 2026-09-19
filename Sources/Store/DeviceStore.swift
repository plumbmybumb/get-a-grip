// SPDX-License-Identifier: MPL-2.0
// Original contributions Copyright 2026 Nuri Bruner.

import Foundation
import Observation
#if !os(watchOS)
import UIKit
#endif

#if os(watchOS)
/// watchOS has no background-task assertion. `.invalid` is the only value there, so
/// `beginBackgroundGrace` takes its DENIED branch — disconnect at once — which is the
/// right rule for a watch: outside a workout session nothing keeps the app alive long
/// enough for a grace to mean anything, and inside one the stream is left alone anyway.
struct BackgroundAssertionID: Equatable, Sendable {
    static let invalid = BackgroundAssertionID()
}
#else
typealias BackgroundAssertionID = UIBackgroundTaskIdentifier
#endif

/// Everything the app knows about the gauge right now: link state, the live force
/// reading, the rolling trace the graph draws, and battery/firmware.
///
/// `@Observable @MainActor` and injected via `.environment()` — the house store
/// pattern. It owns the client and is the ONLY thing that talks to it, so there is
/// exactly one place where wire events become app state.
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
            // Coarse and change-guarded, the same shape as `isReadingLive` below.
            // `TareButton` used to read this raw figure directly, which re-evaluated its
            // whole body — icon, label, `disabled`, the accessibility hint, the alert —
            // at sample rate (~80 Hz) for the entire session to track a value that only
            // ever matters at the ONE threshold `TarePolicy.shouldConfirm` cares about.
            // `TarePolicy.tapDecision` now takes this Bool instead; the tap's own action
            // closure can still read `currentKg` directly, since a read inside a closure
            // creates no observation dependency.
            let loaded = abs(currentKg) >= TarePolicy.confirmationThresholdKg
            if loaded != isLoadedForTare { isLoadedForTare = loaded }
        }
    }
    /// Whether the load is heavy enough that a tare needs confirming — see the `didSet`
    /// on `currentKg` immediately above for why this is published as its own Bool.
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
    /// The first hardware session killed the graph twice (connect-inside-the-runner,
    /// and every tare) while the kg readout stayed alive, because the view anchored
    /// itself to the device's µs counter and kept that anchor in view state. The
    /// counter restarts on the device's own schedule — tare, a re-sent start command,
    /// a reconnect — and a poisoned anchor had no path back. So the store, which SEES
    /// those events, rebuilds a clean monotone clock instead: each point's `t` advances
    /// by the wrap-safe device delta (clamped to one sample period when the delta is
    /// nonsense, i.e. a counter reset), slewed gently toward wall time and snapped
    /// after a genuine gap. The view just draws (now − t); there is nothing left in it
    /// to poison.
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
    /// forward, and a buffer further AHEAD is dropped as a backlog. Bunched delivery
    /// swings about half a clump either way around its average, so this tolerates
    /// clumps of roughly twice its value — well past any radio stack's, far below a
    /// suspended app's backlog.
    static let lateDeliveryLimitSeconds: TimeInterval = 3.0
    /// **NOTHING IS STAMPED IN THE FUTURE: a packet's newest reading lands at its arrival.**
    ///
    /// The clock used to slew every reading toward wall time itself, whose equilibrium is a
    /// packet CENTRED on its own arrival: its newest half stamped ahead of now, so the line
    /// pinned to the newest point paused and lurched at each packet (the pre-redesign
    /// behaviour), or — drawn only when due — waited for it. Two buffered designs followed
    /// on 2026-09-19 (a glide one packet behind; a live pen with a connector) and Nuri
    /// rejected both: the first felt behind his pull, the second looked wrong. The verdict
    /// was "just take the raw data and feed it in", so the clock now targets wall time LESS
    /// half a packet: a packet's newest reading is stamped at the moment it arrived, the
    /// older readings sit behind it by their device deltas, and the trace draws all of it
    /// at once. What smoothing remains is visual and lag-free — see `ForceTraceView`.
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
    /// must say so: a number that looks like a measurement but isn't is worse than
    /// no number.
    private(set) var isMock: Bool

    /// Which gauge the app is driving.
    ///
    /// Persisted in the App Group container rather than in `SettingsStore` because this
    /// store must know the answer BEFORE its first client exists — `init` chooses the
    /// client from it, and the settings store is a sibling environment object built
    /// alongside this one, not before it.
    private(set) var gaugeKind: GaugeKind

    /// **Gate behaviour on THESE flags, never on `gaugeKind` itself.** A rule keyed to a
    /// capability survives the next device; a rule keyed to a device name is a bug waiting
    /// in the one after. The runner reads this for its timing source and its background
    /// policy; Settings reads it for the hardware-verification footnote.
    var gaugeCapabilities: GaugeCapabilities { gaugeKind.capabilities }

    /// How long the live reading survives without a sample before the UI calls it
    /// stale and zeroes the number. One second is right for a CONNECTED stream, where
    /// a missing second means dozens of missing samples; broadcast delivery is
    /// best-effort and BURSTY — a real WH-C06's advertisements arrive in clumps with
    /// multi-second holes (the reference library tolerates TEN seconds), so the
    /// one-second rule made the kg readout and the waiting overlay flap in time with
    /// the radio (Nuri's first hardware session, 2026-08-17). 3.5 s sits well under
    /// the client's own 10 s disconnect, so a scale that genuinely left still reads
    /// as gone.
    var signalSilenceTolerance: TimeInterval {
        gaugeCapabilities.isBroadcast ? 3.5 : 1.0
    }

    /// How old the newest reading may be and still count as "live" for the Tare button —
    /// its displayed MODE (`isReadingLive`, below) and the tap's own safety re-check
    /// (`TarePolicy.isSafeToTareNow` / `.confirmationDecision`) must both read this same
    /// number, or the two can disagree, which IS the bug this exists to fix.
    ///
    /// `TarePolicy.liveReadingMaxAgeSeconds` (0.3 s) is a Tindeq number — right for an
    /// 80 Hz connected stream, where 0.3 s of silence is ~24 missing samples — and stays
    /// untouched, since other call sites may still rely on exactly that meaning. A
    /// broadcast gauge's advertisements arrive in clumps with multi-second holes, so 0.3 s
    /// flipped the button to Wake and back "oscillating back and forth" in time with the
    /// radio (Nuri, 2026-08-17) — and Wake is a no-op there anyway, since the scan never
    /// stops, so the flip was pure noise. 3.5 s matches `signalSilenceTolerance` above,
    /// this store's existing broadcast beat: a software tare captures the newest reading,
    /// and on a scale that only refreshes every few seconds, a 3.5 s-old reading is the
    /// freshest truth on offer. The ≥1 kg confirmation flow still guards a loaded tare
    /// regardless of which bound let the button say "Tare".
    var tareReadingMaxAge: TimeInterval {
        gaugeCapabilities.isBroadcast ? 3.5 : TarePolicy.liveReadingMaxAgeSeconds
    }

    /// Every sample, in order, for whoever is running a session.
    ///
    /// Deliberately a callback rather than something a view observes: `lastSample` is
    /// a snapshot for rendering, and SwiftUI coalesces observable changes, so a view
    /// watching it would see a handful of the ~80 samples that arrive each second and
    /// under-count hang time by an order of magnitude. The runner needs all of them.
    @ObservationIgnored var onSample: ((ForceSample) -> Void)?

    /// The same samples, carrying the store's own PLAYBACK time instead of the device's
    /// raw counter — see `playbackTime`, which is built to be monotone across tares,
    /// counter resets and reconnects.
    ///
    /// A second callback rather than a wider `onSample`, because the two have genuinely
    /// different needs: the runner accrues hang time from device deltas and must not be
    /// handed a slewed clock, while anything measuring over a WINDOW OF SECONDS (the max
    /// test) needs a timeline that cannot jump backwards mid-measurement. Adding a
    /// parameter to `onSample` would have forced one of them to use the other's clock.
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
    /// This exists separately from `isSignalFresh` because they answer different
    /// questions on different clocks. `isSignalFresh` records diagnostic transitions and tolerates a
    /// full second of silence; this tolerates `tareReadingMaxAge`, because a tare cannot
    /// be taken back for the rest of the session.
    ///
    /// It is republished by the same 500 ms watchdog, so it lags the true boundary by up
    /// to one tick. That lag is deliberately in the SAFE direction only: the tap
    /// re-checks `secondsSinceLastSample()` exactly and downgrades to a wake, so the
    /// worst case is a button that still says "Tare" for a moment and restarts the
    /// stream instead — never one that says "Tare" and zeroes an unknown load.
    private(set) var isReadingLive = false

    private func refreshReadingLiveness(now: Date = .now) {
        let live = isStreaming
            && (secondsSinceLastSample(now: now).map { $0 <= tareReadingMaxAge } ?? false)
        if live != isReadingLive { isReadingLive = live }
    }
    @ObservationIgnored private var diagnosticRing = DiagnosticBreadcrumbRing()

    /// ~6 seconds of history — enough to see the shape of a pull without the trace
    /// becoming an unreadable smear. Sized from the gauge's own rate: 480 points was
    /// exactly six seconds of the Progressor's 80 Hz, and at the Dyno's 250 Hz the same
    /// buffer would hold under two seconds, so the graph would end mid-pull.
    /// Two seconds more than the window shows: the buffer's lead (a packet plus its
    /// margin, up to ~1.6 s) is pending past the right edge, and a full buffer's oldest
    /// point must still lie OFF the left edge or the fill's start ramp jitters on screen
    /// as points age out (Nuri, 2026-09-19: "the shading disappears in a jittery way").
    private static let traceSeconds: Double = 8
    private static let minimumTraceCapacity = 480
    private var traceCapacity: Int {
        max(Self.minimumTraceCapacity, Int(Self.traceSeconds * gaugeCapabilities.nominalSampleRate))
    }

    private var client: any ProgressorClient

    init(useMock: Bool = DeviceStore.mockRequestedAtLaunch) {
        isMock = useMock
        // **Demo mode reports the Progressor whatever is stored.** The mock scripts a
        // Tindeq — device µs clock, hardware tare, background-capable — so reporting the
        // stored kind would hand the runner capabilities the client running does not have.
        // The stored choice is untouched and comes back the moment demo mode ends.
        let kind: GaugeKind = useMock ? .progressor : DeviceStore.persistedGaugeKind()
        gaugeKind = kind
        client = useMock ? MockProgressorClient(profile: DeviceStore.mockProfileRequestedAtLaunch)
                         : DeviceStore.makeClient(for: kind)
        wire()
    }

    /// Injection seam for lifecycle policy tests; the app path above still chooses the
    /// real or scripted client from the launch mode.
    ///
    /// The kind comes from the CLIENT rather than from storage: a test that injects a
    /// synthetic-clock gauge is making a statement about what it is driving, and reading
    /// the persisted key here would let one test's Settings choice change another's
    /// capabilities.
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

    /// `-mockDevice` is passed by `./build.sh run`, since a simulator build can never
    /// reach real hardware. DEBUG only, exactly like `-mockProfile` below: a shipped
    /// build must not be switchable to a scripted gauge by a launch argument, where a
    /// session would record loads nobody pulled.
    ///
    /// This gates the ARGUMENT, never the mock: `MockProgressorClient` stays compiled
    /// into every configuration, because the gauge's own "Try demo mode"
    /// (`useMockDevice(_:)`) is how anyone without hardware — App Review included —
    /// gets past a screen that would otherwise never connect.
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

    /// Internal rather than private so the persistence round-trip is testable — and so a
    /// test can put the stored value back afterwards instead of leaving a gauge selected
    /// for every later run.
    ///
    /// An unrecognised raw value reads as `.progressor`: that is the CONSERVATIVE
    /// direction, the same rule `SessionKind` follows for a kind written by a newer build.
    /// The alternative — refusing to build a client at all — would leave someone stuck on
    /// a screen that never connects after a downgrade.
    static func persistedGaugeKind() -> GaugeKind {
        guard let raw = gaugeDefaults.string(forKey: gaugeKindKey),
              let kind = GaugeKind(rawValue: raw) else { return .progressor }
        return kind
    }

    static func persistGaugeKind(_ kind: GaugeKind) {
        gaugeDefaults.set(kind.rawValue, forKey: gaugeKindKey)
    }

    /// **The one place a kind becomes a client**, and it dispatches on CAPABILITIES rather
    /// than on the case name: anything with a GATT profile gets the generic connected
    /// client, a broadcast-only scale gets the scanner, and the Progressor keeps its own
    /// battle-tested client. Adding a device is a codec plus a registry row — never a new
    /// branch here.
    ///
    /// The Tindeq client is NOT the generic one on purpose: its serialized queries,
    /// tare-integrity latch and peripheral quarantine were each earned by a specific
    /// hardware failure of that protocol, and copying them into a client for devices this
    /// project has never held would be borrowed confidence.
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
    /// deliberately does NOT connect: constructing a client's central is what raises the
    /// system Bluetooth prompt, and the house rule is that the ask arrives with a Connect
    /// tap behind it.
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
        // Recorded here too: `disconnect` sets `isStreaming` directly rather than going
        // through `stopStreaming`, so without this the ring would show a link going away
        // with the stream apparently still running.
        if isStreaming { record(.streamStopped(.disconnecting)) }
        cancelBackgroundGrace(leavingBackground: false)
        client.disconnect()
        isStreaming = false
    }

    func tare() {
        guard state.isConnected else { return }
        client.tare()
        // Re-issue the start command whenever a stream should be running. On the first
        // hardware session, taring mid-stream killed the graph for good — whether the
        // firmware stops the measurement or restarts its clock, re-sending start is
        // harmless in every case and restores it in the bad one.
        //
        // **Not for a broadcast gauge.** There "restart the stream" is a scan bounce
        // (`stopScan` then a fresh scan), and the failure it repairs cannot be caused by a
        // tare: nothing was written to the scale, its advertisements never stopped, and the
        // zero is app-side arithmetic. All it would buy is a visible gap in the readings at
        // the moment the user asked for a clean zero.
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
        // A broadcast watchdog usually finds the existing scan already running.
        // Preserve the client's actual scan facts without filling the ring with
        // repeated requests that did not change anything on the radio.
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
    /// **The battery rule used to fire the instant you backgrounded**, and it could not
    /// tell a two-second "hey Siri" from putting the phone in a bag: both cost a full
    /// disconnect and a 5–6 second `Searching… Connecting… Connected` on the way back.
    /// Nuri's own breadcrumb logs are what proved it (2026-08-16) — one session where he
    /// never backgrounded the app showed no disconnect at all, and the next dropped the
    /// link within one second of `Scene: background`. That reconnect churn is what he
    /// reported as "weird Bluetooth drops".
    ///
    /// 45 s is his call: long enough for Siri, a glance at a message, an app switch;
    /// short enough that a phone genuinely put down still frees the gauge.
    private static let backgroundGraceSeconds: UInt64 = 45

    @ObservationIgnored private var isInBackground = false
    @ObservationIgnored private var backgroundGraceTask: Task<Void, Never>?
    @ObservationIgnored private var backgroundAssertion: BackgroundAssertionID = .invalid
    /// Set when the background rule tears down a broadcast scan; consumed by the next
    /// foreground return. Lives only across a background→foreground span, where no UI
    /// is reachable — an explicit `disconnect()` clears it.
    @ObservationIgnored private var resumeScanOnForeground = false

    /// **The battery guarantee is preserved, not traded away.** The original rule fired
    /// at `.background` precisely because a suspended process gets no further callback —
    /// so a plain `Task.sleep` here would simply never run, and the gauge would stay
    /// connected and awake until its battery died.
    ///
    /// The fix is to hold an explicit background assertion for the grace window. Its
    /// EXPIRATION HANDLER is the real "last reliable moment": if iOS decides to suspend
    /// us before the 45 s is up, that handler still runs and still disconnects. So the
    /// window is `min(45 s, whatever iOS grants)`, and the failure mode is a shorter
    /// grace — never a gauge left burning.
    func beginBackgroundGrace() {
        isInBackground = true
        // **A gauge that cannot stream in the background gets no grace at all.** For a
        // broadcast scale the "link" is an unfiltered allow-duplicates scan — the most
        // power-hungry BLE mode there is — and iOS coalesces duplicates the moment we
        // background, so it goes silent whatever we ask for. The client's own 10 s silence
        // watchdog then flips the state to `.scanning` well inside this 45 s window, and
        // `disconnectAfterGrace` only acts on a CONNECTED link: the disconnect could never
        // fire while the scan burned on indefinitely. Reacquiring costs about a second of
        // rescan, so the grace was buying nothing on either side of the trade.
        //
        // `state.isBusy` is in the guard on purpose — scanning and connecting are exactly
        // the states this has to catch.
        if !gaugeCapabilities.sustainsBackgroundStreaming, state.isConnected || state.isBusy {
            disconnect()
            // Consumed by `cancelBackgroundGrace` on the way back. "Connecting" a
            // broadcast gauge is only scanning — no dialog, no write, no pairing — so
            // resuming it automatically is safe, and NOT resuming would charge every
            // app switch a manual Connect tap: the same reconnect churn the 45 s grace
            // below exists to avoid, solved the opposite way round because the radio
            // cost inverts (holding a Tindeq link is cheap; holding a scan is not).
            // Set AFTER `disconnect()`, which clears it as an explicit stop.
            resumeScanOnForeground = true
            return
        }
        guard state.isConnected, !isStreaming, backgroundGraceTask == nil else { return }

        backgroundAssertion = beginAssertion { [weak self] in
            // iOS is about to suspend us. Disconnect NOW or never.
            self?.disconnectAfterGrace()
        }

        // **DENIED means disconnect immediately, not "try anyway".** UIKit returns
        // `.invalid` when it will not grant background time, and with no assertion the
        // sleeping task below simply never runs once we are suspended — leaving the gauge
        // connected, awake, and draining until it is flat. That is the precise failure
        // the original fire-at-background rule existed to prevent, so when there is no
        // grace to be had we fall straight back to it.
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

    /// Injection seam. The Simulator cannot be made to refuse a background assertion on
    /// demand, and "what happens when iOS says no" is the branch that protects the
    /// gauge's battery — the one thing here that must not go untested.
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
        // The broadcast counterpart of cancelling the grace: the background rule tore
        // the scan down outright (see `beginBackgroundGrace`), so the foreground return
        // stands it back up. Before the grace guard — no grace was ever armed there.
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

    /// Internal rather than private so a test can drive it exactly as the expiration
    /// handler does — see `beginAssertion`. Without that seam the grace tests could pass
    /// while this never disconnected at all.
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
    /// While suspended the app receives nothing, and CoreBluetooth hands over whatever it
    /// buffered the moment it wakes. `playbackTime` snaps the first of those to wall time
    /// and then walks forward by their device deltas — so a burst representing half a
    /// minute of real hanging gets replayed into a six-second window as one squashed,
    /// flat line pinned to the right edge, with the graph's whole history replaced by
    /// readings from when nobody was looking (Nuri, 2026-08-09). None of it is drawable:
    /// the window only shows the last six seconds and none of this happened in them.
    ///
    /// The peak is deliberately KEPT — it is a fact about the session, not about the
    /// graph, and the axis is scaled from it.
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
    /// (wall time less half a packet, so the per-sample slew's centred equilibrium puts
    /// the packet's last reading at now) by at most 0.5 ms per sample — enough to track
    /// clock drift, too little to see — and snaps after a genuine stall, where slewing
    /// would take seconds to converge.
    ///
    /// **"One sample period" is THIS gauge's**, not the Tindeq's 12.5 ms. Every reading in
    /// one notification carries the same stamp (see `GattGaugeClient.ingest`), so the
    /// interior ones arrive here with a zero delta and take the fallback — and a packet of N
    /// readings would then advance the clock by N × 12.5 ms whatever the real interval was.
    /// On a device that declares its own sample count that can run the playback clock AHEAD
    /// of wall time, which this timeline cannot represent: the trace is dropped every time
    /// it happens, so the graph would clear itself every few seconds for the session's whole
    /// life. Keyed to the nominal rate the arithmetic is identical for the Progressor at
    /// 80 Hz and honest for the 8–40 Hz kinds.
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
        // host-monotonic elapsed time and cannot be nonsense — and a broadcast
        // scale's multi-second advertisement holes are ordinary delivery, not a
        // reset — so it is trusted up to the 10 s the silence watchdog calls a
        // disconnect. Compressing those real gaps to one period was part of why the
        // sparse WH-C06 trace kept collapsing on hardware (2026-08-17).
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
        // - A TRUSTED delta means the data is contiguous; it merely arrived late. That is
        //   what an iPad's Bluetooth stack does: it hands the stream over in CLUMPS of a
        //   second or more, so the first sample of each clump is a whole clump behind
        //   wall time while its timestamps are perfect. Snapping there (at 0.25 s, then
        //   at 1.0 s) restarted the trace at every clump, and the dropped buffer on the
        //   way back down (below) finished the job: Nuri's iPad drew no line at all
        //   (2026-09-19; reproduced in the simulator with `-mockClumpMS 1200`).
        //   Contiguous data may lag `lateDeliveryLimitSeconds` instead: the line stays
        //   whole and runs a clump behind reality, which is what the readout beside it
        //   already does.
        if error > (trusted ? Self.lateDeliveryLimitSeconds : 1.0) { return target }
        // Running AHEAD of the target is the other half of a clump — its last samples land
        // before their time. `ForceTraceView` draws only what is due and lets the rest
        // wait past its right edge, so being ahead by a clump is harmless; only a real
        // backlog is dropped, by the caller — see `handle(_:)`. Crawling toward the target
        // from far away was tried and was worse: it converges over tens of seconds, and the
        // whole trace sits squashed into a few pixels the entire time (Nuri's 13.9 kg
        // screenshot, 2026-08-09). The ±0.5 ms slew is a 4 % time-stretch: enough to
        // follow the buffer depth as the delivery pattern changes, too little to see.
        return candidate + min(max(error, -0.0005), 0.0005)
    }

    /// Swap in the synthetic device (demo mode, or anything running in the
    /// Simulator). Always compiled in — a DEBUG-only mock leaves anyone without
    /// hardware, reviewers included, stuck on a screen that never connects.
    ///
    /// Leaving demo mode returns to whatever gauge is SELECTED, not always the Progressor:
    /// someone who chose a crane scale and then looked at the demo must land back on the
    /// scale.
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

            // **A BACKLOG DELIVERED IN ONE BURST RESTARTS THE GRAPH.**
            //
            // CoreBluetooth queues notifications while the app is suspended and hands the
            // lot over on wake. Each carries a device timestamp 12.5 ms after the last, so
            // ingesting a few hundred of them in one frame walks the playback clock
            // seconds into the FUTURE. A little of that is ordinary — the tail of a
            // delivery clump is ahead by half a clump, and the graph now holds those
            // points past its right edge until they are due — but seconds of it is proof
            // that what just arrived did not happen now. So past `lateDeliveryLimitSeconds`
            // the buffer is dropped and the next sample starts a fresh run at wall time.
            // During a long flush this simply keeps firing, which is correct: nothing is
            // drawn until samples are arriving at real-time pace again, and then the trace
            // grows in from the right edge and fades up like any other fresh run.
            //
            // Self-healing, and it needs no `scenePhase` hook: a stalled main thread or a
            // radio that buffers for its own reasons is the same fault and gets the same
            // repair. `dropStaleTrace()` on foreground stays as the fast path.
            // It was 0.5 s, sized against an iPhone's 100 ms batches — and it dropped the
            // buffer at the tail of every iPad clump (Nuri's iPad, 2026-09-19).
            let wallNow = Date().timeIntervalSinceReferenceDate
            if let lastT = traceStorage.last?.t,
               lastT > wallNow + Self.lateDeliveryLimitSeconds {
                record(.traceFlush(count: 1))
                traceStorage.removeAll(keepingCapacity: true)
                sampleStateChanged()
                lastTraceMicros = nil
            }

            // The packet span, learned at PACKET starts (a packet's first sample carries
            // the mark; the rest of it arrives in the same turn): it is what aligns the
            // clock so a packet's newest reading lands at its arrival.
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
            if traceStorage.count > traceCapacity {
                traceStorage.removeFirst(traceStorage.count - traceCapacity)
            }
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

    private func record(_ event: DiagnosticBreadcrumb) {
        diagnosticRing.append(event)
        diagnosticEntries = diagnosticRing.entries
    }
}
