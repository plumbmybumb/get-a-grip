#!/usr/bin/env python3
r"""Generate the Android string resources from the iOS string catalogs.

The iOS app is the source of truth for every word in both languages: three
`.xcstrings` catalogs, authored as JSON, whose KEY is the English source
sentence and whose `fr` unit is the French. This script translates that
inventory into `res/values/strings.xml`, `res/values-fr/strings.xml` and a
`STRING_KEYS` / `PLURAL_KEYS` map so the SAME English key resolves on Android:

    tr("Set %d of %d", set, total)        // the UI
    L10n.tr("%d mm · %s · %s", …)         // :engine, through L10n.lookup

so the two apps cannot say different things, and a French sentence is written
once. Android-only strings — the scanner, demo mode, the notification channel
names — have no iOS twin and live in `android_extra.json` beside this script;
they are emitted into `strings_android.xml` in both locales.

    python3 android/scripts/xcstrings_to_android.py

No arguments, no third-party dependencies, idempotent: running it twice
produces no diff. NEVER hand-edit the files it writes — the header of each one
says so, and an edit is lost on the next run.

What the translation has to get right:

* **Format specifiers.** Swift's `%lld` / `%@` / `%1$@` become Java's `%d` /
  `%s` / `%1$s`. A key with two or more UNPOSITIONED specifiers is POSITIONED
  in the emitted value (`%1$s · %2$s`), because French reorders and
  `String.format` would otherwise hand the second argument to the first slot.
  The key map carries BOTH forms — the positioned key and the unpositioned
  alias — because `:engine` is frozen and writes the unpositioned one.
* **Plurals.** A French `variations.plural` becomes an Android `<plurals>`.
  English has no variations in the catalog (the key IS the English), so its
  `one` and `other` are the same sentence — exactly what iOS renders today.
* **Escaping.** `'`, `"`, `\`, `&`, `<`, `>`, and any string whose whitespace
  matters (leading, trailing, or a double space) is quoted so aapt2 keeps it.
* **`translatable="false"`** for anything the catalog has no French for.

Deliberately NOT generated: `CFBundleDisplayName` / `CFBundleName` (bundle
metadata — Android's label is `app_name`, in `android_extra.json`), and
anything from `AnalysisExport`, which is English whatever the UI language and
therefore never reaches a catalog in the first place. `SKIP_KEYS` is the guard
if one ever does.
"""

from __future__ import annotations

import hashlib
import json
import os
import re
import subprocess
import sys

# ---------------------------------------------------------------- paths

HERE = os.path.dirname(os.path.abspath(__file__))
ANDROID = os.path.dirname(HERE)


def ios_root() -> str:
    """Where the iOS working tree is. Normally the parent of `android/`; from a
    git WORKTREE cut for one agent, `android/` is a copy and the catalogs are
    not beside it, so the main worktree (the git common dir's parent) is the
    fallback. An explicit path as argv[1] wins over both."""
    if len(sys.argv) > 1:
        return sys.argv[1]
    parent = os.path.dirname(ANDROID)
    if os.path.exists(os.path.join(parent, IOS_CATALOGS[0])):
        return parent
    common = subprocess.run(
        ["git", "-C", ANDROID, "rev-parse", "--path-format=absolute", "--git-common-dir"],
        capture_output=True,
        text=True,
    ).stdout.strip()
    if common:
        main_tree = os.path.dirname(common)
        if os.path.exists(os.path.join(main_tree, IOS_CATALOGS[0])):
            return main_tree
    raise SystemExit(
        f"cannot find {IOS_CATALOGS[0]} - pass the iOS working tree as the first argument"
    )

IOS_CATALOGS = [
    "Sources/Localizable.xcstrings",
    "Sources/InfoPlist.xcstrings",
    "Widget/Localizable.xcstrings",
]

RES = os.path.join(ANDROID, "app", "src", "main", "res")
KOTLIN = os.path.join(
    ANDROID, "app", "src", "main", "kotlin", "run", "nuri", "getagrip", "l10n"
)
EXTRA = os.path.join(HERE, "android_extra.json")

# Bundle metadata, not UI. The Android label is `app_name`.
SKIP_KEYS = {"CFBundleDisplayName", "CFBundleName"}

