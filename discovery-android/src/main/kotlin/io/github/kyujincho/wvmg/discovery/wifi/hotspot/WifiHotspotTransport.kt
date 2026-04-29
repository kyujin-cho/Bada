/*
 * Copyright 2026 WhenVivoMeetsGoogle contributors.
 *
 * Licensed under the Apache License, Version 2.0.
 */
package io.github.kyujincho.wvmg.discovery.wifi.hotspot

import io.github.kyujincho.wvmg.protocol.medium.Medium
import io.github.kyujincho.wvmg.protocol.medium.UpgradedTransport
import java.net.Socket

/**
 * Concrete [UpgradedTransport] returned by [WifiHotspotMediumProvider].
 *
 * Carries the open TCP [Socket] that the framework's SecureChannel layer
 * will wrap once the upgrade swap (Phase 4 #54) is wired in. Holds a
 * back-reference to the per-association teardown callback so the
 * orchestrator can release the temporary Wi-Fi network when the transfer
 * finishes — the platform will tear the association down anyway when our
 * `ConnectivityManager.NetworkCallback` is unregistered, but releasing
 * deterministically lets us free the radio quickly.
 *
 * Lives in `:discovery-android` (not `:core-protocol`) because it owns
 * an `android.net.*` callback handle and a real `java.net.Socket` whose
 * route was bound to a specific [android.net.Network]; the framework
 * only needs the [Socket] and an opaque "drop this when done" hook.
 */
public class WifiHotspotTransport(
    /**
     * The connected TCP socket whose route is bound to the temporary
     * hotspot network. Reads / writes go out through the Wi-Fi
     * association the provider just brought up — never through whatever
     * default network (mobile data, another Wi-Fi) the device might
     * also have.
     */
    public val socket: Socket,
    /**
     * Idempotent teardown for the temporary hotspot association. Called
     * by [release] (and indirectly by `socket.close()` paths that route
     * through the framework's transport-swap orchestrator).
     */
    private val teardown: () -> Unit,
) : UpgradedTransport {
    override val medium: Medium = Medium.WIFI_HOTSPOT

    private var released: Boolean = false

    /**
     * Close the socket and release the underlying network association.
     * Safe to call multiple times — subsequent calls are no-ops.
     */
    public fun release() {
        if (released) return
        released = true
        // Order matters: close the socket before unregistering the
        // network callback. Closing a socket whose route was lost
        // mid-flight throws IOException from any blocked read/write,
        // which is what the framework's upgrade-failure handling
        // expects. Reversing the order races against the network
        // callback's "onLost" path and can leak a half-open socket.
        try {
            socket.close()
        } catch (_: Exception) {
            // Best-effort: socket may already be closed by the peer.
        }
        try {
            teardown()
        } catch (_: Exception) {
            // Best-effort: teardown may have already been triggered
            // by the platform releasing our NetworkCallback.
        }
    }
}
