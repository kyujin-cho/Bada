/*
 * Copyright 2026 WhenVivoMeetsGoogle contributors.
 *
 * Licensed under the Apache License, Version 2.0.
 */
package io.github.kyujincho.wvmg.protocol.transport

import io.github.kyujincho.wvmg.protocol.endpoint.EndpointInfo

/**
 * Receiver-side listener for an initial Nearby Connections control channel.
 *
 * Phase 1 used Wi-Fi LAN TCP as the only opening channel. Isolated Wi-Fi
 * networks break that assumption: a peer can discover us but cannot reach the
 * advertised LAN address, so it needs another already-connected byte stream
 * before the normal SecureChannel and bandwidth-upgrade negotiation can run.
 *
 * Implementations own a medium-specific advertisement/listener pair, such as a
 * Bluetooth Classic device-name advertisement plus RFCOMM server socket. The
 * accepted stream is handed back as a [ConnectedTransport] and then enters the
 * same inbound protocol stack as a TCP accept.
 */
public interface InitialControlServer : AutoCloseable {
    /** Whether this initial-control advertisement/listener is currently up. */
    public val isActive: Boolean

    /**
     * Start advertising/listening for initial-control connections.
     *
     * Returns `false` when the medium is unavailable on this device or at this
     * moment (missing permission, radio off, unsupported platform feature,
     * etc.). Such failures are non-fatal; callers should keep any other
     * advertisement surfaces running.
     */
    public fun start(
        endpointInfo: EndpointInfo,
        acceptTransport: (ConnectedTransport) -> Unit,
    ): Boolean

    /** Stop advertising/listening. Idempotent. */
    public fun stop()

    override fun close() {
        stop()
    }
}
