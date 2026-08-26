import com.android.build.gradle.internal.api.BaseVariantOutputImpl
import org.gradle.api.tasks.Sync

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
    namespace = "dev.bluehouse.bada"
    compileSdk =
        libs.versions.compileSdk
            .get()
            .toInt()

    defaultConfig {
        applicationId = "dev.bluehouse.bada"
        minSdk =
            libs.versions.minSdk
                .get()
                .toInt()
        targetSdk =
            libs.versions.targetSdk
                .get()
                .toInt()
        versionCode = 2026081401
        versionName = "20260814.01"

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
        // BuildConfig is opt-in on AGP 8+; UpdateRepository reads
        // BuildConfig.VERSION_NAME to compare the installed app against
        // the latest GitHub release tag, so it needs to be generated.
        buildConfig = true
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

/**
 * Bundle the matching Radio Helper APK inside each Bada APK so Settings can
 * install the companion without a browser or a second download. Debug embeds
 * the `.debug` helper; release embeds the release helper. Variant-specific
 * generated asset directories prevent a stale helper variant from leaking
 * into another build. `mergeDebugAssets` / `mergeReleaseAssets` own the final
 * embedding boundary; configuration and asset-presence checks are static-only
 * in this change because Android compilation was not authorized.
 */
fun registerBundledRadioHelper(variantName: String) {
    val capitalizedVariant = variantName.replaceFirstChar { it.uppercase() }
    val generatedAssets = layout.buildDirectory.dir("generated/radio-helper/$variantName")
    val bundleTask =
        tasks.register<Sync>("bundleRadioHelper$capitalizedVariant") {
            dependsOn(":radio-helper:assemble$capitalizedVariant")
            from(project(":radio-helper").layout.buildDirectory.dir("outputs/apk/$variantName")) {
                include("*.apk")
                rename { "radio-helper.apk" }
            }
            into(generatedAssets)
        }

    android.sourceSets.getByName(variantName).assets.srcDir(generatedAssets)
    tasks.matching { it.name == "merge${capitalizedVariant}Assets" }.configureEach {
        dependsOn(bundleTask)
    }
}

registerBundledRadioHelper("debug")
registerBundledRadioHelper("release")

dependencies {
    implementation(project(":core-protocol"))
    implementation(project(":service-android"))
    implementation(project(":discovery-android"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.activity.ktx)
    implementation(libs.androidx.fragment.ktx)
    // Spring physics (SpringAnimation) for the landscape nav pill's
    // elastic drag-follow selection (ElasticBottomNavigationView).
    implementation(libs.androidx.dynamicanimation)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.kotlinx.coroutines.android)

    // Material Components for Android — provides BottomNavigationView for
    // the in-app bottom-nav between the Send/Receive tab and the Settings
    // tab in MainActivity. The activity theme uses the
    // MaterialComponents.*.Bridge variant so existing AppCompat-based
    // widgets keep working unchanged.
    implementation(libs.material)

    // ZXing core — pure-Java QR encoder used to render the Quick Share QR
    // URL as a scannable bitmap on ShowQrActivity (#84). Only the encoder
    // (`QRCodeWriter`) is pulled in; the Android camera/scanner side of
    // ZXing (`zxing-android-embedded`) is intentionally not used.
    implementation(libs.zxing.core)

    // WorkManager — runs the automatic update check (UpdateCheckWorker): a
    // 6-hourly PeriodicWork, scheduled in BadaApplication.onCreate, that polls
    // GitHub Releases and posts an "update available" notification. WorkManager
    // persists the schedule across reboots with no user action.
    implementation(libs.androidx.work.runtime.ktx)

    // NOTE: the self-ADB Wi-Fi stack (libadb-android + Conscrypt + BouncyCastle)
    // was MOVED OUT of :app into :radio-helper. The radios are toggled by the
    // standalone helper APK (which targets API 28 for the legacy capability and
    // self-starts on boot); :app reaches it through the helper's RadioService,
    // so the ADB client must NOT live here. See radio-helper/build.gradle.kts.

    testImplementation(libs.junit4)
    androidTestImplementation(libs.androidx.test.junit)
    androidTestImplementation(libs.androidx.test.espresso.core)
}
