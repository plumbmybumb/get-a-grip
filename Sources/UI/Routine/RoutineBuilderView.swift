// SPDX-License-Identifier: MPL-2.0
// Original contributions Copyright 2026 Nuri Bruner.

import SwiftData
import SwiftUI
import UIKit

/// THE BUILDER — one view, three pages, zero pushes: RHYTHM → SETS → SCHEDULE.
///
/// Replaced the single scrolling document for 1.3.0 (a reviewer: "should be really simple
/// and obvious but the UI for this screen is so busy and confusing"). Creating walks the
/// pages with Next and Back; editing jumps between them with a switcher. It is still ONE
/// view for both — the mode changes the chrome, never the pages — so there is no second
/// surface to keep in sync. RHYTHM still comes first: constants above variables.
///
/// The `NavigationStack` only owns the title, the live subtitle and Cancel/Save; nothing
/// pushes onto it.
struct RoutineBuilderView: View {
    let mode: BuilderMode
    var onFinish: (UUID, Bool) -> Void
    /// Closing is the PRESENTER's job: nothing in this file may read
    /// `@Environment(\.dismiss)` — see `BuilderDocument.onClose`.
    var onClose: () -> Void

    /// Through the store, not a `@Query`: a query here re-ran this wrapper and the whole
    /// document on every CloudKit merge touching ANY routine, mid-typing. The seed is read
    /// once, so a fetch that observes nothing is enough.
    @Environment(TemplateStore.self) private var templates

    init(mode: BuilderMode,
         onClose: @escaping () -> Void,
         onFinish: @escaping (UUID, Bool) -> Void) {
        self.mode = mode
        self.onClose = onClose
        self.onFinish = onFinish
    }

    var body: some View {
        // The seed needs the environment, and `@State` cannot be initialised from
        // it, so the document is a CHILD seeded through its `init`: SwiftUI keeps a
        // child's `@State` across re-evaluations, so the draft is built once and
        // never renders a frame of the wrong routine.
        //
        // ONE surface for creating and editing. The swipe-card setup deck was
        // retired 2026-08-10 (Nuri): it modelled "many grips, one intensity" and
        // fought every protocol shaped like "one grip, many intensities". The
        // document states the skeleton first and the sets inherit it, which is how
        // protocols are written.
        BuilderDocument(mode: mode, seed: seed, onClose: onClose, onFinish: onFinish)
    }

    private var seed: RoutineDraft {
        switch mode {
        case .firstRun, .addAnother:
            // BLANK, always (Nuri, 2026-08-10 and 2026-08-19). Nothing is presumed or
            // OFFERED: the known protocols are still seeds in `SessionPlan` (DEBUG
            // seeding, tests and Duplicate mint from them), but no screen proposes one.
            // "Pick somebody's plan" is the wrong opening move for an app whose pitch is
            // that the plan is yours.
            #if DEBUG
            // Headless: a routine part-way built, `-builderSeedSets N` of the starter's sets.
            if let n = BuilderDebug.int("-builderSeedSets") {
                var draft = RoutineDraft.starter
                draft.plan.sets = Array(draft.plan.sets.prefix(max(0, n)))
                draft.plan.targetLoPercent = 0.20
                draft.plan.targetHiPercent = 0.30
                // `-builderCustomTiming N`: set N carries its own 7 s hold, 15 s rest.
                if let n = BuilderDebug.int("-builderCustomTiming"), draft.plan.sets.indices.contains(n - 1) {
                    draft.plan.sets[n - 1].holdSeconds = 7
                    draft.plan.sets[n - 1].restSeconds = 15
                }
                return draft
            }
            #endif
            return .blank()
        case .edit(let id):
            // Missing means a CloudKit merge deleted it while Today still showed it. A
            // blank draft is non-destructive: `store.save` creates rather than resurrects.
            return templates.routine(id: id)?.draft ?? .blank()
        }
    }
}

// MARK: - The document

