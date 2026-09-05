// SPDX-License-Identifier: MPL-2.0
// Original contributions Copyright 2026 Nuri Bruner.

package run.nuri.getagrip.l10n

import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import java.io.File
import java.util.Locale
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/// The generator's OUTPUT, checked rather than its internals.
///
/// `xcstrings_to_android.py` is a Python script this test suite cannot import, and porting
/// its slugging and specifier rules to a Kotlin twin would just be a second implementation
/// to keep in step. What actually has to hold is a property of the files it writes, and
/// every one of these is a failure that would otherwise reach the phone silently:
///
/// * a key in `STRING_KEYS` whose resource does not exist — a sentence that falls back to
///   its English self in both languages, and looks like a translation that was never done;
/// * a French string whose format specifiers differ from the English — `String.format`
///   either throws at the moment the sentence is drawn or, worse, prints the arguments in
///   the wrong slots;
/// * an unescaped apostrophe, which aapt2 either rejects or silently eats.
@RunWith(RobolectricTestRunner::class)
class StringCatalogTests {

    private val res = File(
        System.getProperty("getagrip.res")
            ?: error("getagrip.res is not set — see app/build.gradle.kts"),
    )

    private val english = File(res, "values/strings.xml").readText()
    private val french = File(res, "values-fr/strings.xml").readText()
    private val englishExtra = File(res, "values/strings_android.xml").readText()
    private val frenchExtra = File(res, "values-fr/strings_android.xml").readText()

    // MARK: - Every key resolves

    /// Every lookup key has a resource behind it, in BOTH languages.
    ///
    /// This is the whole contract of `tr(…)`: the English sentence is the key, and a key
    /// with nothing behind it silently renders the key — which reads as English text in a
    /// French app, and as correct text in an English one, so only a test catches it.
    @Test
    fun everyStringKeyResolvesInBothLanguages() {
        val context = RuntimeEnvironment.getApplication()
        val missing = mutableListOf<String>()
        for ((key, id) in STRING_KEYS) {
            val value = runCatching { context.resources.getString(id) }.getOrNull()
            if (value == null) missing += key
        }
        assertTrue(missing.isEmpty(), "no resource behind: $missing")
        assertTrue(STRING_KEYS.size > 700, "expected the whole catalog, got ${STRING_KEYS.size}")
    }

    /// Same for the counted ones, which resolve through `getQuantityString`.
    @Test
    fun everyPluralKeyResolves() {
        val context = RuntimeEnvironment.getApplication()
        for ((key, id) in PLURAL_KEYS) {
            for (quantity in listOf(0, 1, 2, 5)) {
                val value = runCatching {
                    context.resources.getQuantityString(id, quantity, quantity)
                }.getOrNull()
                assertTrue(value != null, "no plural behind '$key' at $quantity")
            }
        }
        assertEquals(10, PLURAL_KEYS.values.toSet().size, "the catalog's ten counted strings")
    }

    /// A key that had to be POSITIONED for French is reachable under BOTH spellings — the
    /// positioned one and the unpositioned alias `:engine` writes, since `L10n.tr` is
    /// handed `"%d mm · %s · %s"` and Kotlin would read a bare `$d` as a template.
    @Test
    fun theUnpositionedAliasResolvesToTheSamePositionedResource() {
        val positioned = "%1\$d mm · %2\$s · %3\$s"
        val unpositioned = "%d mm · %s · %s"
        assertTrue(positioned in STRING_KEYS, "the positioned key is missing")
        assertTrue(unpositioned in STRING_KEYS, "the engine's unpositioned key is missing")
        assertEquals(STRING_KEYS[positioned], STRING_KEYS[unpositioned])

        val context = RuntimeEnvironment.getApplication()
        val template = context.resources.getString(STRING_KEYS.getValue(unpositioned))
        assertTrue(
            "%1\$d" in template,
            "the stored value must be positioned whatever the key was: $template",
        )
    }

    // MARK: - Specifiers

    /// **The French takes the same arguments, in the same slots.**
    ///
    /// Resources are read under a French configuration so this compares what the app will
    /// actually format, not what the file says.
    @Test
    fun frenchFormatStringsHaveTheSameSpecifiersAsTheirEnglishTwin() {
        val english = STRING_KEYS.values.associateWith {
            RuntimeEnvironment.getApplication().resources.getString(it)
        }
        // `setQualifiers`, not a context built from a French Configuration: Robolectric
        // resolves resources through the ENVIRONMENT's qualifiers, and a copied
        // configuration still reads `values/`.
        RuntimeEnvironment.setQualifiers("fr")
        val fr = RuntimeEnvironment.getApplication()
        val wrong = mutableListOf<String>()
        var translated = 0
        for ((key, id) in STRING_KEYS) {
            val en = english.getValue(id)
            val french = fr.resources.getString(id)
            if (french == en) continue // untranslated by design (a proper noun, a unit)
            translated += 1
            if (slots(en) != slots(french)) wrong += "$key\n    en ${slots(en)}  fr ${slots(french)}"
        }
        RuntimeEnvironment.setQualifiers("en")
        assertTrue(wrong.isEmpty(), "French takes different arguments:\n" + wrong.joinToString("\n"))
        // Belt and braces: if `values-fr` ever stopped being loaded, every string would
        // compare equal and the loop above would pass by checking nothing.
        assertTrue(translated > 500, "only $translated strings differ from English")
    }

