// :rns-meshtastic — Reticulum over a stock Meshtastic node (RNS_Over_Meshtastic
// tunnel): node links (BLE / TCP / USB streams), the client-API protobufs and
// the fragmenting tunnel. Shared by both backends: `:rns-backend-kt` wraps
// [network.columba.app.rns.meshtastic.MeshtasticSession] as a reticulum-kt
// Interface, `:rns-host`'s KotlinMeshtasticBridge exposes it to the Python
// backend's ColumbaMeshtasticInterface.
plugins {
    id("com.android.library")
}

android {
    namespace = "network.columba.app.rns.meshtastic"
    compileSdk = 36

    defaultConfig {
        minSdk = 24
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlin {
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        }
    }
}

dependencies {
    implementation(libs.coroutines.core)
    implementation(libs.coroutines.android)

    testImplementation(libs.junit)
}
