/*
 * Copyright 2026 WhenVivoMeetsGoogle contributors.
 *
 * Licensed under the Apache License, Version 2.0.
 */
package io.github.kyujincho.wvmg.discovery

import android.os.Build
import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

/**
 * Pure-JVM tests for [AndroidNsdRegistrar]'s API-level branching that
 * applies binary TXT attributes to an `NsdServiceInfo`.
 *
 * The Android unit-tests-with-default-values harness can't load
 * `NsdServiceInfo` as-is (the platform stub jar lacks Code attributes
 * for non-abstract methods, throwing `ClassFormatError` even when a
 * test merely instantiates the class). To work around that, the
 * production code's `applyAttributes` helper is exposed in a form that
 * delegates the platform calls through caller-supplied lambdas; the
 * tests below drive both API-level branches against recording lambdas.
 *
 * Bit-exact wire-format equivalence between the two paths is enforced
 * structurally: ISO-8859-1 maps every byte 0x00..0xFF to a single
 * 1-to-1 codepoint, so the legacy String-flavoured setter and the API
 * 33+ ByteArray-flavoured setter emit the same byte sequence on the
 * DNS-SD wire by construction.
 */
class AndroidNsdRegistrarTest {
    private val payload =
        QuickShareMdns.TXT_KEY_ENDPOINT_INFO to
            byteArrayOf(0x00, 0x10.toByte(), 0x80.toByte(), 0xFF.toByte())

    @Test
    fun `API 33+ branch invokes the byte-array setter exactly once per attribute`() {
        var bytesCalls = 0
        var stringCalls = 0
        var capturedKey: String? = null
        var capturedBytes: ByteArray? = null
        AndroidNsdRegistrar.applyAttributes(
            attributes = mapOf(payload),
            sdkInt = Build.VERSION_CODES.TIRAMISU,
            setBytes = { k, v ->
                bytesCalls++
                capturedKey = k
                capturedBytes = v
            },
            setString = { _, _ -> stringCalls++ },
        )
        assertThat(bytesCalls).isEqualTo(1)
        assertThat(stringCalls).isEqualTo(0)
        assertThat(capturedKey).isEqualTo(payload.first)
        assertThat(capturedBytes).isEqualTo(payload.second)
    }

    @Test
    fun `pre-API-33 branch invokes the ISO-8859-1 string setter exactly once per attribute`() {
        var bytesCalls = 0
        var stringCalls = 0
        var capturedKey: String? = null
        var capturedString: String? = null
        AndroidNsdRegistrar.applyAttributes(
            attributes = mapOf(payload),
            sdkInt = Build.VERSION_CODES.S, // API 31 < TIRAMISU.
            setBytes = { _, _ -> bytesCalls++ },
            setString = { k, v ->
                stringCalls++
                capturedKey = k
                capturedString = v
            },
        )
        assertThat(stringCalls).isEqualTo(1)
        assertThat(bytesCalls).isEqualTo(0)
        assertThat(capturedKey).isEqualTo(payload.first)
        // The pre-API-33 branch wraps the bytes in a Latin-1 string so
        // they round-trip the platform's String setter without UTF-8
        // mangling. Decoding the captured String with ISO-8859-1 must
        // reproduce the original bytes exactly.
        assertThat(capturedString!!.toByteArray(Charsets.ISO_8859_1)).isEqualTo(payload.second)
    }

    @Test
    fun `both API branches emit identical wire bytes for the same payload`() {
        // Pin the wire-format equivalence we depend on for interop:
        // whichever branch fires, the bytes that hit the DNS-SD TXT
        // record are the same. The API 33+ branch passes bytes through
        // verbatim; the pre-33 branch wraps them in a Latin-1 string,
        // which round-trips byte-for-byte (every byte 0x00..0xFF maps
        // to a single codepoint).
        val capturedBytes = mutableListOf<Pair<String, ByteArray>>()
        val capturedFromString = mutableListOf<Pair<String, ByteArray>>()

        AndroidNsdRegistrar.applyAttributes(
            attributes = mapOf(payload),
            sdkInt = Build.VERSION_CODES.TIRAMISU,
            setBytes = { k, v -> capturedBytes += k to v },
            setString = { _, _ -> error("should not fire on API 33+") },
        )
        AndroidNsdRegistrar.applyAttributes(
            attributes = mapOf(payload),
            sdkInt = Build.VERSION_CODES.S,
            setBytes = { _, _ -> error("should not fire on pre-API-33") },
            setString = { k, v ->
                capturedFromString += k to v.toByteArray(Charsets.ISO_8859_1)
            },
        )

        assertThat(capturedBytes).hasSize(1)
        assertThat(capturedFromString).hasSize(1)
        assertThat(capturedBytes[0].first).isEqualTo(capturedFromString[0].first)
        assertThat(capturedBytes[0].second).isEqualTo(capturedFromString[0].second)
    }

    @Test
    fun `ISO-8859-1 round trip preserves all 256 byte values`() {
        // Latin-1 maps each byte 0x00..0xFF to a single codepoint, so
        // the round-trip String(bytes, ISO_8859_1).toByteArray(ISO_8859_1)
        // must reproduce the input byte-for-byte. Pin that property here
        // — it is the entire reason the legacy fallback is interop-safe.
        val expected = ByteArray(256) { it.toByte() }
        val asString = String(expected, Charsets.ISO_8859_1)
        val roundTripped = asString.toByteArray(Charsets.ISO_8859_1)
        assertThat(roundTripped).isEqualTo(expected)
    }
}
