// SPDX-License-Identifier: MPL-2.0
// Original contributions Copyright 2026 Nuri Bruner.

import XCTest
@testable import Doigt

/// The seamed half of multi-gauge support: which client a kind gets, what survives a
/// relaunch, and the two pieces of arithmetic every ported device depends on (the
/// synthetic clock and the app-side tare).
///
/// No CoreBluetooth anywhere — same rule as `BLELifecycleTests`. The clients are
/// constructed but never connected, which is safe by design: creating the central is
/// deferred to `connect()` precisely so the system Bluetooth prompt belongs to a tap.
@MainActor
final class MultiGaugeStoreTests: XCTestCase {

    // MARK: - Which client drives which device

    /// **The routing table, asserted rather than trusted.** `makeClient` dispatches on
    /// capabilities, so this is the test that catches a device wired to a client that
    /// physically cannot drive it — a broadcast scale handed the connected client would
    /// scan for a service that does not exist, and a GATT board handed the broadcast
    /// client would never subscribe to anything.
    func testEveryKindGetsTheClientThatCanActuallyDriveIt() {
        for kind in GaugeKind.allCases {
            let client = DeviceStore.makeClient(for: kind)
            XCTAssertEqual(client.kind, kind,
                           "\(kind) got a client that reports itself as \(client.kind)")

            switch kind {
            case .progressor:
                // Its own battle-tested client: serialized queries, the tare-integrity
                // latch and the peripheral quarantine are Tindeq-specific and stay there.
                XCTAssertTrue(client is LiveProgressorClient)
                XCTAssertNil(kind.gatt)
            case .whc06:
                XCTAssertTrue(client is BroadcastGaugeClient)
                XCTAssertNil(kind.gatt, "a broadcast device has nothing to connect to")
                XCTAssertTrue(kind.capabilities.isBroadcast)
            default:
                XCTAssertTrue(client is GattGaugeClient, "\(kind) needs the generic client")
                XCTAssertNotNil(kind.gatt, "\(kind) is driven by GATT and must carry a profile")
            }
        }
    }

    /// The picker lists `selectable`, so a kind missing from it is a device the app can
    /// decode, persist and drive — and that nobody can choose. Exactly ONE kind is
    /// unlisted on purpose: the PB-700BT is a gyroscopic powerball whose stream is RPM,
    /// and offered as a force gauge it would bank fake hang time and record five-figure
    /// maxes. Adding a kind here without adding it to `selectable` must be a decision
    /// with a name, never an oversight — hence the explicit exclusion list.
    func testEverySelectableKindIsReachableAndNoneIsListedTwice() {
        let deliberatelyUnselectable: Set<GaugeKind> = [.pb700bt]
        XCTAssertEqual(Set(GaugeKind.selectable),
                       Set(GaugeKind.allCases).subtracting(deliberatelyUnselectable))
        XCTAssertEqual(GaugeKind.selectable.count,
                       GaugeKind.allCases.count - deliberatelyUnselectable.count,
                       "no kind is listed twice")
        XCTAssertEqual(GaugeKind.selectable.first, .progressor,
                       "the one verified device leads the list")
    }

    /// **These raw values are a STORAGE FORMAT.** `gauge.kind` holds the raw string, so
    /// renaming a case silently resets somebody's chosen gauge to the Progressor on the
    /// next launch — the same class of trap as the grip key's letter order.
    func testGaugeKindRawValuesArePinnedBecauseTheyArePersisted() {
        XCTAssertEqual(GaugeKind.progressor.rawValue, "progressor")
        XCTAssertEqual(GaugeKind.whc06.rawValue, "whc06")
        XCTAssertEqual(GaugeKind.entralpi.rawValue, "entralpi")
        XCTAssertEqual(GaugeKind.forceboard.rawValue, "forceboard")
        XCTAssertEqual(GaugeKind.climbro.rawValue, "climbro")
        XCTAssertEqual(GaugeKind.motherboard.rawValue, "motherboard")
        XCTAssertEqual(GaugeKind.cts500.rawValue, "cts500")
        XCTAssertEqual(GaugeKind.pb700bt.rawValue, "pb700bt")
    }

    // MARK: - Persistence