private struct BuilderDocument: View {
    let mode: BuilderMode
    var onFinish: (UUID, Bool) -> Void

    /// **Never `@Environment(\.dismiss)` here.** Measured 2026-08-11: merely storing it made
    /// a keypress in ANY field re-run this whole body twice — six `SetRowView`s, the grip
    /// panel and every `ValueRow` rebuilt. `_printChanges()` showed `_dismiss changed` per
    /// keystroke: the action's identity moves with the presentation environment, and focus
    /// is part of that. So closing is a plain closure supplied by the presenter.
    var onClose: () -> Void

    @Environment(TemplateStore.self) private var templates
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    /// Changes only when the window does, so unlike `dismiss` it costs the document nothing.
    @Environment(\.horizontalSizeClass) private var sizeClass

    /// The document is a DRAFT VALUE. Nothing touches SwiftData until Save: Cancel IS
    /// undo, and a held stepper cannot fire dozens of CloudKit writes.
    @State private var draft: RoutineDraft
    /// The seed, kept only to answer "is this dirty" — the one baseline, since nothing can
    /// replace the document wholesale any more.
    @State private var initialDraft: RoutineDraft

    /// At most ONE open set row and one open rhythm row, which guarantees at most one dense
    /// control cluster on screen at a time.
    @State private var expanded: UUID?

    /// Held with its ORIGINAL id and original index so Undo restores the same row
    /// rather than an equal-looking new one.
    @State private var removedSet: RemovedSet?
    /// Which set's grip the island panel is editing. HERE, not on the token: nothing
    /// inside a scrolling set row can reach the top of the screen.
    @State private var editingGrip: UUID?
    @State private var showDiscard = false
    @State private var undoTick = 0
    @State private var stashTask: Task<Void, Never>?
    @State private var undoTask: Task<Void, Never>?
    @FocusState private var nameFocused: Bool

    @State private var page: BuilderPage = .rhythm
    /// Which way the last page turn went, so the incoming page slides from the right side.
    @State private var pageForward = true

    init(mode: BuilderMode, seed: RoutineDraft,
         onClose: @escaping () -> Void,
         onFinish: @escaping (UUID, Bool) -> Void) {
        self.mode = mode
        self.onClose = onClose
        self.onFinish = onFinish
        // The Rhythm page edits ONE routine-wide band, so a band every set shares is
        // folded back up to the routine for editing (and demoted again on Save).
        let editable = BuilderDraftPreparation.promotingUniformBand(
            BuilderDraftPreparation.editable(seed))
        _draft = State(initialValue: editable)
        _initialDraft = State(initialValue: editable)
        #if DEBUG
        // Headless states: `-builderPage N` (1-based), `-builderExpandSet N` (1-based).
        if let n = BuilderDebug.int("-builderPage"),
           let start = BuilderPage(rawValue: n - 1) {
            _page = State(initialValue: start)
        }
        if let n = BuilderDebug.int("-builderExpandSet"),
           editable.plan.sets.indices.contains(n - 1) {
            _expanded = State(initialValue: editable.plan.sets[n - 1].id)
        }
        #endif
    }

    // MARK: Body

