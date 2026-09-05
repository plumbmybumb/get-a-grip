// SPDX-License-Identifier: MPL-2.0
// Original contributions Copyright 2026 Nuri Bruner.

package run.nuri.getagrip

import run.nuri.getagrip.ble.MockForceProfile
import run.nuri.getagrip.ble.ProgressorConnectionState
import run.nuri.getagrip.ble.SoftwareTare
import run.nuri.getagrip.ble.StreamStartCause
import run.nuri.getagrip.store.DeviceStore
import run.nuri.getagrip.store.DiagnosticBreadcrumb
import run.nuri.getagrip.store.GaugeClientFactory
import run.nuri.getagrip.store.GaugeClientRouting
import run.nuri.getagrip.store.GaugeClientShape
import run.nuri.getagrip.store.InMemoryGaugeKindStore
import run.nuri.getagrip.store.TarePolicy
import run.nuri.getagrip.engine.GaugeKind
import run.nuri.getagrip.engine.ProgressorCodec
import run.nuri.getagrip.engine.ProgressorCommand
import run.nuri.getagrip.engine.ProgressorEvent
import run.nuri.getagrip.engine.SyntheticSampleClock
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/// The seamed half of multi-gauge support: which client a kind gets, what survives a
/// relaunch, and the two pieces of arithmetic every ported device depends on (the synthetic
/// clock and the app-side tare).
///
/// No Bluetooth anywhere — same rule as `BLELifecycleTests`.
class MultiGaugeStoreTests {

    // MARK: - Which client drives which device

    /// **The routing table, asserted rather than trusted.** The router dispatches on
    /// capabilities, so this is the test that catches a device wired to a client that
    /// physically cannot drive it — a broadcast scale handed the connected client would scan
    /// for a service that does not exist, and a GATT board handed the broadcast client would
    /// never subscribe to anything.
    ///
    /// TRANSLATION NOTE: the Swift test builds the real clients and checks their types,
    /// which is safe there because creating the central is deferred to `connect()`. Every
    /// Android client needs a `Context` and a `BluetoothManager`, so the decision is named
    /// as `GaugeClientShape` and asserted there; `AndroidGaugeClientFactory` is the single
    /// `when` that turns a shape into an object.
    @Test
    fun everyKindGetsTheClientThatCanActuallyDriveIt() {
        for (kind in GaugeKind.entries) {
            when (kind) {
                GaugeKind.progressor -> {
                    // Its own battle-tested client: serialized queries, the tare-integrity
                    // latch and the peripheral quarantine are Tindeq-specific and stay there.
                    assertEquals(GaugeClientShape.progressor, GaugeClientRouting.shape(kind))
                    assertNull(kind.gatt)
                }
                GaugeKind.whc06 -> {
                    assertEquals(GaugeClientShape.broadcast, GaugeClientRouting.shape(kind))
                    assertNull(kind.gatt, "a broadcast device has nothing to connect to")
                    assertTrue(kind.capabilities.isBroadcast)
                }
                else -> {
                    assertEquals(
                        GaugeClientShape.gatt,
                        GaugeClientRouting.shape(kind),
                        "$kind needs the generic client",
                    )
                    assertNotNull(kind.gatt, "$kind is driven by GATT and must carry a profile")
                }
            }
        }
    }

    /// The picker lists `selectable`, so a kind missing from it is a device the app can
    /// decode, persist and drive — and that nobody can choose. Exactly ONE kind is unlisted
    /// on purpose: the PB-700BT is a gyroscopic powerball whose stream is RPM, and offered
    /// as a force gauge it would bank fake hang time and record five-figure maxes. Adding a
    /// kind here without adding it to `selectable` must be a decision with a name, never an
    /// oversight — hence the explicit exclusion list.
    @Test
    fun everySelectableKindIsReachableAndNoneIsListedTwice() {
        val deliberatelyUnselectable = setOf(GaugeKind.pb700bt)
        assertEquals(
            GaugeKind.entries.toSet() - deliberatelyUnselectable,
            GaugeKind.selectable.toSet(),
        )
        assertEquals(
            GaugeKind.entries.size - deliberatelyUnselectable.size,
            GaugeKind.selectable.size,
            "no kind is listed twice",
        )
        assertEquals(
            GaugeKind.progressor,
            GaugeKind.selectable.first(),
            "the one verified device leads the list",
        )
    }

