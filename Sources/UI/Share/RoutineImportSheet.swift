// SPDX-License-Identifier: MPL-2.0
// Original contributions Copyright 2026 Nuri Bruner.

import SwiftUI

/// A routine decoded from a `getagrip://` link, waiting to be looked at. `Identifiable`
/// because `.sheet(item:)` is what presents it, with a fresh `id` per claim from the
/// store's inbox.
struct ImportRequest: Identifiable {
    let id = UUID()
    let draft: RoutineDraft
}

/// The other side of a QR code: somebody else's routine, read out in full, before it is
/// yours.
///
/// It is a PREVIEW, not an editor. Nothing here is adjustable, and that is the honest
/// shape — a routine you have not accepted yet is not a routine you can edit, and fields
/// would be asking a stranger's plan to be corrected before it has been read. Everything
/// in it is one tap from editable the moment it lands: the card it becomes opens the
/// builder on its own plan row.
///
/// **Percentage targets are deliberately NOT translated.** They resolve against the
/// READER's maxes, per hand, at the moment a session starts — which is the entire reason
/// this app prescribes fractions rather than kilograms, and it means the same code
/// prescribes the right load for two people with very different fingers. The two
/// footnotes exist for the cases where that is not the whole story.
struct RoutineImportSheet: View {
    @Environment(\.weightUnit) private var weightUnit
    /// NORMALIZED at init — see the initializer. Read as a value; the sheet observes no
    /// store except for the one save it performs.
    let draft: RoutineDraft

    @Environment(TemplateStore.self) private var templates
    @Environment(\.dismiss) private var dismiss
    @Environment(\.dynamicTypeSize) private var typeSize

    /// Whether THIS sheet's add failed — a local flag, not a read of
    /// `templates.saveError`: keyed to the store field, the sheet opened already
    /// wearing the accusation whenever an earlier, unrelated write had failed and its
    /// alert had not been read yet.
    @State private var saveFailed = false

    /// Folded ONCE, not per body evaluation: `RoutineSummary(previewing:)` walks the
    /// whole rep sequence three times, and it also mints an id, so a computed property
    /// would re-fold a fifty-set routine on every scroll frame and change identity while
    /// doing it.
    private let summary: RoutineSummary

    init(draft: RoutineDraft) {
        // Normalized HERE so the preview shows the routine that will actually land: an
        // empty name becomes the house default on the way in, an emptied set is dropped,
        // and inheritance is consolidated. Previewing the raw draft and saving the
        // normalized one is exactly how a preview and the card it becomes disagree.
        // `normalized` is idempotent, so the store's own pass costs nothing.
        let clean = draft.normalized
        self.draft = clean
        self.summary = RoutineSummary(previewing: clean)
    }

