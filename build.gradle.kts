plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.android.lint) apply false
    alias(libs.plugins.compose) apply false
    alias(libs.plugins.hilt) apply false
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.ksp) apply false
    alias(libs.plugins.room) apply false
    alias(libs.plugins.spotless)
}

// Formats the Gradle scripts and the build-logic sources that no module owns.
// Keep the overrides in step with build-logic's Spotless.kt.
spotless {
    val editorConfig = rootDir.resolve(".editorconfig")
    val ktlintOverrides = mapOf(
        "ij_kotlin_allow_trailing_comma" to true,
        "ij_kotlin_allow_trailing_comma_on_call_site" to true,
    )
    kotlin {
        target("build-logic/convention/src/**/*.kt")
        ktlint(libs.versions.ktlint.get()).setEditorConfigPath(editorConfig).editorConfigOverride(ktlintOverrides)
        endWithNewline()
    }
    kotlinGradle {
        target("*.kts", "build-logic/*.kts", "build-logic/convention/*.kts")
        ktlint(libs.versions.ktlint.get()).setEditorConfigPath(editorConfig).editorConfigOverride(ktlintOverrides)
        endWithNewline()
    }
}
