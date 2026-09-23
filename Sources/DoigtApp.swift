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
                } else if ProcessInfo.processInfo.arguments.contains("-previewGauge") {
                    // The live gauge (Today's gauge button), opened directly and already
                    // reading — screenshots (simctl can't tap, and a gauge at 0.0 kg says
                    // nothing).
                    NavigationStack { GaugeView() }
                        .task {
                            device.connect()
                            try? await Task.sleep(for: .seconds(1.5))
                            device.startStreaming(cause: .manualMeasurement)
                        }
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
                // History-scaling work waits for the first frame — see
                // `TemplateStore.runLaunchMaintenance`. Nothing in `init` above may
                // grow with the number of sessions ever logged.
                .task { templates.runLaunchMaintenance() }
                #if DEBUG
                .onAppear { DebugInteractionDump.scheduleIfRequested() }
                .task {
                    // Rewrites the report Settings › About › Diagnostics copies to
                    // Documents/diagnostics.txt every two seconds, so an on-device issue
                    // can be read without a hand on the screen: a simulator's container,
                    // or a device via `xcrun devicectl device copy from --device <udid>
                    // --domain-type appDataContainer --domain-identifier run.nuri.doigt
                    // --source Documents/diagnostics.txt --destination <file>`. The
                    // trace's last-draw decision rides along. DEBUG only.
                    guard let docs = FileManager.default.urls(for: .documentDirectory,
                                                              in: .userDomainMask).first
                    else { return }
                    while !Task.isCancelled {
                        // The display environment first: it explains a trace that steps at
                        // the packet rate (Reduce Motion) or a screen capped at 60 Hz.
                        let env = "Env: reduceMotion=\(UIAccessibility.isReduceMotionEnabled)"
                            + " reduceTransparency=\(UIAccessibility.isReduceTransparencyEnabled)"
                            + " lowPower=\(ProcessInfo.processInfo.isLowPowerModeEnabled)"
                            + " maxFPS=\(UIScreen.main.maximumFramesPerSecond)"
                            + "\n" + device.playbackReport
                        let report = env + "\n\n" + DiagnosticReport.text(from: device.diagnosticEntries)
                            + "\n\nLast trace draw: " + TraceDrawProbe.shared.line
                            + "\n\n" + device.pipelineDiagnostics.report
                        try? report.write(to: docs.appendingPathComponent("diagnostics.txt"),
                                          atomically: true, encoding: .utf8)
                        if TraceDrawProbe.logsHead {
                            try? TraceDrawProbe.shared.headRows.write(
                                to: docs.appendingPathComponent("tracehead.csv"),
                                atomically: true, encoding: .utf8)
                        }
                        try? await Task.sleep(for: .seconds(2))
                    }
                }
                #endif
                .onChange(of: scenePhase) { _, phase in
                    device.recordScenePhase(String(describing: phase))
                    switch phase {
                    case .active:
                        // Cancel the grace FIRST, before anything slower: coming back
                        // inside the window is its whole point.
                        device.cancelBackgroundGrace()
                        // The clock is refreshed HERE, not inside the store: a device
                        // asleep across midnight may not deliver significantTimeChange
                        // until active, and the store only reacts to the clock.
                        clock.refresh()
                        templates.refreshIfDayChanged()
                        // Re-read the battery on every return, or the chip shows the
                        // level from whenever the gauge first connected.
                        if device.state.isConnected { device.readBattery() }
                    case .background:
                        // THE BATTERY RULE (Nuri, 2026-08-03): leaving the app must not
                        // leave the gauge burning. The Progressor only self-sleeps ten
                        // minutes AFTER a disconnect — connected-but-idle it stays awake
                        // indefinitely. Background is where this belongs because it also
                        // covers "completely closed": a suspended process gets no
                        // termination callback.
                        //
                        // Disconnect, NOT the sleep opcode: sleep powers the device off
                        // and costs a physical button press to wake — the wrong price for
                        // a 30-second app switch. A streaming session keeps the link.
                        //
                        // **SCHEDULED, NOT IMMEDIATE**: a 45 s grace, not weakening the
                        // rule — see `DeviceStore.beginBackgroundGrace`.
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
