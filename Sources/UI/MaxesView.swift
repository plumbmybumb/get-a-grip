// SPDX-License-Identifier: MPL-2.0
// Original contributions Copyright 2026 Nuri Bruner.

import SwiftData
import SwiftUI

/// The screen that makes "about 25 % of your max on this grip" mean something.
///
/// Until this existed, every percentage caption in the set editor read *"Add your max
/// for this grip to see percentages"* — an instruction with nowhere to carry it out.
/// The whole low-intensity prescription this app is built around is a FRACTION (a fifth
/// to a third), and a fraction with no denominator is not advice, it is a blank.
///
/// Two decisions worth stating, because both look like omissions:
///
/// **This is not a grip library.** There is no saved-grip list to curate, no folder, no
/// "create grip" step — the thing Doigt exists to refuse. The composer opens on a grip
/// you already use (`TemplateStore.recentGrips`, which falls back to the seed palette so
/// the rail works on day one) and every field is editable in place. A grip is still a
/// VALUE; the rail is a shortcut to typing one, not a record of one.
///
/// **Nothing is ever edited.** `MaxRecord` is append-only, so recording again for the
/// same grip key adds a row and the previous one becomes history. That is why a grip row
/// with a past behind it opens: without somewhere to show the earlier records, they
/// would exist, count for nothing, and be impossible to delete.
struct MaxesView: View {
    /// Newest first — which is also what makes the fold below correct. Grips come out in
    /// order of their most recent record, and the first record in each bucket is that
    /// grip's current max. "Current" is the NEWEST, never the biggest: a max that has
    /// come down is still the number today's percentages have to be taken from.
    @Query(sort: [SortDescriptor(\MaxRecord.recordedAt, order: .reverse)])
    private var records: [MaxRecord]

    @Environment(TemplateStore.self) private var templates
    @Environment(\.accessibilityReduceMotion) private var reduceMotion

    @State private var composing = false
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

