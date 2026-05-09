/*
 * Copyright 2026 Bada contributors.
 *
 * Licensed under the Apache License, Version 2.0.
 */
package dev.bluehouse.bada.discovery.wifi.hotspot

import dev.bluehouse.bada.protocol.medium.UpgradePathCredentials
import java.net.Socket

/**
 * Server-side hotspot lifecycle.
 *
 * Implemented in production by [AndroidLocalOnlyHotspotController] which
 * wraps `WifiManager.startLocalOnlyHotspot`; the abstraction exists so
 * unit tests can drive the provider's logic without an Android device.
 *
 * `start` is `suspend` because the platform call is asynchronous (the
 * `WifiManager.LocalOnlyHotspotCallback` arrives off the calling thread)
 * and the provider needs to `await` it before serializing credentials.
 */
public interface HotspotController {
    /**
     * Bring up a local-only hotspot and bind a server socket to its
     * gateway IP on a free port.
     *
     * On success, returns the credentials the peer will need plus a
     * handle for later teardown. The caller is expected to start
     * accepting on [HotspotReservation.serverSocket] before returning
     * the credentials over the existing transport.
     *
     * Returns `null` on any platform-side failure (hardware doesn't
     * support local-only hotspot, missing permission, user dismissed
     * the OEM consent prompt on devices that show one). The provider
     * surfaces this back to the framework as "upgrade not available"
     * which falls through to UPGRADE_FAILURE.
     */
    public suspend fun start(): HotspotReservation?
}

/**
 * Bundle of "the AP is up and we're listening" state owned by a single
 * [HotspotController.start] call.
 */
public class HotspotReservation(
    /**
     * Credentials to ship over the existing transport in the
     * `UPGRADE_PATH_AVAILABLE` frame.
     */
    public val credentials: UpgradePathCredentials.WifiHotspot,
    /**
     * Server socket bound to the hotspot interface and listening on
     * [UpgradePathCredentials.WifiHotspot.port]. The framework's
     * upgrade orchestrator pulls one connection off this socket — the
     * remote sender is the only legitimate caller because the SSID +
     * passphrase only just left the device on the original encrypted
     * channel.
     */
    public val serverSocket: java.net.ServerSocket,
    /**
     * Idempotent teardown: stops the hotspot, closes the server
     * socket. Called by the orchestrator on transfer-complete /
     * cancel / failure.
     */
    public val teardown: () -> Unit,
)

/**
 * Client-side join-and-connect flow.
 *
 * Production binding: [AndroidWifiNetworkSpecifierClient] (uses
 * `WifiNetworkSpecifier` + `ConnectivityManager.requestNetwork`).
 * Tests substitute a fake that returns a loopback socket so the
 * provider's adopt path can be exercised on JVM.
 */
public interface HotspotClient {
    /**
     * Associate with [credentials], bind a route to the resulting
     * network, and open a TCP socket to the server's gateway IP /
     * port over that route.
     *
     * Returns `null` when the platform refuses or times out:
     *  * The OEM denies the join request (user dismisses the
     *    "Connect to ..." dialog on API 29+).
     *  * `WifiManager` reports the device is already connected to a
     *    Wi-Fi network the user does not want to drop.
     *  * The TCP connect fails (server not listening yet, gateway
     *    unreachable on the hotspot subnet).
     */
    public suspend fun join(credentials: UpgradePathCredentials.WifiHotspot): JoinResult?
}

/**
 * Result of a successful [HotspotClient.join].
 */
public class JoinResult(
    /** Connected TCP socket whose route is bound to the new association. */
    public val socket: Socket,
    /** Idempotent teardown for the network association behind [socket]. */
    public val teardown: () -> Unit,
)
