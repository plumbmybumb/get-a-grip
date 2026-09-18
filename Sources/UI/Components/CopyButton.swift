// SPDX-License-Identifier: MPL-2.0
// Original contributions Copyright 2026 Nuri Bruner.

import SwiftUI

/// Copies `text` to the pasteboard and acknowledges EVERY tap, not just the first: the
/// label reads "Copied" for two seconds and then reverts, and a success haptic fires on
/// each copy. The revert is the point — a diagnostics ring, a support address and an
/// export document are all worth copying twice, and a label stuck on "Copied" from the
/// first tap gave the second and third tap no acknowledgement at all.
///
/// The look is the caller's: `label` receives whether the copy just happened and draws
/// whatever fits its surface (a footnote capsule in Settings, a full-width glass action
/// in the export sheet), and a `.buttonStyle` applied outside reaches the button inside.
/// `text` is a closure so a report assembled on demand is built on the tap, not on every
/// body evaluation.
struct CopyButton<Label: View>: View {
    let text: () -> String
    let accessibilityLabel: LocalizedStringKey
    @ViewBuilder let label: (_ copied: Bool) -> Label

    @State private var copied = false
    @State private var copyTick = 0
    @State private var revert: Task<Void, Never>?

    var body: some View {
        Button {
            UIPasteboard.general.string = text()
            copyTick += 1
            copied = true
            revert?.cancel()
            revert = Task {
                try? await Task.sleep(for: .seconds(2))
                guard !Task.isCancelled else { return }
                copied = false
            }
        } label: {
            label(copied)
        }
        .accessibilityLabel(accessibilityLabel)
        .accessibilityValue(copied ? String(localized: "Copied") : "")
        .sensoryFeedback(.success, trigger: copyTick)
        .onDisappear { revert?.cancel() }
    }
}

/// The compact form Settings uses twice: a footnote "Copy" in a glass capsule.
struct CompactCopyLabel: View {
    let copied: Bool

    var body: some View {
        Text(copied ? "Copied" : "Copy")
            .font(.system(.footnote, weight: .semibold))
            .foregroundStyle(Accent.graphite)
            .actionLabelLayout(minHeight: 44)
            .accessibleGlass(nil, in: .capsule)
            .contentShape(.capsule)
    }
}
