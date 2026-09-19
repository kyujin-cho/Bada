/*
 * Copyright 2026 Bada contributors.
 *
 * Licensed under the Apache License, Version 2.0.
 */
package dev.bluehouse.bada.service.receiver

import dev.bluehouse.bada.protocol.transport.ConnectedTransport
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Process-local bridge from the Google Tap to Share HCE bootstrap to the live
 * receiver service. It exposes no Name Card state and owns no transfer logic;
 * accepted transports immediately enter [ReceiverSession].
 */
public object GestureReceiverSessionBridge {
    public data class Identity(
        val endpointId: String,
        val endpointInfo: ByteArray,
    )

    private val activeSession: MutableStateFlow<ReceiverSession?> = MutableStateFlow(null)

    internal fun attach(session: ReceiverSession) {
        activeSession.value = session
    }

    internal fun detach(session: ReceiverSession) {
        activeSession.compareAndSet(session, null)
    }

    /** Await a bound receiver session after a cold HCE wake. */
    public suspend fun awaitSession(timeoutMillis: Long): ReceiverSession? =
        withTimeoutOrNull(timeoutMillis) { activeSession.filterNotNull().first() }

    /** Snapshot the identity used by the receiver service for this process. */
    @Suppress("ReturnCount") // Missing session and identity are separate expected cold-start states.
    public fun identity(): Identity? {
        if (activeSession.value == null) return null
        val endpointInfo = EndpointIdentityHolder.snapshot.get() ?: return null
        return Identity(
            endpointId = String(BleEndpointIdHolder.bytesFor(), Charsets.US_ASCII),
            endpointInfo = endpointInfo.serialize(),
        )
    }

    /** Route a connected Tap to Share transport into the ordinary receiver. */
    public fun accept(transport: ConnectedTransport): Boolean {
        val session =
            activeSession.value ?: run {
                transport.close()
                return false
            }
        return runCatching { session.acceptConnectedTransport(transport) }
            .onFailure { transport.close() }
            .isSuccess
    }
}
