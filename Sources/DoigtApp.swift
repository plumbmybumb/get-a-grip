// SPDX-License-Identifier: MPL-2.0
// Original contributions Copyright 2026 Nuri Bruner.

import SwiftData
import SwiftUI

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
        let settings = SettingsStore()
        self.container = container

        #if DEBUG
        // Seeded BEFORE the store is built, so `TemplateStore.init`'s first
        // `syncDerived()` already sees the seeded world — otherwise the first frame
        // renders the pre-seed state and a headless screenshot catches it.
        Self.applyLaunchSeeding(context: container.mainContext)
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

    #if DEBUG
    /// Headless UI verification: simctl cannot tap through a five-step builder, so the
    /// two states worth screenshotting are reachable by launch argument.
    ///
    /// This is the ONLY thing in the app that inserts a routine without a user asking.
    /// It stays behind `#if DEBUG` and behind an explicit argument for a reason:
    /// CloudKit has no uniqueness constraint, so a silent seed at launch on two devices
    /// leaves two identical routines that read as a sync bug.
    private static func applyLaunchSeeding(context: ModelContext) {
        let args = ProcessInfo.processInfo.arguments
        // Mutually exclusive, and "no routines" wins: a run that asks for the empty
        // first-run state must never get a seeded one because both flags were passed.
        if args.contains("-seedNoRoutines") {
            try? context.delete(model: SessionTemplate.self)
            try? context.save()
        } else if args.contains("-seedRoutine") {
            try? context.delete(model: SessionTemplate.self)
            context.insert(SessionTemplate(draft: RoutineDraft.starter.normalized, sortIndex: 0))
            try? context.save()
        } else if args.contains("-seedTwoRoutines") {
            // The deck state: the ritual plus a max-day routine beside it — the same
            // pair `-seedHistory` logs sessions for, so the two flags together give a
            // coherent world.
            try? context.delete(model: SessionTemplate.self)
            // A percent band on the daily, so recording a max demonstrates the
            // "targets that followed" half of the impact receipt…
            var daily = RoutineDraft.starter
            daily.plan.targetLoPercent = 0.18
            daily.plan.targetHiPercent = 0.22
            // The taper Nuri actually trains — the last crimp sets drop to 10 mm — so
            // the demo also exercises the edge SPAN ("20–10 mm") and the compact
            // glyphed stat row the span forces, not just the single-edge case.
            if daily.plan.sets.count >= 2 {
                daily.plan.sets[daily.plan.sets.count - 1].grip.edgeMM = 10
                daily.plan.sets[daily.plan.sets.count - 2].grip.edgeMM = 10
            }
            context.insert(SessionTemplate(draft: daily.normalized, sortIndex: 0))
            // The C4 ladder as a WHENEVER routine — never owed, never reminded — with
            // one TYPED kilogram band swapped onto a ramp set so the same seed also
            // demonstrates the scale-with-the-new-max offer.
            var max = RoutineDraft.maxDay
            max.plan.name = "Max pulls"
            max.plan.sets[1].targetLoPercent = nil
            max.plan.sets[1].targetHiPercent = nil
            max.plan.sets[1].targetLoKg = 25
            max.plan.sets[1].targetHiKg = 30
            context.insert(SessionTemplate(draft: max.normalized, sortIndex: 1))
            try? context.save()
        }

        // History has nothing to draw until sessions exist, and driving three weeks of
        // them by hand is not verification, it is typing.
        if args.contains("-seedHistory") {
            try? context.delete(model: WorkoutLog.self)
            try? context.delete(model: MaxRecord.self)
            seedHistory(context: context)
            seedMaxes(context: context)
            try? context.save()
        }
    }

    /// Three weeks of plausible sessions: mostly twice a day, a few single days, two rest
    /// days, and a load that drifts upward slowly — enough to exercise the month grid,
    /// the trend line and the "holding steady" copy without pretending to be real data.
    private static func seedHistory(context: ModelContext) {
        let plan = RoutineDraft.starter.normalized.plan.executable
        let slots = PlanMath.sequence(for: plan)
        let today = DayStamp.today()
        // ATTACHED to the routines seeded a moment ago, by name. They used to be written
        // with `templateID: nil`, which quietly made the seeded world incoherent in three
        // ways at once: Today's "0 of 2" counts completions BY ID and so could never
        // move, History's trend deck fell back to grouping by name and never exercised
        // the id path at all, and a rename could not be seen to propagate because there
        // was no routine for a log to resolve against. Anything unmatched stays nil,
        // which is still a state worth having — it is what a deleted routine leaves.
        let ids = idsByName(context)

        // 55 days, not 35: the month grid pages by 5-week windows, and a seed that
        // fits inside one window could never demonstrate the swipe.
        for daysAgo in stride(from: 55, through: 0, by: -1) {
            let day = today - daysAgo
            if daysAgo % 7 == 3 { continue }                    // a rest day each week
            let sessions = (daysAgo % 5 == 1) ? 1 : 2           // some days only once
            for session in 0..<sessions {
                // A slow upward drift plus a little day-to-day variation, so the trend
                // has a direction without looking like a straight line.
                let drift = Double(20 - daysAgo) * 0.08
                let wobble = Double((daysAgo * 7 + session * 3) % 5) * 0.2
                let reps = slots.map { slot in
                    RepSummary(setIndex: slot.setIndex, repIndex: slot.repIndex,
                               side: slot.side, grip: slot.grip,
                               targetSeconds: slot.holdSeconds,
                               heldSeconds: Double(slot.holdSeconds),
                               peakKg: 21 + drift + wobble,
                               avgKg: 19.5 + drift + wobble,
                               outcome: .completed)
                }
                let started = day.date().addingTimeInterval(session == 0 ? 8 * 3600 : 19 * 3600)
                context.insert(WorkoutLog(
                    plan: plan, templateID: ids[plan.name], templateName: plan.name,
                    sessionsPerDayTarget: 2, reps: reps,
                    startedAt: started,
                    finishedAt: started.addingTimeInterval(Double(PlanMath.totalSeconds(plan))),
                    day: day))
            }
        }

        // A SECOND routine on the SAME grip at max intensity, every fourth day. This is
        // the exact case the per-routine trend scope exists for: before the scope, these
        // 30 kg sessions averaged into the 20 kg dailies and the "trend" was a zigzag
        // tracking which routine ran, not how strong the fingers were getting.
        var maxPlan = SessionPlan(name: "Max pulls", sets: [SetPlan(repsPerSide: 3)])
        maxPlan.holdSeconds = 5
        maxPlan.restSeconds = 90
        // The prescription its name claims — and what makes the demo show the rung's
        // intensity ladder: a near-max band paints this card's mark alarm red while
        // the untargeted daily stays bleu.
        maxPlan.targetLoPercent = 0.85
        maxPlan.targetHiPercent = 1.0
        let maxSlots = PlanMath.sequence(for: maxPlan.executable)
        for daysAgo in stride(from: 19, through: 1, by: -4) {
            let day = today - daysAgo
            let drift = Double(20 - daysAgo) * 0.1
            let reps = maxSlots.map { slot in
                RepSummary(setIndex: slot.setIndex, repIndex: slot.repIndex,
                           side: slot.side, grip: slot.grip,
                           targetSeconds: slot.holdSeconds,
                           heldSeconds: Double(slot.holdSeconds),
                           peakKg: 31.5 + drift,
                           avgKg: 29.8 + drift,
                           outcome: .completed)
            }
            let started = day.date().addingTimeInterval(13 * 3600)
            context.insert(WorkoutLog(
                plan: maxPlan, templateID: ids[maxPlan.name], templateName: maxPlan.name,
                sessionsPerDayTarget: 2, reps: reps,
                startedAt: started,
                finishedAt: started.addingTimeInterval(Double(PlanMath.totalSeconds(maxPlan))),
                day: day))
        }
    }

    /// The routines just seeded, by name, so the logs below can point at them. Names are
    /// unique within a seed by construction; a real store cannot promise that, but this
    /// runs only behind a launch argument on a store the seed itself just wrote.
    private static func idsByName(_ context: ModelContext) -> [String: UUID] {
        let templates = (try? context.fetch(FetchDescriptor<SessionTemplate>())) ?? []
        return Dictionary(templates.map { ($0.name, $0.id) }, uniquingKeysWith: { a, _ in a })
    }

    /// The Maxes tab's world: a both-hands curve on the main grip, a hands-split pair
    /// on a second, and the benchmark day the newest test landed on. The newest record
    /// is exactly four weeks old, which is the soft nudge's threshold — so a seeded
    /// launch demonstrates the pulsing icon too.
    private static func seedMaxes(context: ModelContext) {
        let plan = RoutineDraft.starter.normalized.plan.executable
        let slots = PlanMath.sequence(for: plan)
        let today = DayStamp.today()
        let mainGrip = slots.first?.grip ?? GripSpec()

        for (index, kg) in [24.0, 26.0, 27.5, 29.0, 30.5].enumerated() {
            let day = today - (56 - index * 7)
            context.insert(MaxRecord(grip: mainGrip, kg: kg, source: .measured,
                                     side: .both,
                                     recordedAt: day.date().addingTimeInterval(10 * 3600)))
        }

        if let split = slots.map(\.grip).first(where: { $0.key != mainGrip.key }) {
            let pairs: [(left: Double, right: Double)] = [(19.0, 21.0), (20.5, 21.8)]
            for (index, pair) in pairs.enumerated() {
                let day = today - (49 - index * 21)
                let at = day.date().addingTimeInterval(10 * 3600)
                context.insert(MaxRecord(grip: split, kg: pair.left, source: .measured,
                                         side: .left, recordedAt: at))
                context.insert(MaxRecord(grip: split, kg: pair.right, source: .measured,
                                         side: .right, recordedAt: at.addingTimeInterval(600)))
            }
        }

        // The day the newest test landed reads as a benchmark day — grid filled,
        // History row present — exactly what `recordMax` would have written live.
        context.insert(WorkoutLog(logged: .benchmark, day: today - 28,
                                  at: (today - 28).date().addingTimeInterval(10 * 3600),
                                  sessionsPerDayTarget: 2))
    }
    #endif
}
