// SPDX-License-Identifier: MPL-2.0
// Original contributions Copyright 2026 Nuri Bruner.

import SwiftData
import SwiftUI

/// Saved records for one grip, grouped by hand. Swipe reveals deletion; removing a
/// current record restores the preceding record as that hand's working max.
struct MaxesView: View {
    let grip: GripSpec
    @Environment(\.weightUnit) private var weightUnit
    /// Newest first — which is also what makes the fold below correct. Grips come out in
    /// order of their most recent record, and the first record in each bucket is that
    /// grip's current max. "Current" is the NEWEST, never the biggest: a max that has
    /// come down is still the number today's percentages have to be taken from.
    @Query(sort: [SortDescriptor(\MaxRecord.recordedAt, order: .reverse)])
    private var records: [MaxRecord]

    @Environment(TemplateStore.self) private var templates
    @Environment(\.accessibilityReduceMotion) private var reduceMotion

    @State private var deleteFailed = false
    /// The grip key whose earlier records are showing — at most one open at a time, the
    /// same accordion rule the builder's set rows follow.
    @State private var expanded: String?

    var body: some View {
        let foldedHistories = histories
        let rowEntries = entries(from: foldedHistories)
        // A real `List`, unlike the builder's document: swipe-to-delete, the row-slide
        // physics and the full-height separatorless rows all come from UIKit, and a
        // hand-rolled drag gesture never matches them. Nothing here needs `scrollTo`,
        // which is the one thing that forced the builder onto a ScrollView.
        List {
            if foldedHistories.isEmpty {
                emptyCard.houseListRow(top: 12, bottom: 10)
            }

            if deleteFailed {
                Text("Couldn't delete this max. Please try again.")
                    .font(.system(.footnote))
                    .foregroundStyle(Accent.alarm)
                    .houseListRow(top: 2, bottom: 6)
            }

            if !foldedHistories.isEmpty {
                // A plain row, never a `Section` header: plain-style headers PIN, and the
                // content then scrolls illegibly behind a clear background.
                CapsLabel(String(localized: "YOUR MAXES")).houseListRow(top: 10, bottom: 2)

                ForEach(rowEntries) { entry in
                    row(for: entry)
                }

                footnote.houseListRow(top: 14, bottom: 24)
            }
        }
        .listStyle(.plain)
        // The list draws its own rows on the slate field; the system's grouped fill would
        // sit between the material cards and the background they are meant to float on.
        .scrollContentBackground(.hidden)
        // ALWAYS via `.background {}`, never as a ZStack sibling — as a sibling it
        // disturbs the safe-area layout and the title creeps under the status bar.
        .background { AppBackground() }
        .scrollEdgeEffectStyle(.soft, for: .bottom)
        .navigationTitle("Earlier records")
        .navigationSubtitle(grip.displayName)
        .sensoryFeedback(.selection, trigger: expanded)

    }

    // MARK: - Rows

    @ViewBuilder
    private func row(for entry: MaxEntry) -> some View {
        switch entry {
        case .current(let history):
            gripRow(history)
                .houseListRow()
                .swipeActions(edge: .trailing, allowsFullSwipe: false) {
                    // Names the HAND: with a left and a right row for one grip, a label
                    // that only said the grip would be the same on both.
                    deleteButton(history.current,
                                 label: String(localized: "Delete this max for \(history.grip.spoken)\(history.current.side == .both ? "" : String(localized: ", \(history.current.side.name.lowercased()) hand"))"))
                }
        case .earlier(let record, let grip):
            earlierRow(record, grip: grip)
                .houseListRow(top: 2, bottom: 2)
                .swipeActions(edge: .trailing, allowsFullSwipe: false) {
                    deleteButton(record, label: String(localized: "Delete this earlier max for \(grip.spoken)"))
                }
        }
    }

    /// One grip, its current max, and — when there is a past — a tap that reveals it.
    ///
    /// The row is only a Button when there is something to open. A control that draws
    /// like a control and does nothing is worse than a plain row.
    @ViewBuilder
    private func gripRow(_ history: GripHistory) -> some View {
        if history.earlier.isEmpty {
            gripFace(history)
        } else {
            Button {
                withAnimation(Motion.state(reduceMotion)) {
                    expanded = (expanded == history.key) ? nil : history.key
                }
            } label: {
                gripFace(history)
            }
            // `.plain`, like the builder's set rows: a row-sized card that scaled on
            // press would drag its own material backdrop out from under it.
            .buttonStyle(PressFeedbackButtonStyle(scales: false))
            .accessibilityHint("Shows the earlier maxes for this grip")
        }
    }

