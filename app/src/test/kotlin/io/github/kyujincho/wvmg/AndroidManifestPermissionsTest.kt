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
 * Manifest-level regression test for the Phase 1 + Phase 2 permission
 * set (#26, #31).
 *
 * We read `app/src/main/AndroidManifest.xml` as a plain text file rather
 * than going through the Android build system. The intent is to catch
 * accidental drift — for example, someone removing the
 * `neverForLocation` flag from `BLUETOOTH_SCAN` (which would force the
 * platform to also require `ACCESS_FINE_LOCATION`), or accidentally
 * declaring a runtime permission we explicitly chose to skip — without
 * paying the cost of pulling Robolectric into the `:app` module just
 * for this one test.
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
        val block = usesPermissionBlockFor("android.permission.NEARBY_WIFI_DEVICES")
        // The neverForLocation flag tells Android we do not derive
        // physical location from peer scans; without it the platform
        // requires ACCESS_FINE_LOCATION on top of NEARBY_WIFI_DEVICES.
        assertTrue(
            "NEARBY_WIFI_DEVICES must use neverForLocation",
            block.contains("android:usesPermissionFlags=\"neverForLocation\""),
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
                "android.permission.INTERNET",
                "android.permission.ACCESS_NETWORK_STATE",
                "android.permission.ACCESS_WIFI_STATE",
            )
        for (permission in required) {
            assertTrue("$permission must be declared", manifest.contains(permission))
        }
    }

    @Test
    fun `multicast permission is declared to satisfy fgs connectedDevice gate`() {
        // CHANGE_WIFI_MULTICAST_STATE was originally pulled in for the
        // JmDNS in-app publisher's MulticastLock. After the #98 migration
        // to NsdManager that lock is unused, but the permission was
        // re-declared in commit 16fbf43 to satisfy the Android 14+
        // foreground-service-type gate for `connectedDevice` (the FGS
        // type used by ReceiverForegroundService). The platform requires
        // the app to hold at least one permission from a fixed set when
        // starting that FGS type, and CHANGE_WIFI_MULTICAST_STATE is the
        // cleanest normal-protection-level entry that does not depend on
        // a runtime grant.
        assertTrue(
            "CHANGE_WIFI_MULTICAST_STATE must be declared so the receiver FGS can launch on API 34+",
            manifest.contains("android.permission.CHANGE_WIFI_MULTICAST_STATE"),
        )
    }

    @Test
    fun `request ignore battery optimizations is declared for issue 47`() {
        // The OEM-aware battery-optimization banner (#47) opens
        // ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS on stock Android
        // as its generic fallback. Without REQUEST_IGNORE_BATTERY_OPTIMIZATIONS
        // declared in the manifest, the system Settings activity still
        // shows but the toggle is a no-op, so the user cannot actually
        // exempt the app — a regression that would render the entire
        // banner pointless on Pixel devices.
        assertTrue(
            "REQUEST_IGNORE_BATTERY_OPTIMIZATIONS must be declared so #47 banner can grant exemption",
            manifest.contains("android.permission.REQUEST_IGNORE_BATTERY_OPTIMIZATIONS"),
        )
    }

    @Test
    fun `bluetooth advertise declared for phase 2 ble auto-discovery`() {
        // BLUETOOTH_ADVERTISE is required (API 31+) so we can broadcast
        // BOTH:
        //   * the sender-side Quick Share pulse on `0xFE2C` that wakes
        //     nearby receivers (#31 / #32), and
        //   * the receiver-side fast-advertisement pulse on `0xFEF3`
        //     that makes us visible to stock Quick Share pickers
        //     (#121).
        // It does not need neverForLocation — that flag is only
        // meaningful for SCAN.
        assertTrue(
            "BLUETOOTH_ADVERTISE must be declared",
            manifest.contains("android.permission.BLUETOOTH_ADVERTISE"),
        )
    }

    @Test
    fun `bluetooth scan declared with neverForLocation flag`() {
        // BLUETOOTH_SCAN must use neverForLocation; without it the
        // platform forces the user to also grant ACCESS_FINE_LOCATION,
        // which we explicitly avoid for our scan-pulse-only use case
        // (#31 / #33).
        val block = usesPermissionBlockFor("android.permission.BLUETOOTH_SCAN")
        assertTrue(
            "BLUETOOTH_SCAN must use neverForLocation",
            block.contains("android:usesPermissionFlags=\"neverForLocation\""),
        )
    }

    @Test
    fun `legacy bluetooth permissions are capped at api 30`() {
        // Pre-API-31 devices use the install-time BLUETOOTH /
        // BLUETOOTH_ADMIN model. We still need them declared so the app
        // works on API 24–30, but they must be capped with
        // maxSdkVersion=30 so API 31+ devices use the runtime variants
        // exclusively (otherwise the install-time grant is silently
        // applied alongside the runtime permission, which confuses
        // some manufacturers' permission auditors).
        val legacyBluetooth = usesPermissionBlockFor("android.permission.BLUETOOTH")
        assertTrue(
            "Legacy BLUETOOTH must declare android:maxSdkVersion=\"30\"",
            legacyBluetooth.contains("android:maxSdkVersion=\"30\""),
        )
        val legacyBluetoothAdmin = usesPermissionBlockFor("android.permission.BLUETOOTH_ADMIN")
        assertTrue(
            "Legacy BLUETOOTH_ADMIN must declare android:maxSdkVersion=\"30\"",
            legacyBluetoothAdmin.contains("android:maxSdkVersion=\"30\""),
        )
    }

    @Test
    fun `bluetooth connect is not declared yet`() {
        // BLUETOOTH_CONNECT is only needed when initiating or accepting
        // GATT connections — Phase 2 only advertises and scans, so
        // declaring CONNECT today would surface a permission prompt the
        // app does not need. Guard against accidentally pulling it in
        // before BLE L2CAP support lands.
        assertFalse(
            "BLUETOOTH_CONNECT must NOT be declared until BLE L2CAP support lands",
            manifest.contains("android.permission.BLUETOOTH_CONNECT"),
        )
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

    /**
     * Returns the substring of the manifest that holds the single
     * `<uses-permission>` element for [permission], from `<uses-permission`
     * up to (but excluding) the closing `/>`. Used to scope per-permission
     * flag assertions (e.g. neverForLocation, maxSdkVersion) so they can
     * tell which `<uses-permission>` element they are actually inspecting.
     *
     * Fails the calling test if the permission is not found at all.
     */
    private fun usesPermissionBlockFor(permission: String): String {
        val needle = "android:name=\"$permission\""
        assertTrue(
            "$permission must be declared",
            manifest.contains(needle),
        )
        // Walk back from the name attribute to the opening tag, then
        // forward to the closing slash. Self-closing `<uses-permission .../>`
        // tags are the only shape we use for these declarations, so this
        // boundary works even when several permissions live next to one
        // another in the file.
        val nameIndex = manifest.indexOf(needle)
        val openIndex = manifest.lastIndexOf("<uses-permission", nameIndex)
        val closeIndex = manifest.indexOf("/>", nameIndex)
        check(openIndex >= 0 && closeIndex > openIndex) {
            "Could not locate <uses-permission .../> block for $permission"
        }
        return manifest.substring(openIndex, closeIndex)
    }
}
