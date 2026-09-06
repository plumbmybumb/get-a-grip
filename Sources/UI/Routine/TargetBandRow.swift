// SPDX-License-Identifier: MPL-2.0
// Original contributions Copyright 2026 Nuri Bruner.

import SwiftUI

/// The target load for ONE grip, as a single row on that grip's card.
///
/// It used to be a screen of its own in the setup deck, which was wrong twice over
/// (Nuri, 2026-08-04: "I don't see why there's a separate card for the Max grip thing —
/// that should be built in"). A percentage only means kilograms once you know WHICH grip
/// it applies to, so a routine-wide card had to talk in abstractions and then hope you
/// checked each grip's max separately. Here the row can say the actual answer — "20–30 %
/// · 6.0–9.0 kg" — because it knows the grip it is sitting on.
///
/// Stored as a PERCENTAGE on the set by default, not kilograms: your max moves, and a
/// routine that silently keeps prescribing last spring's load is the bookkeeping this app
/// exists to refuse. The kilograms are resolved fresh at the start of every session.
///
/// **But kilograms are typeable too** (Nuri, 2026-08-09: *"we also need to be able to set
/// weight ranges even if you don't have your max recorded"*). A percentage of a max you
/// have not measured is a target of nothing — it resolves to no band at run time, and
/// finding that out mid-session is the wrong moment. So the unit is a choice inside
/// Custom, and it defaults to kilograms exactly when the percentage could not work. The
/// kilograms are then the set's own `targetLoKg`/`targetHiKg`, which `PlanMath.targetBand`
/// already ranks ahead of any percentage.
struct TargetBandRow: View {
    @Binding var set: SetPlan
    /// Every max on file, by grip and hand — so a percentage can be shown as the ACTUAL
    /// kilograms each hand will be asked for, which is the whole point of the row.
    var maxes: MaxTable
    /// Whether this routine alternates hands. `bothHands` puts them on the edge together,
    /// so there is no per-hand question to answer and the row must not invent one.
    var handMode: HandMode

    @Environment(\.accessibilityReduceMotion) private var reduceMotion

    /// Whether the editor is open. CLOSED on every appearance, deliberately — see `header`.
    @State private var expanded = false

    /// Sticky once opened: a custom band that happens to LAND on 20–30 % would otherwise
    /// close the fields under the finger that was still editing it.
    @State private var showsCustomFields = false

    /// How a custom band is expressed. Not persisted — the STORED band says which it is
    /// (`targetLoKg` set means kilograms), and this only carries the choice while the
    /// fields are open and both are momentarily empty.
    private enum Unit: Hashable { case percent, kilograms }
    @State private var unit: Unit = .percent

