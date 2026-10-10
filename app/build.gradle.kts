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
        versionCode = 10
        versionName = "0.2.8"
    }

    // Same key the 0.2.x releases were published with, so this build installs
    // over them in place instead of forcing an uninstall. The key lives
    // outside the repository; nothing here is a secret that gets committed.
    signingConfigs {
        create("managed") {
            val ks = file(
                System.getenv("XYKELL_KEYSTORE")
                    ?: "${System.getProperty("user.home")}/.config/xykell/managed.keystore",
            )
            if (ks.exists()) {
                storeFile = ks
                storePassword = System.getenv("XYKELL_KEYSTORE_PASS") ?: "android"
                keyAlias = System.getenv("XYKELL_KEY_ALIAS") ?: "xykell"
                keyPassword = System.getenv("XYKELL_KEY_PASSWORD") ?: "android"
            }
        }
    }

    buildTypes {
        getByName("debug") {
            // Debug builds are auto-signed with the standard debug key.
            // No production keys live in this repository (see docs/RELEASE.md).
            isDebuggable = true
        }
        getByName("release") {
            isMinifyEnabled = false
            val managed = signingConfigs.getByName("managed")
            if (managed.storeFile != null) {
                signingConfig = managed
            }
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
    // The device NDK is not installed, so AGP packages the libs that
    // scripts/build-apk.sh already builds with clang++ into src/main/jniLibs.
    // scripts/build-apk.sh still builds them from src/main/cpp/CMakeLists.txt;
    // this only stops AGP from trying to run CMake itself.
    packaging {
        jniLibs {
            useLegacyPackaging = true
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
