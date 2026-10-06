plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

/**
 * A signing secret, looked up outside the source tree: the Gradle property (a
 * `gradle.properties` in the project or in ~/.gradle, neither of which is committed), the
 * JVM system property (`-Drelaytester.storePassword=…`), or the environment
 * (`RELAYTESTER_STOREPASSWORD`).
 *
 * The environment name is the property name uppercased with every `.` replaced by `_`, so
 * `relaytester.storePassword` becomes `RELAYTESTER_STOREPASSWORD` — there is no underscore
 * between STORE and PASSWORD. Set the variable under exactly that name: a near-miss such as
 * `RELAYTESTER_STORE_PASSWORD` is simply absent, and the signing config then gets a null
 * password.
 *
 * Null when this machine has no signing secrets, so a debug build still works; building the
 * published variant then fails on the missing property name instead of on a keystore error.
 */
fun Project.secret(name: String): String? =
    providers.gradleProperty(name).orNull
        ?: System.getProperty(name)
        ?: providers.environmentVariable(name.uppercase().replace('.', '_')).orNull

android {
    namespace = "com.relaytester.app"
    compileSdk = 36

    signingConfigs {
        create("release") {
            // The published key. The two properties are read from a `gradle.properties`
            // outside this repository (normally ~/.gradle/gradle.properties) or from the
            // command line as -Drelaytester.storePassword=…, so no secret is ever written
            // into the tree. Losing either the file or the password means the published app
            // can never be updated in place again — there is no recovery, so keep a copy of
            // both off this machine.
            storeFile = rootProject.file("keystore-relay-tester-release.p12")
            storeType = "PKCS12"
            keyAlias = "relaytester"
            // v2 is what installs; v3 is the one that carries a key-rotation lineage. Signing
            // with v3 now means that if this key is ever lost, a new key can be introduced as
            // an update (with a proof-of-rotation lineage) instead of making every user
            // uninstall — Android 9+ honours it. Costs nothing today.
            enableV3Signing = true
            storePassword = project.secret("relaytester.storePassword")
            // PKCS12 uses one password for the store and the key; the separate property stays
            // supported so a future JKS key needs no code change.
            keyPassword = project.secret("relaytester.keyPassword")
                ?: project.secret("relaytester.storePassword")
        }
    }

    defaultConfig {
        applicationId = "com.relaytester.app"
        minSdk = 26
        targetSdk = 36
        // Android versionCode must remain monotonic for an in-place upgrade.
        // Encode the public line as major * 10_000 + minor * 100 + patch.
        // 1.4.0 was rebuilt with the model picker on board, so it carries the newest code
        // at a code above the retired 1.4.1/1.4.2 builds (10303 / 10304): installing it
        // over either of those is an upgrade, and the release line stays a single 1.4.0.
        // 1.5.0 keeps one public release; every rebuild code must still upgrade 10500.
        // 10502 adds the detection history, the round progress display, the copy button
        // and the parallel-switch fixes on top of 10501.
        // 10503 adds the swapped result-row actions, the unified expand panel (evaluation
        // card then candidate list), the usable-answer counter beside the model name and
        // the per-model single-question retry.
        // 10504 turns the question area into one three-column block per model and queues a
        // single-question retry behind the question already on the wire when the panel is
        // set to 逐题发送.
        // 10505 swaps the result row's two action cells (the copy takes the cell that was
        // the fingerprint's, aligned to the 可用 pill's text centre), centres the challenge
        // cell's retry, gives the 有效 n/3 counter a fixed slot, and reads both the current
        // detection-package format and the previous one.
        // 10600 adds the update surface: a header entry with a dot when something is
        // published, a page showing both feeds (the app itself and the detection package),
        // byte progress for both downloads, a timestamp for the last check of each, and one
        // switch per feed. The detection package's card finally says what the silent entry
        // check is doing, and an unreadable package format is reported as an app update with
        // the version code to reach instead of a format number.
        // 10700 is the review round: the update chain pins every download and every redirect
        // hop to the release hosts over HTTPS, verifies size and SHA-256 on the part file
        // before the rename and checks the APK against this app's signing certificate;
        // stored credentials report when they can no longer be decrypted, corrupted storage
        // is surfaced instead of silently dropped, and the streaming and balance paths get
        // their missing ceilings and deadlines.
        // 10701 prints the release notes inside the update dialog and sizes both raw dialogs
        // to the real window on both axes (landscape was clipping the card edge). The launch
        // check now runs on every launch while the switch is on — the six-hour window used to
        // swallow it, so nothing was ever checked and the「上次检查」row kept the previous
        // session's time — and a silent check that finds an update opens the page by itself.
        // 10702 lets the suppliers be reordered from a drag panel in the test tab: the order is
        // the profiles list's own, so the test cards, the balance grid and the fingerprint
        // picker all follow one drag. The template editor becomes a dialog over the balance
        // page instead of replacing it, so closing it lands back on the same scroll position
        // instead of the top. A reasoning model's stream no longer fails the detection: the
        // wire ceiling stops judging the answer (reasoning frames used to spend it), and the
        // answer text gets its own bound instead.
        // 10703 makes the reorder panel actually draggable: the gesture hung on the 44dp grip
        // icon, so the first wobble slid off it and the drag died. The whole row is the handle
        // now, the arrows are gone, the rows animate when they swap, and the panel's card no
        // longer carries the 44dp top padding that pushed its footer off the screen (the
        // template editor dialog had the same padding and the same clipped bottom). The
        // balance editor's two selectors fill with the primary colour when chosen, its script
        // field starts empty with a button that fills the example on demand, and a failed
        // configuration write is retried once so the next save cannot store the order its
        // caller just rolled back. Both supplier editors (config, balance credentials) close
        // themselves once a save succeeds — the user used to have to tap 关闭 afterwards — and
        // the save confirmation is a shared StatusToast drawn on the page, not a snackbar
        // trapped under the still-open dialog's scrim.
        versionCode = 10_703
        versionName = "1.7.3"
        ndk {
            // arm64 only: the published update ships one APK, and every device the app is
            // meant for is arm64. The 32-bit and x86 ABIs would each add a copy of the two
            // QuickJS libraries for devices this build never targets.
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
            signingConfig = signingConfigs.getByName("release")
        }
        create("optimized") {
            initWith(getByName("release"))
            // Same signing as `release`: this variant is what gets published, and an app can
            // only be updated in place by a build with the same package name and the same
            // signing certificate. `debug` keeps its own `.debug` package and debug signing
            // so a development build can sit next to the published one.
            signingConfig = signingConfigs.getByName("release")
            // No versionNameSuffix, unlike `debug`: the update page prints the installed
            // version name next to the feed's, and a "-optimized" marker there reads as a
            // different version.
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
    // 供应商排序面板的换位动画来自 foundation 的 animateItem（BOM 2024.12.01 起叫这个名字）。
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    implementation("androidx.datastore:datastore-preferences:1.1.1")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("io.github.taoweiji.quickjs:quickjs-android:1.4.6")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.8.1")
    testImplementation("org.json:json:20240303")

    debugImplementation("androidx.compose.ui:ui-tooling")
}
