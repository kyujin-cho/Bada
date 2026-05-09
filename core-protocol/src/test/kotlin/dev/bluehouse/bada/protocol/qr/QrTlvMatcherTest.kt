/*
 * Copyright 2026 Bada contributors.
 *
 * Licensed under the Apache License, Version 2.0.
 */
package dev.bluehouse.bada.protocol.qr

import com.google.common.truth.Truth.assertThat
import dev.bluehouse.bada.protocol.endpoint.DeviceType
import dev.bluehouse.bada.protocol.endpoint.EndpointInfo
import dev.bluehouse.bada.protocol.endpoint.TlvRecord
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/**
 * End-to-end tests for the sender-side matching logic in [QrTlvMatcher].
 *
 * These exercise the **full** receiver-→sender flow at the protocol layer:
 * we build a receiver-side EndpointInfo with the type=1 TLV record that a
 * QR-bonded receiver would emit, then ask the sender's matcher whether it
 * recognizes itself in the advertisement. Both visibility modes are
 * covered, plus the negative cases (no TLV, wrong-keys TLV, junk TLV).
 */
class QrTlvMatcherTest {
    private val ourKeys =
        QrKeyDerivation.deriveKeys(
            QrKeyData(
                versionByte = 0x02,
                xCoordinate = ByteArray(QrKeyData.X_COORDINATE_LEN) { (it + 1).toByte() },
            ),
        )
    private val otherKeys =
        QrKeyDerivation.deriveKeys(
            QrKeyData(
                versionByte = 0x02,
                xCoordinate = ByteArray(QrKeyData.X_COORDINATE_LEN) { (0xFF - it).toByte() },
            ),
        )

    @Test
    fun `visible-mode TLV with our advertisingToken returns VisibleMatch`() {
        val tlv = QrTlvMatcher.buildVisibleTlv(ourKeys.advertisingToken)
        val info = endpointInfoWithTlvs(listOf(tlv), hidden = false, name = "Pixel")
        assertThat(QrTlvMatcher.matches(info, ourKeys))
            .isEqualTo(QrTlvMatcher.QrMatchResult.VisibleMatch)
    }

    @Test
    fun `visible-mode TLV with someone else's advertisingToken returns NoMatch`() {
        val tlv = QrTlvMatcher.buildVisibleTlv(otherKeys.advertisingToken)
        val info = endpointInfoWithTlvs(listOf(tlv), hidden = false, name = "Pixel")
        assertThat(QrTlvMatcher.matches(info, ourKeys))
            .isEqualTo(QrTlvMatcher.QrMatchResult.NoMatch)
    }

    @Test
    fun `hidden-mode TLV that we encrypted returns HiddenMatch with the device name`() {
        val name = "Galaxy Z Fold 5"
        val tlv = QrTlvMatcher.buildHiddenTlv(ourKeys, name)
        val info = endpointInfoWithTlvs(listOf(tlv), hidden = true, name = null)
        val result = QrTlvMatcher.matches(info, ourKeys)
        assertThat(result).isInstanceOf(QrTlvMatcher.QrMatchResult.HiddenMatch::class.java)
        assertThat((result as QrTlvMatcher.QrMatchResult.HiddenMatch).name).isEqualTo(name)
    }

    @Test
    fun `hidden-mode TLV encrypted with someone else's keys returns NoMatch`() {
        val tlv = QrTlvMatcher.buildHiddenTlv(otherKeys, "stranger")
        val info = endpointInfoWithTlvs(listOf(tlv), hidden = true, name = null)
        assertThat(QrTlvMatcher.matches(info, ourKeys))
            .isEqualTo(QrTlvMatcher.QrMatchResult.NoMatch)
    }

    @Test
    fun `EndpointInfo without a type-1 TLV record returns NoMatch`() {
        val info = endpointInfoWithTlvs(emptyList(), hidden = false, name = "Pixel")
        assertThat(QrTlvMatcher.matches(info, ourKeys))
            .isEqualTo(QrTlvMatcher.QrMatchResult.NoMatch)
    }

    @Test
    fun `EndpointInfo with only a vendor-id TLV record returns NoMatch`() {
        // Type 2 = vendor ID. Should be ignored by the QR matcher.
        val tlv = TlvRecord(EndpointInfo.TLV_TYPE_VENDOR_ID, byteArrayOf(0x01))
        val info = endpointInfoWithTlvs(listOf(tlv), hidden = false, name = "Pixel")
        assertThat(QrTlvMatcher.matches(info, ourKeys))
            .isEqualTo(QrTlvMatcher.QrMatchResult.NoMatch)
    }

