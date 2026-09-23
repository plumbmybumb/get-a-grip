// SPDX-License-Identifier: MPL-2.0
// Original contributions Copyright 2026 Nuri Bruner.

import SwiftUI

/// The target load for ONE grip, as a single row on that grip's card.
///
/// On its own (Nuri, 2026-08-04: "that should be built in"), a routine-wide card had to
/// talk in abstractions; here the row can say the actual answer — "20–30 % · 6.0–9.0 kg"
/// — because it knows its grip.
///
/// Stored as a PERCENTAGE by default: your max moves, and a routine that keeps
/// prescribing last spring's load is the bookkeeping this app refuses. Kilograms are
/// resolved fresh at the start of every session.
///
/// **Kilograms are typeable too** (Nuri, 2026-08-09): a percentage of an unmeasured max
/// resolves to no band at run time. So the unit is a choice inside Custom, defaulting to
/// kilograms exactly when a percentage could not work; they land in the set's own
/// `targetLoKg`/`targetHiKg`, which `PlanMath.targetBand` ranks ahead of any percentage.
struct TargetBandRow: View {
    @Environment(\.weightUnit) private var weightUnit
    @Binding var set: SetPlan
    /// Every max on file, by grip and hand, so a percentage shows the ACTUAL kilograms each
    /// hand will be asked for.
    var maxes: MaxTable
    /// Whether this routine alternates hands. `bothHands` has no per-hand question, and the
    /// row must not invent one.
    var handMode: HandMode

    @Environment(\.accessibilityReduceMotion) private var reduceMotion

    /// Whether the editor is open. CLOSED on every appearance, deliberately — see `header`.
    @State private var expanded = false

    /// Sticky once opened: a custom band that LANDS on 20–30 % would otherwise close the
    /// fields under the finger still editing it.
    @State private var showsCustomFields = false

    /// How a custom band is expressed. Not persisted: the STORED band says which it is; this
    /// carries the choice only while the fields are open and momentarily empty.
    private enum Unit: Hashable { case percent, kilograms }
    @State private var unit: Unit = .percent

