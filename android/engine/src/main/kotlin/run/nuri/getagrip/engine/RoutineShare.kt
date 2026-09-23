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
// The code IS the routine — no server, no account. It rides in the URL FRAGMENT, the
// one part of a URL that never reaches a server, even as a universal link.
//
// NOT carried: reminder times and `remindersEnabled` (personal hours, and an import must
// never ambush the recipient with a permission prompt), the template id, and anything
// the store derived. Percent targets travel and resolve against the RECIPIENT's maxes.
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
    /// Bumped only when the ENVELOPE changes shape; a new `SessionPlan` field does not
    /// need it, since the plan decoder tolerates unknown keys. A bump means "an older
    /// build cannot read this at all".
    const val currentVersion = 1

    // MARK: - Out

    /// The ONE place a routine becomes a URL, so the scheme can later become an https
    /// universal link without touching the app.
    ///
    /// null when the routine cannot make a WORKING code: the encoder mirrors the
    /// decoder's caps, because a 51-set routine once shared as a normal-looking QR that
    /// every phone then refused. Name and note caps apply here too, so sender and
    /// recipient see the same truncated name.
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

    /// Accepts `getagrip://routine#…` and the future `https://<any-host>/…routine…#…`
    /// universal link, so today's build reads codes tomorrow's build hands out.
    ///
    /// Everything past this point is UNTRUSTED camera input: every step is capped or
    /// fails closed, and nothing is trusted to be the size it says it is.
    fun draft(from: String): RoutineDraft {
        val url = parse(from) ?: throw RoutineShareError.notARoutineLink
        if (!isRoutineLink(url)) throw RoutineShareError.notARoutineLink
        // PERCENT-DECODED: base64url needs no encoding, but a scanner or link shortener
        // may percent-encode unreserved characters, and refusing that fails an intact
        // code. null (no '#': a bare typed scheme) differs from empty (a bad scan).
        // (`URI.getFragment()` is the decoded form; `getRawFragment()` is not.)
        val fragment = url.fragment ?: throw RoutineShareError.notARoutineLink
        if (fragment.isEmpty()) throw RoutineShareError.unreadable
        // Length-bounded BEFORE any string work, or a 40 MB link allocates multiples of
        // itself on the main thread just to be refused. 4/3 is base64's expansion, so this
        // is `maxCompressedBytes` measured in characters.
        if (fragment.length > maxCompressedBytes * 4 / 3 + 4) throw RoutineShareError.unreadable

        val compressed = dataFromBase64url(fragment) ?: throw RoutineShareError.unreadable
        if (compressed.size > maxCompressedBytes) throw RoutineShareError.unreadable

        // The compressed cap is the real bound on inflation (see `maxCompressedBytes`);
        // the decompressed check is a backstop.
        val inflated = inflate(compressed, maxDecompressedBytes) ?: throw RoutineShareError.unreadable
        val document = BlobCodec.parse(String(inflated, Charsets.UTF_8))
            ?: throw RoutineShareError.unreadable

        // Version BEFORE the full decode: a future format may reshape the plan, and must
        // read as "update the app", not "damaged" — the strict plan decode would answer first.
        val probe = VersionProbe.fromJson(document) ?: throw RoutineShareError.unreadable
        if (probe.v < 1) throw RoutineShareError.unreadable
        if (probe.v > currentVersion) throw RoutineShareError.newerVersion

        val envelope = Envelope.fromJson(document) ?: throw RoutineShareError.unreadable
        // Counted on the RAW sets, not the executable ones: this cap is about the size of
        // the thing that arrived, not about how much of it would run.
        if (envelope.plan.sets.size > maxSets) throw RoutineShareError.tooLarge

        var plan = envelope.plan
        // NO sets at all is damage: the encoder never builds a code for an empty plan, and
        // "a routine with no pulls" would blame the sharer for a crease in a printout.
        // `.emptyRoutine` is reserved for the distinguishable case below.
        if (plan.sets.isEmpty()) throw RoutineShareError.unreadable
        // Trimmed and capped rather than rejected: a long name is not an attack, and
        // `normalized` turns a whitespace-only name into the house default on save.
        plan = plan.copy(
            name = sanitizedName(plan.name),
            sets = plan.sets.map {
                // Fresh row identity, as in `RoutineDraft.copying`: two people's routines
                // must never share a SetPlan id.
                it.copy(id = UUID.randomUUID(), note = it.note.take(maxNoteCharacters))
            },
        )
        // Sets arrived intact but every one is zero-rep — the one shape that genuinely
        // IS a routine with no pulls in it, so the error can honestly say so.
        if (plan.executable.sets.isEmpty()) throw RoutineShareError.emptyRoutine

        // Start from the defaults, not a decoded draft: reminders must be the RECIPIENT's,
        // and `setSessionsPerDay` (which clamps) is the one door that fills the ladder.
        var out = RoutineDraft()
        // Stated rather than inherited from the default: null is what makes the store
        // CREATE this routine instead of updating one of the recipient's.
        out = out.copy(templateID = null, plan = plan)
        out = out.setSessionsPerDay(envelope.sessionsPerDay)
        // OFF, always: somebody else's routine may not notify on your phone until you say
        // so, and turning it on would trigger the permission prompt at import.
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
        // Any host (the AASA domain is undecided), so the path must say "routine" as a
        // whole COMPONENT — a substring match would claim `/my-routines/7`.
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

    /// The plan rides VERBATIM as `SessionPlan`'s own wire shape: a share DTO would be a
    /// second description of a routine to drift out of step with the first.
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
                // STRICT, unlike every field around it: a lenient `SessionPlan()` fallback
                // has no sets, so a missing or mangled plan read as "a routine with no
                // pulls in it", blaming the sharer. Failing reports `.unreadable`, which
                // is true. Leniency still lives inside `SessionPlan.fromJson` for its fields.
                val plan = SessionPlan.fromJson(o["plan"]) ?: return null
                return Envelope(
                    // Absent reads as 0, below the floor and therefore unreadable: this
                    // format never wrote a payload without a version.
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

    /// Caps on untrusted input, enforced at BOTH ends. The compressed cap is the one that
    /// matters: deflate inflates at most ~1030:1, so 4 KB compressed caps the worst-case
    /// output at ~4 MB (enforced while inflating here — see `inflate`). A legal 50-set
    /// routine is ~1.6 KB.
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