    /// **These raw values are a STORAGE FORMAT.** `gauge.kind` holds the raw string, so
    /// renaming a case silently resets somebody's chosen gauge to the Progressor on the next
    /// launch — the same class of trap as the grip key's letter order.
    @Test
    fun gaugeKindRawValuesArePinnedBecauseTheyArePersisted() {
        assertEquals("progressor", GaugeKind.progressor.rawValue)
        assertEquals("whc06", GaugeKind.whc06.rawValue)
        assertEquals("entralpi", GaugeKind.entralpi.rawValue)
        assertEquals("forceboard", GaugeKind.forceboard.rawValue)
        assertEquals("climbro", GaugeKind.climbro.rawValue)
        assertEquals("motherboard", GaugeKind.motherboard.rawValue)
        assertEquals("cts500", GaugeKind.cts500.rawValue)
        assertEquals("pb700bt", GaugeKind.pb700bt.rawValue)
    }

    // MARK: - Persistence

    @Test
    fun theChosenGaugeSurvivesARelaunch() {
        val kinds = InMemoryGaugeKindStore()
        val factory = GaugeClientFactory { FakeGaugeClient(it) }

        val store = DeviceStore(
            client = FakeGaugeClient(GaugeKind.progressor),
            scope = inertScope(),
            clock = FakeClock(),
            kindStore = kinds,
            clientFactory = factory,
        )
        store.selectGaugeKind(GaugeKind.entralpi)
        assertEquals(GaugeKind.entralpi, store.gaugeKind)

        // A fresh store over the same storage is exactly what the next launch builds.
        val relaunched = DeviceStore(
            useMock = false,
            scope = inertScope(),
            kindStore = kinds,
            clientFactory = factory,
            clock = FakeClock(),
        )
        assertEquals(GaugeKind.entralpi, relaunched.gaugeKind)
        assertFalse(
            relaunched.gaugeCapabilities.hasDeviceClock,
            "capabilities must follow the stored kind, not the Progressor's",
        )
    }

    /// An unknown raw value — a kind written by a newer build, then a downgrade — reads as
    /// the Progressor rather than refusing to build a client. Under-serving one device beats
    /// a screen that never connects.
    @Test
    fun anUnrecognisedStoredKindFallsBackToTheProgressor() {
        val kinds = InMemoryGaugeKindStore()
        // The literal IS the on-disk value — written here on purpose, because no typed API
        // can store a raw value the enum has never heard of.
        kinds.writeRaw("gyroscopic-pinch-block")
        assertEquals(GaugeKind.progressor, kinds.load())
    }

    /// Demo mode is a CLIENT, not a kind: the mock scripts a Tindeq, so reporting the stored
    /// kind would hand the runner a synthetic-clock policy for a device-clocked stream.
    /// Choosing a gauge is also how you leave demo mode.
    @Test
    fun demoModeReportsTheProgressorAndChoosingAGaugeLeavesIt() {
        val kinds = InMemoryGaugeKindStore()
        kinds.save(GaugeKind.whc06)

        val store = DeviceStore(
            useMock = true,
            scope = inertScope(),
            kindStore = kinds,
            clientFactory = GaugeClientFactory { FakeGaugeClient(it) },
            clock = FakeClock(),
            mockFactory = { FakeGaugeClient(GaugeKind.progressor) },
        )
        assertTrue(store.isMock)
        assertEquals(GaugeKind.progressor, store.gaugeKind)
        assertTrue(store.gaugeCapabilities.hasDeviceClock)

        store.selectGaugeKind(GaugeKind.whc06)
        assertFalse(store.isMock, "picking a real gauge leaves the demo device behind")
        assertEquals(GaugeKind.whc06, store.gaugeKind)
        assertFalse(store.gaugeCapabilities.sustainsBackgroundStreaming)
    }

    /// Leaving demo mode must return to the SELECTED gauge, not always to the Progressor.
    @Test
    fun leavingDemoModeReturnsToTheSelectedGauge() {
        val kinds = InMemoryGaugeKindStore()
        kinds.save(GaugeKind.climbro)

        val store = DeviceStore(
            useMock = true,
            scope = inertScope(),
            kindStore = kinds,
            clientFactory = GaugeClientFactory { FakeGaugeClient(it) },
            clock = FakeClock(),
            mockFactory = { FakeGaugeClient(GaugeKind.progressor) },
        )
        store.useMockDevice(false, MockForceProfile.clean)

        assertEquals(GaugeKind.climbro, store.gaugeKind)
        assertFalse(store.isMock)
    }

    // MARK: - The synthetic sample clock

