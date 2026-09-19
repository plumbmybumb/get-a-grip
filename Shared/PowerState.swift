// SPDX-License-Identifier: MPL-2.0
// Original contributions Copyright 2026 Nuri Bruner.

import Foundation
import Observation

/// Low Power Mode, observable. `ProcessInfo` has the answer on the phone and the watch
/// alike, but it is a plain property with a notification beside it, so this is the one
/// place that turns the pair into something a view can depend on. What depends on it:
/// the clock numerals stop rolling (`NumeralRoll`), because a device rationing its
/// battery has said it wants fewer frames, not a digit animation forty times a second.
///
/// A singleton rather than an injected store: it is a fact about the device, not about
/// the app, and every target — phone, watch, widget — reads the same one.
@Observable @MainActor
final class PowerState {
    static let shared = PowerState()

    private(set) var isLowPowerModeEnabled: Bool

    @ObservationIgnored private var observer: (any NSObjectProtocol)?

    private init() {
        #if DEBUG
        // Headless verification: the simulator has no battery, so no Low Power Mode to
        // switch on. `-previewLowPower` stands in for it. Never in a release build.
        if ProcessInfo.processInfo.arguments.contains("-previewLowPower") {
            isLowPowerModeEnabled = true
            return
        }
        #endif
        isLowPowerModeEnabled = ProcessInfo.processInfo.isLowPowerModeEnabled
        observer = NotificationCenter.default.addObserver(
            forName: .NSProcessInfoPowerStateDidChange, object: nil, queue: .main
        ) { _ in
            // The notification lands on the main queue, but not statically on the main
            // actor; hop rather than assume.
            Task { @MainActor in
                PowerState.shared.isLowPowerModeEnabled = ProcessInfo.processInfo.isLowPowerModeEnabled
            }
        }
    }
}
