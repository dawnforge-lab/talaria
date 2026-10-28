import lab.dawnforge.hermes.buildlogic.configureKotlinJvm
import lab.dawnforge.hermes.buildlogic.configureSpotless
import lab.dawnforge.hermes.buildlogic.library
import lab.dawnforge.hermes.buildlogic.libs
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.apply
import org.gradle.kotlin.dsl.dependencies

abstract class JvmLibraryConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) {
        with(target) {
            apply(plugin = "org.jetbrains.kotlin.jvm")
            apply(plugin = "hermes.android.lint")

            configureKotlinJvm()
            configureSpotless()
            dependencies {
                "testImplementation"(libs.library("kotlin.test"))
                "testImplementation"(libs.library("junit4"))
            }
        }
    }
}