    /// **The wrap is deliberate, not a limit to work around.** Truncating host uptime to
    /// `UInt32` reproduces the Tindeq's ~71.6-minute rollover so the whole app keeps ONE
    /// wrap rule, already handled everywhere with wrapping subtraction.
    @Test
    fun syntheticStampsMeasureRealTimeAcrossTheUInt32Wrap() {
        // Exactly representable uptimes, so the expected µs are exact.
        val before = SyntheticSampleClock.micros(4294.5) // 4_294_500_000 µs
        val after = SyntheticSampleClock.micros(4295.0) // 4_295_000_000 µs, wrapped

        assertEquals(4_294_500_000u, before)
        // 4_295_000_000 − 2^32 = 32_704
        assertEquals(32_704u, after)
        assertTrue(after < before, "the counter rolls over, exactly as the gauge's does")
        assertEquals(500_000u, after - before, "and the wrap-safe delta is still half a second")

        // The ordinary case, for completeness: 0.1 s of uptime is 100_000 µs of stamp.
        val first = SyntheticSampleClock.micros(12.5)
        val second = SyntheticSampleClock.micros(12.6)
        assertEquals(100_000u, second - first)
        assertTrue(second > first)
    }

    // MARK: - The app-side tare

    @Test
    fun softwareTareSubtractsTheCapturedZeroAndDiesWithTheLink() {
        val tare = SoftwareTare()

        // Nothing captured yet: readings pass through untouched.
        assertEquals(3.0, tare.value(3.0), 1e-9)
        assertTrue(tare.hasReading)

        tare.capture()
        assertEquals(3.0, tare.offset, 1e-9)
        val atZero = tare.value(3.0)
        val loaded = tare.value(8.5)
        // A load BELOW the captured zero reads negative, and must stay signed: that is what
        // a gauge zeroed under load actually reports when the load comes off.
        val unloaded = tare.value(1.0)
        assertEquals(0.0, atZero, 1e-9)
        assertEquals(5.5, loaded, 1e-9)
        assertEquals(-2.0, unloaded, 1e-9)

        // The link went away: an offset captured against one connection's zero says nothing
        // about the next one's.
        tare.reset()
        assertFalse(tare.hasReading)
        assertEquals(3.0, tare.value(3.0), 1e-9)
    }

    /// **A capture with nothing observed leaves the offset alone.** Zeroing it instead would
    /// move every later reading by the load that was on the gauge, and a tare tapped while
    /// the stream is quiet is exactly the case `TarePolicy` sends to a wake.
    @Test
    fun taringWithNoReadingYetChangesNothing() {
        val fresh = SoftwareTare()
        fresh.capture()
        assertEquals(0.0, fresh.offset, 1e-9)
        assertEquals(4.0, fresh.value(4.0), 1e-9)

        val loaded = SoftwareTare()
        loaded.value(2.0)
        loaded.capture()
        loaded.reset()
        loaded.capture()
        assertEquals(
            0.0, loaded.offset, 1e-9,
            "a reset clears the remembered reading too, so a later capture has nothing to re-apply",
        )
    }

    // MARK: - Tare liveness

    /// **The Tare button's mode and the tap's own re-check must read the same bound, or they
    /// can disagree** — a broadcast gauge's readings land seconds apart, and the old flat
    /// 0.3 s flipped the button to Wake and back "oscillating back and forth" in time with
    /// the radio (Nuri, 2026-08-17). Compared against the CONSTANT, not a literal, so this
    /// test still passes if `TarePolicy.liveReadingMaxAgeSeconds` is ever retuned.
    @Test
    fun tareReadingMaxAgeIsBroadcastAware() {
        val broadcast = DeviceStore(
            client = FakeGaugeClient(GaugeKind.whc06),
            scope = inertScope(),
            clock = FakeClock(),
        )
        assertEquals(
            3.5, broadcast.tareReadingMaxAge, 1e-9,
            "a broadcast gauge's advertisements arrive in bursty clumps, not a steady 80 Hz stream",
        )

        val progressor = DeviceStore(
            client = FakeGaugeClient(GaugeKind.progressor),
            scope = inertScope(),
            clock = FakeClock(),
        )
        assertEquals(
            TarePolicy.liveReadingMaxAgeSeconds, progressor.tareReadingMaxAge, 1e-9,
            "a connected stream keeps the Tindeq's own bound, untouched",
        )
    }

    // MARK: - Battery

