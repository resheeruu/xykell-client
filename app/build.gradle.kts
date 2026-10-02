plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "dev.xykell.client"
    compileSdk = 35

    defaultConfig {
        applicationId = "dev.xykell.client"
        minSdk = 28
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0-m1.5"
    }

    buildTypes {
        getByName("debug") {
            // Debug builds are auto-signed with the standard debug key.
            // No production keys live in this repository (see docs/RELEASE.md).
            isDebuggable = true
        }
        getByName("release") {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    implementation("androidx.appcompat:appcompat:1.7.0")
}