    func testTheChosenGaugeSurvivesARelaunch() {
        let original = DeviceStore.persistedGaugeKind()
        defer { DeviceStore.persistGaugeKind(original) }

        let store = DeviceStore(client: FakeGaugeClient(kind: .progressor))
        store.selectGaugeKind(.entralpi)
        XCTAssertEqual(store.gaugeKind, .entralpi)

        // A fresh store is exactly what the next launch builds.
        let relaunched = DeviceStore(useMock: false)
        XCTAssertEqual(relaunched.gaugeKind, .entralpi)
        XCTAssertFalse(relaunched.gaugeCapabilities.hasDeviceClock,
                       "capabilities must follow the stored kind, not the Progressor's")
    }

    /// An unknown raw value — a kind written by a newer build, then a downgrade — reads as
    /// the Progressor rather than refusing to build a client. Under-serving one device
    /// beats a screen that never connects.
    func testAnUnrecognisedStoredKindFallsBackToTheProgressor() {
        let original = DeviceStore.persistedGaugeKind()
        defer { DeviceStore.persistGaugeKind(original) }

        // The literal IS the on-disk key — written here on purpose, because no API can
        // store a raw value the enum has never heard of.
        (AppGroup.defaults ?? .standard).set("gyroscopic-pinch-block", forKey: "gauge.kind")
        XCTAssertEqual(DeviceStore.persistedGaugeKind(), .progressor)
    }

    /// Demo mode is a CLIENT, not a kind: the mock scripts a Tindeq, so reporting the
    /// stored kind would hand the runner a synthetic-clock policy for a device-clocked
    /// stream. Choosing a gauge is also how you leave demo mode.
    func testDemoModeReportsTheProgressorAndChoosingAGaugeLeavesIt() {
        let original = DeviceStore.persistedGaugeKind()
        defer { DeviceStore.persistGaugeKind(original) }
        DeviceStore.persistGaugeKind(.whc06)

        let store = DeviceStore(useMock: true)
        XCTAssertTrue(store.isMock)
        XCTAssertEqual(store.gaugeKind, .progressor)
        XCTAssertTrue(store.gaugeCapabilities.hasDeviceClock)

        store.selectGaugeKind(.whc06)
        XCTAssertFalse(store.isMock, "picking a real gauge leaves the demo device behind")
        XCTAssertEqual(store.gaugeKind, .whc06)
        XCTAssertFalse(store.gaugeCapabilities.sustainsBackgroundStreaming)
    }

    /// Leaving demo mode must return to the SELECTED gauge, not always to the Progressor.
    func testLeavingDemoModeReturnsToTheSelectedGauge() {
        let original = DeviceStore.persistedGaugeKind()
        defer { DeviceStore.persistGaugeKind(original) }
        DeviceStore.persistGaugeKind(.climbro)

        let store = DeviceStore(useMock: true)
        store.useMockDevice(false)

        XCTAssertEqual(store.gaugeKind, .climbro)
        XCTAssertFalse(store.isMock)
    }

    // MARK: - The synthetic sample clock

    /// **The wrap is deliberate, not a limit to work around.** Truncating host uptime to
    /// `UInt32` reproduces the Tindeq's ~71.6-minute rollover so the whole app keeps ONE
    /// wrap rule, already handled everywhere with `&-`.
    func testSyntheticStampsMeasureRealTimeAcrossTheUInt32Wrap() {
        // Exactly representable uptimes, so the expected µs are exact.
        let before = SyntheticSampleClock.micros(uptime: 4294.5)   // 4_294_500_000 µs
        let after = SyntheticSampleClock.micros(uptime: 4295.0)    // 4_295_000_000 µs, wrapped

        XCTAssertEqual(before, 4_294_500_000)
        // 4_295_000_000 − 2^32 = 32_704
        XCTAssertEqual(after, 32_704)
        XCTAssertLessThan(after, before, "the counter rolls over, exactly as the gauge's does")
        XCTAssertEqual(after &- before, 500_000, "and the wrap-safe delta is still half a second")

        // The ordinary case, for completeness: 0.1 s of uptime is 100_000 µs of stamp.
        let first = SyntheticSampleClock.micros(uptime: 12.5)
        let second = SyntheticSampleClock.micros(uptime: 12.6)
        XCTAssertEqual(second &- first, 100_000)
        XCTAssertGreaterThan(second, first)
    }

    // MARK: - The app-side tare