    /// Two battery shapes, and they must not be forced into one: a standard-service
    /// PERCENTAGE arrives ready to use, while the Progressor's millivolts go through its own
    /// LiPo discharge curve. Pushing the ported devices through that curve would read 42 %
    /// as a voltage.
    @Test
    fun standardBatteryPercentageBypassesTheProgressorDischargeCurve() {
        val client = FakeGaugeClient(GaugeKind.entralpi)
        val store = DeviceStore(client = client, scope = inertScope(), clock = FakeClock())
        assertEquals(GaugeKind.entralpi, store.gaugeKind, "the injected client declares the kind")

        client.emit(ProgressorEvent.BatteryFraction(0.42))
        assertEquals(0.42, store.batteryFraction ?: -1.0, 1e-9)

        client.emit(ProgressorEvent.Battery(3700u))
        assertEquals(
            ProgressorCodec.batteryFraction(3700u),
            store.batteryFraction ?: -1.0,
            1e-9,
        )
        assertTrue((store.batteryFraction ?: -1.0) != 0.42)
    }

    /// **Capability gating of the battery path.** Only the generic connected client can read
    /// 0x2A19, so a kind claiming the standard Battery Service without a GATT profile would
    /// advertise a battery level nothing is able to fetch — the row would sit blank forever
    /// with no way to tell that from a flat cell.
    @Test
    fun onlyConnectedKindsClaimTheStandardBatteryService() {
        for (kind in GaugeKind.entries) {
            if (!kind.capabilities.hasStandardBattery) continue
            assertNotNull(
                kind.gatt,
                "$kind claims 0x180F but has no profile for a client to read it through",
            )
        }
        assertFalse(
            GaugeKind.progressor.capabilities.hasStandardBattery,
            "the Progressor reports millivolts, not a service percentage",
        )
        assertFalse(
            GaugeKind.whc06.capabilities.hasStandardBattery,
            "a broadcast scale exposes no services at all",
        )
    }

    // MARK: - Tare capability

    /// `GattGaugeClient` does a HARDWARE tare only when the profile names both a
    /// characteristic and a payload, and falls back to arithmetic otherwise. So a kind
    /// claiming a hardware tare without those bytes would silently get the software one
    /// while capabilities said otherwise — and `hasHardwareTare` is what tells the runner
    /// whether a zero survives a reconnect.
    @Test
    fun kindsClaimingAHardwareTareCarryTheWriteThatPerformsIt() {
        for (kind in GaugeKind.entries) {
            if (!kind.capabilities.hasHardwareTare) continue
            if (kind == GaugeKind.progressor) continue // its own client, its own opcode
            val gatt = kind.gatt
            assertNotNull(
                gatt?.tareCharacteristicUUID,
                "$kind claims a hardware tare but names no characteristic to write it to",
            )
            assertNotNull(
                gatt?.tarePayload,
                "$kind names a tare characteristic but no bytes to write",
            )
        }
        // The other direction, and it is the one that catches a stale capability table: a
        // profile naming a tare characteristic means `GattGaugeClient` performs a REAL device
        // tare on it, whatever the flag says. `hasHardwareTare` must follow the codec, or the
        // table describes a device the app no longer drives that way.
        for (kind in GaugeKind.entries) {
            if (kind.gatt?.tareCharacteristicUUID == null) continue
            assertTrue(
                kind.capabilities.hasHardwareTare,
                "$kind is tared on the device, so capabilities.hasHardwareTare must be true",
            )
        }
    }

    // MARK: - Backgrounding a gauge that cannot stream in the background

    /// **No grace for a scan.** The window exists so a two-second interruption does not cost
    /// a reconnect, and it is priced for a GATT link. A broadcast scale's link is an
    /// unfiltered all-matches scan that the OS silences the moment we background: the
    /// client's own 10 s watchdog then flips the state to scanning, and a grace only acts on
    /// a CONNECTED link — so the disconnect could never fire while the most expensive scan
    /// mode there is burned on indefinitely.
    ///
    /// Both entry states matter, which is why `isBusy` is in the rule: by the time anything
    /// looks at this the scan has usually already re-armed itself.
    @Test
    fun aGaugeThatCannotStreamInTheBackgroundIsDisconnectedAtOnce() {
        for (state in listOf(
            ProgressorConnectionState.Connected,
            ProgressorConnectionState.Scanning,
        )) {
            val client = FakeGaugeClient(GaugeKind.whc06)
            val device = DeviceStore(client = client, scope = inertScope(), clock = FakeClock())
            client.setState(state)

            device.beginBackgroundGrace()

            assertFalse(device.state.isConnected, "from $state the scan must be stopped")
            assertFalse(device.state.isBusy)
            assertFalse(
                device.diagnosticEntries.any {
                    it.event == DiagnosticBreadcrumb.BackgroundDisconnectScheduled
                },
                "no grace was armed, so the ring must not claim one was",
            )
        }
    }