    private var plan: SessionPlan { draft.plan }
    /// `executable`, like every other fold in the app: a set with no pulls in it is a row
    /// the author emptied out, not a rest.
    private var sets: [SetPlan] { draft.plan.executable.sets }

    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(alignment: .leading, spacing: 18) {
                    header
                    planCard
                    rhythmCard
                    notes
                }
                .padding(.horizontal, Metrics.hPadding)
                .padding(.top, 12)
                .padding(.bottom, 28)
                .frame(maxWidth: Metrics.maxContentWidth)
                .frame(maxWidth: .infinity)
            }
            // ALWAYS via `.background {}`, never as a ZStack sibling.
            .background { AppBackground() }
            .scrollBounceBehavior(.basedOnSize)
            .scrollEdgeEffectStyle(.soft, for: .bottom)
            .navigationTitle("Shared routine")
            .navigationBarTitleDisplayMode(.inline)
            // The decision lives in the safe area rather than at the foot of the
            // document: the payload allows fifty sets, and a primary action that has to
            // be scrolled to is a primary action people do not find.
            .safeAreaInset(edge: .bottom) { actions }
        }
    }

    // MARK: - Identity

    /// The name this routine will actually LAND under. The store deconflicts on save
    /// (`uniqueName`), and two people keeping the shipped default name is the common
    /// case for a shared routine — a preview promising "Daily no-hangs" four seconds
    /// before the card says "Daily no-hangs 2" is the preview and the card disagreeing.
    /// Computed per body pass, so a routine created behind the sheet is still counted.
    private var landingName: String {
        templates.plannedImportName(for: summary.name)
    }

    private var header: some View {
        HStack(alignment: .top, spacing: 12) {
            // The same derived mark Today's card wears, so the routine is recognisable
            // before and after it is yours.
            EdgeMark(fingers: summary.signatureFingers ?? .four,
                     rungTint: PlanMath.IntensityBand.band(for: summary.peakIntensity).tint)
                .padding(.top, 4)

            VStack(alignment: .leading, spacing: 4) {
                Text(landingName)
                    .font(.system(.title2, weight: .semibold))
                    .foregroundStyle(Ink.primary)
                    .fixedSize(horizontal: false, vertical: true)
                Text(summary.metaLine)
                    .font(.system(.footnote))
                    .monospacedDigit()
                    .foregroundStyle(Ink.secondary)
                    .fixedSize(horizontal: false, vertical: true)
            }
        }
        .accessibilityElement(children: .combine)
        .accessibilityLabel(landingName)
        // The rung's colour is invisible to VoiceOver and to greyscale, so the number
        // it stands for is spoken — the same pairing `RoutineCard` makes.
        .accessibilityValue(summary.metaLine + intensitySuffix)
    }

    private var intensitySuffix: String {
        guard let peak = summary.peakIntensity else { return "" }
        return String(localized: ". Peak target \(Int((peak * 100).rounded())) percent of max.")
    }

    // MARK: - The plan

    private var planCard: some View {
        MaterialCard {
            VStack(alignment: .leading, spacing: 10) {
                CapsLabel(String(localized: "WHAT YOU'LL PULL"))
                ForEach(Array(sets.enumerated()), id: \.offset) { index, set in
                    setRow(set)
                    if index < sets.count - 1 {
                        Divider().overlay(Ink.tertiary.opacity(0.14))
                    }
                }
            }
        }
    }

    /// Two layouts, forked at `.accessibility1` — the same fork `RoutineCard`'s plan
    /// row makes, and for the same measured reason: side by side, the scaled glyph plus
    /// a priority-protected count left the grip sentence ~22 pt of a 330 pt row at the
    /// app's type ceiling, on the one screen whose job is stating the grip. Big text is
    /// served by words stacked in full width; the glyph is decoration it can spare.
    @ViewBuilder
    private func setRow(_ set: SetPlan) -> some View {
        if typeSize >= .accessibility1 {
            VStack(alignment: .leading, spacing: 3) {
                Text(set.grip.line)
                    .font(.system(.subheadline, weight: .semibold))
                    .foregroundStyle(Ink.primary)
                    .fixedSize(horizontal: false, vertical: true)
                Text(repText(set))
                    .font(.system(.footnote, weight: .medium))
                    .monospacedDigit()
                    .foregroundStyle(Ink.secondary)
                    .fixedSize(horizontal: false, vertical: true)
                setDetail(set)
            }
            .frame(maxWidth: .infinity, alignment: .leading)
            .accessibilityElement(children: .ignore)
            .accessibilityLabel(setAccessibilityLabel(set))
        } else {
            HStack(alignment: .top, spacing: 12) {
                FingerGlyph(fingers: set.grip.fingers, position: set.grip.position,
                            dot: 7, gap: 3)
                    .padding(.top, 3)

                VStack(alignment: .leading, spacing: 2) {
                    Text(set.grip.line)
                        .font(.system(.subheadline, weight: .semibold))
                        .foregroundStyle(Ink.primary)
                        // No line limit: this sentence is what the screen exists to
                        // state, so it wraps rather than truncates.
                        .fixedSize(horizontal: false, vertical: true)
                    setDetail(set)
                }

                Spacer(minLength: 8)

                Text(repText(set))
                    .font(.system(.footnote, weight: .medium))
                    .monospacedDigit()
                    .foregroundStyle(Ink.secondary)
                    .fixedSize(horizontal: false, vertical: true)
            }
            .accessibilityElement(children: .ignore)
            .accessibilityLabel(setAccessibilityLabel(set))
        }
    }

    /// What this set does DIFFERENTLY — its own timing, its own load. A stranger's
    /// routine owes you these before you accept it: a typed 40 kg band you meet for the
    /// first time in the runner is exactly the surprise this sheet exists to prevent,
    /// and a per-set 3 s hold explains why the estimate above disagrees with the rhythm
    /// line below. Nothing renders for the common set that inherits everything.
    @ViewBuilder
    private func setDetail(_ set: SetPlan) -> some View {
        if let line = setDetailLine(set) {
            Text(line)
                .font(.system(.footnote))
                .monospacedDigit()
                .foregroundStyle(Ink.secondary)
                .fixedSize(horizontal: false, vertical: true)
        }
    }

    private func setDetailLine(_ set: SetPlan) -> String? {
        var parts: [String] = []
        if let hold = set.holdSeconds { parts.append(String(localized: "\(PlanMath.durationText(hold)) hold")) }
        if let rest = set.restSeconds { parts.append(String(localized: "\(PlanMath.durationText(rest)) rest")) }
        if let band = set.targetBand {
            parts.append(String(localized: "\(weightText(band.lowerBound))–\(weightText(band.upperBound)) \(weightUnit.symbol) target"))
        } else if let band = set.targetPercentBand {
            let lo = Int((band.lowerBound * 100).rounded())
            let hi = Int((band.upperBound * 100).rounded())
            parts.append(lo == hi ? String(localized: "\(hi) % of max") : String(localized: "\(lo)–\(hi) % of max"))
        }
        return parts.isEmpty ? nil : parts.joined(separator: " · ")
    }

    private func weightText(_ kg: Double) -> String {
        weightUnit.number(kg)
    }

    private func setAccessibilityLabel(_ set: SetPlan) -> String {
        var label = String(localized: "\(set.grip.spoken). \(repText(set)).")
        if let detail = setDetailLine(set) { label += String(localized: " \(detail).") }
        return label
    }

    /// "6 per side" when the hands take turns, "6 pulls" when they are on the edge
    /// together. The pull count comes from `PlanMath.repCount` rather than a
    /// `× sideCount` written here — that multiplication has exactly one home in the app,
    /// and this is the screen where a silent factor of two would be believed.
    private func repText(_ set: SetPlan) -> String {
        guard plan.handMode.sideCount == 1 else { return String(localized: "\(set.repsPerSide) per side") }
        let pulls = PlanMath.repCount(set, mode: plan.handMode)
        return String(localized: "\(pulls) \(pulls == 1 ? String(localized: "pull") : String(localized: "pulls"))")
    }

    // MARK: - Rhythm and cadence

    private var rhythmCard: some View {
        MaterialCard {
            VStack(alignment: .leading, spacing: 14) {
                VStack(alignment: .leading, spacing: 6) {
                    CapsLabel(String(localized: "RHYTHM"))
                    Text(rhythmLine)
                        .font(.system(.subheadline, weight: .medium))
                        .monospacedDigit()
                        .foregroundStyle(Ink.primary)
                        .fixedSize(horizontal: false, vertical: true)
                    Text(plan.handMode.name)
                        .font(.system(.footnote))
                        .foregroundStyle(Ink.secondary)
                        .fixedSize(horizontal: false, vertical: true)
                }
                VStack(alignment: .leading, spacing: 6) {
                    CapsLabel(String(localized: "EVERY DAY"))
                    Text(cadenceLine)
                        .font(.system(.subheadline, weight: .medium))
                        .monospacedDigit()
                        .foregroundStyle(Ink.primary)
                        .fixedSize(horizontal: false, vertical: true)
                }
            }
        }
    }

    /// The ROUTINE's rhythm — the values every set inherits unless it overrides. A set
    /// that overrides is already folded into the pull counts and the estimate above.
    private var rhythmLine: String {
        var parts = [String(localized: "\(PlanMath.durationText(plan.holdSeconds)) hold"),
                     String(localized: "\(PlanMath.durationText(plan.restSeconds)) rest")]
        // A break "between sets" is a constant dressed as information when there is only
        // one set — the Live Activity drops "Set 1 of 1" for the same reason.
        if summary.setCount > 1 {
            parts.append(String(localized: "\(PlanMath.durationText(plan.setBreakSeconds)) between sets"))
        }
        return parts.joined(separator: " · ")
    }

    /// A WHENEVER routine has no daily target and is never owed, so it says so instead
    /// of quoting a number it does not mean.
    private var cadenceLine: String {
        if draft.isOnDemand { return String(localized: "Whenever you're fresh") }
        switch max(1, draft.sessionsPerDay) {
        case 1: return String(localized: "Once a day")
        case 2: return String(localized: "Twice a day")
        case let n: return String(localized: "\(n)× a day")
        }
    }

    // MARK: - The two honesty notes

    /// Both are shown only when they are TRUE of this routine — a footnote about
    /// kilograms under a routine that carries none is noise, and noise is what teaches
    /// people to stop reading footnotes.
    @ViewBuilder private var notes: some View {
        // Guarded around the STACK, not just inside it: an empty VStack is still a view,
        // and the document's 18 pt spacing would leave a block of nothing under a routine
        // that prescribes no load at all — which is most of them.
        if hasPercentTargets || hasKilogramTargets {
            VStack(alignment: .leading, spacing: 8) {
                if hasPercentTargets {
                    note(String(localized: "Percentage targets use your saved maxes. These may no longer reflect your current strength."))
                }
                if hasKilogramTargets {
                    note(String(localized: "Some fixed weight targets were set by the sender. Review them for your own training."))
                }
            }
        }
    }

    private func note(_ text: String) -> some View {
        Text(text)
            .font(.system(.footnote))
            .foregroundStyle(Ink.tertiary)
            .fixedSize(horizontal: false, vertical: true)
            .frame(maxWidth: .infinity, alignment: .leading)
    }

    /// `PlanMath.targetBand`'s precedence, read as a predicate: a set carrying typed
    /// kilograms never reaches its percentage, so it is not a percentage set.
    private var hasPercentTargets: Bool {
        sets.contains { $0.targetBand == nil && PlanMath.targetPercent($0, in: plan) != nil }
    }

    private var hasKilogramTargets: Bool {
        sets.contains { $0.targetBand != nil }
    }

    // MARK: - The decision

    private var actions: some View {
        VStack(spacing: 4) {
            if saveFailed {
                // The sheet STAYS OPEN on a rollback: dismissing on failure loses the
                // code as well as the routine, and rescanning is somebody else's phone
                // away. Same inline treatment the builder gives its own failed save.
                Text("That routine couldn't be saved just now — nothing was added. Try again.")
                    .font(.system(.footnote, weight: .medium))
                    .foregroundStyle(Accent.alarm)
                    .fixedSize(horizontal: false, vertical: true)
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .padding(.bottom, 6)
            }

            PrimaryGlassButton(title: String(localized: "Add to my routines"),
                               systemImage: "plus",
                               tint: Accent.graphite) {
                add()
            }

            // Quiet, and never destructive-looking: declining a routine costs nothing
            // and undoes nothing.
            Button("Not now") { dismiss() }
                .buttonStyle(PressFeedbackButtonStyle())
                .font(.system(.footnote, weight: .semibold))
                .foregroundStyle(Accent.graphite)
                // Drawn as one line of footnote, hit as 44 — and `contentShape` is
                // mandatory rather than tidy, since padding contributes nothing to
                // SwiftUI's default hit area.
                .frame(maxWidth: .infinity, minHeight: 44)
                .contentShape(.rect)
        }
        .padding(.horizontal, Metrics.hPadding)
        .padding(.bottom, 4)
        .frame(maxWidth: Metrics.maxContentWidth)
        .frame(maxWidth: .infinity)
    }

    /// The new card appears on Today by itself — the routine list there is a `@Query`,
    /// so nothing has to be handed back through the presentation.
    private func add() {
        guard templates.importRoutine(draft) != nil else {
            // Surfaced INLINE, and the store's copy of the failure is consumed — the
            // global "Couldn't save" alert watches the same field from the very view
            // presenting this sheet, and one rollback stated twice reads as two.
            saveFailed = true
            templates.saveError = nil
            return
        }
        saveFailed = false
        dismiss()
    }
}