    /// The bands worth one tap. Low-intensity volume is the app's centre of gravity, so it
    /// gets two of the four; the others reach strength-endurance and max work.
    private static let bands: [(label: String, range: ClosedRange<Double>)] = [
        ("15–25", 0.15...0.25),
        ("20–30", 0.20...0.30),
        ("40–60", 0.40...0.60),
        ("80–100", 0.80...1.00),
    ]

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            header
            if expanded {
                editor
            } else if unresolved, let caption {
                // A percentage that resolves to NOTHING must not go quiet because the
                // editor is closed — the same class of bug as an override hidden until the
                // row opens. The set would run without a target.
                Text(caption)
                    .font(.system(.caption, weight: .medium))
                    .foregroundStyle(StatusTint.armed)
                    .fixedSize(horizontal: false, vertical: true)
            }
        }
        .padding(.vertical, 2)
        // The STORED band decides the unit, or reopening a kilogram band would show
        // percentage fields over it.
        .onAppear { unit = kgBand != nil ? .kilograms : .percent }
        .accessibilityElement(children: .contain)
        .accessibilityLabel(String(localized: "Target load for this grip"))
    }

    /// The row, always. Closed it is 44 pt and states the load; open it is the editor.
    ///
    /// Shut by default since 2026-08-11: open it measured ~293 pt, a third of an expanded
    /// set row, on a screen where most sets carry no target (the starter has none; Nuri's
    /// daily routine goes by feel). The right editor, the wrong thing to scroll past six
    /// times.
    private var header: some View {
        Button {
            withAnimation(Motion.state(reduceMotion)) { expanded.toggle() }
        } label: {
            HStack(spacing: 8) {
                Text("Target load")
                    .font(.system(.subheadline, weight: .medium))
                    .foregroundStyle(Ink.primary)
                Spacer(minLength: 8)
                Text(valueText)
                    .font(.system(.title3, weight: .semibold))
                    .monospacedDigit()
                    .contentTransition(.numericText())
                    .foregroundStyle(valueTint)
                    .lineLimit(1)
                    .minimumScaleFactor(0.8)
                Image(systemName: expanded ? "chevron.up" : "chevron.down")
                    .font(.system(.footnote, weight: .semibold))
                    .foregroundStyle(Ink.tertiary)
            }
            .frame(maxWidth: .infinity, minHeight: 44)
            // MANDATORY: a full-width label with a Spacer hit-tests only its glyphs.
            .contentShape(.rect)
        }
        .buttonStyle(PressFeedbackButtonStyle())
        .accessibilityLabel(String(localized: "Target load"))
        .accessibilityValue(valueText)
        .accessibilityHint(expanded ? String(localized: "Closes the target editor") : String(localized: "Opens the target editor"))
    }

    /// Amber when a hand will go untargeted — the collapsed row's only way to say the
    /// number beside it will not survive to the session.
    private var valueTint: Color {
        if unresolved { return StatusTint.armed }
        return hasTarget ? Ink.primary : Ink.tertiary
    }

    /// A percentage is set, and at least one hand has no max to resolve it against.
    private var unresolved: Bool {
        band != nil && sides.contains { resolved($0) == nil }
    }

    private var editor: some View {
        VStack(alignment: .leading, spacing: 8) {
            ChipGrid(base: 3) {
                Chip(title: String(localized: "None"), isSelected: !hasTarget && !editingCustom) { clear() }
                ForEach(Self.bands, id: \.label) { option in
                    // Selected only while the custom fields are CLOSED; otherwise tapping
                    // Custom lit "20–30 %", the band it seeds from.
                    Chip(title: String(localized: "\(option.label) %"),
                         isSelected: matches(option.range) && !editingCustom) {
                        apply(option.range)
                        showsCustomFields = false
                    }
                }
                // A band no chip holds — 17–22 % was Nuri's own example.
                Chip(title: String(localized: "Custom"), isSelected: editingCustom) { beginCustom() }
            }

            if editingCustom {
                // WHICH UNIT, asked only here: how to express the load is not a preset
                // value, and it only comes up once the presets do not fit.
                Picker("Unit", selection: unitBinding) {
                    Text("% of max").tag(Unit.percent)
                    Text(weightUnit.name).tag(Unit.kilograms)
                }
                .pickerStyle(.segmented)

                // ONE two-ended trimmer; a pair of steppers made "80 to 90" a dozen taps
                // (Nuri, 2026-08-10). It snaps to the 5 % / 0.5 kg resolution targets
                // round to.
                if unit == .kilograms {
                    BandTrimmer(lo: weightUnit.binding(trimLoKgBinding), hi: weightUnit.binding(trimHiKgBinding),
                                scale: weightUnit.sliderRangeFromKg(0...kgScaleTop), step: 0.5,
                                format: { "\($0.formatted(.number.precision(.fractionLength(1)))) \(weightUnit.symbol)" }, spokenUnit: weightUnit.spokenName)
                } else {
                    BandTrimmer(lo: trimLoBinding, hi: trimHiBinding,
                                scale: SetPlan.percentRange, step: 0.05,
                                format: { "\(Int(($0 * 100).rounded())) %" },
                                spokenUnit: String(localized: "percent of max"))
                }
                exactBounds
            }

            if let caption {
                Text(caption)
                    .font(.system(.caption, weight: .medium))
                    // Amber only when a hand will go untargeted; a per-hand breakdown is
                    // information, not a warning.
                    .foregroundStyle(sides.contains { resolved($0) == nil } && band != nil
                                     ? StatusTint.armed : Ink.tertiary)
                    .fixedSize(horizontal: false, vertical: true)
            }
        }
    }

    /// Typing keeps exact values; only dragging snaps to the trimmer's detents.
    private var exactBounds: some View {
        let isPercent = unit == .percent
        let bounds: ClosedRange<Double> = isPercent ? 1...100 : 0...weightUnit.fromKg(kgScaleTop)
        return VStack(spacing: 0) {
            ValueRow(title: String(localized: "Lower bound"), unit: isPercent ? "%" : weightUnit.symbol,
                     value: exactBound(lower: true), range: bounds, decimals: isPercent ? 0 : 1,
                     control: .none)
            ValueRow(title: String(localized: "Upper bound"), unit: isPercent ? "%" : weightUnit.symbol,
                     value: exactBound(lower: false), range: bounds, decimals: isPercent ? 0 : 1,
                     control: .none)
        }
    }

    private func exactBound(lower: Bool) -> Binding<Double> {
        Binding {
            let current = unit == .percent ? (band ?? 0.20...0.30) : (kgBand ?? Self.defaultKgBand)
            let amount = lower ? current.lowerBound : current.upperBound
            return unit == .percent ? amount * 100 : weightUnit.fromKg(amount)
        } set: { typed in
            let percent = unit == .percent
            let current = percent ? (band ?? 0.20...0.30) : (kgBand ?? Self.defaultKgBand)
            let value = percent ? typed / 100 : weightUnit.toKg(typed)
            // Crossing the other end moves it too, so labels never silently swap roles.
            let next = lower ? value...max(value, current.upperBound) : min(value, current.lowerBound)...value
            if percent { apply(next) } else { applyKg(next) }
        }
    }

    // MARK: - Derived

    // `self.` is load-bearing: a computed property whose body STARTS with the
    // identifier `set` parses as a setter accessor.
    private var band: ClosedRange<Double>? { self.set.targetPercentBand }
    /// An explicit kilogram band, which OUTRANKS any percentage (as in `PlanMath.targetBand`).
    /// Setting either clears the other, so the row never shows one and runs the other.
    private var kgBand: ClosedRange<Double>? { self.set.targetBand }
    private var hasTarget: Bool { kgBand != nil || band != nil }

    /// Whether a percentage could resolve to anything for this grip. When it cannot,
    /// kilograms are the only honest way to prescribe a load.
    private var hasResolvableMax: Bool {
        sides.contains { maxes.max(grip: self.set.grip.key, side: $0).map { $0 > 0 } ?? false }
    }

    /// Where a kilogram band starts with nothing to seed it: the low-intensity no-hang load
    /// the app is built around, in units usable without a max.
    private static let defaultKgBand: ClosedRange<Double> = 10...15

    /// The hands this routine asks about, in the order the runner alternates them.
    private var sides: [Side] {
        handMode.sideCount > 1 ? [.left, .right] : [.both]
    }

    /// The kilograms this set asks of one hand, resolved locally from the set's percentage,
    /// not via `PlanMath.targetBand`, because this row holds no whole `SessionPlan`.
    private func resolved(_ side: Side) -> ClosedRange<Double>? {
        // An explicit band needs no max and is the same for both hands.
        if let kgBand { return kgBand }
        guard let band,
              let maxKg = maxes.max(grip: self.set.grip.key, side: side), maxKg > 0
        else { return nil }
        return PlanMath.roundedToHalfKg(maxKg * band.lowerBound)
             ... PlanMath.roundedToHalfKg(maxKg * band.upperBound)
    }

    /// Whether the two hands will be asked for different loads.
    private var differsByHand: Bool {
        sides.count > 1 && resolved(.left) != resolved(.right)
    }

    /// The percentage IS the setting and the kilograms its consequence, so the row leads with
    /// kilograms (what you pull against) when it can compute them, else the percentage.
    ///
    /// **When the hands differ there is no single number**, so it shows the percentage (the
    /// one figure true of both) and the caption carries the two loads.
    private var valueText: String {
        if let kgBand { return weightUnit.bandText(kgBand) }
        guard let band else { return String(localized: "None") }
        guard !differsByHand, let kg = resolved(sides[0]) else { return percentText(band) }
        return weightUnit.bandText(kg)
    }

    private var caption: String? {
        if kgBand != nil {
            // The part worth stating: an explicit load goes stale, the trade for not
            // needing a max.
            return String(localized: "A fixed load, the same on both hands — it stays put when your max moves.")
        }
        guard let band else { return nil }
        let resolvedSides = sides.filter { resolved($0) != nil }

        if resolvedSides.isEmpty {
            // Named: a percentage with no max resolves to no target at run time.
            return String(localized: "No max on file for this grip yet, so this shows no target during a session. Add one in Settings › Maxes.")
        }
        // One hand has a max and the other not (a left- or right-only max). Say
        // WHICH is unloaded, since the row shows a confident band for the other.
        if resolvedSides.count < sides.count, let missing = sides.first(where: { resolved($0) == nil }) {
            return String(localized: "No max for your \(missing.name.lowercased()) hand, so those pulls show no target. Add one in Settings › Maxes.")
        }
        guard differsByHand else {
            return String(localized: "\(percentText(band)) of your max on this grip")
        }
        let loads = sides.compactMap { side -> String? in
            guard let kg = resolved(side) else { return nil }
            return "\(side.prompt.prefix(1)) \(weightUnit.bandText(kg, withUnit: false))"
        }
        return String(localized: "\(percentText(band)) of each hand's max · \(loads.joined(separator: " · ")) \(weightUnit.symbol)")
    }

    private func matches(_ range: ClosedRange<Double>) -> Bool {
        // A kilogram band is never a percentage preset: 20 kg is not "20 %".
        guard kgBand == nil, let band else { return false }
        return abs(band.lowerBound - range.lowerBound) < 0.001
            && abs(band.upperBound - range.upperBound) < 0.001
    }

    /// Custom mode: you asked for it, or the stored band is one no preset can express (synced
    /// or typed earlier), which stops a 17–22 % band opening as "None".
    private var editingCustom: Bool {
        // A kilogram band is always custom: no chip can express one.
        showsCustomFields || kgBand != nil
            || (band != nil && !Self.bands.contains { matches($0.range) })
    }

    /// Opens the fields on a band already in range, so tapping Custom never changes the load.
    private func beginCustom() {
        showsCustomFields = true
        guard !hasTarget else {
            unit = kgBand != nil ? .kilograms : .percent
            return
        }
        // **Kilograms when a percentage could not work**: "20 % of your max" for an
        // unmeasured grip resolves to nothing at run time (Nuri, 2026-08-09).
        unit = hasResolvableMax ? .percent : .kilograms
        if unit == .kilograms { applyKg(Self.defaultKgBand) } else { apply(0.20...0.30) }
    }

    private var unitBinding: Binding<Unit> {
        Binding { unit } set: { new in
            guard new != unit else { return }
            unit = new
            switch new {
            // Seed from what is on screen, so switching units reads as a conversion.
            case .kilograms: applyKg(resolved(sides[0]) ?? Self.defaultKgBand)
            case .percent:   apply(band ?? 0.20...0.30)
            }
        }
    }

    private var trimLoBinding: Binding<Double> {
        Binding { band?.lowerBound ?? 0.20 } set: { new in
            self.set.targetLoPercent = new
            self.set.targetLoKg = nil
            self.set.targetHiKg = nil
        }
    }

    private var trimHiBinding: Binding<Double> {
        Binding { band?.upperBound ?? 0.30 } set: { new in
            self.set.targetHiPercent = new
            self.set.targetLoKg = nil
            self.set.targetHiKg = nil
        }
    }

    private var trimLoKgBinding: Binding<Double> {
        Binding { kgBand?.lowerBound ?? Self.defaultKgBand.lowerBound } set: { new in
            self.set.targetLoKg = new
            if self.set.targetHiKg == nil { self.set.targetHiKg = new }
            clearPercent()
        }
    }

    private var trimHiKgBinding: Binding<Double> {
        Binding { kgBand?.upperBound ?? Self.defaultKgBand.upperBound } set: { new in
            self.set.targetHiKg = new
            if self.set.targetLoKg == nil { self.set.targetLoKg = new }
            clearPercent()
        }
    }

    /// The kilogram scale's top: grows with the band and the strongest max on file, so
    /// strong pullers fit without making 10–15 kg a sliver.
    private var kgScaleTop: Double {
        let bandTop = kgBand?.upperBound ?? Self.defaultKgBand.upperBound
        let maxTop = sides.compactMap { maxes.max(grip: self.set.grip.key, side: $0) }.max() ?? 0
        return (Swift.max(40, bandTop * 1.3, maxTop * 1.2) / 5).rounded(.up) * 5
    }

    private func applyKg(_ range: ClosedRange<Double>) {
        set.targetLoKg = range.lowerBound
        set.targetHiKg = range.upperBound
        clearPercent()
    }

    /// The two cannot coexist: `PlanMath.targetBand` ranks kilograms first, so a leftover
    /// percentage would show one number while the session runs another.
    private func clearPercent() {
        set.targetLoPercent = nil
        set.targetHiPercent = nil
    }

    private func apply(_ range: ClosedRange<Double>) {
        set.targetLoPercent = range.lowerBound
        set.targetHiPercent = range.upperBound
        // Kilograms would silently win (see `clearPercent`), so picking a
        // percentage clears any the list editor put there.
        set.targetLoKg = nil
        set.targetHiKg = nil
    }

    private func clear() {
        set.targetLoPercent = nil
        set.targetHiPercent = nil
        set.targetLoKg = nil
        set.targetHiKg = nil
        showsCustomFields = false
    }

    private func percentText(_ band: ClosedRange<Double>) -> String {
        "\(Int((band.lowerBound * 100).rounded()))–\(Int((band.upperBound * 100).rounded())) %"
    }

}