    var body: some View {
        NavigationStack {
            ScrollViewReader { proxy in
                pagedScroll(proxy)
                // ALWAYS via `.background {}`, never a ZStack sibling, which disturbs the
                // ScrollView's safe-area layout.
                .background { AppBackground() }
                .navigationTitle(title)
                .navigationBarTitleDisplayMode(.inline)
                // The price of every edit, on every page, for zero page space — and the
                // disabled Save's explanation, right beside it: `subtitleText` swaps to the
                // validation issue the instant Save refuses.
                .navigationSubtitle(subtitleText)
                .toolbar {
                    ToolbarItem(placement: .cancellationAction) {
                        Button("Cancel") { cancel() }
                    }
                    ToolbarItem(placement: .confirmationAction) {
                        Button("Save") { save(andStart: false) }
                            .bold()
                            .disabled(draft.validationIssue != nil)
                    }
                    // The keyboard gets its OWN Done (Nuri, 2026-08-10): the toolbar's commit
                    // button over a keyboard read as "stop typing" and saved mid-thought. This
                    // one only ends editing.
                    ToolbarItemGroup(placement: .keyboard) {
                        Spacer()
                        Button("Done") {
                            nameFocused = false
                            UIApplication.shared.sendAction(
                                #selector(UIResponder.resignFirstResponder),
                                to: nil, from: nil, for: nil)
                        }
                        .font(.system(.body, weight: .semibold))
                    }
                }
                .safeAreaInset(edge: .bottom) { bottomBar }
                // BARS, not insets: content scrolling under them gets the system's scroll
                // edge treatment, where a plain inset leaves text colliding with buttons.
                .safeAreaBar(edge: .top, spacing: 0) {
                    if !mode.isCreating { pageSwitcher }
                }
                .safeAreaBar(edge: .bottom) {
                    if mode.isCreating { createNavigation }
                }
            }
        }
        // An OVERLAY, not another presentation: as a full-screen cover the builder
        // owns the top of the screen, so the panel can hang off the island from
        // inside it.
        .overlay { gripPanel }
        .onAppear {
            #if DEBUG
            // Headless verification: `-previewGripPanel` opens the first set's grip
            // panel, which a screenshot cannot otherwise reach.
            if ProcessInfo.processInfo.arguments.contains("-previewGripPanel"),
               let first = draft.plan.sets.first {
                editingGrip = first.id
            }
            #endif
        }
        // Only when there is something to lose: a swipe-down discard has no undo.
        .interactiveDismissDisabled(isDirty)
        .confirmationDialog("Discard this routine?", isPresented: $showDiscard,
                            titleVisibility: .visible) {
            Button("Discard", role: .destructive) { discard() }
            Button("Keep editing", role: .cancel) {}
        }
        .sensoryFeedback(.success, trigger: undoTick)
        .task { start() }
        // The rescue copy: create modes ONLY, and cleared on BOTH Save and Cancel.
        .onChange(of: draft) { _, _ in
            // One pending write reads the current document (coalesce, never
            // cancel-and-restart): no task per intermediate value, and no postponing
            // crash recovery while a control is held.
            guard mode.isCreating, stashTask == nil else { return }
            stashTask = Task {
                try? await Task.sleep(for: .milliseconds(500))
                guard !Task.isCancelled else { return }
                templates.stashDraft(draft)
                stashTask = nil
            }
        }
        .onDisappear {
            stashTask?.cancel()
            stashTask = nil
            undoTask?.cancel()
        }
    }

    @ViewBuilder
    private var gripPanel: some View {
        if let id = editingGrip, let index = draft.plan.sets.firstIndex(where: { $0.id == id }) {
            // No palette (Nuri, 2026-08-11). That also drops a `templates.recentGrips`
            // read, which re-ran the whole document whenever the store resynced.
            GripIslandPanel(grip: $draft.plan.sets[index].grip) {
                editingGrip = nil
            }
        }
    }

    // MARK: Blocks

    /// THE BUILDER OPENS ON THE NAME (Nuri, 2026-08-19).
    ///
    /// A START FROM row of prefill chips used to sit above this, spending the top of the
    /// first screenful on a question with one honest answer, and answering it wrong
    /// replaced the document under you. The seeds live on in `SessionPlan`; only the
    /// OFFER went away.
    private var nameBlock: some View {
        TextField("Daily no-hangs", text: $draft.plan.name)
            .font(.system(.title3, weight: .semibold))
            .foregroundStyle(Ink.primary)
            .focused($nameFocused)
            .submitLabel(.done)
            .textInputAutocapitalization(.sentences)
            .onSubmit { nameFocused = false }
            .glassFieldChrome()
            .accessibilityLabel(String(localized: "Routine name"))
    }

    private var everyDayBlock: some View {
        EveryDaySection(access: access, schedule: draft.schedule)
            .equatable()
    }

