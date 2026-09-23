// SPDX-License-Identifier: MPL-2.0
// Original contributions Copyright 2026 Nuri Bruner.

import SwiftUI

/// A focused callback needs stable identity: publishing a fresh raw closure on
/// every render makes its observing form render again, feeding back into the field.
/// Equality follows the field instance while its action reads live State handles.
struct ValueFieldCommitAction: Equatable {
    private let id: UUID
    private let action: () -> Void

    init(id: UUID, action: @escaping () -> Void) {
        self.id = id
        self.action = action
    }

    func commit() { action() }

    static func == (lhs: Self, rhs: Self) -> Bool { lhs.id == rhs.id }
}

private struct ValueFieldCommitKey: FocusedValueKey {
    typealias Value = ValueFieldCommitAction
}

extension FocusedValues {
    /// A form's Save/Apply can finish the currently focused numeric edit before
    /// reading its draft. The field still owns typing state, and the ordinary
    /// binding changes only at commit rather than on every keystroke.
    var commitValueField: ValueFieldCommitAction? {
        get { self[ValueFieldCommitKey.self] }
        set { self[ValueFieldCommitKey.self] = newValue }
    }
}

/// A number you can drag, tap or type — the app's control for every quantity.
///
/// Replaces chip grids: chips suit a handful of CATEGORICAL choices, not numbers — a
/// menu of nine edge sizes still cannot express 22 mm, and ten stacked in a set row is a
/// wall of buttons.
///
/// Three ways in, by frequency: **drag** for the coarse move; **tap a preset** for the
/// values used most (four at most; more is a menu again); **tap the number** to type an
/// exact one.
struct ValueRow: View {
    let title: String
    var unit: String = ""
    @Binding var value: Double
    /// The range the SLIDER spans — the values you reach for, not what the column stores.
    /// The storage clamp (rest tolerates 600 s) would park a 20 s rest at 3 % of the track
    /// and make every drag a wild jump.
    var range: ClosedRange<Double>
    /// The hard clamp a TYPED value is held to, when the storage range is wider than
    /// anything worth dragging to. Defaults to the slider's range.
    var limit: ClosedRange<Double>?
    var step: Double = 1
    var presets: [Double] = []
    var decimals: Int = 0
    /// Shown under the row when the value deserves a consequence ("= 1:00 under
    /// tension per side", "Below this, the clock stops").
    var caption: String?
    /// WHICH control the row carries (Nuri, 2026-08-04: "not loving how everything is a
    /// button").
    ///
    /// - `.slider` for a continuous quantity where "about right" is the usual intent.
    /// - `.stepper` for a small integer you want EXACTLY: pulls per side. Four chips never
    ///   included 10.
    /// - `.none` when the presets genuinely are the vocabulary.
    ///
    /// Tap-to-type works in all three — the escape hatch, never the only door.
    var control: ValueControl = .slider

    /// Whether the number has become a field. Two changes per edit, so it lives here; the
    /// DRAFT STRING changes per keypress and does not (see `ValueField`).
    @State private var isTyping = false
    @FocusState private var fieldFocused: Bool
    @Environment(\.dynamicTypeSize) private var typeSize

    /// Only when there is a slider to fall back on — see the preset row's comment.
    private var hidesPresets: Bool { control == .slider && typeSize >= .accessibility2 }

    /// Whether a full-width track draws UNDER the title row. Decides the row's spacing: the
    /// gap keeps a draggable strip clear of the numbers; a stepper sits IN the row.
    private var hasTrack: Bool {
        switch control {
        case .slider, .dial: true
        case .stepper, .none: false
        }
    }

    /// The dial's detents — the LADDER, and nothing but the ladder.
    ///
    /// Splicing a typed value in as an extra stop made the control feel broken (Nuri,
    /// 2026-08-11): detents are positioned by INDEX, so a ninth entry re-spaced the other
    /// eight under your finger, and the first drag re-spaced them again. A ruler whose
    /// marks move is not a ruler. `DialTrack` marks an off-ladder value in place instead.
    private func dialValues(_ ladder: [Double]) -> [Double] {
        ladder.filter { (limit ?? range).contains($0) }
    }

