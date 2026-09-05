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

    /// The first-run tour. Hosted HERE rather than inside Today, because the scrim has to
    /// cover the tab bar as well — a lit routine card above a live tab bar invites the one
    /// tap that would walk you out of the tutorial.
    @State private var tour = TourController()

    var body: some View {
        TabView(selection: $selection) {
            Tab("Today", systemImage: "figure.climbing", value: 0) { TodayView() }
            Tab("History", systemImage: "chart.xyaxis.line", value: 1) { HistoryView() }
            // The SOFT NUDGE: once the newest measured max is four weeks stale the
            // icon pulses — the schedule-free version of a benchmark reminder (Nuri,
            // 2026-08-10: "maybe after a while the menu icon pulses"). No badge, no
            // notification; someone who has never measured is never nudged. Still
            // under Reduce Motion: the tab's staleness subtitle carries the same fact.
            Tab(value: 2) {
                MaxesTab()
            } label: {
                Label("Maxes", systemImage: "scalemass.fill")
                    .symbolEffect(.pulse, options: .repeat(.continuous),
                                  isActive: templates.benchmarkNudge && !reduceMotion)
            }
            Tab("Settings", systemImage: "gearshape.fill", value: 3) { SettingsView() }
        }
        // Signature iOS 26: the bar collapses to a pill on scroll-down and returns on
        // scroll-up, handing the content the full screen while it's being read.
        .tabBarMinimizeBehavior(.onScrollDown)
        // A rolled-back write is the one failure with nowhere else to surface. The
        // builder renders `saveError` inline beside its own Save, but delete, undo,
        // reorder and "make this the one Today opens on" all fire from a menu that is
        // already gone by the time the store answers — silence there reads as "the tap
        // did nothing", which is how data-loss bugs get discovered a week late.
        //
        // `.constant(...)` would make this undismissable: the value it reads never
        // changes, so the alert re-presents itself the instant it closes. Clearing the
        // error in the setter is also what lets a SECOND failure alert again.
        .alert("Couldn't save",
               isPresented: Binding(get: { templates.saveError != nil },
                                    set: { if !$0 { templates.saveError = nil } })) {
            Button("OK", role: .cancel) {}
        } message: {
            Text(templates.saveError ?? "")
        }
        // A shared routine, arriving from outside the app. `isRoutineLink` is the cheap
        // routing question — is this ours at all — and it is asked FIRST so a link
        // belonging to some other handler is left alone rather than alerted about.
        //
        // Everything else happens elsewhere, deliberately: the URL goes into the
        // store's INBOX and `TodayView` presents it. Presenting from here was measured
        // (2026-08-19, simulator) tearing down whichever full-screen cover a descendant
        // had up — a running session died unlogged, a dirty builder lost its edits —
        // because this view and TodayView resolve to the same presenting controller.
        // Only TodayView can see its own covers, so only TodayView may present.
        //
        // Onto Today FIRST: the import lands there, so answering it while Settings is
        // on screen would otherwise leave you on a page with no trace of what happened.
        .onOpenURL { url in
            guard RoutineShare.isRoutineLink(url) else { return }
            withAnimation(Motion.state(reduceMotion)) { selection = 0 }
            templates.receiveShareLink(url)
        }
        // Ceiling only. Clamping the FLOOR forces anyone who has reduced their text
        // size back up to Large; the ceiling stays because the runner's hero numeral
        // is fixed-height. This is the app's ONE clamp — never re-clamp downstream.
        .dynamicTypeSize(...DynamicTypeSize.accessibility3)
        .tint(Accent.graphite)   // chrome is ink; bleu and red carry the signals
        .tourHost(tour, act: .intro)
        // A step that names a tab MOVES you to it. The overlay lives above the TabView, so
        // the anchors it reads are whichever tab is on screen — the switch has to happen
        // before the spotlight can find anything.
        .onChange(of: tour.requestedTab) { _, tab in
            guard let tab else { return }
            withAnimation(Motion.state(reduceMotion)) { selection = tab }
            tour.requestedTab = nil
        }
        .onChange(of: tour.current) { _, step in
            guard let tab = step?.tab, tab != selection else { return }
            withAnimation(Motion.state(reduceMotion)) { selection = tab }
        }
        // Started from Today rather than here: which act runs depends on whether a routine
        // exists, and the routine list is a `@Query` that only Today holds.
        .environment(tour)
        // Deliberately NO scenePhase observer: DoigtApp owns the single one, and a
        // second would run `clock.refresh()` + `refreshIfDayChanged()` twice per
        // activation.
    }
}
