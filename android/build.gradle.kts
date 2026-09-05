// Root build: every plugin is declared here with `apply false` so each module picks
// exactly what it needs and the versions live in ONE place (gradle/libs.versions.toml).
plugins {
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.android.application) apply false
}
