// SPDX-License-Identifier: MPL-2.0
// Original contributions Copyright 2026 Nuri Bruner.

import Foundation

@MainActor
protocol BroadcastScanCancellation: AnyObject {
    func cancel()
}

@MainActor
protocol BroadcastScanScheduler: AnyObject {
    var now: TimeInterval { get }
    func schedule(after delay: TimeInterval,
                  action: @escaping @MainActor () -> Void) -> any BroadcastScanCancellation
}

@MainActor
final class MonotonicBroadcastScanScheduler: BroadcastScanScheduler {
    var now: TimeInterval { ProcessInfo.processInfo.systemUptime }

    func schedule(after delay: TimeInterval,
                  action: @escaping @MainActor () -> Void) -> any BroadcastScanCancellation {
        let task = Task { @MainActor in
            do { try await Task.sleep(for: .seconds(delay)) }
            catch { return }
            guard !Task.isCancelled else { return }
            action()
        }
        return Cancellation(task: task)
    }

    private final class Cancellation: BroadcastScanCancellation {
        let task: Task<Void, Never>
        init(task: Task<Void, Never>) { self.task = task }
        func cancel() { task.cancel() }
        deinit { task.cancel() }
    }
}