    /// The same, for the counted ones — every quantity form of a plural.
    @Test
    fun frenchPluralsHaveTheSameSpecifiersAsTheirEnglishTwin() {
        val english = PLURAL_KEYS.values.associateWith {
            RuntimeEnvironment.getApplication().resources.getQuantityText(it, 2).toString()
        }
        RuntimeEnvironment.setQualifiers("fr")
        val fr = RuntimeEnvironment.getApplication()
        for ((key, id) in PLURAL_KEYS) {
            val en = english.getValue(id)
            for (quantity in listOf(0, 1, 2, 5)) {
                val translated = fr.resources.getQuantityText(id, quantity).toString()
                assertEquals(slots(en), slots(translated), "'$key' at quantity $quantity")
            }
        }
        RuntimeEnvironment.setQualifiers("en")
    }

    /// A specifier the app never passes an argument for is a crash at the moment the
    /// sentence is drawn. Formatting every English string with plausible arguments is the
    /// cheapest proof that none of them is malformed.
    @Test
    fun everyEnglishStringFormatsWithoutThrowing() {
        val context = RuntimeEnvironment.getApplication()
        for ((key, id) in STRING_KEYS) {
            val template = context.resources.getString(id)
            val args: Array<Any> = slots(template).map<String, Any> { if (it == "d") 1 else "x" }.toTypedArray()
            if (args.isEmpty()) continue
            runCatching { String.format(Locale.US, template, *args) }
                .onFailure { error("'$key' does not format: ${it.message}") }
        }
    }

    // MARK: - Escaping

    /// Apostrophes and the whitespace that carries meaning.
    ///
    /// aapt2 trims the ends of an unquoted value and collapses runs inside it, so
    /// `"%@ kg  →  %@ kg"` loses the spacing that IS the layout unless the whole value is
    /// quoted; and a bare `'` is either an error or a silently dropped character. Read as
    /// text, because both are invisible once the resource is decoded.
    @Test
    fun noUnescapedApostrophesAndNoLostWhitespace() {
        for ((name, xml) in listOf(
            "values/strings.xml" to english,
            "values-fr/strings.xml" to french,
            "values/strings_android.xml" to englishExtra,
            "values-fr/strings_android.xml" to frenchExtra,
        )) {
            for (value in values(xml)) {
                val quoted = value.startsWith("\"") && value.endsWith("\"")
                val body = if (quoted) value.substring(1, value.length - 1) else value
                for ((index, c) in body.withIndex()) {
                    if (c != '\'') continue
                    val escaped = index > 0 && body[index - 1] == '\\'
                    assertTrue(escaped, "$name: unescaped apostrophe in <$value>")
                }
                if (!quoted) {
                    assertEquals(body.trim(), body, "$name: <$value> would be trimmed by aapt2")
                    assertTrue("  " !in body, "$name: <$value> would lose a double space")
                }
            }
        }
    }

    /// The header says the file is generated and how to regenerate it, because the first
    /// instinct on seeing a wrong string is to fix it where it is drawn.
    @Test
    fun everyGeneratedFileSaysItIsGenerated() {
        for (xml in listOf(english, french, englishExtra, frenchExtra)) {
            assertTrue("GENERATED — do not edit" in xml)
            assertTrue("xcstrings_to_android.py" in xml)
        }
    }

    // MARK: - Helpers

    /// The argument sequence a format string consumes: `["d", "s"]`. Positional
    /// specifiers are placed at their own index, so a reordering translation compares
    /// unequal — which is exactly the failure this is looking for.
    private fun slots(text: String): List<String> {
        val spec = Regex("""%(?:(\d+)\$)?(?:\.\d+)?([dsf%])""")
        val ordered = mutableListOf<String>()
        val positioned = sortedMapOf<Int, String>()
        for (match in spec.findAll(text)) {
            val conversion = match.groupValues[2]
            if (conversion == "%") continue
            val at = match.groupValues[1]
            if (at.isEmpty()) ordered += conversion else positioned[at.toInt()] = conversion
        }
        return if (positioned.isEmpty()) ordered else positioned.values.toList() + ordered
    }

    private fun values(xml: String): List<String> =
        Regex("""<(?:string|item)[^>]*>(.*?)</(?:string|item)>""", RegexOption.DOT_MATCHES_ALL)
            .findAll(xml)
            .map { it.groupValues[1] }
            .toList()
}
