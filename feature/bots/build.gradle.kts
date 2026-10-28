plugins {
    alias(libs.plugins.hermes.android.feature)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "lab.dawnforge.hermes.feature.bots"
}

// Bots: Hermes profiles with their own look, personality and one ongoing chat.
dependencies {
    implementation(projects.core.data)

    implementation(libs.androidx.compose.material.icons.core)
    implementation(libs.kotlinx.serialization.json)
}
