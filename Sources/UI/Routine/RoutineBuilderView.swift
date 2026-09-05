// SPDX-License-Identifier: MPL-2.0
// Original contributions Copyright 2026 Nuri Bruner.

import SwiftData
import SwiftUI
import UIKit

/// THE DOCUMENT — one view, one scrollable document, zero pushes.
///
/// The `NavigationStack` inside exists only to own the title, the live subtitle and the
/// Cancel/Save toolbar; nothing ever pushes onto it. That is what makes "the wizard IS
/// the editor" a literal identity rather than a discipline someone has to maintain: the
/// first-run walkthrough and the 30th four-tap edit are the same screen in the same
/// order, so there is no second surface to keep in sync.
///
/// Document order is NAME → RHYTHM → SETS → EVERY DAY → FINE TUNING → finish/danger.
/// RHYTHM sits ABOVE the set list on purpose — constants above variables, expressed as
/// vertical order instead of as screens — which is what makes changing every rest
/// interval four taps: open, tap the row, tap a chip, Save.
struct RoutineBuilderView: View {
    let mode: BuilderMode
    var onFinish: (UUID, Bool) -> Void
    /// Closing is the PRESENTER's job, deliberately — nothing in this file may read
    /// `@Environment(\.dismiss)`. See `BuilderDocument`'s note: that one property was
    /// costing a full rebuild of the document, every set row and the grip panel on every
    /// single keystroke typed into any field here.
    var onClose: () -> Void

    @Query(sort: [SortDescriptor(\SessionTemplate.sortIndex), SortDescriptor(\SessionTemplate.createdAt)])
    private var routines: [SessionTemplate]

    init(mode: BuilderMode,
         onClose: @escaping () -> Void,
         onFinish: @escaping (UUID, Bool) -> Void) {
        self.mode = mode
        self.onClose = onClose
        self.onFinish = onFinish
    }

    var body: some View {
        // The seed can only be resolved from `@Query`/the environment, and `@State`
        // cannot be initialised from either. So the document is a CHILD view seeded
        // through its `init`: SwiftUI keeps a child's `@State` across re-evaluations of
        // this wrapper, so the draft is built exactly once and the sheet never renders a
        // frame of the wrong routine before correcting itself.
        //
        // ONE surface for creating and editing. The swipe-card setup deck was retired
        // 2026-08-10 (Nuri: "I don't know if this swipe card thing while creating
        // routines actually makes sense") — it modelled "many grips, one intensity",
        // which fit the daily ritual and fought every protocol shaped like "one grip,
        // many intensities". The document already states the skeleton first — name,
        // kind, rhythm, hands — and the sets inherit it, which is how protocols are
        // actually written. First-run guidance is the coach cards plus the tour.
        BuilderDocument(mode: mode, seed: seed, onClose: onClose, onFinish: onFinish)
    }

    private var seed: RoutineDraft {
        switch mode {
        case .firstRun, .addAnother:
            // BLANK, always (Nuri, 2026-08-10: "when creating a routine, I think it
            // should start at blank"; 2026-08-19: "get rid of all the templates and just
            // start with routine name"). Nothing is presumed and nothing is OFFERED
            // either: the known protocols are still seeds in `SessionPlan` — DEBUG launch
            // seeding, the tests and Duplicate all mint from them — but no screen proposes
            // one any more. A chooser above the name field made the opening move "pick
            // somebody's plan" on the one app whose pitch is that the plan is yours.
            return .blank()
        case .edit(let id):
            // Missing means a CloudKit merge deleted it while Today still showed it.
            // A blank draft is the non-destructive answer: `store.save` will create
            // rather than resurrect, and nothing the user typed is thrown away.
            return routines.first(where: { $0.id == id })?.draft ?? .blank()
        }
    }
}

// MARK: - The document

private struct BuilderDocument: View {
    let mode: BuilderMode
    var onFinish: (UUID, Bool) -> Void

    /// **Never `@Environment(\.dismiss)` here.** Measured 2026-08-11: reading it made a
    /// single keypress in ANY field on this screen re-run this entire body — six
    /// `SetRowView`s rebuilt from scratch, the grip panel recreated, and every `ValueRow`
    /// inside them — twice. `_printChanges()` named it outright: `_dismiss changed` on
    /// each keystroke, because the dismiss action's identity moves with the presentation
    /// environment and focus is part of that. Nothing in the body reads it; it is a
    /// stored property, and that is enough to invalidate the whole document.
    ///
    /// It is a plain closure now, supplied by whoever presented this. That also matches
    /// how the screen already worked: `onFinish` was always the presenter's business.
    var onClose: () -> Void

