// SPDX-License-Identifier: MPL-2.0
// Original contributions Copyright 2026 Nuri Bruner.

package run.nuri.getagrip.engine

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/// The export document, byte for byte, against what the REAL iOS formatter produced from
/// the same input (`Fixtures/export/<scenario>.json` → `<scenario>.md`, written by
/// `oracle export generate`).
///
/// A document is prose, so nothing about it is checkable by structure — a wrong tie-break
/// in a sort, a locale-formatted decimal, a rounding mode that is `HALF_UP` instead of
/// printf's ties-to-even, all read as perfectly plausible text. Only the bytes catch them,
/// which is why this comparison is the whole test.
class ExportFixtureTests {

    @Test
    fun everyScenarioProducesTheOraclesDocument() {
        val scenarios = Fixtures.files("export", ".json")
        assertTrue(scenarios.isNotEmpty(),
            "no export scenarios — run `oracle export scenarios && oracle export generate`")

        for (file in scenarios) {
            val name = file.name.removeSuffix(".json")
            val input = assertNotNull(AnalysisExport.Input.fromJson(Fixtures.load("export/$name.json")),
                "$name: the input must decode")
            AnalysisExport.CSVScope.entries.forEach { scope ->
                val selected = if (scope == AnalysisExport.CSVScope.workout) input.copy(sessions = input.sessions.take(1)) else input
                AnalysisExport.CSVDetail.entries.forEach { detail ->
                    assertEquals(Fixtures.text("export/$name.${scope.name}.${detail.name}.csv"), AnalysisExport.csv(selected, scope, detail).text,
                        "$name: ${scope.name}/${detail.name} CSV differs from iOS")
                }
            }
            val expected = Fixtures.text("export/$name.md")
            val actual = AnalysisExport.document(input)

            if (expected != actual) {
                // Name the FIRST differing line: a 900-line diff dumped whole says
                // nothing, and every failure here is one cell in one row.
                val a = expected.split("\n")
                val b = actual.split("\n")
                val index = (0 until maxOf(a.size, b.size))
                    .firstOrNull { a.getOrNull(it) != b.getOrNull(it) } ?: 0
                assertEquals(a.getOrNull(index), b.getOrNull(index),
                    "$name: line ${index + 1} of ${a.size} differs")
            }
            assertEquals(expected, actual, "$name: the document")
        }
    }

    /// The same purity the Swift side asserts: the formatter reads no clock and folds no
    /// map whose order is an accident, so two calls on one input are one document.
    @Test
    fun theDocumentIsAPureFunctionOfItsInput() {
        for (file in Fixtures.files("export", ".json")) {
            val name = file.name.removeSuffix(".json")
            val input = assertNotNull(AnalysisExport.Input.fromJson(Fixtures.load("export/$name.json")))
            assertEquals(AnalysisExport.document(input), AnalysisExport.document(input), name)
            // And the arrival order of the arrays must not show through — ordering is
            // derived from the data.
            val reversed = input.copy(sessions = input.sessions.reversed(),
                maxes = input.maxes.reversed())
            assertEquals(AnalysisExport.document(input), AnalysisExport.document(reversed),
                "$name: reversing the input arrays changed the document")
        }
    }
}
