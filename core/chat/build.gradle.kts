plugins {
    alias(libs.plugins.hermes.jvm.library)
}

// The chat transcript, kept free of Android so its folding rules are plain JVM tests: Hermes'
// stored history and its live events, both turned into the same ChatItem rows.
dependencies {
    api(projects.core.model)
    // GatewayEvent, and the JsonObject rows it carries.
    api(projects.core.gateway)
    api(libs.kotlinx.serialization.json)
}
