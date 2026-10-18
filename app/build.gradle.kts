plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

// Release signing. The key lives OUTSIDE the repo (default ~/keys/purefeed-release.jks,
// override with -Ppurefeed.keystore=...). Its password is read from the macOS Keychain
// item "purefeed-release-key" (see README), and only when a release task is requested,
// so debug builds never touch the Keychain. No key file = unsigned release APK.
val releaseKeystore = file(
    providers.gradleProperty("purefeed.keystore")
        .getOrElse("${System.getProperty("user.home")}/keys/purefeed-release.jks")
)
val wantsRelease = gradle.startParameter.taskNames.any { it.contains("release", ignoreCase = true) }

android {
    namespace = "app.purefeed"
    compileSdk = 37

    defaultConfig {
        applicationId = "app.purefeed"
        minSdk = 26
        targetSdk = 37
        versionCode = 3
        // -Ppurefeed.versionName=1.0.0-test builds a release that thinks it's older, to test
        // the in-app updater end to end against the real latest release. Never publish one.
        versionName = providers.gradleProperty("purefeed.versionName").getOrElse("1.0.2")
    }

    signingConfigs {
        create("release") {
            if (wantsRelease && releaseKeystore.exists()) {
                val password = providers.exec {
                    commandLine("security", "find-generic-password", "-s", "purefeed-release-key", "-w")
                }.standardOutput.asText.get().trim()
                storeFile = releaseKeystore
                storePassword = password
                keyAlias = "purefeed"
                keyPassword = password
            }
        }
    }

    buildTypes {
        debug {
            // Installs alongside the release build, labelled "PureFeed debug".
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
        }
        release {
            isMinifyEnabled = false
            if (releaseKeystore.exists()) signingConfig = signingConfigs.getByName("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
    }

    // rules/rules.json (the copy the remote rules URL serves) is also bundled as the
    // offline fallback, so there's only one file to edit.
    sourceSets {
        getByName("main") {
            assets.directories.add("../rules")
        }
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.webkit)
    implementation(libs.androidx.swiperefreshlayout)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.kotlinx.serialization.json)

    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.material3)
    implementation(libs.compose.ui.tooling.preview)
    debugImplementation(libs.compose.ui.tooling)

    testImplementation(libs.junit)
}
