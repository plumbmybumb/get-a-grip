// SPDX-License-Identifier: MPL-2.0
// Original contributions Copyright 2026 Nuri Bruner.

package run.nuri.getagrip.engine

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

// A grip is a VALUE, not a record. There is no grip library, no folder, no picker of
// saved grips — the thing Frez makes you build before you can pull. A grip lives
// inline on the set row that uses it, and two sets built months apart with the same
// three fields are automatically the same trend series because they compute the same
// `GripSpec.key`.

// MARK: - Fingers

/// Which digits are on the hold. An OptionSet over exactly FIVE bits — the four
/// fingers plus the THUMB — because the domain is closed at the hand: every non-empty
/// combination is representable, so there is no such thing as an "unknown value from a
/// newer build" to fall back from. The thumb earned its bit the honest way (Nuri,
/// 2026-08-04): pinch blocks are real training, and a pinch IS thumb opposition — it
/// was a missing digit, not a new dimension. A genuinely new dimension (pinch width,
/// sloper angle) is still a new FIELD, never a new bit.
///
/// TRANSLATION NOTE (from Shared/Engine/GripSpec.swift): Swift's `OptionSet` becomes a
/// `@JvmInline value class` over the same Int. The masking initializer needs a PRIVATE
/// primary constructor plus `Companion.invoke`, because a Kotlin constructor cannot
/// rewrite its own parameter — so `FingerSet(rawValue)` reads identically to Swift at
/// every call site outside this file. Inside the class the private constructor wins
/// overload resolution and therefore does NOT mask; every internal call below passes an
/// already-masked value (an `or`/`and` of masked bits stays inside the mask). Set
/// algebra is `contains` / `union` / `subtracting`, the three operations Swift's
/// `OptionSet` gave for free.
@JvmInline
value class FingerSet private constructor(val rawValue: Int) {

    fun contains(member: FingerSet): Boolean = (rawValue and member.rawValue) == member.rawValue
    fun union(other: FingerSet): FingerSet = FingerSet(rawValue or other.rawValue)
    fun subtracting(other: FingerSet): FingerSet = FingerSet(rawValue and other.rawValue.inv())
    val isEmpty: Boolean get() = rawValue == 0

    /// THE WIRE FORMAT, and one third of the canonical key. Fixed order, one letter per
    /// finger present: "IMRL", "IMR", "IM", "MR", "RL". Never localized, never
    /// reordered — reordering forks one trend series into two.
    val token: String
        get() {
            val out = StringBuilder()
            for ((position, bit) in allDigits.withIndex()) {
                if (contains(bit)) out.append(letters[position])
            }
            return out.toString()
        }

    /// Four flags, index to little — the FINGERS only, because the drawn row is four
    /// pips and the thumb is its own bar. The one input `FingerGlyph` and `FingerPips`
    /// read, so a drawn hand can never disagree with the token.
    val occupied: List<Boolean> get() = allFingers.map { contains(it) }

    val hasThumb: Boolean get() = contains(thumb)

    /// Digits on the hold, thumb included.
    val count: Int get() = Integer.bitCount(rawValue)

    /// The spoken name. Named combinations get climbing's own words; anything else
    /// gets a composite rather than a made-up label nobody uses at the board.
    val name: String
        get() {
            // The thumb reads as a suffix on whatever the fingers are doing — "Front 2 +
            // thumb" is how a climber says a three-point pinch. Alone, it is just "Thumb".
            if (contains(thumb)) {
                val rest = subtracting(thumb)
                return if (rest.isEmpty) L10n.tr("Thumb") else L10n.tr("%s + thumb", rest.name)
            }
            return when (this) {
                four -> L10n.tr("4 fingers")
                frontThree -> L10n.tr("Front 3")
                backThree -> L10n.tr("Back 3")
                frontTwo -> L10n.tr("Front 2")
                middleTwo -> L10n.tr("Middle 2")
                backTwo -> L10n.tr("Back 2")
                index -> L10n.tr("Index")
                middle -> L10n.tr("Middle")
                ring -> L10n.tr("Ring")
                little -> L10n.tr("Little")
                else -> composite(occupied)
            }
        }

    /// The chip/monogram form. Falls back to the token, which is always short enough.
    val shortName: String
        get() {
            if (contains(thumb)) {
                val rest = subtracting(thumb)
                return if (rest.isEmpty) "T" else rest.shortName + "+T"
            }
            return when (this) {
                four -> "4F"
                frontThree -> "F3"
                backThree -> "B3"
                frontTwo -> "F2"
                middleTwo -> "M2"
                backTwo -> "B2"
                index -> "I"
                middle -> "M"
                ring -> "R"
                little -> "L"
                else -> token
            }
        }

    companion object {
        private const val MASK = 0b11111

        /// Masks to `0b11111`, so an unknown bit cannot exist even if one arrives over
        /// CloudKit. That is what lets every other member of this type be total.
        operator fun invoke(rawValue: Int): FingerSet = FingerSet(rawValue and MASK)

        val index = FingerSet(1 shl 0)
        val middle = FingerSet(1 shl 1)
        val ring = FingerSet(1 shl 2)
        val little = FingerSet(1 shl 3)

        /// Bit FOUR, letter LAST — and that placement is load-bearing: every thumbless
        /// token, and therefore every key ever written before the thumb existed, stays
        /// byte-identical. History cannot fork on an upgrade.
        val thumb = FingerSet(1 shl 4)

        val four = of(listOf(index, middle, ring, little))
        val frontThree = of(listOf(index, middle, ring))
        val backThree = of(listOf(middle, ring, little))
        val frontTwo = of(listOf(index, middle))
        val middleTwo = of(listOf(middle, ring))
        val backTwo = of(listOf(ring, little))

        /// Pip order — index to little, the order the four FINGERS are drawn and read.
        /// The thumb is deliberately not here: it draws as its own bar below the row.
        val allFingers: List<FingerSet> = listOf(index, middle, ring, little)

        /// Token order — fingers first, thumb LAST. One letter per entry, read as a pair
        /// with `letters`.
        val allDigits: List<FingerSet> = listOf(index, middle, ring, little, thumb)
        const val letters = "IMRLT"

        /// TRANSLATION NOTE: the twin of Swift's array-literal `OptionSet` syntax,
        /// `FingerSet([.index, .middle])` — a `List`, not a `vararg`, because the JVM
        /// forbids an array of an inline class.
        fun of(members: List<FingerSet>): FingerSet =
            FingerSet(members.fold(0) { accumulated, member -> accumulated or member.rawValue })

        /// TOTAL: unknown letters are ignored and empty input yields `.four`. Never fails,
        /// because the alternative is a decoder that throws away a whole routine over one
        /// stray character.
        fun fromToken(token: String): FingerSet {
            var parsed = 0
            for (character in token.uppercase()) {
                val position = letters.indexOf(character)
                if (position < 0) continue
                parsed = parsed or allDigits[position].rawValue
            }
            return if (parsed == 0) four else FingerSet(parsed)
        }

        private val singleNames: List<String>
            get() = listOf(L10n.tr("Index"), L10n.tr("Middle"), L10n.tr("Ring"), L10n.tr("Little"))

        /// "Index + ring" — first word capitalised, the rest lowercase, so it reads as one
        /// phrase rather than a row of proper nouns.
        private fun composite(occupied: List<Boolean>): String {
            val parts = singleNames.filterIndexed { i, _ -> occupied.getOrElse(i) { false } }
            val first = parts.firstOrNull() ?: return L10n.tr("No fingers")
            return (listOf(first) + parts.drop(1).map { it.lowercase() }).joinToString(" + ")
        }

        // Codable is SINGLE-VALUE over `token`: the blob reads `"fingers":"IM"`, which is
        // legible in a dump and survives any future change to the bit layout.
        fun fromJson(element: JsonElement?): FingerSet? =
            JsonRead.string(element)?.let { fromToken(it) }
    }
}

