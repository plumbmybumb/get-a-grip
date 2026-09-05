// SPDX-License-Identifier: MPL-2.0
// Original contributions Copyright 2026 Nuri Bruner.

package run.nuri.getagrip.engine

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import java.io.File

/// Loader for the shared fixtures at the repository root (`Fixtures/`). The path
/// arrives as a system property from `engine/build.gradle.kts`, so no test ever guesses
/// a relative path from the working directory.
object Fixtures {
    val root: File by lazy {
        val path = System.getProperty("getagrip.fixtures")
            ?: error("getagrip.fixtures system property not set — run through Gradle")
        File(path).also { require(it.isDirectory) { "Fixtures directory missing at $it" } }
    }

    fun text(relative: String): String = File(root, relative).readText()

    fun load(relative: String): JsonElement = Json.parseToJsonElement(text(relative))

    fun files(directory: String, suffix: String = ".json"): List<File> =
        File(root, directory).listFiles()?.filter { it.name.endsWith(suffix) }?.sortedBy { it.name } ?: emptyList()
}
