// SPDX-License-Identifier: MPL-2.0
// Original contributions Copyright 2026 Nuri Bruner.

import SwiftUI

/// One row of the routine — collapsed it is a sentence, expanded it is the whole set.
///
/// The accordion is not only a readability device: at most one row is open at a time
/// (the builder owns that state), which is what guarantees only ONE dense chip cluster
/// exists on screen at any moment.
struct SetRowView: View {
    @Environment(\.weightUnit) private var weightUnit
    /// **The WHOLE plan, as one keypath-shaped binding — never `$plan.sets[index]`.**
    ///
    /// The row reads it for every resolved number (the inherited hold, the ×2, this
    /// row's clock) and writes its own set back through it, which is why one binding
    /// replaced the pair this used to take.
    ///
    /// The shape is load-bearing, not a tidy-up. Measured on the pinned sim
    /// (2026-08-18): a single keypress into any number field in the builder costs THREE
    /// complete update passes over the presented subtree, and `_printChanges()` named
    /// `SetRowView: _set changed` in every one of the eighteen row bodies they cost —
    /// on a routine where nothing about any set had moved. A binding derived through a
    /// SUBSCRIPT (`$draft.plan.sets[index]`) is rebuilt from scratch each time it is
    /// formed, so SwiftUI can never prove it unchanged; a plain keypath projection
    /// (`$draft.plan`) can be, and was — the probe that established this passed `$draft`
    /// and `$draft.plan` alongside the subscript binding and only the subscript was ever
    /// reported as changed. All six callbacks compared equal throughout, so the closures
    /// were never the problem the house rule would have predicted.
    @Binding var plan: SessionPlan
    /// Which set this row draws — an ID, never an index, so a reorder cannot point a row
    /// at its neighbour.
    let setID: UUID
    var isExpanded: Bool
    /// Every max on file, by grip and hand. Passed in as a value so the row stays
    /// previewable and never touches the store.
    var maxes: MaxTable
    /// Folded once by the builder that owns the whole plan. Computing this in every row
    /// made both the visible and spoken summaries scan every set again.
    var percentBandsVary: Bool

    var onTap: () -> Void
    /// Asks the BUILDER to open the island panel for this set's grip.
    var onEditGrip: () -> Void
    var onMoveUp: () -> Void
    var onMoveDown: () -> Void
    var onDuplicate: () -> Void
    var onRemove: () -> Void

    @Environment(\.accessibilityReduceMotion) private var reduceMotion

    /// The shared interior of the Hold and Rest dials — every detent the shipping
    /// protocols use (3 s C4 holds, 5/7/10/12 s repeaters, 15–60 s rests), defined once
    /// so the two ladders cannot drift apart again. Each dial prepends only its floor.
    private static let secondsLadder: [Double] = [3, 5, 7, 10, 12, 15, 20, 30, 45, 60]

    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            Button(action: onTap) { collapsedFace }
                // .plain, matching the sibling app's list rows: a row-sized card that
                // scaled on press would drag its material backdrop out from under the
                // editor it shares a card with.
                .buttonStyle(PressFeedbackButtonStyle(scales: false))
                // ON THE HEADER, never the whole row: attached to the row, a hold
                // anywhere in the EXPANDED editor — exactly where you are right after
                // Add a set — hoisted the entire editing surface as the lifted preview
                // (Nuri, 2026-08-10). The header is always on screen, so the menu
                // stays one hold away without ever grabbing touches from the controls.
                .contextMenu {
                    Button("Move up", systemImage: "arrow.up", action: onMoveUp)
                        .disabled(!canMoveUp)
                    Button("Move down", systemImage: "arrow.down", action: onMoveDown)
                        .disabled(!canMoveDown)
                    Button("Duplicate", systemImage: "plus.square.on.square", action: onDuplicate)
                    Button("Remove", systemImage: "trash", role: .destructive, action: onRemove)
                } preview: {
                    SetRowPreview(set: set, plan: plan)
                }

