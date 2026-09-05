// SPDX-License-Identifier: MPL-2.0
// Original contributions Copyright 2026 Nuri Bruner.

import Foundation

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
struct FingerSet: OptionSet, Hashable, Sendable, Codable {
    let rawValue: Int

    /// Masks to `0b11111`, so an unknown bit cannot exist even if one arrives over
    /// CloudKit. That is what lets every other member of this type be total.
    init(rawValue: Int) { self.rawValue = rawValue & 0b11111 }

    static let index  = FingerSet(rawValue: 1 << 0)
    static let middle = FingerSet(rawValue: 1 << 1)
    static let ring   = FingerSet(rawValue: 1 << 2)
    static let little = FingerSet(rawValue: 1 << 3)
    /// Bit FOUR, letter LAST — and that placement is load-bearing: every thumbless
    /// token, and therefore every key ever written before the thumb existed, stays
    /// byte-identical. History cannot fork on an upgrade.
    static let thumb  = FingerSet(rawValue: 1 << 4)

    static let four:       FingerSet = [.index, .middle, .ring, .little]
    static let frontThree: FingerSet = [.index, .middle, .ring]
    static let backThree:  FingerSet = [.middle, .ring, .little]
    static let frontTwo:   FingerSet = [.index, .middle]
    static let middleTwo:  FingerSet = [.middle, .ring]
    static let backTwo:    FingerSet = [.ring, .little]

    /// Pip order — index to little, the order the four FINGERS are drawn and read.
    /// The thumb is deliberately not here: it draws as its own bar below the row.
    static let allFingers: [FingerSet] = [.index, .middle, .ring, .little]
    /// Token order — fingers first, thumb LAST. One letter per entry, read as a pair
    /// with `letters`.
    static let allDigits: [FingerSet] = [.index, .middle, .ring, .little, .thumb]
    static let letters = "IMRLT"

    /// THE WIRE FORMAT, and one third of the canonical key. Fixed order, one letter per
    /// finger present: "IMRL", "IMR", "IM", "MR", "RL". Never localized, never
    /// reordered — reordering forks one trend series into two.
    var token: String {
        var out = ""
        for (bit, letter) in zip(Self.allDigits, Self.letters) where contains(bit) {
            out.append(letter)
        }
        return out
    }

    /// TOTAL: unknown letters are ignored and empty input yields `.four`. Never fails,
    /// because the alternative is a decoder that throws away a whole routine over one
    /// stray character.
    init(token: String) {
        var parsed: FingerSet = []
        for character in token.uppercased() {
            guard let position = Self.letters.firstIndex(of: character) else { continue }
            parsed.insert(Self.allDigits[Self.letters.distance(from: Self.letters.startIndex,
                                                               to: position)])
        }
        self = parsed.isEmpty ? .four : parsed
    }

    /// Four flags, index to little — the FINGERS only, because the drawn row is four
    /// pips and the thumb is its own bar. The one input `FingerGlyph` and `FingerPips`
    /// read, so a drawn hand can never disagree with the token.
    var occupied: [Bool] { Self.allFingers.map { contains($0) } }

    var hasThumb: Bool { contains(.thumb) }

    /// Digits on the hold, thumb included.
    var count: Int { rawValue.nonzeroBitCount }

    /// The spoken name. Named combinations get climbing's own words; anything else
    /// gets a composite rather than a made-up label nobody uses at the board.
    var name: String {
        // The thumb reads as a suffix on whatever the fingers are doing — "Front 2 +
        // thumb" is how a climber says a three-point pinch. Alone, it is just "Thumb".
        if contains(.thumb) {
            let rest = subtracting(.thumb)
            return rest.isEmpty ? String(localized: "Thumb") : String(localized: "\(rest.name) + thumb")
        }
        return switch self {
        case .four:       String(localized: "4 fingers")
        case .frontThree: String(localized: "Front 3")
        case .backThree:  String(localized: "Back 3")
        case .frontTwo:   String(localized: "Front 2")
        case .middleTwo:  String(localized: "Middle 2")
        case .backTwo:    String(localized: "Back 2")
        case .index:      String(localized: "Index")
        case .middle:     String(localized: "Middle")
        case .ring:       String(localized: "Ring")
        case .little:     String(localized: "Little")
        default:          Self.composite(occupied: occupied)
        }
    }

    /// The chip/monogram form. Falls back to the token, which is always short enough.
    var shortName: String {
        if contains(.thumb) {
            let rest = subtracting(.thumb)
            return rest.isEmpty ? "T" : "\(rest.shortName)+T"
        }
        return switch self {
        case .four:       "4F"
        case .frontThree: "F3"
        case .backThree:  "B3"
        case .frontTwo:   "F2"
        case .middleTwo:  "M2"
        case .backTwo:    "B2"
        case .index:      "I"
        case .middle:     "M"
        case .ring:       "R"
        case .little:     "L"
        default:          token
        }
    }

