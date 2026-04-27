/*
 * Copyright 2026 WhenVivoMeetsGoogle contributors.
 *
 * Licensed under the Apache License, Version 2.0.
 */
package io.github.kyujincho.wvmg.protocol

/**
 * Identifying constants for the Quick Share protocol implementation.
 *
 * This module is intentionally `android`-free; see the parent build file for
 * the explicit dependency policy. It exists today so the module's
 * `src/main/kotlin` source set is non-empty and `assembleDebug` /
 * `core-protocol:test` succeed before the real protocol code lands in #6+.
 */
public object ProtocolInfo {
    /**
     * mDNS service type used by Quick Share / Nearby Share advertisers.
     *
     * Documented in the NearDrop reference at
     * https://github.com/grishka/NearDrop/blob/master/PROTOCOL.md#advertising
     * and reproduced here so downstream modules don't need to hard-code it.
     */
    public const val MDNS_SERVICE_TYPE: String = "_FC9F5ED42C8A._tcp."

    /** Human-readable name surfaced in logs and diagnostics. */
    public const val NAME: String = "WhenVivoMeetsGoogle/core-protocol"
}
