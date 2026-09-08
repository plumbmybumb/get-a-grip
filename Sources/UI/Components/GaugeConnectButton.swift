// SPDX-License-Identifier: MPL-2.0
// Original contributions Copyright 2026 Nuri Bruner.

import SwiftUI

/// A broadcast search can wait for the scale to wake, so it must remain cancellable
/// wherever connection is offered. Other connection attempts retain their busy state.
struct GaugeConnectButton: View {
    let connectTitle: String

    @Environment(DeviceStore.self) private var device

    private var title: String {
        if device.canCancelBroadcastSearch { return String(localized: "Cancel") }
        return device.state.isBusy ? device.state.label : connectTitle
    }

    var body: some View {
        PrimaryGlassButton(
            title: title,
            systemImage: device.canCancelBroadcastSearch ? "xmark" : "dot.radiowaves.left.and.right",
            tint: Accent.bleu
        ) {
            if device.canCancelBroadcastSearch {
                device.disconnect()
            } else if !device.state.isBusy, !device.state.isConnected {
                device.connect()
            }
        }
        .disabled(device.state.isBusy && !device.canCancelBroadcastSearch)
        .accessibilityLabel(title)
        .accessibilityValue(device.canCancelBroadcastSearch ? device.state.label : "")
        .accessibilityIdentifier("gauge.connectionAction")
    }
}
