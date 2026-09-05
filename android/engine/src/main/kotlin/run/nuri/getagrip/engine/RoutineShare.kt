// SPDX-License-Identifier: MPL-2.0
// Original contributions Copyright 2026 Nuri Bruner.

package run.nuri.getagrip.engine

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.io.ByteArrayOutputStream
import java.net.URI
import java.net.URISyntaxException
import java.util.Base64
import java.util.Locale
import java.util.UUID
import java.util.zip.DataFormatException
import java.util.zip.Deflater
import java.util.zip.Inflater

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
// TRANSLATION NOTE (from Shared/Engine/RoutineShare.swift): four things could not cross
// unchanged, and each is called out again where it happens.
//   1. Apple's `NSData.compressed(using: .zlib)` is RAW DEFLATE (RFC 1951) despite the
//      name — no zlib header, no Adler-32 trailer. MEASURED (2026-09-04): the starter
//      envelope compresses to bytes beginning `ab 56 2a…`, not `78 9c…`. So the twin is
//      `Deflater(level, nowrap = true)` / `Inflater(nowrap = true)`.
//   2. `URL` becomes `java.net.URI`; a string URI cannot parse at all is the same
//      answer as Swift's `URL(string:)` returning nil — `notARoutineLink`.
//   3. Swift's `String.prefix`/`count` are GRAPHEME counts and Kotlin's `take`/`length`
//      are UTF-16 code units. Every capped string here is a name, a note or base64, so
//      the two agree on everything the format actually carries; a name of astral
//      emoji would cap one glyph differently, which is a cap, not a correctness rule.
//   4. Foundation inflates first and measures after; `Inflater` is incremental, so the
//      decompressed cap is enforced WHILE inflating. Same verdict, less allocation.

/// Everything that can go wrong reading a code somebody else's phone produced. The
/// strings are the words shown in the alert — there is no second copy of them in the UI.
///
/// TRANSLATION NOTE: a Swift `Error` enum becomes a sealed class of singletons, so
/// `catch (e: RoutineShareError)` plus an identity comparison reads exactly like the
/// Swift `error as? RoutineShareError == .unreadable`.
sealed class RoutineShareError : Exception() {
    /// Not shaped like a routine link at all: another app's scheme, a web page, or our
    /// own scheme with no payload attached.
    object notARoutineLink : RoutineShareError()

    /// Shaped right, unreadable inside — damaged base64, truncated compression, JSON
    /// that is not an envelope, or a version number this format never wrote.
    object unreadable : RoutineShareError()
    object newerVersion : RoutineShareError()
    object emptyRoutine : RoutineShareError()
    object tooLarge : RoutineShareError()

    val errorDescription: String
        get() = when (this) {
            notARoutineLink -> L10n.tr("This link isn't a routine from Get a Grip.")
            unreadable -> L10n.tr("This routine code couldn't be read. It may be damaged or incomplete.")
            newerVersion -> L10n.tr("This routine was shared from a newer version of Get a Grip. Update the app to import it.")
            emptyRoutine -> L10n.tr("This code contains a routine with no pulls in it.")
            tooLarge -> L10n.tr("This routine is too large to import.")
        }

    override val message: String get() = errorDescription
}

object RoutineShare {
    /// Bumped only when the ENVELOPE changes shape. Adding a field to `SessionPlan` does
    /// not touch this: the plan's own decoder already tolerates keys it has never heard
    /// of, so a routine from a newer build imports with one field missing rather than
    /// refusing outright. A version bump means "an older build cannot read this at all".
    const val currentVersion = 1

    // MARK: - Out

    /// The ONE place a routine becomes a URL, so the scheme can later swap to an https
    /// universal link without another line in the app changing.
    ///
    /// null when this routine cannot become a WORKING code — and the encoder's refusals
    /// mirror the decoder's caps deliberately: a 51-set routine used to share as a
    /// perfectly normal-looking QR that every phone, including the sender's own, then
    /// refused as "too large". A code the sharer cannot learn is broken is worse than
    /// the alert the null routes into. The name and note caps are applied here as well,
    /// for the same symmetry: truncating only on arrival left two people believing they
    /// had the same routine under two different names.
    fun url(draft: RoutineDraft): String? {
        var plan = draft.plan
        if (plan.executable.sets.isEmpty() || plan.sets.size > maxSets) return null
        plan = plan.copy(
            name = sanitizedName(plan.name),
            sets = plan.sets.map { it.copy(note = it.note.take(maxNoteCharacters)) },
        )
        val envelope = Envelope(
            v = currentVersion,
            plan = plan,
            sessionsPerDay = draft.sessionsPerDay,
            isOnDemand = draft.isOnDemand,
        )
        // `.sortedKeys` AND `.withoutEscapingSlashes` on iOS — the second is what
        // `escapeSlashes = false` is here. A grip position or a routine name containing
        // a slash would otherwise cost two bytes of QR for nothing.
        val json = BlobCodec.encode(envelope, escapeSlashes = false) ?: return null
        val compressed = deflate(json.toByteArray(Charsets.UTF_8))
        if (compressed.size > maxCompressedBytes) return null
        return "$scheme://$host#${base64url(compressed)}"
    }