    /// Clamps only what the SLIDER sees: a typed 90 s hold stays 90 s in the model and on
    /// the face; the thumb parks at the end rather than crashing SwiftUI out of range.
    private var sliderBinding: Binding<Double> {
        Binding(get: { min(range.upperBound, max(range.lowerBound, value)) },
                set: { if value != $0 { value = $0 } })
    }

    private var text: String {
        value.formatted(.number.precision(.fractionLength(decimals)))
    }

    var body: some View {
        // Tighter without a track — see `hasTrack`.
        VStack(alignment: .leading, spacing: hasTrack ? 8 : 4) {
            HStack(alignment: .firstTextBaseline) {
                Text(title)
                    .font(.system(.subheadline, weight: .medium))
                    .foregroundStyle(Ink.primary)
                Spacer(minLength: 8)
                if control == .stepper && !isTyping {
                    stepButton("minus", by: -step)
                }
                if isTyping { field } else { tappableValue }
                if control == .stepper && !isTyping {
                    stepButton("plus", by: step)
                }
            }

            switch control {
            case .slider:
                Slider(value: sliderBinding, in: range, step: step)
                    .tint(Accent.graphite)
                    .accessibilityLabel(title)
                    .accessibilityValue(String(localized: "\(text) \(unit)"))
            case .dial(let ladder):
                DialTrack(value: $value,
                          values: dialValues(ladder),
                          format: { $0.formatted(.number.precision(.fractionLength(decimals))) },
                          spokenUnit: unit)
                    .accessibilityLabel(title)
            case .stepper, .none:
                EmptyView()
            }

            if !presets.isEmpty { presetRow }

            if let caption {
                Text(caption)
                    .font(.system(.footnote))
                    .foregroundStyle(Ink.tertiary)
            }
        }
        .padding(.vertical, hasTrack ? 4 : 0)
    }

    /// One step, clamped to the TYPED limit rather than the slider's range: a stepper is
    /// what you reach for to go past what the slider spans.
    ///
    /// IT REPEATS WHILE HELD, per the HIG; without it 80 % to 90 % was a dozen taps (Nuri,
    /// 2026-08-10).
    private func stepButton(_ symbol: String, by delta: Double) -> some View {
        RepeatingStep(symbol: symbol, enabled: canStep(by: delta)) {
            step(by: delta)
        }
        .accessibilityLabel(symbol == "plus" ? String(localized: "Increase \(title)") : String(localized: "Decrease \(title)"))
    }

    private func canStep(by delta: Double) -> Bool {
        let bounds = limit ?? range
        let next = roundedToPrecision(value + delta)
        return next >= bounds.lowerBound && next <= bounds.upperBound
    }

    /// Whether the value actually MOVED, so a held button stops dead at the bound.
    @discardableResult
    private func step(by delta: Double) -> Bool {
        let bounds = limit ?? range
        let next = min(bounds.upperBound,
                       max(bounds.lowerBound, roundedToPrecision(value + delta)))
        guard next != value else { return false }
        value = next
        return true
    }

    /// The value doubles as the button that lets you type it — a value first and a control
    /// second, since most of the time you are reading it.
    private var tappableValue: some View {
        Button {
            isTyping = true
            fieldFocused = true
        } label: {
            HStack(alignment: .firstTextBaseline, spacing: 3) {
                Text(text)
                    .font(.system(.title3, weight: .semibold))
                    .monospacedDigit()
                    .contentTransition(.numericText())
                if !unit.isEmpty {
                    Text(unit).font(.system(.subheadline)).foregroundStyle(Ink.tertiary)
                }
            }
            .foregroundStyle(Ink.primary)
            .padding(.horizontal, 10)
            // 44 both ways: a short unitless value can be one digit plus padding, well
            // under the floor, between two 44×44 steppers.
            .frame(minWidth: 44, minHeight: 44)
            .contentShape(.rect(cornerRadius: 10))
        }
        .buttonStyle(PressFeedbackButtonStyle())
        .accessibilityLabel(String(localized: "\(title), \(text) \(unit). Double tap to type a value."))
    }

