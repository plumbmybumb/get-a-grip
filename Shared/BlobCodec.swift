// SPDX-License-Identifier: MPL-2.0
// Original contributions Copyright 2026 Nuri Bruner.

import Foundation

/// The one JSON door. Every blob column in the SwiftData store and the debounced draft
/// rescue go through here, so there is exactly one encoder configuration to reason
/// about when two devices disagree.
///
/// DISCIPLINE (binding): there is no parallel DTO layer. The engine value types ARE the
/// wire format, with frozen `CodingKeys` and lenient decoders. That is safe only
/// because of one invariant — the only blobs ever DECODED-THEN-RE-ENCODED are
/// `SessionTemplate`'s, and their types contain no lossy conversion at all (`FingerSet`
/// and `GripPosition` are total). Every lossy fallback lives in write-once
/// `WorkoutLog` snapshots. New fields are added with a default and read via
/// `c.value(.key, or:)`; fields are never removed or renamed; enum raws are Strings;
/// the store writes a routine's blob ONLY on a real user edit — no launch migration, no
/// normalize-on-sync, and `undoDelete` restores the raw `Data`.
enum BlobCodec {
    /// `.sortedKeys` makes the bytes a pure function of the content. Without it an
    /// unchanged routine can re-encode differently, SwiftData marks the column dirty,
    /// and CloudKit syncs a no-op on every save — which on two devices reads as an
    /// endless phantom edit nobody made.
    static let encoder: JSONEncoder = {
        let e = JSONEncoder()
        e.outputFormatting = [.sortedKeys]
        e.dateEncodingStrategy = .iso8601
        return e
    }()

    static let decoder: JSONDecoder = {
        let d = JSONDecoder()
        d.dateDecodingStrategy = .iso8601
        return d
    }()

    /// nil on failure rather than a throw: every caller is a property setter on a
    /// `@Model`, and the correct response to "this did not encode" is to leave the good
    /// blob already on disk exactly where it is.
    static func encode<T: Encodable>(_ value: T) -> Data? {
        try? encoder.encode(value)
    }

    /// Empty `Data` → nil, not a decode error: a freshly inserted record has empty blob
    /// columns by definition, and that is not a failure worth surfacing.
    static func decode<T: Decodable>(_ type: T.Type, from data: Data) -> T? {
        guard !data.isEmpty else { return nil }
        return try? decoder.decode(T.self, from: data)
    }

    /// Element-wise: one structurally broken element is dropped instead of taking the
    /// whole routine with it. Decoding `[T].self` in one pass would throw on the first
    /// bad set and lose the five good ones after it — the difference between a routine
    /// missing a row and a routine that vanished.
    static func decodeArray<T: Decodable>(_ type: T.Type, from data: Data) -> [T] {
        guard !data.isEmpty else { return [] }
        guard let elements = try? decoder.decode([Lenient<T>].self, from: data) else { return [] }
        return elements.compactMap(\.value)
    }

    /// Swallows the element's own error so the ARRAY decode never throws. The container
    /// then advances normally to the next element, which is the whole trick — an error
    /// thrown out of an unkeyed element decode leaves the walk stuck on it.
    private struct Lenient<T: Decodable>: Decodable {
        let value: T?

        init(from decoder: Decoder) throws {
            value = try? T(from: decoder)
        }
    }
}