REGENERATE = "python3 android/scripts/xcstrings_to_android.py"

# ---------------------------------------------------------------- specifiers

# Swift/C format specifiers as the catalogs use them: an optional argument
# position, an optional precision, a conversion. `%%` is a literal percent in
# both Swift and Java and passes through untouched.
#
# **Flags and widths are deliberately NOT matched.** C allows a space flag, so a
# permissive pattern reads the French "% du max" as one `% d` specifier and the
# English "% of max" as none — the two then disagree about how many arguments
# the sentence takes, and the `%` gets doubled in one language only. Nothing in
# either catalog uses a flag or a width; if one ever does, add it here WITH the
# space flag still excluded.
SPEC = re.compile(r"%(?:(\d+)\$)?(?:\.(\d+))?(lld|llu|lu|ld|d|u|@|f|%)")

_CONVERSION = {
    "lld": "d",
    "llu": "d",
    "lu": "d",
    "ld": "d",
    "d": "d",
    "u": "d",
    "@": "s",
    "f": "f",
}


def specifiers(text: str) -> list[tuple[str, str, str]]:
    """Every specifier in `text`, `%%` included (as conversion `%`)."""
    return SPEC.findall(text)


def java_format(text: str, positional: bool) -> str:
    """Swift specifiers → Java. `positional` numbers the unpositioned ones.

    Numbering is by order of appearance, which is what iOS itself does with
    unpositioned specifiers — so a French translation that reorders arguments
    would already be broken on iOS, and one that does not is preserved here.
    """
    index = [0]

    def sub(match: re.Match) -> str:
        pos, precision, conversion = match.groups()
        if conversion == "%":
            return "%%"
        index[0] += 1
        slot = pos or (str(index[0]) if positional else "")
        return (
            "%"
            + (slot + "$" if slot else "")
            + ("." + precision if precision else "")
            + _CONVERSION[conversion]
        )

    return SPEC.sub(sub, text)


def wants_positions(text: str) -> bool:
    """True when two or more specifiers are unpositioned — French reorders."""
    found = specifiers(text)
    if any(pos for pos, *_ in found):
        return False  # already positional, leave the author's numbering alone
    return len([1 for *_, c in found if c != "%"]) >= 2


def has_specifier(text: str) -> bool:
    return any(c != "%" for *_, c in specifiers(text))


def shape(text: str) -> list[str]:
    """The argument sequence a string consumes — `['d', 's']` — with literal
    percents dropped. Two strings with the same shape take the same arguments
    in the same order."""
    return [_CONVERSION[c] for *_, c in specifiers(text) if c != "%"]


def translations(fr: dict | None) -> list[str]:
    if not fr:
        return []
    if "value" in fr:
        return [fr["value"]]
    return list(fr["plural"].values())


def escape_stray_percent(text: str) -> str:
    """`%` that is not part of a specifier, in a string that WILL be formatted.

    A string with no specifier is never handed to `String.format`, so its `%`
    stays literal (`% of max`). One that is formatted must double every stray
    percent or the formatter trips over it.

    **Runs on the SWIFT text, before the conversion.** `SPEC` is a Swift
    specifier regex and does not know `%s` or `%1$s`, so a pass over the
    converted string would read every `%s` it had just produced as a stray
    percent and double it.
    """
    if not has_specifier(text):
        return text
    out, last = [], 0
    for match in SPEC.finditer(text):
        out.append(text[last : match.start()].replace("%", "%%"))
        out.append(match.group(0))
        last = match.end()
    out.append(text[last:].replace("%", "%%"))
    return "".join(out)


# ---------------------------------------------------------------- slugs


def base_slug(key: str) -> str:
    body = re.sub(r"[^a-z0-9]+", "_", key.lower()).strip("_")
    body = re.sub(r"_+", "_", body)
    if not body:
        body = "x"
    # Long sentences make unreadable ids and R fields; the hash suffix below
    # keeps the truncated ones apart.
    if len(body) > 56:
        body = body[:56].rstrip("_")
    return "s_" + body


def digest(key: str) -> str:
    return hashlib.sha1(key.encode("utf-8")).hexdigest()[:6]