    private func gripFace(_ history: GripHistory) -> some View {
        HStack(alignment: .top, spacing: 12) {
            // Plain DESIGN sizes: FingerGlyph scales `dot`/`gap` itself, so pre-scaling
            // them here would apply Dynamic Type twice.
            FingerGlyph(fingers: history.grip.fingers, position: history.grip.position,
                        dot: 6, gap: 3)
                .padding(.top, 5)

            VStack(alignment: .leading, spacing: 3) {
                Text(history.grip.line)
                    .font(.system(.subheadline, weight: .semibold))
                    .monospacedDigit()
                    .foregroundStyle(Ink.primary)
                    .lineLimit(2)
                Text(detailLine(history))
                    .font(.system(.footnote))
                    .foregroundStyle(Ink.secondary)
            }

            Spacer(minLength: 8)

            weight(history.current.kg, prominent: true)

            if !history.earlier.isEmpty {
                Image(systemName: "chevron.down")
                    .font(.system(.caption, weight: .semibold))
                    .foregroundStyle(Ink.tertiary)
                    .rotationEffect(.degrees(expanded == history.key ? 0 : -90))
                    .padding(.top, 4)
                    .accessibilityHidden(true)
            }
        }
        .padding(16)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(.regularMaterial,
                    in: RoundedRectangle(cornerRadius: Metrics.radiusCard, style: .continuous))
        // MANDATORY when this face is a Button's label: it holds a Spacer and draws
        // full-width, and SwiftUI's default hit area is the label's OPAQUE content — the
        // material and the padding contribute nothing to it.
        .contentShape(RoundedRectangle(cornerRadius: Metrics.radiusCard, style: .continuous))
        .accessibilityElement(children: .ignore)
        .accessibilityLabel(spokenGrip(history))
    }

    /// A superseded record: indented, quieter, and dated, so the current one keeps the
    /// row's weight. It is here to be READ and to be DELETABLE — nothing more.
    private func earlierRow(_ record: MaxRecord, grip: GripSpec) -> some View {
        HStack(spacing: 12) {
            Rectangle()
                .fill(Ink.tertiary.opacity(0.35))
                .frame(width: 2, height: 16)
                .accessibilityHidden(true)

            Text(when(record))
                .font(.system(.footnote))
                .foregroundStyle(Ink.secondary)

            Spacer(minLength: 8)

            weight(record.kg, prominent: false)
        }
        .padding(.horizontal, 16)
        .padding(.vertical, 11)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(.regularMaterial,
                    in: RoundedRectangle(cornerRadius: Metrics.radiusInner, style: .continuous))
        // OUTSIDE the background, so the indent is empty space rather than a wider card
        // with its content pushed over.
        .padding(.leading, 22)
        .accessibilityElement(children: .ignore)
        .accessibilityLabel(String(localized: "Earlier max for \(grip.spoken). \(weightUnit.number(record.kg)) \(weightUnit.spokenName), recorded \(when(record))."))
    }

    private func weight(_ kg: Double, prominent: Bool) -> some View {
        HStack(alignment: .firstTextBaseline, spacing: 2) {
            Text(weightUnit.number(kg))
                .font(.system(prominent ? .title3 : .subheadline,
                              weight: prominent ? .semibold : .medium))
                .monospacedDigit()
                .contentTransition(.numericText())
                .foregroundStyle(prominent ? Ink.primary : Ink.secondary)
            Text(weightUnit.symbol)
                .font(.system(.caption))
                .foregroundStyle(Ink.tertiary)
        }
        .lineLimit(1)
        .minimumScaleFactor(0.7)
    }

    private func deleteButton(_ record: MaxRecord, label: String) -> some View {
        // Full swipe is deliberately OFF here, and that is the one place this screen
        // departs from the house gesture. Everywhere else a full swipe is safe because a
        // ten-second Undo bar catches it — but `recordMax` always stamps `recordedAt` as
        // NOW, so putting a max back would move it to today and quietly rewrite when it
        // was pulled. Two deliberate actions instead of one undoable one is the honest
        // trade until the store can restore a record with its own date.
        Button(role: .destructive) {
            deleteFailed = !templates.deleteMax(record)
        } label: {
            Label("Delete", systemImage: "trash")
        }
        .tint(Accent.alarm)
        .accessibilityLabel(label)
    }

    // MARK: - Chrome

