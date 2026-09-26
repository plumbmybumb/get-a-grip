// SPDX-License-Identifier: MPL-2.0
// Original contributions Copyright 2026 Nuri Bruner.

import SwiftUI

/// The HANDS label row shared by the critical force setup and the routine builder: the
/// caps label, and which hand goes first as a quiet menu on the SAME row rather than a
/// sentence plus a button under the control.
///
/// `menuTitle == nil` hides the menu — both hands pulling together have no first hand —
/// and `hiddenReason` then tells VoiceOver why, since a missing control says nothing.
struct HandsHeader: View {
    /// "Left first", "Right", … nil hides the menu.
    let menuTitle: String?
    @Binding var side: Side
    let leftTitle: String
    let rightTitle: String
    var hiddenReason: String? = nil
    var menuIdentifier: String? = nil
    /// Keep the row at the menu's height when it is hidden, so choosing Both does not jump
    /// the control under the finger that chose it. Off in the critical force setup, which
    /// has always let the row collapse.
    var reservesMenuHeight = false

    var body: some View {
        HStack(alignment: .firstTextBaseline) {
            CapsLabel(String(localized: "HANDS"))
            Spacer(minLength: 8)
            if let menuTitle {
                Menu {
                    Picker("Hand", selection: $side) {
                        Text(leftTitle).tag(Side.left)
                        Text(rightTitle).tag(Side.right)
                    }
                } label: {
                    HStack(spacing: 4) {
                        Text(menuTitle)
                        Image(systemName: "chevron.up.chevron.down").font(.caption2)
                    }
                    .font(.system(.subheadline, weight: .semibold))
                    .foregroundStyle(Accent.graphite)
                    .frame(minHeight: 44)
                    .contentShape(.rect)
                }
                .accessibilityIdentifier(menuIdentifier ?? "hands.side")
            } else if reservesMenuHeight {
                Color.clear.frame(width: 1, height: 44)
                    .accessibilityHidden(true)
            }
        }
        .accessibilityElement(children: menuTitle == nil ? .combine : .contain)
        .accessibilityValue(menuTitle == nil ? (hiddenReason ?? "") : "")
    }
}