    private var field: some View {
        ValueField(title: title,
                   placeholder: text,
                   unit: unit,
                   decimals: decimals,
                   focus: $fieldFocused) { typed in
            // `nil` means the field was left as found; changing your mind must not zero it.
            if let typed {
                let bounds = limit ?? range
                let next = min(bounds.upperBound, max(bounds.lowerBound, typed))
                if next != value { value = next }
            }
            isTyping = false
            fieldFocused = false
        }
    }

    /// Rounds to what the row can DISPLAY (whole numbers, or one decimal for kilograms),
    /// never to the slider's step.
    private func roundedToPrecision(_ raw: Double) -> Double {
        ValueField.rounded(raw, decimals: decimals)
    }

    private var presetRow: some View {
        AdaptiveActionRow(spacing: 8) {
            ForEach(presets, id: \.self) { preset in
                Button {
                    value = preset
                } label: {
                    Text(preset.formatted(.number.precision(.fractionLength(decimals))))
                        .font(.system(.subheadline, weight: .medium))
                        .monospacedDigit()
                        // 44, THE HIT-TARGET FLOOR, like `Chip`: at 40 a miss reads as "the tap
                        // didn't register".
                        .actionLabelLayout(minHeight: 44, fullWidth: true, fillsRowHeight: true)
                        .foregroundStyle(abs(value - preset) < 0.001 ? Ink.primary : Ink.secondary)
                        .background {
                            if abs(value - preset) < 0.001 {
                                Capsule().fill(Accent.graphiteFlat.opacity(0.20))
                            } else {
                                Capsule().stroke(Ink.tertiary.opacity(0.35), lineWidth: 1)
                            }
                        }
                        .contentShape(.capsule)
                }
                .buttonStyle(PressFeedbackButtonStyle())
                .accessibilityLabel(String(localized: "\(preset.formatted()) \(unit)"))
            }
        }
        // Presets are a shortcut: at accessibility sizes the slider and typed field
        // still work, so dropping them buys back a row of height.
        //
        // ONLY with a slider to fall back on. Where the presets ARE the control,
        // hiding them would leave tap-to-type — the path needing the most dexterity
        // and prior knowledge — as the only way to change a value.
        .opacity(hidesPresets ? 0 : 1)
        .frame(height: hidesPresets ? 0 : nil)
        .clipped()
        .accessibilityHidden(hidesPresets)
    }
}

/// The typed number, and NOTHING else — the one thing on the row that changes per
/// keypress.
///
/// **The draft string lives here, not on `ValueRow`: high-frequency state belongs in a
/// leaf.** On the row, every character re-ran the dial and its detents, scale labels,
/// presets and caption — the same fix `LiveForceReadout` got for the 80 Hz reading,
/// where the "sensor" is a thumb.
///
/// The field's promises: it opens EMPTY with the current value as placeholder
/// (pre-filling put the caret after the digits, so typing 22 over 15 gave "1522"); a
/// comma reads as a point; leaving commits rather than discarding; an untouched field
/// reports `nil`, so changing your mind cannot zero it. Clamping stays with the CALLER,
/// the only place that knows `limit` versus `range`.
struct ValueField: View {
    let title: String
    /// The current value, formatted — shown as the placeholder over the empty field.
    let placeholder: String
    let unit: String
    let decimals: Int
    /// Owned by the row, whose tap opens the field and whose commit closes it.
    var focus: FocusState<Bool>.Binding
    /// The parsed value, rounded to what the row can display — `nil` when nothing was
    /// typed. The caller clamps and closes the field.
    var onCommit: (Double?) -> Void

