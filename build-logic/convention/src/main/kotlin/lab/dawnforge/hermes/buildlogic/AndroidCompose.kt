package lab.dawnforge.hermes.buildlogic

import com.android.build.api.dsl.CommonExtension
import org.gradle.api.Project
import org.gradle.kotlin.dsl.dependencies

/** Turns on Compose and adds the BOM-aligned tooling every Compose module needs. */
internal fun Project.configureAndroidCompose(commonExtension: CommonExtension) {
    commonExtension.apply {
        buildFeatures.apply {
            compose = true
        }
    }

    dependencies {
        val bom = libs.library("androidx.compose.bom")
        "implementation"(platform(bom))
        "androidTestImplementation"(platform(bom))
        "implementation"(libs.library("androidx.compose.ui.tooling.preview"))
        "debugImplementation"(libs.library("androidx.compose.ui.tooling"))
    }
}
