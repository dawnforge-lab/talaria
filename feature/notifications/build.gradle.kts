plugins {
    alias(libs.plugins.hermes.android.feature)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "lab.dawnforge.hermes.feature.notifications"
}

dependencies {
    implementation(projects.core.push)

    implementation(libs.androidx.compose.material.icons.core)
    implementation(libs.kotlinx.serialization.json)
}
