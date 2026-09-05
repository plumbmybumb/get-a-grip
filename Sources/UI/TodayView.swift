// SPDX-License-Identifier: MPL-2.0
// Original contributions Copyright 2026 Nuri Bruner.

import SwiftData
import SwiftUI

/// The ritual front door — the screen Nuri sees every single day, twice.
///
/// What you do today is the first thing on screen and one tap from starting. There is
/// no library to assemble, no session to pick apart from the routine: the routine IS
/// the thing, and the only door to changing it is the summary row on its own card.
///
/// Deliberately no footer disclaimer. Schengen's dashboard carries one because every
/// number there is legally load-bearing; nothing here is. The Tindeq non-affiliation
/// line lives in Settings › About.
struct TodayView: View {
    /// Sorted the same way `TemplateStore` sorts, so the two can never disagree about
    /// which routine is primary. The `id` tiebreak is applied in `ordered` rather than
    /// here because `UUID` is not `Comparable` and a SwiftData `SortDescriptor` cannot
    /// express it — and without it, two devices that both reorder produce duplicate
    /// `sortIndex` values over CloudKit and render in opposite orders.
    @Query(sort: [SortDescriptor(\SessionTemplate.sortIndex),
                  SortDescriptor(\SessionTemplate.createdAt)])
    private var routines: [SessionTemplate]

    @Environment(DeviceStore.self) private var device
    @Environment(TemplateStore.self) private var templates
    @Environment(DayClock.self) private var clock
    @Environment(\.scenePhase) private var scenePhase
    @Environment(\.accessibilityReduceMotion) private var reduceMotion

    @State private var chosenRoutineID: UUID?
    @State private var chosenOnDay: DayStamp?
    /// Which card the deck is resting on. This is a MIRROR of the selection rungs, not
    /// a fourth rung: programmatic moves (a new day, a deleted routine) write it from
    /// `selected`, and only a settle on a card that ISN'T `selected` — i.e. a swipe a
    /// person made — writes back into `chosenRoutineID`. That guard is what keeps the
    /// old rail's semantics intact: a day change still re-asserts the primary without
    /// the sync itself counting as "an explicit tap made today".
    @State private var deckPosition: UUID?
    /// The trailing "New routine" card's scroll identity. Static so the id survives the
    /// view struct being recreated — an id that changed per render would drop the scroll
    /// target out from under anyone parked on the card.
    private static let ghostID = UUID()
    @State private var builder: BuilderMode?
    @Environment(TourController.self) private var tour

    @State private var running: SessionTemplate?
    /// Whether the session being presented was started without a gauge. Not part of the
    /// routine — the same routine is run both ways — so it rides alongside `running`.
    @State private var runningTimerOnly = false
    /// Set when the builder finishes with "Save and start training" and consumed in the
    /// sheet's `onDismiss` — presenting a full-screen cover in the same runloop turn as
    /// a sheet's dismissal is routinely dropped, which would lose the one tap that
    /// mattered most.
    @State private var pendingStartID: UUID?
    @State private var startTick = 0
    @State private var selectTick = 0
    @State private var undoTick = 0
    @State private var loggingSession = false
    /// FROZEN at tap time — see `RoutineShareRequest`. Holding the request rather than
    /// the routine is what stops a swipe, an edit or a CloudKit merge changing the code
    /// on screen out from under whoever is pointing a camera at it.
    @State private var shareRequest: RoutineShareRequest?
    @State private var shareFailed = false
    @State private var scanningRoutine = false
    @State private var scannedRoutine: String?
    /// The scanned routine currently ON SCREEN, claimed from the store's inbox by
    /// `drainImportInbox()`. It lives here — not in `RootTabView`, where the link
    /// actually arrives — because this is the one view that owns every presentation a
    /// scan can collide with: presenting from the root while the builder or runner
    /// cover was up tore the cover down (a session died unlogged; a dirty builder lost
    /// its edits), so the inbox waits until this view can see that nothing else holds
    /// the screen.
    @State private var importPreview: ImportRequest?
    @State private var importError: String?
    @Namespace private var zoom

    /// Nothing this view presents is up or about to be. `pendingStartID` counts as
    /// "about to be": the builder's Save-and-start is consumed in its `onDismiss`, and
    /// draining ahead of it would slide the import sheet in front of the one tap that
    /// mattered most.
    private var canPresentImport: Bool {
        builder == nil && running == nil && pendingStartID == nil
            && !loggingSession && shareRequest == nil && !scanningRoutine
            && importPreview == nil && importError == nil
    }

