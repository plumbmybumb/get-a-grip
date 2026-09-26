// SPDX-License-Identifier: MPL-2.0
// Original contributions Copyright 2026 Nuri Bruner.

import SwiftData
import StoreKit
import SwiftUI

/// The ritual front door — the screen Nuri sees every single day, twice.
///
/// What you do today is the first thing on screen and one tap from starting; there is
/// no library to assemble. The card's plan row opens a preparation overview; its Edit
/// action and the card menu lead to the builder.
///
/// No footer disclaimer: nothing here is legally load-bearing. The Tindeq
/// non-affiliation line lives in Settings › About.
struct TodayView: View {
    var onShowHistory: () -> Void = {}
    /// Sorted the same way as `TemplateStore`, so the two never disagree about which
    /// routine is primary. The `id` tiebreak lives in `ordered` because `UUID` is not
    /// `Comparable` for a `SortDescriptor` — and without it, two devices that both reorder
    /// produce duplicate `sortIndex` values over CloudKit and render in opposite orders.
    @Query(sort: [SortDescriptor(\SessionTemplate.sortIndex),
                  SortDescriptor(\SessionTemplate.createdAt)])
    private var routines: [SessionTemplate]

    @Environment(DeviceStore.self) private var device
    @Environment(TemplateStore.self) private var templates
    @Environment(SettingsStore.self) private var settings
    @Environment(\.requestReview) private var requestReview
    @State private var sessionSavesSeen = 0
    @Environment(DayClock.self) private var clock
    @Environment(\.scenePhase) private var scenePhase
    @Environment(\.accessibilityReduceMotion) private var reduceMotion

    @State private var chosenRoutineID: UUID?
    @State private var chosenOnDay: DayStamp?
    /// Which card the deck is resting on. A MIRROR of the selection rungs, not a fourth
    /// rung: programmatic moves (a new day, a deleted routine) write it from `selected`,
    /// and only a settle on a card that ISN'T `selected` — a person's swipe — writes back
    /// into `chosenRoutineID`. So a day change still re-asserts the primary without the
    /// sync counting as "an explicit tap made today".
    @State private var deckPosition: UUID?
    /// The trailing "New routine" card's scroll identity. Static so it survives the view
    /// being recreated; a per-render id would drop the scroll target under the reader.
    private static let ghostID = UUID()
    @State private var builder: BuilderMode?
    @State private var overview: RoutineOverviewRequest?
    @State private var pendingOverviewEditID: UUID?

    @State private var running: SessionTemplate?
    /// The live gauge as a full-screen cover. Some people use the gauge and no routine
    /// at all (2026-09-20), so it is one tap from Today, like History's and Maxes' doors.
    @State private var showingGauge = false
    /// Whether the presented session was started without a gauge. Not part of the
    /// routine (the same routine runs both ways), so it rides alongside `running`.
    @State private var runningTimerOnly = false
    /// Set by "Save and start training", consumed in the sheet's `onDismiss`: a cover
    /// presented in the same runloop turn as a sheet's dismissal is routinely dropped,
    /// which would lose the one tap that mattered most.
    @State private var pendingStartID: UUID?
    @State private var startTick = 0
    @State private var selectTick = 0
    @State private var undoTick = 0
    @State private var loggingSession = false
    /// FROZEN at tap time — see `RoutineShareRequest` — so a swipe, edit or CloudKit merge
    /// cannot change the code under a camera pointed at it.
    @State private var shareRequest: RoutineShareRequest?
    @State private var shareFailed = false
    @State private var scanningRoutine = false
    @State private var scannedRoutine: String?
    /// The scanned routine ON SCREEN, claimed from the store's inbox by
    /// `drainImportInbox()`. Here, not in `RootTabView` where the link arrives, because
    /// this view owns every presentation a scan can collide with: presenting from the root
    /// over the builder or runner cover tore the cover down (a session died unlogged, a
    /// dirty builder lost its edits).
    @State private var importPreview: ImportRequest?
    @State private var importError: String?
    @Namespace private var zoom