def slugs(keys: list[str]) -> dict[str, str]:
    """Stable resource names. Collisions get a hash suffix — ALL of them, so
    which key wins the bare slug never depends on iteration order."""
    counts: dict[str, int] = {}
    for key in keys:
        counts[base_slug(key)] = counts.get(base_slug(key), 0) + 1
    out = {}
    for key in keys:
        base = base_slug(key)
        out[key] = base if counts[base] == 1 else f"{base}_{digest(key)}"
    return out


# ---------------------------------------------------------------- xml


def xml_value(text: str) -> str:
    """One resource value, escaped for aapt2."""
    body = (
        text.replace("\\", "\\\\")
        .replace('"', '\\"')
        .replace("'", "\\'")
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\n", "\\n")
        .replace("\t", "\\t")
    )
    # aapt2 trims the ends and collapses whitespace runs unless the value is
    # quoted. `%@ kg  →  %@ kg` has two spaces that are the layout.
    if re.search(r"^\s|\s$|\s\s", text):
        return '"' + body + '"'
    if body[:1] in ("@", "?"):
        return "\\" + body
    return body


def xml_header(what: str) -> str:
    return (
        '<?xml version="1.0" encoding="utf-8"?>\n'
        "<!--\n"
        f"  GENERATED — do not edit. {what}\n"
        f"  Regenerate with: {REGENERATE}\n"
        "-->\n"
        "<resources>\n"
    )


# ---------------------------------------------------------------- kotlin


def kotlin_literal(text: str) -> str:
    return (
        '"'
        + text.replace("\\", "\\\\")
        .replace('"', '\\"')
        .replace("$", "\\$")
        .replace("\n", "\\n")
        .replace("\t", "\\t")
        + '"'
    )


# ---------------------------------------------------------------- load


def load_catalogs() -> dict[str, dict]:
    """The union of the three catalogs, keyed by the English source key.

    112 keys appear in two catalogs (the widget resolves against its own
    bundle, so anything the Live Activity renders is in both); their French is
    identical by rule, and `merge_conflict` fails loudly if that ever stops
    being true rather than silently picking one.
    """
    merged: dict[str, dict] = {}
    for relative in IOS_CATALOGS:
        path = os.path.join(ios_root(), relative)
        with open(path, encoding="utf-8") as handle:
            catalog = json.load(handle)
        for key, entry in catalog["strings"].items():
            if key in SKIP_KEYS:
                continue
            if key in merged and unit_of(merged[key]) != unit_of(entry):
                raise SystemExit(
                    f"{relative}: '{key}' disagrees with an earlier catalog"
                )
            merged.setdefault(key, entry)
    return merged


def unit_of(entry: dict) -> str:
    """A comparable fingerprint of what an entry says, for the merge check."""
    return json.dumps(entry.get("localizations", {}), sort_keys=True, ensure_ascii=False)


def english(key: str, entry: dict) -> str:
    """The English text. Normally the key itself — the one exception is a key
    that is an identifier (`runner.rest.badge.next`), where the catalog carries
    an explicit `en` unit because French needs two different words for it."""
    unit = entry.get("localizations", {}).get("en", {}).get("stringUnit")
    return unit["value"] if unit else key


def french(entry: dict) -> dict | None:
    """`{"value": …}` for a plain unit, `{"plural": {...}}` for variations."""
    loc = entry.get("localizations", {}).get("fr")
    if not loc:
        return None
    if "stringUnit" in loc:
        return {"value": loc["stringUnit"]["value"]}
    plural = loc.get("variations", {}).get("plural")
    if plural:
        return {
            "plural": {
                q: plural[q]["stringUnit"]["value"] for q in plural if "stringUnit" in plural[q]
            }
        }
    return None


def load_extra() -> list[dict]:
    if not os.path.exists(EXTRA):
        return []
    with open(EXTRA, encoding="utf-8") as handle:
        return json.load(handle)["strings"]


# ---------------------------------------------------------------- generate