    @State private var draft = ""
    @State private var committed = false
    @State private var commitIdentity = UUID()

    var body: some View {
        HStack(spacing: 6) {
            TextField(placeholder, text: $draft)
                .font(.system(.title3, weight: .semibold))
                .monospacedDigit()
                .multilineTextAlignment(.trailing)
                .keyboardType(decimals > 0 ? .decimalPad : .numberPad)
                .focused(focus)
                .focusedValue(\.commitValueField,
                              ValueFieldCommitAction(id: commitIdentity, action: commit))
                .frame(minWidth: 64)
                .onSubmit(commit)
            if !unit.isEmpty {
                Text(unit).font(.system(.subheadline)).foregroundStyle(Ink.tertiary)
            }
            // Frame and content shape INSIDE the label — the house order. Outside the
            // Button they are layout only: its hit region is its styled label, so the
            // 44 pt floor was declared without being delivered.
            Button(action: commit) {
                Text("Done")
                    .font(.system(.subheadline, weight: .semibold))
                    .padding(.horizontal, 12)
                    .frame(minWidth: 44, minHeight: 44)
                    .contentShape(.rect)
            }
            .buttonStyle(PressFeedbackButtonStyle())
        }
        .onChange(of: focus.wrappedValue) { _, focused in
            // Tapping elsewhere commits: a typed number that silently vanishes is worse
            // than one clamped into range.
            if !focused { commit() }
        }
    }

    private func commit() {
        // Done also removes focus. Deliver this edit once, even if both callbacks fire.
        guard !committed else { return }
        committed = true
        onCommit(Self.parse(draft, decimals: decimals))
    }

    /// Accepts a comma as well as a point ("2,5" in French, "2.5" in English).
    ///
    /// NOT snapped to the row's `step`: the dial lands on round numbers, typing is the
    /// escape hatch for everything else, and a field that turns 7 into 5 is not one. Only
    /// display precision is enforced.
    static func parse(_ raw: String, decimals: Int) -> Double? {
        let cleaned = raw.trimmingCharacters(in: .whitespaces)
            .replacingOccurrences(of: ",", with: ".")
        guard let typed = Double(cleaned), typed.isFinite else { return nil }
        let result = rounded(typed, decimals: decimals)
        return result.isFinite ? result : nil
    }

    /// Rounds to what the row can DISPLAY (whole numbers, or one decimal for kilograms),
    /// never to the slider's step.
    static func rounded(_ raw: Double, decimals: Int) -> Double {
        let scale = pow(10.0, Double(decimals))
        return (raw * scale).rounded() / scale
    }
}

/// Which control a `ValueRow` carries. See `ValueRow.control`.
enum ValueControl: Hashable {
    case slider
    case stepper
    /// A `DialTrack` over the given ladder: evenly-spaced detents, one per value. Right for
    /// a quantity that is EXACT and drawn from a handful of real numbers — nearly all of them.
    case dial([Double])
    case none
}

/// A stepper button that repeats while held, and accelerates.
///
/// A raw drag, not a `Button`, whose action fires on touch-UP: a held press would repeat
/// twenty times and add one more on release. SIMULTANEOUS, so a scroll starting on the
/// glyph still scrolls, and real movement cancels the hold (as with `HorizontalPan`).
private struct RepeatingStep: View {
    var symbol: String
    var enabled: Bool
    /// Returns false when the value could not move, which ends the repeat at the bound.
    var step: @MainActor () -> Bool

    @State private var task: Task<Void, Never>?
    @State private var pressState = RepeatingStepPressState()
    @GestureState private var touchActive = false
    @Environment(\.accessibilityReduceMotion) private var reduceMotion

