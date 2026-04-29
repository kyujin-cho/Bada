/*
 * Copyright 2026 WhenVivoMeetsGoogle contributors.
 *
 * Licensed under the Apache License, Version 2.0.
 */
package io.github.kyujincho.wvmg.protocol.endpoint

import java.security.MessageDigest
import java.util.UUID

/**
 * Stock Nearby Connections / Quick Share service identity.
 *
 * Google's Android Nearby stack uses the literal service id
 * `"NearbySharing"` across discovery surfaces. The mDNS service hash prefix
 * in `QuickShareMdns` is the first three bytes of its SHA-256 digest, and the
 * Bluetooth Classic bootstrap listener derives its RFCOMM SDP UUID from the
 * same string via a type-3 name-based UUID.
 */
public object NearbyServiceId {
    /** Canonical Nearby / Quick Share service id literal. */
    public const val VALUE: String = "NearbySharing"

    /** First 3 bytes of `SHA-256(VALUE)`, used in discovery fingerprints. */
    @JvmStatic
    public val hashPrefix: ByteArray = sha256(VALUE.toByteArray()).copyOf(HASH_PREFIX_BYTES)

    /**
     * Type-3 name-based UUID derived from [VALUE].
     *
     * Matches nearby-connections' Bluetooth Classic transport, which maps the
     * service name onto a deterministic RFCOMM service UUID.
     */
    @JvmStatic
    public val bluetoothServiceUuid: UUID = UUID.nameUUIDFromBytes(VALUE.toByteArray(Charsets.UTF_8))

    private fun sha256(bytes: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(bytes)

    private const val HASH_PREFIX_BYTES: Int = 3
}