    /// Nothing this view presents is up or about to be. `pendingStartID` counts as "about
    /// to be": draining ahead of it would slide the import sheet in front of Save-and-start.
    private var canPresentImport: Bool {
        builder == nil && running == nil && pendingStartID == nil
            && overview == nil && pendingOverviewEditID == nil
            && !loggingSession && shareRequest == nil && !scanningRoutine
            && !showingGauge && importPreview == nil && importError == nil
    }

    /// The single door from the store's inbox to the screen, called on arrival and from
    /// every presentation's `onDismiss` — the same deferral as `startPendingRoutine()`.
    /// Once, on Today, after the fifth saved session has closed — see `ReviewRequestPolicy`.
    /// Decided HERE, not in the summary: the settled screen, a beat after the cover is gone,
    /// never from a tap (it may show nothing). Read off the store's save counter, so a
    /// discarded session never counts.
    private func askForReviewIfDue() {
        guard templates.sessionsSavedThisLaunch > sessionSavesSeen else { return }
        sessionSavesSeen = templates.sessionsSavedThisLaunch
        guard ReviewRequestPolicy.shouldAsk(hangSessionsLogged: templates.hangSessionCount(),
                                            alreadyAsked: settings.reviewRequested) else { return }
        settings.reviewRequested = true
        Task { @MainActor in
            try? await Task.sleep(for: .seconds(1))
            requestReview()
        }
    }

    private func drainImportInbox() {
        guard canPresentImport else { return }
        if let message = templates.claimPendingImportError() {
            importError = message
        } else if let draft = templates.claimPendingImport() {
            importPreview = ImportRequest(draft: draft)
        }
    }

