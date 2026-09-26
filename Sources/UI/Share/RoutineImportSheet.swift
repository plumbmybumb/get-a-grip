// SPDX-License-Identifier: MPL-2.0
// Original contributions Copyright 2026 Nuri Bruner.

import SwiftUI

/// A routine decoded from a `getagrip://` link, waiting to be looked at. Presented by
/// `.sheet(item:)`, with a fresh `id` per claim from the store's inbox.
struct ImportRequest: Identifiable {
    let id = UUID()
    let draft: RoutineDraft
}

/// The other side of a QR code: somebody else's routine, read out in full, before it is
/// yours.
///
/// A PREVIEW, not an editor: a routine you have not accepted is not one you can edit.
/// Once it lands, the card it becomes opens the builder in one tap.
///
/// **Percentage targets are NOT translated.** They resolve against the READER's maxes,
/// per hand, when a session starts — the reason the app prescribes fractions, so one
/// code prescribes the right load for two very different people. The footnotes cover
/// where that is not the whole story.
struct RoutineImportSheet: View {
    @Environment(\.weightUnit) private var weightUnit
    /// NORMALIZED at init. Read as a value; the sheet observes no store beyond its one save.
    let draft: RoutineDraft

    @Environment(TemplateStore.self) private var templates
    @Environment(\.dismiss) private var dismiss
    @Environment(\.dynamicTypeSize) private var typeSize

    /// Whether THIS sheet's add failed. Local, not `templates.saveError`: keyed to the store
    /// field, the sheet opened already accusing whenever an earlier, unrelated write failed.
    @State private var saveFailed = false

    /// Folded ONCE: `RoutineSummary(previewing:)` walks the rep sequence three times and
    /// mints an id, so a computed property would re-fold per scroll frame and change
    /// identity doing it.
    private let summary: RoutineSummary

    init(draft: RoutineDraft) {
        // Normalized HERE so the preview shows what will land (default name, emptied
        // sets dropped, inheritance consolidated); previewing raw and saving
        // normalized is how a preview and its card disagree. Idempotent.
        let clean = draft.normalized
        self.draft = clean
        self.summary = RoutineSummary(previewing: clean)
    }

    private var plan: SessionPlan { draft.plan }
    /// `executable`, like every other fold: a set with no pulls was emptied, not a rest.
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
            // The decision lives in the safe area: with up to fifty sets, a primary
            // action that must be scrolled to is not found.
            .safeAreaInset(edge: .bottom) { actions }
        }
    }

    // MARK: - Identity

    /// The name this routine will actually LAND under. The store deconflicts on save
    /// (`uniqueName`), and two people keeping the default name is the common case — a
    /// preview saying "Daily no-hangs" before the card says "Daily no-hangs 2" is a
    /// disagreement. Per body pass, so a routine created behind the sheet still counts.
    private var landingName: String {
        templates.plannedImportName(for: summary.name)
    }

    private var header: some View {
        HStack(alignment: .top, spacing: 12) {
            // The mark Today's card wears, recognisable before and after it is yours.
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
        // The rung's colour is invisible to VoiceOver and greyscale — see `RoutineCard`.
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

    /// Two layouts, forked at `.accessibility1` like `RoutineCard`'s plan row: side by side
    /// at the type ceiling, the grip sentence got ~22 pt of a 330 pt row, on the screen whose
    /// job is stating the grip. Big text gets words at full width; the glyph is spared.
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
                        // No line limit: the sentence the screen exists for wraps, never truncates.
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

    /// What this set does DIFFERENTLY — its own timing, its own load. A stranger's routine
    /// owes you these before you accept: a typed 40 kg band first met in the runner is the
    /// surprise this sheet prevents. Nothing renders for a set that inherits everything.
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
            parts.append(String(localized: "\(weightUnit.number(band.lowerBound))–\(weightUnit.number(band.upperBound)) \(weightUnit.symbol) target"))
        } else if let band = set.targetPercentBand {
            let lo = Int((band.lowerBound * 100).rounded())
            let hi = Int((band.upperBound * 100).rounded())
            parts.append(lo == hi ? String(localized: "\(hi) % of max") : String(localized: "\(lo)–\(hi) % of max"))
        }
        return parts.isEmpty ? nil : parts.joined(separator: " · ")
    }

    private func setAccessibilityLabel(_ set: SetPlan) -> String {
        var label = String(localized: "\(set.grip.spoken). \(repText(set)).")
        if let detail = setDetailLine(set) { label += String(localized: " \(detail).") }
        return label
    }

    /// "6 per side" when hands take turns, "6 pulls" when together. The count comes from
    /// `PlanMath.repCount`, the one home of that multiplication — a silent factor of two
    /// would be believed here.
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

    /// The ROUTINE's rhythm, inherited unless overridden; overrides are already folded into
    /// the pull counts and estimate above.
    private var rhythmLine: String {
        var parts = [String(localized: "\(PlanMath.durationText(plan.holdSeconds)) hold"),
                     String(localized: "\(PlanMath.durationText(plan.restSeconds)) rest")]
        // A break "between sets" with one set is a constant dressed as information.
        if summary.setCount > 1 {
            parts.append(String(localized: "\(PlanMath.durationText(plan.setBreakSeconds)) between sets"))
        }
        return parts.joined(separator: " · ")
    }

    /// A WHENEVER routine is never owed, so it says so instead of quoting a number.
    private var cadenceLine: String {
        if draft.isOnDemand { return String(localized: "Whenever you're fresh") }
        switch max(1, draft.sessionsPerDay) {
        case 1: return String(localized: "Once a day")
        case 2: return String(localized: "Twice a day")
        case let n: return String(localized: "\(n)× a day")
        }
    }

    // MARK: - The two honesty notes

    /// Each shown only when TRUE of this routine: irrelevant footnotes teach people to stop
    /// reading footnotes.
    @ViewBuilder private var notes: some View {
        // Guarded around the STACK: an empty VStack still takes the 18 pt spacing,
        // under most routines, which prescribe no load.
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

    /// `PlanMath.targetBand`'s precedence as a predicate: a set with typed kilograms never
    /// reaches its percentage.
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
                // STAYS OPEN on a rollback: dismissing loses the code too, and rescanning
                // is somebody else's phone away. Same as the builder's failed save.
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

            // Quiet, never destructive-looking: declining costs and undoes nothing.
            Button("Not now") { dismiss() }
                .buttonStyle(PressFeedbackButtonStyle())
                .font(.system(.footnote, weight: .semibold))
                .foregroundStyle(Accent.graphite)
                // One footnote line drawn, 44 hit; `contentShape` is mandatory since
                // padding is not hit-tested.
                .frame(maxWidth: .infinity, minHeight: 44)
                .contentShape(.rect)
        }
        .padding(.horizontal, Metrics.hPadding)
        .padding(.bottom, 4)
        .frame(maxWidth: Metrics.maxContentWidth)
        .frame(maxWidth: .infinity)
    }

    /// The new card appears on Today by itself (its list is a `@Query`).
    private func add() {
        guard templates.importRoutine(draft) != nil else {
            // INLINE, and the store's copy is consumed: the global "Couldn't save"
            // alert watches the same field from the presenting view, and one rollback
            // stated twice reads as two.
            saveFailed = true
            templates.saveError = nil
            return
        }
        saveFailed = false
        dismiss()
    }
}
