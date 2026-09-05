// SPDX-License-Identifier: MPL-2.0
// Original contributions Copyright 2026 Nuri Bruner.

import Foundation

// A routine as a LINK, and the link as a QR code somebody points a camera at.
//
// The code IS the routine — no server, no account, no lookup. Everything the recipient
// needs rides in the URL FRAGMENT, which is also the one part of a URL that never
// reaches a server even after this grows into a universal link: the routine stays
// between two phones.
//
// What deliberately does NOT travel: reminder times and `remindersEnabled` (personal
// hours, and an import must never ambush the recipient with a notification permission
// prompt), the template id, and everything the store derived. Percent targets DO travel
// and resolve against the RECIPIENT's own maxes — that is the entire reason a
// prescription is a fraction rather than kilograms, and it needs no translation here.
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
    /// Bumped only when the ENVELOPE changes shape. Adding a field to `SessionPlan` does
    /// not touch this: the plan's own decoder already tolerates keys it has never heard
    /// of, so a routine from a newer build imports with one field missing rather than
    /// refusing outright. A version bump means "an older build cannot read this at all".
    static let currentVersion = 1

    // MARK: - Out

    /// The ONE place a routine becomes a URL, so the scheme can later swap to an https
    /// universal link without another line in the app changing.
    ///
    /// nil when this routine cannot become a WORKING code — and the encoder's refusals
    /// mirror the decoder's caps deliberately: a 51-set routine used to share as a
    /// perfectly normal-looking QR that every phone, including the sender's own, then
    /// refused as "too large". A code the sharer cannot learn is broken is worse than
    /// the alert the nil routes into. The name and note caps are applied here as well,
    /// for the same symmetry: truncating only on arrival left two people believing they
    /// had the same routine under two different names.
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

    /// Accepts `getagrip://routine#…` today and `https://<any-host>/…routine…#…` for the
    /// universal-link form that is still out of scope — today's build must be able to
    /// read a link tomorrow's build hands out, or every code shared in between dies at
    /// the App Store fallback.
    ///
    /// Everything past this point is UNTRUSTED input from a camera: every step is capped
    /// or fails closed, and nothing is trusted to be the size it says it is.
    static func draft(from url: URL) throws -> RoutineDraft {
        guard isRoutineLink(url) else { throw RoutineShareError.notARoutineLink }
        // PERCENT-DECODED, deliberately: the base64url alphabet contains no character
        // that needs encoding, so nothing a percent-decode produces could ever have been
        // in a payload this encoder wrote — but a third-party scanner or a link
        // shortener is allowed to percent-encode unreserved characters on the way
        // through, and refusing its output would fail an intact code. The nil-versus-
        // empty distinction is the one that matters — a link with no '#' at all is a
        // bare scheme somebody typed, while an empty payload is a code that scanned
        // badly.
        guard let fragment = url.fragment(percentEncoded: false) else {
            throw RoutineShareError.notARoutineLink
        }
        guard !fragment.isEmpty else { throw RoutineShareError.unreadable }
        // Length-bounded BEFORE any string work: everything below walks or copies the
        // whole fragment, and without this guard a 40 MB link would allocate several
        // multiples of itself on the main actor just to be refused. The 4/3 is base64's
        // own expansion ratio, so this is the same cap as `maxCompressedBytes`, measured
        // in characters.
        guard fragment.count <= maxCompressedBytes * 4 / 3 + 4 else {
            throw RoutineShareError.unreadable
        }

        guard let compressed = data(fromBase64url: fragment),
              compressed.count <= maxCompressedBytes
        else { throw RoutineShareError.unreadable }

        // zlib tops out near 1030:1, so `maxCompressedBytes` — the only bound Foundation
        // lets us enforce BEFORE inflation — caps the transient allocation at ~4 MB.
        // The decompressed check below is therefore a backstop, not the limit; the
        // compressed cap is the real one, and it is sized so a legal 50-set routine
        // (~1.6 KB) still clears it with headroom.
        guard let inflated = try? (compressed as NSData).decompressed(using: .zlib) else {
            throw RoutineShareError.unreadable
        }
        let json = inflated as Data
        guard json.count <= maxDecompressedBytes else { throw RoutineShareError.unreadable }

        // The version is judged BEFORE the envelope is decoded in full: a future format
        // may reshape the plan itself, and its payload must come back as "update the
        // app", never as "damaged" — the plan decode below is strict and would otherwise
        // answer first.
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
        // NO sets at all is damage, not a routine: the encoder refuses to build a code
        // for an empty plan, so a payload with none was mangled between the two phones —
        // and "a routine with no pulls in it" would blame the sharer for a crease in a
        // printout. `.emptyRoutine` is reserved for the one distinguishable case below.
        guard !plan.sets.isEmpty else { throw RoutineShareError.unreadable }
        // Trimmed and capped rather than rejected — a long name is somebody's routine
        // with a long name, not an attack, and the store's own `normalized` turns what
        // is left of a whitespace-only name into the house default on save.
        plan.name = sanitizedName(plan.name)
        plan.sets = plan.sets.map { set in
            var s = set
            // Fresh row identity, same reason as `RoutineDraft.copying`: two people's
            // routines must never share a SetPlan id, or a reorder on one phone is a
            // reorder on the other's list the next time both sync the same rows.
            s.id = UUID()
            s.note = String(s.note.prefix(maxNoteCharacters))
            return s
        }
        // Sets arrived intact but every one is zero-rep — the one shape that genuinely
        // IS a routine with no pulls in it, so the error can honestly say so.
        guard !plan.executable.sets.isEmpty else { throw RoutineShareError.emptyRoutine }

        // Start from the defaults, not from a decoded draft: reminders are the one thing
        // that must be the RECIPIENT's, and `setSessionsPerDay` is the only door that
        // fills the ladder for however many sessions a day this routine asks for. It
        // clamps the count itself, which is why nothing clamps it here.
        var draft = RoutineDraft()
        // Stated rather than inherited from the default: nil is what makes the store
        // CREATE this routine instead of updating one of the recipient's.
        draft.templateID = nil
        draft.plan = plan
        draft.setSessionsPerDay(envelope.sessionsPerDay)
        draft.isOnDemand = envelope.isOnDemand
        // OFF, always. Somebody else's routine may not fire notifications on your phone
        // until you say so — and turning it on here is what would trigger the permission
        // prompt at import.
        draft.remindersEnabled = false
        return draft
    }

    /// Cheap enough to run on every `onOpenURL`: shape only, no payload work. A link that
    /// passes this and then fails to decode gets an error the user can read; one that
    /// fails here is not ours to answer for at all.
    static func isRoutineLink(_ url: URL) -> Bool {
        guard let incoming = url.scheme?.lowercased() else { return false }
        if incoming == scheme { return url.host()?.lowercased() == host }
        // Any host: the AASA domain is not decided, so the path is the only thing that
        // can say "routine" — and it says it as a whole COMPONENT, not a substring, or
        // this guard would claim `/my-routines/7` and every other page with the word in
        // its slug as ours to answer for.
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

    /// The plan rides VERBATIM as `SessionPlan`'s own Codable output — no parallel share
    /// DTO. A DTO would be a second description of a routine to keep in step with the
    /// first, and the drift between them is exactly the parity bug the builder already
    /// taught this codebase about.
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
            // Absent reads as 0, which is below the floor and therefore unreadable: a
            // payload with no version is not a payload this format ever wrote.
            self.v = c.value(.v, or: 0)
            // STRICT, unlike every field around it. The lenient fallback here was
            // `SessionPlan()` — whose set list is empty — so a plan key that was
            // missing, or present but mangled into a string by a bad scan, sailed
            // through and was then reported as "a routine with no pulls in it": the
            // damage got blamed on the sharer. Throwing surfaces it as `.unreadable`,
            // which is the sentence that is actually true. Leniency still lives INSIDE
            // `SessionPlan.init(from:)` for its fields, which is where it belongs.
            self.plan = try c.decode(SessionPlan.self, forKey: .plan)
            self.sessionsPerDay = c.value(.sessionsPerDay, or: 2)
            self.isOnDemand = c.value(.isOnDemand, or: false)
        }
    }

    // MARK: - Wire details

    private static let scheme = "getagrip"
    private static let host = "routine"

    /// Caps on untrusted input, enforced at BOTH ends — the encoder refuses to build
    /// what the decoder would refuse to read. The compressed cap is the load-bearing
    /// one: zlib inflates at most ~1030:1, and Foundation offers no way to bound the
    /// output before it exists, so 4 KB compressed is what actually caps the transient
    /// allocation (~4 MB worst case). A legal 50-set routine measures ~1.6 KB, so the
    /// headroom is real without being an invitation.
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

    /// `.sortedKeys` so the output is stable in practice and the round-trip tests have
    /// one canonical form to pin. It is NOT a cross-device byte-stability guarantee —
    /// neither JSONEncoder's Double formatting nor Apple's deflate output is documented
    /// stable — and nothing here needs one: no URL is ever compared or used as a key,
    /// and a printed code keeps working because the DECODER is stable, not the encoder.
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