    private static let singleNames = [String(localized: "Index"), String(localized: "Middle"),
                                       String(localized: "Ring"), String(localized: "Little")]

    /// "Index + ring" — first word capitalised, the rest lowercase, so it reads as one
    /// phrase rather than a row of proper nouns.
    private static func composite(occupied: [Bool]) -> String {
        let parts = zip(singleNames, occupied).filter(\.1).map(\.0)
        guard let first = parts.first else { return String(localized: "No fingers") }
        return ([first] + parts.dropFirst().map { $0.lowercased() }).joined(separator: " + ")
    }
}

// Codable is SINGLE-VALUE over `token`: the blob reads `"fingers":"IM"`, which is
// legible in a dump and survives any future change to the bit layout.
extension FingerSet {
    init(from decoder: Decoder) throws {
        self.init(token: try decoder.singleValueContainer().decode(String.self))
    }

    func encode(to encoder: Encoder) throws {
        var c = encoder.singleValueContainer()
        try c.encode(token)
    }
}

// MARK: - Position

/// How the hand is set on the edge. An EXTENSIBLE `RawRepresentable` struct, not an
/// enum: this vocabulary is genuinely open (sloper, pinch, monos), two devices sync the
/// same routine, and an enum would fall back on an unknown raw and then silently
/// rewrite the set the next time the user touched it.
struct GripPosition: RawRepresentable, Hashable, Sendable, Codable {
    let rawValue: String

    init(rawValue: String) { self.rawValue = rawValue }
    init(_ raw: String) { self.rawValue = raw }

    static let halfCrimp = GripPosition("halfCrimp")
    static let openHand  = GripPosition("openHand")
    static let fullCrimp = GripPosition("fullCrimp")
    static let drag      = GripPosition("drag")
    /// Thumb-opposition block work. Usually paired with `.thumb` in the finger set,
    /// but deliberately not enforced — the model records what you say you did.
    static let pinch     = GripPosition("pinch")
    /// An isometric curl starting in half crimp. Its own raw value keeps curl
    /// history and max targets separate without changing any existing grip keys.
    static let fingerCurl = GripPosition("fingerCurl")

    /// Chip order, most used first. An unknown position decoded from another build is
    /// NOT in here and still round-trips untouched — that is the point of the struct.
    static let known: [GripPosition] = [.halfCrimp, .openHand, .fullCrimp, .drag, .pinch, .fingerCurl]

    var name: String {
        switch self {
        case .halfCrimp: String(localized: "Half crimp")
        case .openHand:  String(localized: "Open")
        case .fullCrimp: String(localized: "Full crimp")
        case .drag:      String(localized: "Drag")
        case .pinch:     String(localized: "Pinch")
        case .fingerCurl: String(localized: "Finger curl")
        default:         rawValue
        }
    }

    var shortName: String {
        switch self {
        case .halfCrimp: String(localized: "HC")
        case .openHand:  String(localized: "OH")
        case .fullCrimp: String(localized: "FC")
        case .drag:      String(localized: "DR")
        case .pinch:     String(localized: "PN")
        case .fingerCurl: String(localized: "CURL")
        default:         rawValue.uppercased()
        }
    }

    /// 0 = fingers straight, 1 = fully closed. The ONE place a grip glyph's corner
    /// radius comes from, so every drawing of a grip in the app agrees. An unknown raw
    /// gets 0.5 — visibly neither extreme, which is the honest answer.
    var closure: Double {
        switch self {
        case .drag:      0.0
        case .openHand:  0.2
        // A pinch is squeezed, not curled — nearer open than crimp.
        case .pinch:     0.35
        case .halfCrimp, .fingerCurl: 0.65
        case .fullCrimp: 1.0
        default:         0.5
        }
    }
}

// Codable is SINGLE-VALUE over `rawValue`. Written out rather than left to the
// stdlib's RawRepresentable default, because that default THROWS on a raw
// `init(rawValue:)` rejects — and this type must never reject one.
extension GripPosition {
    init(from decoder: Decoder) throws {
        self.init(rawValue: try decoder.singleValueContainer().decode(String.self))
    }

    func encode(to encoder: Encoder) throws {
        var c = encoder.singleValueContainer()
        try c.encode(rawValue)
    }
}

// MARK: - The grip

