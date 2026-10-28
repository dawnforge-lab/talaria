plugins {
    alias(libs.plugins.hermes.android.feature)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "lab.dawnforge.hermes.feature.sessions"
}

dependencies {
    implementation(projects.core.data)
    // The chat list offers notifications until they are set up for the profile.
    implementation(projects.core.push)

    implementation(libs.androidx.compose.material.icons.core)
    implementation(libs.kotlinx.serialization.json)
}
