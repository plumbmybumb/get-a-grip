// SPDX-License-Identifier: MPL-2.0
// Original contributions Copyright 2026 Nuri Bruner.

import SwiftUI

/// A number you can drag, tap or type — the app's control for every quantity.
///
/// Replaces the chip grids that used to carry these. Chips are right for a handful of
/// CATEGORICAL choices ("half crimp / open / full crimp"), and wrong for numbers: a
/// menu of nine edge sizes is an arbitrary list that still cannot express 22 mm, and
/// ten of them stacked in a set row is a wall of buttons rather than a form.
///
/// Three ways in, in order of how often they get used:
/// - **Drag** the slider for the coarse move — the fastest way to "about right".
/// - **Tap a preset** for the values you actually use most (kept to four; more is a
///   menu again).
/// - **Tap the number** to type an exact one, which is the escape hatch a slider on a
///   1–100 range genuinely needs.
struct ValueRow: View {
    let title: String
    var unit: String = ""
    @Binding var value: Double
    /// The range the SLIDER spans — the values you actually reach for, not what the
    /// column can store. Handing the slider the storage clamp (rest tolerates 600 s)
    /// puts a 20 s rest at 3 % of the track: the whole useful span squeezed into a few
    /// pixels, and every drag a wild jump.
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
    /// WHICH control the row carries, because not every quantity wants the same one
    /// (Nuri, 2026-08-04: "not loving how everything is a button").
    ///
    /// - `.slider` for a continuous physical quantity where "about right" is the usual
    ///   intent and the exact number rarely matters: edge size, hold, rest.
    /// - `.stepper` for a small integer you want EXACTLY: pulls per side. Chips can only
    ///   ever offer four of them, and 10 was not one of the four.
    /// - `.none` when the presets genuinely are the vocabulary.
    ///
    /// Tap-to-type works in all three — it is the escape hatch, never the only door.
    /// A row of identical chip sets for five different kinds of quantity is what made the
    /// grip card read as a wall of buttons.
    var control: ValueControl = .slider

    /// Whether the number has become a field. Two changes per edit — the tap and the
    /// commit — so it stays here; the DRAFT STRING, which changes on every keypress,
    /// does not (see `ValueField`).
    @State private var isTyping = false
    @FocusState private var fieldFocused: Bool
    @Environment(\.dynamicTypeSize) private var typeSize

    /// Only when there is a slider to fall back on. Where the presets ARE the control,
    /// hiding them would leave tap-to-type as the single way to change a value.
    private var hidesPresets: Bool { control == .slider && typeSize >= .accessibility2 }

    /// Whether a full-width track draws UNDER the title row. It decides the row's own
    /// spacing: the gap exists to keep a draggable strip clear of the numbers above it,
    /// and a stepper sits IN that row rather than under it.
    private var hasTrack: Bool {
        switch control {
        case .slider, .dial: true
        case .stepper, .none: false
        }
    }

    /// The dial's detents — the LADDER, and nothing but the ladder.
    ///
    /// It used to splice the current value in as an extra stop so a typed 22 mm "kept its
    /// own detent". That is what made the control feel broken (Nuri, 2026-08-11: "every
    /// time you type in a manual number it changes the scale of the bar, but then when you
    /// drag the bar it changes again"): detents are positioned by INDEX, so a ninth entry
    /// re-spaced all eight of the others under your finger, and the first drag landed back
    /// on the ladder and re-spaced them a second time. A ruler whose marks move is not a
    /// ruler. `DialTrack` marks an off-ladder value in place instead.
    private func dialValues(_ ladder: [Double]) -> [Double] {
        ladder.filter { (limit ?? range).contains($0) }
    }

    /// Clamps only what the SLIDER sees. A typed 90 s hold stays 90 s in the model and
    /// on the face; the thumb just parks at the end of its track rather than crashing
    /// SwiftUI with an out-of-range value.
    private var sliderBinding: Binding<Double> {
        Binding(get: { min(range.upperBound, max(range.lowerBound, value)) },
                set: { if value != $0 { value = $0 } })
    }

    private var text: String {
        value.formatted(.number.precision(.fractionLength(decimals)))
    }

