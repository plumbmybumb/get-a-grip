// SPDX-License-Identifier: MPL-2.0
// Original contributions Copyright 2026 Nuri Bruner.

import SwiftUI

/// Copies `text` and acknowledges EVERY tap: the label reads "Copied" for two seconds
/// then reverts, with a success haptic each time. The revert is the point — a label stuck
/// on "Copied" gave the second and third copy no acknowledgement.
///
/// The look is the caller's: `label` receives whether the copy just happened, and an
/// outer `.buttonStyle` reaches the button inside. `text` is a closure, so an on-demand
/// report is built on the tap, not per body evaluation.
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
