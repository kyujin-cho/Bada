/*
 * Copyright 2026 WhenVivoMeetsGoogle contributors.
 *
 * Licensed under the Apache License, Version 2.0.
 */
package io.github.kyujincho.wvmg.discovery.medium

import io.github.kyujincho.wvmg.protocol.medium.Medium
import io.github.kyujincho.wvmg.protocol.medium.UpgradedTransport
import java.io.Closeable
import java.net.Socket

/**
 * [UpgradedTransport] subtype produced by
 * [WifiDirectMediumProvider.adoptUpgrade] (#49).
 *
 * Carries the live TCP [Socket] opened over the freshly-formed Wi-Fi
 * P2P group plus a [teardown] callback the orchestrator must invoke
 * when the upgrade either completes or fails.
 *
 * Why a dedicated subtype: the framework treats every `UpgradedTransport`
 * as opaque, but the orchestrator (#54) needs to recover the socket to
 * wrap it in a fresh `FramedConnection` + SecureChannel. Hand-rolling
 * a marker subclass per medium keeps that coupling explicit and avoids
 * leaking `java.net.Socket` (or worse, `WifiP2pManager`) into
 * `:core-protocol`.
 *
 * [teardown] removes the P2P group ownership we created in
 * `prepareUpgrade` (server side) or releases the platform-supplied
 * channel (client side). The orchestrator drives it from the upgrade
 * completion / failure path so the radio is freed even when an upgrade
 * aborts mid-handshake.
 *
 * @property socket Live TCP socket on the new transport. Caller-owned
 *   from this point on.
 * @property teardown Cleanup hook for the underlying P2P group.
 *   Idempotent; safe to call from any thread.
 */
public data class WifiDirectTransport(
    val socket: Socket,
    val teardown: Closeable,
) : UpgradedTransport {
    override val medium: Medium = Medium.WIFI_DIRECT
}