    /// The rule is keyed to the CAPABILITY, not to "is it a port": a connected gauge that
    /// sustains background streaming still gets the window, so the Bluetooth-drop fix
    /// survives for every device it was written for.
    ///
    @Test
    fun aGaugeThatSustainsBackgroundStreamingStillGetsTheGrace() {
        val client = FakeGaugeClient(GaugeKind.entralpi)
        val device = DeviceStore(client = client, scope = inertScope(), clock = FakeClock())
        client.setState(ProgressorConnectionState.Connected)
        assertTrue(device.gaugeCapabilities.sustainsBackgroundStreaming)

        device.beginBackgroundGrace()

        assertTrue(device.state.isConnected, "the window holds the link open, it does not drop it")
        assertTrue(device.diagnosticEntries.any {
            it.event == DiagnosticBreadcrumb.BackgroundDisconnectScheduled
        })
        device.cancelBackgroundGrace()
        assertTrue(device.state.isConnected)
    }

    /// The teardown above must UNDO ITSELF on foreground: "connecting" a broadcast gauge is
    /// only scanning — no dialog, no write, no pairing — so resuming automatically is safe,
    /// and without it every app switch costs a manual Connect tap, the exact reconnect churn
    /// the Tindeq's grace exists to avoid.
    @Test
    fun aBroadcastScanTornDownByBackgroundingResumesItselfOnForeground() {
        val client = FakeGaugeClient(GaugeKind.whc06)
        val device = DeviceStore(client = client, scope = inertScope(), clock = FakeClock())
        client.setState(ProgressorConnectionState.Connected)

        device.beginBackgroundGrace()
        assertFalse(device.state.isConnected)

        device.cancelBackgroundGrace() // what the foreground lifecycle handler calls
        assertTrue(device.state.isConnected, "the scan must stand back up without a tap")
    }

    /// An EXPLICIT disconnect is a decision, not a casualty of backgrounding, and the
    /// foreground return must not overrule it.
    @Test
    fun anExplicitDisconnectIsNotOverruledByTheForegroundResume() {
        val client = FakeGaugeClient(GaugeKind.whc06)
        val device = DeviceStore(client = client, scope = inertScope(), clock = FakeClock())
        client.setState(ProgressorConnectionState.Connected)

        device.beginBackgroundGrace() // arms the resume
        device.disconnect() // the user's own stop clears it
        device.cancelBackgroundGrace() // next foreground
        assertFalse(device.state.isConnected)
    }

    // MARK: - Tare recovery

    /// **A tare on a crane scale must not bounce the scan.** `tareRecovery` re-sends the
    /// start command because taring a Progressor mid-stream once killed its stream for good;
    /// on a broadcast client the same call is a fresh scan, and nothing a tare does there can
    /// break anything — no write reached the scale, its advertisements never stopped, and the
    /// zero is app-side arithmetic. All it buys is a visible gap in the readings at the
    /// moment somebody asked for a clean zero.
    @Test
    fun taringABroadcastScaleDoesNotRekickTheScan() {
        val broadcast = FakeGaugeClient(GaugeKind.whc06)
        val scale = DeviceStore(client = broadcast, scope = inertScope(), clock = FakeClock())
        broadcast.setState(ProgressorConnectionState.Connected)
        scale.startStreaming(StreamStartCause.initial)
        scale.tare()

        assertEquals(
            listOf(ProgressorCommand.startWeightMeasurement, ProgressorCommand.tare),
            broadcast.commands,
            "the tare added no second start",
        )

        // The connected case is unchanged, and it is the one a hardware session earned.
        val connected = FakeGaugeClient(GaugeKind.entralpi)
        val plate = DeviceStore(client = connected, scope = inertScope(), clock = FakeClock())
        connected.setState(ProgressorConnectionState.Connected)
        plate.startStreaming(StreamStartCause.initial)
        plate.tare()

        assertEquals(
            listOf(
                ProgressorCommand.startWeightMeasurement,
                ProgressorCommand.tare,
                ProgressorCommand.startWeightMeasurement,
            ),
            connected.commands,
        )
    }

    /// Broadcast is the one shape with no wire at all, so everything a client would write has
    /// to be false for it — including the hardware tare, which is why `BroadcastGaugeClient`
    /// carries a `SoftwareTare`.
    @Test
    fun aBroadcastGaugeHasNothingToWriteTo() {
        val capabilities = GaugeKind.whc06.capabilities
        assertTrue(capabilities.isBroadcast)
        assertFalse(capabilities.hasHardwareTare)
        assertFalse(capabilities.hasDeviceClock)
        assertFalse(
            capabilities.sustainsBackgroundStreaming,
            "the OS coalesces duplicate advertisements in the background, so the scan goes silent",
        )
    }
}
