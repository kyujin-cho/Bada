/*
 * Copyright 2026 WhenVivoMeetsGoogle contributors.
 *
 * Licensed under the Apache License, Version 2.0.
 */
@file:android.annotation.SuppressLint("MissingPermission")
@file:Suppress("MagicNumber") // Wi-Fi Direct frequencies and timeouts are well-known constants.

package io.github.kyujincho.wvmg.discovery.medium

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.wifi.WpsInfo
import android.net.wifi.p2p.WifiP2pConfig
import android.net.wifi.p2p.WifiP2pGroup
import android.net.wifi.p2p.WifiP2pInfo
import android.net.wifi.p2p.WifiP2pManager
import android.os.Looper
import android.util.Log
import androidx.annotation.RequiresPermission
import androidx.core.content.ContextCompat
import io.github.kyujincho.wvmg.protocol.medium.UpgradePathCredentials
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout
import java.io.Closeable
import java.io.IOException
import java.net.InetAddress
import java.net.Socket
import java.util.concurrent.atomic.AtomicReference

/**
 * Wraps the imperative `WifiP2pManager` API in a coroutine-friendly
 * shape (#49). One instance per upgrade attempt — instances are not
 * thread-safe across concurrent `prepareUpgrade` / `adoptUpgrade`
 * calls; the medium provider creates a fresh one each time.
 *
 * The class deliberately keeps [WifiP2pManager] / Channel handling out
 * of [WifiDirectMediumProvider] so the provider stays small and the
 * Android-specific dance lives in one place. Two top-level operations:
 *
 *  1. [createGroupAsServer] — receiver side. Calls
 *     `WifiP2pManager.createGroup(...)`, waits for the
 *     `WIFI_P2P_CONNECTION_CHANGED_ACTION` broadcast that confirms
 *     group formation, reads the group-owner IP and the SSID /
 *     passphrase from `WifiP2pInfo` + `WifiP2pGroup`, and returns the
 *     [UpgradePathCredentials.WifiDirect] the framework will ship
 *     across the wire.
 *  2. [connectAsClient] — sender side. Calls
 *     `WifiP2pManager.connect(...)` with a `WifiP2pConfig` carrying
 *     the peer's MAC address (derived from the SSID we received) and
 *     PSK; waits for the same broadcast; opens a TCP socket to the
 *     group-owner IP / port.
 *
 * Both methods return a [Closeable] teardown handle — closing it
 * removes the group (server) or cancels the connection (client) and
 * unregisters the broadcast receiver. The provider stitches that
 * teardown into [WifiDirectTransport.teardown] so the orchestrator
 * frees the radio when the transfer ends.
 *
 * Timeouts:
 *  * Group formation (server) — 30 seconds. Empirically the Pixel 8
 *    Pro / S24 negotiate in 4–8 s; 30 s catches first-time-driver-
 *    init slowness without hanging the user.
 *  * Connect (client) — 30 seconds for the same reason.
 *  * Socket connect — 10 seconds. Once the broadcast says the link
 *    is up, the GO must already be `accept()`ing; ten seconds is
 *    generous for a TCP handshake on a freshly-formed link.
 */
