// SPDX-License-Identifier: MPL-2.0
// Original contributions Copyright 2026 Nuri Bruner.

import SwiftUI
import UIKit

/// Body weight as a number you TYPE, never a slider (Nuri, 2026-09-25): a slider is a
/// poor way to state an exact personal number, and a track whose far end reads as a
/// verdict on your body is worse. Empty until entered, with no invented default; in the
/// user's weight unit; the decimal keypad.
///
/// **The way out lives IN THE ROW** (Thomas, 2026-10-31: "I can't get the keyboard out so
/// I can't Save or exit the settings screen"). The decimal pad has no Return key, and the
/// only exit used to be a CONDITIONAL keyboard toolbar (`if focused { … }`): host
/// machinery the field cannot guarantee. The simulator drew it in both hosts (Settings and
/// the critical force test, UITests/BodyWeightKeyboardUITests), so the failure was never
/// reproduced, only reported — and a keyboard whose one way out belongs to someone else
/// is the bug either way. A Done beside the unit, shown while editing, is `ValueField`'s
/// own shape and depends on no host; it replaces the toolbar rather than doubling it.
/// Hosts also dismiss on scroll.
struct BodyWeightField: View {
    /// Kilograms. nil = not entered.
    @Binding var kilograms: Double?
    var title: String = String(localized: "Body weight")
    /// A card title in Settings, a row label inside a form.
    var titleFont: Font = .system(.subheadline, weight: .medium)

    @Environment(\.weightUnit) private var weightUnit
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @Environment(\.revealAboveKeyboard) private var revealAboveKeyboard
    @Environment(\.dynamicTypeSize) private var typeSize
    @FocusState private var focused: Bool
    @State private var text = ""

    /// How much of the page below the row stays clear of the keyboard while typing: the
    /// rest of a Settings card (its caption and padding) plus a 16 pt gap. Measured on a 13
    /// mini, 2026-10-31: 90 pt at large, 135 at XXXL, 338 at the largest accessibility
    /// size. Scaled with BODY, not caption, because that caption also WRAPS as it grows.
    @ScaledMetric(relativeTo: .body) private var roomBelow: CGFloat = 110
    /// The room actually kept, decided once per keyboard appearance — see `revealCard`.
    @State private var revealRoom: CGFloat = 0
    /// The row's frame, written on every scroll frame while it moves, so it lives in a box
    /// that observation never sees (the `AxisMemory` trick): a `@State` rect would rebuild
    /// the row on every frame of a scroll.
    @State private var frameMemory = FrameMemory()

    private final class FrameMemory {
        var rect: CGRect = .zero
    }

    /// Stored kilograms are clamped to what a person can weigh; a typo is not a weight.
    private static let limit: ClosedRange<Double> = 25...250

    var body: some View {
        Group {
            // At accessibility sizes the title takes its own line: beside the field, unit
            // and Done there is no width for it, and on a 13 mini it broke to "Bod" and "Do"
            // (2026-10-31). Gated on the SIZE, never on a measured fit: Done appears with
            // focus, and a layout that switched on it would rebuild the field and drop the
            // keyboard it had just raised.
            if typeSize.isAccessibilitySize {
                VStack(alignment: .leading, spacing: 4) {
                    titleText
                    HStack(alignment: .firstTextBaseline, spacing: 8) {
                        Spacer(minLength: 0)
                        entry
                    }
                }
            } else {
                HStack(alignment: .firstTextBaseline, spacing: 8) {
                    titleText
                        // The title before the field's slack: with Done showing, "Body
                        // weight" wrapped to "Body" on a 13 mini at large while the field
                        // kept its 110 pt.
                        .layoutPriority(1)
                    Spacer(minLength: 12)
                    entry
                }
            }
        }
        .animation(Motion.state(reduceMotion), value: focused)
        .frame(minHeight: 44)
        .contentShape(.rect)
        // The mark the screen scrolls to (`KeyboardReveal`): the row plus the room below
        // it, hanging past the row from a background so no layout moves. An explicit
        // height: a negative-padded mark measured as the row alone.
        .background(alignment: .top) {
            if focused && revealAboveKeyboard != nil {
                Color.clear
                    .frame(height: frameMemory.rect.height + revealRoom)
                    .id(KeyboardReveal.id)
                    .accessibilityHidden(true)
            }
        }
        .onGeometryChange(for: CGRect.self) { $0.frame(in: .global) } action: { frameMemory.rect = $0 }
        .onReceive(NotificationCenter.default.publisher(for: UIResponder.keyboardDidShowNotification),
                   perform: revealCard)
        .onTapGesture { focused = true }
        .onAppear { text = display(kilograms) }
        .onChange(of: kilograms) { _, kg in if !focused { text = display(kg) } }
        .onChange(of: weightUnit) { _, _ in text = display(kilograms) }
        .onChange(of: focused) { _, isFocused in if !isFocused { commit() } }
        .onSubmit(commit)
    }

    private var titleText: some View {
        Text(title)
            .font(titleFont)
            .foregroundStyle(Ink.primary)
    }

    @ViewBuilder private var entry: some View {
        TextField("—", text: $text)
            .keyboardType(.decimalPad)
            .multilineTextAlignment(.trailing)
            .font(.system(.title3, weight: .semibold))
            .monospacedDigit()
            .focused($focused)
            .frame(minWidth: 60, maxWidth: 110)
            .accessibilityLabel(title)
            .accessibilityIdentifier("bodyWeight.field")
        Text(weightUnit.symbol)
            .font(.system(.subheadline))
            .foregroundStyle(Ink.tertiary)
        if focused {
            // Frame and content shape INSIDE the label — the house order (see
            // `ValueField`): outside the Button they are layout only.
            Button { focused = false } label: {
                Text("Done")
                    .font(.system(.subheadline, weight: .semibold))
                    // One word on one line: squeezed, it set "Do / ne" at XXXL.
                    .fixedSize()
                    .padding(.horizontal, 12)
                    .frame(minWidth: 44, minHeight: 44)
                    .contentShape(.rect)
            }
            .buttonStyle(PressFeedbackButtonStyle())
            .accessibilityIdentifier("bodyWeight.done")
            .transition(.opacity)
        }
    }

    /// Once the keyboard is up, ask the screen to lift the row until `roomBelow` clears the
    /// keyboard — never so far that the row itself passes under the top bar (110 pt kept for
    /// the status and inline navigation bars), and not at all if the room is already clear.
    private func revealCard(_ note: Notification) {
        guard focused, let reveal = revealAboveKeyboard,
              let keyboard = note.userInfo?[UIResponder.keyboardFrameEndUserInfoKey] as? CGRect
        else { return }
        let row = frameMemory.rect
        let room = min(roomBelow, max(0, keyboard.minY - row.height - 110))
        guard row.maxY + room > keyboard.minY else { return }
        revealRoom = room
        // A turn later, so the mark has been laid out at its new height when it is scrolled to.
        Task { @MainActor in reveal() }
    }

    private func display(_ kg: Double?) -> String {
        kg.map { weightUnit.fromKg($0).formatted(.number.precision(.fractionLength(0...1))) } ?? ""
    }

    /// An empty or unreadable entry keeps what was there, rather than wiping a stored weight.
    private func commit() {
        let normalized = text.replacingOccurrences(of: ",", with: ".")
        guard let typed = Double(normalized), typed.isFinite, typed > 0 else {
            text = display(kilograms)
            return
        }
        let kg = min(max(weightUnit.toKg(typed), Self.limit.lowerBound), Self.limit.upperBound)
        kilograms = kg
        text = display(kg)
    }
}
