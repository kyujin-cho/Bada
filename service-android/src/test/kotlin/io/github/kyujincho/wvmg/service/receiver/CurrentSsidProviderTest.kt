/*
 * Copyright 2026 WhenVivoMeetsGoogle contributors.
 *
 * Licensed under the Apache License, Version 2.0.
 */
package io.github.kyujincho.wvmg.service.receiver

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

/**
 * Pure-JVM tests for [CurrentSsidProvider.stripSsidQuotes], the helper
 * that normalises the raw SSID string Android returns from
 * `WifiManager.connectionInfo.ssid` for use in the receiver's
 * persistent notification body (#85).
 *
 * The Android-only path (`readCurrentSsid`) is not exercised here —
 * pulling in Robolectric just to mock `WifiManager.getSystemService`
 * isn't worth it for a one-liner that delegates to this stripper.
 * The unit tests below lock the stripping contract.
 */
class CurrentSsidProviderTest {
    @Test
    fun `strips matching surrounding double quotes`() {
        // The platform consistently wraps the SSID in matching
        // double quotes. The stripper must remove them so the
        // notification body shows just the network name.
        assertThat(CurrentSsidProvider.stripSsidQuotes("\"home-net\"")).isEqualTo("home-net")
    }

    @Test
    fun `passes through an unquoted SSID untouched`() {
        // Some OEM Wi-Fi stacks don't wrap the SSID. Pass-through
        // must leave such inputs unchanged rather than chopping off
        // any other character.
        assertThat(CurrentSsidProvider.stripSsidQuotes("home-net")).isEqualTo("home-net")
    }

    @Test
    fun `null input returns null`() {
        // `WifiManager.connectionInfo` can be null on some Android
        // versions; the helper must absorb that without throwing.
        assertThat(CurrentSsidProvider.stripSsidQuotes(null)).isNull()
    }

    @Test
    fun `blank input returns null`() {
        // A blank or whitespace-only SSID is treated as "unknown" so
        // the notification body falls back to the generic copy.
        assertThat(CurrentSsidProvider.stripSsidQuotes("")).isNull()
        assertThat(CurrentSsidProvider.stripSsidQuotes("   ")).isNull()
    }

    @Test
    fun `unknown ssid sentinel returns null`() {
        // API 31+ returns "<unknown ssid>" for callers without
        // ACCESS_FINE_LOCATION. Issue #85 explicitly chose not to
        // request a new permission for this nice-to-have; the
        // stripper must surface the unknown case as null so the
        // notification falls back gracefully.
        assertThat(CurrentSsidProvider.stripSsidQuotes("<unknown ssid>")).isNull()
    }

    @Test
    fun `quote-wrapped unknown ssid sentinel returns null`() {
        // A defensive check — some OEM builds have been observed
        // returning the unknown sentinel inside quotes. Strip first,
        // then fall through to the unknown-handler.
        assertThat(CurrentSsidProvider.stripSsidQuotes("\"<unknown ssid>\"")).isNull()
    }

    @Test
    fun `empty quoted string returns null`() {
        // `""` strips to empty; the helper must not feed an empty
        // string into the notification body.
        assertThat(CurrentSsidProvider.stripSsidQuotes("\"\"")).isNull()
    }

    @Test
    fun `single leading or trailing quote is preserved`() {
        // Only matching surrounding quotes are stripped. A lone
        // leading quote indicates a malformed value and must pass
        // through verbatim — silently dropping the quote could
        // mask a real bug elsewhere.
        assertThat(CurrentSsidProvider.stripSsidQuotes("\"home-net")).isEqualTo("\"home-net")
        assertThat(CurrentSsidProvider.stripSsidQuotes("home-net\"")).isEqualTo("home-net\"")
    }

    @Test
    fun `quotes inside the SSID are preserved`() {
        // SSIDs may technically contain quote characters. After
        // stripping the surrounding pair the inner quotes must
        // remain.
        assertThat(CurrentSsidProvider.stripSsidQuotes("\"home\"net\"")).isEqualTo("home\"net")
    }

    @Test
    fun `unicode SSIDs survive stripping`() {
        // Wi-Fi SSIDs are arbitrary 0..32-byte strings; many OEMs
        // expose them as UTF-8. Confirm the Kotlin substring slice
        // doesn't mangle multi-byte characters.
        assertThat(CurrentSsidProvider.stripSsidQuotes("\"カフェ-Wi-Fi\"")).isEqualTo("カフェ-Wi-Fi")
    }
}
