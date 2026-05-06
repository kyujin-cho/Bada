/*
 * Copyright 2026 LibreDrop contributors.
 *
 * Licensed under the Apache License, Version 2.0.
 */
package dev.bluehouse.bada.discovery

import com.google.common.truth.Truth.assertThat
import dev.bluehouse.bada.protocol.endpoint.Base64Url
import org.junit.jupiter.api.Test
import java.security.SecureRandom

/**
 * Byte-exact validation of the Quick Share service-instance name layout.
 *
 * The format is fixed by Google's protocol so any deviation here is an
 * interop break. Tests therefore assert against literal byte values
 * rather than going through the [InstanceName] object's own constants.
 */
class InstanceNameTest {
    @Test
    fun `raw bytes follow the documented layout`() {
        val random = DeterministicSecureRandom(seed = 42L)
        val raw = InstanceName.generateRawBytes(random)

        assertThat(raw).hasLength(QuickShareMdns.INSTANCE_NAME_RAW_LEN)
        // Byte 0: PCP marker (0x23).
        assertThat(raw[0]).isEqualTo(0x23.toByte())

        // Bytes 1..4: random ASCII alphanumerics.
        for (i in 1..4) {
            val ch = raw[i].toInt().toChar()
            assertThat(ch.isLetterOrDigit()).isTrue()
            assertThat(ch.code and 0x80).isEqualTo(0)
        }

        // Bytes 5..7: SHA-256("NearbySharing") prefix (0xFC, 0x9F, 0x5E).
        assertThat(raw[5]).isEqualTo(0xFC.toByte())
        assertThat(raw[6]).isEqualTo(0x9F.toByte())
        assertThat(raw[7]).isEqualTo(0x5E.toByte())

        // Bytes 8..9: reserved zeros.
        assertThat(raw[8]).isEqualTo(0.toByte())
        assertThat(raw[9]).isEqualTo(0.toByte())
    }

    @Test
    fun `generate produces URL-safe base64 of a freshly generated raw buffer`() {
        // SecureRandom is not deterministic enough (default-instance seeding
        // varies across implementations), so we assert the structural
        // invariants instead of byte equality.
        val encoded = InstanceName.generate()

        // The encoded form must contain only URL-safe base64 chars (no '+' or '/' or '=').
        encoded.forEach { ch ->
            assertThat(ch in 'A'..'Z' || ch in 'a'..'z' || ch in '0'..'9' || ch == '-' || ch == '_').isTrue()
        }

        // Decoding the encoding must round-trip to a 10-byte buffer that
        // matches the documented layout.
        val decoded = Base64Url.decode(encoded)
        assertThat(decoded).isNotNull()
        assertThat(decoded!!).hasLength(QuickShareMdns.INSTANCE_NAME_RAW_LEN)
        assertThat(decoded[0]).isEqualTo(0x23.toByte())
        assertThat(decoded[5]).isEqualTo(0xFC.toByte())
        assertThat(decoded[6]).isEqualTo(0x9F.toByte())
        assertThat(decoded[7]).isEqualTo(0x5E.toByte())
        assertThat(decoded[8]).isEqualTo(0.toByte())
        assertThat(decoded[9]).isEqualTo(0.toByte())
    }

    @Test
    fun `generate can pin endpoint ID bytes`() {
        val endpointId = "WvMg".toByteArray(Charsets.US_ASCII)
        val encoded = InstanceName.generate(endpointId, DeterministicSecureRandom(seed = 1L))
        val decoded = InstanceName.decodeRawBytes(encoded)

        assertThat(decoded).isNotNull()
        assertThat(InstanceName.extractEndpointId(decoded!!)!!).isEqualTo(endpointId)
        assertThat(decoded[0]).isEqualTo(0x23.toByte())
        assertThat(decoded[5]).isEqualTo(0xFC.toByte())
        assertThat(decoded[6]).isEqualTo(0x9F.toByte())
        assertThat(decoded[7]).isEqualTo(0x5E.toByte())
        assertThat(decoded[8]).isEqualTo(0.toByte())
        assertThat(decoded[9]).isEqualTo(0.toByte())
    }

    @Test
    fun `decodeRawBytes round-trips a freshly generated name`() {
        val encoded = InstanceName.generate(DeterministicSecureRandom(seed = 1234L))
        val decoded = InstanceName.decodeRawBytes(encoded)
        assertThat(decoded).isNotNull()
        assertThat(decoded!!).hasLength(10)
        // Re-encoding the decoded bytes yields the original string (since
        // the encoder uses no padding, that's a strict round-trip check).
        assertThat(Base64Url.encode(decoded)).isEqualTo(encoded)
    }

    @Test
    fun `decodeRawBytes returns null for malformed input`() {
        assertThat(InstanceName.decodeRawBytes("@@not-valid@@")).isNull()
        // 8 bytes of base64 (length 11) decodes to wrong length.
        assertThat(InstanceName.decodeRawBytes("AAAAAAAAAAA")).isNull()
    }

    @Test
    fun `extractEndpointId returns the 4 ID bytes`() {
        val raw =
            byteArrayOf(
                0x23,
                'a'.code.toByte(),
                'B'.code.toByte(),
                '7'.code.toByte(),
                'z'.code.toByte(),
                0xFC.toByte(),
                0x9F.toByte(),
                0x5E.toByte(),
                0x00,
                0x00,
            )
        val id = InstanceName.extractEndpointId(raw)
        assertThat(id).isNotNull()
        assertThat(id!!.size).isEqualTo(4)
        assertThat(String(id, Charsets.US_ASCII)).isEqualTo("aB7z")
    }

    @Test
    fun `extractEndpointId rejects wrong-length input`() {
        assertThat(InstanceName.extractEndpointId(ByteArray(5))).isNull()
        assertThat(InstanceName.extractEndpointId(ByteArray(11))).isNull()
    }

    /**
     * Lightweight deterministic SecureRandom: seeded with the provided
     * value via the standard `setSeed` contract so the generated bytes are
     * reproducible across runs. This is **not** secure (the test name
     * notwithstanding) but it lets us assert on the alphabet without
     * making the test flaky.
     */
    private class DeterministicSecureRandom(
        seed: Long,
    ) : SecureRandom() {
        init {
            // Bypass the default OS-entropy seeding so output is fully
            // determined by the seed we pass in.
            setSeed(seed)
        }
    }
}
