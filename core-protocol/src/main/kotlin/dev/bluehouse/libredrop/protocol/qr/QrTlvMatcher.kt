/*
 * Copyright 2026 LibreDrop contributors.
 *
 * Licensed under the Apache License, Version 2.0.
 */
package dev.bluehouse.libredrop.protocol.qr

import dev.bluehouse.libredrop.protocol.endpoint.EndpointInfo
import dev.bluehouse.libredrop.protocol.endpoint.TlvRecord
import java.security.MessageDigest

/**
 * Sender-side helpers that decide whether a discovered receiver's
 * EndpointInfo is the one we just QR-bonded with.
 *
 * The sender holds the QR code's [DerivedQrKeys] (derived from the keypair
 * it generated). Each discovered receiver advertises an EndpointInfo that
 * may carry a TLV record of `type=1` whose value is one of:
 *
 *  - **Visible mode**: the 16-byte raw advertising token. Match by direct
 *    constant-time byte equality against our token.
 *  - **Hidden mode**: `IV(12) || AES-GCM ciphertext(name) || tag(16)`.
 *    Match by attempting AES-GCM decryption with our `nameEncryptionKey`
 *    and `advertisingToken` AAD; on success the plaintext is the device
 *    name and we connect.
 *
 * Both checks are wrapped in [matches], which inspects every type=1 TLV in
 * the advertisement and returns a structured [QrMatchResult] so the caller
 * (the sender's connection logic, landing in #24) can route the connect
 * the same way regardless of which mode matched.
 *
 * **Constant-time comparison.** Plain `ByteArray.contentEquals` short-
 * circuits on the first differing byte, which leaks information about how
 * many leading bytes of the advertising token an attacker has guessed
 * correctly. We use [MessageDigest.isEqual] instead, which is documented to
 * run in time independent of the inputs (matching the secret length, at
 * least). This matters even though the advertising token is 16 random
 * bytes: if a future PROTOCOL revision introduces low-entropy tokens, the
 * caller side stays safe.
 */
public object QrTlvMatcher {
    /**
     * Outcome of matching a discovered EndpointInfo against our QR keys.
     */
    public sealed interface QrMatchResult {
        /** No type=1 TLV record present, or none of the records match. */
        public object NoMatch : QrMatchResult

        /**
         * The peer is in visible mode and its advertising token matches ours.
         * Connect immediately; the peer's plaintext name is already in
         * [EndpointInfo.deviceName].
         */
        public object VisibleMatch : QrMatchResult

        /**
         * The peer is in hidden mode; AES-GCM decryption recovered the
         * plaintext device [name].
         */
        public data class HiddenMatch(
            val name: String,
        ) : QrMatchResult
    }

    /**
     * Inspects [endpointInfo] for a type=1 TLV record that matches our QR
     * keys. Iterates because an EndpointInfo *can* carry more than one TLV
     * record of the same type (the protocol does not forbid it, and we
     * preserve all of them through parse/serialize) — the first matching
     * record wins, which mirrors NearDrop's pragmatic stance.
     */
    public fun matches(
        endpointInfo: EndpointInfo,
        keys: DerivedQrKeys,
    ): QrMatchResult {
        for (record in endpointInfo.tlvRecords) {
            if (record.type != EndpointInfo.TLV_TYPE_QR_CODE) continue
            val result = matchTlv(record, keys)
            if (result !is QrMatchResult.NoMatch) return result
        }
        return QrMatchResult.NoMatch
    }

    /**
     * Tests a single TLV record against our QR keys. Splits on the value
     * length to decide which mode applies:
     *
     *  - exactly [QrKeyDerivation.DERIVED_KEY_LEN] bytes (16) →
     *    advertising-token comparison.
     *  - strictly greater than [QrHiddenNameCipher.MIN_HIDDEN_NAME_TLV_LEN]
     *    (28) bytes → hidden-name decryption attempt. `>` not `>=`; at
     *    exactly 28 bytes the ciphertext is empty and the recovered name
     *    would be the empty string, which is not a useful match (per the
     *    issue body's "value > 28 bytes" rule).
     *  - any other size → no match. We deliberately do not try to interpret
     *    short or odd-length TLV values; an unknown shape is safer than a
     *    speculative one.
     */
    @Suppress("ReturnCount")
    public fun matchTlv(
        record: TlvRecord,
        keys: DerivedQrKeys,
    ): QrMatchResult {
        if (record.type != EndpointInfo.TLV_TYPE_QR_CODE) return QrMatchResult.NoMatch

        val value = record.value
        if (value.size == QrKeyDerivation.DERIVED_KEY_LEN) {
            // Visible mode: TLV value is the raw advertising token.
            return if (MessageDigest.isEqual(value, keys.advertisingToken)) {
                QrMatchResult.VisibleMatch
            } else {
                QrMatchResult.NoMatch
            }
        }

        if (value.size > QrHiddenNameCipher.MIN_HIDDEN_NAME_TLV_LEN) {
            val name =
                QrHiddenNameCipher.decrypt(
                    nameEncryptionKey = keys.nameEncryptionKey,
                    advertisingToken = keys.advertisingToken,
                    tlvValue = value,
                )
            if (name != null) return QrMatchResult.HiddenMatch(name)
        }

        return QrMatchResult.NoMatch
    }

    /**
     * Builds the type=1 TLV record a **receiver** appends to its own
     * EndpointInfo when it has scanned a QR code in **visible** mode: the
     * raw 16-byte advertising token.
     */
    public fun buildVisibleTlv(advertisingToken: ByteArray): TlvRecord {
        require(advertisingToken.size == QrKeyDerivation.DERIVED_KEY_LEN) {
            "advertisingToken must be ${QrKeyDerivation.DERIVED_KEY_LEN} bytes"
        }
        return TlvRecord(
            type = EndpointInfo.TLV_TYPE_QR_CODE,
            // Defensive copy so the caller cannot later mutate the TLV
            // record's value through the original array reference.
            value = advertisingToken.copyOf(),
        )
    }

    /**
     * Builds the type=1 TLV record a **receiver** appends to its own
     * EndpointInfo when it has scanned a QR code in **hidden** mode:
     * `IV(12) || AES-GCM ciphertext(name) || tag(16)`.
     */
    public fun buildHiddenTlv(
        keys: DerivedQrKeys,
        deviceName: String,
    ): TlvRecord {
        val encrypted =
            QrHiddenNameCipher.encrypt(
                nameEncryptionKey = keys.nameEncryptionKey,
                advertisingToken = keys.advertisingToken,
                deviceName = deviceName,
            )
        return TlvRecord(
            type = EndpointInfo.TLV_TYPE_QR_CODE,
            value = encrypted,
        )
    }
}