    func testSoftwareTareSubtractsTheCapturedZeroAndDiesWithTheLink() {
        var tare = SoftwareTare()

        // Nothing captured yet: readings pass through untouched.
        let untared = tare.value(for: 3.0)
        XCTAssertEqual(untared, 3.0, accuracy: 1e-9)
        XCTAssertTrue(tare.hasReading)

        tare.capture()
        XCTAssertEqual(tare.offset, 3.0, accuracy: 1e-9)
        let atZero = tare.value(for: 3.0)
        let loaded = tare.value(for: 8.5)
        // A load BELOW the captured zero reads negative, and must stay signed: that is
        // what a gauge zeroed under load actually reports when the load comes off.
        let unloaded = tare.value(for: 1.0)
        XCTAssertEqual(atZero, 0.0, accuracy: 1e-9)
        XCTAssertEqual(loaded, 5.5, accuracy: 1e-9)
        XCTAssertEqual(unloaded, -2.0, accuracy: 1e-9)

        // The link went away: an offset captured against one connection's zero says
        // nothing about the next one's.
        tare.reset()
        XCTAssertFalse(tare.hasReading)
        let afterReset = tare.value(for: 3.0)
        XCTAssertEqual(afterReset, 3.0, accuracy: 1e-9)
    }

    /// **A capture with nothing observed leaves the offset alone.** Zeroing it instead
    /// would move every later reading by the load that was on the gauge, and a tare tapped
    /// while the stream is quiet is exactly the case `TarePolicy` sends to a wake.
    func testTaringWithNoReadingYetChangesNothing() {
        var fresh = SoftwareTare()
        fresh.capture()
        XCTAssertEqual(fresh.offset, 0, accuracy: 1e-9)
        let passedThrough = fresh.value(for: 4.0)
        XCTAssertEqual(passedThrough, 4.0, accuracy: 1e-9)

        var loaded = SoftwareTare()
        _ = loaded.value(for: 2.0)
        loaded.capture()
        loaded.reset()
        loaded.capture()
        XCTAssertEqual(loaded.offset, 0, accuracy: 1e-9,
                       "a reset clears the remembered reading too, so a later capture has nothing to re-apply")
    }

    // MARK: - Tare liveness

    /// **The Tare button's mode and the tap's own re-check must read the same bound, or
    /// they can disagree** — a broadcast gauge's readings land seconds apart, and the old
    /// flat 0.3 s flipped the button to Wake and back "oscillating back and forth" in time
    /// with the radio (Nuri, 2026-08-17). Compared against the CONSTANT, not a literal, so
    /// this test still passes if `TarePolicy.liveReadingMaxAgeSeconds` is ever retuned.
    func testTareReadingMaxAgeIsBroadcastAware() {
        let broadcast = DeviceStore(client: FakeGaugeClient(kind: .whc06))
        XCTAssertEqual(broadcast.tareReadingMaxAge, 3.5,
                       "a broadcast gauge's advertisements arrive in bursty clumps, not a steady 80 Hz stream")

        let progressor = DeviceStore(client: FakeGaugeClient(kind: .progressor))
        XCTAssertEqual(progressor.tareReadingMaxAge, TarePolicy.liveReadingMaxAgeSeconds,
                       "a connected stream keeps the Tindeq's own bound, untouched")
    }

    // MARK: - Battery

    /// Two battery shapes, and they must not be forced into one: a standard-service
    /// PERCENTAGE arrives ready to use, while the Progressor's millivolts go through its
    /// own LiPo discharge curve. Pushing the ported devices through that curve would read
    /// 42 % as a voltage.
    func testStandardBatteryPercentageBypassesTheProgressorDischargeCurve() {
        let client = FakeGaugeClient(kind: .entralpi)
        let store = DeviceStore(client: client)
        XCTAssertEqual(store.gaugeKind, .entralpi, "the injected client declares the kind")

        client.emit(.batteryFraction(0.42))
        XCTAssertEqual(store.batteryFraction ?? -1, 0.42, accuracy: 1e-9)

        client.emit(.battery(millivolts: 3700))
        XCTAssertEqual(store.batteryFraction ?? -1,
                       ProgressorCodec.batteryFraction(millivolts: 3700), accuracy: 1e-9)
        XCTAssertNotEqual(store.batteryFraction ?? -1, 0.42, accuracy: 1e-9)
    }

    /// **Capability gating of the battery path.** Only the generic connected client can
    /// read 0x2A19, so a kind claiming the standard Battery Service without a GATT profile
    /// would advertise a battery level nothing is able to fetch — the row would sit blank
    /// forever with no way to tell that from a flat cell.
    func testOnlyConnectedKindsClaimTheStandardBatteryService() {
        for kind in GaugeKind.allCases where kind.capabilities.hasStandardBattery {
            XCTAssertNotNil(kind.gatt,
                            "\(kind) claims 0x180F but has no profile for a client to read it through")
        }
        XCTAssertFalse(GaugeKind.progressor.capabilities.hasStandardBattery,
                       "the Progressor reports millivolts, not a service percentage")
        XCTAssertFalse(GaugeKind.whc06.capabilities.hasStandardBattery,
                       "a broadcast scale exposes no services at all")
    }