    /// The bands worth one tap. Low-intensity volume is the app's centre of gravity, so
    /// it gets two of the four; the others reach strength-endurance and max work without
    /// pretending a slider would be more precise than a person's intent.
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
                // A percentage that resolves to NOTHING must not go quiet just because the
                // editor is closed. That is the same class of bug as a per-set override
                // that only appears once you open the row — the set will run without a
                // target and the row would be the last place you could have found out.
                Text(caption)
                    .font(.system(.caption, weight: .medium))
                    .foregroundStyle(StatusTint.armed)
                    .fixedSize(horizontal: false, vertical: true)
            }
        }
        .padding(.vertical, 2)
        // The STORED band says which unit it is; `unit` only carries the choice while the
        // fields are open. Without this, reopening a set that already holds a kilogram
        // band would show the percentage fields over it.
        .onAppear { unit = kgBand != nil ? .kilograms : .percent }
        .accessibilityElement(children: .contain)
        .accessibilityLabel(String(localized: "Target load for this grip"))
    }

    /// The row, always. Closed it is 44 pt and states the load; open it is the editor.
    ///
    /// It shut by default 2026-08-11, when the section was measured at **~293 pt — a third
    /// of the whole expanded set row**, on a screen where most sets carry no target at all:
    /// the shipping starter has none, and Nuri's own daily routine deliberately has none
    /// because it goes by feel. Six chips, a unit picker, a trimmer, a scale and a
    /// two-line caption is the right editor and the wrong thing to look at six times while
    /// scrolling past sets you are not editing.
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
            // MANDATORY: the label holds a Spacer and draws full-width, and SwiftUI's
            // default hit area is the label's OPAQUE content.
            .contentShape(.rect)
        }
        .buttonStyle(PressFeedbackButtonStyle())
        .accessibilityLabel(String(localized: "Target load"))
        .accessibilityValue(valueText)
        .accessibilityHint(expanded ? String(localized: "Closes the target editor") : String(localized: "Opens the target editor"))
    }

    /// Amber when a hand will genuinely go untargeted — the collapsed row's only way to
    /// say that the number beside it will not survive to the session.
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
                    // A preset only reads as selected while the custom fields are CLOSED.
                    // Otherwise tapping Custom lit "20–30 %" — the band it seeds from —
                    // and the row said it was on a preset while offering you two fields.
                    Chip(title: String(localized: "\(option.label) %"),
                         isSelected: matches(option.range) && !editingCustom) {
                        apply(option.range)
                        showsCustomFields = false
                    }
                }
                // A band nobody thought to make a chip — 17–22 % was Nuri's own example,
                // and four presets could never have held it.
                Chip(title: String(localized: "Custom"), isSelected: editingCustom) { beginCustom() }
            }

            if editingCustom {
                // WHICH UNIT, asked only here. It is a question about how you want to
                // express the load, not another preset value, so it does not belong in
                // the chip row above — and it only ever comes up once you have said the
                // presets do not fit.
                Picker("Unit", selection: unitBinding) {
                    Text("% of max").tag(Unit.percent)
                    Text("Kilograms").tag(Unit.kilograms)
                }
                .pickerStyle(.segmented)

                // ONE two-ended control, the trimmer — it replaced a pair of steppers
                // that made "80 to 90" a dozen taps (Nuri, 2026-08-10). The steps snap
                // to the same 5 % / 0.5 kg resolution the app rounds targets to, so
                // every value it can land on is one people quote to each other exactly.
                if unit == .kilograms {
                    BandTrimmer(lo: trimLoKgBinding, hi: trimHiKgBinding,
                                scale: 0...kgScaleTop, step: 0.5,
                                format: { "\(kgText($0)) kg" }, spokenUnit: String(localized: "kilograms"))
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
                    // Amber only when a hand will genuinely go untargeted — a per-hand
                    // breakdown is information, not a warning.
                    .foregroundStyle(sides.contains { resolved($0) == nil } && band != nil
                                     ? StatusTint.armed : Ink.tertiary)
                    .fixedSize(horizontal: false, vertical: true)
            }
        }
    }

    /// Typing keeps exact values; only dragging snaps to the trimmer's detents.
    private var exactBounds: some View {
        let isPercent = unit == .percent
        let bounds: ClosedRange<Double> = isPercent ? 1...100 : 0...kgScaleTop
        return VStack(spacing: 0) {
            ValueRow(title: String(localized: "Lower bound"), unit: isPercent ? "%" : String(localized: "kg"),
                     value: exactBound(lower: true), range: bounds, decimals: isPercent ? 0 : 1,
                     control: .none)
            ValueRow(title: String(localized: "Upper bound"), unit: isPercent ? "%" : String(localized: "kg"),
                     value: exactBound(lower: false), range: bounds, decimals: isPercent ? 0 : 1,
                     control: .none)
        }
    }

    private func exactBound(lower: Bool) -> Binding<Double> {
        Binding {
            let current = unit == .percent ? (band ?? 0.20...0.30) : (kgBand ?? Self.defaultKgBand)
            return (lower ? current.lowerBound : current.upperBound) * (unit == .percent ? 100 : 1)
        } set: { typed in
            let percent = unit == .percent
            let current = percent ? (band ?? 0.20...0.30) : (kgBand ?? Self.defaultKgBand)
            let value = percent ? typed / 100 : typed
            // Crossing the other end moves it too, so labels never silently swap roles.
            let next = lower ? value...max(value, current.upperBound) : min(value, current.lowerBound)...value
            if percent { apply(next) } else { applyKg(next) }
        }
    }

    // MARK: - Derived

    // `self.` is load-bearing, not noise: a computed property whose body STARTS with the
    // identifier `set` parses as a setter accessor, and the property is named `set`.
    private var band: ClosedRange<Double>? { self.set.targetPercentBand }
    /// An explicit kilogram band, which OUTRANKS any percentage — the same order
    /// `PlanMath.targetBand` resolves in. The two are mutually exclusive here: setting
    /// either clears the other, so the row can never show one and run the other.
    private var kgBand: ClosedRange<Double>? { self.set.targetBand }
    private var hasTarget: Bool { kgBand != nil || band != nil }

    /// Whether a percentage could resolve to anything for this grip. When it cannot, a
    /// percentage target is a target of NOTHING, and kilograms are the only honest way to
    /// prescribe a load.
    private var hasResolvableMax: Bool {
        sides.contains { maxes.max(grip: self.set.grip.key, side: $0).map { $0 > 0 } ?? false }
    }

    /// Where a kilogram band starts when there is nothing to seed it from. The low-
    /// intensity no-hang load this whole app is built around, in the units someone with
    /// no max on file can still reason about.
    private static let defaultKgBand: ClosedRange<Double> = 10...15

    /// The hands this routine asks about, in the order the runner alternates them.
    private var sides: [Side] {
        handMode.sideCount > 1 ? [.left, .right] : [.both]
    }

    /// The kilograms this set will ask of one hand. Resolved locally from the set's own
    /// percentage rather than through `PlanMath.targetBand`, because this row
    /// deliberately does not hold a whole `SessionPlan`.
    private func resolved(_ side: Side) -> ClosedRange<Double>? {
        // An explicit band needs no resolving and no max — it is already the answer, and
        // it is the same answer for both hands.
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

    /// The percentage IS the setting; the kilograms are its consequence, so the row
    /// leads with kilograms once it can compute them — that is the number you pull
    /// against — and falls back to the percentage when there is no max to resolve it.
    ///
    /// **When the hands differ there is no single number to lead with**, so it shows the
    /// percentage — the thing you actually set, and the one figure that IS true of both
    /// hands — and the caption underneath carries the two loads, where there is room for
    /// them to wrap.
    private var valueText: String {
        if let kgBand { return PlanMath.bandText(kgBand) }
        guard let band else { return String(localized: "None") }
        guard !differsByHand, let kg = resolved(sides[0]) else { return percentText(band) }
        return PlanMath.bandText(kg)
    }

    private var caption: String? {
        if kgBand != nil {
            // What it does NOT do is the part worth stating: an explicit load is the one
            // kind that goes stale, and it is the trade you make for not needing a max.
            return String(localized: "A fixed load, the same on both hands — it stays put when your max moves.")
        }
        guard let band else { return nil }
        let resolvedSides = sides.filter { resolved($0) != nil }

        if resolvedSides.isEmpty {
            // Named, not hinted: a percentage with no max resolves to no target at all at
            // run time, and finding that out mid-session is the wrong moment.
            return String(localized: "No max on file for this grip yet, so this shows no target during a session. Add one in Settings › Maxes.")
        }
        // One hand has a max and the other does not — which is what an explicitly
        // left-only or right-only max leaves behind. Say WHICH hand is unloaded, because
        // the row above will happily show a confident band for the other one.
        if resolvedSides.count < sides.count, let missing = sides.first(where: { resolved($0) == nil }) {
            return String(localized: "No max for your \(missing.name.lowercased()) hand, so those pulls show no target. Add one in Settings › Maxes.")
        }
        guard differsByHand else {
            return String(localized: "\(percentText(band)) of your max on this grip")
        }
        let loads = sides.compactMap { side -> String? in
            guard let kg = resolved(side) else { return nil }
            return "\(side.prompt.prefix(1)) \(PlanMath.bandText(kg, withUnit: false))"
        }
        return String(localized: "\(percentText(band)) of each hand's max · \(loads.joined(separator: " · ")) kg")
    }

    private func matches(_ range: ClosedRange<Double>) -> Bool {
        // A kilogram band is never a percentage preset, however the numbers happen to
        // line up — 20 kg is not "20 %".
        guard kgBand == nil, let band else { return false }
        return abs(band.lowerBound - range.lowerBound) < 0.001
            && abs(band.upperBound - range.upperBound) < 0.001
    }

    /// Whether the row is in custom mode: either you asked for it, or the stored band is
    /// one no preset can express (a routine synced from another device, or a value typed
    /// here earlier). The second half is what stops a 17–22 % band opening as "None".
    private var editingCustom: Bool {
        // A kilogram band is always custom: no chip can express one.
        showsCustomFields || kgBand != nil
            || (band != nil && !Self.bands.contains { matches($0.range) })
    }

    /// Opens the fields on a band already in range, so tapping Custom never appears to
    /// change the load — only who chooses it.
    private func beginCustom() {
        showsCustomFields = true
        guard !hasTarget else {
            unit = kgBand != nil ? .kilograms : .percent
            return
        }
        // **Kilograms when a percentage could not work.** Offering "20 % of your max" to
        // someone who has never measured this grip is offering a number that resolves to
        // nothing at run time — the exact hole Nuri hit (2026-08-09).
        unit = hasResolvableMax ? .percent : .kilograms
        if unit == .kilograms { applyKg(Self.defaultKgBand) } else { apply(0.20...0.30) }
    }

    private var unitBinding: Binding<Unit> {
        Binding { unit } set: { new in
            guard new != unit else { return }
            unit = new
            switch new {
            // Seed from what is on screen where that is possible, so switching units
            // reads as a conversion rather than a reset.
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

    /// The kilogram scale's top. Wide enough for strong pullers without making a
    /// 10–15 kg band a sliver: it grows with the band it has to show and with the
    /// strongest max on file for this grip.
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

    /// The two cannot coexist: `PlanMath.targetBand` ranks kilograms first, so leaving a
    /// percentage behind would leave the row showing one number and the session running
    /// another.
    private func clearPercent() {
        set.targetLoPercent = nil
        set.targetHiPercent = nil
    }

    private func percentInt(_ fraction: Double) -> Int { Int((fraction * 100).rounded()) }

    private func apply(_ range: ClosedRange<Double>) {
        set.targetLoPercent = range.lowerBound
        set.targetHiPercent = range.upperBound
        // A percentage and an explicit kilogram band on the same set would leave the kg
        // winning silently — `PlanMath.targetBand` ranks it first — so picking a
        // percentage clears any kilograms the list editor may have put there.
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

    private func kgText(_ kg: Double) -> String {
        kg.formatted(.number.precision(.fractionLength(1)))
    }
}
