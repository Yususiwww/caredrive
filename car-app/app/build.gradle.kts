plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "ai.caredrive.car"
    compileSdk = 37

    defaultConfig {
        applicationId = "ai.caredrive.car"
        // Pleos Connect requires minSdk 28+ (signing key rotation / lineage)
        minSdk = 28
        // Pleos Connect Emulator 2.1.0 is API 34
        targetSdk = 34
        versionCode = 1
        versionName = "0.1.0"
        // Pleos Connect emulator is x86_64, the car is arm64: ship only those (keeps the APK under GitHub's limit).
        ndk { abiFilters += listOf("x86_64", "arm64-v8a") }
        // Relay server on the Mac, as seen from the Pleos emulator. See relay/server.js.
        buildConfigField("String", "RELAY_URL", "\"ws://10.0.2.2:8787/car\"")
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            // Contest build: signed with the local debug key so the APK installs as-is on the emulator.
            signingConfig = signingConfigs.getByName("debug")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.material3)
    implementation(libs.okhttp)
    implementation(libs.kotlinx.serialization.json)

    implementation(libs.pleos.vehicle)
    implementation(libs.pleos.navihelper)
    implementation(libs.pleos.tts)
    // Our own map (rest-stop preview). The emulator navigation map has no base layers.
    implementation(libs.maplibre)

    debugImplementation(libs.compose.ui.tooling)
    testImplementation(libs.junit)
}
