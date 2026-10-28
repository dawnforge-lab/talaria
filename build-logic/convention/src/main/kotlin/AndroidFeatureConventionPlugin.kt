import lab.dawnforge.hermes.buildlogic.library
import lab.dawnforge.hermes.buildlogic.libs
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.apply
import org.gradle.kotlin.dsl.dependencies

/** A screen-level module: Compose UI, Hilt view models and navigation, on top of the design system. */
abstract class AndroidFeatureConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) {
        with(target) {
            apply(plugin = "hermes.android.library")
            apply(plugin = "hermes.android.library.compose")
            apply(plugin = "hermes.hilt")

            dependencies {
                "implementation"(project(":core:designsystem"))
                "implementation"(project(":core:model"))
                "implementation"(project(":core:ui"))

                "implementation"(libs.library("androidx.compose.material3"))
                "implementation"(libs.library("androidx.hilt.lifecycle.viewmodel.compose"))
                "implementation"(libs.library("androidx.lifecycle.runtime.compose"))
                "implementation"(libs.library("androidx.lifecycle.viewmodel.compose"))
                "implementation"(libs.library("androidx.navigation.compose"))

                "testImplementation"(libs.library("kotlinx.coroutines.test"))
                "testImplementation"(libs.library("turbine"))
            }
        }
    }
}
