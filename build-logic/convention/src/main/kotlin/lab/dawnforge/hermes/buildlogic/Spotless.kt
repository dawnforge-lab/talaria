package lab.dawnforge.hermes.buildlogic

import com.diffplug.gradle.spotless.SpotlessExtension
import org.gradle.api.Project
import org.gradle.kotlin.dsl.apply
import org.gradle.kotlin.dsl.configure

/**
 * Trailing commas keep diffs to the line that changed. ktlint picks up the code style from the
 * root .editorconfig but not these two keys when run through Spotless, so they are passed here
 * as well (the root build.gradle.kts repeats them for build-logic's own sources).
 */
private val ktlintOverrides = mapOf(
    "ij_kotlin_allow_trailing_comma" to true,
    "ij_kotlin_allow_trailing_comma_on_call_site" to true,
)

/** ktlint through Spotless, with the rules from the root .editorconfig so the IDE and CI agree. */
internal fun Project.configureSpotless() {
    apply(plugin = "com.diffplug.spotless")
    val ktlintVersion = libs.findVersion("ktlint").get().requiredVersion
    val editorConfig = rootDir.resolve(".editorconfig")
    extensions.configure<SpotlessExtension> {
        kotlin {
            target("src/**/*.kt")
            ktlint(ktlintVersion).setEditorConfigPath(editorConfig).editorConfigOverride(ktlintOverrides)
            endWithNewline()
        }
        kotlinGradle {
            target("*.kts")
            ktlint(ktlintVersion).setEditorConfigPath(editorConfig).editorConfigOverride(ktlintOverrides)
            endWithNewline()
        }
    }
}
