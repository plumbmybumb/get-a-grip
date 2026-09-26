// SPDX-License-Identifier: MPL-2.0
// Original contributions Copyright 2026 Nuri Bruner.

import SwiftUI

/// One row of the routine — collapsed it is a sentence, expanded it is the whole set.
///
/// At most one row is open at a time (the builder owns that), which guarantees only ONE
/// dense control cluster on screen at any moment.
///
/// **It compares itself on VALUES, and writes through closures**, so it re-runs only
/// when its own numbers change: `set` and `defaults` arrive as values, `==` compares
/// those (and the flags), the builder wraps it in `.equatable()`, and writes go through
/// `SetAccess`. See `BuilderInputs.swift` for the measurement and `DraftAccess` for why
/// not a `Binding`.
struct SetRowView: View, Equatable {
    @Environment(\.weightUnit) private var weightUnit
    /// This row's set, as a value. The builder's `ForEach` keys the row on `set.id`, so
    /// a reorder cannot point a row at its neighbour.
    let set: SetPlan
    /// `plan.routineLevel` — the plan-level numbers this set inherits (hold, rest, hands,
    /// lead-in, the routine's band), drawn from here and compared on `setRowKey`. See
    /// `BuilderInputs`.
    let defaults: SessionPlan
    let isExpanded: Bool
    let canMoveUp: Bool
    let canMoveDown: Bool
    /// Every max on file, by grip and hand — a value, so the row never touches the store.
    let maxes: MaxTable
    /// Folded once by the builder; per row, both summaries rescanned every set.
    let percentBandsVary: Bool

    /// The write path, and nothing else: writes the set back by id, and reads only what the
    /// row was drawn with plus its own writes — never the draft. Not drawn from, not in `==`.
    let live: SetAccess

    var onTap: () -> Void
    /// Asks the BUILDER to open the island panel for this set's grip.
    var onEditGrip: () -> Void
    var onMoveUp: () -> Void
    var onMoveDown: () -> Void
    var onDuplicate: () -> Void
    var onRemove: () -> Void

    /// Everything the row draws, and nothing it only writes — `SessionPlan.setRowKey`
    /// lists the plan-level fields.
    nonisolated static func == (a: Self, b: Self) -> Bool {
        a.set == b.set
            && a.isExpanded == b.isExpanded
            && a.canMoveUp == b.canMoveUp
            && a.canMoveDown == b.canMoveDown
            && a.percentBandsVary == b.percentBandsVary
            && a.maxes == b.maxes
            && a.defaults.setRowKey == b.defaults.setRowKey
    }

    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @Environment(\.dynamicTypeSize) private var typeSize

    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            Button(action: onTap) {
                // Accessibility sizes STACK: the chevron beside a two-line grip name
                // crushed every word at AX3.
                if typeSize.isAccessibilitySize { stackedFace } else { compactFace }
            }
                // A row-sized card that scaled on press would drag its backdrop out from
                // under the editor it shares a card with.
                .buttonStyle(PressFeedbackButtonStyle(scales: false))
                // ON THE HEADER, never the whole row: on the row, a hold anywhere in the
                // EXPANDED editor lifted the entire editing surface (Nuri, 2026-08-10). The
                // header is always on screen and never grabs touches from the controls.
                .contextMenu {
                    Button("Move up", systemImage: "arrow.up", action: onMoveUp)
                        .disabled(!canMoveUp)
                    Button("Move down", systemImage: "arrow.down", action: onMoveDown)
                        .disabled(!canMoveDown)
                    Button("Duplicate", systemImage: "plus.square.on.square", action: onDuplicate)
                    Button("Remove", systemImage: "trash", role: .destructive, action: onRemove)
                } preview: {
                    SetRowPreview(set: set, plan: defaults)
                }

