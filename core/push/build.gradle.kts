plugins {
    alias(libs.plugins.hermes.android.library)
    alias(libs.plugins.hermes.hilt)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "lab.dawnforge.hermes.core.push"
}

dependencies {
    // Pairing goes through the signed-in dashboard; secrets use its cipher.
    api(projects.core.data)
    // A confirmation's push carries the first line of its Markdown preview.
    implementation(projects.core.chat)
    // A call from Hermes doesn't ring while a voice conversation is running.
    implementation(projects.core.voice)

    implementation(platform(libs.firebase.bom))
    implementation(libs.firebase.messaging)
    implementation(libs.firebase.installations)
    implementation(libs.androidx.core.ktx)
    // A call from Hermes rings as the phone's own calls do: Telecom's self-managed calls.
    implementation(libs.androidx.core.telecom)
    implementation(libs.androidx.datastore.preferences)
    // Answers from a notification go out as expedited work, which outlives the tap.
    implementation(libs.androidx.work.runtime)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.okhttp)
    implementation(libs.okhttp.coroutines)
    implementation(libs.tink.android)

    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.okhttp.mockwebserver)
}