    private func deleteRow(_ id: UUID) -> some View {
        VStack(alignment: .leading, spacing: 8) {
            // No confirmation dialog: Today arms a 10 s undo bar.
            SecondaryGlassButton(title: String(localized: "Delete routine"), tint: Accent.alarm) {
                deleteRoutine(id)
            }
            Text("Its sessions are deleted too.")
                .font(.system(.footnote))
                .foregroundStyle(Ink.tertiary)
                .fixedSize(horizontal: false, vertical: true)
        }
    }

    @ViewBuilder
    private var bottomBar: some View {
        VStack(spacing: 10) {
            if let removed = removedSet {
                UndoBar(message: String(localized: "Set removed")) { undoRemove() }
                    .id(removed.set.id)
                    .transition(.move(edge: .bottom).combined(with: .opacity))
            }
        }
        .padding(.bottom, 4)
        .animation(Motion.state(reduceMotion),
                   value: removedSet)
    }

    /// The live totals, or the validation reason the moment Save would refuse. Both the
    /// nav-bar subtitle and its bottom-bar fallback quote this line, so a disabled Save
    /// always has its explanation beside it.
    private var subtitleText: String {
        // Paged, creating, on page 1 with nothing built yet: no line rather than a
        // complaint about sets the person has not reached.
        if mode.isCreating, page == .rhythm, draft.plan.executable.sets.isEmpty {
            return ""
        }
        return draft.validationIssue ?? PlanMath.subtitleLine(draft.plan)
    }

    // MARK: Lifecycle

    private func start() {
        #if DEBUG
        // A headless create (`-previewBuilderNew`) must open on what its arguments seed,
        // never on a rescue copy a previous run left behind.
        if ProcessInfo.processInfo.arguments.contains("-previewBuilderNew") {
            templates.clearDraft()
            return
        }
        #endif
        // The rescue copy exists only if a previous session died mid-build.
        // `initialDraft` stays at the seed, so the restored document is DIRTY and
        // Cancel still asks before discarding it.
        if mode.isCreating, let rescued = templates.restoreDraft(), rescued != draft {
            draft = BuilderDraftPreparation.editable(rescued)
        }
    }

    // MARK: Set mutations

    private func toggle(_ id: UUID) {
        withAnimation(Motion.state(reduceMotion)) {
            expanded = (expanded == id) ? nil : id
        }
    }

    private func addSet(_ proxy: ScrollViewProxy) {
        // A FRESH set that follows the Rhythm page; "Duplicate last set" sits beside it.
        var new = SetPlan()
        new.id = UUID()          // a duplicate must never share row identity with its source
        withAnimation(Motion.state(reduceMotion)) {
            draft.plan.sets.append(new)
            expanded = new.id
        }
        // NEXT runloop, once the row exists: otherwise you stay parked at the bottom
        // while the new set opens a screen above (Nuri, 2026-08-10).
        Task { @MainActor in
            withAnimation(Motion.state(reduceMotion)) {
                proxy.scrollTo(new.id, anchor: .top)
            }
        }
    }

    /// Resolved at the moment of the tap, never captured in a row's closure — a row
    /// keeps its closures across its neighbours' removals. See `setsBlock`.
    private func index(of id: UUID) -> Int? {
        draft.plan.sets.firstIndex { $0.id == id }
    }

    /// The children's write path, a closure over the document's state — see `DraftAccess`.
    private var access: DraftAccess {
        DraftAccess(mutate: { change in
            var copy = draft
            change(&copy)
            draft = copy
        })
    }

    /// A row's write path: writes the set back by id, seeded with the value the row
    /// draws so its reads never touch the draft.
    private func setAccess(for set: SetPlan) -> SetAccess {
        SetAccess(current: set) { updated in
            guard let index = index(of: set.id) else { return }
            draft.plan.sets[index] = updated
        }
    }