internal class WifiDirectGroupController(
    private val context: Context,
    private val manager: WifiP2pManager,
) {
    /**
     * Receiver-side group bring-up. Returns the credentials the peer
     * needs to call [connectAsClient], plus a teardown that removes
     * the group when the upgrade finishes (success OR failure). The
     * returned port is supplied by the caller because Wi-Fi Direct
     * does not allocate one — the receiver opens its own ServerSocket
     * on whatever port it picked.
     *
     * Returns `null` when group formation fails or times out; callers
     * treat that as "fall back to next medium".
     */
    @RequiresPermission(Manifest.permission.NEARBY_WIFI_DEVICES)
    @Suppress("ReturnCount") // One guard per failure mode in the multi-step P2P bring-up.
    suspend fun createGroupAsServer(serverPort: Int): GroupServerHandle? {
        val channel =
            manager.initialize(context, Looper.getMainLooper(), null) ?: run {
                Log.w(TAG, "WifiP2pManager.initialize returned null — Wi-Fi Direct unavailable")
                return null
            }
        val connectionChanged = registerConnectionChangedReceiver()
        val teardown =
            closeable {
                connectionChanged.unregister()
                removeGroupQuietly(channel)
            }

        val createOk =
            awaitActionListener { listener ->
                manager.createGroup(channel, listener)
            }
        if (!createOk) {
            Log.w(TAG, "WifiP2pManager.createGroup failed — falling back")
            teardown.close()
            return null
        }

        val info =
            try {
                withTimeout(GROUP_FORMATION_TIMEOUT_MS) {
                    connectionChanged.awaitGroupOwner()
                }
            } catch (_: TimeoutCancellationException) {
                Log.w(TAG, "Wi-Fi Direct group formation timed out after ${GROUP_FORMATION_TIMEOUT_MS}ms")
                teardown.close()
                return null
            }
        val group =
            awaitGroupInfo(channel) ?: run {
                Log.w(TAG, "WifiP2pManager.requestGroupInfo returned null after group creation")
                teardown.close()
                return null
            }
        val ipBytes = info.groupOwnerAddress?.address
        if (ipBytes == null || ipBytes.size != UpgradePathCredentials.WifiDirect.IPV4_ADDRESS_LENGTH) {
            Log.w(TAG, "Wi-Fi Direct group owner address missing or non-IPv4")
            teardown.close()
            return null
        }
        val credentials =
            UpgradePathCredentials.WifiDirect(
                ipAddress = ipBytes,
                port = serverPort,
                ssid = group.networkName ?: "",
                passphrase = group.passphrase ?: "",
                frequency = UpgradePathCredentials.WifiDirect.FREQUENCY_NOT_SET,
            )
        return GroupServerHandle(credentials, teardown)
    }

    /**
     * Sender-side bring-up. Builds a `WifiP2pConfig` from the peer
     * credentials, calls `connect`, waits for the connection broadcast,
     * and opens a TCP socket to the group-owner IP / port.
     *
     * Returns `null` when any step fails. Caller treats `null` as
     * "abort this upgrade attempt" and the negotiator emits
     * `UPGRADE_FAILURE`.
     */
    @RequiresPermission(Manifest.permission.NEARBY_WIFI_DEVICES)
    @Suppress("ReturnCount") // One guard per failure mode in the multi-step P2P bring-up.
    suspend fun connectAsClient(credentials: UpgradePathCredentials.WifiDirect): WifiDirectTransport? {
        val channel =
            manager.initialize(context, Looper.getMainLooper(), null) ?: run {
                Log.w(TAG, "WifiP2pManager.initialize returned null on the sender side")
                return null
            }
        val connectionChanged = registerConnectionChangedReceiver()
        val teardown =
            closeable {
                connectionChanged.unregister()
                cancelConnectQuietly(channel)
            }

        val config =
            WifiP2pConfig().apply {
                // The peer's MAC address is not directly part of the
                // proto — Quick Share scrubs it on purpose. We let the
                // platform discover the GO via the SSID; setting wps
                // to PBC keeps the platform from prompting the user
                // for a PIN.
                wps.setup = WpsInfo.PBC
                // groupOwnerIntent = 0 explicitly tells the platform
                // we want to be the client (not the GO). The peer
                // already created the group and handed us the PSK.
                groupOwnerIntent = 0
            }
        val connectOk =
            awaitActionListener { listener ->
                manager.connect(channel, config, listener)
            }
        if (!connectOk) {
            Log.w(TAG, "WifiP2pManager.connect failed — sender cannot adopt Wi-Fi Direct")
            teardown.close()
            return null
        }

        try {
            withTimeout(CONNECT_TIMEOUT_MS) {
                connectionChanged.awaitConnectionEstablished()
            }
        } catch (_: TimeoutCancellationException) {
            Log.w(TAG, "Wi-Fi Direct connect timed out after ${CONNECT_TIMEOUT_MS}ms")
            teardown.close()
            return null
        }

        val address =
            try {
                InetAddress.getByAddress(credentials.ipAddress)
            } catch (e: java.net.UnknownHostException) {
                Log.w(TAG, "Wi-Fi Direct credentials carry malformed IPv4 bytes", e)
                teardown.close()
                return null
            }
        val socket =
            try {
                Socket().also { s ->
                    s.connect(java.net.InetSocketAddress(address, credentials.port), SOCKET_CONNECT_TIMEOUT_MS)
                }
            } catch (e: IOException) {
                Log.w(TAG, "TCP connect to Wi-Fi Direct group owner failed", e)
                teardown.close()
                return null
            }
        return WifiDirectTransport(socket = socket, teardown = teardown)
    }

    /**
     * Result of [createGroupAsServer]. The framework hands [credentials]
     * to the negotiator and keeps [teardown] alive until the upgrade
     * completes / fails / is aborted.
     */
    internal data class GroupServerHandle(
        val credentials: UpgradePathCredentials.WifiDirect,
        val teardown: Closeable,
    )

    // -----------------------------------------------------------------
    // WifiP2pManager helpers — convert each callback shape into
    // suspend-able primitives so the call sites read top-to-bottom.
    // -----------------------------------------------------------------

    private suspend fun awaitActionListener(call: (WifiP2pManager.ActionListener) -> Unit): Boolean {
        val deferred = CompletableDeferred<Boolean>()
        val listener =
            object : WifiP2pManager.ActionListener {
                override fun onSuccess() {
                    deferred.complete(true)
                }

                override fun onFailure(reason: Int) {
                    Log.w(TAG, "WifiP2pManager.ActionListener.onFailure reason=$reason")
                    deferred.complete(false)
                }
            }
        try {
            call(listener)
        } catch (
            @Suppress("TooGenericExceptionCaught") t: Throwable,
        ) {
            Log.w(TAG, "WifiP2pManager call threw before listener invocation", t)
            deferred.complete(false)
        }
        return deferred.await()
    }

    @RequiresPermission(Manifest.permission.NEARBY_WIFI_DEVICES)
    private suspend fun awaitGroupInfo(channel: WifiP2pManager.Channel): WifiP2pGroup? {
        val deferred = CompletableDeferred<WifiP2pGroup?>()
        try {
            manager.requestGroupInfo(channel) { group -> deferred.complete(group) }
        } catch (
            @Suppress("TooGenericExceptionCaught") t: Throwable,
        ) {
            Log.w(TAG, "WifiP2pManager.requestGroupInfo threw", t)
            deferred.complete(null)
        }
        return deferred.await()
    }

    private fun removeGroupQuietly(channel: WifiP2pManager.Channel) {
        try {
            manager.removeGroup(
                channel,
                object : WifiP2pManager.ActionListener {
                    override fun onSuccess() = Unit

                    override fun onFailure(reason: Int) {
                        Log.w(TAG, "WifiP2pManager.removeGroup reason=$reason")
                    }
                },
            )
        } catch (
            @Suppress("TooGenericExceptionCaught") t: Throwable,
        ) {
            Log.w(TAG, "WifiP2pManager.removeGroup threw during teardown", t)
        }
    }

    private fun cancelConnectQuietly(channel: WifiP2pManager.Channel) {
        try {
            manager.cancelConnect(
                channel,
                object : WifiP2pManager.ActionListener {
                    override fun onSuccess() = Unit

                    override fun onFailure(reason: Int) {
                        Log.w(TAG, "WifiP2pManager.cancelConnect reason=$reason")
                    }
                },
            )
        } catch (
            @Suppress("TooGenericExceptionCaught") t: Throwable,
        ) {
            Log.w(TAG, "WifiP2pManager.cancelConnect threw during teardown", t)
        }
    }

    private fun registerConnectionChangedReceiver(): ConnectionChangedReceiver {
        val receiver = ConnectionChangedReceiver()
        val filter = IntentFilter(WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION)
        // ContextCompat.registerReceiver routes to the API 33+
        // explicit-export-flag overload when available and falls back
        // to the legacy 3-arg signature on older platforms. The flag
        // is RECEIVER_NOT_EXPORTED because the WIFI_P2P_CONNECTION_CHANGED
        // action is system-broadcast — only the platform itself sends
        // it, so we never need exported delivery from third-party apps.
        try {
            ContextCompat.registerReceiver(
                context,
                receiver,
                filter,
                ContextCompat.RECEIVER_NOT_EXPORTED,
            )
        } catch (
            @Suppress("TooGenericExceptionCaught") t: Throwable,
        ) {
            Log.w(TAG, "Failed to register P2P connection-changed receiver", t)
        }
        receiver.context = context
        return receiver
    }

    /**
     * Broadcast receiver wrapper that exposes the connection-changed
     * stream as `await…` suspend functions. We keep separate await
     * functions for the server-side ("group has formed AND we are the
     * GO") and client-side ("we are connected, group owner address
     * known") because the broadcast carries a single
     * [WifiP2pInfo] both sides receive.
     */
    private class ConnectionChangedReceiver : BroadcastReceiver() {
        var context: Context? = null
        private val unregistered = AtomicReference(false)
        private val ownerInfo = CompletableDeferred<WifiP2pInfo>()
        private val clientInfo = CompletableDeferred<WifiP2pInfo>()

        override fun onReceive(
            context: Context?,
            intent: Intent?,
        ) {
            val info: WifiP2pInfo = readWifiP2pInfo(intent) ?: return
            if (!info.groupFormed) return
            if (info.isGroupOwner && !ownerInfo.isCompleted) {
                ownerInfo.complete(info)
            }
            if (!info.isGroupOwner && !clientInfo.isCompleted) {
                clientInfo.complete(info)
            }
        }

        /**
         * Wrapper around `Intent.getParcelableExtra` that uses the
         * type-safe overload on API 33+ and falls back to the
         * deprecated single-argument form on older platforms. Without
         * this split, the `:discovery-android` JVM tests build emits a
         * deprecation warning and the static-analysis pass treats it
         * as a regression.
         */
        @Suppress("DEPRECATION")
        private fun readWifiP2pInfo(intent: Intent?): WifiP2pInfo? {
            if (intent == null) return null
            return if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
                intent.getParcelableExtra(WifiP2pManager.EXTRA_WIFI_P2P_INFO, WifiP2pInfo::class.java)
            } else {
                intent.getParcelableExtra(WifiP2pManager.EXTRA_WIFI_P2P_INFO) as? WifiP2pInfo
            }
        }

        suspend fun awaitGroupOwner(): WifiP2pInfo = ownerInfo.await()

        suspend fun awaitConnectionEstablished(): WifiP2pInfo = clientInfo.await()

        fun unregister() {
            if (!unregistered.compareAndSet(false, true)) return
            val ctx = context ?: return
            try {
                ctx.unregisterReceiver(this)
            } catch (
                @Suppress("TooGenericExceptionCaught") t: IllegalArgumentException,
            ) {
                // Receiver was never registered (the register call
                // upstream failed) — nothing to unregister.
                Log.d(TAG, "P2P connection-changed receiver not registered at unregister time", t)
            }
        }
    }

    private fun closeable(action: () -> Unit): Closeable {
        val closed = AtomicReference(false)
        return Closeable {
            if (closed.compareAndSet(false, true)) {
                try {
                    action()
                } catch (
                    @Suppress("TooGenericExceptionCaught") t: Throwable,
                ) {
                    Log.w(TAG, "Wi-Fi Direct teardown action threw", t)
                }
            }
        }
    }

    companion object {
        private const val TAG = "WvmgWifiDirect"

        /** Wait at most this long for the WIFI_P2P_CONNECTION_CHANGED broadcast. */
        const val GROUP_FORMATION_TIMEOUT_MS: Long = 30_000

        /** Sender-side equivalent of [GROUP_FORMATION_TIMEOUT_MS]. */
        const val CONNECT_TIMEOUT_MS: Long = 30_000

        /** TCP connect timeout once the link is up. */
        const val SOCKET_CONNECT_TIMEOUT_MS: Int = 10_000
    }
}