            if isExpanded {
                editor
                    .padding(.horizontal, 16)
                    .padding(.bottom, 16)
            }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(.regularMaterial,
                    in: RoundedRectangle(cornerRadius: Metrics.radiusCard, style: .continuous))
        .animation(Motion.state(reduceMotion),
                   value: isExpanded)
    }

    // MARK: - This row's set
    //
    // Derived rather than stored, so the only binding the row holds is the plan's.

    /// This row's set. A row whose set has just been removed keeps drawing an empty one
    /// for the frame before the `ForEach` drops it, rather than trapping on a stale
    /// index.
    private var set: SetPlan { plan.sets.first { $0.id == setID } ?? SetPlan() }

    /// Writes back BY ID, so an edit in flight while the list reorders lands on the set
    /// it came from.
    private var setBinding: Binding<SetPlan> {
        Binding(get: { set },
                set: { updated in
                    guard let index else { return }
                    plan.sets[index] = updated
                })
    }

    // MARK: - Collapsed

    private var collapsedFace: some View {
        HStack(alignment: .top, spacing: 12) {
            // Plain DESIGN sizes: FingerGlyph scales `dot`/`gap` itself, so pre-scaling
            // them here would apply Dynamic Type twice and give a speck a glyph at AX3.
            FingerGlyph(fingers: set.grip.fingers, position: set.grip.position,
                        dot: 6, gap: 3)
                .padding(.top, 4)

            VStack(alignment: .leading, spacing: 3) {
                Text(set.grip.line)
                    .font(.system(.subheadline, weight: .semibold))
                    .monospacedDigit()
                    .foregroundStyle(Ink.primary)
                    .lineLimit(2)
                detailLine
                    .font(.system(.footnote))
                    .monospacedDigit()
                    .contentTransition(.numericText())
            }

            Spacer(minLength: 8)

            Text(PlanMath.clockText(PlanMath.setSeconds(set, in: plan)))
                .font(.system(.subheadline, weight: .medium))
                .monospacedDigit()
                .contentTransition(.numericText())
                .foregroundStyle(Ink.tertiary)
        }
        .padding(16)
        .frame(maxWidth: .infinity, alignment: .leading)
        // MANDATORY: the label holds a Spacer and draws full-width, and SwiftUI's
        // default hit area is the label's OPAQUE content — the material and the padding
        // contribute nothing to it.
        .contentShape(RoundedRectangle(cornerRadius: Metrics.radiusCard, style: .continuous))
        // The visual line and the spoken line are the same sentence; it comes free from
        // having written the row as prose in the first place.
        .accessibilityElement(children: .ignore)
        .accessibilityLabel(spokenRow)
        .accessibilityHint(isExpanded ? String(localized: "Closes this set") : String(localized: "Opens this set for editing"))
    }

    /// "6 per side · 1:00 under tension per side", plus any timing override.
    private var detailLine: Text {
        let base = Text("\(repsText) · \(tensionText)").foregroundStyle(Ink.secondary)
        guard !overrideText.isEmpty else { return base }
        // HARD RULE: a per-set timing override MUST render on the COLLAPSED row, in
        // PRIMARY ink. An override that is invisible until you open the row produces a
        // session nobody can explain — including whoever set it.
        //
        // Interpolated rather than `Text + Text`, which iOS 26 deprecates; interpolating
        // a Text keeps that Text's own styling, which is the whole trick here.
        let override = Text(overrideText).foregroundStyle(Ink.primary)
        return Text("\(base)\(override)")
    }

    // MARK: - Expanded editor
    //
    // Fixed order, and NO third level of disclosure: a height-animating reveal inside a
    // height-animating row inside a List is where this class of layout breaks. Two
    // discrete user-initiated toggles with fixed-height content is the ceiling.

    private var editor: some View {
        VStack(alignment: .leading, spacing: 16) {
            Divider().overlay(Ink.tertiary.opacity(0.22))

            // ONE control, 60 pt, where an edge slider + finger pad + position chips used
            // to stack to about 400. The three of them still exist, behind "Something
            // else" in the picker — see `GripToken` for why the default flipped.
            GripToken(grip: set.grip, onEdit: onEditGrip)

            // BOTH readouts, always — never a "count by reps / count by time" mode.
            // A per-set mode is remembered state, and on the 30th edit set 3 would read
            // in seconds and set 4 in reps with no memory of why.
            // A STEPPER, not a slider with chips: this is a small integer you want
            // EXACTLY, nudged around a common one — which is the HIG's own description of
            // when a stepper is the control. Four chips could never hold 4, and 4 is what
            // a max protocol asks for. It repeats while held.
            IntValueRow(title: String(localized: "Pulls per side"),
                        value: setBinding.repsPerSide,
                        range: 1...40, limit: 1...SetPlan.repsRange.upperBound,
                        caption: String(localized: "= \(tensionText)"),
                        control: .stepper)

            timingSection
            targetSection
            moveRow
            removeRow
        }
    }

    /// ALWAYS visible — timing is a property of the set (Nuri, 2026-08-10: "what if
    /// for one pull you want 10 seconds and for the other 20?"). The old
    /// "different timing for this set" toggle framed per-set timing as an exception;
    /// it is the rule now, and a new set copies the previous one's numbers so a
    /// uniform routine still costs nothing extra to author.
    private var timingSection: some View {
        VStack(alignment: .leading, spacing: 12) {
            // DIALS. The slider these replace moved in fives from a floor of 3, so it
            // could land on 3, 8, 13, 18 — 5, 7, 10 and 12 were unreachable by dragging
            // and existed only as chips. Every value the protocols actually use is a
            // detent here, and the scale underneath states all of them.
            // 1–60, the range a seconds control is expected to cover (Nuri,
            // 2026-08-18: "I feel like that's a common range") — the old ladder
            // stopped at 30 and made 45 s and 60 s holds typed-only.
            //
            // **ONE ladder for both dials, differing only at the floor.** These two sit
            // stacked, cover the same sixty seconds, and the dial spaces detents by
            // INDEX — so different ladders render different notches for the same span
            // and read as two different instruments (Nuri, 2026-08-18: "why is the
            // notches on the slider for hold and rest different"). The floor is the one
            // honest difference: a zero-second rest is a real cadence, a zero-second
            // hold is not a hold.
            IntValueRow(title: String(localized: "Hold"), unit: String(localized: "s"), value: holdBinding,
                        range: 1...60, limit: SetPlan.holdRange,
                        control: .dial([1] + Self.secondsLadder))
            IntValueRow(title: String(localized: "Rest between pulls"), unit: String(localized: "s"), value: restBinding,
                        range: 0...60, limit: SetPlan.restRange,
                        control: .dial([0] + Self.secondsLadder))
        }
    }

    /// The set's target load, in the same grammar as `timingSection`: inherited unless
    /// this row says otherwise, and it says which out loud in tertiary ink.
    ///
    /// The toggle reads "Different target for this set" only when there IS a routine
    /// band to differ from — with none set, there is nothing to inherit and the honest
    /// label is just "Target load".
    private var targetSection: some View {
        VStack(alignment: .leading, spacing: 12) {
            // The full band row — presets, the trimmer for custom ranges, percent or
            // kilograms. It replaced a kg-only toggle that could not even SHOW a
            // per-set percent band, which the Max day preset's ramp is made of.
            TargetBandRow(set: setBinding, maxes: maxes, handMode: plan.handMode)

            // With no band of its own, the set follows the routine's — said in the
            // same sentence as before, underneath the row that could override it.
            if !set.hasTarget, !set.hasPercentTarget, inheritsTarget {
                Text(inheritedTargetText)
                    .font(.system(.footnote))
                    .foregroundStyle(Ink.tertiary)
                    .fixedSize(horizontal: false, vertical: true)
            }
        }
    }

    /// Whether the ROUTINE sets a percentage this row would otherwise follow.
    private var inheritsTarget: Bool { plan.targetPercentBand != nil }

    /// What the inherited percentage actually means for THIS grip — the number that
    /// makes a percentage trustworthy. Without a max the row must say so rather than
    /// showing a percentage that resolves to nothing at session time.
    private var inheritedTargetText: String {
        guard let percent = plan.targetPercentBand else { return "" }
        let range = "\(percentText(percent.lowerBound))–\(percentText(percent.upperBound)) %"
        // PER HAND, through the same formatter the deck and the review use — the
        // document and the deck must never quote different loads for one routine.
        guard let load = weightUnit.targetText(set, in: plan, maxes: maxes) else {
            return String(localized: "Following the routine — \(range) of your max, but there is no max on file for this grip yet, so this set will show no target.")
        }
        let perHand = PlanMath.targetDiffersByHand(set, in: plan, maxes: maxes)
        return String(localized: "Following the routine — \(range), which is \(load) on your \(perHand ? String(localized: "maxes for this grip.") : String(localized: "max for this grip."))")
    }

    private func percentText(_ fraction: Double) -> String {
        "\(Int((fraction * 100).rounded()))"
    }

    private func weightText(_ kg: Double) -> String {
        weightUnit.number(kg)
    }

    private var moveRow: some View {
        HStack(spacing: 10) {
            moveButton(String(localized: "Move up"), systemImage: "chevron.up",
                       enabled: canMoveUp, action: onMoveUp)
            moveButton(String(localized: "Move down"), systemImage: "chevron.down",
                       enabled: canMoveDown, action: onMoveDown)
            Spacer(minLength: 0)
        }
    }

    private func moveButton(_ title: String, systemImage: String,
                            enabled: Bool, action: @escaping () -> Void) -> some View {
        Button(action: action) {
            HStack(spacing: 6) {
                Image(systemName: systemImage)
                Text(title)
            }
            .font(.system(.footnote, weight: .semibold))
            .frame(minHeight: 44)
            .padding(.horizontal, 12)
            .contentShape(.rect)
        }
        .buttonStyle(PressFeedbackButtonStyle())
        .disabled(!enabled)
        .foregroundStyle(enabled ? Accent.graphite : Ink.tertiary.opacity(0.5))
        // The dim state otherwise says nothing about why: at either end of the list
        // one of these two buttons is always disabled with no caption on screen.
        // Keyed on the (stable, unlocalized) systemImage rather than the title text,
        // which is now a localized string and must never be compared against an
        // English literal.
        .accessibilityHint(enabled ? "" : (systemImage == "chevron.up" ? String(localized: "Already the first set")
                                                                        : String(localized: "Already the last set")))
    }

    private var removeRow: some View {
        Button(role: .destructive, action: onRemove) {
            HStack(spacing: 8) {
                Image(systemName: "minus.circle")
                Text("Remove this set")
                Spacer(minLength: 0)
            }
            .font(.system(.subheadline, weight: .semibold))
            .foregroundStyle(Accent.alarm)
            .frame(maxWidth: .infinity, minHeight: 44, alignment: .leading)
            .contentShape(Rectangle())
        }
        .buttonStyle(PressFeedbackButtonStyle())
    }

    // MARK: - Bindings into the optionals
    //
    // `nil` on a set means "follow the routine", so each binding READS the resolved value
    // and WRITES this set's own override.

    private var holdBinding: Binding<Int> {
        Binding { PlanMath.hold(set, in: plan) } set: { setBinding.wrappedValue.holdSeconds = $0 }
    }

    private var restBinding: Binding<Int> {
        Binding { PlanMath.rest(set, in: plan) } set: { setBinding.wrappedValue.restSeconds = $0 }
    }

    // MARK: - Derived copy

    private var repsText: String {
        guard plan.handMode.sideCount > 1 else {
            // One-sided modes have no side to divide by, so the copy drops "per side"
            // rather than halving a number that was never doubled.
            return String(localized: "\(set.repsPerSide) \(set.repsPerSide == 1 ? String(localized: "pull") : String(localized: "pulls"))")
        }
        return String(localized: "\(set.repsPerSide) per side")
    }

    private var tensionText: String {
        if let perSide = PlanMath.tensionSecondsPerSide(set, in: plan) {
            return String(localized: "\(PlanMath.clockText(perSide)) under tension per side")
        }
        let reps = PlanMath.repCount(set, mode: plan.handMode)
        return String(localized: "\(PlanMath.clockText(reps * PlanMath.hold(set, in: plan))) under tension")
    }

    /// Every override this set carries, in the order they appear in the editor. Both are
    /// listed because the rule is about VISIBILITY, not about the hold alone.
    private var overrideText: String {
        var parts: [String] = []
        if let hold = set.holdSeconds { parts.append(String(localized: "\(hold) s hold")) }
        if let rest = set.restSeconds { parts.append(String(localized: "\(rest) s rest")) }
        // A target on this set must be visible without opening the row — same rule as a
        // hold override, for the same reason.
        //
        // **A PERCENTAGE shows only where the sets DISAGREE.** A ramp is the whole shape of
        // a max protocol and has to be readable straight down the list — 50–60, 65–75,
        // 80–90 — whereas six sets that all say 18–22 % is the card talking to itself,
        // which is exactly why an inherited band was left off the row in the first place.
        // Since targets are stored per set now, "inherited" is no longer the question;
        // "does this one differ from its neighbours" is.
        if let band = set.targetBand {
            parts.append(String(localized: "\(weightText(band.lowerBound))–\(weightText(band.upperBound)) \(weightUnit.symbol)"))
        } else if percentBandsVary, let percent = set.targetPercentBand {
            parts.append(String(localized: "\(percentText(percent.lowerBound))–\(percentText(percent.upperBound)) %"))
        }
        return parts.isEmpty ? "" : " · " + parts.joined(separator: " · ")
    }

    /// The spoken form of the same overrides — built from the optionals rather than
    /// unpicking the visual string, and in whole words, because "12 s hold" is read out
    /// as "twelve ess hold".
    private var spokenOverride: String {
        var parts: [String] = []
        if let hold = set.holdSeconds { parts.append(String(localized: "\(hold) second hold")) }
        if let rest = set.restSeconds { parts.append(String(localized: "\(rest) second rest")) }
        if let band = set.targetBand {
            parts.append(String(localized: "target \(weightText(band.lowerBound)) to \(weightText(band.upperBound)) \(weightUnit.spokenName)"))
        } else if percentBandsVary, let percent = set.targetPercentBand {
            parts.append(String(localized: "target \(percentText(percent.lowerBound)) to \(percentText(percent.upperBound)) percent of your max"))
        }
        return parts.isEmpty ? "" : ", " + parts.joined(separator: ", ")
    }

    private var spokenRow: String {
        let duration = PlanMath.durationText(PlanMath.setSeconds(set, in: plan))
        return String(localized: "\(set.grip.spoken). \(repsText), \(tensionText)\(spokenOverride). \(duration).")
    }

    // MARK: - Position in the routine

    private var index: Int? { plan.sets.firstIndex(where: { $0.id == setID }) }

    private var canMoveUp: Bool {
        guard let index else { return false }
        return index > 0
    }

    private var canMoveDown: Bool {
        guard let index else { return false }
        return index < plan.sets.count - 1
    }
}