    private func duplicate(_ id: UUID) {
        guard let index = index(of: id) else { return }
        var copy = draft.plan.sets[index]
        copy.id = UUID()
        withAnimation(Motion.state(reduceMotion)) {
            draft.plan.sets.insert(copy, at: index + 1)
            expanded = copy.id
        }
    }

    private func move(_ id: UUID, by offset: Int) {
        guard let index = index(of: id) else { return }
        let target = index + offset
        guard draft.plan.sets.indices.contains(target) else { return }
        withAnimation(Motion.state(reduceMotion)) {
            draft.plan.sets.swapAt(index, target)
        }
    }

    /// Removal holds the `SetPlan` WITH ITS ORIGINAL id and index, so Undo puts the same
    /// row back where it was.
    private func remove(_ id: UUID) {
        guard let index = index(of: id) else { return }
        let set = draft.plan.sets[index]
        withAnimation(Motion.state(reduceMotion)) {
            draft.plan.sets.remove(at: index)
            if expanded == set.id { expanded = nil }
            removedSet = RemovedSet(index: index, set: set)
        }
        undoTask?.cancel()
        undoTask = Task {
            try? await Task.sleep(for: .seconds(10))
            guard !Task.isCancelled else { return }
            removedSet = nil
        }
    }

    private func undoRemove() {
        guard let removed = removedSet else { return }
        undoTask?.cancel()
        withAnimation(Motion.state(reduceMotion)) {
            draft.plan.sets.insert(removed.set, at: min(removed.index, draft.plan.sets.count))
            removedSet = nil
        }
        undoTick += 1
    }

    // MARK: Commit

    private func save(andStart: Bool) {
        nameFocused = false
        guard draft.validationIssue == nil else { return }
        // A rolled-back save leaves the sheet OPEN with the error inline, and the rescue
        // copy has to survive for the retry — `store.save` only clears it on success.
        guard let saved = templates.save(draft) else { return }
        // Cancel the pending stash write, or it re-stashes the draft the save just
        // cleared and it comes back as a ghost.
        stashTask?.cancel()
        onClose()
        onFinish(saved.id, andStart)
    }

    private func cancel() {
        if isDirty { showDiscard = true } else { discard() }
    }

    private func discard() {
        stashTask?.cancel()
        // Otherwise the stash returns as a ghost next time the builder opens.
        templates.clearDraft()
        onClose()
    }

    private func deleteRoutine(_ id: UUID) {
        // Looked up at the tap, not a `@Query` — see `templates` above.
        guard let template = templates.routine(id: id) else {
            onClose()
            return
        }
        // Rolled back → stay open with the error inline, exactly like Save.
        guard templates.delete(template) else { return }
        stashTask?.cancel()
        templates.clearDraft()
        onClose()
    }

    // MARK: Derived

    private var isDirty: Bool { draft != initialDraft }

    private var title: String {
        if mode.editingID != nil { return String(localized: "Edit routine") }
        // Creating: the page names itself, and the dots say how far along.
        return page.title
    }
}

// MARK: - Local value types

/// The one set the sheet's undo bar can put back.
private struct RemovedSet: Equatable {
    var index: Int
    var set: SetPlan
}

// MARK: - The pages

