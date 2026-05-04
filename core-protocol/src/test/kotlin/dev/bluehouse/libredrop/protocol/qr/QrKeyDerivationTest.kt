/*
 * Copyright 2026 LibreDrop contributors.
 *
 * Licensed under the Apache License, Version 2.0.
 */
package dev.bluehouse.libredrop.protocol.qr

import com.google.common.truth.Truth.assertThat
import dev.bluehouse.libredrop.protocol.test.QrCodeVectors
import org.junit.jupiter.api.Test
import java.nio.charset.StandardCharsets

/**
 * KAT tests for [QrKeyDerivation].
 *
 * The derivation is two HKDF-SHA256 calls — the [dev.bluehouse.libredrop.protocol.crypto.Hkdf]
 * implementation is itself locked down by the RFC 5869 vectors, so the
 * remaining surface to verify here is:
 *
 *  1. The exact info strings (`advertisingContext`, `encryptionKey`) — a
 *     typo would silently break interop with every Quick Share peer.
 *  2. The exact output length (16 bytes) — drift here would not even
 *     produce a runtime error, just a wrong-shaped key.
 *  3. Determinism across calls — the same keyData must always derive the
 *     same keys, otherwise sender/receiver matching is impossible.
 */
class QrKeyDerivationTest {
    @Test
    fun `info strings match the protocol spec exactly`() {
        // PROTOCOL.md mandates these two strings literally. Any typo
        // (e.g. "advertising_context" or "encryption_key") is an interop
        // break that would only show up in over-the-air testing.
        assertThat(QrKeyDerivation.ADVERTISING_INFO_BYTES)
            .isEqualTo("advertisingContext".toByteArray(StandardCharsets.US_ASCII))
        assertThat(QrKeyDerivation.NAME_ENCRYPTION_INFO_BYTES)
            .isEqualTo("encryptionKey".toByteArray(StandardCharsets.US_ASCII))
    }

    @Test
    fun `derived keys are exactly 16 bytes`() {
        val keyData = QrKeyData.generate().qrKeyData
        val keys = QrKeyDerivation.deriveKeys(keyData)
        assertThat(keys.advertisingToken.size).isEqualTo(QrKeyDerivation.DERIVED_KEY_LEN)
        assertThat(keys.nameEncryptionKey.size).isEqualTo(QrKeyDerivation.DERIVED_KEY_LEN)
        assertThat(QrKeyDerivation.DERIVED_KEY_LEN).isEqualTo(16)
    }

    @Test
    fun `derivation is deterministic across invocations`() {
        val keyData = QrKeyData.generate().qrKeyData
        val first = QrKeyDerivation.deriveKeys(keyData)
        val second = QrKeyDerivation.deriveKeys(keyData)
        assertThat(first).isEqualTo(second)
    }

    @Test
    fun `advertisingToken and nameEncryptionKey differ for the same keyData`() {
        // Sanity: the only difference between the two HKDF calls is the
        // info string. If the constants accidentally collided (e.g. both
        // were `"encryptionKey"`), the two keys would equal each other.
        val keyData = QrKeyData.generate().qrKeyData
        val keys = QrKeyDerivation.deriveKeys(keyData)
        assertThat(keys.advertisingToken).isNotEqualTo(keys.nameEncryptionKey)
    }

    @Test
    fun `KAT vector 1 — version 0x02, X equal 01 through 20`() {
        val v = QrCodeVectors.testCase1
        val keyData = QrKeyData.parse(v.keyData)!!
        val keys = QrKeyDerivation.deriveKeys(keyData)
        assertThat(keys.advertisingToken).isEqualTo(v.expectedAdvertisingToken)
        assertThat(keys.nameEncryptionKey).isEqualTo(v.expectedNameEncryptionKey)
    }

    @Test
    fun `KAT vector 2 — version 0x03, X equal all 0xAA`() {
        val v = QrCodeVectors.testCase2
        val keyData = QrKeyData.parse(v.keyData)!!
        val keys = QrKeyDerivation.deriveKeys(keyData)
        assertThat(keys.advertisingToken).isEqualTo(v.expectedAdvertisingToken)
        assertThat(keys.nameEncryptionKey).isEqualTo(v.expectedNameEncryptionKey)
    }

    @Test
    fun `KAT vector 3 — version 0x02, X equal all zero`() {
        val v = QrCodeVectors.testCase3
        val keyData = QrKeyData.parse(v.keyData)!!
        val keys = QrKeyDerivation.deriveKeys(keyData)
        assertThat(keys.advertisingToken).isEqualTo(v.expectedAdvertisingToken)
        assertThat(keys.nameEncryptionKey).isEqualTo(v.expectedNameEncryptionKey)
    }

    @Test
    fun `DerivedQrKeys equality is content-based`() {
        val a =
            DerivedQrKeys(
                advertisingToken = ByteArray(16) { it.toByte() },
                nameEncryptionKey = ByteArray(16) { (it + 100).toByte() },
            )
        val b =
            DerivedQrKeys(
                advertisingToken = ByteArray(16) { it.toByte() },
                nameEncryptionKey = ByteArray(16) { (it + 100).toByte() },
            )
        assertThat(a).isEqualTo(b)
        assertThat(a.hashCode()).isEqualTo(b.hashCode())
    }
}
