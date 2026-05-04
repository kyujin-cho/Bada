/*
 * Copyright 2026 WhenVivoMeetsGoogle contributors.
 *
 * Licensed under the Apache License, Version 2.0.
 */
package io.github.kyujincho.wvmg.service.receiver

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test
import java.nio.charset.StandardCharsets

class AdvertisedDeviceNamePreferencesTest {
    @Test
    fun `setCustomName persists the canonical trimmed value`() {
        val prefs = FakeSharedPreferences()
        val sut = AdvertisedDeviceNamePreferences(prefs)

        val stored = sut.setCustomName("  Pixel 9 Pro  ")

        assertThat(stored).isEqualTo("Pixel 9 Pro")
        assertThat(prefs.values[AdvertisedDeviceNamePreferences.KEY_CUSTOM_NAME]).isEqualTo("Pixel 9 Pro")
        assertThat(AdvertisedDeviceNamePreferences(prefs).getCustomName()).isEqualTo("Pixel 9 Pro")
    }

    @Test
    fun `blank custom name is treated as unset`() {
        val prefs = FakeSharedPreferences()
        val sut = AdvertisedDeviceNamePreferences(prefs)

        val stored = sut.setCustomName("   ")

        assertThat(stored).isNull()
        assertThat(prefs.values).doesNotContainKey(AdvertisedDeviceNamePreferences.KEY_CUSTOM_NAME)
        assertThat(sut.getCustomName()).isNull()
    }

    @Test
    fun `setCustomName truncates on a UTF-8 code point boundary`() {
        val prefs = FakeSharedPreferences()
        val sut = AdvertisedDeviceNamePreferences(prefs)

        val stored = sut.setCustomName("abcdefghijklmno😀x")

        assertThat(stored).isEqualTo("abcdefghijklmno😀")
        assertThat(stored!!.toByteArray(StandardCharsets.UTF_8).size)
            .isEqualTo(AdvertisedDeviceNames.MAX_DEVICE_NAME_BYTES)
    }
}