extension BuilderDocument {
    fileprivate func pagedScroll(_ proxy: ScrollViewProxy) -> some View {
        ZStack {
            ScrollView {
                // The one-screen Rhythm page runs tighter: its bottom gutter is the bar's own
                // scroll-edge treatment, and 18 pt between three blocks was 12 pt of fold.
                VStack(alignment: .leading, spacing: onePageRhythm ? 14 : 18) {
                    switch page {
                    case .rhythm:   rhythmPage
                    case .sets:     setsPage(proxy)
                    case .schedule: schedulePage
                    }
                    Color.clear.frame(height: 0).id("anchor.end")
                }
                .padding(.horizontal, Metrics.hPadding)
                .padding(.top, onePageRhythm ? 8 : 12)
                .padding(.bottom, onePageRhythm ? 12 : 28)
                .debugMeasure("page\(page.rawValue + 1)")
                .frame(maxWidth: sizeClass == .regular ? Metrics.maxContentWidthRegular
                                                       : Metrics.maxContentWidth)
                .frame(maxWidth: .infinity)
            }
            .scrollDismissesKeyboard(.interactively)
            .scrollEdgeEffectStyle(.soft, for: .bottom)
            #if DEBUG
            // `-builderMeasure`: the height the page has to fit in, between the bars.
            .onScrollGeometryChange(for: [CGFloat].self) { geo in
                [geo.containerSize.height, geo.contentInsets.top, geo.contentInsets.bottom,
                 geo.contentSize.height]
            } action: { _, g in
                if ProcessInfo.processInfo.arguments.contains("-builderMeasure") {
                    print("BUILDER_VISIBLE container=\(Int(g[0])) insetTop=\(Int(g[1])) insetBottom=\(Int(g[2])) content=\(Int(g[3]))")
                }
            }
            #endif
            // A fresh scroll view per page, so every page opens at its top.
            .id(page)
            .transition(pageTransition)
            #if DEBUG
            .onAppear { debugScroll(proxy) }
            #endif
        }
    }

    /// The Rhythm page is laid out to fit one screen while creating (iPhone SE included).
    private var onePageRhythm: Bool { page == .rhythm }

    private var pageTransition: AnyTransition {
        if reduceMotion { return .opacity }
        return .asymmetric(
            insertion: .move(edge: pageForward ? .trailing : .leading).combined(with: .opacity),
            removal: .move(edge: pageForward ? .leading : .trailing).combined(with: .opacity))
    }

    fileprivate func go(to target: BuilderPage) {
        guard target != page else { return }
        nameFocused = false
        // Direction first, page on the next turn: the outgoing page must be re-rendered with
        // the new direction before it is removed, or it leaves the wrong way.
        pageForward = target.rawValue > page.rawValue
        Task { @MainActor in
            withAnimation(Motion.state(reduceMotion)) { page = target }
        }
    }

    // MARK: Chrome

    fileprivate var pageSwitcher: some View {
        Picker(String(localized: "Page"),
               selection: Binding(get: { page }, set: { go(to: $0) })) {
            ForEach(BuilderPage.allCases) { page in
                Text(page.title).tag(page)
            }
        }
        .pickerStyle(.segmented)
        .controlSize(.large)
        .padding(.horizontal, Metrics.hPadding)
        .padding(.top, 4)
        .padding(.bottom, 8)
        .frame(maxWidth: Metrics.maxContentWidth)
        .frame(maxWidth: .infinity)
        .sensoryFeedback(.selection, trigger: page)
    }

    /// Back · Next while creating; Save routine in Next's place on the last page.
    fileprivate var createNavigation: some View {
        VStack(spacing: 12) {
            BuilderPageDots(current: page)
            // Side by side while Back keeps its one line; stacked, forward action first,
            // at the sizes where it would wrap to "Bac / k".
            ViewThatFits(in: .horizontal) {
                HStack(spacing: 12) {
                    backButton
                    forwardButton
                }
                VStack(spacing: 10) {
                    forwardButton
                    backButton
                }
            }
        }
        .padding(.horizontal, Metrics.hPadding)
        .padding(.top, 4)
        .frame(maxWidth: Metrics.maxContentWidth)
        .frame(maxWidth: .infinity)
    }

    @ViewBuilder
    private var backButton: some View {
        if let previous = page.previous {
            SecondaryGlassButton(title: String(localized: "Back"), systemImage: "chevron.left") {
                go(to: previous)
            }
            .fixedSize()
        }
    }

    @ViewBuilder
    private var forwardButton: some View {
        if let next = page.next {
            PrimaryGlassButton(title: String(localized: "Next")) { go(to: next) }
        } else {
            PrimaryGlassButton(title: String(localized: "Save routine"), systemImage: "checkmark") {
                save(andStart: false)
            }
            .disabled(draft.validationIssue != nil)
        }
    }

