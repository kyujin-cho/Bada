/*
 * Copyright 2026 Bada contributors.
 *
 * Licensed under the Apache License, Version 2.0.
 */
package dev.bluehouse.bada.discovery

/**
 * Wire-level constants for Quick Share's mDNS layer.
 *
 * These values are not configurable: they are part of the on-the-wire
 * protocol and must match Google's official Quick Share / Nearby Share
 * implementations byte-for-byte. Centralizing them here keeps the
 * publish ([Discovery.advertise]) and browse ([Discovery.browse]) sides
 * in lock-step, and makes it obvious to a reviewer that none of these
 * literals were invented locally.
 *
 * Reference: NearDrop's PROTOCOL.md and the corresponding Swift macOS port:
 * https://github.com/grishka/NearDrop/blob/master/PROTOCOL.md
 */
public object QuickShareMdns {
    /**
     * mDNS service type advertised by every Quick Share / Nearby Share peer.
     *
     * The hex prefix is fixed by Google and corresponds to the upper bytes of
     * the Nearby Connections strategy ID; the trailing dot is part of the
     * canonical DNS-SD spelling and JmDNS expects it verbatim when registering
     * or browsing services.
     */
    public const val SERVICE_TYPE: String = "_FC9F5ED42C8A._tcp.local."

    /**
     * Variant without the trailing `local.` suffix. Some JmDNS APIs (and
     * older third-party browsers) accept either form; we keep both as
     * compile-time constants so callers don't have to remember which one is
     * needed where.
     */
    public const val SERVICE_TYPE_SHORT: String = "_FC9F5ED42C8A._tcp."

    /**
     * Form expected by Android's [android.net.nsd.NsdManager]: the bare
     * `_<service>._<protocol>` pair with **no** trailing dot and **no**
     * `local.` suffix. NsdManager.registerService validates this format
     * and rejects either of the dotted variants.
     */
    public const val SERVICE_TYPE_NSD: String = "_FC9F5ED42C8A._tcp"

    /** Length of the raw (pre-base64) service-instance name byte string. */
    public const val INSTANCE_NAME_RAW_LEN: Int = 10

    /** Number of random alphanumeric ASCII bytes used as the per-peer endpoint ID. */
    public const val ENDPOINT_ID_LEN: Int = 4

    /** First byte of the instance name — the Nearby Connections **PCP** marker. */
    public const val PCP_BYTE: Byte = 0x23

    /**
     * The 3-byte service-ID hash embedded in the instance name. These are the
     * first three bytes of `SHA-256("NearbySharing")`, which Google uses as
     * the prefix in the Quick Share advertising fingerprint.
     */
    public val SERVICE_ID_HASH_PREFIX: ByteArray = byteArrayOf(0xFC.toByte(), 0x9F.toByte(), 0x5E.toByte())

    /**
     * TXT-record key carrying the URL-safe-base64
     * [endpoint info][dev.bluehouse.bada.protocol.endpoint.EndpointInfo].
     */
    public const val TXT_KEY_ENDPOINT_INFO: String = "n"

    /**
     * TXT-record key carrying the peer's dotted-decimal IPv4 address.
     *
     * Stock Android Quick Share publishes this alongside `n=`. Samsung's
     * sender UI can surface a BLE-correlated peer by name while still leaving
     * the Wi-Fi-LAN connection candidate unresolved unless this value is
     * present in the mDNS record.
     */
    public const val TXT_KEY_IPV4_ADDRESS: String = "IPv4"

    /**
     * TXT-record key carrying the Wi-Fi channel frequency in MHz.
     *
     * Stock Quick Share records include this as `f=<frequency>`. Samsung's
     * stack already correlates our BLE advertisement by name, but its LAN
     * resolver ignores mDNS candidates that do not look like the stock
     * Wi-Fi-LAN metadata shape, so advertise the same optional hint when the
     * platform exposes it.
     */
    public const val TXT_KEY_WIFI_FREQUENCY: String = "f"

    /**
     * Alphabet used when generating the random 4-byte endpoint ID portion of
     * the service-instance name. Restricting to alphanumeric ASCII keeps the
     * raw bytes safely round-trippable through every URL-safe-base64
     * implementation regardless of locale.
     */
    public const val ENDPOINT_ID_ALPHABET: String =
        "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789"
}