    private var emptyCard: some View {
        MaterialCard {
            VStack(alignment: .leading, spacing: 12) {
                CapsLabel(String(localized: "NO MAXES YET"))

                FingerGlyph(fingers: .four, position: .halfCrimp, dot: 12, gap: 6,
                            tint: Ink.tertiary.opacity(0.55))
                    .accessibilityHidden(true)

                Text("A max records your hardest measured pull on a grip.")
                    .font(.system(.title3, weight: .semibold))
                    .foregroundStyle(Ink.primary)
                    .fixedSize(horizontal: false, vertical: true)

                Text("Percentage targets use the max saved for that grip and hand. A saved max is a reference, not a safe-load limit.")
                    .font(.system(.footnote, weight: .medium))
                    .foregroundStyle(Ink.secondary)
                    .fixedSize(horizontal: false, vertical: true)

                Text("Measure a peak on your gauge, or record a previous measurement. You can update it whenever you need.")
                    .font(.system(.footnote))
                    .foregroundStyle(Ink.tertiary)
                    .fixedSize(horizontal: false, vertical: true)
            }
            .fixedSize(horizontal: false, vertical: true)
        }
    }

    /// Provenance, stated plainly — and it must stay plain now that BOTH sources exist.
    /// The rows carry the distinction individually ("measured"), so this only has to say
    /// that the distinction is there and what these numbers are for.
    private var footnote: some View {
        Text("A max is either measured on the gauge or set by you — the measured ones say so. The percentages elsewhere in the app are worked out from these.")
            .font(.system(.footnote))
            .foregroundStyle(Ink.tertiary)
            .fixedSize(horizontal: false, vertical: true)
    }

    // MARK: - Derived

    /// One bucket per grip **AND HAND**, in order of each bucket's most recent record.
    /// Cheap — a handful of records — and it walks `records` exactly once.
    ///
    /// Bucketing on the grip alone would file your left and right maxes together, make
    /// whichever you recorded second "current", and demote the other to history — so one
    /// hand's number would vanish from the screen it was entered on. They are separate
    /// rows because they are separate facts.
    private var histories: [GripHistory] {
        var order: [String] = []
        var buckets: [String: [MaxRecord]] = [:]
        for record in records where record.gripKey == grip.key { // already newest-first
            let key = record.maxKey
            if buckets[key] == nil { order.append(key) }
            buckets[key, default: []].append(record)
        }
        return order.compactMap { key in
            guard let bucket = buckets[key], let current = bucket.first else { return nil }
            return GripHistory(key: key, grip: current.grip,
                               current: current, earlier: Array(bucket.dropFirst()))
        }
    }

    /// Flattened to ONE entry per list row on purpose: a `ForEach` element that renders
    /// two rows leaves it ambiguous which row a `.swipeActions` belongs to.
    private func entries(from histories: [GripHistory]) -> [MaxEntry] {
        histories.flatMap { history -> [MaxEntry] in
            guard expanded == history.key else { return [.current(history)] }
            return [.current(history)]
                + history.earlier.map { MaxEntry.earlier($0, history.grip) }
        }
    }

    /// Only the DEPARTURES from the default are marked — a named hand, and a measured
    /// provenance. "Both hands" and "typed" are the unremarkable cases, and labelling
    /// them would put two words on every row to distinguish nothing.
    private func detailLine(_ history: GripHistory) -> String {
        var parts = [when(history.current)]
        if history.current.side != .both {
            parts.append(String(localized: "\(history.current.side.name.lowercased()) hand"))
        }
        if history.current.source == .measured { parts.append(String(localized: "measured")) }
        if !history.earlier.isEmpty { parts.append(String(localized: "\(history.earlier.count) earlier")) }
        return parts.joined(separator: " · ")
    }

    private func spokenGrip(_ history: GripHistory) -> String {
        let hand = history.current.side == .both
            ? "" : String(localized: ", \(history.current.side.name.lowercased()) hand")
        let provenance = history.current.source == .measured
            ? String(localized: "measured ") : String(localized: "recorded ")
        var sentence = String(localized: "\(history.grip.spoken)\(hand). Max \(weightUnit.number(history.current.kg)) \(weightUnit.spokenName), \(provenance)\(when(history.current)).")
        if !history.earlier.isEmpty {
            let count = history.earlier.count
            sentence += String(localized: " \(count) earlier \(count == 1 ? String(localized: "max") : String(localized: "maxes")).")
        }
        return sentence
    }

    private func when(_ record: MaxRecord) -> String {
        record.recordedAt.formatted(.relative(presentation: .named))
    }
}