    // MARK: Page 1 — Rhythm

    @ViewBuilder
    fileprivate var rhythmPage: some View {
        nameBlock
        RhythmSection(access: access,
                      defaults: draft.plan.routineLevel,
                      firstSetReps: draft.plan.executable.sets.first?.repsPerSide ?? 6)
            .equatable()
        routineLoadBlock
    }

    private var routineLoadBlock: some View {
        VStack(alignment: .leading, spacing: 10) {
            // No caps label: the row names itself, and page 1 has to fit one screen.
            MaterialCard(verticalPadding: 4, surface: .flat) {
                RoutineTargetRow(
                    band: draft.plan.targetPercentBand,
                    setsVary: draft.plan.sets.contains { $0.hasTarget || $0.hasPercentTarget },
                    onChange: { band in
                        access.mutate { draft in
                            draft.plan.targetLoPercent = band?.lowerBound
                            draft.plan.targetHiPercent = band?.upperBound
                            // One band for every set: their own targets give way.
                            for i in draft.plan.sets.indices {
                                draft.plan.sets[i].targetLoPercent = nil
                                draft.plan.sets[i].targetHiPercent = nil
                                draft.plan.sets[i].targetLoKg = nil
                                draft.plan.sets[i].targetHiKg = nil
                            }
                        }
                    })
                    .equatable()
            }
            if draft.plan.targetPercentBand != nil,
               PlanMath.missingBenchmarkGripCount(draft.plan, maxes: templates.maxTable) > 0 {
                Label("Some grips have no max yet, so their sets have no target.",
                      systemImage: "exclamationmark.circle")
                    .font(.system(.footnote, weight: .medium))
                    .foregroundStyle(StatusTint.armed)
                    .fixedSize(horizontal: false, vertical: true)
            }
        }
    }

    // MARK: Page 2 — Sets

    @ViewBuilder
    fileprivate func setsPage(_ proxy: ScrollViewProxy) -> some View {
        let percentBandsVary = Set(draft.plan.executable.sets.map(\.targetPercentBand)).count > 1
        let defaults = draft.plan.routineLevel
        let last = draft.plan.sets.count - 1
        // Values in, one write path, `.equatable()` so a row re-runs only when its own
        // numbers change. Actions are keyed on the set's ID, never `index`: a row whose
        // neighbour was removed keeps its old closures.
        VStack(spacing: 8) {
            ForEach(Array(draft.plan.sets.enumerated()), id: \.element.id) { index, set in
                setRow(set, index: index, last: last, defaults: defaults,
                       percentBandsVary: percentBandsVary)
            }
        }
        setButtons(proxy)
        setAdvisories
    }

    private func setRow(_ set: SetPlan, index: Int, last: Int, defaults: SessionPlan,
                          percentBandsVary: Bool) -> some View {
        SetRowView(set: set,
                   defaults: defaults,
                   isExpanded: expanded == set.id,
                   canMoveUp: index > 0,
                   canMoveDown: index < last,
                   maxes: templates.maxTable,
                   percentBandsVary: percentBandsVary,
                   live: setAccess(for: set),
                   onTap: { toggle(set.id) },
                   onEditGrip: { editingGrip = set.id },
                   onMoveUp: { move(set.id, by: -1) },
                   onMoveDown: { move(set.id, by: 1) },
                   onDuplicate: { duplicate(set.id) },
                   onRemove: { remove(set.id) })
            .equatable()
            .id(set.id)
    }

    /// "Add a set" and, once one exists, "Duplicate last set" — side by side while they
    /// fit, stacked at large text.
    private func setButtons(_ proxy: ScrollViewProxy) -> some View {
        ViewThatFits(in: .horizontal) {
            HStack(spacing: 10) { setButtonPair(proxy) }
            VStack(spacing: 10) { setButtonPair(proxy) }
        }
    }

