/*
 * Copyright 2026 Bada contributors.
 *
 * Licensed under the Apache License, Version 2.0.
 */
@file:android.annotation.SuppressLint("MissingPermission")
@file:Suppress("MagicNumber") // Wi-Fi Direct frequencies and timeouts are well-known constants.

package dev.bluehouse.bada.discovery.medium

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
import android.os.Build
import android.os.Looper
import android.util.Log
import androidx.annotation.RequiresApi
import androidx.annotation.RequiresPermission
import androidx.core.content.ContextCompat
import dev.bluehouse.bada.protocol.medium.UpgradePathCredentials
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.io.Closeable
import java.io.IOException
import java.net.InetAddress
import java.net.Socket
import java.security.SecureRandom
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

        val requested = RequestedWifiDirectCredentials()
        if (!createServerGroup(channel, requested)) {
            teardown.close()
            return null
        }
        val credentials =
            awaitServerCredentials(
                channel = channel,
                connectionChanged = connectionChanged,
                requested = requested,
                serverPort = serverPort,
            ) ?: run {
                teardown.close()
                return null
            }
        return GroupServerHandle(credentials, teardown)
    }

    private suspend fun createServerGroup(
        channel: WifiP2pManager.Channel,
        requested: RequestedWifiDirectCredentials,
    ): Boolean {
        primeP2pChannel(channel)
        removeExistingGroupBeforeServerCreate(channel)
        val createOk =
            awaitActionListener("createGroup") { listener ->
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    manager.createGroup(channel, requested.toConfig(), listener)
                } else {
                    manager.createGroup(channel, listener)
                }
            }
        if (!createOk) {
            Log.w(TAG, "WifiP2pManager.createGroup failed — falling back")
        }
        return createOk
    }

    @RequiresPermission(Manifest.permission.NEARBY_WIFI_DEVICES)
    private suspend fun primeP2pChannel(channel: WifiP2pManager.Channel) {
        val stateChanged = registerStateChangedReceiver()
        try {
            val discoverStarted =
                awaitActionListener("discoverPeers") { listener ->
                    manager.discoverPeers(channel, listener)
                }
            if (discoverStarted) {
                stateChanged.awaitEnabledOrTimeout(P2P_ENABLE_TIMEOUT_MS)
            } else {
                Log.w(TAG, "WifiP2pManager.discoverPeers failed while priming P2P channel")
            }
        } finally {
            stateChanged.unregister()
            stopPeerDiscoveryQuietly(channel)
        }
    }

    private suspend fun removeExistingGroupBeforeServerCreate(channel: WifiP2pManager.Channel) {
        val removedExisting =
            awaitActionListener("removeGroup") { listener ->
                manager.removeGroup(channel, listener)
            }
        if (removedExisting) {
            Log.w(TAG, "Removed stale Wi-Fi Direct group before creating a new upgrade group")
        }
    }

    private suspend fun awaitServerCredentials(
        channel: WifiP2pManager.Channel,
        connectionChanged: ConnectionChangedReceiver,
        requested: RequestedWifiDirectCredentials,
        serverPort: Int,
    ): UpgradePathCredentials.WifiDirect? {
        val info = awaitGroupOwnerInfo(connectionChanged)
        val group = awaitCreatedGroupInfo(channel)
        return if (info == null || group == null) {
            null
        } else {
            buildServerCredentials(info, group, requested, serverPort)
        }
    }

    private suspend fun awaitGroupOwnerInfo(connectionChanged: ConnectionChangedReceiver): WifiP2pInfo? =
        try {
            withTimeout(GROUP_FORMATION_TIMEOUT_MS) {
                connectionChanged.awaitGroupOwner()
            }
        } catch (_: TimeoutCancellationException) {
            Log.w(TAG, "Wi-Fi Direct group formation timed out after ${GROUP_FORMATION_TIMEOUT_MS}ms")
            null
        }

    private suspend fun awaitCreatedGroupInfo(channel: WifiP2pManager.Channel): WifiP2pGroup? {
        val group = awaitGroupInfo(channel)
        if (group == null) {
            Log.w(TAG, "WifiP2pManager.requestGroupInfo returned null after group creation")
        }
        return group
    }

    @Suppress("ReturnCount") // Null means "decline upgrade"; each guard logs the concrete malformed field.
    private fun buildServerCredentials(
        info: WifiP2pInfo,
        group: WifiP2pGroup,
        requested: RequestedWifiDirectCredentials,
        serverPort: Int,
    ): UpgradePathCredentials.WifiDirect? {
        val ipBytes = info.groupOwnerAddress?.address
        if (ipBytes == null || ipBytes.size != UpgradePathCredentials.WifiDirect.IPV4_ADDRESS_LENGTH) {
            Log.w(TAG, "Wi-Fi Direct group owner address missing or non-IPv4")
            return null
        }
        val strings = selectServerCredentialStrings(group, requested) ?: return null
        val frequency = selectServerFrequency(group)
        Log.w(
            TAG,
            "Wi-Fi Direct group ready ssid='${strings.ssid}' " +
                "passphraseLength=${strings.passphrase.length} frequency=$frequency",
        )
        return UpgradePathCredentials.WifiDirect(
            ipAddress = ipBytes,
            port = serverPort,
            ssid = strings.ssid,
            passphrase = strings.passphrase,
            frequency = frequency,
        )
    }

    private fun selectServerCredentialStrings(
        group: WifiP2pGroup,
        requested: RequestedWifiDirectCredentials,
    ): ServerCredentialStrings? {
        val ssid =
            group.networkName?.takeIf(WifiDirectCredentialShape::isValidNetworkName)
                ?: requested.networkName.takeIf(WifiDirectCredentialShape::isValidNetworkName)
        val passphrase =
            group.passphrase?.takeIf(WifiDirectCredentialShape::isValidPassphrase)
                ?: requested.passphrase.takeIf(WifiDirectCredentialShape::isValidPassphrase)
        if (ssid == null || passphrase == null) {
            Log.w(
                TAG,
                "Wi-Fi Direct group credentials invalid; " +
                    "ssid='${group.networkName.orEmpty()}' passphraseLength=${group.passphrase?.length ?: 0}",
            )
            return null
        }
        return ServerCredentialStrings(ssid, passphrase)
    }

    private fun selectServerFrequency(group: WifiP2pGroup): Int {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            return UpgradePathCredentials.WifiDirect.FREQUENCY_NOT_SET
        }
        return group.frequency
            .takeIf { it > 0 }
            ?: UpgradePathCredentials.WifiDirect.FREQUENCY_NOT_SET
    }

    private data class RequestedWifiDirectCredentials(
        val networkName: String = WifiDirectCredentialShape.generateNetworkName(),
        val passphrase: String = WifiDirectCredentialShape.generatePassphrase(),
    ) {
        @RequiresApi(Build.VERSION_CODES.Q)
        fun toConfig(): WifiP2pConfig =
            WifiP2pConfig
                .Builder()
                .setNetworkName(networkName)
                .setPassphrase(passphrase)
                .enablePersistentMode(false)
                .build()
    }

    private data class ServerCredentialStrings(
        val ssid: String,
        val passphrase: String,
    )

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

        val validNetworkName = WifiDirectCredentialShape.isValidNetworkName(credentials.ssid)
        val validPassphrase = WifiDirectCredentialShape.isValidPassphrase(credentials.passphrase)
        if (!validNetworkName || !validPassphrase) {
            Log.w(
                TAG,
                "Wi-Fi Direct credentials from peer are malformed; " +
                    "ssid=${credentials.ssid} ssidLength=${credentials.ssid.length} " +
                    "passphraseLength=${credentials.passphrase.length} port=${credentials.port}; " +
                    "declining upgrade",
            )
            teardown.close()
            return null
        }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            Log.w(TAG, "Wi-Fi Direct network-name join requires API 29+; declining upgrade")
            teardown.close()
            return null
        }

        val config =
            WifiP2pConfig
                .Builder()
                .setNetworkName(credentials.ssid)
                .setPassphrase(credentials.passphrase)
                .enablePersistentMode(false)
                .build()
                .apply {
                    // The peer already created the group and handed us
                    // its PSK. Keep WPS at PBC so the platform does not
                    // try to surface a PIN prompt.
                    wps.setup = WpsInfo.PBC
                }
        val connectOk =
            awaitActionListener("connect") { listener ->
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
                connectSocketToGroupOwner(address, credentials.port)
            } catch (e: IOException) {
                Log.w(TAG, "TCP connect to Wi-Fi Direct group owner failed", e)
                teardown.close()
                return null
            }
        return WifiDirectTransport(socket, teardown, credentials.frequencyMhzOrNull())
    }

    private fun UpgradePathCredentials.WifiDirect.frequencyMhzOrNull(): Int? =
        frequency.takeIf {
            it != UpgradePathCredentials.WifiDirect.FREQUENCY_NOT_SET && it > 0
        }

    private suspend fun connectSocketToGroupOwner(
        address: InetAddress,
        port: Int,
    ): Socket =
        withContext(Dispatchers.IO) {
            Socket().also { socket ->
                socket.connect(
                    java.net.InetSocketAddress(address, port),
                    SOCKET_CONNECT_TIMEOUT_MS,
                )
            }
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

    private suspend fun awaitActionListener(
        label: String,
        call: (WifiP2pManager.ActionListener) -> Unit,
    ): Boolean {
        val deferred = CompletableDeferred<Boolean>()
        val listener =
            object : WifiP2pManager.ActionListener {
                override fun onSuccess() {
                    Log.w(TAG, "WifiP2pManager.$label onSuccess")
                    deferred.complete(true)
                }

                override fun onFailure(reason: Int) {
                    Log.w(TAG, "WifiP2pManager.$label onFailure reason=$reason")
                    deferred.complete(false)
                }
            }
        try {
            call(listener)
        } catch (
            @Suppress("TooGenericExceptionCaught") t: Throwable,
        ) {
            Log.w(TAG, "WifiP2pManager.$label threw before listener invocation", t)
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

    private fun stopPeerDiscoveryQuietly(channel: WifiP2pManager.Channel) {
        try {
            manager.stopPeerDiscovery(
                channel,
                object : WifiP2pManager.ActionListener {
                    override fun onSuccess() {
                        Log.w(TAG, "WifiP2pManager.stopPeerDiscovery onSuccess")
                    }

                    override fun onFailure(reason: Int) {
                        Log.w(TAG, "WifiP2pManager.stopPeerDiscovery reason=$reason")
                    }
                },
            )
        } catch (
            @Suppress("TooGenericExceptionCaught") t: Throwable,
        ) {
            Log.w(TAG, "WifiP2pManager.stopPeerDiscovery threw during P2P prime", t)
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

    private fun registerStateChangedReceiver(): StateChangedReceiver {
        val receiver = StateChangedReceiver()
        val filter = IntentFilter(WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION)
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
            Log.w(TAG, "Failed to register P2P state-changed receiver", t)
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

    private class StateChangedReceiver : BroadcastReceiver() {
        var context: Context? = null
        private val unregistered = AtomicReference(false)
        private val enabled = CompletableDeferred<Unit>()

        override fun onReceive(
            context: Context?,
            intent: Intent?,
        ) {
            if (intent?.action != WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION) return
            val state =
                intent.getIntExtra(
                    WifiP2pManager.EXTRA_WIFI_STATE,
                    WifiP2pManager.WIFI_P2P_STATE_DISABLED,
                )
            Log.w(TAG, "WIFI_P2P_STATE_CHANGED state=$state")
            if (state == WifiP2pManager.WIFI_P2P_STATE_ENABLED && !enabled.isCompleted) {
                enabled.complete(Unit)
            }
        }

        suspend fun awaitEnabledOrTimeout(timeoutMillis: Long) {
            try {
                withTimeout(timeoutMillis) {
                    enabled.await()
                }
            } catch (_: TimeoutCancellationException) {
                Log.w(TAG, "Wi-Fi Direct state enable wait timed out after ${timeoutMillis}ms")
            }
        }

        fun unregister() {
            if (!unregistered.compareAndSet(false, true)) return
            val ctx = context ?: return
            try {
                ctx.unregisterReceiver(this)
            } catch (
                @Suppress("TooGenericExceptionCaught") t: IllegalArgumentException,
            ) {
                Log.d(TAG, "P2P state-changed receiver not registered at unregister time", t)
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
        private const val TAG = "BadaWifiDirect"

        /** Wait at most this long for the WIFI_P2P_CONNECTION_CHANGED broadcast. */
        const val GROUP_FORMATION_TIMEOUT_MS: Long = 30_000

        /** Short preflight wait for OEMs that lazily enable P2P after discoverPeers. */
        const val P2P_ENABLE_TIMEOUT_MS: Long = 2_000

        /** Sender-side equivalent of [GROUP_FORMATION_TIMEOUT_MS]. */
        const val CONNECT_TIMEOUT_MS: Long = 30_000

        /** TCP connect timeout once the link is up. */
        const val SOCKET_CONNECT_TIMEOUT_MS: Int = 10_000
    }
}

internal object WifiDirectCredentialShape {
    private val random = SecureRandom()
    private val networkNameRegex = Regex("^DIRECT-[A-Za-z0-9]{2}[A-Za-z0-9_-]*$")
    private const val NETWORK_NAME_RANDOM_CHARS = 2
    private const val NETWORK_NAME_SUFFIX = "Bada"
    private const val NETWORK_NAME_SESSION_CHARS = 6
    private const val NETWORK_NAME_MAX_LENGTH = 32
    private const val PASSPHRASE_LENGTH = 16
    private const val PASSPHRASE_MIN_LENGTH = 8
    private const val PASSPHRASE_MAX_LENGTH = 63
    private const val ALPHANUM = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789"

    fun generateNetworkName(): String =
        "DIRECT-${randomToken(NETWORK_NAME_RANDOM_CHARS)}-$NETWORK_NAME_SUFFIX-" +
            randomToken(NETWORK_NAME_SESSION_CHARS)

    fun generatePassphrase(): String = randomToken(PASSPHRASE_LENGTH)

    fun isValidNetworkName(value: String): Boolean =
        value.length <= NETWORK_NAME_MAX_LENGTH && networkNameRegex.matches(value)

    fun isValidPassphrase(value: String): Boolean =
        value.length in PASSPHRASE_MIN_LENGTH..PASSPHRASE_MAX_LENGTH &&
            value.all { it.code in ASCII_PRINTABLE_RANGE }

    private fun randomToken(length: Int): String =
        buildString(length) {
            repeat(length) {
                append(ALPHANUM[random.nextInt(ALPHANUM.length)])
            }
        }

    private val ASCII_PRINTABLE_RANGE = 0x20..0x7E
}
