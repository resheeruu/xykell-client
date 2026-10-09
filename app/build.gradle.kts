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
        versionCode = 7
        versionName = "0.2.5"
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
    // Built by CI's NDK. Local on-device builds use scripts/build-apk.sh
    // (termux aapt2/d8 are aarch64; the SDK's own aapt2 is x86-64).
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
    // Stage 20 (Phase 1): loopback WebSocket transport only. OkHttp 4.12.x
    // (minSdk 21+, Java 8+; repo is minSdk 28 / Java 17): binary frames,
    // subprotocol header, graceful close. No other new dependency.
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    // Host JVM unit tests for the observation pipeline (CI). No device impact.
    testImplementation("junit:junit:4.13.2")
    // org.json ships with Android but unit tests run against android.jar
    // stubs (methods throw "not mocked"); the reference implementation is
    // needed to execute translator tests on the host JVM. Test-only.
    testImplementation("org.json:json:20240303")
}
