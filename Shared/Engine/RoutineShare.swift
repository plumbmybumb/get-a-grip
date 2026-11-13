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
            return String(localized: "Couldn't read this routine code. It may be damaged.")
        case .newerVersion:
            return String(localized: "This routine needs a newer Get a Grip. Update the app to import it.")
        case .emptyRoutine:
            return String(localized: "This routine has no pulls.")
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
        guard !plan.executable.sets.isEmpty, plan.sets.count <= maxSets,
              hasWireSafeStrings(plan) else { return nil }
        plan.name = sanitizedName(plan.name)
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
        // The WHOLE link, in BYTES, before anything is derived from it. The gate used to
        // sit after `fragment(percentEncoded:)` — which had already decoded and copied
        // the fragment — and counted Characters, so a few base letters each carrying
        // megabytes of combining marks walked through it. Bytes of the string as it
        // arrived cannot be dressed up as fewer.
        guard url.absoluteString.utf8.count <= maxLinkBytes else {
            throw RoutineShareError.unreadable
        }
        // PERCENT-DECODED: base64url needs no encoding, but a scanner or link shortener
        // may percent-encode unreserved characters, and refusing that fails an intact
        // code. nil (no '#': a bare typed scheme) differs from empty (a bad scan).
        guard let fragment = url.fragment(percentEncoded: false) else {
            throw RoutineShareError.notARoutineLink
        }
        guard !fragment.isEmpty else { throw RoutineShareError.unreadable }
        // 4/3 is base64's expansion, so this is `maxCompressedBytes` measured in base64
        // characters — and in UTF-8 BYTES, which for base64url is the same number and for
        // anything else is larger.
        guard fragment.utf8.count <= maxCompressedBytes * 4 / 3 + 4 else {
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
        // Nesting, before a parser recurses into it. Foundation stops at its own depth
        // limit; the Kotlin parser does not and overflowed its stack on a 59-character
        // link. One explicit number gives both platforms the same answer.
        guard !exceedsNestingDepth(json) else { throw RoutineShareError.unreadable }

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
        // The plan decoder keeps a grip position and a target basis VERBATIM, which is
        // right for a blob this app wrote and wrong for a stranger's: a 687-character
        // link once carried a 255,000-character "position" into the preview, the saved
        // routine and every pull of every session logged from it.
        guard hasWireSafeStrings(envelope.plan) else { throw RoutineShareError.unreadable }

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
    /// A backstop behind the compressed cap, sized to the payload rather than to the
    /// transient: the largest legal routine (50 sets, every field set) inflates to about
    /// 6 KB, so 32 KB is five times the real thing and an eighth of what this used to
    /// admit. Everything a stranger can smuggle rides inside this number.
    private static let maxDecompressedBytes = 32 * 1024
    /// The link as a string. A full 4 KB payload is 5,462 base64 characters; a scanner
    /// that percent-encodes every one of them triples that. 20 KB clears the worst
    /// honest case and refuses everything else before a single copy is made.
    private static let maxLinkBytes = 20 * 1024
    private static let maxSets = 50
    /// Unicode SCALARS, not Characters: one Character can be a base letter under
    /// thousands of combining marks, so a grapheme cap bounds what is drawn and nothing
    /// about what is stored. Scalars are also the one unit Swift and Kotlin count alike.
    static let maxNameScalars = 60
    /// A real payload nests five deep (envelope, plan, sets, set, grip).
    private static let maxNestingDepth = 64
    /// A vocabulary word from a stranger: see `isWireToken`.
    private static let maxTokenBytes = 32

    /// One cleaning, applied on encode AND decode, so the sharer's screen and the
    /// recipient's can never disagree about what a name became. It is the routine's
    /// title on the card, the lock screen and a notification, so it has to be TEXT:
    ///
    /// - bidi controls are dropped — they reorder what surrounds them, which is how a
    ///   name dresses itself as something else;
    /// - control characters and the two Unicode line breaks become a space — a title is
    ///   one line everywhere it is drawn, and a CSV row is one line too;
    /// - then trim, cap at `maxNameScalars`, and trim what the cut exposed.
    static func sanitizedName(_ name: String) -> String {
        var cleaned = String.UnicodeScalarView()
        for scalar in name.unicodeScalars {
            switch scalar.value {
            case 0x061C, 0x200E, 0x200F, 0x202A...0x202E, 0x2066...0x2069:
                continue
            case 0x00...0x1F, 0x7F...0x9F, 0x2028, 0x2029:
                cleaned.append(" ")
            default:
                cleaned.append(scalar)
            }
        }
        let trimmed = String(cleaned).trimmingCharacters(in: .whitespacesAndNewlines)
        let capped = String(String.UnicodeScalarView(trimmed.unicodeScalars.prefix(maxNameScalars)))
        return capped.trimmingCharacters(in: .whitespacesAndNewlines)
    }

    /// Every string the plan decoder keeps verbatim, checked as a vocabulary word. The
    /// name is cleaned instead (`sanitizedName`); fingers and hands decode into closed
    /// types and keep nothing of what arrived.
    private static func hasWireSafeStrings(_ plan: SessionPlan) -> Bool {
        guard plan.targetBasisRaw.map(isWireToken) ?? true else { return false }
        return plan.sets.allSatisfy { set in
            isWireToken(set.grip.position.rawValue)
                && (set.targetBasisRaw.map(isWireToken) ?? true)
        }
    }

    /// An ASCII letter followed by at most 31 ASCII letters or digits — the shape of
    /// every position and basis this app has ever written (`halfCrimp`, `criticalForce`)
    /// and of any a newer build is likely to add, so `sloper` still round-trips. What it
    /// refuses is everything that is not a word: length, separators that would forge a
    /// `GripSpec.key` or a CSV column, and text that renders as something else.
    private static func isWireToken(_ raw: String) -> Bool {
        let bytes = raw.utf8
        guard (1...maxTokenBytes).contains(bytes.count),
              let first = bytes.first, isASCIILetter(first) else { return false }
        return bytes.allSatisfy { isASCIILetter($0) || (0x30...0x39).contains($0) }
    }

    private static func isASCIILetter(_ byte: UInt8) -> Bool {
        (0x41...0x5A).contains(byte) || (0x61...0x7A).contains(byte)
    }

    /// Brackets outside JSON strings, counted without parsing. Unbalanced input may read
    /// shallow here; it is not JSON, and the decoder says so a line later.
    private static func exceedsNestingDepth(_ json: Data) -> Bool {
        var depth = 0
        var inString = false
        var escaped = false
        for byte in json {
            if inString {
                if escaped {
                    escaped = false
                } else if byte == 0x5C {
                    escaped = true
                } else if byte == 0x22 {
                    inString = false
                }
                continue
            }
            switch byte {
            case 0x22:
                inString = true
            case 0x5B, 0x7B:
                depth += 1
                if depth > maxNestingDepth { return true }
            case 0x5D, 0x7D:
                depth -= 1
            default:
                break
            }
        }
        return false
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
        let remainder = padded.utf8.count % 4
        if remainder == 1 { return nil }
        if remainder > 0 { padded += String(repeating: "=", count: 4 - remainder) }
        return Data(base64Encoded: padded)
    }
}
