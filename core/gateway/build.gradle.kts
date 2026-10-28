plugins {
    alias(libs.plugins.hermes.jvm.library)
    alias(libs.plugins.kotlin.serialization)
}

dependencies {
    api(projects.core.model)
    // The connection exposes JsonObject params and results, and OkHttp's client.
    api(projects.core.net)
    api(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.core)

    testImplementation(projects.testing.fakegateway)
    testImplementation(libs.kotlinx.coroutines.test)
}

// LiveHermesTest runs only with -Phermes.live.file=<properties file with url, username, password>.
val liveFile = providers.gradleProperty("hermes.live.file")
tasks.test {
    inputs.property("hermes.live.file", liveFile.orElse(""))
    liveFile.orNull?.let { systemProperty("hermes.live.file", it) }
    testLogging { showStandardStreams = liveFile.isPresent }
}
