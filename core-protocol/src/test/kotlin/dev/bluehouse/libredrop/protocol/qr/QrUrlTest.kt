/*
 * Copyright 2026 LibreDrop contributors.
 *
 * Licensed under the Apache License, Version 2.0.
 */
package dev.bluehouse.libredrop.protocol.qr

import com.google.common.truth.Truth.assertThat
import dev.bluehouse.libredrop.protocol.endpoint.Base64Url
import org.junit.jupiter.api.Test

/**
 * Tests for the [QrUrl] build/parse codec.
 *
 * The URL is interop-critical and consists of three concatenated string
 * pieces:
 *
 *  1. A fixed canonical prefix (`https://quickshare.google/qrcode`).
 *  2. The fragment marker `#key=`.
 *  3. URL-safe base64 (no padding) of the 35-byte QR payload.
 *
 * The parser is deliberately strict: any deviation from the canonical
 * shape returns `null`. We test both the happy path and the failure modes.
 */
class QrUrlTest {
    @Test
    fun `build produces the canonical Quick Share URL shape`() {
        val keyData =
            QrKeyData(
                versionByte = 0x02,
                xCoordinate = ByteArray(QrKeyData.X_COORDINATE_LEN) { 0x42 },
            )
        val url = QrUrl.build(keyData)
        assertThat(url).startsWith("https://quickshare.google/qrcode#key=")
        // The fragment payload should be the URL-safe base64 of the
        // 35-byte encoded form, without any padding.
        val expectedPayload = Base64Url.encode(keyData.encode())
        assertThat(url).isEqualTo("https://quickshare.google/qrcode#key=$expectedPayload")
    }

    @Test
    fun `build then parse round-trips the QR key data`() {
        val keyData =
            QrKeyData(
                versionByte = 0x03,
                xCoordinate = ByteArray(QrKeyData.X_COORDINATE_LEN) { (it * 7).toByte() },
            )
        val parsed = QrUrl.parse(QrUrl.build(keyData))
        assertThat(parsed).isEqualTo(keyData)
    }

    @Test
    fun `parse returns null for a URL with no fragment`() {
        assertThat(QrUrl.parse("https://quickshare.google/qrcode")).isNull()
        assertThat(QrUrl.parse("https://quickshare.google/qrcode?key=foo")).isNull()
    }

    @Test
    fun `parse returns null for a URL with the wrong scheme or host`() {
        val payload =
            Base64Url.encode(
                QrKeyData(0x02, ByteArray(QrKeyData.X_COORDINATE_LEN)).encode(),
            )
        assertThat(QrUrl.parse("http://quickshare.google/qrcode#key=$payload")).isNull()
        assertThat(QrUrl.parse("https://example.com/qrcode#key=$payload")).isNull()
        assertThat(QrUrl.parse("https://quickshare.google/wrong#key=$payload")).isNull()
    }

    @Test
    fun `parse returns null when the fragment is missing the key parameter`() {
        assertThat(QrUrl.parse("https://quickshare.google/qrcode#")).isNull()
        assertThat(QrUrl.parse("https://quickshare.google/qrcode#key=")).isNull()
        assertThat(QrUrl.parse("https://quickshare.google/qrcode#other=value")).isNull()
    }

    @Test
    fun `parse returns null for malformed base64`() {
        assertThat(QrUrl.parse("https://quickshare.google/qrcode#key=not_valid_b64!@#$"))
            .isNull()
    }

    @Test
    fun `parse returns null when the decoded payload is the wrong length`() {
        // 34 bytes of zeros, base64-encoded — decodes successfully but
        // QrKeyData.parse() rejects it for the wrong total length.
        val tooShort = Base64Url.encode(ByteArray(QrKeyData.TOTAL_LEN - 1))
        assertThat(QrUrl.parse("https://quickshare.google/qrcode#key=$tooShort")).isNull()

        val tooLong = Base64Url.encode(ByteArray(QrKeyData.TOTAL_LEN + 1))
        assertThat(QrUrl.parse("https://quickshare.google/qrcode#key=$tooLong")).isNull()
    }

    @Test
    fun `parse returns null when the decoded payload has non-zero leading bytes`() {
        val bad = ByteArray(QrKeyData.TOTAL_LEN)
        bad[0] = 0x01
        val encoded = Base64Url.encode(bad)
        assertThat(QrUrl.parse("https://quickshare.google/qrcode#key=$encoded")).isNull()
    }

    @Test
    fun `build output is ASCII safe for embedding in a QR bitmap`() {
        // Worst-case payload: every coordinate byte is 0xFF, which encodes
        // to a base64 string with the most URL-unsafe characters. With the
        // URL-safe alphabet none of '+', '/', or '=' should appear in the
        // fragment payload.
        val keyData =
            QrKeyData(
                versionByte = 0x02,
                xCoordinate = ByteArray(QrKeyData.X_COORDINATE_LEN) { 0xFF.toByte() },
            )
        val url = QrUrl.build(keyData)
        for (ch in url) {
            assertThat(ch.code).isLessThan(0x80)
        }
        // The fragment payload (after `#key=`) must use the URL-safe
        // alphabet without padding. The path itself contains a `/` which
        // is fine; we only constrain the payload here.
        val payload = url.substringAfter("#key=")
        assertThat(payload).doesNotContain("+")
        assertThat(payload).doesNotContain("/")
        assertThat(payload).doesNotContain("=")
    }
}
