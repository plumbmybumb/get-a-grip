// SPDX-License-Identifier: MPL-2.0
// Original contributions Copyright 2026 Nuri Bruner.

#if DEBUG && canImport(UIKit) && !os(watchOS)
import UIKit

/// `-dumpInteractions`: writes the UIKit view tree — every `UIView`, its window frame,
/// clipping, and any `UIInteraction`s attached — to `Documents/interactions.txt` a few
/// seconds after launch. Headless diagnostics only: which platform view SwiftUI hands
/// a `UIContextMenuInteraction` to decides what the long-press lift snapshots.
@MainActor
enum DebugInteractionDump {
    static func scheduleIfRequested() {
        guard ProcessInfo.processInfo.arguments.contains("-dumpInteractions") else { return }
        for delay in [3.0, 6.0] {
            DispatchQueue.main.asyncAfter(deadline: .now() + delay) { dump() }
        }
    }

    private static func dump() {
        var lines: [String] = []
        let windows = UIApplication.shared.connectedScenes
            .compactMap { $0 as? UIWindowScene }
            .flatMap(\.windows)
        for window in windows {
            lines.append("WINDOW \(type(of: window)) \(window.frame)")
            walk(window, depth: 1, into: &lines)
        }
        let text = lines.joined(separator: "\n")
        print(text)
        if let docs = FileManager.default.urls(for: .documentDirectory, in: .userDomainMask).first {
            try? text.write(to: docs.appendingPathComponent("interactions.txt"),
                            atomically: true, encoding: .utf8)
        }
    }

    private static func walk(_ view: UIView, depth: Int, into lines: inout [String]) {
        let frame = view.convert(view.bounds, to: nil)
        let interactions = view.interactions.map { String(describing: type(of: $0)) }
        var line = String(repeating: "  ", count: depth)
        line += "\(type(of: view)) frame=\(Int(frame.minX)),\(Int(frame.minY)) \(Int(frame.width))x\(Int(frame.height))"
        if view.clipsToBounds { line += " clips" }
        if view.isHidden { line += " hidden" }
        if view.backgroundColor != nil { line += " bg" }
        if !interactions.isEmpty { line += " INTERACTIONS=\(interactions)" }
        lines.append(line)
        for sub in view.subviews { walk(sub, depth: depth + 1, into: &lines) }
    }
}
#endif