            addRow.houseListRow(top: foldedHistories.isEmpty ? 2 : 12, bottom: 6)

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
        .navigationTitle("Manage maxes")
        .navigationSubtitle("Your max on each grip")
        .sensoryFeedback(.selection, trigger: expanded)
        .sheet(isPresented: $composing) {
            // Seeded from a grip already in the user's routines, so the composer opens on
            // something real rather than on an arbitrary default.
            MaxEntrySheet(seed: templates.recentGrips.first ?? GripSpec()) {
                composing = false
            }
        }
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
        .accessibilityLabel(String(localized: "Earlier max for \(grip.spoken). \(kgText(record.kg)) kilograms, recorded \(when(record))."))
    }

    private func weight(_ kg: Double, prominent: Bool) -> some View {
        HStack(alignment: .firstTextBaseline, spacing: 2) {
            Text(kg, format: .number.precision(.fractionLength(1)))
                .font(.system(prominent ? .title3 : .subheadline,
                              weight: prominent ? .semibold : .medium))
                .monospacedDigit()
                .contentTransition(.numericText())
                .foregroundStyle(prominent ? Ink.primary : Ink.secondary)
            Text("kg")
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
            _ = templates.deleteMax(record)
        } label: {
            Label("Delete", systemImage: "trash")
        }
        .tint(Accent.alarm)
        .accessibilityLabel(label)
    }

    // MARK: - Chrome

    /// The screen's primary action, drawn as a row rather than hidden behind a toolbar
    /// glyph — on a screen you visit in order to add something, the add must be the first
    /// thing under your thumb and must never scroll away behind a list.
    private var addRow: some View {
        Button {
            composing = true
        } label: {
            HStack(spacing: 8) {
                Image(systemName: "plus")
                Text("Add a max")
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
        .accessibilityLabel("Add a max")
    }

    /// Plain language, no jargon, and honest about what the app cannot do for you.
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
        for record in records {                     // already newest-first
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
        var sentence = String(localized: "\(history.grip.spoken)\(hand). Max \(kgText(history.current.kg)) kilograms, \(provenance)\(when(history.current)).")
        if !history.earlier.isEmpty {
            let count = history.earlier.count
            sentence += String(localized: " \(count) earlier \(count == 1 ? String(localized: "max") : String(localized: "maxes")).")
        }
        return sentence
    }

    private func when(_ record: MaxRecord) -> String {
        record.recordedAt.formatted(.relative(presentation: .named))
    }

    private func kgText(_ kg: Double) -> String {
        kg.formatted(.number.precision(.fractionLength(1)))
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

/// Adding a max, as one short document rather than a picker followed by a form.
///
/// Every control here is the same one the builder uses for the same job, deliberately:
/// the edge is an `IntValueRow`, the fingers are `FingerPips`, the position is a
/// `PositionChipRow`. Someone who has built a routine has already learned this screen.
///
/// Internal, not file-private: the Maxes TAB presents this same sheet from its
/// "Measure again" buttons — one composer, however you arrive, so the side chips and
/// the measured-vs-typed provenance rules can never fork.
struct MaxEntrySheet: View {
    @Environment(TemplateStore.self) private var templates
    @Environment(\.accessibilityReduceMotion) private var reduceMotion

    /// Closing belongs to the presenter. Reading the environment dismiss action here
    /// changes its identity with focus, which rebuilt this whole typing-heavy sheet on
    /// every keystroke — the same measured failure the routine builder avoids.
    var onClose: () -> Void

    /// A VALUE, edited freely and written exactly once on Save — same shape as the
    /// builder's draft, and for the same reason: Cancel IS undo, and a dragged slider
    /// cannot fire dozens of CloudKit writes.
    @State private var grip: GripSpec
    @State private var kg: Double = 0
    @State private var failed = false
    @State private var savedTick = 0
    @State private var measuring = false
    /// Defaults to `.both`, which is what an untouched picker has always meant and what
    /// every record written before hands existed means. Nothing here is required.
    @State private var side: Side = .both
    /// What the gauge last handed back, if anything. Provenance is derived by COMPARING
    /// it to the live value rather than by a flag, which makes the answer self-correcting:
    /// drag or type the number away from what was measured and the record honestly
    /// becomes `.manual` again, with no ordering rules about which `onChange` runs first.
    @State private var measuredKg: Double?
    /// Set the moment Save lands with anything to report; the sheet then shows the
    /// receipt instead of dismissing. nil = still editing.
    @State private var impact: TemplateStore.MaxImpact?

    init(seed: GripSpec, onClose: @escaping () -> Void) {
        self.onClose = onClose
        _grip = State(initialValue: seed)
    }

    /// `.measured` ONLY while the value still is the one the gauge produced — see
    /// `measuredKg`. The distinction is on the row afterwards, so it has to be earned.
    private var source: MaxSource {
        measuredKg == kg ? .measured : .manual
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
            .navigationTitle(impact == nil ? "New max" : "Saved")
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
            // Full screen, not a nested sheet: you are hanging off a fingerboard while
            // it is up, and it has to be readable across the room. See `MaxMeasureView`.
            .fullScreenCover(isPresented: $measuring) {
                TrainingAgreementGate(onCancel: { measuring = false }) {
                    MaxMeasureView(grip: grip) { measured in
                        kg = measured
                        measuredKg = measured
                    }
                }
            }
        }
        .sensoryFeedback(.success, trigger: savedTick)
    }

    /// WHICH HAND this max is for. Visible rather than folded behind a disclosure: it is
    /// pre-answered with "Both hands", so it costs nothing to ignore, and a control
    /// hidden behind a chevron is one nobody discovers — which would waste the whole
    /// feature on the people whose hands differ enough to need it.
    ///
    /// The caption is where the consequence lives, because the chips cannot say it: a
    /// side-specific max is a statement that your OTHER hand is different, and it stops
    /// applying to that hand the moment you pick one.
    private var handBlock: some View {
        VStack(alignment: .leading, spacing: 8) {
            CapsLabel(String(localized: "THIS MAX IS FOR"))
            MaxSideChipRow(selection: $side)
            Text(side == .both
                 ? String(localized: "Used for both hands. Pick a hand if yours differ — most people's do.")
                 : String(localized: "Only your \(side.name.lowercased()) hand. Its targets come from this number; your other hand needs its own."))
                .font(.system(.footnote))
                .foregroundStyle(Ink.tertiary)
                .fixedSize(horizontal: false, vertical: true)
        }
    }

    /// The editing form — everything the sheet is until Save lands.
    private var formContent: some View {
        VStack(alignment: .leading, spacing: 18) {
            gripRail

            IntValueRow(title: String(localized: "Edge"), unit: String(localized: "mm"), value: $grip.edgeMM,
                        range: 4...45, limit: GripSpec.edgeRange,
                        presets: [6, 10, 20, 30])

            block(String(localized: "FINGERS")) {
                FingerPips(fingers: $grip.fingers, position: grip.position)
            }

            block(String(localized: "GRIP")) {
                PositionChipRow(selection: $grip.position)
            }

            Divider().overlay(Ink.tertiary.opacity(0.22))

            handBlock

            measureRow

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
            ValueRow(title: String(localized: "Max on this grip"), unit: String(localized: "kg"), value: $kg,
                     range: 0...100, limit: 0...250, step: 0.5, decimals: 1,
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
                    Text(kgText(kg))
                        .font(.system(.largeTitle, weight: .semibold))
                        .monospacedDigit()
                        .foregroundStyle(Ink.primary)
                    Text("kg · \(grip.displayName)")
                        .font(.system(.subheadline))
                        .foregroundStyle(Ink.secondary)
                }
            }

            if !impact.percentMoves.isEmpty {
                block(String(localized: "TARGETS THAT FOLLOWED")) {
                    VStack(alignment: .leading, spacing: 10) {
                        ForEach(impact.percentMoves) { move in
                            VStack(alignment: .leading, spacing: 2) {
                                Text(move.routineName)
                                    .font(.system(.subheadline, weight: .semibold))
                                    .foregroundStyle(Ink.primary)
                                Text(percentLine(move))
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
                block(String(localized: "TYPED KILOGRAMS")) {
                    VStack(alignment: .leading, spacing: 10) {
                        ForEach(impact.kgOffers) { offer in
                            VStack(alignment: .leading, spacing: 2) {
                                Text(offer.routineName)
                                    .font(.system(.subheadline, weight: .semibold))
                                    .foregroundStyle(Ink.primary)
                                ForEach(offer.moves, id: \.self) { move in
                                    Text(String(localized: "\(bandText(move.oldBand)) kg  →  \(bandText(move.newBand)) kg"))
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

    private func percentLine(_ move: TemplateStore.MaxImpact.PercentMove) -> String {
        let pct = String(localized: "\(Int((move.loPercent * 100).rounded()))–\(Int((move.hiPercent * 100).rounded())) %")
        var line = String(localized: "\(pct) · now \(bandText(move.newBand)) kg")
        if let old = move.oldBand, old != move.newBand {
            line += String(localized: " · was \(bandText(old))")
        }
        return line
    }

    private func bandText(_ band: ClosedRange<Double>) -> String {
        String(localized: "\(kgText(band.lowerBound))–\(kgText(band.upperBound))")
    }

    /// The way in to measuring. Offered whatever the gauge is doing — `MaxMeasureView`
    /// handles a missing connection with a Connect button and a way back, which is more
    /// use than a disabled control that explains nothing.
    private var measureRow: some View {
        VStack(alignment: .leading, spacing: 8) {
            SecondaryGlassButton(title: String(localized: "Measure on the gauge"),
                                 systemImage: "waveform.path.ecg") {
                measuring = true
            }
            Text("Pull as hard as you can — Get a Grip keeps the hardest the gauge sees. Or set it by hand below.")
                .font(.system(.footnote))
                .foregroundStyle(Ink.tertiary)
                .fixedSize(horizontal: false, vertical: true)
        }
    }

    // MARK: Blocks

    /// Grips you already train, offered as a starting point — NOT a library. Nothing here
    /// is stored, nothing is curated, and the pick is only a seed: every field below stays
    /// editable, so a grip you have never used costs three taps rather than a setup step.
    private var gripRail: some View {
        VStack(alignment: .leading, spacing: 8) {
            CapsLabel(String(localized: "START FROM"))
            ScrollView(.horizontal) {
                HStack(spacing: 8) {
                    ForEach(templates.recentGrips, id: \.key) { candidate in
                        Chip(title: candidate.shortName,
                             isSelected: candidate.key == grip.key) {
                            grip = candidate
                        }
                        .accessibilityLabel(candidate.spoken)
                    }
                }
                .padding(.vertical, 2)
            }
            .scrollIndicators(.hidden)
            .scrollBounceBehavior(.basedOnSize)

            Text("Grips from your routines. Tap one, then change anything you like.")
                .font(.system(.footnote))
                .foregroundStyle(Ink.tertiary)
                .fixedSize(horizontal: false, vertical: true)
        }
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
        return String(localized: "20–30 % of that is \(kgText(band.lowerBound))–\(kgText(band.upperBound)) kg")
    }

    /// Append, never edit — so the sheet says so before you tap Save rather than leaving
    /// you to discover a second row afterwards.
    @ViewBuilder
    private var existingLine: some View {
        // Keyed by grip AND hand: the record this save supersedes is the one for the
        // SAME hand, and quoting the other hand's number here would read as a
        // contradiction of what you are about to type.
        if let existing = templates.currentMaxes[MaxTable.key(grip: grip.key, side: side)] {
            Text(String(localized: "Your current max on this grip\(side == .both ? "" : String(localized: " for that hand")) is \(kgText(existing.kg)) kg, recorded \(existing.recordedAt.formatted(.relative(presentation: .named))). Saving adds a new one and keeps the old as history."))
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
        Text(source == .measured
             ? "Measured on the gauge — your hardest pull on this grip."
             : "A number you entered. Check its value and units before using it for targets.")
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
        let oldKg = templates.currentMax(for: grip, side: side)
        // Spelled out rather than left to the default — and now it is genuinely a
        // choice: `source` is `.measured` only while the value is still the one the
        // gauge produced.
        guard templates.recordMax(kg, for: grip, source: source, side: side) else {
            failed = true
            return
        }
        savedTick += 1
        let computed = templates.maxImpact(grip: grip, oldKg: oldKg, newKg: kg)
        if computed.isEmpty {
            onClose()
        } else {
            // The sheet becomes the receipt: what followed, and what is on offer.
            withAnimation(Motion.state(reduceMotion)) { impact = computed }
        }
    }

    private func kgText(_ value: Double) -> String {
        value.formatted(.number.precision(.fractionLength(1)))
    }
}

// Row chrome is `.houseListRow` in ScreenScaffold.swift — shared with History, which
// wears the same cards-in-a-List shape for the same reason.

// Deliberately no `#Preview`: this screen needs a `ModelContainer` for `@Query` and a
// `TemplateStore` in the environment, and a preview that traps on launch is worse than
// none. Same as every other screen in `Sources/UI` — previews live on the components.
