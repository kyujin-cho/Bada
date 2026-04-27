/*
 * Copyright 2026 WhenVivoMeetsGoogle contributors.
 *
 * Licensed under the Apache License, Version 2.0.
 */
package io.github.kyujincho.wvmg.protocol.endpoint

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test
import kotlin.random.Random

/**
 * Verifies the URL-safe base64 codec used for the mDNS TXT key `n` value.
 *
 * - Encode output never carries `=` padding (Quick Share peers strip it).
 * - Encode uses the URL-safe alphabet (`-_`), not the standard one (`+/`).
 * - Decode accepts both padded and unpadded input for interop with peers
 *   that do not strip padding.
 */
class Base64UrlTest {
    @Test
    fun `encode produces no equal-sign padding`() {
        // 1 byte of input → standard base64 would emit 2 padding chars.
        assertThat(Base64Url.encode(byteArrayOf(0x66))).isEqualTo("Zg")
        // 2 bytes → 1 padding char in standard base64.
        assertThat(Base64Url.encode(byteArrayOf(0x66, 0x6F))).isEqualTo("Zm8")
    }

    @Test
    fun `encode uses URL-safe alphabet`() {
        // Bytes 0xFB, 0xFF, 0xBF deliberately produce a non-trivial mix that
        // would include `+` and `/` under the standard alphabet but uses
        // `-` and `_` under URL-safe.
        val bytes = byteArrayOf(0xFB.toByte(), 0xFF.toByte(), 0xBF.toByte())
        val encoded = Base64Url.encode(bytes)
        assertThat(encoded).doesNotContain("+")
        assertThat(encoded).doesNotContain("/")
        // Sanity check: at least one URL-safe replacement character appears.
        assertThat(encoded).matches("[A-Za-z0-9_\\-]+")
    }

    @Test
    fun `decode accepts unpadded input`() {
        val original = byteArrayOf(0x01, 0x02, 0x03, 0x04, 0x05)
        val encoded = Base64Url.encode(original)
        // Sanity check: encoder produces no padding for this length either.
        assertThat(encoded).doesNotContain("=")
        assertThat(Base64Url.decode(encoded)).isEqualTo(original)
    }

    @Test
    fun `decode accepts padded input for interop`() {
        // Same byte sequence, manually padded out with `=` characters.
        val padded = "AQIDBAU="
        assertThat(Base64Url.decode(padded)).isEqualTo(byteArrayOf(0x01, 0x02, 0x03, 0x04, 0x05))
    }

    @Test
    fun `decode returns null on illegal characters`() {
        // `+` and `/` are not legal in the URL-safe alphabet.
        assertThat(Base64Url.decode("AA+/")).isNull()
        // Characters outside both alphabets.
        assertThat(Base64Url.decode("!!!!")).isNull()
    }

    @Test
    fun `randomized round-trip across 100 byte arrays of varying length`() {
        val rng = Random(0xBEEF)
        repeat(100) {
            val len = rng.nextInt(0, 64)
            val bytes = ByteArray(len).also { rng.nextBytes(it) }
            val encoded = Base64Url.encode(bytes)
            assertThat(encoded).doesNotContain("=")
            assertThat(Base64Url.decode(encoded)).isEqualTo(bytes)
        }
    }

    @Test
    fun `encode then decode round-trips a serialized EndpointInfo`() {
        // End-to-end smoke test: this is exactly how :discovery-android will
        // publish the TXT record value once it lands.
        val info =
            EndpointInfo(
                version = 1,
                hidden = false,
                deviceType = DeviceType.PHONE,
                reserved = false,
                metadata = ByteArray(EndpointInfo.METADATA_LEN) { it.toByte() },
                deviceName = "Pixel 9 Pro",
            )
        val encoded = Base64Url.encode(info.serialize())
        val decoded = Base64Url.decode(encoded)
        assertThat(decoded).isNotNull()
        assertThat(EndpointInfo.parse(decoded!!)).isEqualTo(info)
    }
}
