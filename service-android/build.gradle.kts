// :service-android — Android-specific service layer. Hosts the foreground
// receiver service (#21), MediaStore writes (#23), and the notification
// surface (#22). All Android-API entanglement lives here so :core-protocol
// can stay JVM-pure.

plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "dev.bluehouse.bada.service"
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
            // The mDNS gate (#34) and a few other paths in this module
            // call `android.util.Log.i / Log.w` for structured diagnostics
            // and observability. Without `returnDefaultValues = true` the
            // AGP unit-test runtime throws "Method i in android.util.Log
            // not mocked" from every JVM test that hits a logging path,
            // which then propagates through generic catch blocks and hides
            // the real test outcome. Default-values mode returns 0/null
            // for any unmocked Android API call — safe for our tests
            // since none of them assert on Log.* output.
            isReturnDefaultValues = true
        }
    }
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    implementation(project(":core-protocol"))
    // :discovery-android exposes the Quick Share mDNS publish/browse API
    // (#18). The receiver foreground service (#21) consumes
    // Discovery.advertise to register itself with peers.
    implementation(project(":discovery-android"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    // ProcessLifecycleOwner — drives the BLE scan-mode switch between
    // BALANCED (background) and LOW_LATENCY (foreground) in
    // ReceiverForegroundService (#35).
    implementation(libs.androidx.lifecycle.process)
    implementation(libs.kotlinx.coroutines.android)

    // Junit Jupiter is the project-wide test runtime. Aligning with
    // :core-protocol and :discovery-android keeps test discovery uniform
    // and lets shared fixtures move freely between modules.
    testImplementation(libs.junit.jupiter.api)
    testImplementation(libs.junit.jupiter.params)
    testImplementation(libs.truth)
    testImplementation(libs.kotlinx.coroutines.test)
    testRuntimeOnly(libs.junit.jupiter.engine)

    androidTestImplementation(libs.androidx.test.junit)
    androidTestImplementation(libs.androidx.test.espresso.core)
}

// Run JVM unit tests with the Jupiter engine so test discovery works
// without per-class @RunWith annotations.
tasks.withType<Test>().configureEach {
    useJUnitPlatform()
}