    var body: some View {
        // Folded ONCE per evaluation and handed down — see `TodaySelection`.
        let deck = makeDeck()
        // 12 rather than the house 22: four blocks that must fit under a large
        // title without scrolling, and the gaps were the easiest points to find.
        ScreenScaffold(title: String(localized: "Today"), subtitle: dateLine, spacing: 12,
                       fitsOnePage: true, gridsOnWideScreens: true) {
            header(deck)
                .staggerIn(0)
                .toolbar {
                    ToolbarItem(placement: .topBarTrailing) {
                        Button("Live gauge", systemImage: "gauge.with.dots.needle.bottom.50percent") {
                            showingGauge = true
                        }
                        .labelStyle(.iconOnly)
                        .tint(Accent.graphite)
                        .accessibilityHint("Opens the live gauge")
                        .accessibilityIdentifier("today.gauge")
                    }
                }

            // The deck exists once ANY routine does: with one routine its neighbour is
            // the ghost card, an honest peek, and otherwise "New routine" lived only in
            // the ⋯ menu (Nuri, 2026-08-10).
            Group {
                if deck.ordered.isEmpty {
                    emptyCard
                } else if sizeClass == .regular {
                    // A wide window shows the routines SIDE BY SIDE: the peek answered a screen
                    // that fits one card, and a tablet fits two.
                    routineGrid(deck)
                } else {
                    routineDeck(deck)
                }
            }
            // Motion.state: a deck appearing is a state change nobody flicked, so no bounce.
            .animation(Motion.state(reduceMotion), value: deck.ordered.count)
            .staggerIn(1)

            // With no routine there is no ritual for the strip to describe.
            if !deck.ordered.isEmpty {
                ConsistencyCard(days: templates.consistency,
                                onLogClimb: { loggingSession = true },
                                onShowHistory: onShowHistory)
                    .staggerIn(2)
            }
        }
        // The builder is FULL SCREEN, not a sheet: a sheet's top edge sits ~50 pt below
        // the Dynamic Island, which the grip panel must reach, and the builder needs the
        // 50 pt. A COVER and never a push: nothing touches SwiftData until Save, so
        // Cancel IS undo and a back chevron would promise save-as-you-go.
        .sheet(item: $overview, onDismiss: {
            // Let the sheet finish dismissing before presenting the editor. A queued
            // import waits for the edit, as it waits for a runner.
            if let id = pendingOverviewEditID {
                pendingOverviewEditID = nil
                if ordered.contains(where: { $0.id == id }) { builder = .edit(id) }
            }
            drainImportInbox()
        }) { request in
            RoutineOverviewSheet(request: request, onEdit: {
                pendingOverviewEditID = request.id
                overview = nil
            }, onClose: { overview = nil })
        }
        .fullScreenCover(item: $builder,
                         onDismiss: { startPendingRoutine(); drainImportInbox() }) { mode in
            // Closing is OURS: the builder must not read `@Environment(\.dismiss)`, which
            // re-ran its whole document on every keystroke. See `BuilderDocument`.
            RoutineBuilderView(mode: mode, onClose: { builder = nil }) { routineID, startNow in
                // Whatever the builder just produced is what you meant to be looking at.
                chosenRoutineID = routineID
                chosenOnDay = clock.today
                if startNow { pendingStartID = routineID }
            }
            .navigationTransition(.zoom(sourceID: mode.zoomID, in: zoom))
        }
        .fullScreenCover(item: $running, onDismiss: { drainImportInbox(); askForReviewIfDue() }) { routine in
            RunnerView(template: routine, timerOnly: runningTimerOnly)
                .onAppear { templates.noteSessionStarted(routine) }
        }
        // A COVER, like the runner, not a push: the phone is on a bench and the graph
        // wants the whole screen. Its own stack, for the inline title and Done.
        .fullScreenCover(isPresented: $showingGauge, onDismiss: { drainImportInbox() }) {
            NavigationStack { GaugeView(presentedAsCover: true) }
        }
        #if DEBUG
        // `initial: true`: the routine list is a `@Query`, unknowable on the first frame,
        // so `onAppear` would find no routine to open or start.
        .onChange(of: deck.ordered.count, initial: true) { _, _ in
            // Headless verification: `-previewBuilder` opens the first routine's editor
            // (`simctl` cannot tap).
            if ProcessInfo.processInfo.arguments.contains("-previewBuilder"),
               builder == nil, let first = ordered.first {
                builder = .edit(first.id)
            }
            // `-previewBuilderNew`: the create door (a new routine), for headless screenshots.
            if ProcessInfo.processInfo.arguments.contains("-previewBuilderNew"), builder == nil {
                builder = ordered.isEmpty ? .firstRun : .addAnother
            }
            // `-startFirstRoutine`: the "Connect and start" tap for a headless run; with
            // `-mockDevice`, a whole measured session through the real store and runner.
            if ProcessInfo.processInfo.arguments.contains("-startFirstRoutine"),
               running == nil, let first = ordered.first {
                start(first)
            }
        }
        #endif
        .sheet(isPresented: $loggingSession, onDismiss: { drainImportInbox() }) {
            SessionLogSheet(onClose: { loggingSession = false })
        }
        .sheet(item: $shareRequest, onDismiss: { drainImportInbox() }) { request in
            RoutineShareSheet(request: request) { shareRequest = nil }
        }
        .sheet(isPresented: $scanningRoutine, onDismiss: {
            // Route only after the camera sheet has dismissed; the inbox owns
            // validation, errors and the preview.
            if let raw = scannedRoutine {
                scannedRoutine = nil
                if let url = URL(string: raw) {
                    templates.receiveShareLink(url)
                } else {
                    importError = String(localized: "This link isn't a routine from Get a Grip.")
                }
            }
            drainImportInbox()
        }) {
            RoutineScannerSheet(onScanned: { raw in
                scannedRoutine = raw
                scanningRoutine = false
            }, onClose: { scanningRoutine = false })
        }
        // Fires when a routine cannot become a WORKING code (no pulls, or past the
        // 50-set import ceiling). Rare — which is why a silent no-op would read as a
        // missed tap.
        .alert("Couldn't share", isPresented: $shareFailed) {
            Button("OK", role: .cancel) {}
        } message: {
            Text("Couldn't build a share code for this routine.")
        }
        // The import inbox's two outlets. Every presentation above drains again on
        // dismissal, so a scan that landed mid-cover appears once the screen is free.
        .sheet(item: $importPreview, onDismiss: { drainImportInbox() }) { request in
            RoutineImportSheet(draft: request.draft)
        }
        // Non-constant binding, same reason as the saveError alert in RootTabView; the
        // setter drains so an import queued behind the error appears once it is read.
        .alert("Couldn't import",
               isPresented: Binding(get: { importError != nil },
                                    set: { if !$0 { importError = nil; drainImportInbox() } })) {
            Button("OK", role: .cancel) {}
        } message: {
            Text(importError ?? "")
        }
        .onChange(of: templates.pendingImport) { _, incoming in
            if incoming != nil { drainImportInbox() }
        }
        .onChange(of: templates.pendingImportError) { _, incoming in
            if incoming != nil { drainImportInbox() }
        }
        // A cold-launch link can land before the observers above exist; sweep it.
        .onAppear { drainImportInbox() }
        .safeAreaInset(edge: .bottom) { undoBar }
        .sensoryFeedback(.impact(weight: .medium, intensity: 0.7), trigger: startTick)
        .sensoryFeedback(.selection, trigger: selectTick)
        .sensoryFeedback(.success, trigger: undoTick)
        .onChange(of: scenePhase) { _, phase in
            // A new day always re-asserts `upNext` — "what meets you every time you
            // open the app". (Within a day, becoming active refreshes which routine is
            // calling, so answering a reminder lands on the routine that sent it.)
            guard phase == .active, chosenOnDay != clock.today else { return }
            chosenRoutineID = nil
            chosenOnDay = nil
        }
    }

