/*
 * Copyright 2026 WhenVivoMeetsGoogle contributors.
 *
 * Licensed under the Apache License, Version 2.0.
 */
package io.github.kyujincho.wvmg.discovery.medium

import android.Manifest
import android.content.Context
import android.net.wifi.p2p.WifiP2pManager
import android.util.Log
import androidx.annotation.RequiresPermission
import io.github.kyujincho.wvmg.protocol.medium.Medium
import io.github.kyujincho.wvmg.protocol.medium.MediumProvider
import io.github.kyujincho.wvmg.protocol.medium.UpgradePathCredentials
import io.github.kyujincho.wvmg.protocol.medium.UpgradedTransport
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.atomic.AtomicReference

/**
 * Wi-Fi Direct (P2P) bandwidth-upgrade provider (#49).
 *
 * Plugs into the [io.github.kyujincho.wvmg.protocol.medium.MediumRegistry]
 * to advertise [Medium.WIFI_DIRECT] as a supported upgrade target and
 * to drive the actual P2P group bring-up:
 *
 *  - **Sender role.** Discover + connect over Wi-Fi LAN as today,
 *    then on [adoptUpgrade] join the receiver-created P2P group via
 *    [WifiP2pManager.connect] and open a TCP socket to the group-owner
 *    IP.
 *  - **Receiver role.** Stand up a P2P group via
 *    [WifiP2pManager.createGroup], read the SSID + passphrase + GO IP
 *    from the platform, and ship them to the sender as
 *    [UpgradePathCredentials.WifiDirect].
 *
 * The actual `WifiP2pManager` choreography lives in
 * [WifiDirectGroupController]; this class is the small, testable
 * surface the framework consumes.
 *
 * Lifecycle:
 *  * One provider per process — register it in
 *    [io.github.kyujincho.wvmg.protocol.medium.MediumRegistry] at
 *    `Application.onCreate`.
 *  * `prepareUpgrade` allocates a ServerSocket on a free port, hands
 *    its number to the controller, and stashes both the socket and the
 *    teardown so [pendingServer] / [pendingClient] can later be
 *    reclaimed by the orchestrator (#54). Until #54 wires the swap,
 *    the provider simply tears down on every prepare/adopt to avoid
 *    leaking radios.
 *
 * Permissions: caller MUST hold [Manifest.permission.NEARBY_WIFI_DEVICES]
 * on API 33+ (or [Manifest.permission.ACCESS_FINE_LOCATION] on older
 * platforms). [isSupported] returns false when the permission is
 * missing, so the framework simply will not pick this medium until the
 * grant is in place.
 */