// MARK: - Row model

/// One grip's records: the newest, and everything it superseded.
private struct GripHistory {
    let key: String
    let grip: GripSpec
    let current: MaxRecord
    let earlier: [MaxRecord]
}

/// A single list row. The two cases look genuinely different and carry different swipe
/// actions, so they stay separate rather than sharing one row builder with flags.
private enum MaxEntry: Identifiable {
    case current(GripHistory)
    case earlier(MaxRecord, GripSpec)

    var id: String {
        switch self {
        case .current(let history):  "grip-\(history.key)"
        case .earlier(let record, _): "record-\(record.id.uuidString)"
        }
    }
}

// MARK: - The composer

/// The legacy shared benchmark remains editable separately from individual hands.
/// Its explicit weight-target rescale receipt is preserved; no automatic rewriting.
struct MaxEntrySheet: View {
    @Environment(\.weightUnit) private var weightUnit
    @Environment(TemplateStore.self) private var templates
    @Environment(\.accessibilityReduceMotion) private var reduceMotion

    /// Closing belongs to the presenter. Reading the environment dismiss action here
    /// changes its identity with focus, which rebuilt this whole typing-heavy sheet on
    /// every keystroke — the same measured failure the routine builder avoids.
    var onSaved: (() -> Void)?
    var onClose: () -> Void

    /// A VALUE, edited freely and written exactly once on Save — same shape as the
    /// builder's draft, and for the same reason: Cancel IS undo, and a dragged slider
    /// cannot fire dozens of CloudKit writes.
    @State private var grip: GripSpec
    @State private var kg: Double = 0
    @State private var loaded = false
    @State private var failed = false
    @State private var savedTick = 0
    /// Defaults to `.both`, which is what an untouched picker has always meant and what
    /// every record written before hands existed means. Nothing here is required.
    @State private var side: Side = .both
    /// Set the moment Save lands with anything to report; the sheet then shows the
    /// receipt instead of dismissing. nil = still editing.
    @State private var impact: TemplateStore.MaxImpact?

    init(seed: GripSpec, side: Side = .both, onSaved: (() -> Void)? = nil, onClose: @escaping () -> Void) {
        self.onClose = onClose
        self.onSaved = onSaved
        _grip = State(initialValue: seed)
        _side = State(initialValue: side)
    }

    var body: some View {
        NavigationStack {
            ScrollView {
                if let impact {
                    impactContent(impact)
                } else {
                    formContent
                }
            }
            .scrollDismissesKeyboard(.interactively)
            .background { AppBackground() }
            .scrollEdgeEffectStyle(.soft, for: .bottom)
            .navigationTitle(impact == nil ? "Shared max" : "Saved")
            .navigationBarTitleDisplayMode(.inline)
            // The grip as it currently stands, live — so the thing being recorded is
            // stated somewhere fixed while you are three controls deep changing it.
            .navigationSubtitle(grip.displayName)
            .toolbar {
                if impact == nil {
                    ToolbarItem(placement: .cancellationAction) {
                        Button("Cancel") { onClose() }
                    }
                    ToolbarItem(placement: .confirmationAction) {
                        Button("Save") { save() }
                            .bold()
                            // `recordMax` rejects zero outright — a 0 kg max would make
                            // every percentage caption in the app divide by nothing.
                            .disabled(kg <= 0)
                    }
                } else {
                    // The max is already SAVED — there is no cancel any more, and the
                    // kg offer's "Leave them" is a button in the content, not chrome.
                    ToolbarItem(placement: .confirmationAction) {
                        Button("Done") { onClose() }.bold()
                    }
                }
            }

        }
        .sensoryFeedback(.success, trigger: savedTick)
        .onAppear {
            if !loaded {
                kg = templates.maxTable.exact(grip: grip.key, side: side) ?? 0
                loaded = true
            }
        }
    }

