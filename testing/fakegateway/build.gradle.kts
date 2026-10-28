plugins {
    alias(libs.plugins.hermes.jvm.library)
}

dependencies {
    api(projects.core.model)
    api(libs.okhttp.mockwebserver)
    api(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.core)
}
