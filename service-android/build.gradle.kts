// :service-android — Android-specific service layer. Hosts the foreground
// receiver service (#21), MediaStore writes (#23), and the notification
// surface (#22). All Android-API entanglement lives here so :core-protocol
// can stay JVM-pure.

plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "io.github.kyujincho.wvmg.service"
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
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    implementation(project(":core-protocol"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.kotlinx.coroutines.android)

    // Junit Jupiter is the project-wide test runtime. Aligning with
    // :core-protocol and :discovery-android keeps test discovery uniform
    // and lets shared fixtures move freely between modules.
    testImplementation(libs.junit.jupiter.api)
    testImplementation(libs.junit.jupiter.params)
    testImplementation(libs.truth)
    testRuntimeOnly(libs.junit.jupiter.engine)

    androidTestImplementation(libs.androidx.test.junit)
    androidTestImplementation(libs.androidx.test.espresso.core)
}

// Run JVM unit tests with the Jupiter engine so test discovery works
// without per-class @RunWith annotations.
tasks.withType<Test>().configureEach {
    useJUnitPlatform()
}
