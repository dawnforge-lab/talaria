import com.android.build.api.dsl.LibraryExtension
import lab.dawnforge.hermes.buildlogic.AndroidSdk
import lab.dawnforge.hermes.buildlogic.configureKotlinAndroid
import lab.dawnforge.hermes.buildlogic.configureSpotless
import lab.dawnforge.hermes.buildlogic.library
import lab.dawnforge.hermes.buildlogic.libs
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.apply
import org.gradle.kotlin.dsl.configure
import org.gradle.kotlin.dsl.dependencies

abstract class AndroidLibraryConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) {
        with(target) {
            apply(plugin = "com.android.library")
            apply(plugin = "hermes.android.lint")

            extensions.configure<LibraryExtension> {
                configureKotlinAndroid(this)
                testOptions.targetSdk = AndroidSdk.TARGET
                lint.targetSdk = AndroidSdk.TARGET
                defaultConfig.testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
                testOptions.animationsDisabled = true
                // Resources in ":core:designsystem" must be prefixed "core_designsystem_", so names never clash.
                resourcePrefix = path.split("""\W""".toRegex()).drop(1).distinct()
                    .joinToString(separator = "_").lowercase() + "_"
            }
            configureSpotless()
            dependencies {
                // AGP's built-in Kotlin does not pick kotlin-test's JUnit binding by itself.
                "testImplementation"(libs.library("kotlin.test.junit"))
                "testImplementation"(libs.library("junit4"))
            }
        }
    }
}
