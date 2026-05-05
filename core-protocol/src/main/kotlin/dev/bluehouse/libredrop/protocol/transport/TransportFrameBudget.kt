/*
 * Copyright 2026 LibreDrop contributors.
 *
 * Licensed under the Apache License, Version 2.0.
 */
package dev.bluehouse.libredrop.protocol.transport

import dev.bluehouse.libredrop.protocol.medium.Medium

/**
 * Peer-specific outbound frame ceilings layered on top of the local 5 MiB
 * receive-side framing guard in [FramedConnection].
 *
 * Stock Google Play Services rejects some upgraded Wi-Fi Direct
 * `SecureMessage` frames well below the generic 5 MiB transport cap. Live
 * Galaxy interop runs failed at 2,097,144, 2,097,128, 2,097,112, 2,097,080
 * bytes, so the sender must enforce a tighter ceiling before it writes into
 * the upgraded socket buffer. Otherwise several oversized writes can be
 * accepted locally and only later cause a peer-side reset.
 *
 * The exact parser ceiling is internal to Play Services, so keep a small
 * guard band below the smallest observed reject to absorb protobuf-varint and
 * block-padding drift without materially impacting throughput.
 */
internal object TransportFrameBudget {
    internal const val PLAY_SERVICES_UPGRADED_MAX_FRAME_LENGTH: Int = (2 * 1024 * 1024) - 4096

    fun maxOutgoingFrameLength(medium: Medium): Int =
        when (medium) {
            Medium.WIFI_DIRECT -> PLAY_SERVICES_UPGRADED_MAX_FRAME_LENGTH
            else -> FramedConnection.SANE_FRAME_LENGTH - 1
        }
}