    // MARK: - In

    /// Accepts `getagrip://routine#…` today and `https://<any-host>/…routine…#…` for the
    /// universal-link form that is still out of scope — today's build must be able to
    /// read a link tomorrow's build hands out, or every code shared in between dies at
    /// the App Store fallback.
    ///
    /// Everything past this point is UNTRUSTED input from a camera: every step is capped
    /// or fails closed, and nothing is trusted to be the size it says it is.
    fun draft(from: String): RoutineDraft {
        val url = parse(from) ?: throw RoutineShareError.notARoutineLink
        if (!isRoutineLink(url)) throw RoutineShareError.notARoutineLink
        // PERCENT-DECODED, deliberately: the base64url alphabet contains no character
        // that needs encoding, so nothing a percent-decode produces could ever have been
        // in a payload this encoder wrote — but a third-party scanner or a link
        // shortener is allowed to percent-encode unreserved characters on the way
        // through, and refusing its output would fail an intact code. The null-versus-
        // empty distinction is the one that matters — a link with no '#' at all is a
        // bare scheme somebody typed, while an empty payload is a code that scanned
        // badly. (`URI.getFragment()` is the decoded form; `getRawFragment()` is not.)
        val fragment = url.fragment ?: throw RoutineShareError.notARoutineLink
        if (fragment.isEmpty()) throw RoutineShareError.unreadable
        // Length-bounded BEFORE any string work: everything below walks or copies the
        // whole fragment, and without this guard a 40 MB link would allocate several
        // multiples of itself on the main thread just to be refused. The 4/3 is base64's
        // own expansion ratio, so this is the same cap as `maxCompressedBytes`, measured
        // in characters.
        if (fragment.length > maxCompressedBytes * 4 / 3 + 4) throw RoutineShareError.unreadable

        val compressed = dataFromBase64url(fragment) ?: throw RoutineShareError.unreadable
        if (compressed.size > maxCompressedBytes) throw RoutineShareError.unreadable

        // zlib tops out near 1030:1, so `maxCompressedBytes` — the only bound that can be
        // enforced BEFORE inflation — caps the transient allocation at ~4 MB. The
        // decompressed check is therefore a backstop, not the limit; the compressed cap
        // is the real one, and it is sized so a legal 50-set routine (~1.6 KB) still
        // clears it with headroom.
        val inflated = inflate(compressed, maxDecompressedBytes) ?: throw RoutineShareError.unreadable
        val document = BlobCodec.parse(String(inflated, Charsets.UTF_8))
            ?: throw RoutineShareError.unreadable

        // The version is judged BEFORE the envelope is decoded in full: a future format
        // may reshape the plan itself, and its payload must come back as "update the
        // app", never as "damaged" — the plan decode below is strict and would otherwise
        // answer first.
        val probe = VersionProbe.fromJson(document) ?: throw RoutineShareError.unreadable
        if (probe.v < 1) throw RoutineShareError.unreadable
        if (probe.v > currentVersion) throw RoutineShareError.newerVersion

        val envelope = Envelope.fromJson(document) ?: throw RoutineShareError.unreadable
        // Counted on the RAW sets, not the executable ones: this cap is about the size of
        // the thing that arrived, not about how much of it would run.
        if (envelope.plan.sets.size > maxSets) throw RoutineShareError.tooLarge

        var plan = envelope.plan
        // NO sets at all is damage, not a routine: the encoder refuses to build a code
        // for an empty plan, so a payload with none was mangled between the two phones —
        // and "a routine with no pulls in it" would blame the sharer for a crease in a
        // printout. `.emptyRoutine` is reserved for the one distinguishable case below.
        if (plan.sets.isEmpty()) throw RoutineShareError.unreadable
        // Trimmed and capped rather than rejected — a long name is somebody's routine
        // with a long name, not an attack, and the store's own `normalized` turns what
        // is left of a whitespace-only name into the house default on save.
        plan = plan.copy(
            name = sanitizedName(plan.name),
            sets = plan.sets.map {
                // Fresh row identity, same reason as `RoutineDraft.copying`: two people's
                // routines must never share a SetPlan id, or a reorder on one phone is a
                // reorder on the other's list the next time both sync the same rows.
                it.copy(id = UUID.randomUUID(), note = it.note.take(maxNoteCharacters))
            },
        )
        // Sets arrived intact but every one is zero-rep — the one shape that genuinely
        // IS a routine with no pulls in it, so the error can honestly say so.
        if (plan.executable.sets.isEmpty()) throw RoutineShareError.emptyRoutine

        // Start from the defaults, not from a decoded draft: reminders are the one thing
        // that must be the RECIPIENT's, and `setSessionsPerDay` is the only door that
        // fills the ladder for however many sessions a day this routine asks for. It
        // clamps the count itself, which is why nothing clamps it here.
        var out = RoutineDraft()
        // Stated rather than inherited from the default: null is what makes the store
        // CREATE this routine instead of updating one of the recipient's.
        out = out.copy(templateID = null, plan = plan)
        out = out.setSessionsPerDay(envelope.sessionsPerDay)
        // OFF, always. Somebody else's routine may not fire notifications on your phone
        // until you say so — and turning it on here is what would trigger the permission
        // prompt at import.
        return out.copy(isOnDemand = envelope.isOnDemand, remindersEnabled = false)
    }

