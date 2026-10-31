// SPDX-License-Identifier: MPL-2.0
// Original contributions Copyright 2026 Nuri Bruner.

#if DEBUG
import SwiftUI
import UIKit

/// `-dumpRunnerLayout`: while a runner is on screen, rewrites
/// `Documents/runner-layout.txt` every second with the window's size, its safe-area
/// insets, the interface orientation and the frames the runner reported — the
/// measurements docs/IPHONE_DUO_NIGHTSTAND.md quotes. A rotation the simulator was
/// given from outside (`devicectl device orientation set`) shows up on the next write.
/// Headless diagnostics only; nothing reads it back.
@MainActor
enum RunnerLayoutProbe {
    static let isEnabled = ProcessInfo.processInfo.arguments.contains("-dumpRunnerLayout")
    private static var frames: [String: CGRect] = [:]
    private static var writer: Task<Void, Never>?

    static func record(_ name: String, _ frame: CGRect) {
        guard isEnabled else { return }
        frames[name] = frame
        guard writer == nil else { return }
        writer = Task { @MainActor in
            while !Task.isCancelled {
                write()
                try? await Task.sleep(for: .seconds(1))
            }
        }
    }

    private static func write() {
        guard let scene = UIApplication.shared.connectedScenes
            .compactMap({ $0 as? UIWindowScene }).first,
              let window = scene.windows.first(where: \.isKeyWindow) ?? scene.windows.first
        else { return }
        let insets = window.safeAreaInsets
        var lines = [
            "orientation \(scene.effectiveGeometry.interfaceOrientation.rawValue)"
                + " (1 portrait, 3 landscapeRight, 4 landscapeLeft)",
            "window \(Int(window.bounds.width))x\(Int(window.bounds.height))",
            "safeArea top \(insets.top) leading \(insets.left) bottom \(insets.bottom) trailing \(insets.right)",
            "sizeClass h \(window.traitCollection.horizontalSizeClass.rawValue)"
                + " v \(window.traitCollection.verticalSizeClass.rawValue) (1 compact, 2 regular)",
        ]
        for (name, frame) in frames.sorted(by: { $0.key < $1.key }) {
            lines.append("\(name) x \(Int(frame.minX)) y \(Int(frame.minY)) \(Int(frame.width))x\(Int(frame.height))")
        }
        guard let docs = FileManager.default.urls(for: .documentDirectory, in: .userDomainMask).first
        else { return }
        try? lines.joined(separator: "\n").write(to: docs.appendingPathComponent("runner-layout.txt"),
                                                 atomically: true, encoding: .utf8)
    }
}

extension View {
    /// Reports this view's window frame to `RunnerLayoutProbe` under `name`.
    func runnerLayoutProbe(_ name: String) -> some View {
        onGeometryChange(for: CGRect.self) { $0.frame(in: .global) } action: {
            RunnerLayoutProbe.record(name, $0)
        }
    }
}
#else
import SwiftUI

extension View {
    func runnerLayoutProbe(_ name: String) -> some View { self }
}
#endif