    var body: some View {
        // Tighter without a track: the gap exists to keep a draggable slider clear of the
        // numbers above it, and a stepper sits IN that row rather than under it.
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

    /// One step, clamped to the TYPED limit rather than the slider's range — the slider
    /// spans what you usually want, and a stepper is what you reach for when you want
    /// the value past it.
    ///
    /// IT REPEATS WHILE HELD, which is the HIG's own guidance for a stepper and the thing
    /// whose absence made 80 % to 90 % a dozen separate taps (Nuri, 2026-08-10: "You can't
    /// hold down +").
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

    /// Returns whether the value actually MOVED, which is what lets a held button stop
    /// dead at the bound instead of spinning against it.
    @discardableResult
    private func step(by delta: Double) -> Bool {
        let bounds = limit ?? range
        let next = min(bounds.upperBound,
                       max(bounds.lowerBound, roundedToPrecision(value + delta)))
        guard next != value else { return false }
        value = next
        return true
    }

    /// The value doubles as the button that lets you type it. It reads as a value
    /// first and a control second, which is the right emphasis — most of the time you
    /// are reading it, not editing it.
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
            // 44 both ways — a short unitless value (e.g. "Pulls per side", 1-20, no
            // `unit:`) is a 1-2 digit glyph plus 20 pt of padding, well under the
            // house floor, sandwiched between two correctly-sized 44×44 steppers.
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
            // `nil` means the field was left as it was found — tapping the number and
            // changing your mind must not zero it.
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
        HStack(spacing: 8) {
            ForEach(presets, id: \.self) { preset in
                Button {
                    value = preset
                } label: {
                    Text(preset.formatted(.number.precision(.fractionLength(decimals))))
                        .font(.system(.subheadline, weight: .medium))
                        .monospacedDigit()
                        .lineLimit(1)
                        .minimumScaleFactor(0.8)
                        .frame(maxWidth: .infinity)
                        // 44, not 40. THE HIT-TARGET FLOOR — `Chip` has always used 44
                        // and these presets sat four points under it, on every slider row
                        // in the app. Four points is exactly the kind of miss that reads
                        // as "the tap didn't register" rather than as a mistake.
                        .frame(height: 44)
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
        // Presets are a shortcut, not the control. At accessibility sizes the slider
        // and the typed field still work, so dropping them costs nothing and buys back
        // a whole row of height on the screen that needs it most.
        //
        // ONLY when there is a slider to fall back on. In compact mode the presets ARE
        // the control — hiding them too would leave tap-to-type as the single way to
        // change a value, which is the one path that needs the most dexterity and the
        // most prior knowledge of what to enter.
        .opacity(hidesPresets ? 0 : 1)
        .frame(height: hidesPresets ? 0 : nil)
        .clipped()
        .accessibilityHidden(hidesPresets)
    }
}

/// The typed number, and NOTHING else — the one thing on the row that changes per
/// keypress.
///
/// **The draft string lives here rather than on `ValueRow` because the house rule is
/// that high-frequency state belongs in a leaf.** With it on the row, every character
/// re-ran the row's whole body: the dial and its eleven detents plus eleven formatted
/// scale labels, the preset capsules and the caption — none of which the text you are
/// typing can touch. This is the same fix `LiveForceReadout` and `LiveTrace` got for
/// the 80 Hz force reading, applied to the one place in the app where the "sensor" is
/// somebody's thumb.
///
/// Everything the field promised before is unchanged and still lives in one place:
/// it opens EMPTY with the current value as its placeholder (pre-filling put the caret
/// after the existing digits, so typing 22 over a 15 gave "1522"); a comma reads as a
/// point; leaving the field commits rather than discarding; and an untouched field
/// reports `nil` so tapping a number and changing your mind cannot zero it. Clamping
/// stays with the CALLER, which is the only place that knows `limit` versus `range`.
struct ValueField: View {
    let title: String
    /// The current value, formatted — shown as the placeholder over the empty field.
    let placeholder: String
    let unit: String
    let decimals: Int
    /// Owned by the row, because the row's tap is what opens the field and its commit is
    /// what closes it. Focus changes twice per edit; the draft changes on every keypress.
    var focus: FocusState<Bool>.Binding
    /// The parsed value, rounded to what the row can display — `nil` when nothing was
    /// typed. The caller clamps and closes the field.
    var onCommit: (Double?) -> Void

    @State private var draft = ""
    @State private var committed = false

    var body: some View {
        HStack(spacing: 6) {
            TextField(placeholder, text: $draft)
                .font(.system(.title3, weight: .semibold))
                .monospacedDigit()
                .multilineTextAlignment(.trailing)
                .keyboardType(decimals > 0 ? .decimalPad : .numberPad)
                .focused(focus)
                .frame(minWidth: 64)
                .onSubmit(commit)
            if !unit.isEmpty {
                Text(unit).font(.system(.subheadline)).foregroundStyle(Ink.tertiary)
            }
            // Frame and content shape INSIDE the label — the house order. Applied
            // outside the Button they are layout only: a Button's hit region is its
            // styled label, and an ancestor `contentShape` does not forward taps to
            // it, so the 44 pt floor was being declared without being delivered.
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
            // Tapping elsewhere commits rather than discarding — a typed number that
            // silently vanishes is worse than one clamped into range.
            if !focused { commit() }
        }
    }

    private func commit() {
        // Done also removes focus. Deliver this edit once, even if both callbacks fire.
        guard !committed else { return }
        committed = true
        onCommit(Self.parse(draft, decimals: decimals))
    }

    /// Accepts a comma as well as a point: the same phone reads "2,5" in French and
    /// "2.5" in English, and a keypad does not care which one you were taught.
    ///
    /// Deliberately NOT snapped to the row's `step`. The dial lands on the ladder so it
    /// is easy to reach a round number by dragging; typing is the escape hatch for
    /// everything else, and a field that silently turns 7 into 5 is not an escape hatch.
    /// Only the display precision is enforced.
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
    /// A `DialTrack` over the given ladder — evenly-spaced detents, one per value it can
    /// produce. The right control for a quantity that is EXACT and drawn from a handful
    /// of real-world numbers, which is nearly every quantity in a routine.
    case dial([Double])
    case none
}

/// A stepper button that repeats while held, and accelerates.
///
/// A raw drag rather than a `Button`, because a Button's action fires on touch-UP: a held
/// press would repeat twenty times and then add one more on release. Attached as a
/// SIMULTANEOUS gesture so a scroll that happens to start on the glyph still scrolls, and
/// any real movement cancels the hold — the same courtesy `HorizontalPan` buys the strips.
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
            // 44pt around a glyph that draws far smaller — the house rule for every
            // bare-glyph control.
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
            // A raw `DragGesture` gives VoiceOver nothing to activate — unlike a real
            // `Button`, this is a bare `Image`. The accelerating hold is still
            // sighted-only (there is no VoiceOver equivalent to "held down"), but a
            // double tap now performs one step, which is the path `HoldToEndButton`
            // sets as precedent for a gesture-only control.
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
                    // The dwell before it takes over. Any shorter and a deliberate single
                    // tap starts running away from you.
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
    /// The stepper's increment, when it differs from the slider's. A hold slider moves in
    /// fives because that is how you think about it; a stepper that also moved in fives
    /// could never reach 12.
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