// MARK: - Position

/// How the hand is set on the edge. An EXTENSIBLE `RawRepresentable` struct, not an
/// enum: this vocabulary is genuinely open (sloper, pinch, monos), two devices sync the
/// same routine, and an enum would fall back on an unknown raw and then silently
/// rewrite the set the next time the user touched it.
@JvmInline
value class GripPosition(val rawValue: String) {

    val name: String
        get() = when (this) {
            halfCrimp -> L10n.tr("Half crimp")
            openHand -> L10n.tr("Open")
            fullCrimp -> L10n.tr("Full crimp")
            drag -> L10n.tr("Drag")
            pinch -> L10n.tr("Pinch")
            fingerCurl -> L10n.tr("Finger curl")
            else -> rawValue
        }

    val shortName: String
        get() = when (this) {
            halfCrimp -> L10n.tr("HC")
            openHand -> L10n.tr("OH")
            fullCrimp -> L10n.tr("FC")
            drag -> L10n.tr("DR")
            pinch -> L10n.tr("PN")
            fingerCurl -> L10n.tr("CURL")
            else -> rawValue.uppercase()
        }

    /// 0 = fingers straight, 1 = fully closed. The ONE place a grip glyph's corner
    /// radius comes from, so every drawing of a grip in the app agrees. An unknown raw
    /// gets 0.5 — visibly neither extreme, which is the honest answer.
    val closure: Double
        get() = when (this) {
            drag -> 0.0
            openHand -> 0.2
            // A pinch is squeezed, not curled — nearer open than crimp.
            pinch -> 0.35
            halfCrimp, fingerCurl -> 0.65
            fullCrimp -> 1.0
            else -> 0.5
        }

    companion object {
        val halfCrimp = GripPosition("halfCrimp")
        val openHand = GripPosition("openHand")
        val fullCrimp = GripPosition("fullCrimp")
        val drag = GripPosition("drag")

        /// Thumb-opposition block work. A pinch always includes the thumb; GripSpec
        /// enforces that invariant during construction, copying and decoding.
        val pinch = GripPosition("pinch")
        /// Half-crimp isometric curl: a distinct identity for history and max targets.
        val fingerCurl = GripPosition("fingerCurl")

        /// Chip order, most used first. An unknown position decoded from another build is
        /// NOT in here and still round-trips untouched — that is the point of the struct.
        val known: List<GripPosition> = listOf(halfCrimp, openHand, fullCrimp, drag, pinch, fingerCurl)

        // Codable is SINGLE-VALUE over `rawValue`. Written out rather than left to the
        // stdlib's RawRepresentable default, because that default THROWS when an
        // `init(rawValue:)` rejects — and this type must never reject one.
        fun fromJson(element: JsonElement?): GripPosition? =
            JsonRead.string(element)?.let { GripPosition(it) }
    }
}

