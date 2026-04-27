/*
 * Copyright 2026 WhenVivoMeetsGoogle contributors.
 *
 * Licensed under the Apache License, Version 2.0.
 */
package io.github.kyujincho.wvmg.protocol.qr

import io.github.kyujincho.wvmg.protocol.endpoint.Base64Url

/**
 * Builds and parses Quick Share QR-code URLs.
 *
 * Wire format (per the issue body and PROTOCOL.md):
 *
 * ```
 * https://quickshare.google/qrcode#key=<urlsafe-base64-of-keyData>
 * ```
 *
 * Three properties matter for interop:
 *
 *  1. The payload sits in the **fragment** (`#…`), not the query string.
 *     Fragments never travel to the server, which is exactly what Quick
 *     Share wants because the URL is purely a transport for the local
 *     receiver→sender handshake — there is no `quickshare.google` server
 *     that should ever see the key data.
 *  2. The payload is **URL-safe base64 without padding** (`-_` alphabet, no
 *     trailing `=`). This matches the encoding used elsewhere in the
 *     Quick Share stack ([Base64Url]) so the same codec applies everywhere.
 *  3. The host is fixed (`quickshare.google`) and the path is fixed
 *     (`/qrcode`). Variations in scheme casing or trailing slashes appear
 *     in real-world QR scanners but are rejected here on the parse side
 *     to keep the implementation tight; senders we control always emit
 *     the canonical form.
 *
 * Like the rest of the protocol surface, the parser never throws on bad
 * input — it returns `null` for any malformed URL. Callers should treat
 * parse failures as "ignore this QR code", not as a hard error.
 */
public object QrUrl {
    /** Canonical scheme. */
    public const val SCHEME: String = "https"

    /** Canonical host. */
    public const val HOST: String = "quickshare.google"

    /** Canonical path. */
    public const val PATH: String = "/qrcode"

    /** Fragment parameter name carrying the URL-safe base64 payload. */
    public const val KEY_PARAM: String = "key"

    /** Canonical URL prefix up to (but excluding) the `#key=` fragment. */
    public const val URL_PREFIX: String = "$SCHEME://$HOST$PATH"

    /** Canonical fragment prefix; everything after it is the base64 payload. */
    public const val FRAGMENT_PREFIX: String = "#$KEY_PARAM="

    /**
     * Builds the canonical Quick Share QR-code URL for [keyData].
     *
     * The output is ASCII-only and safe to feed directly into a QR-bitmap
     * encoder.
     */
    public fun build(keyData: QrKeyData): String = URL_PREFIX + FRAGMENT_PREFIX + Base64Url.encode(keyData.encode())

    /**
     * Parses a Quick Share QR-code URL back into its [QrKeyData].
     *
     * Returns `null` for any malformed input: wrong scheme, wrong host,
     * wrong path, missing fragment, missing `key=` parameter, undecodable
     * base64, or a payload that does not match the 35-byte QR layout.
     *
     * The accept-set is deliberately tight. We do not normalize the scheme
     * casing, do not tolerate query parameters, and do not accept extra
     * fragment parameters. A QR code that doesn't match this exact shape
     * isn't one we generated; the safe default is to skip it.
     */
    @Suppress("ReturnCount")
    public fun parse(url: String): QrKeyData? {
        // Split on the first '#' so we don't accidentally treat a literal
        // '#' inside a malformed payload as a fragment separator.
        val hashIndex = url.indexOf('#')
        if (hashIndex < 0) return null
        val urlPart = url.substring(0, hashIndex)
        val fragment = url.substring(hashIndex + 1)

        if (urlPart != URL_PREFIX) return null

        // Fragment must be exactly `key=<payload>` — no extra params, no
        // ampersand-separated values. That's how we generate it; deviations
        // are not ours.
        val keyPrefix = "$KEY_PARAM="
        if (!fragment.startsWith(keyPrefix)) return null
        val encoded = fragment.substring(keyPrefix.length)
        if (encoded.isEmpty()) return null

        val decoded = Base64Url.decode(encoded) ?: return null
        return QrKeyData.parse(decoded)
    }
}
