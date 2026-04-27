/*
 * Copyright 2026 WhenVivoMeetsGoogle contributors.
 *
 * Licensed under the Apache License, Version 2.0.
 */
package io.github.kyujincho.wvmg.protocol.endpoint

import java.util.Base64

/**
 * URL-safe base64 codec used for the mDNS TXT record key `n`.
 *
 * Quick Share TXT records carry the [EndpointInfo] bytes encoded with the
 * URL-safe alphabet (`-_` instead of `+/`) and **without** padding (`=`).
 * The reference NearDrop encoder strips the trailing `=` characters before
 * publishing the TXT record, so we mirror that behavior — both for round-trip
 * symmetry and so peers that round-trip our advertisement back to us see the
 * exact byte string they sent.
 *
 * The decoder accepts both padded and unpadded input (matching JDK
 * `Base64.getUrlDecoder()`'s lenient stance) so we interoperate with peers
 * that encode their advertisements with padding.
 *
 * Performance: the JDK's `Base64.Encoder` / `Base64.Decoder` are zero-cost
 * thread-safe singletons; we cache them as `private val` to avoid the
 * `getInstance` static lookup on every advertisement we publish.
 */
public object Base64Url {
    private val ENCODER: Base64.Encoder = Base64.getUrlEncoder().withoutPadding()
    private val DECODER: Base64.Decoder = Base64.getUrlDecoder()

    /**
     * Encodes [bytes] as URL-safe base64 **without** trailing `=` padding.
     *
     * The output is ASCII and safe to drop into a DNS TXT record value
     * directly.
     */
    public fun encode(bytes: ByteArray): String = ENCODER.encodeToString(bytes)

    /**
     * Decodes a URL-safe base64 string. Padding is optional.
     *
     * Returns `null` on any decoding error (illegal characters, malformed
     * length). Like [EndpointInfo.parse], this never throws on bad peer
     * data — it just signals "ignore this advertisement".
     */
    public fun decode(input: String): ByteArray? =
        try {
            DECODER.decode(input)
        } catch (_: IllegalArgumentException) {
            null
        }
}
