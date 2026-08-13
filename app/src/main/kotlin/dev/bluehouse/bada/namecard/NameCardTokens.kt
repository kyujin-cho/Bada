/*
 * Copyright 2026 Bada contributors.
 *
 * Licensed under the Apache License, Version 2.0.
 */
package dev.bluehouse.bada.namecard

import dev.bluehouse.bada.protocol.namecard.NameCardBootstrap
import java.security.MessageDigest

/**
 * **Name Card rendezvous-token helpers** — the two pure operations the
 * token-authenticated exchange ([NameCardBleExchange]) performs on the 16-byte
 * rendezvous token minted at the NFC tap:
 *
 *  - [verify] — constant-time equality between the session token and the token a
 *    peer presented in its HELLO ([NameCardConsentCodec]). Timing-safe via
 *    [MessageDigest.isEqual] so a byte-by-byte comparison can't be probed.
 *  - [advertisedId] — the 8-byte SHA-256 prefix of the token that rides in the
 *    BLE advertisement service data. The raw token is NEVER broadcast (a BLE
 *    sniffer must not learn the HELLO credential; the token travels only over
 *    the proximity-bound NFC tap); the short id also keeps the 128-bit
 *    service-data UUID + payload within the 31-byte legacy advertisement.
 *
 * Pure JVM (no `android.*`); unit-tested in `NameCardTokensTest`.
 */
internal object NameCardTokens {
    /** Length of the derived id carried in BLE advertisement service data. */
    const val ADVERTISED_ID_LEN: Int = 8

    /**
     * Constant-time check that [presented] equals the session's [expected] token.
     * Null or wrong-length input never matches.
     */
    fun verify(
        expected: ByteArray?,
        presented: ByteArray?,
    ): Boolean =
        expected != null &&
            presented != null &&
            expected.size == NameCardBootstrap.TOKEN_LEN &&
            presented.size == NameCardBootstrap.TOKEN_LEN &&
            MessageDigest.isEqual(expected, presented)

    /** The 8-byte SHA-256 prefix of [token] — the value advertised/scanned over BLE. */
    fun advertisedId(token: ByteArray): ByteArray =
        MessageDigest.getInstance("SHA-256").digest(token).copyOf(ADVERTISED_ID_LEN)
}