    // MARK: - Tare capability

    /// `GattGaugeClient` does a HARDWARE tare only when the profile names both a
    /// characteristic and a payload, and falls back to arithmetic otherwise. So a kind
    /// claiming a hardware tare without those bytes would silently get the software one
    /// while capabilities said otherwise — and `hasHardwareTare` is what tells the runner
    /// whether a zero survives a reconnect.
    func testKindsClaimingAHardwareTareCarryTheWriteThatPerformsIt() {
        for kind in GaugeKind.allCases where kind.capabilities.hasHardwareTare {
            guard kind != .progressor else { continue }   // its own client, its own opcode
            let gatt = kind.gatt
            XCTAssertNotNil(gatt?.tareCharacteristicUUID,
                            "\(kind) claims a hardware tare but names no characteristic to write it to")
            XCTAssertNotNil(gatt?.tarePayload,
                            "\(kind) names a tare characteristic but no bytes to write")
        }
        // The other direction, and it is the one that catches a stale capability table: a
        // profile naming a tare characteristic means `GattGaugeClient` performs a REAL
        // device tare on it, whatever the flag says. `hasHardwareTare` must follow the
        // codec, or the table describes a device the app no longer drives that way.
        for kind in GaugeKind.allCases where kind.gatt?.tareCharacteristicUUID != nil {
            XCTAssertTrue(kind.capabilities.hasHardwareTare,
                          "\(kind) is tared on the device, so GaugeKind.capabilities.hasHardwareTare must be true")
        }
    }

    // MARK: - Backgrounding a gauge that cannot stream in the background

    /// **No grace for a scan.** The 45 s window exists so a two-second "hey Siri" does not
    /// cost a reconnect, and it is priced for a GATT link. A broadcast scale's link is an
    /// unfiltered allow-duplicates scan that iOS silences the moment we background: the
    /// client's own 10 s watchdog then flips the state to `.scanning`, and
    /// `disconnectAfterGrace` only acts on a CONNECTED link — so the disconnect could never
    /// fire while the most expensive scan mode there is burned on indefinitely.
    ///
    /// Both entry states matter, which is why `isBusy` is in the rule: by the time anything
    /// looks at this the scan has usually already re-armed itself.
    func testAGaugeThatCannotStreamInTheBackgroundIsDisconnectedAtOnce() {
        for state in [ProgressorConnectionState.connected, .scanning] {
            let client = FakeGaugeClient(kind: .whc06)
            let device = DeviceStore(client: client)
            device.beginAssertion = { _ in .invalid }
            device.endAssertion = { _ in }
            client.setState(state)

            device.beginBackgroundGrace()

            XCTAssertFalse(device.state.isConnected, "from \(state) the scan must be stopped")
            XCTAssertFalse(device.state.isBusy)
            XCTAssertFalse(device.diagnosticEntries.contains {
                $0.event == .backgroundDisconnectScheduled
            }, "no grace was armed, so the ring must not claim one was")
        }
    }

    /// The rule is keyed to the CAPABILITY, not to "is it a port": a connected gauge that
    /// sustains background streaming still gets the window, so the Bluetooth-drop fix
    /// survives for every device it was written for.
    func testAGaugeThatSustainsBackgroundStreamingStillGetsTheGrace() {
        let client = FakeGaugeClient(kind: .entralpi)
        let device = DeviceStore(client: client)
        device.beginAssertion = { _ in UIBackgroundTaskIdentifier(rawValue: 1) }
        device.endAssertion = { _ in }
        client.setState(.connected)

        device.beginBackgroundGrace()

        XCTAssertTrue(device.state.isConnected)
        XCTAssertTrue(device.diagnosticEntries.contains {
            $0.event == .backgroundDisconnectScheduled
        })
        device.cancelBackgroundGrace()
    }

