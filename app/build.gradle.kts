plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.relaytester.app"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.relaytester.app"
        minSdk = 26
        targetSdk = 36
        // Android versionCode must remain monotonic for an in-place upgrade.
        // Encode the public line as major * 10_000 + minor * 100 + patch.
        versionCode = 10_100
        versionName = "1.1.0"
        ndk {
            abiFilters += "arm64-v8a"
        }
    }

    buildFeatures {
        compose = true
        // No source references BuildConfig. Avoid generating an otherwise
        // unused Java class and its startup/build-time worker overhead.
        buildConfig = false
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
        }
        release {
            // Release and the local optimized build use the same production
            // bytecode/resource pipeline. This changes no Compose output; it
            // reduces install-time verification and cold-start class loading.
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
        create("optimized") {
            initWith(getByName("release"))
            // Keep this package identical to the installed debug package so
            // emulator validation can use adb install -r without touching the
            // user's DataStore or Keystore records.
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-optimized"
            signingConfig = signingConfigs.getByName("debug")
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
    val composeBom = platform("androidx.compose:compose-bom:2024.12.01")

    implementation("androidx.core:core-ktx:1.17.0")
    implementation("androidx.activity:activity-compose:1.10.0")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    implementation("androidx.datastore:datastore-preferences:1.1.1")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("io.github.taoweiji.quickjs:quickjs-android:1.4.6")

    debugImplementation("androidx.compose.ui:ui-tooling")
}
