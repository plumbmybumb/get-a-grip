// SPDX-License-Identifier: MPL-2.0
// Original contributions Copyright 2026 Nuri Bruner.

import Foundation

// A routine as a LINK, and the link as a QR code somebody points a camera at.
//
// The code IS the routine — no server, no account. It rides in the URL FRAGMENT, the
// one part of a URL that never reaches a server, even as a universal link.
//
// NOT carried: reminder times and `remindersEnabled` (personal hours, and an import must
// never ambush the recipient with a permission prompt), the template id, and anything
// the store derived. Percent targets travel and resolve against the RECIPIENT's maxes.
//
// Pure Foundation: this file compiles into the widget target too.

/// Everything that can go wrong reading a code somebody else's phone produced. The
/// strings are the words shown in the alert — there is no second copy of them in the UI.
enum RoutineShareError: Error, LocalizedError, Equatable {
    /// Not shaped like a routine link at all: another app's scheme, a web page, or our
    /// own scheme with no payload attached.
    case notARoutineLink
    /// Shaped right, unreadable inside — damaged base64, truncated compression, JSON
    /// that is not an envelope, or a version number this format never wrote.
    case unreadable
    case newerVersion
    case emptyRoutine
    case tooLarge

    var errorDescription: String? {
        switch self {
        case .notARoutineLink:
            return String(localized: "This link isn't a routine from Get a Grip.")
        case .unreadable:
            return String(localized: "This routine code couldn't be read. It may be damaged or incomplete.")
        case .newerVersion:
            return String(localized: "This routine was shared from a newer version of Get a Grip. Update the app to import it.")
        case .emptyRoutine:
            return String(localized: "This code contains a routine with no pulls in it.")
        case .tooLarge:
            return String(localized: "This routine is too large to import.")
        }
    }
}

enum RoutineShare {
    /// Bumped only when the ENVELOPE changes shape; a new `SessionPlan` field does not
    /// need it, since the plan decoder tolerates unknown keys. A bump means "an older
    /// build cannot read this at all".
    static let currentVersion = 1

    // MARK: - Out

    /// The ONE place a routine becomes a URL, so the scheme can later become an https
    /// universal link without touching the app.
    ///
    /// nil when the routine cannot make a WORKING code: the encoder mirrors the
    /// decoder's caps, because a 51-set routine once shared as a normal-looking QR that
    /// every phone then refused. Name and note caps apply here too, so sender and
    /// recipient see the same truncated name.
    static func url(for draft: RoutineDraft) -> URL? {
        var plan = draft.plan
        guard !plan.executable.sets.isEmpty, plan.sets.count <= maxSets else { return nil }
        plan.name = sanitizedName(plan.name)
        plan.sets = plan.sets.map { set in
            var s = set
            s.note = String(s.note.prefix(maxNoteCharacters))
            return s
        }
        let envelope = Envelope(v: currentVersion,
                                plan: plan,
                                sessionsPerDay: draft.sessionsPerDay,
                                isOnDemand: draft.isOnDemand)
        guard let json = try? encoder.encode(envelope) else { return nil }
        guard let compressed = try? (json as NSData).compressed(using: .zlib),
              compressed.count <= maxCompressedBytes else { return nil }
        return URL(string: "\(scheme)://\(host)#\(base64url(compressed as Data))")
    }

    // MARK: - In

