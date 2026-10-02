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

    // Shared native core (same ProfileManager sources as the game module).
    // Built by CI's NDK; phone never builds APKs (aapt2 is x86-64).
    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }
    defaultConfig {
        ndk {
            abiFilters += "arm64-v8a"
        }
    }

    // Single-catalog rule: the launcher reads the same registry/features.json
    // as native (copied at build time, never duplicated in source).
    sourceSets {
        getByName("main").assets.srcDir(
            "${layout.buildDirectory.get().asFile}/generated/registry")
    }
}

val copyRegistry = tasks.register<Copy>("copyRegistry") {
    from(rootProject.file("registry/features.json"))
    into(layout.buildDirectory.dir("generated/registry"))
}
tasks.named("preBuild") { dependsOn(copyRegistry) }

dependencies {
    implementation("androidx.appcompat:appcompat:1.7.0")
}
