// :discovery-android — Android-specific peer discovery. Hosts the
// NsdManager publish/browse wrappers (#18, migrated from JmDNS in #98)
// and, in Phase 2, BLE advertise/scan code. Lives in its own module so
// :core-protocol stays JVM-pure and so the transport layer can be
// swapped without touching protocol logic.

plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "dev.bluehouse.bada.discovery"
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
            isIncludeAndroidResources = true
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

    // Junit Jupiter is the project-wide test runtime; align with
    // :core-protocol so all unit tests run under the same engine.
    testImplementation(libs.junit4)
    testImplementation(libs.junit.jupiter.api)
    testImplementation(libs.junit.jupiter.params)
    testImplementation(libs.truth)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.robolectric)
    testRuntimeOnly(libs.junit.jupiter.engine)
    testRuntimeOnly(libs.junit.vintage.engine)

    androidTestImplementation(libs.androidx.test.junit)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.truth)
}

afterEvaluate {
    val debugUnitTest = tasks.named<Test>("testDebugUnitTest")
    val robolectricAndroidAllClasspath =
        configurations.detachedConfiguration(
            dependencies.create(
                libs.robolectric.android.all
                    .get(),
            ),
        )
    val robolectricDebugUnitTest =
        tasks.register<Test>("robolectricDebugUnitTest") {
            val debugClasspath =
                debugUnitTest
                    .get()
                    .classpath
                    .filter { file -> !file.name.startsWith("mockable-android") }
            description = "Runs Robolectric JUnit4 integration tests for discovery-android."
            group = "verification"
            testClassesDirs = debugUnitTest.get().testClassesDirs
            classpath = files(robolectricAndroidAllClasspath) + debugClasspath
            include("**/AndroidNsdRobolectricTest.class")
            shouldRunAfter(debugUnitTest)
        }

    debugUnitTest.configure {
        finalizedBy(robolectricDebugUnitTest)
    }
}

// Run regular JVM unit tests with the Jupiter engine so test discovery
// works without per-class @RunWith annotations. Robolectric tests use the
// dedicated JUnit4 task above because Jupiter resolves Android platform
// descriptors before Robolectric can install its sandbox classloader.
tasks.withType<Test>().configureEach {
    if (name != "robolectricDebugUnitTest") {
        useJUnitPlatform()
        exclude("**/AndroidNsdRobolectricTest.class")
        exclude("**/AndroidNsdRobolectricTest$*.class")
        exclude("**/AndroidNsdRobolectricTestKt.class")
        exclude("**/TestShadowNsdManager*.class")
    }
}
