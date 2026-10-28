plugins {
    alias(libs.plugins.hermes.android.library)
    alias(libs.plugins.hermes.hilt)
}

android {
    namespace = "lab.dawnforge.hermes.core.voice"
}

// The phone's side of voice: the microphone, playback, its own speech engines, and the dictation
// and read-aloud that use them with Hermes' speech.
dependencies {
    api(projects.core.data)
    implementation(projects.core.chat)

    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.androidx.core.ktx)

    testImplementation(libs.kotlinx.coroutines.test)
}
