/*
 * Copyright 2026 Bada contributors.
 *
 * Licensed under the Apache License, Version 2.0.
 */
package dev.bluehouse.bada.discovery.medium

import android.Manifest
import android.content.Context
import android.net.wifi.p2p.WifiP2pManager
import android.util.Log
import androidx.annotation.RequiresPermission
import dev.bluehouse.bada.protocol.medium.Medium
import dev.bluehouse.bada.protocol.medium.MediumProvider
import dev.bluehouse.bada.protocol.medium.UpgradePathCredentials
import dev.bluehouse.bada.protocol.medium.UpgradedTransport
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.Closeable
import java.io.IOException
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * Wi-Fi Direct (P2P) bandwidth-upgrade provider (#49).
 *
 * Plugs into the [dev.bluehouse.bada.protocol.medium.MediumRegistry]
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
 *    [dev.bluehouse.bada.protocol.medium.MediumRegistry] at
 *    `Application.onCreate`.
 *  * `prepareUpgrade` allocates a ServerSocket on a free port, hands
 *    its number to the controller, and stashes both the socket and the
 *    teardown so [pendingServer] / [pendingClient] can later be
 *    reclaimed by the orchestrator (#54). Wi-Fi Direct is device-wide,
 *    so overlapping upgrade attempts are declined and the framework
 *    falls back to the next available medium.
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

    /** Wi-Fi Direct group ownership is device-wide; serialize receiver-side upgrades. */
    private val serverUpgradeActive = AtomicBoolean(false)

    /** Client joins are also device-wide and should not stomp on each other. */
    private val clientUpgradeActive = AtomicBoolean(false)

    override fun isSupported(): Boolean = availability.isSupported()

    @RequiresPermission(Manifest.permission.NEARBY_WIFI_DEVICES)
    @Suppress("ReturnCount") // One guard per failure mode — controller missing, socket alloc, group failure.
    override suspend fun prepareUpgrade(): UpgradePathCredentials? {
        if (!serverUpgradeActive.compareAndSet(false, true)) {
            Log.w(TAG, "Wi-Fi Direct server upgrade already active; declining concurrent prepare")
            return null
        }

        var prepared = false
        val controller =
            controllerFactory() ?: run {
                Log.w(TAG, "WifiP2pManager unavailable on prepareUpgrade")
                serverUpgradeActive.set(false)
                return null
            }
        val serverSocket =
            try {
                serverSocketFactory()
            } catch (e: IOException) {
                Log.w(TAG, "Failed to allocate ServerSocket for Wi-Fi Direct upgrade", e)
                serverUpgradeActive.set(false)
                return null
            }
        try {
            val handle = controller.createGroupAsServer(serverPort = serverSocket.localPort)
            if (handle == null) {
                return null
            }
            val guardedHandle =
                handle.copy(teardown = leaseReleasingTeardown(handle.teardown, serverUpgradeActive))
            pendingServer.set(PendingServer(guardedHandle, serverSocket))
            prepared = true
            return guardedHandle.credentials
        } finally {
            if (!prepared) {
                serverSocket.runCatching { close() }
                serverUpgradeActive.set(false)
            }
        }
    }

    @RequiresPermission(Manifest.permission.NEARBY_WIFI_DEVICES)
    @Suppress("ReturnCount") // Five guards: medium mismatch, missing concrete creds, controller, connect, success.
    override suspend fun adoptUpgrade(credentials: UpgradePathCredentials): UpgradedTransport? {
        if (!clientUpgradeActive.compareAndSet(false, true)) {
            Log.w(TAG, "Wi-Fi Direct client upgrade already active; declining concurrent adopt")
            return null
        }

        var adopted = false
        // The framework does not enforce credentials.medium == this.medium;
        // the contract on MediumProvider says we must validate explicitly.
        // Returning null falls back to the next ladder rung instead of
        // hard-erroring the connection.
        if (credentials.medium != Medium.WIFI_DIRECT) {
            Log.w(TAG, "adoptUpgrade called with mismatched medium ${credentials.medium}")
            clientUpgradeActive.set(false)
            return null
        }
        val direct =
            credentials as? UpgradePathCredentials.WifiDirect ?: run {
                Log.w(TAG, "adoptUpgrade got Wi-Fi Direct medium but no concrete credentials")
                clientUpgradeActive.set(false)
                return null
            }
        val controller =
            controllerFactory() ?: run {
                Log.w(TAG, "WifiP2pManager unavailable on adoptUpgrade")
                clientUpgradeActive.set(false)
                return null
            }
        try {
            val transport = controller.connectAsClient(direct) ?: return null
            val guardedTransport =
                transport.copy(teardown = leaseReleasingTeardown(transport.teardown, clientUpgradeActive))
            pendingClient.getAndSet(guardedTransport)?.let { stale ->
                // A previous adopt attempt's transport is still around; tear
                // it down so we never leak the underlying socket. This only
                // happens when the orchestrator drops a successful adopt
                // without claiming the transport (e.g. peer aborted between
                // our adopt and their CLIENT_INTRODUCTION_ACK).
                stale.runCatching { socket.close() }
                stale.runCatching { teardown.close() }
            }
            adopted = true
            return guardedTransport
        } finally {
            if (!adopted) {
                clientUpgradeActive.set(false)
            }
        }
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
        val pending = pendingServer.get() ?: return null
        return acceptPendingServerTransport(pending)
    }

    private fun acceptPendingServerTransport(pending: PendingServer): WifiDirectTransport? =
        try {
            // Block-accept on the receiver-side socket. The peer is about
            // to call connect on this exact port. Close the listening socket
            // once accept returns to free the port.
            val socket = pending.serverSocket.use { listener -> listener.accept() }
            Log.w(TAG, "Wi-Fi Direct ServerSocket accepted peer=${socket.remoteSocketAddress}")
            claimAcceptedServerTransport(pending, socket)
        } catch (e: IOException) {
            Log.w(TAG, "Wi-Fi Direct ServerSocket.accept threw", e)
            if (pendingServer.compareAndSet(pending, null)) {
                pending.handle.teardown.runCatching { close() }
            }
            null
        }

    private fun claimAcceptedServerTransport(
        pending: PendingServer,
        socket: Socket,
    ): WifiDirectTransport? {
        if (!pendingServer.compareAndSet(pending, null)) {
            socket.runCatching { close() }
            return null
        }
        return WifiDirectTransport(
            socket = socket,
            teardown = pending.handle.teardown,
            frequencyMhz = pending.handle.credentials.frequencyMhzOrNull(),
        )
    }

    private fun UpgradePathCredentials.WifiDirect.frequencyMhzOrNull(): Int? =
        frequency.takeIf {
            it != UpgradePathCredentials.WifiDirect.FREQUENCY_NOT_SET && it > 0
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
        serverUpgradeActive.set(false)
        clientUpgradeActive.set(false)
    }

    private fun leaseReleasingTeardown(
        delegate: Closeable,
        active: AtomicBoolean,
    ): Closeable {
        val closed = AtomicBoolean(false)
        return Closeable {
            if (closed.compareAndSet(false, true)) {
                try {
                    delegate.close()
                } finally {
                    active.set(false)
                }
            }
        }
    }

    private data class PendingServer(
        val handle: WifiDirectGroupController.GroupServerHandle,
        val serverSocket: ServerSocket,
    )

    private companion object {
        private const val TAG = "BadaWifiDirect"
    }
}