// MARK: - Context-menu preview

/// The COMPACT lift for a set row's context menu. The default preview hoisted the
/// row's ENTIRE expanded editor — a screen-tall platter with half-rendered chip grids
/// (Nuri, 2026-08-10: "I don't think it needs to show the entire page"). The menu is
/// about the set as a THING to move or copy, so the lift shows the thing: its glyph,
/// its grip, its size — the same anatomy as the collapsed row it stands in for.
struct SetRowPreview: View {
    let set: SetPlan
    let plan: SessionPlan

    var body: some View {
        HStack(spacing: 12) {
            RoundedRectangle(cornerRadius: 10, style: .continuous)
                .fill(Ink.tertiary.opacity(0.16))
                .frame(width: 44, height: 44)
                .overlay {
                    FingerGlyph(fingers: set.grip.fingers, position: set.grip.position,
                                dot: 5, gap: 2.5)
                }
            VStack(alignment: .leading, spacing: 2) {
                Text(set.grip.line)
                    .font(.system(.subheadline, weight: .semibold))
                    .monospacedDigit()
                    .foregroundStyle(Ink.primary)
                    .lineLimit(1)
                Text("\(set.repsPerSide) per side · \(PlanMath.hold(set, in: plan)) s hold · \(PlanMath.rest(set, in: plan)) s rest")
                    .font(.system(.footnote))
                    .monospacedDigit()
                    .foregroundStyle(Ink.secondary)
                    .lineLimit(1)
            }
        }
        .padding(16)
        .background(.regularMaterial)
    }
}