    /// The editing form — everything the sheet is until Save lands.
    private var formContent: some View {
        VStack(alignment: .leading, spacing: 18) {
            HStack(spacing: 16) {
                FingerGlyph(fingers: grip.fingers, position: grip.position, dot: 9, gap: 4)
                Text(grip.displayName)
                    .font(.system(.headline))
            }
            Text("Used when a hand has no individual max, and for pulls with both hands together. Individual left and right maxes stay unchanged.")
                .font(.system(.subheadline))
                .foregroundStyle(Ink.secondary)
                .fixedSize(horizontal: false, vertical: true)

            // No section label above it: the row states its own subject, and a
            // "MAX" caps label over a row titled "Max on this grip" is the same
            // word twice in eighteen points of height.
            //
            // The slider spans 0…100, NOT the old 0…60 (Nuri, 2026-08-04: "there
            // are people who can do a 20 mil edge much more than 60 kg"). 60 was
            // never a storage limit — typing already reached 200 — but a slider
            // that stops is read as a ceiling, and being told your max is
            // off-scale is a poor welcome. 100 keeps a typical 25 kg pull at a
            // quarter of the track, which is still a usable drag.
            ValueRow(title: String(localized: "Max on this grip"), unit: weightUnit.symbol, value: weightUnit.binding($kg),
                     range: weightUnit.sliderRangeFromKg(0...100), limit: weightUnit.rangeFromKg(0...250), step: 0.5, decimals: 1,
                     caption: bandCaption)

            existingLine
            if failed { errorLine }
            provenanceLine
        }
        .padding(.horizontal, Metrics.hPadding)
        .padding(.top, 12)
        .padding(.bottom, 28)
        .frame(maxWidth: Metrics.maxContentWidth)
        .frame(maxWidth: .infinity)
    }

    /// **What the number you just saved moves** — shown INSTEAD of dismissing, and only
    /// when there is something to say. Two sections with two different verbs: percent
    /// bands already moved (they follow the newest max by design — this is visibility,
    /// not a question), while typed-kilogram sets are OFFERED a rescale, because a
    /// number a person typed is never rewritten by arithmetic without a yes.
    private func impactContent(_ impact: TemplateStore.MaxImpact) -> some View {
        VStack(alignment: .leading, spacing: 18) {
            block(String(localized: "SAVED")) {
                HStack(alignment: .firstTextBaseline, spacing: 6) {
                    Text(weightUnit.number(kg))
                        .font(.system(.largeTitle, weight: .semibold))
                        .monospacedDigit()
                        .foregroundStyle(Ink.primary)
                    Text("\(weightUnit.symbol) · \(grip.displayName)")
                        .font(.system(.subheadline))
                        .foregroundStyle(Ink.secondary)
                }
            }

            if !impact.percentMoves.isEmpty {
                block(String(localized: "TARGETS THAT FOLLOWED")) {
                    VStack(alignment: .leading, spacing: 10) {
                        ForEach(impact.percentMoves) { move in
                            VStack(alignment: .leading, spacing: 2) {
                                Text(move.side == .both ? move.routineName : "\(move.routineName) · \(move.side.name)")
                                    .font(.system(.subheadline, weight: .semibold))
                                    .foregroundStyle(Ink.primary)
                                Text(move.line(unit: weightUnit))
                                    .font(.system(.footnote))
                                    .monospacedDigit()
                                    .foregroundStyle(Ink.secondary)
                            }
                        }
                        Text("Percent targets always follow your newest max — nothing to do.")
                            .font(.system(.footnote))
                            .foregroundStyle(Ink.tertiary)
                            .fixedSize(horizontal: false, vertical: true)
                    }
                }
            }

            if !impact.kgOffers.isEmpty, let ratio = impact.ratio {
                block(String(localized: "Weight targets").uppercased()) {
                    VStack(alignment: .leading, spacing: 10) {
                        ForEach(impact.kgOffers) { offer in
                            VStack(alignment: .leading, spacing: 2) {
                                Text(offer.routineName)
                                    .font(.system(.subheadline, weight: .semibold))
                                    .foregroundStyle(Ink.primary)
                                ForEach(offer.moves, id: \.self) { move in
                                    Text(String(localized: "\(weightUnit.bandText(move.oldBand, withUnit: false)) \(weightUnit.symbol)  →  \(weightUnit.bandText(move.newBand, withUnit: false)) \(weightUnit.symbol)"))
                                        .font(.system(.footnote))
                                        .monospacedDigit()
                                        .foregroundStyle(Ink.secondary)
                                }
                            }
                        }
                        Text("These were typed by hand, so they never move on their own. Scale them with the new max, or leave them.")
                            .font(.system(.footnote))
                            .foregroundStyle(Ink.tertiary)
                            .fixedSize(horizontal: false, vertical: true)

                        PrimaryGlassButton(title: String(localized: "Scale with the new max"),
                                           tint: Accent.graphite) {
                            templates.scaleKgTargets(
                                grip: grip, ratio: ratio,
                                routineIDs: impact.kgOffers.map(\.routineID))
                            savedTick += 1
                            onClose()
                        }
                        Button("Leave them as they are") { onClose() }
                            .font(.system(.footnote, weight: .semibold))
                            .foregroundStyle(Accent.graphite)
                            .frame(maxWidth: .infinity, minHeight: 44)
                            .contentShape(.rect)
                            .buttonStyle(PressFeedbackButtonStyle())
                    }
                }
            }
        }
        .padding(.horizontal, Metrics.hPadding)
        .padding(.top, 12)
        .padding(.bottom, 28)
        .frame(maxWidth: Metrics.maxContentWidth)
        .frame(maxWidth: .infinity)
    }