    /// Cheap enough to run on every incoming link: shape only, no payload work. A link
    /// that passes this and then fails to decode gets an error the user can read; one
    /// that fails here is not ours to answer for at all.
    fun isRoutineLink(url: String): Boolean {
        val parsed = parse(url) ?: return false
        return isRoutineLink(parsed)
    }

    private fun isRoutineLink(url: URI): Boolean {
        val incoming = url.scheme?.lowercase(Locale.ROOT) ?: return false
        if (incoming == scheme) return url.host?.lowercase(Locale.ROOT) == host
        // Any host: the AASA domain is not decided, so the path is the only thing that
        // can say "routine" — and it says it as a whole COMPONENT, not a substring, or
        // this guard would claim `/my-routines/7` and every other page with the word in
        // its slug as ours to answer for.
        if (incoming == "https") {
            return pathComponents(url).any { it.lowercase(Locale.ROOT) == host }
        }
        return false
    }

    /// Swift's `URL.pathComponents` leads with "/" for an absolute path; that element can
    /// never equal `host`, so dropping the empties is the same predicate.
    private fun pathComponents(url: URI): List<String> =
        (url.path ?: "").split('/').filter { it.isNotEmpty() }

    private fun parse(text: String): URI? =
        try { URI(text) } catch (_: URISyntaxException) { null }

    // MARK: - The envelope

    /// The version alone, read first — see the ordering note in `draft(from:)`. Lenient
    /// so that a missing `v` reads as 0 and falls below the floor rather than throwing
    /// into the wrong error.
    private data class VersionProbe(val v: Int) {
        companion object {
            /// null only where Foundation would throw: the payload is not a JSON object
            /// at all, so there is no keyed container to read `v` out of.
            fun fromJson(element: JsonElement): VersionProbe? {
                val o = JsonRead.obj(element) ?: return null
                return VersionProbe(o.intOr("v", 0))
            }
        }
    }

    /// The plan rides VERBATIM as `SessionPlan`'s own wire shape — no parallel share
    /// DTO. A DTO would be a second description of a routine to keep in step with the
    /// first, and the drift between them is exactly the parity bug the builder already
    /// taught this codebase about.
    ///
    /// FROZEN keys. Additive only, same rule as every other blob in the app.
    data class Envelope(
        val v: Int,
        val plan: SessionPlan,
        val sessionsPerDay: Int,
        val isOnDemand: Boolean,
    ) : JsonEncodable {

        override fun toJson(): JsonElement = JsonObject(
            linkedMapOf(
                "v" to JsonPrimitive(v),
                "plan" to plan.toJson(),
                "sessionsPerDay" to JsonPrimitive(sessionsPerDay),
                "isOnDemand" to JsonPrimitive(isOnDemand),
            )
        )

        companion object {
            /// Written out rather than synthesized so an unknown key or a retyped field
            /// costs one value instead of the whole routine — the house rule from
            /// `Leniency`.
            fun fromJson(element: JsonElement): Envelope? {
                val o = JsonRead.obj(element) ?: return null
                // STRICT, unlike every field around it. The lenient fallback here was
                // `SessionPlan()` — whose set list is empty — so a plan key that was
                // missing, or present but mangled into a string by a bad scan, sailed
                // through and was then reported as "a routine with no pulls in it": the
                // damage got blamed on the sharer. Failing surfaces it as `.unreadable`,
                // which is the sentence that is actually true. Leniency still lives
                // INSIDE `SessionPlan.fromJson` for its fields, which is where it belongs.
                val plan = SessionPlan.fromJson(o["plan"]) ?: return null
                return Envelope(
                    // Absent reads as 0, which is below the floor and therefore
                    // unreadable: a payload with no version is not a payload this format
                    // ever wrote.
                    v = o.intOr("v", 0),
                    plan = plan,
                    sessionsPerDay = o.intOr("sessionsPerDay", 2),
                    isOnDemand = o.boolOr("isOnDemand", false),
                )
            }
        }
    }

