import com.android.build.api.variant.BuildConfigField
import groovy.json.JsonSlurper
import java.util.Properties

plugins {
    alias(libs.plugins.hermes.android.application)
    alias(libs.plugins.hermes.android.application.compose)
    alias(libs.plugins.hermes.hilt)
    alias(libs.plugins.kotlin.serialization)
}

// Push notifications: Firebase's settings for each app id come from google-services.json (the
// file the Firebase console hands out, kept out of git), read here instead of by the Google
// Services plugin. Without the file the build has no push, and the app says so.
val firebaseClients: Map<String, Map<String, String>> = file("google-services.json")
    .takeIf(File::exists)
    ?.let(::firebaseClientsIn)
    .orEmpty()

val localProperties: Properties = Properties().apply {
    rootProject.file("local.properties").takeIf(File::exists)?.reader()?.use(::load)
}

// The relay a build offers by default: `hermes.relayUrl` in local.properties, as it names a private host.
val defaultRelayUrl: String = localProperties.getProperty("hermes.relayUrl").orEmpty()

// The Google Maps key for map cards: `hermes.mapsApiKey` in local.properties, kept out of git. Without
// one, places are drawn as rows that open the maps app.
val mapsApiKey: String = localProperties.getProperty("hermes.mapsApiKey").orEmpty().trim()

// Release signing, from the environment (the release workflow's secrets). Without it,
// assembleRelease builds an unsigned APK.
val releaseKeystore: String? = providers.environmentVariable("TALARIA_KEYSTORE").orNull?.takeIf(String::isNotBlank)

// A release's version, from the tag it's built for: -Ptalaria.versionName=1.2.0 -Ptalaria.versionCode=7.
val releaseVersionName: String? = providers.gradleProperty("talaria.versionName").orNull
val releaseVersionCode: Int? = providers.gradleProperty("talaria.versionCode").orNull?.toInt()

android {
    namespace = "lab.dawnforge.hermes"

    buildFeatures {
        buildConfig = true
    }

    defaultConfig {
        // The app id predates the name Talaria; changing it would mean a fresh install.
        applicationId = "lab.dawnforge.hermes"
        versionCode = releaseVersionCode ?: 1
        versionName = releaseVersionName ?: "0.1.0"
        manifestPlaceholders["mapsApiKey"] = mapsApiKey
    }

    signingConfigs {
        if (releaseKeystore != null) {
            create("release") {
                storeFile = file(releaseKeystore)
                storePassword = providers.environmentVariable("TALARIA_KEYSTORE_PASSWORD").orNull
                keyAlias = providers.environmentVariable("TALARIA_KEY_ALIAS").orNull
                keyPassword = providers.environmentVariable("TALARIA_KEY_PASSWORD").orNull
            }
        }
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
        }
        release {
            signingConfig = signingConfigs.findByName("release")
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }
}

androidComponents {
    onVariants { variant ->
        val fields = variant.buildConfigFields ?: return@onVariants

        // Empty when missing: a null constant would make Kotlin call the build's own checks always true.
        fun string(value: String?) = BuildConfigField("String", "\"${value.orEmpty()}\"", null)
        for (key in listOf("appId", "apiKey", "projectId", "senderId")) {
            val name = "FIREBASE_" + key.replace(Regex("([A-Z])"), "_$1").uppercase()
            fields.put(name, variant.applicationId.map { id -> string(firebaseClients[id]?.get(key)) })
        }
        fields.put("DEFAULT_RELAY_URL", string(defaultRelayUrl.trim()))
        fields.put("MAPS_ENABLED", BuildConfigField("boolean", mapsApiKey.isNotEmpty().toString(), null))
    }
}

/** Per Android app id in a google-services.json: what FirebaseOptions needs. */
fun firebaseClientsIn(file: File): Map<String, Map<String, String>> {
    @Suppress("UNCHECKED_CAST")
    val root = JsonSlurper().parse(file) as Map<String, Any?>
    val project = root["project_info"] as Map<String, Any?>
    val clients = root["client"] as List<Map<String, Any?>>
    return clients.associate { client ->
        val info = client["client_info"] as Map<String, Any?>
        val packageName = (info["android_client_info"] as Map<String, Any?>)["package_name"] as String
        val apiKey = (client["api_key"] as List<Map<String, Any?>>).first()["current_key"] as String
        packageName to mapOf(
            "appId" to info["mobilesdk_app_id"] as String,
            "apiKey" to apiKey,
            "projectId" to project["project_id"] as String,
            "senderId" to project["project_number"] as String,
        )
    }
}

dependencies {
    // The app's own resources, such as its network security rules, checked as plain files.
    testImplementation(libs.kotlin.test.junit)
    testImplementation(libs.junit4)
    implementation(projects.core.designsystem)
    implementation(projects.core.data)
    implementation(projects.core.push)
    implementation(projects.feature.connect)
    implementation(projects.feature.sessions)
    implementation(projects.feature.chat)
    implementation(projects.feature.notifications)
    implementation(projects.feature.cron)
    implementation(projects.feature.settings)
    implementation(projects.feature.tasks)
    implementation(projects.feature.bots)

    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.biometric)
    debugImplementation(libs.health.connect)
    implementation(libs.androidx.compose.material.icons.core)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.glance.appwidget)
    implementation(libs.androidx.glance.material3)
    implementation(libs.androidx.hilt.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.navigation.compose)
    // FirebaseOptions, built from this build's google-services.json values.
    implementation(platform(libs.firebase.bom))
    implementation(libs.firebase.messaging)

    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.test.ext.junit)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
}
