import java.text.SimpleDateFormat
import java.util.Date
import java.util.TimeZone

/** Build-time version: minutes since 2026-01-01 UTC, so phone and car can tell which build is newer. */
abstract class BuildMinutes : ValueSource<Long, ValueSourceParameters.None> {
    override fun obtain(): Long = System.currentTimeMillis() / 60_000
}
val buildEpochMinute = providers.of(BuildMinutes::class) {}.get()
val buildMinutes = (buildEpochMinute - 1_767_225_600L / 60).toInt()
val buildStamp = "1.0." + SimpleDateFormat("yyyyMMdd.HHmm").apply { timeZone = TimeZone.getTimeZone("UTC") }.format(Date(buildEpochMinute * 60_000))

plugins {
    alias(libs.plugins.android.application)
}

android {
    namespace = "com.nhowka.autobridge"
    ndkVersion = "28.2.13676358"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "com.nhowka.autobridge"
        minSdk = 30
        targetSdk = 37
        versionCode = buildMinutes
        versionName = buildStamp
        ndk { abiFilters += "arm64-v8a" }
    }

    buildTypes {
        release {
            // Personal sideload builds: signed with the debug key so they install over debug builds.
            signingConfig = signingConfigs.getByName("debug")
            optimization {
                enable = true
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "4.1.2"
            buildStagingDirectory = file("${System.getProperty("user.home")}/.gradle-builds/AutoBridge/cxx")
        }
    }
    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

dependencies {
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.core.ktx)
    testImplementation(libs.junit)
    testImplementation(libs.json) // real org.json for JVM unit tests
}