    // MARK: - Wire details

    const val scheme = "getagrip"
    const val host = "routine"

    /// Caps on untrusted input, enforced at BOTH ends — the encoder refuses to build
    /// what the decoder would refuse to read. The compressed cap is the load-bearing
    /// one: deflate inflates at most ~1030:1, and there is no way to bound the output
    /// before it exists, so 4 KB compressed is what actually caps the transient
    /// allocation (~4 MB worst case). A legal 50-set routine measures ~1.6 KB, so the
    /// headroom is real without being an invitation.
    const val maxCompressedBytes = 4 * 1024
    const val maxDecompressedBytes = 256 * 1024
    const val maxSets = 50
    const val maxNameCharacters = 60
    const val maxNoteCharacters = 500

    /// One trim + cap, applied on encode AND decode, so the sharer's screen and the
    /// recipient's can never disagree about what a too-long name became.
    fun sanitizedName(name: String): String = name.trim().take(maxNameCharacters)

    // MARK: - Compression

    /// RAW DEFLATE, matching Apple's `.zlib` — see the file header. The level is this
    /// side's own choice: nothing compares two encoders' bytes, only what they decode to.
    private fun deflate(bytes: ByteArray): ByteArray {
        val deflater = Deflater(Deflater.BEST_COMPRESSION, true)
        try {
            deflater.setInput(bytes)
            deflater.finish()
            val out = ByteArrayOutputStream(bytes.size)
            val buffer = ByteArray(16 * 1024)
            while (!deflater.finished()) {
                val n = deflater.deflate(buffer)
                if (n == 0 && deflater.needsInput()) break
                out.write(buffer, 0, n)
            }
            return out.toByteArray()
        } finally {
            deflater.end()
        }
    }

    /// null on anything that is not an intact deflate stream, INCLUDING one that inflates
    /// past `limit`. Foundation inflates whole and measures after; bounding the walk is
    /// the same verdict without ever holding the bomb. A stream that runs out of input
    /// before its final block — a QR read from half a screenshot, the commonest real
    /// damage — is `needsInput` with nothing left, and is refused rather than truncated.
    private fun inflate(bytes: ByteArray, limit: Int): ByteArray? {
        val inflater = Inflater(true)
        try {
            inflater.setInput(bytes)
            val out = ByteArrayOutputStream(bytes.size * 4)
            val buffer = ByteArray(16 * 1024)
            while (!inflater.finished()) {
                val n = try {
                    inflater.inflate(buffer)
                } catch (_: DataFormatException) {
                    return null
                }
                if (n == 0) {
                    if (inflater.needsInput() || inflater.needsDictionary()) return null
                    if (!inflater.finished()) return null
                }
                if (out.size() + n > limit) return null
                out.write(buffer, 0, n)
            }
            return out.toByteArray()
        } finally {
            inflater.end()
        }
    }

    // MARK: - base64url

    /// base64url: standard base64 with the two URL-hostile characters swapped and the
    /// padding dropped. Padding is pure length in a QR code, and every '=' costs modules.
    fun base64url(bytes: ByteArray): String =
        Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)

    /// null on anything that is not base64url, including a length no base64 string can
    /// have — one leftover character is 6 bits, which never encoded a byte. Both
    /// alphabets are accepted for the same reason Swift's is: an intermediary may have
    /// re-encoded the payload, and the strict decoder underneath rejects everything else.
    fun dataFromBase64url(text: String): ByteArray? {
        var padded = text.replace('-', '+').replace('_', '/')
        val remainder = padded.length % 4
        if (remainder == 1) return null
        if (remainder > 0) padded += "=".repeat(4 - remainder)
        return try {
            Base64.getDecoder().decode(padded)
        } catch (_: IllegalArgumentException) {
            null
        }
    }
}
