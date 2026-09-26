// SPDX-License-Identifier: MPL-2.0
// Original contributions Copyright 2026 Nuri Bruner.

import SwiftData
import SwiftUI
import UIKit

/// THE DOCUMENT — one view, one scrollable document, zero pushes.
///
/// The `NavigationStack` inside only owns the title, the live subtitle and the
/// Cancel/Save toolbar; nothing pushes onto it. Creating a routine and the 30th edit are
/// the same screen in the same order, with no second surface to keep in sync.
///
/// Document order is NAME → RHYTHM → SETS → EVERY DAY → FINE TUNING → finish/danger.
/// RHYTHM sits ABOVE the set list on purpose — constants above variables — which is what
/// makes changing every rest interval four taps.
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

    init(mode: BuilderMode, seed: RoutineDraft,
         onClose: @escaping () -> Void,
         onFinish: @escaping (UUID, Bool) -> Void) {
        self.mode = mode
        self.onClose = onClose
        self.onFinish = onFinish
        let editable = BuilderDraftPreparation.editable(seed)
        _draft = State(initialValue: editable)
        _initialDraft = State(initialValue: editable)
    }

    // MARK: Body

    var body: some View {
        NavigationStack {
            ScrollViewReader { proxy in
                // ScrollView + eager VStack, not a `List`: `List` + zero min row height +
                // accordion rows + a bottom `safeAreaInset` in a sheet was the riskiest
                // combination here. It costs swipe-to-delete and `.onMove`, both with
                // guaranteed equivalents (expanded-row chevrons, context menu, undo bar).
                //
                // NOT `LazyVStack`: adding a set's `scrollTo` must find a row below the
                // fold. A routine is a dozen rows, so eager layout is free.
                ScrollView {
                    VStack(alignment: .leading, spacing: 18) {
                        // The NAME first, in every mode (Nuri, 2026-08-19), so creating and
                        // editing open on the same first screenful.
                        nameBlock
                        rhythmBlock
                        setsBlock(proxy)
                        totalsBlock
                        everyDayBlock
                        FineTuningSection(access: access, defaults: draft.plan.routineLevel)
                            .equatable()
                        finishBlock
                    }
                    .padding(.horizontal, Metrics.hPadding)
                    .padding(.top, 12)
                    .padding(.bottom, 28)
                    // The regular-width column on an iPad: a 440 pt set list centred in a
                    // full-screen cover is a phone in a frame.
                    .frame(maxWidth: sizeClass == .regular ? Metrics.maxContentWidthRegular
                                                           : Metrics.maxContentWidth)
                    .frame(maxWidth: .infinity)
                }
                .scrollDismissesKeyboard(.interactively)
                // ALWAYS via `.background {}`, never a ZStack sibling, which disturbs the
                // ScrollView's safe-area layout.
                .background { AppBackground() }
                .scrollEdgeEffectStyle(.soft, for: .bottom)
                .navigationTitle(title)
                .navigationBarTitleDisplayMode(.inline)
                // The price of every edit, always visible, for zero document space. If this
                // ever stops rendering under an inline title, flip `subtitleInNavigationBar`
                // and the line draws in the bottom safe-area inset instead.
                //
                // **Also the disabled Save's only NEARBY explanation.** In edit mode
                // `finishBlock` never draws a primary button, so the toolbar Save is the only
                // save path, and `subtitleText` swaps to the validation issue the instant it
                // refuses, right beside it.
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

    /// THE DOCUMENT OPENS ON THE NAME (Nuri, 2026-08-19).
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

    private var rhythmBlock: some View {
        RhythmSection(access: access,
                      defaults: draft.plan.routineLevel,
                      firstSetReps: draft.plan.executable.sets.first?.repsPerSide ?? 6)
            .equatable()
    }

    private func setsBlock(_ proxy: ScrollViewProxy) -> some View {
        let percentBandsVary = Set(draft.plan.executable.sets.map(\.targetPercentBand)).count > 1
        // Once per body, not once per row: every row compares itself on this.
        let defaults = draft.plan.routineLevel
        let last = draft.plan.sets.count - 1
        return VStack(alignment: .leading, spacing: 10) {
            // A plain row, never a `Section` header: plain-style headers PIN, and
            // content then scrolls illegibly behind them.
            CapsLabel(String(localized: "SETS"))
            ForEach(Array(draft.plan.sets.enumerated()), id: \.element.id) { index, set in
                // VALUES in, one write path, and `.equatable()` so a row re-runs only when
                // its own numbers change (see `SetRowView`). Actions are keyed on the set's
                // ID, never `index`: a row whose neighbour was removed keeps its old
                // closures, and a captured index would point one row off.
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
                    // The context menu lives on the row's HEADER in `SetRowView`; chevrons
                    // cover reordering, so no drag gesture is load-bearing. The id lets adding a
                    // set scroll its TOP into view.
                    .id(set.id)
            }
            addSetRow(proxy)
        }
    }

    /// Two quiet lines and, when the plan gets silly, one advisory that FLAGS and never
    /// blocks — an hour of no-hangs is a choice, not an error.
    private var totalsBlock: some View {
        VStack(alignment: .leading, spacing: 6) {
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
            if draft.plan.executable.sets.contains(where: { $0.targetBand == nil && PlanMath.targetPercent($0, in: draft.plan) != nil }) {
                Text("Percentage targets use your saved maxes. These may no longer reflect your current strength.")
                    .font(.system(.footnote))
                    .foregroundStyle(Ink.tertiary)
                    .fixedSize(horizontal: false, vertical: true)
            }
            if PlanMath.missingBenchmarkGripCount(draft.plan, maxes: templates.maxTable) > 0 {
                Label("Some percentage targets have no saved max, so they will show no target.", systemImage: "exclamationmark.circle")
                    .font(.system(.footnote, weight: .medium))
                    .foregroundStyle(StatusTint.armed)
                    .fixedSize(horizontal: false, vertical: true)
            }
            if PlanMath.totalSeconds(draft.plan) > 3600 {
                Label("That's over an hour. Fine if you mean it.", systemImage: "clock")
                    .font(.system(.footnote, weight: .medium))
                    .foregroundStyle(StatusTint.armed)
                    .padding(.top, 4)
            }
        }
    }

    private var everyDayBlock: some View {
        EveryDaySection(access: access, schedule: draft.schedule)
            .equatable()
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
                // The sheet STAYS OPEN on a rollback: dismissing would destroy the routine
                // with the form. The plain sentence reassures; `localizedDescription` would not.
                Text("That change couldn't be saved — the routine is still here. Try again.")
                    .font(.system(.footnote, weight: .medium))
                    .foregroundStyle(Accent.alarm)
                    .fixedSize(horizontal: false, vertical: true)
            }
            if let id = mode.editingID {
                deleteRow(id)
            } else {
                // Saves and stops: building a routine and doing one are two decisions, and
                // Start lives on Today (Nuri, 2026-08-09).
                PrimaryGlassButton(title: String(localized: "Save routine"),
                                   systemImage: "checkmark",
                                   tint: Accent.graphite) {
                    save(andStart: false)
                }
                .disabled(draft.validationIssue != nil)
            }
        }
    }

    private func deleteRow(_ id: UUID) -> some View {
        VStack(alignment: .leading, spacing: 8) {
            // No confirmation dialog: Today arms a 10 s undo bar.
            SecondaryGlassButton(title: String(localized: "Delete routine"), tint: Accent.alarm) {
                deleteRoutine(id)
            }
            Text("Past sessions keep the routine they were done with.")
                .font(.system(.footnote))
                .foregroundStyle(Ink.tertiary)
                .fixedSize(horizontal: false, vertical: true)
        }
    }

    /// Duplicates the previous set AND opens it, because editing the copy is the next
    /// thing you will do.
    ///
    /// The RECENT rail above this is gone (Nuri, 2026-08-11: too cluttered); the island
    /// picker makes a second grip cheap from inside the set itself.
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
            // MANDATORY: a full-width label with a Spacer is otherwise not hit-tested
            // outside its glyphs.
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

    /// The live totals, or the validation reason the moment Save would refuse. Both the
    /// nav-bar subtitle and its bottom-bar fallback quote this line, so a disabled Save
    /// always has its explanation beside it.
    private var subtitleText: String {
        draft.validationIssue ?? PlanMath.subtitleLine(draft.plan)
    }

    // MARK: Lifecycle

    private func start() {
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
        var new = draft.plan.sets.last ?? SetPlan()
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
        mode.editingID == nil ? String(localized: "Your routine") : String(localized: "Edit routine")
    }

    /// PROBE 1. `true` ships the live total as the navigation subtitle; `false` moves the
    /// identical line into the bottom safe-area inset above the undo-bar slot.
    private static let subtitleInNavigationBar = true
}

// MARK: - Local value types

/// The one set the sheet's undo bar can put back.
private struct RemovedSet: Equatable {
    var index: Int
    var set: SetPlan
}
