/*
 * Copyright 2026 WhenVivoMeetsGoogle contributors.
 *
 * Licensed under the Apache License, Version 2.0.
 */
package io.github.kyujincho.wvmg.service.receiver

import android.content.Context
import android.net.wifi.WifiManager

/**
 * Look up the device's current Wi-Fi SSID for display in the
 * receiver's persistent foreground notification (#85).
 *
 * ### Why this lives in its own object
 *
 * The notification body builder is heavily exercised by unit tests; the
 * Wi-Fi system service is not unit-testable on a plain JVM. Splitting
 * the lookup off behind [CurrentSsidProvider] (which the notification
 * builder consumes via the platform-agnostic [stripSsidQuotes] helper)
 * keeps the SSID-stripping logic JVM-testable while letting the
 * Android-only [readCurrentSsid] step stay isolated.
 *
 * ### Caveats called out in the issue body
 *
 *  - Android wraps the SSID it returns from `WifiManager.connectionInfo`
 *    in surrounding double quotes. We strip those before display.
 *  - On API 31+ `WifiManager.getConnectionInfo()` is deprecated and may
 *    return the literal string `<unknown ssid>` for apps without
 *    precise location permission. We do **not** request a new permission
 *    for this nice-to-have surface — callers are expected to handle the
 *    unknown case gracefully (the notification falls back to a generic
 *    "Receiving on this network" string).
 *  - Any exception from the system service (rare, but observed on some
 *    OEM builds) is treated as "unknown".
 */
internal object CurrentSsidProvider {
    /**
     * The literal string Android returns from
     * `WifiManager.connectionInfo.ssid` when the caller is not
     * authorised to read the SSID. Documented at
     * `WifiManager.UNKNOWN_SSID`; mirrored here as a constant so the
     * notification builder doesn't need to depend on the system
     * service.
     */
    internal const val UNKNOWN_SSID: String = "<unknown ssid>"

    /**
     * Read the current Wi-Fi SSID from the system. Returns `null` when
     * the SSID is unavailable, redacted, or the caller's permission
     * grant prevents the system from disclosing it.
     *
     * This intentionally suppresses the deprecation warning on
     * `WifiManager.getConnectionInfo()` — there is no API-level-stable
     * replacement that doesn't require either `NETWORK_CALLBACK`
     * acrobatics or new runtime permissions, both of which are
     * out of scope for the issue's "nice to have, no new permission"
     * guidance.
     */
    @Suppress("DEPRECATION", "TooGenericExceptionCaught")
    fun readCurrentSsid(context: Context): String? {
        val wifi =
            context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
                ?: return null
        return try {
            stripSsidQuotes(wifi.connectionInfo?.ssid)
        } catch (
            @Suppress("SwallowedException") t: Throwable,
        ) {
            // Some OEM Wi-Fi stacks throw when the device is in a
            // transient connectivity state (Wi-Fi off mid-call, captive
            // portal redirect, etc.). The notification surface is
            // strictly informational; a thrown exception here must not
            // take the receiver service down.
            null
        }
    }

    /**
     * Pure-JVM helper for stripping the SSID's surrounding double
     * quotes and normalising the unknown / blank / null cases.
     *
     * Android wraps the textual SSID in matching `"`-delimited quotes
     * because the field is otherwise an opaque byte sequence (Wi-Fi
     * SSIDs are arbitrary 0..32-byte strings, not necessarily UTF-8).
     * We strip those quotes for display since the notification body is
     * always shown as text.
     *
     * Returns `null` for inputs that should fall through to the
     * generic "Receiving on this network" body — that includes a `null`
     * input, a blank string, the literal `<unknown ssid>` sentinel, and
     * a quote-only string (`""`). All other inputs return the
     * quote-stripped form.
     */
    fun stripSsidQuotes(raw: String?): String? {
        // Pre-strip rejection: blank input, the bare unknown sentinel,
        // and the quote-only string `""` all bypass the strip step.
        if (raw.isNullOrBlank() || raw == UNKNOWN_SSID) return null
        val stripped =
            if (raw.length >= 2 && raw.startsWith('"') && raw.endsWith('"')) {
                raw.substring(1, raw.length - 1)
            } else {
                raw
            }
        // Post-strip rejection: an empty inner value, or the unknown
        // sentinel coming back wrapped in quotes (observed on some
        // OEM builds), must fall through to the generic notification
        // body. A single combined check keeps detekt's ReturnCount
        // rule satisfied.
        return stripped.takeUnless { it.isBlank() || it == UNKNOWN_SSID }
    }
}
