// :discovery-android — Android-specific peer discovery. Hosts the JmDNS /
// NsdManager wrappers (#18) and, in Phase 2, BLE advertise/scan code.
// Lives in its own module so :core-protocol stays JVM-pure and so the
// transport layer can be swapped without touching protocol logic.

plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "io.github.kyujincho.wvmg.discovery"
    compileSdk =
        libs.versions.compileSdk
            .get()
            .toInt()

    defaultConfig {
        minSdk =
            libs.versions.minSdk
                .get()
                .toInt()

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        consumerProguardFiles("consumer-rules.pro")
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    testOptions {
        unitTests {
            // The discovery code calls `android.util.Log.i(...)` for the
            // structured diagnostics added in #83. Without
            // `returnDefaultValues = true` the AGP unit-test runtime
            // throws `Method i in android.util.Log not mocked` from
            // every JVM test that hits a logging path.
            isReturnDefaultValues = true
        }
    }
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    implementation(project(":core-protocol"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.kotlinx.coroutines.android)

    // JmDNS for Quick Share mDNS publish + browse (#18). The Android
    // platform's NsdManager has historically had bugs with multi-key TXT
    // records, so we use JmDNS for full control over the wire format.
    implementation(libs.jmdns)

    // Junit Jupiter is the project-wide test runtime; align with
    // :core-protocol so all unit tests run under the same engine.
    testImplementation(libs.junit.jupiter.api)
    testImplementation(libs.junit.jupiter.params)
    testImplementation(libs.truth)
    testImplementation(libs.kotlinx.coroutines.test)
    testRuntimeOnly(libs.junit.jupiter.engine)

    androidTestImplementation(libs.androidx.test.junit)
}

// Run JVM unit tests with the Jupiter engine so test discovery works
// without per-class @RunWith annotations.
tasks.withType<Test>().configureEach {
    useJUnitPlatform()
}