    @Test
    fun `TLV value of unexpected length returns NoMatch when neither 16 nor over 28`() {
        // 20 bytes — not 16 (visible) and not >28 (hidden). The matcher
        // shouldn't speculate; it should just return NoMatch.
        val tlv = TlvRecord(EndpointInfo.TLV_TYPE_QR_CODE, ByteArray(20))
        val info = endpointInfoWithTlvs(listOf(tlv), hidden = false, name = "Pixel")
        assertThat(QrTlvMatcher.matches(info, ourKeys))
            .isEqualTo(QrTlvMatcher.QrMatchResult.NoMatch)
    }

    @Test
    fun `TLV value of exactly 28 bytes is treated as not-hidden-name`() {
        // The issue body says "value > 28 bytes" triggers the hidden-mode
        // decrypt attempt. A 28-byte value is exactly IV+tag with empty
        // ciphertext — match the documented behavior and return NoMatch.
        val tlv = TlvRecord(EndpointInfo.TLV_TYPE_QR_CODE, ByteArray(28))
        val info = endpointInfoWithTlvs(listOf(tlv), hidden = false, name = "Pixel")
        assertThat(QrTlvMatcher.matches(info, ourKeys))
            .isEqualTo(QrTlvMatcher.QrMatchResult.NoMatch)
    }

    @Test
    fun `multiple type-1 TLVs — first matching record wins`() {
        val mismatch = QrTlvMatcher.buildVisibleTlv(otherKeys.advertisingToken)
        val match = QrTlvMatcher.buildVisibleTlv(ourKeys.advertisingToken)
        // Place the mismatch first; the matcher should still find the
        // matching record by iterating.
        val info = endpointInfoWithTlvs(listOf(mismatch, match), hidden = false, name = "Pixel")
        assertThat(QrTlvMatcher.matches(info, ourKeys))
            .isEqualTo(QrTlvMatcher.QrMatchResult.VisibleMatch)
    }

    @Test
    fun `buildVisibleTlv defensively copies the advertising token`() {
        val token = ByteArray(QrKeyDerivation.DERIVED_KEY_LEN) { 0x42 }
        val tlv = QrTlvMatcher.buildVisibleTlv(token)
        // Mutating the original must not affect the TLV record.
        token[0] = 0x01
        assertThat(tlv.value[0].toInt() and 0xFF).isEqualTo(0x42)
    }

    @Test
    fun `buildVisibleTlv rejects advertisingToken of the wrong size`() {
        assertThrows<IllegalArgumentException> {
            QrTlvMatcher.buildVisibleTlv(ByteArray(15))
        }
        assertThrows<IllegalArgumentException> {
            QrTlvMatcher.buildVisibleTlv(ByteArray(17))
        }
    }

    @Test
    fun `end-to-end visible flow — receiver builds TLV, sender matches`() {
        // Receiver scans QR, derives keys, publishes EndpointInfo with TLV.
        val keyData = QrKeyData.generate().qrKeyData
        val keys = QrKeyDerivation.deriveKeys(keyData)
        val receiverTlv = QrTlvMatcher.buildVisibleTlv(keys.advertisingToken)
        val receiverInfo =
            endpointInfoWithTlvs(listOf(receiverTlv), hidden = false, name = "Receiver")

        // Sender (who has the same QR keys, since it generated them).
        val senderResult = QrTlvMatcher.matches(receiverInfo, keys)
        assertThat(senderResult).isEqualTo(QrTlvMatcher.QrMatchResult.VisibleMatch)
    }

    @Test
    fun `end-to-end hidden flow — receiver encrypts name, sender decrypts`() {
        val keyData = QrKeyData.generate().qrKeyData
        val keys = QrKeyDerivation.deriveKeys(keyData)
        val receiverTlv = QrTlvMatcher.buildHiddenTlv(keys, "Hidden Phone")
        val receiverInfo =
            endpointInfoWithTlvs(listOf(receiverTlv), hidden = true, name = null)

        val senderResult = QrTlvMatcher.matches(receiverInfo, keys)
        assertThat(senderResult).isInstanceOf(QrTlvMatcher.QrMatchResult.HiddenMatch::class.java)
        assertThat((senderResult as QrTlvMatcher.QrMatchResult.HiddenMatch).name)
            .isEqualTo("Hidden Phone")
    }

    private fun endpointInfoWithTlvs(
        records: List<TlvRecord>,
        hidden: Boolean,
        name: String?,
    ): EndpointInfo =
        EndpointInfo(
            version = 1,
            hidden = hidden,
            deviceType = DeviceType.PHONE,
            reserved = false,
            metadata = ByteArray(EndpointInfo.METADATA_LEN) { 0x42 },
            deviceName = name,
            tlvRecords = records,
        )
}