    var body: some View {
        Image(systemName: symbol)
            .font(.system(.subheadline, weight: .semibold))
            .foregroundStyle(enabled ? Accent.graphite : Ink.tertiary.opacity(0.5))
            // Match the shared button: immediate press, critically damped release.
            .scaleEffect(pressState.isPressed && !reduceMotion ? 0.94 : 1)
            .animation(pressState.isPressed ? nil : Motion.state(reduceMotion),
                       value: pressState.isPressed)
            // 44pt around a much smaller glyph — the house rule.
            .frame(width: 44, height: 44)
            .contentShape(.circle)
            .simultaneousGesture(press)
            .onDisappear { cancel() }
            // GestureState resets on interruption as well as normal release; onEnded
            // alone misses a scroll recognizer or system gesture cancelling the touch.
            .onChange(of: touchActive) { _, active in
                if !active {
                    cancel()
                    pressState = RepeatingStepPressState()
                }
            }
            // A raw `DragGesture` gives VoiceOver nothing to activate. The accelerating
            // hold stays sighted-only, but a double tap performs one step — the
            // `HoldToEndButton` precedent for a gesture-only control.
            .accessibilityAddTraits(.isButton)
            .accessibilityAction {
                guard enabled else { return }
                _ = step()
            }
    }

    private var press: some Gesture {
        DragGesture(minimumDistance: 0)
            .updating($touchActive) { _, active, _ in active = true }
            .onChanged { drag in
                guard pressState.update(translation: drag.translation, enabled: enabled) else {
                    if !pressState.isPressed { cancel() }
                    return
                }
                task = Task { @MainActor in
                    // The dwell before repeating; shorter and a single tap runs away from you.
                    try? await Task.sleep(for: .milliseconds(450))
                    var delay = 90
                    while !Task.isCancelled {
                        pressState.didRepeat = true
                        guard step() else { break }
                        try? await Task.sleep(for: .milliseconds(delay))
                        delay = max(35, delay - 5)
                    }
                }
            }
            .onEnded { drag in
                let shouldStep = pressState.finish(translation: drag.translation, enabled: enabled)
                cancel()
                // A tap is a press that never reached the dwell — and never wandered.
                if shouldStep { _ = step() }
            }
    }

    private func cancel() {
        task?.cancel()
        task = nil
        pressState.isPressed = false
    }
}

/// Cancellation lasts for the entire touch. Moving off a stepper and back must not
/// restart its repeat task or turn the end of a page scroll into a value edit.
struct RepeatingStepPressState {
    var isPressed = false
    var didRepeat = false
    private var cancelled = false
    private static let slop: CGFloat = 10

    mutating func update(translation: CGSize, enabled: Bool) -> Bool {
        if Self.hasMoved(translation) || !enabled {
            cancelled = true
            isPressed = false
            return false
        }
        guard !cancelled, !isPressed else { return false }
        isPressed = true
        return true
    }

    mutating func finish(translation: CGSize, enabled: Bool) -> Bool {
        let commits = isPressed && !cancelled && !didRepeat && enabled && !Self.hasMoved(translation)
        self = Self()
        return commits
    }

    private static func hasMoved(_ translation: CGSize) -> Bool {
        abs(translation.width) >= slop || abs(translation.height) >= slop
    }
}

/// Integer flavour, so callers binding an `Int` don't each write the same conversion.
struct IntValueRow: View {
    let title: String
    var unit: String = ""
    @Binding var value: Int
    var range: ClosedRange<Int>
    var limit: ClosedRange<Int>?
    var step: Int = 1
    var presets: [Int] = []
    var caption: String?
    var control: ValueControl = .slider
    /// The stepper's increment, when it differs from the slider's: a stepper moving in the
    /// slider's fives could never reach 12.
    var stepBy: Int?

    var body: some View {
        ValueRow(
            title: title,
            unit: unit,
            value: Binding(get: { Double(value) },
                           set: { value = Int($0.rounded()) }),
            range: Double(range.lowerBound)...Double(range.upperBound),
            limit: limit.map { Double($0.lowerBound)...Double($0.upperBound) },
            step: Double(stepBy ?? step),
            presets: presets.map(Double.init),
            decimals: 0,
            caption: caption,
            control: control
        )
    }
}
