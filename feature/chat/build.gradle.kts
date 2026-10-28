plugins {
    alias(libs.plugins.hermes.android.feature)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "lab.dawnforge.hermes.feature.chat"
}

dependencies {
    implementation(projects.core.data)

    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.material.icons.core)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.markdown.renderer.m3)
    implementation(libs.markdown.renderer.code)
    // Map cards; drawn only when the build has a Maps key (hermes.mapsApiKey in local.properties).
    implementation(libs.maps.compose)
    // Dictation into the composer, and replies read aloud.
    implementation(projects.core.voice)
    implementation(libs.okhttp)
}
