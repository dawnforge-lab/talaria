plugins {
    alias(libs.plugins.hermes.android.library)
    alias(libs.plugins.hermes.hilt)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "lab.dawnforge.hermes.core.data"
}

dependencies {
    api(projects.core.model)
    // Sign-in results carry core:net's ConnectionFailure, and the gateway status its states, to the features.
    api(projects.core.net)
    api(projects.core.gateway)
    // The chat screen folds history and events with core:chat's transcript rules.
    api(projects.core.chat)
    // Hilt's generated code in the app sees the database DataModule provides.
    api(projects.core.database)

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.androidx.lifecycle.process)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.tink.android)
    implementation(libs.androidx.webkit)
    implementation(libs.androidx.work.runtime)
    implementation(libs.play.services.location)
    implementation(libs.health.connect)
    implementation(libs.kotlinx.coroutines.play.services)

    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.okhttp.mockwebserver)
    // The unclear-delivery branch against a fault-injected gateway (UnclearDeliveryFaultTest).
    testImplementation(projects.testing.fakegateway)
}

// LiveSessionsTest runs only with -Phermes.live.file=<properties file with url, username, password>.
// -Phermes.live.seed=N leaves N throwaway chats for checking the screens by hand.
val liveFile = providers.gradleProperty("hermes.live.file")
val liveSeed = providers.gradleProperty("hermes.live.seed")
// -Phermes.live.turn=<dir> runs one real, paid model turn and records it there (LiveTurnCaptureTest).
val liveTurn = providers.gradleProperty("hermes.live.turn")
val livePrompt = providers.gradleProperty("hermes.live.prompt")
val liveInto = providers.gradleProperty("hermes.live.into")
// -Phermes.live.push=approval|question runs a real turn that waits for a phone to answer its notification.
val livePush = providers.gradleProperty("hermes.live.push")
// -Phermes.live.cron=true creates a scheduled job, runs it once (a real model turn) and deletes it.
val liveCron = providers.gradleProperty("hermes.live.cron")
tasks.withType<Test>().configureEach {
    inputs.property("hermes.live.file", liveFile.orElse(""))
    inputs.property("hermes.live.seed", liveSeed.orElse(""))
    inputs.property("hermes.live.turn", liveTurn.orElse(""))
    inputs.property("hermes.live.prompt", livePrompt.orElse(""))
    inputs.property("hermes.live.into", liveInto.orElse(""))
    inputs.property("hermes.live.push", livePush.orElse(""))
    inputs.property("hermes.live.cron", liveCron.orElse(""))
    liveFile.orNull?.let { systemProperty("hermes.live.file", it) }
    liveSeed.orNull?.let { systemProperty("hermes.live.seed", it) }
    liveTurn.orNull?.let { systemProperty("hermes.live.turn", it) }
    livePrompt.orNull?.let { systemProperty("hermes.live.prompt", it) }
    liveInto.orNull?.let { systemProperty("hermes.live.into", it) }
    livePush.orNull?.let { systemProperty("hermes.live.push", it) }
    liveCron.orNull?.let { systemProperty("hermes.live.cron", it) }
    testLogging { showStandardStreams = liveFile.isPresent }
}
