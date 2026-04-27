/*
 * Copyright 2026 WhenVivoMeetsGoogle contributors.
 *
 * Licensed under the Apache License, Version 2.0.
 */
package io.github.kyujincho.wvmg.discovery

import io.github.kyujincho.wvmg.discovery.QuickShareMdns.ENDPOINT_ID_ALPHABET
import io.github.kyujincho.wvmg.discovery.QuickShareMdns.ENDPOINT_ID_LEN
import io.github.kyujincho.wvmg.discovery.QuickShareMdns.INSTANCE_NAME_RAW_LEN
import io.github.kyujincho.wvmg.discovery.QuickShareMdns.PCP_BYTE
import io.github.kyujincho.wvmg.discovery.QuickShareMdns.SERVICE_ID_HASH_PREFIX
import io.github.kyujincho.wvmg.protocol.endpoint.Base64Url
import java.security.SecureRandom

/**
 * Builds and parses the **service-instance name** Quick Share advertises in mDNS.
 *
 * The instance name is a 10-byte buffer encoded with URL-safe-base64
 * (no padding). Layout (big-endian byte ordering of the source buffer):
 *
 * ```
 * +-------+-----------------+----------------+-----------+
 * | byte  | content         | description    | length    |
 * |-------|-----------------|----------------|-----------|
 * |  0    | 0x23            | PCP marker     | 1 byte    |
 * |  1..4 | random alnum    | endpoint ID    | 4 bytes   |
 * |  5..7 | 0xFC,0x9F,0x5E  | service-ID hash| 3 bytes   |
 * |  8..9 | 0x00,0x00       | reserved zeros | 2 bytes   |
 * +-------+-----------------+----------------+-----------+
 * ```
 *
 * The 3-byte hash prefix is the first three bytes of
 * `SHA-256("NearbySharing")` and is fixed by Google's protocol. The 4-byte
 * endpoint ID is a per-instance nonce drawn from `[A-Za-z0-9]` — restricting
 * the alphabet to ASCII alphanumerics keeps the raw bytes deterministic
 * across locales and gives every byte exactly one valid round-trip through
 * URL-safe-base64.
 *
 * Both the publisher and the browser need to interpret this format
 * consistently, so building **and** parsing live in one file with shared
 * constants from [QuickShareMdns].
 */
public object InstanceName {
    /**
     * Generates a fresh service-instance name string ready to hand to
     * `ServiceInfo.create`. The returned string is the URL-safe-base64
     * encoding of a freshly generated [INSTANCE_NAME_RAW_LEN]-byte buffer.
     *
     * [random] is injected so deterministic fixtures can drive unit tests
     * without depending on the global SecureRandom instance.
     */
    public fun generate(random: SecureRandom = SecureRandom()): String {
        val raw = generateRawBytes(random)
        return Base64Url.encode(raw)
    }

    /**
     * Returns the raw 10-byte buffer that gets URL-safe-base64-encoded into
     * the on-the-wire service-instance name. Exposed (instead of being
     * inlined inside [generate]) so unit tests can assert byte-for-byte on
     * the structure without re-implementing the layout.
     */
    public fun generateRawBytes(random: SecureRandom = SecureRandom()): ByteArray {
        val out = ByteArray(INSTANCE_NAME_RAW_LEN)
        out[0] = PCP_BYTE
        for (i in 0 until ENDPOINT_ID_LEN) {
            // SecureRandom.nextInt(bound) is the standard way to draw a
            // bias-free index from a small alphabet; .ints() and friends
            // work too but allocate a Stream we don't need here.
            val idx = random.nextInt(ENDPOINT_ID_ALPHABET.length)
            out[1 + i] = ENDPOINT_ID_ALPHABET[idx].code.toByte()
        }
        SERVICE_ID_HASH_PREFIX.copyInto(
            destination = out,
            destinationOffset = 1 + ENDPOINT_ID_LEN,
        )
        // Last two bytes are the reserved zero pad. ByteArray is already
        // zero-filled by the JVM, so no explicit assignment is needed —
        // documenting the intent here keeps the layout obvious to readers.
        return out
    }

    /**
     * Decodes [encoded] back to its raw 10-byte buffer. Returns `null` if
     * decoding fails or the result is the wrong length, since malformed
     * instance names from the wild must be skipped silently rather than
     * causing the browser to error out.
     */
    public fun decodeRawBytes(encoded: String): ByteArray? {
        val raw = Base64Url.decode(encoded) ?: return null
        return if (raw.size == INSTANCE_NAME_RAW_LEN) raw else null
    }

    /**
     * Returns the 4-byte endpoint ID slice of an already-decoded raw buffer,
     * or `null` if [raw] is not the right length. The endpoint ID is the
     * primary key Quick Share peers use to dedupe sightings of the same
     * device across mDNS update events.
     */
    public fun extractEndpointId(raw: ByteArray): ByteArray? {
        if (raw.size != INSTANCE_NAME_RAW_LEN) return null
        return raw.copyOfRange(1, 1 + ENDPOINT_ID_LEN)
    }
}