    @Environment(TemplateStore.self) private var templates
    @Environment(SettingsStore.self) private var settings
    @Environment(TourController.self) private var tour
    @Environment(\.accessibilityReduceMotion) private var reduceMotion

    @Query(sort: [SortDescriptor(\SessionTemplate.sortIndex), SortDescriptor(\SessionTemplate.createdAt)])
    private var routines: [SessionTemplate]

    /// The document is a DRAFT VALUE. Nothing here touches SwiftData until Save, which
    /// is what makes reordering, removing and experimenting free: Cancel IS undo, and a
    /// held stepper cannot fire dozens of CloudKit writes.
    @State private var draft: RoutineDraft
    /// The seed, kept only to answer "is this dirty" — and the ONLY baseline there is,
    /// now that nothing can replace the document wholesale. The prefill chips needed a
    /// second one (a chip's own output, since `.starter` mints fresh set ids per call and
    /// so never compares equal to itself); with them gone, dirty is one comparison again.
    @State private var initialDraft: RoutineDraft

    /// At most ONE open set row, and at most one open rhythm row. The accordion is not
    /// only a readability device: it is what guarantees exactly one dense chip cluster
    /// can exist on screen at a time.
    @State private var expanded: UUID?

    /// 1…5 are the inline cards, 6 is the closing card, `retiredCoachStep` is off.
    @State private var coachStep: Int = BuilderDocument.retiredCoachStep
    /// Held with its ORIGINAL id and original index so Undo restores the same row
    /// rather than an equal-looking new one.
    @State private var removedSet: RemovedSet?
    /// Which set's grip the island panel is editing. It lives HERE, not on the token:
    /// the panel hangs off the Dynamic Island, and nothing inside a scrolling set row can
    /// reach the top of the screen.
    @State private var editingGrip: UUID?
    @State private var showDiscard = false
    @State private var undoTick = 0
    @State private var stashTask: Task<Void, Never>?
    @State private var undoTask: Task<Void, Never>?
    @FocusState private var nameFocused: Bool

    init(mode: BuilderMode, seed: RoutineDraft,
         onClose: @escaping () -> Void,
         onFinish: @escaping (UUID, Bool) -> Void) {
        self.mode = mode
        self.onClose = onClose
        self.onFinish = onFinish
        _draft = State(initialValue: seed)
        _initialDraft = State(initialValue: seed)
    }

    /// Where the guide sits when it is off. One past the closing card, so `max`-style
    /// advancement can never revive it.
    static let retiredCoachStep = 7
    /// The closing card's step. The five numbered cards are 1…5.
    static let closingCoachStep = 6
    static let coachTotal = 5

    // MARK: Body

