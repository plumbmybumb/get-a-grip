// SPDX-License-Identifier: MPL-2.0
// Original contributions Copyright 2026 Nuri Bruner.

import SwiftData
import SwiftUI

/// Today, on the wrist: the routines that synced from the phone, how many rounds each
/// has had today, a Start, and the live gauge. No builder, no history, no maxes — the
/// phone is where the library lives; this is the one screen the ritual needs.
struct WatchRootView: View {
    var storageMode: StorageMode

    @Environment(DayClock.self) private var clock
    @Environment(SessionLedger.self) private var ledger
    @Environment(\.modelContext) private var context
    @State private var running: RunRequest?
    #if DEBUG
    /// Headless verification: `-previewWatchGauge` opens the gauge screen on launch,
    /// because `simctl` cannot tap a watch.
    @State private var previewingGauge = ProcessInfo.processInfo.arguments.contains("-previewWatchGauge")
    #endif

    var body: some View {
        NavigationStack {
            WatchTodayList(storageMode: storageMode, day: clock.today) { request in
                running = request
            }
            #if DEBUG
            .navigationDestination(isPresented: $previewingGauge) { WatchGaugeView() }
            #endif
            // Keyed on the DAY, and on the list rather than on this view: a watch worn
            // past midnight rebuilds the today-query with the new floor, while the
            // session pushed on top — which may be mid-rest at 00:00 — is left untouched.
            .id(clock.today)
            .navigationTitle("Get a Grip")
            // A PUSH with no back button, as the system Workout app runs one — not a
            // cover, whose close button would end a session with no summary or save.
            // The only way out is End; the crown leaves the session running.
            .navigationDestination(item: $running) { request in
                WatchRunnerView(template: request.template, timerOnly: request.timerOnly)
                    .navigationBarBackButtonHidden(true)
            }
        }
        // A session that finished on the wrist but was never saved or discarded — the
        // watch came off, the battery went — is offered back once, at launch. Save
        // writes through `SessionLedger`, the watch summary's own path.
        .unsavedSessionRecovery { draft in
            ledger.recordSession(plan: draft.plan, template: routine(draft.templateID),
                                 reps: draft.reps,
                                 startedAt: draft.startedAt,
                                 finishedAt: draft.finishedAt,
                                 rpe: nil) != nil
        }
    }

    private func routine(_ id: UUID?) -> SessionTemplate? {
        guard let id else { return nil }
        let descriptor = FetchDescriptor<SessionTemplate>(predicate: #Predicate { $0.id == id })
        return (try? context.fetch(descriptor))?.first
    }
}

/// What Start asks for. `Hashable` for the push (a `PersistentModel` already is); a
/// timer-only request is a different item from a measured one so switching modes
/// re-presents.
struct RunRequest: Identifiable, Hashable {
    let template: SessionTemplate
    let timerOnly: Bool
    var id: String { "\(template.id.uuidString)-\(timerOnly ? "timer" : "gauge")" }
}

private struct WatchTodayList: View {
    var storageMode: StorageMode
    var day: DayStamp
    var onStart: (RunRequest) -> Void

    @Environment(DeviceStore.self) private var device

    /// `sortIndex`, then creation, then id — the phone's total order; see
    /// `TemplateStore.routineOrder`.
    @Query(sort: [SortDescriptor(\SessionTemplate.sortIndex),
                  SortDescriptor(\SessionTemplate.createdAt)])
    private var routines: [SessionTemplate]

    /// Today's rows only — the join for "1 of 2 today", a cheap Int predicate.
    @Query private var todayLogs: [WorkoutLog]

    init(storageMode: StorageMode, day: DayStamp, onStart: @escaping (RunRequest) -> Void) {
        self.storageMode = storageMode
        self.day = day
        self.onStart = onStart
        let floor = day.raw
        _todayLogs = Query(filter: #Predicate<WorkoutLog> { $0.dayKey >= floor },
                           sort: [SortDescriptor(\WorkoutLog.startedAt)])
    }

    var body: some View {
        Group {
            if routines.isEmpty {
                empty
            } else {
                list
            }
        }
        #if DEBUG
        // Headless verification: `-previewWatchRunner` opens the first routine's session
        // on launch (with `-previewWatchTimerOnly`, on the clock alone), because
        // `simctl` cannot tap a watch.
        .onAppear {
            let arguments = ProcessInfo.processInfo.arguments
            guard arguments.contains("-previewWatchRunner"), let first = ordered.first else { return }
            onStart(RunRequest(template: first,
                               timerOnly: arguments.contains("-previewWatchTimerOnly")))
        }
        #endif
    }

    private var list: some View {
        List {
            ForEach(ordered) { routine in
                NavigationLink {
                    WatchRoutineScreen(template: routine,
                                       completedToday: completed(routine),
                                       isDoneForToday: isDone(routine)) { timerOnly in
                        onStart(RunRequest(template: routine, timerOnly: timerOnly))
                    }
                } label: {
                    row(routine)
                }
            }
            Section {
                // A door, not just a status line: the live gauge on the wrist (2026-09-20).
                NavigationLink {
                    WatchGaugeView()
                } label: {
                    gaugeRow
                }
                .accessibilityIdentifier("watch.gauge")
            } footer: {
                if storageMode != .cloud {
                    Text("Not syncing with iCloud on this watch. Routines and sessions stay here.")
                }
            }
        }
    }

    private var empty: some View {
        VStack(spacing: 8) {
            Image(systemName: "figure.climbing")
                .font(.title2)
                .foregroundStyle(.secondary)
                .accessibilityHidden(true)
            Text("No routines yet")
                .font(.headline)
            Text("Build one on your iPhone. It syncs here on its own.")
                .font(.caption2)
                .foregroundStyle(.secondary)
                .multilineTextAlignment(.center)
        }
        .padding()
    }