/// A grip, as a VALUE. There is no Grip model, no library, no folder — a grip lives
/// inline on the set row that uses it, and two sets built independently with the same
/// three fields are automatically the same trend series.
struct GripSpec: Hashable, Sendable, Codable {
    /// WHOLE millimetres. Int, not Double, and that is load-bearing: `key` is a
    /// serialization, and Double formatting is locale-dependent — "20" vs "20.0" vs
    /// "20,0" would fork one trend series into three between a French phone and an
    /// American one.
    var edgeMM: Int = 20
    /// A PINCH ALWAYS INCLUDES THE THUMB (Nuri, 2026-08-04: "there's no world where you
    /// can pinch without the thumb"). It is not a preference — a pinch IS thumb
    /// opposition, so a pinch without one is not a grip anybody can perform, and the app
    /// must not be able to represent it. Enforced HERE rather than in the three screens
    /// that edit a grip, so no surface can produce one and no blob can decode into one.
    var fingers: FingerSet = .four {
        didSet { if position == .pinch { fingers.formUnion(.thumb) } }
    }
    var position: GripPosition = .halfCrimp {
        didSet { if position == .pinch { fingers.formUnion(.thumb) } }
    }

    /// Shadows the memberwise init, which would otherwise be the one door past the
    /// invariant: property observers do not fire during initialisation.
    init(edgeMM: Int = 20, fingers: FingerSet = .four,
         position: GripPosition = .halfCrimp) {
        self.edgeMM = edgeMM
        self.position = position
        self.fingers = position == .pinch ? fingers.union(.thumb) : fingers
    }

    /// Decode clamp. An edge outside this is a message from another build, not a real
    /// hold — 100 mm is already a jug and 0 mm is not a thing you can stand on.
    static let edgeRange = 1...100

    /// ### THE CANONICAL KEY — FROZEN FOREVER.
    /// `"<edgeMM>|<fingerToken>|<positionRaw>"` → "20|IMRL|halfCrimp", "20|IM|fullCrimp".
    /// The only thing joining a rep pulled in March to one pulled in December and to
    /// the `MaxRecord` that says what 25 % means for it. NEVER localize, reformat,
    /// reorder or pad. `|` is safe: no component can contain one — the edge is an Int,
    /// the token is drawn from "IMRL", and a position raw is an identifier.
    /// NEVER STORED — always computed, because a stored copy is a second source of
    /// truth that can disagree with the fields beside it.
    /// A fourth dimension takes a "v2:" namespace plus a migration, or lives outside
    /// the key.
    var key: String { "\(edgeMM)|\(fingers.token)|\(position.rawValue)" }

    /// Sentence case, for a set row inside running copy: "20 mm · 4 fingers · half crimp".
    /// It differs from `displayName` in the POSITION's case only — the finger names are
    /// labels ("Front 3", "4 fingers") whose capital is part of the name.
    var line: String { String(localized: "\(edgeMM) mm · \(fingers.name) · \(position.name.lowercased())") }

    /// Title case, for a heading or a picker: "20 mm · 4 fingers · Half crimp".
    var displayName: String { String(localized: "\(edgeMM) mm · \(fingers.name) · \(position.name)") }

    /// The chip form, for anywhere a full line will not fit: "20mm F2 FC".
    var shortName: String { String(localized: "\(edgeMM)mm \(fingers.shortName) \(position.shortName)") }

    /// VoiceOver: commas, not middle dots, so it is read as a sentence rather than
    /// spelled out as punctuation.
    var spoken: String { String(localized: "\(edgeMM) mm edge, \(fingers.name), \(position.name.lowercased())") }

    /// FROZEN. Renaming one of these orphans every routine already on the user's phone.
    enum CodingKeys: String, CodingKey {
        case edgeMM, fingers, position
    }
}

// The lenient decoder lives in an extension so the memberwise
// `GripSpec(edgeMM:fingers:position:)` survives — declaring `init(from:)` in the body
// would delete it, and the prefill is written in memberwise form.
extension GripSpec {
    init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        self.edgeMM = Self.edgeRange.clamping(c.value(.edgeMM, or: 20))
        self.fingers = c.value(.fingers, or: .four)
        self.position = c.value(.position, or: .halfCrimp)
        // Observers do not fire during init, so the pinch invariant is asserted by hand.
        // A blob written before the rule existed self-heals on read — which DOES change
        // its `key` from "20|IM|pinch" to "20|IMT|pinch", so a max recorded against the
        // thumbless form no longer joins. Acceptable only because `.pinch` is hours old
        // and unshipped; a rule that re-keys shipped data needs a "v2:" namespace and a
        // migration instead.
        if self.position == .pinch { self.fingers.formUnion(.thumb) }
    }
}