    /// Accepts `getagrip://routine#…` and the future `https://<any-host>/…routine…#…`
    /// universal link, so today's build reads codes tomorrow's build hands out.
    ///
    /// Everything past this point is UNTRUSTED camera input: every step is capped or
    /// fails closed, and nothing is trusted to be the size it says it is.
    static func draft(from url: URL) throws -> RoutineDraft {
        guard isRoutineLink(url) else { throw RoutineShareError.notARoutineLink }
        // PERCENT-DECODED: base64url needs no encoding, but a scanner or link shortener
        // may percent-encode unreserved characters, and refusing that fails an intact
        // code. nil (no '#': a bare typed scheme) differs from empty (a bad scan).
        guard let fragment = url.fragment(percentEncoded: false) else {
            throw RoutineShareError.notARoutineLink
        }
        guard !fragment.isEmpty else { throw RoutineShareError.unreadable }
        // Length-bounded BEFORE any string work, or a 40 MB link allocates multiples of
        // itself on the main actor just to be refused. 4/3 is base64's expansion, so this
        // is `maxCompressedBytes` measured in characters.
        guard fragment.count <= maxCompressedBytes * 4 / 3 + 4 else {
            throw RoutineShareError.unreadable
        }

        guard let compressed = data(fromBase64url: fragment),
              compressed.count <= maxCompressedBytes
        else { throw RoutineShareError.unreadable }

        // The compressed cap is the real bound on inflation (see `maxCompressedBytes`);
        // the decompressed check below is a backstop.
        guard let inflated = try? (compressed as NSData).decompressed(using: .zlib) else {
            throw RoutineShareError.unreadable
        }
        let json = inflated as Data
        guard json.count <= maxDecompressedBytes else { throw RoutineShareError.unreadable }

        // Version BEFORE the full decode: a future format may reshape the plan and must
        // read as "update the app", not "damaged", which the strict plan decode would say.
        guard let probe = try? decoder.decode(VersionProbe.self, from: json) else {
            throw RoutineShareError.unreadable
        }
        guard probe.v >= 1 else { throw RoutineShareError.unreadable }
        guard probe.v <= currentVersion else { throw RoutineShareError.newerVersion }

        guard let envelope = try? decoder.decode(Envelope.self, from: json) else {
            throw RoutineShareError.unreadable
        }
        // Counted on the RAW sets, not the executable ones: this cap is about the size of
        // the thing that arrived, not about how much of it would run.
        guard envelope.plan.sets.count <= maxSets else { throw RoutineShareError.tooLarge }

        var plan = envelope.plan
        // NO sets at all is damage: the encoder never builds a code for an empty plan, and
        // "a routine with no pulls" would blame the sharer for a crease in a printout.
        // `.emptyRoutine` is reserved for the distinguishable case below.
        guard !plan.sets.isEmpty else { throw RoutineShareError.unreadable }
        // Trimmed and capped rather than rejected: a long name is not an attack, and
        // `normalized` turns a whitespace-only name into the house default on save.
        plan.name = sanitizedName(plan.name)
        plan.sets = plan.sets.map { set in
            var s = set
            // Fresh row identity, as in `RoutineDraft.copying`: two people's routines
            // must never share a SetPlan id.
            s.id = UUID()
            s.note = String(s.note.prefix(maxNoteCharacters))
            return s
        }
        // Sets arrived intact but every one is zero-rep — the one shape that genuinely
        // IS a routine with no pulls in it, so the error can honestly say so.
        guard !plan.executable.sets.isEmpty else { throw RoutineShareError.emptyRoutine }

        // Start from the defaults, not a decoded draft: reminders must be the RECIPIENT's,
        // and `setSessionsPerDay` (which clamps) is the one door that fills the ladder.
        var draft = RoutineDraft()
        // Stated rather than inherited from the default: nil is what makes the store
        // CREATE this routine instead of updating one of the recipient's.
        draft.templateID = nil
        draft.plan = plan
        draft.setSessionsPerDay(envelope.sessionsPerDay)
        draft.isOnDemand = envelope.isOnDemand
        // OFF, always: somebody else's routine may not notify on your phone until you say
        // so, and turning it on would trigger the permission prompt at import.
        draft.remindersEnabled = false
        return draft
    }

    /// Cheap enough to run on every `onOpenURL`: shape only, no payload work. A link that
    /// passes this and then fails to decode gets an error the user can read; one that
    /// fails here is not ours to answer for at all.
    static func isRoutineLink(_ url: URL) -> Bool {
        guard let incoming = url.scheme?.lowercased() else { return false }
        if incoming == scheme { return url.host()?.lowercased() == host }
        // Any host (the AASA domain is undecided), so the path must say "routine" as a
        // whole COMPONENT — a substring match would claim `/my-routines/7`.
        if incoming == "https" {
            return url.pathComponents.contains { $0.lowercased() == host }
        }
        return false
    }

    // MARK: - The envelope

    /// The version alone, read first — see the ordering note in `draft(from:)`. Lenient
    /// so that a missing `v` reads as 0 and falls below the floor rather than throwing
    /// into the wrong error.
    private struct VersionProbe: Codable {
        var v: Int

