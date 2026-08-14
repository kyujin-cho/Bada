/*
 * Copyright 2026 Bada contributors.
 *
 * Licensed under the Apache License, Version 2.0.
 */
@file:Suppress("MissingPermission")

package dev.bluehouse.bada.gestureexchange

import android.app.KeyguardManager
import android.nfc.cardemulation.HostApduService
import android.os.Build
import android.os.Bundle
import dev.bluehouse.bada.discovery.medium.WifiDirectMediumProvider
import dev.bluehouse.bada.protocol.gestureexchange.GestureAid
import dev.bluehouse.bada.protocol.gestureexchange.GestureTagProtocolMachine
import dev.bluehouse.bada.protocol.gestureexchange.proto.ConnectivityInfo
import dev.bluehouse.bada.protocol.gestureexchange.proto.EndpointConnectivityEntry
import dev.bluehouse.bada.protocol.gestureexchange.proto.WifiDirectCredentials
import dev.bluehouse.bada.protocol.medium.UpgradePathCredentials
import dev.bluehouse.bada.service.receiver.GestureReceiverSessionBridge
import dev.bluehouse.bada.service.receiver.ReceiverForegroundService
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Unlocked HCE responder for Google-compatible “Tap to Share” file sessions on
 * `A00000047609`. It creates a real Wi-Fi Direct listener, returns its encrypted
 * credentials, then injects the accepted socket into SuperDrop's existing
 * receiver. Proprietary Name Card HCE/BLE remains a separate service.
 */