    /// The single door from the store's inbox to the screen, called on arrival and from
    /// every presentation's `onDismiss` — the same deferral idiom as
    /// `startPendingRoutine()`, and for the same reason: presenting in the runloop turn
    /// that dismissed something else is routinely dropped.
    private func drainImportInbox() {
        guard canPresentImport else { return }
        if let message = templates.claimPendingImportError() {
            importError = message
        } else if let draft = templates.claimPendingImport() {
            importPreview = ImportRequest(draft: draft)
        }
    }

    var body: some View {
        // spacing 16, not the house 22: three or four blocks that must land inside one
        // screen without being as dense as Schengen's dashboard.
        // 12 rather than the house 22, and rather than the 16 it carried before: Today has
        // four blocks and the gaps between them were the easiest twelve points to find
        // when the page had to fit under a large title without scrolling.
        ScreenScaffold(title: String(localized: "Today"), subtitle: dateLine, spacing: 12, fitsOnePage: true) {
            header
                .staggerIn(0)

            // The deck exists the moment ANY routine does — with one routine its only
            // neighbour is the ghost card, and that peek is honest: there genuinely is
            // something behind the ritual (creating the next one). Without it a
            // single-routine user had no swipe to the ghost at all and "New routine"
            // lived only in the ⋯ menu (Nuri, 2026-08-10).
            Group {
                if ordered.isEmpty {
                    emptyCard
                } else {
                    routineDeck
                }
            }
            // Motion.state, not .snappy: a deck appearing is a state change nobody
            // flicked, so it has earned no bounce — and the token already carries the
            // Reduce Motion branch this line used to hand-retype.
            .animation(Motion.state(reduceMotion), value: ordered.count)
            .staggerIn(1)

            // With no routine there is no ritual, and nothing the strip could honestly
            // describe.
            if !ordered.isEmpty {
                ConsistencyCard(days: templates.consistency) { loggingSession = true }
                    .tourAnchor(.consistency)
                    .staggerIn(2)
            }
        }
        // FULL SCREEN, not a sheet. A sheet's top edge sits ~50 pt below the Dynamic
        // Island, so the grip panel had to be a second presentation stacked on top of it
        // just to reach the screen's top. Full screen collapses that layer, and buys back
        // the 50 pt on the one screen in the app that is fighting for vertical space.
        // Still a COVER and never a push: nothing here touches SwiftData until Save, so
        // Cancel IS undo and a back chevron would promise save-as-you-go.
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
        .fullScreenCover(item: $running, onDismiss: { drainImportInbox() }) {
            RunnerView(template: $0, timerOnly: runningTimerOnly)
        }
        // `initial: true` so this is both the start trigger and the resume trigger. The
        // routine list arrives through a `@Query`, so "is there a routine" is not knowable
        // on the first frame — reading it once in `onAppear` would show the empty-handed
        // act to somebody who has six routines.
        .onChange(of: ordered.count, initial: true) { _, _ in syncTour() }
        // ALSO when a session closes. Saving your first routine with "Save and start
        // training" takes you straight into the runner, so the resume has to wait for you
        // to come back — otherwise act two starts behind the cover, the session act is
        // blocked by an act already running, and you get Today's tour over a live workout.
        .onChange(of: running?.id) { _, _ in syncTour() }
        .sheet(isPresented: $loggingSession, onDismiss: { drainImportInbox() }) {
            SessionLogSheet(onClose: { loggingSession = false })
        }
        .sheet(item: $shareRequest, onDismiss: { drainImportInbox() }) { request in
            RoutineShareSheet(request: request) { shareRequest = nil }
        }
        .sheet(isPresented: $scanningRoutine, onDismiss: {
            // Route only after the camera sheet has finished dismissing. The existing
            // inbox owns validation, error messages and the import preview.
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
        // The share alert fires when a routine cannot become a WORKING code — no pulls
        // in it, or past the 50-set import ceiling. Rare, which is exactly why a silent
        // no-op would be unreadable: a menu item that does nothing is indistinguishable
        // from a tap that missed.
        .alert("Couldn't share", isPresented: $shareFailed) {
            Button("OK", role: .cancel) {}
        } message: {
            Text("Couldn't build a share code for this routine.")
        }
        // The import inbox's two outlets. Arrival is watched here; every presentation
        // above drains again on dismissal, so a scan that landed mid-cover appears the
        // moment the screen is free instead of being torn into or lost.
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
        // A link that cold-launched the app can land before the two observers above are
        // in place; the appear pass sweeps whatever is already waiting.
        .onAppear { drainImportInbox() }
        .safeAreaInset(edge: .bottom) { undoBar }
        .sensoryFeedback(.impact(weight: .medium, intensity: 0.7), trigger: startTick)
        .sensoryFeedback(.selection, trigger: selectTick)
        .sensoryFeedback(.success, trigger: undoTick)
        .onChange(of: scenePhase) { _, phase in
            // A new day always re-asserts `upNext` — that is literally "this is what
            // meets you every time you open the app". (Within a day, becoming active
            // also refreshes `recentlyCalled`, so answering a reminder lands on the
            // routine that sent it even while yesterday's pin is long gone.)
            guard phase == .active, chosenOnDay != clock.today else { return }
            chosenRoutineID = nil
            chosenOnDay = nil
        }
    }

    // MARK: - Header

    private var header: some View {
        HStack(spacing: 10) {
            DeviceChip()
            Spacer(minLength: 8)
            if let next = summary?.nextReminder {
                Label(next.displayText(), systemImage: "bell")
                    .font(.system(.footnote))
                    .monospacedDigit()
                    .foregroundStyle(Ink.tertiary)
                    // "19:30" is fine to read and too terse to hear.
                    .accessibilityLabel("Next reminder at \(next.displayText())")
            }
        }
    }

    /// "Monday 3 August". The M1 subtitle ("Gauge connected") duplicated the DeviceChip
    /// thirty points below it; a daily-ritual screen says what day it is. Read off the
    /// app's own `DayClock` rather than `Date.now` so it re-renders when the day rolls
    /// under a phone that was left open.
    private var dateLine: String {
        clock.today.date().formatted(.dateTime.weekday(.wide).day().month(.wide))
    }

    // MARK: - The deck

    /// The routines as a PAGED DECK — Music's Top Picks, not a browse feed. The card
    /// that used to sit under a rail of name-chips IS the chooser now: the next
    /// routine peeks in from the trailing edge, swiping to a card chooses it (same
    /// rung, same day-scoped pin as the old rail tap), and a new day — or a fresher
    /// reminder — snaps the deck back to `upNext`, the card wearing the border. This
    /// folds two controls that were pretending not to be one — the chip NAMED a
    /// routine the card was already showing — and it makes the whole app speak the
    /// builder deck's physics: cards slide sideways, the front card is live. The
    /// honest cost, accepted: sibling NAMES aren't readable without a swipe. At the
    /// two-or-three routines this app is built around, the peek plus each card's own
    /// done-dots carry what the rail carried.
    private var routineDeck: some View {
        ScrollView(.horizontal) {
            HStack(alignment: .top, spacing: 8) {
                ForEach(ordered) { routine in
                    // The border only exists where there are siblings to distinguish —
                    // with one routine it would mark the only real card there is.
                    routineCard(routine,
                                isUpNext: ordered.count > 1 && routine.id == upNext?.id)
                        .containerRelativeFrame(.horizontal)
                }
                // The quiet door at the end of the deck — the same grammar as the
                // builder's spare grip card: the next thing waits BEHIND the things
                // you have, so creating never sits at the same visual weight as the
                // ritual (the rule the rail enforced by having no `+` at all).
                newRoutineGhost
                    .containerRelativeFrame(.horizontal)
                    .id(Self.ghostID)
            }
            .scrollTargetLayout()
        }
        // `.always`: one card per gesture, however hard the flick. Music's carousels
        // let momentum carry you several items because they are browsing surfaces;
        // this deck is a CHOOSER where every settle is a deliberate pick (a pin and a
        // haptic), so it moves like the builder's pager — one card per swipe.
        .scrollTargetBehavior(.viewAligned(limitBehavior: .always))
        .scrollPosition(id: $deckPosition)
        .scrollIndicators(.hidden)
        // Full-bleed: the deck escapes the column's padding so the neighbour peeks at
        // the SCREEN edge (the Music carousel move), then the content margins put a
        // settled card back on the house grid, aligned with the title above it. The
        // trailing margin is deliberately 8 wider than the grid: peek = trailing
        // margin − card gap, and 12 pt of neighbour was a hairline where 20 reads as
        // a card. The last card still lands exactly on the grid — leading margin +
        // card width + trailing margin is precisely the viewport.
        .padding(.horizontal, -Metrics.hPadding)
        .contentMargins(.leading, Metrics.hPadding, for: .scrollContent)
        .contentMargins(.trailing, Metrics.hPadding + 8, for: .scrollContent)
        // Placed, never animated, on first layout — the deck must simply BE on the
        // day's routine, not visibly travel there.
        .onAppear { deckPosition = selected?.id }
        // Programmatic re-selection: the day rolling over, a save landing, a CloudKit
        // merge deleting the card under you. The swipe direction never loops through
        // here — a settle on `selected` itself is filtered below, and pinning writes
        // `chosenRoutineID`, which makes `selected` equal the settle target.
        .onChange(of: selected?.id) { _, id in
            guard let id, deckPosition != id else { return }
            withAnimation(Motion.state(reduceMotion)) { deckPosition = id }
        }
        // A settle on a card someone swiped to is the old rail tap. The `ordered`
        // check keeps the ghost from being "chosen": parking on it is browsing, not
        // picking a ritual, and it must not survive as a stale rung-1 pin.
        .onChange(of: deckPosition) { _, id in
            guard let id, id != selected?.id,
                  ordered.contains(where: { $0.id == id }) else { return }
            chosenRoutineID = id
            selectTick += 1
            chosenOnDay = clock.today
        }
    }

    /// Deliberately NOT a `MaterialCard`: a hairline outline against the material of
    /// its neighbours is the same "provisional" reading as an unselected chip — this
    /// is a routine that could exist, next to ones that do.
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
                    Text("A rest-day plan, a max day —\nwhatever this one isn't.")
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

    private func routineCard(_ routine: SessionTemplate, isUpNext: Bool = false) -> some View {
        RoutineCard(
            summary: templates.summary(for: routine),
            completionText: templates.completionText(routine),
            isUpNext: isUpNext,
            deviceState: device.state,
            battery: device.batteryFraction,
            onStart: { start(routine) },
            onStartTimerOnly: { start(routine, timerOnly: true) },
            onEdit: { builder = .edit(routine.id) },
            onDuplicate: { _ = templates.duplicate(routine) },
            onShare: { share(routine) },
            // `.addAnother` zooms out of the deck's ghost card when the deck exists;
            // from the menu on a single-routine screen there is no source on screen
            // and the zoom degrades to the standard sheet presentation, which is right.
            onNew: { builder = .addAnother },
            onMakePrimary: { templates.makePrimary(routine) },
            onDelete: { _ = templates.delete(routine) },
            onDemo: { device.useMockDevice(true) }
        )
        .matchedTransitionSource(id: routine.id.uuidString, in: zoom)
    }

    /// Exactly ONE button. No "start from a preset" second door — the prefill is the
    /// document's initial state INSIDE the sheet, where it can be edited in place, and
    /// rendering six read-only set rows here would duplicate the document you are one
    /// tap from while pushing the button a screen and a half down at accessibility3.
    private var emptyCard: some View {
        MaterialCard {
            VStack(alignment: .leading, spacing: 12) {
                CapsLabel(String(localized: "YOUR ROUTINE"))

                FingerGlyph(fingers: .four, position: .halfCrimp, dot: 12, gap: 6,
                            tint: Ink.tertiary.opacity(0.55))
                    .accessibilityHidden(true)

                Text("Get a Grip is built around one routine you actually commit to.")
                    .font(.system(.title3, weight: .semibold))
                    .foregroundStyle(Ink.primary)
                    .fixedSize(horizontal: false, vertical: true)

                Text("A low-intensity no-hang plan is ready to go — 6 sets, 36 pulls, about 21 minutes, twice a day. Change anything you like.")
                    .font(.system(.footnote, weight: .medium))
                    .foregroundStyle(Ink.secondary)
                    .fixedSize(horizontal: false, vertical: true)

                PrimaryGlassButton(title: String(localized: "Build my routine"), tint: Accent.graphite) {
                    builder = .firstRun
                }
                .tourAnchor(.buildRoutine)

                scanRoutineButton

                if !device.state.isConnected {
                    // Removes the "do I need the hardware in my hand first?" hesitation
                    // at exactly the moment it occurs.
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
            if templates.lastDeleted != nil {
                UndoBar(message: String(localized: "Routine deleted")) {
                    templates.undoDelete()
                    undoTick += 1
                }
                .padding(.horizontal, Metrics.hPadding)
                .transition(.move(edge: .bottom).combined(with: .opacity))
            }
        }
        // 8, not 4: the bar sat close enough to the tab bar that a reach for Undo
        // could land on the tab strip instead (rank 3 audit finding).
        .padding(.bottom, 8)
        .animation(Motion.state(reduceMotion),
                   value: templates.lastDeleted)
    }

    // MARK: - Selection

    /// The store's TOTAL order, re-applied here for the `id` tiebreak `@Query` cannot
    /// express. Cheap: a handful of routines, and the alternative is the two disagreeing
    /// about which one is primary.
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

    /// Four rungs, in order:
    /// 1. an explicit tap or swipe made TODAY — cleared by a day change, never persisted;
    /// 2. the routine whose reminder CALLED most recently (see `recentlyCalled`);
    /// 3. the routine started today on this device, so doing the rest-day one this
    ///    morning means the evening open shows it again;
    /// 4. the primary (lowest `sortIndex`).
    ///
    /// Rung 4 is also what self-heals when a CloudKit merge deletes the chosen routine:
    /// the `first(where:)` above simply misses and the rule falls through.
    private var selected: SessionTemplate? {
        if let id = chosenRoutineID, chosenOnDay == clock.today,
           let match = ordered.first(where: { $0.id == id }) {
            return match
        }
        return upNext
    }

    /// Rungs 2–4: the routine the app would front WITH NO HAND ON IT — the deck's home
    /// card, and the one wearing the up-next border. Split from `selected` because the
    /// border must ignore rung 1: swipe away to browse and the border stays put on the
    /// called card, which is what makes it information ("this one is being asked of
    /// you") rather than decoration on whatever is in front.
    private var upNext: SessionTemplate? {
        if let called = recentlyCalled { return called }
        if let id = templates.suggestedRoutineID,
           let match = ordered.first(where: { $0.id == id }) {
            return match
        }
        return ordered.first
    }

    /// The routine whose reminder fired most recently today and whose day is still
    /// owed — opening the app off the back of a notification should land on the
    /// routine that sent it (Nuri, 2026-08-10).
    ///
    /// Computed from the SCHEDULE, deliberately not from the delivered-notification
    /// list: the schedule is synchronous (the deck must not jump a frame after
    /// appearing while an async query lands), it still works with notifications off —
    /// at 13:05 it is Max o'clock whether or not a banner said so — and the planner's
    /// suppression of already-trained days is mirrored by the `isDoneForToday` filter:
    /// a routine you finished has been answered and cannot be "calling".
    ///
    /// Freshness rides on re-render: `scenePhase` is read by this view, so returning
    /// to the app — the notification-tap path — recomputes this and the deck's sync
    /// scroll animates over to whoever called. A crossing that happens while the app
    /// sits open foregrounded is picked up on the next render, which is soon enough
    /// for a heuristic about attention.
    private var recentlyCalled: SessionTemplate? {
        let comps = Calendar.current.dateComponents([.hour, .minute], from: Date())
        let now = (comps.hour ?? 0) * 60 + (comps.minute ?? 0)
        var best: (routine: SessionTemplate, firedAt: Int)?
        for routine in ordered
        where routine.remindersEnabled && !templates.isDoneForToday(routine) {
            guard let fired = routine.reminders.map(\.minutesFromMidnight)
                .filter({ $0 <= now }).max() else { continue }
            // Strictly greater, so a tie goes to the earlier routine in `ordered` —
            // stable, and biased toward the primary.
            if best == nil || fired > best!.firedAt { best = (routine, fired) }
        }
        return best?.routine
    }

    private var summary: RoutineSummary? {
        selected.map { templates.summary(for: $0) }
    }

    // MARK: - Starting

    /// Start or resume the tour, but never over a session. Called on first appearance, on
    /// the routine list changing, and on the runner closing.
    private func syncTour() {
        guard running == nil else { return }
        if tour.awaitingRoutine, !ordered.isEmpty {
            tour.routineCreated()
        } else {
            tour.beginIfUnseen(.intro, hasRoutine: !ordered.isEmpty)
        }
    }

    private func start(_ routine: SessionTemplate, timerOnly: Bool = false) {
        // Written on START, not on finish: the useful question at 19:00 is "which one am
        // I in the middle of", not "which one did I complete".
        templates.noteSessionStarted(routine)
        startTick += 1
        // Set BEFORE `running`, because assigning `running` is what presents the cover and
        // builds the RunnerView — a flag written afterwards would arrive a frame late,
        // which is a whole lead-in spent connecting to a gauge nobody asked for.
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

    /// Builds the code HERE, not in the sheet: the URL and the name beside it are read
    /// off the same routine in the same turn, so the picture and the words on that sheet
    /// cannot describe two different plans.
    ///
    /// Reminders, `templateID` and everything else store-derived stay behind — the
    /// payload is the plan and its cadence, nothing personal.
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
