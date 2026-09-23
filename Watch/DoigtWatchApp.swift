// SPDX-License-Identifier: MPL-2.0
// Original contributions Copyright 2026 Nuri Bruner.

import SwiftData
import SwiftUI

/// The watch is a STANDALONE GAUGE HOST, not a remote for the phone. It connects to the
/// gauge, runs `SessionRunner` and writes the `WorkoutLog` itself; the phone learns via
/// CloudKit. No WatchConnectivity, so a session works with the phone at home.
///
/// Three screens and no builder: routines are made on the phone and arrive by sync. The
/// wrist gets the ritual, never the library.
///
/// **One gauge, one central.** A Progressor accepts a single Bluetooth connection, so
/// whichever device pressed Start owns it. This app connects only when a session starts,
/// so a watch cannot steal the gauge from a phone mid-session.
@main
struct DoigtWatchApp: App {
    @Environment(\.scenePhase) private var scenePhase

    private let container: ModelContainer
    private let storageMode: StorageMode
    @State private var clock: DayClock
    @State private var ledger: SessionLedger
    @State private var device = DeviceStore()

    init() {
        let (container, mode) = Self.makeContainer()
        let clock = DayClock()
        self.container = container
        self.storageMode = mode
        #if DEBUG
        // The watch simulator has no CloudKit and so no routines; the same arguments the
        // phone takes seed the same world here, before the first frame reads it.
        DebugSeeding.applyLaunchSeeding(context: container.mainContext)
        #endif
        let ledger = SessionLedger(context: container.mainContext)
        _clock = State(initialValue: clock)
        _ledger = State(initialValue: ledger)
    }

    var body: some Scene {
        WindowGroup {
            WatchRootView(storageMode: storageMode)
                .environment(clock)
                .environment(ledger)
                .environment(device)
                // The phone's one-shot training-day repair, on the wrist too, after the
                // first frame — see `SessionLedger.repairTrainingDaysIfNeeded`.
                .task { ledger.repairTrainingDaysIfNeeded(defaults: .standard) }
                .onChange(of: scenePhase) { _, phase in
                    device.recordScenePhase(String(describing: phase))
                    switch phase {
                    case .active:
                        device.cancelBackgroundGrace()
                        // A watch worn past midnight must flip "2 of 2 today" back to
                        // "0 of 2" without a relaunch — the same rule as the phone.
                        clock.refresh()
                        if device.state.isConnected { device.readBattery() }
                    case .background:
                        // THE BATTERY RULE, watch edition: with no background-task
                        // assertion to hold a grace on, the store disconnects at once —
                        // unless a session is streaming, in which case the workout
                        // session is what keeps the link alive and the runner owns it.
                        device.beginBackgroundGrace()
                    default:
                        break
                    }
                }
        }
        .modelContainer(container)
    }

    /// The same container name and the same CloudKit database as the phone — that is
    /// the entire sync story. No App Group here: groups are per platform, and nothing
    /// on the wrist reads one. A simulator build strips the iCloud entitlement, so the
    /// local rung is what `-mockDevice` runs on.
    private static func makeContainer() -> (ModelContainer, StorageMode) {
        let schema = Schema([SessionTemplate.self, WorkoutLog.self, MaxRecord.self])

        let cloud = ModelConfiguration("Doigt", schema: schema,
                                       cloudKitDatabase: .private(AppGroup.cloudContainer))
        if let container = try? ModelContainer(for: schema, configurations: [cloud]) {
            return (container, .cloud)
        }

        let local = ModelConfiguration("Doigt", schema: schema, cloudKitDatabase: .none)
        do {
            return (try ModelContainer(for: schema, configurations: [local]), .localOnly)
        } catch {
            fatalError("Could not create ModelContainer: \(error)")
        }
    }
}
