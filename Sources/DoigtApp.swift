// SPDX-License-Identifier: MPL-2.0
// Original contributions Copyright 2026 Nuri Bruner.

import SwiftData
import SwiftUI
import UIKit

@main
struct DoigtApp: App {
    @Environment(\.scenePhase) private var scenePhase

    private let container: ModelContainer
    @State private var clock: DayClock
    @State private var settings: SettingsStore
    @State private var templates: TemplateStore
    @State private var device = DeviceStore()
    #if DEBUG
    @State private var showSummaryPreview = ProcessInfo.processInfo.arguments.contains("-previewSummary")
    #endif

    init() {
        SessionActivityController.clearOrphanedActivities()
        let (container, mode) = Self.makeContainer()
        let clock = DayClock()
        // An iPad is the second device, not the one in the pocket at 19:30 — it stays
        // quiet until Settings says otherwise. The one idiom read in the app, and it only
        // seeds a preference the person can flip.
        let settings = SettingsStore(remindersDefault: UIDevice.current.userInterfaceIdiom != .pad)
        self.container = container

        #if DEBUG
        // Seeded BEFORE the store is built, so `TemplateStore.init`'s first
        // `syncDerived()` already sees the seeded world — otherwise the first frame
        // renders the pre-seed state and a headless screenshot catches it.
        DebugSeeding.applyLaunchSeeding(context: container.mainContext)
        #endif

        _clock = State(initialValue: clock)
        _settings = State(initialValue: settings)
        _templates = State(initialValue: TemplateStore(context: container.mainContext,
                                                       clock: clock, settings: settings,
                                                       storageMode: mode))
    }

    var body: some Scene {
        WindowGroup {
            Group {
                #if DEBUG
                if let phase = RunnerCuePreviewPhase.requested {
                    DebugRunnerPreview(
                        phase: phase,
                        timerOnly: ProcessInfo.processInfo.arguments.contains("-previewRunnerTimer"),
                        paused: ProcessInfo.processInfo.arguments.contains("-previewRunnerPaused"))
                } else if showSummaryPreview {
                    DebugSummaryPreview { showSummaryPreview = false }
                } else {
                    RootTabView()
                }
                #else
                RootTabView()
                #endif
            }
                .environment(clock)
                .environment(settings)
                .environment(\.weightUnit, settings.weightUnit)
                .environment(templates)
                .environment(device)
                .onChange(of: scenePhase) { _, phase in
                    device.recordScenePhase(String(describing: phase))
                    switch phase {
                    case .active:
                        // A phone left open past local midnight must flip "2 of 2 today"
                        // back to "0 of 2" without a relaunch, and a CloudKit import that
                        // merged while we were backgrounded has to be picked up here.
                        //
                        // The clock is refreshed HERE, not inside the store: a device
                        // asleep across midnight may not deliver significantTimeChange
                        // until it is active again, and the store only reacts to
                        // whatever day the clock reports. Refresh, then recompute.
                        // FIRST, before anything slower: coming back inside the window is
                        // the whole point of the grace period, and the link must survive
                        // it untouched.
                        device.cancelBackgroundGrace()
                        clock.refresh()
                        templates.refreshIfDayChanged()
                        // Re-read the battery on every return: the chip otherwise shows
                        // the level from whenever the gauge first connected, which over
                        // a long day quietly becomes a lie.
                        if device.state.isConnected { device.readBattery() }
                    case .background:
                        // THE BATTERY RULE (Nuri, 2026-08-03): leaving the app must not
                        // leave the gauge burning. The Progressor only self-sleeps ten
                        // minutes AFTER a disconnect — connected-but-idle it stays awake
                        // indefinitely, so an app swiped away with the link up drains the
                        // device until the battery dies. Background is where this belongs
                        // because it also covers "completely closed": a suspended process
                        // gets no termination callback.
                        //
                        // Disconnect, deliberately NOT the sleep opcode: sleep powers the
                        // device off and costs a physical button press to wake, which is
                        // the wrong price for a 30-second app switch. A dropped link
                        // reaches the same off state ten minutes later on its own.
                        //
                        // A mid-session background keeps the link: the runner has just
                        // paused itself and the climber is coming back; if iOS suspends
                        // us anyway the link dies on its own and the runner already
                        // waits for reconnect.
                        //
                        // **SCHEDULED, NOT IMMEDIATE** (Nuri, 2026-08-16). Disconnecting
                        // the instant we backgrounded could not tell a two-second "hey
                        // Siri" from a phone put in a bag, and charged both the same 5–6
                        // second reconnect on the way back — which is what he reported as
                        // Bluetooth dropping. His own breadcrumb logs proved it: a session
                        // he never backgrounded never dropped the link once, and the next
                        // one dropped it within a second of `Scene: background`.
                        //
                        // The rule above is NOT weakened, only delayed — see
                        // `beginBackgroundGrace`, which holds a background assertion and
                        // disconnects from its expiration handler if iOS suspends us
                        // early, and disconnects at once if that assertion is refused.
                        device.beginBackgroundGrace()
                    default:
                        break
                    }
                }
        }
        .modelContainer(container)
    }

    /// CloudKit-backed store in the App Group, with a deliberate local-only fallback:
    /// ad-hoc-signed simulator builds strip the iCloud entitlements, so the CloudKit
    /// configuration throws there — the app must still work fully offline (sync is a
    /// bonus, never a requirement). The store name "Doigt" is frozen: changing it points
    /// the app at a different file and every routine appears to have vanished.
    private static func makeContainer() -> (ModelContainer, StorageMode) {
        let schema = Schema([SessionTemplate.self, WorkoutLog.self, MaxRecord.self])

        let cloud = ModelConfiguration(
            "Doigt",
            schema: schema,
            groupContainer: .identifier(AppGroup.id),
            cloudKitDatabase: .private(AppGroup.cloudContainer)
        )
        if let container = try? ModelContainer(for: schema, configurations: [cloud]) {
            return (container, .cloud)
        }

        let local = ModelConfiguration(
            "Doigt",
            schema: schema,
            groupContainer: .identifier(AppGroup.id),
            cloudKitDatabase: .none
        )
        if let container = try? ModelContainer(for: schema, configurations: [local]) {
            return (container, .localOnly)
        }

        // Last resort: default location, no App Group (a build with BOTH entitlements
        // stripped). This is a DIFFERENT store file — routines saved to the group
        // container are not in it, so an existing user would open the app to no
        // routines at all. It must never pass silently: `.isolated` drives a warning
        // row in Settings › About.
        let fallback = ModelConfiguration("Doigt", schema: schema, cloudKitDatabase: .none)
        do {
            return (try ModelContainer(for: schema, configurations: [fallback]), .isolated)
        } catch {
            fatalError("Could not create ModelContainer: \(error)")
        }
    }

}