    // MARK: - Header

    private func header(_ deck: Deck) -> some View {
        HStack(spacing: 10) {
            DeviceChip()
            Spacer(minLength: 8)
            if let next = deck.selectedSummary?.nextReminder {
                Label(next.displayText(), systemImage: "bell")
                    .font(.system(.footnote))
                    .monospacedDigit()
                    .foregroundStyle(Ink.tertiary)
                    // "19:30" is fine to read and too terse to hear.
                    .accessibilityLabel("Next reminder at \(next.displayText())")
            }
        }
    }

    /// "Monday 3 August" — a daily-ritual screen says what day it is. Read off `DayClock`,
    /// not `Date.now`, so it re-renders when the day rolls under an open phone.
    private var dateLine: String {
        clock.today.date().formatted(.dateTime.weekday(.wide).day().month(.wide))
    }

    // MARK: - The deck

    /// Size CLASS, never the idiom: an iPad in Slide Over is a phone and keeps the deck.
    @Environment(\.horizontalSizeClass) private var sizeClass

    /// The regular-width layout: the same cards and ghost, two wide where the window
    /// allows. No pin and no settle — every routine is on screen, and the up-next border
    /// still says which one Today opens on.
    private func routineGrid(_ deck: Deck) -> some View {
        CardGrid {
            ForEach(deck.cards) { card in
                routineCard(card, deck: deck)
            }
            newRoutineGhost
                .id(Self.ghostID)
        }
    }