def main() -> int:
    catalog = load_catalogs()
    keys = sorted(catalog)

    names = slugs(keys)

    rows = []  # (name, key, en, fr, is_plural)
    for key in keys:
        entry = catalog[key]
        en = english(key, entry)
        fr = french(entry)
        rows.append((names[key], key, en, fr))

    # ------------------------------------------------ strings.xml (en / fr)
    lines_en, lines_fr = [], []
    plural_names: dict[str, str] = {}
    key_map: list[tuple[str, str]] = []  # (lookup key, resource name)
    plural_map: list[tuple[str, str]] = []

    mismatched: list[str] = []

    for name, key, en, fr in rows:
        positional = wants_positions(en)
        # The lookup key is the ENGLISH text with Java specifiers. An identifier
        # key (the `runner.rest.badge.next` exception) has no specifiers, so the
        # conversion below is a no-op on it.
        java_key = java_format(key, positional)
        alias = java_format(key, False)

        is_plural = bool(fr and "plural" in fr)
        value_en = java_format(escape_stray_percent(en), positional)

        # **The French must take the same arguments in the same order.** With
        # unpositioned specifiers iOS itself feeds them in order of appearance,
        # so a translation that dropped, added or reordered one is a bug in the
        # catalog — and here it would be a silently wrong number, or a crash in
        # `String.format`. Fail the generation instead.
        for text in translations(fr):
            if shape(text) != shape(en):
                mismatched.append(f"  {key!r}\n    en {shape(en)}  fr {shape(text)}")

        if is_plural:
            plural_names[key] = name
            # English has no variations in the catalog: iOS renders the key for
            # every count, so both quantities carry the same sentence.
            lines_en.append(f"    <plurals name={quote(name)}>")
            for quantity in ("one", "other"):
                lines_en.append(
                    f"        <item quantity=\"{quantity}\">{xml_value(value_en)}</item>"
                )
            lines_en.append("    </plurals>")
            lines_fr.append(f"    <plurals name={quote(name)}>")
            for quantity in ("zero", "one", "two", "few", "many", "other"):
                text = fr["plural"].get(quantity)
                if text is None:
                    continue
                value = java_format(escape_stray_percent(text), positional)
                lines_fr.append(
                    f"        <item quantity=\"{quantity}\">{xml_value(value)}</item>"
                )
            lines_fr.append("    </plurals>")
            plural_map.append((java_key, name))
            if alias != java_key:
                plural_map.append((alias, name))
        else:
            attr = "" if fr else ' translatable="false"'
            lines_en.append(
                f"    <string name={quote(name)}{attr}>{xml_value(value_en)}</string>"
            )
            if fr:
                value_fr = java_format(escape_stray_percent(fr["value"]), positional)
                lines_fr.append(
                    f"    <string name={quote(name)}>{xml_value(value_fr)}</string>"
                )
            key_map.append((java_key, name))
            if alias != java_key:
                key_map.append((alias, name))

    # ------------------------------------------------ android-only
    extra = load_extra()
    # **An Android-only string may never shadow a catalog key.** Two resources under one
    # lookup key means whichever the map wrote last wins, and the losing screen quietly
    # stops matching iOS — which is the exact drift this whole pipeline exists to prevent.
    catalog_keys = {k for k, _ in key_map} | {k for k, _ in plural_map}
    shadowed = [
        item["en"]
        for item in extra
        if item.get("inKeyMap") is not False and java_format(item["en"], False) in catalog_keys
    ]
    if shadowed:
        raise SystemExit(
            "android_extra.json shadows keys the iOS catalogs already carry "
            "(delete them there, or change the English):\n  "
            + "\n  ".join(repr(s) for s in shadowed)
        )

    extra_en, extra_fr = [], []
    for item in extra:
        name = item.get("name") or slug_for_extra(item["en"], extra)
        en = item["en"]
        fr = item.get("fr")
        positional = wants_positions(en)
        attr = "" if fr else ' translatable="false"'
        if item.get("translatable") is False:
            attr = ' translatable="false"'
        value_en = java_format(escape_stray_percent(en), positional)
        extra_en.append(
            f"    <string name={quote(name)}{attr}>{xml_value(value_en)}</string>"
        )
        if fr and item.get("translatable") is not False:
            value_fr = java_format(escape_stray_percent(fr), positional)
            extra_fr.append(f"    <string name={quote(name)}>{xml_value(value_fr)}</string>")
        if item.get("inKeyMap") is False:
            continue
        java_key = java_format(en, positional)
        alias = java_format(en, False)
        key_map.append((java_key, name))
        if alias != java_key:
            key_map.append((alias, name))

    if mismatched:
        raise SystemExit(
            "the French takes different arguments from the English:\n"
            + "\n".join(mismatched)
        )

    write(
        os.path.join(RES, "values", "strings.xml"),
        xml_header("English, from the iOS string catalogs.") + "\n".join(lines_en) + "\n</resources>\n",
    )
    write(
        os.path.join(RES, "values-fr", "strings.xml"),
        xml_header("French, from the iOS string catalogs.") + "\n".join(lines_fr) + "\n</resources>\n",
    )
    write(
        os.path.join(RES, "values", "strings_android.xml"),
        xml_header("Android-only English, from android_extra.json.")
        + "\n".join(extra_en)
        + "\n</resources>\n",
    )
    write(
        os.path.join(RES, "values-fr", "strings_android.xml"),
        xml_header("Android-only French, from android_extra.json.")
        + "\n".join(extra_fr)
        + "\n</resources>\n",
    )

    write(os.path.join(KOTLIN, "StringKeys.kt"), kotlin_source(key_map, plural_map))

    print(
        f"{len(rows)} keys ({len(plural_names)} plurals) + {len(extra)} Android-only; "
        f"{len(key_map)} string lookups, {len(plural_map)} plural lookups"
    )
    return 0