    @ViewBuilder
    private func setButtonPair(_ proxy: ScrollViewProxy) -> some View {
        dashedButton(String(localized: "Add a set"), systemImage: "plus") { addSet(proxy) }
        if !draft.plan.sets.isEmpty {
            dashedButton(String(localized: "Duplicate last set"), systemImage: "plus.square.on.square") {
                duplicateLast(proxy)
            }
        }
    }

    private func dashedButton(_ title: String, systemImage: String,
                              action: @escaping () -> Void) -> some View {
        Button(action: action) {
            HStack(spacing: 8) {
                Image(systemName: systemImage)
                // NOT `.fixedSize()`: at AX3 a fixed line is wider than the phone, and a
                // child's ideal width widens the whole column. `ViewThatFits` already
                // measures the single-line width to choose side by side or stacked.
                Text(title)
            }
            .font(.system(.subheadline, weight: .semibold))
            .foregroundStyle(Accent.graphite)
            .padding(.horizontal, 16)
            .frame(maxWidth: .infinity, minHeight: 50)
            .background {
                RoundedRectangle(cornerRadius: Metrics.radiusCard, style: .continuous)
                    .strokeBorder(Ink.tertiary.opacity(0.45),
                                  style: StrokeStyle(lineWidth: 1.2, dash: [5, 4]))
            }
            .contentShape(RoundedRectangle(cornerRadius: Metrics.radiusCard, style: .continuous))
        }
        .buttonStyle(PressFeedbackButtonStyle())
    }

    private func duplicateLast(_ proxy: ScrollViewProxy) {
        guard var copy = draft.plan.sets.last else { return }
        copy.id = UUID()
        withAnimation(Motion.state(reduceMotion)) {
            draft.plan.sets.append(copy)
            expanded = copy.id
        }
        Task { @MainActor in
            withAnimation(Motion.state(reduceMotion)) {
                proxy.scrollTo(copy.id, anchor: .top)
            }
        }
    }

    /// Advisories about the sets — never the totals, which the subtitle already states.
    /// Missing maxes are flagged on the Rhythm page, under the band that needs them.
    @ViewBuilder
    private var setAdvisories: some View {
        if PlanMath.totalSeconds(draft.plan) > 3600 {
            Label("This routine runs over an hour.", systemImage: "clock")
                .font(.system(.footnote, weight: .medium))
                .foregroundStyle(StatusTint.armed)
        }
    }

    // MARK: Page 3 — Schedule

    @ViewBuilder
    fileprivate var schedulePage: some View {
        everyDayBlock
        FineTuningSection(access: access, defaults: draft.plan.routineLevel)
            .equatable()
        if templates.saveError != nil {
            Text("Couldn't save the change. Try again.")
                .font(.system(.footnote, weight: .medium))
                .foregroundStyle(Accent.alarm)
                .fixedSize(horizontal: false, vertical: true)
        }
        if let id = mode.editingID {
            deleteRow(id)
                .padding(.top, 8)
        }
    }

    // MARK: Headless hooks

    #if DEBUG
    /// `-builderScrollTo expanded|end` — for screenshots of what is below the fold.
    fileprivate func debugScroll(_ proxy: ScrollViewProxy) {
        guard let target = BuilderDebug.string("-builderScrollTo") else { return }
        Task { @MainActor in
            try? await Task.sleep(for: .milliseconds(400))
            switch target {
            case "expanded": if let id = expanded { proxy.scrollTo(id, anchor: .top) }
            case "end": proxy.scrollTo("anchor.end", anchor: .bottom)
            default: break
            }
        }
    }
    #endif
}

private extension View {
    /// `-builderMeasure` (DEBUG): logs the content height, for the page-height table.
    @ViewBuilder
    func debugMeasure(_ label: String) -> some View {
        #if DEBUG
        if ProcessInfo.processInfo.arguments.contains("-builderMeasure") {
            onGeometryChange(for: CGFloat.self) { $0.size.height } action: { height in
                print("BUILDER_MEASURE \(label) \(Int(height.rounded()))")
            }
        } else {
            self
        }
        #else
        self
        #endif
    }
}
