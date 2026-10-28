plugins {
    alias(libs.plugins.hermes.android.library)
    alias(libs.plugins.hermes.android.library.compose)
}

android {
    namespace = "lab.dawnforge.hermes.core.ui"
}

dependencies {
    api(projects.core.net)
    api(projects.core.gateway)
    implementation(projects.core.designsystem)
}
