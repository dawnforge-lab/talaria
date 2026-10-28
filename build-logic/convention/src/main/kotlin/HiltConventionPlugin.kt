import lab.dawnforge.hermes.buildlogic.library
import lab.dawnforge.hermes.buildlogic.libs
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.apply
import org.gradle.kotlin.dsl.dependencies

/** Hilt through KSP. JVM modules get hilt-core; Android modules get the Hilt Gradle plugin too. */
abstract class HiltConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) {
        with(target) {
            apply(plugin = "com.google.devtools.ksp")

            dependencies {
                "ksp"(libs.library("hilt.compiler"))
                // Lets Dagger read metadata written by this Kotlin version.
                "ksp"(libs.library("kotlin.metadata"))
            }

            pluginManager.withPlugin("org.jetbrains.kotlin.jvm") {
                dependencies {
                    "implementation"(libs.library("hilt.core"))
                }
            }

            pluginManager.withPlugin("com.android.base") {
                apply(plugin = "dagger.hilt.android.plugin")
                dependencies {
                    "implementation"(libs.library("hilt.android"))
                }
            }
        }
    }
}
