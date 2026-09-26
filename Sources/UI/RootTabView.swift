// SPDX-License-Identifier: MPL-2.0
// Original contributions Copyright 2026 Nuri Bruner.

import SwiftUI

struct RootTabView: View {
    /// Read only for `saveError`. Routines themselves are never fetched here — the
    /// screens use `@Query`, which tracks CloudKit merges live.
    @Environment(TemplateStore.self) private var templates

    @Environment(\.accessibilityReduceMotion) private var reduceMotion

    @State private var selection: Int = {
        #if DEBUG
        // Headless UI verification: `-tab N` preselects a tab (simctl can't tap).
        let args = ProcessInfo.processInfo.arguments
        if let flag = args.firstIndex(of: "-tab"), flag + 1 < args.count,
           let tab = Int(args[flag + 1]), (0...3).contains(tab) {
            return tab
        }
        #endif
        return 0
    }()

    var body: some View {
        TabView(selection: $selection) {
            Tab("Today", systemImage: "figure.climbing", value: 0) {
                TodayView(onShowHistory: { selection = 1 })
            }
            Tab("History", systemImage: "chart.xyaxis.line", value: 1) { HistoryView() }
            // The SOFT NUDGE: once the newest measured max is four weeks stale the icon
            // pulses (Nuri, 2026-08-10). No badge, no notification, never for someone
            // who has not measured. Still under Reduce Motion; the tab's subtitle
            // carries the same fact.
            Tab(value: 2) {
                MaxesTab()
            } label: {
                Label("Benchmarks", systemImage: "scalemass.fill")
                    .symbolEffect(.pulse, options: .repeat(.continuous),
                                  isActive: templates.benchmarkNudge && !reduceMotion)
            }
            Tab("Settings", systemImage: "gearshape.fill", value: 3) {
                SettingsView(onShowToday: {
                    withAnimation(Motion.state(reduceMotion)) { selection = 0 }
                })
            }
        }
        // The bar collapses to a pill on scroll-down and returns on scroll-up.
        .tabBarMinimizeBehavior(.onScrollDown)
        // iPad: the top tab bar that expands into a sidebar; iPhone: the ordinary bar.
        .tabViewStyle(.sidebarAdaptable)
        // A rolled-back write with nowhere else to surface: the builder shows
        // `saveError` inline, but delete, undo, reorder and "open on this one" fire
        // from a menu already gone when the store answers, and silence reads as "the
        // tap did nothing" — how data-loss bugs surface a week late.
        //
        // Not `.constant(...)`: it would re-present the instant it closes. Clearing
        // the error in the setter also lets a SECOND failure alert again.
        .alert("Couldn't save",
               isPresented: Binding(get: { templates.saveError != nil },
                                    set: { if !$0 { templates.saveError = nil } })) {
            Button("OK", role: .cancel) {}
        } message: {
            Text(templates.saveError ?? "")
        }
        // A shared routine, arriving from outside. `isRoutineLink` is asked FIRST so
        // a link for some other handler is left alone.
        //
        // The URL goes into the store's INBOX and `TodayView` presents it. Presenting
        // from here tore down whichever full-screen cover a descendant had up (a
        // running session died unlogged, a dirty builder lost its edits), because
        // both views resolve to the same presenting controller; only TodayView can
        // see its own covers.
        //
        // Onto Today FIRST, where the import lands.
        .onOpenURL { url in
            guard RoutineShare.isRoutineLink(url) else { return }
            withAnimation(Motion.state(reduceMotion)) { selection = 0 }
            templates.receiveShareLink(url)
        }
        // Ceiling only: clamping the FLOOR forces reduced text back up to Large; the
        // ceiling protects the runner's fixed-height hero. The app's ONE clamp —
        // never re-clamp downstream.
        .dynamicTypeSize(...DynamicTypeSize.accessibility3)
        .tint(Accent.graphite)   // chrome is ink; bleu and red carry the signals
        // A session that finished but was neither saved nor discarded before the app
        // died is offered back once, at launch — see `UnsavedSessionDraft`.
        .unsavedSessionRecovery { draft in
            templates.recordSession(plan: draft.plan,
                                    template: draft.templateID.flatMap { templates.routine(id: $0) },
                                    reps: draft.reps,
                                    startedAt: draft.startedAt,
                                    finishedAt: draft.finishedAt,
                                    rpe: nil) != nil
        }
        // NO scenePhase observer: DoigtApp owns the single one; a second would run
        // `clock.refresh()` + `refreshIfDayChanged()` twice per activation.
    }
}