    /// The routines as a PAGED DECK — Music's Top Picks, not a browse feed. The card IS the
    /// chooser: the next routine peeks from the trailing edge, swiping to a card chooses it
    /// (same rung, same day-scoped pin as the old rail tap), and a new day or a fresher
    /// reminder snaps back to `upNext`, the card wearing the border. It replaced a rail of
    /// name-chips that only NAMED the card already showing. Accepted cost: sibling names
    /// need a swipe, which at two or three routines the peek and done-dots cover.
    private func routineDeck(_ deck: Deck) -> some View {
        ScrollView(.horizontal) {
            HStack(alignment: .top, spacing: 8) {
                ForEach(deck.cards) { card in
                    routineCard(card, deck: deck)
                        .containerRelativeFrame(.horizontal)
                }
                // The quiet door at the end of the deck: the next thing waits BEHIND the
                // things you have, so creating never weighs the same as the ritual.
                newRoutineGhost
                    .containerRelativeFrame(.horizontal)
                    .id(Self.ghostID)
            }
            .scrollTargetLayout()
        }
        // `.always`: one card per gesture, however hard the flick. This is a CHOOSER
        // where every settle is a pick (a pin and a haptic), not a browsing carousel.
        .scrollTargetBehavior(.viewAligned(limitBehavior: .always))
        // UNCLIPPED, for the long press. UIKit parents the context-menu lift (the
        // card scaled ~2.5 % with a shadow) inside the nearest scroll view, which is
        // one card tall; clipped, every lift sliced the card's corners flat and cut
        // the shadow into a hard screen-wide band (Nuri's phone, 2026-09-19).
        // Confirmed with `LongPressLiftUITests` + `simctl io recordVideo` and the
        // `-dumpInteractions` view dump. Safe: the deck is full-bleed and the row is
        // the tallest card, so nothing else can spill.
        .scrollClipDisabled()
        .scrollPosition(id: $deckPosition)
        .scrollIndicators(.hidden)
        // Full-bleed so the neighbour peeks at the SCREEN edge; content margins put a
        // settled card back on the house grid. Trailing margin is 8 wider than the
        // grid: peek = trailing margin − card gap, and 12 pt of neighbour read as a
        // hairline where 20 reads as a card. The last card still lands on the grid.
        .padding(.horizontal, -Metrics.hPadding)
        .contentMargins(.leading, Metrics.hPadding, for: .scrollContent)
        .contentMargins(.trailing, Metrics.hPadding + 8, for: .scrollContent)
        // Placed, never animated, on first layout.
        .onAppear { deckPosition = deck.selectedID }
        // Programmatic re-selection: a day rollover, a save, a CloudKit merge deleting
        // the card. Never loops: a settle on `selected` is filtered below, and pinning
        // makes `selected` equal the settle target.
        .onChange(of: deck.selectedID) { _, id in
            guard let id, deckPosition != id else { return }
            withAnimation(Motion.state(reduceMotion)) { deckPosition = id }
        }
        // A settle on a swiped-to card is the old rail tap. The `ordered` check keeps
        // the ghost from being "chosen" — parking on it is browsing, and must not
        // survive as a stale rung-1 pin.
        .onChange(of: deckPosition) { _, id in
            guard let id, id != deck.selectedID,
                  deck.ordered.contains(where: { $0.id == id }) else { return }
            chosenRoutineID = id
            selectTick += 1
            chosenOnDay = clock.today
        }
    }

    /// NOT a `MaterialCard`: a hairline outline reads as "provisional", like an unselected
    /// chip — a routine that could exist, next to ones that do.
    private var newRoutineGhost: some View {
        VStack(spacing: 12) {
            Button { builder = .addAnother } label: {
                VStack(spacing: 10) {
                    Image(systemName: "plus")
                        .font(.system(.title2, weight: .medium))
                        .foregroundStyle(Ink.secondary)
                    Text("New routine")
                        .font(.system(.subheadline, weight: .semibold))
                        .foregroundStyle(Ink.primary)
                    Text("For rest days, max days and more.")
                        .font(.system(.footnote))
                        .foregroundStyle(Ink.tertiary)
                        .multilineTextAlignment(.center)
                }
                .frame(maxWidth: .infinity, maxHeight: .infinity)
                .padding(.vertical, 12)
                .contentShape(RoundedRectangle(cornerRadius: Metrics.radiusCard, style: .continuous))
            }
            .buttonStyle(PressFeedbackButtonStyle())
            .accessibilityLabel("New routine")
            .accessibilityHint("Opens the routine builder.")
            .matchedTransitionSource(id: BuilderMode.addAnother.zoomID, in: zoom)
            scanRoutineButton
        }
        .padding(16)
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .background(
            RoundedRectangle(cornerRadius: Metrics.radiusCard, style: .continuous)
                .strokeBorder(Ink.tertiary.opacity(0.35), lineWidth: 1)
        )
    }

    private var scanRoutineButton: some View {
        Button { scanningRoutine = true } label: {
            Label("Scan a routine", systemImage: "qrcode.viewfinder")
                .font(.system(.footnote, weight: .medium))
                .foregroundStyle(Ink.secondary)
                .padding(.horizontal, 12)
                .frame(minHeight: 44)
                .contentShape(.rect)
        }
        .buttonStyle(PressFeedbackButtonStyle())
        .frame(maxWidth: .infinity)
    }

    // MARK: - Cards

