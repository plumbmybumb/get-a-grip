// SPDX-License-Identifier: MPL-2.0
// Original contributions Copyright 2026 Nuri Bruner.

import Foundation

// Decoding leniency, in one place.
//
// Every blob this app stores is read back by a build that may be older or newer than
// the one that wrote it: SwiftData columns hold JSON, CloudKit syncs it between two
// phones, and the user is never told which device won. So decoding is written to be
// TOTAL — a routine that loses one field is still a routine, while a routine that
// throws is a routine the user watches vanish.
//
// Pure Foundation: this file compiles into the widget target too.

extension KeyedDecodingContainer {
    /// `try?`, deliberately: a MISSING key (an old blob written before the field
    /// existed) and a RETYPED key (a newer build that changed Int to Double) both fall
    /// back to `fallback`. A throw here costs the whole routine, not one field.
    func value<T: Decodable>(_ key: Key, or fallback: T) -> T {
        optional(key) ?? fallback
    }

    /// The same tolerance for genuinely optional fields, where absent and unreadable
    /// mean the same thing to every caller.
    func optional<T: Decodable>(_ key: Key) -> T? {
        guard let decoded = try? decodeIfPresent(T.self, forKey: key) else { return nil }
        return decoded
    }
}

extension ClosedRange {
    /// Clamp rather than reject. A decoded value outside its range is a message from
    /// another build, not a corrupt file — the nearest legal value keeps the routine
    /// usable and keeps every downstream `0..<n` loop finite.
    func clamping(_ v: Bound) -> Bound {
        if v < lowerBound { return lowerBound }
        if v > upperBound { return upperBound }
        return v
    }
}