    @ViewBuilder
    private func block<Content: View>(_ label: String,
                                      @ViewBuilder content: () -> Content) -> some View {
        VStack(alignment: .leading, spacing: 8) {
            CapsLabel(label)
            content()
        }
    }

    /// What this max BUYS you, stated while you are still setting it: the low-intensity
    /// band is the reason the number is being asked for at all.
    ///
    /// **Also the disabled Save's only explanation.** Save disables on `kg <= 0`, and
    /// `suggestedBand` returns nil for exactly that range — so before this an empty
    /// "Add a max" sheet showed a dimmed Save with nothing on screen to say why.
    private var bandCaption: String? {
        guard kg > 0 else { return String(localized: "Enter a max above zero to save it.") }
        guard let band = PlanMath.suggestedBand(maxKg: kg) else { return nil }
        return String(localized: "20–30 % of that is \(weightUnit.number(band.lowerBound))–\(weightUnit.number(band.upperBound)) \(weightUnit.symbol)")
    }

    /// Append, never edit — so the sheet says so before you tap Save rather than leaving
    /// you to discover a second row afterwards.
    @ViewBuilder
    private var existingLine: some View {
        // Keyed by grip AND hand: the record this save supersedes is the one for the
        // SAME hand, and quoting the other hand's number here would read as a
        // contradiction of what you are about to type.
        if let existing = templates.currentMaxes[MaxTable.key(grip: grip.key, side: side)] {
            Text(String(localized: "Your current max on this grip\(side == .both ? "" : String(localized: " for that hand")) is \(weightUnit.number(existing.kg)) \(weightUnit.symbol), recorded \(existing.recordedAt.formatted(.relative(presentation: .named))). Saving adds a new one and keeps the old as history."))
                .font(.system(.footnote))
                .foregroundStyle(Ink.secondary)
                .fixedSize(horizontal: false, vertical: true)
        }
    }

    /// A rolled-back save leaves the sheet OPEN with the error inline — dismissing on
    /// failure destroys the form and the number with it.
    private var errorLine: some View {
        Text("That couldn't be saved — nothing was recorded. Try again.")
            .font(.system(.footnote, weight: .medium))
            .foregroundStyle(Accent.alarm)
            .fixedSize(horizontal: false, vertical: true)
    }

    /// Says which of the two this number IS, and keeps saying it as the number changes.
    /// It used to read "Doigt does not measure it for you", which was load-bearing while
    /// that was true and would be a lie the moment measuring shipped — so it moved with
    /// the feature rather than being deleted by it.
    private var provenanceLine: some View {
        Text("A number you entered. Check its value and units before using it for targets.")
            .font(.system(.footnote))
            .foregroundStyle(Ink.tertiary)
            .fixedSize(horizontal: false, vertical: true)
    }

    // MARK: Commit

    private func save() {
        guard kg > 0 else { return }
        failed = false
        // Asked BEFORE the record lands — afterwards the old max is just history and
        // the ratio it anchors is gone.
        let previousMaxes = templates.maxTable
        guard templates.recordMax(kg, for: grip, source: .manual, side: side) else {
            failed = true
            return
        }
        savedTick += 1
        onSaved?()
        let computed = templates.maxImpact(grip: grip, previousMaxes: previousMaxes, newKg: kg, side: side)
        if computed.isEmpty {
            onClose()
        } else {
            // The sheet becomes the receipt: what followed, and what is on offer.
            withAnimation(Motion.state(reduceMotion)) { impact = computed }
        }
    }
}

// Row chrome is `.houseListRow` in ScreenScaffold.swift — shared with History, which
// wears the same cards-in-a-List shape for the same reason.

// Deliberately no `#Preview`: this screen needs a `ModelContainer` for `@Query` and a
// `TemplateStore` in the environment, and a preview that traps on launch is worse than
// none. Same as every other screen in `Sources/UI` — previews live on the components.
