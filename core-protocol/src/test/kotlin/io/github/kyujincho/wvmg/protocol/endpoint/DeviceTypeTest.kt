/*
 * Copyright 2026 WhenVivoMeetsGoogle contributors.
 *
 * Licensed under the Apache License, Version 2.0.
 */
package io.github.kyujincho.wvmg.protocol.endpoint

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

/**
 * Pins the [DeviceType] raw values to PROTOCOL.md so we cannot accidentally
 * shuffle them in a future refactor — peers on the wire identify themselves
 * by the integer value, not the enum name.
 */
class DeviceTypeTest {
    @Test
    fun `raw values match PROTOCOL md`() {
        assertThat(DeviceType.UNKNOWN.raw).isEqualTo(0)
        assertThat(DeviceType.PHONE.raw).isEqualTo(1)
        assertThat(DeviceType.TABLET.raw).isEqualTo(2)
        assertThat(DeviceType.LAPTOP.raw).isEqualTo(3)
        assertThat(DeviceType.CAR.raw).isEqualTo(4)
        assertThat(DeviceType.FOLDABLE.raw).isEqualTo(5)
        assertThat(DeviceType.XR.raw).isEqualTo(6)
    }

    @Test
    fun `fromRaw maps documented values to their enum`() {
        for (type in DeviceType.entries) {
            assertThat(DeviceType.fromRaw(type.raw)).isEqualTo(type)
        }
    }

    @Test
    fun `fromRaw collapses undefined raw values to UNKNOWN`() {
        assertThat(DeviceType.fromRaw(7)).isEqualTo(DeviceType.UNKNOWN)
        // Out-of-range raw values shouldn't crash either; the parser only
        // ever feeds 0..7 in practice, but defensive coverage doesn't hurt.
        assertThat(DeviceType.fromRaw(99)).isEqualTo(DeviceType.UNKNOWN)
        assertThat(DeviceType.fromRaw(-1)).isEqualTo(DeviceType.UNKNOWN)
    }
}
