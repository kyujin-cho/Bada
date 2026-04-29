/*
 * Copyright 2026 WhenVivoMeetsGoogle contributors.
 *
 * Licensed under the Apache License, Version 2.0.
 */
package io.github.kyujincho.wvmg.protocol.medium

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/**
 * Unit coverage for [UpgradePathCredentials.Bluetooth] (#51).
 *
 * The companion-object MAC <-> string helpers are the load-bearing
 * piece — every wire round-trip goes through them — so the parser must
 * be strict (length, hex-only) and the formatter must produce exactly
 * the canonical "AA:BB:CC:DD:EE:FF" shape Android's
 * `BluetoothAdapter.getRemoteDevice(String)` accepts.
 */
class UpgradePathCredentialsBluetoothTest {
    @Test
    fun `bytesToMacString formats the canonical colon-separated upper-case shape`() {
        val mac = byteArrayOf(0xAA.toByte(), 0xBB.toByte(), 0xCC.toByte(), 0xDD.toByte(), 0xEE.toByte(), 0xFF.toByte())
        assertThat(UpgradePathCredentials.Bluetooth.bytesToMacString(mac)).isEqualTo("AA:BB:CC:DD:EE:FF")
    }

    @Test
    fun `bytesToMacString preserves zeros and low nibbles`() {
        val mac = byteArrayOf(0x00, 0x01, 0x0A, 0x10, 0x7F, 0x80.toByte())
        assertThat(UpgradePathCredentials.Bluetooth.bytesToMacString(mac)).isEqualTo("00:01:0A:10:7F:80")
    }

    @Test
    fun `macStringToBytes parses the canonical shape`() {
        val bytes = UpgradePathCredentials.Bluetooth.macStringToBytes("AA:BB:CC:DD:EE:FF")
        assertThat(bytes).isEqualTo(
            byteArrayOf(0xAA.toByte(), 0xBB.toByte(), 0xCC.toByte(), 0xDD.toByte(), 0xEE.toByte(), 0xFF.toByte()),
        )
    }

    @Test
    fun `macStringToBytes accepts hyphen-separated and mixed case`() {
        val bytes = UpgradePathCredentials.Bluetooth.macStringToBytes("aa-bb-cc-dd-ee-ff")
        assertThat(bytes).isEqualTo(
            byteArrayOf(0xAA.toByte(), 0xBB.toByte(), 0xCC.toByte(), 0xDD.toByte(), 0xEE.toByte(), 0xFF.toByte()),
        )
    }

    @Test
    fun `macStringToBytes round-trips bytesToMacString`() {
        val original = byteArrayOf(0x12, 0x34, 0x56, 0x78, 0x9A.toByte(), 0xBC.toByte())
        val formatted = UpgradePathCredentials.Bluetooth.bytesToMacString(original)
        val reparsed = UpgradePathCredentials.Bluetooth.macStringToBytes(formatted)
        assertThat(reparsed).isEqualTo(original)
    }

    @Test
    fun `macStringToBytes rejects strings whose hex digit count is not 12`() {
        assertThrows<IllegalArgumentException> {
            UpgradePathCredentials.Bluetooth.macStringToBytes("AA:BB:CC:DD:EE")
        }
        assertThrows<IllegalArgumentException> {
            UpgradePathCredentials.Bluetooth.macStringToBytes("AA:BB:CC:DD:EE:FF:00")
        }
    }

    @Test
    fun `macStringToBytes rejects non-hex digits`() {
        assertThrows<IllegalArgumentException> {
            UpgradePathCredentials.Bluetooth.macStringToBytes("ZZ:BB:CC:DD:EE:FF")
        }
    }

    @Test
    fun `constructor rejects MAC of wrong length`() {
        assertThrows<IllegalArgumentException> {
            UpgradePathCredentials.Bluetooth(
                macAddress = byteArrayOf(0x01, 0x02),
                serviceUuid = "a82efa21-ae5c-3dde-9bbc-f16da7b16c1a",
            )
        }
    }

    @Test
    fun `constructor rejects empty serviceUuid`() {
        assertThrows<IllegalArgumentException> {
            UpgradePathCredentials.Bluetooth(
                macAddress = ByteArray(UpgradePathCredentials.Bluetooth.MAC_ADDRESS_BYTES),
                serviceUuid = "",
            )
        }
    }

    @Test
    fun `equals uses content-equality on the MAC byte array`() {
        val a =
            UpgradePathCredentials.Bluetooth(
                macAddress = byteArrayOf(0x01, 0x02, 0x03, 0x04, 0x05, 0x06),
                serviceUuid = "a82efa21-ae5c-3dde-9bbc-f16da7b16c1a",
            )
        val b =
            UpgradePathCredentials.Bluetooth(
                macAddress = byteArrayOf(0x01, 0x02, 0x03, 0x04, 0x05, 0x06),
                serviceUuid = "a82efa21-ae5c-3dde-9bbc-f16da7b16c1a",
            )
        assertThat(a).isEqualTo(b)
        assertThat(a.hashCode()).isEqualTo(b.hashCode())
    }

    @Test
    fun `equals differs when the MAC differs`() {
        val a =
            UpgradePathCredentials.Bluetooth(
                macAddress = byteArrayOf(0x01, 0x02, 0x03, 0x04, 0x05, 0x06),
                serviceUuid = "a82efa21-ae5c-3dde-9bbc-f16da7b16c1a",
            )
        val b =
            UpgradePathCredentials.Bluetooth(
                macAddress = byteArrayOf(0x01, 0x02, 0x03, 0x04, 0x05, 0x07),
                serviceUuid = "a82efa21-ae5c-3dde-9bbc-f16da7b16c1a",
            )
        assertThat(a).isNotEqualTo(b)
    }

    @Test
    fun `medium is BLUETOOTH`() {
        val creds =
            UpgradePathCredentials.Bluetooth(
                macAddress = byteArrayOf(0x01, 0x02, 0x03, 0x04, 0x05, 0x06),
                serviceUuid = "a82efa21-ae5c-3dde-9bbc-f16da7b16c1a",
            )
        assertThat(creds.medium).isEqualTo(Medium.BLUETOOTH)
    }
}
