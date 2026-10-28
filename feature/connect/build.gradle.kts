plugins {
    alias(libs.plugins.hermes.android.feature)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "lab.dawnforge.hermes.feature.connect"
}

dependencies {
    implementation(projects.core.data)

    implementation(libs.androidx.browser)

    implementation(libs.androidx.biometric)
    implementation(libs.androidx.webkit)
    implementation(libs.androidx.compose.material.icons.core)
    implementation(libs.androidx.camera.camera2)
    implementation(libs.androidx.camera.lifecycle)
    implementation(libs.androidx.camera.view)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.zxing.core)
}
