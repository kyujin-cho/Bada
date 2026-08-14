/*
 * Copyright 2026 Bada contributors.
 *
 * Licensed under the Apache License, Version 2.0.
 */
@file:Suppress("MissingPermission")

package dev.bluehouse.bada.gestureexchange

import android.app.Activity
import android.nfc.NfcAdapter
import android.nfc.Tag
import android.nfc.tech.IsoDep
import android.os.Build
import dev.bluehouse.bada.discovery.medium.WifiDirectMediumProvider
import dev.bluehouse.bada.protocol.endpoint.EndpointInfo
import dev.bluehouse.bada.protocol.gestureexchange.GestureLocalIdentity
import dev.bluehouse.bada.protocol.gestureexchange.GestureReaderProtocolMachine
import dev.bluehouse.bada.protocol.gestureexchange.proto.ConnectivityInfo
import dev.bluehouse.bada.protocol.medium.UpgradePathCredentials
import dev.bluehouse.bada.protocol.transport.ConnectedTransport
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.net.InetAddress
import java.util.concurrent.atomic.AtomicLong

/**
 * Foreground NFC reader for Google-compatible “Tap to Share”. It owns the new
 * `A00000047609` path only. Name Card's reader remains a separate class/AID and
 * is never enabled by this object.
 */
internal class GestureExchangeReader(
    private val activity: Activity,
    private val identity: () -> GestureLocalIdentity,
    private val onConnected: (Peer) -> Unit,
    private val onDiagnostic: (String) -> Unit,
) {
    data class Peer(
        val endpointId: String,
        val endpointInfo: EndpointInfo,
        val transport: ConnectedTransport,
    )

    private val adapter = NfcAdapter.getDefaultAdapter(activity)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val generation = AtomicLong(0)
    private var exchangeJob: Job? = null

    fun enable() {
        if (!GestureTapToSharePreferences.from(activity).isEnabled()) return
        val nfc = adapter ?: return
        val flags = NfcAdapter.FLAG_READER_NFC_A or NfcAdapter.FLAG_READER_SKIP_NDEF_CHECK
        runCatching { nfc.enableReaderMode(activity, ::onTag, flags, null) }
            .onSuccess { diagnostic("reader_enabled") }
            .onFailure { diagnostic("reader_enable_failed type=${it::class.java.simpleName}") }
    }

    fun disable() {
        generation.incrementAndGet()
        exchangeJob?.cancel()
        exchangeJob = null
        adapter?.let { runCatching { it.disableReaderMode(activity) } }
    }

    fun close() {
        disable()
        scope.cancel()
    }

    @Suppress("TooGenericExceptionCaught") // One NFC session maps all protocol/I/O failures to teardown.
    private fun onTag(tag: Tag) {
        if (exchangeJob?.isActive == true) return
        val session = generation.incrementAndGet()
        exchangeJob =
            scope.launch {
                val isoDep =
                    IsoDep.get(tag) ?: run {
                        diagnostic("tag_without_isodep")
                        return@launch
                    }
                val provider = WifiDirectMediumProvider(activity.applicationContext)
                val machine = GestureReaderProtocolMachine(identity(), Build.MANUFACTURER)
                var connectedPeer: Peer? = null
                try {
                    isoDep.connect()
                    isoDep.timeout = NFC_TIMEOUT_MS
                    diagnostic("reader_started")
                    var response = isoDep.transceive(machine.start())
                    repeat(MAX_TRANSCEIVES) {
                        when (val result = machine.handle(response)) {
                            is GestureReaderProtocolMachine.Result.Continue -> {
                                diagnostic("reader_step index=$it out=${result.bytes.size} in=${response.size}")
                                response = isoDep.transceive(result.bytes)
                            }
                            is GestureReaderProtocolMachine.Result.ConnectivityReady -> {
                                val peer = connectPeer(provider, result.info) ?: error("peer route unavailable")
                                connectedPeer = peer
                                val terminal = isoDep.transceive(result.completionRequest)
                                check(machine.handle(terminal) == GestureReaderProtocolMachine.Result.Completed)
                                if (generation.get() != session) {
                                    peer.transport.close()
                                    connectedPeer = null
                                    return@launch
                                }
                                machine.clear()
                                diagnostic("reader_completed medium=wifi_direct")
                                onConnected(peer)
                                connectedPeer = null
                                return@launch
                            }
                            is GestureReaderProtocolMachine.Result.Failed -> error(result.reason)
                            GestureReaderProtocolMachine.Result.Completed -> error("completion without connectivity")
                        }
                    }
                    error("NFC transceive limit exceeded")
                } catch (t: Exception) {
                    connectedPeer?.transport?.close()
                    provider.cancelPendingUpgrade()
                    machine.clear()
                    diagnostic("reader_failed type=${t::class.java.simpleName}")
                } finally {
                    runCatching { isoDep.close() }
                }
            }
    }

    @Suppress("ReturnCount") // Each malformed or unavailable field is an intentional soft rejection.
    private suspend fun connectPeer(
        provider: WifiDirectMediumProvider,
        info: ConnectivityInfo,
    ): Peer? {
        val entry = info.entriesList.firstOrNull { it.zeroPartyIdentifier == ZERO_PARTY } ?: return null
        if (!entry.hasWifiDirect() || entry.endpointId.isBlank()) return null
        val endpointInfo = EndpointInfo.parse(entry.endpointInfo.toByteArray()) ?: return null
        val wire = entry.wifiDirect
        val credentials =
            UpgradePathCredentials.WifiDirect(
                ipAddress = InetAddress.getByName(DEFAULT_GROUP_OWNER).address,
                port = wire.port,
                ssid = wire.ssid,
                passphrase = wire.password,
                frequency = wire.frequencyMhz,
            )
        val adopted = provider.adoptUpgrade(credentials) ?: return null
        val transport = provider.consumePendingClientTransport() ?: adopted
        return Peer(entry.endpointId, endpointInfo, transport)
    }

    private fun diagnostic(event: String) {
        GestureVisualSignal.onProtocolEvent(event)
        GestureDiagnosticUpload.record(activity, event)
        onDiagnostic(event)
    }

    private companion object {
        private const val ZERO_PARTY = "nearby.sharing"
        private const val DEFAULT_GROUP_OWNER = "192.168.49.1"
        private const val MAX_TRANSCEIVES = 8
        private const val NFC_TIMEOUT_MS = 20_000
    }
}