        enum CodingKeys: String, CodingKey { case v }

        init(from decoder: Decoder) throws {
            let c = try decoder.container(keyedBy: CodingKeys.self)
            self.v = c.value(.v, or: 0)
        }
    }

    /// The plan rides VERBATIM as `SessionPlan`'s own Codable output: a share DTO would be
    /// a second description of a routine to drift out of step with the first.
    private struct Envelope: Codable {
        var v: Int
        var plan: SessionPlan
        var sessionsPerDay: Int
        var isOnDemand: Bool

        /// FROZEN. Additive only, same rule as every other blob in the app.
        enum CodingKeys: String, CodingKey {
            case v, plan, sessionsPerDay, isOnDemand
        }

        init(v: Int, plan: SessionPlan, sessionsPerDay: Int, isOnDemand: Bool) {
            self.v = v
            self.plan = plan
            self.sessionsPerDay = sessionsPerDay
            self.isOnDemand = isOnDemand
        }

        /// Written out rather than synthesized so an unknown key or a retyped field costs
        /// one value instead of the whole routine — the house rule from `Leniency`.
        init(from decoder: Decoder) throws {
            let c = try decoder.container(keyedBy: CodingKeys.self)
            // Absent reads as 0, below the floor and therefore unreadable: this format
            // never wrote a payload without a version.
            self.v = c.value(.v, or: 0)
            // STRICT, unlike every field around it: a lenient `SessionPlan()` fallback has
            // no sets, so a missing or mangled plan read as "a routine with no pulls in
            // it", blaming the sharer. Throwing reports `.unreadable`, which is true.
            // Leniency still lives inside `SessionPlan.init(from:)` for its fields.
            self.plan = try c.decode(SessionPlan.self, forKey: .plan)
            self.sessionsPerDay = c.value(.sessionsPerDay, or: 2)
            self.isOnDemand = c.value(.isOnDemand, or: false)
        }
    }

    // MARK: - Wire details

    private static let scheme = "getagrip"
    private static let host = "routine"

    /// Caps on untrusted input, enforced at BOTH ends. The compressed cap is the one that
    /// matters: zlib inflates at most ~1030:1 and Foundation cannot bound the output
    /// before it exists, so 4 KB compressed caps the transient allocation at ~4 MB. A
    /// legal 50-set routine is ~1.6 KB.
    private static let maxCompressedBytes = 4 * 1024
    private static let maxDecompressedBytes = 256 * 1024
    private static let maxSets = 50
    private static let maxNameCharacters = 60
    private static let maxNoteCharacters = 500

    /// One trim + cap, applied on encode AND decode, so the sharer's screen and the
    /// recipient's can never disagree about what a too-long name became.
    private static func sanitizedName(_ name: String) -> String {
        String(name.trimmingCharacters(in: .whitespacesAndNewlines)
            .prefix(maxNameCharacters))
    }

    /// `.sortedKeys` gives the round-trip tests one canonical form. Not a cross-device
    /// byte-stability guarantee, and none is needed: no URL is compared or used as a key,
    /// and a printed code keeps working because the DECODER is stable.
    private static let encoder: JSONEncoder = {
        let e = JSONEncoder()
        e.outputFormatting = [.sortedKeys, .withoutEscapingSlashes]
        return e
    }()

    private static let decoder = JSONDecoder()

    /// base64url: standard base64 with the two URL-hostile characters swapped and the
    /// padding dropped. Padding is pure length in a QR code, and every '=' costs modules.
    private static func base64url(_ data: Data) -> String {
        var s = data.base64EncodedString()
            .replacingOccurrences(of: "+", with: "-")
            .replacingOccurrences(of: "/", with: "_")
        while s.hasSuffix("=") { s.removeLast() }
        return s
    }

    /// nil on anything that is not base64url, including a length no base64 string can
    /// have — one leftover character is 6 bits, which never encoded a byte.
    private static func data(fromBase64url string: String) -> Data? {
        var padded = string
            .replacingOccurrences(of: "-", with: "+")
            .replacingOccurrences(of: "_", with: "/")
        let remainder = padded.count % 4
        if remainder == 1 { return nil }
        if remainder > 0 { padded += String(repeating: "=", count: 4 - remainder) }
        return Data(base64Encoded: padded)
    }
}
