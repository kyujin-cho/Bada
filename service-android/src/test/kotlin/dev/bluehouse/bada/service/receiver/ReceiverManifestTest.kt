/*
 * Copyright 2026 LibreDrop contributors.
 *
 * Licensed under the Apache License, Version 2.0.
 */
package dev.bluehouse.bada.service.receiver

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test
import java.io.File

/**
 * Manifest-level regression test for the Phase 1 foreground service
 * declarations contributed by [ReceiverForegroundService] (#21).
 *
 * Mirrors the approach used by `:app/src/test/.../AndroidManifestPermissionsTest`:
 * we read the manifest as plain text rather than pulling in Robolectric.
 * That keeps the unit test layer fast, deterministic, and free of
 * Android-runtime gotchas.
 */
class ReceiverManifestTest {
    private val manifest: String by lazy {
        // The working directory for module unit tests is the module
        // root, so a relative path is enough.
        val file = File("src/main/AndroidManifest.xml")
        assertThat(file.exists()).isTrue()
        file.readText()
    }

    @Test
    fun `foreground service declared with connectedDevice type`() {
        assertThat(manifest).contains(".receiver.ReceiverForegroundService")
        assertThat(manifest).contains("android:foregroundServiceType=\"connectedDevice\"")
    }

    @Test
    fun `foreground service is not exported`() {
        // The service is internal — exported=true would let other apps
        // unilaterally start our receiver, which we never want.
        val serviceBlock =
            manifest
                .substringAfter(".receiver.ReceiverForegroundService")
                .substringBefore("/>")
        assertThat(serviceBlock).contains("android:exported=\"false\"")
    }

    @Test
    fun `foreground service permission declared`() {
        assertThat(manifest).contains("android.permission.FOREGROUND_SERVICE")
    }

    @Test
    fun `foreground service connected device permission declared`() {
        // Required on API 34+ to honour foregroundServiceType="connectedDevice".
        assertThat(manifest).contains("android.permission.FOREGROUND_SERVICE_CONNECTED_DEVICE")
    }

    @Test
    fun `legacy storage permission still scoped to API 28`() {
        // Regression guard: the WRITE_EXTERNAL_STORAGE entry from #23
        // must keep its maxSdkVersion="28" cap so we don't trigger
        // unnecessary runtime prompts on API 29+.
        assertThat(manifest).contains("android.permission.WRITE_EXTERNAL_STORAGE")
        assertThat(manifest).contains("android:maxSdkVersion=\"28\"")
    }
}
