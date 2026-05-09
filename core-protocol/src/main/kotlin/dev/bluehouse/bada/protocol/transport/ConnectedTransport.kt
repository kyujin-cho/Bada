/*
 * Copyright 2026 Bada contributors.
 *
 * Licensed under the Apache License, Version 2.0.
 */
package dev.bluehouse.bada.protocol.transport

import dev.bluehouse.bada.protocol.medium.Medium
import java.io.InputStream
import java.io.OutputStream
import java.net.Socket

/**
 * Connected byte-stream transport feeding the Nearby Connections stack.
 *
 * The initial Bada receiver path historically assumed the first control
 * channel was always a Wi-Fi LAN [Socket]. Phase 4 changes that assumption:
 * stock Quick Share may need to reach us over a non-LAN medium first and only
 * then negotiate a higher-bandwidth path. This interface lets the core
 * protocol treat "already-connected stream with a known medium label" as the
 * true input, regardless of whether it came from TCP, Bluetooth RFCOMM, BLE,
 * or a future Android-only medium.
 */
public interface ConnectedTransport : AutoCloseable {
    /** Medium the current stream was established over. */
    public val medium: Medium

    /** Read half of the connected stream. */
    public val inputStream: InputStream

    /** Write half of the connected stream. */
    public val outputStream: OutputStream
}

/**
 * Wrap a connected [Socket] as a [ConnectedTransport].
 */
public fun Socket.asConnectedTransport(medium: Medium = Medium.WIFI_LAN): ConnectedTransport =
    object : ConnectedTransport {
        override val medium: Medium = medium

        override val inputStream: InputStream
            get() = getInputStream()

        override val outputStream: OutputStream
            get() = getOutputStream()

        override fun close() {
            this@asConnectedTransport.close()
        }
    }
