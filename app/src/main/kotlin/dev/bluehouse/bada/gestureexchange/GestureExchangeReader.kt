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
import android.os.Bundle
import dev.bluehouse.bada.discovery.medium.WifiDirectMediumProvider
import dev.bluehouse.bada.nfc.Api37NfcSessionAdapter
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
import java.util.concurrent.atomic.AtomicBoolean
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
    private val acceptingTag = AtomicBoolean(false)
    private var exchangeJob: Job? = null
    private var roleLease: GestureRoleCoordinator.Lease? = null

    @Suppress("ReturnCount") // Disabled setting, absent NFC, and role conflict are distinct no-op guards.
    fun enable() {
        if (!GestureTapToSharePreferences.from(activity).isEnabled()) return
        val nfc = adapter ?: return
        if (roleLease == null) roleLease = GestureRoleCoordinator.claim(GestureRoleCoordinator.Role.SEND)
        if (!GestureRoleCoordinator.owns(roleLease)) {
            diagnostic("reader_role_conflict")
            return
        }
        val flags =
            NfcAdapter.FLAG_READER_NFC_A or
                NfcAdapter.FLAG_READER_SKIP_NDEF_CHECK or
                NfcAdapter.FLAG_READER_NO_PLATFORM_SOUNDS
        val annotationValidated =
            activity
                .getSharedPreferences(API37_PREFERENCES, Activity.MODE_PRIVATE)
                .getBoolean(API37_ANNOTATION_VALIDATED, false)
        val extras: Bundle? =
            if (Build.VERSION.SDK_INT >= API_37) {
                Api37NfcSessionAdapter.readerExtras(nfc, annotationValidated)
            } else {
                null
            }
        runCatching { nfc.enableReaderMode(activity, ::onTag, flags, extras) }
            .onSuccess {
                acceptingTag.set(true)
                diagnostic("reader_enabled")
            }.onFailure {
                diagnostic("reader_enable_failed type=${it::class.java.simpleName}")
                GestureRoleCoordinator.release(roleLease)
                roleLease = null
            }
    }

    fun disable() {
        acceptingTag.set(false)
        generation.incrementAndGet()
        exchangeJob?.cancel()
        exchangeJob = null
        adapter?.let { runCatching { it.disableReaderMode(activity) } }
        GestureRoleCoordinator.release(roleLease)
        roleLease = null
    }

    fun close() {
        disable()
        scope.cancel()
    }

    @Suppress("TooGenericExceptionCaught") // One NFC session maps all protocol/I/O failures to teardown.
    private fun onTag(tag: Tag) {
        if (!acceptingTag.compareAndSet(true, false)) return
        val session = generation.incrementAndGet()
        exchangeJob =
            scope.launch {
                val isoDep =
                    IsoDep.get(tag) ?: run {
                        diagnostic("tag_without_isodep")
                        acceptingTag.set(true)
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
                                adapter?.let { runCatching { it.disableReaderMode(activity) } }
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
                    if (generation.get() == session) acceptingTag.set(true)
                } finally {
                    runCatching { isoDep.close() }
                }
            }
    }

    @Suppress("ReturnCount", "CyclomaticComplexMethod") // One guard per untrusted handover field.
    private suspend fun connectPeer(
        provider: WifiDirectMediumProvider,
        info: ConnectivityInfo,
    ): Peer? {
        val matching = info.entriesList.filter { it.zeroPartyIdentifier == ZERO_PARTY }
        if (matching.size != 1) return null
        val entry = matching.single()
        if (!entry.hasWifiDirect() || !ENDPOINT_ID.matches(entry.endpointId) || entry.serviceId.isBlank()) return null
        val endpointInfo = EndpointInfo.parse(entry.endpointInfo.toByteArray()) ?: return null
        val wire = entry.wifiDirect
        if (entry.endpointInfo.size() > MAX_ENDPOINT_INFO_BYTES || wire.port !in 1..65535) return null
        if (wire.ssid.isBlank() || wire.ssid.length > MAX_SSID_LENGTH) return null
        if (wire.password.length !in MIN_PASSPHRASE_LENGTH..MAX_PASSPHRASE_LENGTH) return null
        if (wire.frequencyMhz != -1 && wire.frequencyMhz !in MIN_WIFI_FREQUENCY_MHZ..MAX_WIFI_FREQUENCY_MHZ) {
            return null
        }
        if (!info.bluetoothMac.isEmpty && info.bluetoothMac.size() != BLUETOOTH_MAC_BYTES) return null
        val groupOwner =
            wire.groupOwnerIpv4
                .takeUnless { it.isBlank() || it == UNSPECIFIED_IPV4 }
                ?: DEFAULT_GROUP_OWNER
        val credentials =
            UpgradePathCredentials.WifiDirect(
                ipAddress = InetAddress.getByName(groupOwner).address,
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
        private const val API37_PREFERENCES = "gesture_exchange_api37"
        private const val API37_ANNOTATION_VALIDATED = "polling_annotation_physically_validated"
        private const val API_37 = 37
        private const val NFC_TIMEOUT_MS = 3_000
        private const val MAX_ENDPOINT_INFO_BYTES = 1_024
        private const val MAX_SSID_LENGTH = 32
        private const val MIN_PASSPHRASE_LENGTH = 8
        private const val MAX_PASSPHRASE_LENGTH = 63
        private const val MIN_WIFI_FREQUENCY_MHZ = 2_400
        private const val MAX_WIFI_FREQUENCY_MHZ = 7_125
        private const val BLUETOOTH_MAC_BYTES = 6
        private const val UNSPECIFIED_IPV4 = "0.0.0.0"
        private val ENDPOINT_ID = Regex("^[A-Za-z0-9]{4}$")
    }
}
