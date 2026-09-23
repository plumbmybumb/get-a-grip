// SPDX-License-Identifier: MPL-2.0
// Original contributions Copyright 2026 Nuri Bruner.

import SwiftUI

/// The app's forgiveness bar: a glass capsule that undoes the last destructive edit.
///
/// For the deletions with no confirmation dialog. A dialog taxes the 99 % of taps that
/// meant it and gets dismissed blindly; ten seconds of bar costs the confident nothing.
///
/// **The WHOLE capsule undoes**, not the word at its end: a bar-sized target for a thumb,
/// mid-session, without looking. Shaped like `GlassPillButton` — glass INSIDE the label,
/// then `.contentShape`, then the button style.
///
/// Dumb on purpose: the caller owns the timer and the transition, because what is
/// restored lives in the store.
struct UndoBar: View {
    var message: String
    var action: () -> Void

    var body: some View {
        // The caller owns restoration feedback.
        Button(action: action) {
            HStack(spacing: 10) {
                Image(systemName: "arrow.uturn.backward")
                    .font(.system(.footnote, weight: .semibold))
                    .foregroundStyle(Accent.graphite)
                Text(message)
                    .font(.system(.footnote, weight: .medium))
                    .foregroundStyle(Ink.primary)
                    .fixedSize(horizontal: false, vertical: true)
                Spacer(minLength: 8)
                Text("Undo")
                    .font(.system(.footnote, weight: .semibold))
                    .foregroundStyle(Accent.graphite)
            }
            .padding(.horizontal, 16)
            .padding(.vertical, 11)
            // 44, not ~40: the ONLY reversal path for a delete on a 10 s deadline, where
            // a near miss reads as "the tap didn't register".
            .frame(minHeight: 44)
            // `.accessibleGlass`: floating over content, it must go genuinely opaque
            // under Reduce Transparency.
            .accessibleGlass(nil, in: .capsule)
            .contentShape(.capsule)
        }
        .buttonStyle(PressFeedbackButtonStyle())
        .padding(.horizontal, Metrics.hPadding)
        // One element: the message and "Undo" are halves of one statement.
        .accessibilityElement(children: .combine)
        .accessibilityLabel(String(localized: "Undo. \(message)"))
    }
}

#Preview {
    VStack {
        Spacer()
        UndoBar(message: "Routine deleted") {}
        UndoBar(message: "Set removed") {}
    }
    .padding(.bottom, 24)
    .frame(maxWidth: .infinity)
    .background { AppBackground() }
}