    private var ordered: [SessionTemplate] {
        routines.sorted { a, b in
            if a.sortIndex != b.sortIndex { return a.sortIndex < b.sortIndex }
            if a.createdAt != b.createdAt { return a.createdAt < b.createdAt }
            return a.id.uuidString < b.id.uuidString
        }
    }

    private func row(_ routine: SessionTemplate) -> some View {
        VStack(alignment: .leading, spacing: 3) {
            HStack(alignment: .firstTextBaseline, spacing: 6) {
                Text(routine.name)
                    .font(.headline)
                    .lineLimit(1)
                Spacer(minLength: 0)
                if isDone(routine) {
                    Image(systemName: "checkmark.circle.fill")
                        .foregroundStyle(StatusTint.engaged)
                        .accessibilityLabel(Text("Done for today"))
                }
            }
            Text(subtitle(routine))
                .font(.caption2)
                .foregroundStyle(.secondary)
                .lineLimit(1)
                .monospacedDigit()
        }
    }

    /// The same words the phone's card uses: minutes, then rounds. A whenever routine
    /// is never owed, so it says so instead of counting toward a target it has not got.
    private func subtitle(_ routine: SessionTemplate) -> String {
        let minutes = max(1, Int((Double(routine.estimatedSeconds) / 60).rounded()))
        let done = completed(routine)
        let tally: String
        if routine.isOnDemand {
            tally = done == 0 ? String(localized: "whenever") : String(localized: "done \(done) today")
        } else {
            tally = String(localized: "\(done) of \(max(1, routine.sessionsPerDay)) today")
        }
        return String(localized: "≈\(minutes) min · \(tally)")
    }

    /// `hangCompletions` plus the hand-logged hangs, exactly as `TemplateStore.completed`
    /// adds them: a hang logged by hand on the phone belongs to no routine and counts
    /// for every routine's day.
    private func completed(_ routine: SessionTemplate) -> Int {
        (todayLogs.hangCompletions(on: day)[routine.id] ?? 0) + todayLogs.unattributedHangs(on: day)
    }

    /// A climb or a benchmark SETTLES the day for every routine — the phone's rule.
    private func isDone(_ routine: SessionTemplate) -> Bool {
        let settled = todayLogs.climb(on: day) != nil || todayLogs.benchmark(on: day)
        if routine.isOnDemand { return settled || completed(routine) > 0 }
        return settled || completed(routine) >= max(1, routine.sessionsPerDay)
    }

    private var gaugeRow: some View {
        HStack(spacing: 8) {
            Image(systemName: "dot.radiowaves.left.and.right")
                .foregroundStyle(device.state.isConnected ? StatusTint.engaged : Color.secondary)
                .accessibilityHidden(true)
            VStack(alignment: .leading, spacing: 2) {
                Text(device.isMock ? String(localized: "Demo gauge") : device.gaugeKind.displayName)
                    .font(.footnote)
                Text(device.state.label)
                    .font(.caption2)
                    .foregroundStyle(.secondary)
            }
        }
        .accessibilityElement(children: .combine)
    }
}

/// One routine, before Start: what it is, how today stands, and the two doors — with
/// the gauge, or on the clock alone. Connecting happens on Start, never here, so
/// opening a routine to look at it cannot take the gauge away from a phone mid-session.
struct WatchRoutineScreen: View {
    let template: SessionTemplate
    var completedToday: Int
    var isDoneForToday: Bool
    var onStart: (_ timerOnly: Bool) -> Void

    @Environment(DeviceStore.self) private var device

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 10) {
                Text(template.summaryLine)
                    .font(.footnote)
                    .foregroundStyle(.secondary)
                    .fixedSize(horizontal: false, vertical: true)
                Text(tally)
                    .font(.caption2)
                    .foregroundStyle(.secondary)
                    .monospacedDigit()
                Button {
                    onStart(false)
                } label: {
                    Label(isDoneForToday ? String(localized: "Start another") : String(localized: "Start"),
                          systemImage: "play.fill")
                        .frame(maxWidth: .infinity)
                }
                .buttonStyle(.borderedProminent)
                .tint(StatusTint.engaged)
                .accessibilityIdentifier("watch.start")
                Button {
                    onStart(true)
                } label: {
                    Label("Timer only", systemImage: "timer")
                        .frame(maxWidth: .infinity)
                }
                .buttonStyle(.bordered)
                .accessibilityIdentifier("watch.startTimerOnly")
                gaugeLine
                // Always compiled in, like the phone's — see `DeviceStore.useMockDevice`.
                if !device.state.isConnected, !device.isMock {
                    Button("Try demo mode") { device.useMockDevice(true) }
                        .buttonStyle(.plain)
                        .font(.caption2)
                        .foregroundStyle(.secondary)
                }
            }
        }
        .navigationTitle(template.name)
    }

    private var tally: String {
        if template.isOnDemand {
            return completedToday == 0 ? String(localized: "A whenever routine — nothing owed today")
                                       : String(localized: "Done \(completedToday) times today")
        }
        return String(localized: "\(completedToday) of \(max(1, template.sessionsPerDay)) sessions done today")
    }

    private var gaugeLine: some View {
        Label {
            let gauge = device.isMock ? String(localized: "Demo gauge") : device.gaugeKind.displayName
            Text("\(gauge) · \(device.state.label)")
        } icon: {
            Image(systemName: "dot.radiowaves.left.and.right")
                .foregroundStyle(device.state.isConnected ? StatusTint.engaged : Color.secondary)
        }
        .font(.caption2)
        .foregroundStyle(.secondary)
    }
}
