plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

// Release signing. On GitHub Actions the build job has no signing key at all: it sets
// UNSIGNED_RELEASE=true and gets app-release-unsigned.apk, which a separate job signs with apksigner
// (see .github/workflows/build.yml). An unsigned APK cannot be installed, so nothing unsigned can ship.
// A keystore from environment variables still works (SIGNING_KEYSTORE_PATH, KEYSTORE_PASSWORD,
// KEY_ALIAS, KEY_PASSWORD). Local builds without one fall back to the debug key; on CI a build that
// has neither a key nor UNSIGNED_RELEASE=true fails, so a debug-signed APK is never published as a release.
val releaseKeystorePath: String? = System.getenv("SIGNING_KEYSTORE_PATH")?.takeIf { it.isNotBlank() && file(it).exists() }
val isCi: Boolean = System.getenv("CI") == "true" || System.getenv("GITHUB_ACTIONS") == "true"
val releaseSigningComplete: Boolean = releaseKeystorePath != null &&
    listOf("KEYSTORE_PASSWORD", "KEY_ALIAS", "KEY_PASSWORD").all { !System.getenv(it).isNullOrBlank() }
val unsignedRelease: Boolean = System.getenv("UNSIGNED_RELEASE") == "true"

android {
    namespace = "com.vknxworks.lumenctrl"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.vknxworks.lumenctrl"
        minSdk = 35
        targetSdk = 35
        // Bump both to publish a new release (see CHANGELOG.md).
        versionCode = 7
        versionName = "0.4.0"
        // 64-bit ARM phones only (arm64-v8a), like every Ctrl app.
        ndk { abiFilters += listOf("arm64-v8a") }
    }

    signingConfigs {
        if (releaseKeystorePath != null) {
            create("release") {
                storeFile = file(releaseKeystorePath)
                storePassword = System.getenv("KEYSTORE_PASSWORD")
                keyAlias = System.getenv("KEY_ALIAS")
                keyPassword = System.getenv("KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = when {
                // Asked for explicitly: no signing here at all, the output is app-release-unsigned.apk.
                unsignedRelease -> null
                releaseSigningComplete -> signingConfigs.getByName("release")
                isCi -> throw GradleException(
                    "Release signing is not configured on CI (keystore, KEYSTORE_PASSWORD, KEY_ALIAS, KEY_PASSWORD) " +
                        "and UNSIGNED_RELEASE=true is not set. Refusing to sign the release with the debug key.",
                )
                else -> signingConfigs.getByName("debug")
            }
        }
        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures { compose = true }
    // Models are memory-mapped straight from the APK, so they must stay uncompressed.
    androidResources { noCompress += "tflite" }

    packaging {
        resources.excludes += listOf("/META-INF/{AL2.0,LGPL2.1}", "/META-INF/INDEX.LIST")
    }

    // F-Droid: no dependency-info block in the APK.
    dependenciesInfo {
        includeInApk = false
        includeInBundle = false
    }

    lint {
        abortOnError = false
        checkReleaseBuilds = false
    }
}

kotlin { jvmToolchain(17) }

// Google-free: no Google services or SDKs may reach the APK. AndroidX pulls Guava's
// one-interface listenablefuture jar, and profileinstaller (not needed for an offline app)
// pulls concurrent-futures. The one Google-made library kept on purpose is LiteRT, the
// open-source (Apache 2.0) on-device ML runtime behind AI masks and AI Denoise; it has
// no Play services, no Firebase and no network code path (the app has no INTERNET permission).
configurations.configureEach {
    exclude(group = "com.google.guava")
    exclude(group = "androidx.profileinstaller", module = "profileinstaller")
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2026.09.00")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended:1.7.8")
    implementation("androidx.activity:activity-compose:1.13.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.10.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.10.0")
    implementation("androidx.core:core-ktx:1.18.0")
    implementation("androidx.exifinterface:exifinterface:1.4.1")
    // On-device inference for AI masks and AI Denoise: LiteRT (TensorFlow Lite's successor),
    // standalone, no Play Services, no network, built for 16 KB memory pages.
    implementation("com.google.ai.edge.litert:litert:1.4.0")
}