public class GestureExchangeHceService : HostApduService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var connectivityJob: Job? = null
    private var acceptJob: Job? = null

    @Volatile private var activeSession: HceSession? = null

    @Suppress("ReturnCount") // ISO-7816 guards return immediately with their exact status words.
    override fun processCommandApdu(
        apdu: ByteArray?,
        extras: Bundle?,
    ): ByteArray? {
        if (apdu == null) return GestureAid.WRONG_LENGTH.clone()
        if (!GestureTapToSharePreferences.from(this).isEnabled()) return GestureAid.FILE_NOT_FOUND.clone()
        if ((getSystemService(KEYGUARD_SERVICE) as KeyguardManager).isDeviceLocked) {
            diagnostic("hce_rejected_locked")
            return LOCKED.clone()
        }
        if (apdu.contentEquals(GestureAid.SELECT_PRIMARY_APDU)) {
            teardownActiveSession()
            activeSession =
                HceSession(
                    machine = GestureTagProtocolMachine(Build.MANUFACTURER),
                    provider = WifiDirectMediumProvider(applicationContext),
                )
            diagnostic("hce_selected")
        }
        val session = activeSession ?: return GestureAid.FAILURE.clone()
        return when (val result = synchronized(session.machine) { session.machine.handle(apdu) }) {
            is GestureTagProtocolMachine.Result.Respond -> {
                if (result.bytes.contentEquals(GestureAid.COMPLETED)) {
                    session.handoverCompleted = true
                    acceptPeerAsync(session)
                    diagnostic("hce_completed")
                }
                result.bytes
            }
            is GestureTagProtocolMachine.Result.NeedsConnectivity -> {
                diagnostic("hce_connectivity_requested services=${result.capability.serviceDataCount}")
                provideConnectivityAsync(session)
                null
            }
            is GestureTagProtocolMachine.Result.Failed -> {
                diagnostic("hce_failed reason=${result.reason.take(MAX_REASON_LENGTH)}")
                result.statusWord
            }
            GestureTagProtocolMachine.Result.Completed -> GestureAid.COMPLETED.clone()
        }
    }

    override fun onDeactivated(reason: Int) {
        val session = activeSession
        diagnostic("hce_deactivated reason=$reason completed=${session?.handoverCompleted == true}")
        if (session != null && !session.handoverCompleted && activeSession === session) {
            teardownActiveSession()
        }
    }

    override fun onDestroy() {
        teardownActiveSession()
        scope.cancel()
        super.onDestroy()
    }

    @Suppress("TooGenericExceptionCaught") // Maps platform/P2P failures to the protocol failure status.
    private fun provideConnectivityAsync(session: HceSession) {
        connectivityJob =
            scope.launch {
                val response =
                    try {
                        ReceiverForegroundService.start(this@GestureExchangeHceService)
                        GestureReceiverSessionBridge.awaitSession(RECEIVER_START_TIMEOUT_MS)
                            ?: error("receiver session start timed out")
                        check(activeSession === session) { "NFC session was superseded" }
                        val identity = GestureReceiverSessionBridge.identity() ?: error("receiver identity unavailable")
                        val credentials =
                            session.provider.prepareUpgrade() as? UpgradePathCredentials.WifiDirect
                                ?: error("Wi-Fi Direct group unavailable")
                        synchronized(session.machine) {
                            check(activeSession === session) { "NFC session was superseded" }
                            session.machine.provideConnectivity(buildConnectivity(identity, credentials))
                        }
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (error: Exception) {
                        session.provider.cancelPendingUpgrade()
                        diagnostic("hce_prepare_failed type=${error::class.java.simpleName}")
                        GestureTagProtocolMachine.Result.Failed(GestureAid.FAILURE.clone(), "connectivity unavailable")
                    }
                if (activeSession === session) {
                    when (response) {
                        is GestureTagProtocolMachine.Result.Respond -> sendResponseApdu(response.bytes)
                        is GestureTagProtocolMachine.Result.Failed -> sendResponseApdu(response.statusWord)
                        else -> sendResponseApdu(GestureAid.FAILURE.clone())
                    }
                }
            }
    }

    private fun buildConnectivity(
        identity: GestureReceiverSessionBridge.Identity,
        credentials: UpgradePathCredentials.WifiDirect,
    ): ConnectivityInfo {
        val wireCredentials =
            WifiDirectCredentials
                .newBuilder()
                .setSsid(credentials.ssid)
                .setPassword(credentials.passphrase)
                .setPort(credentials.port)
                .setFrequencyMhz(credentials.frequency)
        val entry =
            EndpointConnectivityEntry
                .newBuilder()
                .setWifiDirect(wireCredentials)
                .setServiceId(SERVICE_ID)
                .setEndpointId(identity.endpointId)
                .setEndpointInfo(
                    com.google.protobuf.ByteString
                        .copyFrom(identity.endpointInfo),
                ).setZeroPartyIdentifier(ZERO_PARTY)
        return ConnectivityInfo.newBuilder().addEntries(entry).build()
    }

    private fun acceptPeerAsync(session: HceSession) {
        acceptJob =
            scope.launch {
                if (activeSession !== session) return@launch
                val transport =
                    session.provider.consumePendingServerTransport() ?: run {
                        diagnostic("hce_accept_failed no_transport")
                        finishSession(session)
                        return@launch
                    }
                val accepted = GestureReceiverSessionBridge.accept(transport)
                diagnostic("hce_transport_handoff accepted=$accepted")
                finishSession(session)
            }
    }

    private fun finishSession(session: HceSession) {
        if (activeSession === session) {
            synchronized(session.machine) { session.machine.clear() }
            activeSession = null
        }
    }

    private fun teardownActiveSession() {
        val session = activeSession
        activeSession = null
        connectivityJob?.cancel()
        connectivityJob = null
        acceptJob?.cancel()
        acceptJob = null
        session?.provider?.cancelPendingUpgrade()
        session?.machine?.let { synchronized(it) { it.clear() } }
    }

    private fun diagnostic(event: String) {
        GestureVisualSignal.onProtocolEvent(event)
        GestureDiagnosticUpload.record(this, event)
    }

    private companion object {
        private val LOCKED = byteArrayOf(0x69, 0x82.toByte())
        private const val SERVICE_ID = "NearbySharing"
        private const val ZERO_PARTY = "nearby.sharing"
        private const val RECEIVER_START_TIMEOUT_MS = 8_000L
        private const val MAX_REASON_LENGTH = 80
    }

    private class HceSession(
        val machine: GestureTagProtocolMachine,
        val provider: WifiDirectMediumProvider,
        @Volatile var handoverCompleted: Boolean = false,
    )
}