    /// VALUE-COMPARED (`.equatable()`): with eleven closures the card never compared equal,
    /// so every Today render re-ran every card's body. See `RoutineCard.==`.
    private func routineCard(_ card: Deck.Card, deck: Deck) -> some View {
        let routine = card.routine
        return RoutineCard(
            summary: card.summary,
            completionText: templates.completionText(routine),
            // Only where there are siblings to distinguish.
            isUpNext: deck.ordered.count > 1 && routine.id == deck.upNextID,
            deviceState: device.state,
            battery: device.batteryFraction,
            onStart: { start(routine) },
            onStartTimerOnly: { start(routine, timerOnly: true) },
            onEdit: { builder = .edit(routine.id) },
            onOverview: {
                overview = RoutineOverviewRequest(id: routine.id, plan: routine.plan,
                                                  summary: templates.summary(for: routine))
            },
            onDuplicate: { _ = templates.duplicate(routine) },
            onShare: { share(routine) },
            // Zooms out of the ghost card when the deck exists; from the menu there is
            // no source on screen and it degrades to the standard presentation.
            onNew: { builder = .addAnother },
            onMakePrimary: { templates.makePrimary(routine) },
            onDelete: { _ = templates.delete(routine) },
            onDemo: { device.useMockDevice(true) }
        )
        .equatable()
        .matchedTransitionSource(id: routine.id.uuidString, in: zoom)
    }

    /// Exactly ONE button. The builder opens BLANK (Nuri, 2026-08-10 and 2026-08-19), so the
    /// line under the heading promises that and nothing more. It once described a six-set
    /// prefill and kept saying so for a month after the prefill left, through the 1.0.3
    /// App Store build. Copy that describes a mechanism has to move with the mechanism.
    private var emptyCard: some View {
        MaterialCard {
            VStack(alignment: .leading, spacing: 12) {
                CapsLabel(String(localized: "YOUR ROUTINE"))

                FingerGlyph(fingers: .four, position: .halfCrimp, dot: 12, gap: 6,
                            tint: Ink.tertiary.opacity(0.55))
                    .accessibilityHidden(true)

                Text("Build your hang routine")
                    .font(.system(.title3, weight: .semibold))
                    .foregroundStyle(Ink.primary)
                    .fixedSize(horizontal: false, vertical: true)

                Text("Name it, add your grips and timing, and set reminders.")
                    .font(.system(.footnote, weight: .medium))
                    .foregroundStyle(Ink.secondary)
                    .fixedSize(horizontal: false, vertical: true)

                PrimaryGlassButton(title: String(localized: "Build my routine"), tint: Accent.graphite) {
                    builder = .firstRun
                }

                scanRoutineButton

                if !device.state.isConnected {
                    // Answers "do I need the hardware first?" exactly when it occurs.
                    Text("You can connect your gauge later.")
                        .font(.system(.caption))
                        .foregroundStyle(Ink.tertiary)
                }
            }
        }
        .matchedTransitionSource(id: "build-routine", in: zoom)
    }

    // MARK: - Bottom chrome

    @ViewBuilder
    private var undoBar: some View {
        VStack(spacing: 0) {
            if let deleted = templates.lastDeleted {
                UndoBar(message: undoMessage(sessions: deleted.sessions.count)) {
                    templates.undoDelete()
                    undoTick += 1
                }
                .transition(.move(edge: .bottom).combined(with: .opacity))
            }
        }
        // 8, not 4: a reach for Undo could land on the tab strip.
        .padding(.bottom, 8)
        .animation(Motion.state(reduceMotion),
                   value: templates.lastDeleted)
    }

    /// Names the sessions because nobody expects them on the line: a routine's history
    /// goes with it (`TemplateStore.delete`), and "Routine deleted" alone undersells it.
    private func undoMessage(sessions: Int) -> String {
        switch sessions {
        case 0: String(localized: "Routine deleted")
        case 1: String(localized: "Routine and its session deleted")
        default: String(localized: "Routine and its \(sessions) sessions deleted")
        }
    }

    // MARK: - Selection

    /// The store's TOTAL order, re-applied for the `id` tiebreak `@Query` cannot express,
    /// so the two never disagree about which one is primary.
    private var ordered: [SessionTemplate] {
        routines.sorted { a, b in
            // Rituals lead the deck; whenever-routines sit behind them (Nuri,
            // 2026-08-10). Within each group the user's own order holds.
            if a.isOnDemand != b.isOnDemand { return !a.isOnDemand }
            if a.sortIndex != b.sortIndex { return a.sortIndex < b.sortIndex }
            if a.createdAt != b.createdAt { return a.createdAt < b.createdAt }
            return a.id.uuidString < b.id.uuidString
        }
    }

