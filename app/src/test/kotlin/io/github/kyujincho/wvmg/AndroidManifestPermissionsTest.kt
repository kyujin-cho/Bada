/*
 * Copyright 2026 WhenVivoMeetsGoogle contributors.
 *
 * Licensed under the Apache License, Version 2.0.
 */
package io.github.kyujincho.wvmg

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Manifest-level regression test for the Phase 1 permission set (#26).
 *
 * We read `app/src/main/AndroidManifest.xml` as a plain text file rather
 * than going through the Android build system. The intent is to catch
 * accidental drift — for example, someone removing the
 * `neverForLocation` flag, or adding a Phase 2 permission ahead of
 * schedule — without paying the cost of pulling Robolectric into the
 * `:app` module just for this one test.
 *
 * The CI job `:app:test` runs this on every PR.
 */
class AndroidManifestPermissionsTest {
    private val manifest: String by lazy {
        // The working directory for module unit tests is the module root,
        // i.e. `<repo>/app`, so a relative path is enough.
        val file = File("src/main/AndroidManifest.xml")
        assertTrue("AndroidManifest.xml should exist at ${file.absolutePath}", file.exists())
        file.readText()
    }

    @Test
    fun `nearby wifi devices declared with neverForLocation flag`() {
        assertTrue(
            "NEARBY_WIFI_DEVICES permission must be declared",
            manifest.contains("android.permission.NEARBY_WIFI_DEVICES"),
        )
        // The neverForLocation flag tells Android we do not derive
        // physical location from peer scans; without it the platform
        // requires ACCESS_FINE_LOCATION on top of NEARBY_WIFI_DEVICES.
        assertTrue(
            "NEARBY_WIFI_DEVICES must use neverForLocation",
            manifest.contains("android:usesPermissionFlags=\"neverForLocation\""),
        )
    }

    @Test
    fun `post notifications permission declared`() {
        assertTrue(
            "POST_NOTIFICATIONS must be declared (gated by API 33+)",
            manifest.contains("android.permission.POST_NOTIFICATIONS"),
        )
    }

    @Test
    fun `compile-time wifi and network permissions declared`() {
        val required =
            listOf(
                "android.permission.CHANGE_WIFI_MULTICAST_STATE",
                "android.permission.INTERNET",
                "android.permission.ACCESS_NETWORK_STATE",
                "android.permission.ACCESS_WIFI_STATE",
            )
        for (permission in required) {
            assertTrue("$permission must be declared", manifest.contains(permission))
        }
    }

    @Test
    fun `phase 2 permissions are not declared in phase 1`() {
        // Bluetooth permissions belong to Phase 2 (BLE auto-discovery).
        // Declaring them now would surface unnecessary install-time
        // permission warnings to users — guard against that here.
        val phase2Forbidden =
            listOf(
                "android.permission.BLUETOOTH_SCAN",
                "android.permission.BLUETOOTH_ADVERTISE",
                "android.permission.BLUETOOTH_CONNECT",
            )
        for (permission in phase2Forbidden) {
            assertFalse(
                "$permission must NOT be declared in Phase 1",
                manifest.contains(permission),
            )
        }
    }

    @Test
    fun `media permissions are not declared in phase 1`() {
        // Phase 1 uses the system share-intent flow, which does not need
        // direct media access. READ_MEDIA_* would surface needless
        // permissions to users — guard against that drift.
        val mediaForbidden =
            listOf(
                "android.permission.READ_MEDIA_IMAGES",
                "android.permission.READ_MEDIA_VIDEO",
                "android.permission.READ_MEDIA_AUDIO",
                "android.permission.ACCESS_FINE_LOCATION",
                "android.permission.ACCESS_COARSE_LOCATION",
            )
        for (permission in mediaForbidden) {
            assertFalse(
                "$permission must NOT be declared in Phase 1",
                manifest.contains(permission),
            )
        }
    }

    @Test
    fun `permissions onboarding activity is registered and not exported`() {
        assertTrue(
            "PermissionsOnboardingActivity must be registered",
            manifest.contains(".onboarding.PermissionsOnboardingActivity"),
        )
        // Internal activity — must not be launchable from other apps.
        val onboardingBlock =
            manifest
                .substringAfter(".onboarding.PermissionsOnboardingActivity")
                .substringBefore("</activity>")
        assertTrue(
            "PermissionsOnboardingActivity must declare android:exported=\"false\"",
            onboardingBlock.contains("android:exported=\"false\""),
        )
    }

    @Test
    fun `send activity declares share intent filters`() {
        // #24: the system share sheet routes ACTION_SEND /
        // ACTION_SEND_MULTIPLE here. Both filters must be declared on
        // the same activity, both must accept all MIME types, and the
        // activity must be exported (the share sheet runs in a different
        // process and needs to be able to start it).
        assertTrue(
            "SendActivity must be registered",
            manifest.contains(".send.SendActivity"),
        )
        val sendBlock =
            manifest
                .substringAfter(".send.SendActivity")
                .substringBefore("</activity>")
        assertTrue(
            "SendActivity must be exported (share sheet calls cross-process)",
            sendBlock.contains("android:exported=\"true\""),
        )
        assertTrue(
            "ACTION_SEND filter must be declared on SendActivity",
            sendBlock.contains("android.intent.action.SEND"),
        )
        assertTrue(
            "ACTION_SEND_MULTIPLE filter must be declared on SendActivity",
            sendBlock.contains("android.intent.action.SEND_MULTIPLE"),
        )
        assertTrue(
            "SendActivity must accept */* MIME types per #24 acceptance criteria",
            sendBlock.contains("android:mimeType=\"*/*\""),
        )
        assertTrue(
            "DEFAULT category must be declared so share sheet can resolve us",
            sendBlock.contains("android.intent.category.DEFAULT"),
        )
    }

    @Test
    fun `show qr activity is registered and not exported`() {
        assertTrue(
            "ShowQrActivity must be registered",
            manifest.contains(".send.ShowQrActivity"),
        )
        val qrBlock =
            manifest
                .substringAfter(".send.ShowQrActivity")
                .substringBefore("/>")
        // Internal activity — only SendActivity launches it.
        assertTrue(
            "ShowQrActivity must declare android:exported=\"false\"",
            qrBlock.contains("android:exported=\"false\""),
        )
    }
}
