// :radio-helper — standalone companion APK whose ONLY job is to toggle
// Wi-Fi and Bluetooth on behalf of Bada.
//
// WHY A SEPARATE APK: the radio-enable APIs are targetSdkVersion-gated, not
// permission-gated. Per the AOSP docs, WifiManager.setWifiEnabled() works
// only for apps targeting API <= 28, and BluetoothAdapter.enable() only for
// apps targeting API <= 32. The main :app must target a modern SDK (scoped
// storage, FGS types, notifications, the HCE wake, ...), so it CANNOT hold
// the legacy capability itself. This module targets API 28 so the OS applies
// "legacy rules" and lets it flip both radios silently — the same trick used
// by Tasker Settings / MacroDroid Helper. DO NOT raise targetSdk here.
plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

data class HelperReleaseSigningInputs(
    val keystoreFile: String,
    val keystorePassword: String,
    val keyAlias: String,
    val keyPassword: String,
)

/**
 * Reads the same four signing inputs as `:app`. A complete set signs the
 * release helper with Bada's release key so its signature-protected
 * `BIND_RADIO` service accepts the main app; a partial set fails configuration
 * instead of producing an unusable mismatched pair. Local builds with no
 * signing inputs retain the Android plugin's ordinary unsigned-release rule.
 */
fun helperReleaseSigningInputs(): HelperReleaseSigningInputs? {
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
    if (present.isEmpty()) return null

    val missing = values.filterValues { it == null }.keys
    check(missing.isEmpty()) {
        "Radio Helper signing config is incomplete. Missing: ${missing.joinToString()}"
    }
    return HelperReleaseSigningInputs(
        keystoreFile = values.getValue("KEYSTORE_FILE")!!,
        keystorePassword = values.getValue("KEYSTORE_PASSWORD")!!,
        keyAlias = values.getValue("KEY_ALIAS")!!,
        keyPassword = values.getValue("KEY_PASSWORD")!!,
    )
}

val helperReleaseSigningInputs = helperReleaseSigningInputs()

android {
    namespace = "dev.bluehouse.bada.radiohelper"
    compileSdk =
        libs.versions.compileSdk
            .get()
            .toInt()

    defaultConfig {
        applicationId = "dev.bluehouse.bada.radiohelper"
        minSdk =
            libs.versions.minSdk
                .get()
                .toInt()
        // INTENTIONALLY LOW — this is the whole point of the module.
        targetSdk = 28
        versionCode = 1
        versionName = "1.0"
    }

    signingConfigs {
        if (helperReleaseSigningInputs != null) {
            create("release") {
                storeFile = file(helperReleaseSigningInputs.keystoreFile)
                storePassword = helperReleaseSigningInputs.keystorePassword
                keyAlias = helperReleaseSigningInputs.keyAlias
                keyPassword = helperReleaseSigningInputs.keyPassword
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
            if (helperReleaseSigningInputs != null) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    lint {
        // Play bars apps targeting an old API; this is a sideloaded helper,
        // so the deliberately-low targetSdk must not fail the build.
        disable += "ExpiredTargetSdkVersion"
    }

    buildFeatures {
        // AIDL for the Shizuku user-service interface; BuildConfig for DEBUG.
        aidl = true
        buildConfig = true
    }
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    // Shizuku = the silent Wi-Fi fallback when the OEM clamps setWifiEnabled
    // even with WRITE_SECURE_SETTINGS. We run a Shizuku USER SERVICE (shell
    // UID) that calls `svc wifi enable/disable` — robust across versions,
    // unlike binding the hidden IWifiManager AIDL (transaction codes shift).
    implementation(libs.shizuku.api)
    implementation(libs.shizuku.provider)

    // Self-ADB Wi-Fi (silent, no Shizuku): pure-Java Android-11 ADB client. The
    // helper pairs ONCE with the device's own Wireless Debugging, then self-
    // connects via the library's autoConnect() (mDNS + TLSv1.3) to run `svc wifi`.
    // Self-starts on boot (adb-auto-enable model) so there's NO per-reboot step.
    // 3.1.1 (was 1.0.1): fixes the "connect after pairing fails" class of issue
    // (libadb-android #4) and provides autoConnect()/connectTls() which discover
    // and connect correctly — the manual discover+connect(127.0.0.1) on 1.0.1 hit
    // IOException on ColorOS (paired but adbd unreachable).
    implementation(libs.libadb.android)
    // conscrypt: libadb's SslUtils reflectively instantiates org.conscrypt.
    // OpenSSLProvider for a self-contained TLSv1.3 (avoids the hidden-API path the
    // platform conscrypt would need). We do NOT register it as a global provider.
    implementation(libs.conscrypt.android)
    // BouncyCastle = our self-signed cert generation. Aligned to jdk15to18:1.81 to
    // match the bcprov libadb 3.1.1 pulls in (avoid a bcprov/bcpkix version skew).
    implementation(libs.bouncycastle.bcprov)
    implementation(libs.bouncycastle.bcpkix)

    testImplementation(libs.junit4)
    testImplementation(libs.truth)
}