def slug_for_extra(en: str, extra: list[dict]) -> str:
    """Android-only names never collide with the catalog's: they carry an
    `a_` prefix instead of `s_`."""
    names = [item["en"] for item in extra if not item.get("name")]
    counts = {}
    for text in names:
        counts[base_slug(text)] = counts.get(base_slug(text), 0) + 1
    base = base_slug(en)
    name = base if counts[base] == 1 else f"{base}_{digest(en)}"
    return "a_" + name[2:]


def quote(name: str) -> str:
    return '"' + name + '"'


def kotlin_source(key_map: list[tuple[str, str]], plural_map: list[tuple[str, str]]) -> str:
    """`STRING_KEYS` and `PLURAL_KEYS`, chunked so no one method comes near
    the JVM's 64 KB limit."""
    head = f"""package run.nuri.getagrip.l10n

import run.nuri.getagrip.R

// GENERATED — do not edit.
// From the iOS string catalogs; regenerate with:
//   {REGENERATE}
//
// The KEY is the ENGLISH SENTENCE with Java format specifiers — the same key
// the iOS app passes to `String(localized:)` and the same one `:engine` passes
// to `L10n.tr`. Keys whose specifiers had to be POSITIONED for French are here
// twice: once positioned (`%1$s · %2$s`) and once under the unpositioned
// alias `:engine` writes, so both resolve to the one positioned resource.
"""
    body = [head]
    body.append(_map_source("STRING_KEYS", "string", key_map))
    body.append(_map_source("PLURAL_KEYS", "plurals", plural_map))
    return "\n".join(body)


def _map_source(name: str, kind: str, pairs: list[tuple[str, str]]) -> str:
    pairs = sorted(set(pairs))
    chunks = [pairs[i : i + 150] for i in range(0, len(pairs), 150)] or [[]]
    out = [f"val {name}: Map<String, Int> = buildMap({len(pairs)}) {{"]
    for index in range(len(chunks)):
        out.append(f"    {name.lower()}Chunk{index}(this)")
    out.append("}")
    out.append("")
    for index, chunk in enumerate(chunks):
        out.append(f"private fun {name.lower()}Chunk{index}(into: MutableMap<String, Int>) {{")
        for key, resource in chunk:
            out.append(f"    into[{kotlin_literal(key)}] = R.{kind}.{resource}")
        out.append("}")
        out.append("")
    return "\n".join(out)


def write(path: str, content: str) -> None:
    os.makedirs(os.path.dirname(path), exist_ok=True)
    if os.path.exists(path):
        with open(path, encoding="utf-8") as handle:
            if handle.read() == content:
                return
    with open(path, "w", encoding="utf-8") as handle:
        handle.write(content)


if __name__ == "__main__":
    sys.exit(main())