    var body: some View {
        NavigationStack {
            ScrollViewReader { proxy in
                // PROBE 4, resolved conservatively: `List` + `defaultMinListRowHeight: 0`
                // + accordion rows + a bottom `safeAreaInset` INSIDE a sheet is the
                // riskiest combination in this design, and a shipped-and-working simple
                // layout beats an elegant one that mis-renders. What that costs is
                // swipe-to-delete and `.onMove`, both of which already have guaranteed
                // equivalents here (the expanded row's chevrons, the context menu, and
                // the undo bar), so nothing is only reachable by a gesture.
                //
                // NOT `LazyVStack`: the guide's `scrollTo` has to find an anchor that may
                // be a screenful below the fold, and a lazy stack has not built it yet.
                // A routine is a dozen rows, not a feed, so eager layout is free.
                ScrollView {
                    VStack(alignment: .leading, spacing: 18) {
                        // The first block is the NAME, in every mode (Nuri, 2026-08-19).
                        // Creating and editing now open on the same first screenful, so
                        // "this same screen is the editor" is true of the top of the page
                        // as well as the rest of it.
                        nameBlock(proxy)
                        rhythmBlock(proxy)
                        setsBlock(proxy)
                        totalsBlock(proxy)
                        everyDayBlock(proxy)
                        FineTuningSection(draft: $draft)
                        finishBlock
                    }
                    .padding(.horizontal, Metrics.hPadding)
                    .padding(.top, 12)
                    .padding(.bottom, 28)
                    .frame(maxWidth: Metrics.maxContentWidth)
                    .frame(maxWidth: .infinity)
                }
                .scrollDismissesKeyboard(.interactively)
                // ALWAYS via `.background {}`, never as a ZStack sibling — as a sibling
                // it disturbs the ScrollView's safe-area layout.
                .background { AppBackground() }
                .scrollEdgeEffectStyle(.soft, for: .bottom)
                .navigationTitle(title)
                .navigationBarTitleDisplayMode(.inline)
                // The price of every edit, always visible, for zero document space.
                // PROBE 1: if this does not render under an inline title inside a sheet,
                // flip `subtitleInNavigationBar` to false — the same line then draws in
                // the bottom safe-area inset instead. That is the whole swap.
                //
                // **Doubles as the disabled Save's only NEARBY explanation.** The toolbar
                // Save disables on `draft.validationIssue`, but its old explanation lived
                // only in `finishBlock`, at the very bottom of a document that can run a
                // dozen rows — and in edit mode `finishBlock` never even draws the primary
                // button, so the toolbar control was the ONLY save path with its reason a
                // screen away. `subtitleText` swaps to the issue the instant Save refuses,
                // right next to the control that refused.
                .navigationSubtitle(Self.subtitleInNavigationBar ? subtitleText : "")
                .toolbar {
                    ToolbarItem(placement: .cancellationAction) {
                        Button("Cancel") { cancel() }
                    }
                    ToolbarItem(placement: .confirmationAction) {
                        Button("Save") { save(andStart: false) }
                            .bold()
                            .disabled(draft.validationIssue != nil)
                    }
                    // The keyboard gets its OWN Done (Nuri, 2026-08-10): a commit
                    // button hovering over a keyboard reads as "stop typing", and
                    // tapping it saved the routine mid-thought. This one only ends
                    // editing.
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
            }
        }
        // An OVERLAY, not another presentation. The builder is a full-screen cover now,
        // so it owns the top of the screen and the panel can hang off the island from
        // inside it — which is what let the nested `fullScreenCover` (and its clear
        // presentation background) go away entirely.
        .overlay { gripPanel }
        // The builder act of the first-run tour draws over THIS screen — hosted here
        // since the deck it used to live on is gone.
        .tourHost(tour, act: .builder)
        .onAppear { if mode.isCreating { tour.builderOpened() } }
        // Only when there is something to lose: a swipe-down that discards six sets of
        // authored intent has no undo, unlike everything else in this document.
        .interactiveDismissDisabled(isDirty)
        .confirmationDialog("Discard this routine?", isPresented: $showDiscard,
                            titleVisibility: .visible) {
            Button("Discard", role: .destructive) { discard() }
            Button("Keep editing", role: .cancel) {}
        }
        .sensoryFeedback(.selection, trigger: coachStep)
        .sensoryFeedback(.success, trigger: undoTick)
        .task { start() }
        // The rescue copy: create modes ONLY, and cleared on BOTH Save and Cancel.
        .onChange(of: draft) { _, _ in
            // One pending write reads the current document. Dragging/typing must not
            // allocate and cancel a task for every intermediate value, nor postpone
            // crash recovery indefinitely while a control is held.
            guard mode.isCreating, stashTask == nil else { return }
            stashTask = Task {
                try? await Task.sleep(for: .milliseconds(500))
                guard !Task.isCancelled else { return }
                templates.stashDraft(draft)
                stashTask = nil
            }
        }
        // The guide advances on a real VALUE EDIT and on nothing else — not on scroll,
        // not on expanding a row — so it can never run away from someone still reading.
        .onChange(of: draft.plan.name) { noteEdit(reaching: 2) }
        .onChange(of: rhythmSignature) { noteEdit(reaching: 3) }
        .onChange(of: gripSignature) { noteEdit(reaching: 4) }
        .onChange(of: repsSignature) { noteEdit(reaching: 5) }
        .onChange(of: everyDaySignature) { noteEdit(reaching: 6) }
        .onDisappear {
            stashTask?.cancel()
            stashTask = nil
            undoTask?.cancel()
        }
    }

    @ViewBuilder
    private var gripPanel: some View {
        if let id = editingGrip, let index = draft.plan.sets.firstIndex(where: { $0.id == id }) {
            // No palette any more (Nuri, 2026-08-11: "lets remove the grips you use from
            // this view"). Losing it also drops a `templates.recentGrips` read from this
            // body — an observation dependency that re-ran the whole document, every set
            // row and the panel itself whenever the store resynced.
            GripIslandPanel(grip: $draft.plan.sets[index].grip) {
                editingGrip = nil
            }
        }
    }

    // MARK: Blocks

    /// THE DOCUMENT OPENS ON THE NAME (Nuri, 2026-08-19: "get rid of all the templates
    /// and just start with routine name").
    ///
    /// A START FROM row of prefill chips used to sit above this — starter plan, max day,
    /// blank, and a copy of your first routine. It cost the top of the first screenful to
    /// ask a question that only has one honest answer for somebody who already knows what
    /// they train, and answering it wrong replaced the document under you. Adding a set is
    /// two taps; a routine you did not write is not a shortcut. The seeds themselves live
    /// on in `SessionPlan` — nothing but the OFFER went away.
    private func nameBlock(_ proxy: ScrollViewProxy) -> some View {
        VStack(alignment: .leading, spacing: 10) {
            coachCard(1, proxy)
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
        .id(BuilderAnchor.name)
    }

    /// RHYTHM and LOAD share one anchor and one coach step, deliberately: they are the
    /// same kind of thing — the defaults every set inherits — and splitting them would
    /// add a sixth step to a setup whose whole pitch is that it is short.
    private func rhythmBlock(_ proxy: ScrollViewProxy) -> some View {
        VStack(alignment: .leading, spacing: 16) {
            coachCard(2, proxy)
            RhythmSection(draft: $draft)
                .tourAnchor(.builderRhythm)
        }
        .id(BuilderAnchor.rhythm)
    }

    private func setsBlock(_ proxy: ScrollViewProxy) -> some View {
        let percentBandsVary = Set(draft.plan.executable.sets.map(\.targetPercentBand)).count > 1
        return VStack(alignment: .leading, spacing: 10) {
            coachCard(3, proxy)
            // A plain row, never a `Section` header: plain-style headers PIN, and
            // content then scrolls illegibly behind a clear background.
            CapsLabel(String(localized: "SETS"))
            ForEach(Array(draft.plan.sets.enumerated()), id: \.element.id) { index, set in
                // `$draft.plan`, NOT `$draft.plan.sets[index]` — a subscript binding is
                // the one input SwiftUI can never prove unchanged, and it was costing a
                // full rebuild of every row on every keystroke typed anywhere on this
                // screen. See `SetRowView.plan`.
                SetRowView(plan: $draft.plan,
                           setID: set.id,
                           isExpanded: expanded == set.id,
                           maxes: templates.maxTable,
                           percentBandsVary: percentBandsVary,
                           onTap: { toggle(set.id) },
                           onEditGrip: { editingGrip = set.id },
                           onMoveUp: { move(index, by: -1) },
                           onMoveDown: { move(index, by: 1) },
                           onDuplicate: { duplicate(index) },
                           onRemove: { remove(at: index) })
                    // The context menu (with its compact preview) lives on the row's
                    // HEADER inside `SetRowView` — chevrons in the expanded row still
                    // cover reordering, so no drag gesture is load-bearing.
                    // A scroll target per row, so adding a set can bring its TOP into
                    // view — same id the ForEach keys identity on.
                    .id(set.id)
            }
            addSetRow(proxy)
        }
        .tourAnchor(.builderSets)
        .id(BuilderAnchor.sets)
    }

    /// Two quiet lines and, when the plan gets silly, one advisory that FLAGS and never
    /// blocks — an hour of no-hangs is a choice, not an error.
    private func totalsBlock(_ proxy: ScrollViewProxy) -> some View {
        VStack(alignment: .leading, spacing: 6) {
            coachCard(4, proxy)
            Text(PlanMath.totalsLine(draft.plan))
                .font(.system(.footnote, weight: .medium))
                .monospacedDigit()
                .contentTransition(.numericText())
                .foregroundStyle(Ink.secondary)
            if let perSide = PlanMath.perSideLine(draft.plan) {
                Text(perSide)
                    .font(.system(.footnote))
                    .monospacedDigit()
                    .contentTransition(.numericText())
                    .foregroundStyle(Ink.tertiary)
            }
            if PlanMath.totalSeconds(draft.plan) > 3600 {
                Label("That's over an hour. Fine if you mean it.", systemImage: "clock")
                    .font(.system(.footnote, weight: .medium))
                    .foregroundStyle(StatusTint.armed)
                    .padding(.top, 4)
            }
        }
        .id(BuilderAnchor.totals)
    }

    private func everyDayBlock(_ proxy: ScrollViewProxy) -> some View {
        VStack(alignment: .leading, spacing: 12) {
            coachCard(5, proxy)
            EveryDaySection(draft: $draft)
        }
        .id(BuilderAnchor.everyDay)
    }

    @ViewBuilder
    private var finishBlock: some View {
        VStack(alignment: .leading, spacing: 12) {
            if let issue = draft.validationIssue {
                Label(issue, systemImage: "exclamationmark.triangle.fill")
                    .font(.system(.footnote, weight: .medium))
                    .foregroundStyle(StatusTint.armed)
                    .fixedSize(horizontal: false, vertical: true)
            }
            if templates.saveError != nil {
                // The sheet STAYS OPEN on a rollback: dismissing on failure destroys the
                // form and the routine with it, and the traveller-facing sentence is the
                // reassurance, not the store's `localizedDescription`.
                Text("That change couldn't be saved — the routine is still here. Try again.")
                    .font(.system(.footnote, weight: .medium))
                    .foregroundStyle(Accent.alarm)
                    .fixedSize(horizontal: false, vertical: true)
            }
            if let id = mode.editingID {
                deleteRow(id)
            } else {
                if coachStep == Self.closingCoachStep { CoachClosingCard() }
                // Saves and stops. Same rule as the deck: building a routine and doing one
                // are two decisions, and Start lives on Today where you take it every
                // other day (Nuri, 2026-08-09).
                PrimaryGlassButton(title: String(localized: "Save routine"),
                                   systemImage: "checkmark",
                                   tint: Accent.graphite) {
                    save(andStart: false)
                }
                .disabled(draft.validationIssue != nil)
                .tourAnchor(.builderFinish)
            }
        }
        .id(BuilderAnchor.finish)
    }

    private func deleteRow(_ id: UUID) -> some View {
        VStack(alignment: .leading, spacing: 8) {
            // No confirmation dialog: Today arms a 10 s undo bar, and forgiveness beats
            // a dialog people learn to dismiss blindly.
            SecondaryGlassButton(title: String(localized: "Delete routine"), tint: Accent.alarm) {
                deleteRoutine(id)
            }
            Text("Past sessions keep the routine they were done with.")
                .font(.system(.footnote))
                .foregroundStyle(Ink.tertiary)
                .fixedSize(horizontal: false, vertical: true)
        }
    }

    /// Duplicates the previous set AND opens it, because editing the copy is
    /// unambiguously the next thing you will do.
    ///
    /// The RECENT rail that used to sit above this is gone (Nuri, 2026-08-11: "I don't
    /// think I like the recent holds... makes it so cluttered"). It existed to make a
    /// second grip cheap, and the island picker does that job now from inside the set
    /// itself — two shelves of the same grips, one of which you had to scroll past every
    /// time, was one too many.
    private func addSetRow(_ proxy: ScrollViewProxy) -> some View {
        Button {
            addSet(proxy)
        } label: {
            HStack(spacing: 8) {
                Image(systemName: "plus")
                Text("Add a set")
                Spacer(minLength: 0)
            }
            .font(.system(.subheadline, weight: .semibold))
            .foregroundStyle(Accent.graphite)
            .padding(.horizontal, 16)
            .frame(maxWidth: .infinity, minHeight: 50, alignment: .leading)
            .background {
                RoundedRectangle(cornerRadius: Metrics.radiusCard, style: .continuous)
                    .strokeBorder(Ink.tertiary.opacity(0.45),
                                  style: StrokeStyle(lineWidth: 1.2, dash: [5, 4]))
            }
            // MANDATORY: the label holds a Spacer and draws full-width, and a stroked
            // background contributes nothing to SwiftUI's default hit area.
            .contentShape(RoundedRectangle(cornerRadius: Metrics.radiusCard, style: .continuous))
        }
        .buttonStyle(PressFeedbackButtonStyle())
    }

    @ViewBuilder
    private var bottomBar: some View {
        VStack(spacing: 10) {
            // PROBE 1's fallback lives here; see `subtitleInNavigationBar`.
            if !Self.subtitleInNavigationBar { totalsBar }
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

    private var totalsBar: some View {
        Text(subtitleText)
            .font(.system(.footnote, weight: .medium))
            .monospacedDigit()
            .contentTransition(.numericText())
            .foregroundStyle(draft.validationIssue != nil ? StatusTint.armed : Ink.secondary)
            .padding(.horizontal, 16)
            .padding(.vertical, 9)
            .accessibleGlass(in: .capsule)
    }

    /// The live totals, ordinarily — the validation reason instead, the moment Save
    /// would refuse. See the `navigationSubtitle` comment: this is the one line both the
    /// nav-bar subtitle and its bottom-bar fallback quote, so whichever PROBE 1 picks,
    /// a disabled Save always has its explanation right beside it.
    private var subtitleText: String {
        draft.validationIssue ?? PlanMath.subtitleLine(draft.plan)
    }

    // MARK: The guide

    @ViewBuilder
    private func coachCard(_ step: Int, _ proxy: ScrollViewProxy) -> some View {
        if coachStep == step, let script = Self.script[safe: step - 1] {
            CoachCard(step: step, total: Self.coachTotal,
                      title: script.title, body: script.message,
                      onNext: { advanceCoach(to: step + 1, proxy: proxy) },
                      onSkip: { retireCoach() })
                .transition(.opacity)
        }
    }

    /// `Next` scrolls to the next section — that motion IS the step-by-step setup, with
    /// no modal sequence and nothing that can trap a tap. The card draws inline at its
    /// anchor regardless, so a `scrollTo` misfire degrades to "no auto-scroll".
    private func advanceCoach(to step: Int, proxy: ScrollViewProxy) {
        withAnimation(Motion.state(reduceMotion)) {
            coachStep = step
            proxy.scrollTo(Self.anchor(forStep: step), anchor: .top)
        }
    }

    private func retireCoach() {
        withAnimation(Motion.state(reduceMotion)) {
            coachStep = Self.retiredCoachStep
        }
        // Skipping is "not now and not next time". It stays a preference, not a one-way
        // door: Settings › Show the setup guide again puts it back.
        settings.builderGuideDone = true
    }

    /// Advance on a real value edit only, and only forwards.
    private func noteEdit(reaching step: Int) {
        guard coachStep < step, coachStep <= Self.closingCoachStep else { return }
        withAnimation(Motion.state(reduceMotion)) {
            coachStep = step
        }
    }

    private static func anchor(forStep step: Int) -> BuilderAnchor {
        switch step {
        case 1: .name
        case 2: .rhythm
        case 3: .sets
        case 4: .totals
        case 5: .everyDay
        default: .finish
        }
    }

    // MARK: Lifecycle

    private func start() {
        // `@State` cannot read the environment in `init`, so the guide's starting step
        // is set on the first frame instead — behind the sheet's own presentation
        // animation, so nothing visibly pops in.
        // CREATING only. The guide walks an empty document into a routine; opening it over
        // one that already exists narrates work already done. By the time anyone edits it
        // has been walked anyway — saving a routine sets `builderGuideDone`.
        if mode.isCreating, !settings.builderGuideDone { coachStep = 1 }

        // The rescue copy only exists if a previous session died mid-build: Save and
        // Cancel both clear it. `initialDraft` deliberately stays at the seed, so the
        // restored document counts as DIRTY and Cancel still asks before discarding it.
        if mode.isCreating, let rescued = templates.restoreDraft(), rescued != draft {
            draft = rescued
            // A rescued draft means this build was already under way in a previous
            // session, so the walkthrough has been walked. Retiring it here also keeps
            // the restore's own value changes from deciding which card to show.
            coachStep = Self.retiredCoachStep
        }
    }

    // MARK: Set mutations

    private func toggle(_ id: UUID) {
        withAnimation(Motion.state(reduceMotion)) {
            expanded = (expanded == id) ? nil : id
        }
    }

    private func addSet(_ proxy: ScrollViewProxy) {
        var new = draft.plan.sets.last ?? SetPlan()
        new.id = UUID()          // a duplicate must never share row identity with its source
        withAnimation(Motion.state(reduceMotion)) {
            draft.plan.sets.append(new)
            expanded = new.id
        }
        // NEXT runloop, once the row exists to scroll to: the tap left you parked at
        // the bottom of the list while the new set opened a screen above the button
        // (Nuri, 2026-08-10) — bring its top under the title instead.
        Task { @MainActor in
            withAnimation(Motion.state(reduceMotion)) {
                proxy.scrollTo(new.id, anchor: .top)
            }
        }
    }

    private func duplicate(_ index: Int) {
        guard draft.plan.sets.indices.contains(index) else { return }
        var copy = draft.plan.sets[index]
        copy.id = UUID()
        withAnimation(Motion.state(reduceMotion)) {
            draft.plan.sets.insert(copy, at: index + 1)
            expanded = copy.id
        }
    }

    private func move(_ index: Int, by offset: Int) {
        let target = index + offset
        guard draft.plan.sets.indices.contains(index),
              draft.plan.sets.indices.contains(target) else { return }
        withAnimation(Motion.state(reduceMotion)) {
            draft.plan.sets.swapAt(index, target)
        }
    }

    /// Removal holds the `SetPlan` WITH ITS ORIGINAL id and index: Undo has to put the
    /// same row back where it was, not an equal-looking new one two places down.
    private func remove(at index: Int) {
        guard draft.plan.sets.indices.contains(index) else { return }
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
        // The guide has done its job the moment a routine exists.
        settings.builderGuideDone = true
        // Cancel the pending stash write: without this a debounce armed half a second
        // ago re-stashes the draft the save just cleared, and it comes back as a ghost.
        stashTask?.cancel()
        onClose()
        onFinish(saved.id, andStart)
    }

    private func cancel() {
        if isDirty { showDiscard = true } else { discard() }
    }

    private func discard() {
        stashTask?.cancel()
        // A stash that outlives an explicit Cancel returns as a ghost the next time the
        // builder opens.
        templates.clearDraft()
        onClose()
    }

    private func deleteRoutine(_ id: UUID) {
        guard let template = routines.first(where: { $0.id == id }) else {
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
        mode.editingID == nil ? String(localized: "Your routine") : String(localized: "Edit routine")
    }

    /// Cheap string signatures, because `onChange` needs one Equatable value per thing
    /// the guide can react to and the draft as a whole changes on every keystroke.
    private var rhythmSignature: String {
        "\(draft.plan.setBreakSeconds)|\(draft.plan.handMode.rawValue)|\(draft.plan.waitForReleaseBeforeRest)"
    }

    private var gripSignature: String {
        draft.plan.sets.map(\.grip.key).joined(separator: ",")
    }

    private var repsSignature: String {
        draft.plan.sets.map { String($0.repsPerSide) }.joined(separator: ",")
    }

    private var everyDaySignature: String {
        "\(draft.sessionsPerDay)|\(draft.remindersEnabled)|\(draft.reminders.map(\.slot).joined(separator: "-"))"
    }

    /// PROBE 1. `true` ships the live total as the navigation subtitle; `false` moves the
    /// identical line into the bottom safe-area inset above the undo-bar slot.
    private static let subtitleInNavigationBar = true

    private struct CoachScript {
        let title: String
        let message: String
    }

    /// Five cards, in document order. Card 1 was rewritten twice for the same reason —
    /// it described a prefill (2026-08-10) and then a chooser (2026-08-19) that the
    /// document no longer has. A card naming a control that is not on screen is
    /// indistinguishable from a bug to the person reading it.
    private static let script: [CoachScript] = [
        CoachScript(title: String(localized: "Name it first"),
                    message: String(localized: "This is what Today calls it. Everything else you build underneath, and nothing is saved until you tap Save.")),
        CoachScript(title: String(localized: "What every set shares"),
                    message: String(localized: "The break between sets, how your hands split the work, and whether a rest waits for you to let go. Everything else lives on each set.")),
        CoachScript(title: String(localized: "Your sets, in order"),
                    message: String(localized: "Tap a set for its grip, its pulls, its own hold and rest, and its target. Add a set copies the last one, so a uniform routine is quick.")),
        CoachScript(title: String(localized: "How much on each side"),
                    message: String(localized: "Each set says how many pulls you do per side. The line underneath adds up your total time under tension per side.")),
        CoachScript(title: String(localized: "Ritual or whenever"),
                    message: String(localized: "A daily ritual has a target and reminders. A whenever routine just waits on Today until you feel like it.")),
    ]
}

// MARK: - Local value types

/// The one set the sheet's undo bar can put back.
private struct RemovedSet: Equatable {
    var index: Int
    var set: SetPlan
}

private extension Array {
    /// The coach script is indexed by step number; an out-of-range step must draw
    /// nothing rather than trap.
    subscript(safe index: Int) -> Element? {
        indices.contains(index) ? self[index] : nil
    }
}