public class WifiDirectMediumProvider internal constructor(
    private val availability: WifiDirectAvailability,
    private val controllerFactory: () -> WifiDirectGroupController?,
    private val serverSocketFactory: () -> ServerSocket,
) : MediumProvider {
    /**
     * Production constructor. Reaches into the system service for
     * [WifiP2pManager] only when `controllerFactory` is invoked
     * (lazily, per upgrade attempt) — that means a registered-but-
     * unsupported provider does not pin any radio resources at startup.
     */
    public constructor(context: Context) : this(
        availability = WifiDirectAvailability.Default(context.applicationContext),
        controllerFactory = {
            val ctx = context.applicationContext
            val mgr = ctx.getSystemService(Context.WIFI_P2P_SERVICE) as? WifiP2pManager
            mgr?.let { WifiDirectGroupController(ctx, it) }
        },
        // Bind to 0.0.0.0:0 so the OS picks a free ephemeral port.
        // The receiver will hand the port number to the sender as part
        // of the credentials.
        serverSocketFactory = { ServerSocket(0) },
    )

    override val medium: Medium = Medium.WIFI_DIRECT

    /**
     * Server-side state held between `prepareUpgrade` and the
     * orchestrator's accept hook (added in #54). Today the framework
     * does not call into us after `prepareUpgrade` returns, so we
     * simply expose the slot for #54 to consume; tests reach in to
     * verify cleanup. Atomic so there is exactly one in-flight upgrade
     * per provider — concurrent prepare attempts would otherwise stomp
     * on each other's group ownership.
     */
    private val pendingServer: AtomicReference<PendingServer?> = AtomicReference(null)

    /** Same shape as [pendingServer], but for the client-side adopt path. */
    private val pendingClient: AtomicReference<WifiDirectTransport?> = AtomicReference(null)

    override fun isSupported(): Boolean = availability.isSupported()

    @RequiresPermission(Manifest.permission.NEARBY_WIFI_DEVICES)
    @Suppress("ReturnCount") // One guard per failure mode — controller missing, socket alloc, group failure.
    override suspend fun prepareUpgrade(): UpgradePathCredentials? {
        val previous = pendingServer.getAndSet(null)
        previous?.serverSocket?.runCatching { close() }
        previous?.handle?.teardown?.runCatching { close() }

        val controller =
            controllerFactory() ?: run {
                Log.w(TAG, "WifiP2pManager unavailable on prepareUpgrade")
                return null
            }
        val serverSocket =
            try {
                serverSocketFactory()
            } catch (e: IOException) {
                Log.w(TAG, "Failed to allocate ServerSocket for Wi-Fi Direct upgrade", e)
                return null
            }
        val handle = controller.createGroupAsServer(serverPort = serverSocket.localPort)
        if (handle == null) {
            serverSocket.runCatching { close() }
            return null
        }
        pendingServer.set(PendingServer(handle, serverSocket))
        return handle.credentials
    }

    @RequiresPermission(Manifest.permission.NEARBY_WIFI_DEVICES)
    @Suppress("ReturnCount") // Five guards: medium mismatch, missing concrete creds, controller, connect, success.
    override suspend fun adoptUpgrade(credentials: UpgradePathCredentials): UpgradedTransport? {
        // The framework does not enforce credentials.medium == this.medium;
        // the contract on MediumProvider says we must validate explicitly.
        // Returning null falls back to the next ladder rung instead of
        // hard-erroring the connection.
        if (credentials.medium != Medium.WIFI_DIRECT) {
            Log.w(TAG, "adoptUpgrade called with mismatched medium ${credentials.medium}")
            return null
        }
        val direct =
            credentials as? UpgradePathCredentials.WifiDirect ?: run {
                Log.w(TAG, "adoptUpgrade got Wi-Fi Direct medium but no concrete credentials")
                return null
            }
        val controller =
            controllerFactory() ?: run {
                Log.w(TAG, "WifiP2pManager unavailable on adoptUpgrade")
                return null
            }
        val transport = controller.connectAsClient(direct) ?: return null
        pendingClient.getAndSet(transport)?.let { stale ->
            // A previous adopt attempt's transport is still around; tear
            // it down so we never leak the underlying socket. This only
            // happens when the orchestrator drops a successful adopt
            // without claiming the transport (e.g. peer aborted between
            // our adopt and their CLIENT_INTRODUCTION_ACK).
            stale.runCatching { socket.close() }
            stale.runCatching { teardown.close() }
        }
        return transport
    }

    override suspend fun acceptUpgrade(): UpgradedTransport? =
        withContext(Dispatchers.IO) {
            consumePendingServerTransport()
        }

    override fun cancelPendingUpgrade() {
        cancelPending()
    }

    /**
     * **Internal — for the orchestrator wired in #54.** Hands back the
     * server-side `ServerSocket` allocated in [prepareUpgrade] and
     * clears the slot. Returns `null` when no upgrade is pending.
     */
    public fun consumePendingServerSocket(): Socket? = consumePendingServerTransport()?.socket

    /**
     * **Internal — for the orchestrator wired in #54.** Hands back the
     * connected server-side transport allocated in [prepareUpgrade].
     */
    public fun consumePendingServerTransport(): WifiDirectTransport? {
        val pending = pendingServer.getAndSet(null) ?: return null
        // Block-accept on the receiver-side socket. The peer is about
        // to call connect on this exact port. We hand teardown back as
        // a closeable on the returned transport in #54; for now the
        // server socket itself is the only handle the orchestrator
        // needs, so close the listening socket once accept returns to
        // free the port.
        return try {
            pending.serverSocket
                .use { listener -> listener.accept() }
                .let { socket ->
                    WifiDirectTransport(
                        socket = socket,
                        teardown = pending.handle.teardown,
                    )
                }
        } catch (e: IOException) {
            Log.w(TAG, "Wi-Fi Direct ServerSocket.accept threw", e)
            pending.handle.teardown.runCatching { close() }
            null
        }
    }

    /**
     * **Internal — for the orchestrator wired in #54.** Hands back the
     * client-side transport produced by the most recent successful
     * [adoptUpgrade] and clears the slot. Returns `null` when no
     * upgrade is pending.
     */
    public fun consumePendingClientTransport(): WifiDirectTransport? = pendingClient.getAndSet(null)

    /**
     * Tear down any in-flight P2P group ownership / client
     * connection. Idempotent. Called by the orchestrator when an
     * upgrade aborts; safe to call from any thread.
     */
    public fun cancelPending() {
        pendingServer.getAndSet(null)?.let { pending ->
            pending.serverSocket.runCatching { close() }
            pending.handle.teardown.runCatching { close() }
        }
        pendingClient.getAndSet(null)?.let { transport ->
            transport.socket.runCatching { close() }
            transport.teardown.runCatching { close() }
        }
    }

    private data class PendingServer(
        val handle: WifiDirectGroupController.GroupServerHandle,
        val serverSocket: ServerSocket,
    )

    private companion object {
        private const val TAG = "WvmgWifiDirect"
    }
}
