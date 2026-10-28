plugins {
    alias(libs.plugins.hermes.android.feature)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "lab.dawnforge.hermes.feature.settings"
}

dependencies {
    implementation(projects.core.data)
    // The voice page: the phone's own speech, and hearing how replies sound.
    implementation(projects.core.voice)

    implementation(libs.health.connect)
    implementation(libs.androidx.compose.material.icons.core)
    implementation(libs.kotlinx.serialization.json)
}
