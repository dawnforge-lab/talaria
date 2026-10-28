package lab.dawnforge.hermes.buildlogic

import com.android.build.api.dsl.CommonExtension
import org.gradle.api.JavaVersion
import org.gradle.api.Project
import org.gradle.api.plugins.JavaPluginExtension
import org.gradle.kotlin.dsl.assign
import org.gradle.kotlin.dsl.configure
import org.gradle.kotlin.dsl.dependencies
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.dsl.KotlinAndroidProjectExtension
import org.jetbrains.kotlin.gradle.dsl.KotlinBaseExtension
import org.jetbrains.kotlin.gradle.dsl.KotlinJvmProjectExtension

internal object AndroidSdk {
    const val COMPILE = 37
    const val TARGET = 37
    const val MIN = 26
}

private val javaVersion = JavaVersion.VERSION_17
private val projectJvmTarget = JvmTarget.JVM_17

/** Shared Android and Kotlin settings for application and library modules (AGP built-in Kotlin). */
internal fun Project.configureKotlinAndroid(commonExtension: CommonExtension) {
    commonExtension.apply {
        compileSdk = AndroidSdk.COMPILE
        defaultConfig.minSdk = AndroidSdk.MIN

        compileOptions.apply {
            sourceCompatibility = javaVersion
            targetCompatibility = javaVersion
            // java.time and other newer JDK APIs on older devices.
            isCoreLibraryDesugaringEnabled = true
        }
    }

    configureKotlin<KotlinAndroidProjectExtension>()

    dependencies {
        "coreLibraryDesugaring"(libs.library("android.desugarJdkLibs"))
    }
}

/** Settings for pure Kotlin/JVM modules, which hold logic that never needs the Android framework. */
internal fun Project.configureKotlinJvm() {
    extensions.configure<JavaPluginExtension> {
        sourceCompatibility = javaVersion
        targetCompatibility = javaVersion
    }

    configureKotlin<KotlinJvmProjectExtension>()
}

private inline fun <reified T : KotlinBaseExtension> Project.configureKotlin() = configure<T> {
    // CI passes -PwarningsAsErrors=true.
    val warningsAsErrors = providers.gradleProperty("warningsAsErrors")
        .map(String::toBoolean)
        .orElse(false)
    when (this) {
        is KotlinAndroidProjectExtension -> compilerOptions
        is KotlinJvmProjectExtension -> compilerOptions
        else -> error("Unsupported Kotlin extension ${T::class}")
    }.apply {
        jvmTarget = projectJvmTarget
        allWarningsAsErrors = warningsAsErrors
    }
}