    /// The teardown above must UNDO ITSELF on foreground: "connecting" a broadcast gauge
    /// is only scanning — no dialog, no write, no pairing — so resuming automatically is
    /// safe, and without it every app switch costs a manual Connect tap, the exact
    /// reconnect churn the Tindeq's grace exists to avoid.
    func testABroadcastScanTornDownByBackgroundingResumesItselfOnForeground() {
        let client = FakeGaugeClient(kind: .whc06)
        let device = DeviceStore(client: client)
        device.beginAssertion = { _ in .invalid }
        device.endAssertion = { _ in }
        client.setState(.connected)

        device.beginBackgroundGrace()
        XCTAssertFalse(device.state.isConnected)

        device.cancelBackgroundGrace()   // what the foreground scenePhase handler calls
        XCTAssertTrue(device.state.isConnected, "the scan must stand back up without a tap")
    }

    /// An EXPLICIT disconnect is a decision, not a casualty of backgrounding, and the
    /// foreground return must not overrule it.
    func testAnExplicitDisconnectIsNotOverruledByTheForegroundResume() {
        let client = FakeGaugeClient(kind: .whc06)
        let device = DeviceStore(client: client)
        device.beginAssertion = { _ in .invalid }
        device.endAssertion = { _ in }
        client.setState(.connected)

        device.beginBackgroundGrace()    // arms the resume
        device.disconnect()              // the user's own stop clears it
        device.cancelBackgroundGrace()   // next foreground
        XCTAssertFalse(device.state.isConnected)
    }

    // MARK: - Tare recovery

    /// **A tare on a crane scale must not bounce the scan.** `.tareRecovery` re-sends the
    /// start command because taring a Progressor mid-stream once killed its stream for good;
    /// on a broadcast client the same call is `stopScan()` plus a fresh scan, and nothing a
    /// tare does there can break anything — no write reached the scale, its advertisements
    /// never stopped, and the zero is app-side arithmetic. All it buys is a visible gap in
    /// the readings at the moment somebody asked for a clean zero.
    func testTaringABroadcastScaleDoesNotRekickTheScan() {
        let broadcast = FakeGaugeClient(kind: .whc06)
        let scale = DeviceStore(client: broadcast)
        broadcast.setState(.connected)
        scale.startStreaming(cause: .initial)
        scale.tare()

        XCTAssertEqual(broadcast.commands, [.startWeightMeasurement, .tare],
                       "the tare added no second start")

        // The connected case is unchanged, and it is the one a hardware session earned.
        let connected = FakeGaugeClient(kind: .entralpi)
        let plate = DeviceStore(client: connected)
        connected.setState(.connected)
        plate.startStreaming(cause: .initial)
        plate.tare()

        XCTAssertEqual(connected.commands,
                       [.startWeightMeasurement, .tare, .startWeightMeasurement])
    }

    /// Broadcast is the one shape with no wire at all, so everything a client would write
    /// has to be false for it — including the hardware tare, which is why
    /// `BroadcastGaugeClient` carries a `SoftwareTare`.
    func testABroadcastGaugeHasNothingToWriteTo() {
        let capabilities = GaugeKind.whc06.capabilities
        XCTAssertTrue(capabilities.isBroadcast)
        XCTAssertFalse(capabilities.hasHardwareTare)
        XCTAssertFalse(capabilities.hasDeviceClock)
        XCTAssertFalse(capabilities.sustainsBackgroundStreaming,
                       "iOS coalesces duplicate advertisements in the background, so the scan goes silent")
    }
}

/// A client that reports whatever kind a test needs and pushes events on demand. The
/// counterpart to `BLELifecycleTests`' recording client, which is fixed to the Progressor.
@MainActor
private final class FakeGaugeClient: ProgressorClient {
    var onEvent: ((ProgressorEvent) -> Void)?
    var onStateChange: ((ProgressorConnectionState) -> Void)?
    var onDiagnostic: ((ProgressorClientDiagnostic) -> Void)?

    private(set) var state: ProgressorConnectionState = .idle
    private(set) var deviceName: String? = "Test gauge"
    let kind: GaugeKind
    private(set) var commands: [ProgressorCommand] = []

    init(kind: GaugeKind) {
        self.kind = kind
    }

    func connect() { setState(.connected) }
    func disconnect() { setState(.disconnected(reason: nil)) }
    func send(_ command: ProgressorCommand) { commands.append(command) }

    func startStreaming(cause: StreamStartCause) {
        commands.append(.startWeightMeasurement)
        onDiagnostic?(.streamStartWritten(cause))
    }

    func sleepDevice() { send(.enterSleep) }

    func setState(_ next: ProgressorConnectionState) {
        state = next
        onStateChange?(next)
    }

    func emit(_ event: ProgressorEvent) { onEvent?(event) }
}
