// SPDX-License-Identifier: MPL-2.0
// Original contributions Copyright 2026 Nuri Bruner.

import SwiftUI

/// The app's forgiveness bar: a glass capsule that undoes the last destructive edit.
///
/// Used for the two deletions that have no confirmation dialog — a routine removed from
/// Today, a set swiped out of the builder. A dialog in front of every delete is a tax on
/// the 99 % of taps that meant it, and people learn to dismiss it blindly; a bar that
/// hangs around for ten seconds costs the confident nothing and saves the wrong tap.
///
/// **The WHOLE capsule undoes**, not the word at its right end: this is a bar-sized
/// target reachable with a thumb, mid-session, without looking. Shaped like
/// `GlassPillButton` — glass INSIDE the label, then `.contentShape`, then the button
/// style outside; glass wrapped around a container that holds a Button swallows its
/// touches.
///
/// The bar is dumb on purpose: the caller owns the ten-second timer and the transition,
/// because the thing being restored lives in the store, not here.
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
                    .lineLimit(1)
                    .minimumScaleFactor(0.85)
                Spacer(minLength: 8)
                Text("Undo")
                    .font(.system(.footnote, weight: .semibold))
                    .foregroundStyle(Accent.graphite)
            }
            .padding(.horizontal, 16)
            .padding(.vertical, 11)
            // 44, not ~40. The bar is the ONLY reversal path for two destructive
            // deletes on a 10 s deadline — the exact miss `ValueRow`'s presetRow
            // comment already names ("the kind of miss that reads as 'the tap
            // didn't register'"), here on the control that matters most.
            .frame(minHeight: 44)
            // `.accessibleGlass`, never raw `.glassEffect`: this bar floats over a card
            // or a list, and under Reduce Transparency it has to become genuinely
            // opaque or the words sit on top of the content they cover.
            .accessibleGlass(nil, in: .capsule)
            .contentShape(.capsule)
        }
        .buttonStyle(PressFeedbackButtonStyle())
        .padding(.horizontal, Metrics.hPadding)
        // One element, one sentence: the message and the word "Undo" are halves of the
        // same statement and would otherwise be two stops that each say too little.
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
