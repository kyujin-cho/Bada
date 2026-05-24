/*
 * Copyright 2026 Bada contributors.
 *
 * Licensed under the Apache License, Version 2.0.
 */
package dev.bluehouse.bada.protocol.qr

import java.security.PrivateKey
import java.security.Signature

/**
 * Builds the `qr_code_handshake_data` value the QR-originating (file-
 * sending) device must place in its `PairedKeyEncryptionFrame`.
 *
 * Per PROTOCOL.md "QR codes": the sending device proves it owns the
 * keypair published in the QR code by sending an **ECDSA signature of
 * the UKEY2 auth key** (the same `authString` the connection PIN is
 * derived from), made with the **private key matching the QR public
 * key**. The signature is emitted in **IEEE P1363** form — the raw
 * fixed-width `R || S` concatenation (64 bytes for P-256), not the ASN.1
 * DER `SEQUENCE { INTEGER r, INTEGER s }` that `java.security.Signature`
 * produces by default — so we sign with `SHA256withECDSA` and convert
 * DER → P1363 ourselves (the `*inP1363Format` JCA aliases are not
 * available across every API level we ship to).
 */
public object QrHandshakeSigner {
    /** P-256 coordinate width in bytes; the P1363 signature is twice this. */
    private const val COORD_LEN: Int = 32

    private const val SIGNATURE_ALGORITHM: String = "SHA256withECDSA"

    private const val DER_SEQUENCE_TAG: Int = 0x30
    private const val DER_INTEGER_TAG: Int = 0x02
    private const val BYTE_MASK: Int = 0xFF

    /**
     * Signs [ukey2AuthKey] with [privateKey] (an EC P-256 key) and returns
     * the 64-byte IEEE P1363 `R || S` signature for use as
     * `qr_code_handshake_data`.
     */
    public fun sign(
        privateKey: PrivateKey,
        ukey2AuthKey: ByteArray,
    ): ByteArray {
        val signature = Signature.getInstance(SIGNATURE_ALGORITHM)
        signature.initSign(privateKey)
        signature.update(ukey2AuthKey)
        return derToP1363(signature.sign())
    }

    /**
     * Converts an ASN.1 DER ECDSA signature (`SEQUENCE { INTEGER r,
     * INTEGER s }`) into the fixed-width `R || S` P1363 form, each
     * integer left-padded / leading-zero-stripped to [COORD_LEN] bytes.
     *
     * A P-256 DER signature is always short enough that every length
     * field uses the single-byte short form, so we parse it directly
     * without a general-purpose ASN.1 length decoder.
     */
    private fun derToP1363(der: ByteArray): ByteArray {
        var offset = 0
        require(der[offset++].toInt() and BYTE_MASK == DER_SEQUENCE_TAG) { "not a DER SEQUENCE" }
        offset++ // sequence length (short form for a P-256 signature)
        require(der[offset++].toInt() and BYTE_MASK == DER_INTEGER_TAG) { "missing INTEGER r" }
        val rLen = der[offset++].toInt() and BYTE_MASK
        val r = der.copyOfRange(offset, offset + rLen)
        offset += rLen
        require(der[offset++].toInt() and BYTE_MASK == DER_INTEGER_TAG) { "missing INTEGER s" }
        val sLen = der[offset++].toInt() and BYTE_MASK
        val s = der.copyOfRange(offset, offset + sLen)
        return toFixedWidth(r) + toFixedWidth(s)
    }

    /**
     * Renders a DER INTEGER (signed big-endian, possibly carrying a
     * leading sign byte or fewer than [COORD_LEN] significant bytes) as
     * an unsigned big-endian value exactly [COORD_LEN] bytes wide.
     */
    private fun toFixedWidth(value: ByteArray): ByteArray {
        var start = 0
        while (start < value.size - 1 && value[start].toInt() == 0) {
            start++
        }
        val trimmed = value.copyOfRange(start, value.size)
        require(trimmed.size <= COORD_LEN) { "integer wider than P-256 coordinate" }
        val out = ByteArray(COORD_LEN)
        trimmed.copyInto(out, destinationOffset = COORD_LEN - trimmed.size)
        return out
    }
}