    /// Everything the body asks about the routines, folded once per evaluation.
    private struct Deck {
        let ordered: [SessionTemplate]
        /// Rungs 2–4 — see `TodaySelection.upNextID`. Split from `selectedID` because the
        /// border ignores rung 1: swipe away to browse and the border stays on the called
        /// card, which makes it information rather than decoration on whatever is in front.
        let upNextID: UUID?
        /// Four rungs, in order:
        /// 1. an explicit tap or swipe made TODAY — cleared by a day change, never
        ///    persisted;
        /// 2. the routine whose reminder CALLED most recently;
        /// 3. the routine started today on this device, so doing the rest-day one this
        ///    morning means the evening open shows it again;
        /// 4. the primary (lowest `sortIndex`).
        ///
        /// Rung 4 is also what self-heals when a CloudKit merge deletes the chosen
        /// routine: rung 1 simply misses and the rule falls through.
        let selectedID: UUID?
        /// One per routine, in `ordered`'s order, with its summary (built once, shared by card
        /// and header).
        let cards: [Card]

        struct Card: Identifiable {
            let routine: SessionTemplate
            let summary: RoutineSummary
            var id: UUID { routine.id }
        }

        var selectedSummary: RoutineSummary? {
            selectedID.flatMap { id in cards.first { $0.id == id }?.summary }
        }
    }

    /// "Calling" is computed from the SCHEDULE, not the delivered-notification list: it is
    /// synchronous (the deck must not jump a frame after appearing), it works with
    /// notifications off, and the planner's suppression of trained days is mirrored by
    /// the `isDoneForToday` filter — a finished routine has been answered.
    ///
    /// Freshness rides on re-render: this view reads `scenePhase`, so returning to the app
    /// (the notification-tap path) recomputes it and the deck scrolls to whoever called. A
    /// crossing while the app sits open is picked up on the next render.
    private func makeDeck() -> Deck {
        let ordered = self.ordered
        let candidates = ordered.map { routine in
            TodaySelection.Candidate(
                id: routine.id,
                callingMinutes: routine.remindersEnabled && !templates.isDoneForToday(routine)
                    ? routine.reminders.map(\.minutesFromMidnight) : [])
        }
        let upNextID = TodaySelection.upNextID(candidates, suggestedID: templates.suggestedRoutineID,
                                               nowMinutes: TodaySelection.minutesNow())
        let selectedID = TodaySelection.selectedID(chosenID: chosenRoutineID,
                                                   chosenToday: chosenOnDay == clock.today,
                                                   among: candidates.map(\.id), upNextID: upNextID)
        let cards = ordered.map { Deck.Card(routine: $0, summary: templates.summary(for: $0)) }
        return Deck(ordered: ordered, upNextID: upNextID, selectedID: selectedID, cards: cards)
    }

    // MARK: - Starting

    private func start(_ routine: SessionTemplate, timerOnly: Bool = false) {
        // Prepare the destination. The agreement gate records the start only when
        // it actually admits the runner; declining does not mark a routine started.
        startTick += 1
        // Set BEFORE `running`, which presents the cover: a flag written afterwards
        // arrives a frame late, a lead-in spent connecting to an unwanted gauge.
        runningTimerOnly = timerOnly
        running = routine
    }

    private func startPendingRoutine() {
        guard let id = pendingStartID else { return }
        pendingStartID = nil
        guard let routine = templates.routine(id: id) else { return }
        start(routine)
    }

    // MARK: - Sharing

    /// Builds the code HERE, not in the sheet: the URL and the name beside it come from
    /// the same routine in the same turn, so they cannot describe two plans.
    ///
    /// Reminders, `templateID` and everything store-derived stay behind — the payload is
    /// the plan and its cadence, nothing personal.
    private func share(_ routine: SessionTemplate) {
        guard let url = RoutineShare.url(for: routine.draft) else {
            shareFailed = true
            return
        }
        let summary = templates.summary(for: routine)
        shareRequest = RoutineShareRequest(
            name: summary.name,
            metaLine: summary.metaLine,
            signatureFingers: summary.signatureFingers,
            peakIntensity: summary.peakIntensity,
            url: url)
    }
}
