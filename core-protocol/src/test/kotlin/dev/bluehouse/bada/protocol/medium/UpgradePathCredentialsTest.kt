/*
 * Copyright 2026 Bada contributors.
 *
 * Licensed under the Apache License, Version 2.0.
 */
package dev.bluehouse.bada.protocol.medium

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

/**
 * Structural-equality coverage for the per-medium [UpgradePathCredentials]
 * subtypes. Important because each subtype with a `ByteArray` field
 * needs an explicit `equals`/`hashCode` override (Kotlin `data class`
 * defaults to reference equality on arrays); a regression there breaks
 * round-trip tests in `BandwidthUpgradeFramesTest` with confusing
 * "expected X got X" failures.
 */
class UpgradePathCredentialsTest {
    @Test
    fun `WifiAware equals compares IPv6 bytes structurally`() {
        val ipv6a = byteArrayOf(0xFE.toByte(), 0x80.toByte(), 0, 0, 0, 0, 0, 0, 1, 2, 3, 4, 5, 6, 7, 8)
        val ipv6b = byteArrayOf(0xFE.toByte(), 0x80.toByte(), 0, 0, 0, 0, 0, 0, 1, 2, 3, 4, 5, 6, 7, 8)
        val first =
            UpgradePathCredentials.WifiAware(
                serviceName = "bada-quickshare-aware",
                ipv6Address = ipv6a,
                port = 1234,
                passphrase = "secret",
            )
        val second =
            UpgradePathCredentials.WifiAware(
                serviceName = "bada-quickshare-aware",
                ipv6Address = ipv6b,
                port = 1234,
                passphrase = "secret",
            )
        assertThat(first).isEqualTo(second)
        assertThat(first.hashCode()).isEqualTo(second.hashCode())
    }

    @Test
    fun `WifiAware not-equal when port differs`() {
        val ipv6 = ByteArray(16)
        val first =
            UpgradePathCredentials.WifiAware(
                serviceName = "svc",
                ipv6Address = ipv6,
                port = 1,
                passphrase = "p",
            )
        val second = first.copy(port = 2)
        assertThat(first).isNotEqualTo(second)
    }

    @Test
    fun `WifiAware not-equal when passphrase differs`() {
        val ipv6 = ByteArray(16)
        val first =
            UpgradePathCredentials.WifiAware(
                serviceName = "svc",
                ipv6Address = ipv6,
                port = 1,
                passphrase = "alpha",
            )
        val second = first.copy(passphrase = "beta")
        assertThat(first).isNotEqualTo(second)
    }

    @Test
    fun `WifiAware medium is always WIFI_AWARE`() {
        val creds =
            UpgradePathCredentials.WifiAware(
                serviceName = "svc",
                ipv6Address = ByteArray(16),
                port = 0,
                passphrase = "passphrase",
            )
        assertThat(creds.medium).isEqualTo(Medium.WIFI_AWARE)
    }
}
