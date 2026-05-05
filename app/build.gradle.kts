import com.android.build.gradle.internal.api.BaseVariantOutputImpl

// :app — Android application module. Empty by design at this stage; the
// real share intent handling (#24), settings UI, and device list land in
// later issues. This module's job is to wire :service-android,
// :discovery-android, and :core-protocol together.

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

data class ReleaseSigningInputs(
    val keystoreFile: String,
    val keystorePassword: String,
    val keyAlias: String,
    val keyPassword: String,
)

fun isReleaseTaskRequested(): Boolean =
    gradle.startParameter.taskNames.any { taskName ->
        taskName.substringAfterLast(':').contains("release", ignoreCase = true)
    }

fun releaseSigningInputs(releaseTaskRequested: Boolean): ReleaseSigningInputs? {
    fun propertyOrEnvironment(name: String): String? =
        providers
            .gradleProperty(name)
            .orElse(providers.environmentVariable(name))
            .orNull
            ?.takeIf { it.isNotBlank() }

    val values =
        mapOf(
            "KEYSTORE_FILE" to propertyOrEnvironment("KEYSTORE_FILE"),
            "KEYSTORE_PASSWORD" to propertyOrEnvironment("KEYSTORE_PASSWORD"),
            "KEY_ALIAS" to propertyOrEnvironment("KEY_ALIAS"),
            "KEY_PASSWORD" to propertyOrEnvironment("KEY_PASSWORD"),
        )
    val present = values.filterValues { it != null }
    if (present.isEmpty()) {
        return null
    }

    val missing = values.filterValues { it == null }.keys
    if (missing.isNotEmpty()) {
        if (releaseTaskRequested) {
            error("Release signing config is incomplete. Missing: ${missing.joinToString()}")
        }
        return null
    }

    return ReleaseSigningInputs(
        keystoreFile = values.getValue("KEYSTORE_FILE")!!,
        keystorePassword = values.getValue("KEYSTORE_PASSWORD")!!,
        keyAlias = values.getValue("KEY_ALIAS")!!,
        keyPassword = values.getValue("KEY_PASSWORD")!!,
    )
}

val releaseSigningInputs = releaseSigningInputs(isReleaseTaskRequested())

android {
    namespace = "dev.bluehouse.libredrop"
    compileSdk =
        libs.versions.compileSdk
            .get()
            .toInt()

    defaultConfig {
        applicationId = "dev.bluehouse.libredrop"
        minSdk =
            libs.versions.minSdk
                .get()
                .toInt()
        targetSdk =
            libs.versions.targetSdk
                .get()
                .toInt()
        versionCode = 1
        versionName = "0.1.0-alpha01"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        if (releaseSigningInputs != null) {
            create("release") {
                storeFile = file(releaseSigningInputs.keystoreFile)
                storePassword = releaseSigningInputs.keystorePassword
                keyAlias = releaseSigningInputs.keyAlias
                keyPassword = releaseSigningInputs.keyPassword
            }
        }
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
            isDebuggable = true
        }
        release {
            isMinifyEnabled = false
            if (releaseSigningInputs != null) {
                signingConfig = signingConfigs.getByName("release")
            }
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        viewBinding = true
    }
}

android.applicationVariants.configureEach {
    if (buildType.name != "release") {
        return@configureEach
    }

    val applicationId = applicationId
    val versionName =
        mergedFlavor.versionName
            ?: error("Release APK filename requires a versionName.")

    outputs.configureEach {
        (this as BaseVariantOutputImpl).outputFileName = "$applicationId-$versionName.apk"
    }
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    implementation(project(":core-protocol"))
    implementation(project(":service-android"))
    implementation(project(":discovery-android"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.activity.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.kotlinx.coroutines.android)

    // ZXing core — pure-Java QR encoder used to render the Quick Share QR
    // URL as a scannable bitmap on ShowQrActivity (#84). Only the encoder
    // (`QRCodeWriter`) is pulled in; the Android camera/scanner side of
    // ZXing (`zxing-android-embedded`) is intentionally not used.
    implementation(libs.zxing.core)

    testImplementation(libs.junit4)
    androidTestImplementation(libs.androidx.test.junit)
    androidTestImplementation(libs.androidx.test.espresso.core)
}
