// SPDX-License-Identifier: MPL-2.0
// Original contributions Copyright 2026 Nuri Bruner.

package run.nuri.getagrip.l10n

import android.content.Context
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import run.nuri.getagrip.engine.FingerSet
import run.nuri.getagrip.engine.GripPosition
import run.nuri.getagrip.engine.GripSpec
import run.nuri.getagrip.engine.L10n
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/// The seam between `:engine` and the app's string resources.
///
/// `:engine` is pure Kotlin and owns no resources; `GetAGripApplication` installs a lookup
/// that answers its keys out of `STRING_KEYS`. These tests drive that lookup directly
/// against a French `Resources`, because the failure it guards is invisible in English:
/// with no lookup at all `L10n.tr` returns its key, which IS the English sentence, so an
/// English build looks perfect while a French one is entirely untranslated.
@RunWith(RobolectricTestRunner::class)
class L10nWiringTests {

    @After
    fun tearDown() {
        L10n.lookup = null
        AppResources.current = null
        RuntimeEnvironment.setQualifiers("en")
    }

    /// **`setQualifiers`, not `createConfigurationContext`.** Robolectric resolves
    /// resources through the qualifiers of the environment it built, so a context made from
    /// a French `Configuration` still reads `values/`. Switching the environment is what
    /// actually loads `values-fr/`, and `tearDown` puts it back.
    private fun french(): Context {
        RuntimeEnvironment.setQualifiers("fr")
        return RuntimeEnvironment.getApplication()
    }

    /// The wiring `GetAGripApplication.installStringLookup` performs, verbatim.
    private fun install(context: Context) {
        L10n.lookup = { key -> STRING_KEYS[key]?.let { context.resources.getString(it) } }
        AppResources.current = context.resources
    }

    /// A plain key comes back in French.
    @Test
    fun theLookupReturnsFrenchForAKnownKey() {
        install(french())
        assertEquals("Tire maintenant", L10n.tr("Pull now"))
    }

    /// **The lookup returns the TEMPLATE, not a formatted string.** `getString(id)` with no
    /// arguments deliberately does not format, so `L10n.tr(key, args)` can. Getting this
    /// backwards would double-format and throw on the second pass.
    @Test
    fun theLookupReturnsTheTemplateAndL10nFormatsIt() {
        install(french())
        assertEquals("%1\$d sur %2\$d", rawTemplate("%d of %d"))
        assertEquals("3 sur 6", L10n.tr("%d of %d", 3, 6))
    }

    private fun rawTemplate(key: String): String = L10n.lookup!!.invoke(key)!!

    /// A key the engine writes UNPOSITIONED resolves to the POSITIONED French resource and
    /// still formats correctly. This is why the generator emits both spellings: Kotlin
    /// reads a bare `$d` inside a string literal as a template, so `:engine` cannot write
    /// the positioned form.
    @Test
    fun anUnpositionedEngineKeyFormatsThroughThePositionedFrenchResource() {
        install(french())
        val grip = GripSpec(edgeMM = 20, fingers = FingerSet.four, position = GripPosition.halfCrimp)
        // `GripSpec.displayName` is `L10n.tr("%d mm · %s · %s", …)` inside the engine.
        assertEquals("20 mm · 4 doigts · Semi-arquée", grip.displayName)
    }

    /// With no lookup installed — a JVM test, or before `Application.onCreate` — the engine
    /// falls back to the English key. That is the right fallback and the reason a missing
    /// resource is invisible without `StringCatalogTests`.
    @Test
    fun withNoLookupTheEngineFallsBackToTheEnglishKey() {
        L10n.lookup = null
        assertEquals("Pull now", L10n.tr("Pull now"))
        assertEquals("3 of 6", L10n.tr("%d of %d", 3, 6))
    }

    /// English still resolves, and through the SAME map — a lookup that only worked for the
    /// translated language would be a fallback dressed as a feature.
    @Test
    fun theLookupReturnsEnglishUnderAnEnglishLocale() {
        install(RuntimeEnvironment.getApplication())
        assertEquals("Pull now", L10n.tr("Pull now"))
        assertEquals("3 of 6", L10n.tr("%d of %d", 3, 6))
    }

    /// A counted string picks the French plural form. `L10n` cannot do this — it is handed
    /// a key and no quantity — which is the whole reason `trQuantity` exists.
    @Test
    fun trQuantityPicksTheFrenchPluralForm() {
        AppResources.current = french().resources
        assertEquals("1 minute", trQuantity("%d minutes", 1))
        assertEquals("12 minutes", trQuantity("%d minutes", 12))
    }

    /// And degrades to the English key when there are no resources at all.
    @Test
    fun trQuantityFallsBackToTheKey() {
        AppResources.current = null
        assertEquals("12 minutes", trQuantity("%d minutes", 12))
    }

    /// **Nothing on the wire goes through the lookup.** A French phone must produce the
    /// same grip key, the same max-table key and the same share payload as an English one,
    /// or two people cannot swap a routine and a synced record stops matching itself.
    @Test
    fun wireKeysAreUntouchedByTheLocale() {
        install(french())
        val grip = GripSpec(edgeMM = 20, fingers = FingerSet.four, position = GripPosition.halfCrimp)
        assertEquals("20|IMRL|halfCrimp", grip.key)
        assertTrue(grip.displayName != grip.key)
    }
}
