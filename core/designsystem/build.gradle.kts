plugins {
    alias(libs.plugins.hermes.android.library)
    alias(libs.plugins.hermes.android.library.compose)
}

android {
    namespace = "lab.dawnforge.hermes.core.designsystem"
}

dependencies {
    api(projects.core.model)

    api(libs.androidx.compose.material3)
    api(libs.androidx.compose.runtime)
    api(libs.androidx.compose.ui)
}