            if isExpanded {
                editor
                    .padding(.horizontal, 16)
                    .padding(.bottom, 16)
            }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        // Flat, not material: the row animates open and shut above five siblings,
        // and a blur per row per frame is what a 13 mini could not afford.
        // See `CardSurface`.
        .cardSurface(.flat,
                     in: RoundedRectangle(cornerRadius: Metrics.radiusCard, style: .continuous))
        .animation(Motion.state(reduceMotion),
                   value: isExpanded)
    }

    // MARK: - This row's set

    /// Shared with every control in the editor — see `SetAccess`.
    private var setBinding: Binding<SetPlan> { live.binding }

    // MARK: - Collapsed

    /// Two lines: the grip, then pulls · load · any custom timing.
    private var compactFace: some View {
        HStack(alignment: .center, spacing: 12) {
            FingerGlyph(fingers: set.grip.fingers, position: set.grip.position,
                        dot: 6, gap: 3)
            VStack(alignment: .leading, spacing: 2) {
                Text(set.grip.line)
                    .font(.system(.subheadline, weight: .semibold))
                    .monospacedDigit()
                    .foregroundStyle(Ink.primary)
                    .lineLimit(2)
                compactDetail
                    .font(.system(.footnote))
                    .monospacedDigit()
            }
            Spacer(minLength: 8)
            Image(systemName: isExpanded ? "chevron.up" : "chevron.down")
                .font(.system(.footnote, weight: .semibold))
                .foregroundStyle(Ink.tertiary)
        }
        .padding(.horizontal, 16)
        .padding(.vertical, 12)
        .frame(maxWidth: .infinity, minHeight: 56, alignment: .leading)
        .contentShape(RoundedRectangle(cornerRadius: Metrics.radiusCard, style: .continuous))
        .accessibilityElement(children: .ignore)
        .accessibilityLabel(spokenRow)
        .accessibilityHint(isExpanded ? String(localized: "Closes this set") : String(localized: "Opens this set for editing"))
    }

    /// Pulls, then the load — in TERTIARY when it is only the routine's band repeated, so
    /// six rows of the same percentage do not shout — then any timing override in primary.
    /// The compact faces at accessibility sizes: glyph and chevron, then the words, each
    /// on its own line and free to wrap.
    private var stackedFace: some View {
        VStack(alignment: .leading, spacing: 6) {
            HStack {
                FingerGlyph(fingers: set.grip.fingers, position: set.grip.position,
                            dot: 6, gap: 3)
                Spacer(minLength: 8)
                Image(systemName: isExpanded ? "chevron.up" : "chevron.down")
                    .font(.system(.footnote, weight: .semibold))
                    .foregroundStyle(Ink.tertiary)
            }
            Text(set.grip.line)
                .font(.system(.subheadline, weight: .semibold))
                .foregroundStyle(Ink.primary)
                .fixedSize(horizontal: false, vertical: true)
            compactDetail
                .font(.system(.footnote))
                .fixedSize(horizontal: false, vertical: true)
        }
        .padding(16)
        .frame(maxWidth: .infinity, alignment: .leading)
        .contentShape(Rectangle())
        .accessibilityElement(children: .ignore)
        .accessibilityLabel(spokenRow)
        .accessibilityHint(isExpanded ? String(localized: "Closes this set") : String(localized: "Opens this set for editing"))
    }

    private var compactDetail: Text {
        var text = Text(repsText).foregroundStyle(Ink.secondary)
        if let load = loadText {
            let inherited = !set.hasTarget && !set.hasPercentTarget
            text = Text("\(text)\(Text(" · " + load).foregroundStyle(inherited ? Ink.tertiary : Ink.secondary))")
        }
        let timing = timingOverrideText
        guard !timing.isEmpty else { return text }
        return Text("\(text)\(Text(" · " + timing).foregroundStyle(Ink.primary))")
    }

    /// The set's load as it will run: its own kilograms, its own percentage, or the
    /// routine's percentage it follows. nil = no target.
    private var loadText: String? {
        if let band = set.targetBand {
            return String(localized: "\(weightUnit.number(band.lowerBound))–\(weightUnit.number(band.upperBound)) \(weightUnit.symbol)")
        }
        if let percent = set.targetPercentBand ?? defaults.targetPercentBand {
            return "\(percentText(percent.lowerBound))–\(percentText(percent.upperBound)) %"
        }
        return nil
    }

    /// Custom timing, where it differs from the routine's — in PRIMARY ink on the closed
    /// row: an override invisible until you open the row produces a session nobody can
    /// explain, including whoever set it.
    private var timingOverrideText: String {
        var parts: [String] = []
        if let hold = set.holdSeconds, hold != defaults.holdSeconds { parts.append(String(localized: "\(hold) s hold")) }
        if let rest = set.restSeconds, rest != defaults.restSeconds { parts.append(String(localized: "\(rest) s rest")) }
        return parts.joined(separator: " · ")
    }

    // MARK: - Expanded editor
    //
    // Fixed order, and NO third level of disclosure: a height-animating reveal
    // inside a height-animating row is where this layout breaks. Two toggles
    // with fixed-height content is the ceiling.

    private var editor: some View {
        VStack(alignment: .leading, spacing: 16) {
            Divider().overlay(Ink.tertiary.opacity(0.22))

            // ONE 60 pt control, where edge slider + finger pad + position chips
            // stacked to ~400. They remain behind "Something else" — see `GripToken`.
            GripToken(grip: set.grip, onEdit: onEditGrip)

            // BOTH readouts, always — never a "count by reps / by time" mode, which is
            // remembered state that leaves set 3 in seconds and set 4 in reps with no
            // memory of why.
            // A STEPPER: a small integer wanted EXACTLY, the HIG's case for one. Four
            // chips could never hold the 4 a max protocol asks for. Repeats while held.
            IntValueRow(title: String(localized: "Pulls per side"),
                        value: setBinding.repsPerSide,
                        range: 1...40, limit: 1...SetPlan.repsRange.upperBound,
                        control: .stepper)

            customTiming
            targetSection
            moveRow
            removeRow
        }
    }

    /// CUSTOM TIMING — off, the set follows the Rhythm page's hold and rest; on, it has
    /// its own, on the same ladder steppers (Nuri, 2026-09-26).
    ///
    /// Per-set timing is real (a max ramp, a repeater block), but it was the rule on every
    /// row — two full dials in each set — for a routine whose sets almost never differ. A
    /// switch states the exception; the closed row still shows it in primary ink.
    private var customTiming: some View {
        VStack(alignment: .leading, spacing: 0) {
            Toggle("Custom timing", isOn: customTimingBinding.animation(Motion.state(reduceMotion)))
                .font(.system(.subheadline, weight: .medium))
                .foregroundStyle(Ink.primary)
                .tint(Accent.graphite)
                .frame(minHeight: 46)
            if set.overridesTiming {
                VStack(alignment: .leading, spacing: 0) {
                    TimingStepper(kind: .hold, value: holdBinding)
                    TimingStepper(kind: .rest, value: restBinding)
                }
                .padding(.leading, 12)
                .transition(.opacity.combined(with: .move(edge: .top)))
            }
        }
    }

    /// The set's target load, in the grammar of `customTiming`: inherited unless this row
    /// says otherwise, and saying which in tertiary ink.
    ///
    /// "Different target for this set" only when there IS a routine band to differ from;
    /// otherwise the honest label is "Target load".
    private var targetSection: some View {
        VStack(alignment: .leading, spacing: 12) {
            // The full band row (presets, trimmer, percent or kg) — it replaced a
            // kg-only toggle that could not show a per-set percent band, which the Max
            // day ramp is made of.
            TargetBandRow(set: setBinding, maxes: maxes, handMode: defaults.handMode)

            // With no band of its own, the set follows the routine's, said under the
            // row that could override it.
            if !set.hasTarget, !set.hasPercentTarget, inheritsTarget {
                Text(inheritedTargetText)
                    .font(.system(.footnote))
                    .foregroundStyle(Ink.tertiary)
                    .fixedSize(horizontal: false, vertical: true)
            }
        }
    }

    /// Whether the ROUTINE sets a percentage this row would otherwise follow.
    private var inheritsTarget: Bool { defaults.targetPercentBand != nil }

    /// What the inherited percentage means for THIS grip. Without a max the row must say
    /// so rather than show a percentage that resolves to nothing at session time.
    private var inheritedTargetText: String {
        guard let percent = defaults.targetPercentBand else { return "" }
        let range = "\(percentText(percent.lowerBound))–\(percentText(percent.upperBound)) %"
        // PER HAND, through the shared formatter, so no two screens quote different
        // loads for one routine.
        guard let load = weightUnit.targetText(set, in: defaults, maxes: maxes) else {
            return String(localized: "Routine target: \(range) of max. No max for this grip yet.")
        }
        return String(localized: "Routine target: \(range), so \(load).")
    }

    private func percentText(_ fraction: Double) -> String {
        "\(Int((fraction * 100).rounded()))"
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
        // The dim state says nothing about why on its own. Keyed on the stable,
        // unlocalized systemImage, never on the localized title.
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

    /// ON seeds both overrides with the routine's current values, so the steppers start
    /// where the set already was; OFF returns the set to the routine's timing.
    private var customTimingBinding: Binding<Bool> {
        Binding {
            setBinding.wrappedValue.overridesTiming
        } set: { on in
            var updated = setBinding.wrappedValue
            if on {
                updated.holdSeconds = PlanMath.hold(updated, in: defaults)
                updated.restSeconds = PlanMath.rest(updated, in: defaults)
            } else {
                updated.holdSeconds = nil
                updated.restSeconds = nil
            }
            setBinding.wrappedValue = updated
        }
    }

    private var holdBinding: Binding<Int> {
        Binding { PlanMath.hold(setBinding.wrappedValue, in: defaults) }
            set: { setBinding.wrappedValue.holdSeconds = $0 }
    }

    private var restBinding: Binding<Int> {
        Binding { PlanMath.rest(setBinding.wrappedValue, in: defaults) }
            set: { setBinding.wrappedValue.restSeconds = $0 }
    }

    // MARK: - Derived copy

    private var repsText: String {
        guard defaults.handMode.sideCount > 1 else {
            // One-sided modes have no side to divide by, so "per side" is dropped.
            return String(localized: "\(set.repsPerSide) \(set.repsPerSide == 1 ? String(localized: "pull") : String(localized: "pulls"))")
        }
        return String(localized: "\(set.repsPerSide) per side")
    }

    private var tensionText: String {
        if let perSide = PlanMath.tensionSecondsPerSide(set, in: defaults) {
            return String(localized: "\(PlanMath.clockText(perSide)) under tension per side")
        }
        let reps = PlanMath.repCount(set, mode: defaults.handMode)
        return String(localized: "\(PlanMath.clockText(reps * PlanMath.hold(set, in: defaults))) under tension")
    }

    /// The spoken form of the same overrides, built from the optionals in whole words:
    /// "12 s hold" is read out as "twelve ess hold".
    private var spokenOverride: String {
        var parts: [String] = []
        if let hold = set.holdSeconds, hold != defaults.holdSeconds { parts.append(String(localized: "\(hold) second hold")) }
        if let rest = set.restSeconds, rest != defaults.restSeconds { parts.append(String(localized: "\(rest) second rest")) }
        if let band = set.targetBand {
            parts.append(String(localized: "target \(weightUnit.number(band.lowerBound)) to \(weightUnit.number(band.upperBound)) \(weightUnit.spokenName)"))
        } else if percentBandsVary, let percent = set.targetPercentBand {
            parts.append(String(localized: "target \(percentText(percent.lowerBound)) to \(percentText(percent.upperBound)) percent of your max"))
        }
        return parts.isEmpty ? "" : ", " + parts.joined(separator: ", ")
    }

    private var spokenRow: String {
        let duration = PlanMath.durationText(PlanMath.setSeconds(set, in: defaults))
        return String(localized: "\(set.grip.spoken). \(repsText), \(tensionText)\(spokenOverride). \(duration).")
    }

}

// MARK: - Context-menu preview

/// The COMPACT lift for a set row's context menu. The default preview hoisted the whole
/// expanded editor (Nuri, 2026-08-10). The menu is about the set as a THING to move or
/// copy, so the lift shows its glyph, grip and size, like the collapsed row.
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