// MARK: - The grip

/// A grip, as a VALUE. There is no Grip model, no library, no folder — a grip lives
/// inline on the set row that uses it, and two sets built independently with the same
/// three fields are automatically the same trend series.
///
/// TRANSLATION NOTE: Swift enforces the pinch invariant with `didSet` observers on
/// `fingers` and `position` plus a hand-written `init` that shadows the memberwise one.
/// Kotlin has neither property observers nor mutable value semantics, so this is a plain
/// class (NOT a `data class`) whose `fingers` PROPERTY is computed from the constructor
/// parameter of the same name — a property initializer is the one place a Kotlin
/// constructor can normalize its own input, and unlike an `init { require(…) }` guard it
/// heals rather than throws, which is what the decoder needs. There is deliberately no
/// generated `copy`: the Swift mutations (`spec.position = .pinch`) become
/// `withEdgeMM` / `withFingers` / `withPosition`, which re-enter the constructor and
/// therefore re-assert the invariant. `equals`/`hashCode` are hand-written over the
/// three RESOLVED fields, so a grip built as a thumbless pinch is equal to — and hashes
/// with — the same grip built with the thumb.
class GripSpec(
    /// WHOLE millimetres. Int, not Double, and that is load-bearing: `key` is a
    /// serialization, and Double formatting is locale-dependent — "20" vs "20.0" vs
    /// "20,0" would fork one trend series into three between a French phone and an
    /// American one.
    val edgeMM: Int = 20,
    fingers: FingerSet = FingerSet.four,
    val position: GripPosition = GripPosition.halfCrimp,
) : JsonEncodable {

    /// A PINCH ALWAYS INCLUDES THE THUMB (Nuri, 2026-08-04: "there's no world where you
    /// can pinch without the thumb"). It is not a preference — a pinch IS thumb
    /// opposition, so a pinch without one is not a grip anybody can perform, and the app
    /// must not be able to represent it. Enforced HERE rather than in the three screens
    /// that edit a grip, so no surface can produce one and no blob can decode into one.
    val fingers: FingerSet =
        if (position == GripPosition.pinch) fingers.union(FingerSet.thumb) else fingers

    fun withEdgeMM(edgeMM: Int): GripSpec = GripSpec(edgeMM, this.fingers, position)
    fun withFingers(fingers: FingerSet): GripSpec = GripSpec(edgeMM, fingers, position)
    fun withPosition(position: GripPosition): GripSpec = GripSpec(edgeMM, fingers, position)

    /// ### THE CANONICAL KEY — FROZEN FOREVER.
    /// `"<edgeMM>|<fingerToken>|<positionRaw>"` — "20|IMRL|halfCrimp", "20|IM|fullCrimp".
    /// The only thing joining a rep pulled in March to one pulled in December and to
    /// the `MaxRecord` that says what 25 % means for it. NEVER localize, reformat,
    /// reorder or pad. `|` is safe: no component can contain one — the edge is an Int,
    /// the token is drawn from "IMRL", and a position raw is an identifier.
    /// NEVER STORED — always computed, because a stored copy is a second source of
    /// truth that can disagree with the fields beside it.
    /// A fourth dimension takes a "v2:" namespace plus a migration, or lives outside
    /// the key.
    val key: String get() = "$edgeMM|${fingers.token}|${position.rawValue}"

    /// Sentence case, for a set row inside running copy: "20 mm · 4 fingers · half crimp".
    /// It differs from `displayName` in the POSITION's case only — the finger names are
    /// labels ("Front 3", "4 fingers") whose capital is part of the name.
    val line: String
        get() = L10n.tr("%d mm · %s · %s", edgeMM, fingers.name, position.name.lowercase())

    /// Title case, for a heading or a picker: "20 mm · 4 fingers · Half crimp".
    val displayName: String
        get() = L10n.tr("%d mm · %s · %s", edgeMM, fingers.name, position.name)

    /// The chip form, for anywhere a full line will not fit: "20mm F2 FC".
    val shortName: String
        get() = L10n.tr("%dmm %s %s", edgeMM, fingers.shortName, position.shortName)

    /// VoiceOver: commas, not middle dots, so it is read as a sentence rather than
    /// spelled out as punctuation.
    val spoken: String
        get() = L10n.tr("%d mm edge, %s, %s", edgeMM, fingers.name, position.name.lowercase())

    /// FROZEN keys. Renaming one of these orphans every routine already on the user's phone.
    override fun toJson(): JsonElement = JsonObject(
        mapOf(
            "edgeMM" to JsonPrimitive(edgeMM),
            "fingers" to JsonPrimitive(fingers.token),
            "position" to JsonPrimitive(position.rawValue),
        )
    )

    override fun equals(other: Any?): Boolean = other is GripSpec &&
        edgeMM == other.edgeMM && fingers == other.fingers && position == other.position

    override fun hashCode(): Int =
        (edgeMM * 31 + fingers.rawValue) * 31 + position.rawValue.hashCode()

    override fun toString(): String = "GripSpec($key)"

    companion object {
        /// Decode clamp. An edge outside this is a message from another build, not a real
        /// hold — 100 mm is already a jug and 0 mm is not a thing you can stand on.
        val edgeRange = 1..100

        fun fromJson(element: JsonElement?): GripSpec? = JsonRead.obj(element)?.let { fromJson(it) }

        /// The lenient decoder. A blob written before the pinch rule existed self-heals on
        /// read — which DOES change its `key` from "20|IM|pinch" to "20|IMT|pinch", so a
        /// max recorded against the thumbless form no longer joins. Acceptable only
        /// because `.pinch` is hours old and unshipped; a rule that re-keys shipped data
        /// needs a "v2:" namespace and a migration instead. Here the invariant needs no
        /// hand-written re-assertion at all: the constructor IS the only door.
        fun fromJson(o: JsonObject): GripSpec = GripSpec(
            edgeMM = edgeRange.clamping(o.intOr("edgeMM", 20)),
            fingers = o.valueOr("fingers", FingerSet.four) { FingerSet.fromJson(it) },
            position = o.valueOr("position", GripPosition.halfCrimp) { GripPosition.fromJson(it) },
        )
    }
}
